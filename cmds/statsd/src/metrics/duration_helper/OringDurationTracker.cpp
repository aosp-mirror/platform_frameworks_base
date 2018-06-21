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
        sp<ConditionWizard> wizard, int conditionIndex, const vector<Matcher>& dimensionInCondition,
        bool nesting, int64_t currentBucketStartNs, int64_t currentBucketNum,
        int64_t startTimeNs, int64_t bucketSizeNs, bool conditionSliced, bool fullLink,
        const vector<sp<DurationAnomalyTracker>>& anomalyTrackers)
    : DurationTracker(key, id, eventKey, wizard, conditionIndex, dimensionInCondition, nesting,
                      currentBucketStartNs, currentBucketNum, startTimeNs, bucketSizeNs,
                      conditionSliced, fullLink, anomalyTrackers),
      mStarted(),
      mPaused() {
    mLastStartTime = 0;
    if (mWizard != nullptr) {
        mSameConditionDimensionsInTracker =
            mWizard->equalOutputDimensions(conditionIndex, mDimensionInCondition);
    }
}

unique_ptr<DurationTracker> OringDurationTracker::clone(const int64_t eventTime) {
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
        StatsdStats::getInstance().noteMetricDimensionSize(mConfigKey, mTrackerId, newTupleCount);
        // 2. Don't add more tuples, we are above the allowed threshold. Drop the data.
        if (newTupleCount > StatsdStats::kDimensionKeySizeHardLimit) {
            ALOGE("OringDurTracker %lld dropping data for dimension key %s",
                (long long)mTrackerId, newKey.toString().c_str());
            return true;
        }
    }
    return false;
}

void OringDurationTracker::noteStart(const HashableDimensionKey& key, bool condition,
                                     const int64_t eventTime, const ConditionKey& conditionKey) {
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
    VLOG("Oring: %s start, condition %d", key.toString().c_str(), condition);
}

