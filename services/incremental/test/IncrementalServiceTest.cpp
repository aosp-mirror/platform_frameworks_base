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
                       binder::Status(const std::string& imagePath, const std::string& targetDir,
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
        _aidl_return->cmd = nullptr;
        _aidl_return->log = nullptr;
        return binder::Status::ok();
    }
    binder::Status incFsSuccess(const std::string& imagePath, const std::string& targetDir,
                                int32_t flags, IncrementalFileSystemControlParcel* _aidl_return) {
        _aidl_return->cmd = std::make_unique<os::ParcelFileDescriptor>(std::move(cmdFd));
        _aidl_return->log = std::make_unique<os::ParcelFileDescriptor>(std::move(logFd));
        return binder::Status::ok();
    }

private:
    TemporaryFile cmdFile;
    TemporaryFile logFile;
    base::unique_fd cmdFd;
    base::unique_fd logFd;
};

class MockIncrementalManager : public IncrementalManagerWrapper {
public:
    MOCK_CONST_METHOD5(prepareDataLoader,
                       binder::Status(int32_t mountId, const FileSystemControlParcel& control,
                                      const DataLoaderParamsParcel& params,
                                      const sp<IDataLoaderStatusListener>& listener,
                                      bool* _aidl_return));
    MOCK_CONST_METHOD2(startDataLoader, binder::Status(int32_t mountId, bool* _aidl_return));
    MOCK_CONST_METHOD1(destroyDataLoader, binder::Status(int32_t mountId));
    MOCK_CONST_METHOD3(newFileForDataLoader,
                       binder::Status(int32_t mountId, int64_t inode,
                                      const ::std::vector<uint8_t>& metadata));
    MOCK_CONST_METHOD1(showHealthBlockedUI, binder::Status(int32_t mountId));

    binder::Status prepareDataLoaderOk(int32_t mountId, const FileSystemControlParcel& control,
                                       const DataLoaderParamsParcel& params,
                                       const sp<IDataLoaderStatusListener>& listener,
                                       bool* _aidl_return) {
        mId = mountId;
        mListener = listener;
        *_aidl_return = true;
        return binder::Status::ok();
    }

    binder::Status startDataLoaderOk(int32_t mountId, bool* _aidl_return) {
        *_aidl_return = true;
        return binder::Status::ok();
    }

    void prepareDataLoaderFails() {
        ON_CALL(*this, prepareDataLoader(_, _, _, _, _))
                .WillByDefault(Return(
                        (binder::Status::fromExceptionCode(1, String8("failed to prepare")))));
    }
    void prepareDataLoaderSuccess() {
        ON_CALL(*this, prepareDataLoader(_, _, _, _, _))
                .WillByDefault(Invoke(this, &MockIncrementalManager::prepareDataLoaderOk));
    }
    void startDataLoaderSuccess() {
        ON_CALL(*this, startDataLoader(_, _))
                .WillByDefault(Invoke(this, &MockIncrementalManager::startDataLoaderOk));
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
};

class MockIncFs : public IncFsWrapper {
public:
    MOCK_CONST_METHOD5(makeFile,
                       Inode(Control control, std::string_view name, Inode parent, Size size,
                             std::string_view metadata));
    MOCK_CONST_METHOD5(makeDir,
                       Inode(Control control, std::string_view name, Inode parent,
                             std::string_view metadata, int mode));
    MOCK_CONST_METHOD2(getMetadata, RawMetadata(Control control, Inode inode));
    MOCK_CONST_METHOD4(link,
                       ErrorCode(Control control, Inode item, Inode targetParent,
                                 std::string_view name));
    MOCK_CONST_METHOD3(unlink, ErrorCode(Control control, Inode parent, std::string_view name));
    MOCK_CONST_METHOD3(writeBlocks,
                       ErrorCode(Control control, const incfs_new_data_block blocks[],
                                 int blocksCount));

