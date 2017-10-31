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

using std::string;
using std::unordered_map;
using std::vector;

namespace android {
namespace os {
namespace statsd {

DurationMetricProducer::DurationMetricProducer(const DurationMetric& metric,
                                               const int conditionIndex, const size_t startIndex,
                                               const size_t stopIndex, const size_t stopAllIndex,
                                               const sp<ConditionWizard>& wizard,
                                               const vector<KeyMatcher>& internalDimension)
    // TODO: Pass in the start time from MetricsManager, instead of calling time() here.
    : MetricProducer(time(nullptr) * NANO_SECONDS_IN_A_SECOND, conditionIndex, wizard),
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

    VLOG("metric %lld created. bucket size %lld start_time: %lld", metric.metric_id(),
         (long long)mBucketSizeNs, (long long)mStartTimeNs);
}

DurationMetricProducer::~DurationMetricProducer() {
    VLOG("~DurationMetric() called");
}

unique_ptr<DurationTracker> DurationMetricProducer::createDurationTracker(
        vector<DurationBucketInfo>& bucket) {
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
    VLOG("Metric %lld onSlicedConditionMayChange", mMetric.metric_id());
    // Now for each of the on-going event, check if the condition has changed for them.
    flushIfNeeded(eventTime);
    for (auto& pair : mCurrentSlicedDuration) {
        pair.second->onSlicedConditionMayChange(eventTime);
    }
}

void DurationMetricProducer::onConditionChanged(const bool conditionMet, const uint64_t eventTime) {
    VLOG("Metric %lld onConditionChanged", mMetric.metric_id());
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

StatsLogReport DurationMetricProducer::onDumpReport() {
    VLOG("metric %lld dump report now...", mMetric.metric_id());
    StatsLogReport report;
    report.set_metric_id(mMetric.metric_id());
    report.set_start_report_nanos(mStartTimeNs);
    // Dump current bucket if it's stale.
    // If current bucket is still on-going, don't force dump current bucket.
    // In finish(), We can force dump current bucket.
    flushIfNeeded(time(nullptr) * NANO_SECONDS_IN_A_SECOND);
    report.set_end_report_nanos(mCurrentBucketStartTimeNs);

    StatsLogReport_DurationMetricDataWrapper* wrapper = report.mutable_duration_metrics();
    for (const auto& pair : mPastBuckets) {
        const HashableDimensionKey& hashableKey = pair.first;
        auto it = mDimensionKeyMap.find(hashableKey);
        if (it == mDimensionKeyMap.end()) {
            ALOGW("Dimension key %s not found?!?! skip...", hashableKey.c_str());
            continue;
        }
        VLOG("  dimension key %s", hashableKey.c_str());
        addDurationBucketsToReport(*wrapper, it->second, pair.second);
    }
    return report;
};

void DurationMetricProducer::flushIfNeeded(uint64_t eventTime) {
    if (mCurrentBucketStartTimeNs + mBucketSizeNs > eventTime) {
        return;
    }

    VLOG("flushing...........");
    for (auto it = mCurrentSlicedDuration.begin(); it != mCurrentSlicedDuration.end(); ++it) {
        if (it->second->flushIfNeeded(eventTime)) {
            VLOG("erase bucket for key %s", it->first.c_str());
            mCurrentSlicedDuration.erase(it);
        }
    }

    int numBucketsForward = (eventTime - mCurrentBucketStartTimeNs) / mBucketSizeNs;
    mCurrentBucketStartTimeNs += numBucketsForward * mBucketSizeNs;
}

void DurationMetricProducer::onMatchedLogEventInternal(
        const size_t matcherIndex, const HashableDimensionKey& eventKey,
        const map<string, HashableDimensionKey>& conditionKeys, bool condition,
        const LogEvent& event) {
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
// TODO: return actual proto size when ProtoOutputStream is ready for use for
// DurationMetricsProducer.
//    return mProto->size();
  return 0;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
