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

    result = StorageManager::writeTrainInfo(trainInfo.trainVersionCode, trainInfo.trainName,
                                            trainInfo.status, trainInfo.experimentIds);

    EXPECT_TRUE(result);

    InstallTrainInfo trainInfoResult;
    result = StorageManager::readTrainInfo(trainInfoResult);
    EXPECT_TRUE(result);

    EXPECT_EQ(trainInfo.trainVersionCode, trainInfoResult.trainVersionCode);
    EXPECT_EQ(trainInfo.trainName.size(), trainInfoResult.trainName.size());
    EXPECT_EQ(trainInfo.trainName, trainInfoResult.trainName);
    EXPECT_EQ(trainInfo.status, trainInfoResult.status);
    EXPECT_EQ(trainInfo.experimentIds.size(), trainInfoResult.experimentIds.size());
    EXPECT_EQ(trainInfo.experimentIds, trainInfoResult.experimentIds);
}

TEST(StorageManagerTest, TrainInfoReadWriteEmptyTrainNameTest) {
    InstallTrainInfo trainInfo;
    trainInfo.trainVersionCode = 12345;
    trainInfo.trainName = "";
    trainInfo.status = 1;
    const char* expIds = "test_ids";
    trainInfo.experimentIds.assign(expIds, expIds + strlen(expIds));

    bool result;

    result = StorageManager::writeTrainInfo(trainInfo.trainVersionCode, trainInfo.trainName,
                                            trainInfo.status, trainInfo.experimentIds);

    EXPECT_TRUE(result);

    InstallTrainInfo trainInfoResult;
    result = StorageManager::readTrainInfo(trainInfoResult);
    EXPECT_TRUE(result);

    EXPECT_EQ(trainInfo.trainVersionCode, trainInfoResult.trainVersionCode);
    EXPECT_EQ(trainInfo.trainName.size(), trainInfoResult.trainName.size());
    EXPECT_EQ(trainInfo.trainName, trainInfoResult.trainName);
    EXPECT_EQ(trainInfo.status, trainInfoResult.status);
    EXPECT_EQ(trainInfo.experimentIds.size(), trainInfoResult.experimentIds.size());
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

    result = StorageManager::writeTrainInfo(trainInfo.trainVersionCode, trainInfo.trainName,
                                            trainInfo.status, trainInfo.experimentIds);

    EXPECT_TRUE(result);

    InstallTrainInfo trainInfoResult;
    result = StorageManager::readTrainInfo(trainInfoResult);
    EXPECT_TRUE(result);

    EXPECT_EQ(trainInfo.trainVersionCode, trainInfoResult.trainVersionCode);
    EXPECT_EQ(trainInfo.trainName.size(), trainInfoResult.trainName.size());
    EXPECT_EQ(trainInfo.trainName, trainInfoResult.trainName);
    EXPECT_EQ(trainInfo.status, trainInfoResult.status);
    EXPECT_EQ(trainInfo.experimentIds.size(), trainInfoResult.experimentIds.size());
    EXPECT_EQ(trainInfo.experimentIds, trainInfoResult.experimentIds);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
