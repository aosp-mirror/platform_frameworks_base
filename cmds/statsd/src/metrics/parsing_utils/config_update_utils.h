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
#include "external/StatsPullerManager.h"
#include "matchers/AtomMatchingTracker.h"

namespace android {
namespace os {
namespace statsd {

// Helper functions for MetricsManager to update itself from a new StatsdConfig.
// *Note*: only updateStatsdConfig() should be called from outside this file.
// All other functions are intermediate steps, created to make unit testing easier.

// Possible update states for a component. PRESERVE means we should keep the existing one.
// REPLACE means we should create a new one, either because it didn't exist or it changed.
enum UpdateStatus {
    UPDATE_UNKNOWN = 0,
    UPDATE_PRESERVE = 1,
    UPDATE_REPLACE = 2,
};

// Recursive function to determine if a matcher needs to be updated.
// input:
// [config]: the input StatsdConfig
// [matcherIdx]: the index of the current matcher to be updated
// [newAtomMatchingTrackerMap]: matcher id to index mapping in the input StatsdConfig
// [oldAtomMatchingTrackerMap]: matcher id to index mapping in the existing MetricsManager
// [oldAtomMatchingTrackers]: stores the existing AtomMatchingTrackers
// output:
// [matchersToUpdate]: vector of the update status of each matcher. The matcherIdx index will
//                     be updated from UPDATE_UNKNOWN after this call.
// [cycleTracker]: intermediate param used during recursion.
bool determineMatcherUpdateStatus(const StatsdConfig& config, const int matcherIdx,
                                  const unordered_map<int64_t, int>& oldAtomMatchingTrackerMap,
                                  const vector<sp<AtomMatchingTracker>>& oldAtomMatchingTrackers,
                                  const unordered_map<int64_t, int>& newAtomMatchingTrackerMap,
                                  vector<UpdateStatus>& matchersToUpdate,
                                  vector<bool>& cycleTracker);

// Updates the AtomMatchingTrackers.
// input:
// [config]: the input StatsdConfig
// [oldAtomMatchingTrackerMap]: existing matcher id to index mapping
// [oldAtomMatchingTrackers]: stores the existing AtomMatchingTrackers
// output:
// [allTagIds]: contains the set of all interesting tag ids to this config.
// [newAtomMatchingTrackerMap]: new matcher id to index mapping
// [newAtomMatchers]: stores the new AtomMatchingTrackers
bool updateAtomTrackers(const StatsdConfig& config, const sp<UidMap>& uidMap,
                        const unordered_map<int64_t, int>& oldAtomMatchingTrackerMap,
                        const vector<sp<AtomMatchingTracker>>& oldAtomMatchingTrackers,
                        set<int>& allTagIds, unordered_map<int64_t, int>& newAtomMatchingTrackerMap,
                        vector<sp<AtomMatchingTracker>>& newAtomMatchingTrackers);

// Updates the existing MetricsManager from a new StatsdConfig.
// Parameters are the members of MetricsManager. See MetricsManager for declaration.
bool updateStatsdConfig(const ConfigKey& key, const StatsdConfig& config, const sp<UidMap>& uidMap,
                        const sp<StatsPullerManager>& pullerManager,
                        const sp<AlarmMonitor>& anomalyAlarmMonitor,
                        const sp<AlarmMonitor>& periodicAlarmMonitor, const int64_t timeBaseNs,
                        const int64_t currentTimeNs,
                        const std::vector<sp<AtomMatchingTracker>>& oldAtomMatchingTrackers,
                        const unordered_map<int64_t, int>& oldAtomMatchingTrackerMap,
                        std::set<int>& allTagIds,
                        std::vector<sp<AtomMatchingTracker>>& newAtomMatchingTrackers,
                        unordered_map<int64_t, int>& newAtomMatchingTrackerMap);

}  // namespace statsd
}  // namespace os
}  // namespace android
