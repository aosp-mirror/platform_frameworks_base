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

#include "src/condition/ConditionTimer.h"

#include <gtest/gtest.h>
#include <stdio.h>

#ifdef __ANDROID__

namespace android {
namespace os {
namespace statsd {

static int64_t time_base = 10;
static int64_t ct_start_time = 200;

TEST(ConditionTimerTest, TestTimer_Inital_False) {
    ConditionTimer timer(false, time_base);
    EXPECT_EQ(false, timer.mCondition);
    EXPECT_EQ(0, timer.mTimerNs);

    EXPECT_EQ(0, timer.newBucketStart(ct_start_time));
    EXPECT_EQ(0, timer.mTimerNs);

    timer.onConditionChanged(true, ct_start_time + 5);
    EXPECT_EQ(ct_start_time + 5, timer.mLastConditionTrueTimestampNs);
    EXPECT_EQ(true, timer.mCondition);

    EXPECT_EQ(95, timer.newBucketStart(ct_start_time + 100));
    EXPECT_EQ(ct_start_time + 100, timer.mLastConditionTrueTimestampNs);
    EXPECT_EQ(true, timer.mCondition);
}

TEST(ConditionTimerTest, TestTimer_Inital_True) {
    ConditionTimer timer(true, time_base);
    EXPECT_EQ(true, timer.mCondition);
    EXPECT_EQ(0, timer.mTimerNs);

    EXPECT_EQ(ct_start_time - time_base, timer.newBucketStart(ct_start_time));
    EXPECT_EQ(true, timer.mCondition);
    EXPECT_EQ(0, timer.mTimerNs);
    EXPECT_EQ(ct_start_time, timer.mLastConditionTrueTimestampNs);

    timer.onConditionChanged(false, ct_start_time + 5);
    EXPECT_EQ(5, timer.mTimerNs);

    EXPECT_EQ(5, timer.newBucketStart(ct_start_time + 100));
    EXPECT_EQ(0, timer.mTimerNs);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
