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
using android::util::FIELD_TYPE_FLOAT;
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
// for ValueMetricDataWrapper
const int FIELD_ID_DATA = 1;
// for ValueMetricData
const int FIELD_ID_DIMENSION_IN_WHAT = 1;
const int FIELD_ID_DIMENSION_IN_CONDITION = 2;
const int FIELD_ID_BUCKET_INFO = 3;
// for ValueBucketInfo
const int FIELD_ID_START_BUCKET_NANOS = 1;
const int FIELD_ID_END_BUCKET_NANOS = 2;
const int FIELD_ID_VALUE = 3;

// ValueMetric has a minimum bucket size of 10min so that we don't pull too frequently
ValueMetricProducer::ValueMetricProducer(const ConfigKey& key, const ValueMetric& metric,
                                         const int conditionIndex,
                                         const sp<ConditionWizard>& wizard, const int pullTagId,
                                         const int64_t startTimeNs,
                                         shared_ptr<StatsPullerManager> statsPullerManager)
    : MetricProducer(metric.id(), key, startTimeNs, conditionIndex, wizard),
      mValueField(metric.value_field()),
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
    // TODO: valuemetric for pushed events may need unlimited bucket length
    int64_t bucketSizeMills = 0;
    if (metric.has_bucket()) {
        bucketSizeMills = TimeUnitToBucketSizeInMillisGuardrailed(key.GetUid(), metric.bucket());
    } else {
        bucketSizeMills = TimeUnitToBucketSizeInMillis(ONE_HOUR);
    }

    mBucketSizeNs = bucketSizeMills * 1000000;
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

    if (mValueField.child_size()) {
        mField = mValueField.child(0).field();
    }
    mConditionSliced = (metric.links().size() > 0) || (mDimensionsInCondition.size() > 0);

    // Kicks off the puller immediately.
    if (mPullTagId != -1) {
        mStatsPullerManager->RegisterReceiver(
            mPullTagId, this, mCurrentBucketStartTimeNs + mBucketSizeNs, mBucketSizeNs);
    }

    VLOG("value metric %lld created. bucket size %lld start_time: %lld",
        (long long)metric.id(), (long long)mBucketSizeNs, (long long)mStartTimeNs);
}

// for testing
ValueMetricProducer::ValueMetricProducer(const ConfigKey& key, const ValueMetric& metric,
                                         const int conditionIndex,
                                         const sp<ConditionWizard>& wizard, const int pullTagId,
                                         const int64_t startTimeNs)
    : ValueMetricProducer(key, metric, conditionIndex, wizard, pullTagId, startTimeNs,
                          make_shared<StatsPullerManager>()) {
}

ValueMetricProducer::~ValueMetricProducer() {
    VLOG("~ValueMetricProducer() called");
    if (mPullTagId != -1) {
        mStatsPullerManager->UnRegisterReceiver(mPullTagId, this);
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

void ValueMetricProducer::onDumpReportLocked(const int64_t dumpTimeNs,
                                             const bool include_current_partial_bucket,
                                             ProtoOutputStream* protoOutput) {
    VLOG("metric %lld dump report now...", (long long)mMetricId);
    if (include_current_partial_bucket) {
        flushLocked(dumpTimeNs);
    } else {
        flushIfNeededLocked(dumpTimeNs);
    }
    if (mPastBuckets.empty()) {
        return;
    }
    protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_ID, (long long)mMetricId);
    uint64_t protoToken = protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_ID_VALUE_METRICS);

    for (const auto& pair : mPastBuckets) {
        const MetricDimensionKey& dimensionKey = pair.first;
        VLOG("  dimension key %s", dimensionKey.toString().c_str());
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

        // Then fill bucket_info (ValueBucketInfo).
        for (const auto& bucket : pair.second) {
            uint64_t bucketInfoToken = protoOutput->start(
                    FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_BUCKET_INFO);
            protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_START_BUCKET_NANOS,
                               (long long)bucket.mBucketStartNs);
            protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_END_BUCKET_NANOS,
                               (long long)bucket.mBucketEndNs);
            protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_VALUE, (long long)bucket.mValue);
            protoOutput->end(bucketInfoToken);
            VLOG("\t bucket [%lld - %lld] count: %lld", (long long)bucket.mBucketStartNs,
                 (long long)bucket.mBucketEndNs, (long long)bucket.mValue);
        }
        protoOutput->end(wrapperToken);
    }
    protoOutput->end(protoToken);

    VLOG("metric %lld dump report now...", (long long)mMetricId);
    mPastBuckets.clear();
    // TODO: Clear mDimensionKeyMap once the report is dumped.
}

void ValueMetricProducer::onConditionChangedLocked(const bool condition,
                                                   const int64_t eventTimeNs) {
    mCondition = condition;

    if (eventTimeNs < mCurrentBucketStartTimeNs) {
        VLOG("Skip event due to late arrival: %lld vs %lld", (long long)eventTimeNs,
             (long long)mCurrentBucketStartTimeNs);
        return;
    }

    flushIfNeededLocked(eventTimeNs);

    if (mPullTagId != -1) {
        vector<shared_ptr<LogEvent>> allData;
        if (mStatsPullerManager->Pull(mPullTagId, eventTimeNs, &allData)) {
            if (allData.size() == 0) {
                return;
            }
            for (const auto& data : allData) {
                onMatchedLogEventLocked(0, *data);
            }
        }
        return;
    }
}

