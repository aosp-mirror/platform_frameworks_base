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

#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/unique_fd.h>
#include <binder/ParcelFileDescriptor.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <utils/Log.h>
#include <utils/String16.h>

#include <chrono>
#include <future>

#include "IncrementalService.h"
#include "IncrementalServiceValidation.h"
#include "Metadata.pb.h"
#include "ServiceWrappers.h"

using namespace testing;
using namespace android::incremental;
using namespace std::literals;
using testing::_;
using testing::Invoke;
using testing::NiceMock;

#undef LOG_TAG
#define LOG_TAG "IncrementalServiceTest"

using namespace android::incfs;
using namespace android::content::pm;
using PerUidReadTimeouts = android::os::incremental::PerUidReadTimeouts;

namespace android::os::incremental {

class MockVoldService : public VoldServiceWrapper {
public:
    MOCK_CONST_METHOD5(mountIncFs,
                       binder::Status(const std::string& backingPath, const std::string& targetDir,
                                      int32_t flags, const std::string& sysfsName,
                                      IncrementalFileSystemControlParcel* _aidl_return));
    MOCK_CONST_METHOD1(unmountIncFs, binder::Status(const std::string& dir));
    MOCK_CONST_METHOD2(bindMount,
                       binder::Status(const std::string& sourceDir, const std::string& argetDir));
    MOCK_CONST_METHOD4(
            setIncFsMountOptions,
            binder::Status(const ::android::os::incremental::IncrementalFileSystemControlParcel&,
                           bool, bool, const std::string&));

    void mountIncFsFails() {
        ON_CALL(*this, mountIncFs(_, _, _, _, _))
                .WillByDefault(
                        Return(binder::Status::fromExceptionCode(1, String8("failed to mount"))));
    }
    void mountIncFsInvalidControlParcel() {
        ON_CALL(*this, mountIncFs(_, _, _, _, _))
                .WillByDefault(Invoke(this, &MockVoldService::getInvalidControlParcel));
    }
    void mountIncFsSuccess() {
        ON_CALL(*this, mountIncFs(_, _, _, _, _))
                .WillByDefault(Invoke(this, &MockVoldService::incFsSuccess));
    }
    void bindMountFails() {
        ON_CALL(*this, bindMount(_, _))
                .WillByDefault(Return(
                        binder::Status::fromExceptionCode(1, String8("failed to bind-mount"))));
    }
    void bindMountSuccess() {
        ON_CALL(*this, bindMount(_, _)).WillByDefault(Return(binder::Status::ok()));
    }
    void setIncFsMountOptionsFails() const {
        ON_CALL(*this, setIncFsMountOptions(_, _, _, _))
                .WillByDefault(Return(
                        binder::Status::fromExceptionCode(1, String8("failed to set options"))));
    }
    void setIncFsMountOptionsSuccess() {
        ON_CALL(*this, setIncFsMountOptions(_, _, _, _))
                .WillByDefault(Invoke(this, &MockVoldService::setIncFsMountOptionsOk));
    }
    binder::Status getInvalidControlParcel(const std::string& imagePath,
                                           const std::string& targetDir, int32_t flags,
                                           const std::string& sysfsName,
                                           IncrementalFileSystemControlParcel* _aidl_return) {
        _aidl_return = {};
        return binder::Status::ok();
    }
    binder::Status incFsSuccess(const std::string& imagePath, const std::string& targetDir,
                                int32_t flags, const std::string& sysfsName,
                                IncrementalFileSystemControlParcel* _aidl_return) {
        _aidl_return->pendingReads.reset(base::unique_fd(dup(STDIN_FILENO)));
        _aidl_return->cmd.reset(base::unique_fd(dup(STDIN_FILENO)));
        _aidl_return->log.reset(base::unique_fd(dup(STDIN_FILENO)));
        return binder::Status::ok();
    }
    binder::Status setIncFsMountOptionsOk(
            const ::android::os::incremental::IncrementalFileSystemControlParcel& control,
            bool enableReadLogs, bool enableReadTimeouts, const std::string& sysfsName) {
        mReadLogsEnabled = enableReadLogs;
        mReadTimeoutsEnabled = enableReadTimeouts;
        return binder::Status::ok();
    }

    bool readLogsEnabled() const { return mReadLogsEnabled; }
    bool readTimeoutsEnabled() const { return mReadTimeoutsEnabled; }

private:
    TemporaryFile cmdFile;
    TemporaryFile logFile;

    bool mReadLogsEnabled = false;
    bool mReadTimeoutsEnabled = true;
};

class MockDataLoader : public IDataLoader {
public:
    MockDataLoader() {
        initializeCreateOk();
        ON_CALL(*this, start(_)).WillByDefault(Invoke(this, &MockDataLoader::startOk));
        ON_CALL(*this, stop(_)).WillByDefault(Invoke(this, &MockDataLoader::stopOk));
        ON_CALL(*this, destroy(_)).WillByDefault(Invoke(this, &MockDataLoader::destroyOk));
        ON_CALL(*this, prepareImage(_, _, _))
                .WillByDefault(Invoke(this, &MockDataLoader::prepareImageOk));
    }
    IBinder* onAsBinder() override { return nullptr; }
    MOCK_METHOD4(create,
                 binder::Status(int32_t id, const DataLoaderParamsParcel& params,
                                const FileSystemControlParcel& control,
                                const sp<IDataLoaderStatusListener>& listener));
    MOCK_METHOD1(start, binder::Status(int32_t id));
    MOCK_METHOD1(stop, binder::Status(int32_t id));
    MOCK_METHOD1(destroy, binder::Status(int32_t id));
    MOCK_METHOD3(prepareImage,
                 binder::Status(int32_t id, const std::vector<InstallationFileParcel>& addedFiles,
                                const std::vector<std::string>& removedFiles));

    void initializeCreateOk() {
        ON_CALL(*this, create(_, _, _, _)).WillByDefault(Invoke(this, &MockDataLoader::createOk));
    }

    void initializeCreateOkNoStatus() {
        ON_CALL(*this, create(_, _, _, _))
                .WillByDefault(Invoke(this, &MockDataLoader::createOkNoStatus));
    }

    binder::Status createOk(int32_t id, const content::pm::DataLoaderParamsParcel& params,
                            const content::pm::FileSystemControlParcel& control,
                            const sp<content::pm::IDataLoaderStatusListener>& listener) {
        createOkNoStatus(id, params, control, listener);
        reportStatus(id);
        return binder::Status::ok();
    }
    binder::Status createOkNoStatus(int32_t id, const content::pm::DataLoaderParamsParcel& params,
                                    const content::pm::FileSystemControlParcel& control,
                                    const sp<content::pm::IDataLoaderStatusListener>& listener) {
        mServiceConnector = control.service;
        mListener = listener;
        mStatus = IDataLoaderStatusListener::DATA_LOADER_CREATED;
        return binder::Status::ok();
    }
    binder::Status startOk(int32_t id) {
        setAndReportStatus(id, IDataLoaderStatusListener::DATA_LOADER_STARTED);
        return binder::Status::ok();
    }
    binder::Status stopOk(int32_t id) {
        setAndReportStatus(id, IDataLoaderStatusListener::DATA_LOADER_STOPPED);
        return binder::Status::ok();
    }
    binder::Status destroyOk(int32_t id) {
        setAndReportStatus(id, IDataLoaderStatusListener::DATA_LOADER_DESTROYED);
        mListener = nullptr;
        return binder::Status::ok();
    }
    binder::Status prepareImageOk(int32_t id,
                                  const ::std::vector<content::pm::InstallationFileParcel>&,
                                  const ::std::vector<::std::string>&) {
        setAndReportStatus(id, IDataLoaderStatusListener::DATA_LOADER_IMAGE_READY);
        return binder::Status::ok();
    }
    int32_t setStorageParams(bool enableReadLogs) {
        int32_t result = -1;
        EXPECT_NE(mServiceConnector.get(), nullptr);
        EXPECT_TRUE(mServiceConnector->setStorageParams(enableReadLogs, &result).isOk());
        return result;
    }
    int status() const { return mStatus; }

private:
    void setAndReportStatus(int id, int status) {
        mStatus = status;
        reportStatus(id);
    }
    void reportStatus(int id) {
        if (mListener) {
            mListener->onStatusChanged(id, mStatus);
        }
    }

    sp<IIncrementalServiceConnector> mServiceConnector;
    sp<IDataLoaderStatusListener> mListener;
    int mStatus = IDataLoaderStatusListener::DATA_LOADER_DESTROYED;
};

class MockDataLoaderManager : public DataLoaderManagerWrapper {
public:
    MockDataLoaderManager(sp<IDataLoader> dataLoader) : mDataLoaderHolder(std::move(dataLoader)) {
        EXPECT_TRUE(mDataLoaderHolder != nullptr);
    }

    MOCK_CONST_METHOD5(bindToDataLoader,
                       binder::Status(int32_t mountId, const DataLoaderParamsParcel& params,
                                      int bindDelayMs,
                                      const sp<IDataLoaderStatusListener>& listener,
                                      bool* _aidl_return));
    MOCK_CONST_METHOD2(getDataLoader,
                       binder::Status(int32_t mountId, sp<IDataLoader>* _aidl_return));
    MOCK_CONST_METHOD1(unbindFromDataLoader, binder::Status(int32_t mountId));

    void bindToDataLoaderSuccess() {
        ON_CALL(*this, bindToDataLoader(_, _, _, _, _))
                .WillByDefault(Invoke(this, &MockDataLoaderManager::bindToDataLoaderOk));
    }
    void bindToDataLoaderFails() {
        ON_CALL(*this, bindToDataLoader(_, _, _, _, _))
                .WillByDefault(Return(
                        (binder::Status::fromExceptionCode(1, String8("failed to prepare")))));
    }
    void getDataLoaderSuccess() {
        ON_CALL(*this, getDataLoader(_, _))
                .WillByDefault(Invoke(this, &MockDataLoaderManager::getDataLoaderOk));
    }
    void unbindFromDataLoaderSuccess() {
        ON_CALL(*this, unbindFromDataLoader(_))
                .WillByDefault(Invoke(this, &MockDataLoaderManager::unbindFromDataLoaderOk));
    }
    binder::Status bindToDataLoaderOk(int32_t mountId, const DataLoaderParamsParcel& params,
                                      int bindDelayMs,
                                      const sp<IDataLoaderStatusListener>& listener,
                                      bool* _aidl_return) {
        mId = mountId;
        mListener = listener;
        mDataLoader = mDataLoaderHolder;
        mBindDelayMs = bindDelayMs;
        *_aidl_return = true;
        if (mListener) {
            mListener->onStatusChanged(mId, IDataLoaderStatusListener::DATA_LOADER_BOUND);
        }
        return binder::Status::ok();
    }
    binder::Status bindToDataLoaderNotOkWithNoDelay(int32_t mountId,
                                                    const DataLoaderParamsParcel& params,
                                                    int bindDelayMs,
                                                    const sp<IDataLoaderStatusListener>& listener,
                                                    bool* _aidl_return) {
        CHECK(bindDelayMs == 0) << bindDelayMs;
        *_aidl_return = false;
        return binder::Status::ok();
    }
    binder::Status bindToDataLoaderBindingWithNoDelay(int32_t mountId,
                                                      const DataLoaderParamsParcel& params,
                                                      int bindDelayMs,
                                                      const sp<IDataLoaderStatusListener>& listener,
                                                      bool* _aidl_return) {
        CHECK(bindDelayMs == 0) << bindDelayMs;
        *_aidl_return = true;
        if (listener) {
            listener->onStatusChanged(mId, IDataLoaderStatusListener::DATA_LOADER_BINDING);
        }
        return binder::Status::ok();
    }
    binder::Status bindToDataLoaderOkWithNoDelay(int32_t mountId,
                                                 const DataLoaderParamsParcel& params,
                                                 int bindDelayMs,
                                                 const sp<IDataLoaderStatusListener>& listener,
                                                 bool* _aidl_return) {
        CHECK(bindDelayMs == 0) << bindDelayMs;
        return bindToDataLoaderOk(mountId, params, bindDelayMs, listener, _aidl_return);
    }
    binder::Status bindToDataLoaderOkWith1sDelay(int32_t mountId,
                                                 const DataLoaderParamsParcel& params,
                                                 int bindDelayMs,
                                                 const sp<IDataLoaderStatusListener>& listener,
                                                 bool* _aidl_return) {
        CHECK(100 * 9 <= bindDelayMs && bindDelayMs <= 100 * 11) << bindDelayMs;
        return bindToDataLoaderOk(mountId, params, bindDelayMs, listener, _aidl_return);
    }
    binder::Status bindToDataLoaderOkWith10sDelay(int32_t mountId,
                                                  const DataLoaderParamsParcel& params,
                                                  int bindDelayMs,
                                                  const sp<IDataLoaderStatusListener>& listener,
                                                  bool* _aidl_return) {
        CHECK(100 * 9 * 9 <= bindDelayMs && bindDelayMs <= 100 * 11 * 11) << bindDelayMs;
        return bindToDataLoaderOk(mountId, params, bindDelayMs, listener, _aidl_return);
    }
    binder::Status bindToDataLoaderOkWith100sDelay(int32_t mountId,
                                                   const DataLoaderParamsParcel& params,
                                                   int bindDelayMs,
                                                   const sp<IDataLoaderStatusListener>& listener,
                                                   bool* _aidl_return) {
        CHECK(100 * 9 * 9 * 9 < bindDelayMs && bindDelayMs < 100 * 11 * 11 * 11) << bindDelayMs;
        return bindToDataLoaderOk(mountId, params, bindDelayMs, listener, _aidl_return);
    }
    binder::Status bindToDataLoaderOkWith1000sDelay(int32_t mountId,
                                                    const DataLoaderParamsParcel& params,
                                                    int bindDelayMs,
                                                    const sp<IDataLoaderStatusListener>& listener,
                                                    bool* _aidl_return) {
        CHECK(100 * 9 * 9 * 9 * 9 < bindDelayMs && bindDelayMs < 100 * 11 * 11 * 11 * 11)
                << bindDelayMs;
        return bindToDataLoaderOk(mountId, params, bindDelayMs, listener, _aidl_return);
    }
    binder::Status bindToDataLoaderOkWith10000sDelay(int32_t mountId,
                                                     const DataLoaderParamsParcel& params,
                                                     int bindDelayMs,
                                                     const sp<IDataLoaderStatusListener>& listener,
                                                     bool* _aidl_return) {
        CHECK(100 * 9 * 9 * 9 * 9 * 9 < bindDelayMs && bindDelayMs < 100 * 11 * 11 * 11 * 11 * 11)
                << bindDelayMs;
        return bindToDataLoaderOk(mountId, params, bindDelayMs, listener, _aidl_return);
    }

