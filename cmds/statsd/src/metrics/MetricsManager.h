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

#pragma once

#include "condition/ConditionTracker.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "logd/LogEvent.h"
#include "matchers/LogMatchingTracker.h"
#include "metrics/MetricProducer.h"

#include <unordered_map>

namespace android {
namespace os {
namespace statsd {

// A MetricsManager is responsible for managing metrics from one single config source.
class MetricsManager {
public:
    MetricsManager(const StatsdConfig& config);

    ~MetricsManager();

    // Return whether the configuration is valid.
    bool isConfigValid() const;

    void onLogEvent(const LogEvent& event);

    // Called when everything should wrap up. We are about to finish (e.g., new config comes).
    void finish();

    // Config source owner can call onDumpReport() to get all the metrics collected.
    std::vector<StatsLogReport> onDumpReport();

private:
    // All event tags that are interesting to my metrics.
    std::set<int> mTagIds;

    // We only store the sp of LogMatchingTracker, MetricProducer, and ConditionTracker in
    // MetricManager. There are relationship between them, and the relationship are denoted by index
    // instead of pointers. The reasons for this are: (1) the relationship between them are
    // complicated, store index instead of pointers reduce the risk of A holds B's sp, and B holds
    // A's sp. (2) When we evaluate matcher results, or condition results, we can quickly get the
    // related results from a cache using the index.

    // Hold all the log entry matchers from the config.
    std::vector<sp<LogMatchingTracker>> mAllLogEntryMatchers;

    // Hold all the conditions from the config.
    std::vector<sp<ConditionTracker>> mAllConditionTrackers;

    // Hold all metrics from the config.
    std::vector<sp<MetricProducer>> mAllMetricProducers;

    // To make the log processing more efficient, we want to do as much filtering as possible
    // before we go into individual trackers and conditions to match.

    // 1st filter: check if the event tag id is in mTagIds.
    // 2nd filter: if it is, we parse the event because there is at least one member is interested.
    //             then pass to all LogMatchingTrackers (itself also filter events by ids).
    // 3nd filter: for LogMatchingTrackers that matched this event, we pass this event to the
    //             ConditionTrackers and MetricProducers that use this matcher.
    // 4th filter: for ConditionTrackers that changed value due to this event, we pass
    //             new conditions to  metrics that use this condition.

    // The following map is initialized from the statsd_config.

    // maps from the index of the LogMatchingTracker to index of MetricProducer.
    std::unordered_map<int, std::vector<int>> mTrackerToMetricMap;

    // maps from LogMatchingTracker to ConditionTracker
    std::unordered_map<int, std::vector<int>> mTrackerToConditionMap;

    // maps from ConditionTracker to MetricProducer
    std::unordered_map<int, std::vector<int>> mConditionToMetricMap;

    bool mConfigValid;
};

}  // namespace statsd
}  // namespace os
}  // namespace android

