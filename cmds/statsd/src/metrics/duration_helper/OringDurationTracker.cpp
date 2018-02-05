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
#include "OringDurationTracker.h"
#include "guardrail/StatsdStats.h"

namespace android {
namespace os {
namespace statsd {

using std::pair;

OringDurationTracker::OringDurationTracker(
        const ConfigKey& key, const int64_t& id, const MetricDimensionKey& eventKey,
        sp<ConditionWizard> wizard, int conditionIndex,
        const FieldMatcher& dimensionInCondition, bool nesting, uint64_t currentBucketStartNs,
        uint64_t bucketSizeNs, bool conditionSliced,
        const vector<sp<DurationAnomalyTracker>>& anomalyTrackers)
    : DurationTracker(key, id, eventKey, wizard, conditionIndex, dimensionInCondition, nesting,
                      currentBucketStartNs, bucketSizeNs, conditionSliced, anomalyTrackers),
      mStarted(),
      mPaused() {
    mLastStartTime = 0;
}

unique_ptr<DurationTracker> OringDurationTracker::clone(const uint64_t eventTime) {
    auto clonedTracker = make_unique<OringDurationTracker>(*this);
    clonedTracker->mLastStartTime = eventTime;
    clonedTracker->mDuration = 0;
    return clonedTracker;
}

bool OringDurationTracker::hitGuardRail(const HashableDimensionKey& newKey) {
    // ===========GuardRail==============
    // 1. Report the tuple count if the tuple count > soft limit
    if (mConditionKeyMap.find(newKey) != mConditionKeyMap.end()) {
        return false;
    }
    if (mConditionKeyMap.size() > StatsdStats::kDimensionKeySizeSoftLimit - 1) {
        size_t newTupleCount = mConditionKeyMap.size() + 1;
        StatsdStats::getInstance().noteMetricDimensionSize(
            mConfigKey, hashMetricDimensionKey(mTrackerId, mEventKey),
            newTupleCount);
        // 2. Don't add more tuples, we are above the allowed threshold. Drop the data.
        if (newTupleCount > StatsdStats::kDimensionKeySizeHardLimit) {
            ALOGE("OringDurTracker %lld dropping data for dimension key %s",
                (long long)mTrackerId, newKey.c_str());
            return true;
        }
    }
    return false;
}

void OringDurationTracker::noteStart(const HashableDimensionKey& key, bool condition,
                                     const uint64_t eventTime, const ConditionKey& conditionKey) {
    if (hitGuardRail(key)) {
        return;
    }
    if (condition) {
        if (mStarted.size() == 0) {
            mLastStartTime = eventTime;
            VLOG("record first start....");
            startAnomalyAlarm(eventTime);
        }
        mStarted[key]++;
    } else {
        mPaused[key]++;
    }

    if (mConditionSliced && mConditionKeyMap.find(key) == mConditionKeyMap.end()) {
        mConditionKeyMap[key] = conditionKey;
    }
    VLOG("Oring: %s start, condition %d", key.c_str(), condition);
}

void OringDurationTracker::noteStop(const HashableDimensionKey& key, const uint64_t timestamp,
                                    const bool stopAll) {
    declareAnomalyIfAlarmExpired(timestamp);
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
            detectAndDeclareAnomaly(timestamp, mCurrentBucketNum, mDuration);
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
    if (mStarted.empty()) {
        stopAnomalyAlarm();
    }
}

void OringDurationTracker::noteStopAll(const uint64_t timestamp) {
    declareAnomalyIfAlarmExpired(timestamp);
    if (!mStarted.empty()) {
        mDuration += (timestamp - mLastStartTime);
        VLOG("Oring Stop all: record duration %lld %lld ", (long long)timestamp - mLastStartTime,
             (long long)mDuration);
        detectAndDeclareAnomaly(timestamp, mCurrentBucketNum, mDuration);
    }

    stopAnomalyAlarm();
    mStarted.clear();
    mPaused.clear();
    mConditionKeyMap.clear();
}

bool OringDurationTracker::flushIfNeeded(
        uint64_t eventTime, unordered_map<MetricDimensionKey, vector<DurationBucket>>* output) {
    if (eventTime < mCurrentBucketStartTimeNs + mBucketSizeNs) {
        return false;
    }
    VLOG("OringDurationTracker Flushing.............");
    // adjust the bucket start time
    int numBucketsForward = (eventTime - mCurrentBucketStartTimeNs) / mBucketSizeNs;
    DurationBucket current_info;
    current_info.mBucketStartNs = mCurrentBucketStartTimeNs;
    current_info.mBucketEndNs = current_info.mBucketStartNs + mBucketSizeNs;
    current_info.mBucketNum = mCurrentBucketNum;
    // Process the current bucket.
    if (mStarted.size() > 0) {
        mDuration += (current_info.mBucketEndNs - mLastStartTime);
    }
    if (mDuration > 0) {
        current_info.mDuration = mDuration;
        (*output)[mEventKey].push_back(current_info);
        addPastBucketToAnomalyTrackers(current_info.mDuration, current_info.mBucketNum);
        VLOG("  duration: %lld", (long long)current_info.mDuration);
    }

    if (mStarted.size() > 0) {
        for (int i = 1; i < numBucketsForward; i++) {
            DurationBucket info;
            info.mBucketStartNs = mCurrentBucketStartTimeNs + mBucketSizeNs * i;
            info.mBucketEndNs = info.mBucketStartNs + mBucketSizeNs;
            info.mBucketNum = mCurrentBucketNum + i;
            info.mDuration = mBucketSizeNs;
            (*output)[mEventKey].push_back(info);
            addPastBucketToAnomalyTrackers(info.mDuration, info.mBucketNum);
            VLOG("  add filling bucket with duration %lld", (long long)info.mDuration);
        }
    }
    mCurrentBucketStartTimeNs += numBucketsForward * mBucketSizeNs;
    mCurrentBucketNum += numBucketsForward;

    mLastStartTime = mCurrentBucketStartTimeNs;
    mDuration = 0;

    // if all stopped, then tell owner it's safe to remove this tracker.
    return mStarted.empty() && mPaused.empty();
}

void OringDurationTracker::onSlicedConditionMayChange(const uint64_t timestamp) {
    declareAnomalyIfAlarmExpired(timestamp);
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
            std::unordered_set<HashableDimensionKey> conditionDimensionKeySet;
            ConditionState conditionState =
                mWizard->query(mConditionTrackerIndex, mConditionKeyMap[key],
                               mDimensionInCondition, &conditionDimensionKeySet);
            if (conditionState != ConditionState::kTrue ||
                (mDimensionInCondition.has_field() &&
                 conditionDimensionKeySet.find(mEventKey.getDimensionKeyInCondition()) ==
                    conditionDimensionKeySet.end())) {
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
            detectAndDeclareAnomaly(timestamp, mCurrentBucketNum, mDuration);
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
            std::unordered_set<HashableDimensionKey> conditionDimensionKeySet;
            ConditionState conditionState =
                mWizard->query(mConditionTrackerIndex, mConditionKeyMap[key],
                               mDimensionInCondition, &conditionDimensionKeySet);
            if (conditionState == ConditionState::kTrue &&
                (!mDimensionInCondition.has_field() ||
                 conditionDimensionKeySet.find(mEventKey.getDimensionKeyInCondition())
                    != conditionDimensionKeySet.end())) {
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

    if (mStarted.empty() && !pausedToStarted.empty()) {
        startAnomalyAlarm(timestamp);
    }
    mStarted.insert(pausedToStarted.begin(), pausedToStarted.end());
    mPaused.insert(startedToPaused.begin(), startedToPaused.end());

    if (mStarted.empty()) {
        stopAnomalyAlarm();
    }
}

void OringDurationTracker::onConditionChanged(bool condition, const uint64_t timestamp) {
    declareAnomalyIfAlarmExpired(timestamp);
    if (condition) {
        if (!mPaused.empty()) {
            VLOG("Condition true, all started");
            if (mStarted.empty()) {
                mLastStartTime = timestamp;
            }
            if (mStarted.empty() && !mPaused.empty()) {
                startAnomalyAlarm(timestamp);
            }
            mStarted.insert(mPaused.begin(), mPaused.end());
            mPaused.clear();
        }
    } else {
        if (!mStarted.empty()) {
            VLOG("Condition false, all paused");
            mDuration += (timestamp - mLastStartTime);
            mPaused.insert(mStarted.begin(), mStarted.end());
            mStarted.clear();
            detectAndDeclareAnomaly(timestamp, mCurrentBucketNum, mDuration);
        }
    }
    if (mStarted.empty()) {
        stopAnomalyAlarm();
    }
}

int64_t OringDurationTracker::predictAnomalyTimestampNs(
        const DurationAnomalyTracker& anomalyTracker, const uint64_t eventTimestampNs) const {
    // TODO: Unit-test this and see if it can be done more efficiently (e.g. use int32).
    // All variables below represent durations (not timestamps).

    // The time until the current bucket ends. This is how much more 'space' it can hold.
    const int64_t currRemainingBucketSizeNs =
            mBucketSizeNs - (eventTimestampNs - mCurrentBucketStartTimeNs);
    // TODO: This should never be < 0. Document/guard against possible failures if it is.

    const int64_t thresholdNs = anomalyTracker.getAnomalyThreshold();

    // As we move into the future, old buckets get overwritten (so their old data is erased).

    // Sum of past durations. Will change as we overwrite old buckets.
    int64_t pastNs = mDuration;
    pastNs += anomalyTracker.getSumOverPastBuckets(mEventKey);

    // How much of the threshold is still unaccounted after considering pastNs.
    int64_t leftNs = thresholdNs - pastNs;

    // First deal with the remainder of the current bucket.
    if (leftNs <= currRemainingBucketSizeNs) {  // Predict the anomaly will occur in this bucket.
        return eventTimestampNs + leftNs;
    }
    // The remainder of this bucket contributes, but we must then move to the next bucket.
    pastNs += currRemainingBucketSizeNs;

    // Now deal with the past buckets, starting with the oldest.
    for (int futBucketIdx = 0; futBucketIdx < anomalyTracker.getNumOfPastBuckets();
         futBucketIdx++) {
        // We now overwrite the oldest bucket with the previous 'current', and start a new
        // 'current'.
        pastNs -= anomalyTracker.getPastBucketValue(
                mEventKey, mCurrentBucketNum - anomalyTracker.getNumOfPastBuckets() + futBucketIdx);
        leftNs = thresholdNs - pastNs;
        if (leftNs <= mBucketSizeNs) {  // Predict anomaly will occur in this bucket.
            return eventTimestampNs + currRemainingBucketSizeNs + (futBucketIdx * mBucketSizeNs) +
                   leftNs;
        } else {  // This bucket would be entirely filled, and we'll need to move to the next
                  // bucket.
            pastNs += mBucketSizeNs;
        }
    }

    // If we have reached this point, we even have to overwrite the the original current bucket.
    // Thus, none of the past data will still be extant - pastNs is now 0.
    return eventTimestampNs + thresholdNs;
}

void OringDurationTracker::dumpStates(FILE* out, bool verbose) const {
    fprintf(out, "\t\t started count %lu\n", (unsigned long)mStarted.size());
    fprintf(out, "\t\t paused count %lu\n", (unsigned long)mPaused.size());
    fprintf(out, "\t\t current duration %lld\n", (long long)mDuration);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
