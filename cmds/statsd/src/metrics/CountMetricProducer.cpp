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

#include "CountMetricProducer.h"
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
using std::map;
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

CountMetricProducer::CountMetricProducer(const ConfigKey& key, const CountMetric& metric,
                                         const int conditionIndex,
                                         const sp<ConditionWizard>& wizard,
                                         const uint64_t startTimeNs)
    : MetricProducer(key, startTimeNs, conditionIndex, wizard), mMetric(metric) {
    // TODO: evaluate initial conditions. and set mConditionMet.
    if (metric.has_bucket() && metric.bucket().has_bucket_size_millis()) {
        mBucketSizeNs = metric.bucket().bucket_size_millis() * 1000 * 1000;
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

CountMetricProducer::~CountMetricProducer() {
    VLOG("~CountMetricProducer() called");
}

void CountMetricProducer::startNewProtoOutputStream(long long startTime) {
    std::lock_guard<std::shared_timed_mutex> writeLock(mRWMutex);

    mProto = std::make_unique<ProtoOutputStream>();
    mProto->write(FIELD_TYPE_STRING | FIELD_ID_NAME, mMetric.name());
    mProto->write(FIELD_TYPE_INT64 | FIELD_ID_START_REPORT_NANOS, startTime);
    mProtoToken = mProto->start(FIELD_TYPE_MESSAGE | FIELD_ID_COUNT_METRICS);
}

void CountMetricProducer::finish() {
}

void CountMetricProducer::onSlicedConditionMayChange(const uint64_t eventTime) {
    VLOG("Metric %s onSlicedConditionMayChange", mMetric.name().c_str());
}

void CountMetricProducer::serializeBuckets() {
    std::lock_guard<std::shared_timed_mutex> writeLock(mRWMutex);
    VLOG("metric %s dump report now...", mMetric.name().c_str());

    for (const auto& counter : mPastBuckets) {
        const HashableDimensionKey& hashableKey = counter.first;
        VLOG("  dimension key %s", hashableKey.c_str());
        auto it = mDimensionKeyMap.find(hashableKey);
        if (it == mDimensionKeyMap.end()) {
            ALOGE("Dimension key %s not found?!?! skip...", hashableKey.c_str());
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
                mProto->write(FIELD_TYPE_STRING | FIELD_ID_VALUE_STR, kv.value_str());
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
        for (const auto& bucket : counter.second) {
            long long bucketInfoToken =
                    mProto->start(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_BUCKET_INFO);
            mProto->write(FIELD_TYPE_INT64 | FIELD_ID_START_BUCKET_NANOS,
                          (long long)bucket.mBucketStartNs);
            mProto->write(FIELD_TYPE_INT64 | FIELD_ID_END_BUCKET_NANOS,
                          (long long)bucket.mBucketEndNs);
            mProto->write(FIELD_TYPE_INT64 | FIELD_ID_COUNT, (long long)bucket.mCount);
            mProto->end(bucketInfoToken);
            VLOG("\t bucket [%lld - %lld] count: %lld", (long long)bucket.mBucketStartNs,
                 (long long)bucket.mBucketEndNs, (long long)bucket.mCount);
        }
        mProto->end(wrapperToken);
    }
    mProto->end(mProtoToken);
    mProto->write(FIELD_TYPE_INT64 | FIELD_ID_END_REPORT_NANOS,
                  (long long)mCurrentBucketStartTimeNs);

    mPastBuckets.clear();
    // TODO: Clear mDimensionKeyMap once the report is dumped.
}

std::unique_ptr<std::vector<uint8_t>> CountMetricProducer::onDumpReport() {
    long long endTime = time(nullptr) * NS_PER_SEC;
    VLOG("metric %s dump report now...", mMetric.name().c_str());
    // Dump current bucket if it's stale.
    // If current bucket is still on-going, don't force dump current bucket.
    // In finish(), We can force dump current bucket.
    flushIfNeeded(endTime);

    // TODO(yanglu): merge these three functions to one to avoid three locks.
    serializeBuckets();

    std::unique_ptr<std::vector<uint8_t>> buffer = serializeProto();

    startNewProtoOutputStream(endTime);

    return buffer;
}

void CountMetricProducer::onConditionChanged(const bool conditionMet, const uint64_t eventTime) {
    VLOG("Metric %s onConditionChanged", mMetric.name().c_str());
    std::lock_guard<std::shared_timed_mutex> writeLock(mRWMutex);
    mCondition = conditionMet;
}

bool CountMetricProducer::hitGuardRail(const HashableDimensionKey& newKey) {
    std::shared_lock<std::shared_timed_mutex> readLock(mRWMutex);
    if (mCurrentSlicedCounter->find(newKey) != mCurrentSlicedCounter->end()) {
        return false;
    }
    // ===========GuardRail==============
    // 1. Report the tuple count if the tuple count > soft limit
    if (mCurrentSlicedCounter->size() > StatsdStats::kDimensionKeySizeSoftLimit - 1) {
        size_t newTupleCount = mCurrentSlicedCounter->size() + 1;
        StatsdStats::getInstance().noteMetricDimensionSize(mConfigKey, mMetric.name(),
                                                           newTupleCount);
        // 2. Don't add more tuples, we are above the allowed threshold. Drop the data.
        if (newTupleCount > StatsdStats::kDimensionKeySizeHardLimit) {
            ALOGE("CountMetric %s dropping data for dimension key %s", mMetric.name().c_str(),
                  newKey.c_str());
            return true;
        }
    }

    return false;
}
void CountMetricProducer::onMatchedLogEventInternal(
        const size_t matcherIndex, const HashableDimensionKey& eventKey,
        const map<string, HashableDimensionKey>& conditionKey, bool condition,
        const LogEvent& event, bool scheduledPull) {
    uint64_t eventTimeNs = event.GetTimestampNs();

    flushIfNeeded(eventTimeNs);

    // ===========GuardRail==============
    if (hitGuardRail(eventKey)) {
        return;
    }

    // TODO(yanglu): move the following logic to a seperate function to make it lockable.
    {
        std::lock_guard<std::shared_timed_mutex> writeLock(mRWMutex);
        if (condition == false) {
            return;
        }

        auto it = mCurrentSlicedCounter->find(eventKey);
        if (it == mCurrentSlicedCounter->end()) {
            // create a counter for the new key
            mCurrentSlicedCounter->insert({eventKey, 1});
        } else {
            // increment the existing value
            auto& count = it->second;
            count++;
        }
        const int64_t& count = mCurrentSlicedCounter->find(eventKey)->second;
        for (auto& tracker : mAnomalyTrackers) {
            tracker->detectAndDeclareAnomaly(eventTimeNs, mCurrentBucketNum, eventKey, count);
        }
        VLOG("metric %s %s->%lld", mMetric.name().c_str(), eventKey.c_str(), (long long)(count));
    }
}

// When a new matched event comes in, we check if event falls into the current
// bucket. If not, flush the old counter to past buckets and initialize the new bucket.
void CountMetricProducer::flushIfNeeded(const uint64_t eventTimeNs) {
    std::lock_guard<std::shared_timed_mutex> writeLock(mRWMutex);

    if (eventTimeNs < mCurrentBucketStartTimeNs + mBucketSizeNs) {
        return;
    }

    CountBucket info;
    info.mBucketStartNs = mCurrentBucketStartTimeNs;
    info.mBucketEndNs = mCurrentBucketStartTimeNs + mBucketSizeNs;
    info.mBucketNum = mCurrentBucketNum;
    for (const auto& counter : *mCurrentSlicedCounter) {
        info.mCount = counter.second;
        auto& bucketList = mPastBuckets[counter.first];
        bucketList.push_back(info);
        VLOG("metric %s, dump key value: %s -> %lld", mMetric.name().c_str(), counter.first.c_str(),
             (long long)counter.second);
    }

    for (auto& tracker : mAnomalyTrackers) {
        tracker->addPastBucket(mCurrentSlicedCounter, mCurrentBucketNum);
    }

    // Reset counters (do not clear, since the old one is still referenced in mAnomalyTrackers).
    mCurrentSlicedCounter = std::make_shared<DimToValMap>();
    uint64_t numBucketsForward = (eventTimeNs - mCurrentBucketStartTimeNs) / mBucketSizeNs;
    mCurrentBucketStartTimeNs = mCurrentBucketStartTimeNs + numBucketsForward * mBucketSizeNs;
    mCurrentBucketNum += numBucketsForward;
    VLOG("metric %s: new bucket start time: %lld", mMetric.name().c_str(),
         (long long)mCurrentBucketStartTimeNs);
}

// Rough estimate of CountMetricProducer buffer stored. This number will be
// greater than actual data size as it contains each dimension of
// CountMetricData is  duplicated.
size_t CountMetricProducer::byteSize() const {
    std::shared_lock<std::shared_timed_mutex> readLock(mRWMutex);
    size_t totalSize = 0;
    for (const auto& pair : mPastBuckets) {
        totalSize += pair.second.size() * kBucketSize;
    }
    return totalSize;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
