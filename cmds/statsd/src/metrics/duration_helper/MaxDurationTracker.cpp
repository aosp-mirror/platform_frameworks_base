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
#include "MaxDurationTracker.h"

namespace android {
namespace os {
namespace statsd {

MaxDurationTracker::MaxDurationTracker(sp<ConditionWizard> wizard, int conditionIndex,
                                       uint64_t currentBucketStartNs, uint64_t bucketSizeNs,
                                       std::vector<DurationBucketInfo>& bucket)
    : DurationTracker(wizard, conditionIndex, currentBucketStartNs, bucketSizeNs, bucket) {
}

void MaxDurationTracker::noteStart(const HashableDimensionKey& key, bool condition,
                                   const uint64_t eventTime, const ConditionKey& conditionKey) {
    // this will construct a new DurationInfo if this key didn't exist.
    DurationInfo& duration = mInfos[key];
    duration.conditionKeys = conditionKey;
    VLOG("MaxDuration: key %s start condition %d", key.c_str(), condition);

    switch (duration.state) {
        case kStarted:
            // The same event is already started. Because we are not counting nesting, so ignore.
            break;
        case kPaused:
            // Safe to do nothing here. Paused means started but condition is false.
            break;
        case kStopped:
            if (!condition) {
                // event started, but we need to wait for the condition to become true.
                duration.state = DurationState::kPaused;
            } else {
                duration.state = DurationState::kStarted;
                duration.lastStartTime = eventTime;
            }
            break;
    }
}

void MaxDurationTracker::noteStop(const HashableDimensionKey& key, const uint64_t eventTime) {
    VLOG("MaxDuration: key %s stop", key.c_str());
    if (mInfos.find(key) == mInfos.end()) {
        // we didn't see a start event before. do nothing.
        return;
    }
    DurationInfo& duration = mInfos[key];

    switch (duration.state) {
        case DurationState::kStopped:
            // already stopped, do nothing.
            break;
        case DurationState::kStarted: {
            duration.state = DurationState::kStopped;
            int64_t durationTime = eventTime - duration.lastStartTime;
            VLOG("Max, key %s, Stop %lld %lld %lld", key.c_str(), (long long)duration.lastStartTime,
                 (long long)eventTime, (long long)durationTime);
            duration.lastDuration = duration.lastDuration + durationTime;
            VLOG("  record duration: %lld ", (long long)duration.lastDuration);
            break;
        }
        case DurationState::kPaused: {
            duration.state = DurationState::kStopped;
            break;
        }
    }

    if (duration.lastDuration > mDuration) {
        mDuration = duration.lastDuration;
        VLOG("Max: new max duration: %lld", (long long)mDuration);
    }
    // Once an atom duration ends, we erase it. Next time, if we see another atom event with the
    // same name, they are still considered as different atom durations.
    mInfos.erase(key);
}
void MaxDurationTracker::noteStopAll(const uint64_t eventTime) {
    for (auto& pair : mInfos) {
        noteStop(pair.first, eventTime);
    }
}

bool MaxDurationTracker::flushIfNeeded(uint64_t eventTime) {
    if (mCurrentBucketStartTimeNs + mBucketSizeNs > eventTime) {
        return false;
    }

    VLOG("MaxDurationTracker flushing.....");

    // adjust the bucket start time
    int numBucketsForward = (eventTime - mCurrentBucketStartTimeNs) / mBucketSizeNs;

    DurationBucketInfo info;
    uint64_t endTime = mCurrentBucketStartTimeNs + mBucketSizeNs;
    info.set_start_bucket_nanos(mCurrentBucketStartTimeNs);
    info.set_end_bucket_nanos(endTime);

    uint64_t oldBucketStartTimeNs = mCurrentBucketStartTimeNs;
    mCurrentBucketStartTimeNs += (numBucketsForward)*mBucketSizeNs;

    bool hasOnGoingStartedEvent = false;  // a kStarted event last across bucket boundaries.
    bool hasPendingEvent =
            false;  // has either a kStarted or kPaused event across bucket boundaries
                    // meaning we need to carry them over to the new bucket.
    for (auto it = mInfos.begin(); it != mInfos.end(); ++it) {
        int64_t finalDuration = it->second.lastDuration;
        if (it->second.state == kStarted) {
            // the event is still on-going, duration needs to be updated.
            // |..lastDurationTime_recorded...last_start -----|bucket_end. We need to record the
            // duration between lastStartTime and bucketEnd.
            int64_t durationTime = endTime - it->second.lastStartTime;

            finalDuration += durationTime;
            VLOG("  unrecorded %lld -> %lld", (long long)(durationTime), (long long)finalDuration);
            // if the event is still on-going, we need to fill the buckets between prev_bucket and
            // now_bucket. |prev_bucket|...|..|...|now_bucket|
            hasOnGoingStartedEvent = true;
        }

        if (finalDuration > mDuration) {
            mDuration = finalDuration;
        }

        if (it->second.state == DurationState::kStopped) {
            // No need to keep buckets for events that were stopped before.
            mInfos.erase(it);
        } else {
            hasPendingEvent = true;
            // for kPaused, and kStarted event, we will keep track of them, and reset the start time
            // and duration.
            it->second.lastStartTime = mCurrentBucketStartTimeNs;
            it->second.lastDuration = 0;
        }
    }

    if (mDuration != 0) {
        info.set_duration_nanos(mDuration);
        mBucket.push_back(info);
        VLOG("  final duration for last bucket: %lld", (long long)mDuration);
    }

    mDuration = 0;
    if (hasOnGoingStartedEvent) {
        for (int i = 1; i < numBucketsForward; i++) {
            DurationBucketInfo info;
            info.set_start_bucket_nanos(oldBucketStartTimeNs + mBucketSizeNs * i);
            info.set_end_bucket_nanos(endTime + mBucketSizeNs * i);
            info.set_duration_nanos(mBucketSizeNs);
            mBucket.push_back(info);
            VLOG("  filling gap bucket with duration %lld", (long long)mBucketSizeNs);
        }
    }
    // If this tracker has no pending events, tell owner to remove.
    return !hasPendingEvent;
}

void MaxDurationTracker::onSlicedConditionMayChange(const uint64_t timestamp) {
    //  VLOG("Metric %lld onSlicedConditionMayChange", mMetric.metric_id());
    // Now for each of the on-going event, check if the condition has changed for them.
    for (auto& pair : mInfos) {
        if (pair.second.state == kStopped) {
            continue;
        }
        bool conditionMet = mWizard->query(mConditionTrackerIndex, pair.second.conditionKeys) ==
                            ConditionState::kTrue;
        VLOG("key: %s, condition: %d", pair.first.c_str(), conditionMet);
        noteConditionChanged(pair.first, conditionMet, timestamp);
    }
}

void MaxDurationTracker::onConditionChanged(bool condition, const uint64_t timestamp) {
    for (auto& pair : mInfos) {
        noteConditionChanged(pair.first, condition, timestamp);
    }
}

void MaxDurationTracker::noteConditionChanged(const HashableDimensionKey& key, bool conditionMet,
                                              const uint64_t timestamp) {
    auto it = mInfos.find(key);
    if (it == mInfos.end()) {
        return;
    }

    switch (it->second.state) {
        case kStarted:
            // if condition becomes false, kStarted -> kPaused. Record the current duration.
            if (!conditionMet) {
                it->second.state = DurationState::kPaused;
                it->second.lastDuration += (timestamp - it->second.lastStartTime);

                VLOG("MaxDurationTracker Key: %s Started->Paused ", key.c_str());
            }
            break;
        case kStopped:
            // nothing to do if it's stopped.
            break;
        case kPaused:
            // if condition becomes true, kPaused -> kStarted. and the start time is the condition
            // change time.
            if (conditionMet) {
                it->second.state = DurationState::kStarted;
                it->second.lastStartTime = timestamp;
                VLOG("MaxDurationTracker Key: %s Paused->Started", key.c_str());
            }
            break;
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android