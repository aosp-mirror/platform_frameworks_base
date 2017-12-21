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

#define DEBUG false  // STOPSHIP if true
#include "Log.h"

#include "GaugeMetricProducer.h"
#include "guardrail/StatsdStats.h"

#include <cutils/log.h>

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
using std::make_shared;
using std::shared_ptr;

namespace android {
namespace os {
namespace statsd {

// for StatsLogReport
const int FIELD_ID_NAME = 1;
const int FIELD_ID_START_REPORT_NANOS = 2;
const int FIELD_ID_END_REPORT_NANOS = 3;
const int FIELD_ID_GAUGE_METRICS = 8;
// for GaugeMetricDataWrapper
const int FIELD_ID_DATA = 1;
// for GaugeMetricData
const int FIELD_ID_DIMENSION = 1;
const int FIELD_ID_BUCKET_INFO = 2;
// for KeyValuePair
const int FIELD_ID_KEY = 1;
const int FIELD_ID_VALUE_STR = 2;
const int FIELD_ID_VALUE_INT = 3;
const int FIELD_ID_VALUE_BOOL = 4;
const int FIELD_ID_VALUE_FLOAT = 5;
// for GaugeBucketInfo
const int FIELD_ID_START_BUCKET_NANOS = 1;
const int FIELD_ID_END_BUCKET_NANOS = 2;
const int FIELD_ID_ATOM = 3;

GaugeMetricProducer::GaugeMetricProducer(const ConfigKey& key, const GaugeMetric& metric,
                                         const int conditionIndex,
                                         const sp<ConditionWizard>& wizard, const int atomTagId,
                                         const int pullTagId, const uint64_t startTimeNs,
                                         shared_ptr<StatsPullerManager> statsPullerManager)
    : MetricProducer(metric.name(), key, startTimeNs, conditionIndex, wizard),
      mStatsPullerManager(statsPullerManager),
      mPullTagId(pullTagId),
      mAtomTagId(atomTagId) {
    if (metric.has_bucket() && metric.bucket().has_bucket_size_millis()) {
        mBucketSizeNs = metric.bucket().bucket_size_millis() * 1000 * 1000;
    } else {
        mBucketSizeNs = kDefaultGaugemBucketSizeNs;
    }

    for (int i = 0; i < metric.gauge_fields().field_num_size(); i++) {
        mGaugeFields.push_back(metric.gauge_fields().field_num(i));
    }

    // TODO: use UidMap if uid->pkg_name is required
    mDimension.insert(mDimension.begin(), metric.dimension().begin(), metric.dimension().end());

    if (metric.links().size() > 0) {
        mConditionLinks.insert(mConditionLinks.begin(), metric.links().begin(),
                               metric.links().end());
        mConditionSliced = true;
    }

    // Kicks off the puller immediately.
    if (mPullTagId != -1) {
        mStatsPullerManager->RegisterReceiver(mPullTagId, this,
                                             metric.bucket().bucket_size_millis());
    }

    VLOG("metric %s created. bucket size %lld start_time: %lld", metric.name().c_str(),
         (long long)mBucketSizeNs, (long long)mStartTimeNs);
}

// for testing
GaugeMetricProducer::GaugeMetricProducer(const ConfigKey& key, const GaugeMetric& metric,
                                         const int conditionIndex,
                                         const sp<ConditionWizard>& wizard, const int pullTagId,
                                         const int atomTagId, const int64_t startTimeNs)
    : GaugeMetricProducer(key, metric, conditionIndex, wizard, pullTagId, atomTagId, startTimeNs,
                          make_shared<StatsPullerManager>()) {
}

GaugeMetricProducer::~GaugeMetricProducer() {
    VLOG("~GaugeMetricProducer() called");
    if (mPullTagId != -1) {
        mStatsPullerManager->UnRegisterReceiver(mPullTagId, this);
    }
}

void GaugeMetricProducer::onDumpReportLocked(const uint64_t dumpTimeNs,
                                             ProtoOutputStream* protoOutput) {
    VLOG("gauge metric %s dump report now...", mName.c_str());

    flushIfNeededLocked(dumpTimeNs);

    protoOutput->write(FIELD_TYPE_STRING | FIELD_ID_NAME, mName);
    protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_START_REPORT_NANOS, (long long)mStartTimeNs);
    long long protoToken = protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_ID_GAUGE_METRICS);

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

