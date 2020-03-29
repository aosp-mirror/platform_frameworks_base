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

class FakeDataLoader : public IDataLoader {
public:
    IBinder* onAsBinder() override { return nullptr; }
    binder::Status create(int32_t, const DataLoaderParamsParcel&, const FileSystemControlParcel&,
                          const sp<IDataLoaderStatusListener>&) override {
        return binder::Status::ok();
    }
    binder::Status start(int32_t) override { return binder::Status::ok(); }
    binder::Status stop(int32_t) override { return binder::Status::ok(); }
    binder::Status destroy(int32_t) override { return binder::Status::ok(); }
    binder::Status prepareImage(int32_t,
                                const std::vector<InstallationFileParcel>&,
                                const std::vector<std::string>&) override {
        return binder::Status::ok();
    }
};

class MockDataLoaderManager : public DataLoaderManagerWrapper {
public:
    MOCK_CONST_METHOD5(initializeDataLoader,
                       binder::Status(int32_t mountId, const DataLoaderParamsParcel& params,
                                      const FileSystemControlParcel& control,
                                      const sp<IDataLoaderStatusListener>& listener,
                                      bool* _aidl_return));
    MOCK_CONST_METHOD2(getDataLoader,
                       binder::Status(int32_t mountId, sp<IDataLoader>* _aidl_return));
    MOCK_CONST_METHOD1(destroyDataLoader, binder::Status(int32_t mountId));

    binder::Status initializeDataLoaderOk(int32_t mountId, const DataLoaderParamsParcel& params,
                                          const FileSystemControlParcel& control,
                                          const sp<IDataLoaderStatusListener>& listener,
                                          bool* _aidl_return) {
        mId = mountId;
        mListener = listener;
        *_aidl_return = true;
        return binder::Status::ok();
    }

    binder::Status getDataLoaderOk(int32_t mountId, sp<IDataLoader>* _aidl_return) {
        *_aidl_return = mDataLoader;
        return binder::Status::ok();
    }

    void initializeDataLoaderFails() {
        ON_CALL(*this, initializeDataLoader(_, _, _, _, _))
                .WillByDefault(Return(
                        (binder::Status::fromExceptionCode(1, String8("failed to prepare")))));
    }
    void initializeDataLoaderSuccess() {
        ON_CALL(*this, initializeDataLoader(_, _, _, _, _))
                .WillByDefault(Invoke(this, &MockDataLoaderManager::initializeDataLoaderOk));
    }
    void getDataLoaderSuccess() {
        ON_CALL(*this, getDataLoader(_, _))
                .WillByDefault(Invoke(this, &MockDataLoaderManager::getDataLoaderOk));
    }
    void setDataLoaderStatusNotReady() {
        mListener->onStatusChanged(mId, IDataLoaderStatusListener::DATA_LOADER_DESTROYED);
    }
    void setDataLoaderStatusReady() {
        mListener->onStatusChanged(mId, IDataLoaderStatusListener::DATA_LOADER_CREATED);
    }

private:
    int mId;
    sp<IDataLoaderStatusListener> mListener;
    sp<IDataLoader> mDataLoader = sp<IDataLoader>(new FakeDataLoader());
};

class MockIncFs : public IncFsWrapper {
public:
    MOCK_CONST_METHOD3(createControl, Control(IncFsFd cmd, IncFsFd pendingReads, IncFsFd logs));
    MOCK_CONST_METHOD5(makeFile,
                       ErrorCode(const Control& control, std::string_view path, int mode, FileId id,
                                 NewFileParams params));
    MOCK_CONST_METHOD3(makeDir, ErrorCode(const Control& control, std::string_view path, int mode));
    MOCK_CONST_METHOD2(getMetadata, RawMetadata(const Control& control, FileId fileid));
    MOCK_CONST_METHOD2(getMetadata, RawMetadata(const Control& control, std::string_view path));
    MOCK_CONST_METHOD2(getFileId, FileId(const Control& control, std::string_view path));
    MOCK_CONST_METHOD3(link,
                       ErrorCode(const Control& control, std::string_view from, std::string_view to));
    MOCK_CONST_METHOD2(unlink, ErrorCode(const Control& control, std::string_view path));
    MOCK_CONST_METHOD2(openWrite, base::unique_fd(const Control& control, FileId id));
    MOCK_CONST_METHOD1(writeBlocks, ErrorCode(Span<const DataBlock> blocks));