    binder::Status getDataLoaderOk(int32_t mountId, sp<IDataLoader>* _aidl_return) {
        *_aidl_return = mDataLoader;
        return binder::Status::ok();
    }
    void setDataLoaderStatusCreated() {
        mListener->onStatusChanged(mId, IDataLoaderStatusListener::DATA_LOADER_CREATED);
    }
    void setDataLoaderStatusStarted() {
        mListener->onStatusChanged(mId, IDataLoaderStatusListener::DATA_LOADER_STARTED);
    }
    void setDataLoaderStatusDestroyed() {
        mListener->onStatusChanged(mId, IDataLoaderStatusListener::DATA_LOADER_DESTROYED);
    }
    void setDataLoaderStatusUnavailable() {
        mListener->onStatusChanged(mId, IDataLoaderStatusListener::DATA_LOADER_UNAVAILABLE);
    }
    void setDataLoaderStatusUnrecoverable() {
        mListener->onStatusChanged(mId, IDataLoaderStatusListener::DATA_LOADER_UNRECOVERABLE);
    }
    binder::Status unbindFromDataLoaderOk(int32_t id) {
        mBindDelayMs = -1;
        if (mDataLoader) {
            if (auto status = mDataLoader->destroy(id); !status.isOk()) {
                return status;
            }
            mDataLoader = nullptr;
        } else if (mListener) {
            mListener->onStatusChanged(id, IDataLoaderStatusListener::DATA_LOADER_DESTROYED);
        }
        return binder::Status::ok();
    }

    int bindDelayMs() const { return mBindDelayMs; }

private:
    int mId = -1;
    int mBindDelayMs = -1;
    sp<IDataLoaderStatusListener> mListener;
    sp<IDataLoader> mDataLoader;
    sp<IDataLoader> mDataLoaderHolder;
};

class MockIncFs : public IncFsWrapper {
public:
    MOCK_CONST_METHOD0(features, Features());
    MOCK_CONST_METHOD1(listExistingMounts, void(const ExistingMountCallback& cb));
    MOCK_CONST_METHOD1(openMount, Control(std::string_view path));
    MOCK_CONST_METHOD4(createControl,
                       Control(IncFsFd cmd, IncFsFd pendingReads, IncFsFd logs,
                               IncFsFd blocksWritten));
    MOCK_CONST_METHOD5(makeFile,
                       ErrorCode(const Control& control, std::string_view path, int mode, FileId id,
                                 NewFileParams params));
    MOCK_CONST_METHOD4(makeMappedFile,
                       ErrorCode(const Control& control, std::string_view path, int mode,
                                 NewMappedFileParams params));
    MOCK_CONST_METHOD3(makeDir, ErrorCode(const Control& control, std::string_view path, int mode));
    MOCK_CONST_METHOD3(makeDirs,
                       ErrorCode(const Control& control, std::string_view path, int mode));
    MOCK_CONST_METHOD2(getMetadata, RawMetadata(const Control& control, FileId fileid));
    MOCK_CONST_METHOD2(getMetadata, RawMetadata(const Control& control, std::string_view path));
    MOCK_CONST_METHOD2(getFileId, FileId(const Control& control, std::string_view path));
    MOCK_CONST_METHOD2(countFilledBlocks,
                       std::pair<IncFsBlockIndex, IncFsBlockIndex>(const Control& control,
                                                                   std::string_view path));
    MOCK_CONST_METHOD2(isFileFullyLoaded,
                       incfs::LoadingState(const Control& control, std::string_view path));
    MOCK_CONST_METHOD2(isFileFullyLoaded, incfs::LoadingState(const Control& control, FileId id));
    MOCK_CONST_METHOD1(isEverythingFullyLoaded, incfs::LoadingState(const Control& control));
    MOCK_CONST_METHOD3(link,
                       ErrorCode(const Control& control, std::string_view from,
                                 std::string_view to));
    MOCK_CONST_METHOD2(unlink, ErrorCode(const Control& control, std::string_view path));
    MOCK_CONST_METHOD2(openForSpecialOps, UniqueFd(const Control& control, FileId id));
    MOCK_CONST_METHOD1(writeBlocks, ErrorCode(std::span<const DataBlock> blocks));
    MOCK_CONST_METHOD3(reserveSpace, ErrorCode(const Control& control, FileId id, IncFsSize size));
    MOCK_CONST_METHOD3(waitForPendingReads,
                       WaitResult(const Control& control, std::chrono::milliseconds timeout,
                                  std::vector<incfs::ReadInfoWithUid>* pendingReadsBuffer));
    MOCK_CONST_METHOD2(setUidReadTimeouts,
                       ErrorCode(const Control& control,
                                 const std::vector<PerUidReadTimeouts>& perUidReadTimeouts));
    MOCK_CONST_METHOD2(forEachFile, ErrorCode(const Control& control, FileCallback cb));
    MOCK_CONST_METHOD2(forEachIncompleteFile, ErrorCode(const Control& control, FileCallback cb));
    MOCK_CONST_METHOD1(getMetrics, std::optional<Metrics>(std::string_view path));
    MOCK_CONST_METHOD1(getLastReadError, std::optional<LastReadError>(const Control& control));

    MockIncFs() {
        ON_CALL(*this, listExistingMounts(_)).WillByDefault(Return());
        ON_CALL(*this, reserveSpace(_, _, _)).WillByDefault(Return(0));
    }

    void makeFileFails() { ON_CALL(*this, makeFile(_, _, _, _, _)).WillByDefault(Return(-1)); }
    void makeFileSuccess() { ON_CALL(*this, makeFile(_, _, _, _, _)).WillByDefault(Return(0)); }

    void countFilledBlocksSuccess() {
        ON_CALL(*this, countFilledBlocks(_, _)).WillByDefault(Return(std::make_pair(1, 2)));
    }

    void countFilledBlocksFullyLoaded() {
        ON_CALL(*this, countFilledBlocks(_, _)).WillByDefault(Return(std::make_pair(10000, 10000)));
    }

    void countFilledBlocksFails() {
        ON_CALL(*this, countFilledBlocks(_, _)).WillByDefault(Return(std::make_pair(-1, -1)));
    }

    void countFilledBlocksEmpty() {
        ON_CALL(*this, countFilledBlocks(_, _)).WillByDefault(Return(std::make_pair(0, 0)));
    }

    void openMountSuccess() {
        ON_CALL(*this, openMount(_)).WillByDefault(Invoke(this, &MockIncFs::openMountForHealth));
    }

    // 1000ms
    void waitForPendingReadsSuccess(uint64_t ts = 0) {
        ON_CALL(*this, waitForPendingReads(_, _, _))
                .WillByDefault(
                        Invoke([ts](const Control& control, std::chrono::milliseconds timeout,
                                    std::vector<incfs::ReadInfoWithUid>* pendingReadsBuffer) {
                            pendingReadsBuffer->push_back({.bootClockTsUs = ts});
                            return android::incfs::WaitResult::HaveData;
                        }));
    }

    void waitForPendingReadsTimeout() {
        ON_CALL(*this, waitForPendingReads(_, _, _))
                .WillByDefault(Return(android::incfs::WaitResult::Timeout));
    }

    static constexpr auto kPendingReadsFd = 42;
    Control openMountForHealth(std::string_view) {
        return UniqueControl(IncFs_CreateControl(-1, kPendingReadsFd, -1, -1));
    }

    RawMetadata getMountInfoMetadata(const Control& control, std::string_view path) {
        metadata::Mount m;
        m.mutable_storage()->set_id(100);
        m.mutable_loader()->set_package_name("com.test");
        m.mutable_loader()->set_arguments("com.uri");
        const auto metadata = m.SerializeAsString();
        m.mutable_loader()->release_arguments();
        m.mutable_loader()->release_package_name();
        return {metadata.begin(), metadata.end()};
    }
    RawMetadata getStorageMetadata(const Control& control, std::string_view path) {
        metadata::Storage st;
        st.set_id(100);
        auto metadata = st.SerializeAsString();
        return {metadata.begin(), metadata.end()};
    }
    RawMetadata getBindPointMetadata(const Control& control, std::string_view path) {
        metadata::BindPoint bp;
        std::string destPath = "dest";
        std::string srcPath = "src";
        bp.set_storage_id(100);
        bp.set_allocated_dest_path(&destPath);
        bp.set_allocated_source_subdir(&srcPath);
        const auto metadata = bp.SerializeAsString();
        bp.release_source_subdir();
        bp.release_dest_path();
        return std::vector<char>(metadata.begin(), metadata.end());
    }
};

class MockAppOpsManager : public AppOpsManagerWrapper {
public:
    MOCK_CONST_METHOD3(checkPermission, binder::Status(const char*, const char*, const char*));
    MOCK_METHOD3(startWatchingMode, void(int32_t, const String16&, const sp<IAppOpsCallback>&));
    MOCK_METHOD1(stopWatchingMode, void(const sp<IAppOpsCallback>&));

    void checkPermissionSuccess() {
        ON_CALL(*this, checkPermission(_, _, _)).WillByDefault(Return(android::incremental::Ok()));
    }
    void checkPermissionNoCrossUsers() {
        ON_CALL(*this,
                checkPermission("android.permission.LOADER_USAGE_STATS",
                                "android:loader_usage_stats", _))
                .WillByDefault(Return(android::incremental::Ok()));
        ON_CALL(*this, checkPermission("android.permission.INTERACT_ACROSS_USERS", nullptr, _))
                .WillByDefault(
                        Return(android::incremental::Exception(binder::Status::EX_SECURITY, {})));
    }
    void checkPermissionFails() {
        ON_CALL(*this, checkPermission(_, _, _))
                .WillByDefault(
                        Return(android::incremental::Exception(binder::Status::EX_SECURITY, {})));
    }
    void initializeStartWatchingMode() {
        ON_CALL(*this, startWatchingMode(_, _, _))
                .WillByDefault(Invoke(this, &MockAppOpsManager::storeCallback));
    }
    void storeCallback(int32_t, const String16&, const sp<IAppOpsCallback>& cb) {
        mStoredCallback = cb;
    }

    sp<IAppOpsCallback> mStoredCallback;
};

class MockJniWrapper : public JniWrapper {
public:
    MOCK_CONST_METHOD0(initializeForCurrentThread, void());

    MockJniWrapper() { EXPECT_CALL(*this, initializeForCurrentThread()).Times(2); }
};

class MockLooperWrapper : public LooperWrapper {
public:
    MOCK_METHOD5(addFd, int(int, int, int, android::Looper_callbackFunc, void*));
    MOCK_METHOD1(removeFd, int(int));
    MOCK_METHOD0(wake, void());
    MOCK_METHOD1(pollAll, int(int));

    MockLooperWrapper() {
        ON_CALL(*this, addFd(_, _, _, _, _))
                .WillByDefault(Invoke(this, &MockLooperWrapper::storeCallback));
        ON_CALL(*this, removeFd(_)).WillByDefault(Invoke(this, &MockLooperWrapper::clearCallback));
        ON_CALL(*this, pollAll(_)).WillByDefault(Invoke(this, &MockLooperWrapper::wait10Ms));
    }

