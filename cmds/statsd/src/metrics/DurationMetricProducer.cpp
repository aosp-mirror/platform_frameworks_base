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
#include "DurationMetricProducer.h"
#include "guardrail/StatsdStats.h"
#include "stats_util.h"
#include "stats_log_util.h"

#include <limits.h>
#include <stdlib.h>

using android::util::FIELD_COUNT_REPEATED;
using android::util::FIELD_TYPE_BOOL;
using android::util::FIELD_TYPE_FLOAT;
using android::util::FIELD_TYPE_INT32;
using android::util::FIELD_TYPE_INT64;
using android::util::FIELD_TYPE_MESSAGE;
using android::util::FIELD_TYPE_STRING;
using android::util::ProtoOutputStream;
using std::string;
using std::unordered_map;
using std::vector;

namespace android {
namespace os {
namespace statsd {

// for StatsLogReport
const int FIELD_ID_ID = 1;
const int FIELD_ID_DURATION_METRICS = 6;
const int FIELD_ID_TIME_BASE = 9;
const int FIELD_ID_BUCKET_SIZE = 10;
const int FIELD_ID_DIMENSION_PATH_IN_WHAT = 11;
const int FIELD_ID_DIMENSION_PATH_IN_CONDITION = 12;
// for DurationMetricDataWrapper
const int FIELD_ID_DATA = 1;
// for DurationMetricData
const int FIELD_ID_DIMENSION_IN_WHAT = 1;
const int FIELD_ID_DIMENSION_IN_CONDITION = 2;
const int FIELD_ID_BUCKET_INFO = 3;
const int FIELD_ID_DIMENSION_LEAF_IN_WHAT = 4;
const int FIELD_ID_DIMENSION_LEAF_IN_CONDITION = 5;
// for DurationBucketInfo
const int FIELD_ID_DURATION = 3;
const int FIELD_ID_BUCKET_NUM = 4;
const int FIELD_ID_START_BUCKET_ELAPSED_MILLIS = 5;
const int FIELD_ID_END_BUCKET_ELAPSED_MILLIS = 6;

DurationMetricProducer::DurationMetricProducer(const ConfigKey& key, const DurationMetric& metric,
                                               const int conditionIndex, const size_t startIndex,
                                               const size_t stopIndex, const size_t stopAllIndex,
                                               const bool nesting,
                                               const sp<ConditionWizard>& wizard,
                                               const FieldMatcher& internalDimensions,
                                               const int64_t startTimeNs)
    : MetricProducer(metric.id(), key, startTimeNs, conditionIndex, wizard),
      mAggregationType(metric.aggregation_type()),
      mStartIndex(startIndex),
      mStopIndex(stopIndex),
      mStopAllIndex(stopAllIndex),
      mNested(nesting),
      mContainANYPositionInInternalDimensions(false) {
    // TODO: The following boiler plate code appears in all MetricProducers, but we can't abstract
    // them in the base class, because the proto generated CountMetric, and DurationMetric are
    // not related. Maybe we should add a template in the future??
    if (metric.has_bucket()) {
        mBucketSizeNs =
                TimeUnitToBucketSizeInMillisGuardrailed(key.GetUid(), metric.bucket()) * 1000000;
    } else {
        mBucketSizeNs = LLONG_MAX;
    }

    if (metric.has_dimensions_in_what()) {
        translateFieldMatcher(metric.dimensions_in_what(), &mDimensionsInWhat);
        mContainANYPositionInDimensionsInWhat = HasPositionANY(metric.dimensions_in_what());
    }

    if (internalDimensions.has_field()) {
        translateFieldMatcher(internalDimensions, &mInternalDimensions);
        mContainANYPositionInInternalDimensions = HasPositionANY(internalDimensions);
    }
    if (mContainANYPositionInInternalDimensions) {
        ALOGE("Position ANY in internal dimension not supported.");
    }
    if (mContainANYPositionInDimensionsInWhat) {
        ALOGE("Position ANY in dimension_in_what not supported.");
    }

    if (metric.has_dimensions_in_condition()) {
        translateFieldMatcher(metric.dimensions_in_condition(), &mDimensionsInCondition);
    }

    mSliceByPositionALL = HasPositionALL(metric.dimensions_in_what()) ||
            HasPositionALL(metric.dimensions_in_condition());

    if (metric.links().size() > 0) {
        for (const auto& link : metric.links()) {
            Metric2Condition mc;
            mc.conditionId = link.condition();
            translateFieldMatcher(link.fields_in_what(), &mc.metricFields);
            translateFieldMatcher(link.fields_in_condition(), &mc.conditionFields);
            mMetric2ConditionLinks.push_back(mc);
        }
    }
    mConditionSliced = (metric.links().size() > 0) || (mDimensionsInCondition.size() > 0);
    mUnSlicedPartCondition = ConditionState::kUnknown;

    mUseWhatDimensionAsInternalDimension = equalDimensions(mDimensionsInWhat, mInternalDimensions);
    if (mWizard != nullptr && mConditionTrackerIndex >= 0) {
        mSameConditionDimensionsInTracker =
            mWizard->equalOutputDimensions(mConditionTrackerIndex, mDimensionsInCondition);
        if (mMetric2ConditionLinks.size() == 1) {
            mHasLinksToAllConditionDimensionsInTracker =
                mWizard->equalOutputDimensions(mConditionTrackerIndex,
                                               mMetric2ConditionLinks.begin()->conditionFields);
        }
    }
    VLOG("metric %lld created. bucket size %lld start_time: %lld", (long long)metric.id(),
         (long long)mBucketSizeNs, (long long)mTimeBaseNs);
}

DurationMetricProducer::~DurationMetricProducer() {
    VLOG("~DurationMetric() called");
}

sp<AnomalyTracker> DurationMetricProducer::addAnomalyTracker(
        const Alert &alert, const sp<AlarmMonitor>& anomalyAlarmMonitor) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mAggregationType == DurationMetric_AggregationType_SUM) {
        if (alert.trigger_if_sum_gt() > alert.num_buckets() * mBucketSizeNs) {
            ALOGW("invalid alert for SUM: threshold (%f) > possible recordable value (%d x %lld)",
                  alert.trigger_if_sum_gt(), alert.num_buckets(), (long long)mBucketSizeNs);
            return nullptr;
        }
    }
    sp<DurationAnomalyTracker> anomalyTracker =
        new DurationAnomalyTracker(alert, mConfigKey, anomalyAlarmMonitor);
    if (anomalyTracker != nullptr) {
        mAnomalyTrackers.push_back(anomalyTracker);
    }
    return anomalyTracker;
}

unique_ptr<DurationTracker> DurationMetricProducer::createDurationTracker(
        const MetricDimensionKey& eventKey) const {
    switch (mAggregationType) {
        case DurationMetric_AggregationType_SUM:
            return make_unique<OringDurationTracker>(
                    mConfigKey, mMetricId, eventKey, mWizard, mConditionTrackerIndex,
                    mDimensionsInCondition, mNested, mCurrentBucketStartTimeNs, mCurrentBucketNum,
                    mTimeBaseNs, mBucketSizeNs, mConditionSliced,
                    mHasLinksToAllConditionDimensionsInTracker, mAnomalyTrackers);
        case DurationMetric_AggregationType_MAX_SPARSE:
            return make_unique<MaxDurationTracker>(
                    mConfigKey, mMetricId, eventKey, mWizard, mConditionTrackerIndex,
                    mDimensionsInCondition, mNested, mCurrentBucketStartTimeNs, mCurrentBucketNum,
                    mTimeBaseNs, mBucketSizeNs, mConditionSliced,
                    mHasLinksToAllConditionDimensionsInTracker, mAnomalyTrackers);
    }
}

// SlicedConditionChange optimization case 1:
// 1. If combination condition, logical operation is AND, only one sliced child predicate.
// 2. No condition in dimension
// 3. The links covers all dimension fields in the sliced child condition predicate.
void DurationMetricProducer::onSlicedConditionMayChangeLocked_opt1(bool condition,
                                                                   const int64_t eventTime) {
    if (mMetric2ConditionLinks.size() != 1 ||
        !mHasLinksToAllConditionDimensionsInTracker ||
        !mDimensionsInCondition.empty()) {
        return;
    }

    bool  currentUnSlicedPartCondition = true;
    if (!mWizard->IsSimpleCondition(mConditionTrackerIndex)) {
        ConditionState unslicedPartState =
            mWizard->getUnSlicedPartConditionState(mConditionTrackerIndex);
        // When the unsliced part is still false, return directly.
        if (mUnSlicedPartCondition == ConditionState::kFalse &&
            unslicedPartState == ConditionState::kFalse) {
            return;
        }
        mUnSlicedPartCondition = unslicedPartState;
        currentUnSlicedPartCondition = mUnSlicedPartCondition > 0;
    }

    auto dimensionsChangedToTrue = mWizard->getChangedToTrueDimensions(mConditionTrackerIndex);
    auto dimensionsChangedToFalse = mWizard->getChangedToFalseDimensions(mConditionTrackerIndex);

    // The condition change is from the unsliced predicates.
    // We need to find out the true dimensions from the sliced predicate and flip their condition
    // state based on the new unsliced condition state.
    if (dimensionsChangedToTrue == nullptr || dimensionsChangedToFalse == nullptr ||
        (dimensionsChangedToTrue->empty() && dimensionsChangedToFalse->empty())) {
        std::set<HashableDimensionKey> trueConditionDimensions;
        mWizard->getTrueSlicedDimensions(mConditionTrackerIndex, &trueConditionDimensions);
        for (auto& whatIt : mCurrentSlicedDurationTrackerMap) {
            HashableDimensionKey linkedConditionDimensionKey;
            getDimensionForCondition(whatIt.first.getValues(),
                                     mMetric2ConditionLinks[0],
                                     &linkedConditionDimensionKey);
            if (trueConditionDimensions.find(linkedConditionDimensionKey) !=
                    trueConditionDimensions.end()) {
                for (auto& condIt : whatIt.second) {
                    condIt.second->onConditionChanged(
                        currentUnSlicedPartCondition, eventTime);
                }
            }
        }
    } else {
        // Handle the condition change from the sliced predicate.
        if (currentUnSlicedPartCondition) {
            for (auto& whatIt : mCurrentSlicedDurationTrackerMap) {
                HashableDimensionKey linkedConditionDimensionKey;
                getDimensionForCondition(whatIt.first.getValues(),
                                         mMetric2ConditionLinks[0],
                                         &linkedConditionDimensionKey);
                if (dimensionsChangedToTrue->find(linkedConditionDimensionKey) !=
                        dimensionsChangedToTrue->end()) {
                    for (auto& condIt : whatIt.second) {
                        condIt.second->onConditionChanged(true, eventTime);
                    }
                }
                if (dimensionsChangedToFalse->find(linkedConditionDimensionKey) !=
                        dimensionsChangedToFalse->end()) {
                    for (auto& condIt : whatIt.second) {
                        condIt.second->onConditionChanged(false, eventTime);
                    }
                }
            }
        }
    }
}


// SlicedConditionChange optimization case 2:
// 1. If combination condition, logical operation is AND, only one sliced child predicate.
// 2. Has dimensions_in_condition and it equals to the output dimensions of the sliced predicate.
void DurationMetricProducer::onSlicedConditionMayChangeLocked_opt2(bool condition,
                                                                   const int64_t eventTime) {
    if (mMetric2ConditionLinks.size() > 1 || !mSameConditionDimensionsInTracker) {
        return;
    }

    auto dimensionsChangedToTrue = mWizard->getChangedToTrueDimensions(mConditionTrackerIndex);
    auto dimensionsChangedToFalse = mWizard->getChangedToFalseDimensions(mConditionTrackerIndex);

    bool  currentUnSlicedPartCondition = true;
    if (!mWizard->IsSimpleCondition(mConditionTrackerIndex)) {
        ConditionState unslicedPartState =
            mWizard->getUnSlicedPartConditionState(mConditionTrackerIndex);
        // When the unsliced part is still false, return directly.
        if (mUnSlicedPartCondition == ConditionState::kFalse &&
            unslicedPartState == ConditionState::kFalse) {
            return;
        }
        mUnSlicedPartCondition = unslicedPartState;
        currentUnSlicedPartCondition = mUnSlicedPartCondition > 0;
    }

    const std::set<HashableDimensionKey>* trueDimensionsToProcess = nullptr;
    const std::set<HashableDimensionKey>* falseDimensionsToProcess = nullptr;

    std::set<HashableDimensionKey> currentTrueConditionDimensions;
    if (dimensionsChangedToTrue == nullptr || dimensionsChangedToFalse == nullptr ||
        (dimensionsChangedToTrue->empty() && dimensionsChangedToFalse->empty())) {
        mWizard->getTrueSlicedDimensions(mConditionTrackerIndex, &currentTrueConditionDimensions);
        trueDimensionsToProcess = &currentTrueConditionDimensions;
    } else if (currentUnSlicedPartCondition) {
        // Handles the condition change from the sliced predicate. If the unsliced condition state
        // is not true, not need to do anything.
        trueDimensionsToProcess = dimensionsChangedToTrue;
        falseDimensionsToProcess = dimensionsChangedToFalse;
    }

    if (trueDimensionsToProcess == nullptr && falseDimensionsToProcess == nullptr) {
        return;
    }

    for (auto& whatIt : mCurrentSlicedDurationTrackerMap) {
        if (falseDimensionsToProcess != nullptr) {
            for (const auto& changedDim : *falseDimensionsToProcess) {
                auto condIt = whatIt.second.find(changedDim);
                if (condIt != whatIt.second.end()) {
                    condIt->second->onConditionChanged(false, eventTime);
                }
            }
        }
        if (trueDimensionsToProcess != nullptr) {
            HashableDimensionKey linkedConditionDimensionKey;
            if (!trueDimensionsToProcess->empty() && mMetric2ConditionLinks.size() == 1) {
                getDimensionForCondition(whatIt.first.getValues(),
                                         mMetric2ConditionLinks[0],
                                         &linkedConditionDimensionKey);
            }
            for (auto& trueDim : *trueDimensionsToProcess) {
                auto condIt = whatIt.second.find(trueDim);
                if (condIt != whatIt.second.end()) {
                    condIt->second->onConditionChanged(
                        currentUnSlicedPartCondition, eventTime);
                } else {
                    if (mMetric2ConditionLinks.size() == 0 ||
                        trueDim.contains(linkedConditionDimensionKey)) {
                        if (!whatIt.second.empty()) {
                            auto newEventKey = MetricDimensionKey(whatIt.first, trueDim);
                            if (hitGuardRailLocked(newEventKey)) {
                                continue;
                            }
                            unique_ptr<DurationTracker> newTracker =
                                whatIt.second.begin()->second->clone(eventTime);
                            if (newTracker != nullptr) {
                                newTracker->setEventKey(newEventKey);
                                newTracker->onConditionChanged(true, eventTime);
                                whatIt.second[trueDim] = std::move(newTracker);
                            }
                        }
                    }
                }
            }
        }
    }
}

void DurationMetricProducer::onSlicedConditionMayChangeLocked(bool overallCondition,
                                                              const int64_t eventTime) {
    VLOG("Metric %lld onSlicedConditionMayChange", (long long)mMetricId);
    flushIfNeededLocked(eventTime);

    if (!mConditionSliced) {
        return;
    }

    bool changeDimTrackable = mWizard->IsChangedDimensionTrackable(mConditionTrackerIndex);
    if (changeDimTrackable && mHasLinksToAllConditionDimensionsInTracker &&
        mDimensionsInCondition.empty()) {
        onSlicedConditionMayChangeLocked_opt1(overallCondition, eventTime);
        return;
    }

    if (changeDimTrackable && mSameConditionDimensionsInTracker &&
        mMetric2ConditionLinks.size() <= 1) {
        onSlicedConditionMayChangeLocked_opt2(overallCondition, eventTime);
        return;
    }

    // Now for each of the on-going event, check if the condition has changed for them.
    for (auto& whatIt : mCurrentSlicedDurationTrackerMap) {
        for (auto& pair : whatIt.second) {
            pair.second->onSlicedConditionMayChange(overallCondition, eventTime);
        }
    }

    if (mDimensionsInCondition.empty()) {
        return;
    }

    if (mMetric2ConditionLinks.empty()) {
        std::unordered_set<HashableDimensionKey> conditionDimensionsKeySet;
        mWizard->getMetConditionDimension(mConditionTrackerIndex, mDimensionsInCondition,
                                          !mSameConditionDimensionsInTracker,
                                          &conditionDimensionsKeySet);
        for (const auto& whatIt : mCurrentSlicedDurationTrackerMap) {
            for (const auto& pair : whatIt.second) {
                conditionDimensionsKeySet.erase(pair.first);
            }
        }
        for (const auto& conditionDimension : conditionDimensionsKeySet) {
            for (auto& whatIt : mCurrentSlicedDurationTrackerMap) {
                if (!whatIt.second.empty()) {
                    auto newEventKey = MetricDimensionKey(whatIt.first, conditionDimension);
                    if (hitGuardRailLocked(newEventKey)) {
                        continue;
                    }
                    unique_ptr<DurationTracker> newTracker =
                        whatIt.second.begin()->second->clone(eventTime);
                    if (newTracker != nullptr) {
                        newTracker->setEventKey(MetricDimensionKey(newEventKey));
                        newTracker->onSlicedConditionMayChange(overallCondition, eventTime);
                        whatIt.second[conditionDimension] = std::move(newTracker);
                    }
                }
            }
        }
    } else {
        for (auto& whatIt : mCurrentSlicedDurationTrackerMap) {
            ConditionKey conditionKey;
            for (const auto& link : mMetric2ConditionLinks) {
                getDimensionForCondition(whatIt.first.getValues(), link,
                                         &conditionKey[link.conditionId]);
            }
            std::unordered_set<HashableDimensionKey> conditionDimensionsKeys;
            mWizard->query(mConditionTrackerIndex, conditionKey, mDimensionsInCondition,
                           !mSameConditionDimensionsInTracker,
                           !mHasLinksToAllConditionDimensionsInTracker,
                           &conditionDimensionsKeys);

            for (const auto& conditionDimension : conditionDimensionsKeys) {
                if (!whatIt.second.empty() &&
                    whatIt.second.find(conditionDimension) == whatIt.second.end()) {
                    auto newEventKey = MetricDimensionKey(whatIt.first, conditionDimension);
                    if (hitGuardRailLocked(newEventKey)) {
                        continue;
                    }
                    auto newTracker = whatIt.second.begin()->second->clone(eventTime);
                    if (newTracker != nullptr) {
                        newTracker->setEventKey(newEventKey);
                        newTracker->onSlicedConditionMayChange(overallCondition, eventTime);
                        whatIt.second[conditionDimension] = std::move(newTracker);
                    }
                }
            }
        }
    }
}

void DurationMetricProducer::onConditionChangedLocked(const bool conditionMet,
                                                      const int64_t eventTime) {
    VLOG("Metric %lld onConditionChanged", (long long)mMetricId);
    mCondition = conditionMet;
    flushIfNeededLocked(eventTime);
    // TODO: need to populate the condition change time from the event which triggers the condition
    // change, instead of using current time.
    for (auto& whatIt : mCurrentSlicedDurationTrackerMap) {
        for (auto& pair : whatIt.second) {
            pair.second->onConditionChanged(conditionMet, eventTime);
        }
    }
}

void DurationMetricProducer::dropDataLocked(const int64_t dropTimeNs) {
    flushIfNeededLocked(dropTimeNs);
    mPastBuckets.clear();
}

void DurationMetricProducer::clearPastBucketsLocked(const int64_t dumpTimeNs) {
    flushIfNeededLocked(dumpTimeNs);
    mPastBuckets.clear();
}

void DurationMetricProducer::onDumpReportLocked(const int64_t dumpTimeNs,
                                                const bool include_current_partial_bucket,
                                                std::set<string> *str_set,
                                                ProtoOutputStream* protoOutput) {
    if (include_current_partial_bucket) {
        flushLocked(dumpTimeNs);
    } else {
        flushIfNeededLocked(dumpTimeNs);
    }
    if (mPastBuckets.empty()) {
        VLOG(" Duration metric, empty return");
        return;
    }

    protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_ID, (long long)mMetricId);
    protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_TIME_BASE, (long long)mTimeBaseNs);
    protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_BUCKET_SIZE, (long long)mBucketSizeNs);

    if (!mSliceByPositionALL) {
        if (!mDimensionsInWhat.empty()) {
            uint64_t dimenPathToken = protoOutput->start(
                    FIELD_TYPE_MESSAGE | FIELD_ID_DIMENSION_PATH_IN_WHAT);
            writeDimensionPathToProto(mDimensionsInWhat, protoOutput);
            protoOutput->end(dimenPathToken);
        }
        if (!mDimensionsInCondition.empty()) {
            uint64_t dimenPathToken = protoOutput->start(
                    FIELD_TYPE_MESSAGE | FIELD_ID_DIMENSION_PATH_IN_CONDITION);
            writeDimensionPathToProto(mDimensionsInCondition, protoOutput);
            protoOutput->end(dimenPathToken);
        }
    }

    uint64_t protoToken = protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_ID_DURATION_METRICS);

    VLOG("Duration metric %lld dump report now...", (long long)mMetricId);

    for (const auto& pair : mPastBuckets) {
        const MetricDimensionKey& dimensionKey = pair.first;
        VLOG("  dimension key %s", dimensionKey.toString().c_str());

        uint64_t wrapperToken =
                protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_DATA);

        // First fill dimension.
        if (mSliceByPositionALL) {
            uint64_t dimensionToken = protoOutput->start(
                    FIELD_TYPE_MESSAGE | FIELD_ID_DIMENSION_IN_WHAT);
            writeDimensionToProto(dimensionKey.getDimensionKeyInWhat(), str_set, protoOutput);
            protoOutput->end(dimensionToken);

            if (dimensionKey.hasDimensionKeyInCondition()) {
                uint64_t dimensionInConditionToken = protoOutput->start(
                        FIELD_TYPE_MESSAGE | FIELD_ID_DIMENSION_IN_CONDITION);
                writeDimensionToProto(dimensionKey.getDimensionKeyInCondition(),
                                      str_set, protoOutput);
                protoOutput->end(dimensionInConditionToken);
            }
        } else {
            writeDimensionLeafNodesToProto(dimensionKey.getDimensionKeyInWhat(),
                                           FIELD_ID_DIMENSION_LEAF_IN_WHAT, str_set, protoOutput);
            if (dimensionKey.hasDimensionKeyInCondition()) {
                writeDimensionLeafNodesToProto(dimensionKey.getDimensionKeyInCondition(),
                                               FIELD_ID_DIMENSION_LEAF_IN_CONDITION,
                                               str_set, protoOutput);
            }
        }
        // Then fill bucket_info (DurationBucketInfo).
        for (const auto& bucket : pair.second) {
            uint64_t bucketInfoToken = protoOutput->start(
                    FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_BUCKET_INFO);
            if (bucket.mBucketEndNs - bucket.mBucketStartNs != mBucketSizeNs) {
                protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_START_BUCKET_ELAPSED_MILLIS,
                                   (long long)NanoToMillis(bucket.mBucketStartNs));
                protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_END_BUCKET_ELAPSED_MILLIS,
                                   (long long)NanoToMillis(bucket.mBucketEndNs));
            } else {
                protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_BUCKET_NUM,
                                   (long long)(getBucketNumFromEndTimeNs(bucket.mBucketEndNs)));
            }
            protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_DURATION, (long long)bucket.mDuration);
            protoOutput->end(bucketInfoToken);
            VLOG("\t bucket [%lld - %lld] duration: %lld", (long long)bucket.mBucketStartNs,
                 (long long)bucket.mBucketEndNs, (long long)bucket.mDuration);
        }

        protoOutput->end(wrapperToken);
    }

    protoOutput->end(protoToken);
    mPastBuckets.clear();
}

