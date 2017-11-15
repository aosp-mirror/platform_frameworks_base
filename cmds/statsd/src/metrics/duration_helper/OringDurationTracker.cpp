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

using std::pair;

OringDurationTracker::OringDurationTracker(sp<ConditionWizard> wizard, int conditionIndex,
                                           bool nesting, uint64_t currentBucketStartNs,
                                           uint64_t bucketSizeNs,
                                           std::vector<DurationBucket>& bucket)
    : DurationTracker(wizard, conditionIndex, nesting, currentBucketStartNs, bucketSizeNs, bucket),
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
        mStarted[key]++;
    } else {
        mPaused[key]++;
    }

    if (mConditionKeyMap.find(key) == mConditionKeyMap.end()) {
        mConditionKeyMap[key] = conditionKey;
    }

    VLOG("Oring: %s start, condition %d", key.c_str(), condition);
}

void OringDurationTracker::noteStop(const HashableDimensionKey& key, const uint64_t timestamp,
                                    const bool stopAll) {
    VLOG("Oring: %s stop", key.c_str());
    auto it = mStarted.find(key);
    if (it != mStarted.end()) {
        (it->second)--;
        if (stopAll || !mNested || it->second <= 0) {
            mStarted.erase(it);
            mConditionKeyMap.erase(key);
        }
        if (mStarted.empty()) {
            mDuration += (timestamp - mLastStartTime);
            VLOG("record duration %lld, total %lld ", (long long)timestamp - mLastStartTime,
                 (long long)mDuration);
        }
    }

    auto pausedIt = mPaused.find(key);
    if (pausedIt != mPaused.end()) {
        (pausedIt->second)--;
        if (stopAll || !mNested || pausedIt->second <= 0) {
            mPaused.erase(pausedIt);
            mConditionKeyMap.erase(key);
        }
        }
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
    DurationBucket info;
    uint64_t endTime = mCurrentBucketStartTimeNs + mBucketSizeNs;
    info.mBucketStartNs = mCurrentBucketStartTimeNs;
    info.mBucketEndNs = endTime;

    uint64_t oldBucketStartTimeNs = mCurrentBucketStartTimeNs;
    mCurrentBucketStartTimeNs += (numBucketsForward)*mBucketSizeNs;

    if (mStarted.size() > 0) {
        mDuration += (endTime - mLastStartTime);
    }
    if (mDuration != 0) {
        info.mDuration = mDuration;
        // it will auto create new vector of CountbucketInfo if the key is not found.
        mBucket.push_back(info);
        VLOG("  duration: %lld", (long long)mDuration);
    }

    if (mStarted.size() > 0) {
        for (int i = 1; i < numBucketsForward; i++) {
            DurationBucket info;
            info.mBucketStartNs = oldBucketStartTimeNs + mBucketSizeNs * i;
            info.mBucketEndNs = endTime + mBucketSizeNs * i;
            info.mDuration = mBucketSizeNs;
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
    vector<pair<HashableDimensionKey, int>> startedToPaused;
    vector<pair<HashableDimensionKey, int>> pausedToStarted;
    if (!mStarted.empty()) {
        for (auto it = mStarted.begin(); it != mStarted.end();) {
            const auto& key = it->first;
            if (mConditionKeyMap.find(key) == mConditionKeyMap.end()) {
                VLOG("Key %s dont have condition key", key.c_str());
                ++it;
                continue;
            }
            if (mWizard->query(mConditionTrackerIndex, mConditionKeyMap[key]) !=
                ConditionState::kTrue) {
                startedToPaused.push_back(*it);
                it = mStarted.erase(it);
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
            const auto& key = it->first;
            if (mConditionKeyMap.find(key) == mConditionKeyMap.end()) {
                VLOG("Key %s dont have condition key", key.c_str());
                ++it;
                continue;
            }
            if (mWizard->query(mConditionTrackerIndex, mConditionKeyMap[key]) ==
                ConditionState::kTrue) {
                pausedToStarted.push_back(*it);
                it = mPaused.erase(it);
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
