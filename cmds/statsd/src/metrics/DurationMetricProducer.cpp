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
// for DurationMetricDataWrapper
const int FIELD_ID_DATA = 1;
// for DurationMetricData
const int FIELD_ID_DIMENSION_IN_WHAT = 1;
const int FIELD_ID_DIMENSION_IN_CONDITION = 2;
const int FIELD_ID_BUCKET_INFO = 3;
// for DurationBucketInfo
const int FIELD_ID_START_BUCKET_ELAPSED_NANOS = 1;
const int FIELD_ID_END_BUCKET_ELAPSED_NANOS = 2;
const int FIELD_ID_DURATION = 3;

DurationMetricProducer::DurationMetricProducer(const ConfigKey& key, const DurationMetric& metric,
                                               const int conditionIndex, const size_t startIndex,
                                               const size_t stopIndex, const size_t stopAllIndex,
                                               const bool nesting,
                                               const sp<ConditionWizard>& wizard,
                                               const FieldMatcher& internalDimensions,
                                               const uint64_t startTimeNs)
    : MetricProducer(metric.id(), key, startTimeNs, conditionIndex, wizard),
      mAggregationType(metric.aggregation_type()),
      mStartIndex(startIndex),
      mStopIndex(stopIndex),
      mStopAllIndex(stopAllIndex),
      mNested(nesting) {
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
    }

    if (internalDimensions.has_field()) {
        translateFieldMatcher(internalDimensions, &mInternalDimensions);
    }

    if (metric.has_dimensions_in_condition()) {
        translateFieldMatcher(metric.dimensions_in_condition(), &mDimensionsInCondition);
    }

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

    if (mDimensionsInWhat.size() == mInternalDimensions.size()) {
        bool mUseWhatDimensionAsInternalDimension = true;
        for (size_t i = 0; mUseWhatDimensionAsInternalDimension &&
            i < mDimensionsInWhat.size(); ++i) {
            if (mDimensionsInWhat[i] != mInternalDimensions[i]) {
                mUseWhatDimensionAsInternalDimension = false;
            }
        }
    } else {
        mUseWhatDimensionAsInternalDimension = false;
    }

    VLOG("metric %lld created. bucket size %lld start_time: %lld", (long long)metric.id(),
         (long long)mBucketSizeNs, (long long)mStartTimeNs);
}

DurationMetricProducer::~DurationMetricProducer() {
    VLOG("~DurationMetric() called");
}

sp<AnomalyTracker> DurationMetricProducer::addAnomalyTracker(
        const Alert &alert, const sp<AlarmMonitor>& anomalyAlarmMonitor) {
    std::lock_guard<std::mutex> lock(mMutex);
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
                    mStartTimeNs, mBucketSizeNs, mConditionSliced, mAnomalyTrackers);
        case DurationMetric_AggregationType_MAX_SPARSE:
            return make_unique<MaxDurationTracker>(
                    mConfigKey, mMetricId, eventKey, mWizard, mConditionTrackerIndex,
                    mDimensionsInCondition, mNested, mCurrentBucketStartTimeNs, mCurrentBucketNum,
                    mStartTimeNs, mBucketSizeNs, mConditionSliced, mAnomalyTrackers);
    }
}

