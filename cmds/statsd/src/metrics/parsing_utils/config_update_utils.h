/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include <vector>

#include "anomaly/AlarmMonitor.h"
#include "condition/ConditionTracker.h"
#include "external/StatsPullerManager.h"
#include "matchers/AtomMatchingTracker.h"

namespace android {
namespace os {
namespace statsd {

// Helper functions for MetricsManager to update itself from a new StatsdConfig.
// *Note*: only updateStatsdConfig() should be called from outside this file.
// All other functions are intermediate steps, created to make unit testing easier.

// Possible update states for a component. PRESERVE means we should keep the existing one.
// REPLACE means we should create a new one because the existing one changed
// NEW means we should create a new one because one does not currently exist.
enum UpdateStatus {
    UPDATE_UNKNOWN = 0,
    UPDATE_PRESERVE = 1,
    UPDATE_REPLACE = 2,
    UPDATE_NEW = 3,
};

// Recursive function to determine if a matcher needs to be updated.
// input:
// [config]: the input StatsdConfig
// [matcherIdx]: the index of the current matcher to be updated
// [oldAtomMatchingTrackerMap]: matcher id to index mapping in the existing MetricsManager
// [oldAtomMatchingTrackers]: stores the existing AtomMatchingTrackers
// [newAtomMatchingTrackerMap]: matcher id to index mapping in the input StatsdConfig
// output:
// [matchersToUpdate]: vector of the update status of each matcher. The matcherIdx index will
//                     be updated from UPDATE_UNKNOWN after this call.
// [cycleTracker]: intermediate param used during recursion.
// Returns whether the function was successful or not.
bool determineMatcherUpdateStatus(
        const StatsdConfig& config, const int matcherIdx,
        const std::unordered_map<int64_t, int>& oldAtomMatchingTrackerMap,
        const std::vector<sp<AtomMatchingTracker>>& oldAtomMatchingTrackers,
        const std::unordered_map<int64_t, int>& newAtomMatchingTrackerMap,
        std::vector<UpdateStatus>& matchersToUpdate, std::vector<bool>& cycleTracker);

// Updates the AtomMatchingTrackers.
// input:
// [config]: the input StatsdConfig
// [oldAtomMatchingTrackerMap]: existing matcher id to index mapping
// [oldAtomMatchingTrackers]: stores the existing AtomMatchingTrackers
// output:
// [allTagIds]: contains the set of all interesting tag ids to this config.
// [newAtomMatchingTrackerMap]: new matcher id to index mapping
// [newAtomMatchingTrackers]: stores the new AtomMatchingTrackers
// [replacedMatchers]: set of matcher ids that changed and have been replaced
bool updateAtomMatchingTrackers(const StatsdConfig& config, const sp<UidMap>& uidMap,
                                const std::unordered_map<int64_t, int>& oldAtomMatchingTrackerMap,
                                const std::vector<sp<AtomMatchingTracker>>& oldAtomMatchingTrackers,
                                std::set<int>& allTagIds,
                                std::unordered_map<int64_t, int>& newAtomMatchingTrackerMap,
                                std::vector<sp<AtomMatchingTracker>>& newAtomMatchingTrackers,
                                std::set<int64_t>& replacedMatchers);

// Recursive function to determine if a condition needs to be updated.
// input:
// [config]: the input StatsdConfig
// [conditionIdx]: the index of the current condition to be updated
// [oldConditionTrackerMap]: condition id to index mapping in the existing MetricsManager
// [oldConditionTrackers]: stores the existing ConditionTrackers
// [newConditionTrackerMap]: condition id to index mapping in the input StatsdConfig
// [replacedMatchers]: set of replaced matcher ids. conditions using these matchers must be replaced
// output:
// [conditionsToUpdate]: vector of the update status of each condition. The conditionIdx index will
//                       be updated from UPDATE_UNKNOWN after this call.
// [cycleTracker]: intermediate param used during recursion.
// Returns whether the function was successful or not.
bool determineConditionUpdateStatus(const StatsdConfig& config, const int conditionIdx,
                                    const std::unordered_map<int64_t, int>& oldConditionTrackerMap,
                                    const std::vector<sp<ConditionTracker>>& oldConditionTrackers,
                                    const std::unordered_map<int64_t, int>& newConditionTrackerMap,
                                    const std::set<int64_t>& replacedMatchers,
                                    std::vector<UpdateStatus>& conditionsToUpdate,
                                    std::vector<bool>& cycleTracker);

// Updates ConditionTrackers
// input:
// [config]: the input config
// [atomMatchingTrackerMap]: AtomMatchingTracker name to index mapping from previous step.
// [replacedMatchers]: ids of replaced matchers. conditions depending on these must also be replaced
// [oldConditionTrackerMap]: existing matcher id to index mapping
// [oldConditionTrackers]: stores the existing ConditionTrackers
// output:
// [newConditionTrackerMap]: new condition id to index mapping
// [newConditionTrackers]: stores the sp to all the ConditionTrackers
// [trackerToConditionMap]: contains the mapping from the index of an atom matcher
//                          to indices of condition trackers that use the matcher
// [conditionCache]: stores the current conditions for each ConditionTracker
// [replacedConditions]: set of matcher ids that have changed and have been replaced
bool updateConditions(const ConfigKey& key, const StatsdConfig& config,
                      const std::unordered_map<int64_t, int>& atomMatchingTrackerMap,
                      const std::set<int64_t>& replacedMatchers,
                      const std::unordered_map<int64_t, int>& oldConditionTrackerMap,
                      const std::vector<sp<ConditionTracker>>& oldConditionTrackers,
                      std::unordered_map<int64_t, int>& newConditionTrackerMap,
                      std::vector<sp<ConditionTracker>>& newConditionTrackers,
                      std::unordered_map<int, std::vector<int>>& trackerToConditionMap,
                      std::vector<ConditionState>& conditionCache,
                      std::set<int64_t>& replacedConditions);

// Updates the existing MetricsManager from a new StatsdConfig.
// Parameters are the members of MetricsManager. See MetricsManager for declaration.
bool updateStatsdConfig(const ConfigKey& key, const StatsdConfig& config, const sp<UidMap>& uidMap,
                        const sp<StatsPullerManager>& pullerManager,
                        const sp<AlarmMonitor>& anomalyAlarmMonitor,
                        const sp<AlarmMonitor>& periodicAlarmMonitor, const int64_t timeBaseNs,
                        const int64_t currentTimeNs,
                        const std::vector<sp<AtomMatchingTracker>>& oldAtomMatchingTrackers,
                        const std::unordered_map<int64_t, int>& oldAtomMatchingTrackerMap,
                        const std::vector<sp<ConditionTracker>>& oldConditionTrackers,
                        const std::unordered_map<int64_t, int>& oldConditionTrackerMap,
                        std::set<int>& allTagIds,
                        std::vector<sp<AtomMatchingTracker>>& newAtomMatchingTrackers,
                        std::unordered_map<int64_t, int>& newAtomMatchingTrackerMap,
                        std::vector<sp<ConditionTracker>>& newConditionTrackers,
                        std::unordered_map<int64_t, int>& newConditionTrackerMap,
                        std::unordered_map<int, std::vector<int>>& trackerToConditionMap);

}  // namespace statsd
}  // namespace os
}  // namespace android
