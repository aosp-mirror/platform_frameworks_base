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
#include "dimension.h"
#include "stats_log_util.h"

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
const int FIELD_ID_ID = 1;
const int FIELD_ID_START_REPORT_NANOS = 2;
const int FIELD_ID_END_REPORT_NANOS = 3;
const int FIELD_ID_GAUGE_METRICS = 8;
// for GaugeMetricDataWrapper
const int FIELD_ID_DATA = 1;
// for GaugeMetricData
const int FIELD_ID_DIMENSION_IN_WHAT = 1;
const int FIELD_ID_DIMENSION_IN_CONDITION = 2;
const int FIELD_ID_BUCKET_INFO = 3;
// for GaugeBucketInfo
const int FIELD_ID_START_BUCKET_NANOS = 1;
const int FIELD_ID_END_BUCKET_NANOS = 2;
const int FIELD_ID_ATOM = 3;
const int FIELD_ID_TIMESTAMP = 4;

GaugeMetricProducer::GaugeMetricProducer(const ConfigKey& key, const GaugeMetric& metric,
                                         const int conditionIndex,
                                         const sp<ConditionWizard>& wizard,
                                         const int pullTagId, const uint64_t startTimeNs,
                                         shared_ptr<StatsPullerManager> statsPullerManager)
    : MetricProducer(metric.id(), key, startTimeNs, conditionIndex, wizard),
      mStatsPullerManager(statsPullerManager),
      mPullTagId(pullTagId) {
    mCurrentSlicedBucket = std::make_shared<DimToGaugeAtomsMap>();
    mCurrentSlicedBucketForAnomaly = std::make_shared<DimToValMap>();
    int64_t bucketSizeMills = 0;
    if (metric.has_bucket()) {
        bucketSizeMills = TimeUnitToBucketSizeInMillis(metric.bucket());
    } else {
        bucketSizeMills = TimeUnitToBucketSizeInMillis(ONE_HOUR);
    }
    mBucketSizeNs = bucketSizeMills * 1000000;

    mSamplingType = metric.sampling_type();
    mFieldFilter = metric.gauge_fields_filter();

    // TODO: use UidMap if uid->pkg_name is required
    mDimensionsInWhat = metric.dimensions_in_what();
    mDimensionsInCondition = metric.dimensions_in_condition();

    if (metric.links().size() > 0) {
        mConditionLinks.insert(mConditionLinks.begin(), metric.links().begin(),
                               metric.links().end());
    }
    mConditionSliced = (metric.links().size() > 0)||
        (mDimensionsInCondition.has_field() && mDimensionsInCondition.child_size() > 0);

    // Kicks off the puller immediately.
    if (mPullTagId != -1 && mSamplingType == GaugeMetric::RANDOM_ONE_SAMPLE) {
        mStatsPullerManager->RegisterReceiver(mPullTagId, this, bucketSizeMills);
    }

    VLOG("metric %lld created. bucket size %lld start_time: %lld", (long long)metric.id(),
         (long long)mBucketSizeNs, (long long)mStartTimeNs);
}

// for testing
GaugeMetricProducer::GaugeMetricProducer(const ConfigKey& key, const GaugeMetric& metric,
                                         const int conditionIndex,
                                         const sp<ConditionWizard>& wizard, const int pullTagId,
                                         const int64_t startTimeNs)
    : GaugeMetricProducer(key, metric, conditionIndex, wizard, pullTagId, startTimeNs,
                          make_shared<StatsPullerManager>()) {
}

GaugeMetricProducer::~GaugeMetricProducer() {
    VLOG("~GaugeMetricProducer() called");
    if (mPullTagId != -1) {
        mStatsPullerManager->UnRegisterReceiver(mPullTagId, this);
    }
}

void GaugeMetricProducer::onDumpReportLocked(const uint64_t dumpTimeNs, StatsLogReport* report) {
    flushIfNeededLocked(dumpTimeNs);
    ProtoOutputStream pbOutput;
    onDumpReportLocked(dumpTimeNs, &pbOutput);
    parseProtoOutputStream(pbOutput, report);
}

void GaugeMetricProducer::onDumpReportLocked(const uint64_t dumpTimeNs,
                                             ProtoOutputStream* protoOutput) {
    VLOG("gauge metric %lld report now...", (long long)mMetricId);

    flushIfNeededLocked(dumpTimeNs);
    if (mPastBuckets.empty()) {
        return;
    }

    protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_ID, (long long)mMetricId);
    protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_START_REPORT_NANOS, (long long)mStartTimeNs);
    long long protoToken = protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_ID_GAUGE_METRICS);

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

        // Then fill bucket_info (GaugeBucketInfo).
        for (const auto& bucket : pair.second) {
            long long bucketInfoToken = protoOutput->start(
                    FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_BUCKET_INFO);
            protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_START_BUCKET_NANOS,
                               (long long)bucket.mBucketStartNs);
            protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_END_BUCKET_NANOS,
                               (long long)bucket.mBucketEndNs);

            if (!bucket.mGaugeAtoms.empty()) {
                long long atomsToken =
                    protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_ATOM);
                for (const auto& atom : bucket.mGaugeAtoms) {
                    writeFieldValueTreeToStream(*atom.mFields, protoOutput);
                }
                protoOutput->end(atomsToken);

                for (const auto& atom : bucket.mGaugeAtoms) {
                    protoOutput->write(FIELD_TYPE_INT64 | FIELD_COUNT_REPEATED | FIELD_ID_TIMESTAMP,
                                       (long long)atom.mTimestamps);
                }
            }
            protoOutput->end(bucketInfoToken);
            VLOG("\t bucket [%lld - %lld] includes %d atoms.", (long long)bucket.mBucketStartNs,
                 (long long)bucket.mBucketEndNs, (int)bucket.mGaugeAtoms.size());
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
    VLOG("Metric %lld onConditionChanged", (long long)mMetricId);
    flushIfNeededLocked(eventTime);
    mCondition = conditionMet;

    // Push mode. No need to proactively pull the gauge data.
    if (mPullTagId == -1) {
        return;
    }

    bool triggerPuller = false;
    switch(mSamplingType) {
        // When the metric wants to do random sampling and there is already one gauge atom for the
        // current bucket, do not do it again.
        case GaugeMetric::RANDOM_ONE_SAMPLE: {
            triggerPuller = mCondition && mCurrentSlicedBucket->empty();
            break;
        }
        case GaugeMetric::ALL_CONDITION_CHANGES: {
            triggerPuller = true;
            break;
        }
        default:
            break;
    }
    if (!triggerPuller) {
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
    VLOG("Metric %lld onSlicedConditionMayChange", (long long)mMetricId);
}

