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

#include <gtest/gtest_prod.h>
#include <stdint.h>

namespace android {
namespace os {
namespace statsd {

/**
 * A simple stopwatch to time the duration of condition being true.
 *
 * The owner of the stopwatch (MetricProducer) is responsible to notify the stopwatch when condition
 * changes (start/pause), and when to start a new bucket (a new lap basically). All timestamps
 * should be elapsedRealTime in nano seconds.
 *
 * Keep the timer simple and inline everything. This class is *NOT* thread safe. Caller is
 * responsible for thread safety.
 */
class ConditionTimer {
public:
    explicit ConditionTimer(bool initCondition, int64_t bucketStartNs) : mCondition(initCondition) {
        if (initCondition) {
            mLastConditionTrueTimestampNs = bucketStartNs;
        }
    };

    // Tracks how long the condition has been stayed true in the *current* bucket.
    // When a new bucket is created, this value will be reset to 0.
    int64_t mTimerNs = 0;

    // Last elapsed real timestamp when condition turned to true
    // When a new bucket is created and the condition is true, then the timestamp is set
    // to be the bucket start timestamp.
    int64_t mLastConditionTrueTimestampNs = 0;

    bool mCondition = false;

    int64_t newBucketStart(int64_t nextBucketStartNs) {
        if (mCondition) {
            mTimerNs += (nextBucketStartNs - mLastConditionTrueTimestampNs);
            mLastConditionTrueTimestampNs = nextBucketStartNs;
        }

        int64_t temp = mTimerNs;
        mTimerNs = 0;
        return temp;
    }

    void onConditionChanged(bool newCondition, int64_t timestampNs) {
        if (newCondition == mCondition) {
            return;
        }
        mCondition = newCondition;
        if (newCondition) {
            mLastConditionTrueTimestampNs = timestampNs;
        } else {
            mTimerNs += (timestampNs - mLastConditionTrueTimestampNs);
        }
    }

    FRIEND_TEST(ConditionTimerTest, TestTimer_Inital_False);
    FRIEND_TEST(ConditionTimerTest, TestTimer_Inital_True);
};

}  // namespace statsd
}  // namespace os
}  // namespace android