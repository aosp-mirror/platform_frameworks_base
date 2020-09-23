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
            mLastConditionChangeTimestampNs = bucketStartNs;
        }
    };

    // Tracks how long the condition has been stayed true in the *current* bucket.
    // When a new bucket is created, this value will be reset to 0.
    int64_t mTimerNs = 0;

    // Last elapsed real timestamp when condition changed.
    int64_t mLastConditionChangeTimestampNs = 0;

    bool mCondition = false;

    int64_t newBucketStart(int64_t nextBucketStartNs) {
        if (mCondition) {
            // Normally, the next bucket happens after the last condition
            // change. In this case, add the time between the condition becoming
            // true to the next bucket start time.
            // Otherwise, the next bucket start time is before the last
            // condition change time, this means that the condition was false at
            // the bucket boundary before the condition became true, so the
            // timer should not get updated and the last condition change time
            // remains as is.
            if (nextBucketStartNs >= mLastConditionChangeTimestampNs) {
                mTimerNs += (nextBucketStartNs - mLastConditionChangeTimestampNs);
                mLastConditionChangeTimestampNs = nextBucketStartNs;
            }
        } else if (mLastConditionChangeTimestampNs > nextBucketStartNs) {
            // The next bucket start time is before the last condition change
            // time, this means that the condition was true at the bucket
            // boundary before the condition became false, so adjust the timer
            // to match how long the condition was true to the bucket boundary.
            // This means remove the amount the condition stayed true in the
            // next bucket from the current bucket.
            mTimerNs -= (mLastConditionChangeTimestampNs - nextBucketStartNs);
        }

        int64_t temp = mTimerNs;
        mTimerNs = 0;

        if (!mCondition && (mLastConditionChangeTimestampNs > nextBucketStartNs)) {
            // The next bucket start time is before the last condition change
            // time, this means that the condition was true at the bucket
            // boundary and remained true in the next bucket up to the condition
            // change to false, so adjust the timer to match how long the
            // condition stayed true in the next bucket (now the current bucket).
            mTimerNs = mLastConditionChangeTimestampNs - nextBucketStartNs;
        }
        return temp;
    }

    void onConditionChanged(bool newCondition, int64_t timestampNs) {
        if (newCondition == mCondition) {
            return;
        }
        mCondition = newCondition;
        if (newCondition == false) {
            mTimerNs += (timestampNs - mLastConditionChangeTimestampNs);
        }
        mLastConditionChangeTimestampNs = timestampNs;
    }

    FRIEND_TEST(ConditionTimerTest, TestTimer_Inital_False);
    FRIEND_TEST(ConditionTimerTest, TestTimer_Inital_True);
};

}  // namespace statsd
}  // namespace os
}  // namespace android
