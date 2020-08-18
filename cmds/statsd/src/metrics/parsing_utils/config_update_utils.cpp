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

#define DEBUG false  // STOPSHIP if true

#include "config_update_utils.h"

#include "external/StatsPullerManager.h"
#include "hash.h"
#include "metrics_manager_util.h"

namespace android {
namespace os {
namespace statsd {

// Recursive function to determine if a matcher needs to be updated. Populates matcherToUpdate.
// Returns whether the function was successful or not.
bool determineMatcherUpdateStatus(const StatsdConfig& config, const int matcherIdx,
                                  const unordered_map<int64_t, int>& oldAtomMatchingTrackerMap,
                                  const vector<sp<AtomMatchingTracker>>& oldAtomMatchingTrackers,
                                  const unordered_map<int64_t, int>& newAtomMatchingTrackerMap,
                                  vector<UpdateStatus>& matchersToUpdate,
                                  vector<bool>& cycleTracker) {
    // Have already examined this matcher.
    if (matchersToUpdate[matcherIdx] != UPDATE_UNKNOWN) {
        return true;
    }

    const AtomMatcher& matcher = config.atom_matcher(matcherIdx);
    int64_t id = matcher.id();
    // Check if new matcher.
    const auto& oldAtomMatchingTrackerIt = oldAtomMatchingTrackerMap.find(id);
    if (oldAtomMatchingTrackerIt == oldAtomMatchingTrackerMap.end()) {
        matchersToUpdate[matcherIdx] = UPDATE_REPLACE;
        return true;
    }

    // This is an existing matcher. Check if it has changed.
    string serializedMatcher;
    if (!matcher.SerializeToString(&serializedMatcher)) {
        ALOGE("Unable to serialize matcher %lld", (long long)id);
        return false;
    }
    uint64_t newProtoHash = Hash64(serializedMatcher);
    if (newProtoHash != oldAtomMatchingTrackers[oldAtomMatchingTrackerIt->second]->getProtoHash()) {
        matchersToUpdate[matcherIdx] = UPDATE_REPLACE;
        return true;
    }

    switch (matcher.contents_case()) {
        case AtomMatcher::ContentsCase::kSimpleAtomMatcher: {
            matchersToUpdate[matcherIdx] = UPDATE_PRESERVE;
            return true;
        }
        case AtomMatcher::ContentsCase::kCombination: {
            // Recurse to check if children have changed.
            cycleTracker[matcherIdx] = true;
            UpdateStatus status = UPDATE_PRESERVE;
            for (const int64_t childMatcherId : matcher.combination().matcher()) {
                const auto& childIt = newAtomMatchingTrackerMap.find(childMatcherId);
                if (childIt == newAtomMatchingTrackerMap.end()) {
                    ALOGW("Matcher %lld not found in the config", (long long)childMatcherId);
                    return false;
                }
                const int childIdx = childIt->second;
                if (cycleTracker[childIdx]) {
                    ALOGE("Cycle detected in matcher config");
                    return false;
                }
                if (!determineMatcherUpdateStatus(
                            config, childIdx, oldAtomMatchingTrackerMap, oldAtomMatchingTrackers,
                            newAtomMatchingTrackerMap, matchersToUpdate, cycleTracker)) {
                    return false;
                }

                if (matchersToUpdate[childIdx] == UPDATE_REPLACE) {
                    status = UPDATE_REPLACE;
                    break;
                }
            }
            matchersToUpdate[matcherIdx] = status;
            cycleTracker[matcherIdx] = false;
            return true;
        }
        default: {
            ALOGE("Matcher \"%lld\" malformed", (long long)id);
            return false;
        }
    }
    return true;
}

bool updateAtomTrackers(const StatsdConfig& config, const sp<UidMap>& uidMap,
                        const unordered_map<int64_t, int>& oldAtomMatchingTrackerMap,
                        const vector<sp<AtomMatchingTracker>>& oldAtomMatchingTrackers,
                        set<int>& allTagIds, unordered_map<int64_t, int>& newAtomMatchingTrackerMap,
                        vector<sp<AtomMatchingTracker>>& newAtomMatchingTrackers) {
    const int atomMatcherCount = config.atom_matcher_size();

    vector<AtomMatcher> matcherProtos;
    matcherProtos.reserve(atomMatcherCount);
    newAtomMatchingTrackers.reserve(atomMatcherCount);

    // Maps matcher id to their position in the config. For fast lookup of dependencies.
    for (int i = 0; i < atomMatcherCount; i++) {
        const AtomMatcher& matcher = config.atom_matcher(i);
        if (newAtomMatchingTrackerMap.find(matcher.id()) != newAtomMatchingTrackerMap.end()) {
            ALOGE("Duplicate atom matcher found for id %lld", (long long)matcher.id());
            return false;
        }
        newAtomMatchingTrackerMap[matcher.id()] = i;
        matcherProtos.push_back(matcher);
    }

    // For combination matchers, we need to determine if any children need to be updated.
    vector<UpdateStatus> matchersToUpdate(atomMatcherCount, UPDATE_UNKNOWN);
    vector<bool> cycleTracker(atomMatcherCount, false);
    for (int i = 0; i < atomMatcherCount; i++) {
        if (!determineMatcherUpdateStatus(config, i, oldAtomMatchingTrackerMap,
                                          oldAtomMatchingTrackers, newAtomMatchingTrackerMap,
                                          matchersToUpdate, cycleTracker)) {
            return false;
        }
    }

    for (int i = 0; i < atomMatcherCount; i++) {
        const AtomMatcher& matcher = config.atom_matcher(i);
        const int64_t id = matcher.id();
        switch (matchersToUpdate[i]) {
            case UPDATE_PRESERVE: {
                const auto& oldAtomMatchingTrackerIt = oldAtomMatchingTrackerMap.find(id);
                if (oldAtomMatchingTrackerIt == oldAtomMatchingTrackerMap.end()) {
                    ALOGE("Could not find AtomMatcher %lld in the previous config, but expected it "
                          "to be there",
                          (long long)id);
                    return false;
                }
                const sp<AtomMatchingTracker>& tracker =
                        oldAtomMatchingTrackers[oldAtomMatchingTrackerIt->second];
                if (!tracker->onConfigUpdated(matcherProtos[i], i, newAtomMatchingTrackerMap)) {
                    ALOGW("Config update failed for matcher %lld", (long long)id);
                    return false;
                }
                newAtomMatchingTrackers.push_back(tracker);
                break;
            }
            case UPDATE_REPLACE: {
                sp<AtomMatchingTracker> tracker = createAtomMatchingTracker(matcher, i, uidMap);
                if (tracker == nullptr) {
                    return false;
                }
                newAtomMatchingTrackers.push_back(tracker);
                break;
            }
            default: {
                ALOGE("Matcher \"%lld\" update state is unknown. This should never happen",
                      (long long)id);
                return false;
            }
        }
    }

    std::fill(cycleTracker.begin(), cycleTracker.end(), false);
    for (auto& matcher : newAtomMatchingTrackers) {
        if (!matcher->init(matcherProtos, newAtomMatchingTrackers, newAtomMatchingTrackerMap,
                           cycleTracker)) {
            return false;
        }
        // Collect all the tag ids that are interesting. TagIds exist in leaf nodes only.
        const set<int>& tagIds = matcher->getAtomIds();
        allTagIds.insert(tagIds.begin(), tagIds.end());
    }

    return true;
}

bool updateStatsdConfig(const ConfigKey& key, const StatsdConfig& config, const sp<UidMap>& uidMap,
                        const sp<StatsPullerManager>& pullerManager,
                        const sp<AlarmMonitor>& anomalyAlarmMonitor,
                        const sp<AlarmMonitor>& periodicAlarmMonitor, const int64_t timeBaseNs,
                        const int64_t currentTimeNs,
                        const vector<sp<AtomMatchingTracker>>& oldAtomMatchingTrackers,
                        const unordered_map<int64_t, int>& oldAtomMatchingTrackerMap,
                        set<int>& allTagIds,
                        vector<sp<AtomMatchingTracker>>& newAtomMatchingTrackers,
                        unordered_map<int64_t, int>& newAtomMatchingTrackerMap) {
    if (!updateAtomTrackers(config, uidMap, oldAtomMatchingTrackerMap, oldAtomMatchingTrackers,
                            allTagIds, newAtomMatchingTrackerMap, newAtomMatchingTrackers)) {
        ALOGE("updateAtomMatchingTrackers failed");
        return false;
    }

    return true;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
