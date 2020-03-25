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

#include <set>
#include <unordered_map>
#include <vector>

#include "../anomaly/AlarmTracker.h"
#include "../condition/ConditionTracker.h"
#include "../external/StatsPullerManager.h"
#include "../matchers/LogMatchingTracker.h"
#include "../metrics/MetricProducer.h"

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
// [key]: the config key that this config belongs to
// [config]: the input StatsdConfig
// output:
// [logTrackerMap]: this map should contain matcher name to index mapping
// [allAtomMatchers]: should store the sp to all the LogMatchingTracker
// [allTagIds]: contains the set of all interesting tag ids to this config.
bool initLogTrackers(const StatsdConfig& config,
                     const UidMap& uidMap,
                     std::unordered_map<int64_t, int>& logTrackerMap,
                     std::vector<sp<LogMatchingTracker>>& allAtomMatchers,
                     std::set<int>& allTagIds);

// Initialize ConditionTrackers
// input:
// [key]: the config key that this config belongs to
// [config]: the input config
// [logTrackerMap]: LogMatchingTracker name to index mapping from previous step.
// output:
// [conditionTrackerMap]: this map should contain condition name to index mapping
// [allConditionTrackers]: stores the sp to all the ConditionTrackers
// [trackerToConditionMap]: contain the mapping from index of
//                        log tracker to condition trackers that use the log tracker
bool initConditions(const ConfigKey& key, const StatsdConfig& config,
                    const std::unordered_map<int64_t, int>& logTrackerMap,
                    std::unordered_map<int64_t, int>& conditionTrackerMap,
                    std::vector<sp<ConditionTracker>>& allConditionTrackers,
                    std::unordered_map<int, std::vector<int>>& trackerToConditionMap,
                    std::unordered_map<int, std::vector<MetricConditionLink>>& eventConditionLinks);

// Initialize State maps using State protos in the config. These maps will
// eventually be passed to MetricProducers to initialize their state info.
// input:
// [config]: the input config
// output:
// [stateAtomIdMap]: this map should contain the mapping from state ids to atom ids
// [allStateGroupMaps]: this map should contain the mapping from states ids and state
//                      values to state group ids for all states
bool initStates(const StatsdConfig& config, unordered_map<int64_t, int>& stateAtomIdMap,
                unordered_map<int64_t, unordered_map<int, int64_t>>& allStateGroupMaps);

// Initialize MetricProducers.
// input:
// [key]: the config key that this config belongs to
// [config]: the input config
// [timeBaseSec]: start time base for all metrics
// [logTrackerMap]: LogMatchingTracker name to index mapping from previous step.
// [conditionTrackerMap]: condition name to index mapping
// [stateAtomIdMap]: contains the mapping from state ids to atom ids
// [allStateGroupMaps]: contains the mapping from atom ids and state values to
//                      state group ids for all states
// output:
// [allMetricProducers]: contains the list of sp to the MetricProducers created.
// [conditionToMetricMap]: contains the mapping from condition tracker index to
//                          the list of MetricProducer index
// [trackerToMetricMap]: contains the mapping from log tracker to MetricProducer index.
bool initMetrics(
        const ConfigKey& key, const StatsdConfig& config, const int64_t timeBaseTimeNs,
        const int64_t currentTimeNs, UidMap& uidMap, const sp<StatsPullerManager>& pullerManager,
        const std::unordered_map<int64_t, int>& logTrackerMap,
        const std::unordered_map<int64_t, int>& conditionTrackerMap,
        const std::unordered_map<int, std::vector<MetricConditionLink>>& eventConditionLinks,
        const vector<sp<LogMatchingTracker>>& allAtomMatchers,
        const unordered_map<int64_t, int>& stateAtomIdMap,
        const unordered_map<int64_t, unordered_map<int, int64_t>>& allStateGroupMaps,
        vector<sp<ConditionTracker>>& allConditionTrackers,
        std::vector<sp<MetricProducer>>& allMetricProducers,
        std::unordered_map<int, std::vector<int>>& conditionToMetricMap,
        std::unordered_map<int, std::vector<int>>& trackerToMetricMap,
        std::set<int64_t>& noReportMetricIds,
        std::unordered_map<int, std::vector<int>>& activationAtomTrackerToMetricMap,
        std::unordered_map<int, std::vector<int>>& deactivationAtomTrackerToMetricMap,
        std::vector<int>& metricsWithActivation);

// Initialize MetricsManager from StatsdConfig.
// Parameters are the members of MetricsManager. See MetricsManager for declaration.
bool initStatsdConfig(const ConfigKey& key, const StatsdConfig& config, UidMap& uidMap,
                      const sp<StatsPullerManager>& pullerManager,
                      const sp<AlarmMonitor>& anomalyAlarmMonitor,
                      const sp<AlarmMonitor>& periodicAlarmMonitor, const int64_t timeBaseNs,
                      const int64_t currentTimeNs, std::set<int>& allTagIds,
                      std::vector<sp<LogMatchingTracker>>& allAtomMatchers,
                      std::vector<sp<ConditionTracker>>& allConditionTrackers,
                      std::vector<sp<MetricProducer>>& allMetricProducers,
                      vector<sp<AnomalyTracker>>& allAnomalyTrackers,
                      vector<sp<AlarmTracker>>& allPeriodicAlarmTrackers,
                      std::unordered_map<int, std::vector<int>>& conditionToMetricMap,
                      std::unordered_map<int, std::vector<int>>& trackerToMetricMap,
                      std::unordered_map<int, std::vector<int>>& trackerToConditionMap,
                      unordered_map<int, std::vector<int>>& activationAtomTrackerToMetricMap,
                      unordered_map<int, std::vector<int>>& deactivationAtomTrackerToMetricMap,
                      std::unordered_map<int64_t, int>& alertTrackerMap,
                      vector<int>& metricsWithActivation,
                      std::set<int64_t>& noReportMetricIds);

bool isStateConditionTracker(const SimplePredicate& simplePredicate, std::vector<Matcher>* primaryKeys);

}  // namespace statsd
}  // namespace os
}  // namespace android
