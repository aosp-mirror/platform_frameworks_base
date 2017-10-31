/*
 * Copyright (C) 2017 The Android Open Source Project
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
#define DEBUG true
#include "Log.h"
#include "OringDurationTracker.h"

namespace android {
namespace os {
namespace statsd {
OringDurationTracker::OringDurationTracker(sp<ConditionWizard> wizard, int conditionIndex,
                                           uint64_t currentBucketStartNs, uint64_t bucketSizeNs,
                                           std::vector<DurationBucketInfo>& bucket)
    : DurationTracker(wizard, conditionIndex, currentBucketStartNs, bucketSizeNs, bucket),
      mStarted(),
      mPaused() {
    mLastStartTime = 0;
}

void OringDurationTracker::noteStart(const HashableDimensionKey& key, bool condition,
                                     const uint64_t eventTime, const ConditionKey& conditionKey) {
    if (condition) {
        if (mStarted.size() == 0) {
            mLastStartTime = eventTime;
            VLOG("record first start....");
        }
        mStarted.insert(key);
    } else {
        mPaused.insert(key);
    }

    if (mConditionKeyMap.find(key) == mConditionKeyMap.end()) {
        mConditionKeyMap[key] = conditionKey;
    }

    VLOG("Oring: %s start, condition %d", key.c_str(), condition);
}

void OringDurationTracker::noteStop(const HashableDimensionKey& key, const uint64_t timestamp) {
    VLOG("Oring: %s stop", key.c_str());
    auto it = mStarted.find(key);
    if (it != mStarted.end()) {
        mStarted.erase(it);
        if (mStarted.empty()) {
            mDuration += (timestamp - mLastStartTime);
            VLOG("record duration %lld, total %lld ", (long long)timestamp - mLastStartTime,
                 (long long)mDuration);
        }
    }

    mPaused.erase(key);
    mConditionKeyMap.erase(key);
}
void OringDurationTracker::noteStopAll(const uint64_t timestamp) {
    if (!mStarted.empty()) {
        mDuration += (timestamp - mLastStartTime);
        VLOG("Oring Stop all: record duration %lld %lld ", (long long)timestamp - mLastStartTime,
             (long long)mDuration);
    }

    mStarted.clear();
    mPaused.clear();
    mConditionKeyMap.clear();
}

bool OringDurationTracker::flushIfNeeded(uint64_t eventTime) {
    if (mCurrentBucketStartTimeNs + mBucketSizeNs > eventTime) {
        return false;
    }
    VLOG("OringDurationTracker Flushing.............");
    // adjust the bucket start time
    int numBucketsForward = (eventTime - mCurrentBucketStartTimeNs) / mBucketSizeNs;
    DurationBucketInfo info;
    uint64_t endTime = mCurrentBucketStartTimeNs + mBucketSizeNs;
    info.set_start_bucket_nanos(mCurrentBucketStartTimeNs);
    info.set_end_bucket_nanos(endTime);

    uint64_t oldBucketStartTimeNs = mCurrentBucketStartTimeNs;
    mCurrentBucketStartTimeNs += (numBucketsForward)*mBucketSizeNs;

    if (mStarted.size() > 0) {
        mDuration += (endTime - mLastStartTime);
    }
    if (mDuration != 0) {
        info.set_duration_nanos(mDuration);
        // it will auto create new vector of CountbucketInfo if the key is not found.
        mBucket.push_back(info);
        VLOG("  duration: %lld", (long long)mDuration);
    }

    if (mStarted.size() > 0) {
        for (int i = 1; i < numBucketsForward; i++) {
            DurationBucketInfo info;
            info.set_start_bucket_nanos(oldBucketStartTimeNs + mBucketSizeNs * i);
            info.set_end_bucket_nanos(endTime + mBucketSizeNs * i);
            info.set_duration_nanos(mBucketSizeNs);
            mBucket.push_back(info);
            VLOG("  add filling bucket with duration %lld", (long long)mBucketSizeNs);
        }
    }
    mLastStartTime = mCurrentBucketStartTimeNs;
    mDuration = 0;

    // if all stopped, then tell owner it's safe to remove this tracker.
    return mStarted.empty() && mPaused.empty();
}

void OringDurationTracker::onSlicedConditionMayChange(const uint64_t timestamp) {
    vector<HashableDimensionKey> startedToPaused;
    vector<HashableDimensionKey> pausedToStarted;
    if (!mStarted.empty()) {
        for (auto it = mStarted.begin(); it != mStarted.end();) {
            auto key = *it;
            if (mConditionKeyMap.find(key) == mConditionKeyMap.end()) {
                VLOG("Key %s dont have condition key", key.c_str());
                ++it;
                continue;
            }
            if (mWizard->query(mConditionTrackerIndex, mConditionKeyMap[key]) !=
                ConditionState::kTrue) {
                it = mStarted.erase(it);
                startedToPaused.push_back(key);
                VLOG("Key %s started -> paused", key.c_str());
            } else {
                ++it;
            }
        }

        if (mStarted.empty()) {
            mDuration += (timestamp - mLastStartTime);
            VLOG("Duration add %lld , to %lld ", (long long)(timestamp - mLastStartTime),
                 (long long)mDuration);
        }
    }

    if (!mPaused.empty()) {
        for (auto it = mPaused.begin(); it != mPaused.end();) {
            auto key = *it;
            if (mConditionKeyMap.find(key) == mConditionKeyMap.end()) {
                VLOG("Key %s dont have condition key", key.c_str());
                ++it;
                continue;
            }
            if (mWizard->query(mConditionTrackerIndex, mConditionKeyMap[key]) ==
                ConditionState::kTrue) {
                it = mPaused.erase(it);
                pausedToStarted.push_back(key);
                VLOG("Key %s paused -> started", key.c_str());
            } else {
                ++it;
            }
        }

        if (mStarted.empty() && pausedToStarted.size() > 0) {
            mLastStartTime = timestamp;
        }
    }

    mStarted.insert(pausedToStarted.begin(), pausedToStarted.end());
    mPaused.insert(startedToPaused.begin(), startedToPaused.end());
}

void OringDurationTracker::onConditionChanged(bool condition, const uint64_t timestamp) {
    if (condition) {
        if (!mPaused.empty()) {
            VLOG("Condition true, all started");
            if (mStarted.empty()) {
                mLastStartTime = timestamp;
            }
            mStarted.insert(mPaused.begin(), mPaused.end());
        }
    } else {
        if (!mStarted.empty()) {
            VLOG("Condition false, all paused");
            mDuration += (timestamp - mLastStartTime);
            mPaused.insert(mStarted.begin(), mStarted.end());
        }
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android
