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
const int FIELD_ID_NAME = 1;
const int FIELD_ID_START_REPORT_NANOS = 2;
const int FIELD_ID_END_REPORT_NANOS = 3;
const int FIELD_ID_DURATION_METRICS = 6;
// for DurationMetricDataWrapper
const int FIELD_ID_DATA = 1;
// for DurationMetricData
const int FIELD_ID_DIMENSION = 1;
const int FIELD_ID_BUCKET_INFO = 2;
// for KeyValuePair
const int FIELD_ID_KEY = 1;
const int FIELD_ID_VALUE_STR = 2;
const int FIELD_ID_VALUE_INT = 3;
const int FIELD_ID_VALUE_BOOL = 4;
const int FIELD_ID_VALUE_FLOAT = 5;
// for DurationBucketInfo
const int FIELD_ID_START_BUCKET_NANOS = 1;
const int FIELD_ID_END_BUCKET_NANOS = 2;
const int FIELD_ID_DURATION = 3;

DurationMetricProducer::DurationMetricProducer(const ConfigKey& key, const DurationMetric& metric,
                                               const int conditionIndex, const size_t startIndex,
                                               const size_t stopIndex, const size_t stopAllIndex,
                                               const bool nesting,
                                               const sp<ConditionWizard>& wizard,
                                               const vector<KeyMatcher>& internalDimension,
                                               const uint64_t startTimeNs)
    : MetricProducer(metric.name(), key, startTimeNs, conditionIndex, wizard),
      mAggregationType(metric.aggregation_type()),
      mStartIndex(startIndex),
      mStopIndex(stopIndex),
      mStopAllIndex(stopAllIndex),
      mNested(nesting),
      mInternalDimension(internalDimension) {
    // TODO: The following boiler plate code appears in all MetricProducers, but we can't abstract
    // them in the base class, because the proto generated CountMetric, and DurationMetric are
    // not related. Maybe we should add a template in the future??
    if (metric.has_bucket() && metric.bucket().has_bucket_size_millis()) {
        mBucketSizeNs = metric.bucket().bucket_size_millis() * 1000000;
    } else {
        mBucketSizeNs = LLONG_MAX;
    }

    // TODO: use UidMap if uid->pkg_name is required
    mDimension.insert(mDimension.begin(), metric.dimension().begin(), metric.dimension().end());

    if (metric.links().size() > 0) {
        mConditionLinks.insert(mConditionLinks.begin(), metric.links().begin(),
                               metric.links().end());
        mConditionSliced = true;
    }

    VLOG("metric %s created. bucket size %lld start_time: %lld", metric.name().c_str(),
         (long long)mBucketSizeNs, (long long)mStartTimeNs);
}

DurationMetricProducer::~DurationMetricProducer() {
    VLOG("~DurationMetric() called");
}

sp<AnomalyTracker> DurationMetricProducer::addAnomalyTracker(const Alert &alert) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (alert.trigger_if_sum_gt() > alert.number_of_buckets() * mBucketSizeNs) {
        ALOGW("invalid alert: threshold (%lld) > possible recordable value (%d x %lld)",
              alert.trigger_if_sum_gt(), alert.number_of_buckets(),
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
        const HashableDimensionKey& eventKey) const {
    switch (mAggregationType) {
        case DurationMetric_AggregationType_SUM:
            return make_unique<OringDurationTracker>(
                    mConfigKey, mName, eventKey, mWizard, mConditionTrackerIndex, mNested,
                    mCurrentBucketStartTimeNs, mBucketSizeNs, mAnomalyTrackers);
        case DurationMetric_AggregationType_MAX_SPARSE:
            return make_unique<MaxDurationTracker>(
                    mConfigKey, mName, eventKey, mWizard, mConditionTrackerIndex, mNested,
                    mCurrentBucketStartTimeNs, mBucketSizeNs, mAnomalyTrackers);
    }
}

void DurationMetricProducer::onSlicedConditionMayChangeLocked(const uint64_t eventTime) {
    VLOG("Metric %s onSlicedConditionMayChange", mName.c_str());
    flushIfNeededLocked(eventTime);
    // Now for each of the on-going event, check if the condition has changed for them.
    for (auto& pair : mCurrentSlicedDuration) {
        pair.second->onSlicedConditionMayChange(eventTime);
    }
}

void DurationMetricProducer::onConditionChangedLocked(const bool conditionMet,
                                                      const uint64_t eventTime) {
    VLOG("Metric %s onConditionChanged", mName.c_str());
    mCondition = conditionMet;
    flushIfNeededLocked(eventTime);
    // TODO: need to populate the condition change time from the event which triggers the condition
    // change, instead of using current time.
    for (auto& pair : mCurrentSlicedDuration) {
        pair.second->onConditionChanged(conditionMet, eventTime);
    }
}

void DurationMetricProducer::onDumpReportLocked(const uint64_t dumpTimeNs,
                                                ProtoOutputStream* protoOutput) {
    flushIfNeededLocked(dumpTimeNs);

    protoOutput->write(FIELD_TYPE_STRING | FIELD_ID_NAME, mName);
    protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_START_REPORT_NANOS, (long long)mStartTimeNs);
    long long protoToken = protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_ID_DURATION_METRICS);

    VLOG("metric %s dump report now...", mName.c_str());

    for (const auto& pair : mPastBuckets) {
        const HashableDimensionKey& hashableKey = pair.first;
        const vector<KeyValuePair>& kvs = hashableKey.getKeyValuePairs();
        VLOG("  dimension key %s", hashableKey.c_str());

        long long wrapperToken =
                protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_DATA);

        // First fill dimension (KeyValuePairs).
        for (const auto& kv : kvs) {
            long long dimensionToken = protoOutput->start(
                    FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_DIMENSION);
            protoOutput->write(FIELD_TYPE_INT32 | FIELD_ID_KEY, kv.key());
            if (kv.has_value_str()) {
                protoOutput->write(FIELD_TYPE_STRING | FIELD_ID_VALUE_STR, kv.value_str());
            } else if (kv.has_value_int()) {
                protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_VALUE_INT, kv.value_int());
            } else if (kv.has_value_bool()) {
                protoOutput->write(FIELD_TYPE_BOOL | FIELD_ID_VALUE_BOOL, kv.value_bool());
            } else if (kv.has_value_float()) {
                protoOutput->write(FIELD_TYPE_FLOAT | FIELD_ID_VALUE_FLOAT, kv.value_float());
            }
            protoOutput->end(dimensionToken);
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
    protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_END_REPORT_NANOS, (long long)dumpTimeNs);
    mPastBuckets.clear();
    mStartTimeNs = mCurrentBucketStartTimeNs;
}