void ValueMetricProducer::onDataPulled(const std::vector<std::shared_ptr<LogEvent>>& allData) {
    std::lock_guard<std::mutex> lock(mMutex);

    if (mCondition == true || mConditionTrackerIndex < 0) {
        if (allData.size() == 0) {
            return;
        }
        // For scheduled pulled data, the effective event time is snap to the nearest
        // bucket boundary to make bucket finalize.
        int64_t realEventTime = allData.at(0)->GetElapsedTimestampNs();
        int64_t eventTime = mStartTimeNs +
            ((realEventTime - mStartTimeNs) / mBucketSizeNs) * mBucketSizeNs;

        mCondition = false;
        for (const auto& data : allData) {
            data->setElapsedTimestampNs(eventTime - 1);
            onMatchedLogEventLocked(0, *data);
        }

        mCondition = true;
        for (const auto& data : allData) {
            data->setElapsedTimestampNs(eventTime);
            onMatchedLogEventLocked(0, *data);
        }
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
            fprintf(out, "\t(what)%s\t(condition)%s  (value)%lld\n",
                it.first.getDimensionKeyInWhat().toString().c_str(),
                it.first.getDimensionKeyInCondition().toString().c_str(),
                (unsigned long long)it.second.sum);
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
            ALOGE("ValueMetric %lld dropping data for dimension key %s",
                (long long)mMetricId, newKey.toString().c_str());
            return true;
        }
    }

    return false;
}

void ValueMetricProducer::onMatchedLogEventInternalLocked(
        const size_t matcherIndex, const MetricDimensionKey& eventKey,
        const ConditionKey& conditionKey, bool condition,
        const LogEvent& event) {
    int64_t eventTimeNs = event.GetElapsedTimestampNs();
    if (eventTimeNs < mCurrentBucketStartTimeNs) {
        VLOG("Skip event due to late arrival: %lld vs %lld", (long long)eventTimeNs,
             (long long)mCurrentBucketStartTimeNs);
        return;
    }

    flushIfNeededLocked(eventTimeNs);

    if (hitGuardRailLocked(eventKey)) {
        return;
    }
    Interval& interval = mCurrentSlicedBucket[eventKey];

    int error = 0;
    const long value = event.GetLong(mField, &error);
    if (error < 0) {
        return;
    }

    if (mPullTagId != -1) { // for pulled events
        if (mCondition == true) {
            if (!interval.startUpdated) {
                interval.start = value;
                interval.startUpdated = true;
            } else {
                // skip it if there is already value recorded for the start
                VLOG("Already recorded value for this dimension %s", eventKey.toString().c_str());
            }
        } else {
            // Generally we expect value to be monotonically increasing.
            // If not, there was a reset event. We take the absolute value as
            // diff in this case.
            if (interval.startUpdated) {
                if (value > interval.start) {
                    interval.sum += (value - interval.start);
                } else {
                    interval.sum += value;
                }
                interval.hasValue = true;
                interval.startUpdated = false;
            } else {
                VLOG("No start for matching end %ld", value);
                interval.tainted += 1;
            }
        }
    } else {    // for pushed events
        interval.sum += value;
        interval.hasValue = true;
    }

    long wholeBucketVal = interval.sum;
    auto prev = mCurrentFullBucket.find(eventKey);
    if (prev != mCurrentFullBucket.end()) {
        wholeBucketVal += prev->second;
    }
    for (auto& tracker : mAnomalyTrackers) {
        tracker->detectAndDeclareAnomaly(eventTimeNs, mCurrentBucketNum, eventKey, wholeBucketVal);
    }
}

void ValueMetricProducer::flushIfNeededLocked(const int64_t& eventTimeNs) {
    int64_t currentBucketEndTimeNs = getCurrentBucketEndTimeNs();

    if (currentBucketEndTimeNs > eventTimeNs) {
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
    }
    VLOG("metric %lld: new bucket start time: %lld", (long long)mMetricId,
         (long long)mCurrentBucketStartTimeNs);
}

void ValueMetricProducer::flushCurrentBucketLocked(const int64_t& eventTimeNs) {
    VLOG("finalizing bucket for %ld, dumping %d slices", (long)mCurrentBucketStartTimeNs,
         (int)mCurrentSlicedBucket.size());
    int64_t fullBucketEndTimeNs = getCurrentBucketEndTimeNs();

    ValueBucket info;
    info.mBucketStartNs = mCurrentBucketStartTimeNs;
    if (eventTimeNs < fullBucketEndTimeNs) {
        info.mBucketEndNs = eventTimeNs;
    } else {
        info.mBucketEndNs = fullBucketEndTimeNs;
    }

    int tainted = 0;
    for (const auto& slice : mCurrentSlicedBucket) {
        tainted += slice.second.tainted;
        tainted += slice.second.startUpdated;
        if (slice.second.hasValue) {
            info.mValue = slice.second.sum;
            // it will auto create new vector of ValuebucketInfo if the key is not found.
            auto& bucketList = mPastBuckets[slice.first];
            bucketList.push_back(info);
        }
    }
    VLOG("%d tainted pairs in the bucket", tainted);

    if (eventTimeNs > fullBucketEndTimeNs) {  // If full bucket, send to anomaly tracker.
        // Accumulate partial buckets with current value and then send to anomaly tracker.
        if (mCurrentFullBucket.size() > 0) {
            for (const auto& slice : mCurrentSlicedBucket) {
                mCurrentFullBucket[slice.first] += slice.second.sum;
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
                        tracker->addPastBucket(slice.first, slice.second.sum, mCurrentBucketNum);
                    }
                }
            }
        }
    } else {
        // Accumulate partial bucket.
        for (const auto& slice : mCurrentSlicedBucket) {
            mCurrentFullBucket[slice.first] += slice.second.sum;
        }
    }

    // Reset counters
    mCurrentSlicedBucket.clear();
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
