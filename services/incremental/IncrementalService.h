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

#pragma once

#include <android/content/pm/BnDataLoaderStatusListener.h>
#include <android/content/pm/DataLoaderParamsParcel.h>
#include <android/content/pm/FileSystemControlParcel.h>
#include <android/content/pm/IDataLoaderStatusListener.h>
#include <android/os/incremental/BnIncrementalServiceConnector.h>
#include <android/os/incremental/BnStorageHealthListener.h>
#include <android/os/incremental/StorageHealthCheckParams.h>
#include <binder/IAppOpsCallback.h>
#include <utils/String16.h>
#include <utils/StrongPointer.h>
#include <ziparchive/zip_archive.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <functional>
#include <limits>
#include <map>
#include <mutex>
#include <span>
#include <string>
#include <string_view>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

#include "ServiceWrappers.h"
#include "incfs.h"
#include "path.h"

namespace android::incremental {

using MountId = int;
using StorageId = int;
using FileId = incfs::FileId;
using BlockIndex = incfs::BlockIndex;
using RawMetadata = incfs::RawMetadata;
using Clock = std::chrono::steady_clock;
using TimePoint = std::chrono::time_point<Clock>;
using Seconds = std::chrono::seconds;
using BootClockTsUs = uint64_t;

using IDataLoaderStatusListener = ::android::content::pm::IDataLoaderStatusListener;
using DataLoaderStatusListener = ::android::sp<IDataLoaderStatusListener>;

using StorageHealthCheckParams = ::android::os::incremental::StorageHealthCheckParams;
using IStorageHealthListener = ::android::os::incremental::IStorageHealthListener;
using StorageHealthListener = ::android::sp<IStorageHealthListener>;

class IncrementalService final {
public:
    explicit IncrementalService(ServiceManagerWrapper&& sm, std::string_view rootDir);

#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wnon-virtual-dtor"
    ~IncrementalService();
#pragma GCC diagnostic pop

    static constexpr StorageId kInvalidStorageId = -1;
    static constexpr StorageId kMaxStorageId = std::numeric_limits<int>::max();

    static constexpr BootClockTsUs kMaxBootClockTsUs = std::numeric_limits<BootClockTsUs>::max();

    enum CreateOptions {
        TemporaryBind = 1,
        PermanentBind = 2,
        CreateNew = 4,
        OpenExisting = 8,

        Default = TemporaryBind | CreateNew
    };

    enum class BindKind {
        Temporary = 0,
        Permanent = 1,
    };

    static FileId idFromMetadata(std::span<const uint8_t> metadata);
    static inline FileId idFromMetadata(std::span<const char> metadata) {
        return idFromMetadata({(const uint8_t*)metadata.data(), metadata.size()});
    }

    void onDump(int fd);

    void onSystemReady();

    StorageId createStorage(std::string_view mountPoint,
                            content::pm::DataLoaderParamsParcel&& dataLoaderParams,
                            CreateOptions options, const DataLoaderStatusListener& statusListener,
                            StorageHealthCheckParams&& healthCheckParams,
                            const StorageHealthListener& healthListener);
    StorageId createLinkedStorage(std::string_view mountPoint, StorageId linkedStorage,
                                  CreateOptions options = CreateOptions::Default);
    StorageId openStorage(std::string_view path);

    int bind(StorageId storage, std::string_view source, std::string_view target, BindKind kind);
    int unbind(StorageId storage, std::string_view target);
    void deleteStorage(StorageId storage);

    int setStorageParams(StorageId storage, bool enableReadLogs);

    int makeFile(StorageId storage, std::string_view path, int mode, FileId id,
                 incfs::NewFileParams params);
    int makeDir(StorageId storage, std::string_view path, int mode = 0755);
    int makeDirs(StorageId storage, std::string_view path, int mode = 0755);

    int link(StorageId sourceStorageId, std::string_view oldPath, StorageId destStorageId,
             std::string_view newPath);
    int unlink(StorageId storage, std::string_view path);

    bool isRangeLoaded(StorageId storage, FileId file, std::pair<BlockIndex, BlockIndex> range) {
        return false;
    }

    RawMetadata getMetadata(StorageId storage, std::string_view path) const;
    RawMetadata getMetadata(StorageId storage, FileId node) const;

    bool startLoading(StorageId storage) const;

    bool configureNativeBinaries(StorageId storage, std::string_view apkFullPath,
                                 std::string_view libDirRelativePath, std::string_view abi);
    bool waitForNativeBinariesExtraction(StorageId storage);

