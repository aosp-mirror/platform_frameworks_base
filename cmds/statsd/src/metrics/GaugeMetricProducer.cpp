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

#include "../guardrail/StatsdStats.h"
#include "GaugeMetricProducer.h"
#include "../stats_log_util.h"

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
const int FIELD_ID_START_BUCKET_ELAPSED_NANOS = 1;
const int FIELD_ID_END_BUCKET_ELAPSED_NANOS = 2;
const int FIELD_ID_ATOM = 3;
const int FIELD_ID_ELAPSED_ATOM_TIMESTAMP = 4;
const int FIELD_ID_WALL_CLOCK_ATOM_TIMESTAMP = 5;

GaugeMetricProducer::GaugeMetricProducer(const ConfigKey& key, const GaugeMetric& metric,
                                         const int conditionIndex,
                                         const sp<ConditionWizard>& wizard, const int pullTagId,
                                         const int64_t timeBaseNs, const int64_t startTimeNs,
                                         shared_ptr<StatsPullerManager> statsPullerManager)
    : MetricProducer(metric.id(), key, timeBaseNs, conditionIndex, wizard),
      mStatsPullerManager(statsPullerManager),
      mPullTagId(pullTagId),
      mDimensionSoftLimit(StatsdStats::kAtomDimensionKeySizeLimitMap.find(pullTagId) !=
                                          StatsdStats::kAtomDimensionKeySizeLimitMap.end()
                                  ? StatsdStats::kAtomDimensionKeySizeLimitMap.at(pullTagId).first
                                  : StatsdStats::kDimensionKeySizeSoftLimit),
      mDimensionHardLimit(StatsdStats::kAtomDimensionKeySizeLimitMap.find(pullTagId) !=
                                          StatsdStats::kAtomDimensionKeySizeLimitMap.end()
                                  ? StatsdStats::kAtomDimensionKeySizeLimitMap.at(pullTagId).second
                                  : StatsdStats::kDimensionKeySizeHardLimit) {
    mCurrentSlicedBucket = std::make_shared<DimToGaugeAtomsMap>();
    mCurrentSlicedBucketForAnomaly = std::make_shared<DimToValMap>();
    int64_t bucketSizeMills = 0;
    if (metric.has_bucket()) {
        bucketSizeMills = TimeUnitToBucketSizeInMillisGuardrailed(key.GetUid(), metric.bucket());
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
        mContainANYPositionInDimensionsInWhat = HasPositionANY(metric.dimensions_in_what());
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

    flushIfNeededLocked(startTimeNs);
    // Kicks off the puller immediately.
    if (mPullTagId != -1 && mSamplingType == GaugeMetric::RANDOM_ONE_SAMPLE) {
        mStatsPullerManager->RegisterReceiver(
                mPullTagId, this, getCurrentBucketEndTimeNs(), mBucketSizeNs);
    }

    VLOG("Gauge metric %lld created. bucket size %lld start_time: %lld sliced %d",
         (long long)metric.id(), (long long)mBucketSizeNs, (long long)mTimeBaseNs,
         mConditionSliced);
}

// for testing
GaugeMetricProducer::GaugeMetricProducer(const ConfigKey& key, const GaugeMetric& metric,
                                         const int conditionIndex,
                                         const sp<ConditionWizard>& wizard, const int pullTagId,
                                         const int64_t timeBaseNs, const int64_t startTimeNs)
    : GaugeMetricProducer(key, metric, conditionIndex, wizard, pullTagId, timeBaseNs, startTimeNs,
                          make_shared<StatsPullerManager>()) {
}

GaugeMetricProducer::~GaugeMetricProducer() {
    VLOG("~GaugeMetricProducer() called");
    if (mPullTagId != -1 && mSamplingType == GaugeMetric::RANDOM_ONE_SAMPLE) {
        mStatsPullerManager->UnRegisterReceiver(mPullTagId, this);
    }
}

void GaugeMetricProducer::dumpStatesLocked(FILE* out, bool verbose) const {
    if (mCurrentSlicedBucket == nullptr ||
        mCurrentSlicedBucket->size() == 0) {
        return;
    }

    fprintf(out, "GaugeMetric %lld dimension size %lu\n", (long long)mMetricId,
            (unsigned long)mCurrentSlicedBucket->size());
    if (verbose) {
        for (const auto& it : *mCurrentSlicedBucket) {
            fprintf(out, "\t(what)%s\t(condition)%s  %d atoms\n",
                it.first.getDimensionKeyInWhat().toString().c_str(),
                it.first.getDimensionKeyInCondition().toString().c_str(),
                (int)it.second.size());
        }
    }
}

void GaugeMetricProducer::onDumpReportLocked(const int64_t dumpTimeNs,
                                             const bool include_current_partial_bucket,
                                             ProtoOutputStream* protoOutput) {
    VLOG("Gauge metric %lld report now...", (long long)mMetricId);
    if (include_current_partial_bucket) {
        flushLocked(dumpTimeNs);
    } else {
        flushIfNeededLocked(dumpTimeNs);
    }

    flushIfNeededLocked(dumpTimeNs);
    if (mPastBuckets.empty()) {
        return;
    }

    protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_ID, (long long)mMetricId);
    uint64_t protoToken = protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_ID_GAUGE_METRICS);

    for (const auto& pair : mPastBuckets) {
        const MetricDimensionKey& dimensionKey = pair.first;

        VLOG("Gauge dimension key %s", dimensionKey.toString().c_str());
        uint64_t wrapperToken =
                protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_DATA);

        // First fill dimension.
        uint64_t dimensionToken = protoOutput->start(
                FIELD_TYPE_MESSAGE | FIELD_ID_DIMENSION_IN_WHAT);
        writeDimensionToProto(dimensionKey.getDimensionKeyInWhat(), protoOutput);
        protoOutput->end(dimensionToken);

        if (dimensionKey.hasDimensionKeyInCondition()) {
            uint64_t dimensionInConditionToken = protoOutput->start(
                    FIELD_TYPE_MESSAGE | FIELD_ID_DIMENSION_IN_CONDITION);
            writeDimensionToProto(dimensionKey.getDimensionKeyInCondition(), protoOutput);
            protoOutput->end(dimensionInConditionToken);
        }

        // Then fill bucket_info (GaugeBucketInfo).
        for (const auto& bucket : pair.second) {
            uint64_t bucketInfoToken = protoOutput->start(
                    FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_BUCKET_INFO);
            protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_START_BUCKET_ELAPSED_NANOS,
                               (long long)bucket.mBucketStartNs);
            protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_END_BUCKET_ELAPSED_NANOS,
                               (long long)bucket.mBucketEndNs);

            if (!bucket.mGaugeAtoms.empty()) {
                for (const auto& atom : bucket.mGaugeAtoms) {
                    uint64_t atomsToken =
                        protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED |
                                           FIELD_ID_ATOM);
                    writeFieldValueTreeToStream(mTagId, *(atom.mFields), protoOutput);
                    protoOutput->end(atomsToken);
                }
                const bool truncateTimestamp =
                        android::util::AtomsInfo::kNotTruncatingTimestampAtomWhiteList.find(
                                mTagId) ==
                        android::util::AtomsInfo::kNotTruncatingTimestampAtomWhiteList.end();
                for (const auto& atom : bucket.mGaugeAtoms) {
                    const int64_t elapsedTimestampNs =  truncateTimestamp ?
                        truncateTimestampNsToFiveMinutes(atom.mElapsedTimestamps) :
                            atom.mElapsedTimestamps;
                    const int64_t wallClockNs = truncateTimestamp ?
                        truncateTimestampNsToFiveMinutes(atom.mWallClockTimestampNs) :
                            atom.mWallClockTimestampNs;
                    protoOutput->write(
                        FIELD_TYPE_INT64 | FIELD_COUNT_REPEATED | FIELD_ID_ELAPSED_ATOM_TIMESTAMP,
                        (long long)elapsedTimestampNs);
                    protoOutput->write(
                        FIELD_TYPE_INT64 | FIELD_COUNT_REPEATED |
                            FIELD_ID_WALL_CLOCK_ATOM_TIMESTAMP,
                        (long long)wallClockNs);
                }
            }
            protoOutput->end(bucketInfoToken);
            VLOG("Gauge \t bucket [%lld - %lld] includes %d atoms.",
                 (long long)bucket.mBucketStartNs, (long long)bucket.mBucketEndNs,
                 (int)bucket.mGaugeAtoms.size());
        }
        protoOutput->end(wrapperToken);
    }
    protoOutput->end(protoToken);

    mPastBuckets.clear();
    // TODO: Clear mDimensionKeyMap once the report is dumped.
}

