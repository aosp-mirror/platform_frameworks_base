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

namespace android::os::incremental {

class MockVoldService : public VoldServiceWrapper {
public:
    MOCK_CONST_METHOD4(mountIncFs,
                       binder::Status(const std::string& backingPath, const std::string& targetDir,
                                      int32_t flags,
                                      IncrementalFileSystemControlParcel* _aidl_return));
    MOCK_CONST_METHOD1(unmountIncFs, binder::Status(const std::string& dir));
    MOCK_CONST_METHOD2(bindMount,
                       binder::Status(const std::string& sourceDir, const std::string& argetDir));
    MOCK_CONST_METHOD2(setIncFsMountOptions,
                       binder::Status(const ::android::os::incremental::IncrementalFileSystemControlParcel&, bool));

    void mountIncFsFails() {
        ON_CALL(*this, mountIncFs(_, _, _, _))
                .WillByDefault(
                        Return(binder::Status::fromExceptionCode(1, String8("failed to mount"))));
    }
    void mountIncFsInvalidControlParcel() {
        ON_CALL(*this, mountIncFs(_, _, _, _))
                .WillByDefault(Invoke(this, &MockVoldService::getInvalidControlParcel));
    }
    void mountIncFsSuccess() {
        ON_CALL(*this, mountIncFs(_, _, _, _))
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
        ON_CALL(*this, setIncFsMountOptions(_, _))
                .WillByDefault(
                        Return(binder::Status::fromExceptionCode(1, String8("failed to set options"))));
    }
    void setIncFsMountOptionsSuccess() {
        ON_CALL(*this, setIncFsMountOptions(_, _)).WillByDefault(Return(binder::Status::ok()));
    }
    binder::Status getInvalidControlParcel(const std::string& imagePath,
                                           const std::string& targetDir, int32_t flags,
                                           IncrementalFileSystemControlParcel* _aidl_return) {
        _aidl_return = {};
        return binder::Status::ok();
    }
    binder::Status incFsSuccess(const std::string& imagePath, const std::string& targetDir,
                                int32_t flags, IncrementalFileSystemControlParcel* _aidl_return) {
        _aidl_return->pendingReads.reset(base::unique_fd(dup(STDIN_FILENO)));
        _aidl_return->cmd.reset(base::unique_fd(dup(STDIN_FILENO)));
        _aidl_return->log.reset(base::unique_fd(dup(STDIN_FILENO)));
        return binder::Status::ok();
    }

private:
    TemporaryFile cmdFile;
    TemporaryFile logFile;
};

class MockDataLoader : public IDataLoader {
public:
    MockDataLoader() {
        ON_CALL(*this, create(_, _, _, _)).WillByDefault(Invoke(this, &MockDataLoader::createOk));
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

    void initializeCreateOkNoStatus() {
        ON_CALL(*this, create(_, _, _, _))
                .WillByDefault(Invoke(this, &MockDataLoader::createOkNoStatus));
    }

    binder::Status createOk(int32_t id, const content::pm::DataLoaderParamsParcel& params,
                            const content::pm::FileSystemControlParcel& control,
                            const sp<content::pm::IDataLoaderStatusListener>& listener) {
        createOkNoStatus(id, params, control, listener);
        if (mListener) {
            mListener->onStatusChanged(id, IDataLoaderStatusListener::DATA_LOADER_CREATED);
        }
        return binder::Status::ok();
    }
    binder::Status createOkNoStatus(int32_t id, const content::pm::DataLoaderParamsParcel& params,
                                    const content::pm::FileSystemControlParcel& control,
                                    const sp<content::pm::IDataLoaderStatusListener>& listener) {
        mServiceConnector = control.service;
        mListener = listener;
        return binder::Status::ok();
    }
    binder::Status startOk(int32_t id) {
        if (mListener) {
            mListener->onStatusChanged(id, IDataLoaderStatusListener::DATA_LOADER_STARTED);
        }
        return binder::Status::ok();
    }
    binder::Status stopOk(int32_t id) {
        if (mListener) {
            mListener->onStatusChanged(id, IDataLoaderStatusListener::DATA_LOADER_STOPPED);
        }
        return binder::Status::ok();
    }
    binder::Status destroyOk(int32_t id) {
        if (mListener) {
            mListener->onStatusChanged(id, IDataLoaderStatusListener::DATA_LOADER_DESTROYED);
        }
        mListener = nullptr;
        return binder::Status::ok();
    }
    binder::Status prepareImageOk(int32_t id,
                                  const ::std::vector<content::pm::InstallationFileParcel>&,
                                  const ::std::vector<::std::string>&) {
        if (mListener) {
            mListener->onStatusChanged(id, IDataLoaderStatusListener::DATA_LOADER_IMAGE_READY);
        }
        return binder::Status::ok();
    }
    int32_t setStorageParams(bool enableReadLogs) {
        int32_t result = -1;
        EXPECT_NE(mServiceConnector.get(), nullptr);
        EXPECT_TRUE(mServiceConnector->setStorageParams(enableReadLogs, &result).isOk());
        return result;
    }

private:
    sp<IIncrementalServiceConnector> mServiceConnector;
    sp<IDataLoaderStatusListener> mListener;
};

class MockDataLoaderManager : public DataLoaderManagerWrapper {
public:
    MockDataLoaderManager(sp<IDataLoader> dataLoader) : mDataLoaderHolder(std::move(dataLoader)) {
        EXPECT_TRUE(mDataLoaderHolder != nullptr);
    }