    class AppOpsListener : public android::BnAppOpsCallback {
    public:
        AppOpsListener(IncrementalService& incrementalService, std::string packageName)
              : incrementalService(incrementalService), packageName(std::move(packageName)) {}
        void opChanged(int32_t op, const String16& packageName) final;

    private:
        IncrementalService& incrementalService;
        const std::string packageName;
    };

    class IncrementalServiceConnector : public os::incremental::BnIncrementalServiceConnector {
    public:
        IncrementalServiceConnector(IncrementalService& incrementalService, int32_t storage)
              : incrementalService(incrementalService), storage(storage) {}
        binder::Status setStorageParams(bool enableReadLogs, int32_t* _aidl_return) final;

    private:
        IncrementalService& incrementalService;
        int32_t const storage;
    };

private:
    struct IncFsMount;

    class DataLoaderStub : public content::pm::BnDataLoaderStatusListener {
    public:
        DataLoaderStub(IncrementalService& service, MountId id,
                       content::pm::DataLoaderParamsParcel&& params,
                       content::pm::FileSystemControlParcel&& control,
                       const DataLoaderStatusListener* statusListener,
                       StorageHealthCheckParams&& healthCheckParams,
                       const StorageHealthListener* healthListener, std::string&& healthPath);
        ~DataLoaderStub();
        // Cleans up the internal state and invalidates DataLoaderStub. Any subsequent calls will
        // result in an error.
        void cleanupResources();

        bool requestCreate();
        bool requestStart();
        bool requestDestroy();

        void onDump(int fd);

        MountId id() const { return mId; }
        const content::pm::DataLoaderParamsParcel& params() const { return mParams; }

    private:
        binder::Status onStatusChanged(MountId mount, int newStatus) final;

        void registerForPendingReads();
        void unregisterFromPendingReads();
        int onPendingReads();

        bool isValid() const { return mId != kInvalidStorageId; }
        sp<content::pm::IDataLoader> getDataLoader();

        bool bind();
        bool create();
        bool start();
        bool destroy();

        bool setTargetStatus(int status);
        void setTargetStatusLocked(int status);

        bool fsmStep();

        // Watching for pending reads.
        void healthStatusOk();
        // Pending reads detected, waiting for Xsecs to confirm blocked state.
        void healthStatusReadsPending();
        // There are reads pending for X+secs, waiting for additional Ysecs to confirm unhealthy
        // state.
        void healthStatusBlocked();
        // There are reads pending for X+Ysecs, marking storage as unhealthy.
        void healthStatusUnhealthy();

        IncrementalService& mService;

        std::mutex mMutex;
        MountId mId = kInvalidStorageId;
        content::pm::DataLoaderParamsParcel mParams;
        content::pm::FileSystemControlParcel mControl;
        DataLoaderStatusListener mStatusListener;
        StorageHealthListener mHealthListener;

        std::condition_variable mStatusCondition;
        int mCurrentStatus = content::pm::IDataLoaderStatusListener::DATA_LOADER_DESTROYED;
        int mTargetStatus = content::pm::IDataLoaderStatusListener::DATA_LOADER_DESTROYED;
        TimePoint mTargetStatusTs = {};

        std::string mHealthPath;
        incfs::UniqueControl mHealthControl;
    };
    using DataLoaderStubPtr = sp<DataLoaderStub>;

    struct IncFsMount {
        struct Bind {
            StorageId storage;
            std::string savedFilename;
            std::string sourceDir;
            BindKind kind;
        };

        struct Storage {
            std::string name;
        };

        using Control = incfs::UniqueControl;

        using BindMap = std::map<std::string, Bind, path::PathLess>;
        using StorageMap = std::unordered_map<StorageId, Storage>;

        mutable std::mutex lock;
        const std::string root;
        Control control;
        /*const*/ MountId mountId;
        StorageMap storages;
        BindMap bindPoints;
        DataLoaderStubPtr dataLoaderStub;
        std::atomic<int> nextStorageDirNo{0};
        const IncrementalService& incrementalService;

        IncFsMount(std::string root, MountId mountId, Control control,
                   const IncrementalService& incrementalService)
              : root(std::move(root)),
                control(std::move(control)),
                mountId(mountId),
                incrementalService(incrementalService) {}
        IncFsMount(IncFsMount&&) = delete;
        IncFsMount& operator=(IncFsMount&&) = delete;
        ~IncFsMount();

        StorageMap::iterator makeStorage(StorageId id);

