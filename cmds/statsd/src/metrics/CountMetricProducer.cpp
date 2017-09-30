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
#include "parse_util.h"

#include <cutils/log.h>
#include <limits.h>
#include <stdlib.h>

using std::unordered_map;

namespace android {
namespace os {
namespace statsd {

CountMetricProducer::CountMetricProducer(const CountMetric& metric,
                                         const sp<ConditionTracker> condition)
    : mMetric(metric),
      mConditionTracker(condition),
      mStartTime(std::time(nullptr)),
      mCounter(0),
      mCurrentBucketStartTime(mStartTime) {
    // TODO: evaluate initial conditions. and set mConditionMet.
    if (metric.has_bucket() && metric.bucket().has_bucket_size_millis()) {
        mBucketSize_sec = metric.bucket().bucket_size_millis() / 1000;
    } else {
        mBucketSize_sec = LONG_MAX;
    }

    VLOG("created. bucket size %lu start_time: %lu", mBucketSize_sec, mStartTime);
}

CountMetricProducer::CountMetricProducer(const CountMetric& metric)
    : CountMetricProducer(metric, new ConditionTracker()) {
}

CountMetricProducer::~CountMetricProducer() {
    VLOG("~CountMetricProducer() called");
}

void CountMetricProducer::finish() {
    // TODO: write the StatsLogReport to dropbox using
    // DropboxWriter.
    onDumpReport();
}

void CountMetricProducer::onDumpReport() {
    VLOG("dump report now...");
}

void CountMetricProducer::onMatchedLogEvent(const LogEventWrapper& event) {
    time_t eventTime = event.timestamp_ns / 1000000000;

    // this is old event, maybe statsd restarted?
    if (eventTime < mStartTime) {
        return;
    }

    if (mConditionTracker->isConditionMet()) {
        flushCounterIfNeeded(eventTime);
        mCounter++;
    }
}

// When a new matched event comes in, we check if it falls into the current bucket. And flush the
// counter to the StatsLogReport and adjust the bucket if needed.
void CountMetricProducer::flushCounterIfNeeded(const time_t& eventTime) {
    if (mCurrentBucketStartTime + mBucketSize_sec > eventTime) {
        return;
    }

    // TODO: add a KeyValuePair to StatsLogReport.
    ALOGD("CountMetric: dump counter %d", mCounter);

    // reset counter
    mCounter = 0;

    // adjust the bucket start time
    mCurrentBucketStartTime =
            mCurrentBucketStartTime +
            ((eventTime - mCurrentBucketStartTime) / mBucketSize_sec) * mBucketSize_sec;

    VLOG("new bucket start time: %lu", mCurrentBucketStartTime);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