std::shared_ptr<FieldValueMap> GaugeMetricProducer::getGaugeFields(const LogEvent& event) {
    std::shared_ptr<FieldValueMap> gaugeFields =
        std::make_shared<FieldValueMap>(event.getFieldValueMap());
    if (!mFieldFilter.include_all()) {
        filterFields(mFieldFilter.fields(), gaugeFields.get());
    }
    return gaugeFields;
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

bool GaugeMetricProducer::hitGuardRailLocked(const MetricDimensionKey& newKey) {
    if (mCurrentSlicedBucket->find(newKey) != mCurrentSlicedBucket->end()) {
        return false;
    }
    // 1. Report the tuple count if the tuple count > soft limit
    if (mCurrentSlicedBucket->size() > StatsdStats::kDimensionKeySizeSoftLimit - 1) {
        size_t newTupleCount = mCurrentSlicedBucket->size() + 1;
        StatsdStats::getInstance().noteMetricDimensionSize(mConfigKey, mMetricId, newTupleCount);
        // 2. Don't add more tuples, we are above the allowed threshold. Drop the data.
        if (newTupleCount > StatsdStats::kDimensionKeySizeHardLimit) {
            ALOGE("GaugeMetric %lld dropping data for dimension key %s",
                (long long)mMetricId, newKey.c_str());
            return true;
        }
    }

    return false;
}

void GaugeMetricProducer::onMatchedLogEventInternalLocked(
        const size_t matcherIndex, const MetricDimensionKey& eventKey,
        const ConditionKey& conditionKey, bool condition,
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

    // When gauge metric wants to randomly sample the output atom, we just simply use the first
    // gauge in the given bucket.
    if (mCurrentSlicedBucket->find(eventKey) != mCurrentSlicedBucket->end() &&
        mSamplingType == GaugeMetric::RANDOM_ONE_SAMPLE) {
        return;
    }
    if (hitGuardRailLocked(eventKey)) {
        return;
    }
    GaugeAtom gaugeAtom;
    gaugeAtom.mFields = getGaugeFields(event);
    gaugeAtom.mTimestamps = eventTimeNs;
    (*mCurrentSlicedBucket)[eventKey].push_back(gaugeAtom);
    // Anomaly detection on gauge metric only works when there is one numeric
    // field specified.
    if (mAnomalyTrackers.size() > 0) {
        if (gaugeAtom.mFields->size() == 1) {
            const DimensionsValue& dimensionsValue = gaugeAtom.mFields->begin()->second;
            long gaugeVal = 0;
            if (dimensionsValue.has_value_int()) {
                gaugeVal = (long)dimensionsValue.value_int();
            } else if (dimensionsValue.has_value_long()) {
                gaugeVal = dimensionsValue.value_long();
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
        if (slice.second.empty() || slice.second.front().mFields->empty()) {
            continue;
        }
        const DimensionsValue& dimensionsValue = slice.second.front().mFields->begin()->second;
        long gaugeVal = 0;
        if (dimensionsValue.has_value_int()) {
            gaugeVal = (long)dimensionsValue.value_int();
        } else if (dimensionsValue.has_value_long()) {
            gaugeVal = dimensionsValue.value_long();
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
        info.mGaugeAtoms = slice.second;
        auto& bucketList = mPastBuckets[slice.first];
        bucketList.push_back(info);
        VLOG("gauge metric %lld, dump key value: %s",
            (long long)mMetricId, slice.first.c_str());
    }

    // Reset counters
    if (mAnomalyTrackers.size() > 0) {
        updateCurrentSlicedBucketForAnomaly();
        for (auto& tracker : mAnomalyTrackers) {
            tracker->addPastBucket(mCurrentSlicedBucketForAnomaly, mCurrentBucketNum);
        }
    }

    mCurrentSlicedBucketForAnomaly = std::make_shared<DimToValMap>();
    mCurrentSlicedBucket = std::make_shared<DimToGaugeAtomsMap>();

    // Adjusts the bucket start time
    int64_t numBucketsForward = (eventTimeNs - mCurrentBucketStartTimeNs) / mBucketSizeNs;
    mCurrentBucketStartTimeNs = mCurrentBucketStartTimeNs + numBucketsForward * mBucketSizeNs;
    mCurrentBucketNum += numBucketsForward;
    VLOG("metric %lld: new bucket start time: %lld", (long long)mMetricId,
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