    MOCK_CONST_METHOD4(bindToDataLoader,
                       binder::Status(int32_t mountId, const DataLoaderParamsParcel& params,
                                      const sp<IDataLoaderStatusListener>& listener,
                                      bool* _aidl_return));
    MOCK_CONST_METHOD2(getDataLoader,
                       binder::Status(int32_t mountId, sp<IDataLoader>* _aidl_return));
    MOCK_CONST_METHOD1(unbindFromDataLoader, binder::Status(int32_t mountId));

    void bindToDataLoaderSuccess() {
        ON_CALL(*this, bindToDataLoader(_, _, _, _))
                .WillByDefault(Invoke(this, &MockDataLoaderManager::bindToDataLoaderOk));
    }
    void bindToDataLoaderFails() {
        ON_CALL(*this, bindToDataLoader(_, _, _, _))
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
                                      const sp<IDataLoaderStatusListener>& listener,
                                      bool* _aidl_return) {
        mId = mountId;
        mListener = listener;
        mDataLoader = mDataLoaderHolder;
        *_aidl_return = true;
        if (mListener) {
            mListener->onStatusChanged(mId, IDataLoaderStatusListener::DATA_LOADER_BOUND);
        }
        return binder::Status::ok();
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
    binder::Status unbindFromDataLoaderOk(int32_t id) {
        if (mDataLoader) {
            if (auto status = mDataLoader->destroy(id); !status.isOk()) {
                return status;
            }
            mDataLoader = nullptr;
        }
        if (mListener) {
            mListener->onStatusChanged(id, IDataLoaderStatusListener::DATA_LOADER_DESTROYED);
        }
        return binder::Status::ok();
    }

private:
    int mId;
    sp<IDataLoaderStatusListener> mListener;
    sp<IDataLoader> mDataLoader;
    sp<IDataLoader> mDataLoaderHolder;
};

class MockIncFs : public IncFsWrapper {
public:
    MOCK_CONST_METHOD1(listExistingMounts, void(const ExistingMountCallback& cb));
    MOCK_CONST_METHOD1(openMount, Control(std::string_view path));
    MOCK_CONST_METHOD3(createControl, Control(IncFsFd cmd, IncFsFd pendingReads, IncFsFd logs));
    MOCK_CONST_METHOD5(makeFile,
                       ErrorCode(const Control& control, std::string_view path, int mode, FileId id,
                                 NewFileParams params));
    MOCK_CONST_METHOD3(makeDir, ErrorCode(const Control& control, std::string_view path, int mode));
    MOCK_CONST_METHOD3(makeDirs,
                       ErrorCode(const Control& control, std::string_view path, int mode));
    MOCK_CONST_METHOD2(getMetadata, RawMetadata(const Control& control, FileId fileid));
    MOCK_CONST_METHOD2(getMetadata, RawMetadata(const Control& control, std::string_view path));
    MOCK_CONST_METHOD2(getFileId, FileId(const Control& control, std::string_view path));
    MOCK_CONST_METHOD3(link,
                       ErrorCode(const Control& control, std::string_view from, std::string_view to));
    MOCK_CONST_METHOD2(unlink, ErrorCode(const Control& control, std::string_view path));
    MOCK_CONST_METHOD2(openForSpecialOps, base::unique_fd(const Control& control, FileId id));
    MOCK_CONST_METHOD1(writeBlocks, ErrorCode(std::span<const DataBlock> blocks));
    MOCK_CONST_METHOD3(waitForPendingReads,
                       WaitResult(const Control& control, std::chrono::milliseconds timeout,
                                  std::vector<incfs::ReadInfo>* pendingReadsBuffer));

