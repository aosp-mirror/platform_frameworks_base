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
    if (!metric.gauge_fields_filter().include_all()) {
        translateFieldMatcher(metric.gauge_fields_filter().fields(), &mFieldMatchers);
    }

    // TODO: use UidMap if uid->pkg_name is required
    if (metric.has_dimensions_in_what()) {
        translateFieldMatcher(metric.dimensions_in_what(), &mDimensionsInWhat);
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

void GaugeMetricProducer::onDumpReportLocked(const uint64_t dumpTimeNs,
                                             ProtoOutputStream* protoOutput) {
    VLOG("gauge metric %lld report now...", (long long)mMetricId);

    flushIfNeededLocked(dumpTimeNs);
    if (mPastBuckets.empty()) {
        return;
    }

    protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_ID, (long long)mMetricId);
    long long protoToken = protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_ID_GAUGE_METRICS);

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
                    writeFieldValueTreeToStream(mTagId, *(atom.mFields), protoOutput);
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

    mPastBuckets.clear();
    // TODO: Clear mDimensionKeyMap once the report is dumped.
}

void GaugeMetricProducer::pullLocked() {
    vector<std::shared_ptr<LogEvent>> allData;
    if (!mStatsPullerManager->Pull(mPullTagId, &allData)) {
        ALOGE("Stats puller failed for tag: %d", mPullTagId);
        return;
    }
    for (const auto& data : allData) {
        onMatchedLogEventLocked(0, *data);
    }
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

std::shared_ptr<vector<FieldValue>> GaugeMetricProducer::getGaugeFields(const LogEvent& event) {
    if (mFieldMatchers.size() > 0) {
        std::shared_ptr<vector<FieldValue>> gaugeFields = std::make_shared<vector<FieldValue>>();
        filterGaugeValues(mFieldMatchers, event.getValues(), gaugeFields.get());
        return gaugeFields;
    } else {
        return std::make_shared<vector<FieldValue>>(event.getValues());
    }
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
    mTagId = event.GetTagId();
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
    GaugeAtom gaugeAtom(getGaugeFields(event), eventTimeNs);
    (*mCurrentSlicedBucket)[eventKey].push_back(gaugeAtom);
    // Anomaly detection on gauge metric only works when there is one numeric
    // field specified.
    if (mAnomalyTrackers.size() > 0) {
        if (gaugeAtom.mFields->size() == 1) {
            const Value& value = gaugeAtom.mFields->begin()->mValue;
            long gaugeVal = 0;
            if (value.getType() == INT) {
                gaugeVal = (long)value.int_value;
            } else if (value.getType() == LONG) {
                gaugeVal = value.long_value;
            }
            for (auto& tracker : mAnomalyTrackers) {
                tracker->detectAndDeclareAnomaly(eventTimeNs, mCurrentBucketNum, eventKey,
                                                 gaugeVal);
            }
        }
    }
}

void GaugeMetricProducer::updateCurrentSlicedBucketForAnomaly() {
    for (const auto& slice : *mCurrentSlicedBucket) {
        if (slice.second.empty()) {
            continue;
        }
        const Value& value = slice.second.front().mFields->front().mValue;
        long gaugeVal = 0;
        if (value.getType() == INT) {
            gaugeVal = (long)value.int_value;
        } else if (value.getType() == LONG) {
            gaugeVal = value.long_value;
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
    uint64_t currentBucketEndTimeNs = getCurrentBucketEndTimeNs();

    if (eventTimeNs < currentBucketEndTimeNs) {
        VLOG("eventTime is %lld, less than next bucket start time %lld", (long long)eventTimeNs,
             (long long)(mCurrentBucketStartTimeNs + mBucketSizeNs));
        return;
    }

    flushCurrentBucketLocked(eventTimeNs);

    // Adjusts the bucket start and end times.
    int64_t numBucketsForward = 1 + (eventTimeNs - currentBucketEndTimeNs) / mBucketSizeNs;
    mCurrentBucketStartTimeNs = currentBucketEndTimeNs + (numBucketsForward - 1) * mBucketSizeNs;
    mCurrentBucketNum += numBucketsForward;
    VLOG("metric %lld: new bucket start time: %lld", (long long)mMetricId,
         (long long)mCurrentBucketStartTimeNs);
}

void GaugeMetricProducer::flushCurrentBucketLocked(const uint64_t& eventTimeNs) {
    uint64_t fullBucketEndTimeNs = getCurrentBucketEndTimeNs();

    GaugeBucket info;
    info.mBucketStartNs = mCurrentBucketStartTimeNs;
    if (eventTimeNs < fullBucketEndTimeNs) {
        info.mBucketEndNs = eventTimeNs;
    } else {
        info.mBucketEndNs = fullBucketEndTimeNs;
    }
    info.mBucketNum = mCurrentBucketNum;

    for (const auto& slice : *mCurrentSlicedBucket) {
        info.mGaugeAtoms = slice.second;
        auto& bucketList = mPastBuckets[slice.first];
        bucketList.push_back(info);
        VLOG("gauge metric %lld, dump key value: %s", (long long)mMetricId, slice.first.c_str());
    }

    // If we have anomaly trackers, we need to update the partial bucket values.
    if (mAnomalyTrackers.size() > 0) {
        updateCurrentSlicedBucketForAnomaly();

        if (eventTimeNs > fullBucketEndTimeNs) {
            // This is known to be a full bucket, so send this data to the anomaly tracker.
            for (auto& tracker : mAnomalyTrackers) {
                tracker->addPastBucket(mCurrentSlicedBucketForAnomaly, mCurrentBucketNum);
            }
            mCurrentSlicedBucketForAnomaly = std::make_shared<DimToValMap>();
        }
    }

    mCurrentSlicedBucket = std::make_shared<DimToGaugeAtomsMap>();
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
