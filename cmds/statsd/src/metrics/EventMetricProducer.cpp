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

using android::util::FIELD_COUNT_REPEATED;
using android::util::FIELD_TYPE_BOOL;
using android::util::FIELD_TYPE_FLOAT;
using android::util::FIELD_TYPE_INT32;
using android::util::FIELD_TYPE_INT64;
using android::util::FIELD_TYPE_STRING;
using android::util::FIELD_TYPE_MESSAGE;
using android::util::ProtoOutputStream;
using std::map;
using std::string;
using std::unordered_map;
using std::vector;

namespace android {
namespace os {
namespace statsd {

// for StatsLogReport
const int FIELD_ID_NAME = 1;
const int FIELD_ID_START_REPORT_NANOS = 2;
const int FIELD_ID_END_REPORT_NANOS = 3;
const int FIELD_ID_EVENT_METRICS = 4;
// for EventMetricDataWrapper
const int FIELD_ID_DATA = 1;
// for EventMetricData
const int FIELD_ID_TIMESTAMP_NANOS = 1;
const int FIELD_ID_ATOMS = 2;

EventMetricProducer::EventMetricProducer(const ConfigKey& key, const EventMetric& metric,
                                         const int conditionIndex,
                                         const sp<ConditionWizard>& wizard,
                                         const uint64_t startTimeNs)
    : MetricProducer(key, startTimeNs, conditionIndex, wizard), mMetric(metric) {
    if (metric.links().size() > 0) {
        mConditionLinks.insert(mConditionLinks.begin(), metric.links().begin(),
                               metric.links().end());
        mConditionSliced = true;
    }

    startNewProtoOutputStream(mStartTimeNs);

    VLOG("metric %s created. bucket size %lld start_time: %lld", metric.name().c_str(),
         (long long)mBucketSizeNs, (long long)mStartTimeNs);
}

EventMetricProducer::~EventMetricProducer() {
    VLOG("~EventMetricProducer() called");
}

void EventMetricProducer::startNewProtoOutputStream(long long startTime) {
    std::lock_guard<std::shared_timed_mutex> writeLock(mRWMutex);
    mProto = std::make_unique<ProtoOutputStream>();
    // TODO: We need to auto-generate the field IDs for StatsLogReport, EventMetricData,
    // and StatsEvent.
    mProto->write(FIELD_TYPE_STRING | FIELD_ID_NAME, mMetric.name());
    mProto->write(FIELD_TYPE_INT64 | FIELD_ID_START_REPORT_NANOS, startTime);
    mProtoToken = mProto->start(FIELD_TYPE_MESSAGE | FIELD_ID_EVENT_METRICS);
}

void EventMetricProducer::finish() {
}

void EventMetricProducer::onSlicedConditionMayChange(const uint64_t eventTime) {
}

std::unique_ptr<std::vector<uint8_t>> EventMetricProducer::onDumpReport() {
    long long endTime = time(nullptr) * NS_PER_SEC;
    // TODO(yanglu): make this section to an util function.
    {
        std::lock_guard<std::shared_timed_mutex> writeLock(mRWMutex);
        mProto->end(mProtoToken);
        mProto->write(FIELD_TYPE_INT64 | FIELD_ID_END_REPORT_NANOS, endTime);

        size_t bufferSize = mProto->size();
        VLOG("metric %s dump report now... proto size: %zu ", mMetric.name().c_str(), bufferSize);
    }

    std::unique_ptr<std::vector<uint8_t>> buffer = serializeProto();

    startNewProtoOutputStream(endTime);

    return buffer;
}

void EventMetricProducer::onConditionChanged(const bool conditionMet, const uint64_t eventTime) {
    VLOG("Metric %s onConditionChanged", mMetric.name().c_str());
    std::lock_guard<std::shared_timed_mutex> writeLock(mRWMutex);
    mCondition = conditionMet;
}

void EventMetricProducer::onMatchedLogEventInternal(
        const size_t matcherIndex, const HashableDimensionKey& eventKey,
        const std::map<std::string, HashableDimensionKey>& conditionKey, bool condition,
        const LogEvent& event, bool scheduledPull) {
    std::lock_guard<std::shared_timed_mutex> writeLock(mRWMutex);
    if (!condition) {
        return;
    }

    long long wrapperToken =
            mProto->start(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_DATA);
    mProto->write(FIELD_TYPE_INT64 | FIELD_ID_TIMESTAMP_NANOS, (long long)event.GetTimestampNs());
    long long eventToken = mProto->start(FIELD_TYPE_MESSAGE | FIELD_ID_ATOMS);
    event.ToProto(*mProto);
    mProto->end(eventToken);
    mProto->end(wrapperToken);
}

size_t EventMetricProducer::byteSize() const {
    std::shared_lock<std::shared_timed_mutex> readLock(mRWMutex);
    return mProto->bytesWritten();
}

}  // namespace statsd
}  // namespace os
}  // namespace android