void DurationMetricProducer::flushIfNeededLocked(const int64_t& eventTimeNs) {
    int64_t currentBucketEndTimeNs = getCurrentBucketEndTimeNs();

    if (currentBucketEndTimeNs > eventTimeNs) {
        return;
    }
    VLOG("flushing...........");
    for (auto whatIt = mCurrentSlicedDurationTrackerMap.begin();
            whatIt != mCurrentSlicedDurationTrackerMap.end();) {
        for (auto it = whatIt->second.begin(); it != whatIt->second.end();) {
            if (it->second->flushIfNeeded(eventTimeNs, &mPastBuckets)) {
                VLOG("erase bucket for key %s %s",
                     whatIt->first.toString().c_str(), it->first.toString().c_str());
                it = whatIt->second.erase(it);
            } else {
                ++it;
            }
        }
        if (whatIt->second.empty()) {
            whatIt = mCurrentSlicedDurationTrackerMap.erase(whatIt);
        } else {
            whatIt++;
        }
    }

    int numBucketsForward = 1 + (eventTimeNs - currentBucketEndTimeNs) / mBucketSizeNs;
    mCurrentBucketStartTimeNs = currentBucketEndTimeNs + (numBucketsForward - 1) * mBucketSizeNs;
    mCurrentBucketNum += numBucketsForward;
}

