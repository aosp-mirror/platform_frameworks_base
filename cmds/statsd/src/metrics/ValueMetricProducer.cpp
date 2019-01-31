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

#include "ValueMetricProducer.h"
#include "../guardrail/StatsdStats.h"
#include "../stats_log_util.h"

#include <cutils/log.h>
#include <limits.h>
#include <stdlib.h>

using android::util::FIELD_COUNT_REPEATED;
using android::util::FIELD_TYPE_BOOL;
using android::util::FIELD_TYPE_DOUBLE;
using android::util::FIELD_TYPE_INT32;
using android::util::FIELD_TYPE_INT64;
using android::util::FIELD_TYPE_MESSAGE;
using android::util::FIELD_TYPE_STRING;
using android::util::ProtoOutputStream;
using std::list;
using std::make_pair;
using std::make_shared;
using std::map;
using std::shared_ptr;
using std::unique_ptr;
using std::unordered_map;

namespace android {
namespace os {
namespace statsd {

// for StatsLogReport
const int FIELD_ID_ID = 1;
const int FIELD_ID_VALUE_METRICS = 7;
const int FIELD_ID_TIME_BASE = 9;
const int FIELD_ID_BUCKET_SIZE = 10;
const int FIELD_ID_DIMENSION_PATH_IN_WHAT = 11;
const int FIELD_ID_DIMENSION_PATH_IN_CONDITION = 12;
const int FIELD_ID_IS_ACTIVE = 14;
// for ValueMetricDataWrapper
const int FIELD_ID_DATA = 1;
const int FIELD_ID_SKIPPED = 2;
const int FIELD_ID_SKIPPED_START_MILLIS = 3;
const int FIELD_ID_SKIPPED_END_MILLIS = 4;
// for ValueMetricData
const int FIELD_ID_DIMENSION_IN_WHAT = 1;
const int FIELD_ID_DIMENSION_IN_CONDITION = 2;
const int FIELD_ID_BUCKET_INFO = 3;
const int FIELD_ID_DIMENSION_LEAF_IN_WHAT = 4;
const int FIELD_ID_DIMENSION_LEAF_IN_CONDITION = 5;
// for ValueBucketInfo
const int FIELD_ID_VALUE_INDEX = 1;
const int FIELD_ID_VALUE_LONG = 2;
const int FIELD_ID_VALUE_DOUBLE = 3;
const int FIELD_ID_VALUES = 9;
const int FIELD_ID_BUCKET_NUM = 4;
const int FIELD_ID_START_BUCKET_ELAPSED_MILLIS = 5;
const int FIELD_ID_END_BUCKET_ELAPSED_MILLIS = 6;

const Value ZERO_LONG((int64_t)0);
const Value ZERO_DOUBLE((int64_t)0);

// ValueMetric has a minimum bucket size of 10min so that we don't pull too frequently
ValueMetricProducer::ValueMetricProducer(
        const ConfigKey& key, const ValueMetric& metric, const int conditionIndex,
        const sp<ConditionWizard>& conditionWizard, const int whatMatcherIndex,
        const sp<EventMatcherWizard>& matcherWizard, const int pullTagId, const int64_t timeBaseNs,
        const int64_t startTimeNs, const sp<StatsPullerManager>& pullerManager)
    : MetricProducer(metric.id(), key, timeBaseNs, conditionIndex, conditionWizard),
      mWhatMatcherIndex(whatMatcherIndex),
      mEventMatcherWizard(matcherWizard),
      mPullerManager(pullerManager),
      mPullTagId(pullTagId),
      mIsPulled(pullTagId != -1),
      mMinBucketSizeNs(metric.min_bucket_size_nanos()),
      mDimensionSoftLimit(StatsdStats::kAtomDimensionKeySizeLimitMap.find(pullTagId) !=
                                          StatsdStats::kAtomDimensionKeySizeLimitMap.end()
                                  ? StatsdStats::kAtomDimensionKeySizeLimitMap.at(pullTagId).first
                                  : StatsdStats::kDimensionKeySizeSoftLimit),
      mDimensionHardLimit(StatsdStats::kAtomDimensionKeySizeLimitMap.find(pullTagId) !=
                                          StatsdStats::kAtomDimensionKeySizeLimitMap.end()
                                  ? StatsdStats::kAtomDimensionKeySizeLimitMap.at(pullTagId).second
                                  : StatsdStats::kDimensionKeySizeHardLimit),
      mUseAbsoluteValueOnReset(metric.use_absolute_value_on_reset()),
      mAggregationType(metric.aggregation_type()),
      mUseDiff(metric.has_use_diff() ? metric.use_diff() : (mIsPulled ? true : false)),
      mValueDirection(metric.value_direction()),
      mSkipZeroDiffOutput(metric.skip_zero_diff_output()),
      mUseZeroDefaultBase(metric.use_zero_default_base()),
      mHasGlobalBase(false),
      mMaxPullDelayNs(metric.max_pull_delay_sec() > 0 ? metric.max_pull_delay_sec() * NS_PER_SEC
                                                      : StatsdStats::kPullMaxDelayNs),
      mSplitBucketForAppUpgrade(metric.split_bucket_for_app_upgrade()) {
    int64_t bucketSizeMills = 0;
    if (metric.has_bucket()) {
        bucketSizeMills = TimeUnitToBucketSizeInMillisGuardrailed(key.GetUid(), metric.bucket());
    } else {
        bucketSizeMills = TimeUnitToBucketSizeInMillis(ONE_HOUR);
    }

    mBucketSizeNs = bucketSizeMills * 1000000;

    translateFieldMatcher(metric.value_field(), &mFieldMatchers);

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

    if (mIsPulled) {
        mPullerManager->RegisterReceiver(mPullTagId, this, getCurrentBucketEndTimeNs(),
                                         mBucketSizeNs);
    }

    // Only do this for partial buckets like first bucket. All other buckets should use
    // flushIfNeeded to adjust start and end to bucket boundaries.
    // Adjust start for partial bucket
    mCurrentBucketStartTimeNs = startTimeNs;
    // Kicks off the puller immediately if condition is true and diff based.
    if (mIsPulled && mCondition && mUseDiff) {
        pullAndMatchEventsLocked(startTimeNs);
    }
    VLOG("value metric %lld created. bucket size %lld start_time: %lld", (long long)metric.id(),
         (long long)mBucketSizeNs, (long long)mTimeBaseNs);
}

ValueMetricProducer::~ValueMetricProducer() {
    VLOG("~ValueMetricProducer() called");
    if (mIsPulled) {
        mPullerManager->UnRegisterReceiver(mPullTagId, this);
    }
}

void ValueMetricProducer::onSlicedConditionMayChangeLocked(bool overallCondition,
                                                           const int64_t eventTime) {
    VLOG("Metric %lld onSlicedConditionMayChange", (long long)mMetricId);
}

void ValueMetricProducer::dropDataLocked(const int64_t dropTimeNs) {
    flushIfNeededLocked(dropTimeNs);
    mPastBuckets.clear();
}

void ValueMetricProducer::clearPastBucketsLocked(const int64_t dumpTimeNs) {
    flushIfNeededLocked(dumpTimeNs);
    mPastBuckets.clear();
    mSkippedBuckets.clear();
}

void ValueMetricProducer::onDumpReportLocked(const int64_t dumpTimeNs,
                                             const bool include_current_partial_bucket,
                                             const bool erase_data,
                                             std::set<string> *str_set,
                                             ProtoOutputStream* protoOutput) {
    VLOG("metric %lld dump report now...", (long long)mMetricId);
    if (include_current_partial_bucket) {
        flushLocked(dumpTimeNs);
    } else {
        flushIfNeededLocked(dumpTimeNs);
    }
    protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_ID, (long long)mMetricId);
    protoOutput->write(FIELD_TYPE_BOOL | FIELD_ID_IS_ACTIVE, isActiveLocked());

