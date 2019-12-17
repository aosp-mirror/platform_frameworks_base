/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "IncrementalService"

#include "IncrementalService.h"

#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/properties.h>
#include <android-base/stringprintf.h>
#include <android-base/strings.h>
#include <android/content/pm/IDataLoaderStatusListener.h>
#include <android/os/IVold.h>
#include <androidfw/ZipFileRO.h>
#include <androidfw/ZipUtils.h>
#include <binder/BinderService.h>
#include <binder/ParcelFileDescriptor.h>
#include <binder/Status.h>
#include <sys/stat.h>
#include <uuid/uuid.h>
#include <zlib.h>

#include <iterator>
#include <span>
#include <stack>
#include <thread>
#include <type_traits>

#include "Metadata.pb.h"

using namespace std::literals;
using namespace android::content::pm;

namespace android::incremental {

namespace {

using IncrementalFileSystemControlParcel =
        ::android::os::incremental::IncrementalFileSystemControlParcel;

struct Constants {
    static constexpr auto backing = "backing_store"sv;
    static constexpr auto mount = "mount"sv;
    static constexpr auto image = "incfs.img"sv;
    static constexpr auto storagePrefix = "st"sv;
    static constexpr auto mountpointMdPrefix = ".mountpoint."sv;
    static constexpr auto infoMdName = ".info"sv;
};

static const Constants& constants() {
    static Constants c;
    return c;
}

template <base::LogSeverity level = base::ERROR>
bool mkdirOrLog(std::string_view name, int mode = 0770, bool allowExisting = true) {
    auto cstr = path::c_str(name);
    if (::mkdir(cstr, mode)) {
        if (errno != EEXIST) {
            PLOG(level) << "Can't create directory '" << name << '\'';
            return false;
        }
        struct stat st;
        if (::stat(cstr, &st) || !S_ISDIR(st.st_mode)) {
            PLOG(level) << "Path exists but is not a directory: '" << name << '\'';
            return false;
        }
    }
    return true;
}

static std::string toMountKey(std::string_view path) {
    if (path.empty()) {
        return "@none";
    }
    if (path == "/"sv) {
        return "@root";
    }
    if (path::isAbsolute(path)) {
        path.remove_prefix(1);
    }
    std::string res(path);
    std::replace(res.begin(), res.end(), '/', '_');
    std::replace(res.begin(), res.end(), '@', '_');
    return res;
}

static std::pair<std::string, std::string> makeMountDir(std::string_view incrementalDir,
                                                        std::string_view path) {
    auto mountKey = toMountKey(path);
    const auto prefixSize = mountKey.size();
    for (int counter = 0; counter < 1000;
         mountKey.resize(prefixSize), base::StringAppendF(&mountKey, "%d", counter++)) {
        auto mountRoot = path::join(incrementalDir, mountKey);
        if (mkdirOrLog(mountRoot, 0770, false)) {
            return {mountKey, mountRoot};
        }
    }
    return {};
}

template <class ProtoMessage, class Control>
static ProtoMessage parseFromIncfs(const IncFsWrapper* incfs, Control&& control,
                                   std::string_view path) {
    struct stat st;
    if (::stat(path::c_str(path), &st)) {
        return {};
    }
    auto md = incfs->getMetadata(control, st.st_ino);
    ProtoMessage message;
    return message.ParseFromArray(md.data(), md.size()) ? message : ProtoMessage{};
}

static bool isValidMountTarget(std::string_view path) {
    return path::isAbsolute(path) && path::isEmptyDir(path).value_or(true);
}

std::string makeBindMdName() {
    static constexpr auto uuidStringSize = 36;

    uuid_t guid;
    uuid_generate(guid);

    std::string name;
    const auto prefixSize = constants().mountpointMdPrefix.size();
    name.reserve(prefixSize + uuidStringSize);

    name = constants().mountpointMdPrefix;
    name.resize(prefixSize + uuidStringSize);
    uuid_unparse(guid, name.data() + prefixSize);

    return name;
}
} // namespace

IncrementalService::IncFsMount::~IncFsMount() {
    incrementalService.mIncrementalManager->destroyDataLoader(mountId);
    control.reset();
    LOG(INFO) << "Unmounting and cleaning up mount " << mountId << " with root '" << root << '\'';
    for (auto&& [target, _] : bindPoints) {
        LOG(INFO) << "\tbind: " << target;
        incrementalService.mVold->unmountIncFs(target);
    }
    LOG(INFO) << "\troot: " << root;
    incrementalService.mVold->unmountIncFs(path::join(root, constants().mount));
    cleanupFilesystem(root);
}

auto IncrementalService::IncFsMount::makeStorage(StorageId id) -> StorageMap::iterator {
    metadata::Storage st;
    st.set_id(id);
    auto metadata = st.SerializeAsString();

    std::string name;
    for (int no = nextStorageDirNo.fetch_add(1, std::memory_order_relaxed), i = 0;
         i < 1024 && no >= 0; no = nextStorageDirNo.fetch_add(1, std::memory_order_relaxed), ++i) {
        name.clear();
        base::StringAppendF(&name, "%.*s%d", int(constants().storagePrefix.size()),
                            constants().storagePrefix.data(), no);
        if (auto node =
                    incrementalService.mIncFs->makeDir(control, name, INCFS_ROOT_INODE, metadata);
            node >= 0) {
            std::lock_guard l(lock);
            return storages.insert_or_assign(id, Storage{std::move(name), node}).first;
        }
    }
    nextStorageDirNo = 0;
    return storages.end();
}

void IncrementalService::IncFsMount::cleanupFilesystem(std::string_view root) {
    ::unlink(path::join(root, constants().backing, constants().image).c_str());
    ::rmdir(path::join(root, constants().backing).c_str());
    ::rmdir(path::join(root, constants().mount).c_str());
    ::rmdir(path::c_str(root));
}

IncrementalService::IncrementalService(const ServiceManagerWrapper& sm, std::string_view rootDir)
      : mVold(sm.getVoldService()),
        mIncrementalManager(sm.getIncrementalManager()),
        mIncFs(sm.getIncFs()),
        mIncrementalDir(rootDir) {
    if (!mVold) {
        LOG(FATAL) << "Vold service is unavailable";
    }
    if (!mIncrementalManager) {
        LOG(FATAL) << "IncrementalManager service is unavailable";
    }
    // TODO(b/136132412): check that root dir should already exist
    // TODO(b/136132412): enable mount existing dirs after SELinux rules are merged
    // mountExistingImages();
}

IncrementalService::~IncrementalService() = default;

std::optional<std::future<void>> IncrementalService::onSystemReady() {
    std::promise<void> threadFinished;
    if (mSystemReady.exchange(true)) {
        return {};
    }

    std::vector<IfsMountPtr> mounts;
    {
        std::lock_guard l(mLock);
        mounts.reserve(mMounts.size());
        for (auto&& [id, ifs] : mMounts) {
            if (ifs->mountId == id) {
                mounts.push_back(ifs);
            }
        }
    }

    std::thread([this, mounts = std::move(mounts)]() {
        std::vector<IfsMountPtr> failedLoaderMounts;
        for (auto&& ifs : mounts) {
            if (prepareDataLoader(*ifs, nullptr)) {
                LOG(INFO) << "Successfully started data loader for mount " << ifs->mountId;
            } else {
                LOG(WARNING) << "Failed to start data loader for mount " << ifs->mountId;
                failedLoaderMounts.push_back(std::move(ifs));
            }
        }

        while (!failedLoaderMounts.empty()) {
            LOG(WARNING) << "Deleting failed mount " << failedLoaderMounts.back()->mountId;
            deleteStorage(*failedLoaderMounts.back());
            failedLoaderMounts.pop_back();
        }
        mPrepareDataLoaders.set_value_at_thread_exit();
    }).detach();
    return mPrepareDataLoaders.get_future();
}

auto IncrementalService::getStorageSlotLocked() -> MountMap::iterator {
    for (;;) {
        if (mNextId == kMaxStorageId) {
            mNextId = 0;
        }
        auto id = ++mNextId;
        auto [it, inserted] = mMounts.try_emplace(id, nullptr);
        if (inserted) {
            return it;
        }
    }
}

StorageId IncrementalService::createStorage(std::string_view mountPoint,
                                            DataLoaderParamsParcel&& dataLoaderParams,
                                            CreateOptions options) {
    LOG(INFO) << "createStorage: " << mountPoint << " | " << int(options);
    if (!path::isAbsolute(mountPoint)) {
        LOG(ERROR) << "path is not absolute: " << mountPoint;
        return kInvalidStorageId;
    }

    auto mountNorm = path::normalize(mountPoint);
    {
        const auto id = findStorageId(mountNorm);
        if (id != kInvalidStorageId) {
            if (options & CreateOptions::OpenExisting) {
                LOG(INFO) << "Opened existing storage " << id;
                return id;
            }
            LOG(ERROR) << "Directory " << mountPoint << " is already mounted at storage " << id;
            return kInvalidStorageId;
        }
    }

    if (!(options & CreateOptions::CreateNew)) {
        LOG(ERROR) << "not requirested create new storage, and it doesn't exist: " << mountPoint;
        return kInvalidStorageId;
    }

    if (!path::isEmptyDir(mountNorm)) {
        LOG(ERROR) << "Mounting over existing non-empty directory is not supported: " << mountNorm;
        return kInvalidStorageId;
    }
    auto [mountKey, mountRoot] = makeMountDir(mIncrementalDir, mountNorm);
    if (mountRoot.empty()) {
        LOG(ERROR) << "Bad mount point";
        return kInvalidStorageId;
    }
    // Make sure the code removes all crap it may create while still failing.
    auto firstCleanup = [](const std::string* ptr) { IncFsMount::cleanupFilesystem(*ptr); };
    auto firstCleanupOnFailure =
            std::unique_ptr<std::string, decltype(firstCleanup)>(&mountRoot, firstCleanup);

    auto mountTarget = path::join(mountRoot, constants().mount);
    if (!mkdirOrLog(path::join(mountRoot, constants().backing)) || !mkdirOrLog(mountTarget)) {
        return kInvalidStorageId;
    }

    const auto image = path::join(mountRoot, constants().backing, constants().image);
    IncFsMount::Control control;
    {
        std::lock_guard l(mMountOperationLock);
        IncrementalFileSystemControlParcel controlParcel;
        auto status = mVold->mountIncFs(image, mountTarget, incfs::truncate, &controlParcel);
        if (!status.isOk()) {
            LOG(ERROR) << "Vold::mountIncFs() failed: " << status.toString8();
            return kInvalidStorageId;
        }
        if (!controlParcel.cmd || !controlParcel.log) {
            LOG(ERROR) << "Vold::mountIncFs() returned invalid control parcel.";
            return kInvalidStorageId;
        }
        control.cmdFd = controlParcel.cmd->release();
        control.logFd = controlParcel.log->release();
    }

    std::unique_lock l(mLock);
    const auto mountIt = getStorageSlotLocked();
    const auto mountId = mountIt->first;
    l.unlock();

    auto ifs =
            std::make_shared<IncFsMount>(std::move(mountRoot), mountId, std::move(control), *this);
    // Now it's the |ifs|'s responsibility to clean up after itself, and the only cleanup we need
    // is the removal of the |ifs|.
    firstCleanupOnFailure.release();

    auto secondCleanup = [this, &l](auto itPtr) {
        if (!l.owns_lock()) {
            l.lock();
        }
        mMounts.erase(*itPtr);
    };
    auto secondCleanupOnFailure =
            std::unique_ptr<decltype(mountIt), decltype(secondCleanup)>(&mountIt, secondCleanup);

    const auto storageIt = ifs->makeStorage(ifs->mountId);
    if (storageIt == ifs->storages.end()) {
        LOG(ERROR) << "Can't create default storage directory";
        return kInvalidStorageId;
    }

    {
        metadata::Mount m;
        m.mutable_storage()->set_id(ifs->mountId);
        m.mutable_loader()->set_type((int)dataLoaderParams.type);
        m.mutable_loader()->set_package_name(dataLoaderParams.packageName);
        m.mutable_loader()->set_class_name(dataLoaderParams.className);
        m.mutable_loader()->set_arguments(dataLoaderParams.arguments);
        const auto metadata = m.SerializeAsString();
        m.mutable_loader()->release_arguments();
        m.mutable_loader()->release_class_name();
        m.mutable_loader()->release_package_name();
        if (auto err = mIncFs->makeFile(ifs->control, constants().infoMdName, INCFS_ROOT_INODE, 0,
                                        metadata);
            err < 0) {
            LOG(ERROR) << "Saving mount metadata failed: " << -err;
            return kInvalidStorageId;
        }
    }

    const auto bk =
            (options & CreateOptions::PermanentBind) ? BindKind::Permanent : BindKind::Temporary;
    if (auto err = addBindMount(*ifs, storageIt->first, std::string(storageIt->second.name),
                                std::move(mountNorm), bk, l);
        err < 0) {
        LOG(ERROR) << "adding bind mount failed: " << -err;
        return kInvalidStorageId;
    }

    // Done here as well, all data structures are in good state.
    secondCleanupOnFailure.release();

    if (!prepareDataLoader(*ifs, &dataLoaderParams)) {
        LOG(ERROR) << "prepareDataLoader() failed";
        deleteStorageLocked(*ifs, std::move(l));
        return kInvalidStorageId;
    }

    mountIt->second = std::move(ifs);
    l.unlock();
    LOG(INFO) << "created storage " << mountId;
    return mountId;
}

StorageId IncrementalService::createLinkedStorage(std::string_view mountPoint,
                                                  StorageId linkedStorage,
                                                  IncrementalService::CreateOptions options) {
    if (!isValidMountTarget(mountPoint)) {
        LOG(ERROR) << "Mount point is invalid or missing";
        return kInvalidStorageId;
    }

    std::unique_lock l(mLock);
    const auto& ifs = getIfsLocked(linkedStorage);
    if (!ifs) {
        LOG(ERROR) << "Ifs unavailable";
        return kInvalidStorageId;
    }

    const auto mountIt = getStorageSlotLocked();
    const auto storageId = mountIt->first;
    const auto storageIt = ifs->makeStorage(storageId);
    if (storageIt == ifs->storages.end()) {
        LOG(ERROR) << "Can't create a new storage";
        mMounts.erase(mountIt);
        return kInvalidStorageId;
    }

    l.unlock();

    const auto bk =
            (options & CreateOptions::PermanentBind) ? BindKind::Permanent : BindKind::Temporary;
    if (auto err = addBindMount(*ifs, storageIt->first, std::string(storageIt->second.name),
                                path::normalize(mountPoint), bk, l);
        err < 0) {
        LOG(ERROR) << "bindMount failed with error: " << err;
        return kInvalidStorageId;
    }

    mountIt->second = ifs;
    return storageId;
}

IncrementalService::BindPathMap::const_iterator IncrementalService::findStorageLocked(
        std::string_view path) const {
    auto bindPointIt = mBindsByPath.upper_bound(path);
    if (bindPointIt == mBindsByPath.begin()) {
        return mBindsByPath.end();
    }
    --bindPointIt;
    if (!path::startsWith(path, bindPointIt->first)) {
        return mBindsByPath.end();
    }
    return bindPointIt;
}

StorageId IncrementalService::findStorageId(std::string_view path) const {
    std::lock_guard l(mLock);
    auto it = findStorageLocked(path);
    if (it == mBindsByPath.end()) {
        return kInvalidStorageId;
    }
    return it->second->second.storage;
}

void IncrementalService::deleteStorage(StorageId storageId) {
    const auto ifs = getIfs(storageId);
    if (!ifs) {
        return;
    }
    deleteStorage(*ifs);
}

void IncrementalService::deleteStorage(IncrementalService::IncFsMount& ifs) {
    std::unique_lock l(ifs.lock);
    deleteStorageLocked(ifs, std::move(l));
}

void IncrementalService::deleteStorageLocked(IncrementalService::IncFsMount& ifs,
                                             std::unique_lock<std::mutex>&& ifsLock) {
    const auto storages = std::move(ifs.storages);
    // Don't move the bind points out: Ifs's dtor will use them to unmount everything.
    const auto bindPoints = ifs.bindPoints;
    ifsLock.unlock();

    std::lock_guard l(mLock);
    for (auto&& [id, _] : storages) {
        if (id != ifs.mountId) {
            mMounts.erase(id);
        }
    }
    for (auto&& [path, _] : bindPoints) {
        mBindsByPath.erase(path);
    }
    mMounts.erase(ifs.mountId);
}

StorageId IncrementalService::openStorage(std::string_view pathInMount) {
    if (!path::isAbsolute(pathInMount)) {
        return kInvalidStorageId;
    }

    return findStorageId(path::normalize(pathInMount));
}

Inode IncrementalService::nodeFor(StorageId storage, std::string_view subpath) const {
    const auto ifs = getIfs(storage);
    if (!ifs) {
        return -1;
    }
    std::unique_lock l(ifs->lock);
    auto storageIt = ifs->storages.find(storage);
    if (storageIt == ifs->storages.end()) {
        return -1;
    }
    if (subpath.empty() || subpath == "."sv) {
        return storageIt->second.node;
    }
    auto path = path::join(ifs->root, constants().mount, storageIt->second.name, subpath);
    l.unlock();
    struct stat st;
    if (::stat(path.c_str(), &st)) {
        return -1;
    }
    return st.st_ino;
}

std::pair<Inode, std::string_view> IncrementalService::parentAndNameFor(
        StorageId storage, std::string_view subpath) const {
    auto name = path::basename(subpath);
    if (name.empty()) {
        return {-1, {}};
    }
    auto dir = path::dirname(subpath);
    if (dir.empty() || dir == "/"sv) {
        return {-1, {}};
    }
    auto inode = nodeFor(storage, dir);
    return {inode, name};
}

IncrementalService::IfsMountPtr IncrementalService::getIfs(StorageId storage) const {
    std::lock_guard l(mLock);
    return getIfsLocked(storage);
}

const IncrementalService::IfsMountPtr& IncrementalService::getIfsLocked(StorageId storage) const {
    auto it = mMounts.find(storage);
    if (it == mMounts.end()) {
        static const IfsMountPtr kEmpty = {};
        return kEmpty;
    }
    return it->second;
}

int IncrementalService::bind(StorageId storage, std::string_view sourceSubdir,
                             std::string_view target, BindKind kind) {
    if (!isValidMountTarget(target)) {
        return -EINVAL;
    }

    const auto ifs = getIfs(storage);
    if (!ifs) {
        return -EINVAL;
    }
    std::unique_lock l(ifs->lock);
    const auto storageInfo = ifs->storages.find(storage);
    if (storageInfo == ifs->storages.end()) {
        return -EINVAL;
    }
    auto source = path::join(storageInfo->second.name, sourceSubdir);
    l.unlock();
    std::unique_lock l2(mLock, std::defer_lock);
    return addBindMount(*ifs, storage, std::move(source), path::normalize(target), kind, l2);
}

int IncrementalService::unbind(StorageId storage, std::string_view target) {
    if (!path::isAbsolute(target)) {
        return -EINVAL;
    }

    LOG(INFO) << "Removing bind point " << target;

    // Here we should only look up by the exact target, not by a subdirectory of any existing mount,
    // otherwise there's a chance to unmount something completely unrelated
    const auto norm = path::normalize(target);
    std::unique_lock l(mLock);
    const auto storageIt = mBindsByPath.find(norm);
    if (storageIt == mBindsByPath.end() || storageIt->second->second.storage != storage) {
        return -EINVAL;
    }
    const auto bindIt = storageIt->second;
    const auto storageId = bindIt->second.storage;
    const auto ifs = getIfsLocked(storageId);
    if (!ifs) {
        LOG(ERROR) << "Internal error: storageId " << storageId << " for bound path " << target
                   << " is missing";
        return -EFAULT;
    }
    mBindsByPath.erase(storageIt);
    l.unlock();

    mVold->unmountIncFs(bindIt->first);
    std::unique_lock l2(ifs->lock);
    if (ifs->bindPoints.size() <= 1) {
        ifs->bindPoints.clear();
        deleteStorageLocked(*ifs, std::move(l2));
    } else {
        const std::string savedFile = std::move(bindIt->second.savedFilename);
        ifs->bindPoints.erase(bindIt);
        l2.unlock();
        if (!savedFile.empty()) {
            mIncFs->unlink(ifs->control, INCFS_ROOT_INODE, savedFile);
        }
    }
    return 0;
}

Inode IncrementalService::makeFile(StorageId storageId, std::string_view pathUnderStorage,
                                   long size, std::string_view metadata,
                                   std::string_view signature) {
    (void)signature;
    auto [parentInode, name] = parentAndNameFor(storageId, pathUnderStorage);
    if (parentInode < 0) {
        return -EINVAL;
    }
    if (auto ifs = getIfs(storageId)) {
        auto inode = mIncFs->makeFile(ifs->control, name, parentInode, size, metadata);
        if (inode < 0) {
            return inode;
        }
        auto metadataBytes = std::vector<uint8_t>();
        if (metadata.data() != nullptr && metadata.size() > 0) {
            metadataBytes.insert(metadataBytes.end(), &metadata.data()[0],
                                 &metadata.data()[metadata.size()]);
        }
        mIncrementalManager->newFileForDataLoader(ifs->mountId, inode, metadataBytes);
        return inode;
    }
    return -EINVAL;
}

Inode IncrementalService::makeDir(StorageId storageId, std::string_view pathUnderStorage,
                                  std::string_view metadata) {
    auto [parentInode, name] = parentAndNameFor(storageId, pathUnderStorage);
    if (parentInode < 0) {
        return -EINVAL;
    }
    if (auto ifs = getIfs(storageId)) {
        return mIncFs->makeDir(ifs->control, name, parentInode, metadata);
    }
    return -EINVAL;
}

Inode IncrementalService::makeDirs(StorageId storageId, std::string_view pathUnderStorage,
                                   std::string_view metadata) {
    const auto ifs = getIfs(storageId);
    if (!ifs) {
        return -EINVAL;
    }
    std::string_view parentDir(pathUnderStorage);
    auto p = parentAndNameFor(storageId, pathUnderStorage);
    std::stack<std::string> pathsToCreate;
    while (p.first < 0) {
        parentDir = path::dirname(parentDir);
        pathsToCreate.emplace(parentDir);
        p = parentAndNameFor(storageId, parentDir);
    }
    Inode inode;
    while (!pathsToCreate.empty()) {
        p = parentAndNameFor(storageId, pathsToCreate.top());
        pathsToCreate.pop();
        inode = mIncFs->makeDir(ifs->control, p.second, p.first, metadata);
        if (inode < 0) {
            return inode;
        }
    }
    return mIncFs->makeDir(ifs->control, path::basename(pathUnderStorage), inode, metadata);
}

int IncrementalService::link(StorageId storage, Inode item, Inode newParent,
                             std::string_view newName) {
    if (auto ifs = getIfs(storage)) {
        return mIncFs->link(ifs->control, item, newParent, newName);
    }
    return -EINVAL;
}

int IncrementalService::unlink(StorageId storage, Inode parent, std::string_view name) {
    if (auto ifs = getIfs(storage)) {
        return mIncFs->unlink(ifs->control, parent, name);
    }
    return -EINVAL;
}

int IncrementalService::addBindMount(IncFsMount& ifs, StorageId storage, std::string&& sourceSubdir,
                                     std::string&& target, BindKind kind,
                                     std::unique_lock<std::mutex>& mainLock) {
    if (!isValidMountTarget(target)) {
        return -EINVAL;
    }

    std::string mdFileName;
    if (kind != BindKind::Temporary) {
        metadata::BindPoint bp;
        bp.set_storage_id(storage);
        bp.set_allocated_dest_path(&target);
        bp.set_allocated_source_subdir(&sourceSubdir);
        const auto metadata = bp.SerializeAsString();
        bp.release_source_subdir();
        bp.release_dest_path();
        mdFileName = makeBindMdName();
        auto node = mIncFs->makeFile(ifs.control, mdFileName, INCFS_ROOT_INODE, 0, metadata);
        if (node < 0) {
            return int(node);
        }
    }

    return addBindMountWithMd(ifs, storage, std::move(mdFileName), std::move(sourceSubdir),
                              std::move(target), kind, mainLock);
}

int IncrementalService::addBindMountWithMd(IncrementalService::IncFsMount& ifs, StorageId storage,
                                           std::string&& metadataName, std::string&& sourceSubdir,
                                           std::string&& target, BindKind kind,
                                           std::unique_lock<std::mutex>& mainLock) {
    LOG(INFO) << "Adding bind mount: " << sourceSubdir << " -> " << target;
    {
        auto path = path::join(ifs.root, constants().mount, sourceSubdir);
        std::lock_guard l(mMountOperationLock);
        const auto status = mVold->bindMount(path, target);
        if (!status.isOk()) {
            LOG(ERROR) << "Calling Vold::bindMount() failed: " << status.toString8();
            return status.exceptionCode() == binder::Status::EX_SERVICE_SPECIFIC
                    ? status.serviceSpecificErrorCode() > 0 ? -status.serviceSpecificErrorCode()
                                                            : status.serviceSpecificErrorCode() == 0
                                    ? -EFAULT
                                    : status.serviceSpecificErrorCode()
                    : -EIO;
        }
    }

    if (!mainLock.owns_lock()) {
        mainLock.lock();
    }
    std::lock_guard l(ifs.lock);
    const auto [it, _] =
            ifs.bindPoints.insert_or_assign(target,
                                            IncFsMount::Bind{storage, std::move(metadataName),
                                                             std::move(sourceSubdir), kind});
    mBindsByPath[std::move(target)] = it;
    return 0;
}

RawMetadata IncrementalService::getMetadata(StorageId storage, Inode node) const {
    const auto ifs = getIfs(storage);
    if (!ifs) {
        return {};
    }
    return mIncFs->getMetadata(ifs->control, node);
}

std::vector<std::string> IncrementalService::listFiles(StorageId storage) const {
    const auto ifs = getIfs(storage);
    if (!ifs) {
        return {};
    }

    std::unique_lock l(ifs->lock);
    auto subdirIt = ifs->storages.find(storage);
    if (subdirIt == ifs->storages.end()) {
        return {};
    }
    auto dir = path::join(ifs->root, constants().mount, subdirIt->second.name);
    l.unlock();

    const auto prefixSize = dir.size() + 1;
    std::vector<std::string> todoDirs{std::move(dir)};
    std::vector<std::string> result;
    do {
        auto currDir = std::move(todoDirs.back());
        todoDirs.pop_back();

        auto d =
                std::unique_ptr<DIR, decltype(&::closedir)>(::opendir(currDir.c_str()), ::closedir);
        while (auto e = ::readdir(d.get())) {
            if (e->d_type == DT_REG) {
                result.emplace_back(
                        path::join(std::string_view(currDir).substr(prefixSize), e->d_name));
                continue;
            }
            if (e->d_type == DT_DIR) {
                if (e->d_name == "."sv || e->d_name == ".."sv) {
                    continue;
                }
                todoDirs.emplace_back(path::join(currDir, e->d_name));
                continue;
            }
        }
    } while (!todoDirs.empty());
    return result;
}

bool IncrementalService::startLoading(StorageId storage) const {
    const auto ifs = getIfs(storage);
    if (!ifs) {
        return false;
    }
    bool started = false;
    std::unique_lock l(ifs->lock);
    if (ifs->dataLoaderStatus != IDataLoaderStatusListener::DATA_LOADER_CREATED) {
        if (ifs->dataLoaderReady.wait_for(l, Seconds(5)) == std::cv_status::timeout) {
            LOG(ERROR) << "Timeout waiting for data loader to be ready";
            return false;
        }
    }
    auto status = mIncrementalManager->startDataLoader(ifs->mountId, &started);
    if (!status.isOk()) {
        return false;
    }
    return started;
}

void IncrementalService::mountExistingImages() {
    auto d = std::unique_ptr<DIR, decltype(&::closedir)>(::opendir(mIncrementalDir.c_str()),
                                                         ::closedir);
    while (auto e = ::readdir(d.get())) {
        if (e->d_type != DT_DIR) {
            continue;
        }
        if (e->d_name == "."sv || e->d_name == ".."sv) {
            continue;
        }
        auto root = path::join(mIncrementalDir, e->d_name);
        if (!mountExistingImage(root, e->d_name)) {
            IncFsMount::cleanupFilesystem(root);
        }
    }
}

bool IncrementalService::mountExistingImage(std::string_view root, std::string_view key) {
    LOG(INFO) << "Trying to mount: " << key;

    auto mountTarget = path::join(root, constants().mount);
    const auto image = path::join(root, constants().backing, constants().image);

    IncFsMount::Control control;
    IncrementalFileSystemControlParcel controlParcel;
    auto status = mVold->mountIncFs(image, mountTarget, 0, &controlParcel);
    if (!status.isOk()) {
        LOG(ERROR) << "Vold::mountIncFs() failed: " << status.toString8();
        return false;
    }
    if (controlParcel.cmd) {
        control.cmdFd = controlParcel.cmd->release();
    }
    if (controlParcel.log) {
        control.logFd = controlParcel.log->release();
    }

    auto ifs = std::make_shared<IncFsMount>(std::string(root), -1, std::move(control), *this);

    auto m = parseFromIncfs<metadata::Mount>(mIncFs.get(), ifs->control,
                                             path::join(mountTarget, constants().infoMdName));
    if (!m.has_loader() || !m.has_storage()) {
        LOG(ERROR) << "Bad mount metadata in mount at " << root;
        return false;
    }

    ifs->mountId = m.storage().id();
    mNextId = std::max(mNextId, ifs->mountId + 1);

    std::vector<std::pair<std::string, metadata::BindPoint>> bindPoints;
    auto d = std::unique_ptr<DIR, decltype(&::closedir)>(::opendir(path::c_str(mountTarget)),
                                                         ::closedir);
    while (auto e = ::readdir(d.get())) {
        if (e->d_type == DT_REG) {
            auto name = std::string_view(e->d_name);
            if (name.starts_with(constants().mountpointMdPrefix)) {
                bindPoints.emplace_back(name,
                                        parseFromIncfs<metadata::BindPoint>(mIncFs.get(),
                                                                            ifs->control,
                                                                            path::join(mountTarget,
                                                                                       name)));
                if (bindPoints.back().second.dest_path().empty() ||
                    bindPoints.back().second.source_subdir().empty()) {
                    bindPoints.pop_back();
                    mIncFs->unlink(ifs->control, INCFS_ROOT_INODE, name);
                }
            }
        } else if (e->d_type == DT_DIR) {
            if (e->d_name == "."sv || e->d_name == ".."sv) {
                continue;
            }
            auto name = std::string_view(e->d_name);
            if (name.starts_with(constants().storagePrefix)) {
                auto md = parseFromIncfs<metadata::Storage>(mIncFs.get(), ifs->control,
                                                            path::join(mountTarget, name));
                auto [_, inserted] = mMounts.try_emplace(md.id(), ifs);
                if (!inserted) {
                    LOG(WARNING) << "Ignoring storage with duplicate id " << md.id()
                                 << " for mount " << root;
                    continue;
                }
                ifs->storages.insert_or_assign(md.id(),
                                               IncFsMount::Storage{std::string(name),
                                                                   Inode(e->d_ino)});
                mNextId = std::max(mNextId, md.id() + 1);
            }
        }
    }

    if (ifs->storages.empty()) {
        LOG(WARNING) << "No valid storages in mount " << root;
        return false;
    }

    int bindCount = 0;
    for (auto&& bp : bindPoints) {
        std::unique_lock l(mLock, std::defer_lock);
        bindCount += !addBindMountWithMd(*ifs, bp.second.storage_id(), std::move(bp.first),
                                         std::move(*bp.second.mutable_source_subdir()),
                                         std::move(*bp.second.mutable_dest_path()),
                                         BindKind::Permanent, l);
    }

    if (bindCount == 0) {
        LOG(WARNING) << "No valid bind points for mount " << root;
        deleteStorage(*ifs);
        return false;
    }

    DataLoaderParamsParcel dlParams;
    dlParams.type = (DataLoaderType)m.loader().type();
    dlParams.packageName = std::move(*m.mutable_loader()->mutable_package_name());
    dlParams.className = std::move(*m.mutable_loader()->mutable_class_name());
    dlParams.arguments = std::move(*m.mutable_loader()->mutable_arguments());
    if (!prepareDataLoader(*ifs, &dlParams)) {
        deleteStorage(*ifs);
        return false;
    }

    mMounts[ifs->mountId] = std::move(ifs);
    return true;
}

bool IncrementalService::prepareDataLoader(IncrementalService::IncFsMount& ifs,
                                           DataLoaderParamsParcel* params) {
    if (!mSystemReady.load(std::memory_order_relaxed)) {
        std::unique_lock l(ifs.lock);
        if (params) {
            if (ifs.savedDataLoaderParams) {
                LOG(WARNING) << "Trying to pass second set of data loader parameters, ignored it";
            } else {
                ifs.savedDataLoaderParams = std::move(*params);
            }
        } else {
            if (!ifs.savedDataLoaderParams) {
                LOG(ERROR) << "Mount " << ifs.mountId
                           << " is broken: no data loader params (system is not ready yet)";
                return false;
            }
        }
        return true; // eventually...
    }
    if (base::GetBoolProperty("incremental.skip_loader", false)) {
        LOG(INFO) << "Skipped data loader because of incremental.skip_loader property";
        std::unique_lock l(ifs.lock);
        ifs.savedDataLoaderParams.reset();
        return true;
    }

    std::unique_lock l(ifs.lock);
    if (ifs.dataLoaderStatus == IDataLoaderStatusListener::DATA_LOADER_CREATED) {
        LOG(INFO) << "Skipped data loader preparation because it already exists";
        return true;
    }

    auto* dlp = params ? params
                       : ifs.savedDataLoaderParams ? &ifs.savedDataLoaderParams.value() : nullptr;
    if (!dlp) {
        LOG(ERROR) << "Mount " << ifs.mountId << " is broken: no data loader params";
        return false;
    }
    FileSystemControlParcel fsControlParcel;
    fsControlParcel.incremental = std::make_unique<IncrementalFileSystemControlParcel>();
    fsControlParcel.incremental->cmd =
            std::make_unique<os::ParcelFileDescriptor>(base::unique_fd(::dup(ifs.control.cmdFd)));
    fsControlParcel.incremental->log =
            std::make_unique<os::ParcelFileDescriptor>(base::unique_fd(::dup(ifs.control.logFd)));
    sp<IncrementalDataLoaderListener> listener = new IncrementalDataLoaderListener(*this);
    bool created = false;
    auto status = mIncrementalManager->prepareDataLoader(ifs.mountId, fsControlParcel, *dlp,
                                                         listener, &created);
    if (!status.isOk() || !created) {
        LOG(ERROR) << "Failed to create a data loader for mount " << ifs.mountId;
        return false;
    }
    ifs.savedDataLoaderParams.reset();
    return true;
}

binder::Status IncrementalService::IncrementalDataLoaderListener::onStatusChanged(MountId mountId,
                                                                                  int newStatus) {
    std::unique_lock l(incrementalService.mLock);
    const auto& ifs = incrementalService.getIfsLocked(mountId);
    if (!ifs) {
        LOG(WARNING) << "Received data loader status " << int(newStatus) << " for unknown mount "
                     << mountId;
        return binder::Status::ok();
    }
    ifs->dataLoaderStatus = newStatus;
    switch (newStatus) {
        case IDataLoaderStatusListener::DATA_LOADER_NO_CONNECTION: {
            auto now = Clock::now();
            if (ifs->connectionLostTime.time_since_epoch().count() == 0) {
                ifs->connectionLostTime = now;
                break;
            }
            auto duration =
                    std::chrono::duration_cast<Seconds>(now - ifs->connectionLostTime).count();
            if (duration >= 10) {
                incrementalService.mIncrementalManager->showHealthBlockedUI(mountId);
            }
            break;
        }
        case IDataLoaderStatusListener::DATA_LOADER_CONNECTION_OK: {
            ifs->dataLoaderStatus = IDataLoaderStatusListener::DATA_LOADER_STARTED;
            break;
        }
        case IDataLoaderStatusListener::DATA_LOADER_CREATED: {
            ifs->dataLoaderReady.notify_one();
            break;
        }
        case IDataLoaderStatusListener::DATA_LOADER_DESTROYED: {
            ifs->dataLoaderStatus = IDataLoaderStatusListener::DATA_LOADER_STOPPED;
            incrementalService.deleteStorageLocked(*ifs, std::move(l));
            break;
        }
        case IDataLoaderStatusListener::DATA_LOADER_STARTED: {
            break;
        }
        case IDataLoaderStatusListener::DATA_LOADER_STOPPED: {
            break;
        }
        default: {
            LOG(WARNING) << "Unknown data loader status: " << newStatus
                         << " for mount: " << mountId;
            break;
        }
    }

    return binder::Status::ok();
}

} // namespace android::incremental
