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
const int FIELD_ID_TIME_BASE = 9;
const int FIELD_ID_BUCKET_SIZE = 10;
const int FIELD_ID_DIMENSION_PATH_IN_WHAT = 11;
const int FIELD_ID_DIMENSION_PATH_IN_CONDITION = 12;
const int FIELD_ID_IS_ACTIVE = 14;
// for GaugeMetricDataWrapper
const int FIELD_ID_DATA = 1;
const int FIELD_ID_SKIPPED = 2;
const int FIELD_ID_SKIPPED_START_MILLIS = 3;
const int FIELD_ID_SKIPPED_END_MILLIS = 4;
// for GaugeMetricData
const int FIELD_ID_DIMENSION_IN_WHAT = 1;
const int FIELD_ID_DIMENSION_IN_CONDITION = 2;
const int FIELD_ID_BUCKET_INFO = 3;
const int FIELD_ID_DIMENSION_LEAF_IN_WHAT = 4;
const int FIELD_ID_DIMENSION_LEAF_IN_CONDITION = 5;
// for GaugeBucketInfo
const int FIELD_ID_ATOM = 3;
const int FIELD_ID_ELAPSED_ATOM_TIMESTAMP = 4;
const int FIELD_ID_BUCKET_NUM = 6;
const int FIELD_ID_START_BUCKET_ELAPSED_MILLIS = 7;
const int FIELD_ID_END_BUCKET_ELAPSED_MILLIS = 8;

GaugeMetricProducer::GaugeMetricProducer(
        const ConfigKey& key, const GaugeMetric& metric, const int conditionIndex,
        const sp<ConditionWizard>& wizard, const int whatMatcherIndex,
        const sp<EventMatcherWizard>& matcherWizard, const int pullTagId, const int triggerAtomId,
        const int atomId, const int64_t timeBaseNs, const int64_t startTimeNs,
        const sp<StatsPullerManager>& pullerManager)
    : MetricProducer(metric.id(), key, timeBaseNs, conditionIndex, wizard),
      mWhatMatcherIndex(whatMatcherIndex),
      mEventMatcherWizard(matcherWizard),
      mPullerManager(pullerManager),
      mPullTagId(pullTagId),
      mTriggerAtomId(triggerAtomId),
      mAtomId(atomId),
      mIsPulled(pullTagId != -1),
      mMinBucketSizeNs(metric.min_bucket_size_nanos()),
      mMaxPullDelayNs(metric.max_pull_delay_sec() > 0 ? metric.max_pull_delay_sec() * NS_PER_SEC
                                                      : StatsdStats::kPullMaxDelayNs),
      mDimensionSoftLimit(StatsdStats::kAtomDimensionKeySizeLimitMap.find(pullTagId) !=
                                          StatsdStats::kAtomDimensionKeySizeLimitMap.end()
                                  ? StatsdStats::kAtomDimensionKeySizeLimitMap.at(pullTagId).first
                                  : StatsdStats::kDimensionKeySizeSoftLimit),
      mDimensionHardLimit(StatsdStats::kAtomDimensionKeySizeLimitMap.find(pullTagId) !=
                                          StatsdStats::kAtomDimensionKeySizeLimitMap.end()
                                  ? StatsdStats::kAtomDimensionKeySizeLimitMap.at(pullTagId).second
                                  : StatsdStats::kDimensionKeySizeHardLimit),
      mGaugeAtomsPerDimensionLimit(metric.max_num_gauge_atoms_per_bucket()),
      mSplitBucketForAppUpgrade(metric.split_bucket_for_app_upgrade()) {
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
    mSliceByPositionALL = HasPositionALL(metric.dimensions_in_what()) ||
            HasPositionALL(metric.dimensions_in_condition());

    flushIfNeededLocked(startTimeNs);
    // Kicks off the puller immediately.
    if (mIsPulled && mSamplingType == GaugeMetric::RANDOM_ONE_SAMPLE) {
        mPullerManager->RegisterReceiver(mPullTagId, this, getCurrentBucketEndTimeNs(),
                                         mBucketSizeNs);
    }

    // Adjust start for partial bucket
    mCurrentBucketStartTimeNs = startTimeNs;
    if (mIsPulled && mSamplingType == GaugeMetric::RANDOM_ONE_SAMPLE) {
        pullAndMatchEventsLocked(startTimeNs);
    }

    VLOG("Gauge metric %lld created. bucket size %lld start_time: %lld sliced %d",
         (long long)metric.id(), (long long)mBucketSizeNs, (long long)mTimeBaseNs,
         mConditionSliced);
}

