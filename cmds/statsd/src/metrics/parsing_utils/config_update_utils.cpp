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
#include "Log.h"

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
        matchersToUpdate[matcherIdx] = UPDATE_NEW;
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

bool updateAtomMatchingTrackers(const StatsdConfig& config, const sp<UidMap>& uidMap,
                                const unordered_map<int64_t, int>& oldAtomMatchingTrackerMap,
                                const vector<sp<AtomMatchingTracker>>& oldAtomMatchingTrackers,
                                set<int>& allTagIds,
                                unordered_map<int64_t, int>& newAtomMatchingTrackerMap,
                                vector<sp<AtomMatchingTracker>>& newAtomMatchingTrackers,
                                set<int64_t>& replacedMatchers) {
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
            case UPDATE_REPLACE:
                replacedMatchers.insert(id);
                [[fallthrough]];  // Intentionally fallthrough to create the new matcher.
            case UPDATE_NEW: {
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

// Recursive function to determine if a condition needs to be updated. Populates conditionsToUpdate.
// Returns whether the function was successful or not.
bool determineConditionUpdateStatus(const StatsdConfig& config, const int conditionIdx,
                                    const unordered_map<int64_t, int>& oldConditionTrackerMap,
                                    const vector<sp<ConditionTracker>>& oldConditionTrackers,
                                    const unordered_map<int64_t, int>& newConditionTrackerMap,
                                    const set<int64_t>& replacedMatchers,
                                    vector<UpdateStatus>& conditionsToUpdate,
                                    vector<bool>& cycleTracker) {
    // Have already examined this condition.
    if (conditionsToUpdate[conditionIdx] != UPDATE_UNKNOWN) {
        return true;
    }

    const Predicate& predicate = config.predicate(conditionIdx);
    int64_t id = predicate.id();
    // Check if new condition.
    const auto& oldConditionTrackerIt = oldConditionTrackerMap.find(id);
    if (oldConditionTrackerIt == oldConditionTrackerMap.end()) {
        conditionsToUpdate[conditionIdx] = UPDATE_NEW;
        return true;
    }

    // This is an existing condition. Check if it has changed.
    string serializedCondition;
    if (!predicate.SerializeToString(&serializedCondition)) {
        ALOGE("Unable to serialize matcher %lld", (long long)id);
        return false;
    }
    uint64_t newProtoHash = Hash64(serializedCondition);
    if (newProtoHash != oldConditionTrackers[oldConditionTrackerIt->second]->getProtoHash()) {
        conditionsToUpdate[conditionIdx] = UPDATE_REPLACE;
        return true;
    }

    switch (predicate.contents_case()) {
        case Predicate::ContentsCase::kSimplePredicate: {
            // Need to check if any of the underlying matchers changed.
            const SimplePredicate& simplePredicate = predicate.simple_predicate();
            if (simplePredicate.has_start()) {
                if (replacedMatchers.find(simplePredicate.start()) != replacedMatchers.end()) {
                    conditionsToUpdate[conditionIdx] = UPDATE_REPLACE;
                    return true;
                }
            }
            if (simplePredicate.has_stop()) {
                if (replacedMatchers.find(simplePredicate.stop()) != replacedMatchers.end()) {
                    conditionsToUpdate[conditionIdx] = UPDATE_REPLACE;
                    return true;
                }
            }
            if (simplePredicate.has_stop_all()) {
                if (replacedMatchers.find(simplePredicate.stop_all()) != replacedMatchers.end()) {
                    conditionsToUpdate[conditionIdx] = UPDATE_REPLACE;
                    return true;
                }
            }
            conditionsToUpdate[conditionIdx] = UPDATE_PRESERVE;
            return true;
        }
        case Predicate::ContentsCase::kCombination: {
            // Need to recurse on the children to see if any of the child predicates changed.
            cycleTracker[conditionIdx] = true;
            UpdateStatus status = UPDATE_PRESERVE;
            for (const int64_t childPredicateId : predicate.combination().predicate()) {
                const auto& childIt = newConditionTrackerMap.find(childPredicateId);
                if (childIt == newConditionTrackerMap.end()) {
                    ALOGW("Predicate %lld not found in the config", (long long)childPredicateId);
                    return false;
                }
                const int childIdx = childIt->second;
                if (cycleTracker[childIdx]) {
                    ALOGE("Cycle detected in predicate config");
                    return false;
                }
                if (!determineConditionUpdateStatus(config, childIdx, oldConditionTrackerMap,
                                                    oldConditionTrackers, newConditionTrackerMap,
                                                    replacedMatchers, conditionsToUpdate,
                                                    cycleTracker)) {
                    return false;
                }

                if (conditionsToUpdate[childIdx] == UPDATE_REPLACE) {
                    status = UPDATE_REPLACE;
                    break;
                }
            }
            conditionsToUpdate[conditionIdx] = status;
            cycleTracker[conditionIdx] = false;
            return true;
        }
        default: {
            ALOGE("Predicate \"%lld\" malformed", (long long)id);
            return false;
        }
    }

    return true;
}

bool updateConditions(const ConfigKey& key, const StatsdConfig& config,
                      const unordered_map<int64_t, int>& atomMatchingTrackerMap,
                      const set<int64_t>& replacedMatchers,
                      const unordered_map<int64_t, int>& oldConditionTrackerMap,
                      const vector<sp<ConditionTracker>>& oldConditionTrackers,
                      unordered_map<int64_t, int>& newConditionTrackerMap,
                      vector<sp<ConditionTracker>>& newConditionTrackers,
                      unordered_map<int, vector<int>>& trackerToConditionMap,
                      vector<ConditionState>& conditionCache, set<int64_t>& replacedConditions) {
    vector<Predicate> conditionProtos;
    const int conditionTrackerCount = config.predicate_size();
    conditionProtos.reserve(conditionTrackerCount);
    newConditionTrackers.reserve(conditionTrackerCount);
    conditionCache.assign(conditionTrackerCount, ConditionState::kNotEvaluated);

    for (int i = 0; i < conditionTrackerCount; i++) {
        const Predicate& condition = config.predicate(i);
        if (newConditionTrackerMap.find(condition.id()) != newConditionTrackerMap.end()) {
            ALOGE("Duplicate Predicate found!");
            return false;
        }
        newConditionTrackerMap[condition.id()] = i;
        conditionProtos.push_back(condition);
    }

    vector<UpdateStatus> conditionsToUpdate(conditionTrackerCount, UPDATE_UNKNOWN);
    vector<bool> cycleTracker(conditionTrackerCount, false);
    for (int i = 0; i < conditionTrackerCount; i++) {
        if (!determineConditionUpdateStatus(config, i, oldConditionTrackerMap, oldConditionTrackers,
                                            newConditionTrackerMap, replacedMatchers,
                                            conditionsToUpdate, cycleTracker)) {
            return false;
        }
    }

    // Update status has been determined for all conditions. Now perform the update.
    set<int> preservedConditions;
    for (int i = 0; i < conditionTrackerCount; i++) {
        const Predicate& predicate = config.predicate(i);
        const int64_t id = predicate.id();
        switch (conditionsToUpdate[i]) {
            case UPDATE_PRESERVE: {
                preservedConditions.insert(i);
                const auto& oldConditionTrackerIt = oldConditionTrackerMap.find(id);
                if (oldConditionTrackerIt == oldConditionTrackerMap.end()) {
                    ALOGE("Could not find Predicate %lld in the previous config, but expected it "
                          "to be there",
                          (long long)id);
                    return false;
                }
                const int oldIndex = oldConditionTrackerIt->second;
                newConditionTrackers.push_back(oldConditionTrackers[oldIndex]);
                break;
            }
            case UPDATE_REPLACE:
                replacedConditions.insert(id);
                [[fallthrough]];  // Intentionally fallthrough to create the new condition tracker.
            case UPDATE_NEW: {
                sp<ConditionTracker> tracker =
                        createConditionTracker(key, predicate, i, atomMatchingTrackerMap);
                if (tracker == nullptr) {
                    return false;
                }
                newConditionTrackers.push_back(tracker);
                break;
            }
            default: {
                ALOGE("Condition \"%lld\" update state is unknown. This should never happen",
                      (long long)id);
                return false;
            }
        }
    }

    // Update indices of preserved predicates.
    for (const int conditionIndex : preservedConditions) {
        if (!newConditionTrackers[conditionIndex]->onConfigUpdated(
                    conditionProtos, conditionIndex, newConditionTrackers, atomMatchingTrackerMap,
                    newConditionTrackerMap)) {
            ALOGE("Failed to update condition %lld",
                  (long long)newConditionTrackers[conditionIndex]->getConditionId());
            return false;
        }
    }

    std::fill(cycleTracker.begin(), cycleTracker.end(), false);
    for (int conditionIndex = 0; conditionIndex < conditionTrackerCount; conditionIndex++) {
        const sp<ConditionTracker>& conditionTracker = newConditionTrackers[conditionIndex];
        // Calling init on preserved conditions is OK. It is needed to fill the condition cache.
        if (!conditionTracker->init(conditionProtos, newConditionTrackers, newConditionTrackerMap,
                                    cycleTracker, conditionCache)) {
            return false;
        }
        for (const int trackerIndex : conditionTracker->getAtomMatchingTrackerIndex()) {
            vector<int>& conditionList = trackerToConditionMap[trackerIndex];
            conditionList.push_back(conditionIndex);
        }
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
                        const vector<sp<ConditionTracker>>& oldConditionTrackers,
                        const unordered_map<int64_t, int>& oldConditionTrackerMap,
                        set<int>& allTagIds,
                        vector<sp<AtomMatchingTracker>>& newAtomMatchingTrackers,
                        unordered_map<int64_t, int>& newAtomMatchingTrackerMap,
                        vector<sp<ConditionTracker>>& newConditionTrackers,
                        unordered_map<int64_t, int>& newConditionTrackerMap,
                        unordered_map<int, vector<int>>& trackerToConditionMap) {
    set<int64_t> replacedMatchers;
    set<int64_t> replacedConditions;
    vector<ConditionState> conditionCache;

    if (!updateAtomMatchingTrackers(config, uidMap, oldAtomMatchingTrackerMap,
                                    oldAtomMatchingTrackers, allTagIds, newAtomMatchingTrackerMap,
                                    newAtomMatchingTrackers, replacedMatchers)) {
        ALOGE("updateAtomMatchingTrackers failed");
        return false;
    }
    VLOG("updateAtomMatchingTrackers succeeded");

    if (!updateConditions(key, config, newAtomMatchingTrackerMap, replacedMatchers,
                          oldConditionTrackerMap, oldConditionTrackers, newConditionTrackerMap,
                          newConditionTrackers, trackerToConditionMap, conditionCache,
                          replacedConditions)) {
        ALOGE("updateConditions failed");
        return false;
    }
    VLOG("updateConditions succeeded");

    return true;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
