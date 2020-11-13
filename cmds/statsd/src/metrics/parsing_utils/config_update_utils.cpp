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
#include "matchers/EventMatcherWizard.h"
#include "metrics_manager_util.h"

using google::protobuf::MessageLite;

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

// Returns true if any matchers in the metric activation were replaced.
bool metricActivationDepsChange(const StatsdConfig& config,
                                const unordered_map<int64_t, int>& metricToActivationMap,
                                const int64_t metricId, const set<int64_t>& replacedMatchers) {
    const auto& metricActivationIt = metricToActivationMap.find(metricId);
    if (metricActivationIt == metricToActivationMap.end()) {
        return false;
    }
    const MetricActivation& metricActivation = config.metric_activation(metricActivationIt->second);
    for (int i = 0; i < metricActivation.event_activation_size(); i++) {
        const EventActivation& activation = metricActivation.event_activation(i);
        if (replacedMatchers.find(activation.atom_matcher_id()) != replacedMatchers.end()) {
            return true;
        }
        if (activation.has_deactivation_atom_matcher_id()) {
            if (replacedMatchers.find(activation.deactivation_atom_matcher_id()) !=
                replacedMatchers.end()) {
                return true;
            }
        }
    }
    return false;
}

bool determineMetricUpdateStatus(
        const StatsdConfig& config, const MessageLite& metric, const int64_t metricId,
        const MetricType metricType, const set<int64_t>& matcherDependencies,
        const set<int64_t>& conditionDependencies,
        const ::google::protobuf::RepeatedField<int64_t>& stateDependencies,
        const ::google::protobuf::RepeatedPtrField<MetricConditionLink>& conditionLinks,
        const unordered_map<int64_t, int>& oldMetricProducerMap,
        const vector<sp<MetricProducer>>& oldMetricProducers,
        const unordered_map<int64_t, int>& metricToActivationMap,
        const set<int64_t>& replacedMatchers, const set<int64_t>& replacedConditions,
        const set<int64_t>& replacedStates, UpdateStatus& updateStatus) {
    // Check if new metric
    const auto& oldMetricProducerIt = oldMetricProducerMap.find(metricId);
    if (oldMetricProducerIt == oldMetricProducerMap.end()) {
        updateStatus = UPDATE_NEW;
        return true;
    }

    // This is an existing metric, check if it has changed.
    uint64_t metricHash;
    if (!getMetricProtoHash(config, metric, metricId, metricToActivationMap, metricHash)) {
        return false;
    }
    const sp<MetricProducer> oldMetricProducer = oldMetricProducers[oldMetricProducerIt->second];
    if (oldMetricProducer->getMetricType() != metricType ||
        oldMetricProducer->getProtoHash() != metricHash) {
        updateStatus = UPDATE_REPLACE;
        return true;
    }

    // Take intersections of the matchers/predicates/states that the metric
    // depends on with those that have been replaced. If a metric depends on any
    // replaced component, it too must be replaced.
    set<int64_t> intersection;
    set_intersection(matcherDependencies.begin(), matcherDependencies.end(),
                     replacedMatchers.begin(), replacedMatchers.end(),
                     inserter(intersection, intersection.begin()));
    if (intersection.size() > 0) {
        updateStatus = UPDATE_REPLACE;
        return true;
    }
    set_intersection(conditionDependencies.begin(), conditionDependencies.end(),
                     replacedConditions.begin(), replacedConditions.end(),
                     inserter(intersection, intersection.begin()));
    if (intersection.size() > 0) {
        updateStatus = UPDATE_REPLACE;
        return true;
    }
    set_intersection(stateDependencies.begin(), stateDependencies.end(), replacedStates.begin(),
                     replacedStates.end(), inserter(intersection, intersection.begin()));
    if (intersection.size() > 0) {
        updateStatus = UPDATE_REPLACE;
        return true;
    }

    for (const auto& metricConditionLink : conditionLinks) {
        if (replacedConditions.find(metricConditionLink.condition()) != replacedConditions.end()) {
            updateStatus = UPDATE_REPLACE;
            return true;
        }
    }

    if (metricActivationDepsChange(config, metricToActivationMap, metricId, replacedMatchers)) {
        updateStatus = UPDATE_REPLACE;
        return true;
    }

    updateStatus = UPDATE_PRESERVE;
    return true;
}

