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

#include "EventMetricProducer.h"
#include "stats_util.h"

#include <limits.h>
#include <stdlib.h>

using android::util::ProtoOutputStream;
using std::map;
using std::string;
using std::unordered_map;
using std::vector;

namespace android {
namespace os {
namespace statsd {

// for StatsLogReport
const int FIELD_ID_METRIC_ID = 1;
const int FIELD_ID_START_REPORT_NANOS = 2;
const int FIELD_ID_END_REPORT_NANOS = 2;
const int FIELD_ID_EVENT_METRICS = 4;
// for EventMetricData
const int FIELD_ID_TIMESTAMP_NANOS = 1;
const int FIELD_ID_STATS_EVENTS = 2;
// for CountMetricDataWrapper
const int FIELD_ID_DATA = 1;

EventMetricProducer::EventMetricProducer(const EventMetric& metric, const int conditionIndex,
                                         const sp<ConditionWizard>& wizard)
    // TODO: Pass in the start time from MetricsManager, instead of calling time() here.
    : MetricProducer((time(nullptr) * NANO_SECONDS_IN_A_SECOND), conditionIndex, wizard),
      mMetric(metric) {
    if (metric.links().size() > 0) {
        mConditionLinks.insert(mConditionLinks.begin(), metric.links().begin(),
                               metric.links().end());
        mConditionSliced = true;
    }

    startNewProtoOutputStream(mStartTimeNs);

    VLOG("metric %lld created. bucket size %lld start_time: %lld", metric.metric_id(),
         (long long)mBucketSizeNs, (long long)mStartTimeNs);
}

EventMetricProducer::~EventMetricProducer() {
    VLOG("~EventMetricProducer() called");
}

void EventMetricProducer::startNewProtoOutputStream(long long startTime) {
    mProto = std::make_unique<ProtoOutputStream>();
    // TODO: We need to auto-generate the field IDs for StatsLogReport, EventMetricData,
    // and StatsEvent.
    mProto->write(TYPE_INT32 + FIELD_ID_METRIC_ID, mMetric.metric_id());
    mProto->write(TYPE_INT64 + FIELD_ID_START_REPORT_NANOS, startTime);
    mProtoToken = mProto->start(TYPE_MESSAGE + FIELD_ID_EVENT_METRICS);
}

void EventMetricProducer::finish() {
}

void EventMetricProducer::onSlicedConditionMayChange(const uint64_t eventTime) {
}

StatsLogReport EventMetricProducer::onDumpReport() {
    long long endTime = time(nullptr) * NANO_SECONDS_IN_A_SECOND;
    mProto->end(mProtoToken);
    mProto->write(TYPE_INT64 + FIELD_ID_END_REPORT_NANOS, endTime);

    size_t bufferSize = mProto->size();
    VLOG("metric %lld dump report now... proto size: %zu ", mMetric.metric_id(), bufferSize);
    std::unique_ptr<uint8_t[]> buffer(new uint8_t[bufferSize]);
    size_t pos = 0;
    auto it = mProto->data();
    while (it.readBuffer() != NULL) {
        size_t toRead = it.currentToRead();
        std::memcpy(&buffer[pos], it.readBuffer(), toRead);
        pos += toRead;
        it.rp()->move(toRead);
    }

    startNewProtoOutputStream(endTime);

    // TODO: Once we migrate all MetricProducers to use ProtoOutputStream, we should return this:
    // return std::move(buffer);
    return StatsLogReport();
}

void EventMetricProducer::onConditionChanged(const bool conditionMet, const uint64_t eventTime) {
    VLOG("Metric %lld onConditionChanged", mMetric.metric_id());
    mCondition = conditionMet;
}

void EventMetricProducer::onMatchedLogEventInternal(
        const size_t matcherIndex, const HashableDimensionKey& eventKey,
        const std::map<std::string, HashableDimensionKey>& conditionKey, bool condition,
        const LogEvent& event) {

    if (!condition) {
        return;
    }

    long long wrapperToken = mProto->start(TYPE_MESSAGE + FIELD_ID_DATA);
    mProto->write(TYPE_INT64 + FIELD_ID_TIMESTAMP_NANOS, (long long)event.GetTimestampNs());
    long long eventToken = mProto->start(TYPE_MESSAGE + FIELD_ID_STATS_EVENTS);
    event.ToProto(*mProto);
    mProto->end(eventToken);
    mProto->end(wrapperToken);
}

size_t EventMetricProducer::byteSize() {
  return mProto->size();
}

}  // namespace statsd
}  // namespace os
}  // namespace android
