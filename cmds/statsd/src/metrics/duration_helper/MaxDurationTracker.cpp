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
                                       const vector<Matcher>& dimensionInCondition, bool nesting,
                                       uint64_t currentBucketStartNs, uint64_t currentBucketNum,
                                       uint64_t startTimeNs, uint64_t bucketSizeNs,
                                       bool conditionSliced,
                                       const vector<sp<DurationAnomalyTracker>>& anomalyTrackers)
    : DurationTracker(key, id, eventKey, wizard, conditionIndex, dimensionInCondition, nesting,
                      currentBucketStartNs, currentBucketNum, startTimeNs, bucketSizeNs,
                      conditionSliced, anomalyTrackers) {
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
        StatsdStats::getInstance().noteMetricDimensionSize(mConfigKey, mTrackerId, newTupleCount);
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
                duration.lastDuration += durationTime;
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

bool MaxDurationTracker::flushCurrentBucket(
        const uint64_t& eventTimeNs,
        std::unordered_map<MetricDimensionKey, std::vector<DurationBucket>>* output) {
    VLOG("MaxDurationTracker flushing.....");

    // adjust the bucket start time
    int numBucketsForward = 0;
    uint64_t fullBucketEnd = getCurrentBucketEndTimeNs();
    uint64_t currentBucketEndTimeNs;
    if (eventTimeNs >= fullBucketEnd) {
        numBucketsForward = 1 + (eventTimeNs - fullBucketEnd) / mBucketSizeNs;
        currentBucketEndTimeNs = fullBucketEnd;
    } else {
        // This must be a partial bucket.
        currentBucketEndTimeNs = eventTimeNs;
    }

    bool hasPendingEvent =
            false;  // has either a kStarted or kPaused event across bucket boundaries
    // meaning we need to carry them over to the new bucket.
    for (auto it = mInfos.begin(); it != mInfos.end(); ++it) {
        if (it->second.state == DurationState::kStopped) {
            // No need to keep buckets for events that were stopped before.
            mInfos.erase(it);
        } else {
            hasPendingEvent = true;
        }
    }

    // mDuration is updated in noteStop to the maximum duration that ended in the current bucket.
    if (mDuration != 0) {
        DurationBucket info;
        info.mBucketStartNs = mCurrentBucketStartTimeNs;
        info.mBucketEndNs = currentBucketEndTimeNs;
        info.mBucketNum = mCurrentBucketNum;
        info.mDuration = mDuration;
        (*output)[mEventKey].push_back(info);
        VLOG("  final duration for last bucket: %lld", (long long)mDuration);
    }

    if (numBucketsForward > 0) {
        mCurrentBucketStartTimeNs = fullBucketEnd + (numBucketsForward - 1) * mBucketSizeNs;
        mCurrentBucketNum += numBucketsForward;
    } else {  // We must be forming a partial bucket.
        mCurrentBucketStartTimeNs = eventTimeNs;
    }

    mDuration = 0;
    // If this tracker has no pending events, tell owner to remove.
    return !hasPendingEvent;
}

bool MaxDurationTracker::flushIfNeeded(
        uint64_t eventTimeNs, unordered_map<MetricDimensionKey, vector<DurationBucket>>* output) {
    if (eventTimeNs < getCurrentBucketEndTimeNs()) {
        return false;
    }
    return flushCurrentBucket(eventTimeNs, output);
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
        bool conditionMet =
                (conditionState == ConditionState::kTrue) &&
                (mDimensionInCondition.size() == 0 ||
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
