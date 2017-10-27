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

#include "CountAnomalyTracker.h"
#include "CountMetricProducer.h"
#include "stats_util.h"

#include <cutils/log.h>
#include <limits.h>
#include <stdlib.h>

using std::map;
using std::string;
using std::unordered_map;
using std::vector;

namespace android {
namespace os {
namespace statsd {

// TODO: add back AnomalyTracker.
CountMetricProducer::CountMetricProducer(const CountMetric& metric, const int conditionIndex,
                                         const sp<ConditionWizard>& wizard)
    // TODO: Pass in the start time from MetricsManager, instead of calling time() here.
    : MetricProducer((time(nullptr) * NANO_SECONDS_IN_A_SECOND), conditionIndex, wizard),
      mMetric(metric) {
    // TODO: evaluate initial conditions. and set mConditionMet.
    if (metric.has_bucket() && metric.bucket().has_bucket_size_millis()) {
        mBucketSizeNs = metric.bucket().bucket_size_millis() * 1000 * 1000;
    } else {
        mBucketSizeNs = LLONG_MAX;
    }

    mAnomalyTrackers.reserve(metric.alerts_size());
    for (int i = 0; i < metric.alerts_size(); i++) {
        const Alert& alert = metric.alerts(i);
        if (alert.trigger_if_sum_gt() > 0 && alert.number_of_buckets() > 0) {
            mAnomalyTrackers.push_back(std::make_unique<CountAnomalyTracker>(alert));
        } else {
            ALOGW("Ignoring invalid count metric alert: threshold=%lld num_buckets= %d",
                  alert.trigger_if_sum_gt(), alert.number_of_buckets());
        }
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

CountMetricProducer::~CountMetricProducer() {
    VLOG("~CountMetricProducer() called");
}

void CountMetricProducer::finish() {
    // TODO: write the StatsLogReport to dropbox using
    // DropboxWriter.
}

static void addSlicedCounterToReport(StatsLogReport_CountMetricDataWrapper& wrapper,
                                     const vector<KeyValuePair>& key,
                                     const vector<CountBucketInfo>& buckets) {
    CountMetricData* data = wrapper.add_data();
    for (const auto& kv : key) {
        data->add_dimension()->CopyFrom(kv);
    }
    for (const auto& bucket : buckets) {
        data->add_bucket_info()->CopyFrom(bucket);
        VLOG("\t bucket [%lld - %lld] count: %lld", bucket.start_bucket_nanos(),
             bucket.end_bucket_nanos(), bucket.count());
    }
}

void CountMetricProducer::onSlicedConditionMayChange() {
    VLOG("Metric %lld onSlicedConditionMayChange", mMetric.metric_id());
}

StatsLogReport CountMetricProducer::onDumpReport() {
    VLOG("metric %lld dump report now...", mMetric.metric_id());

    StatsLogReport report;
    report.set_metric_id(mMetric.metric_id());
    report.set_start_report_nanos(mStartTimeNs);

    // Dump current bucket if it's stale.
    // If current bucket is still on-going, don't force dump current bucket.
    // In finish(), We can force dump current bucket.
    flushCounterIfNeeded(time(nullptr) * NANO_SECONDS_IN_A_SECOND);
    report.set_end_report_nanos(mCurrentBucketStartTimeNs);

    StatsLogReport_CountMetricDataWrapper* wrapper = report.mutable_count_metrics();

    for (const auto& pair : mPastBuckets) {
        const HashableDimensionKey& hashableKey = pair.first;
        auto it = mDimensionKeyMap.find(hashableKey);
        if (it == mDimensionKeyMap.end()) {
            ALOGE("Dimension key %s not found?!?! skip...", hashableKey.c_str());
            continue;
        }

        VLOG("  dimension key %s", hashableKey.c_str());
        addSlicedCounterToReport(*wrapper, it->second, pair.second);
    }
    return report;
    // TODO: Clear mPastBuckets, mDimensionKeyMap once the report is dumped.
}

void CountMetricProducer::onConditionChanged(const bool conditionMet) {
    VLOG("Metric %lld onConditionChanged", mMetric.metric_id());
    mCondition = conditionMet;
}

void CountMetricProducer::onMatchedLogEventInternal(
        const size_t matcherIndex, const HashableDimensionKey& eventKey,
        const map<string, HashableDimensionKey>& conditionKey, bool condition,
        const LogEvent& event) {
    uint64_t eventTimeNs = event.GetTimestampNs();

    flushCounterIfNeeded(eventTimeNs);

    if (condition == false) {
        return;
    }

    auto it = mCurrentSlicedCounter.find(eventKey);

    if (it == mCurrentSlicedCounter.end()) {
        // create a counter for the new key
        mCurrentSlicedCounter[eventKey] = 1;

    } else {
        // increment the existing value
        auto& count = it->second;
        count++;
    }

    // TODO: Re-add anomaly detection (similar to):
    // for (auto& tracker : mAnomalyTrackers) {
    //     tracker->checkAnomaly(mCounter);
    // }

    VLOG("metric %lld %s->%d", mMetric.metric_id(), eventKey.c_str(),
         mCurrentSlicedCounter[eventKey]);
}

// When a new matched event comes in, we check if event falls into the current
// bucket. If not, flush the old counter to past buckets and initialize the new bucket.
void CountMetricProducer::flushCounterIfNeeded(const uint64_t eventTimeNs) {
    if (mCurrentBucketStartTimeNs + mBucketSizeNs > eventTimeNs) {
        return;
    }

    // adjust the bucket start time
    int64_t numBucketsForward = (eventTimeNs - mCurrentBucketStartTimeNs) / mBucketSizeNs;

    CountBucketInfo info;
    info.set_start_bucket_nanos(mCurrentBucketStartTimeNs);
    info.set_end_bucket_nanos(mCurrentBucketStartTimeNs + mBucketSizeNs);

    for (const auto& counter : mCurrentSlicedCounter) {
        info.set_count(counter.second);
        // it will auto create new vector of CountbucketInfo if the key is not found.
        auto& bucketList = mPastBuckets[counter.first];
        bucketList.push_back(info);

        VLOG("metric %lld, dump key value: %s -> %d", mMetric.metric_id(), counter.first.c_str(),
             counter.second);
    }

    // TODO: Re-add anomaly detection (similar to):
    // for (auto& tracker : mAnomalyTrackers) {
    //     tracker->addPastBucket(mCounter, numBucketsForward);
    //}

    // Reset counters
    mCurrentSlicedCounter.clear();

    mCurrentBucketStartTimeNs = mCurrentBucketStartTimeNs + numBucketsForward * mBucketSizeNs;
    VLOG("metric %lld: new bucket start time: %lld", mMetric.metric_id(),
         (long long)mCurrentBucketStartTimeNs);
}

size_t CountMetricProducer::byteSize() {
// TODO: return actual proto size when ProtoOutputStream is ready for use for
// CountMetricsProducer.
//    return mProto->size();
    return 0;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