void DurationMetricProducer::flushCurrentBucketLocked(const int64_t& eventTimeNs) {
    for (auto whatIt = mCurrentSlicedDurationTrackerMap.begin();
            whatIt != mCurrentSlicedDurationTrackerMap.end();) {
        for (auto it = whatIt->second.begin(); it != whatIt->second.end();) {
            if (it->second->flushCurrentBucket(eventTimeNs, &mPastBuckets)) {
                VLOG("erase bucket for key %s %s", whatIt->first.toString().c_str(),
                     it->first.toString().c_str());
                it = whatIt->second.erase(it);
            } else {
                ++it;
            }
        }
        if (whatIt->second.empty()) {
            whatIt = mCurrentSlicedDurationTrackerMap.erase(whatIt);
        } else {
            whatIt++;
        }
    }
}

void DurationMetricProducer::dumpStatesLocked(FILE* out, bool verbose) const {
    if (mCurrentSlicedDurationTrackerMap.size() == 0) {
        return;
    }

    fprintf(out, "DurationMetric %lld dimension size %lu\n", (long long)mMetricId,
            (unsigned long)mCurrentSlicedDurationTrackerMap.size());
    if (verbose) {
        for (const auto& whatIt : mCurrentSlicedDurationTrackerMap) {
            for (const auto& slice : whatIt.second) {
                fprintf(out, "\t(what)%s\t(condition)%s\n", whatIt.first.toString().c_str(),
                        slice.first.toString().c_str());
                slice.second->dumpStates(out, verbose);
            }
        }
    }
}