bool determineAllMetricUpdateStatuses(const StatsdConfig& config,
                                      const unordered_map<int64_t, int>& oldMetricProducerMap,
                                      const vector<sp<MetricProducer>>& oldMetricProducers,
                                      const unordered_map<int64_t, int>& metricToActivationMap,
                                      const set<int64_t>& replacedMatchers,
                                      const set<int64_t>& replacedConditions,
                                      const set<int64_t>& replacedStates,
                                      vector<UpdateStatus>& metricsToUpdate) {
    int metricIndex = 0;
    for (int i = 0; i < config.count_metric_size(); i++, metricIndex++) {
        const CountMetric& metric = config.count_metric(i);
        set<int64_t> conditionDependencies;
        if (metric.has_condition()) {
            conditionDependencies.insert(metric.condition());
        }
        if (!determineMetricUpdateStatus(
                    config, metric, metric.id(), METRIC_TYPE_COUNT, {metric.what()},
                    conditionDependencies, metric.slice_by_state(), metric.links(),
                    oldMetricProducerMap, oldMetricProducers, metricToActivationMap,
                    replacedMatchers, replacedConditions, replacedStates,
                    metricsToUpdate[metricIndex])) {
            return false;
        }
    }
    for (int i = 0; i < config.duration_metric_size(); i++, metricIndex++) {
        const DurationMetric& metric = config.duration_metric(i);
        set<int64_t> conditionDependencies({metric.what()});
        if (metric.has_condition()) {
            conditionDependencies.insert(metric.condition());
        }
        if (!determineMetricUpdateStatus(
                    config, metric, metric.id(), METRIC_TYPE_DURATION, /*matcherDependencies=*/{},
                    conditionDependencies, metric.slice_by_state(), metric.links(),
                    oldMetricProducerMap, oldMetricProducers, metricToActivationMap,
                    replacedMatchers, replacedConditions, replacedStates,
                    metricsToUpdate[metricIndex])) {
            return false;
        }
    }
    for (int i = 0; i < config.event_metric_size(); i++, metricIndex++) {
        const EventMetric& metric = config.event_metric(i);
        set<int64_t> conditionDependencies;
        if (metric.has_condition()) {
            conditionDependencies.insert(metric.condition());
        }
        if (!determineMetricUpdateStatus(
                    config, metric, metric.id(), METRIC_TYPE_EVENT, {metric.what()},
                    conditionDependencies, ::google::protobuf::RepeatedField<int64_t>(),
                    metric.links(), oldMetricProducerMap, oldMetricProducers, metricToActivationMap,
                    replacedMatchers, replacedConditions, replacedStates,
                    metricsToUpdate[metricIndex])) {
            return false;
        }
    }
    for (int i = 0; i < config.value_metric_size(); i++, metricIndex++) {
        const ValueMetric& metric = config.value_metric(i);
        set<int64_t> conditionDependencies;
        if (metric.has_condition()) {
            conditionDependencies.insert(metric.condition());
        }
        if (!determineMetricUpdateStatus(
                    config, metric, metric.id(), METRIC_TYPE_VALUE, {metric.what()},
                    conditionDependencies, metric.slice_by_state(), metric.links(),
                    oldMetricProducerMap, oldMetricProducers, metricToActivationMap,
                    replacedMatchers, replacedConditions, replacedStates,
                    metricsToUpdate[metricIndex])) {
            return false;
        }
    }
    for (int i = 0; i < config.gauge_metric_size(); i++, metricIndex++) {
        const GaugeMetric& metric = config.gauge_metric(i);
        set<int64_t> conditionDependencies;
        if (metric.has_condition()) {
            conditionDependencies.insert(metric.condition());
        }
        set<int64_t> matcherDependencies({metric.what()});
        if (metric.has_trigger_event()) {
            matcherDependencies.insert(metric.trigger_event());
        }
        if (!determineMetricUpdateStatus(
                    config, metric, metric.id(), METRIC_TYPE_GAUGE, matcherDependencies,
                    conditionDependencies, ::google::protobuf::RepeatedField<int64_t>(),
                    metric.links(), oldMetricProducerMap, oldMetricProducers, metricToActivationMap,
                    replacedMatchers, replacedConditions, replacedStates,
                    metricsToUpdate[metricIndex])) {
            return false;
        }
    }
    return true;
}