    MockIncFs() { ON_CALL(*this, listExistingMounts(_)).WillByDefault(Return()); }

    void makeFileFails() { ON_CALL(*this, makeFile(_, _, _, _, _)).WillByDefault(Return(-1)); }
    void makeFileSuccess() { ON_CALL(*this, makeFile(_, _, _, _, _)).WillByDefault(Return(0)); }
    void openMountSuccess() {
        ON_CALL(*this, openMount(_)).WillByDefault(Invoke(this, &MockIncFs::openMountForHealth));
    }
    void waitForPendingReadsSuccess() {
        ON_CALL(*this, waitForPendingReads(_, _, _))
                .WillByDefault(Invoke(this, &MockIncFs::waitForPendingReadsForHealth));
    }

    static constexpr auto kPendingReadsFd = 42;
    Control openMountForHealth(std::string_view) {
        return UniqueControl(IncFs_CreateControl(-1, kPendingReadsFd, -1));
    }

    WaitResult waitForPendingReadsForHealth(
            const Control& control, std::chrono::milliseconds timeout,
            std::vector<incfs::ReadInfo>* pendingReadsBuffer) const {
        pendingReadsBuffer->push_back({.bootClockTsUs = 0});
        return android::incfs::WaitResult::HaveData;
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
        ON_CALL(*this, pollAll(_)).WillByDefault(Invoke(this, &MockLooperWrapper::sleepFor));
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

    int sleepFor(int timeoutMillis) {
        std::this_thread::sleep_for(std::chrono::milliseconds(timeoutMillis));
        return 0;
    }

    android::Looper_callbackFunc mCallback = nullptr;
    void* mCallbackData = nullptr;
};

class MockServiceManager : public ServiceManagerWrapper {
public:
    MockServiceManager(std::unique_ptr<MockVoldService> vold,
                       std::unique_ptr<MockDataLoaderManager> dataLoaderManager,
                       std::unique_ptr<MockIncFs> incfs,
                       std::unique_ptr<MockAppOpsManager> appOpsManager,
                       std::unique_ptr<MockJniWrapper> jni,
                       std::unique_ptr<MockLooperWrapper> looper)
          : mVold(std::move(vold)),
            mDataLoaderManager(std::move(dataLoaderManager)),
            mIncFs(std::move(incfs)),
            mAppOpsManager(std::move(appOpsManager)),
            mJni(std::move(jni)),
            mLooper(std::move(looper)) {}
    std::unique_ptr<VoldServiceWrapper> getVoldService() final { return std::move(mVold); }
    std::unique_ptr<DataLoaderManagerWrapper> getDataLoaderManager() final {
        return std::move(mDataLoaderManager);
    }
    std::unique_ptr<IncFsWrapper> getIncFs() final { return std::move(mIncFs); }
    std::unique_ptr<AppOpsManagerWrapper> getAppOpsManager() final { return std::move(mAppOpsManager); }
    std::unique_ptr<JniWrapper> getJni() final { return std::move(mJni); }
    std::unique_ptr<LooperWrapper> getLooper() final { return std::move(mLooper); }

private:
    std::unique_ptr<MockVoldService> mVold;
    std::unique_ptr<MockDataLoaderManager> mDataLoaderManager;
    std::unique_ptr<MockIncFs> mIncFs;
    std::unique_ptr<MockAppOpsManager> mAppOpsManager;
    std::unique_ptr<MockJniWrapper> mJni;
    std::unique_ptr<MockLooperWrapper> mLooper;
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
        mIncrementalService =
                std::make_unique<IncrementalService>(MockServiceManager(std::move(vold),
                                                                        std::move(
                                                                                dataloaderManager),
                                                                        std::move(incFs),
                                                                        std::move(appOps),
                                                                        std::move(jni),
                                                                        std::move(looper)),
                                                     mRootDir.path);
        mDataLoaderParcel.packageName = "com.test";
        mDataLoaderParcel.arguments = "uri";
        mDataLoaderManager->unbindFromDataLoaderSuccess();
        mIncrementalService->onSystemReady();
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

protected:
    NiceMock<MockVoldService>* mVold = nullptr;
    NiceMock<MockIncFs>* mIncFs = nullptr;
    NiceMock<MockDataLoaderManager>* mDataLoaderManager = nullptr;
    NiceMock<MockAppOpsManager>* mAppOpsManager = nullptr;
    NiceMock<MockJniWrapper>* mJni = nullptr;
    NiceMock<MockLooperWrapper>* mLooper = nullptr;
    NiceMock<MockDataLoader>* mDataLoader = nullptr;
    std::unique_ptr<IncrementalService> mIncrementalService;
    TemporaryDir mRootDir;
    DataLoaderParamsParcel mDataLoaderParcel;
};

TEST_F(IncrementalServiceTest, testCreateStorageMountIncFsFails) {
    mVold->mountIncFsFails();
    EXPECT_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _)).Times(0);
    TemporaryDir tempDir;
    int storageId = mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel),
                                                       IncrementalService::CreateOptions::CreateNew,
                                                       {}, {}, {});
    ASSERT_LT(storageId, 0);
}

