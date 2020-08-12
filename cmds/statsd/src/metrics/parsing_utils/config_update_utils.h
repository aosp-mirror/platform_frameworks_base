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
#include "matchers/LogMatchingTracker.h"

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
// [newLogTrackerMap]: matcher id to index mapping in the input StatsdConfig
// [oldLogTrackerMap]: matcher id to index mapping in the existing MetricsManager
// [oldAtomMatchers]: stores the existing LogMatchingTrackers
// output:
// [matchersToUpdate]: vector of the update status of each matcher. The matcherIdx index will
//                     be updated from UPDATE_UNKNOWN after this call.
// [cycleTracker]: intermediate param used during recursion.
bool determineMatcherUpdateStatus(const StatsdConfig& config, const int matcherIdx,
                                  const unordered_map<int64_t, int>& oldLogTrackerMap,
                                  const vector<sp<LogMatchingTracker>>& oldAtomMatchers,
                                  const unordered_map<int64_t, int>& newLogTrackerMap,
                                  vector<UpdateStatus>& matchersToUpdate,
                                  vector<bool>& cycleTracker);

// Updates the LogMatchingTrackers.
// input:
// [config]: the input StatsdConfig
// [oldLogTrackerMap]: existing matcher id to index mapping
// [oldAtomMatchers]: stores the existing LogMatchingTrackers
// output:
// [allTagIds]: contains the set of all interesting tag ids to this config.
// [newLogTrackerMap]: new matcher id to index mapping
// [newAtomMatchers]: stores the new LogMatchingTrackers
bool updateLogTrackers(const StatsdConfig& config, const sp<UidMap>& uidMap,
                       const unordered_map<int64_t, int>& oldLogTrackerMap,
                       const vector<sp<LogMatchingTracker>>& oldAtomMatchers, set<int>& allTagIds,
                       unordered_map<int64_t, int>& newLogTrackerMap,
                       vector<sp<LogMatchingTracker>>& newAtomMatchers);

// Updates the existing MetricsManager from a new StatsdConfig.
// Parameters are the members of MetricsManager. See MetricsManager for declaration.
bool updateStatsdConfig(const ConfigKey& key, const StatsdConfig& config, const sp<UidMap>& uidMap,
                        const sp<StatsPullerManager>& pullerManager,
                        const sp<AlarmMonitor>& anomalyAlarmMonitor,
                        const sp<AlarmMonitor>& periodicAlarmMonitor, const int64_t timeBaseNs,
                        const int64_t currentTimeNs,
                        const std::vector<sp<LogMatchingTracker>>& oldAtomMatchers,
                        const unordered_map<int64_t, int>& oldLogTrackerMap,
                        std::set<int>& allTagIds,
                        std::vector<sp<LogMatchingTracker>>& newAtomMatchers,
                        unordered_map<int64_t, int>& newLogTrackerMap);

}  // namespace statsd
}  // namespace os
}  // namespace android