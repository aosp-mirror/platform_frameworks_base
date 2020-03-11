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

#include <android-base/strings.h>
#include <android-base/unique_fd.h>
#include <android/content/pm/DataLoaderParamsParcel.h>
#include <binder/IServiceManager.h>
#include <utils/String16.h>
#include <utils/StrongPointer.h>
#include <utils/Vector.h>

#include <atomic>
#include <chrono>
#include <future>
#include <limits>
#include <map>
#include <mutex>
#include <span>
#include <string>
#include <string_view>
#include <unordered_map>
#include <utility>
#include <vector>

#include "ServiceWrappers.h"
#include "android/content/pm/BnDataLoaderStatusListener.h"
#include "incfs.h"
#include "path.h"

using namespace android::os::incremental;

namespace android::os {
class IVold;
}

namespace android::incremental {

using MountId = int;
using StorageId = int;
using FileId = incfs::FileId;
using BlockIndex = incfs::BlockIndex;
using RawMetadata = incfs::RawMetadata;
using Clock = std::chrono::steady_clock;
using TimePoint = std::chrono::time_point<Clock>;
using Seconds = std::chrono::seconds;

using DataLoaderStatusListener = ::android::sp<::android::content::pm::IDataLoaderStatusListener>;

class IncrementalService final {
public:
    explicit IncrementalService(ServiceManagerWrapper&& sm, std::string_view rootDir);

#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wnon-virtual-dtor"
    ~IncrementalService();
#pragma GCC diagnostic pop

    static constexpr StorageId kInvalidStorageId = -1;
    static constexpr StorageId kMaxStorageId = std::numeric_limits<int>::max();

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

    std::optional<std::future<void>> onSystemReady();

    StorageId createStorage(std::string_view mountPoint, DataLoaderParamsParcel&& dataLoaderParams,
                            const DataLoaderStatusListener& dataLoaderStatusListener,
                            CreateOptions options = CreateOptions::Default);
    StorageId createLinkedStorage(std::string_view mountPoint, StorageId linkedStorage,
                                  CreateOptions options = CreateOptions::Default);
    StorageId openStorage(std::string_view path);

    FileId nodeFor(StorageId storage, std::string_view path) const;
    std::pair<FileId, std::string_view> parentAndNameFor(StorageId storage,
                                                         std::string_view path) const;

    int bind(StorageId storage, std::string_view source, std::string_view target, BindKind kind);
    int unbind(StorageId storage, std::string_view target);
    void deleteStorage(StorageId storage);

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

    RawMetadata getMetadata(StorageId storage, FileId node) const;

    std::vector<std::string> listFiles(StorageId storage) const;
    bool startLoading(StorageId storage) const;
    bool configureNativeBinaries(StorageId storage, std::string_view apkFullPath,
                                 std::string_view libDirRelativePath, std::string_view abi);
    class IncrementalDataLoaderListener : public android::content::pm::BnDataLoaderStatusListener {
    public:
        IncrementalDataLoaderListener(IncrementalService& incrementalService,
                                      DataLoaderStatusListener externalListener)
              : incrementalService(incrementalService), externalListener(externalListener) {}
        // Callbacks interface
        binder::Status onStatusChanged(MountId mount, int newStatus) override;

    private:
        IncrementalService& incrementalService;
        DataLoaderStatusListener externalListener;
    };

private:
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

        using BindMap = std::map<std::string, Bind>;
        using StorageMap = std::unordered_map<StorageId, Storage>;

        mutable std::mutex lock;
        const std::string root;
        Control control;
        /*const*/ MountId mountId;
        StorageMap storages;
        BindMap bindPoints;
        std::optional<DataLoaderParamsParcel> savedDataLoaderParams;
        std::atomic<int> nextStorageDirNo{0};
        std::atomic<int> dataLoaderStatus = -1;
        bool dataLoaderStartRequested = false;
        TimePoint connectionLostTime = TimePoint();
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

    void mountExistingImages();
    bool mountExistingImage(std::string_view root, std::string_view key);

    IfsMountPtr getIfs(StorageId storage) const;
    const IfsMountPtr& getIfsLocked(StorageId storage) const;
    int addBindMount(IncFsMount& ifs, StorageId storage, std::string_view storageRoot,
                     std::string&& source, std::string&& target, BindKind kind,
                     std::unique_lock<std::mutex>& mainLock);

    int addBindMountWithMd(IncFsMount& ifs, StorageId storage, std::string&& metadataName,
                           std::string&& source, std::string&& target, BindKind kind,
                           std::unique_lock<std::mutex>& mainLock);

    bool prepareDataLoader(IncFsMount& ifs, DataLoaderParamsParcel* params = nullptr,
                           const DataLoaderStatusListener* externalListener = nullptr);
    bool startDataLoader(MountId mountId) const;

    BindPathMap::const_iterator findStorageLocked(std::string_view path) const;
    StorageId findStorageId(std::string_view path) const;

    void deleteStorage(IncFsMount& ifs);
    void deleteStorageLocked(IncFsMount& ifs, std::unique_lock<std::mutex>&& ifsLock);
    MountMap::iterator getStorageSlotLocked();
    std::string normalizePathToStorage(const IfsMountPtr incfs, StorageId storage,
                                       std::string_view path);

    // Member variables
    std::unique_ptr<VoldServiceWrapper> mVold;
    std::unique_ptr<DataLoaderManagerWrapper> mDataLoaderManager;
    std::unique_ptr<IncFsWrapper> mIncFs;
    const std::string mIncrementalDir;

    mutable std::mutex mLock;
    mutable std::mutex mMountOperationLock;
    MountMap mMounts;
    BindPathMap mBindsByPath;

    std::atomic_bool mSystemReady = false;
    StorageId mNextId = 0;
    std::promise<void> mPrepareDataLoaders;
};

} // namespace android::incremental