TEST_F(IncrementalServiceTest, testCreateStorageMountIncFsInvalidControlParcel) {
    mVold->mountIncFsInvalidControlParcel();
    EXPECT_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _)).Times(0);
    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_)).Times(0);
    TemporaryDir tempDir;
    int storageId = mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel),
                                                       IncrementalService::CreateOptions::CreateNew,
                                                       {}, {}, {});
    ASSERT_LT(storageId, 0);
}

TEST_F(IncrementalServiceTest, testCreateStorageMakeFileFails) {
    mVold->mountIncFsSuccess();
    mIncFs->makeFileFails();
    EXPECT_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _)).Times(0);
    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_)).Times(0);
    EXPECT_CALL(*mVold, unmountIncFs(_));
    TemporaryDir tempDir;
    int storageId = mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel),
                                                       IncrementalService::CreateOptions::CreateNew,
                                                       {}, {}, {});
    ASSERT_LT(storageId, 0);
}

TEST_F(IncrementalServiceTest, testCreateStorageBindMountFails) {
    mVold->mountIncFsSuccess();
    mIncFs->makeFileSuccess();
    mVold->bindMountFails();
    EXPECT_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _)).Times(0);
    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_)).Times(0);
    EXPECT_CALL(*mVold, unmountIncFs(_));
    TemporaryDir tempDir;
    int storageId = mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel),
                                                       IncrementalService::CreateOptions::CreateNew,
                                                       {}, {}, {});
    ASSERT_LT(storageId, 0);
}

TEST_F(IncrementalServiceTest, testCreateStoragePrepareDataLoaderFails) {
    mVold->mountIncFsSuccess();
    mIncFs->makeFileSuccess();
    mVold->bindMountSuccess();
    mDataLoaderManager->bindToDataLoaderFails();
    EXPECT_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _)).Times(1);
    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_)).Times(0);
    EXPECT_CALL(*mDataLoader, create(_, _, _, _)).Times(0);
    EXPECT_CALL(*mDataLoader, start(_)).Times(0);
    EXPECT_CALL(*mDataLoader, destroy(_)).Times(0);
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    TemporaryDir tempDir;
    int storageId = mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel),
                                                       IncrementalService::CreateOptions::CreateNew,
                                                       {}, {}, {});
    ASSERT_LT(storageId, 0);
}

