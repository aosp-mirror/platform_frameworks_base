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
#include "dimension.h"

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
const int FIELD_ID_START_BUCKET_NANOS = 1;
const int FIELD_ID_END_BUCKET_NANOS = 2;
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
      mNested(nesting),
      mInternalDimensions(internalDimensions) {
    // TODO: The following boiler plate code appears in all MetricProducers, but we can't abstract
    // them in the base class, because the proto generated CountMetric, and DurationMetric are
    // not related. Maybe we should add a template in the future??
    if (metric.has_bucket()) {
        mBucketSizeNs = TimeUnitToBucketSizeInMillis(metric.bucket()) * 1000000;
    } else {
        mBucketSizeNs = LLONG_MAX;
    }

    // TODO: use UidMap if uid->pkg_name is required
    mDimensionsInWhat = metric.dimensions_in_what();
    mDimensionsInCondition = metric.dimensions_in_condition();

    if (metric.links().size() > 0) {
        mConditionLinks.insert(mConditionLinks.begin(), metric.links().begin(),
                               metric.links().end());
    }
    mConditionSliced = (metric.links().size() > 0)||
        (mDimensionsInCondition.has_field() && mDimensionsInCondition.child_size() > 0);

    VLOG("metric %lld created. bucket size %lld start_time: %lld", (long long)metric.id(),
         (long long)mBucketSizeNs, (long long)mStartTimeNs);
}

DurationMetricProducer::~DurationMetricProducer() {
    VLOG("~DurationMetric() called");
}

sp<AnomalyTracker> DurationMetricProducer::addAnomalyTracker(const Alert &alert) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (alert.trigger_if_sum_gt() > alert.num_buckets() * mBucketSizeNs) {
        ALOGW("invalid alert: threshold (%f) > possible recordable value (%d x %lld)",
              alert.trigger_if_sum_gt(), alert.num_buckets(),
              (long long)mBucketSizeNs);
        return nullptr;
    }
    sp<DurationAnomalyTracker> anomalyTracker = new DurationAnomalyTracker(alert, mConfigKey);
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
    for (auto& pair : mCurrentSlicedDurationTrackerMap) {
        pair.second->onSlicedConditionMayChange(eventTime);
    }


    std::unordered_set<HashableDimensionKey> conditionDimensionsKeySet;
    ConditionState conditionState = mWizard->getMetConditionDimension(
        mConditionTrackerIndex, mDimensionsInCondition, &conditionDimensionsKeySet);

    bool condition = (conditionState == ConditionState::kTrue);
    for (auto& pair : mCurrentSlicedDurationTrackerMap) {
        conditionDimensionsKeySet.erase(pair.first.getDimensionKeyInCondition());
    }
    std::unordered_set<MetricDimensionKey> newKeys;
    for (const auto& conditionDimensionsKey : conditionDimensionsKeySet) {
        for (auto& pair : mCurrentSlicedDurationTrackerMap) {
            auto newKey =
                MetricDimensionKey(pair.first.getDimensionKeyInWhat(), conditionDimensionsKey);
            if (newKeys.find(newKey) == newKeys.end()) {
                mCurrentSlicedDurationTrackerMap[newKey] = pair.second->clone(eventTime);
                mCurrentSlicedDurationTrackerMap[newKey]->setEventKey(newKey);
                mCurrentSlicedDurationTrackerMap[newKey]->onSlicedConditionMayChange(eventTime);
            }
            newKeys.insert(newKey);
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
    for (auto& pair : mCurrentSlicedDurationTrackerMap) {
        pair.second->onConditionChanged(conditionMet, eventTime);
    }
}

void DurationMetricProducer::onDumpReportLocked(const uint64_t dumpTimeNs, StatsLogReport* report) {
    flushIfNeededLocked(dumpTimeNs);
    report->set_metric_id(mMetricId);

    auto duration_metrics = report->mutable_duration_metrics();
    for (const auto& pair : mPastBuckets) {
        DurationMetricData* metricData = duration_metrics->add_data();
        *metricData->mutable_dimensions_in_what() =
            pair.first.getDimensionKeyInWhat().getDimensionsValue();
        *metricData->mutable_dimensions_in_condition() =
            pair.first.getDimensionKeyInCondition().getDimensionsValue();
        for (const auto& bucket : pair.second) {
            auto bucketInfo = metricData->add_bucket_info();
            bucketInfo->set_start_bucket_nanos(bucket.mBucketStartNs);
            bucketInfo->set_end_bucket_nanos(bucket.mBucketEndNs);
            bucketInfo->set_duration_nanos(bucket.mDuration);
        }
    }
}

void DurationMetricProducer::onDumpReportLocked(const uint64_t dumpTimeNs,
                                                ProtoOutputStream* protoOutput) {
    flushIfNeededLocked(dumpTimeNs);
    if (mPastBuckets.empty()) {
        return;
    }

    protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_ID, (long long)mMetricId);
    long long protoToken = protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_ID_DURATION_METRICS);

    VLOG("metric %lld dump report now...", (long long)mMetricId);

    for (const auto& pair : mPastBuckets) {
        const MetricDimensionKey& dimensionKey = pair.first;
        VLOG("  dimension key %s", dimensionKey.c_str());

        long long wrapperToken =
                protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_DATA);

        // First fill dimension.
        long long dimensionToken = protoOutput->start(
                FIELD_TYPE_MESSAGE | FIELD_ID_DIMENSION_IN_WHAT);
        writeDimensionsValueProtoToStream(
            dimensionKey.getDimensionKeyInWhat().getDimensionsValue(), protoOutput);
        protoOutput->end(dimensionToken);

        if (dimensionKey.hasDimensionKeyInCondition()) {
            long long dimensionInConditionToken = protoOutput->start(
                    FIELD_TYPE_MESSAGE | FIELD_ID_DIMENSION_IN_CONDITION);
            writeDimensionsValueProtoToStream(
                dimensionKey.getDimensionKeyInCondition().getDimensionsValue(), protoOutput);
            protoOutput->end(dimensionInConditionToken);
        }

        // Then fill bucket_info (DurationBucketInfo).
        for (const auto& bucket : pair.second) {
            long long bucketInfoToken = protoOutput->start(
                    FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_BUCKET_INFO);
            protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_START_BUCKET_NANOS,
                               (long long)bucket.mBucketStartNs);
            protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_END_BUCKET_NANOS,
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
    for (auto it = mCurrentSlicedDurationTrackerMap.begin();
            it != mCurrentSlicedDurationTrackerMap.end();) {
        if (it->second->flushIfNeeded(eventTimeNs, &mPastBuckets)) {
            VLOG("erase bucket for key %s", it->first.c_str());
            it = mCurrentSlicedDurationTrackerMap.erase(it);
        } else {
            ++it;
        }
    }

    int numBucketsForward = 1 + (eventTimeNs - currentBucketEndTimeNs) / mBucketSizeNs;
    mCurrentBucketStartTimeNs = currentBucketEndTimeNs + (numBucketsForward - 1) * mBucketSizeNs;
    mCurrentBucketNum += numBucketsForward;
}