    if (mPastBuckets.empty() && mSkippedBuckets.empty()) {
        return;
    }
    protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_TIME_BASE, (long long)mTimeBaseNs);
    protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_BUCKET_SIZE, (long long)mBucketSizeNs);
    // Fills the dimension path if not slicing by ALL.
    if (!mSliceByPositionALL) {
        if (!mDimensionsInWhat.empty()) {
            uint64_t dimenPathToken =
                    protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_ID_DIMENSION_PATH_IN_WHAT);
            writeDimensionPathToProto(mDimensionsInWhat, protoOutput);
            protoOutput->end(dimenPathToken);
        }
        if (!mDimensionsInCondition.empty()) {
            uint64_t dimenPathToken =
                    protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_ID_DIMENSION_PATH_IN_CONDITION);
            writeDimensionPathToProto(mDimensionsInCondition, protoOutput);
            protoOutput->end(dimenPathToken);
        }
    }

    uint64_t protoToken = protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_ID_VALUE_METRICS);

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
        VLOG("  dimension key %s", dimensionKey.toString().c_str());
        uint64_t wrapperToken =
                protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_DATA);

        // First fill dimension.
        if (mSliceByPositionALL) {
            uint64_t dimensionToken =
                    protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_ID_DIMENSION_IN_WHAT);
            writeDimensionToProto(dimensionKey.getDimensionKeyInWhat(), str_set, protoOutput);
            protoOutput->end(dimensionToken);
            if (dimensionKey.hasDimensionKeyInCondition()) {
                uint64_t dimensionInConditionToken =
                        protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_ID_DIMENSION_IN_CONDITION);
                writeDimensionToProto(dimensionKey.getDimensionKeyInCondition(), str_set,
                                      protoOutput);
                protoOutput->end(dimensionInConditionToken);
            }
        } else {
            writeDimensionLeafNodesToProto(dimensionKey.getDimensionKeyInWhat(),
                                           FIELD_ID_DIMENSION_LEAF_IN_WHAT, str_set, protoOutput);
            if (dimensionKey.hasDimensionKeyInCondition()) {
                writeDimensionLeafNodesToProto(dimensionKey.getDimensionKeyInCondition(),
                                               FIELD_ID_DIMENSION_LEAF_IN_CONDITION, str_set,
                                               protoOutput);
            }
        }

        // Then fill bucket_info (ValueBucketInfo).
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
            for (int i = 0; i < (int)bucket.valueIndex.size(); i ++) {
                int index = bucket.valueIndex[i];
                const Value& value = bucket.values[i];
                uint64_t valueToken = protoOutput->start(
                        FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_VALUES);
                protoOutput->write(FIELD_TYPE_INT32 | FIELD_ID_VALUE_INDEX,
                                   index);
                if (value.getType() == LONG) {
                    protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_VALUE_LONG,
                                       (long long)value.long_value);
                    VLOG("\t bucket [%lld - %lld] value %d: %lld", (long long)bucket.mBucketStartNs,
                         (long long)bucket.mBucketEndNs, index, (long long)value.long_value);
                } else if (value.getType() == DOUBLE) {
                    protoOutput->write(FIELD_TYPE_DOUBLE | FIELD_ID_VALUE_DOUBLE,
                                       value.double_value);
                    VLOG("\t bucket [%lld - %lld] value %d: %.2f", (long long)bucket.mBucketStartNs,
                         (long long)bucket.mBucketEndNs, index, value.double_value);
                } else {
                    VLOG("Wrong value type for ValueMetric output: %d", value.getType());
                }
                protoOutput->end(valueToken);
            }
            protoOutput->end(bucketInfoToken);
        }
        protoOutput->end(wrapperToken);
    }
    protoOutput->end(protoToken);

    VLOG("metric %lld dump report now...", (long long)mMetricId);
    if (erase_data) {
        mPastBuckets.clear();
        mSkippedBuckets.clear();
    }
}