GaugeMetricProducer::~GaugeMetricProducer() {
    VLOG("~GaugeMetricProducer() called");
    if (mIsPulled && mSamplingType == GaugeMetric::RANDOM_ONE_SAMPLE) {
        mPullerManager->UnRegisterReceiver(mPullTagId, this);
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

void GaugeMetricProducer::clearPastBucketsLocked(const int64_t dumpTimeNs) {
    flushIfNeededLocked(dumpTimeNs);
    mPastBuckets.clear();
    mSkippedBuckets.clear();
}

void GaugeMetricProducer::onDumpReportLocked(const int64_t dumpTimeNs,
                                             const bool include_current_partial_bucket,
                                             const bool erase_data,
                                             std::set<string> *str_set,
                                             ProtoOutputStream* protoOutput) {
    VLOG("Gauge metric %lld report now...", (long long)mMetricId);
    if (include_current_partial_bucket) {
        flushLocked(dumpTimeNs);
    } else {
        flushIfNeededLocked(dumpTimeNs);
    }

    protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_ID, (long long)mMetricId);
    protoOutput->write(FIELD_TYPE_BOOL | FIELD_ID_IS_ACTIVE, isActiveLocked());

    if (mPastBuckets.empty()) {
        return;
    }

    protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_TIME_BASE, (long long)mTimeBaseNs);
    protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_BUCKET_SIZE, (long long)mBucketSizeNs);

    // Fills the dimension path if not slicing by ALL.
    if (!mSliceByPositionALL) {
        if (!mDimensionsInWhat.empty()) {
            uint64_t dimenPathToken = protoOutput->start(
                    FIELD_TYPE_MESSAGE | FIELD_ID_DIMENSION_PATH_IN_WHAT);
            writeDimensionPathToProto(mDimensionsInWhat, protoOutput);
            protoOutput->end(dimenPathToken);
        }
        if (!mDimensionsInCondition.empty()) {
            uint64_t dimenPathToken = protoOutput->start(
                    FIELD_TYPE_MESSAGE | FIELD_ID_DIMENSION_PATH_IN_CONDITION);
            writeDimensionPathToProto(mDimensionsInCondition, protoOutput);
            protoOutput->end(dimenPathToken);
        }
    }

    uint64_t protoToken = protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_ID_GAUGE_METRICS);

    for (const auto& pair : mSkippedBuckets) {
        uint64_t wrapperToken =
                protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_SKIPPED);
        protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_SKIPPED_START_MILLIS,
                           (long long)(NanoToMillis(pair.first)));
        protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_SKIPPED_END_MILLIS,
                           (long long)(NanoToMillis(pair.second)));
        protoOutput->end(wrapperToken);
    }

    for (const auto& pair : mPastBuckets) {
        const MetricDimensionKey& dimensionKey = pair.first;

        VLOG("Gauge dimension key %s", dimensionKey.toString().c_str());
        uint64_t wrapperToken =
                protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_DATA);

        // First fill dimension.
        if (mSliceByPositionALL) {
            uint64_t dimensionToken = protoOutput->start(
                    FIELD_TYPE_MESSAGE | FIELD_ID_DIMENSION_IN_WHAT);
            writeDimensionToProto(dimensionKey.getDimensionKeyInWhat(), str_set, protoOutput);
            protoOutput->end(dimensionToken);

            if (dimensionKey.hasDimensionKeyInCondition()) {
                uint64_t dimensionInConditionToken = protoOutput->start(
                        FIELD_TYPE_MESSAGE | FIELD_ID_DIMENSION_IN_CONDITION);
                writeDimensionToProto(dimensionKey.getDimensionKeyInCondition(),
                                      str_set, protoOutput);
                protoOutput->end(dimensionInConditionToken);
            }
        } else {
            writeDimensionLeafNodesToProto(dimensionKey.getDimensionKeyInWhat(),
                                           FIELD_ID_DIMENSION_LEAF_IN_WHAT, str_set, protoOutput);
            if (dimensionKey.hasDimensionKeyInCondition()) {
                writeDimensionLeafNodesToProto(dimensionKey.getDimensionKeyInCondition(),
                                               FIELD_ID_DIMENSION_LEAF_IN_CONDITION,
                                               str_set, protoOutput);
            }
        }

        // Then fill bucket_info (GaugeBucketInfo).
        for (const auto& bucket : pair.second) {
            uint64_t bucketInfoToken = protoOutput->start(
                    FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_BUCKET_INFO);

            if (bucket.mBucketEndNs - bucket.mBucketStartNs != mBucketSizeNs) {
                protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_START_BUCKET_ELAPSED_MILLIS,
                                   (long long)NanoToMillis(bucket.mBucketStartNs));
                protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_END_BUCKET_ELAPSED_MILLIS,
                                   (long long)NanoToMillis(bucket.mBucketEndNs));
            } else {
                protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_BUCKET_NUM,
                                   (long long)(getBucketNumFromEndTimeNs(bucket.mBucketEndNs)));
            }

            if (!bucket.mGaugeAtoms.empty()) {
                for (const auto& atom : bucket.mGaugeAtoms) {
                    uint64_t atomsToken =
                        protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED |
                                           FIELD_ID_ATOM);
                    writeFieldValueTreeToStream(mAtomId, *(atom.mFields), protoOutput);
                    protoOutput->end(atomsToken);
                }
                const bool truncateTimestamp =
                        android::util::AtomsInfo::kNotTruncatingTimestampAtomWhiteList.find(
                                mAtomId) ==
                        android::util::AtomsInfo::kNotTruncatingTimestampAtomWhiteList.end();
                for (const auto& atom : bucket.mGaugeAtoms) {
                    const int64_t elapsedTimestampNs =  truncateTimestamp ?
                        truncateTimestampNsToFiveMinutes(atom.mElapsedTimestamps) :
                            atom.mElapsedTimestamps;
                    protoOutput->write(
                        FIELD_TYPE_INT64 | FIELD_COUNT_REPEATED | FIELD_ID_ELAPSED_ATOM_TIMESTAMP,
                        (long long)elapsedTimestampNs);
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


    if (erase_data) {
        mPastBuckets.clear();
        mSkippedBuckets.clear();
    }
}