// Called when a metric is preserved during a config update. Finds the metric in oldMetricProducers
// and calls onConfigUpdated to update all indices.
optional<sp<MetricProducer>> updateMetric(
        const StatsdConfig& config, const int configIndex, const int metricIndex,
        const int64_t metricId, const vector<sp<AtomMatchingTracker>>& allAtomMatchingTrackers,
        const unordered_map<int64_t, int>& oldAtomMatchingTrackerMap,
        const unordered_map<int64_t, int>& newAtomMatchingTrackerMap,
        const sp<EventMatcherWizard>& matcherWizard,
        const vector<sp<ConditionTracker>>& allConditionTrackers,
        const unordered_map<int64_t, int>& conditionTrackerMap, const sp<ConditionWizard>& wizard,
        const unordered_map<int64_t, int>& oldMetricProducerMap,
        const vector<sp<MetricProducer>>& oldMetricProducers,
        const unordered_map<int64_t, int>& metricToActivationMap,
        unordered_map<int, vector<int>>& trackerToMetricMap,
        unordered_map<int, vector<int>>& conditionToMetricMap,
        unordered_map<int, vector<int>>& activationAtomTrackerToMetricMap,
        unordered_map<int, vector<int>>& deactivationAtomTrackerToMetricMap,
        vector<int>& metricsWithActivation) {
    const auto& oldMetricProducerIt = oldMetricProducerMap.find(metricId);
    if (oldMetricProducerIt == oldMetricProducerMap.end()) {
        ALOGE("Could not find Metric %lld in the previous config, but expected it "
              "to be there",
              (long long)metricId);
        return nullopt;
    }
    const int oldIndex = oldMetricProducerIt->second;
    sp<MetricProducer> producer = oldMetricProducers[oldIndex];
    if (!producer->onConfigUpdated(config, configIndex, metricIndex, allAtomMatchingTrackers,
                                   oldAtomMatchingTrackerMap, newAtomMatchingTrackerMap,
                                   matcherWizard, allConditionTrackers, conditionTrackerMap, wizard,
                                   metricToActivationMap, trackerToMetricMap, conditionToMetricMap,
                                   activationAtomTrackerToMetricMap,
                                   deactivationAtomTrackerToMetricMap, metricsWithActivation)) {
        return nullopt;
    }
    return {producer};
}