void DurationMetricProducer::onSlicedConditionMayChangeLocked(const uint64_t eventTime) {
    VLOG("Metric %lld onSlicedConditionMayChange", (long long)mMetricId);
    flushIfNeededLocked(eventTime);

    // Now for each of the on-going event, check if the condition has changed for them.
    for (auto& whatIt : mCurrentSlicedDurationTrackerMap) {
        for (auto& pair : whatIt.second) {
            pair.second->onSlicedConditionMayChange(eventTime);
        }
    }

    if (mDimensionsInCondition.empty()) {
        return;
    }

    if (mMetric2ConditionLinks.empty()) {
        std::unordered_set<HashableDimensionKey> conditionDimensionsKeySet;
        mWizard->getMetConditionDimension(mConditionTrackerIndex, mDimensionsInCondition,
                                          &conditionDimensionsKeySet);
        for (const auto& whatIt : mCurrentSlicedDurationTrackerMap) {
            for (const auto& pair : whatIt.second) {
                conditionDimensionsKeySet.erase(pair.first);
            }
        }
        for (const auto& conditionDimension : conditionDimensionsKeySet) {
            for (auto& whatIt : mCurrentSlicedDurationTrackerMap) {
                if (!whatIt.second.empty()) {
                    unique_ptr<DurationTracker> newTracker =
                        whatIt.second.begin()->second->clone(eventTime);
                    newTracker->setEventKey(MetricDimensionKey(whatIt.first, conditionDimension));
                    newTracker->onSlicedConditionMayChange(eventTime);
                    whatIt.second[conditionDimension] = std::move(newTracker);
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
                           &conditionDimensionsKeys);

            for (const auto& conditionDimension : conditionDimensionsKeys) {
                if (!whatIt.second.empty() &&
                    whatIt.second.find(conditionDimension) == whatIt.second.end()) {
                    auto newTracker = whatIt.second.begin()->second->clone(eventTime);
                    newTracker->setEventKey(MetricDimensionKey(whatIt.first, conditionDimension));
                    newTracker->onSlicedConditionMayChange(eventTime);
                    whatIt.second[conditionDimension] = std::move(newTracker);
                }
            }
        }
    }
}

void DurationMetricProducer::onConditionChangedLocked(const bool conditionMet,
                                                      const uint64_t eventTime) {
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

void DurationMetricProducer::dropDataLocked(const uint64_t dropTimeNs) {
    flushIfNeededLocked(dropTimeNs);
    mPastBuckets.clear();
}

void DurationMetricProducer::onDumpReportLocked(const uint64_t dumpTimeNs,
                                                ProtoOutputStream* protoOutput) {
    flushIfNeededLocked(dumpTimeNs);
    if (mPastBuckets.empty()) {
        VLOG(" Duration metric, empty return");
        return;
    }

    protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_ID, (long long)mMetricId);
    long long protoToken = protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_ID_DURATION_METRICS);

    VLOG("Duration metric %lld dump report now...", (long long)mMetricId);

    for (const auto& pair : mPastBuckets) {
        const MetricDimensionKey& dimensionKey = pair.first;
        VLOG("  dimension key %s", dimensionKey.c_str());

        long long wrapperToken =
                protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_DATA);

        // First fill dimension.
        long long dimensionToken = protoOutput->start(
                FIELD_TYPE_MESSAGE | FIELD_ID_DIMENSION_IN_WHAT);
        writeDimensionToProto(dimensionKey.getDimensionKeyInWhat(), protoOutput);
        protoOutput->end(dimensionToken);

        if (dimensionKey.hasDimensionKeyInCondition()) {
            long long dimensionInConditionToken = protoOutput->start(
                    FIELD_TYPE_MESSAGE | FIELD_ID_DIMENSION_IN_CONDITION);
            writeDimensionToProto(dimensionKey.getDimensionKeyInCondition(), protoOutput);
            protoOutput->end(dimensionInConditionToken);
        }

        // Then fill bucket_info (DurationBucketInfo).
        for (const auto& bucket : pair.second) {
            long long bucketInfoToken = protoOutput->start(
                    FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_BUCKET_INFO);
            protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_START_BUCKET_ELAPSED_NANOS,
                               (long long)bucket.mBucketStartNs);
            protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_END_BUCKET_ELAPSED_NANOS,
                               (long long)bucket.mBucketEndNs);
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