void GaugeMetricProducer::pullAndMatchEventsLocked(const int64_t timestampNs) {
    bool triggerPuller = false;
    switch(mSamplingType) {
        // When the metric wants to do random sampling and there is already one gauge atom for the
        // current bucket, do not do it again.
        case GaugeMetric::RANDOM_ONE_SAMPLE: {
            triggerPuller = mCondition && mCurrentSlicedBucket->empty();
            break;
        }
        case GaugeMetric::CONDITION_CHANGE_TO_TRUE: {
            triggerPuller = mCondition;
            break;
        }
        case GaugeMetric::FIRST_N_SAMPLES: {
            triggerPuller = mCondition;
            break;
        }
        default:
            break;
    }
    if (!triggerPuller) {
        return;
    }
    vector<std::shared_ptr<LogEvent>> allData;
    if (!mPullerManager->Pull(mPullTagId, &allData)) {
        ALOGE("Gauge Stats puller failed for tag: %d at %lld", mPullTagId, (long long)timestampNs);
        return;
    }
    const int64_t pullDelayNs = getElapsedRealtimeNs() - timestampNs;
    if (pullDelayNs > mMaxPullDelayNs) {
        ALOGE("Pull finish too late for atom %d", mPullTagId);
        StatsdStats::getInstance().notePullExceedMaxDelay(mPullTagId);
        StatsdStats::getInstance().notePullDelay(mPullTagId, pullDelayNs);
        return;
    }
    StatsdStats::getInstance().notePullDelay(mPullTagId, pullDelayNs);
    for (const auto& data : allData) {
        LogEvent localCopy = data->makeCopy();
        localCopy.setElapsedTimestampNs(timestampNs);
        if (mEventMatcherWizard->matchLogEvent(localCopy, mWhatMatcherIndex) ==
            MatchingState::kMatched) {
            onMatchedLogEventLocked(mWhatMatcherIndex, localCopy);
        }
    }
}