void ValueMetricProducer::resetBase() {
    for (auto& slice : mCurrentSlicedBucket) {
        for (auto& interval : slice.second) {
            interval.hasBase = false;
        }
    }
    mHasGlobalBase = false;
}

void ValueMetricProducer::onConditionChangedLocked(const bool condition,
                                                   const int64_t eventTimeNs) {
    if (eventTimeNs < mCurrentBucketStartTimeNs) {
        VLOG("Skip event due to late arrival: %lld vs %lld", (long long)eventTimeNs,
             (long long)mCurrentBucketStartTimeNs);
        StatsdStats::getInstance().noteConditionChangeInNextBucket(mMetricId);
        return;
    }

    flushIfNeededLocked(eventTimeNs);

    // Pull on condition changes.
    if (mIsPulled && (mCondition != condition)) {
        pullAndMatchEventsLocked(eventTimeNs);
    }

    // when condition change from true to false, clear diff base but don't
    // reset other counters as we may accumulate more value in the bucket.
    if (mUseDiff && mCondition && !condition) {
        resetBase();
    }

    mCondition = condition;
}

void ValueMetricProducer::pullAndMatchEventsLocked(const int64_t timestampNs) {
    vector<std::shared_ptr<LogEvent>> allData;
    if (!mPullerManager->Pull(mPullTagId, &allData)) {
        ALOGE("Gauge Stats puller failed for tag: %d at %lld", mPullTagId, (long long)timestampNs);
        resetBase();
        return;
    }
    const int64_t pullDelayNs = getElapsedRealtimeNs() - timestampNs;
    if (pullDelayNs > mMaxPullDelayNs) {
        ALOGE("Pull finish too late for atom %d, longer than %lld", mPullTagId,
              (long long)mMaxPullDelayNs);
        StatsdStats::getInstance().notePullExceedMaxDelay(mPullTagId);
        StatsdStats::getInstance().notePullDelay(mPullTagId, pullDelayNs);
        resetBase();
        return;
    }
    StatsdStats::getInstance().notePullDelay(mPullTagId, pullDelayNs);

    if (timestampNs < mCurrentBucketStartTimeNs) {
        // The data will be skipped in onMatchedLogEventInternalLocked, but we don't want to report
        // for every event, just the pull
        StatsdStats::getInstance().noteLateLogEventSkipped(mMetricId);
    }

    for (const auto& data : allData) {
        // make a copy before doing and changes
        LogEvent localCopy = data->makeCopy();
        localCopy.setElapsedTimestampNs(timestampNs);
        if (mEventMatcherWizard->matchLogEvent(localCopy, mWhatMatcherIndex) ==
            MatchingState::kMatched) {
            onMatchedLogEventLocked(mWhatMatcherIndex, localCopy);
        }
    }
    mHasGlobalBase = true;
}

