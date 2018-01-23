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

#define DEBUG false

#include "Log.h"
#include "MaxDurationTracker.h"
#include "guardrail/StatsdStats.h"

namespace android {
namespace os {
namespace statsd {

MaxDurationTracker::MaxDurationTracker(const ConfigKey& key, const int64_t& id,
                                       const MetricDimensionKey& eventKey,
                                       sp<ConditionWizard> wizard, int conditionIndex,
                                       const FieldMatcher& dimensionInCondition, bool nesting,
                                       uint64_t currentBucketStartNs, uint64_t bucketSizeNs,
                                       bool conditionSliced,
                                       const vector<sp<DurationAnomalyTracker>>& anomalyTrackers)
    : DurationTracker(key, id, eventKey, wizard, conditionIndex, dimensionInCondition, nesting,
                      currentBucketStartNs, bucketSizeNs, conditionSliced, anomalyTrackers) {
}

unique_ptr<DurationTracker> MaxDurationTracker::clone(const uint64_t eventTime) {
    auto clonedTracker = make_unique<MaxDurationTracker>(*this);
    for (auto it = clonedTracker->mInfos.begin(); it != clonedTracker->mInfos.end(); ++it) {
        it->second.lastStartTime = eventTime;
        it->second.lastDuration = 0;
    }
    return clonedTracker;
}

bool MaxDurationTracker::hitGuardRail(const HashableDimensionKey& newKey) {
    // ===========GuardRail==============
    if (mInfos.find(newKey) != mInfos.end()) {
        // if the key existed, we are good!
        return false;
    }
    // 1. Report the tuple count if the tuple count > soft limit
    if (mInfos.size() > StatsdStats::kDimensionKeySizeSoftLimit - 1) {
        size_t newTupleCount = mInfos.size() + 1;
        StatsdStats::getInstance().noteMetricDimensionSize(
            mConfigKey, hashMetricDimensionKey(mTrackerId, mEventKey),
            newTupleCount);
        // 2. Don't add more tuples, we are above the allowed threshold. Drop the data.
        if (newTupleCount > StatsdStats::kDimensionKeySizeHardLimit) {
            ALOGE("MaxDurTracker %lld dropping data for dimension key %s",
                (long long)mTrackerId, newKey.c_str());
            return true;
        }
    }
    return false;
}

void MaxDurationTracker::noteStart(const HashableDimensionKey& key, bool condition,
                                   const uint64_t eventTime, const ConditionKey& conditionKey) {
    // this will construct a new DurationInfo if this key didn't exist.
    if (hitGuardRail(key)) {
        return;
    }

    DurationInfo& duration = mInfos[key];
    if (mConditionSliced) {
        duration.conditionKeys = conditionKey;
    }
    VLOG("MaxDuration: key %s start condition %d", key.c_str(), condition);

    switch (duration.state) {
        case kStarted:
            duration.startCount++;
            break;
        case kPaused:
            duration.startCount++;
            break;
        case kStopped:
            if (!condition) {
                // event started, but we need to wait for the condition to become true.
                duration.state = DurationState::kPaused;
            } else {
                duration.state = DurationState::kStarted;
                duration.lastStartTime = eventTime;
            }
            duration.startCount = 1;
            break;
    }
}


void MaxDurationTracker::noteStop(const HashableDimensionKey& key, const uint64_t eventTime,
                                  bool forceStop) {
    declareAnomalyIfAlarmExpired(eventTime);
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
            duration.startCount--;
            if (forceStop || !mNested || duration.startCount <= 0) {
                duration.state = DurationState::kStopped;
                int64_t durationTime = eventTime - duration.lastStartTime;
                VLOG("Max, key %s, Stop %lld %lld %lld", key.c_str(),
                     (long long)duration.lastStartTime, (long long)eventTime,
                     (long long)durationTime);
                duration.lastDuration = duration.lastDuration + durationTime;
                VLOG("  record duration: %lld ", (long long)duration.lastDuration);
            }
            break;
        }
        case DurationState::kPaused: {
            duration.startCount--;
            if (forceStop || !mNested || duration.startCount <= 0) {
                duration.state = DurationState::kStopped;
            }
            break;
        }
    }