    int storeCallback(int, int, int, android::Looper_callbackFunc callback, void* data) {
        mCallback = callback;
        mCallbackData = data;
        return 0;
    }

    int clearCallback(int) {
        mCallback = nullptr;
        mCallbackData = nullptr;
        return 0;
    }

    int wait10Ms(int) {
        // This is called from a loop in runCmdLooper.
        // Sleeping for 10ms only to avoid busy looping.
        std::this_thread::sleep_for(10ms);
        return 0;
    }

    android::Looper_callbackFunc mCallback = nullptr;
    void* mCallbackData = nullptr;
};

class MockTimedQueueWrapper : public TimedQueueWrapper {
public:
    MOCK_METHOD3(addJob, void(MountId, Milliseconds, Job));
    MOCK_METHOD1(removeJobs, void(MountId));
    MOCK_METHOD0(stop, void());

    MockTimedQueueWrapper() {
        ON_CALL(*this, addJob(_, _, _))
                .WillByDefault(Invoke(this, &MockTimedQueueWrapper::storeJob));
        ON_CALL(*this, removeJobs(_)).WillByDefault(Invoke(this, &MockTimedQueueWrapper::clearJob));
    }

    void storeJob(MountId id, Milliseconds after, Job what) {
        mId = id;
        mAfter = after;
        mWhat = std::move(what);
    }

    void clearJob(MountId id) {
        if (mId == id) {
            mAfter = {};
            mWhat = {};
        }
    }

    MountId mId = -1;
    Milliseconds mAfter;
    Job mWhat;
};

class MockFsWrapper : public FsWrapper {
public:
    MOCK_CONST_METHOD2(listFilesRecursive, void(std::string_view, FileCallback));
    void hasNoFile() { ON_CALL(*this, listFilesRecursive(_, _)).WillByDefault(Return()); }
    void hasFiles() {
        ON_CALL(*this, listFilesRecursive(_, _))
                .WillByDefault(Invoke(this, &MockFsWrapper::fakeFiles));
    }
    void fakeFiles(std::string_view directoryPath, FileCallback onFile) {
        for (auto file : {"base.apk", "split.apk", "lib/a.so"}) {
            if (!onFile(file)) break;
        }
    }
};

class MockClockWrapper : public ClockWrapper {
public:
    MOCK_CONST_METHOD0(now, TimePoint());

    void start() { ON_CALL(*this, now()).WillByDefault(Invoke(this, &MockClockWrapper::getClock)); }

    template <class Delta>
    void advance(Delta delta) {
        mClock += delta;
    }

    void advanceMs(int deltaMs) { mClock += std::chrono::milliseconds(deltaMs); }

    TimePoint getClock() const { return mClock; }
    std::optional<timespec> getClockMono() const {
        const auto nsSinceEpoch =
                std::chrono::duration_cast<std::chrono::nanoseconds>(mClock.time_since_epoch())
                        .count();
        timespec ts = {.tv_sec = static_cast<time_t>(nsSinceEpoch / 1000000000LL),
                       .tv_nsec = static_cast<long>(nsSinceEpoch % 1000000000LL)};
        return ts;
    }

    TimePoint mClock = Clock::now();
};

class MockStorageHealthListener : public os::incremental::BnStorageHealthListener {
public:
    MOCK_METHOD2(onHealthStatus, binder::Status(int32_t storageId, int32_t status));

    MockStorageHealthListener() {
        ON_CALL(*this, onHealthStatus(_, _))
                .WillByDefault(Invoke(this, &MockStorageHealthListener::storeStorageIdAndStatus));
    }

    binder::Status storeStorageIdAndStatus(int32_t storageId, int32_t status) {
        mStorageId = storageId;
        mStatus = status;
        return binder::Status::ok();
    }

    int32_t mStorageId = -1;
    int32_t mStatus = -1;
};

class MockStorageLoadingProgressListener : public IStorageLoadingProgressListener {
public:
    MockStorageLoadingProgressListener() = default;
    MOCK_METHOD2(onStorageLoadingProgressChanged,
                 binder::Status(int32_t storageId, float progress));
    MOCK_METHOD0(onAsBinder, IBinder*());
};

class MockServiceManager : public ServiceManagerWrapper {
public:
    MockServiceManager(std::unique_ptr<MockVoldService> vold,
                       std::unique_ptr<MockDataLoaderManager> dataLoaderManager,
                       std::unique_ptr<MockIncFs> incfs,
                       std::unique_ptr<MockAppOpsManager> appOpsManager,
                       std::unique_ptr<MockJniWrapper> jni,
                       std::unique_ptr<MockLooperWrapper> looper,
                       std::unique_ptr<MockTimedQueueWrapper> timedQueue,
                       std::unique_ptr<MockTimedQueueWrapper> progressUpdateJobQueue,
                       std::unique_ptr<MockFsWrapper> fs, std::unique_ptr<MockClockWrapper> clock)
          : mVold(std::move(vold)),
            mDataLoaderManager(std::move(dataLoaderManager)),
            mIncFs(std::move(incfs)),
            mAppOpsManager(std::move(appOpsManager)),
            mJni(std::move(jni)),
            mLooper(std::move(looper)),
            mTimedQueue(std::move(timedQueue)),
            mProgressUpdateJobQueue(std::move(progressUpdateJobQueue)),
            mFs(std::move(fs)),
            mClock(std::move(clock)) {}
    std::unique_ptr<VoldServiceWrapper> getVoldService() final { return std::move(mVold); }
    std::unique_ptr<DataLoaderManagerWrapper> getDataLoaderManager() final {
        return std::move(mDataLoaderManager);
    }
    std::unique_ptr<IncFsWrapper> getIncFs() final { return std::move(mIncFs); }
    std::unique_ptr<AppOpsManagerWrapper> getAppOpsManager() final {
        return std::move(mAppOpsManager);
    }
    std::unique_ptr<JniWrapper> getJni() final { return std::move(mJni); }
    std::unique_ptr<LooperWrapper> getLooper() final { return std::move(mLooper); }
    std::unique_ptr<TimedQueueWrapper> getTimedQueue() final { return std::move(mTimedQueue); }
    std::unique_ptr<TimedQueueWrapper> getProgressUpdateJobQueue() final {
        return std::move(mProgressUpdateJobQueue);
    }
    std::unique_ptr<FsWrapper> getFs() final { return std::move(mFs); }
    std::unique_ptr<ClockWrapper> getClock() final { return std::move(mClock); }

private:
    std::unique_ptr<MockVoldService> mVold;
    std::unique_ptr<MockDataLoaderManager> mDataLoaderManager;
    std::unique_ptr<MockIncFs> mIncFs;
    std::unique_ptr<MockAppOpsManager> mAppOpsManager;
    std::unique_ptr<MockJniWrapper> mJni;
    std::unique_ptr<MockLooperWrapper> mLooper;
    std::unique_ptr<MockTimedQueueWrapper> mTimedQueue;
    std::unique_ptr<MockTimedQueueWrapper> mProgressUpdateJobQueue;
    std::unique_ptr<MockFsWrapper> mFs;
    std::unique_ptr<MockClockWrapper> mClock;
};

// --- IncrementalServiceTest ---

class IncrementalServiceTest : public testing::Test {
public:
    void SetUp() override {
        auto vold = std::make_unique<NiceMock<MockVoldService>>();
        mVold = vold.get();
        sp<NiceMock<MockDataLoader>> dataLoader{new NiceMock<MockDataLoader>};
        mDataLoader = dataLoader.get();
        auto dataloaderManager = std::make_unique<NiceMock<MockDataLoaderManager>>(dataLoader);
        mDataLoaderManager = dataloaderManager.get();
        auto incFs = std::make_unique<NiceMock<MockIncFs>>();
        mIncFs = incFs.get();
        auto appOps = std::make_unique<NiceMock<MockAppOpsManager>>();
        mAppOpsManager = appOps.get();
        auto jni = std::make_unique<NiceMock<MockJniWrapper>>();
        mJni = jni.get();
        auto looper = std::make_unique<NiceMock<MockLooperWrapper>>();
        mLooper = looper.get();
        auto timedQueue = std::make_unique<NiceMock<MockTimedQueueWrapper>>();
        mTimedQueue = timedQueue.get();
        auto progressUpdateJobQueue = std::make_unique<NiceMock<MockTimedQueueWrapper>>();
        mProgressUpdateJobQueue = progressUpdateJobQueue.get();
        auto fs = std::make_unique<NiceMock<MockFsWrapper>>();
        mFs = fs.get();
        auto clock = std::make_unique<NiceMock<MockClockWrapper>>();
        mClock = clock.get();
        mIncrementalService = std::make_unique<
                IncrementalService>(MockServiceManager(std::move(vold),
                                                       std::move(dataloaderManager),
                                                       std::move(incFs), std::move(appOps),
                                                       std::move(jni), std::move(looper),
                                                       std::move(timedQueue),
                                                       std::move(progressUpdateJobQueue),
                                                       std::move(fs), std::move(clock)),
                                    mRootDir.path);
        mDataLoaderParcel.packageName = "com.test";
        mDataLoaderParcel.arguments = "uri";
        mDataLoaderManager->unbindFromDataLoaderSuccess();
        mIncrementalService->onSystemReady();
        mClock->start();
        setupSuccess();
    }

    void setUpExistingMountDir(const std::string& rootDir) {
        const auto dir = rootDir + "/dir1";
        const auto mountDir = dir + "/mount";
        const auto backingDir = dir + "/backing_store";
        const auto storageDir = mountDir + "/st0";
        ASSERT_EQ(0, mkdir(dir.c_str(), 0755));
        ASSERT_EQ(0, mkdir(mountDir.c_str(), 0755));
        ASSERT_EQ(0, mkdir(backingDir.c_str(), 0755));
        ASSERT_EQ(0, mkdir(storageDir.c_str(), 0755));
        const auto mountInfoFile = rootDir + "/dir1/mount/.info";
        const auto mountPointsFile = rootDir + "/dir1/mount/.mountpoint.abcd";
        ASSERT_TRUE(base::WriteStringToFile("info", mountInfoFile));
        ASSERT_TRUE(base::WriteStringToFile("mounts", mountPointsFile));
        ON_CALL(*mIncFs, getMetadata(_, std::string_view(mountInfoFile)))
                .WillByDefault(Invoke(mIncFs, &MockIncFs::getMountInfoMetadata));
        ON_CALL(*mIncFs, getMetadata(_, std::string_view(mountPointsFile)))
                .WillByDefault(Invoke(mIncFs, &MockIncFs::getBindPointMetadata));
        ON_CALL(*mIncFs, getMetadata(_, std::string_view(rootDir + "/dir1/mount/st0")))
                .WillByDefault(Invoke(mIncFs, &MockIncFs::getStorageMetadata));
    }

    void setupSuccess() {
        mVold->mountIncFsSuccess();
        mIncFs->makeFileSuccess();
        mVold->bindMountSuccess();
        mDataLoaderManager->bindToDataLoaderSuccess();
        mDataLoaderManager->getDataLoaderSuccess();
    }

    void checkHealthMetrics(int storageId, long expectedMillisSinceOldestPendingRead,
                            int expectedStorageHealthStatusCode) {
        android::os::PersistableBundle result{};
        mIncrementalService->getMetrics(storageId, &result);
        ASSERT_EQ(6, (int)result.size());
        int64_t millisSinceOldestPendingRead = -1;
        ASSERT_TRUE(result.getLong(String16(BnIncrementalService::
                                                    METRICS_MILLIS_SINCE_OLDEST_PENDING_READ()
                                                            .c_str()),
                                   &millisSinceOldestPendingRead));
        // Allow 10ms.
        ASSERT_LE(expectedMillisSinceOldestPendingRead, millisSinceOldestPendingRead);
        ASSERT_GE(expectedMillisSinceOldestPendingRead + 10, millisSinceOldestPendingRead);
        int storageHealthStatusCode = -1;
        ASSERT_TRUE(
                result.getInt(String16(BnIncrementalService::METRICS_STORAGE_HEALTH_STATUS_CODE()
                                               .c_str()),
                              &storageHealthStatusCode));
        ASSERT_EQ(expectedStorageHealthStatusCode, storageHealthStatusCode);
        int dataLoaderStatusCode = -1;
        ASSERT_TRUE(result.getInt(String16(BnIncrementalService::METRICS_DATA_LOADER_STATUS_CODE()
                                                   .c_str()),
                                  &dataLoaderStatusCode));
        ASSERT_EQ(IDataLoaderStatusListener::DATA_LOADER_STARTED, dataLoaderStatusCode);
    }