TEST_F(IncrementalServiceTest, testDeleteStorageSuccess) {
    mVold->mountIncFsSuccess();
    mIncFs->makeFileSuccess();
    mVold->bindMountSuccess();
    mDataLoaderManager->bindToDataLoaderSuccess();
    mDataLoaderManager->getDataLoaderSuccess();
    EXPECT_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _)).Times(1);
    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_)).Times(1);
    EXPECT_CALL(*mDataLoader, create(_, _, _, _)).Times(1);
    EXPECT_CALL(*mDataLoader, start(_)).Times(0);
    EXPECT_CALL(*mDataLoader, destroy(_)).Times(1);
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    TemporaryDir tempDir;
    int storageId = mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel),
                                                       IncrementalService::CreateOptions::CreateNew,
                                                       {}, {}, {});
    ASSERT_GE(storageId, 0);
    mIncrementalService->deleteStorage(storageId);
}

TEST_F(IncrementalServiceTest, testDataLoaderDestroyed) {
    mVold->mountIncFsSuccess();
    mIncFs->makeFileSuccess();
    mVold->bindMountSuccess();
    mDataLoaderManager->bindToDataLoaderSuccess();
    mDataLoaderManager->getDataLoaderSuccess();
    EXPECT_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _)).Times(2);
    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_)).Times(1);
    EXPECT_CALL(*mDataLoader, create(_, _, _, _)).Times(2);
    EXPECT_CALL(*mDataLoader, start(_)).Times(0);
    EXPECT_CALL(*mDataLoader, destroy(_)).Times(1);
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    TemporaryDir tempDir;
    int storageId = mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel),
                                                       IncrementalService::CreateOptions::CreateNew,
                                                       {}, {}, {});
    ASSERT_GE(storageId, 0);
    // Simulated crash/other connection breakage.
    mDataLoaderManager->setDataLoaderStatusDestroyed();
}

TEST_F(IncrementalServiceTest, testStartDataLoaderCreate) {
    mVold->mountIncFsSuccess();
    mIncFs->makeFileSuccess();
    mVold->bindMountSuccess();
    mDataLoader->initializeCreateOkNoStatus();
    mDataLoaderManager->bindToDataLoaderSuccess();
    mDataLoaderManager->getDataLoaderSuccess();
    EXPECT_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _)).Times(1);
    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_)).Times(1);
    EXPECT_CALL(*mDataLoader, create(_, _, _, _)).Times(1);
    EXPECT_CALL(*mDataLoader, start(_)).Times(1);
    EXPECT_CALL(*mDataLoader, destroy(_)).Times(1);
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    TemporaryDir tempDir;
    int storageId = mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel),
                                                       IncrementalService::CreateOptions::CreateNew,
                                                       {}, {}, {});
    ASSERT_GE(storageId, 0);
    mDataLoaderManager->setDataLoaderStatusCreated();
    ASSERT_TRUE(mIncrementalService->startLoading(storageId));
    mDataLoaderManager->setDataLoaderStatusStarted();
}

TEST_F(IncrementalServiceTest, testStartDataLoaderPendingStart) {
    mVold->mountIncFsSuccess();
    mIncFs->makeFileSuccess();
    mVold->bindMountSuccess();
    mDataLoader->initializeCreateOkNoStatus();
    mDataLoaderManager->bindToDataLoaderSuccess();
    mDataLoaderManager->getDataLoaderSuccess();
    EXPECT_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _)).Times(1);
    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_)).Times(1);
    EXPECT_CALL(*mDataLoader, create(_, _, _, _)).Times(2);
    EXPECT_CALL(*mDataLoader, start(_)).Times(1);
    EXPECT_CALL(*mDataLoader, destroy(_)).Times(1);
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    TemporaryDir tempDir;
    int storageId = mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel),
                                                       IncrementalService::CreateOptions::CreateNew,
                                                       {}, {}, {});
    ASSERT_GE(storageId, 0);
    ASSERT_TRUE(mIncrementalService->startLoading(storageId));
    mDataLoaderManager->setDataLoaderStatusCreated();
}