int64_t ValueMetricProducer::calcPreviousBucketEndTime(const int64_t currentTimeNs) {
    return mTimeBaseNs + ((currentTimeNs - mTimeBaseNs) / mBucketSizeNs) * mBucketSizeNs;
}

void ValueMetricProducer::onDataPulled(const std::vector<std::shared_ptr<LogEvent>>& allData) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mCondition) {
        if (allData.size() == 0) {
            VLOG("Data pulled is empty");
            StatsdStats::getInstance().noteEmptyData(mPullTagId);
            return;
        }
        // For scheduled pulled data, the effective event time is snap to the nearest
        // bucket end. In the case of waking up from a deep sleep state, we will
        // attribute to the previous bucket end. If the sleep was long but not very long, we
        // will be in the immediate next bucket. Previous bucket may get a larger number as
        // we pull at a later time than real bucket end.
        // If the sleep was very long, we skip more than one bucket before sleep. In this case,
        // if the diff base will be cleared and this new data will serve as new diff base.
        int64_t realEventTime = allData.at(0)->GetElapsedTimestampNs();
        int64_t bucketEndTime = calcPreviousBucketEndTime(realEventTime) - 1;
        if (bucketEndTime < mCurrentBucketStartTimeNs) {
            VLOG("Skip bucket end pull due to late arrival: %lld vs %lld", (long long)bucketEndTime,
                 (long long)mCurrentBucketStartTimeNs);
            StatsdStats::getInstance().noteLateLogEventSkipped(mMetricId);
            return;
        }
        for (const auto& data : allData) {
            LogEvent localCopy = data->makeCopy();
            if (mEventMatcherWizard->matchLogEvent(localCopy, mWhatMatcherIndex) ==
                MatchingState::kMatched) {
                localCopy.setElapsedTimestampNs(bucketEndTime);
                onMatchedLogEventLocked(mWhatMatcherIndex, localCopy);
            }
        }
        mHasGlobalBase = true;
    } else {
        VLOG("No need to commit data on condition false.");
    }
}

void ValueMetricProducer::dumpStatesLocked(FILE* out, bool verbose) const {
    if (mCurrentSlicedBucket.size() == 0) {
        return;
    }

    fprintf(out, "ValueMetric %lld dimension size %lu\n", (long long)mMetricId,
            (unsigned long)mCurrentSlicedBucket.size());
    if (verbose) {
        for (const auto& it : mCurrentSlicedBucket) {
          for (const auto& interval : it.second) {
            fprintf(out, "\t(what)%s\t(condition)%s  (value)%s\n",
                    it.first.getDimensionKeyInWhat().toString().c_str(),
                    it.first.getDimensionKeyInCondition().toString().c_str(),
                    interval.value.toString().c_str());
          }
        }
    }
}