void DurationMetricProducer::flushCurrentBucketLocked(const uint64_t& eventTimeNs) {
    for (auto it = mCurrentSlicedDurationTrackerMap.begin();
         it != mCurrentSlicedDurationTrackerMap.end();) {
        if (it->second->flushCurrentBucket(eventTimeNs, &mPastBuckets)) {
            VLOG("erase bucket for key %s", it->first.c_str());
            it = mCurrentSlicedDurationTrackerMap.erase(it);
        } else {
            ++it;
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
        for (const auto& slice : mCurrentSlicedDurationTrackerMap) {
            fprintf(out, "\t%s\n", slice.first.c_str());
            slice.second->dumpStates(out, verbose);
        }
    }
}

bool DurationMetricProducer::hitGuardRailLocked(const MetricDimensionKey& newKey) {
    // the key is not new, we are good.
    if (mCurrentSlicedDurationTrackerMap.find(newKey) != mCurrentSlicedDurationTrackerMap.end()) {
        return false;
    }
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

void DurationMetricProducer::onMatchedLogEventInternalLocked(
        const size_t matcherIndex, const MetricDimensionKey& eventKey,
        const ConditionKey& conditionKeys, bool condition,
        const LogEvent& event) {
    flushIfNeededLocked(event.GetTimestampNs());

    if (matcherIndex == mStopAllIndex) {
        for (auto& pair : mCurrentSlicedDurationTrackerMap) {
            pair.second->noteStopAll(event.GetTimestampNs());
        }
        return;
    }

    if (mCurrentSlicedDurationTrackerMap.find(eventKey) == mCurrentSlicedDurationTrackerMap.end()) {
        if (hitGuardRailLocked(eventKey)) {
            return;
        }
        mCurrentSlicedDurationTrackerMap[eventKey] = createDurationTracker(eventKey);
    }

    auto it = mCurrentSlicedDurationTrackerMap.find(eventKey);

    std::vector<DimensionsValue> values;
    getDimensionKeys(event, mInternalDimensions, &values);
    if (values.empty()) {
        if (matcherIndex == mStartIndex) {
            it->second->noteStart(DEFAULT_DIMENSION_KEY, condition,
                                  event.GetTimestampNs(), conditionKeys);
        } else if (matcherIndex == mStopIndex) {
            it->second->noteStop(DEFAULT_DIMENSION_KEY, event.GetTimestampNs(), false);
        }
    } else {
        for (const DimensionsValue& value : values) {
            if (matcherIndex == mStartIndex) {
                it->second->noteStart(
                    HashableDimensionKey(value), condition, event.GetTimestampNs(), conditionKeys);
            } else if (matcherIndex == mStopIndex) {
                it->second->noteStop(
                   HashableDimensionKey(value), event.GetTimestampNs(), false);
            }
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
