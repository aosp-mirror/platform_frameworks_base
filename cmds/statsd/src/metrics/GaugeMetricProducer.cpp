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

#include "GaugeMetricProducer.h"
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

GaugeMetricProducer::GaugeMetricProducer(const GaugeMetric& metric, const int conditionIndex,
                                         const sp<ConditionWizard>& wizard, const int pullTagId)
    : MetricProducer((time(nullptr) * NANO_SECONDS_IN_A_SECOND), conditionIndex, wizard),
      mMetric(metric),
      mPullTagId(pullTagId) {
    if (metric.has_bucket() && metric.bucket().has_bucket_size_millis()) {
        mBucketSizeNs = metric.bucket().bucket_size_millis() * 1000 * 1000;
    } else {
        mBucketSizeNs = kDefaultGaugemBucketSizeNs;
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
        mStatsPullerManager.RegisterReceiver(mPullTagId, this,
                                             metric.bucket().bucket_size_millis());
    }

    VLOG("metric %lld created. bucket size %lld start_time: %lld", metric.metric_id(),
         (long long)mBucketSizeNs, (long long)mStartTimeNs);
}

GaugeMetricProducer::~GaugeMetricProducer() {
    VLOG("~GaugeMetricProducer() called");
}

void GaugeMetricProducer::finish() {
}

static void addSlicedGaugeToReport(const vector<KeyValuePair>& key,
                                   const vector<GaugeBucketInfo>& buckets,
                                   StatsLogReport_GaugeMetricDataWrapper& wrapper) {
    GaugeMetricData* data = wrapper.add_data();
    for (const auto& kv : key) {
        data->add_dimension()->CopyFrom(kv);
    }
    for (const auto& bucket : buckets) {
        data->add_bucket_info()->CopyFrom(bucket);
        VLOG("\t bucket [%lld - %lld] gauge: %lld", bucket.start_bucket_nanos(),
             bucket.end_bucket_nanos(), bucket.gauge());
    }
}

StatsLogReport GaugeMetricProducer::onDumpReport() {
    VLOG("gauge metric %lld dump report now...", mMetric.metric_id());

    StatsLogReport report;
    report.set_metric_id(mMetric.metric_id());
    report.set_start_report_nanos(mStartTimeNs);

    // Dump current bucket if it's stale.
    // If current bucket is still on-going, don't force dump current bucket.
    // In finish(), We can force dump current bucket.
    flushGaugeIfNeededLocked(time(nullptr) * NANO_SECONDS_IN_A_SECOND);
    report.set_end_report_nanos(mCurrentBucketStartTimeNs);

    StatsLogReport_GaugeMetricDataWrapper* wrapper = report.mutable_gauge_metrics();

    for (const auto& pair : mPastBuckets) {
        const HashableDimensionKey& hashableKey = pair.first;
        auto it = mDimensionKeyMap.find(hashableKey);
        if (it == mDimensionKeyMap.end()) {
            ALOGE("Dimension key %s not found?!?! skip...", hashableKey.c_str());
            continue;
        }

        VLOG("  dimension key %s", hashableKey.c_str());
        addSlicedGaugeToReport(it->second, pair.second, *wrapper);
    }
    return report;
    // TODO: Clear mPastBuckets, mDimensionKeyMap once the report is dumped.
}

void GaugeMetricProducer::onConditionChanged(const bool conditionMet, const uint64_t eventTime) {
    AutoMutex _l(mLock);
    VLOG("Metric %lld onConditionChanged", mMetric.metric_id());
    mCondition = conditionMet;

    // Push mode. Nothing to do.
    if (mPullTagId == -1) {
        return;
    }
    // If (1) the condition is not met or (2) we already pulled the gauge metric in the current
    // bucket, do not pull gauge again.
    if (!mCondition || mCurrentSlicedBucket.size() > 0) {
        return;
    }
    vector<std::shared_ptr<LogEvent>> allData;
    if (!mStatsPullerManager.Pull(mPullTagId, &allData)) {
        ALOGE("Stats puller failed for tag: %d", mPullTagId);
        return;
    }
    for (const auto& data : allData) {
        onMatchedLogEvent(0, *data, false /*scheduledPull*/);
    }
    flushGaugeIfNeededLocked(eventTime);
}