    void makeFileFails() { ON_CALL(*this, makeFile(_, _, _, _, _)).WillByDefault(Return(-1)); }
    void makeFileSuccess() { ON_CALL(*this, makeFile(_, _, _, _, _)).WillByDefault(Return(0)); }
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

class MockServiceManager : public ServiceManagerWrapper {
public:
    MockServiceManager(std::unique_ptr<MockVoldService> vold,
                       std::unique_ptr<MockDataLoaderManager> manager,
                       std::unique_ptr<MockIncFs> incfs)
          : mVold(std::move(vold)),
            mDataLoaderManager(std::move(manager)),
            mIncFs(std::move(incfs)) {}
    std::unique_ptr<VoldServiceWrapper> getVoldService() final { return std::move(mVold); }
    std::unique_ptr<DataLoaderManagerWrapper> getDataLoaderManager() final {
        return std::move(mDataLoaderManager);
    }
    std::unique_ptr<IncFsWrapper> getIncFs() final { return std::move(mIncFs); }

private:
    std::unique_ptr<MockVoldService> mVold;
    std::unique_ptr<MockDataLoaderManager> mDataLoaderManager;
    std::unique_ptr<MockIncFs> mIncFs;
};

// --- IncrementalServiceTest ---

class IncrementalServiceTest : public testing::Test {
public:
    void SetUp() override {
        auto vold = std::make_unique<NiceMock<MockVoldService>>();
        mVold = vold.get();
        auto dataloaderManager = std::make_unique<NiceMock<MockDataLoaderManager>>();
        mDataLoaderManager = dataloaderManager.get();
        auto incFs = std::make_unique<NiceMock<MockIncFs>>();
        mIncFs = incFs.get();
        mIncrementalService =
                std::make_unique<IncrementalService>(MockServiceManager(std::move(vold),
                                                                        std::move(
                                                                                dataloaderManager),
                                                                        std::move(incFs)),
                                                     mRootDir.path);
        mDataLoaderParcel.packageName = "com.test";
        mDataLoaderParcel.arguments = "uri";
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
    NiceMock<MockVoldService>* mVold;
    NiceMock<MockIncFs>* mIncFs;
    NiceMock<MockDataLoaderManager>* mDataLoaderManager;
    std::unique_ptr<IncrementalService> mIncrementalService;
    TemporaryDir mRootDir;
    DataLoaderParamsParcel mDataLoaderParcel;
};

TEST_F(IncrementalServiceTest, testCreateStorageMountIncFsFails) {
    mVold->mountIncFsFails();
    EXPECT_CALL(*mDataLoaderManager, initializeDataLoader(_, _, _, _, _)).Times(0);
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel), {},
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_LT(storageId, 0);
}

TEST_F(IncrementalServiceTest, testCreateStorageMountIncFsInvalidControlParcel) {
    mVold->mountIncFsInvalidControlParcel();
    EXPECT_CALL(*mDataLoaderManager, initializeDataLoader(_, _, _, _, _)).Times(0);
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel), {},
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_LT(storageId, 0);
}

TEST_F(IncrementalServiceTest, testCreateStorageMakeFileFails) {
    mVold->mountIncFsSuccess();
    mIncFs->makeFileFails();
    EXPECT_CALL(*mDataLoaderManager, initializeDataLoader(_, _, _, _, _)).Times(0);
    EXPECT_CALL(*mDataLoaderManager, destroyDataLoader(_));
    EXPECT_CALL(*mVold, unmountIncFs(_));
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel), {},
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_LT(storageId, 0);
}

TEST_F(IncrementalServiceTest, testCreateStorageBindMountFails) {
    mVold->mountIncFsSuccess();
    mIncFs->makeFileSuccess();
    mVold->bindMountFails();
    EXPECT_CALL(*mDataLoaderManager, initializeDataLoader(_, _, _, _, _)).Times(0);
    EXPECT_CALL(*mDataLoaderManager, destroyDataLoader(_));
    EXPECT_CALL(*mVold, unmountIncFs(_));
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel), {},
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_LT(storageId, 0);
}

TEST_F(IncrementalServiceTest, testCreateStoragePrepareDataLoaderFails) {
    mVold->mountIncFsSuccess();
    mIncFs->makeFileSuccess();
    mVold->bindMountSuccess();
    mDataLoaderManager->initializeDataLoaderFails();
    EXPECT_CALL(*mDataLoaderManager, destroyDataLoader(_));
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel), {},
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_LT(storageId, 0);
}