    void checkBindingMetrics(int storageId, int64_t expectedMillisSinceLastDataLoaderBind,
                             int64_t expectedDataLoaderBindDelayMillis) {
        android::os::PersistableBundle result{};
        mIncrementalService->getMetrics(storageId, &result);
        ASSERT_EQ(6, (int)result.size());
        int dataLoaderStatus = -1;
        ASSERT_TRUE(result.getInt(String16(BnIncrementalService::METRICS_DATA_LOADER_STATUS_CODE()
                                                   .c_str()),
                                  &dataLoaderStatus));
        ASSERT_EQ(IDataLoaderStatusListener::DATA_LOADER_STARTED, dataLoaderStatus);
        int64_t millisSinceLastDataLoaderBind = -1;
        ASSERT_TRUE(result.getLong(String16(BnIncrementalService::
                                                    METRICS_MILLIS_SINCE_LAST_DATA_LOADER_BIND()
                                                            .c_str()),
                                   &millisSinceLastDataLoaderBind));
        ASSERT_EQ(expectedMillisSinceLastDataLoaderBind, millisSinceLastDataLoaderBind);
        int64_t dataLoaderBindDelayMillis = -1;
        ASSERT_TRUE(
                result.getLong(String16(
                                       BnIncrementalService::METRICS_DATA_LOADER_BIND_DELAY_MILLIS()
                                               .c_str()),
                               &dataLoaderBindDelayMillis));
        ASSERT_EQ(expectedDataLoaderBindDelayMillis, dataLoaderBindDelayMillis);
    }

protected:
    NiceMock<MockVoldService>* mVold = nullptr;
    NiceMock<MockIncFs>* mIncFs = nullptr;
    NiceMock<MockDataLoaderManager>* mDataLoaderManager = nullptr;
    NiceMock<MockAppOpsManager>* mAppOpsManager = nullptr;
    NiceMock<MockJniWrapper>* mJni = nullptr;
    NiceMock<MockLooperWrapper>* mLooper = nullptr;
    NiceMock<MockTimedQueueWrapper>* mTimedQueue = nullptr;
    NiceMock<MockTimedQueueWrapper>* mProgressUpdateJobQueue = nullptr;
    NiceMock<MockFsWrapper>* mFs = nullptr;
    NiceMock<MockClockWrapper>* mClock = nullptr;
    NiceMock<MockDataLoader>* mDataLoader = nullptr;
    std::unique_ptr<IncrementalService> mIncrementalService;
    TemporaryDir mRootDir;
    DataLoaderParamsParcel mDataLoaderParcel;
};

TEST_F(IncrementalServiceTest, testCreateStorageMountIncFsFails) {
    mVold->mountIncFsFails();
    EXPECT_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _, _)).Times(0);
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_LT(storageId, 0);
}

TEST_F(IncrementalServiceTest, testCreateStorageMountIncFsInvalidControlParcel) {
    mVold->mountIncFsInvalidControlParcel();
    EXPECT_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _, _)).Times(0);
    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_)).Times(0);
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_LT(storageId, 0);
}

TEST_F(IncrementalServiceTest, testCreateStorageMakeFileFails) {
    mVold->mountIncFsSuccess();
    mIncFs->makeFileFails();
    EXPECT_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _, _)).Times(0);
    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_)).Times(0);
    EXPECT_CALL(*mVold, unmountIncFs(_));
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_LT(storageId, 0);
}

TEST_F(IncrementalServiceTest, testCreateStorageBindMountFails) {
    mVold->mountIncFsSuccess();
    mIncFs->makeFileSuccess();
    mVold->bindMountFails();
    EXPECT_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _, _)).Times(0);
    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_)).Times(0);
    EXPECT_CALL(*mVold, unmountIncFs(_));
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_LT(storageId, 0);
}

TEST_F(IncrementalServiceTest, testCreateStoragePrepareDataLoaderFails) {
    mVold->mountIncFsSuccess();
    mIncFs->makeFileSuccess();
    mVold->bindMountSuccess();
    mDataLoaderManager->bindToDataLoaderFails();
    EXPECT_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _, _)).Times(1);
    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_)).Times(0);
    EXPECT_CALL(*mDataLoader, create(_, _, _, _)).Times(0);
    EXPECT_CALL(*mDataLoader, start(_)).Times(0);
    EXPECT_CALL(*mDataLoader, destroy(_)).Times(0);
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_GE(storageId, 0);
    mIncrementalService->startLoading(storageId, std::move(mDataLoaderParcel), {}, {}, {}, {});
}

TEST_F(IncrementalServiceTest, testDeleteStorageSuccess) {
    EXPECT_CALL(*mDataLoader, create(_, _, _, _)).Times(1);
    EXPECT_CALL(*mDataLoader, start(_)).Times(1);
    EXPECT_CALL(*mDataLoader, destroy(_)).Times(1);
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_GE(storageId, 0);
    mIncrementalService->startLoading(storageId, std::move(mDataLoaderParcel), {}, {}, {}, {});
    mIncrementalService->deleteStorage(storageId);
}

TEST_F(IncrementalServiceTest, testDataLoaderDestroyedAndDelayed) {
    EXPECT_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _, _)).Times(7);
    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_)).Times(1);
    EXPECT_CALL(*mDataLoader, create(_, _, _, _)).Times(7);
    EXPECT_CALL(*mDataLoader, start(_)).Times(7);
    EXPECT_CALL(*mDataLoader, destroy(_)).Times(1);
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_GE(storageId, 0);
    mIncrementalService->startLoading(storageId, std::move(mDataLoaderParcel), {}, {}, {}, {});

    // Simulated crash/other connection breakage.

    ON_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _, _))
            .WillByDefault(Invoke(mDataLoaderManager,
                                  &MockDataLoaderManager::bindToDataLoaderOkWith1sDelay));
    checkBindingMetrics(storageId, 0, 0);
    mClock->advanceMs(mDataLoaderManager->bindDelayMs());
    checkBindingMetrics(storageId, 0, 0);
    mDataLoaderManager->setDataLoaderStatusDestroyed();

    ON_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _, _))
            .WillByDefault(Invoke(mDataLoaderManager,
                                  &MockDataLoaderManager::bindToDataLoaderOkWith10sDelay));
    checkBindingMetrics(storageId, 0, mDataLoaderManager->bindDelayMs());
    mClock->advanceMs(mDataLoaderManager->bindDelayMs());
    checkBindingMetrics(storageId, mDataLoaderManager->bindDelayMs(),
                        mDataLoaderManager->bindDelayMs());
    mDataLoaderManager->setDataLoaderStatusDestroyed();

    ON_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _, _))
            .WillByDefault(Invoke(mDataLoaderManager,
                                  &MockDataLoaderManager::bindToDataLoaderOkWith100sDelay));
    checkBindingMetrics(storageId, 0, mDataLoaderManager->bindDelayMs());
    mClock->advanceMs(mDataLoaderManager->bindDelayMs());
    checkBindingMetrics(storageId, mDataLoaderManager->bindDelayMs(),
                        mDataLoaderManager->bindDelayMs());
    mDataLoaderManager->setDataLoaderStatusDestroyed();

    ON_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _, _))
            .WillByDefault(Invoke(mDataLoaderManager,
                                  &MockDataLoaderManager::bindToDataLoaderOkWith1000sDelay));
    checkBindingMetrics(storageId, 0, mDataLoaderManager->bindDelayMs());
    mClock->advanceMs(mDataLoaderManager->bindDelayMs());
    checkBindingMetrics(storageId, mDataLoaderManager->bindDelayMs(),
                        mDataLoaderManager->bindDelayMs());
    mDataLoaderManager->setDataLoaderStatusDestroyed();

    ON_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _, _))
            .WillByDefault(Invoke(mDataLoaderManager,
                                  &MockDataLoaderManager::bindToDataLoaderOkWith10000sDelay));
    checkBindingMetrics(storageId, 0, mDataLoaderManager->bindDelayMs());
    // Try the reduced delay, just in case.
    mClock->advanceMs(mDataLoaderManager->bindDelayMs() / 2);
    checkBindingMetrics(storageId, mDataLoaderManager->bindDelayMs() / 2,
                        mDataLoaderManager->bindDelayMs());
    mDataLoaderManager->setDataLoaderStatusDestroyed();

    ON_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _, _))
            .WillByDefault(Invoke(mDataLoaderManager,
                                  &MockDataLoaderManager::bindToDataLoaderOkWith10000sDelay));
    checkBindingMetrics(storageId, 0, mDataLoaderManager->bindDelayMs());
    mClock->advanceMs(mDataLoaderManager->bindDelayMs());
    checkBindingMetrics(storageId, mDataLoaderManager->bindDelayMs(),
                        mDataLoaderManager->bindDelayMs());
    mDataLoaderManager->setDataLoaderStatusDestroyed();
}

TEST_F(IncrementalServiceTest, testDataLoaderOnRestart) {
    mIncFs->waitForPendingReadsSuccess();
    mIncFs->openMountSuccess();

    constexpr auto bindRetryInterval = 5s;

    EXPECT_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _, _)).Times(11);
    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_)).Times(1);
    EXPECT_CALL(*mDataLoader, create(_, _, _, _)).Times(7);
    EXPECT_CALL(*mDataLoader, start(_)).Times(7);
    EXPECT_CALL(*mDataLoader, destroy(_)).Times(1);
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    EXPECT_CALL(*mTimedQueue, addJob(_, _, _)).Times(4);
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_GE(storageId, 0);

    // First binds to DataLoader fails... because it's restart.
    ON_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _, _))
            .WillByDefault(Invoke(mDataLoaderManager,
                                  &MockDataLoaderManager::bindToDataLoaderNotOkWithNoDelay));

    // Request DL start.
    mIncrementalService->startLoading(storageId, std::move(mDataLoaderParcel), {}, {}, {}, {});

    // Retry callback present.
    ASSERT_EQ(storageId, mTimedQueue->mId);
    ASSERT_EQ(mTimedQueue->mAfter, bindRetryInterval);
    auto retryCallback = mTimedQueue->mWhat;
    mTimedQueue->clearJob(storageId);

    // Expecting the same bindToDataLoaderNotOkWithNoDelay call.
    mClock->advance(5s);

    retryCallback();
    // Retry callback present.
    ASSERT_EQ(storageId, mTimedQueue->mId);
    ASSERT_EQ(mTimedQueue->mAfter, bindRetryInterval);
    retryCallback = mTimedQueue->mWhat;
    mTimedQueue->clearJob(storageId);

    // Returning "binding" so that we can retry.
    ON_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _, _))
            .WillByDefault(Invoke(mDataLoaderManager,
                                  &MockDataLoaderManager::bindToDataLoaderBindingWithNoDelay));

    // Expecting bindToDataLoaderBindingWithNoDelay call.
    mClock->advance(5s);

    retryCallback();
    // No retry callback.
    ASSERT_EQ(mTimedQueue->mAfter, 0ms);
    ASSERT_EQ(mTimedQueue->mWhat, nullptr);

    // Should not change the bindToDataLoader call count
    ASSERT_NE(nullptr, mLooper->mCallback);
    ASSERT_NE(nullptr, mLooper->mCallbackData);
    auto looperCb = mLooper->mCallback;
    auto looperCbData = mLooper->mCallbackData;
    looperCb(-1, -1, looperCbData);

    // Expecting the same bindToDataLoaderBindingWithNoDelay call.
    mClock->advance(5s);

    // Use pending reads callback to trigger binding.
    looperCb(-1, -1, looperCbData);

    // No retry callback.
    ASSERT_EQ(mTimedQueue->mAfter, 0ms);
    ASSERT_EQ(mTimedQueue->mWhat, nullptr);

    // Now we are out of 10m "retry" budget, let's finally bind.
    ON_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _, _))
            .WillByDefault(Invoke(mDataLoaderManager, &MockDataLoaderManager::bindToDataLoaderOk));
    mClock->advance(11min);

    // Use pending reads callback to trigger binding.
    looperCb(-1, -1, looperCbData);

    // No retry callback.
    ASSERT_EQ(mTimedQueue->mAfter, 0ms);
    ASSERT_EQ(mTimedQueue->mWhat, nullptr);

    // And test the rest of the backoff.
    // Simulated crash/other connection breakage.
    ON_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _, _))
            .WillByDefault(Invoke(mDataLoaderManager,
                                  &MockDataLoaderManager::bindToDataLoaderOkWith1sDelay));
    mClock->advanceMs(mDataLoaderManager->bindDelayMs());
    mDataLoaderManager->setDataLoaderStatusDestroyed();

    ON_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _, _))
            .WillByDefault(Invoke(mDataLoaderManager,
                                  &MockDataLoaderManager::bindToDataLoaderOkWith10sDelay));
    mClock->advanceMs(mDataLoaderManager->bindDelayMs());
    mDataLoaderManager->setDataLoaderStatusDestroyed();

    ON_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _, _))
            .WillByDefault(Invoke(mDataLoaderManager,
                                  &MockDataLoaderManager::bindToDataLoaderOkWith100sDelay));
    mClock->advanceMs(mDataLoaderManager->bindDelayMs());
    mDataLoaderManager->setDataLoaderStatusDestroyed();

    ON_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _, _))
            .WillByDefault(Invoke(mDataLoaderManager,
                                  &MockDataLoaderManager::bindToDataLoaderOkWith1000sDelay));
    mClock->advanceMs(mDataLoaderManager->bindDelayMs());
    mDataLoaderManager->setDataLoaderStatusDestroyed();

    ON_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _, _))
            .WillByDefault(Invoke(mDataLoaderManager,
                                  &MockDataLoaderManager::bindToDataLoaderOkWith10000sDelay));
    mClock->advanceMs(mDataLoaderManager->bindDelayMs());
    mDataLoaderManager->setDataLoaderStatusDestroyed();

    ON_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _, _))
            .WillByDefault(Invoke(mDataLoaderManager,
                                  &MockDataLoaderManager::bindToDataLoaderOkWith10000sDelay));
    mClock->advanceMs(mDataLoaderManager->bindDelayMs());
    mDataLoaderManager->setDataLoaderStatusDestroyed();
}

