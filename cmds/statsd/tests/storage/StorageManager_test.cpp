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
    TrainInfo trainInfo;
    trainInfo.trainVersionCode = 12345;
    const char* expIds = "test_ids";
    trainInfo.experimentIds.assign(expIds, expIds + strlen(expIds));

    StorageManager::writeTrainInfo(trainInfo.trainVersionCode, trainInfo.experimentIds);

    TrainInfo result;
    StorageManager::readTrainInfo(result);
    EXPECT_EQ(trainInfo.trainVersionCode, result.trainVersionCode);
    EXPECT_EQ(trainInfo.experimentIds.size(), result.experimentIds.size());
    EXPECT_EQ(trainInfo.experimentIds, result.experimentIds);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