TEST_F(IncrementalServiceTest, testDeleteStorageSuccess) {
    mVold->mountIncFsSuccess();
    mIncFs->makeFileSuccess();
    mVold->bindMountSuccess();
    mDataLoaderManager->initializeDataLoaderSuccess();
    EXPECT_CALL(*mDataLoaderManager, destroyDataLoader(_));
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel), {},
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_GE(storageId, 0);
    mIncrementalService->deleteStorage(storageId);
}

TEST_F(IncrementalServiceTest, testOnStatusNotReady) {
    mVold->mountIncFsSuccess();
    mIncFs->makeFileSuccess();
    mVold->bindMountSuccess();
    mDataLoaderManager->initializeDataLoaderSuccess();
    EXPECT_CALL(*mDataLoaderManager, destroyDataLoader(_));
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel), {},
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_GE(storageId, 0);
    mDataLoaderManager->setDataLoaderStatusNotReady();
}

TEST_F(IncrementalServiceTest, testStartDataLoaderSuccess) {
    mVold->mountIncFsSuccess();
    mIncFs->makeFileSuccess();
    mVold->bindMountSuccess();
    mDataLoaderManager->initializeDataLoaderSuccess();
    mDataLoaderManager->getDataLoaderSuccess();
    EXPECT_CALL(*mDataLoaderManager, destroyDataLoader(_));
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel), {},
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_GE(storageId, 0);
    mDataLoaderManager->setDataLoaderStatusReady();
    ASSERT_TRUE(mIncrementalService->startLoading(storageId));
}

TEST_F(IncrementalServiceTest, testMakeDirectory) {
    mVold->mountIncFsSuccess();
    mIncFs->makeFileSuccess();
    mVold->bindMountSuccess();
    mDataLoaderManager->initializeDataLoaderSuccess();
    mDataLoaderManager->getDataLoaderSuccess();
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel), {},
                                               IncrementalService::CreateOptions::CreateNew);
    std::string dir_path("test");

    std::string tempPath(tempDir.path);
    std::replace(tempPath.begin(), tempPath.end(), '/', '_');
    std::string mount_dir = std::string(mRootDir.path) + "/MT_" + tempPath.substr(1);
    std::string normalized_dir_path = mount_dir + "/mount/st_1_0/" + dir_path;

    // Expecting incfs to call makeDir on a path like:
    // /data/local/tmp/TemporaryDir-06yixG/data_local_tmp_TemporaryDir-xwdFhT/mount/st_1_0/test
    EXPECT_CALL(*mIncFs, makeDir(_, std::string_view(normalized_dir_path), _));
    auto res = mIncrementalService->makeDir(storageId, dir_path, 0555);
    ASSERT_EQ(res, 0);
}

TEST_F(IncrementalServiceTest, testMakeDirectories) {
    mVold->mountIncFsSuccess();
    mIncFs->makeFileSuccess();
    mVold->bindMountSuccess();
    mDataLoaderManager->initializeDataLoaderSuccess();
    mDataLoaderManager->getDataLoaderSuccess();
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel), {},
                                               IncrementalService::CreateOptions::CreateNew);
    auto first = "first"sv;
    auto second = "second"sv;
    auto third = "third"sv;

    std::string tempPath(tempDir.path);
    std::replace(tempPath.begin(), tempPath.end(), '/', '_');
    std::string mount_dir = std::string(mRootDir.path) + "/MT_" + tempPath.substr(1);

    InSequence seq;
    auto parent_path = std::string(first) + "/" + std::string(second);
    auto dir_path = parent_path + "/" + std::string(third);

    std::string normalized_first_path = mount_dir + "/mount/st_1_0/" + std::string(first);
    std::string normalized_parent_path = mount_dir + "/mount/st_1_0/" + parent_path;
    std::string normalized_dir_path = mount_dir + "/mount/st_1_0/" + dir_path;

    EXPECT_CALL(*mIncFs, makeDir(_, std::string_view(normalized_dir_path), _))
            .WillOnce(Return(-ENOENT));
    EXPECT_CALL(*mIncFs, makeDir(_, std::string_view(normalized_parent_path), _))
            .WillOnce(Return(-ENOENT));
    EXPECT_CALL(*mIncFs, makeDir(_, std::string_view(normalized_first_path), _))
            .WillOnce(Return(0));
    EXPECT_CALL(*mIncFs, makeDir(_, std::string_view(normalized_parent_path), _))
            .WillOnce(Return(0));
    EXPECT_CALL(*mIncFs, makeDir(_, std::string_view(normalized_dir_path), _)).WillOnce(Return(0));
    auto res = mIncrementalService->makeDirs(storageId, normalized_dir_path, 0555);
    ASSERT_EQ(res, 0);
}
} // namespace android::os::incremental