void DurationMetricProducer::flushIfNeededLocked(const uint64_t& eventTimeNs) {
    uint64_t currentBucketEndTimeNs = getCurrentBucketEndTimeNs();

    if (currentBucketEndTimeNs > eventTimeNs) {
        return;
    }
    VLOG("flushing...........");
    for (auto whatIt = mCurrentSlicedDurationTrackerMap.begin();
            whatIt != mCurrentSlicedDurationTrackerMap.end();) {
        for (auto it = whatIt->second.begin(); it != whatIt->second.end();) {
            if (it->second->flushIfNeeded(eventTimeNs, &mPastBuckets)) {
                VLOG("erase bucket for key %s %s", whatIt->first.c_str(), it->first.c_str());
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

void DurationMetricProducer::flushCurrentBucketLocked(const uint64_t& eventTimeNs) {
    for (auto whatIt = mCurrentSlicedDurationTrackerMap.begin();
            whatIt != mCurrentSlicedDurationTrackerMap.end();) {
        for (auto it = whatIt->second.begin(); it != whatIt->second.end();) {
            if (it->second->flushCurrentBucket(eventTimeNs, &mPastBuckets)) {
                VLOG("erase bucket for key %s %s", whatIt->first.c_str(), it->first.c_str());
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
                fprintf(out, "\t%s\t%s\n", whatIt.first.c_str(), slice.first.c_str());
                slice.second->dumpStates(out, verbose);
            }
        }
    }
}

bool DurationMetricProducer::hitGuardRailLocked(const MetricDimensionKey& newKey) {
    // 1. Report the tuple count if the tuple count > soft limit
    if (mCurrentSlicedDurationTrackerMap.size() > StatsdStats::kDimensionKeySizeSoftLimit - 1) {
        size_t newTupleCount = mCurrentSlicedDurationTrackerMap.size() + 1;
        StatsdStats::getInstance().noteMetricDimensionSize(mConfigKey, mMetricId, newTupleCount);
        // 2. Don't add more tuples, we are above the allowed threshold. Drop the data.
        if (newTupleCount > StatsdStats::kDimensionKeySizeHardLimit) {
            ALOGE("DurationMetric %lld dropping data for dimension key %s",
                (long long)mMetricId, newKey.c_str());
            return true;
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

    std::vector<HashableDimensionKey> values;
    filterValues(mInternalDimensions, event.getValues(), &values);
    if (values.empty()) {
        it->second->noteStart(DEFAULT_DIMENSION_KEY, condition,
                              event.GetElapsedTimestampNs(), conditionKeys);
    } else {
        for (const auto& value : values) {
            it->second->noteStart(value, condition, event.GetElapsedTimestampNs(), conditionKeys);
        }
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
    uint64_t eventTimeNs = event.GetElapsedTimestampNs();
    if (eventTimeNs < mStartTimeNs) {
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

    vector<HashableDimensionKey> dimensionInWhatValues;
    if (!mDimensionsInWhat.empty()) {
        filterValues(mDimensionsInWhat, event.getValues(), &dimensionInWhatValues);
    } else {
        dimensionInWhatValues.push_back(DEFAULT_DIMENSION_KEY);
    }

    // Handles Stop events.
    if (matcherIndex == mStopIndex) {
        if (mUseWhatDimensionAsInternalDimension) {
            for (const HashableDimensionKey& whatKey : dimensionInWhatValues) {
                auto whatIt = mCurrentSlicedDurationTrackerMap.find(whatKey);
                if (whatIt != mCurrentSlicedDurationTrackerMap.end()) {
                    for (const auto& condIt : whatIt->second) {
                        condIt.second->noteStop(whatKey, event.GetElapsedTimestampNs(), false);
                    }
                }
            }
            return;
        }

        std::vector<HashableDimensionKey> internalDimensionKeys;
        filterValues(mInternalDimensions, event.getValues(), &internalDimensionKeys);
        if (internalDimensionKeys.empty()) {
            internalDimensionKeys.push_back(DEFAULT_DIMENSION_KEY);
        }
        for (const HashableDimensionKey& whatDimension : dimensionInWhatValues) {
            auto whatIt = mCurrentSlicedDurationTrackerMap.find(whatDimension);
            if (whatIt != mCurrentSlicedDurationTrackerMap.end()) {
                for (const auto& condIt : whatIt->second) {
                    for (const auto& internalDimensionKey : internalDimensionKeys) {
                        condIt.second->noteStop(
                            internalDimensionKey, event.GetElapsedTimestampNs(), false);
                    }
                }
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

    for (const auto& whatDimension : dimensionInWhatValues) {
        auto whatIt = mCurrentSlicedDurationTrackerMap.find(whatDimension);
        // If the what dimension is already there, we should update all the trackers even
        // the condition is false.
        if (whatIt != mCurrentSlicedDurationTrackerMap.end()) {
            for (const auto& condIt : whatIt->second) {
                const bool cond = dimensionKeysInCondition.find(condIt.first) !=
                        dimensionKeysInCondition.end();
                handleStartEvent(MetricDimensionKey(whatDimension, condIt.first),
                                 conditionKey, cond, event);
            }
        } else {
            // If it is a new what dimension key, we need to handle the start events for all current
            // condition dimensions.
            for (const auto& conditionDimension : dimensionKeysInCondition) {
                handleStartEvent(MetricDimensionKey(whatDimension, conditionDimension),
                                 conditionKey, condition, event);
            }
        }
        if (dimensionKeysInCondition.empty()) {
            handleStartEvent(MetricDimensionKey(whatDimension, DEFAULT_DIMENSION_KEY),
                             conditionKey, condition, event);
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
