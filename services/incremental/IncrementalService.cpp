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

#include <ctime>
#include <filesystem>
#include <iterator>
#include <span>
#include <stack>
#include <thread>
#include <type_traits>

#include "Metadata.pb.h"

using namespace std::literals;
using namespace android::content::pm;
namespace fs = std::filesystem;

namespace android::incremental {

namespace {

using IncrementalFileSystemControlParcel =
        ::android::os::incremental::IncrementalFileSystemControlParcel;

struct Constants {
    static constexpr auto backing = "backing_store"sv;
    static constexpr auto mount = "mount"sv;
    static constexpr auto mountKeyPrefix = "MT_"sv;
    static constexpr auto storagePrefix = "st"sv;
    static constexpr auto mountpointMdPrefix = ".mountpoint."sv;
    static constexpr auto infoMdName = ".info"sv;
    static constexpr auto libDir = "lib"sv;
    static constexpr auto libSuffix = ".so"sv;
    static constexpr auto blockSize = 4096;
};

static const Constants& constants() {
    static Constants c;
    return c;
}

template <base::LogSeverity level = base::ERROR>
bool mkdirOrLog(std::string_view name, int mode = 0770, bool allowExisting = true) {
    auto cstr = path::c_str(name);
    if (::mkdir(cstr, mode)) {
        if (!allowExisting || errno != EEXIST) {
            PLOG(level) << "Can't create directory '" << name << '\'';
            return false;
        }
        struct stat st;
        if (::stat(cstr, &st) || !S_ISDIR(st.st_mode)) {
            PLOG(level) << "Path exists but is not a directory: '" << name << '\'';
            return false;
        }
    }
    if (::chmod(cstr, mode)) {
        PLOG(level) << "Changing permission failed for '" << name << '\'';
        return false;
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
    return std::string(constants().mountKeyPrefix) + res;
}

static std::pair<std::string, std::string> makeMountDir(std::string_view incrementalDir,
                                                        std::string_view path) {
    auto mountKey = toMountKey(path);
    const auto prefixSize = mountKey.size();
    for (int counter = 0; counter < 1000;
         mountKey.resize(prefixSize), base::StringAppendF(&mountKey, "%d", counter++)) {
        auto mountRoot = path::join(incrementalDir, mountKey);
        if (mkdirOrLog(mountRoot, 0777, false)) {
            return {mountKey, mountRoot};
        }
    }
    return {};
}

template <class ProtoMessage, class Control>
static ProtoMessage parseFromIncfs(const IncFsWrapper* incfs, Control&& control,
                                   std::string_view path) {
    auto md = incfs->getMetadata(control, path);
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
    incrementalService.mDataLoaderManager->destroyDataLoader(mountId);
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
    std::string name;
    for (int no = nextStorageDirNo.fetch_add(1, std::memory_order_relaxed), i = 0;
         i < 1024 && no >= 0; no = nextStorageDirNo.fetch_add(1, std::memory_order_relaxed), ++i) {
        name.clear();
        base::StringAppendF(&name, "%.*s_%d_%d", int(constants().storagePrefix.size()),
                            constants().storagePrefix.data(), id, no);
        auto fullName = path::join(root, constants().mount, name);
        if (auto err = incrementalService.mIncFs->makeDir(control, fullName, 0755); !err) {
            std::lock_guard l(lock);
            return storages.insert_or_assign(id, Storage{std::move(fullName)}).first;
        } else if (err != EEXIST) {
            LOG(ERROR) << __func__ << "(): failed to create dir |" << fullName << "| " << err;
            break;
        }
    }
    nextStorageDirNo = 0;
    return storages.end();
}

static std::unique_ptr<DIR, decltype(&::closedir)> openDir(const char* path) {
    return {::opendir(path), ::closedir};
}

static int rmDirContent(const char* path) {
    auto dir = openDir(path);
    if (!dir) {
        return -EINVAL;
    }
    while (auto entry = ::readdir(dir.get())) {
        if (entry->d_name == "."sv || entry->d_name == ".."sv) {
            continue;
        }
        auto fullPath = android::base::StringPrintf("%s/%s", path, entry->d_name);
        if (entry->d_type == DT_DIR) {
            if (const auto err = rmDirContent(fullPath.c_str()); err != 0) {
                PLOG(WARNING) << "Failed to delete " << fullPath << " content";
                return err;
            }
            if (const auto err = ::rmdir(fullPath.c_str()); err != 0) {
                PLOG(WARNING) << "Failed to rmdir " << fullPath;
                return err;
            }
        } else {
            if (const auto err = ::unlink(fullPath.c_str()); err != 0) {
                PLOG(WARNING) << "Failed to delete " << fullPath;
                return err;
            }
        }
    }
    return 0;
}

void IncrementalService::IncFsMount::cleanupFilesystem(std::string_view root) {
    rmDirContent(path::join(root, constants().backing).c_str());
    ::rmdir(path::join(root, constants().backing).c_str());
    ::rmdir(path::join(root, constants().mount).c_str());
    ::rmdir(path::c_str(root));
}

IncrementalService::IncrementalService(ServiceManagerWrapper&& sm, std::string_view rootDir)
      : mVold(sm.getVoldService()),
        mDataLoaderManager(sm.getDataLoaderManager()),
        mIncFs(sm.getIncFs()),
        mIncrementalDir(rootDir) {
    if (!mVold) {
        LOG(FATAL) << "Vold service is unavailable";
    }
    if (!mDataLoaderManager) {
        LOG(FATAL) << "DataLoaderManagerService is unavailable";
    }
    mountExistingImages();
}

FileId IncrementalService::idFromMetadata(std::span<const uint8_t> metadata) {
    return IncFs_FileIdFromMetadata({(const char*)metadata.data(), metadata.size()});
}

IncrementalService::~IncrementalService() = default;

inline const char* toString(TimePoint t) {
    using SystemClock = std::chrono::system_clock;
    time_t time = SystemClock::to_time_t(
            SystemClock::now() +
            std::chrono::duration_cast<SystemClock::duration>(t - Clock::now()));
    return std::ctime(&time);
}

inline const char* toString(IncrementalService::BindKind kind) {
    switch (kind) {
        case IncrementalService::BindKind::Temporary:
            return "Temporary";
        case IncrementalService::BindKind::Permanent:
            return "Permanent";
    }
}

void IncrementalService::onDump(int fd) {
    dprintf(fd, "Incremental is %s\n", incfs::enabled() ? "ENABLED" : "DISABLED");
    dprintf(fd, "Incremental dir: %s\n", mIncrementalDir.c_str());

    std::unique_lock l(mLock);

    dprintf(fd, "Mounts (%d):\n", int(mMounts.size()));
    for (auto&& [id, ifs] : mMounts) {
        const IncFsMount& mnt = *ifs.get();
        dprintf(fd, "\t[%d]:\n", id);
        dprintf(fd, "\t\tmountId: %d\n", mnt.mountId);
        dprintf(fd, "\t\tnextStorageDirNo: %d\n", mnt.nextStorageDirNo.load());
        dprintf(fd, "\t\tdataLoaderStatus: %d\n", mnt.dataLoaderStatus.load());
        dprintf(fd, "\t\tconnectionLostTime: %s\n", toString(mnt.connectionLostTime));
        if (mnt.savedDataLoaderParams) {
            const auto& params = mnt.savedDataLoaderParams.value();
            dprintf(fd, "\t\tsavedDataLoaderParams:\n");
            dprintf(fd, "\t\t\ttype: %s\n", toString(params.type).c_str());
            dprintf(fd, "\t\t\tpackageName: %s\n", params.packageName.c_str());
            dprintf(fd, "\t\t\tclassName: %s\n", params.className.c_str());
            dprintf(fd, "\t\t\targuments: %s\n", params.arguments.c_str());
            dprintf(fd, "\t\t\tdynamicArgs: %d\n", int(params.dynamicArgs.size()));
        }
        dprintf(fd, "\t\tstorages (%d):\n", int(mnt.storages.size()));
        for (auto&& [storageId, storage] : mnt.storages) {
            dprintf(fd, "\t\t\t[%d] -> [%s]\n", storageId, storage.name.c_str());
        }

        dprintf(fd, "\t\tbindPoints (%d):\n", int(mnt.bindPoints.size()));
        for (auto&& [target, bind] : mnt.bindPoints) {
            dprintf(fd, "\t\t\t[%s]->[%d]:\n", target.c_str(), bind.storage);
            dprintf(fd, "\t\t\t\tsavedFilename: %s\n", bind.savedFilename.c_str());
            dprintf(fd, "\t\t\t\tsourceDir: %s\n", bind.sourceDir.c_str());
            dprintf(fd, "\t\t\t\tkind: %s\n", toString(bind.kind));
        }
    }

    dprintf(fd, "Sorted binds (%d):\n", int(mBindsByPath.size()));
    for (auto&& [target, mountPairIt] : mBindsByPath) {
        const auto& bind = mountPairIt->second;
        dprintf(fd, "\t\t[%s]->[%d]:\n", target.c_str(), bind.storage);
        dprintf(fd, "\t\t\tsavedFilename: %s\n", bind.savedFilename.c_str());
        dprintf(fd, "\t\t\tsourceDir: %s\n", bind.sourceDir.c_str());
        dprintf(fd, "\t\t\tkind: %s\n", toString(bind.kind));
    }
}

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
        for (auto&& ifs : mounts) {
            if (prepareDataLoader(*ifs)) {
                LOG(INFO) << "Successfully started data loader for mount " << ifs->mountId;
            } else {
                // TODO(b/133435829): handle data loader start failures
                LOG(WARNING) << "Failed to start data loader for mount " << ifs->mountId;
            }
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

StorageId IncrementalService::createStorage(
        std::string_view mountPoint, DataLoaderParamsParcel&& dataLoaderParams,
        const DataLoaderStatusListener& dataLoaderStatusListener, CreateOptions options) {
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
    const auto backing = path::join(mountRoot, constants().backing);
    if (!mkdirOrLog(backing, 0777) || !mkdirOrLog(mountTarget)) {
        return kInvalidStorageId;
    }

    IncFsMount::Control control;
    {
        std::lock_guard l(mMountOperationLock);
        IncrementalFileSystemControlParcel controlParcel;

        if (auto err = rmDirContent(backing.c_str())) {
            LOG(ERROR) << "Coudn't clean the backing directory " << backing << ": " << err;
            return kInvalidStorageId;
        }
        if (!mkdirOrLog(path::join(backing, ".index"), 0777)) {
            return kInvalidStorageId;
        }
        auto status = mVold->mountIncFs(backing, mountTarget, 0, &controlParcel);
        if (!status.isOk()) {
            LOG(ERROR) << "Vold::mountIncFs() failed: " << status.toString8();
            return kInvalidStorageId;
        }
        if (controlParcel.cmd.get() < 0 || controlParcel.pendingReads.get() < 0 ||
            controlParcel.log.get() < 0) {
            LOG(ERROR) << "Vold::mountIncFs() returned invalid control parcel.";
            return kInvalidStorageId;
        }
        control.cmd = controlParcel.cmd.release().release();
        control.pendingReads = controlParcel.pendingReads.release().release();
        control.logs = controlParcel.log.release().release();
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
        LOG(ERROR) << "Can't create a default storage directory";
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
        if (auto err =
                    mIncFs->makeFile(ifs->control,
                                     path::join(ifs->root, constants().mount,
                                                constants().infoMdName),
                                     0777, idFromMetadata(metadata),
                                     {.metadata = {metadata.data(), (IncFsSize)metadata.size()}})) {
            LOG(ERROR) << "Saving mount metadata failed: " << -err;
            return kInvalidStorageId;
        }
    }

    const auto bk =
            (options & CreateOptions::PermanentBind) ? BindKind::Permanent : BindKind::Temporary;
    if (auto err = addBindMount(*ifs, storageIt->first, storageIt->second.name,
                                std::string(storageIt->second.name), std::move(mountNorm), bk, l);
        err < 0) {
        LOG(ERROR) << "adding bind mount failed: " << -err;
        return kInvalidStorageId;
    }

    // Done here as well, all data structures are in good state.
    secondCleanupOnFailure.release();

    if (!prepareDataLoader(*ifs, &dataLoaderParams, &dataLoaderStatusListener)) {
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
    if (auto err = addBindMount(*ifs, storageIt->first, storageIt->second.name,
                                std::string(storageIt->second.name), path::normalize(mountPoint),
                                bk, l);
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

FileId IncrementalService::nodeFor(StorageId storage, std::string_view subpath) const {
    const auto ifs = getIfs(storage);
    if (!ifs) {
        return kIncFsInvalidFileId;
    }
    std::unique_lock l(ifs->lock);
    auto storageIt = ifs->storages.find(storage);
    if (storageIt == ifs->storages.end()) {
        return kIncFsInvalidFileId;
    }
    if (subpath.empty() || subpath == "."sv) {
        return kIncFsInvalidFileId;
    }
    auto path = path::join(ifs->root, constants().mount, storageIt->second.name, subpath);
    l.unlock();
    return mIncFs->getFileId(ifs->control, path);
}

std::pair<FileId, std::string_view> IncrementalService::parentAndNameFor(
        StorageId storage, std::string_view subpath) const {
    auto name = path::basename(subpath);
    if (name.empty()) {
        return {kIncFsInvalidFileId, {}};
    }
    auto dir = path::dirname(subpath);
    if (dir.empty() || dir == "/"sv) {
        return {kIncFsInvalidFileId, {}};
    }
    auto id = nodeFor(storage, dir);
    return {id, name};
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

int IncrementalService::bind(StorageId storage, std::string_view source, std::string_view target,
                             BindKind kind) {
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
    std::string normSource = normalizePathToStorage(ifs, storage, source);
    l.unlock();
    std::unique_lock l2(mLock, std::defer_lock);
    return addBindMount(*ifs, storage, storageInfo->second.name, std::move(normSource),
                        path::normalize(target), kind, l2);
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
            mIncFs->unlink(ifs->control, path::join(ifs->root, constants().mount, savedFile));
        }
    }
    return 0;
}

std::string IncrementalService::normalizePathToStorage(const IncrementalService::IfsMountPtr ifs,
                                                       StorageId storage, std::string_view path) {
    const auto storageInfo = ifs->storages.find(storage);
    if (storageInfo == ifs->storages.end()) {
        return {};
    }
    std::string normPath;
    if (path::isAbsolute(path)) {
        normPath = path::normalize(path);
    } else {
        normPath = path::normalize(path::join(storageInfo->second.name, path));
    }
    if (!path::startsWith(normPath, storageInfo->second.name)) {
        return {};
    }
    return normPath;
}

int IncrementalService::makeFile(StorageId storage, std::string_view path, int mode, FileId id,
                                 incfs::NewFileParams params) {
    if (auto ifs = getIfs(storage)) {
        std::string normPath = normalizePathToStorage(ifs, storage, path);
        if (normPath.empty()) {
            return -EINVAL;
        }
        auto err = mIncFs->makeFile(ifs->control, normPath, mode, id, params);
        if (err) {
            return err;
        }
        std::vector<uint8_t> metadataBytes;
        if (params.metadata.data && params.metadata.size > 0) {
            metadataBytes.assign(params.metadata.data, params.metadata.data + params.metadata.size);
        }
        return 0;
    }
    return -EINVAL;
}

int IncrementalService::makeDir(StorageId storageId, std::string_view path, int mode) {
    if (auto ifs = getIfs(storageId)) {
        std::string normPath = normalizePathToStorage(ifs, storageId, path);
        if (normPath.empty()) {
            return -EINVAL;
        }
        return mIncFs->makeDir(ifs->control, normPath, mode);
    }
    return -EINVAL;
}

int IncrementalService::makeDirs(StorageId storageId, std::string_view path, int mode) {
    const auto ifs = getIfs(storageId);
    if (!ifs) {
        return -EINVAL;
    }
    std::string normPath = normalizePathToStorage(ifs, storageId, path);
    if (normPath.empty()) {
        return -EINVAL;
    }
    auto err = mIncFs->makeDir(ifs->control, normPath, mode);
    if (err == -EEXIST) {
        return 0;
    } else if (err != -ENOENT) {
        return err;
    }
    if (auto err = makeDirs(storageId, path::dirname(normPath), mode)) {
        return err;
    }
    return mIncFs->makeDir(ifs->control, normPath, mode);
}

int IncrementalService::link(StorageId sourceStorageId, std::string_view oldPath,
                             StorageId destStorageId, std::string_view newPath) {
    if (auto ifsSrc = getIfs(sourceStorageId), ifsDest = getIfs(destStorageId);
        ifsSrc && ifsSrc == ifsDest) {
        std::string normOldPath = normalizePathToStorage(ifsSrc, sourceStorageId, oldPath);
        std::string normNewPath = normalizePathToStorage(ifsDest, destStorageId, newPath);
        if (normOldPath.empty() || normNewPath.empty()) {
            return -EINVAL;
        }
        return mIncFs->link(ifsSrc->control, normOldPath, normNewPath);
    }
    return -EINVAL;
}

int IncrementalService::unlink(StorageId storage, std::string_view path) {
    if (auto ifs = getIfs(storage)) {
        std::string normOldPath = normalizePathToStorage(ifs, storage, path);
        return mIncFs->unlink(ifs->control, normOldPath);
    }
    return -EINVAL;
}

int IncrementalService::addBindMount(IncFsMount& ifs, StorageId storage,
                                     std::string_view storageRoot, std::string&& source,
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
        bp.set_allocated_source_subdir(&source);
        const auto metadata = bp.SerializeAsString();
        bp.release_dest_path();
        bp.release_source_subdir();
        mdFileName = makeBindMdName();
        auto node =
                mIncFs->makeFile(ifs.control, path::join(ifs.root, constants().mount, mdFileName),
                                 0444, idFromMetadata(metadata),
                                 {.metadata = {metadata.data(), (IncFsSize)metadata.size()}});
        if (node) {
            return int(node);
        }
    }

    return addBindMountWithMd(ifs, storage, std::move(mdFileName), std::move(source),
                              std::move(target), kind, mainLock);
}

int IncrementalService::addBindMountWithMd(IncrementalService::IncFsMount& ifs, StorageId storage,
                                           std::string&& metadataName, std::string&& source,
                                           std::string&& target, BindKind kind,
                                           std::unique_lock<std::mutex>& mainLock) {
    {
        std::lock_guard l(mMountOperationLock);
        const auto status = mVold->bindMount(source, target);
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
                                                             std::move(source), kind});
    mBindsByPath[std::move(target)] = it;
    return 0;
}

RawMetadata IncrementalService::getMetadata(StorageId storage, FileId node) const {
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
    {
        std::unique_lock l(mLock);
        const auto& ifs = getIfsLocked(storage);
        if (!ifs) {
            return false;
        }
        if (ifs->dataLoaderStatus != IDataLoaderStatusListener::DATA_LOADER_CREATED) {
            ifs->dataLoaderStartRequested = true;
            return true;
        }
    }
    return startDataLoader(storage);
}

bool IncrementalService::startDataLoader(MountId mountId) const {
    sp<IDataLoader> dataloader;
    auto status = mDataLoaderManager->getDataLoader(mountId, &dataloader);
    if (!status.isOk()) {
        return false;
    }
    if (!dataloader) {
        return false;
    }
    status = dataloader->start();
    if (!status.isOk()) {
        return false;
    }
    return true;
}

void IncrementalService::mountExistingImages() {
    for (const auto& entry : fs::directory_iterator(mIncrementalDir)) {
        const auto path = entry.path().u8string();
        const auto name = entry.path().filename().u8string();
        if (!base::StartsWith(name, constants().mountKeyPrefix)) {
            continue;
        }
        const auto root = path::join(mIncrementalDir, name);
        if (!mountExistingImage(root, name)) {
            IncFsMount::cleanupFilesystem(path);
        }
    }
}

bool IncrementalService::mountExistingImage(std::string_view root, std::string_view key) {
    auto mountTarget = path::join(root, constants().mount);
    const auto backing = path::join(root, constants().backing);

    IncFsMount::Control control;
    IncrementalFileSystemControlParcel controlParcel;
    auto status = mVold->mountIncFs(backing, mountTarget, 0, &controlParcel);
    if (!status.isOk()) {
        LOG(ERROR) << "Vold::mountIncFs() failed: " << status.toString8();
        return false;
    }
    control.cmd = controlParcel.cmd.release().release();
    control.pendingReads = controlParcel.pendingReads.release().release();
    control.logs = controlParcel.log.release().release();

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
    auto d = openDir(path::c_str(mountTarget));
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
                    mIncFs->unlink(ifs->control, path::join(ifs->root, constants().mount, name));
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
                ifs->storages.insert_or_assign(md.id(), IncFsMount::Storage{std::string(name)});
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

    mMounts[ifs->mountId] = std::move(ifs);
    return true;
}

bool IncrementalService::prepareDataLoader(IncrementalService::IncFsMount& ifs,
                                           DataLoaderParamsParcel* params,
                                           const DataLoaderStatusListener* externalListener) {
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

    std::unique_lock l(ifs.lock);
    if (ifs.dataLoaderStatus != -1) {
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
    fsControlParcel.incremental = IncrementalFileSystemControlParcel();
    fsControlParcel.incremental->cmd.reset(base::unique_fd(::dup(ifs.control.cmd)));
    fsControlParcel.incremental->pendingReads.reset(
            base::unique_fd(::dup(ifs.control.pendingReads)));
    fsControlParcel.incremental->log.reset(base::unique_fd(::dup(ifs.control.logs)));
    sp<IncrementalDataLoaderListener> listener =
            new IncrementalDataLoaderListener(*this, *externalListener);
    bool created = false;
    auto status = mDataLoaderManager->initializeDataLoader(ifs.mountId, *dlp, fsControlParcel,
                                                           listener, &created);
    if (!status.isOk() || !created) {
        LOG(ERROR) << "Failed to create a data loader for mount " << ifs.mountId;
        return false;
    }
    ifs.savedDataLoaderParams.reset();
    return true;
}

// Extract lib filse from zip, create new files in incfs and write data to them
bool IncrementalService::configureNativeBinaries(StorageId storage, std::string_view apkFullPath,
                                                 std::string_view libDirRelativePath,
                                                 std::string_view abi) {
    const auto ifs = getIfs(storage);
    // First prepare target directories if they don't exist yet
    if (auto res = makeDirs(storage, libDirRelativePath, 0755)) {
        LOG(ERROR) << "Failed to prepare target lib directory " << libDirRelativePath
                   << " errno: " << res;
        return false;
    }

    std::unique_ptr<ZipFileRO> zipFile(ZipFileRO::open(apkFullPath.data()));
    if (!zipFile) {
        LOG(ERROR) << "Failed to open zip file at " << apkFullPath;
        return false;
    }
    void* cookie = nullptr;
    const auto libFilePrefix = path::join(constants().libDir, abi);
    if (!zipFile.get()->startIteration(&cookie, libFilePrefix.c_str() /* prefix */,
                                       constants().libSuffix.data() /* suffix */)) {
        LOG(ERROR) << "Failed to start zip iteration for " << apkFullPath;
        return false;
    }
    ZipEntryRO entry = nullptr;
    bool success = true;
    while ((entry = zipFile.get()->nextEntry(cookie)) != nullptr) {
        char fileName[PATH_MAX];
        if (zipFile.get()->getEntryFileName(entry, fileName, sizeof(fileName))) {
            continue;
        }
        const auto libName = path::basename(fileName);
        const auto targetLibPath = path::join(libDirRelativePath, libName);
        const auto targetLibPathAbsolute = normalizePathToStorage(ifs, storage, targetLibPath);
        // If the extract file already exists, skip
        struct stat st;
        if (stat(targetLibPathAbsolute.c_str(), &st) == 0) {
            LOG(INFO) << "Native lib file already exists: " << targetLibPath
                      << "; skipping extraction";
            continue;
        }

        uint32_t uncompressedLen;
        if (!zipFile.get()->getEntryInfo(entry, nullptr, &uncompressedLen, nullptr, nullptr,
                                         nullptr, nullptr)) {
            LOG(ERROR) << "Failed to read native lib entry: " << fileName;
            success = false;
            break;
        }

        // Create new lib file without signature info
        incfs::NewFileParams libFileParams{};
        libFileParams.size = uncompressedLen;
        libFileParams.verification.hashAlgorithm = INCFS_HASH_NONE;
        // Metadata of the new lib file is its relative path
        IncFsSpan libFileMetadata;
        libFileMetadata.data = targetLibPath.c_str();
        libFileMetadata.size = targetLibPath.size();
        libFileParams.metadata = libFileMetadata;
        incfs::FileId libFileId = idFromMetadata(targetLibPath);
        if (auto res = makeFile(storage, targetLibPath, 0777, libFileId, libFileParams)) {
            LOG(ERROR) << "Failed to make file for: " << targetLibPath << " errno: " << res;
            success = false;
            // If one lib file fails to be created, abort others as well
            break;
        }

        // Write extracted data to new file
        std::vector<uint8_t> libData(uncompressedLen);
        if (!zipFile.get()->uncompressEntry(entry, &libData[0], uncompressedLen)) {
            LOG(ERROR) << "Failed to extract native lib zip entry: " << fileName;
            success = false;
            break;
        }
        android::base::unique_fd writeFd(mIncFs->openWrite(ifs->control, libFileId));
        if (writeFd < 0) {
            LOG(ERROR) << "Failed to open write fd for: " << targetLibPath << " errno: " << writeFd;
            success = false;
            break;
        }
        const int numBlocks = uncompressedLen / constants().blockSize + 1;
        std::vector<IncFsDataBlock> instructions;
        auto remainingData = std::span(libData);
        for (int i = 0; i < numBlocks - 1; i++) {
            auto inst = IncFsDataBlock{
                    .fileFd = writeFd,
                    .pageIndex = static_cast<IncFsBlockIndex>(i),
                    .compression = INCFS_COMPRESSION_KIND_NONE,
                    .kind = INCFS_BLOCK_KIND_DATA,
                    .dataSize = static_cast<uint16_t>(constants().blockSize),
                    .data = reinterpret_cast<const char*>(remainingData.data()),
            };
            instructions.push_back(inst);
            remainingData = remainingData.subspan(constants().blockSize);
        }
        // Last block
        auto inst = IncFsDataBlock{
                .fileFd = writeFd,
                .pageIndex = static_cast<IncFsBlockIndex>(numBlocks - 1),
                .compression = INCFS_COMPRESSION_KIND_NONE,
                .kind = INCFS_BLOCK_KIND_DATA,
                .dataSize = static_cast<uint16_t>(remainingData.size()),
                .data = reinterpret_cast<const char*>(remainingData.data()),
        };
        instructions.push_back(inst);
        size_t res = mIncFs->writeBlocks(instructions);
        if (res != instructions.size()) {
            LOG(ERROR) << "Failed to write data into: " << targetLibPath;
            success = false;
        }
        instructions.clear();
    }
    zipFile.get()->endIteration(cookie);
    return success;
}

binder::Status IncrementalService::IncrementalDataLoaderListener::onStatusChanged(MountId mountId,
                                                                                  int newStatus) {
    if (externalListener) {
        // Give an external listener a chance to act before we destroy something.
        externalListener->onStatusChanged(mountId, newStatus);
    }

    bool startRequested = false;
    {
        std::unique_lock l(incrementalService.mLock);
        const auto& ifs = incrementalService.getIfsLocked(mountId);
        if (!ifs) {
            LOG(WARNING) << "Received data loader status " << int(newStatus) << " for unknown mount "
                         << mountId;
            return binder::Status::ok();
        }
        ifs->dataLoaderStatus = newStatus;

        if (newStatus == IDataLoaderStatusListener::DATA_LOADER_DESTROYED) {
            ifs->dataLoaderStatus = IDataLoaderStatusListener::DATA_LOADER_STOPPED;
            incrementalService.deleteStorageLocked(*ifs, std::move(l));
            return binder::Status::ok();
        }

        startRequested = ifs->dataLoaderStartRequested;
    }

    switch (newStatus) {
        case IDataLoaderStatusListener::DATA_LOADER_NO_CONNECTION: {
            // TODO(b/150411019): handle data loader connection loss
            break;
        }
        case IDataLoaderStatusListener::DATA_LOADER_CONNECTION_OK: {
            // TODO(b/150411019): handle data loader connection loss
            break;
        }
        case IDataLoaderStatusListener::DATA_LOADER_CREATED: {
            if (startRequested) {
                incrementalService.startDataLoader(mountId);
            }
            break;
        }
        case IDataLoaderStatusListener::DATA_LOADER_DESTROYED: {
            break;
        }
        case IDataLoaderStatusListener::DATA_LOADER_STARTED: {
            break;
        }
        case IDataLoaderStatusListener::DATA_LOADER_STOPPED: {
            break;
        }
        case IDataLoaderStatusListener::DATA_LOADER_IMAGE_READY: {
            break;
        }
        case IDataLoaderStatusListener::DATA_LOADER_IMAGE_NOT_READY: {
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