TEST_F(IncrementalServiceTest, testStartDataLoaderCreate) {
    mDataLoader->initializeCreateOkNoStatus();
    EXPECT_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _, _)).Times(1);
    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_)).Times(1);
    EXPECT_CALL(*mDataLoader, create(_, _, _, _)).Times(1);
    EXPECT_CALL(*mDataLoader, start(_)).Times(1);
    EXPECT_CALL(*mDataLoader, destroy(_)).Times(1);
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_GE(storageId, 0);
    ASSERT_TRUE(mIncrementalService->startLoading(storageId, std::move(mDataLoaderParcel), {}, {},
                                                  {}, {}));
    mDataLoaderManager->setDataLoaderStatusCreated();
    mDataLoaderManager->setDataLoaderStatusStarted();
}

TEST_F(IncrementalServiceTest, testStartDataLoaderPendingStart) {
    mDataLoader->initializeCreateOkNoStatus();
    EXPECT_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _, _)).Times(1);
    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_)).Times(1);
    EXPECT_CALL(*mDataLoader, create(_, _, _, _)).Times(1);
    EXPECT_CALL(*mDataLoader, start(_)).Times(1);
    EXPECT_CALL(*mDataLoader, destroy(_)).Times(1);
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_GE(storageId, 0);
    ASSERT_TRUE(mIncrementalService->startLoading(storageId, std::move(mDataLoaderParcel), {}, {},
                                                  {}, {}));
    mDataLoaderManager->setDataLoaderStatusCreated();
}

TEST_F(IncrementalServiceTest, testStartDataLoaderCreateUnavailable) {
    mDataLoader->initializeCreateOkNoStatus();
    EXPECT_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _, _)).Times(1);
    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_)).Times(1);
    EXPECT_CALL(*mDataLoader, create(_, _, _, _)).Times(1);
    EXPECT_CALL(*mDataLoader, start(_)).Times(0);
    EXPECT_CALL(*mDataLoader, destroy(_)).Times(1);
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_GE(storageId, 0);
    ASSERT_TRUE(mIncrementalService->startLoading(storageId, std::move(mDataLoaderParcel), {}, {},
                                                  {}, {}));
    mDataLoaderManager->setDataLoaderStatusUnavailable();
}

TEST_F(IncrementalServiceTest, testStartDataLoaderCreateUnrecoverable) {
    mDataLoader->initializeCreateOkNoStatus();
    EXPECT_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _, _)).Times(1);
    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_)).Times(1);
    EXPECT_CALL(*mDataLoader, create(_, _, _, _)).Times(1);
    EXPECT_CALL(*mDataLoader, start(_)).Times(0);
    EXPECT_CALL(*mDataLoader, destroy(_)).Times(1);
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_GE(storageId, 0);
    ASSERT_TRUE(mIncrementalService->startLoading(storageId, std::move(mDataLoaderParcel), {}, {},
                                                  {}, {}));
    mDataLoaderManager->setDataLoaderStatusUnrecoverable();
}

TEST_F(IncrementalServiceTest, testStartDataLoaderRecreateOnPendingReads) {
    mIncFs->waitForPendingReadsSuccess();
    mIncFs->openMountSuccess();
    mDataLoader->initializeCreateOkNoStatus();

    EXPECT_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _, _)).Times(2);
    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_)).Times(2);
    EXPECT_CALL(*mDataLoader, create(_, _, _, _)).Times(2);
    EXPECT_CALL(*mDataLoader, start(_)).Times(0);
    EXPECT_CALL(*mDataLoader, destroy(_)).Times(2);
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    EXPECT_CALL(*mLooper, addFd(MockIncFs::kPendingReadsFd, _, _, _, _)).Times(1);
    EXPECT_CALL(*mLooper, removeFd(MockIncFs::kPendingReadsFd)).Times(1);
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_GE(storageId, 0);
    ASSERT_TRUE(mIncrementalService->startLoading(storageId, std::move(mDataLoaderParcel), {}, {},
                                                  {}, {}));
    mDataLoaderManager->setDataLoaderStatusUnrecoverable();

    // Timed callback present.
    ASSERT_EQ(storageId, mTimedQueue->mId);
    ASSERT_GE(mTimedQueue->mAfter, 10ms);
    auto timedCallback = mTimedQueue->mWhat;
    mTimedQueue->clearJob(storageId);

    // First callback call to propagate unrecoverable.
    timedCallback();

    // And second call to trigger recreation.
    ASSERT_NE(nullptr, mLooper->mCallback);
    ASSERT_NE(nullptr, mLooper->mCallbackData);
    mLooper->mCallback(-1, -1, mLooper->mCallbackData);
}

TEST_F(IncrementalServiceTest, testStartDataLoaderUnavailable) {
    mIncFs->openMountSuccess();
    mDataLoader->initializeCreateOkNoStatus();

    EXPECT_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _, _)).Times(3);
    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_)).Times(3);
    EXPECT_CALL(*mDataLoader, create(_, _, _, _)).Times(3);
    EXPECT_CALL(*mDataLoader, start(_)).Times(1);
    EXPECT_CALL(*mDataLoader, destroy(_)).Times(2);
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    EXPECT_CALL(*mLooper, addFd(MockIncFs::kPendingReadsFd, _, _, _, _)).Times(1);
    EXPECT_CALL(*mLooper, removeFd(MockIncFs::kPendingReadsFd)).Times(1);
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_GE(storageId, 0);

    ON_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _, _))
            .WillByDefault(Invoke(mDataLoaderManager,
                                  &MockDataLoaderManager::bindToDataLoaderOkWithNoDelay));

    ASSERT_TRUE(mIncrementalService->startLoading(storageId, std::move(mDataLoaderParcel), {}, {},
                                                  {}, {}));

    // Unavailable.
    mDataLoaderManager->setDataLoaderStatusUnavailable();

    // Timed callback present.
    ASSERT_EQ(storageId, mTimedQueue->mId);
    ASSERT_GE(mTimedQueue->mAfter, 10ms);
    auto timedCallback = mTimedQueue->mWhat;
    mTimedQueue->clearJob(storageId);

    // Propagating unavailable and expecting it to trigger rebind with 1s retry delay.
    ON_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _, _))
            .WillByDefault(Invoke(mDataLoaderManager,
                                  &MockDataLoaderManager::bindToDataLoaderOkWith1sDelay));
    timedCallback();

    // Unavailable #2.
    mDataLoaderManager->setDataLoaderStatusUnavailable();

    // Timed callback present.
    ASSERT_EQ(storageId, mTimedQueue->mId);
    ASSERT_GE(mTimedQueue->mAfter, 10ms);
    timedCallback = mTimedQueue->mWhat;
    mTimedQueue->clearJob(storageId);

    // Propagating unavailable and expecting it to trigger rebind with 10s retry delay.
    // This time succeed.
    mDataLoader->initializeCreateOk();
    ON_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _, _))
            .WillByDefault(Invoke(mDataLoaderManager,
                                  &MockDataLoaderManager::bindToDataLoaderOkWith10sDelay));
    timedCallback();
}

TEST_F(IncrementalServiceTest, testStartDataLoaderUnhealthyStorage) {
    mIncFs->openMountSuccess();

    EXPECT_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _, _)).Times(1);
    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_)).Times(1);
    EXPECT_CALL(*mDataLoader, create(_, _, _, _)).Times(1);
    EXPECT_CALL(*mDataLoader, start(_)).Times(1);
    EXPECT_CALL(*mDataLoader, destroy(_)).Times(1);
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    EXPECT_CALL(*mLooper, addFd(MockIncFs::kPendingReadsFd, _, _, _, _)).Times(2);
    EXPECT_CALL(*mLooper, removeFd(MockIncFs::kPendingReadsFd)).Times(2);
    EXPECT_CALL(*mTimedQueue, addJob(_, _, _)).Times(6);

    sp<NiceMock<MockStorageHealthListener>> listener{new NiceMock<MockStorageHealthListener>};
    NiceMock<MockStorageHealthListener>* listenerMock = listener.get();
    EXPECT_CALL(*listenerMock, onHealthStatus(_, IStorageHealthListener::HEALTH_STATUS_OK))
            .Times(2);
    EXPECT_CALL(*listenerMock,
                onHealthStatus(_, IStorageHealthListener::HEALTH_STATUS_READS_PENDING))
            .Times(1);
    EXPECT_CALL(*listenerMock, onHealthStatus(_, IStorageHealthListener::HEALTH_STATUS_BLOCKED))
            .Times(1);
    EXPECT_CALL(*listenerMock, onHealthStatus(_, IStorageHealthListener::HEALTH_STATUS_UNHEALTHY))
            .Times(2);

    StorageHealthCheckParams params;
    params.blockedTimeoutMs = 10000;
    params.unhealthyTimeoutMs = 20000;
    params.unhealthyMonitoringMs = 30000;

    using MS = std::chrono::milliseconds;
    using MCS = std::chrono::microseconds;

    const auto blockedTimeout = MS(params.blockedTimeoutMs);
    const auto unhealthyTimeout = MS(params.unhealthyTimeoutMs);
    const auto unhealthyMonitoring = MS(params.unhealthyMonitoringMs);

    const uint64_t kFirstTimestampUs = 1000000000ll;
    const uint64_t kBlockedTimestampUs =
            kFirstTimestampUs - std::chrono::duration_cast<MCS>(blockedTimeout).count();
    const uint64_t kUnhealthyTimestampUs =
            kFirstTimestampUs - std::chrono::duration_cast<MCS>(unhealthyTimeout).count();

    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_GE(storageId, 0);
    mIncrementalService->startLoading(storageId, std::move(mDataLoaderParcel), {},
                                      std::move(params), listener, {});

    // Healthy state, registered for pending reads.
    ASSERT_NE(nullptr, mLooper->mCallback);
    ASSERT_NE(nullptr, mLooper->mCallbackData);
    ASSERT_EQ(storageId, listener->mStorageId);
    ASSERT_EQ(IStorageHealthListener::HEALTH_STATUS_OK, listener->mStatus);
    checkHealthMetrics(storageId, 0, listener->mStatus);

    // Looper/epoll callback.
    mIncFs->waitForPendingReadsSuccess(kFirstTimestampUs);
    mLooper->mCallback(-1, -1, mLooper->mCallbackData);

    // Unregister from pending reads and wait.
    ASSERT_EQ(nullptr, mLooper->mCallback);
    ASSERT_EQ(nullptr, mLooper->mCallbackData);
    ASSERT_EQ(storageId, listener->mStorageId);
    ASSERT_EQ(IStorageHealthListener::HEALTH_STATUS_READS_PENDING, listener->mStatus);
    // Timed callback present.
    ASSERT_EQ(storageId, mTimedQueue->mId);
    ASSERT_GE(mTimedQueue->mAfter, blockedTimeout);
    auto timedCallback = mTimedQueue->mWhat;
    mTimedQueue->clearJob(storageId);

    // Timed job callback for blocked.
    mIncFs->waitForPendingReadsSuccess(kBlockedTimestampUs);
    timedCallback();

    // Still not registered, and blocked.
    ASSERT_EQ(nullptr, mLooper->mCallback);
    ASSERT_EQ(nullptr, mLooper->mCallbackData);
    ASSERT_EQ(storageId, listener->mStorageId);
    ASSERT_EQ(IStorageHealthListener::HEALTH_STATUS_BLOCKED, listener->mStatus);
    checkHealthMetrics(storageId, params.blockedTimeoutMs, listener->mStatus);

    // Timed callback present.
    ASSERT_EQ(storageId, mTimedQueue->mId);
    ASSERT_GE(mTimedQueue->mAfter, 1000ms);
    timedCallback = mTimedQueue->mWhat;
    mTimedQueue->clearJob(storageId);

    // Timed job callback for unhealthy.
    mIncFs->waitForPendingReadsSuccess(kUnhealthyTimestampUs);
    timedCallback();

    // Still not registered, and blocked.
    ASSERT_EQ(nullptr, mLooper->mCallback);
    ASSERT_EQ(nullptr, mLooper->mCallbackData);
    ASSERT_EQ(storageId, listener->mStorageId);
    ASSERT_EQ(IStorageHealthListener::HEALTH_STATUS_UNHEALTHY, listener->mStatus);
    checkHealthMetrics(storageId, params.unhealthyTimeoutMs, listener->mStatus);

    // Timed callback present.
    ASSERT_EQ(storageId, mTimedQueue->mId);
    ASSERT_GE(mTimedQueue->mAfter, unhealthyMonitoring);
    timedCallback = mTimedQueue->mWhat;
    mTimedQueue->clearJob(storageId);

    // One more unhealthy.
    mIncFs->waitForPendingReadsSuccess(kUnhealthyTimestampUs);
    timedCallback();

    // Still not registered, and blocked.
    ASSERT_EQ(nullptr, mLooper->mCallback);
    ASSERT_EQ(nullptr, mLooper->mCallbackData);
    ASSERT_EQ(storageId, listener->mStorageId);
    ASSERT_EQ(IStorageHealthListener::HEALTH_STATUS_UNHEALTHY, listener->mStatus);
    checkHealthMetrics(storageId, params.unhealthyTimeoutMs, listener->mStatus);

    // Timed callback present.
    ASSERT_EQ(storageId, mTimedQueue->mId);
    ASSERT_GE(mTimedQueue->mAfter, unhealthyMonitoring);
    timedCallback = mTimedQueue->mWhat;
    mTimedQueue->clearJob(storageId);

    // And now healthy.
    mIncFs->waitForPendingReadsTimeout();
    timedCallback();

    // Healthy state, registered for pending reads.
    ASSERT_NE(nullptr, mLooper->mCallback);
    ASSERT_NE(nullptr, mLooper->mCallbackData);
    ASSERT_EQ(storageId, listener->mStorageId);
    ASSERT_EQ(IStorageHealthListener::HEALTH_STATUS_OK, listener->mStatus);
    checkHealthMetrics(storageId, 0, listener->mStatus);
}