bool DurationMetricProducer::hitGuardRailLocked(const MetricDimensionKey& newKey) {
    auto whatIt = mCurrentSlicedDurationTrackerMap.find(newKey.getDimensionKeyInWhat());
    if (whatIt != mCurrentSlicedDurationTrackerMap.end()) {
        auto condIt = whatIt->second.find(newKey.getDimensionKeyInCondition());
        if (condIt != whatIt->second.end()) {
            return false;
        }
        if (whatIt->second.size() > StatsdStats::kDimensionKeySizeSoftLimit - 1) {
            size_t newTupleCount = whatIt->second.size() + 1;
            StatsdStats::getInstance().noteMetricDimensionInConditionSize(
                    mConfigKey, mMetricId, newTupleCount);
            // 2. Don't add more tuples, we are above the allowed threshold. Drop the data.
            if (newTupleCount > StatsdStats::kDimensionKeySizeHardLimit) {
                ALOGE("DurationMetric %lld dropping data for condition dimension key %s",
                    (long long)mMetricId, newKey.getDimensionKeyInCondition().toString().c_str());
                return true;
            }
        }
    } else {
        // 1. Report the tuple count if the tuple count > soft limit
        if (mCurrentSlicedDurationTrackerMap.size() > StatsdStats::kDimensionKeySizeSoftLimit - 1) {
            size_t newTupleCount = mCurrentSlicedDurationTrackerMap.size() + 1;
            StatsdStats::getInstance().noteMetricDimensionSize(
                    mConfigKey, mMetricId, newTupleCount);
            // 2. Don't add more tuples, we are above the allowed threshold. Drop the data.
            if (newTupleCount > StatsdStats::kDimensionKeySizeHardLimit) {
                ALOGE("DurationMetric %lld dropping data for what dimension key %s",
                    (long long)mMetricId, newKey.getDimensionKeyInWhat().toString().c_str());
                return true;
            }
        }
    }
    return false;
}

