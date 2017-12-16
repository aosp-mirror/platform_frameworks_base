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
    : MetricProducer(metric.name(), key, startTimeNs, conditionIndex, wizard) {
    if (metric.links().size() > 0) {
        mConditionLinks.insert(mConditionLinks.begin(), metric.links().begin(),
                               metric.links().end());
        mConditionSliced = true;
    }

    startNewProtoOutputStreamLocked();

    VLOG("metric %s created. bucket size %lld start_time: %lld", metric.name().c_str(),
         (long long)mBucketSizeNs, (long long)mStartTimeNs);
}

EventMetricProducer::~EventMetricProducer() {
    VLOG("~EventMetricProducer() called");
}

void EventMetricProducer::startNewProtoOutputStreamLocked() {
    mProto = std::make_unique<ProtoOutputStream>();
}

void EventMetricProducer::onSlicedConditionMayChangeLocked(const uint64_t eventTime) {
}

std::unique_ptr<std::vector<uint8_t>> serializeProtoLocked(ProtoOutputStream& protoOutput) {
    size_t bufferSize = protoOutput.size();

    std::unique_ptr<std::vector<uint8_t>> buffer(new std::vector<uint8_t>(bufferSize));

    size_t pos = 0;
    auto it = protoOutput.data();
    while (it.readBuffer() != NULL) {
        size_t toRead = it.currentToRead();
        std::memcpy(&((*buffer)[pos]), it.readBuffer(), toRead);
        pos += toRead;
        it.rp()->move(toRead);
    }

    return buffer;
}

void EventMetricProducer::onDumpReportLocked(const uint64_t dumpTimeNs,
                                             ProtoOutputStream* protoOutput) {
    protoOutput->write(FIELD_TYPE_STRING | FIELD_ID_NAME, mName);
    protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_START_REPORT_NANOS, (long long)mStartTimeNs);
    protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_END_REPORT_NANOS, (long long)dumpTimeNs);

    size_t bufferSize = mProto->size();
    VLOG("metric %s dump report now... proto size: %zu ", mName.c_str(), bufferSize);
    std::unique_ptr<std::vector<uint8_t>> buffer = serializeProtoLocked(*mProto);

    protoOutput->write(FIELD_TYPE_MESSAGE | FIELD_ID_EVENT_METRICS,
                       reinterpret_cast<char*>(buffer.get()->data()), buffer.get()->size());

    startNewProtoOutputStreamLocked();
    mStartTimeNs = dumpTimeNs;
}

void EventMetricProducer::onConditionChangedLocked(const bool conditionMet,
                                                   const uint64_t eventTime) {
    VLOG("Metric %s onConditionChanged", mName.c_str());
    mCondition = conditionMet;
}

void EventMetricProducer::onMatchedLogEventInternalLocked(
        const size_t matcherIndex, const HashableDimensionKey& eventKey,
        const std::map<std::string, HashableDimensionKey>& conditionKey, bool condition,
        const LogEvent& event) {
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

size_t EventMetricProducer::byteSizeLocked() const {
    return mProto->bytesWritten();
}

}  // namespace statsd
}  // namespace os
}  // namespace android
