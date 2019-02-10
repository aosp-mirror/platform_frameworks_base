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
#include "stats_log_util.h"

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
const int FIELD_ID_ID = 1;
const int FIELD_ID_EVENT_METRICS = 4;
const int FIELD_ID_IS_ACTIVE = 14;
// for EventMetricDataWrapper
const int FIELD_ID_DATA = 1;
// for EventMetricData
const int FIELD_ID_ELAPSED_TIMESTAMP_NANOS = 1;
const int FIELD_ID_ATOMS = 2;

EventMetricProducer::EventMetricProducer(const ConfigKey& key, const EventMetric& metric,
                                         const int conditionIndex,
                                         const sp<ConditionWizard>& wizard,
                                         const int64_t startTimeNs)
    : MetricProducer(metric.id(), key, startTimeNs, conditionIndex, wizard) {
    if (metric.links().size() > 0) {
        for (const auto& link : metric.links()) {
            Metric2Condition mc;
            mc.conditionId = link.condition();
            translateFieldMatcher(link.fields_in_what(), &mc.metricFields);
            translateFieldMatcher(link.fields_in_condition(), &mc.conditionFields);
            mMetric2ConditionLinks.push_back(mc);
        }
        mConditionSliced = true;
    }
    mProto = std::make_unique<ProtoOutputStream>();
    VLOG("metric %lld created. bucket size %lld start_time: %lld", (long long)metric.id(),
         (long long)mBucketSizeNs, (long long)mTimeBaseNs);
}

EventMetricProducer::~EventMetricProducer() {
    VLOG("~EventMetricProducer() called");
}

void EventMetricProducer::dropDataLocked(const int64_t dropTimeNs) {
    mProto->clear();
    StatsdStats::getInstance().noteBucketDropped(mMetricId);
}

void EventMetricProducer::onSlicedConditionMayChangeLocked(bool overallCondition,
                                                           const int64_t eventTime) {
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

void EventMetricProducer::clearPastBucketsLocked(const int64_t dumpTimeNs) {
    mProto->clear();
}

void EventMetricProducer::onDumpReportLocked(const int64_t dumpTimeNs,
                                             const bool include_current_partial_bucket,
                                             const bool erase_data,
                                             std::set<string> *str_set,
                                             ProtoOutputStream* protoOutput) {
    protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_ID, (long long)mMetricId);
    protoOutput->write(FIELD_TYPE_BOOL | FIELD_ID_IS_ACTIVE, isActiveLocked());
    if (mProto->size() <= 0) {
        return;
    }

    size_t bufferSize = mProto->size();
    VLOG("metric %lld dump report now... proto size: %zu ",
        (long long)mMetricId, bufferSize);
    std::unique_ptr<std::vector<uint8_t>> buffer = serializeProtoLocked(*mProto);

    protoOutput->write(FIELD_TYPE_MESSAGE | FIELD_ID_EVENT_METRICS,
                       reinterpret_cast<char*>(buffer.get()->data()), buffer.get()->size());

    if (erase_data) {
        mProto->clear();
    }
}

void EventMetricProducer::onConditionChangedLocked(const bool conditionMet,
                                                   const int64_t eventTime) {
    VLOG("Metric %lld onConditionChanged", (long long)mMetricId);
    mCondition = conditionMet;
}

void EventMetricProducer::onMatchedLogEventInternalLocked(
        const size_t matcherIndex, const MetricDimensionKey& eventKey,
        const ConditionKey& conditionKey, bool condition,
        const LogEvent& event) {
    if (!condition) {
        return;
    }

    uint64_t wrapperToken =
            mProto->start(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_DATA);
    const bool truncateTimestamp =
            android::util::AtomsInfo::kNotTruncatingTimestampAtomWhiteList.find(event.GetTagId()) ==
            android::util::AtomsInfo::kNotTruncatingTimestampAtomWhiteList.end();
    if (truncateTimestamp) {
        mProto->write(FIELD_TYPE_INT64 | FIELD_ID_ELAPSED_TIMESTAMP_NANOS,
            (long long)truncateTimestampNsToFiveMinutes(event.GetElapsedTimestampNs()));
    } else {
        mProto->write(FIELD_TYPE_INT64 | FIELD_ID_ELAPSED_TIMESTAMP_NANOS,
            (long long)event.GetElapsedTimestampNs());
    }

    uint64_t eventToken = mProto->start(FIELD_TYPE_MESSAGE | FIELD_ID_ATOMS);
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
