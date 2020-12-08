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

#include <android-base/logging.h>
#include <android-base/no_destructor.h>
#include <android-base/properties.h>
#include <android-base/stringprintf.h>
#include <binder/AppOpsManager.h>
#include <binder/Nullable.h>
#include <binder/Status.h>
#include <sys/stat.h>
#include <uuid/uuid.h>

#include <charconv>
#include <ctime>
#include <iterator>
#include <span>
#include <type_traits>

#include "IncrementalServiceValidation.h"
#include "Metadata.pb.h"

using namespace std::literals;
namespace fs = std::filesystem;

constexpr const char* kDataUsageStats = "android.permission.LOADER_USAGE_STATS";
constexpr const char* kOpUsage = "android:loader_usage_stats";

namespace android::incremental {

using content::pm::DataLoaderParamsParcel;
using content::pm::FileSystemControlParcel;
using content::pm::IDataLoader;

namespace {

using IncrementalFileSystemControlParcel = os::incremental::IncrementalFileSystemControlParcel;

struct Constants {
    static constexpr auto backing = "backing_store"sv;
    static constexpr auto mount = "mount"sv;
    static constexpr auto mountKeyPrefix = "MT_"sv;
    static constexpr auto storagePrefix = "st"sv;
    static constexpr auto mountpointMdPrefix = ".mountpoint."sv;
    static constexpr auto infoMdName = ".info"sv;
    static constexpr auto readLogsDisabledMarkerName = ".readlogs_disabled"sv;
    static constexpr auto libDir = "lib"sv;
    static constexpr auto libSuffix = ".so"sv;
    static constexpr auto blockSize = 4096;
    static constexpr auto systemPackage = "android"sv;
};

static const Constants& constants() {
    static constexpr Constants c;
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
    if (path.size() > 16) {
        path = path.substr(0, 16);
    }
    std::string res(path);
    std::replace_if(
            res.begin(), res.end(), [](char c) { return c == '/' || c == '@'; }, '_');
    return std::string(constants().mountKeyPrefix) += res;
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

template <class Map>
typename Map::const_iterator findParentPath(const Map& map, std::string_view path) {
    const auto nextIt = map.upper_bound(path);
    if (nextIt == map.begin()) {
        return map.end();
    }
    const auto suspectIt = std::prev(nextIt);
    if (!path::startsWith(path, suspectIt->first)) {
        return map.end();
    }
    return suspectIt;
}

static base::unique_fd dup(base::borrowed_fd fd) {
    const auto res = fcntl(fd.get(), F_DUPFD_CLOEXEC, 0);
    return base::unique_fd(res);
}

template <class ProtoMessage, class Control>
static ProtoMessage parseFromIncfs(const IncFsWrapper* incfs, const Control& control,
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

static bool checkReadLogsDisabledMarker(std::string_view root) {
    const auto markerPath = path::c_str(path::join(root, constants().readLogsDisabledMarkerName));
    struct stat st;
    return (::stat(markerPath, &st) == 0);
}

} // namespace

IncrementalService::IncFsMount::~IncFsMount() {
    if (dataLoaderStub) {
        dataLoaderStub->cleanupResources();
        dataLoaderStub = {};
    }
    control.close();
    LOG(INFO) << "Unmounting and cleaning up mount " << mountId << " with root '" << root << '\'';
    for (auto&& [target, _] : bindPoints) {
        LOG(INFO) << "  bind: " << target;
        incrementalService.mVold->unmountIncFs(target);
    }
    LOG(INFO) << "  root: " << root;
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

template <class Func>
static auto makeCleanup(Func&& f) {
    auto deleter = [f = std::move(f)](auto) { f(); };
    // &f is a dangling pointer here, but we actually never use it as deleter moves it in.
    return std::unique_ptr<Func, decltype(deleter)>(&f, std::move(deleter));
}

static std::unique_ptr<DIR, decltype(&::closedir)> openDir(const char* dir) {
    return {::opendir(dir), ::closedir};
}

static auto openDir(std::string_view dir) {
    return openDir(path::c_str(dir));
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
        auto fullPath = base::StringPrintf("%s/%s", path, entry->d_name);
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
        mAppOpsManager(sm.getAppOpsManager()),
        mJni(sm.getJni()),
        mLooper(sm.getLooper()),
        mTimedQueue(sm.getTimedQueue()),
        mIncrementalDir(rootDir) {
    CHECK(mVold) << "Vold service is unavailable";
    CHECK(mDataLoaderManager) << "DataLoaderManagerService is unavailable";
    CHECK(mAppOpsManager) << "AppOpsManager is unavailable";
    CHECK(mJni) << "JNI is unavailable";
    CHECK(mLooper) << "Looper is unavailable";
    CHECK(mTimedQueue) << "TimedQueue is unavailable";

    mJobQueue.reserve(16);
    mJobProcessor = std::thread([this]() {
        mJni->initializeForCurrentThread();
        runJobProcessing();
    });
    mCmdLooperThread = std::thread([this]() {
        mJni->initializeForCurrentThread();
        runCmdLooper();
    });

    const auto mountedRootNames = adoptMountedInstances();
    mountExistingImages(mountedRootNames);
}

IncrementalService::~IncrementalService() {
    {
        std::lock_guard lock(mJobMutex);
        mRunning = false;
    }
    mJobCondition.notify_all();
    mJobProcessor.join();
    mLooper->wake();
    mCmdLooperThread.join();
    mTimedQueue->stop();
    // Ensure that mounts are destroyed while the service is still valid.
    mBindsByPath.clear();
    mMounts.clear();
}

static const char* toString(IncrementalService::BindKind kind) {
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

    dprintf(fd, "Mounts (%d): {\n", int(mMounts.size()));
    for (auto&& [id, ifs] : mMounts) {
        const IncFsMount& mnt = *ifs;
        dprintf(fd, "  [%d]: {\n", id);
        if (id != mnt.mountId) {
            dprintf(fd, "    reference to mountId: %d\n", mnt.mountId);
        } else {
            dprintf(fd, "    mountId: %d\n", mnt.mountId);
            dprintf(fd, "    root: %s\n", mnt.root.c_str());
            dprintf(fd, "    nextStorageDirNo: %d\n", mnt.nextStorageDirNo.load());
            if (mnt.dataLoaderStub) {
                mnt.dataLoaderStub->onDump(fd);
            } else {
                dprintf(fd, "    dataLoader: null\n");
            }
            dprintf(fd, "    storages (%d): {\n", int(mnt.storages.size()));
            for (auto&& [storageId, storage] : mnt.storages) {
                dprintf(fd, "      [%d] -> [%s]\n", storageId, storage.name.c_str());
            }
            dprintf(fd, "    }\n");

            dprintf(fd, "    bindPoints (%d): {\n", int(mnt.bindPoints.size()));
            for (auto&& [target, bind] : mnt.bindPoints) {
                dprintf(fd, "      [%s]->[%d]:\n", target.c_str(), bind.storage);
                dprintf(fd, "        savedFilename: %s\n", bind.savedFilename.c_str());
                dprintf(fd, "        sourceDir: %s\n", bind.sourceDir.c_str());
                dprintf(fd, "        kind: %s\n", toString(bind.kind));
            }
            dprintf(fd, "    }\n");
        }
        dprintf(fd, "  }\n");
    }
    dprintf(fd, "}\n");
    dprintf(fd, "Sorted binds (%d): {\n", int(mBindsByPath.size()));
    for (auto&& [target, mountPairIt] : mBindsByPath) {
        const auto& bind = mountPairIt->second;
        dprintf(fd, "    [%s]->[%d]:\n", target.c_str(), bind.storage);
        dprintf(fd, "      savedFilename: %s\n", bind.savedFilename.c_str());
        dprintf(fd, "      sourceDir: %s\n", bind.sourceDir.c_str());
        dprintf(fd, "      kind: %s\n", toString(bind.kind));
    }
    dprintf(fd, "}\n");
}

void IncrementalService::onSystemReady() {
    if (mSystemReady.exchange(true)) {
        return;
    }

    std::vector<IfsMountPtr> mounts;
    {
        std::lock_guard l(mLock);
        mounts.reserve(mMounts.size());
        for (auto&& [id, ifs] : mMounts) {
            if (ifs->mountId == id &&
                ifs->dataLoaderStub->params().packageName == Constants::systemPackage) {
                mounts.push_back(ifs);
            }
        }
    }

    if (mounts.empty()) {
        return;
    }

    std::thread([this, mounts = std::move(mounts)]() {
        mJni->initializeForCurrentThread();
        for (auto&& ifs : mounts) {
            ifs->dataLoaderStub->requestStart();
        }
    }).detach();
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
                                            content::pm::DataLoaderParamsParcel&& dataLoaderParams,
                                            CreateOptions options,
                                            const DataLoaderStatusListener& statusListener,
                                            StorageHealthCheckParams&& healthCheckParams,
                                            const StorageHealthListener& healthListener) {
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
        int cmd = controlParcel.cmd.release().release();
        int pendingReads = controlParcel.pendingReads.release().release();
        int logs = controlParcel.log.release().release();
        control = mIncFs->createControl(cmd, pendingReads, logs);
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
        m.mutable_loader()->set_allocated_package_name(&dataLoaderParams.packageName);
        m.mutable_loader()->set_allocated_class_name(&dataLoaderParams.className);
        m.mutable_loader()->set_allocated_arguments(&dataLoaderParams.arguments);
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

    auto dataLoaderStub = prepareDataLoader(*ifs, std::move(dataLoaderParams), &statusListener,
                                            std::move(healthCheckParams), &healthListener);
    CHECK(dataLoaderStub);

    mountIt->second = std::move(ifs);
    l.unlock();

    if (mSystemReady.load(std::memory_order_relaxed) && !dataLoaderStub->requestCreate()) {
        // failed to create data loader
        LOG(ERROR) << "initializeDataLoader() failed";
        deleteStorage(dataLoaderStub->id());
        return kInvalidStorageId;
    }

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
    auto ifs = getIfsLocked(linkedStorage);
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
        (void)mIncFs->unlink(ifs->control, storageIt->second.name);
        ifs->storages.erase(storageIt);
        return kInvalidStorageId;
    }

    mountIt->second = ifs;
    return storageId;
}

IncrementalService::BindPathMap::const_iterator IncrementalService::findStorageLocked(
        std::string_view path) const {
    return findParentPath(mBindsByPath, path);
}

StorageId IncrementalService::findStorageId(std::string_view path) const {
    std::lock_guard l(mLock);
    auto it = findStorageLocked(path);
    if (it == mBindsByPath.end()) {
        return kInvalidStorageId;
    }
    return it->second->second.storage;
}

void IncrementalService::disableReadLogs(StorageId storageId) {
    std::unique_lock l(mLock);
    const auto ifs = getIfsLocked(storageId);
    if (!ifs) {
        LOG(ERROR) << "disableReadLogs failed, invalid storageId: " << storageId;
        return;
    }
    if (!ifs->readLogsEnabled()) {
        return;
    }
    ifs->disableReadLogs();
    l.unlock();

    const auto metadata = constants().readLogsDisabledMarkerName;
    if (auto err = mIncFs->makeFile(ifs->control,
                                    path::join(ifs->root, constants().mount,
                                               constants().readLogsDisabledMarkerName),
                                    0777, idFromMetadata(metadata), {})) {
        //{.metadata = {metadata.data(), (IncFsSize)metadata.size()}})) {
        LOG(ERROR) << "Failed to make marker file for storageId: " << storageId;
        return;
    }

    setStorageParams(storageId, /*enableReadLogs=*/false);
}

int IncrementalService::setStorageParams(StorageId storageId, bool enableReadLogs) {
    const auto ifs = getIfs(storageId);
    if (!ifs) {
        LOG(ERROR) << "setStorageParams failed, invalid storageId: " << storageId;
        return -EINVAL;
    }

    const auto& params = ifs->dataLoaderStub->params();
    if (enableReadLogs) {
        if (!ifs->readLogsEnabled()) {
            LOG(ERROR) << "setStorageParams failed, readlogs disabled for storageId: " << storageId;
            return -EPERM;
        }

        if (auto status = mAppOpsManager->checkPermission(kDataUsageStats, kOpUsage,
                                                          params.packageName.c_str());
            !status.isOk()) {
            LOG(ERROR) << "checkPermission failed: " << status.toString8();
            return fromBinderStatus(status);
        }
    }

    if (auto status = applyStorageParams(*ifs, enableReadLogs); !status.isOk()) {
        LOG(ERROR) << "applyStorageParams failed: " << status.toString8();
        return fromBinderStatus(status);
    }

    if (enableReadLogs) {
        registerAppOpsCallback(params.packageName);
    }

    return 0;
}

binder::Status IncrementalService::applyStorageParams(IncFsMount& ifs, bool enableReadLogs) {
    os::incremental::IncrementalFileSystemControlParcel control;
    control.cmd.reset(dup(ifs.control.cmd()));
    control.pendingReads.reset(dup(ifs.control.pendingReads()));
    auto logsFd = ifs.control.logs();
    if (logsFd >= 0) {
        control.log.reset(dup(logsFd));
    }

    std::lock_guard l(mMountOperationLock);
    return mVold->setIncFsMountOptions(control, enableReadLogs);
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

IncrementalService::IfsMountPtr IncrementalService::getIfs(StorageId storage) const {
    std::lock_guard l(mLock);
    return getIfsLocked(storage);
}

const IncrementalService::IfsMountPtr& IncrementalService::getIfsLocked(StorageId storage) const {
    auto it = mMounts.find(storage);
    if (it == mMounts.end()) {
        static const base::NoDestructor<IfsMountPtr> kEmpty{};
        return *kEmpty;
    }
    return it->second;
}

int IncrementalService::bind(StorageId storage, std::string_view source, std::string_view target,
                             BindKind kind) {
    if (!isValidMountTarget(target)) {
        LOG(ERROR) << __func__ << ": not a valid bind target " << target;
        return -EINVAL;
    }

    const auto ifs = getIfs(storage);
    if (!ifs) {
        LOG(ERROR) << __func__ << ": no ifs object for storage " << storage;
        return -EINVAL;
    }

    std::unique_lock l(ifs->lock);
    const auto storageInfo = ifs->storages.find(storage);
    if (storageInfo == ifs->storages.end()) {
        LOG(ERROR) << "no storage";
        return -EINVAL;
    }
    std::string normSource = normalizePathToStorageLocked(*ifs, storageInfo, source);
    if (normSource.empty()) {
        LOG(ERROR) << "invalid source path";
        return -EINVAL;
    }
    l.unlock();
    std::unique_lock l2(mLock, std::defer_lock);
    return addBindMount(*ifs, storage, storageInfo->second.name, std::move(normSource),
                        path::normalize(target), kind, l2);
}

int IncrementalService::unbind(StorageId storage, std::string_view target) {
    if (!path::isAbsolute(target)) {
        return -EINVAL;
    }

    LOG(INFO) << "Removing bind point " << target << " for storage " << storage;

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

std::string IncrementalService::normalizePathToStorageLocked(
        const IncFsMount& incfs, IncFsMount::StorageMap::const_iterator storageIt,
        std::string_view path) const {
    if (!path::isAbsolute(path)) {
        return path::normalize(path::join(storageIt->second.name, path));
    }
    auto normPath = path::normalize(path);
    if (path::startsWith(normPath, storageIt->second.name)) {
        return normPath;
    }
    // not that easy: need to find if any of the bind points match
    const auto bindIt = findParentPath(incfs.bindPoints, normPath);
    if (bindIt == incfs.bindPoints.end()) {
        return {};
    }
    return path::join(bindIt->second.sourceDir, path::relativize(bindIt->first, normPath));
}

std::string IncrementalService::normalizePathToStorage(const IncFsMount& ifs, StorageId storage,
                                                       std::string_view path) const {
    std::unique_lock l(ifs.lock);
    const auto storageInfo = ifs.storages.find(storage);
    if (storageInfo == ifs.storages.end()) {
        return {};
    }
    return normalizePathToStorageLocked(ifs, storageInfo, path);
}

int IncrementalService::makeFile(StorageId storage, std::string_view path, int mode, FileId id,
                                 incfs::NewFileParams params) {
    if (auto ifs = getIfs(storage)) {
        std::string normPath = normalizePathToStorage(*ifs, storage, path);
        if (normPath.empty()) {
            LOG(ERROR) << "Internal error: storageId " << storage
                       << " failed to normalize: " << path;
            return -EINVAL;
        }
        auto err = mIncFs->makeFile(ifs->control, normPath, mode, id, params);
        if (err) {
            LOG(ERROR) << "Internal error: storageId " << storage << " failed to makeFile: " << err;
            return err;
        }
        return 0;
    }
    return -EINVAL;
}

int IncrementalService::makeDir(StorageId storageId, std::string_view path, int mode) {
    if (auto ifs = getIfs(storageId)) {
        std::string normPath = normalizePathToStorage(*ifs, storageId, path);
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
    return makeDirs(*ifs, storageId, path, mode);
}

int IncrementalService::makeDirs(const IncFsMount& ifs, StorageId storageId, std::string_view path,
                                 int mode) {
    std::string normPath = normalizePathToStorage(ifs, storageId, path);
    if (normPath.empty()) {
        return -EINVAL;
    }
    return mIncFs->makeDirs(ifs.control, normPath, mode);
}

int IncrementalService::link(StorageId sourceStorageId, std::string_view oldPath,
                             StorageId destStorageId, std::string_view newPath) {
    std::unique_lock l(mLock);
    auto ifsSrc = getIfsLocked(sourceStorageId);
    if (!ifsSrc) {
        return -EINVAL;
    }
    if (sourceStorageId != destStorageId && getIfsLocked(destStorageId) != ifsSrc) {
        return -EINVAL;
    }
    l.unlock();
    std::string normOldPath = normalizePathToStorage(*ifsSrc, sourceStorageId, oldPath);
    std::string normNewPath = normalizePathToStorage(*ifsSrc, destStorageId, newPath);
    if (normOldPath.empty() || normNewPath.empty()) {
        LOG(ERROR) << "Invalid paths in link(): " << normOldPath << " | " << normNewPath;
        return -EINVAL;
    }
    return mIncFs->link(ifsSrc->control, normOldPath, normNewPath);
}

int IncrementalService::unlink(StorageId storage, std::string_view path) {
    if (auto ifs = getIfs(storage)) {
        std::string normOldPath = normalizePathToStorage(*ifs, storage, path);
        return mIncFs->unlink(ifs->control, normOldPath);
    }
    return -EINVAL;
}

int IncrementalService::addBindMount(IncFsMount& ifs, StorageId storage,
                                     std::string_view storageRoot, std::string&& source,
                                     std::string&& target, BindKind kind,
                                     std::unique_lock<std::mutex>& mainLock) {
    if (!isValidMountTarget(target)) {
        LOG(ERROR) << __func__ << ": invalid mount target " << target;
        return -EINVAL;
    }

    std::string mdFileName;
    std::string metadataFullPath;
    if (kind != BindKind::Temporary) {
        metadata::BindPoint bp;
        bp.set_storage_id(storage);
        bp.set_allocated_dest_path(&target);
        bp.set_allocated_source_subdir(&source);
        const auto metadata = bp.SerializeAsString();
        bp.release_dest_path();
        bp.release_source_subdir();
        mdFileName = makeBindMdName();
        metadataFullPath = path::join(ifs.root, constants().mount, mdFileName);
        auto node = mIncFs->makeFile(ifs.control, metadataFullPath, 0444, idFromMetadata(metadata),
                                     {.metadata = {metadata.data(), (IncFsSize)metadata.size()}});
        if (node) {
            LOG(ERROR) << __func__ << ": couldn't create a mount node " << mdFileName;
            return int(node);
        }
    }

    const auto res = addBindMountWithMd(ifs, storage, std::move(mdFileName), std::move(source),
                                        std::move(target), kind, mainLock);
    if (res) {
        mIncFs->unlink(ifs.control, metadataFullPath);
    }
    return res;
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
    addBindMountRecordLocked(ifs, storage, std::move(metadataName), std::move(source),
                             std::move(target), kind);
    return 0;
}

void IncrementalService::addBindMountRecordLocked(IncFsMount& ifs, StorageId storage,
                                                  std::string&& metadataName, std::string&& source,
                                                  std::string&& target, BindKind kind) {
    const auto [it, _] =
            ifs.bindPoints.insert_or_assign(target,
                                            IncFsMount::Bind{storage, std::move(metadataName),
                                                             std::move(source), kind});
    mBindsByPath[std::move(target)] = it;
}

RawMetadata IncrementalService::getMetadata(StorageId storage, std::string_view path) const {
    const auto ifs = getIfs(storage);
    if (!ifs) {
        return {};
    }
    const auto normPath = normalizePathToStorage(*ifs, storage, path);
    if (normPath.empty()) {
        return {};
    }
    return mIncFs->getMetadata(ifs->control, normPath);
}

RawMetadata IncrementalService::getMetadata(StorageId storage, FileId node) const {
    const auto ifs = getIfs(storage);
    if (!ifs) {
        return {};
    }
    return mIncFs->getMetadata(ifs->control, node);
}

bool IncrementalService::startLoading(StorageId storage) const {
    DataLoaderStubPtr dataLoaderStub;
    {
        std::unique_lock l(mLock);
        const auto& ifs = getIfsLocked(storage);
        if (!ifs) {
            return false;
        }
        dataLoaderStub = ifs->dataLoaderStub;
        if (!dataLoaderStub) {
            return false;
        }
    }
    dataLoaderStub->requestStart();
    return true;
}

std::unordered_set<std::string_view> IncrementalService::adoptMountedInstances() {
    std::unordered_set<std::string_view> mountedRootNames;
    mIncFs->listExistingMounts([this, &mountedRootNames](auto root, auto backingDir, auto binds) {
        LOG(INFO) << "Existing mount: " << backingDir << "->" << root;
        for (auto [source, target] : binds) {
            LOG(INFO) << "  bind: '" << source << "'->'" << target << "'";
            LOG(INFO) << "         " << path::join(root, source);
        }

        // Ensure it's a kind of a mount that's managed by IncrementalService
        if (path::basename(root) != constants().mount ||
            path::basename(backingDir) != constants().backing) {
            return;
        }
        const auto expectedRoot = path::dirname(root);
        if (path::dirname(backingDir) != expectedRoot) {
            return;
        }
        if (path::dirname(expectedRoot) != mIncrementalDir) {
            return;
        }
        if (!path::basename(expectedRoot).starts_with(constants().mountKeyPrefix)) {
            return;
        }

        LOG(INFO) << "Looks like an IncrementalService-owned: " << expectedRoot;

        // make sure we clean up the mount if it happens to be a bad one.
        // Note: unmounting needs to run first, so the cleanup object is created _last_.
        auto cleanupFiles = makeCleanup([&]() {
            LOG(INFO) << "Failed to adopt existing mount, deleting files: " << expectedRoot;
            IncFsMount::cleanupFilesystem(expectedRoot);
        });
        auto cleanupMounts = makeCleanup([&]() {
            LOG(INFO) << "Failed to adopt existing mount, cleaning up: " << expectedRoot;
            for (auto&& [_, target] : binds) {
                mVold->unmountIncFs(std::string(target));
            }
            mVold->unmountIncFs(std::string(root));
        });

        auto control = mIncFs->openMount(root);
        if (!control) {
            LOG(INFO) << "failed to open mount " << root;
            return;
        }

        auto mountRecord =
                parseFromIncfs<metadata::Mount>(mIncFs.get(), control,
                                                path::join(root, constants().infoMdName));
        if (!mountRecord.has_loader() || !mountRecord.has_storage()) {
            LOG(ERROR) << "Bad mount metadata in mount at " << expectedRoot;
            return;
        }

        auto mountId = mountRecord.storage().id();
        mNextId = std::max(mNextId, mountId + 1);

        DataLoaderParamsParcel dataLoaderParams;
        {
            const auto& loader = mountRecord.loader();
            dataLoaderParams.type = (content::pm::DataLoaderType)loader.type();
            dataLoaderParams.packageName = loader.package_name();
            dataLoaderParams.className = loader.class_name();
            dataLoaderParams.arguments = loader.arguments();
        }

        auto ifs = std::make_shared<IncFsMount>(std::string(expectedRoot), mountId,
                                                std::move(control), *this);
        cleanupFiles.release(); // ifs will take care of that now

        // Check if marker file present.
        if (checkReadLogsDisabledMarker(root)) {
            ifs->disableReadLogs();
        }

        std::vector<std::pair<std::string, metadata::BindPoint>> permanentBindPoints;
        auto d = openDir(root);
        while (auto e = ::readdir(d.get())) {
            if (e->d_type == DT_REG) {
                auto name = std::string_view(e->d_name);
                if (name.starts_with(constants().mountpointMdPrefix)) {
                    permanentBindPoints
                            .emplace_back(name,
                                          parseFromIncfs<metadata::BindPoint>(mIncFs.get(),
                                                                              ifs->control,
                                                                              path::join(root,
                                                                                         name)));
                    if (permanentBindPoints.back().second.dest_path().empty() ||
                        permanentBindPoints.back().second.source_subdir().empty()) {
                        permanentBindPoints.pop_back();
                        mIncFs->unlink(ifs->control, path::join(root, name));
                    } else {
                        LOG(INFO) << "Permanent bind record: '"
                                  << permanentBindPoints.back().second.source_subdir() << "'->'"
                                  << permanentBindPoints.back().second.dest_path() << "'";
                    }
                }
            } else if (e->d_type == DT_DIR) {
                if (e->d_name == "."sv || e->d_name == ".."sv) {
                    continue;
                }
                auto name = std::string_view(e->d_name);
                if (name.starts_with(constants().storagePrefix)) {
                    int storageId;
                    const auto res =
                            std::from_chars(name.data() + constants().storagePrefix.size() + 1,
                                            name.data() + name.size(), storageId);
                    if (res.ec != std::errc{} || *res.ptr != '_') {
                        LOG(WARNING) << "Ignoring storage with invalid name '" << name
                                     << "' for mount " << expectedRoot;
                        continue;
                    }
                    auto [_, inserted] = mMounts.try_emplace(storageId, ifs);
                    if (!inserted) {
                        LOG(WARNING) << "Ignoring storage with duplicate id " << storageId
                                     << " for mount " << expectedRoot;
                        continue;
                    }
                    ifs->storages.insert_or_assign(storageId,
                                                   IncFsMount::Storage{path::join(root, name)});
                    mNextId = std::max(mNextId, storageId + 1);
                }
            }
        }

        if (ifs->storages.empty()) {
            LOG(WARNING) << "No valid storages in mount " << root;
            return;
        }

        // now match the mounted directories with what we expect to have in the metadata
        {
            std::unique_lock l(mLock, std::defer_lock);
            for (auto&& [metadataFile, bindRecord] : permanentBindPoints) {
                auto mountedIt = std::find_if(binds.begin(), binds.end(),
                                              [&, bindRecord = bindRecord](auto&& bind) {
                                                  return bind.second == bindRecord.dest_path() &&
                                                          path::join(root, bind.first) ==
                                                          bindRecord.source_subdir();
                                              });
                if (mountedIt != binds.end()) {
                    LOG(INFO) << "Matched permanent bound " << bindRecord.source_subdir()
                              << " to mount " << mountedIt->first;
                    addBindMountRecordLocked(*ifs, bindRecord.storage_id(), std::move(metadataFile),
                                             std::move(*bindRecord.mutable_source_subdir()),
                                             std::move(*bindRecord.mutable_dest_path()),
                                             BindKind::Permanent);
                    if (mountedIt != binds.end() - 1) {
                        std::iter_swap(mountedIt, binds.end() - 1);
                    }
                    binds = binds.first(binds.size() - 1);
                } else {
                    LOG(INFO) << "Didn't match permanent bound " << bindRecord.source_subdir()
                              << ", mounting";
                    // doesn't exist - try mounting back
                    if (addBindMountWithMd(*ifs, bindRecord.storage_id(), std::move(metadataFile),
                                           std::move(*bindRecord.mutable_source_subdir()),
                                           std::move(*bindRecord.mutable_dest_path()),
                                           BindKind::Permanent, l)) {
                        mIncFs->unlink(ifs->control, metadataFile);
                    }
                }
            }
        }

        // if anything stays in |binds| those are probably temporary binds; system restarted since
        // they were mounted - so let's unmount them all.
        for (auto&& [source, target] : binds) {
            if (source.empty()) {
                continue;
            }
            mVold->unmountIncFs(std::string(target));
        }
        cleanupMounts.release(); // ifs now manages everything

        if (ifs->bindPoints.empty()) {
            LOG(WARNING) << "No valid bind points for mount " << expectedRoot;
            deleteStorage(*ifs);
            return;
        }

        prepareDataLoaderLocked(*ifs, std::move(dataLoaderParams));
        CHECK(ifs->dataLoaderStub);

        mountedRootNames.insert(path::basename(ifs->root));

        // not locking here at all: we're still in the constructor, no other calls can happen
        mMounts[ifs->mountId] = std::move(ifs);
    });

    return mountedRootNames;
}

void IncrementalService::mountExistingImages(
        const std::unordered_set<std::string_view>& mountedRootNames) {
    auto dir = openDir(mIncrementalDir);
    if (!dir) {
        PLOG(WARNING) << "Couldn't open the root incremental dir " << mIncrementalDir;
        return;
    }
    while (auto entry = ::readdir(dir.get())) {
        if (entry->d_type != DT_DIR) {
            continue;
        }
        std::string_view name = entry->d_name;
        if (!name.starts_with(constants().mountKeyPrefix)) {
            continue;
        }
        if (mountedRootNames.find(name) != mountedRootNames.end()) {
            continue;
        }
        const auto root = path::join(mIncrementalDir, name);
        if (!mountExistingImage(root)) {
            IncFsMount::cleanupFilesystem(root);
        }
    }
}

bool IncrementalService::mountExistingImage(std::string_view root) {
    auto mountTarget = path::join(root, constants().mount);
    const auto backing = path::join(root, constants().backing);

    IncrementalFileSystemControlParcel controlParcel;
    auto status = mVold->mountIncFs(backing, mountTarget, 0, &controlParcel);
    if (!status.isOk()) {
        LOG(ERROR) << "Vold::mountIncFs() failed: " << status.toString8();
        return false;
    }

    int cmd = controlParcel.cmd.release().release();
    int pendingReads = controlParcel.pendingReads.release().release();
    int logs = controlParcel.log.release().release();
    IncFsMount::Control control = mIncFs->createControl(cmd, pendingReads, logs);

    auto ifs = std::make_shared<IncFsMount>(std::string(root), -1, std::move(control), *this);

    auto mount = parseFromIncfs<metadata::Mount>(mIncFs.get(), ifs->control,
                                                 path::join(mountTarget, constants().infoMdName));
    if (!mount.has_loader() || !mount.has_storage()) {
        LOG(ERROR) << "Bad mount metadata in mount at " << root;
        return false;
    }

    ifs->mountId = mount.storage().id();
    mNextId = std::max(mNextId, ifs->mountId + 1);

    // Check if marker file present.
    if (checkReadLogsDisabledMarker(mountTarget)) {
        ifs->disableReadLogs();
    }

    // DataLoader params
    DataLoaderParamsParcel dataLoaderParams;
    {
        const auto& loader = mount.loader();
        dataLoaderParams.type = (content::pm::DataLoaderType)loader.type();
        dataLoaderParams.packageName = loader.package_name();
        dataLoaderParams.className = loader.class_name();
        dataLoaderParams.arguments = loader.arguments();
    }

    prepareDataLoader(*ifs, std::move(dataLoaderParams));
    CHECK(ifs->dataLoaderStub);

    std::vector<std::pair<std::string, metadata::BindPoint>> bindPoints;
    auto d = openDir(mountTarget);
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
                int storageId;
                const auto res = std::from_chars(name.data() + constants().storagePrefix.size() + 1,
                                                 name.data() + name.size(), storageId);
                if (res.ec != std::errc{} || *res.ptr != '_') {
                    LOG(WARNING) << "Ignoring storage with invalid name '" << name << "' for mount "
                                 << root;
                    continue;
                }
                auto [_, inserted] = mMounts.try_emplace(storageId, ifs);
                if (!inserted) {
                    LOG(WARNING) << "Ignoring storage with duplicate id " << storageId
                                 << " for mount " << root;
                    continue;
                }
                ifs->storages.insert_or_assign(storageId,
                                               IncFsMount::Storage{
                                                       path::join(root, constants().mount, name)});
                mNextId = std::max(mNextId, storageId + 1);
            }
        }
    }

    if (ifs->storages.empty()) {
        LOG(WARNING) << "No valid storages in mount " << root;
        return false;
    }

    int bindCount = 0;
    {
        std::unique_lock l(mLock, std::defer_lock);
        for (auto&& bp : bindPoints) {
            bindCount += !addBindMountWithMd(*ifs, bp.second.storage_id(), std::move(bp.first),
                                             std::move(*bp.second.mutable_source_subdir()),
                                             std::move(*bp.second.mutable_dest_path()),
                                             BindKind::Permanent, l);
        }
    }

    if (bindCount == 0) {
        LOG(WARNING) << "No valid bind points for mount " << root;
        deleteStorage(*ifs);
        return false;
    }

    // not locking here at all: we're still in the constructor, no other calls can happen
    mMounts[ifs->mountId] = std::move(ifs);
    return true;
}

void IncrementalService::runCmdLooper() {
    constexpr auto kTimeoutMsecs = -1;
    while (mRunning.load(std::memory_order_relaxed)) {
        mLooper->pollAll(kTimeoutMsecs);
    }
}

IncrementalService::DataLoaderStubPtr IncrementalService::prepareDataLoader(
        IncFsMount& ifs, DataLoaderParamsParcel&& params,
        const DataLoaderStatusListener* statusListener,
        StorageHealthCheckParams&& healthCheckParams, const StorageHealthListener* healthListener) {
    std::unique_lock l(ifs.lock);
    prepareDataLoaderLocked(ifs, std::move(params), statusListener, std::move(healthCheckParams),
                            healthListener);
    return ifs.dataLoaderStub;
}

void IncrementalService::prepareDataLoaderLocked(IncFsMount& ifs, DataLoaderParamsParcel&& params,
                                                 const DataLoaderStatusListener* statusListener,
                                                 StorageHealthCheckParams&& healthCheckParams,
                                                 const StorageHealthListener* healthListener) {
    if (ifs.dataLoaderStub) {
        LOG(INFO) << "Skipped data loader preparation because it already exists";
        return;
    }

    FileSystemControlParcel fsControlParcel;
    fsControlParcel.incremental = aidl::make_nullable<IncrementalFileSystemControlParcel>();
    fsControlParcel.incremental->cmd.reset(dup(ifs.control.cmd()));
    fsControlParcel.incremental->pendingReads.reset(dup(ifs.control.pendingReads()));
    fsControlParcel.incremental->log.reset(dup(ifs.control.logs()));
    fsControlParcel.service = new IncrementalServiceConnector(*this, ifs.mountId);

    ifs.dataLoaderStub =
            new DataLoaderStub(*this, ifs.mountId, std::move(params), std::move(fsControlParcel),
                               statusListener, std::move(healthCheckParams), healthListener,
                               path::join(ifs.root, constants().mount));
}

template <class Duration>
static long elapsedMcs(Duration start, Duration end) {
    return std::chrono::duration_cast<std::chrono::microseconds>(end - start).count();
}

// Extract lib files from zip, create new files in incfs and write data to them
// Lib files should be placed next to the APK file in the following matter:
// Example:
// /path/to/base.apk
// /path/to/lib/arm/first.so
// /path/to/lib/arm/second.so
bool IncrementalService::configureNativeBinaries(StorageId storage, std::string_view apkFullPath,
                                                 std::string_view libDirRelativePath,
                                                 std::string_view abi, bool extractNativeLibs) {
    auto start = Clock::now();

    const auto ifs = getIfs(storage);
    if (!ifs) {
        LOG(ERROR) << "Invalid storage " << storage;
        return false;
    }

    const auto targetLibPathRelativeToStorage =
            path::join(path::dirname(normalizePathToStorage(*ifs, storage, apkFullPath)),
                       libDirRelativePath);

    // First prepare target directories if they don't exist yet
    if (auto res = makeDirs(*ifs, storage, targetLibPathRelativeToStorage, 0755)) {
        LOG(ERROR) << "Failed to prepare target lib directory " << targetLibPathRelativeToStorage
                   << " errno: " << res;
        return false;
    }

    auto mkDirsTs = Clock::now();
    ZipArchiveHandle zipFileHandle;
    if (OpenArchive(path::c_str(apkFullPath), &zipFileHandle)) {
        LOG(ERROR) << "Failed to open zip file at " << apkFullPath;
        return false;
    }

    // Need a shared pointer: will be passing it into all unpacking jobs.
    std::shared_ptr<ZipArchive> zipFile(zipFileHandle, [](ZipArchiveHandle h) { CloseArchive(h); });
    void* cookie = nullptr;
    const auto libFilePrefix = path::join(constants().libDir, abi);
    if (StartIteration(zipFile.get(), &cookie, libFilePrefix, constants().libSuffix)) {
        LOG(ERROR) << "Failed to start zip iteration for " << apkFullPath;
        return false;
    }
    auto endIteration = [](void* cookie) { EndIteration(cookie); };
    auto iterationCleaner = std::unique_ptr<void, decltype(endIteration)>(cookie, endIteration);

    auto openZipTs = Clock::now();

    std::vector<Job> jobQueue;
    ZipEntry entry;
    std::string_view fileName;
    while (!Next(cookie, &entry, &fileName)) {
        if (fileName.empty()) {
            continue;
        }

        if (!extractNativeLibs) {
            // ensure the file is properly aligned and unpacked
            if (entry.method != kCompressStored) {
                LOG(WARNING) << "Library " << fileName << " must be uncompressed to mmap it";
                return false;
            }
            if ((entry.offset & (constants().blockSize - 1)) != 0) {
                LOG(WARNING) << "Library " << fileName
                             << " must be page-aligned to mmap it, offset = 0x" << std::hex
                             << entry.offset;
                return false;
            }
            continue;
        }

        auto startFileTs = Clock::now();

        const auto libName = path::basename(fileName);
        auto targetLibPath = path::join(targetLibPathRelativeToStorage, libName);
        const auto targetLibPathAbsolute = normalizePathToStorage(*ifs, storage, targetLibPath);
        // If the extract file already exists, skip
        if (access(targetLibPathAbsolute.c_str(), F_OK) == 0) {
            if (perfLoggingEnabled()) {
                LOG(INFO) << "incfs: Native lib file already exists: " << targetLibPath
                          << "; skipping extraction, spent "
                          << elapsedMcs(startFileTs, Clock::now()) << "mcs";
            }
            continue;
        }

        // Create new lib file without signature info
        incfs::NewFileParams libFileParams = {
                .size = entry.uncompressed_length,
                .signature = {},
                // Metadata of the new lib file is its relative path
                .metadata = {targetLibPath.c_str(), (IncFsSize)targetLibPath.size()},
        };
        incfs::FileId libFileId = idFromMetadata(targetLibPath);
        if (auto res = mIncFs->makeFile(ifs->control, targetLibPathAbsolute, 0777, libFileId,
                                        libFileParams)) {
            LOG(ERROR) << "Failed to make file for: " << targetLibPath << " errno: " << res;
            // If one lib file fails to be created, abort others as well
            return false;
        }

        auto makeFileTs = Clock::now();

        // If it is a zero-byte file, skip data writing
        if (entry.uncompressed_length == 0) {
            if (perfLoggingEnabled()) {
                LOG(INFO) << "incfs: Extracted " << libName
                          << "(0 bytes): " << elapsedMcs(startFileTs, makeFileTs) << "mcs";
            }
            continue;
        }

        jobQueue.emplace_back([this, zipFile, entry, ifs = std::weak_ptr<IncFsMount>(ifs),
                               libFileId, libPath = std::move(targetLibPath),
                               makeFileTs]() mutable {
            extractZipFile(ifs.lock(), zipFile.get(), entry, libFileId, libPath, makeFileTs);
        });

        if (perfLoggingEnabled()) {
            auto prepareJobTs = Clock::now();
            LOG(INFO) << "incfs: Processed " << libName << ": "
                      << elapsedMcs(startFileTs, prepareJobTs)
                      << "mcs, make file: " << elapsedMcs(startFileTs, makeFileTs)
                      << " prepare job: " << elapsedMcs(makeFileTs, prepareJobTs);
        }
    }

    auto processedTs = Clock::now();

    if (!jobQueue.empty()) {
        {
            std::lock_guard lock(mJobMutex);
            if (mRunning) {
                auto& existingJobs = mJobQueue[ifs->mountId];
                if (existingJobs.empty()) {
                    existingJobs = std::move(jobQueue);
                } else {
                    existingJobs.insert(existingJobs.end(), std::move_iterator(jobQueue.begin()),
                                        std::move_iterator(jobQueue.end()));
                }
            }
        }
        mJobCondition.notify_all();
    }

    if (perfLoggingEnabled()) {
        auto end = Clock::now();
        LOG(INFO) << "incfs: configureNativeBinaries complete in " << elapsedMcs(start, end)
                  << "mcs, make dirs: " << elapsedMcs(start, mkDirsTs)
                  << " open zip: " << elapsedMcs(mkDirsTs, openZipTs)
                  << " make files: " << elapsedMcs(openZipTs, processedTs)
                  << " schedule jobs: " << elapsedMcs(processedTs, end);
    }

    return true;
}

void IncrementalService::extractZipFile(const IfsMountPtr& ifs, ZipArchiveHandle zipFile,
                                        ZipEntry& entry, const incfs::FileId& libFileId,
                                        std::string_view targetLibPath,
                                        Clock::time_point scheduledTs) {
    if (!ifs) {
        LOG(INFO) << "Skipping zip file " << targetLibPath << " extraction for an expired mount";
        return;
    }

    auto libName = path::basename(targetLibPath);
    auto startedTs = Clock::now();

    // Write extracted data to new file
    // NOTE: don't zero-initialize memory, it may take a while for nothing
    auto libData = std::unique_ptr<uint8_t[]>(new uint8_t[entry.uncompressed_length]);
    if (ExtractToMemory(zipFile, &entry, libData.get(), entry.uncompressed_length)) {
        LOG(ERROR) << "Failed to extract native lib zip entry: " << libName;
        return;
    }

    auto extractFileTs = Clock::now();

    const auto writeFd = mIncFs->openForSpecialOps(ifs->control, libFileId);
    if (!writeFd.ok()) {
        LOG(ERROR) << "Failed to open write fd for: " << targetLibPath << " errno: " << writeFd;
        return;
    }

    auto openFileTs = Clock::now();
    const int numBlocks =
            (entry.uncompressed_length + constants().blockSize - 1) / constants().blockSize;
    std::vector<IncFsDataBlock> instructions(numBlocks);
    auto remainingData = std::span(libData.get(), entry.uncompressed_length);
    for (int i = 0; i < numBlocks; i++) {
        const auto blockSize = std::min<long>(constants().blockSize, remainingData.size());
        instructions[i] = IncFsDataBlock{
                .fileFd = writeFd.get(),
                .pageIndex = static_cast<IncFsBlockIndex>(i),
                .compression = INCFS_COMPRESSION_KIND_NONE,
                .kind = INCFS_BLOCK_KIND_DATA,
                .dataSize = static_cast<uint32_t>(blockSize),
                .data = reinterpret_cast<const char*>(remainingData.data()),
        };
        remainingData = remainingData.subspan(blockSize);
    }
    auto prepareInstsTs = Clock::now();

    size_t res = mIncFs->writeBlocks(instructions);
    if (res != instructions.size()) {
        LOG(ERROR) << "Failed to write data into: " << targetLibPath;
        return;
    }

    if (perfLoggingEnabled()) {
        auto endFileTs = Clock::now();
        LOG(INFO) << "incfs: Extracted " << libName << "(" << entry.compressed_length << " -> "
                  << entry.uncompressed_length << " bytes): " << elapsedMcs(startedTs, endFileTs)
                  << "mcs, scheduling delay: " << elapsedMcs(scheduledTs, startedTs)
                  << " extract: " << elapsedMcs(startedTs, extractFileTs)
                  << " open: " << elapsedMcs(extractFileTs, openFileTs)
                  << " prepare: " << elapsedMcs(openFileTs, prepareInstsTs)
                  << " write: " << elapsedMcs(prepareInstsTs, endFileTs);
    }
}

bool IncrementalService::waitForNativeBinariesExtraction(StorageId storage) {
    struct WaitPrinter {
        const Clock::time_point startTs = Clock::now();
        ~WaitPrinter() noexcept {
            if (perfLoggingEnabled()) {
                const auto endTs = Clock::now();
                LOG(INFO) << "incfs: waitForNativeBinariesExtraction() complete in "
                          << elapsedMcs(startTs, endTs) << "mcs";
            }
        }
    } waitPrinter;

    MountId mount;
    {
        auto ifs = getIfs(storage);
        if (!ifs) {
            return true;
        }
        mount = ifs->mountId;
    }

    std::unique_lock lock(mJobMutex);
    mJobCondition.wait(lock, [this, mount] {
        return !mRunning ||
                (mPendingJobsMount != mount && mJobQueue.find(mount) == mJobQueue.end());
    });
    return mRunning;
}

bool IncrementalService::perfLoggingEnabled() {
    static const bool enabled = base::GetBoolProperty("incremental.perflogging", false);
    return enabled;
}

void IncrementalService::runJobProcessing() {
    for (;;) {
        std::unique_lock lock(mJobMutex);
        mJobCondition.wait(lock, [this]() { return !mRunning || !mJobQueue.empty(); });
        if (!mRunning) {
            return;
        }

        auto it = mJobQueue.begin();
        mPendingJobsMount = it->first;
        auto queue = std::move(it->second);
        mJobQueue.erase(it);
        lock.unlock();

        for (auto&& job : queue) {
            job();
        }

        lock.lock();
        mPendingJobsMount = kInvalidStorageId;
        lock.unlock();
        mJobCondition.notify_all();
    }
}

void IncrementalService::registerAppOpsCallback(const std::string& packageName) {
    sp<IAppOpsCallback> listener;
    {
        std::unique_lock lock{mCallbacksLock};
        auto& cb = mCallbackRegistered[packageName];
        if (cb) {
            return;
        }
        cb = new AppOpsListener(*this, packageName);
        listener = cb;
    }

    mAppOpsManager->startWatchingMode(AppOpsManager::OP_GET_USAGE_STATS,
                                      String16(packageName.c_str()), listener);
}

bool IncrementalService::unregisterAppOpsCallback(const std::string& packageName) {
    sp<IAppOpsCallback> listener;
    {
        std::unique_lock lock{mCallbacksLock};
        auto found = mCallbackRegistered.find(packageName);
        if (found == mCallbackRegistered.end()) {
            return false;
        }
        listener = found->second;
        mCallbackRegistered.erase(found);
    }

    mAppOpsManager->stopWatchingMode(listener);
    return true;
}

void IncrementalService::onAppOpChanged(const std::string& packageName) {
    if (!unregisterAppOpsCallback(packageName)) {
        return;
    }

    std::vector<IfsMountPtr> affected;
    {
        std::lock_guard l(mLock);
        affected.reserve(mMounts.size());
        for (auto&& [id, ifs] : mMounts) {
            if (ifs->mountId == id && ifs->dataLoaderStub->params().packageName == packageName) {
                affected.push_back(ifs);
            }
        }
    }
    for (auto&& ifs : affected) {
        applyStorageParams(*ifs, false);
    }
}

void IncrementalService::addTimedJob(MountId id, Milliseconds after, Job what) {
    if (id == kInvalidStorageId) {
        return;
    }
    mTimedQueue->addJob(id, after, std::move(what));
}

void IncrementalService::removeTimedJobs(MountId id) {
    if (id == kInvalidStorageId) {
        return;
    }
    mTimedQueue->removeJobs(id);
}

IncrementalService::DataLoaderStub::DataLoaderStub(IncrementalService& service, MountId id,
                                                   DataLoaderParamsParcel&& params,
                                                   FileSystemControlParcel&& control,
                                                   const DataLoaderStatusListener* statusListener,
                                                   StorageHealthCheckParams&& healthCheckParams,
                                                   const StorageHealthListener* healthListener,
                                                   std::string&& healthPath)
      : mService(service),
        mId(id),
        mParams(std::move(params)),
        mControl(std::move(control)),
        mStatusListener(statusListener ? *statusListener : DataLoaderStatusListener()),
        mHealthListener(healthListener ? *healthListener : StorageHealthListener()),
        mHealthPath(std::move(healthPath)),
        mHealthCheckParams(std::move(healthCheckParams)) {
    if (mHealthListener) {
        if (!isHealthParamsValid()) {
            mHealthListener = {};
        }
    } else {
        // Disable advanced health check statuses.
        mHealthCheckParams.blockedTimeoutMs = -1;
    }
    updateHealthStatus();
}

IncrementalService::DataLoaderStub::~DataLoaderStub() {
    if (isValid()) {
        cleanupResources();
    }
}

void IncrementalService::DataLoaderStub::cleanupResources() {
    auto now = Clock::now();
    {
        std::unique_lock lock(mMutex);
        mHealthPath.clear();
        unregisterFromPendingReads();
        resetHealthControl();
        mService.removeTimedJobs(mId);
    }

    requestDestroy();

    {
        std::unique_lock lock(mMutex);
        mParams = {};
        mControl = {};
        mHealthControl = {};
        mHealthListener = {};
        mStatusCondition.wait_until(lock, now + 60s, [this] {
            return mCurrentStatus == IDataLoaderStatusListener::DATA_LOADER_DESTROYED;
        });
        mStatusListener = {};
        mId = kInvalidStorageId;
    }
}

sp<content::pm::IDataLoader> IncrementalService::DataLoaderStub::getDataLoader() {
    sp<IDataLoader> dataloader;
    auto status = mService.mDataLoaderManager->getDataLoader(id(), &dataloader);
    if (!status.isOk()) {
        LOG(ERROR) << "Failed to get dataloader: " << status.toString8();
        return {};
    }
    if (!dataloader) {
        LOG(ERROR) << "DataLoader is null: " << status.toString8();
        return {};
    }
    return dataloader;
}

bool IncrementalService::DataLoaderStub::requestCreate() {
    return setTargetStatus(IDataLoaderStatusListener::DATA_LOADER_CREATED);
}

bool IncrementalService::DataLoaderStub::requestStart() {
    return setTargetStatus(IDataLoaderStatusListener::DATA_LOADER_STARTED);
}

bool IncrementalService::DataLoaderStub::requestDestroy() {
    return setTargetStatus(IDataLoaderStatusListener::DATA_LOADER_DESTROYED);
}

bool IncrementalService::DataLoaderStub::setTargetStatus(int newStatus) {
    {
        std::unique_lock lock(mMutex);
        setTargetStatusLocked(newStatus);
    }
    return fsmStep();
}

void IncrementalService::DataLoaderStub::setTargetStatusLocked(int status) {
    auto oldStatus = mTargetStatus;
    mTargetStatus = status;
    mTargetStatusTs = Clock::now();
    LOG(DEBUG) << "Target status update for DataLoader " << id() << ": " << oldStatus << " -> "
               << status << " (current " << mCurrentStatus << ")";
}

bool IncrementalService::DataLoaderStub::bind() {
    bool result = false;
    auto status = mService.mDataLoaderManager->bindToDataLoader(id(), mParams, this, &result);
    if (!status.isOk() || !result) {
        LOG(ERROR) << "Failed to bind a data loader for mount " << id();
        return false;
    }
    return true;
}

bool IncrementalService::DataLoaderStub::create() {
    auto dataloader = getDataLoader();
    if (!dataloader) {
        return false;
    }
    auto status = dataloader->create(id(), mParams, mControl, this);
    if (!status.isOk()) {
        LOG(ERROR) << "Failed to create DataLoader: " << status.toString8();
        return false;
    }
    return true;
}

bool IncrementalService::DataLoaderStub::start() {
    auto dataloader = getDataLoader();
    if (!dataloader) {
        return false;
    }
    auto status = dataloader->start(id());
    if (!status.isOk()) {
        LOG(ERROR) << "Failed to start DataLoader: " << status.toString8();
        return false;
    }
    return true;
}

bool IncrementalService::DataLoaderStub::destroy() {
    return mService.mDataLoaderManager->unbindFromDataLoader(id()).isOk();
}

bool IncrementalService::DataLoaderStub::fsmStep() {
    if (!isValid()) {
        return false;
    }

    int currentStatus;
    int targetStatus;
    {
        std::unique_lock lock(mMutex);
        currentStatus = mCurrentStatus;
        targetStatus = mTargetStatus;
    }

    LOG(DEBUG) << "fsmStep: " << id() << ": " << currentStatus << " -> " << targetStatus;

    if (currentStatus == targetStatus) {
        return true;
    }

    switch (targetStatus) {
        case IDataLoaderStatusListener::DATA_LOADER_UNAVAILABLE:
            // Do nothing, this is a reset state.
            break;
        case IDataLoaderStatusListener::DATA_LOADER_DESTROYED: {
            return destroy();
        }
        case IDataLoaderStatusListener::DATA_LOADER_STARTED: {
            switch (currentStatus) {
                case IDataLoaderStatusListener::DATA_LOADER_CREATED:
                case IDataLoaderStatusListener::DATA_LOADER_STOPPED:
                    return start();
            }
            [[fallthrough]];
        }
        case IDataLoaderStatusListener::DATA_LOADER_CREATED:
            switch (currentStatus) {
                case IDataLoaderStatusListener::DATA_LOADER_DESTROYED:
                case IDataLoaderStatusListener::DATA_LOADER_UNAVAILABLE:
                    return bind();
                case IDataLoaderStatusListener::DATA_LOADER_BOUND:
                    return create();
            }
            break;
        default:
            LOG(ERROR) << "Invalid target status: " << targetStatus
                       << ", current status: " << currentStatus;
            break;
    }
    return false;
}

binder::Status IncrementalService::DataLoaderStub::onStatusChanged(MountId mountId, int newStatus) {
    if (!isValid()) {
        return binder::Status::
                fromServiceSpecificError(-EINVAL, "onStatusChange came to invalid DataLoaderStub");
    }
    if (id() != mountId) {
        LOG(ERROR) << "Mount ID mismatch: expected " << id() << ", but got: " << mountId;
        return binder::Status::fromServiceSpecificError(-EPERM, "Mount ID mismatch.");
    }

    int targetStatus, oldStatus;
    DataLoaderStatusListener listener;
    {
        std::unique_lock lock(mMutex);
        if (mCurrentStatus == newStatus) {
            return binder::Status::ok();
        }

        oldStatus = mCurrentStatus;
        mCurrentStatus = newStatus;
        targetStatus = mTargetStatus;

        listener = mStatusListener;

        if (mCurrentStatus == IDataLoaderStatusListener::DATA_LOADER_UNAVAILABLE) {
            // For unavailable, unbind from DataLoader to ensure proper re-commit.
            setTargetStatusLocked(IDataLoaderStatusListener::DATA_LOADER_DESTROYED);
        }
    }

    LOG(DEBUG) << "Current status update for DataLoader " << id() << ": " << oldStatus << " -> "
               << newStatus << " (target " << targetStatus << ")";

    if (listener) {
        listener->onStatusChanged(mountId, newStatus);
    }

    fsmStep();

    mStatusCondition.notify_all();

    return binder::Status::ok();
}

bool IncrementalService::DataLoaderStub::isHealthParamsValid() const {
    return mHealthCheckParams.blockedTimeoutMs > 0 &&
            mHealthCheckParams.blockedTimeoutMs < mHealthCheckParams.unhealthyTimeoutMs;
}

void IncrementalService::DataLoaderStub::onHealthStatus(StorageHealthListener healthListener,
                                                        int healthStatus) {
    LOG(DEBUG) << id() << ": healthStatus: " << healthStatus;
    if (healthListener) {
        healthListener->onHealthStatus(id(), healthStatus);
    }
}

void IncrementalService::DataLoaderStub::updateHealthStatus(bool baseline) {
    LOG(DEBUG) << id() << ": updateHealthStatus" << (baseline ? " (baseline)" : "");

    int healthStatusToReport = -1;
    StorageHealthListener healthListener;

    {
        std::unique_lock lock(mMutex);
        unregisterFromPendingReads();

        healthListener = mHealthListener;

        // Healthcheck depends on timestamp of the oldest pending read.
        // To get it, we need to re-open a pendingReads FD to get a full list of reads.
        // Additionally we need to re-register for epoll with fresh FDs in case there are no reads.
        const auto now = Clock::now();
        const auto kernelTsUs = getOldestPendingReadTs();
        if (baseline) {
            // Updating baseline only on looper/epoll callback, i.e. on new set of pending reads.
            mHealthBase = {now, kernelTsUs};
        }

        if (kernelTsUs == kMaxBootClockTsUs || mHealthBase.kernelTsUs == kMaxBootClockTsUs ||
            mHealthBase.userTs > now) {
            LOG(DEBUG) << id() << ": No pending reads or invalid base, report Ok and wait.";
            registerForPendingReads();
            healthStatusToReport = IStorageHealthListener::HEALTH_STATUS_OK;
            lock.unlock();
            onHealthStatus(healthListener, healthStatusToReport);
            return;
        }

        resetHealthControl();

        // Always make sure the data loader is started.
        setTargetStatusLocked(IDataLoaderStatusListener::DATA_LOADER_STARTED);

        // Skip any further processing if health check params are invalid.
        if (!isHealthParamsValid()) {
            LOG(DEBUG) << id()
                       << ": Skip any further processing if health check params are invalid.";
            healthStatusToReport = IStorageHealthListener::HEALTH_STATUS_READS_PENDING;
            lock.unlock();
            onHealthStatus(healthListener, healthStatusToReport);
            // Triggering data loader start. This is a one-time action.
            fsmStep();
            return;
        }

        // Don't schedule timer job less than 500ms in advance.
        static constexpr auto kTolerance = 500ms;

        const auto blockedTimeout = std::chrono::milliseconds(mHealthCheckParams.blockedTimeoutMs);
        const auto unhealthyTimeout =
                std::chrono::milliseconds(mHealthCheckParams.unhealthyTimeoutMs);
        const auto unhealthyMonitoring =
                std::max(1000ms,
                         std::chrono::milliseconds(mHealthCheckParams.unhealthyMonitoringMs));

        const auto kernelDeltaUs = kernelTsUs - mHealthBase.kernelTsUs;
        const auto userTs = mHealthBase.userTs + std::chrono::microseconds(kernelDeltaUs);
        const auto delta = std::chrono::duration_cast<std::chrono::milliseconds>(now - userTs);

        Milliseconds checkBackAfter;
        if (delta + kTolerance < blockedTimeout) {
            LOG(DEBUG) << id() << ": Report reads pending and wait for blocked status.";
            checkBackAfter = blockedTimeout - delta;
            healthStatusToReport = IStorageHealthListener::HEALTH_STATUS_READS_PENDING;
        } else if (delta + kTolerance < unhealthyTimeout) {
            LOG(DEBUG) << id() << ": Report blocked and wait for unhealthy.";
            checkBackAfter = unhealthyTimeout - delta;
            healthStatusToReport = IStorageHealthListener::HEALTH_STATUS_BLOCKED;
        } else {
            LOG(DEBUG) << id() << ": Report unhealthy and continue monitoring.";
            checkBackAfter = unhealthyMonitoring;
            healthStatusToReport = IStorageHealthListener::HEALTH_STATUS_UNHEALTHY;
        }
        LOG(DEBUG) << id() << ": updateHealthStatus in " << double(checkBackAfter.count()) / 1000.0
                   << "secs";
        mService.addTimedJob(id(), checkBackAfter, [this]() { updateHealthStatus(); });
    }

    // With kTolerance we are expecting these to execute before the next update.
    if (healthStatusToReport != -1) {
        onHealthStatus(healthListener, healthStatusToReport);
    }

    fsmStep();
}

const incfs::UniqueControl& IncrementalService::DataLoaderStub::initializeHealthControl() {
    if (mHealthPath.empty()) {
        resetHealthControl();
        return mHealthControl;
    }
    if (mHealthControl.pendingReads() < 0) {
        mHealthControl = mService.mIncFs->openMount(mHealthPath);
    }
    if (mHealthControl.pendingReads() < 0) {
        LOG(ERROR) << "Failed to open health control for: " << id() << ", path: " << mHealthPath
                   << "(" << mHealthControl.cmd() << ":" << mHealthControl.pendingReads() << ":"
                   << mHealthControl.logs() << ")";
    }
    return mHealthControl;
}

void IncrementalService::DataLoaderStub::resetHealthControl() {
    mHealthControl = {};
}

BootClockTsUs IncrementalService::DataLoaderStub::getOldestPendingReadTs() {
    auto result = kMaxBootClockTsUs;

    const auto& control = initializeHealthControl();
    if (control.pendingReads() < 0) {
        return result;
    }

    std::vector<incfs::ReadInfo> pendingReads;
    if (mService.mIncFs->waitForPendingReads(control, 0ms, &pendingReads) !=
                android::incfs::WaitResult::HaveData ||
        pendingReads.empty()) {
        return result;
    }

    LOG(DEBUG) << id() << ": pendingReads: " << control.pendingReads() << ", "
               << pendingReads.size() << ": " << pendingReads.front().bootClockTsUs;

    for (auto&& pendingRead : pendingReads) {
        result = std::min(result, pendingRead.bootClockTsUs);
    }
    return result;
}

void IncrementalService::DataLoaderStub::registerForPendingReads() {
    const auto pendingReadsFd = mHealthControl.pendingReads();
    if (pendingReadsFd < 0) {
        return;
    }

    LOG(DEBUG) << id() << ": addFd(pendingReadsFd): " << pendingReadsFd;

    mService.mLooper->addFd(
            pendingReadsFd, android::Looper::POLL_CALLBACK, android::Looper::EVENT_INPUT,
            [](int, int, void* data) -> int {
                auto&& self = (DataLoaderStub*)data;
                self->updateHealthStatus(/*baseline=*/true);
                return 0;
            },
            this);
    mService.mLooper->wake();
}

void IncrementalService::DataLoaderStub::unregisterFromPendingReads() {
    const auto pendingReadsFd = mHealthControl.pendingReads();
    if (pendingReadsFd < 0) {
        return;
    }

    LOG(DEBUG) << id() << ": removeFd(pendingReadsFd): " << pendingReadsFd;

    mService.mLooper->removeFd(pendingReadsFd);
    mService.mLooper->wake();
}

void IncrementalService::DataLoaderStub::onDump(int fd) {
    dprintf(fd, "    dataLoader: {\n");
    dprintf(fd, "      currentStatus: %d\n", mCurrentStatus);
    dprintf(fd, "      targetStatus: %d\n", mTargetStatus);
    dprintf(fd, "      targetStatusTs: %lldmcs\n",
            (long long)(elapsedMcs(mTargetStatusTs, Clock::now())));
    dprintf(fd, "      health: {\n");
    dprintf(fd, "        path: %s\n", mHealthPath.c_str());
    dprintf(fd, "        base: %lldmcs (%lld)\n",
            (long long)(elapsedMcs(mHealthBase.userTs, Clock::now())),
            (long long)mHealthBase.kernelTsUs);
    dprintf(fd, "        blockedTimeoutMs: %d\n", int(mHealthCheckParams.blockedTimeoutMs));
    dprintf(fd, "        unhealthyTimeoutMs: %d\n", int(mHealthCheckParams.unhealthyTimeoutMs));
    dprintf(fd, "        unhealthyMonitoringMs: %d\n",
            int(mHealthCheckParams.unhealthyMonitoringMs));
    dprintf(fd, "      }\n");
    const auto& params = mParams;
    dprintf(fd, "      dataLoaderParams: {\n");
    dprintf(fd, "        type: %s\n", toString(params.type).c_str());
    dprintf(fd, "        packageName: %s\n", params.packageName.c_str());
    dprintf(fd, "        className: %s\n", params.className.c_str());
    dprintf(fd, "        arguments: %s\n", params.arguments.c_str());
    dprintf(fd, "      }\n");
    dprintf(fd, "    }\n");
}

void IncrementalService::AppOpsListener::opChanged(int32_t, const String16&) {
    incrementalService.onAppOpChanged(packageName);
}

binder::Status IncrementalService::IncrementalServiceConnector::setStorageParams(
        bool enableReadLogs, int32_t* _aidl_return) {
    *_aidl_return = incrementalService.setStorageParams(storage, enableReadLogs);
    return binder::Status::ok();
}

FileId IncrementalService::idFromMetadata(std::span<const uint8_t> metadata) {
    return IncFs_FileIdFromMetadata({(const char*)metadata.data(), metadata.size()});
}

} // namespace android::incremental