void DurationMetricProducer::flushIfNeededLocked(const uint64_t& eventTime) {
    if (mCurrentBucketStartTimeNs + mBucketSizeNs > eventTime) {
        return;
    }
    VLOG("flushing...........");
    for (auto it = mCurrentSlicedDuration.begin(); it != mCurrentSlicedDuration.end();) {
        if (it->second->flushIfNeeded(eventTime, &mPastBuckets)) {
            VLOG("erase bucket for key %s", it->first.c_str());
            it = mCurrentSlicedDuration.erase(it);
        } else {
            ++it;
        }
    }

    int numBucketsForward = (eventTime - mCurrentBucketStartTimeNs) / mBucketSizeNs;
    mCurrentBucketStartTimeNs += numBucketsForward * mBucketSizeNs;
    mCurrentBucketNum += numBucketsForward;
}

bool DurationMetricProducer::hitGuardRailLocked(const HashableDimensionKey& newKey) {
    // the key is not new, we are good.
    if (mCurrentSlicedDuration.find(newKey) != mCurrentSlicedDuration.end()) {
        return false;
    }
    // 1. Report the tuple count if the tuple count > soft limit
    if (mCurrentSlicedDuration.size() > StatsdStats::kDimensionKeySizeSoftLimit - 1) {
        size_t newTupleCount = mCurrentSlicedDuration.size() + 1;
        StatsdStats::getInstance().noteMetricDimensionSize(mConfigKey, mName, newTupleCount);
        // 2. Don't add more tuples, we are above the allowed threshold. Drop the data.
        if (newTupleCount > StatsdStats::kDimensionKeySizeHardLimit) {
            ALOGE("DurationMetric %s dropping data for dimension key %s", mName.c_str(),
                  newKey.c_str());
            return true;
        }
    }
    return false;
}

void DurationMetricProducer::onMatchedLogEventInternalLocked(
        const size_t matcherIndex, const HashableDimensionKey& eventKey,
        const map<string, HashableDimensionKey>& conditionKeys, bool condition,
        const LogEvent& event) {
    flushIfNeededLocked(event.GetTimestampNs());

    if (matcherIndex == mStopAllIndex) {
        for (auto& pair : mCurrentSlicedDuration) {
            pair.second->noteStopAll(event.GetTimestampNs());
        }
        return;
    }

    HashableDimensionKey atomKey(getDimensionKey(event, mInternalDimension));

    if (mCurrentSlicedDuration.find(eventKey) == mCurrentSlicedDuration.end()) {
        if (hitGuardRailLocked(eventKey)) {
            return;
        }
        mCurrentSlicedDuration[eventKey] = createDurationTracker(eventKey);
    }

    auto it = mCurrentSlicedDuration.find(eventKey);

    if (matcherIndex == mStartIndex) {
        it->second->noteStart(atomKey, condition, event.GetTimestampNs(), conditionKeys);
    } else if (matcherIndex == mStopIndex) {
        it->second->noteStop(atomKey, event.GetTimestampNs(), false);
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