void DurationMetricProducer::handleStartEvent(const MetricDimensionKey& eventKey,
                                              const ConditionKey& conditionKeys,
                                              bool condition, const LogEvent& event) {
    const auto& whatKey = eventKey.getDimensionKeyInWhat();
    const auto& condKey = eventKey.getDimensionKeyInCondition();

    auto whatIt = mCurrentSlicedDurationTrackerMap.find(whatKey);
    if (whatIt == mCurrentSlicedDurationTrackerMap.end()) {
        if (hitGuardRailLocked(eventKey)) {
            return;
        }
        mCurrentSlicedDurationTrackerMap[whatKey][condKey] = createDurationTracker(eventKey);
    } else {
        if (whatIt->second.find(condKey) == whatIt->second.end()) {
            if (hitGuardRailLocked(eventKey)) {
                return;
            }
            mCurrentSlicedDurationTrackerMap[whatKey][condKey] = createDurationTracker(eventKey);
        }
    }

    auto it = mCurrentSlicedDurationTrackerMap.find(whatKey)->second.find(condKey);
    if (mUseWhatDimensionAsInternalDimension) {
        it->second->noteStart(whatKey, condition,
                              event.GetElapsedTimestampNs(), conditionKeys);
        return;
    }

    if (mInternalDimensions.empty()) {
        it->second->noteStart(DEFAULT_DIMENSION_KEY, condition,
                              event.GetElapsedTimestampNs(), conditionKeys);
    } else {
        HashableDimensionKey dimensionKey = DEFAULT_DIMENSION_KEY;
        filterValues(mInternalDimensions, event.getValues(), &dimensionKey);
        it->second->noteStart(
            dimensionKey, condition, event.GetElapsedTimestampNs(), conditionKeys);
    }

}

