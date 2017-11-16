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
#include "DurationMetricProducer.h"
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

DurationMetricProducer::DurationMetricProducer(const DurationMetric& metric,
                                               const int conditionIndex, const size_t startIndex,
                                               const size_t stopIndex, const size_t stopAllIndex,
                                               const sp<ConditionWizard>& wizard,
                                               const vector<KeyMatcher>& internalDimension,
                                               const uint64_t startTimeNs)
    : MetricProducer(startTimeNs, conditionIndex, wizard),
      mMetric(metric),
      mStartIndex(startIndex),
      mStopIndex(stopIndex),
      mStopAllIndex(stopAllIndex),
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

    startNewProtoOutputStream(mStartTimeNs);

    VLOG("metric %s created. bucket size %lld start_time: %lld", metric.name().c_str(),
         (long long)mBucketSizeNs, (long long)mStartTimeNs);
}

DurationMetricProducer::~DurationMetricProducer() {
    VLOG("~DurationMetric() called");
}

void DurationMetricProducer::startNewProtoOutputStream(long long startTime) {
    mProto = std::make_unique<ProtoOutputStream>();
    mProto->write(FIELD_TYPE_STRING | FIELD_ID_NAME, mMetric.name());
    mProto->write(FIELD_TYPE_INT64 | FIELD_ID_START_REPORT_NANOS, startTime);
    mProtoToken = mProto->start(FIELD_TYPE_MESSAGE | FIELD_ID_DURATION_METRICS);
}

unique_ptr<DurationTracker> DurationMetricProducer::createDurationTracker(
        vector<DurationBucket>& bucket) {
    switch (mMetric.type()) {
        case DurationMetric_AggregationType_DURATION_SUM:
            return make_unique<OringDurationTracker>(mWizard, mConditionTrackerIndex,
                                                     mCurrentBucketStartTimeNs, mBucketSizeNs,
                                                     bucket);
        case DurationMetric_AggregationType_DURATION_MAX_SPARSE:
            return make_unique<MaxDurationTracker>(mWizard, mConditionTrackerIndex,
                                                   mCurrentBucketStartTimeNs, mBucketSizeNs,
                                                   bucket);
    }
}

void DurationMetricProducer::finish() {
    // TODO: write the StatsLogReport to dropbox using
    // DropboxWriter.
}

void DurationMetricProducer::onSlicedConditionMayChange(const uint64_t eventTime) {
    VLOG("Metric %s onSlicedConditionMayChange", mMetric.name().c_str());
    // Now for each of the on-going event, check if the condition has changed for them.
    flushIfNeeded(eventTime);
    for (auto& pair : mCurrentSlicedDuration) {
        pair.second->onSlicedConditionMayChange(eventTime);
    }
}

void DurationMetricProducer::onConditionChanged(const bool conditionMet, const uint64_t eventTime) {
    VLOG("Metric %s onConditionChanged", mMetric.name().c_str());
    mCondition = conditionMet;
    // TODO: need to populate the condition change time from the event which triggers the condition
    // change, instead of using current time.

    flushIfNeeded(eventTime);
    for (auto& pair : mCurrentSlicedDuration) {
        pair.second->onConditionChanged(conditionMet, eventTime);
    }
}

static void addDurationBucketsToReport(StatsLogReport_DurationMetricDataWrapper& wrapper,
                                       const vector<KeyValuePair>& key,
                                       const vector<DurationBucketInfo>& buckets) {
    DurationMetricData* data = wrapper.add_data();
    for (const auto& kv : key) {
        data->add_dimension()->CopyFrom(kv);
    }
    for (const auto& bucket : buckets) {
        data->add_bucket_info()->CopyFrom(bucket);
        VLOG("\t bucket [%lld - %lld] duration(ns): %lld", bucket.start_bucket_nanos(),
             bucket.end_bucket_nanos(), bucket.duration_nanos());
    }
}

