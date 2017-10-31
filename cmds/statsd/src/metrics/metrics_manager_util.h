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
#ifndef METRIC_UTIL_H
#define METRIC_UTIL_H
#include <memory>
#include <set>
#include <unordered_map>
#include <vector>

#include "../condition/ConditionTracker.h"
#include "../matchers/LogMatchingTracker.h"

namespace android {
namespace os {
namespace statsd {

// Helper functions for MetricsManager to initialize from StatsdConfig.
// *Note*: only initStatsdConfig() should be called from outside.
// All other functions are intermediate
// steps, created to make unit tests easier. And most of the parameters in these
// functions are temporary objects in the initialization phase.

// Initialize the LogMatchingTrackers.
// input:
// [config]: the input StatsdConfig
// output:
// [logTrackerMap]: this map should contain matcher name to index mapping
// [allLogEntryMatchers]: should store the sp to all the LogMatchingTracker
// [allTagIds]: contains the set of all interesting tag ids to this config.
bool initLogTrackers(const StatsdConfig& config,
                     std::unordered_map<std::string, int>& logTrackerMap,
                     std::vector<sp<LogMatchingTracker>>& allLogEntryMatchers,
                     std::set<int>& allTagIds);

// Initialize ConditionTrackers
// input:
// [config]: the input config
// [logTrackerMap]: LogMatchingTracker name to index mapping from previous step.
// output:
// [conditionTrackerMap]: this map should contain condition name to index mapping
// [allConditionTrackers]: stores the sp to all the ConditionTrackers
// [trackerToConditionMap]: contain the mapping from index of
//                        log tracker to condition trackers that use the log tracker
bool initConditions(const StatsdConfig& config,
                    const std::unordered_map<std::string, int>& logTrackerMap,
                    std::unordered_map<std::string, int>& conditionTrackerMap,
                    std::vector<sp<ConditionTracker>>& allConditionTrackers,
                    std::unordered_map<int, std::vector<int>>& trackerToConditionMap,
                    std::unordered_map<int, std::vector<EventConditionLink>>& eventConditionLinks);

// Initialize MetricProducers.
// input:
// [config]: the input config
// [logTrackerMap]: LogMatchingTracker name to index mapping from previous step.
// [conditionTrackerMap]: condition name to index mapping
// output:
// [allMetricProducers]: contains the list of sp to the MetricProducers created.
// [conditionToMetricMap]: contains the mapping from condition tracker index to
//                          the list of MetricProducer index
// [trackerToMetricMap]: contains the mapping from log tracker to MetricProducer index.
bool initMetrics(
        const StatsdConfig& config, const std::unordered_map<std::string, int>& logTrackerMap,
        const std::unordered_map<std::string, int>& conditionTrackerMap,
        const std::unordered_map<int, std::vector<EventConditionLink>>& eventConditionLinks,
        const vector<sp<LogMatchingTracker>>& allLogEntryMatchers,
        vector<sp<ConditionTracker>>& allConditionTrackers,
        std::vector<sp<MetricProducer>>& allMetricProducers,
        std::unordered_map<int, std::vector<int>>& conditionToMetricMap,
        std::unordered_map<int, std::vector<int>>& trackerToMetricMap);

// Initialize MetricsManager from StatsdConfig.
// Parameters are the members of MetricsManager. See MetricsManager for declaration.
bool initStatsdConfig(const StatsdConfig& config, std::set<int>& allTagIds,
                      std::vector<sp<LogMatchingTracker>>& allLogEntryMatchers,
                      std::vector<sp<ConditionTracker>>& allConditionTrackers,
                      std::vector<sp<MetricProducer>>& allMetricProducers,
                      std::unordered_map<int, std::vector<int>>& conditionToMetricMap,
                      std::unordered_map<int, std::vector<int>>& trackerToMetricMap,
                      std::unordered_map<int, std::vector<int>>& trackerToConditionMap);

}  // namespace statsd
}  // namespace os
}  // namespace android
#endif  // METRIC_UTIL_H