        // Then fill bucket_info (GaugeBucketInfo).
        for (const auto& bucket : pair.second) {
            long long bucketInfoToken = protoOutput->start(
                    FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_BUCKET_INFO);
            protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_START_BUCKET_NANOS,
                               (long long)bucket.mBucketStartNs);
            protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_END_BUCKET_NANOS,
                               (long long)bucket.mBucketEndNs);
            long long atomToken = protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_ID_ATOM);
            long long eventToken = protoOutput->start(FIELD_TYPE_MESSAGE | mAtomTagId);
            for (const auto& pair : bucket.mEvent->kv) {
                if (pair.has_value_int()) {
                    protoOutput->write(FIELD_TYPE_INT32 | pair.key(), pair.value_int());
                } else if (pair.has_value_long()) {
                    protoOutput->write(FIELD_TYPE_INT64 | pair.key(), pair.value_long());
                } else if (pair.has_value_str()) {
                    protoOutput->write(FIELD_TYPE_STRING | pair.key(), pair.value_str());
                } else if (pair.has_value_long()) {
                    protoOutput->write(FIELD_TYPE_FLOAT | pair.key(), pair.value_float());
                } else if (pair.has_value_bool()) {
                    protoOutput->write(FIELD_TYPE_BOOL | pair.key(), pair.value_bool());
                }
            }
            protoOutput->end(eventToken);
            protoOutput->end(atomToken);
            protoOutput->end(bucketInfoToken);
            VLOG("\t bucket [%lld - %lld] content: %s", (long long)bucket.mBucketStartNs,
                 (long long)bucket.mBucketEndNs, bucket.mEvent->ToString().c_str());
        }
        protoOutput->end(wrapperToken);
    }
    protoOutput->end(protoToken);
    protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_END_REPORT_NANOS, (long long)dumpTimeNs);

    mPastBuckets.clear();
    mStartTimeNs = mCurrentBucketStartTimeNs;
    // TODO: Clear mDimensionKeyMap once the report is dumped.
}

void GaugeMetricProducer::onConditionChangedLocked(const bool conditionMet,
                                                   const uint64_t eventTime) {
    VLOG("Metric %s onConditionChanged", mName.c_str());
    flushIfNeededLocked(eventTime);
    mCondition = conditionMet;

    // Push mode. No need to proactively pull the gauge data.
    if (mPullTagId == -1) {
        return;
    }
    // No need to pull again. Either scheduled pull or condition on true happened
    if (!mCondition) {
        return;
    }
    // Already have gauge metric for the current bucket, do not do it again.
    if (mCurrentSlicedBucket->size() > 0) {
        return;
    }
    vector<std::shared_ptr<LogEvent>> allData;
    if (!mStatsPullerManager->Pull(mPullTagId, &allData)) {
        ALOGE("Stats puller failed for tag: %d", mPullTagId);
        return;
    }
    for (const auto& data : allData) {
        onMatchedLogEventLocked(0, *data);
    }
    flushIfNeededLocked(eventTime);
}

void GaugeMetricProducer::onSlicedConditionMayChangeLocked(const uint64_t eventTime) {
    VLOG("Metric %s onSlicedConditionMayChange", mName.c_str());
}

shared_ptr<EventKV> GaugeMetricProducer::getGauge(const LogEvent& event) {
    shared_ptr<EventKV> ret = make_shared<EventKV>();
    if (mGaugeFields.size() == 0) {
        for (int i = 1; i <= event.size(); i++) {
            ret->kv.push_back(event.GetKeyValueProto(i));
        }
    } else {
        for (int i = 0; i < (int)mGaugeFields.size(); i++) {
            ret->kv.push_back(event.GetKeyValueProto(mGaugeFields[i]));
        }
    }
    return ret;
}

void GaugeMetricProducer::onDataPulled(const std::vector<std::shared_ptr<LogEvent>>& allData) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (allData.size() == 0) {
        return;
    }
    for (const auto& data : allData) {
        onMatchedLogEventLocked(0, *data);
    }
}

bool GaugeMetricProducer::hitGuardRailLocked(const HashableDimensionKey& newKey) {
    if (mCurrentSlicedBucket->find(newKey) != mCurrentSlicedBucket->end()) {
        return false;
    }
    // 1. Report the tuple count if the tuple count > soft limit
    if (mCurrentSlicedBucket->size() > StatsdStats::kDimensionKeySizeSoftLimit - 1) {
        size_t newTupleCount = mCurrentSlicedBucket->size() + 1;
        StatsdStats::getInstance().noteMetricDimensionSize(mConfigKey, mName, newTupleCount);
        // 2. Don't add more tuples, we are above the allowed threshold. Drop the data.
        if (newTupleCount > StatsdStats::kDimensionKeySizeHardLimit) {
            ALOGE("GaugeMetric %s dropping data for dimension key %s", mName.c_str(),
                  newKey.c_str());
            return true;
        }
    }

    return false;
}