std::unique_ptr<std::vector<uint8_t>> DurationMetricProducer::onDumpReport() {
    long long endTime = time(nullptr) * NS_PER_SEC;

    // Dump current bucket if it's stale.
    // If current bucket is still on-going, don't force dump current bucket.
    // In finish(), We can force dump current bucket.
    flushIfNeeded(endTime);
    VLOG("metric %s dump report now...", mMetric.name().c_str());

    for (const auto& pair : mPastBuckets) {
        const HashableDimensionKey& hashableKey = pair.first;
        VLOG("  dimension key %s", hashableKey.c_str());
        auto it = mDimensionKeyMap.find(hashableKey);
        if (it == mDimensionKeyMap.end()) {
            ALOGW("Dimension key %s not found?!?! skip...", hashableKey.c_str());
            continue;
        }
        long long wrapperToken =
                mProto->start(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_DATA);

        // First fill dimension (KeyValuePairs).
        for (const auto& kv : it->second) {
            long long dimensionToken =
                    mProto->start(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_DIMENSION);
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

        // Then fill bucket_info (DurationBucketInfo).
        for (const auto& bucket : pair.second) {
            long long bucketInfoToken =
                    mProto->start(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_BUCKET_INFO);
            mProto->write(FIELD_TYPE_INT64 | FIELD_ID_START_BUCKET_NANOS,
                          (long long)bucket.mBucketStartNs);
            mProto->write(FIELD_TYPE_INT64 | FIELD_ID_END_BUCKET_NANOS,
                          (long long)bucket.mBucketEndNs);
            mProto->write(FIELD_TYPE_INT64 | FIELD_ID_DURATION, (long long)bucket.mDuration);
            mProto->end(bucketInfoToken);
            VLOG("\t bucket [%lld - %lld] duration: %lld", (long long)bucket.mBucketStartNs,
                 (long long)bucket.mBucketEndNs, (long long)bucket.mDuration);
        }

        mProto->end(wrapperToken);
    }

    mProto->end(mProtoToken);
    mProto->write(FIELD_TYPE_INT64 | FIELD_ID_END_REPORT_NANOS,
                  (long long)mCurrentBucketStartTimeNs);

    std::unique_ptr<std::vector<uint8_t>> buffer = serializeProto();

    startNewProtoOutputStream(endTime);
    mPastBuckets.clear();

    return buffer;
}

void DurationMetricProducer::flushIfNeeded(uint64_t eventTime) {
    if (mCurrentBucketStartTimeNs + mBucketSizeNs > eventTime) {
        return;
    }

    VLOG("flushing...........");
    for (auto it = mCurrentSlicedDuration.begin(); it != mCurrentSlicedDuration.end();) {
        if (it->second->flushIfNeeded(eventTime)) {
            VLOG("erase bucket for key %s", it->first.c_str());
            it = mCurrentSlicedDuration.erase(it);
        } else {
            ++it;
        }
    }

    int numBucketsForward = (eventTime - mCurrentBucketStartTimeNs) / mBucketSizeNs;
    mCurrentBucketStartTimeNs += numBucketsForward * mBucketSizeNs;
}

void DurationMetricProducer::onMatchedLogEventInternal(
        const size_t matcherIndex, const HashableDimensionKey& eventKey,
        const map<string, HashableDimensionKey>& conditionKeys, bool condition,
        const LogEvent& event, bool scheduledPull) {
    flushIfNeeded(event.GetTimestampNs());

    if (matcherIndex == mStopAllIndex) {
        for (auto& pair : mCurrentSlicedDuration) {
            pair.second->noteStopAll(event.GetTimestampNs());
        }
        return;
    }

    HashableDimensionKey atomKey = getHashableKey(getDimensionKey(event, mInternalDimension));

    if (mCurrentSlicedDuration.find(eventKey) == mCurrentSlicedDuration.end()) {
        mCurrentSlicedDuration[eventKey] = createDurationTracker(mPastBuckets[eventKey]);
    }

    auto it = mCurrentSlicedDuration.find(eventKey);

    if (matcherIndex == mStartIndex) {
        it->second->noteStart(atomKey, condition, event.GetTimestampNs(), conditionKeys);
    } else if (matcherIndex == mStopIndex) {
        it->second->noteStop(atomKey, event.GetTimestampNs());
    }
}

size_t DurationMetricProducer::byteSize() {
  size_t totalSize = 0;
  for (const auto& pair : mPastBuckets) {
      totalSize += pair.second.size() * kBucketSize;
  }
  return totalSize;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