TEST_F(IncrementalServiceTest, testStartDataLoaderCreateUnavailable) {
    mVold->mountIncFsSuccess();
    mIncFs->makeFileSuccess();
    mVold->bindMountSuccess();
    mDataLoader->initializeCreateOkNoStatus();
    mDataLoaderManager->bindToDataLoaderSuccess();
    mDataLoaderManager->getDataLoaderSuccess();
    EXPECT_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _)).Times(1);
    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_)).Times(1);
    EXPECT_CALL(*mDataLoader, create(_, _, _, _)).Times(1);
    EXPECT_CALL(*mDataLoader, start(_)).Times(0);
    EXPECT_CALL(*mDataLoader, destroy(_)).Times(1);
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    TemporaryDir tempDir;
    int storageId = mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel),
                                                       IncrementalService::CreateOptions::CreateNew,
                                                       {}, {}, {});
    ASSERT_GE(storageId, 0);
    mDataLoaderManager->setDataLoaderStatusUnavailable();
}

TEST_F(IncrementalServiceTest, testStartDataLoaderRecreateOnPendingReads) {
    mVold->mountIncFsSuccess();
    mIncFs->makeFileSuccess();
    mIncFs->openMountSuccess();
    mIncFs->waitForPendingReadsSuccess();
    mVold->bindMountSuccess();
    mDataLoader->initializeCreateOkNoStatus();
    mDataLoaderManager->bindToDataLoaderSuccess();
    mDataLoaderManager->getDataLoaderSuccess();
    EXPECT_CALL(*mDataLoaderManager, bindToDataLoader(_, _, _, _)).Times(2);
    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_)).Times(2);
    EXPECT_CALL(*mDataLoader, create(_, _, _, _)).Times(2);
    EXPECT_CALL(*mDataLoader, start(_)).Times(0);
    EXPECT_CALL(*mDataLoader, destroy(_)).Times(2);
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    EXPECT_CALL(*mLooper, addFd(MockIncFs::kPendingReadsFd, _, _, _, _)).Times(1);
    EXPECT_CALL(*mLooper, removeFd(MockIncFs::kPendingReadsFd)).Times(1);
    TemporaryDir tempDir;
    int storageId = mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel),
                                                       IncrementalService::CreateOptions::CreateNew,
                                                       {}, {}, {});
    ASSERT_GE(storageId, 0);
    mDataLoaderManager->setDataLoaderStatusUnavailable();
    ASSERT_NE(nullptr, mLooper->mCallback);
    ASSERT_NE(nullptr, mLooper->mCallbackData);
    mLooper->mCallback(-1, -1, mLooper->mCallbackData);
}

TEST_F(IncrementalServiceTest, testSetIncFsMountOptionsSuccess) {
    mVold->mountIncFsSuccess();
    mIncFs->makeFileSuccess();
    mVold->bindMountSuccess();
    mVold->setIncFsMountOptionsSuccess();
    mDataLoaderManager->bindToDataLoaderSuccess();
    mDataLoaderManager->getDataLoaderSuccess();
    mAppOpsManager->checkPermissionSuccess();
    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_));
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    // We are calling setIncFsMountOptions(true).
    EXPECT_CALL(*mVold, setIncFsMountOptions(_, true)).Times(1);
    // After setIncFsMountOptions succeeded expecting to start watching.
    EXPECT_CALL(*mAppOpsManager, startWatchingMode(_, _, _)).Times(1);
    // Not expecting callback removal.
    EXPECT_CALL(*mAppOpsManager, stopWatchingMode(_)).Times(0);
    TemporaryDir tempDir;
    int storageId = mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel),
                                                       IncrementalService::CreateOptions::CreateNew,
                                                       {}, {}, {});
    ASSERT_GE(storageId, 0);
    ASSERT_GE(mDataLoader->setStorageParams(true), 0);
}