void GaugeMetricProducer::onMatchedLogEventInternalLocked(
        const size_t matcherIndex, const HashableDimensionKey& eventKey,
        const map<string, HashableDimensionKey>& conditionKey, bool condition,
        const LogEvent& event) {
    if (condition == false) {
        return;
    }
    uint64_t eventTimeNs = event.GetTimestampNs();
    if (eventTimeNs < mCurrentBucketStartTimeNs) {
        VLOG("Skip event due to late arrival: %lld vs %lld", (long long)eventTimeNs,
             (long long)mCurrentBucketStartTimeNs);
        return;
    }
    flushIfNeededLocked(eventTimeNs);

    // For gauge metric, we just simply use the first gauge in the given bucket.
    if (mCurrentSlicedBucket->find(eventKey) != mCurrentSlicedBucket->end()) {
        return;
    }
    shared_ptr<EventKV> gauge = getGauge(event);
    if (hitGuardRailLocked(eventKey)) {
        return;
    }
    (*mCurrentSlicedBucket)[eventKey] = gauge;
    // Anomaly detection on gauge metric only works when there is one numeric
    // field specified.
    if (mAnomalyTrackers.size() > 0) {
        if (gauge->kv.size() == 1) {
            KeyValuePair pair = gauge->kv[0];
            long gaugeVal = 0;
            if (pair.has_value_int()) {
                gaugeVal = (long)pair.value_int();
            } else if (pair.has_value_long()) {
                gaugeVal = pair.value_long();
            }
            for (auto& tracker : mAnomalyTrackers) {
                tracker->detectAndDeclareAnomaly(eventTimeNs, mCurrentBucketNum, eventKey,
                                                 gaugeVal);
            }
        }
    }
}

void GaugeMetricProducer::updateCurrentSlicedBucketForAnomaly() {
    mCurrentSlicedBucketForAnomaly->clear();
    status_t err = NO_ERROR;
    for (const auto& slice : *mCurrentSlicedBucket) {
        KeyValuePair pair = slice.second->kv[0];
        long gaugeVal = 0;
        if (pair.has_value_int()) {
            gaugeVal = (long)pair.value_int();
        } else if (pair.has_value_long()) {
            gaugeVal = pair.value_long();
        }
        (*mCurrentSlicedBucketForAnomaly)[slice.first] = gaugeVal;
    }
}

// When a new matched event comes in, we check if event falls into the current
// bucket. If not, flush the old counter to past buckets and initialize the new
// bucket.
// if data is pushed, onMatchedLogEvent will only be called through onConditionChanged() inside
// the GaugeMetricProducer while holding the lock.
void GaugeMetricProducer::flushIfNeededLocked(const uint64_t& eventTimeNs) {
    if (eventTimeNs < mCurrentBucketStartTimeNs + mBucketSizeNs) {
        VLOG("eventTime is %lld, less than next bucket start time %lld", (long long)eventTimeNs,
             (long long)(mCurrentBucketStartTimeNs + mBucketSizeNs));
        return;
    }

    GaugeBucket info;
    info.mBucketStartNs = mCurrentBucketStartTimeNs;
    info.mBucketEndNs = mCurrentBucketStartTimeNs + mBucketSizeNs;
    info.mBucketNum = mCurrentBucketNum;

    for (const auto& slice : *mCurrentSlicedBucket) {
        info.mEvent = slice.second;
        auto& bucketList = mPastBuckets[slice.first];
        bucketList.push_back(info);
        VLOG("gauge metric %s, dump key value: %s -> %s", mName.c_str(),
             slice.first.c_str(), slice.second->ToString().c_str());
    }

    // Reset counters
    if (mAnomalyTrackers.size() > 0) {
        updateCurrentSlicedBucketForAnomaly();
        for (auto& tracker : mAnomalyTrackers) {
            tracker->addPastBucket(mCurrentSlicedBucketForAnomaly, mCurrentBucketNum);
        }
    }

    mCurrentSlicedBucket = std::make_shared<DimToEventKVMap>();

    // Adjusts the bucket start time
    int64_t numBucketsForward = (eventTimeNs - mCurrentBucketStartTimeNs) / mBucketSizeNs;
    mCurrentBucketStartTimeNs = mCurrentBucketStartTimeNs + numBucketsForward * mBucketSizeNs;
    mCurrentBucketNum += numBucketsForward;
    VLOG("metric %s: new bucket start time: %lld", mName.c_str(),
         (long long)mCurrentBucketStartTimeNs);
}

size_t GaugeMetricProducer::byteSizeLocked() const {
    size_t totalSize = 0;
    for (const auto& pair : mPastBuckets) {
        totalSize += pair.second.size() * kBucketSize;
    }
    return totalSize;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
