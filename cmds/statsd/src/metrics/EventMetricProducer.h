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

#ifndef EVENT_METRIC_PRODUCER_H
#define EVENT_METRIC_PRODUCER_H

#include <unordered_map>

#include <android/util/ProtoOutputStream.h>

#include "../condition/ConditionTracker.h"
#include "../matchers/matcher_util.h"
#include "MetricProducer.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "stats_util.h"

namespace android {
namespace os {
namespace statsd {

class EventMetricProducer : public MetricProducer {
public:
    // TODO: Pass in the start time from MetricsManager, it should be consistent for all metrics.
    EventMetricProducer(const ConfigKey& key, const EventMetric& eventMetric,
                        const int conditionIndex, const sp<ConditionWizard>& wizard,
                        const int64_t startTimeNs);

    virtual ~EventMetricProducer();

private:
    void onMatchedLogEventInternalLocked(
            const size_t matcherIndex, const MetricDimensionKey& eventKey,
            const ConditionKey& conditionKey, bool condition,
            const LogEvent& event) override;

    void onDumpReportLocked(const int64_t dumpTimeNs,
                            const bool include_current_partial_bucket,
                            std::set<string> *str_set,
                            android::util::ProtoOutputStream* protoOutput) override;
    void clearPastBucketsLocked(const int64_t dumpTimeNs) override;

    // Internal interface to handle condition change.
    void onConditionChangedLocked(const bool conditionMet, const int64_t eventTime) override;

    // Internal interface to handle sliced condition change.
    void onSlicedConditionMayChangeLocked(bool overallCondition, const int64_t eventTime) override;

    void dropDataLocked(const int64_t dropTimeNs) override;

    // Internal function to calculate the current used bytes.
    size_t byteSizeLocked() const override;

    void dumpStatesLocked(FILE* out, bool verbose) const override{};

    // Maps to a EventMetricDataWrapper. Storing atom events in ProtoOutputStream
    // is more space efficient than storing LogEvent.
    std::unique_ptr<android::util::ProtoOutputStream> mProto;
};

}  // namespace statsd
}  // namespace os
}  // namespace android
#endif  // EVENT_METRIC_PRODUCER_H