bool updateMetrics(const ConfigKey& key, const StatsdConfig& config, const int64_t timeBaseNs,
                   const int64_t currentTimeNs, const sp<StatsPullerManager>& pullerManager,
                   const unordered_map<int64_t, int>& oldAtomMatchingTrackerMap,
                   const unordered_map<int64_t, int>& newAtomMatchingTrackerMap,
                   const set<int64_t>& replacedMatchers,
                   const vector<sp<AtomMatchingTracker>>& allAtomMatchingTrackers,
                   const unordered_map<int64_t, int>& conditionTrackerMap,
                   const set<int64_t>& replacedConditions,
                   vector<sp<ConditionTracker>>& allConditionTrackers,
                   const vector<ConditionState>& initialConditionCache,
                   const unordered_map<int64_t, int>& stateAtomIdMap,
                   const unordered_map<int64_t, unordered_map<int, int64_t>>& allStateGroupMaps,
                   const set<int64_t>& replacedStates,
                   const unordered_map<int64_t, int>& oldMetricProducerMap,
                   const vector<sp<MetricProducer>>& oldMetricProducers,
                   unordered_map<int64_t, int>& newMetricProducerMap,
                   vector<sp<MetricProducer>>& newMetricProducers,
                   unordered_map<int, vector<int>>& conditionToMetricMap,
                   unordered_map<int, vector<int>>& trackerToMetricMap,
                   set<int64_t>& noReportMetricIds,
                   unordered_map<int, vector<int>>& activationAtomTrackerToMetricMap,
                   unordered_map<int, vector<int>>& deactivationAtomTrackerToMetricMap,
                   vector<int>& metricsWithActivation, set<int64_t>& replacedMetrics) {
    sp<ConditionWizard> wizard = new ConditionWizard(allConditionTrackers);
    sp<EventMatcherWizard> matcherWizard = new EventMatcherWizard(allAtomMatchingTrackers);
    const int allMetricsCount = config.count_metric_size() + config.duration_metric_size() +
                                config.event_metric_size() + config.gauge_metric_size() +
                                config.value_metric_size();
    newMetricProducers.reserve(allMetricsCount);

    // Construct map from metric id to metric activation index. The map will be used to determine
    // the metric activation corresponding to a metric.
    unordered_map<int64_t, int> metricToActivationMap;
    for (int i = 0; i < config.metric_activation_size(); i++) {
        const MetricActivation& metricActivation = config.metric_activation(i);
        int64_t metricId = metricActivation.metric_id();
        if (metricToActivationMap.find(metricId) != metricToActivationMap.end()) {
            ALOGE("Metric %lld has multiple MetricActivations", (long long)metricId);
            return false;
        }
        metricToActivationMap.insert({metricId, i});
    }

    vector<UpdateStatus> metricsToUpdate(allMetricsCount, UPDATE_UNKNOWN);
    if (!determineAllMetricUpdateStatuses(config, oldMetricProducerMap, oldMetricProducers,
                                          metricToActivationMap, replacedMatchers,
                                          replacedConditions, replacedStates, metricsToUpdate)) {
        return false;
    }

    // Now, perform the update. Must iterate the metric types in the same order
    int metricIndex = 0;
    for (int i = 0; i < config.count_metric_size(); i++, metricIndex++) {
        const CountMetric& metric = config.count_metric(i);
        newMetricProducerMap[metric.id()] = metricIndex;
        optional<sp<MetricProducer>> producer;
        switch (metricsToUpdate[metricIndex]) {
            case UPDATE_PRESERVE: {
                producer = updateMetric(
                        config, i, metricIndex, metric.id(), allAtomMatchingTrackers,
                        oldAtomMatchingTrackerMap, newAtomMatchingTrackerMap, matcherWizard,
                        allConditionTrackers, conditionTrackerMap, wizard, oldMetricProducerMap,
                        oldMetricProducers, metricToActivationMap, trackerToMetricMap,
                        conditionToMetricMap, activationAtomTrackerToMetricMap,
                        deactivationAtomTrackerToMetricMap, metricsWithActivation);
                break;
            }
            case UPDATE_REPLACE:
                replacedMetrics.insert(metric.id());
                [[fallthrough]];  // Intentionally fallthrough to create the new metric producer.
            case UPDATE_NEW: {
                producer = createCountMetricProducerAndUpdateMetadata(
                        key, config, timeBaseNs, currentTimeNs, metric, metricIndex,
                        allAtomMatchingTrackers, newAtomMatchingTrackerMap, allConditionTrackers,
                        conditionTrackerMap, initialConditionCache, wizard, stateAtomIdMap,
                        allStateGroupMaps, metricToActivationMap, trackerToMetricMap,
                        conditionToMetricMap, activationAtomTrackerToMetricMap,
                        deactivationAtomTrackerToMetricMap, metricsWithActivation);
                break;
            }
            default: {
                ALOGE("Metric \"%lld\" update state is unknown. This should never happen",
                      (long long)metric.id());
                return false;
            }
        }
        if (!producer) {
            return false;
        }
        newMetricProducers.push_back(producer.value());
    }
    for (int i = 0; i < config.duration_metric_size(); i++, metricIndex++) {
        const DurationMetric& metric = config.duration_metric(i);
        newMetricProducerMap[metric.id()] = metricIndex;
        optional<sp<MetricProducer>> producer;
        switch (metricsToUpdate[metricIndex]) {
            case UPDATE_PRESERVE: {
                producer = updateMetric(
                        config, i, metricIndex, metric.id(), allAtomMatchingTrackers,
                        oldAtomMatchingTrackerMap, newAtomMatchingTrackerMap, matcherWizard,
                        allConditionTrackers, conditionTrackerMap, wizard, oldMetricProducerMap,
                        oldMetricProducers, metricToActivationMap, trackerToMetricMap,
                        conditionToMetricMap, activationAtomTrackerToMetricMap,
                        deactivationAtomTrackerToMetricMap, metricsWithActivation);
                break;
            }
            case UPDATE_REPLACE:
                replacedMetrics.insert(metric.id());
                [[fallthrough]];  // Intentionally fallthrough to create the new metric producer.
            case UPDATE_NEW: {
                producer = createDurationMetricProducerAndUpdateMetadata(
                        key, config, timeBaseNs, currentTimeNs, metric, metricIndex,
                        allAtomMatchingTrackers, newAtomMatchingTrackerMap, allConditionTrackers,
                        conditionTrackerMap, initialConditionCache, wizard, stateAtomIdMap,
                        allStateGroupMaps, metricToActivationMap, trackerToMetricMap,
                        conditionToMetricMap, activationAtomTrackerToMetricMap,
                        deactivationAtomTrackerToMetricMap, metricsWithActivation);
                break;
            }
            default: {
                ALOGE("Metric \"%lld\" update state is unknown. This should never happen",
                      (long long)metric.id());
                return false;
            }
        }
        if (!producer) {
            return false;
        }
        newMetricProducers.push_back(producer.value());
    }
    for (int i = 0; i < config.event_metric_size(); i++, metricIndex++) {
        const EventMetric& metric = config.event_metric(i);
        newMetricProducerMap[metric.id()] = metricIndex;
        optional<sp<MetricProducer>> producer;
        switch (metricsToUpdate[metricIndex]) {
            case UPDATE_PRESERVE: {
                producer = updateMetric(
                        config, i, metricIndex, metric.id(), allAtomMatchingTrackers,
                        oldAtomMatchingTrackerMap, newAtomMatchingTrackerMap, matcherWizard,
                        allConditionTrackers, conditionTrackerMap, wizard, oldMetricProducerMap,
                        oldMetricProducers, metricToActivationMap, trackerToMetricMap,
                        conditionToMetricMap, activationAtomTrackerToMetricMap,
                        deactivationAtomTrackerToMetricMap, metricsWithActivation);
                break;
            }
            case UPDATE_REPLACE:
                replacedMetrics.insert(metric.id());
                [[fallthrough]];  // Intentionally fallthrough to create the new metric producer.
            case UPDATE_NEW: {
                producer = createEventMetricProducerAndUpdateMetadata(
                        key, config, timeBaseNs, metric, metricIndex, allAtomMatchingTrackers,
                        newAtomMatchingTrackerMap, allConditionTrackers, conditionTrackerMap,
                        initialConditionCache, wizard, metricToActivationMap, trackerToMetricMap,
                        conditionToMetricMap, activationAtomTrackerToMetricMap,
                        deactivationAtomTrackerToMetricMap, metricsWithActivation);
                break;
            }
            default: {
                ALOGE("Metric \"%lld\" update state is unknown. This should never happen",
                      (long long)metric.id());
                return false;
            }
        }
        if (!producer) {
            return false;
        }
        newMetricProducers.push_back(producer.value());
    }

    for (int i = 0; i < config.value_metric_size(); i++, metricIndex++) {
        const ValueMetric& metric = config.value_metric(i);
        newMetricProducerMap[metric.id()] = metricIndex;
        optional<sp<MetricProducer>> producer;
        switch (metricsToUpdate[metricIndex]) {
            case UPDATE_PRESERVE: {
                producer = updateMetric(
                        config, i, metricIndex, metric.id(), allAtomMatchingTrackers,
                        oldAtomMatchingTrackerMap, newAtomMatchingTrackerMap, matcherWizard,
                        allConditionTrackers, conditionTrackerMap, wizard, oldMetricProducerMap,
                        oldMetricProducers, metricToActivationMap, trackerToMetricMap,
                        conditionToMetricMap, activationAtomTrackerToMetricMap,
                        deactivationAtomTrackerToMetricMap, metricsWithActivation);
                break;
            }
            case UPDATE_REPLACE:
                replacedMetrics.insert(metric.id());
                [[fallthrough]];  // Intentionally fallthrough to create the new metric producer.
            case UPDATE_NEW: {
                producer = createValueMetricProducerAndUpdateMetadata(
                        key, config, timeBaseNs, currentTimeNs, pullerManager, metric, metricIndex,
                        allAtomMatchingTrackers, newAtomMatchingTrackerMap, allConditionTrackers,
                        conditionTrackerMap, initialConditionCache, wizard, matcherWizard,
                        stateAtomIdMap, allStateGroupMaps, metricToActivationMap,
                        trackerToMetricMap, conditionToMetricMap, activationAtomTrackerToMetricMap,
                        deactivationAtomTrackerToMetricMap, metricsWithActivation);
                break;
            }
            default: {
                ALOGE("Metric \"%lld\" update state is unknown. This should never happen",
                      (long long)metric.id());
                return false;
            }
        }
        if (!producer) {
            return false;
        }
        newMetricProducers.push_back(producer.value());
    }

    for (int i = 0; i < config.gauge_metric_size(); i++, metricIndex++) {
        const GaugeMetric& metric = config.gauge_metric(i);
        newMetricProducerMap[metric.id()] = metricIndex;
        optional<sp<MetricProducer>> producer;
        switch (metricsToUpdate[metricIndex]) {
            case UPDATE_PRESERVE: {
                producer = updateMetric(
                        config, i, metricIndex, metric.id(), allAtomMatchingTrackers,
                        oldAtomMatchingTrackerMap, newAtomMatchingTrackerMap, matcherWizard,
                        allConditionTrackers, conditionTrackerMap, wizard, oldMetricProducerMap,
                        oldMetricProducers, metricToActivationMap, trackerToMetricMap,
                        conditionToMetricMap, activationAtomTrackerToMetricMap,
                        deactivationAtomTrackerToMetricMap, metricsWithActivation);
                break;
            }
            case UPDATE_REPLACE:
                replacedMetrics.insert(metric.id());
                [[fallthrough]];  // Intentionally fallthrough to create the new metric producer.
            case UPDATE_NEW: {
                producer = createGaugeMetricProducerAndUpdateMetadata(
                        key, config, timeBaseNs, currentTimeNs, pullerManager, metric, metricIndex,
                        allAtomMatchingTrackers, newAtomMatchingTrackerMap, allConditionTrackers,
                        conditionTrackerMap, initialConditionCache, wizard, matcherWizard,
                        metricToActivationMap, trackerToMetricMap, conditionToMetricMap,
                        activationAtomTrackerToMetricMap, deactivationAtomTrackerToMetricMap,
                        metricsWithActivation);
                break;
            }
            default: {
                ALOGE("Metric \"%lld\" update state is unknown. This should never happen",
                      (long long)metric.id());
                return false;
            }
        }
        if (!producer) {
            return false;
        }
        newMetricProducers.push_back(producer.value());
    }

    for (int i = 0; i < config.no_report_metric_size(); ++i) {
        const int64_t noReportMetric = config.no_report_metric(i);
        if (newMetricProducerMap.find(noReportMetric) == newMetricProducerMap.end()) {
            ALOGW("no_report_metric %" PRId64 " not exist", noReportMetric);
            return false;
        }
        noReportMetricIds.insert(noReportMetric);
    }
    const set<int> atomsAllowedFromAnyUid(config.whitelisted_atom_ids().begin(),
                                          config.whitelisted_atom_ids().end());
    for (int i = 0; i < allMetricsCount; i++) {
        sp<MetricProducer> producer = newMetricProducers[i];
        // Register metrics to StateTrackers
        for (int atomId : producer->getSlicedStateAtoms()) {
            // Register listener for atoms that use allowed_log_sources.
            // Using atoms allowed from any uid as a sliced state atom is not allowed.
            // Redo this check for all metrics in case the atoms allowed from any uid changed.
            if (atomsAllowedFromAnyUid.find(atomId) != atomsAllowedFromAnyUid.end()) {
                return false;
                // Preserved metrics should've already registered.`
            } else if (metricsToUpdate[i] != UPDATE_PRESERVE) {
                StateManager::getInstance().registerListener(atomId, producer);
            }
        }
    }

    // Init new/replaced metrics.
    for (size_t i = 0; i < newMetricProducers.size(); i++) {
        if (metricsToUpdate[i] == UPDATE_REPLACE || metricsToUpdate[i] == UPDATE_NEW) {
            newMetricProducers[i]->prepareFirstBucket();
        }
    }
    return true;
}

