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

#define LOG_TAG "CountMetric"
#define DEBUG true  // STOPSHIP if true
#define VLOG(...) \
    if (DEBUG) ALOGD(__VA_ARGS__);

#include "CountMetricProducer.h"
#include "CountAnomalyTracker.h"

#include <cutils/log.h>
#include <limits.h>
#include <stdlib.h>

using std::unordered_map;

namespace android {
namespace os {
namespace statsd {

CountMetricProducer::CountMetricProducer(const CountMetric& metric, const bool hasCondition)
    : mMetric(metric),
      mStartTime(time(nullptr)),
      mCounter(0),
      mCurrentBucketStartTime(mStartTime),
      // TODO: read mAnomalyTracker parameters from config file.
      mAnomalyTracker(6, 10),
      mCondition(hasCondition ? ConditionState::kUnknown : ConditionState::kTrue) {
    // TODO: evaluate initial conditions. and set mConditionMet.
    if (metric.has_bucket() && metric.bucket().has_bucket_size_millis()) {
        mBucketSize_sec = metric.bucket().bucket_size_millis() / 1000;
    } else {
        mBucketSize_sec = LONG_MAX;
    }

    VLOG("created. bucket size %lu start_time: %lu", mBucketSize_sec, mStartTime);
}

CountMetricProducer::~CountMetricProducer() {
    VLOG("~CountMetricProducer() called");
}

void CountMetricProducer::finish() {
    // TODO: write the StatsLogReport to dropbox using
    // DropboxWriter.
}

void CountMetricProducer::onDumpReport() {
    VLOG("dump report now...");
}

void CountMetricProducer::onConditionChanged(const bool conditionMet) {
    VLOG("onConditionChanged");
    mCondition = conditionMet;
}

void CountMetricProducer::onMatchedLogEvent(const LogEventWrapper& event) {
    time_t eventTime = event.timestamp_ns / 1000000000;

    // this is old event, maybe statsd restarted?
    if (eventTime < mStartTime) {
        return;
    }

    if (mCondition == ConditionState::kTrue) {
        flushCounterIfNeeded(eventTime);
        mCounter++;
        mAnomalyTracker.checkAnomaly(mCounter);
        VLOG("metric %lld count %d", mMetric.metric_id(), mCounter);
    }
}

// When a new matched event comes in, we check if it falls into the current
// bucket. And flush the counter to the StatsLogReport and adjust the bucket if
// needed.
void CountMetricProducer::flushCounterIfNeeded(const time_t& eventTime) {
    if (mCurrentBucketStartTime + mBucketSize_sec > eventTime) {
        return;
    }

    // TODO: add a KeyValuePair to StatsLogReport.
    ALOGD("%lld:  dump counter %d", mMetric.metric_id(), mCounter);

    // adjust the bucket start time
    time_t numBucketsForward = (eventTime - mCurrentBucketStartTime)
            / mBucketSize_sec;

    mCurrentBucketStartTime = mCurrentBucketStartTime +
            (numBucketsForward) * mBucketSize_sec;

    // reset counter
    mAnomalyTracker.addPastBucket(mCounter, numBucketsForward);
    mCounter = 0;

    VLOG("%lld: new bucket start time: %lu", mMetric.metric_id(), mCurrentBucketStartTime);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