TEST_F(IncrementalServiceTest, testSetIncFsMountOptionsSuccess) {
    mVold->setIncFsMountOptionsSuccess();
    mAppOpsManager->checkPermissionSuccess();

    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_));
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    // on startLoading
    EXPECT_CALL(*mVold, setIncFsMountOptions(_, false, _, _)).Times(1);
    // We are calling setIncFsMountOptions(true).
    EXPECT_CALL(*mVold, setIncFsMountOptions(_, true, _, _)).Times(1);
    // After setIncFsMountOptions succeeded expecting to start watching.
    EXPECT_CALL(*mAppOpsManager, startWatchingMode(_, _, _)).Times(1);
    // Not expecting callback removal.
    EXPECT_CALL(*mAppOpsManager, stopWatchingMode(_)).Times(0);
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_GE(storageId, 0);
    ASSERT_TRUE(mIncrementalService->startLoading(storageId, std::move(mDataLoaderParcel), {}, {},
                                                  {}, {}));
    ASSERT_GE(mDataLoader->setStorageParams(true), 0);
}

TEST_F(IncrementalServiceTest, testSetIncFsMountOptionsSuccessAndDisabled) {
    mVold->setIncFsMountOptionsSuccess();
    mAppOpsManager->checkPermissionSuccess();

    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_));
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    // Enabling and then disabling readlogs.
    EXPECT_CALL(*mVold, setIncFsMountOptions(_, true, _, _)).Times(1);
    EXPECT_CALL(*mVold, setIncFsMountOptions(_, false, _, _)).Times(2);
    // After setIncFsMountOptions succeeded expecting to start watching.
    EXPECT_CALL(*mAppOpsManager, startWatchingMode(_, _, _)).Times(1);
    // Not expecting callback removal.
    EXPECT_CALL(*mAppOpsManager, stopWatchingMode(_)).Times(0);
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_GE(storageId, 0);
    ASSERT_TRUE(mIncrementalService->startLoading(storageId, std::move(mDataLoaderParcel), {}, {},
                                                  {}, {}));
    ASSERT_GE(mDataLoader->setStorageParams(true), 0);
    // Now disable.
    mIncrementalService->disallowReadLogs(storageId);
    ASSERT_EQ(mDataLoader->setStorageParams(true), -EPERM);
}

TEST_F(IncrementalServiceTest, testSetIncFsMountOptionsSuccessAndTimedOut) {
    mVold->setIncFsMountOptionsSuccess();
    mAppOpsManager->checkPermissionSuccess();

    const auto readLogsMaxInterval = 2h;

    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_));
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    // Enabling and then disabling readlogs.
    EXPECT_CALL(*mVold, setIncFsMountOptions(_, true, _, _)).Times(2);
    EXPECT_CALL(*mVold, setIncFsMountOptions(_, false, _, _)).Times(2);
    // After setIncFsMountOptions succeeded expecting to start watching.
    EXPECT_CALL(*mAppOpsManager, startWatchingMode(_, _, _)).Times(1);
    // Not expecting callback removal.
    EXPECT_CALL(*mAppOpsManager, stopWatchingMode(_)).Times(0);
    EXPECT_CALL(*mTimedQueue, addJob(_, _, _)).Times(2);
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_GE(storageId, 0);
    ASSERT_TRUE(mIncrementalService->startLoading(storageId, std::move(mDataLoaderParcel), {}, {},
                                                  {}, {}));

    // Disable readlogs callback present.
    ASSERT_EQ(storageId, mTimedQueue->mId);
    ASSERT_EQ(mTimedQueue->mAfter, readLogsMaxInterval);
    auto callback = mTimedQueue->mWhat;
    mTimedQueue->clearJob(storageId);

    ASSERT_GE(mDataLoader->setStorageParams(true), 0);
    // Now advance clock for 1hr.
    mClock->advance(1h);
    ASSERT_GE(mDataLoader->setStorageParams(true), 0);
    // Now call the timed callback, it should turn off the readlogs.
    callback();
    // Now advance clock for 2hrs.
    mClock->advance(readLogsMaxInterval);
    ASSERT_EQ(mDataLoader->setStorageParams(true), -EPERM);
}

TEST_F(IncrementalServiceTest, testSetIncFsMountOptionsSuccessAndNoTimedOutForSystem) {
    mVold->setIncFsMountOptionsSuccess();
    mAppOpsManager->checkPermissionSuccess();

    const auto readLogsMaxInterval = 2h;

    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_));
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    // Enabling and then disabling readlogs.
    EXPECT_CALL(*mVold, setIncFsMountOptions(_, true, _, _)).Times(3);
    EXPECT_CALL(*mVold, setIncFsMountOptions(_, false, _, _)).Times(1);
    // After setIncFsMountOptions succeeded expecting to start watching.
    EXPECT_CALL(*mAppOpsManager, startWatchingMode(_, _, _)).Times(1);
    // Not expecting callback removal.
    EXPECT_CALL(*mAppOpsManager, stopWatchingMode(_)).Times(0);
    EXPECT_CALL(*mTimedQueue, addJob(_, _, _)).Times(2);
    // System data loader.
    mDataLoaderParcel.packageName = "android";
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_GE(storageId, 0);
    ASSERT_TRUE(mIncrementalService->startLoading(storageId, std::move(mDataLoaderParcel), {}, {},
                                                  {}, {}));

    // IfsState callback.
    auto callback = mTimedQueue->mWhat;
    mTimedQueue->clearJob(storageId);

    ASSERT_GE(mDataLoader->setStorageParams(true), 0);
    // Now advance clock for 1hr.
    mClock->advance(1h);
    ASSERT_GE(mDataLoader->setStorageParams(true), 0);
    // Now advance clock for 2hrs.
    mClock->advance(readLogsMaxInterval);
    // IfsStorage callback should not affect anything.
    callback();
    ASSERT_EQ(mDataLoader->setStorageParams(true), 0);
}

TEST_F(IncrementalServiceTest, testSetIncFsMountOptionsSuccessAndNewInstall) {
    mVold->setIncFsMountOptionsSuccess();
    mAppOpsManager->checkPermissionSuccess();

    const auto readLogsMaxInterval = 2h;

    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_)).Times(2);
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    // Enabling and then disabling readlogs.
    EXPECT_CALL(*mVold, setIncFsMountOptions(_, true, _, _)).Times(5);
    EXPECT_CALL(*mVold, setIncFsMountOptions(_, false, _, _)).Times(3);
    // After setIncFsMountOptions succeeded expecting to start watching.
    EXPECT_CALL(*mAppOpsManager, startWatchingMode(_, _, _)).Times(1);
    // Not expecting callback removal.
    EXPECT_CALL(*mAppOpsManager, stopWatchingMode(_)).Times(0);
    EXPECT_CALL(*mTimedQueue, addJob(_, _, _)).Times(4);
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_GE(storageId, 0);

    // Before install - long timeouts.
    ASSERT_TRUE(mVold->readTimeoutsEnabled());

    auto dataLoaderParcel = mDataLoaderParcel;
    ASSERT_TRUE(mIncrementalService->startLoading(storageId, std::move(dataLoaderParcel), {}, {},
                                                  {}, {}));
    // During install - short timeouts.
    ASSERT_FALSE(mVold->readTimeoutsEnabled());

    // Disable readlogs callback present.
    ASSERT_EQ(storageId, mTimedQueue->mId);
    ASSERT_EQ(mTimedQueue->mAfter, readLogsMaxInterval);
    auto callback = mTimedQueue->mWhat;
    mTimedQueue->clearJob(storageId);

    ASSERT_GE(mDataLoader->setStorageParams(true), 0);
    // Now advance clock for 1.5hrs.
    mClock->advance(90min);
    ASSERT_GE(mDataLoader->setStorageParams(true), 0);

    mIncrementalService->onInstallationComplete(storageId);
    // After install - long timeouts.
    ASSERT_TRUE(mVold->readTimeoutsEnabled());

    // New installation.
    ASSERT_TRUE(mIncrementalService->startLoading(storageId, std::move(mDataLoaderParcel), {}, {},
                                                  {}, {}));
    // New installation - short timeouts.
    ASSERT_FALSE(mVold->readTimeoutsEnabled());

    // New callback present.
    ASSERT_EQ(storageId, mTimedQueue->mId);
    ASSERT_EQ(mTimedQueue->mAfter, readLogsMaxInterval);
    auto callback2 = mTimedQueue->mWhat;
    mTimedQueue->clearJob(storageId);

    // Old callback should not disable readlogs (setIncFsMountOptions should be called only once).
    callback();
    // Advance clock for another 1.5hrs.
    mClock->advance(90min);
    // Still success even it's 3hrs past first install.
    ASSERT_GE(mDataLoader->setStorageParams(true), 0);

    // New one should disable.
    callback2();
    // And timeout.
    mClock->advance(90min);
    ASSERT_EQ(mDataLoader->setStorageParams(true), -EPERM);

    mIncrementalService->onInstallationComplete(storageId);
    // After install - long timeouts.
    ASSERT_TRUE(mVold->readTimeoutsEnabled());
}

TEST_F(IncrementalServiceTest, testSetIncFsMountOptionsSuccessAndPermissionChanged) {
    mVold->setIncFsMountOptionsSuccess();
    mAppOpsManager->checkPermissionSuccess();
    mAppOpsManager->initializeStartWatchingMode();

    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_));
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    // We are calling setIncFsMountOptions(true).
    EXPECT_CALL(*mVold, setIncFsMountOptions(_, true, _, _)).Times(1);
    // setIncFsMountOptions(false) is called on the callback.
    EXPECT_CALL(*mVold, setIncFsMountOptions(_, false, _, _)).Times(2);
    // After setIncFsMountOptions succeeded expecting to start watching.
    EXPECT_CALL(*mAppOpsManager, startWatchingMode(_, _, _)).Times(1);
    // After callback is called, disable read logs and remove callback.
    EXPECT_CALL(*mAppOpsManager, stopWatchingMode(_)).Times(1);
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_GE(storageId, 0);
    ASSERT_TRUE(mIncrementalService->startLoading(storageId, std::move(mDataLoaderParcel), {}, {},
                                                  {}, {}));
    ASSERT_GE(mDataLoader->setStorageParams(true), 0);
    ASSERT_NE(nullptr, mAppOpsManager->mStoredCallback.get());
    mAppOpsManager->mStoredCallback->opChanged(0, {});
}

TEST_F(IncrementalServiceTest, testSetIncFsMountOptionsCheckPermissionFails) {
    mAppOpsManager->checkPermissionFails();

    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_));
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    // checkPermission fails, no calls to set opitions,  start or stop WatchingMode.
    EXPECT_CALL(*mVold, setIncFsMountOptions(_, true, _, _)).Times(0);
    EXPECT_CALL(*mVold, setIncFsMountOptions(_, false, _, _)).Times(1);
    EXPECT_CALL(*mAppOpsManager, startWatchingMode(_, _, _)).Times(0);
    EXPECT_CALL(*mAppOpsManager, stopWatchingMode(_)).Times(0);
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_GE(storageId, 0);
    ASSERT_TRUE(mIncrementalService->startLoading(storageId, std::move(mDataLoaderParcel), {}, {},
                                                  {}, {}));
    ASSERT_LT(mDataLoader->setStorageParams(true), 0);
}