bool ValueMetricProducer::hitGuardRailLocked(const MetricDimensionKey& newKey) {
    // ===========GuardRail==============
    // 1. Report the tuple count if the tuple count > soft limit
    if (mCurrentSlicedBucket.find(newKey) != mCurrentSlicedBucket.end()) {
        return false;
    }
    if (mCurrentSlicedBucket.size() > mDimensionSoftLimit - 1) {
        size_t newTupleCount = mCurrentSlicedBucket.size() + 1;
        StatsdStats::getInstance().noteMetricDimensionSize(mConfigKey, mMetricId, newTupleCount);
        // 2. Don't add more tuples, we are above the allowed threshold. Drop the data.
        if (newTupleCount > mDimensionHardLimit) {
            ALOGE("ValueMetric %lld dropping data for dimension key %s", (long long)mMetricId,
                  newKey.toString().c_str());
            StatsdStats::getInstance().noteHardDimensionLimitReached(mMetricId);
            return true;
        }
    }

    return false;
}

bool ValueMetricProducer::hitFullBucketGuardRailLocked(const MetricDimensionKey& newKey) {
    // ===========GuardRail==============
    // 1. Report the tuple count if the tuple count > soft limit
    if (mCurrentFullBucket.find(newKey) != mCurrentFullBucket.end()) {
        return false;
    }
    if (mCurrentFullBucket.size() > mDimensionSoftLimit - 1) {
        size_t newTupleCount = mCurrentFullBucket.size() + 1;
        // 2. Don't add more tuples, we are above the allowed threshold. Drop the data.
        if (newTupleCount > mDimensionHardLimit) {
            ALOGE("ValueMetric %lld dropping data for full bucket dimension key %s",
                  (long long)mMetricId,
                  newKey.toString().c_str());
            return true;
        }
    }

    return false;
}

bool getDoubleOrLong(const LogEvent& event, const Matcher& matcher, Value& ret) {
    for (const FieldValue& value : event.getValues()) {
        if (value.mField.matches(matcher)) {
            switch (value.mValue.type) {
                case INT:
                    ret.setLong(value.mValue.int_value);
                    break;
                case LONG:
                    ret.setLong(value.mValue.long_value);
                    break;
                case FLOAT:
                    ret.setDouble(value.mValue.float_value);
                    break;
                case DOUBLE:
                    ret.setDouble(value.mValue.double_value);
                    break;
                default:
                    break;
            }
            return true;
        }
    }
    return false;
}