    void makeFileFails() { ON_CALL(*this, makeFile(_, _, _, _, _)).WillByDefault(Return(-1)); }
    void makeFileSuccess() { ON_CALL(*this, makeFile(_, _, _, _, _)).WillByDefault(Return(0)); }
    RawMetadata getMountInfoMetadata(Control control, Inode inode) {
        metadata::Mount m;
        m.mutable_storage()->set_id(100);
        m.mutable_loader()->set_package_name("com.test");
        m.mutable_loader()->set_arguments("com.uri");
        const auto metadata = m.SerializeAsString();
        m.mutable_loader()->release_arguments();
        m.mutable_loader()->release_package_name();
        return std::vector<char>(metadata.begin(), metadata.end());
    }
    RawMetadata getStorageMetadata(Control control, Inode inode) {
        metadata::Storage st;
        st.set_id(100);
        auto metadata = st.SerializeAsString();
        return std::vector<char>(metadata.begin(), metadata.end());
    }
    RawMetadata getBindPointMetadata(Control control, Inode inode) {
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
    MockServiceManager(std::shared_ptr<MockVoldService> vold,
                       std::shared_ptr<MockIncrementalManager> manager,
                       std::shared_ptr<MockIncFs> incfs)
          : mVold(vold), mIncrementalManager(manager), mIncFs(incfs) {}
    std::shared_ptr<VoldServiceWrapper> getVoldService() const override { return mVold; }
    std::shared_ptr<IncrementalManagerWrapper> getIncrementalManager() const override {
        return mIncrementalManager;
    }
    std::shared_ptr<IncFsWrapper> getIncFs() const override { return mIncFs; }

private:
    std::shared_ptr<MockVoldService> mVold;
    std::shared_ptr<MockIncrementalManager> mIncrementalManager;
    std::shared_ptr<MockIncFs> mIncFs;
};

// --- IncrementalServiceTest ---

static Inode inode(std::string_view path) {
    struct stat st;
    if (::stat(path::c_str(path), &st)) {
        return -1;
    }
    return st.st_ino;
}

class IncrementalServiceTest : public testing::Test {
public:
    void SetUp() override {
        mVold = std::make_shared<NiceMock<MockVoldService>>();
        mIncrementalManager = std::make_shared<NiceMock<MockIncrementalManager>>();
        mIncFs = std::make_shared<NiceMock<MockIncFs>>();
        MockServiceManager serviceManager = MockServiceManager(mVold, mIncrementalManager, mIncFs);
        mIncrementalService = std::make_unique<IncrementalService>(serviceManager, mRootDir.path);
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
        ASSERT_GE(inode(mountInfoFile), 0);
        ASSERT_GE(inode(mountPointsFile), 0);
        ON_CALL(*mIncFs, getMetadata(_, inode(mountInfoFile)))
                .WillByDefault(Invoke(mIncFs.get(), &MockIncFs::getMountInfoMetadata));
        ON_CALL(*mIncFs, getMetadata(_, inode(mountPointsFile)))
                .WillByDefault(Invoke(mIncFs.get(), &MockIncFs::getBindPointMetadata));
        ON_CALL(*mIncFs, getMetadata(_, inode(rootDir + "/dir1/mount/st0")))
                .WillByDefault(Invoke(mIncFs.get(), &MockIncFs::getStorageMetadata));
    }

protected:
    std::shared_ptr<NiceMock<MockVoldService>> mVold;
    std::shared_ptr<NiceMock<MockIncFs>> mIncFs;
    std::shared_ptr<NiceMock<MockIncrementalManager>> mIncrementalManager;
    std::unique_ptr<IncrementalService> mIncrementalService;
    TemporaryDir mRootDir;
    DataLoaderParamsParcel mDataLoaderParcel;
};

/*
TEST_F(IncrementalServiceTest, testBootMountExistingImagesSuccess) {
    TemporaryDir tempDir;
    setUpExistingMountDir(tempDir.path);
    mVold->mountIncFsSuccess();
    mVold->bindMountSuccess();
    mIncrementalManager->prepareDataLoaderSuccess();
    ON_CALL(*mIncrementalManager, destroyDataLoader(_)).WillByDefault(Return(binder::Status::ok()));

    EXPECT_CALL(*mVold, mountIncFs(_, _, _, _)).Times(1);
    EXPECT_CALL(*mIncrementalManager, prepareDataLoader(_, _, _, _, _)).Times(1);

    MockServiceManager serviceManager = MockServiceManager(mVold, mIncrementalManager, mIncFs);
    std::unique_ptr<IncrementalService> incrementalService =
            std::make_unique<IncrementalService>(serviceManager, tempDir.path);
    auto finished = incrementalService->onSystemReady();
    if (finished) {
        finished->wait();
    }
}
*/

TEST_F(IncrementalServiceTest, testCreateStorageMountIncFsFails) {
    mVold->mountIncFsFails();
    EXPECT_CALL(*mIncrementalManager, prepareDataLoader(_, _, _, _, _)).Times(0);
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel),
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_LT(storageId, 0);
}

TEST_F(IncrementalServiceTest, testCreateStorageMountIncFsInvalidControlParcel) {
    mVold->mountIncFsInvalidControlParcel();
    EXPECT_CALL(*mIncrementalManager, prepareDataLoader(_, _, _, _, _)).Times(0);
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel),
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_LT(storageId, 0);
}

TEST_F(IncrementalServiceTest, testCreateStorageMakeFileFails) {
    mVold->mountIncFsSuccess();
    mIncFs->makeFileFails();
    EXPECT_CALL(*mIncrementalManager, prepareDataLoader(_, _, _, _, _)).Times(0);
    EXPECT_CALL(*mIncrementalManager, destroyDataLoader(_));
    EXPECT_CALL(*mVold, unmountIncFs(_));
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel),
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_LT(storageId, 0);
}