TEST_F(IncrementalServiceTest, testSetIncFsMountOptionsCheckPermissionNoCrossUsers) {
    mAppOpsManager->checkPermissionNoCrossUsers();

    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_));
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    // checkPermission fails, no calls to set opitions,  start or stop WatchingMode.
    EXPECT_CALL(*mVold, setIncFsMountOptions(_, true, _, _)).Times(0);
    EXPECT_CALL(*mVold, setIncFsMountOptions(_, false, _, _)).Times(1);
    EXPECT_CALL(*mAppOpsManager, startWatchingMode(_, _, _)).Times(0);
    EXPECT_CALL(*mAppOpsManager, stopWatchingMode(_)).Times(0);
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_GE(storageId, 0);
    ASSERT_TRUE(mIncrementalService->startLoading(storageId, std::move(mDataLoaderParcel), {}, {},
                                                  {}, {}));
    ASSERT_LT(mDataLoader->setStorageParams(true), 0);
}

TEST_F(IncrementalServiceTest, testSetIncFsMountOptionsFails) {
    mVold->setIncFsMountOptionsFails();
    mAppOpsManager->checkPermissionSuccess();

    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_));
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    // We are calling setIncFsMountOptions.
    EXPECT_CALL(*mVold, setIncFsMountOptions(_, true, _, _)).Times(1);
    EXPECT_CALL(*mVold, setIncFsMountOptions(_, false, _, _)).Times(1);
    // setIncFsMountOptions fails, no calls to start or stop WatchingMode.
    EXPECT_CALL(*mAppOpsManager, startWatchingMode(_, _, _)).Times(0);
    EXPECT_CALL(*mAppOpsManager, stopWatchingMode(_)).Times(0);
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_GE(storageId, 0);
    ASSERT_TRUE(mIncrementalService->startLoading(storageId, std::move(mDataLoaderParcel), {}, {},
                                                  {}, {}));
    ASSERT_LT(mDataLoader->setStorageParams(true), 0);
}

TEST_F(IncrementalServiceTest, testMakeDirectory) {
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    std::string dir_path("test");

    // Expecting incfs to call makeDir on a path like:
    // <root>/*/mount/<storage>/test
    EXPECT_CALL(*mIncFs,
                makeDir(_, Truly([&](std::string_view arg) {
                            return arg.starts_with(mRootDir.path) &&
                                    arg.ends_with("/mount/st_1_0/" + dir_path);
                        }),
                        _));
    auto res = mIncrementalService->makeDir(storageId, dir_path, 0555);
    ASSERT_EQ(res, 0);
}

TEST_F(IncrementalServiceTest, testMakeDirectories) {
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    auto first = "first"sv;
    auto second = "second"sv;
    auto third = "third"sv;
    auto dir_path = std::string(first) + "/" + std::string(second) + "/" + std::string(third);

    EXPECT_CALL(*mIncFs,
                makeDirs(_, Truly([&](std::string_view arg) {
                             return arg.starts_with(mRootDir.path) &&
                                     arg.ends_with("/mount/st_1_0/" + dir_path);
                         }),
                         _));
    auto res = mIncrementalService->makeDirs(storageId, dir_path, 0555);
    ASSERT_EQ(res, 0);
}

TEST_F(IncrementalServiceTest, testIsFileFullyLoadedNoData) {
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    EXPECT_CALL(*mIncFs, isFileFullyLoaded(_, An<std::string_view>()))
            .Times(1)
            .WillOnce(Return(incfs::LoadingState::MissingBlocks));
    ASSERT_GT((int)mIncrementalService->isFileFullyLoaded(storageId, "base.apk"), 0);
}

TEST_F(IncrementalServiceTest, testIsFileFullyLoadedError) {
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    EXPECT_CALL(*mIncFs, isFileFullyLoaded(_, An<std::string_view>()))
            .Times(1)
            .WillOnce(Return(incfs::LoadingState(-1)));
    ASSERT_LT((int)mIncrementalService->isFileFullyLoaded(storageId, "base.apk"), 0);
}

TEST_F(IncrementalServiceTest, testIsFileFullyLoadedSuccess) {
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    EXPECT_CALL(*mIncFs, isFileFullyLoaded(_, An<std::string_view>()))
            .Times(1)
            .WillOnce(Return(incfs::LoadingState::Full));
    ASSERT_EQ(0, (int)mIncrementalService->isFileFullyLoaded(storageId, "base.apk"));
}

TEST_F(IncrementalServiceTest, testGetLoadingProgressSuccessWithNoFile) {
    mIncFs->countFilledBlocksSuccess();
    mFs->hasNoFile();

    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_EQ(1, mIncrementalService->getLoadingProgress(storageId).getProgress());
}

TEST_F(IncrementalServiceTest, testGetLoadingProgressFailsWithFailedRanges) {
    mIncFs->countFilledBlocksFails();
    mFs->hasFiles();

    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    EXPECT_CALL(*mIncFs, countFilledBlocks(_, _)).Times(1);
    ASSERT_EQ(-1, mIncrementalService->getLoadingProgress(storageId).getProgress());
}

TEST_F(IncrementalServiceTest, testGetLoadingProgressSuccessWithEmptyRanges) {
    mIncFs->countFilledBlocksEmpty();
    mFs->hasFiles();

    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    EXPECT_CALL(*mIncFs, countFilledBlocks(_, _)).Times(3);
    ASSERT_EQ(1, mIncrementalService->getLoadingProgress(storageId).getProgress());
}

TEST_F(IncrementalServiceTest, testGetLoadingProgressSuccess) {
    mIncFs->countFilledBlocksSuccess();
    mFs->hasFiles();

    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    EXPECT_CALL(*mIncFs, countFilledBlocks(_, _)).Times(3);
    ASSERT_EQ(0.5, mIncrementalService->getLoadingProgress(storageId).getProgress());
}

TEST_F(IncrementalServiceTest, testRegisterLoadingProgressListenerSuccess) {
    mIncFs->countFilledBlocksSuccess();
    mFs->hasFiles();

    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    sp<NiceMock<MockStorageLoadingProgressListener>> listener{
            new NiceMock<MockStorageLoadingProgressListener>};
    NiceMock<MockStorageLoadingProgressListener>* listenerMock = listener.get();
    EXPECT_CALL(*listenerMock, onStorageLoadingProgressChanged(_, _)).Times(2);
    EXPECT_CALL(*mProgressUpdateJobQueue, addJob(_, _, _)).Times(2);
    mIncrementalService->registerLoadingProgressListener(storageId, listener);
    // Timed callback present.
    ASSERT_EQ(storageId, mProgressUpdateJobQueue->mId);
    ASSERT_EQ(mProgressUpdateJobQueue->mAfter, 1000ms);
    auto timedCallback = mProgressUpdateJobQueue->mWhat;
    timedCallback();
    ASSERT_EQ(storageId, mProgressUpdateJobQueue->mId);
    ASSERT_EQ(mProgressUpdateJobQueue->mAfter, 1000ms);
    mIncrementalService->unregisterLoadingProgressListener(storageId);
    ASSERT_EQ(mProgressUpdateJobQueue->mAfter, Milliseconds{});
}

TEST_F(IncrementalServiceTest, testRegisterLoadingProgressListenerFailsToGetProgress) {
    mIncFs->countFilledBlocksFails();
    mFs->hasFiles();

    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    sp<NiceMock<MockStorageLoadingProgressListener>> listener{
            new NiceMock<MockStorageLoadingProgressListener>};
    NiceMock<MockStorageLoadingProgressListener>* listenerMock = listener.get();
    EXPECT_CALL(*listenerMock, onStorageLoadingProgressChanged(_, _)).Times(0);
    mIncrementalService->registerLoadingProgressListener(storageId, listener);
}

TEST_F(IncrementalServiceTest, testStartDataLoaderUnbindOnAllDone) {
    mFs->hasFiles();

    const auto stateUpdateInterval = 1s;

    EXPECT_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _, _)).Times(1);
    // No unbinding just yet.
    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_)).Times(0);
    EXPECT_CALL(*mDataLoader, create(_, _, _, _)).Times(1);
    EXPECT_CALL(*mDataLoader, start(_)).Times(1);
    EXPECT_CALL(*mDataLoader, destroy(_)).Times(1);
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    // System data loader to get rid of readlog timeout callback.
    mDataLoaderParcel.packageName = "android";
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_GE(storageId, 0);
    ASSERT_TRUE(mIncrementalService->startLoading(storageId, std::move(mDataLoaderParcel), {}, {},
                                                  {}, {}));

    // Started.
    ASSERT_EQ(mDataLoader->status(), IDataLoaderStatusListener::DATA_LOADER_STARTED);

    // IfsState callback present.
    ASSERT_EQ(IncrementalService::kAllStoragesId, mTimedQueue->mId);
    ASSERT_EQ(mTimedQueue->mAfter, stateUpdateInterval);
    auto callback = mTimedQueue->mWhat;
    mTimedQueue->clearJob(IncrementalService::kAllStoragesId);

    // Not loaded yet.
    EXPECT_CALL(*mIncFs, isEverythingFullyLoaded(_))
            .WillOnce(Return(incfs::LoadingState::MissingBlocks));

    // Send the callback, should not do anything.
    callback();

    // Still started.
    ASSERT_EQ(mDataLoader->status(), IDataLoaderStatusListener::DATA_LOADER_STARTED);

    // Still present.
    ASSERT_EQ(IncrementalService::kAllStoragesId, mTimedQueue->mId);
    ASSERT_EQ(mTimedQueue->mAfter, stateUpdateInterval);
    callback = mTimedQueue->mWhat;
    mTimedQueue->clearJob(IncrementalService::kAllStoragesId);

    // Fully loaded.
    EXPECT_CALL(*mIncFs, isEverythingFullyLoaded(_)).WillOnce(Return(incfs::LoadingState::Full));
    // Expect the unbind.
    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_)).Times(1);

    callback();

    // Destroyed.
    ASSERT_EQ(mDataLoader->status(), IDataLoaderStatusListener::DATA_LOADER_DESTROYED);
}

TEST_F(IncrementalServiceTest, testStartDataLoaderUnbindOnAllDoneWithReadlogs) {
    mFs->hasFiles();

    // Readlogs.
    mVold->setIncFsMountOptionsSuccess();
    mAppOpsManager->checkPermissionSuccess();

    const auto stateUpdateInterval = 1s;

    EXPECT_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _, _)).Times(1);
    // No unbinding just yet.
    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_)).Times(0);
    EXPECT_CALL(*mDataLoader, create(_, _, _, _)).Times(1);
    EXPECT_CALL(*mDataLoader, start(_)).Times(1);
    EXPECT_CALL(*mDataLoader, destroy(_)).Times(1);
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    // System data loader to get rid of readlog timeout callback.
    mDataLoaderParcel.packageName = "android";
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_GE(storageId, 0);
    ASSERT_TRUE(mIncrementalService->startLoading(storageId, std::move(mDataLoaderParcel), {}, {},
                                                  {}, {}));

    // Started.
    ASSERT_EQ(mDataLoader->status(), IDataLoaderStatusListener::DATA_LOADER_STARTED);

    // IfsState callback present.
    ASSERT_EQ(IncrementalService::kAllStoragesId, mTimedQueue->mId);
    ASSERT_EQ(mTimedQueue->mAfter, stateUpdateInterval);
    auto callback = mTimedQueue->mWhat;
    mTimedQueue->clearJob(IncrementalService::kAllStoragesId);

    // Not loaded yet.
    EXPECT_CALL(*mIncFs, isEverythingFullyLoaded(_))
            .WillOnce(Return(incfs::LoadingState::MissingBlocks));

    // Send the callback, should not do anything.
    callback();

    // Still started.
    ASSERT_EQ(mDataLoader->status(), IDataLoaderStatusListener::DATA_LOADER_STARTED);

    // Still present.
    ASSERT_EQ(IncrementalService::kAllStoragesId, mTimedQueue->mId);
    ASSERT_EQ(mTimedQueue->mAfter, stateUpdateInterval);
    callback = mTimedQueue->mWhat;
    mTimedQueue->clearJob(IncrementalService::kAllStoragesId);

    // Fully loaded.
    EXPECT_CALL(*mIncFs, isEverythingFullyLoaded(_))
            .WillOnce(Return(incfs::LoadingState::Full))
            .WillOnce(Return(incfs::LoadingState::Full));
    // But with readlogs.
    ASSERT_GE(mDataLoader->setStorageParams(true), 0);

    // Send the callback, still nothing.
    callback();

    // Still started.
    ASSERT_EQ(mDataLoader->status(), IDataLoaderStatusListener::DATA_LOADER_STARTED);

    // Still present.
    ASSERT_EQ(IncrementalService::kAllStoragesId, mTimedQueue->mId);
    ASSERT_EQ(mTimedQueue->mAfter, stateUpdateInterval);
    callback = mTimedQueue->mWhat;
    mTimedQueue->clearJob(IncrementalService::kAllStoragesId);

    // Disable readlogs and expect the unbind.
    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_)).Times(1);
    ASSERT_GE(mDataLoader->setStorageParams(false), 0);

    callback();

    // Destroyed.
    ASSERT_EQ(mDataLoader->status(), IDataLoaderStatusListener::DATA_LOADER_DESTROYED);
}