bool determineAlertUpdateStatus(const Alert& alert,
                                const unordered_map<int64_t, int>& oldAlertTrackerMap,
                                const vector<sp<AnomalyTracker>>& oldAnomalyTrackers,
                                const set<int64_t>& replacedMetrics, UpdateStatus& updateStatus) {
    // Check if new alert.
    const auto& oldAnomalyTrackerIt = oldAlertTrackerMap.find(alert.id());
    if (oldAnomalyTrackerIt == oldAlertTrackerMap.end()) {
        updateStatus = UPDATE_NEW;
        return true;
    }

    // This is an existing alert, check if it has changed.
    string serializedAlert;
    if (!alert.SerializeToString(&serializedAlert)) {
        ALOGW("Unable to serialize alert %lld", (long long)alert.id());
        return false;
    }
    uint64_t newProtoHash = Hash64(serializedAlert);
    const auto [success, oldProtoHash] =
            oldAnomalyTrackers[oldAnomalyTrackerIt->second]->getProtoHash();
    if (!success) {
        return false;
    }
    if (newProtoHash != oldProtoHash) {
        updateStatus = UPDATE_REPLACE;
        return true;
    }

    // Check if the metric this alert relies on has changed.
    if (replacedMetrics.find(alert.metric_id()) != replacedMetrics.end()) {
        updateStatus = UPDATE_REPLACE;
        return true;
    }

    updateStatus = UPDATE_PRESERVE;
    return true;
}

