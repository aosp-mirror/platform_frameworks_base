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

#define DEBUG false  // STOPSHIP if true
#include "Log.h"

#include "metrics_manager_util.h"

#include <inttypes.h>

#include "FieldValue.h"
#include "condition/CombinationConditionTracker.h"
#include "condition/SimpleConditionTracker.h"
#include "external/StatsPullerManager.h"
#include "hash.h"
#include "matchers/CombinationAtomMatchingTracker.h"
#include "matchers/EventMatcherWizard.h"
#include "matchers/SimpleAtomMatchingTracker.h"
#include "metrics/CountMetricProducer.h"
#include "metrics/DurationMetricProducer.h"
#include "metrics/EventMetricProducer.h"
#include "metrics/GaugeMetricProducer.h"
#include "metrics/MetricProducer.h"
#include "metrics/ValueMetricProducer.h"
#include "state/StateManager.h"
#include "stats_util.h"

using google::protobuf::MessageLite;
using std::set;
using std::unordered_map;
using std::vector;

namespace android {
namespace os {
namespace statsd {

namespace {

bool hasLeafNode(const FieldMatcher& matcher) {
    if (!matcher.has_field()) {
        return false;
    }
    for (int i = 0; i < matcher.child_size(); ++i) {
        if (hasLeafNode(matcher.child(i))) {
            return true;
        }
    }
    return true;
}

}  // namespace

sp<AtomMatchingTracker> createAtomMatchingTracker(const AtomMatcher& logMatcher, const int index,
                                                  const sp<UidMap>& uidMap) {
    string serializedMatcher;
    if (!logMatcher.SerializeToString(&serializedMatcher)) {
        ALOGE("Unable to serialize matcher %lld", (long long)logMatcher.id());
        return nullptr;
    }
    uint64_t protoHash = Hash64(serializedMatcher);
    switch (logMatcher.contents_case()) {
        case AtomMatcher::ContentsCase::kSimpleAtomMatcher:
            return new SimpleAtomMatchingTracker(logMatcher.id(), index, protoHash,
                                                 logMatcher.simple_atom_matcher(), uidMap);
        case AtomMatcher::ContentsCase::kCombination:
            return new CombinationAtomMatchingTracker(logMatcher.id(), index, protoHash);
        default:
            ALOGE("Matcher \"%lld\" malformed", (long long)logMatcher.id());
            return nullptr;
    }
}

sp<ConditionTracker> createConditionTracker(
        const ConfigKey& key, const Predicate& predicate, const int index,
        const unordered_map<int64_t, int>& atomMatchingTrackerMap) {
    string serializedPredicate;
    if (!predicate.SerializeToString(&serializedPredicate)) {
        ALOGE("Unable to serialize predicate %lld", (long long)predicate.id());
        return nullptr;
    }
    uint64_t protoHash = Hash64(serializedPredicate);
    switch (predicate.contents_case()) {
        case Predicate::ContentsCase::kSimplePredicate: {
            return new SimpleConditionTracker(key, predicate.id(), protoHash, index,
                                              predicate.simple_predicate(), atomMatchingTrackerMap);
        }
        case Predicate::ContentsCase::kCombination: {
            return new CombinationConditionTracker(predicate.id(), index, protoHash);
        }
        default:
            ALOGE("Predicate \"%lld\" malformed", (long long)predicate.id());
            return nullptr;
    }
}

bool getMetricProtoHash(const StatsdConfig& config, const MessageLite& metric, const int64_t id,
                        const unordered_map<int64_t, int>& metricToActivationMap,
                        uint64_t& metricHash) {
    string serializedMetric;
    if (!metric.SerializeToString(&serializedMetric)) {
        ALOGE("Unable to serialize metric %lld", (long long)id);
        return false;
    }
    metricHash = Hash64(serializedMetric);

    // Combine with activation hash, if applicable
    const auto& metricActivationIt = metricToActivationMap.find(id);
    if (metricActivationIt != metricToActivationMap.end()) {
        string serializedActivation;
        const MetricActivation& activation = config.metric_activation(metricActivationIt->second);
        if (!activation.SerializeToString(&serializedActivation)) {
            ALOGE("Unable to serialize metric activation for metric %lld", (long long)id);
            return false;
        }
        metricHash = Hash64(to_string(metricHash).append(to_string(Hash64(serializedActivation))));
    }
    return true;
}

bool handleMetricWithAtomMatchingTrackers(
        const int64_t matcherId, const int metricIndex, const bool enforceOneAtom,
        const vector<sp<AtomMatchingTracker>>& allAtomMatchingTrackers,
        const unordered_map<int64_t, int>& atomMatchingTrackerMap,
        unordered_map<int, vector<int>>& trackerToMetricMap, int& logTrackerIndex) {
    auto logTrackerIt = atomMatchingTrackerMap.find(matcherId);
    if (logTrackerIt == atomMatchingTrackerMap.end()) {
        ALOGW("cannot find the AtomMatcher \"%lld\" in config", (long long)matcherId);
        return false;
    }
    if (enforceOneAtom && allAtomMatchingTrackers[logTrackerIt->second]->getAtomIds().size() > 1) {
        ALOGE("AtomMatcher \"%lld\" has more than one tag ids. When a metric has dimension, "
              "the \"what\" can only be about one atom type. trigger_event matchers can also only "
              "be about one atom type.",
              (long long)matcherId);
        return false;
    }
    logTrackerIndex = logTrackerIt->second;
    auto& metric_list = trackerToMetricMap[logTrackerIndex];
    metric_list.push_back(metricIndex);
    return true;
}

bool handleMetricWithConditions(
        const int64_t condition, const int metricIndex,
        const unordered_map<int64_t, int>& conditionTrackerMap,
        const ::google::protobuf::RepeatedPtrField<::android::os::statsd::MetricConditionLink>&
                links,
        const vector<sp<ConditionTracker>>& allConditionTrackers, int& conditionIndex,
        unordered_map<int, vector<int>>& conditionToMetricMap) {
    auto condition_it = conditionTrackerMap.find(condition);
    if (condition_it == conditionTrackerMap.end()) {
        ALOGW("cannot find Predicate \"%lld\" in the config", (long long)condition);
        return false;
    }

    for (const auto& link : links) {
        auto it = conditionTrackerMap.find(link.condition());
        if (it == conditionTrackerMap.end()) {
            ALOGW("cannot find Predicate \"%lld\" in the config", (long long)link.condition());
            return false;
        }
    }
    conditionIndex = condition_it->second;

    // will create new vector if not exist before.
    auto& metricList = conditionToMetricMap[condition_it->second];
    metricList.push_back(metricIndex);
    return true;
}

// Initializes state data structures for a metric.
// input:
// [config]: the input config
// [stateIds]: the slice_by_state ids for this metric
// [stateAtomIdMap]: this map contains the mapping from all state ids to atom ids
// [allStateGroupMaps]: this map contains the mapping from state ids and state
//                      values to state group ids for all states
// output:
// [slicedStateAtoms]: a vector of atom ids of all the slice_by_states
// [stateGroupMap]: this map should contain the mapping from states ids and state
//                      values to state group ids for all states that this metric
//                      is interested in
bool handleMetricWithStates(
        const StatsdConfig& config, const ::google::protobuf::RepeatedField<int64_t>& stateIds,
        const unordered_map<int64_t, int>& stateAtomIdMap,
        const unordered_map<int64_t, unordered_map<int, int64_t>>& allStateGroupMaps,
        vector<int>& slicedStateAtoms,
        unordered_map<int, unordered_map<int, int64_t>>& stateGroupMap) {
    for (const auto& stateId : stateIds) {
        auto it = stateAtomIdMap.find(stateId);
        if (it == stateAtomIdMap.end()) {
            ALOGW("cannot find State %" PRId64 " in the config", stateId);
            return false;
        }
        int atomId = it->second;
        slicedStateAtoms.push_back(atomId);

        auto stateIt = allStateGroupMaps.find(stateId);
        if (stateIt != allStateGroupMaps.end()) {
            stateGroupMap[atomId] = stateIt->second;
        }
    }
    return true;
}

bool handleMetricWithStateLink(const FieldMatcher& stateMatcher,
                               const vector<Matcher>& dimensionsInWhat) {
    vector<Matcher> stateMatchers;
    translateFieldMatcher(stateMatcher, &stateMatchers);

    return subsetDimensions(stateMatchers, dimensionsInWhat);
}

// Validates a metricActivation and populates state.
// EventActivationMap and EventDeactivationMap are supplied to a MetricProducer
//      to provide the producer with state about its activators and deactivators.
// Returns false if there are errors.
bool handleMetricActivation(
        const StatsdConfig& config, const int64_t metricId, const int metricIndex,
        const unordered_map<int64_t, int>& metricToActivationMap,
        const unordered_map<int64_t, int>& atomMatchingTrackerMap,
        unordered_map<int, vector<int>>& activationAtomTrackerToMetricMap,
        unordered_map<int, vector<int>>& deactivationAtomTrackerToMetricMap,
        vector<int>& metricsWithActivation,
        unordered_map<int, shared_ptr<Activation>>& eventActivationMap,
        unordered_map<int, vector<shared_ptr<Activation>>>& eventDeactivationMap) {
    // Check if metric has an associated activation
    auto itr = metricToActivationMap.find(metricId);
    if (itr == metricToActivationMap.end()) {
        return true;
    }

    int activationIndex = itr->second;
    const MetricActivation& metricActivation = config.metric_activation(activationIndex);

    for (int i = 0; i < metricActivation.event_activation_size(); i++) {
        const EventActivation& activation = metricActivation.event_activation(i);

        auto itr = atomMatchingTrackerMap.find(activation.atom_matcher_id());
        if (itr == atomMatchingTrackerMap.end()) {
            ALOGE("Atom matcher not found for event activation.");
            return false;
        }

        ActivationType activationType = (activation.has_activation_type())
                                                ? activation.activation_type()
                                                : metricActivation.activation_type();
        std::shared_ptr<Activation> activationWrapper =
                std::make_shared<Activation>(activationType, activation.ttl_seconds() * NS_PER_SEC);

        int atomMatcherIndex = itr->second;
        activationAtomTrackerToMetricMap[atomMatcherIndex].push_back(metricIndex);
        eventActivationMap.emplace(atomMatcherIndex, activationWrapper);

        if (activation.has_deactivation_atom_matcher_id()) {
            itr = atomMatchingTrackerMap.find(activation.deactivation_atom_matcher_id());
            if (itr == atomMatchingTrackerMap.end()) {
                ALOGE("Atom matcher not found for event deactivation.");
                return false;
            }
            int deactivationAtomMatcherIndex = itr->second;
            deactivationAtomTrackerToMetricMap[deactivationAtomMatcherIndex].push_back(metricIndex);
            eventDeactivationMap[deactivationAtomMatcherIndex].push_back(activationWrapper);
        }
    }

    metricsWithActivation.push_back(metricIndex);
    return true;
}

// Validates a metricActivation and populates state.
// Fills the new event activation/deactivation maps, preserving the existing activations
// Returns false if there are errors.
bool handleMetricActivationOnConfigUpdate(
        const StatsdConfig& config, const int64_t metricId, const int metricIndex,
        const unordered_map<int64_t, int>& metricToActivationMap,
        const unordered_map<int64_t, int>& oldAtomMatchingTrackerMap,
        const unordered_map<int64_t, int>& newAtomMatchingTrackerMap,
        const unordered_map<int, shared_ptr<Activation>>& oldEventActivationMap,
        unordered_map<int, vector<int>>& activationAtomTrackerToMetricMap,
        unordered_map<int, vector<int>>& deactivationAtomTrackerToMetricMap,
        vector<int>& metricsWithActivation,
        unordered_map<int, shared_ptr<Activation>>& newEventActivationMap,
        unordered_map<int, vector<shared_ptr<Activation>>>& newEventDeactivationMap) {
    // Check if metric has an associated activation.
    const auto& itr = metricToActivationMap.find(metricId);
    if (itr == metricToActivationMap.end()) {
        return true;
    }

    int activationIndex = itr->second;
    const MetricActivation& metricActivation = config.metric_activation(activationIndex);

    for (int i = 0; i < metricActivation.event_activation_size(); i++) {
        const int64_t activationMatcherId = metricActivation.event_activation(i).atom_matcher_id();

        const auto& newActivationIt = newAtomMatchingTrackerMap.find(activationMatcherId);
        if (newActivationIt == newAtomMatchingTrackerMap.end()) {
            ALOGE("Atom matcher not found in new config for event activation.");
            return false;
        }
        int newActivationMatcherIndex = newActivationIt->second;

        // Find the old activation struct and copy it over.
        const auto& oldActivationIt = oldAtomMatchingTrackerMap.find(activationMatcherId);
        if (oldActivationIt == oldAtomMatchingTrackerMap.end()) {
            ALOGE("Atom matcher not found in existing config for event activation.");
            return false;
        }
        int oldActivationMatcherIndex = oldActivationIt->second;
        const auto& oldEventActivationIt = oldEventActivationMap.find(oldActivationMatcherIndex);
        if (oldEventActivationIt == oldEventActivationMap.end()) {
            ALOGE("Could not find existing event activation to update");
            return false;
        }
        newEventActivationMap.emplace(newActivationMatcherIndex, oldEventActivationIt->second);
        activationAtomTrackerToMetricMap[newActivationMatcherIndex].push_back(metricIndex);

        if (metricActivation.event_activation(i).has_deactivation_atom_matcher_id()) {
            const int64_t deactivationMatcherId =
                    metricActivation.event_activation(i).deactivation_atom_matcher_id();
            const auto& newDeactivationIt = newAtomMatchingTrackerMap.find(deactivationMatcherId);
            if (newDeactivationIt == newAtomMatchingTrackerMap.end()) {
                ALOGE("Deactivation atom matcher not found in new config for event activation.");
                return false;
            }
            int newDeactivationMatcherIndex = newDeactivationIt->second;
            newEventDeactivationMap[newDeactivationMatcherIndex].push_back(
                    oldEventActivationIt->second);
            deactivationAtomTrackerToMetricMap[newDeactivationMatcherIndex].push_back(metricIndex);
        }
    }

    metricsWithActivation.push_back(metricIndex);
    return true;
}

optional<sp<MetricProducer>> createCountMetricProducerAndUpdateMetadata(
        const ConfigKey& key, const StatsdConfig& config, const int64_t timeBaseNs,
        const int64_t currentTimeNs, const CountMetric& metric, const int metricIndex,
        const vector<sp<AtomMatchingTracker>>& allAtomMatchingTrackers,
        const unordered_map<int64_t, int>& atomMatchingTrackerMap,
        vector<sp<ConditionTracker>>& allConditionTrackers,
        const unordered_map<int64_t, int>& conditionTrackerMap,
        const vector<ConditionState>& initialConditionCache, const sp<ConditionWizard>& wizard,
        const unordered_map<int64_t, int>& stateAtomIdMap,
        const unordered_map<int64_t, unordered_map<int, int64_t>>& allStateGroupMaps,
        const unordered_map<int64_t, int>& metricToActivationMap,
        unordered_map<int, vector<int>>& trackerToMetricMap,
        unordered_map<int, vector<int>>& conditionToMetricMap,
        unordered_map<int, vector<int>>& activationAtomTrackerToMetricMap,
        unordered_map<int, vector<int>>& deactivationAtomTrackerToMetricMap,
        vector<int>& metricsWithActivation) {
    if (!metric.has_id() || !metric.has_what()) {
        ALOGE("cannot find metric id or \"what\" in CountMetric \"%lld\"", (long long)metric.id());
        return nullopt;
    }
    int trackerIndex;
    if (!handleMetricWithAtomMatchingTrackers(metric.what(), metricIndex,
                                              metric.has_dimensions_in_what(),
                                              allAtomMatchingTrackers, atomMatchingTrackerMap,
                                              trackerToMetricMap, trackerIndex)) {
        return nullopt;
    }

    int conditionIndex = -1;
    if (metric.has_condition()) {
        if (!handleMetricWithConditions(metric.condition(), metricIndex, conditionTrackerMap,
                                        metric.links(), allConditionTrackers, conditionIndex,
                                        conditionToMetricMap)) {
            return nullopt;
        }
    } else {
        if (metric.links_size() > 0) {
            ALOGW("metrics has a MetricConditionLink but doesn't have a condition");
            return nullopt;
        }
    }

    std::vector<int> slicedStateAtoms;
    unordered_map<int, unordered_map<int, int64_t>> stateGroupMap;
    if (metric.slice_by_state_size() > 0) {
        if (!handleMetricWithStates(config, metric.slice_by_state(), stateAtomIdMap,
                                    allStateGroupMaps, slicedStateAtoms, stateGroupMap)) {
            return nullopt;
        }
    } else {
        if (metric.state_link_size() > 0) {
            ALOGW("CountMetric has a MetricStateLink but doesn't have a slice_by_state");
            return nullopt;
        }
    }

    unordered_map<int, shared_ptr<Activation>> eventActivationMap;
    unordered_map<int, vector<shared_ptr<Activation>>> eventDeactivationMap;
    if (!handleMetricActivation(config, metric.id(), metricIndex, metricToActivationMap,
                                atomMatchingTrackerMap, activationAtomTrackerToMetricMap,
                                deactivationAtomTrackerToMetricMap, metricsWithActivation,
                                eventActivationMap, eventDeactivationMap)) {
        return nullopt;
    }

    uint64_t metricHash;
    if (!getMetricProtoHash(config, metric, metric.id(), metricToActivationMap, metricHash)) {
        return nullopt;
    }

    return {new CountMetricProducer(key, metric, conditionIndex, initialConditionCache, wizard,
                                    metricHash, timeBaseNs, currentTimeNs, eventActivationMap,
                                    eventDeactivationMap, slicedStateAtoms, stateGroupMap)};
}

optional<sp<MetricProducer>> createDurationMetricProducerAndUpdateMetadata(
        const ConfigKey& key, const StatsdConfig& config, const int64_t timeBaseNs,
        const int64_t currentTimeNs, const DurationMetric& metric, const int metricIndex,
        const vector<sp<AtomMatchingTracker>>& allAtomMatchingTrackers,
        const unordered_map<int64_t, int>& atomMatchingTrackerMap,
        vector<sp<ConditionTracker>>& allConditionTrackers,
        const unordered_map<int64_t, int>& conditionTrackerMap,
        const vector<ConditionState>& initialConditionCache, const sp<ConditionWizard>& wizard,
        const unordered_map<int64_t, int>& stateAtomIdMap,
        const unordered_map<int64_t, unordered_map<int, int64_t>>& allStateGroupMaps,
        const unordered_map<int64_t, int>& metricToActivationMap,
        unordered_map<int, vector<int>>& trackerToMetricMap,
        unordered_map<int, vector<int>>& conditionToMetricMap,
        unordered_map<int, vector<int>>& activationAtomTrackerToMetricMap,
        unordered_map<int, vector<int>>& deactivationAtomTrackerToMetricMap,
        vector<int>& metricsWithActivation) {
    if (!metric.has_id() || !metric.has_what()) {
        ALOGE("cannot find metric id or \"what\" in DurationMetric \"%lld\"",
              (long long)metric.id());
        return nullopt;
    }
    const auto& what_it = conditionTrackerMap.find(metric.what());
    if (what_it == conditionTrackerMap.end()) {
        ALOGE("DurationMetric's \"what\" is not present in the condition trackers");
        return nullopt;
    }

    const int whatIndex = what_it->second;
    const Predicate& durationWhat = config.predicate(whatIndex);
    if (durationWhat.contents_case() != Predicate::ContentsCase::kSimplePredicate) {
        ALOGE("DurationMetric's \"what\" must be a simple condition");
        return nullopt;
    }

    const SimplePredicate& simplePredicate = durationWhat.simple_predicate();
    bool nesting = simplePredicate.count_nesting();

    int startIndex = -1, stopIndex = -1, stopAllIndex = -1;
    if (!simplePredicate.has_start() ||
        !handleMetricWithAtomMatchingTrackers(
                simplePredicate.start(), metricIndex, metric.has_dimensions_in_what(),
                allAtomMatchingTrackers, atomMatchingTrackerMap, trackerToMetricMap, startIndex)) {
        ALOGE("Duration metrics must specify a valid start event matcher");
        return nullopt;
    }

    if (simplePredicate.has_stop() &&
        !handleMetricWithAtomMatchingTrackers(
                simplePredicate.stop(), metricIndex, metric.has_dimensions_in_what(),
                allAtomMatchingTrackers, atomMatchingTrackerMap, trackerToMetricMap, stopIndex)) {
        return nullopt;
    }

    if (simplePredicate.has_stop_all() &&
        !handleMetricWithAtomMatchingTrackers(simplePredicate.stop_all(), metricIndex,
                                              metric.has_dimensions_in_what(),
                                              allAtomMatchingTrackers, atomMatchingTrackerMap,
                                              trackerToMetricMap, stopAllIndex)) {
        return nullopt;
    }

    FieldMatcher internalDimensions = simplePredicate.dimensions();

    int conditionIndex = -1;
    if (metric.has_condition()) {
        if (!handleMetricWithConditions(metric.condition(), metricIndex, conditionTrackerMap,
                                        metric.links(), allConditionTrackers, conditionIndex,
                                        conditionToMetricMap)) {
            return nullopt;
        }
    } else if (metric.links_size() > 0) {
        ALOGW("metrics has a MetricConditionLink but doesn't have a condition");
        return nullopt;
    }

    std::vector<int> slicedStateAtoms;
    unordered_map<int, unordered_map<int, int64_t>> stateGroupMap;
    if (metric.slice_by_state_size() > 0) {
        if (metric.aggregation_type() == DurationMetric::MAX_SPARSE) {
            ALOGE("DurationMetric with aggregation type MAX_SPARSE cannot be sliced by state");
            return nullopt;
        }
        if (!handleMetricWithStates(config, metric.slice_by_state(), stateAtomIdMap,
                                    allStateGroupMaps, slicedStateAtoms, stateGroupMap)) {
            return nullopt;
        }
    } else if (metric.state_link_size() > 0) {
        ALOGW("DurationMetric has a MetricStateLink but doesn't have a sliced state");
        return nullopt;
    }

    // Check that all metric state links are a subset of dimensions_in_what fields.
    std::vector<Matcher> dimensionsInWhat;
    translateFieldMatcher(metric.dimensions_in_what(), &dimensionsInWhat);
    for (const auto& stateLink : metric.state_link()) {
        if (!handleMetricWithStateLink(stateLink.fields_in_what(), dimensionsInWhat)) {
            ALOGW("DurationMetric's MetricStateLinks must be a subset of dimensions in what");
            return nullopt;
        }
    }

    unordered_map<int, shared_ptr<Activation>> eventActivationMap;
    unordered_map<int, vector<shared_ptr<Activation>>> eventDeactivationMap;
    if (!handleMetricActivation(config, metric.id(), metricIndex, metricToActivationMap,
                                atomMatchingTrackerMap, activationAtomTrackerToMetricMap,
                                deactivationAtomTrackerToMetricMap, metricsWithActivation,
                                eventActivationMap, eventDeactivationMap)) {
        return nullopt;
    }

    uint64_t metricHash;
    if (!getMetricProtoHash(config, metric, metric.id(), metricToActivationMap, metricHash)) {
        return nullopt;
    }

    sp<MetricProducer> producer = new DurationMetricProducer(
            key, metric, conditionIndex, initialConditionCache, whatIndex, startIndex, stopIndex,
            stopAllIndex, nesting, wizard, metricHash, internalDimensions, timeBaseNs,
            currentTimeNs, eventActivationMap, eventDeactivationMap, slicedStateAtoms,
            stateGroupMap);
    if (!producer->isValid()) {
        return nullopt;
    }
    return {producer};
}

optional<sp<MetricProducer>> createEventMetricProducerAndUpdateMetadata(
        const ConfigKey& key, const StatsdConfig& config, const int64_t timeBaseNs,
        const EventMetric& metric, const int metricIndex,
        const vector<sp<AtomMatchingTracker>>& allAtomMatchingTrackers,
        const unordered_map<int64_t, int>& atomMatchingTrackerMap,
        vector<sp<ConditionTracker>>& allConditionTrackers,
        const unordered_map<int64_t, int>& conditionTrackerMap,
        const vector<ConditionState>& initialConditionCache, const sp<ConditionWizard>& wizard,
        const unordered_map<int64_t, int>& metricToActivationMap,
        unordered_map<int, vector<int>>& trackerToMetricMap,
        unordered_map<int, vector<int>>& conditionToMetricMap,
        unordered_map<int, vector<int>>& activationAtomTrackerToMetricMap,
        unordered_map<int, vector<int>>& deactivationAtomTrackerToMetricMap,
        vector<int>& metricsWithActivation) {
    if (!metric.has_id() || !metric.has_what()) {
        ALOGE("cannot find the metric name or what in config");
        return nullopt;
    }
    int trackerIndex;
    if (!handleMetricWithAtomMatchingTrackers(metric.what(), metricIndex, false,
                                              allAtomMatchingTrackers, atomMatchingTrackerMap,
                                              trackerToMetricMap, trackerIndex)) {
        return nullopt;
    }

    int conditionIndex = -1;
    if (metric.has_condition()) {
        if (!handleMetricWithConditions(metric.condition(), metricIndex, conditionTrackerMap,
                                        metric.links(), allConditionTrackers, conditionIndex,
                                        conditionToMetricMap)) {
            return nullopt;
        }
    } else {
        if (metric.links_size() > 0) {
            ALOGW("metrics has a MetricConditionLink but doesn't have a condition");
            return nullopt;
        }
    }

    unordered_map<int, shared_ptr<Activation>> eventActivationMap;
    unordered_map<int, vector<shared_ptr<Activation>>> eventDeactivationMap;
    bool success = handleMetricActivation(config, metric.id(), metricIndex, metricToActivationMap,
                                          atomMatchingTrackerMap, activationAtomTrackerToMetricMap,
                                          deactivationAtomTrackerToMetricMap, metricsWithActivation,
                                          eventActivationMap, eventDeactivationMap);
    if (!success) return nullptr;

    uint64_t metricHash;
    if (!getMetricProtoHash(config, metric, metric.id(), metricToActivationMap, metricHash)) {
        return nullopt;
    }

    return {new EventMetricProducer(key, metric, conditionIndex, initialConditionCache, wizard,
                                    metricHash, timeBaseNs, eventActivationMap,
                                    eventDeactivationMap)};
}

optional<sp<MetricProducer>> createValueMetricProducerAndUpdateMetadata(
        const ConfigKey& key, const StatsdConfig& config, const int64_t timeBaseNs,
        const int64_t currentTimeNs, const sp<StatsPullerManager>& pullerManager,
        const ValueMetric& metric, const int metricIndex,
        const vector<sp<AtomMatchingTracker>>& allAtomMatchingTrackers,
        const unordered_map<int64_t, int>& atomMatchingTrackerMap,
        vector<sp<ConditionTracker>>& allConditionTrackers,
        const unordered_map<int64_t, int>& conditionTrackerMap,
        const vector<ConditionState>& initialConditionCache, const sp<ConditionWizard>& wizard,
        const sp<EventMatcherWizard>& matcherWizard,
        const unordered_map<int64_t, int>& stateAtomIdMap,
        const unordered_map<int64_t, unordered_map<int, int64_t>>& allStateGroupMaps,
        const unordered_map<int64_t, int>& metricToActivationMap,
        unordered_map<int, vector<int>>& trackerToMetricMap,
        unordered_map<int, vector<int>>& conditionToMetricMap,
        unordered_map<int, vector<int>>& activationAtomTrackerToMetricMap,
        unordered_map<int, vector<int>>& deactivationAtomTrackerToMetricMap,
        vector<int>& metricsWithActivation) {
    if (!metric.has_id() || !metric.has_what()) {
        ALOGE("cannot find metric id or \"what\" in ValueMetric \"%lld\"", (long long)metric.id());
        return nullopt;
    }
    if (!metric.has_value_field()) {
        ALOGE("cannot find \"value_field\" in ValueMetric \"%lld\"", (long long)metric.id());
        return nullopt;
    }
    std::vector<Matcher> fieldMatchers;
    translateFieldMatcher(metric.value_field(), &fieldMatchers);
    if (fieldMatchers.size() < 1) {
        ALOGE("incorrect \"value_field\" in ValueMetric \"%lld\"", (long long)metric.id());
        return nullopt;
    }

    int trackerIndex;
    if (!handleMetricWithAtomMatchingTrackers(metric.what(), metricIndex,
                                              metric.has_dimensions_in_what(),
                                              allAtomMatchingTrackers, atomMatchingTrackerMap,
                                              trackerToMetricMap, trackerIndex)) {
        return nullopt;
    }

    sp<AtomMatchingTracker> atomMatcher = allAtomMatchingTrackers.at(trackerIndex);
    // If it is pulled atom, it should be simple matcher with one tagId.
    if (atomMatcher->getAtomIds().size() != 1) {
        return nullopt;
    }
    int atomTagId = *(atomMatcher->getAtomIds().begin());
    int pullTagId = pullerManager->PullerForMatcherExists(atomTagId) ? atomTagId : -1;

    int conditionIndex = -1;
    if (metric.has_condition()) {
        if (!handleMetricWithConditions(metric.condition(), metricIndex, conditionTrackerMap,
                                        metric.links(), allConditionTrackers, conditionIndex,
                                        conditionToMetricMap)) {
            return nullopt;
        }
    } else if (metric.links_size() > 0) {
        ALOGE("metrics has a MetricConditionLink but doesn't have a condition");
        return nullopt;
    }

    std::vector<int> slicedStateAtoms;
    unordered_map<int, unordered_map<int, int64_t>> stateGroupMap;
    if (metric.slice_by_state_size() > 0) {
        if (!handleMetricWithStates(config, metric.slice_by_state(), stateAtomIdMap,
                                    allStateGroupMaps, slicedStateAtoms, stateGroupMap)) {
            return nullopt;
        }
    } else if (metric.state_link_size() > 0) {
        ALOGE("ValueMetric has a MetricStateLink but doesn't have a sliced state");
        return nullopt;
    }

    // Check that all metric state links are a subset of dimensions_in_what fields.
    std::vector<Matcher> dimensionsInWhat;
    translateFieldMatcher(metric.dimensions_in_what(), &dimensionsInWhat);
    for (const auto& stateLink : metric.state_link()) {
        if (!handleMetricWithStateLink(stateLink.fields_in_what(), dimensionsInWhat)) {
            ALOGW("ValueMetric's MetricStateLinks must be a subset of the dimensions in what");
            return nullopt;
        }
    }

    unordered_map<int, shared_ptr<Activation>> eventActivationMap;
    unordered_map<int, vector<shared_ptr<Activation>>> eventDeactivationMap;
    if (!handleMetricActivation(config, metric.id(), metricIndex, metricToActivationMap,
                                          atomMatchingTrackerMap, activationAtomTrackerToMetricMap,
                                          deactivationAtomTrackerToMetricMap, metricsWithActivation,
                                          eventActivationMap, eventDeactivationMap)) {
        return nullopt;
    }

    uint64_t metricHash;
    if (!getMetricProtoHash(config, metric, metric.id(), metricToActivationMap, metricHash)) {
        return nullopt;
    }

    return {new ValueMetricProducer(key, metric, conditionIndex, initialConditionCache, wizard,
                                    metricHash, trackerIndex, matcherWizard, pullTagId, timeBaseNs,
                                    currentTimeNs, pullerManager, eventActivationMap,
                                    eventDeactivationMap, slicedStateAtoms, stateGroupMap)};
}

optional<sp<MetricProducer>> createGaugeMetricProducerAndUpdateMetadata(
        const ConfigKey& key, const StatsdConfig& config, const int64_t timeBaseNs,
        const int64_t currentTimeNs, const sp<StatsPullerManager>& pullerManager,
        const GaugeMetric& metric, const int metricIndex,
        const vector<sp<AtomMatchingTracker>>& allAtomMatchingTrackers,
        const unordered_map<int64_t, int>& atomMatchingTrackerMap,
        vector<sp<ConditionTracker>>& allConditionTrackers,
        const unordered_map<int64_t, int>& conditionTrackerMap,
        const vector<ConditionState>& initialConditionCache, const sp<ConditionWizard>& wizard,
        const sp<EventMatcherWizard>& matcherWizard,
        const unordered_map<int64_t, int>& metricToActivationMap,
        unordered_map<int, vector<int>>& trackerToMetricMap,
        unordered_map<int, vector<int>>& conditionToMetricMap,
        unordered_map<int, vector<int>>& activationAtomTrackerToMetricMap,
        unordered_map<int, vector<int>>& deactivationAtomTrackerToMetricMap,
        vector<int>& metricsWithActivation) {
    if (!metric.has_id() || !metric.has_what()) {
        ALOGE("cannot find metric id or \"what\" in GaugeMetric \"%lld\"", (long long)metric.id());
        return nullopt;
    }

    if ((!metric.gauge_fields_filter().has_include_all() ||
         (metric.gauge_fields_filter().include_all() == false)) &&
        !hasLeafNode(metric.gauge_fields_filter().fields())) {
        ALOGW("Incorrect field filter setting in GaugeMetric %lld", (long long)metric.id());
        return nullopt;
    }
    if ((metric.gauge_fields_filter().has_include_all() &&
         metric.gauge_fields_filter().include_all() == true) &&
        hasLeafNode(metric.gauge_fields_filter().fields())) {
        ALOGW("Incorrect field filter setting in GaugeMetric %lld", (long long)metric.id());
        return nullopt;
    }

    int trackerIndex;
    if (!handleMetricWithAtomMatchingTrackers(metric.what(), metricIndex,
                                              metric.has_dimensions_in_what(),
                                              allAtomMatchingTrackers, atomMatchingTrackerMap,
                                              trackerToMetricMap, trackerIndex)) {
        return nullopt;
    }

    sp<AtomMatchingTracker> atomMatcher = allAtomMatchingTrackers.at(trackerIndex);
    // For GaugeMetric atom, it should be simple matcher with one tagId.
    if (atomMatcher->getAtomIds().size() != 1) {
        return nullopt;
    }
    int atomTagId = *(atomMatcher->getAtomIds().begin());
    int pullTagId = pullerManager->PullerForMatcherExists(atomTagId) ? atomTagId : -1;

    int triggerTrackerIndex;
    int triggerAtomId = -1;
    if (metric.has_trigger_event()) {
        if (pullTagId == -1) {
            ALOGW("Pull atom not specified for trigger");
            return nullopt;
        }
        // trigger_event should be used with FIRST_N_SAMPLES
        if (metric.sampling_type() != GaugeMetric::FIRST_N_SAMPLES) {
            ALOGW("Gauge Metric with trigger event must have sampling type FIRST_N_SAMPLES");
            return nullopt;
        }
        if (!handleMetricWithAtomMatchingTrackers(metric.trigger_event(), metricIndex,
                                                  /*enforceOneAtom=*/true, allAtomMatchingTrackers,
                                                  atomMatchingTrackerMap, trackerToMetricMap,
                                                  triggerTrackerIndex)) {
            return nullopt;
        }
        sp<AtomMatchingTracker> triggerAtomMatcher =
                allAtomMatchingTrackers.at(triggerTrackerIndex);
        triggerAtomId = *(triggerAtomMatcher->getAtomIds().begin());
    }

    if (!metric.has_trigger_event() && pullTagId != -1 &&
        metric.sampling_type() == GaugeMetric::FIRST_N_SAMPLES) {
        ALOGW("FIRST_N_SAMPLES is only for pushed event or pull_on_trigger");
        return nullopt;
    }

    int conditionIndex = -1;
    if (metric.has_condition()) {
        if (!handleMetricWithConditions(metric.condition(), metricIndex, conditionTrackerMap,
                                        metric.links(), allConditionTrackers, conditionIndex,
                                        conditionToMetricMap)) {
            return nullopt;
        }
    } else {
        if (metric.links_size() > 0) {
            ALOGW("metrics has a MetricConditionLink but doesn't have a condition");
            return nullopt;
        }
    }

    unordered_map<int, shared_ptr<Activation>> eventActivationMap;
    unordered_map<int, vector<shared_ptr<Activation>>> eventDeactivationMap;
    if (!handleMetricActivation(config, metric.id(), metricIndex, metricToActivationMap,
                                atomMatchingTrackerMap, activationAtomTrackerToMetricMap,
                                deactivationAtomTrackerToMetricMap, metricsWithActivation,
                                eventActivationMap, eventDeactivationMap)) {
        return nullopt;
    }

    uint64_t metricHash;
    if (!getMetricProtoHash(config, metric, metric.id(), metricToActivationMap, metricHash)) {
        return nullopt;
    }

    return {new GaugeMetricProducer(key, metric, conditionIndex, initialConditionCache, wizard,
                                    metricHash, trackerIndex, matcherWizard, pullTagId,
                                    triggerAtomId, atomTagId, timeBaseNs, currentTimeNs,
                                    pullerManager, eventActivationMap, eventDeactivationMap)};
}

optional<sp<AnomalyTracker>> createAnomalyTracker(
        const Alert& alert, const sp<AlarmMonitor>& anomalyAlarmMonitor,
        const unordered_map<int64_t, int>& metricProducerMap,
        vector<sp<MetricProducer>>& allMetricProducers) {
    const auto& itr = metricProducerMap.find(alert.metric_id());
    if (itr == metricProducerMap.end()) {
        ALOGW("alert \"%lld\" has unknown metric id: \"%lld\"", (long long)alert.id(),
              (long long)alert.metric_id());
        return nullopt;
    }
    if (!alert.has_trigger_if_sum_gt()) {
        ALOGW("invalid alert: missing threshold");
        return nullopt;
    }
    if (alert.trigger_if_sum_gt() < 0 || alert.num_buckets() <= 0) {
        ALOGW("invalid alert: threshold=%f num_buckets= %d", alert.trigger_if_sum_gt(),
              alert.num_buckets());
        return nullopt;
    }
    const int metricIndex = itr->second;
    sp<MetricProducer> metric = allMetricProducers[metricIndex];
    sp<AnomalyTracker> anomalyTracker = metric->addAnomalyTracker(alert, anomalyAlarmMonitor);
    if (anomalyTracker == nullptr) {
        // The ALOGW for this invalid alert was already displayed in addAnomalyTracker().
        return nullopt;
    }
    return {anomalyTracker};
}

bool initAtomMatchingTrackers(const StatsdConfig& config, const sp<UidMap>& uidMap,
                              unordered_map<int64_t, int>& atomMatchingTrackerMap,
                              vector<sp<AtomMatchingTracker>>& allAtomMatchingTrackers,
                              set<int>& allTagIds) {
    vector<AtomMatcher> matcherConfigs;
    const int atomMatcherCount = config.atom_matcher_size();
    matcherConfigs.reserve(atomMatcherCount);
    allAtomMatchingTrackers.reserve(atomMatcherCount);

    for (int i = 0; i < atomMatcherCount; i++) {
        const AtomMatcher& logMatcher = config.atom_matcher(i);
        sp<AtomMatchingTracker> tracker = createAtomMatchingTracker(logMatcher, i, uidMap);
        if (tracker == nullptr) {
            return false;
        }
        allAtomMatchingTrackers.push_back(tracker);
        if (atomMatchingTrackerMap.find(logMatcher.id()) != atomMatchingTrackerMap.end()) {
            ALOGE("Duplicate AtomMatcher found!");
            return false;
        }
        atomMatchingTrackerMap[logMatcher.id()] = i;
        matcherConfigs.push_back(logMatcher);
    }

    vector<bool> stackTracker2(allAtomMatchingTrackers.size(), false);
    for (auto& matcher : allAtomMatchingTrackers) {
        if (!matcher->init(matcherConfigs, allAtomMatchingTrackers, atomMatchingTrackerMap,
                           stackTracker2)) {
            return false;
        }
        // Collect all the tag ids that are interesting. TagIds exist in leaf nodes only.
        const set<int>& tagIds = matcher->getAtomIds();
        allTagIds.insert(tagIds.begin(), tagIds.end());
    }
    return true;
}

bool initConditions(const ConfigKey& key, const StatsdConfig& config,
                    const unordered_map<int64_t, int>& atomMatchingTrackerMap,
                    unordered_map<int64_t, int>& conditionTrackerMap,
                    vector<sp<ConditionTracker>>& allConditionTrackers,
                    unordered_map<int, std::vector<int>>& trackerToConditionMap,
                    vector<ConditionState>& initialConditionCache) {
    vector<Predicate> conditionConfigs;
    const int conditionTrackerCount = config.predicate_size();
    conditionConfigs.reserve(conditionTrackerCount);
    allConditionTrackers.reserve(conditionTrackerCount);
    initialConditionCache.assign(conditionTrackerCount, ConditionState::kNotEvaluated);

    for (int i = 0; i < conditionTrackerCount; i++) {
        const Predicate& condition = config.predicate(i);
        sp<ConditionTracker> tracker =
                createConditionTracker(key, condition, i, atomMatchingTrackerMap);
        if (tracker == nullptr) {
            return false;
        }
        allConditionTrackers.push_back(tracker);
        if (conditionTrackerMap.find(condition.id()) != conditionTrackerMap.end()) {
            ALOGE("Duplicate Predicate found!");
            return false;
        }
        conditionTrackerMap[condition.id()] = i;
        conditionConfigs.push_back(condition);
    }

    vector<bool> stackTracker(allConditionTrackers.size(), false);
    for (size_t i = 0; i < allConditionTrackers.size(); i++) {
        auto& conditionTracker = allConditionTrackers[i];
        if (!conditionTracker->init(conditionConfigs, allConditionTrackers, conditionTrackerMap,
                                    stackTracker, initialConditionCache)) {
            return false;
        }
        for (const int trackerIndex : conditionTracker->getAtomMatchingTrackerIndex()) {
            auto& conditionList = trackerToConditionMap[trackerIndex];
            conditionList.push_back(i);
        }
    }
    return true;
}

bool initStates(const StatsdConfig& config, unordered_map<int64_t, int>& stateAtomIdMap,
                unordered_map<int64_t, unordered_map<int, int64_t>>& allStateGroupMaps,
                map<int64_t, uint64_t>& stateProtoHashes) {
    for (int i = 0; i < config.state_size(); i++) {
        const State& state = config.state(i);
        const int64_t stateId = state.id();
        stateAtomIdMap[stateId] = state.atom_id();

        string serializedState;
        if (!state.SerializeToString(&serializedState)) {
            ALOGE("Unable to serialize state %lld", (long long)stateId);
            return false;
        }
        stateProtoHashes[stateId] = Hash64(serializedState);

        const StateMap& stateMap = state.map();
        for (auto group : stateMap.group()) {
            for (auto value : group.value()) {
                allStateGroupMaps[stateId][value] = group.group_id();
            }
        }
    }

    return true;
}

bool initMetrics(const ConfigKey& key, const StatsdConfig& config, const int64_t timeBaseTimeNs,
                 const int64_t currentTimeNs, const sp<StatsPullerManager>& pullerManager,
                 const unordered_map<int64_t, int>& atomMatchingTrackerMap,
                 const unordered_map<int64_t, int>& conditionTrackerMap,
                 const vector<sp<AtomMatchingTracker>>& allAtomMatchingTrackers,
                 const unordered_map<int64_t, int>& stateAtomIdMap,
                 const unordered_map<int64_t, unordered_map<int, int64_t>>& allStateGroupMaps,
                 vector<sp<ConditionTracker>>& allConditionTrackers,
                 const vector<ConditionState>& initialConditionCache,
                 vector<sp<MetricProducer>>& allMetricProducers,
                 unordered_map<int, vector<int>>& conditionToMetricMap,
                 unordered_map<int, vector<int>>& trackerToMetricMap,
                 unordered_map<int64_t, int>& metricMap, std::set<int64_t>& noReportMetricIds,
                 unordered_map<int, vector<int>>& activationAtomTrackerToMetricMap,
                 unordered_map<int, vector<int>>& deactivationAtomTrackerToMetricMap,
                 vector<int>& metricsWithActivation) {
    sp<ConditionWizard> wizard = new ConditionWizard(allConditionTrackers);
    sp<EventMatcherWizard> matcherWizard = new EventMatcherWizard(allAtomMatchingTrackers);
    const int allMetricsCount = config.count_metric_size() + config.duration_metric_size() +
                                config.event_metric_size() + config.gauge_metric_size() +
                                config.value_metric_size();
    allMetricProducers.reserve(allMetricsCount);

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

    // Build MetricProducers for each metric defined in config.
    // build CountMetricProducer
    for (int i = 0; i < config.count_metric_size(); i++) {
        int metricIndex = allMetricProducers.size();
        const CountMetric& metric = config.count_metric(i);
        metricMap.insert({metric.id(), metricIndex});
        optional<sp<MetricProducer>> producer = createCountMetricProducerAndUpdateMetadata(
                key, config, timeBaseTimeNs, currentTimeNs, metric, metricIndex,
                allAtomMatchingTrackers, atomMatchingTrackerMap, allConditionTrackers,
                conditionTrackerMap, initialConditionCache, wizard, stateAtomIdMap,
                allStateGroupMaps, metricToActivationMap, trackerToMetricMap, conditionToMetricMap,
                activationAtomTrackerToMetricMap, deactivationAtomTrackerToMetricMap,
                metricsWithActivation);
        if (!producer) {
            return false;
        }
        allMetricProducers.push_back(producer.value());
    }

    // build DurationMetricProducer
    for (int i = 0; i < config.duration_metric_size(); i++) {
        int metricIndex = allMetricProducers.size();
        const DurationMetric& metric = config.duration_metric(i);
        metricMap.insert({metric.id(), metricIndex});

        optional<sp<MetricProducer>> producer = createDurationMetricProducerAndUpdateMetadata(
                key, config, timeBaseTimeNs, currentTimeNs, metric, metricIndex,
                allAtomMatchingTrackers, atomMatchingTrackerMap, allConditionTrackers,
                conditionTrackerMap, initialConditionCache, wizard, stateAtomIdMap,
                allStateGroupMaps, metricToActivationMap, trackerToMetricMap, conditionToMetricMap,
                activationAtomTrackerToMetricMap, deactivationAtomTrackerToMetricMap,
                metricsWithActivation);
        if (!producer) {
            return false;
        }
        allMetricProducers.push_back(producer.value());
    }

    // build EventMetricProducer
    for (int i = 0; i < config.event_metric_size(); i++) {
        int metricIndex = allMetricProducers.size();
        const EventMetric& metric = config.event_metric(i);
        metricMap.insert({metric.id(), metricIndex});
        optional<sp<MetricProducer>> producer = createEventMetricProducerAndUpdateMetadata(
                key, config, timeBaseTimeNs, metric, metricIndex, allAtomMatchingTrackers,
                atomMatchingTrackerMap, allConditionTrackers, conditionTrackerMap,
                initialConditionCache, wizard, metricToActivationMap, trackerToMetricMap,
                conditionToMetricMap, activationAtomTrackerToMetricMap,
                deactivationAtomTrackerToMetricMap, metricsWithActivation);
        if (!producer) {
            return false;
        }
        allMetricProducers.push_back(producer.value());
    }

    // build ValueMetricProducer
    for (int i = 0; i < config.value_metric_size(); i++) {
        int metricIndex = allMetricProducers.size();
        const ValueMetric& metric = config.value_metric(i);
        metricMap.insert({metric.id(), metricIndex});
        optional<sp<MetricProducer>> producer = createValueMetricProducerAndUpdateMetadata(
                key, config, timeBaseTimeNs, currentTimeNs, pullerManager, metric, metricIndex,
                allAtomMatchingTrackers, atomMatchingTrackerMap, allConditionTrackers,
                conditionTrackerMap, initialConditionCache, wizard, matcherWizard, stateAtomIdMap,
                allStateGroupMaps, metricToActivationMap, trackerToMetricMap, conditionToMetricMap,
                activationAtomTrackerToMetricMap, deactivationAtomTrackerToMetricMap,
                metricsWithActivation);
        if (!producer) {
            return false;
        }
        allMetricProducers.push_back(producer.value());
    }

    // Gauge metrics.
    for (int i = 0; i < config.gauge_metric_size(); i++) {
        int metricIndex = allMetricProducers.size();
        const GaugeMetric& metric = config.gauge_metric(i);
        metricMap.insert({metric.id(), metricIndex});
        optional<sp<MetricProducer>> producer = createGaugeMetricProducerAndUpdateMetadata(
                key, config, timeBaseTimeNs, currentTimeNs, pullerManager, metric, metricIndex,
                allAtomMatchingTrackers, atomMatchingTrackerMap, allConditionTrackers,
                conditionTrackerMap, initialConditionCache, wizard, matcherWizard,
                metricToActivationMap, trackerToMetricMap, conditionToMetricMap,
                activationAtomTrackerToMetricMap, deactivationAtomTrackerToMetricMap,
                metricsWithActivation);
        if (!producer) {
            return false;
        }
        allMetricProducers.push_back(producer.value());
    }
    for (int i = 0; i < config.no_report_metric_size(); ++i) {
        const auto no_report_metric = config.no_report_metric(i);
        if (metricMap.find(no_report_metric) == metricMap.end()) {
            ALOGW("no_report_metric %" PRId64 " not exist", no_report_metric);
            return false;
        }
        noReportMetricIds.insert(no_report_metric);
    }

    const set<int> whitelistedAtomIds(config.whitelisted_atom_ids().begin(),
                                      config.whitelisted_atom_ids().end());
    for (const auto& it : allMetricProducers) {
        // Register metrics to StateTrackers
        for (int atomId : it->getSlicedStateAtoms()) {
            // Register listener for non-whitelisted atoms only. Using whitelisted atom as a sliced
            // state atom is not allowed.
            if (whitelistedAtomIds.find(atomId) == whitelistedAtomIds.end()) {
                StateManager::getInstance().registerListener(atomId, it);
            } else {
                return false;
            }
        }
    }
    return true;
}

bool initAlerts(const StatsdConfig& config, const unordered_map<int64_t, int>& metricProducerMap,
                unordered_map<int64_t, int>& alertTrackerMap,
                const sp<AlarmMonitor>& anomalyAlarmMonitor,
                vector<sp<MetricProducer>>& allMetricProducers,
                vector<sp<AnomalyTracker>>& allAnomalyTrackers) {
    for (int i = 0; i < config.alert_size(); i++) {
        const Alert& alert = config.alert(i);
        alertTrackerMap.insert(std::make_pair(alert.id(), allAnomalyTrackers.size()));
        optional<sp<AnomalyTracker>> anomalyTracker = createAnomalyTracker(
                alert, anomalyAlarmMonitor, metricProducerMap, allMetricProducers);
        if (!anomalyTracker) {
            return false;
        }
        allAnomalyTrackers.push_back(anomalyTracker.value());
    }
    if (!initSubscribersForSubscriptionType(config, Subscription::ALERT, alertTrackerMap,
                                            allAnomalyTrackers)) {
        return false;
    }
    return true;
}

bool initAlarms(const StatsdConfig& config, const ConfigKey& key,
                const sp<AlarmMonitor>& periodicAlarmMonitor, const int64_t timeBaseNs,
                const int64_t currentTimeNs, vector<sp<AlarmTracker>>& allAlarmTrackers) {
    unordered_map<int64_t, int> alarmTrackerMap;
    int64_t startMillis = timeBaseNs / 1000 / 1000;
    int64_t currentTimeMillis = currentTimeNs / 1000 / 1000;
    for (int i = 0; i < config.alarm_size(); i++) {
        const Alarm& alarm = config.alarm(i);
        if (alarm.offset_millis() <= 0) {
            ALOGW("Alarm offset_millis should be larger than 0.");
            return false;
        }
        if (alarm.period_millis() <= 0) {
            ALOGW("Alarm period_millis should be larger than 0.");
            return false;
        }
        alarmTrackerMap.insert(std::make_pair(alarm.id(), allAlarmTrackers.size()));
        allAlarmTrackers.push_back(
                new AlarmTracker(startMillis, currentTimeMillis, alarm, key, periodicAlarmMonitor));
    }
    if (!initSubscribersForSubscriptionType(config, Subscription::ALARM, alarmTrackerMap,
                                            allAlarmTrackers)) {
        return false;
    }
    return true;
}

bool initStatsdConfig(const ConfigKey& key, const StatsdConfig& config, const sp<UidMap>& uidMap,
                      const sp<StatsPullerManager>& pullerManager,
                      const sp<AlarmMonitor>& anomalyAlarmMonitor,
                      const sp<AlarmMonitor>& periodicAlarmMonitor, const int64_t timeBaseNs,
                      const int64_t currentTimeNs, set<int>& allTagIds,
                      vector<sp<AtomMatchingTracker>>& allAtomMatchingTrackers,
                      unordered_map<int64_t, int>& atomMatchingTrackerMap,
                      vector<sp<ConditionTracker>>& allConditionTrackers,
                      unordered_map<int64_t, int>& conditionTrackerMap,
                      vector<sp<MetricProducer>>& allMetricProducers,
                      unordered_map<int64_t, int>& metricProducerMap,
                      vector<sp<AnomalyTracker>>& allAnomalyTrackers,
                      vector<sp<AlarmTracker>>& allPeriodicAlarmTrackers,
                      unordered_map<int, std::vector<int>>& conditionToMetricMap,
                      unordered_map<int, std::vector<int>>& trackerToMetricMap,
                      unordered_map<int, std::vector<int>>& trackerToConditionMap,
                      unordered_map<int, std::vector<int>>& activationAtomTrackerToMetricMap,
                      unordered_map<int, std::vector<int>>& deactivationAtomTrackerToMetricMap,
                      unordered_map<int64_t, int>& alertTrackerMap,
                      vector<int>& metricsWithActivation, map<int64_t, uint64_t>& stateProtoHashes,
                      set<int64_t>& noReportMetricIds) {
    vector<ConditionState> initialConditionCache;
    unordered_map<int64_t, int> stateAtomIdMap;
    unordered_map<int64_t, unordered_map<int, int64_t>> allStateGroupMaps;

    if (!initAtomMatchingTrackers(config, uidMap, atomMatchingTrackerMap, allAtomMatchingTrackers,
                                  allTagIds)) {
        ALOGE("initAtomMatchingTrackers failed");
        return false;
    }
    VLOG("initAtomMatchingTrackers succeed...");

    if (!initConditions(key, config, atomMatchingTrackerMap, conditionTrackerMap,
                        allConditionTrackers, trackerToConditionMap, initialConditionCache)) {
        ALOGE("initConditionTrackers failed");
        return false;
    }

    if (!initStates(config, stateAtomIdMap, allStateGroupMaps, stateProtoHashes)) {
        ALOGE("initStates failed");
        return false;
    }
    if (!initMetrics(key, config, timeBaseNs, currentTimeNs, pullerManager, atomMatchingTrackerMap,
                     conditionTrackerMap, allAtomMatchingTrackers, stateAtomIdMap,
                     allStateGroupMaps, allConditionTrackers, initialConditionCache,
                     allMetricProducers, conditionToMetricMap, trackerToMetricMap,
                     metricProducerMap, noReportMetricIds, activationAtomTrackerToMetricMap,
                     deactivationAtomTrackerToMetricMap, metricsWithActivation)) {
        ALOGE("initMetricProducers failed");
        return false;
    }
    if (!initAlerts(config, metricProducerMap, alertTrackerMap, anomalyAlarmMonitor,
                    allMetricProducers, allAnomalyTrackers)) {
        ALOGE("initAlerts failed");
        return false;
    }
    if (!initAlarms(config, key, periodicAlarmMonitor, timeBaseNs, currentTimeNs,
                    allPeriodicAlarmTrackers)) {
        ALOGE("initAlarms failed");
        return false;
    }

    return true;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