void GaugeMetricProducer::pullLocked(const int64_t timestampNs) {
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
    if (!mStatsPullerManager->Pull(mPullTagId, timestampNs, &allData)) {
        ALOGE("Gauge Stats puller failed for tag: %d", mPullTagId);
        return;
    }

    for (const auto& data : allData) {
        onMatchedLogEventLocked(0, *data);
    }
}

void GaugeMetricProducer::onConditionChangedLocked(const bool conditionMet,
                                                   const int64_t eventTimeNs) {
    VLOG("GaugeMetric %lld onConditionChanged", (long long)mMetricId);
    flushIfNeededLocked(eventTimeNs);
    mCondition = conditionMet;

    if (mPullTagId != -1 && mCondition) {
        pullLocked(eventTimeNs);
    }  // else: Push mode. No need to proactively pull the gauge data.
}

void GaugeMetricProducer::onSlicedConditionMayChangeLocked(bool overallCondition,
                                                           const int64_t eventTimeNs) {
    VLOG("GaugeMetric %lld onSlicedConditionMayChange overall condition %d", (long long)mMetricId,
         overallCondition);
    flushIfNeededLocked(eventTimeNs);
    // If the condition is sliced, mCondition is true if any of the dimensions is true. And we will
    // pull for every dimension.
    mCondition = overallCondition;
    if (mPullTagId != -1) {
        pullLocked(eventTimeNs);
    }  // else: Push mode. No need to proactively pull the gauge data.
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
    if (mCurrentSlicedBucket->size() > mDimensionSoftLimit - 1) {
        size_t newTupleCount = mCurrentSlicedBucket->size() + 1;
        StatsdStats::getInstance().noteMetricDimensionSize(mConfigKey, mMetricId, newTupleCount);
        // 2. Don't add more tuples, we are above the allowed threshold. Drop the data.
        if (newTupleCount > mDimensionHardLimit) {
            ALOGE("GaugeMetric %lld dropping data for dimension key %s",
                (long long)mMetricId, newKey.toString().c_str());
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
    int64_t eventTimeNs = event.GetElapsedTimestampNs();
    mTagId = event.GetTagId();
    if (eventTimeNs < mCurrentBucketStartTimeNs) {
        VLOG("Gauge Skip event due to late arrival: %lld vs %lld", (long long)eventTimeNs,
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
    GaugeAtom gaugeAtom(getGaugeFields(event), eventTimeNs, getWallClockNs());
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

void GaugeMetricProducer::dropDataLocked(const int64_t dropTimeNs) {
    flushIfNeededLocked(dropTimeNs);
    mPastBuckets.clear();
}

// When a new matched event comes in, we check if event falls into the current
// bucket. If not, flush the old counter to past buckets and initialize the new
// bucket.
// if data is pushed, onMatchedLogEvent will only be called through onConditionChanged() inside
// the GaugeMetricProducer while holding the lock.
void GaugeMetricProducer::flushIfNeededLocked(const int64_t& eventTimeNs) {
    int64_t currentBucketEndTimeNs = getCurrentBucketEndTimeNs();

    if (eventTimeNs < currentBucketEndTimeNs) {
        VLOG("Gauge eventTime is %lld, less than next bucket start time %lld",
             (long long)eventTimeNs, (long long)(mCurrentBucketStartTimeNs + mBucketSizeNs));
        return;
    }

    flushCurrentBucketLocked(eventTimeNs);

    // Adjusts the bucket start and end times.
    int64_t numBucketsForward = 1 + (eventTimeNs - currentBucketEndTimeNs) / mBucketSizeNs;
    mCurrentBucketStartTimeNs = currentBucketEndTimeNs + (numBucketsForward - 1) * mBucketSizeNs;
    mCurrentBucketNum += numBucketsForward;
    VLOG("Gauge metric %lld: new bucket start time: %lld", (long long)mMetricId,
         (long long)mCurrentBucketStartTimeNs);
}

void GaugeMetricProducer::flushCurrentBucketLocked(const int64_t& eventTimeNs) {
    int64_t fullBucketEndTimeNs = getCurrentBucketEndTimeNs();

    GaugeBucket info;
    info.mBucketStartNs = mCurrentBucketStartTimeNs;
    if (eventTimeNs < fullBucketEndTimeNs) {
        info.mBucketEndNs = eventTimeNs;
    } else {
        info.mBucketEndNs = fullBucketEndTimeNs;
    }

    for (const auto& slice : *mCurrentSlicedBucket) {
        info.mGaugeAtoms = slice.second;
        auto& bucketList = mPastBuckets[slice.first];
        bucketList.push_back(info);
        VLOG("Gauge gauge metric %lld, dump key value: %s", (long long)mMetricId,
             slice.first.toString().c_str());
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