void ValueMetricProducer::onMatchedLogEventInternalLocked(const size_t matcherIndex,
                                                          const MetricDimensionKey& eventKey,
                                                          const ConditionKey& conditionKey,
                                                          bool condition, const LogEvent& event) {
    int64_t eventTimeNs = event.GetElapsedTimestampNs();
    if (eventTimeNs < mCurrentBucketStartTimeNs) {
        VLOG("Skip event due to late arrival: %lld vs %lld", (long long)eventTimeNs,
             (long long)mCurrentBucketStartTimeNs);
        return;
    }

    flushIfNeededLocked(eventTimeNs);

    // For pulled data, we already check condition when we decide to pull or
    // in onDataPulled. So take all of them.
    // For pushed data, just check condition.
    if (!(mIsPulled || condition)) {
        VLOG("ValueMetric skip event because condition is false");
        return;
    }

    if (hitGuardRailLocked(eventKey)) {
        return;
    }
    vector<Interval>& multiIntervals = mCurrentSlicedBucket[eventKey];
    if (multiIntervals.size() < mFieldMatchers.size()) {
        VLOG("Resizing number of intervals to %d", (int)mFieldMatchers.size());
        multiIntervals.resize(mFieldMatchers.size());
    }

    // We only use anomaly detection under certain cases.
    // N.B.: The anomaly detection cases were modified in order to fix an issue with value metrics
    // containing multiple values. We tried to retain all previous behaviour, but we are unsure the
    // previous behaviour was correct. At the time of the fix, anomaly detection had no owner.
    // Whoever next works on it should look into the cases where it is triggered in this function.
    // Discussion here: http://ag/6124370.
    bool useAnomalyDetection = true;

    for (int i = 0; i < (int)mFieldMatchers.size(); i++) {
        const Matcher& matcher = mFieldMatchers[i];
        Interval& interval = multiIntervals[i];
        interval.valueIndex = i;
        Value value;
        if (!getDoubleOrLong(event, matcher, value)) {
            VLOG("Failed to get value %d from event %s", i, event.ToString().c_str());
            StatsdStats::getInstance().noteBadValueType(mMetricId);
            return;
        }
        interval.seenNewData = true;

        if (mUseDiff) {
            if (!interval.hasBase) {
                if (mHasGlobalBase && mUseZeroDefaultBase) {
                    // The bucket has global base. This key does not.
                    // Optionally use zero as base.
                    interval.base = (value.type == LONG ? ZERO_LONG : ZERO_DOUBLE);
                    interval.hasBase = true;
                } else {
                    // no base. just update base and return.
                    interval.base = value;
                    interval.hasBase = true;
                    // If we're missing a base, do not use anomaly detection on incomplete data
                    useAnomalyDetection = false;
                    // Continue (instead of return) here in order to set interval.base and
                    // interval.hasBase for other intervals
                    continue;
                }
            }
            Value diff;
            switch (mValueDirection) {
                case ValueMetric::INCREASING:
                    if (value >= interval.base) {
                        diff = value - interval.base;
                    } else if (mUseAbsoluteValueOnReset) {
                        diff = value;
                    } else {
                        VLOG("Unexpected decreasing value");
                        StatsdStats::getInstance().notePullDataError(mPullTagId);
                        interval.base = value;
                        // If we've got bad data, do not use anomaly detection
                        useAnomalyDetection = false;
                        continue;
                    }
                    break;
                case ValueMetric::DECREASING:
                    if (interval.base >= value) {
                        diff = interval.base - value;
                    } else if (mUseAbsoluteValueOnReset) {
                        diff = value;
                    } else {
                        VLOG("Unexpected increasing value");
                        StatsdStats::getInstance().notePullDataError(mPullTagId);
                        interval.base = value;
                        // If we've got bad data, do not use anomaly detection
                        useAnomalyDetection = false;
                        continue;
                    }
                    break;
                case ValueMetric::ANY:
                    diff = value - interval.base;
                    break;
                default:
                    break;
            }
            interval.base = value;
            value = diff;
        }

        if (interval.hasValue) {
            switch (mAggregationType) {
                case ValueMetric::SUM:
                    // for AVG, we add up and take average when flushing the bucket
                case ValueMetric::AVG:
                    interval.value += value;
                    break;
                case ValueMetric::MIN:
                    interval.value = std::min(value, interval.value);
                    break;
                case ValueMetric::MAX:
                    interval.value = std::max(value, interval.value);
                    break;
                default:
                    break;
            }
        } else {
            interval.value = value;
            interval.hasValue = true;
        }
        interval.sampleSize += 1;
    }

    // Only trigger the tracker if all intervals are correct
    if (useAnomalyDetection) {
        // TODO: propgate proper values down stream when anomaly support doubles
        long wholeBucketVal = multiIntervals[0].value.long_value;
        auto prev = mCurrentFullBucket.find(eventKey);
        if (prev != mCurrentFullBucket.end()) {
            wholeBucketVal += prev->second;
        }
        for (auto& tracker : mAnomalyTrackers) {
            tracker->detectAndDeclareAnomaly(
                eventTimeNs, mCurrentBucketNum, eventKey, wholeBucketVal);
        }
    }
}

void ValueMetricProducer::flushIfNeededLocked(const int64_t& eventTimeNs) {
    int64_t currentBucketEndTimeNs = getCurrentBucketEndTimeNs();

    if (eventTimeNs < currentBucketEndTimeNs) {
        VLOG("eventTime is %lld, less than next bucket start time %lld", (long long)eventTimeNs,
             (long long)(currentBucketEndTimeNs));
        return;
    }

    flushCurrentBucketLocked(eventTimeNs);

    int64_t numBucketsForward = 1 + (eventTimeNs - currentBucketEndTimeNs) / mBucketSizeNs;
    mCurrentBucketStartTimeNs = currentBucketEndTimeNs + (numBucketsForward - 1) * mBucketSizeNs;
    mCurrentBucketNum += numBucketsForward;

    if (numBucketsForward > 1) {
        VLOG("Skipping forward %lld buckets", (long long)numBucketsForward);
        StatsdStats::getInstance().noteSkippedForwardBuckets(mMetricId);
        // take base again in future good bucket.
        resetBase();
    }
    VLOG("metric %lld: new bucket start time: %lld", (long long)mMetricId,
         (long long)mCurrentBucketStartTimeNs);
}