    if (duration.lastDuration > mDuration) {
        mDuration = duration.lastDuration;
        detectAndDeclareAnomaly(eventTime, mCurrentBucketNum, mDuration);
        VLOG("Max: new max duration: %lld", (long long)mDuration);
    }
    // Once an atom duration ends, we erase it. Next time, if we see another atom event with the
    // same name, they are still considered as different atom durations.
    if (duration.state == DurationState::kStopped) {
        mInfos.erase(key);
    }
}

void MaxDurationTracker::noteStopAll(const uint64_t eventTime) {
    std::set<HashableDimensionKey> keys;
    for (const auto& pair : mInfos) {
        keys.insert(pair.first);
    }
    for (auto& key : keys) {
        noteStop(key, eventTime, true);
    }
}

bool MaxDurationTracker::flushIfNeeded(
        uint64_t eventTime, unordered_map<MetricDimensionKey, vector<DurationBucket>>* output) {
    if (mCurrentBucketStartTimeNs + mBucketSizeNs > eventTime) {
        return false;
    }

    VLOG("MaxDurationTracker flushing.....");

    // adjust the bucket start time
    int numBucketsForward = (eventTime - mCurrentBucketStartTimeNs) / mBucketSizeNs;

    uint64_t endTime = mCurrentBucketStartTimeNs + mBucketSizeNs;

    DurationBucket info;
    info.mBucketStartNs = mCurrentBucketStartTimeNs;
    info.mBucketEndNs = endTime;
    info.mBucketNum = mCurrentBucketNum;

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
        info.mDuration = mDuration;
        (*output)[mEventKey].push_back(info);
        addPastBucketToAnomalyTrackers(info.mDuration, info.mBucketNum);
        VLOG("  final duration for last bucket: %lld", (long long)mDuration);
    }

    mDuration = 0;
    if (hasOnGoingStartedEvent) {
        for (int i = 1; i < numBucketsForward; i++) {
            DurationBucket info;
            info.mBucketStartNs = oldBucketStartTimeNs + mBucketSizeNs * i;
            info.mBucketEndNs = endTime + mBucketSizeNs * i;
            info.mBucketNum = mCurrentBucketNum + i;
            info.mDuration = mBucketSizeNs;
            (*output)[mEventKey].push_back(info);
            addPastBucketToAnomalyTrackers(info.mDuration, info.mBucketNum);
            VLOG("  filling gap bucket with duration %lld", (long long)mBucketSizeNs);
        }
    }

    mCurrentBucketNum += numBucketsForward;
    // If this tracker has no pending events, tell owner to remove.
    return !hasPendingEvent;
}

void MaxDurationTracker::onSlicedConditionMayChange(const uint64_t timestamp) {
    // Now for each of the on-going event, check if the condition has changed for them.
    for (auto& pair : mInfos) {
        if (pair.second.state == kStopped) {
            continue;
        }
        std::unordered_set<HashableDimensionKey> conditionDimensionKeySet;
        ConditionState conditionState = mWizard->query(
            mConditionTrackerIndex, pair.second.conditionKeys, mDimensionInCondition,
            &conditionDimensionKeySet);
        bool conditionMet = (conditionState == ConditionState::kTrue) &&
            (!mDimensionInCondition.has_field() ||
             conditionDimensionKeySet.find(mEventKey.getDimensionKeyInCondition()) !=
                conditionDimensionKeySet.end());
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
    declareAnomalyIfAlarmExpired(timestamp);
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
    if (it->second.lastDuration > mDuration) {
        mDuration = it->second.lastDuration;
        detectAndDeclareAnomaly(timestamp, mCurrentBucketNum, mDuration);
    }
}

int64_t MaxDurationTracker::predictAnomalyTimestampNs(const DurationAnomalyTracker& anomalyTracker,
                                                      const uint64_t currentTimestamp) const {
    ALOGE("Max duration producer does not support anomaly timestamp prediction!!!");
    return currentTimestamp;
}

void MaxDurationTracker::dumpStates(FILE* out, bool verbose) const {
    fprintf(out, "\t\t sub-durations %lu\n", (unsigned long)mInfos.size());
    fprintf(out, "\t\t current duration %lld\n", (long long)mDuration);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