        static void cleanupFilesystem(std::string_view root);
    };

    using IfsMountPtr = std::shared_ptr<IncFsMount>;
    using MountMap = std::unordered_map<MountId, IfsMountPtr>;
    using BindPathMap = std::map<std::string, IncFsMount::BindMap::iterator, path::PathLess>;

    static bool perfLoggingEnabled();

    std::unordered_set<std::string_view> adoptMountedInstances();
    void mountExistingImages(const std::unordered_set<std::string_view>& mountedRootNames);
    bool mountExistingImage(std::string_view root);

    IfsMountPtr getIfs(StorageId storage) const;
    const IfsMountPtr& getIfsLocked(StorageId storage) const;
    int addBindMount(IncFsMount& ifs, StorageId storage, std::string_view storageRoot,
                     std::string&& source, std::string&& target, BindKind kind,
                     std::unique_lock<std::mutex>& mainLock);

    int addBindMountWithMd(IncFsMount& ifs, StorageId storage, std::string&& metadataName,
                           std::string&& source, std::string&& target, BindKind kind,
                           std::unique_lock<std::mutex>& mainLock);

    void addBindMountRecordLocked(IncFsMount& ifs, StorageId storage, std::string&& metadataName,
                                  std::string&& source, std::string&& target, BindKind kind);

    DataLoaderStubPtr prepareDataLoader(IncFsMount& ifs,
                                        content::pm::DataLoaderParamsParcel&& params,
                                        const DataLoaderStatusListener* statusListener = nullptr,
                                        StorageHealthCheckParams&& healthCheckParams = {},
                                        const StorageHealthListener* healthListener = nullptr);
    void prepareDataLoaderLocked(IncFsMount& ifs, content::pm::DataLoaderParamsParcel&& params,
                                 const DataLoaderStatusListener* statusListener = nullptr,
                                 StorageHealthCheckParams&& healthCheckParams = {},
                                 const StorageHealthListener* healthListener = nullptr);

    BindPathMap::const_iterator findStorageLocked(std::string_view path) const;
    StorageId findStorageId(std::string_view path) const;

    void deleteStorage(IncFsMount& ifs);
    void deleteStorageLocked(IncFsMount& ifs, std::unique_lock<std::mutex>&& ifsLock);
    MountMap::iterator getStorageSlotLocked();
    std::string normalizePathToStorage(const IncFsMount& incfs, StorageId storage,
                                       std::string_view path) const;
    std::string normalizePathToStorageLocked(const IncFsMount& incfs,
                                             IncFsMount::StorageMap::const_iterator storageIt,
                                             std::string_view path) const;
    int makeDirs(const IncFsMount& ifs, StorageId storageId, std::string_view path, int mode);
    binder::Status applyStorageParams(IncFsMount& ifs, bool enableReadLogs);

    void registerAppOpsCallback(const std::string& packageName);
    bool unregisterAppOpsCallback(const std::string& packageName);
    void onAppOpChanged(const std::string& packageName);

    void runJobProcessing();
    void extractZipFile(const IfsMountPtr& ifs, ZipArchiveHandle zipFile, ZipEntry& entry,
                        const incfs::FileId& libFileId, std::string_view targetLibPath,
                        Clock::time_point scheduledTs);

    void runCmdLooper();

private:
    const std::unique_ptr<VoldServiceWrapper> mVold;
    const std::unique_ptr<DataLoaderManagerWrapper> mDataLoaderManager;
    const std::unique_ptr<IncFsWrapper> mIncFs;
    const std::unique_ptr<AppOpsManagerWrapper> mAppOpsManager;
    const std::unique_ptr<JniWrapper> mJni;
    const std::unique_ptr<LooperWrapper> mLooper;
    const std::string mIncrementalDir;

    mutable std::mutex mLock;
    mutable std::mutex mMountOperationLock;
    MountMap mMounts;
    BindPathMap mBindsByPath;

    std::mutex mCallbacksLock;
    std::map<std::string, sp<AppOpsListener>> mCallbackRegistered;

    std::atomic_bool mSystemReady = false;
    StorageId mNextId = 0;

    std::atomic_bool mRunning{true};

    using Job = std::function<void()>;
    std::unordered_map<MountId, std::vector<Job>> mJobQueue;
    MountId mPendingJobsMount = kInvalidStorageId;
    std::condition_variable mJobCondition;
    std::mutex mJobMutex;
    std::thread mJobProcessor;

    std::thread mCmdLooperThread;
};

} // namespace android::incremental