static std::vector<PerUidReadTimeouts> createPerUidTimeouts(
        std::initializer_list<std::tuple<int, int, int, int>> tuples) {
    std::vector<PerUidReadTimeouts> result;
    for (auto&& tuple : tuples) {
        result.emplace_back();
        auto& timeouts = result.back();
        timeouts.uid = std::get<0>(tuple);
        timeouts.minTimeUs = std::get<1>(tuple);
        timeouts.minPendingTimeUs = std::get<2>(tuple);
        timeouts.maxPendingTimeUs = std::get<3>(tuple);
    }
    return result;
}

static ErrorCode checkPerUidTimeouts(const Control& control,
                                     const std::vector<PerUidReadTimeouts>& perUidReadTimeouts) {
    std::vector<PerUidReadTimeouts> expected =
            createPerUidTimeouts({{0, 1, 2, 3}, {1, 2, 3, 4}, {2, 3, 4, 100000000}});
    EXPECT_EQ(expected, perUidReadTimeouts);
    return 0;
}

static ErrorCode checkPerUidTimeoutsEmpty(
        const Control& control, const std::vector<PerUidReadTimeouts>& perUidReadTimeouts) {
    EXPECT_EQ(0u, perUidReadTimeouts.size());
    return 0;
}

TEST_F(IncrementalServiceTest, testPerUidTimeoutsTooShort) {
    EXPECT_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _, _)).Times(1);
    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_)).Times(1);
    EXPECT_CALL(*mDataLoader, create(_, _, _, _)).Times(1);
    EXPECT_CALL(*mDataLoader, start(_)).Times(1);
    EXPECT_CALL(*mDataLoader, destroy(_)).Times(1);
    EXPECT_CALL(*mIncFs, setUidReadTimeouts(_, _)).Times(0);
    EXPECT_CALL(*mTimedQueue, addJob(_, _, _)).Times(2);
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_GE(storageId, 0);
    mIncrementalService->startLoading(storageId, std::move(mDataLoaderParcel), {}, {}, {},
                                      createPerUidTimeouts(
                                              {{0, 1, 2, 3}, {1, 2, 3, 4}, {2, 3, 4, 5}}));
}

TEST_F(IncrementalServiceTest, testPerUidTimeoutsSuccess) {
    mVold->setIncFsMountOptionsSuccess();
    mAppOpsManager->checkPermissionSuccess();
    mFs->hasFiles();

    EXPECT_CALL(*mIncFs, setUidReadTimeouts(_, _))
            // First call.
            .WillOnce(Invoke(&checkPerUidTimeouts))
            // Fully loaded and no readlogs.
            .WillOnce(Invoke(&checkPerUidTimeoutsEmpty));
    EXPECT_CALL(*mTimedQueue, addJob(_, _, _)).Times(3);

    // Loading storage.
    EXPECT_CALL(*mIncFs, isEverythingFullyLoaded(_))
            .WillOnce(Return(incfs::LoadingState::MissingBlocks))
            .WillOnce(Return(incfs::LoadingState::MissingBlocks))
            .WillOnce(Return(incfs::LoadingState::Full));

    // Mark DataLoader as 'system' so that readlogs don't pollute the timed queue.
    mDataLoaderParcel.packageName = "android";

    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_GE(storageId, 0);
    mIncrementalService->startLoading(storageId, std::move(mDataLoaderParcel), {}, {}, {},
                                      createPerUidTimeouts(
                                              {{0, 1, 2, 3}, {1, 2, 3, 4}, {2, 3, 4, 100000000}}));

    {
        // Timed callback present -> 0 progress.
        ASSERT_EQ(IncrementalService::kAllStoragesId, mTimedQueue->mId);
        ASSERT_GE(mTimedQueue->mAfter, std::chrono::seconds(1));
        const auto timedCallback = mTimedQueue->mWhat;
        mTimedQueue->clearJob(IncrementalService::kAllStoragesId);

        // Call it again.
        timedCallback();
    }

    {
        // Still present -> some progress.
        ASSERT_EQ(IncrementalService::kAllStoragesId, mTimedQueue->mId);
        ASSERT_GE(mTimedQueue->mAfter, std::chrono::seconds(1));
        const auto timedCallback = mTimedQueue->mWhat;
        mTimedQueue->clearJob(IncrementalService::kAllStoragesId);

        // Fully loaded but readlogs collection enabled.
        ASSERT_GE(mDataLoader->setStorageParams(true), 0);

        // Call it again.
        timedCallback();
    }

    {
        // Still present -> fully loaded + readlogs.
        ASSERT_EQ(IncrementalService::kAllStoragesId, mTimedQueue->mId);
        ASSERT_GE(mTimedQueue->mAfter, std::chrono::seconds(1));
        const auto timedCallback = mTimedQueue->mWhat;
        mTimedQueue->clearJob(IncrementalService::kAllStoragesId);

        // Now disable readlogs.
        ASSERT_GE(mDataLoader->setStorageParams(false), 0);

        // Call it again.
        timedCallback();
    }

    // No callbacks anymore -> fully loaded and no readlogs.
    ASSERT_EQ(mTimedQueue->mAfter, Milliseconds());
}

TEST_F(IncrementalServiceTest, testInvalidMetricsQuery) {
    const auto invalidStorageId = 100;
    android::os::PersistableBundle result{};
    mIncrementalService->getMetrics(invalidStorageId, &result);
    int64_t expected = -1, value = -1;
    ASSERT_FALSE(
            result.getLong(String16(BnIncrementalService::METRICS_MILLIS_SINCE_OLDEST_PENDING_READ()
                                            .c_str()),
                           &value));
    ASSERT_EQ(expected, value);
    ASSERT_TRUE(result.empty());
}

TEST_F(IncrementalServiceTest, testNoDataLoaderMetrics) {
    mVold->setIncFsMountOptionsSuccess();
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_GE(storageId, 0);
    android::os::PersistableBundle result{};
    mIncrementalService->getMetrics(storageId, &result);
    int64_t expected = -1, value = -1;
    ASSERT_FALSE(
            result.getLong(String16(BnIncrementalService::METRICS_MILLIS_SINCE_OLDEST_PENDING_READ()
                                            .c_str()),
                           &value));
    ASSERT_EQ(expected, value);
    ASSERT_EQ(1, (int)result.size());
    bool expectedReadLogsEnabled = false;
    ASSERT_TRUE(
            result.getBoolean(String16(BnIncrementalService::METRICS_READ_LOGS_ENABLED().c_str()),
                              &expectedReadLogsEnabled));
    ASSERT_EQ(mVold->readLogsEnabled(), expectedReadLogsEnabled);
}

TEST_F(IncrementalServiceTest, testInvalidMetricsKeys) {
    mVold->setIncFsMountOptionsSuccess();
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_GE(storageId, 0);
    ASSERT_TRUE(mIncrementalService->startLoading(storageId, std::move(mDataLoaderParcel), {}, {},
                                                  {}, {}));
    android::os::PersistableBundle result{};
    mIncrementalService->getMetrics(storageId, &result);
    int64_t expected = -1, value = -1;
    ASSERT_FALSE(result.getLong(String16("invalid"), &value));
    ASSERT_EQ(expected, value);
    ASSERT_EQ(6, (int)result.size());
}

TEST_F(IncrementalServiceTest, testMetricsWithNoLastReadError) {
    mVold->setIncFsMountOptionsSuccess();
    ON_CALL(*mIncFs, getMetrics(_))
            .WillByDefault(Return(Metrics{
                    .readsDelayedMin = 10,
                    .readsDelayedMinUs = 5000,
                    .readsDelayedPending = 10,
                    .readsDelayedPendingUs = 5000,
                    .readsFailedHashVerification = 10,
                    .readsFailedOther = 10,
                    .readsFailedTimedOut = 10,
            }));
    ON_CALL(*mIncFs, getLastReadError(_)).WillByDefault(Return(LastReadError{}));
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_GE(storageId, 0);
    ASSERT_TRUE(mIncrementalService->startLoading(storageId, std::move(mDataLoaderParcel), {}, {},
                                                  {}, {}));
    android::os::PersistableBundle result{};
    mIncrementalService->getMetrics(storageId, &result);
    ASSERT_EQ(9, (int)result.size());

    int expectedtotalDelayedReads = 20, totalDelayedReads = -1;
    ASSERT_TRUE(result.getInt(String16(BnIncrementalService::METRICS_TOTAL_DELAYED_READS().c_str()),
                              &totalDelayedReads));
    ASSERT_EQ(expectedtotalDelayedReads, totalDelayedReads);
    int expectedtotalFailedReads = 30, totalFailedReads = -1;
    ASSERT_TRUE(result.getInt(String16(BnIncrementalService::METRICS_TOTAL_FAILED_READS().c_str()),
                              &totalFailedReads));
    ASSERT_EQ(expectedtotalFailedReads, totalFailedReads);
    int64_t expectedtotalDelayedReadsMillis = 10, totalDelayedReadsMillis = -1;
    ASSERT_TRUE(result.getLong(String16(BnIncrementalService::METRICS_TOTAL_DELAYED_READS_MILLIS()
                                                .c_str()),
                               &totalDelayedReadsMillis));
    ASSERT_EQ(expectedtotalDelayedReadsMillis, totalDelayedReadsMillis);

    int64_t expectedMillisSinceLastReadError = -1, millisSinceLastReadError = -1;
    ASSERT_FALSE(
            result.getLong(String16(BnIncrementalService::METRICS_MILLIS_SINCE_LAST_READ_ERROR()
                                            .c_str()),
                           &millisSinceLastReadError));
    ASSERT_EQ(expectedMillisSinceLastReadError, millisSinceLastReadError);
    int expectedLastReadErrorNumber = -1, lastReadErrorNumber = -1;
    ASSERT_FALSE(
            result.getInt(String16(BnIncrementalService::METRICS_LAST_READ_ERROR_NUMBER().c_str()),
                          &lastReadErrorNumber));
    ASSERT_EQ(expectedLastReadErrorNumber, lastReadErrorNumber);
    int expectedLastReadUid = -1, lastReadErrorUid = -1;
    ASSERT_FALSE(
            result.getInt(String16(BnIncrementalService::METRICS_LAST_READ_ERROR_UID().c_str()),
                          &lastReadErrorUid));
    ASSERT_EQ(expectedLastReadUid, lastReadErrorUid);
}

TEST_F(IncrementalServiceTest, testMetricsWithLastReadError) {
    mVold->setIncFsMountOptionsSuccess();
    ON_CALL(*mIncFs, getMetrics(_)).WillByDefault(Return(Metrics{}));
    mClock->advanceMs(5);
    const auto now = mClock->getClock();
    ON_CALL(*mIncFs, getLastReadError(_))
            .WillByDefault(Return(LastReadError{.timestampUs = static_cast<uint64_t>(
                                                        duration_cast<std::chrono::microseconds>(
                                                                now.time_since_epoch())
                                                                .count()),
                                                .errorNo = static_cast<uint32_t>(-ETIME),
                                                .uid = 20000}));
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, mDataLoaderParcel,
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_GE(storageId, 0);
    ASSERT_TRUE(mIncrementalService->startLoading(storageId, std::move(mDataLoaderParcel), {}, {},
                                                  {}, {}));
    mClock->advanceMs(10);
    android::os::PersistableBundle result{};
    mIncrementalService->getMetrics(storageId, &result);
    ASSERT_EQ(12, (int)result.size());
    int64_t expectedMillisSinceLastReadError = 10, millisSinceLastReadError = -1;
    ASSERT_TRUE(result.getLong(String16(BnIncrementalService::METRICS_MILLIS_SINCE_LAST_READ_ERROR()
                                                .c_str()),
                               &millisSinceLastReadError));
    ASSERT_EQ(expectedMillisSinceLastReadError, millisSinceLastReadError);
    int expectedLastReadErrorNumber = -ETIME, lastReadErrorNumber = -1;
    ASSERT_TRUE(
            result.getInt(String16(BnIncrementalService::METRICS_LAST_READ_ERROR_NUMBER().c_str()),
                          &lastReadErrorNumber));
    ASSERT_EQ(expectedLastReadErrorNumber, lastReadErrorNumber);
    int expectedLastReadUid = 20000, lastReadErrorUid = -1;
    ASSERT_TRUE(result.getInt(String16(BnIncrementalService::METRICS_LAST_READ_ERROR_UID().c_str()),
                              &lastReadErrorUid));
    ASSERT_EQ(expectedLastReadUid, lastReadErrorUid);
}

} // namespace android::os::incremental
