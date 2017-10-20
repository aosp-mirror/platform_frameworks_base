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
      mMetric(metric),
      // TODO: read mAnomalyTracker parameters from config file.
      mAnomalyTracker(6, 10) {
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

void CountMetricProducer::onMatchedLogEvent(const size_t matcherIndex, const LogEvent& event) {
    uint64_t eventTimeNs = event.GetTimestampNs();
    // this is old event, maybe statsd restarted?
    if (eventTimeNs < mStartTimeNs) {
        return;
    }

    flushCounterIfNeeded(eventTimeNs);

    if (mConditionSliced) {
        map<string, HashableDimensionKey> conditionKeys;
        for (const auto& link : mConditionLinks) {
            VLOG("Condition link key_in_main size %d", link.key_in_main_size());
            HashableDimensionKey conditionKey = getDimensionKeyForCondition(event, link);
            conditionKeys[link.condition()] = conditionKey;
        }
        if (mWizard->query(mConditionTrackerIndex, conditionKeys) != ConditionState::kTrue) {
            VLOG("metric %lld sliced condition not met", mMetric.metric_id());
            return;
        }
    } else {
        if (!mCondition) {
            VLOG("metric %lld condition not met", mMetric.metric_id());
            return;
        }
    }

    HashableDimensionKey hashableKey;

    if (mDimension.size() > 0) {
        vector<KeyValuePair> key = getDimensionKey(event, mDimension);
        hashableKey = getHashableKey(key);
        // Add the HashableDimensionKey->vector<KeyValuePair> to the map, because StatsLogReport
        // expects vector<KeyValuePair>.
        if (mDimensionKeyMap.find(hashableKey) == mDimensionKeyMap.end()) {
            mDimensionKeyMap[hashableKey] = key;
        }
    } else {
        hashableKey = DEFAULT_DIMENSION_KEY;
    }

    auto it = mCurrentSlicedCounter.find(hashableKey);

    if (it == mCurrentSlicedCounter.end()) {
        // create a counter for the new key
        mCurrentSlicedCounter[hashableKey] = 1;

    } else {
        // increment the existing value
        auto& count = it->second;
        count++;
    }

    VLOG("metric %lld %s->%d", mMetric.metric_id(), hashableKey.c_str(),
         mCurrentSlicedCounter[hashableKey]);
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

    // Reset counters
    mCurrentSlicedCounter.clear();

    mCurrentBucketStartTimeNs = mCurrentBucketStartTimeNs + numBucketsForward * mBucketSizeNs;
    VLOG("metric %lld: new bucket start time: %lld", mMetric.metric_id(),
         (long long)mCurrentBucketStartTimeNs);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