void GaugeMetricProducer::onConditionChangedLocked(const bool conditionMet,
                                                   const int64_t eventTimeNs) {
    VLOG("GaugeMetric %lld onConditionChanged", (long long)mMetricId);
    flushIfNeededLocked(eventTimeNs);
    mCondition = conditionMet;
    if (mIsPulled && mTriggerAtomId == -1) {
        pullAndMatchEventsLocked(eventTimeNs);
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
    if (mIsPulled && mTriggerAtomId == -1) {
        pullAndMatchEventsLocked(eventTimeNs);
    }  // else: Push mode. No need to proactively pull the gauge data.
}

std::shared_ptr<vector<FieldValue>> GaugeMetricProducer::getGaugeFields(const LogEvent& event) {
    std::shared_ptr<vector<FieldValue>> gaugeFields;
    if (mFieldMatchers.size() > 0) {
        gaugeFields = std::make_shared<vector<FieldValue>>();
        filterGaugeValues(mFieldMatchers, event.getValues(), gaugeFields.get());
    } else {
        gaugeFields = std::make_shared<vector<FieldValue>>(event.getValues());
    }
    // Trim all dimension fields from output. Dimensions will appear in output report and will
    // benefit from dictionary encoding. For large pulled atoms, this can give the benefit of
    // optional repeated field.
    for (const auto& field : mDimensionsInWhat) {
        for (auto it = gaugeFields->begin(); it != gaugeFields->end();) {
            if (it->mField.matches(field)) {
                it = gaugeFields->erase(it);
            } else {
                it++;
            }
        }
    }
    return gaugeFields;
}

void GaugeMetricProducer::onDataPulled(const std::vector<std::shared_ptr<LogEvent>>& allData,
                                       bool pullSuccess, int64_t originalPullTimeNs) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (!pullSuccess || allData.size() == 0) {
        return;
    }
    for (const auto& data : allData) {
        if (mEventMatcherWizard->matchLogEvent(
                *data, mWhatMatcherIndex) == MatchingState::kMatched) {
            onMatchedLogEventLocked(mWhatMatcherIndex, *data);
        }
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
    if (eventTimeNs < mCurrentBucketStartTimeNs) {
        VLOG("Gauge Skip event due to late arrival: %lld vs %lld", (long long)eventTimeNs,
             (long long)mCurrentBucketStartTimeNs);
        return;
    }
    flushIfNeededLocked(eventTimeNs);

    if (mTriggerAtomId == event.GetTagId()) {
        pullAndMatchEventsLocked(eventTimeNs);
        return;
    }

    // When gauge metric wants to randomly sample the output atom, we just simply use the first
    // gauge in the given bucket.
    if (mCurrentSlicedBucket->find(eventKey) != mCurrentSlicedBucket->end() &&
        mSamplingType == GaugeMetric::RANDOM_ONE_SAMPLE) {
        return;
    }
    if (hitGuardRailLocked(eventKey)) {
        return;
    }
    if ((*mCurrentSlicedBucket)[eventKey].size() >= mGaugeAtomsPerDimensionLimit) {
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

void GaugeMetricProducer::dropDataLocked(const int64_t dropTimeNs) {
    flushIfNeededLocked(dropTimeNs);
    StatsdStats::getInstance().noteBucketDropped(mMetricId);
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

    if (info.mBucketEndNs - mCurrentBucketStartTimeNs >= mMinBucketSizeNs) {
        for (const auto& slice : *mCurrentSlicedBucket) {
            info.mGaugeAtoms = slice.second;
            auto& bucketList = mPastBuckets[slice.first];
            bucketList.push_back(info);
            VLOG("Gauge gauge metric %lld, dump key value: %s", (long long)mMetricId,
                 slice.first.toString().c_str());
        }
    } else {
        mSkippedBuckets.emplace_back(info.mBucketStartNs, info.mBucketEndNs);
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
        for (const auto& bucket : pair.second) {
            totalSize += bucket.mGaugeAtoms.size() * sizeof(GaugeAtom);
            for (const auto& atom : bucket.mGaugeAtoms) {
                if (atom.mFields != nullptr) {
                    totalSize += atom.mFields->size() * sizeof(FieldValue);
                }
            }
        }
    }
    return totalSize;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