void GaugeMetricProducer::onSlicedConditionMayChange(const uint64_t eventTime) {
    VLOG("Metric %lld onSlicedConditionMayChange", mMetric.metric_id());
}

long GaugeMetricProducer::getGauge(const LogEvent& event) {
    status_t err = NO_ERROR;
    long val = event.GetLong(mMetric.gauge_field(), &err);
    if (err == NO_ERROR) {
        return val;
    } else {
        VLOG("Can't find value in message.");
        return -1;
    }
}

void GaugeMetricProducer::onDataPulled(const std::vector<std::shared_ptr<LogEvent>>& allData) {
    AutoMutex mutex(mLock);
    if (allData.size() == 0) {
        return;
    }
    for (const auto& data : allData) {
        onMatchedLogEvent(0, *data, true /*scheduledPull*/);
    }
    uint64_t eventTime = allData.at(0)->GetTimestampNs();
    flushGaugeIfNeededLocked(eventTime);
}

void GaugeMetricProducer::onMatchedLogEventInternal(
        const size_t matcherIndex, const HashableDimensionKey& eventKey,
        const map<string, HashableDimensionKey>& conditionKey, bool condition,
        const LogEvent& event, bool scheduledPull) {
    if (condition == false) {
        return;
    }
    uint64_t eventTimeNs = event.GetTimestampNs();
    if (eventTimeNs < mCurrentBucketStartTimeNs) {
        VLOG("Skip event due to late arrival: %lld vs %lld", (long long)eventTimeNs,
             (long long)mCurrentBucketStartTimeNs);
        return;
    }

    // For gauge metric, we just simply use the latest guage in the given bucket.
    const long gauge = getGauge(event);
    if (gauge < 0) {
        VLOG("Invalid gauge at event Time: %lld", (long long)eventTimeNs);
        return;
    }
    mCurrentSlicedBucket[eventKey] = gauge;
    if (mPullTagId < 0) {
        flushGaugeIfNeededLocked(eventTimeNs);
    }
}

// When a new matched event comes in, we check if event falls into the current
// bucket. If not, flush the old counter to past buckets and initialize the new
// bucket.
// if data is pushed, onMatchedLogEvent will only be called through onConditionChanged() inside
// the GaugeMetricProducer while holding the lock.
void GaugeMetricProducer::flushGaugeIfNeededLocked(const uint64_t eventTimeNs) {
    if (mCurrentBucketStartTimeNs + mBucketSizeNs > eventTimeNs) {
        VLOG("event time is %lld, less than next bucket start time %lld", (long long)eventTimeNs,
             (long long)(mCurrentBucketStartTimeNs + mBucketSizeNs));
        return;
    }

    // Adjusts the bucket start time
    int64_t numBucketsForward = (eventTimeNs - mCurrentBucketStartTimeNs) / mBucketSizeNs;

    GaugeBucketInfo info;
    info.set_start_bucket_nanos(mCurrentBucketStartTimeNs);
    info.set_end_bucket_nanos(mCurrentBucketStartTimeNs + mBucketSizeNs);

    for (const auto& slice : mCurrentSlicedBucket) {
        info.set_gauge(slice.second);
        auto& bucketList = mPastBuckets[slice.first];
        bucketList.push_back(info);

        VLOG("gauge metric %lld, dump key value: %s -> %ld", mMetric.metric_id(),
             slice.first.c_str(), slice.second);
    }
    // Reset counters
    mCurrentSlicedBucket.clear();

    mCurrentBucketStartTimeNs = mCurrentBucketStartTimeNs + numBucketsForward * mBucketSizeNs;
    VLOG("metric %lld: new bucket start time: %lld", mMetric.metric_id(),
         (long long)mCurrentBucketStartTimeNs);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
