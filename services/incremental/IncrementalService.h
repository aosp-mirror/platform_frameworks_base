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
#include <android/os/incremental/IIncrementalManager.h>
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
using Inode = incfs::Inode;
using BlockIndex = incfs::BlockIndex;
using RawMetadata = incfs::RawMetadata;
using Clock = std::chrono::steady_clock;
using TimePoint = std::chrono::time_point<Clock>;
using Seconds = std::chrono::seconds;

class IncrementalService {
public:
    explicit IncrementalService(const ServiceManagerWrapper& sm, std::string_view rootDir);

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

    std::optional<std::future<void>> onSystemReady();

    StorageId createStorage(std::string_view mountPoint,
                            DataLoaderParamsParcel&& dataLoaderParams,
                            CreateOptions options = CreateOptions::Default);
    StorageId createLinkedStorage(std::string_view mountPoint, StorageId linkedStorage,
                                  CreateOptions options = CreateOptions::Default);
    StorageId openStorage(std::string_view pathInMount);

    Inode nodeFor(StorageId storage, std::string_view subpath) const;
    std::pair<Inode, std::string_view> parentAndNameFor(StorageId storage,
                                                        std::string_view subpath) const;

    int bind(StorageId storage, std::string_view subdir, std::string_view target, BindKind kind);
    int unbind(StorageId storage, std::string_view target);
    void deleteStorage(StorageId storage);

    Inode makeFile(StorageId storage, std::string_view name, long size, std::string_view metadata,
                   std::string_view signature);
    Inode makeDir(StorageId storage, std::string_view name, std::string_view metadata = {});
    Inode makeDirs(StorageId storage, std::string_view name, std::string_view metadata = {});

    int link(StorageId storage, Inode item, Inode newParent, std::string_view newName);
    int unlink(StorageId storage, Inode parent, std::string_view name);

    bool isRangeLoaded(StorageId storage, Inode file, std::pair<BlockIndex, BlockIndex> range) {
        return false;
    }

    RawMetadata getMetadata(StorageId storage, Inode node) const;
    std::string getSigngatureData(StorageId storage, Inode node) const { return {}; }

    std::vector<std::string> listFiles(StorageId storage) const;
    bool startLoading(StorageId storage) const;

    class IncrementalDataLoaderListener : public android::content::pm::BnDataLoaderStatusListener {
    public:
        IncrementalDataLoaderListener(IncrementalService& incrementalService)
              : incrementalService(incrementalService) {}
        // Callbacks interface
        binder::Status onStatusChanged(MountId mount, int newStatus) override;

    private:
        IncrementalService& incrementalService;
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
            Inode node;
        };

        struct Control {
            operator IncFsControl() const { return {cmdFd, logFd}; }
            void reset() {
                cmdFd.reset();
                logFd.reset();
            }

            base::unique_fd cmdFd;
            base::unique_fd logFd;
        };

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
        std::condition_variable dataLoaderReady;
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
    int addBindMount(IncFsMount& ifs, StorageId storage, std::string&& sourceSubdir,
                     std::string&& target, BindKind kind, std::unique_lock<std::mutex>& mainLock);

    int addBindMountWithMd(IncFsMount& ifs, StorageId storage, std::string&& metadataName,
                           std::string&& sourceSubdir, std::string&& target, BindKind kind,
                           std::unique_lock<std::mutex>& mainLock);

    bool prepareDataLoader(IncFsMount& ifs, DataLoaderParamsParcel* params);
    BindPathMap::const_iterator findStorageLocked(std::string_view path) const;
    StorageId findStorageId(std::string_view path) const;

    void deleteStorage(IncFsMount& ifs);
    void deleteStorageLocked(IncFsMount& ifs, std::unique_lock<std::mutex>&& ifsLock);
    MountMap::iterator getStorageSlotLocked();

    // Member variables
    // These are shared pointers for the sake of unit testing
    std::shared_ptr<VoldServiceWrapper> mVold;
    std::shared_ptr<IncrementalManagerWrapper> mIncrementalManager;
    std::shared_ptr<IncFsWrapper> mIncFs;
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
