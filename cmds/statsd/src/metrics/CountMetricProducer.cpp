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

#define DEBUG true  // STOPSHIP if true
#include "Log.h"

#include "CountAnomalyTracker.h"
#include "CountMetricProducer.h"
#include "stats_util.h"

#include <limits.h>
#include <stdlib.h>

using namespace android::util;
using android::util::ProtoOutputStream;
using std::map;
using std::string;
using std::unordered_map;
using std::vector;

namespace android {
namespace os {
namespace statsd {

// for StatsLogReport
const int FIELD_ID_METRIC_ID = 1;
const int FIELD_ID_START_REPORT_NANOS = 2;
const int FIELD_ID_END_REPORT_NANOS = 3;
const int FIELD_ID_COUNT_METRICS = 5;
// for CountMetricDataWrapper
const int FIELD_ID_DATA = 1;
// for CountMetricData
const int FIELD_ID_DIMENSION = 1;
const int FIELD_ID_BUCKET_INFO = 2;
// for KeyValuePair
const int FIELD_ID_KEY = 1;
const int FIELD_ID_VALUE_STR = 2;
const int FIELD_ID_VALUE_INT = 3;
const int FIELD_ID_VALUE_BOOL = 4;
const int FIELD_ID_VALUE_FLOAT = 5;
// for CountBucketInfo
const int FIELD_ID_START_BUCKET_NANOS = 1;
const int FIELD_ID_END_BUCKET_NANOS = 2;
const int FIELD_ID_COUNT = 3;

// TODO: add back AnomalyTracker.
CountMetricProducer::CountMetricProducer(const CountMetric& metric, const int conditionIndex,
                                         const sp<ConditionWizard>& wizard)
    // TODO: Pass in the start time from MetricsManager, instead of calling time() here.
    : MetricProducer((time(nullptr) * NANO_SECONDS_IN_A_SECOND), conditionIndex, wizard),
      mMetric(metric) {
    // TODO: evaluate initial conditions. and set mConditionMet.
    if (metric.has_bucket() && metric.bucket().has_bucket_size_millis()) {
        mBucketSizeNs = metric.bucket().bucket_size_millis() * 1000 * 1000;
    } else {
        mBucketSizeNs = LLONG_MAX;
    }

    mAnomalyTrackers.reserve(metric.alerts_size());
    for (int i = 0; i < metric.alerts_size(); i++) {
        const Alert& alert = metric.alerts(i);
        if (alert.trigger_if_sum_gt() > 0 && alert.number_of_buckets() > 0) {
            mAnomalyTrackers.push_back(std::make_unique<CountAnomalyTracker>(alert));
        } else {
            ALOGW("Ignoring invalid count metric alert: threshold=%lld num_buckets= %d",
                  alert.trigger_if_sum_gt(), alert.number_of_buckets());
        }
    }

    // TODO: use UidMap if uid->pkg_name is required
    mDimension.insert(mDimension.begin(), metric.dimension().begin(), metric.dimension().end());

    if (metric.links().size() > 0) {
        mConditionLinks.insert(mConditionLinks.begin(), metric.links().begin(),
                               metric.links().end());
        mConditionSliced = true;
    }

    startNewProtoOutputStream(mStartTimeNs);

    VLOG("metric %lld created. bucket size %lld start_time: %lld", metric.metric_id(),
         (long long)mBucketSizeNs, (long long)mStartTimeNs);
}

CountMetricProducer::~CountMetricProducer() {
    VLOG("~CountMetricProducer() called");
}

void CountMetricProducer::startNewProtoOutputStream(long long startTime) {
    mProto = std::make_unique<ProtoOutputStream>();
    mProto->write(FIELD_TYPE_INT32 | FIELD_ID_METRIC_ID, mMetric.metric_id());
    mProto->write(FIELD_TYPE_INT64 | FIELD_ID_START_REPORT_NANOS, startTime);
    mProtoToken = mProto->start(FIELD_TYPE_MESSAGE | FIELD_ID_COUNT_METRICS);
}

void CountMetricProducer::finish() {
}

void CountMetricProducer::onSlicedConditionMayChange(const uint64_t eventTime) {
    VLOG("Metric %lld onSlicedConditionMayChange", mMetric.metric_id());
}

StatsLogReport CountMetricProducer::onDumpReport() {
    long long endTime = time(nullptr) * NANO_SECONDS_IN_A_SECOND;

    // Dump current bucket if it's stale.
    // If current bucket is still on-going, don't force dump current bucket.
    // In finish(), We can force dump current bucket.
    flushCounterIfNeeded(endTime);

    for (const auto& counter : mPastBucketProtos) {
        const HashableDimensionKey& hashableKey = counter.first;
        auto it = mDimensionKeyMap.find(hashableKey);
        if (it == mDimensionKeyMap.end()) {
            ALOGE("Dimension key %s not found?!?! skip...", hashableKey.c_str());
            continue;
        }
        long long wrapperToken = mProto->start(FIELD_TYPE_MESSAGE | FIELD_ID_DATA);

        // First fill dimension (KeyValuePairs).
        for (const auto& kv : it->second) {
            long long dimensionToken = mProto->start(FIELD_TYPE_MESSAGE | FIELD_ID_DIMENSION);
            mProto->write(FIELD_TYPE_INT32 | FIELD_ID_KEY, kv.key());
            if (kv.has_value_str()) {
                mProto->write(FIELD_TYPE_INT32 | FIELD_ID_VALUE_STR, kv.value_str());
            } else if (kv.has_value_int()) {
                mProto->write(FIELD_TYPE_INT64 | FIELD_ID_VALUE_INT, kv.value_int());
            } else if (kv.has_value_bool()) {
                mProto->write(FIELD_TYPE_BOOL | FIELD_ID_VALUE_BOOL, kv.value_bool());
            } else if (kv.has_value_float()) {
                mProto->write(FIELD_TYPE_FLOAT | FIELD_ID_VALUE_FLOAT, kv.value_float());
            }
            mProto->end(dimensionToken);
        }

        // Then fill bucket_info (CountBucketInfo).
        for (const auto& proto : counter.second) {
            size_t bufferSize = proto->size();
            char* buffer(new char[bufferSize]);
            size_t pos = 0;
            auto it = proto->data();
            while (it.readBuffer() != NULL) {
                size_t toRead = it.currentToRead();
                std::memcpy(&buffer[pos], it.readBuffer(), toRead);
                pos += toRead;
                it.rp()->move(toRead);
            }
            mProto->write(FIELD_TYPE_MESSAGE | FIELD_ID_DIMENSION, buffer, bufferSize);
        }

        mProto->end(wrapperToken);
    }

    mProto->end(mProtoToken);
    mProto->write(FIELD_TYPE_INT64 | FIELD_ID_END_REPORT_NANOS,
                  (long long)mCurrentBucketStartTimeNs);

    size_t bufferSize = mProto->size();
    VLOG("metric %lld dump report now...", mMetric.metric_id());
    std::unique_ptr<uint8_t[]> buffer(new uint8_t[bufferSize]);
    size_t pos = 0;
    auto it = mProto->data();
    while (it.readBuffer() != NULL) {
        size_t toRead = it.currentToRead();
        std::memcpy(&buffer[pos], it.readBuffer(), toRead);
        pos += toRead;
        it.rp()->move(toRead);
    }

    startNewProtoOutputStream(endTime);
    mPastBucketProtos.clear();
    mByteSize = 0;

    // TODO: Once we migrate all MetricProducers to use ProtoOutputStream, we should return this:
    // return std::move(buffer);
    return StatsLogReport();

    // TODO: Clear mDimensionKeyMap once the report is dumped.
}

void CountMetricProducer::onConditionChanged(const bool conditionMet, const uint64_t eventTime) {
    VLOG("Metric %lld onConditionChanged", mMetric.metric_id());
    mCondition = conditionMet;
}

void CountMetricProducer::onMatchedLogEventInternal(
        const size_t matcherIndex, const HashableDimensionKey& eventKey,
        const map<string, HashableDimensionKey>& conditionKey, bool condition,
        const LogEvent& event, bool scheduledPull) {
    uint64_t eventTimeNs = event.GetTimestampNs();

    flushCounterIfNeeded(eventTimeNs);

    if (condition == false) {
        return;
    }

    auto it = mCurrentSlicedCounter.find(eventKey);

    if (it == mCurrentSlicedCounter.end()) {
        // create a counter for the new key
        mCurrentSlicedCounter[eventKey] = 1;

    } else {
        // increment the existing value
        auto& count = it->second;
        count++;
    }

    // TODO: Re-add anomaly detection (similar to):
    // for (auto& tracker : mAnomalyTrackers) {
    //     tracker->checkAnomaly(mCounter);
    // }

    VLOG("metric %lld %s->%d", mMetric.metric_id(), eventKey.c_str(),
         mCurrentSlicedCounter[eventKey]);
}

// When a new matched event comes in, we check if event falls into the current
// bucket. If not, flush the old counter to past buckets and initialize the new bucket.
void CountMetricProducer::flushCounterIfNeeded(const uint64_t eventTimeNs) {
    if (mCurrentBucketStartTimeNs + mBucketSizeNs > eventTimeNs) {
        return;
    }

    // adjust the bucket start time
    int64_t numBucketsForward = (eventTimeNs - mCurrentBucketStartTimeNs) / mBucketSizeNs;

    for (const auto& counter : mCurrentSlicedCounter) {
        unique_ptr<ProtoOutputStream> proto = make_unique<ProtoOutputStream>();
        proto->write(FIELD_TYPE_INT64 | FIELD_ID_START_BUCKET_NANOS,
                     (long long)mCurrentBucketStartTimeNs);
        proto->write(FIELD_TYPE_INT64 | FIELD_ID_END_BUCKET_NANOS,
                      (long long)mCurrentBucketStartTimeNs + mBucketSizeNs);
        proto->write(FIELD_TYPE_INT64 | FIELD_ID_COUNT, (long long)counter.second);

        auto& bucketList = mPastBucketProtos[counter.first];
        bucketList.push_back(std::move(proto));
        mByteSize += proto->size();

        VLOG("metric %lld, dump key value: %s -> %d", mMetric.metric_id(), counter.first.c_str(),
             counter.second);
    }

    // TODO: Re-add anomaly detection (similar to):
    // for (auto& tracker : mAnomalyTrackers) {
    //     tracker->addPastBucket(mCounter, numBucketsForward);
    //}

    // Reset counters
    mCurrentSlicedCounter.clear();

    mCurrentBucketStartTimeNs = mCurrentBucketStartTimeNs + numBucketsForward * mBucketSizeNs;
    VLOG("metric %lld: new bucket start time: %lld", mMetric.metric_id(),
         (long long)mCurrentBucketStartTimeNs);
}

// Rough estimate of CountMetricProducer buffer stored. This number will be
// greater than actual data size as it contains each dimension of
// CountMetricData is  duplicated.
size_t CountMetricProducer::byteSize() {
    return mByteSize;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
