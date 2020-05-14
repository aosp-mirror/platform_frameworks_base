// Copyright (C) 2019 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include <android-base/unique_fd.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <stdio.h>
#include "src/storage/StorageManager.h"

#ifdef __ANDROID__

namespace android {
namespace os {
namespace statsd {

using namespace testing;
using std::make_shared;
using std::shared_ptr;
using std::vector;
using testing::Contains;

TEST(StorageManagerTest, TrainInfoReadWriteTest) {
    InstallTrainInfo trainInfo;
    trainInfo.trainVersionCode = 12345;
    trainInfo.trainName = "This is a train name #)$(&&$";
    trainInfo.status = 1;
    const char* expIds = "test_ids";
    trainInfo.experimentIds.assign(expIds, expIds + strlen(expIds));

    bool result;

    result = StorageManager::writeTrainInfo(trainInfo);

    EXPECT_TRUE(result);

    InstallTrainInfo trainInfoResult;
    result = StorageManager::readTrainInfo(trainInfo.trainName, trainInfoResult);
    EXPECT_TRUE(result);

    EXPECT_EQ(trainInfo.trainVersionCode, trainInfoResult.trainVersionCode);
    ASSERT_EQ(trainInfo.trainName.size(), trainInfoResult.trainName.size());
    EXPECT_EQ(trainInfo.trainName, trainInfoResult.trainName);
    EXPECT_EQ(trainInfo.status, trainInfoResult.status);
    ASSERT_EQ(trainInfo.experimentIds.size(), trainInfoResult.experimentIds.size());
    EXPECT_EQ(trainInfo.experimentIds, trainInfoResult.experimentIds);
}

TEST(StorageManagerTest, TrainInfoReadWriteTrainNameSizeOneTest) {
    InstallTrainInfo trainInfo;
    trainInfo.trainVersionCode = 12345;
    trainInfo.trainName = "{";
    trainInfo.status = 1;
    const char* expIds = "test_ids";
    trainInfo.experimentIds.assign(expIds, expIds + strlen(expIds));

    bool result;

    result = StorageManager::writeTrainInfo(trainInfo);

    EXPECT_TRUE(result);

    InstallTrainInfo trainInfoResult;
    result = StorageManager::readTrainInfo(trainInfo.trainName, trainInfoResult);
    EXPECT_TRUE(result);

    EXPECT_EQ(trainInfo.trainVersionCode, trainInfoResult.trainVersionCode);
    ASSERT_EQ(trainInfo.trainName.size(), trainInfoResult.trainName.size());
    EXPECT_EQ(trainInfo.trainName, trainInfoResult.trainName);
    EXPECT_EQ(trainInfo.status, trainInfoResult.status);
    ASSERT_EQ(trainInfo.experimentIds.size(), trainInfoResult.experimentIds.size());
    EXPECT_EQ(trainInfo.experimentIds, trainInfoResult.experimentIds);
}

TEST(StorageManagerTest, SortFileTest) {
    vector<StorageManager::FileInfo> list;
    // assume now sec is 500
    list.emplace_back("200_5000_123454", false, 20, 300);
    list.emplace_back("300_2000_123454_history", true, 30, 200);
    list.emplace_back("400_100009_123454_history", true, 40, 100);
    list.emplace_back("100_2000_123454", false, 50, 400);

    StorageManager::sortFiles(&list);
    EXPECT_EQ("200_5000_123454", list[0].mFileName);
    EXPECT_EQ("100_2000_123454", list[1].mFileName);
    EXPECT_EQ("400_100009_123454_history", list[2].mFileName);
    EXPECT_EQ("300_2000_123454_history", list[3].mFileName);
}

const string testDir = "/data/misc/stats-data/";
const string file1 = testDir + "2557169347_1066_1";
const string file2 = testDir + "2557169349_1066_1";
const string file1_history = file1 + "_history";
const string file2_history = file2 + "_history";

bool prepareLocalHistoryTestFiles() {
    android::base::unique_fd fd(TEMP_FAILURE_RETRY(
            open(file1.c_str(), O_WRONLY | O_CREAT | O_CLOEXEC, S_IRUSR | S_IWUSR)));
    if (fd != -1) {
        dprintf(fd, "content");
    } else {
        return false;
    }

    android::base::unique_fd fd2(TEMP_FAILURE_RETRY(
            open(file2.c_str(), O_WRONLY | O_CREAT | O_CLOEXEC, S_IRUSR | S_IWUSR)));
    if (fd2 != -1) {
        dprintf(fd2, "content");
    } else {
        return false;
    }
    return true;
}

void clearLocalHistoryTestFiles() {
    TEMP_FAILURE_RETRY(remove(file1.c_str()));
    TEMP_FAILURE_RETRY(remove(file2.c_str()));
    TEMP_FAILURE_RETRY(remove(file1_history.c_str()));
    TEMP_FAILURE_RETRY(remove(file2_history.c_str()));
}

bool fileExist(string name) {
    android::base::unique_fd fd(TEMP_FAILURE_RETRY(open(name.c_str(), O_RDONLY | O_CLOEXEC)));
    return fd != -1;
}

/* The following AppendConfigReportTests test the 4 combinations of [whether erase data] [whether
 * the caller is adb] */
TEST(StorageManagerTest, AppendConfigReportTest1) {
    EXPECT_TRUE(prepareLocalHistoryTestFiles());

    ProtoOutputStream out;
    StorageManager::appendConfigMetricsReport(ConfigKey(1066, 1), &out, false /*erase?*/,
                                              false /*isAdb?*/);

    EXPECT_FALSE(fileExist(file1));
    EXPECT_FALSE(fileExist(file2));

    EXPECT_TRUE(fileExist(file1_history));
    EXPECT_TRUE(fileExist(file2_history));
    clearLocalHistoryTestFiles();
}

TEST(StorageManagerTest, AppendConfigReportTest2) {
    EXPECT_TRUE(prepareLocalHistoryTestFiles());

    ProtoOutputStream out;
    StorageManager::appendConfigMetricsReport(ConfigKey(1066, 1), &out, true /*erase?*/,
                                              false /*isAdb?*/);

    EXPECT_FALSE(fileExist(file1));
    EXPECT_FALSE(fileExist(file2));
    EXPECT_FALSE(fileExist(file1_history));
    EXPECT_FALSE(fileExist(file2_history));

    clearLocalHistoryTestFiles();
}

TEST(StorageManagerTest, AppendConfigReportTest3) {
    EXPECT_TRUE(prepareLocalHistoryTestFiles());

    ProtoOutputStream out;
    StorageManager::appendConfigMetricsReport(ConfigKey(1066, 1), &out, false /*erase?*/,
                                              true /*isAdb?*/);

    EXPECT_TRUE(fileExist(file1));
    EXPECT_TRUE(fileExist(file2));
    EXPECT_FALSE(fileExist(file1_history));
    EXPECT_FALSE(fileExist(file2_history));

    clearLocalHistoryTestFiles();
}

TEST(StorageManagerTest, AppendConfigReportTest4) {
    EXPECT_TRUE(prepareLocalHistoryTestFiles());

    ProtoOutputStream out;
    StorageManager::appendConfigMetricsReport(ConfigKey(1066, 1), &out, true /*erase?*/,
                                              true /*isAdb?*/);

    EXPECT_FALSE(fileExist(file1));
    EXPECT_FALSE(fileExist(file2));
    EXPECT_FALSE(fileExist(file1_history));
    EXPECT_FALSE(fileExist(file2_history));

    clearLocalHistoryTestFiles();
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