bool updateAlerts(const StatsdConfig& config, const unordered_map<int64_t, int>& metricProducerMap,
                  const set<int64_t>& replacedMetrics,
                  const unordered_map<int64_t, int>& oldAlertTrackerMap,
                  const vector<sp<AnomalyTracker>>& oldAnomalyTrackers,
                  const sp<AlarmMonitor>& anomalyAlarmMonitor,
                  vector<sp<MetricProducer>>& allMetricProducers,
                  unordered_map<int64_t, int>& newAlertTrackerMap,
                  vector<sp<AnomalyTracker>>& newAnomalyTrackers) {
    int alertCount = config.alert_size();
    vector<UpdateStatus> alertUpdateStatuses(alertCount);
    for (int i = 0; i < alertCount; i++) {
        if (!determineAlertUpdateStatus(config.alert(i), oldAlertTrackerMap, oldAnomalyTrackers,
                                        replacedMetrics, alertUpdateStatuses[i])) {
            return false;
        }
    }

    for (int i = 0; i < alertCount; i++) {
        const Alert& alert = config.alert(i);
        newAlertTrackerMap[alert.id()] = newAnomalyTrackers.size();
        switch (alertUpdateStatuses[i]) {
            case UPDATE_PRESERVE: {
                // Find the alert and update it.
                const auto& oldAnomalyTrackerIt = oldAlertTrackerMap.find(alert.id());
                if (oldAnomalyTrackerIt == oldAlertTrackerMap.end()) {
                    ALOGW("Could not find AnomalyTracker %lld in the previous config, but "
                          "expected it to be there",
                          (long long)alert.id());
                    return false;
                }
                sp<AnomalyTracker> anomalyTracker = oldAnomalyTrackers[oldAnomalyTrackerIt->second];
                anomalyTracker->onConfigUpdated();
                // Add the alert to the relevant metric.
                const auto& metricProducerIt = metricProducerMap.find(alert.metric_id());
                if (metricProducerIt == metricProducerMap.end()) {
                    ALOGW("alert \"%lld\" has unknown metric id: \"%lld\"", (long long)alert.id(),
                          (long long)alert.metric_id());
                    return false;
                }
                allMetricProducers[metricProducerIt->second]->addAnomalyTracker(anomalyTracker);
                newAnomalyTrackers.push_back(anomalyTracker);
                break;
            }
            case UPDATE_REPLACE:
            case UPDATE_NEW: {
                optional<sp<AnomalyTracker>> anomalyTracker = createAnomalyTracker(
                        alert, anomalyAlarmMonitor, metricProducerMap, allMetricProducers);
                if (!anomalyTracker) {
                    return false;
                }
                newAnomalyTrackers.push_back(anomalyTracker.value());
                break;
            }
            default: {
                ALOGE("Alert \"%lld\" update state is unknown. This should never happen",
                      (long long)alert.id());
                return false;
            }
        }
    }
    if (!initSubscribersForSubscriptionType(config, Subscription::ALERT, newAlertTrackerMap,
                                            newAnomalyTrackers)) {
        return false;
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
                        const vector<sp<MetricProducer>>& oldMetricProducers,
                        const unordered_map<int64_t, int>& oldMetricProducerMap,
                        const vector<sp<AnomalyTracker>>& oldAnomalyTrackers,
                        const unordered_map<int64_t, int>& oldAlertTrackerMap,
                        const map<int64_t, uint64_t>& oldStateProtoHashes, set<int>& allTagIds,
                        vector<sp<AtomMatchingTracker>>& newAtomMatchingTrackers,
                        unordered_map<int64_t, int>& newAtomMatchingTrackerMap,
                        vector<sp<ConditionTracker>>& newConditionTrackers,
                        unordered_map<int64_t, int>& newConditionTrackerMap,
                        vector<sp<MetricProducer>>& newMetricProducers,
                        unordered_map<int64_t, int>& newMetricProducerMap,
                        vector<sp<AnomalyTracker>>& newAnomalyTrackers,
                        unordered_map<int64_t, int>& newAlertTrackerMap,
                        vector<sp<AlarmTracker>>& newPeriodicAlarmTrackers,
                        unordered_map<int, vector<int>>& conditionToMetricMap,
                        unordered_map<int, vector<int>>& trackerToMetricMap,
                        unordered_map<int, vector<int>>& trackerToConditionMap,
                        unordered_map<int, vector<int>>& activationTrackerToMetricMap,
                        unordered_map<int, vector<int>>& deactivationTrackerToMetricMap,
                        vector<int>& metricsWithActivation,
                        map<int64_t, uint64_t>& newStateProtoHashes,
                        set<int64_t>& noReportMetricIds) {
    set<int64_t> replacedMatchers;
    set<int64_t> replacedConditions;
    set<int64_t> replacedMetrics;
    vector<ConditionState> conditionCache;
    unordered_map<int64_t, int> stateAtomIdMap;
    unordered_map<int64_t, unordered_map<int, int64_t>> allStateGroupMaps;

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

    // Share with metrics_manager_util,
    if (!initStates(config, stateAtomIdMap, allStateGroupMaps, newStateProtoHashes)) {
        ALOGE("initStates failed");
        return false;
    }

    set<int64_t> replacedStates;
    for (const auto& [stateId, stateHash] : oldStateProtoHashes) {
        const auto& it = newStateProtoHashes.find(stateId);
        if (it != newStateProtoHashes.end() && it->second != stateHash) {
            replacedStates.insert(stateId);
        }
    }
    if (!updateMetrics(key, config, timeBaseNs, currentTimeNs, pullerManager,
                       oldAtomMatchingTrackerMap, newAtomMatchingTrackerMap, replacedMatchers,
                       newAtomMatchingTrackers, newConditionTrackerMap, replacedConditions,
                       newConditionTrackers, conditionCache, stateAtomIdMap, allStateGroupMaps,
                       replacedStates, oldMetricProducerMap, oldMetricProducers,
                       newMetricProducerMap, newMetricProducers, conditionToMetricMap,
                       trackerToMetricMap, noReportMetricIds, activationTrackerToMetricMap,
                       deactivationTrackerToMetricMap, metricsWithActivation, replacedMetrics)) {
        ALOGE("updateMetrics failed");
        return false;
    }

    if (!updateAlerts(config, newMetricProducerMap, replacedMetrics, oldAlertTrackerMap,
                      oldAnomalyTrackers, anomalyAlarmMonitor, newMetricProducers,
                      newAlertTrackerMap, newAnomalyTrackers)) {
        ALOGE("updateAlerts failed");
        return false;
    }

    // Alarms do not have any state, so we can reuse the initialization logic.
    if (!initAlarms(config, key, periodicAlarmMonitor, timeBaseNs, currentTimeNs,
                    newPeriodicAlarmTrackers)) {
        ALOGE("initAlarms failed");
        return false;
    }
    return true;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