void DurationMetricProducer::onMatchedLogEventInternalLocked(
        const size_t matcherIndex, const MetricDimensionKey& eventKey,
        const ConditionKey& conditionKeys, bool condition,
        const LogEvent& event) {
    ALOGW("Not used in duration tracker.");
}

void DurationMetricProducer::onMatchedLogEventLocked(const size_t matcherIndex,
                                                     const LogEvent& event) {
    int64_t eventTimeNs = event.GetElapsedTimestampNs();
    if (eventTimeNs < mTimeBaseNs) {
        return;
    }

    flushIfNeededLocked(event.GetElapsedTimestampNs());

    // Handles Stopall events.
    if (matcherIndex == mStopAllIndex) {
        for (auto& whatIt : mCurrentSlicedDurationTrackerMap) {
            for (auto& pair : whatIt.second) {
                pair.second->noteStopAll(event.GetElapsedTimestampNs());
            }
        }
        return;
    }

    HashableDimensionKey dimensionInWhat;
    if (!mDimensionsInWhat.empty()) {
        filterValues(mDimensionsInWhat, event.getValues(), &dimensionInWhat);
    } else {
       dimensionInWhat = DEFAULT_DIMENSION_KEY;
    }

    // Handles Stop events.
    if (matcherIndex == mStopIndex) {
        if (mUseWhatDimensionAsInternalDimension) {
            auto whatIt = mCurrentSlicedDurationTrackerMap.find(dimensionInWhat);
            if (whatIt != mCurrentSlicedDurationTrackerMap.end()) {
                for (const auto& condIt : whatIt->second) {
                    condIt.second->noteStop(dimensionInWhat, event.GetElapsedTimestampNs(), false);
                }
            }
            return;
        }

        HashableDimensionKey internalDimensionKey = DEFAULT_DIMENSION_KEY;
        if (!mInternalDimensions.empty()) {
            filterValues(mInternalDimensions, event.getValues(), &internalDimensionKey);
        }

        auto whatIt = mCurrentSlicedDurationTrackerMap.find(dimensionInWhat);
        if (whatIt != mCurrentSlicedDurationTrackerMap.end()) {
            for (const auto& condIt : whatIt->second) {
                condIt.second->noteStop(
                    internalDimensionKey, event.GetElapsedTimestampNs(), false);
            }
        }
        return;
    }

    bool condition;
    ConditionKey conditionKey;
    std::unordered_set<HashableDimensionKey> dimensionKeysInCondition;
    if (mConditionSliced) {
        for (const auto& link : mMetric2ConditionLinks) {
            getDimensionForCondition(event.getValues(), link, &conditionKey[link.conditionId]);
        }

        auto conditionState =
            mWizard->query(mConditionTrackerIndex, conditionKey, mDimensionsInCondition,
                           !mSameConditionDimensionsInTracker,
                           !mHasLinksToAllConditionDimensionsInTracker,
                           &dimensionKeysInCondition);
        condition = (conditionState == ConditionState::kTrue);
        if (mDimensionsInCondition.empty() && condition) {
            dimensionKeysInCondition.insert(DEFAULT_DIMENSION_KEY);
        }
    } else {
        condition = mCondition;
        if (condition) {
            dimensionKeysInCondition.insert(DEFAULT_DIMENSION_KEY);
        }
    }

    if (dimensionKeysInCondition.empty()) {
        handleStartEvent(MetricDimensionKey(dimensionInWhat, DEFAULT_DIMENSION_KEY),
                         conditionKey, condition, event);
    } else {
        auto whatIt = mCurrentSlicedDurationTrackerMap.find(dimensionInWhat);
        // If the what dimension is already there, we should update all the trackers even
        // the condition is false.
        if (whatIt != mCurrentSlicedDurationTrackerMap.end()) {
            for (const auto& condIt : whatIt->second) {
                const bool cond = dimensionKeysInCondition.find(condIt.first) !=
                        dimensionKeysInCondition.end();
                handleStartEvent(MetricDimensionKey(dimensionInWhat, condIt.first),
                    conditionKey, cond, event);
                dimensionKeysInCondition.erase(condIt.first);
            }
        }
        for (const auto& conditionDimension : dimensionKeysInCondition) {
            handleStartEvent(MetricDimensionKey(dimensionInWhat, conditionDimension), conditionKey,
                             condition, event);
        }
    }
}

size_t DurationMetricProducer::byteSizeLocked() const {
    size_t totalSize = 0;
    for (const auto& pair : mPastBuckets) {
        totalSize += pair.second.size() * kBucketSize;
    }
    return totalSize;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