void ValueMetricProducer::flushCurrentBucketLocked(const int64_t& eventTimeNs) {
    VLOG("finalizing bucket for %ld, dumping %d slices", (long)mCurrentBucketStartTimeNs,
         (int)mCurrentSlicedBucket.size());
    int64_t fullBucketEndTimeNs = getCurrentBucketEndTimeNs();

    int64_t bucketEndTime = eventTimeNs < fullBucketEndTimeNs ? eventTimeNs : fullBucketEndTimeNs;

    if (bucketEndTime - mCurrentBucketStartTimeNs >= mMinBucketSizeNs) {
        // The current bucket is large enough to keep.
        for (const auto& slice : mCurrentSlicedBucket) {
            ValueBucket bucket;
            bucket.mBucketStartNs = mCurrentBucketStartTimeNs;
            bucket.mBucketEndNs = bucketEndTime;
            for (const auto& interval : slice.second) {
                if (interval.hasValue) {
                    // skip the output if the diff is zero
                    if (mSkipZeroDiffOutput && mUseDiff && interval.value.isZero()) {
                        continue;
                    }
                    bucket.valueIndex.push_back(interval.valueIndex);
                    if (mAggregationType != ValueMetric::AVG) {
                        bucket.values.push_back(interval.value);
                    } else {
                        double sum = interval.value.type == LONG ? (double)interval.value.long_value
                                                                 : interval.value.double_value;
                        bucket.values.push_back(Value((double)sum / interval.sampleSize));
                    }
                }
            }
            // it will auto create new vector of ValuebucketInfo if the key is not found.
            if (bucket.valueIndex.size() > 0) {
                auto& bucketList = mPastBuckets[slice.first];
                bucketList.push_back(bucket);
            }
        }
    } else {
        mSkippedBuckets.emplace_back(mCurrentBucketStartTimeNs, bucketEndTime);
    }

    if (eventTimeNs > fullBucketEndTimeNs) {  // If full bucket, send to anomaly tracker.
        // Accumulate partial buckets with current value and then send to anomaly tracker.
        if (mCurrentFullBucket.size() > 0) {
            for (const auto& slice : mCurrentSlicedBucket) {
                if (hitFullBucketGuardRailLocked(slice.first)) {
                    continue;
                }
                // TODO: fix this when anomaly can accept double values
                mCurrentFullBucket[slice.first] += slice.second[0].value.long_value;
            }
            for (const auto& slice : mCurrentFullBucket) {
                for (auto& tracker : mAnomalyTrackers) {
                    if (tracker != nullptr) {
                        tracker->addPastBucket(slice.first, slice.second, mCurrentBucketNum);
                    }
                }
            }
            mCurrentFullBucket.clear();
        } else {
            // Skip aggregating the partial buckets since there's no previous partial bucket.
            for (const auto& slice : mCurrentSlicedBucket) {
                for (auto& tracker : mAnomalyTrackers) {
                    if (tracker != nullptr) {
                        // TODO: fix this when anomaly can accept double values
                        tracker->addPastBucket(slice.first, slice.second[0].value.long_value,
                                               mCurrentBucketNum);
                    }
                }
            }
        }
    } else {
        // Accumulate partial bucket.
        for (const auto& slice : mCurrentSlicedBucket) {
            // TODO: fix this when anomaly can accept double values
            mCurrentFullBucket[slice.first] += slice.second[0].value.long_value;
        }
    }

    for (auto it = mCurrentSlicedBucket.begin(); it != mCurrentSlicedBucket.end();) {
        bool obsolete = true;
        for (auto& interval : it->second) {
            interval.hasValue = false;
            interval.sampleSize = 0;
            if (interval.seenNewData) {
                obsolete = false;
            }
            interval.seenNewData = false;
        }

        if (obsolete) {
            it = mCurrentSlicedBucket.erase(it);
        } else {
            it++;
        }
    }
}

size_t ValueMetricProducer::byteSizeLocked() const {
    size_t totalSize = 0;
    for (const auto& pair : mPastBuckets) {
        totalSize += pair.second.size() * kBucketSize;
    }
    return totalSize;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