TEST_F(IncrementalServiceTest, testSetIncFsMountOptionsSuccessAndPermissionChanged) {
    mVold->mountIncFsSuccess();
    mIncFs->makeFileSuccess();
    mVold->bindMountSuccess();
    mVold->setIncFsMountOptionsSuccess();
    mDataLoaderManager->bindToDataLoaderSuccess();
    mDataLoaderManager->getDataLoaderSuccess();
    mAppOpsManager->checkPermissionSuccess();
    mAppOpsManager->initializeStartWatchingMode();
    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_));
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    // We are calling setIncFsMountOptions(true).
    EXPECT_CALL(*mVold, setIncFsMountOptions(_, true)).Times(1);
    // setIncFsMountOptions(false) is called on the callback.
    EXPECT_CALL(*mVold, setIncFsMountOptions(_, false)).Times(1);
    // After setIncFsMountOptions succeeded expecting to start watching.
    EXPECT_CALL(*mAppOpsManager, startWatchingMode(_, _, _)).Times(1);
    // After callback is called, disable read logs and remove callback.
    EXPECT_CALL(*mAppOpsManager, stopWatchingMode(_)).Times(1);
    TemporaryDir tempDir;
    int storageId = mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel),
                                                       IncrementalService::CreateOptions::CreateNew,
                                                       {}, {}, {});
    ASSERT_GE(storageId, 0);
    ASSERT_GE(mDataLoader->setStorageParams(true), 0);
    ASSERT_NE(nullptr, mAppOpsManager->mStoredCallback.get());
    mAppOpsManager->mStoredCallback->opChanged(0, {});
}

TEST_F(IncrementalServiceTest, testSetIncFsMountOptionsCheckPermissionFails) {
    mVold->mountIncFsSuccess();
    mIncFs->makeFileSuccess();
    mVold->bindMountSuccess();
    mDataLoaderManager->bindToDataLoaderSuccess();
    mDataLoaderManager->getDataLoaderSuccess();
    mAppOpsManager->checkPermissionFails();
    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_));
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    // checkPermission fails, no calls to set opitions,  start or stop WatchingMode.
    EXPECT_CALL(*mVold, setIncFsMountOptions(_, true)).Times(0);
    EXPECT_CALL(*mAppOpsManager, startWatchingMode(_, _, _)).Times(0);
    EXPECT_CALL(*mAppOpsManager, stopWatchingMode(_)).Times(0);
    TemporaryDir tempDir;
    int storageId = mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel),
                                                       IncrementalService::CreateOptions::CreateNew,
                                                       {}, {}, {});
    ASSERT_GE(storageId, 0);
    ASSERT_LT(mDataLoader->setStorageParams(true), 0);
}

TEST_F(IncrementalServiceTest, testSetIncFsMountOptionsFails) {
    mVold->mountIncFsSuccess();
    mIncFs->makeFileSuccess();
    mVold->bindMountSuccess();
    mVold->setIncFsMountOptionsFails();
    mDataLoaderManager->bindToDataLoaderSuccess();
    mDataLoaderManager->getDataLoaderSuccess();
    mAppOpsManager->checkPermissionSuccess();
    EXPECT_CALL(*mDataLoaderManager, unbindFromDataLoader(_));
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    // We are calling setIncFsMountOptions.
    EXPECT_CALL(*mVold, setIncFsMountOptions(_, true)).Times(1);
    // setIncFsMountOptions fails, no calls to start or stop WatchingMode.
    EXPECT_CALL(*mAppOpsManager, startWatchingMode(_, _, _)).Times(0);
    EXPECT_CALL(*mAppOpsManager, stopWatchingMode(_)).Times(0);
    TemporaryDir tempDir;
    int storageId = mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel),
                                                       IncrementalService::CreateOptions::CreateNew,
                                                       {}, {}, {});
    ASSERT_GE(storageId, 0);
    ASSERT_LT(mDataLoader->setStorageParams(true), 0);
}

TEST_F(IncrementalServiceTest, testMakeDirectory) {
    mVold->mountIncFsSuccess();
    mIncFs->makeFileSuccess();
    mVold->bindMountSuccess();
    mDataLoaderManager->bindToDataLoaderSuccess();
    mDataLoaderManager->getDataLoaderSuccess();
    TemporaryDir tempDir;
    int storageId = mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel),
                                                       IncrementalService::CreateOptions::CreateNew,
                                                       {}, {}, {});
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
    mVold->mountIncFsSuccess();
    mIncFs->makeFileSuccess();
    mVold->bindMountSuccess();
    mDataLoaderManager->bindToDataLoaderSuccess();
    mDataLoaderManager->getDataLoaderSuccess();
    TemporaryDir tempDir;
    int storageId = mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel),
                                                       IncrementalService::CreateOptions::CreateNew,
                                                       {}, {}, {});
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
} // namespace android::os::incremental