void OringDurationTracker::noteStop(const HashableDimensionKey& key, const int64_t timestamp,
                                    const bool stopAll) {
    VLOG("Oring: %s stop", key.toString().c_str());
    auto it = mStarted.find(key);
    if (it != mStarted.end()) {
        (it->second)--;
        if (stopAll || !mNested || it->second <= 0) {
            mStarted.erase(it);
            mConditionKeyMap.erase(key);
        }
        if (mStarted.empty()) {
            mDuration += (timestamp - mLastStartTime);
            detectAndDeclareAnomaly(timestamp, mCurrentBucketNum, mDuration + mDurationFullBucket);
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
        stopAnomalyAlarm(timestamp);
    }
}

void OringDurationTracker::noteStopAll(const int64_t timestamp) {
    if (!mStarted.empty()) {
        mDuration += (timestamp - mLastStartTime);
        VLOG("Oring Stop all: record duration %lld %lld ", (long long)timestamp - mLastStartTime,
             (long long)mDuration);
        detectAndDeclareAnomaly(timestamp, mCurrentBucketNum, mDuration + mDurationFullBucket);
    }

    stopAnomalyAlarm(timestamp);
    mStarted.clear();
    mPaused.clear();
    mConditionKeyMap.clear();
}

bool OringDurationTracker::flushCurrentBucket(
        const int64_t& eventTimeNs,
        std::unordered_map<MetricDimensionKey, std::vector<DurationBucket>>* output) {
    VLOG("OringDurationTracker Flushing.............");

    // Note that we have to mimic the bucket time changes we do in the
    // MetricProducer#notifyAppUpgrade.

    int numBucketsForward = 0;
    int64_t fullBucketEnd = getCurrentBucketEndTimeNs();
    int64_t currentBucketEndTimeNs;

    if (eventTimeNs >= fullBucketEnd) {
        numBucketsForward = 1 + (eventTimeNs - fullBucketEnd) / mBucketSizeNs;
        currentBucketEndTimeNs = fullBucketEnd;
    } else {
        // This must be a partial bucket.
        currentBucketEndTimeNs = eventTimeNs;
    }

    // Process the current bucket.
    if (mStarted.size() > 0) {
        mDuration += (currentBucketEndTimeNs - mLastStartTime);
    }
    if (mDuration > 0) {
        DurationBucket current_info;
        current_info.mBucketStartNs = mCurrentBucketStartTimeNs;
        current_info.mBucketEndNs = currentBucketEndTimeNs;
        current_info.mDuration = mDuration;
        (*output)[mEventKey].push_back(current_info);
        mDurationFullBucket += mDuration;
        VLOG("  duration: %lld", (long long)current_info.mDuration);
    }
    if (eventTimeNs > fullBucketEnd) {
        // End of full bucket, can send to anomaly tracker now.
        addPastBucketToAnomalyTrackers(mDurationFullBucket, mCurrentBucketNum);
        mDurationFullBucket = 0;
    }

    if (mStarted.size() > 0) {
        for (int i = 1; i < numBucketsForward; i++) {
            DurationBucket info;
            info.mBucketStartNs = fullBucketEnd + mBucketSizeNs * (i - 1);
            info.mBucketEndNs = info.mBucketStartNs + mBucketSizeNs;
            info.mDuration = mBucketSizeNs;
            (*output)[mEventKey].push_back(info);
            // Safe to send these buckets to anomaly tracker since they must be full buckets.
            // If it's a partial bucket, numBucketsForward would be 0.
            addPastBucketToAnomalyTrackers(info.mDuration, mCurrentBucketNum + i);
            VLOG("  add filling bucket with duration %lld", (long long)info.mDuration);
        }
    } else {
        if (numBucketsForward >= 2) {
            addPastBucketToAnomalyTrackers(0, mCurrentBucketNum + numBucketsForward - 1);
        }
    }

    mDuration = 0;

    if (numBucketsForward > 0) {
        mCurrentBucketStartTimeNs = fullBucketEnd + (numBucketsForward - 1) * mBucketSizeNs;
        mCurrentBucketNum += numBucketsForward;
    } else {  // We must be forming a partial bucket.
        mCurrentBucketStartTimeNs = eventTimeNs;
    }
    mLastStartTime = mCurrentBucketStartTimeNs;

    // if all stopped, then tell owner it's safe to remove this tracker.
    return mStarted.empty() && mPaused.empty();
}

bool OringDurationTracker::flushIfNeeded(
        int64_t eventTimeNs, unordered_map<MetricDimensionKey, vector<DurationBucket>>* output) {
    if (eventTimeNs < getCurrentBucketEndTimeNs()) {
        return false;
    }
    return flushCurrentBucket(eventTimeNs, output);
}

void OringDurationTracker::onSlicedConditionMayChange(bool overallCondition,
                                                      const int64_t timestamp) {
    vector<pair<HashableDimensionKey, int>> startedToPaused;
    vector<pair<HashableDimensionKey, int>> pausedToStarted;
    if (!mStarted.empty()) {
        for (auto it = mStarted.begin(); it != mStarted.end();) {
            const auto& key = it->first;
            const auto& condIt = mConditionKeyMap.find(key);
            if (condIt == mConditionKeyMap.end()) {
                VLOG("Key %s dont have condition key", key.toString().c_str());
                ++it;
                continue;
            }
            std::unordered_set<HashableDimensionKey> conditionDimensionKeySet;
            ConditionState conditionState =
                mWizard->query(mConditionTrackerIndex, condIt->second,
                               mDimensionInCondition,
                               !mSameConditionDimensionsInTracker,
                               !mHasLinksToAllConditionDimensionsInTracker,
                               &conditionDimensionKeySet);
            if (conditionState != ConditionState::kTrue ||
                (mDimensionInCondition.size() != 0 &&
                 conditionDimensionKeySet.find(mEventKey.getDimensionKeyInCondition()) ==
                         conditionDimensionKeySet.end())) {
                startedToPaused.push_back(*it);
                it = mStarted.erase(it);
                VLOG("Key %s started -> paused", key.toString().c_str());
            } else {
                ++it;
            }
        }

        if (mStarted.empty()) {
            mDuration += (timestamp - mLastStartTime);
            VLOG("Duration add %lld , to %lld ", (long long)(timestamp - mLastStartTime),
                 (long long)mDuration);
            detectAndDeclareAnomaly(timestamp, mCurrentBucketNum, mDuration + mDurationFullBucket);
        }
    }

    if (!mPaused.empty()) {
        for (auto it = mPaused.begin(); it != mPaused.end();) {
            const auto& key = it->first;
            if (mConditionKeyMap.find(key) == mConditionKeyMap.end()) {
                VLOG("Key %s dont have condition key", key.toString().c_str());
                ++it;
                continue;
            }
            std::unordered_set<HashableDimensionKey> conditionDimensionKeySet;
            ConditionState conditionState =
                mWizard->query(mConditionTrackerIndex, mConditionKeyMap[key],
                               mDimensionInCondition,
                               !mSameConditionDimensionsInTracker,
                               !mHasLinksToAllConditionDimensionsInTracker,
                               &conditionDimensionKeySet);
            if (conditionState == ConditionState::kTrue &&
                (mDimensionInCondition.size() == 0 ||
                 conditionDimensionKeySet.find(mEventKey.getDimensionKeyInCondition()) !=
                         conditionDimensionKeySet.end())) {
                pausedToStarted.push_back(*it);
                it = mPaused.erase(it);
                VLOG("Key %s paused -> started", key.toString().c_str());
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
        stopAnomalyAlarm(timestamp);
    }
}

void OringDurationTracker::onConditionChanged(bool condition, const int64_t timestamp) {
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
            detectAndDeclareAnomaly(timestamp, mCurrentBucketNum, mDuration + mDurationFullBucket);
        }
    }
    if (mStarted.empty()) {
        stopAnomalyAlarm(timestamp);
    }
}

int64_t OringDurationTracker::predictAnomalyTimestampNs(
        const DurationAnomalyTracker& anomalyTracker, const int64_t eventTimestampNs) const {

    // The anomaly threshold.
    const int64_t thresholdNs = anomalyTracker.getAnomalyThreshold();

    // The timestamp of the current bucket end.
    const int64_t currentBucketEndNs = getCurrentBucketEndTimeNs();

    // The past duration ns for the current bucket.
    int64_t currentBucketPastNs = mDuration + mDurationFullBucket;

    // As we move into the future, old buckets get overwritten (so their old data is erased).
    // Sum of past durations. Will change as we overwrite old buckets.
    int64_t pastNs = currentBucketPastNs + anomalyTracker.getSumOverPastBuckets(mEventKey);

    // The refractory period end timestamp for dimension mEventKey.
    const int64_t refractoryPeriodEndNs =
            anomalyTracker.getRefractoryPeriodEndsSec(mEventKey) * NS_PER_SEC;

    // The anomaly should happen when accumulated wakelock duration is above the threshold and
    // not within the refractory period.
    int64_t anomalyTimestampNs =
        std::max(eventTimestampNs + thresholdNs - pastNs, refractoryPeriodEndNs);
    // If the predicted the anomaly timestamp is within the current bucket, return it directly.
    if (anomalyTimestampNs <= currentBucketEndNs) {
        return std::max(eventTimestampNs, anomalyTimestampNs);
    }

    // Remove the old bucket.
    if (anomalyTracker.getNumOfPastBuckets() > 0) {
        pastNs -= anomalyTracker.getPastBucketValue(
                            mEventKey,
                            mCurrentBucketNum - anomalyTracker.getNumOfPastBuckets());
        // Add the remaining of the current bucket to the accumulated wakelock duration.
        pastNs += (currentBucketEndNs - eventTimestampNs);
    } else {
        // The anomaly depends on only one bucket.
        pastNs = 0;
    }

    // The anomaly will not happen in the current bucket. We need to iterate over the future buckets
    // to predict the accumulated wakelock duration and determine the anomaly timestamp accordingly.
    for (int futureBucketIdx = 1; futureBucketIdx <= anomalyTracker.getNumOfPastBuckets() + 1;
            futureBucketIdx++) {
        // The alarm candidate timestamp should meet two requirements:
        // 1. the accumulated wakelock duration is above the threshold.
        // 2. it is not within the refractory period.
        // 3. the alarm timestamp falls in this bucket. Otherwise we need to flush the past buckets,
        //    find the new alarm candidate timestamp and check these requirements again.
        const int64_t bucketEndNs = currentBucketEndNs + futureBucketIdx * mBucketSizeNs;
        int64_t anomalyTimestampNs =
            std::max(bucketEndNs - mBucketSizeNs + thresholdNs - pastNs, refractoryPeriodEndNs);
        if (anomalyTimestampNs <= bucketEndNs) {
            return anomalyTimestampNs;
        }
        if (anomalyTracker.getNumOfPastBuckets() <= 0) {
            continue;
        }

        // No valid alarm timestamp is found in this bucket. The clock moves to the end of the
        // bucket. Update the pastNs.
        pastNs += mBucketSizeNs;
        // 1. If the oldest past bucket is still in the past bucket window, we could fetch the past
        // bucket and erase it from pastNs.
        // 2. If the oldest past bucket is the current bucket, we should compute the
        //   wakelock duration in the current bucket and erase it from pastNs.
        // 3. Otherwise all othe past buckets are ancient.
        if (futureBucketIdx < anomalyTracker.getNumOfPastBuckets()) {
            pastNs -= anomalyTracker.getPastBucketValue(
                    mEventKey,
                    mCurrentBucketNum - anomalyTracker.getNumOfPastBuckets() + futureBucketIdx);
        } else if (futureBucketIdx == anomalyTracker.getNumOfPastBuckets()) {
            pastNs -= (currentBucketPastNs + (currentBucketEndNs - eventTimestampNs));
        }
    }

    return std::max(eventTimestampNs + thresholdNs, refractoryPeriodEndNs);
}

void OringDurationTracker::dumpStates(FILE* out, bool verbose) const {
    fprintf(out, "\t\t started count %lu\n", (unsigned long)mStarted.size());
    fprintf(out, "\t\t paused count %lu\n", (unsigned long)mPaused.size());
    fprintf(out, "\t\t current duration %lld\n", (long long)mDuration);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