TEST_F(IncrementalServiceTest, testCreateStorageBindMountFails) {
    mVold->mountIncFsSuccess();
    mIncFs->makeFileSuccess();
    mVold->bindMountFails();
    EXPECT_CALL(*mIncrementalManager, prepareDataLoader(_, _, _, _, _)).Times(0);
    EXPECT_CALL(*mIncrementalManager, destroyDataLoader(_));
    EXPECT_CALL(*mVold, unmountIncFs(_));
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel),
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_LT(storageId, 0);
}

TEST_F(IncrementalServiceTest, testCreateStoragePrepareDataLoaderFails) {
    mVold->mountIncFsSuccess();
    mIncFs->makeFileSuccess();
    mVold->bindMountSuccess();
    mIncrementalManager->prepareDataLoaderFails();
    EXPECT_CALL(*mIncrementalManager, destroyDataLoader(_));
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel),
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_LT(storageId, 0);
}

TEST_F(IncrementalServiceTest, testDeleteStorageSuccess) {
    mVold->mountIncFsSuccess();
    mIncFs->makeFileSuccess();
    mVold->bindMountSuccess();
    mIncrementalManager->prepareDataLoaderSuccess();
    EXPECT_CALL(*mIncrementalManager, destroyDataLoader(_));
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel),
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_GE(storageId, 0);
    mIncrementalService->deleteStorage(storageId);
}

TEST_F(IncrementalServiceTest, testOnStatusNotReady) {
    mVold->mountIncFsSuccess();
    mIncFs->makeFileSuccess();
    mVold->bindMountSuccess();
    mIncrementalManager->prepareDataLoaderSuccess();
    EXPECT_CALL(*mIncrementalManager, destroyDataLoader(_));
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel),
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_GE(storageId, 0);
    mIncrementalManager->setDataLoaderStatusNotReady();
}

TEST_F(IncrementalServiceTest, testStartDataLoaderSuccess) {
    mVold->mountIncFsSuccess();
    mIncFs->makeFileSuccess();
    mVold->bindMountSuccess();
    mIncrementalManager->prepareDataLoaderSuccess();
    mIncrementalManager->startDataLoaderSuccess();
    EXPECT_CALL(*mIncrementalManager, destroyDataLoader(_));
    EXPECT_CALL(*mVold, unmountIncFs(_)).Times(2);
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel),
                                               IncrementalService::CreateOptions::CreateNew);
    ASSERT_GE(storageId, 0);
    mIncrementalManager->setDataLoaderStatusReady();
    ASSERT_TRUE(mIncrementalService->startLoading(storageId));
}

TEST_F(IncrementalServiceTest, testMakeDirectory) {
    mVold->mountIncFsSuccess();
    mIncFs->makeFileSuccess();
    mVold->bindMountSuccess();
    mIncrementalManager->prepareDataLoaderSuccess();
    mIncrementalManager->startDataLoaderSuccess();
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel),
                                               IncrementalService::CreateOptions::CreateNew);
    std::string_view dir_path("test");
    EXPECT_CALL(*mIncFs, makeDir(_, dir_path, _, _, _));
    int fileIno = mIncrementalService->makeDir(storageId, dir_path, "");
    ASSERT_GE(fileIno, 0);
}

TEST_F(IncrementalServiceTest, testMakeDirectoryNoParent) {
    mVold->mountIncFsSuccess();
    mIncFs->makeFileSuccess();
    mVold->bindMountSuccess();
    mIncrementalManager->prepareDataLoaderSuccess();
    mIncrementalManager->startDataLoaderSuccess();
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel),
                                               IncrementalService::CreateOptions::CreateNew);
    std::string_view first("first");
    std::string_view second("second");
    std::string dir_path = std::string(first) + "/" + std::string(second);
    EXPECT_CALL(*mIncFs, makeDir(_, first, _, _, _)).Times(0);
    EXPECT_CALL(*mIncFs, makeDir(_, second, _, _, _)).Times(0);
    int fileIno = mIncrementalService->makeDir(storageId, dir_path, "");
    ASSERT_LT(fileIno, 0);
}

TEST_F(IncrementalServiceTest, testMakeDirectories) {
    mVold->mountIncFsSuccess();
    mIncFs->makeFileSuccess();
    mVold->bindMountSuccess();
    mIncrementalManager->prepareDataLoaderSuccess();
    mIncrementalManager->startDataLoaderSuccess();
    TemporaryDir tempDir;
    int storageId =
            mIncrementalService->createStorage(tempDir.path, std::move(mDataLoaderParcel),
                                               IncrementalService::CreateOptions::CreateNew);
    std::string_view first("first");
    std::string_view second("second");
    std::string_view third("third");
    InSequence seq;
    EXPECT_CALL(*mIncFs, makeDir(_, first, _, _, _));
    EXPECT_CALL(*mIncFs, makeDir(_, second, _, _, _));
    EXPECT_CALL(*mIncFs, makeDir(_, third, _, _, _));
    std::string dir_path =
            std::string(first) + "/" + std::string(second) + "/" + std::string(third);
    int fileIno = mIncrementalService->makeDirs(storageId, dir_path, "");
    ASSERT_GE(fileIno, 0);
}
} // namespace android::os::incremental
