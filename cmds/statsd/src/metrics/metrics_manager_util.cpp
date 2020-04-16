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

#include "atoms_info.h"
#include "FieldValue.h"
#include "MetricProducer.h"
#include "condition/CombinationConditionTracker.h"
#include "condition/SimpleConditionTracker.h"
#include "external/StatsPullerManager.h"
#include "matchers/CombinationLogMatchingTracker.h"
#include "matchers/EventMatcherWizard.h"
#include "matchers/SimpleLogMatchingTracker.h"
#include "metrics/CountMetricProducer.h"
#include "metrics/DurationMetricProducer.h"
#include "metrics/EventMetricProducer.h"
#include "metrics/GaugeMetricProducer.h"
#include "metrics/ValueMetricProducer.h"
#include "state/StateManager.h"
#include "stats_util.h"

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

bool handleMetricWithLogTrackers(const int64_t what, const int metricIndex,
                                 const bool usedForDimension,
                                 const vector<sp<LogMatchingTracker>>& allAtomMatchers,
                                 const unordered_map<int64_t, int>& logTrackerMap,
                                 unordered_map<int, std::vector<int>>& trackerToMetricMap,
                                 int& logTrackerIndex) {
    auto logTrackerIt = logTrackerMap.find(what);
    if (logTrackerIt == logTrackerMap.end()) {
        ALOGW("cannot find the AtomMatcher \"%lld\" in config", (long long)what);
        return false;
    }
    if (usedForDimension && allAtomMatchers[logTrackerIt->second]->getAtomIds().size() > 1) {
        ALOGE("AtomMatcher \"%lld\" has more than one tag ids. When a metric has dimension, "
              "the \"what\" can only about one atom type.",
              (long long)what);
        return false;
    }
    logTrackerIndex = logTrackerIt->second;
    auto& metric_list = trackerToMetricMap[logTrackerIndex];
    metric_list.push_back(metricIndex);
    return true;
}

bool handlePullMetricTriggerWithLogTrackers(
        const int64_t trigger, const int metricIndex,
        const vector<sp<LogMatchingTracker>>& allAtomMatchers,
        const unordered_map<int64_t, int>& logTrackerMap,
        unordered_map<int, std::vector<int>>& trackerToMetricMap, int& logTrackerIndex) {
    auto logTrackerIt = logTrackerMap.find(trigger);
    if (logTrackerIt == logTrackerMap.end()) {
        ALOGW("cannot find the AtomMatcher \"%lld\" in config", (long long)trigger);
        return false;
    }
    if (allAtomMatchers[logTrackerIt->second]->getAtomIds().size() > 1) {
        ALOGE("AtomMatcher \"%lld\" has more than one tag ids."
              "Trigger can only be one atom type.",
              (long long)trigger);
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
        vector<sp<ConditionTracker>>& allConditionTrackers, int& conditionIndex,
        unordered_map<int, std::vector<int>>& conditionToMetricMap) {
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
        allConditionTrackers[condition_it->second]->setSliced(true);
        allConditionTrackers[it->second]->setSliced(true);
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
        const StatsdConfig& config,
        const int64_t metricId,
        const int metricIndex,
        const unordered_map<int64_t, int>& metricToActivationMap,
        const unordered_map<int64_t, int>& logTrackerMap,
        unordered_map<int, vector<int>>& activationAtomTrackerToMetricMap,
        unordered_map<int, vector<int>>& deactivationAtomTrackerToMetricMap,
        vector<int>& metricsWithActivation,
        unordered_map<int, shared_ptr<Activation>>& eventActivationMap,
        unordered_map<int, vector<shared_ptr<Activation>>>& eventDeactivationMap) {
    // Check if metric has an associated activation
    auto itr = metricToActivationMap.find(metricId);
    if (itr == metricToActivationMap.end()) return true;

    int activationIndex = itr->second;
    const MetricActivation& metricActivation = config.metric_activation(activationIndex);

    for (int i = 0; i < metricActivation.event_activation_size(); i++) {
        const EventActivation& activation = metricActivation.event_activation(i);

        auto itr = logTrackerMap.find(activation.atom_matcher_id());
        if (itr == logTrackerMap.end()) {
            ALOGE("Atom matcher not found for event activation.");
            return false;
        }

        ActivationType activationType = (activation.has_activation_type()) ?
                activation.activation_type() : metricActivation.activation_type();
        std::shared_ptr<Activation> activationWrapper = std::make_shared<Activation>(
                activationType, activation.ttl_seconds() * NS_PER_SEC);

        int atomMatcherIndex = itr->second;
        activationAtomTrackerToMetricMap[atomMatcherIndex].push_back(metricIndex);
        eventActivationMap.emplace(atomMatcherIndex, activationWrapper);

        if (activation.has_deactivation_atom_matcher_id()) {
            itr = logTrackerMap.find(activation.deactivation_atom_matcher_id());
            if (itr == logTrackerMap.end()) {
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

bool initLogTrackers(const StatsdConfig& config, const UidMap& uidMap,
                     unordered_map<int64_t, int>& logTrackerMap,
                     vector<sp<LogMatchingTracker>>& allAtomMatchers, set<int>& allTagIds) {
    vector<AtomMatcher> matcherConfigs;
    const int atomMatcherCount = config.atom_matcher_size();
    matcherConfigs.reserve(atomMatcherCount);
    allAtomMatchers.reserve(atomMatcherCount);

    for (int i = 0; i < atomMatcherCount; i++) {
        const AtomMatcher& logMatcher = config.atom_matcher(i);

        int index = allAtomMatchers.size();
        switch (logMatcher.contents_case()) {
            case AtomMatcher::ContentsCase::kSimpleAtomMatcher:
                allAtomMatchers.push_back(new SimpleLogMatchingTracker(
                        logMatcher.id(), index, logMatcher.simple_atom_matcher(), uidMap));
                break;
            case AtomMatcher::ContentsCase::kCombination:
                allAtomMatchers.push_back(
                        new CombinationLogMatchingTracker(logMatcher.id(), index));
                break;
            default:
                ALOGE("Matcher \"%lld\" malformed", (long long)logMatcher.id());
                return false;
                // continue;
        }
        if (logTrackerMap.find(logMatcher.id()) != logTrackerMap.end()) {
            ALOGE("Duplicate AtomMatcher found!");
            return false;
        }
        logTrackerMap[logMatcher.id()] = index;
        matcherConfigs.push_back(logMatcher);
    }

    vector<bool> stackTracker2(allAtomMatchers.size(), false);
    for (auto& matcher : allAtomMatchers) {
        if (!matcher->init(matcherConfigs, allAtomMatchers, logTrackerMap, stackTracker2)) {
            return false;
        }
        // Collect all the tag ids that are interesting. TagIds exist in leaf nodes only.
        const set<int>& tagIds = matcher->getAtomIds();
        allTagIds.insert(tagIds.begin(), tagIds.end());
    }
    return true;
}

bool initConditions(const ConfigKey& key, const StatsdConfig& config,
                    const unordered_map<int64_t, int>& logTrackerMap,
                    unordered_map<int64_t, int>& conditionTrackerMap,
                    vector<sp<ConditionTracker>>& allConditionTrackers,
                    unordered_map<int, std::vector<int>>& trackerToConditionMap) {
    vector<Predicate> conditionConfigs;
    const int conditionTrackerCount = config.predicate_size();
    conditionConfigs.reserve(conditionTrackerCount);
    allConditionTrackers.reserve(conditionTrackerCount);

    for (int i = 0; i < conditionTrackerCount; i++) {
        const Predicate& condition = config.predicate(i);
        int index = allConditionTrackers.size();
        switch (condition.contents_case()) {
            case Predicate::ContentsCase::kSimplePredicate: {
                allConditionTrackers.push_back(new SimpleConditionTracker(
                        key, condition.id(), index, condition.simple_predicate(), logTrackerMap));
                break;
            }
            case Predicate::ContentsCase::kCombination: {
                allConditionTrackers.push_back(
                        new CombinationConditionTracker(condition.id(), index));
                break;
            }
            default:
                ALOGE("Predicate \"%lld\" malformed", (long long)condition.id());
                return false;
        }
        if (conditionTrackerMap.find(condition.id()) != conditionTrackerMap.end()) {
            ALOGE("Duplicate Predicate found!");
            return false;
        }
        conditionTrackerMap[condition.id()] = index;
        conditionConfigs.push_back(condition);
    }

    vector<bool> stackTracker(allConditionTrackers.size(), false);
    for (size_t i = 0; i < allConditionTrackers.size(); i++) {
        auto& conditionTracker = allConditionTrackers[i];
        if (!conditionTracker->init(conditionConfigs, allConditionTrackers, conditionTrackerMap,
                                    stackTracker)) {
            return false;
        }
        for (const int trackerIndex : conditionTracker->getLogTrackerIndex()) {
            auto& conditionList = trackerToConditionMap[trackerIndex];
            conditionList.push_back(i);
        }
    }
    return true;
}

bool initStates(const StatsdConfig& config, unordered_map<int64_t, int>& stateAtomIdMap,
                unordered_map<int64_t, unordered_map<int, int64_t>>& allStateGroupMaps) {
    for (int i = 0; i < config.state_size(); i++) {
        const State& state = config.state(i);
        const int64_t stateId = state.id();
        stateAtomIdMap[stateId] = state.atom_id();

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
                 const int64_t currentTimeNs,
                 const sp<StatsPullerManager>& pullerManager,
                 const unordered_map<int64_t, int>& logTrackerMap,
                 const unordered_map<int64_t, int>& conditionTrackerMap,
                 const vector<sp<LogMatchingTracker>>& allAtomMatchers,
                 const unordered_map<int64_t, int>& stateAtomIdMap,
                 const unordered_map<int64_t, unordered_map<int, int64_t>>& allStateGroupMaps,
                 vector<sp<ConditionTracker>>& allConditionTrackers,
                 vector<sp<MetricProducer>>& allMetricProducers,
                 unordered_map<int, vector<int>>& conditionToMetricMap,
                 unordered_map<int, vector<int>>& trackerToMetricMap,
                 unordered_map<int64_t, int>& metricMap, std::set<int64_t>& noReportMetricIds,
                 unordered_map<int, vector<int>>& activationAtomTrackerToMetricMap,
                 unordered_map<int, vector<int>>& deactivationAtomTrackerToMetricMap,
                 vector<int>& metricsWithActivation) {
    sp<ConditionWizard> wizard = new ConditionWizard(allConditionTrackers);
    sp<EventMatcherWizard> matcherWizard = new EventMatcherWizard(allAtomMatchers);
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
            ALOGE("Metric %lld has multiple MetricActivations", (long long) metricId);
            return false;
        }
        metricToActivationMap.insert({metricId, i});
    }

    // Build MetricProducers for each metric defined in config.
    // build CountMetricProducer
    for (int i = 0; i < config.count_metric_size(); i++) {
        const CountMetric& metric = config.count_metric(i);
        if (!metric.has_what()) {
            ALOGW("cannot find \"what\" in CountMetric \"%lld\"", (long long)metric.id());
            return false;
        }

        int metricIndex = allMetricProducers.size();
        metricMap.insert({metric.id(), metricIndex});
        int trackerIndex;
        if (!handleMetricWithLogTrackers(metric.what(), metricIndex,
                                         metric.has_dimensions_in_what(),
                                         allAtomMatchers, logTrackerMap, trackerToMetricMap,
                                         trackerIndex)) {
            return false;
        }

        int conditionIndex = -1;
        if (metric.has_condition()) {
            if (!handleMetricWithConditions(metric.condition(), metricIndex, conditionTrackerMap,
                                            metric.links(), allConditionTrackers, conditionIndex,
                                            conditionToMetricMap)) {
                return false;
            }
        } else {
            if (metric.links_size() > 0) {
                ALOGW("metrics has a MetricConditionLink but doesn't have a condition");
                return false;
            }
        }

        std::vector<int> slicedStateAtoms;
        unordered_map<int, unordered_map<int, int64_t>> stateGroupMap;
        if (metric.slice_by_state_size() > 0) {
            if (!handleMetricWithStates(config, metric.slice_by_state(), stateAtomIdMap,
                                        allStateGroupMaps, slicedStateAtoms, stateGroupMap)) {
                return false;
            }
        } else {
            if (metric.state_link_size() > 0) {
                ALOGW("CountMetric has a MetricStateLink but doesn't have a slice_by_state");
                return false;
            }
        }

        unordered_map<int, shared_ptr<Activation>> eventActivationMap;
        unordered_map<int, vector<shared_ptr<Activation>>> eventDeactivationMap;
        bool success = handleMetricActivation(config, metric.id(), metricIndex,
                metricToActivationMap, logTrackerMap, activationAtomTrackerToMetricMap,
                deactivationAtomTrackerToMetricMap, metricsWithActivation, eventActivationMap,
                eventDeactivationMap);
        if (!success) return false;

        sp<MetricProducer> countProducer = new CountMetricProducer(
                key, metric, conditionIndex, wizard, timeBaseTimeNs, currentTimeNs,
                eventActivationMap, eventDeactivationMap, slicedStateAtoms, stateGroupMap);
        allMetricProducers.push_back(countProducer);
    }

    // build DurationMetricProducer
    for (int i = 0; i < config.duration_metric_size(); i++) {
        int metricIndex = allMetricProducers.size();
        const DurationMetric& metric = config.duration_metric(i);
        metricMap.insert({metric.id(), metricIndex});

        auto what_it = conditionTrackerMap.find(metric.what());
        if (what_it == conditionTrackerMap.end()) {
            ALOGE("DurationMetric's \"what\" is invalid");
            return false;
        }

        const Predicate& durationWhat = config.predicate(what_it->second);

        if (durationWhat.contents_case() != Predicate::ContentsCase::kSimplePredicate) {
            ALOGE("DurationMetric's \"what\" must be a simple condition");
            return false;
        }

        const auto& simplePredicate = durationWhat.simple_predicate();

        bool nesting = simplePredicate.count_nesting();

        int trackerIndices[3] = {-1, -1, -1};
        if (!simplePredicate.has_start() ||
            !handleMetricWithLogTrackers(simplePredicate.start(), metricIndex,
                                         metric.has_dimensions_in_what(), allAtomMatchers,
                                         logTrackerMap, trackerToMetricMap, trackerIndices[0])) {
            ALOGE("Duration metrics must specify a valid the start event matcher");
            return false;
        }

        if (simplePredicate.has_stop() &&
            !handleMetricWithLogTrackers(simplePredicate.stop(), metricIndex,
                                         metric.has_dimensions_in_what(), allAtomMatchers,
                                         logTrackerMap, trackerToMetricMap, trackerIndices[1])) {
            return false;
        }

        if (simplePredicate.has_stop_all() &&
            !handleMetricWithLogTrackers(simplePredicate.stop_all(), metricIndex,
                                         metric.has_dimensions_in_what(), allAtomMatchers,
                                         logTrackerMap, trackerToMetricMap, trackerIndices[2])) {
            return false;
        }

        FieldMatcher internalDimensions = simplePredicate.dimensions();

        int conditionIndex = -1;

        if (metric.has_condition()) {
            bool good = handleMetricWithConditions(
                    metric.condition(), metricIndex, conditionTrackerMap, metric.links(),
                    allConditionTrackers, conditionIndex, conditionToMetricMap);
            if (!good) {
                return false;
            }
        } else {
            if (metric.links_size() > 0) {
                ALOGW("metrics has a MetricConditionLink but doesn't have a condition");
                return false;
            }
        }

        std::vector<int> slicedStateAtoms;
        unordered_map<int, unordered_map<int, int64_t>> stateGroupMap;
        if (metric.slice_by_state_size() > 0) {
            if (metric.aggregation_type() == DurationMetric::MAX_SPARSE) {
                ALOGE("DurationMetric with aggregation type MAX_SPARSE cannot be sliced by state");
                return false;
            }
            if (!handleMetricWithStates(config, metric.slice_by_state(), stateAtomIdMap,
                                        allStateGroupMaps, slicedStateAtoms, stateGroupMap)) {
                return false;
            }
        } else {
            if (metric.state_link_size() > 0) {
                ALOGW("DurationMetric has a MetricStateLink but doesn't have a sliced state");
                return false;
            }
        }

        // Check that all metric state links are a subset of dimensions_in_what fields.
        std::vector<Matcher> dimensionsInWhat;
        translateFieldMatcher(metric.dimensions_in_what(), &dimensionsInWhat);
        for (const auto& stateLink : metric.state_link()) {
            if (!handleMetricWithStateLink(stateLink.fields_in_what(), dimensionsInWhat)) {
                return false;
            }
        }

        unordered_map<int, shared_ptr<Activation>> eventActivationMap;
        unordered_map<int, vector<shared_ptr<Activation>>> eventDeactivationMap;
        bool success = handleMetricActivation(config, metric.id(), metricIndex,
                metricToActivationMap, logTrackerMap, activationAtomTrackerToMetricMap,
                deactivationAtomTrackerToMetricMap, metricsWithActivation, eventActivationMap,
                eventDeactivationMap);
        if (!success) return false;

        sp<MetricProducer> durationMetric = new DurationMetricProducer(
                key, metric, conditionIndex, trackerIndices[0], trackerIndices[1],
                trackerIndices[2], nesting, wizard, internalDimensions, timeBaseTimeNs,
                currentTimeNs, eventActivationMap, eventDeactivationMap, slicedStateAtoms,
                stateGroupMap);

        allMetricProducers.push_back(durationMetric);
    }

    // build EventMetricProducer
    for (int i = 0; i < config.event_metric_size(); i++) {
        int metricIndex = allMetricProducers.size();
        const EventMetric& metric = config.event_metric(i);
        metricMap.insert({metric.id(), metricIndex});
        if (!metric.has_id() || !metric.has_what()) {
            ALOGW("cannot find the metric name or what in config");
            return false;
        }
        int trackerIndex;
        if (!handleMetricWithLogTrackers(metric.what(), metricIndex, false, allAtomMatchers,
                                         logTrackerMap, trackerToMetricMap, trackerIndex)) {
            return false;
        }

        int conditionIndex = -1;
        if (metric.has_condition()) {
            bool good = handleMetricWithConditions(
                    metric.condition(), metricIndex, conditionTrackerMap, metric.links(),
                    allConditionTrackers, conditionIndex, conditionToMetricMap);
            if (!good) {
                return false;
            }
        } else {
            if (metric.links_size() > 0) {
                ALOGW("metrics has a MetricConditionLink but doesn't have a condition");
                return false;
            }
        }

        unordered_map<int, shared_ptr<Activation>> eventActivationMap;
        unordered_map<int, vector<shared_ptr<Activation>>> eventDeactivationMap;
        bool success = handleMetricActivation(config, metric.id(), metricIndex,
                metricToActivationMap, logTrackerMap, activationAtomTrackerToMetricMap,
                deactivationAtomTrackerToMetricMap, metricsWithActivation, eventActivationMap,
                eventDeactivationMap);
        if (!success) return false;

        sp<MetricProducer> eventMetric = new EventMetricProducer(
                key, metric, conditionIndex, wizard, timeBaseTimeNs, eventActivationMap,
                eventDeactivationMap);

        allMetricProducers.push_back(eventMetric);
    }

    // build ValueMetricProducer
    for (int i = 0; i < config.value_metric_size(); i++) {
        const ValueMetric& metric = config.value_metric(i);
        if (!metric.has_what()) {
            ALOGW("cannot find \"what\" in ValueMetric \"%lld\"", (long long)metric.id());
            return false;
        }
        if (!metric.has_value_field()) {
            ALOGW("cannot find \"value_field\" in ValueMetric \"%lld\"", (long long)metric.id());
            return false;
        }
        std::vector<Matcher> fieldMatchers;
        translateFieldMatcher(metric.value_field(), &fieldMatchers);
        if (fieldMatchers.size() < 1) {
            ALOGW("incorrect \"value_field\" in ValueMetric \"%lld\"", (long long)metric.id());
            return false;
        }

        int metricIndex = allMetricProducers.size();
        metricMap.insert({metric.id(), metricIndex});
        int trackerIndex;
        if (!handleMetricWithLogTrackers(metric.what(), metricIndex,
                                         metric.has_dimensions_in_what(),
                                         allAtomMatchers, logTrackerMap, trackerToMetricMap,
                                         trackerIndex)) {
            return false;
        }

        sp<LogMatchingTracker> atomMatcher = allAtomMatchers.at(trackerIndex);
        // If it is pulled atom, it should be simple matcher with one tagId.
        if (atomMatcher->getAtomIds().size() != 1) {
            return false;
        }
        int atomTagId = *(atomMatcher->getAtomIds().begin());
        int pullTagId = pullerManager->PullerForMatcherExists(atomTagId) ? atomTagId : -1;

        int conditionIndex = -1;
        if (metric.has_condition()) {
            bool good = handleMetricWithConditions(
                    metric.condition(), metricIndex, conditionTrackerMap, metric.links(),
                    allConditionTrackers, conditionIndex, conditionToMetricMap);
            if (!good) {
                return false;
            }
        } else {
            if (metric.links_size() > 0) {
                ALOGW("metrics has a MetricConditionLink but doesn't have a condition");
                return false;
            }
        }

        std::vector<int> slicedStateAtoms;
        unordered_map<int, unordered_map<int, int64_t>> stateGroupMap;
        if (metric.slice_by_state_size() > 0) {
            if (!handleMetricWithStates(config, metric.slice_by_state(), stateAtomIdMap,
                                        allStateGroupMaps, slicedStateAtoms, stateGroupMap)) {
                return false;
            }
        } else {
            if (metric.state_link_size() > 0) {
                ALOGW("ValueMetric has a MetricStateLink but doesn't have a sliced state");
                return false;
            }
        }

        // Check that all metric state links are a subset of dimensions_in_what fields.
        std::vector<Matcher> dimensionsInWhat;
        translateFieldMatcher(metric.dimensions_in_what(), &dimensionsInWhat);
        for (const auto& stateLink : metric.state_link()) {
            if (!handleMetricWithStateLink(stateLink.fields_in_what(), dimensionsInWhat)) {
                return false;
            }
        }

        unordered_map<int, shared_ptr<Activation>> eventActivationMap;
        unordered_map<int, vector<shared_ptr<Activation>>> eventDeactivationMap;
        bool success = handleMetricActivation(
                config, metric.id(), metricIndex, metricToActivationMap, logTrackerMap,
                activationAtomTrackerToMetricMap, deactivationAtomTrackerToMetricMap,
                metricsWithActivation, eventActivationMap, eventDeactivationMap);
        if (!success) return false;

        sp<MetricProducer> valueProducer = new ValueMetricProducer(
                key, metric, conditionIndex, wizard, trackerIndex, matcherWizard, pullTagId,
                timeBaseTimeNs, currentTimeNs, pullerManager, eventActivationMap,
                eventDeactivationMap, slicedStateAtoms, stateGroupMap);
        allMetricProducers.push_back(valueProducer);
    }

    // Gauge metrics.
    for (int i = 0; i < config.gauge_metric_size(); i++) {
        const GaugeMetric& metric = config.gauge_metric(i);
        if (!metric.has_what()) {
            ALOGW("cannot find \"what\" in GaugeMetric \"%lld\"", (long long)metric.id());
            return false;
        }

        if ((!metric.gauge_fields_filter().has_include_all() ||
             (metric.gauge_fields_filter().include_all() == false)) &&
            !hasLeafNode(metric.gauge_fields_filter().fields())) {
            ALOGW("Incorrect field filter setting in GaugeMetric %lld", (long long)metric.id());
            return false;
        }
        if ((metric.gauge_fields_filter().has_include_all() &&
             metric.gauge_fields_filter().include_all() == true) &&
            hasLeafNode(metric.gauge_fields_filter().fields())) {
            ALOGW("Incorrect field filter setting in GaugeMetric %lld", (long long)metric.id());
            return false;
        }

        int metricIndex = allMetricProducers.size();
        metricMap.insert({metric.id(), metricIndex});
        int trackerIndex;
        if (!handleMetricWithLogTrackers(metric.what(), metricIndex,
                                         metric.has_dimensions_in_what(),
                                         allAtomMatchers, logTrackerMap, trackerToMetricMap,
                                         trackerIndex)) {
            return false;
        }

        sp<LogMatchingTracker> atomMatcher = allAtomMatchers.at(trackerIndex);
        // For GaugeMetric atom, it should be simple matcher with one tagId.
        if (atomMatcher->getAtomIds().size() != 1) {
            return false;
        }
        int atomTagId = *(atomMatcher->getAtomIds().begin());
        int pullTagId = pullerManager->PullerForMatcherExists(atomTagId) ? atomTagId : -1;

        int triggerTrackerIndex;
        int triggerAtomId = -1;
        if (metric.has_trigger_event()) {
            if (pullTagId == -1) {
                ALOGW("Pull atom not specified for trigger");
                return false;
            }
            // event_trigger should be used with FIRST_N_SAMPLES
            if (metric.sampling_type() != GaugeMetric::FIRST_N_SAMPLES) {
                return false;
            }
            if (!handlePullMetricTriggerWithLogTrackers(metric.trigger_event(), metricIndex,
                                                        allAtomMatchers, logTrackerMap,
                                                        trackerToMetricMap, triggerTrackerIndex)) {
                return false;
            }
            sp<LogMatchingTracker> triggerAtomMatcher = allAtomMatchers.at(triggerTrackerIndex);
            triggerAtomId = *(triggerAtomMatcher->getAtomIds().begin());
        }

        if (!metric.has_trigger_event() && pullTagId != -1 &&
            metric.sampling_type() == GaugeMetric::FIRST_N_SAMPLES) {
            ALOGW("FIRST_N_SAMPLES is only for pushed event or pull_on_trigger");
            return false;
        }

        int conditionIndex = -1;
        if (metric.has_condition()) {
            bool good = handleMetricWithConditions(
                    metric.condition(), metricIndex, conditionTrackerMap, metric.links(),
                    allConditionTrackers, conditionIndex, conditionToMetricMap);
            if (!good) {
                return false;
            }
        } else {
            if (metric.links_size() > 0) {
                ALOGW("metrics has a MetricConditionLink but doesn't have a condition");
                return false;
            }
        }

        unordered_map<int, shared_ptr<Activation>> eventActivationMap;
        unordered_map<int, vector<shared_ptr<Activation>>> eventDeactivationMap;
        bool success = handleMetricActivation(config, metric.id(), metricIndex,
                metricToActivationMap, logTrackerMap, activationAtomTrackerToMetricMap,
                deactivationAtomTrackerToMetricMap, metricsWithActivation, eventActivationMap,
                eventDeactivationMap);
        if (!success) return false;

        sp<MetricProducer> gaugeProducer = new GaugeMetricProducer(
                key, metric, conditionIndex, wizard, trackerIndex, matcherWizard, pullTagId,
                triggerAtomId, atomTagId, timeBaseTimeNs, currentTimeNs, pullerManager,
                eventActivationMap, eventDeactivationMap);
        allMetricProducers.push_back(gaugeProducer);
    }
    for (int i = 0; i < config.no_report_metric_size(); ++i) {
        const auto no_report_metric = config.no_report_metric(i);
        if (metricMap.find(no_report_metric) == metricMap.end()) {
            ALOGW("no_report_metric %" PRId64 " not exist", no_report_metric);
            return false;
        }
        noReportMetricIds.insert(no_report_metric);
    }
    for (const auto& it : allMetricProducers) {
        // Register metrics to StateTrackers
        for (int atomId : it->getSlicedStateAtoms()) {
            StateManager::getInstance().registerListener(atomId, it);
        }
    }
    return true;
}

bool initAlerts(const StatsdConfig& config,
                const unordered_map<int64_t, int>& metricProducerMap,
                unordered_map<int64_t, int>& alertTrackerMap,
                const sp<AlarmMonitor>& anomalyAlarmMonitor,
                vector<sp<MetricProducer>>& allMetricProducers,
                vector<sp<AnomalyTracker>>& allAnomalyTrackers) {
    for (int i = 0; i < config.alert_size(); i++) {
        const Alert& alert = config.alert(i);
        const auto& itr = metricProducerMap.find(alert.metric_id());
        if (itr == metricProducerMap.end()) {
            ALOGW("alert \"%lld\" has unknown metric id: \"%lld\"", (long long)alert.id(),
                  (long long)alert.metric_id());
            return false;
        }
        if (!alert.has_trigger_if_sum_gt()) {
            ALOGW("invalid alert: missing threshold");
            return false;
        }
        if (alert.trigger_if_sum_gt() < 0 || alert.num_buckets() <= 0) {
            ALOGW("invalid alert: threshold=%f num_buckets= %d",
                  alert.trigger_if_sum_gt(), alert.num_buckets());
            return false;
        }
        const int metricIndex = itr->second;
        sp<MetricProducer> metric = allMetricProducers[metricIndex];
        sp<AnomalyTracker> anomalyTracker = metric->addAnomalyTracker(alert, anomalyAlarmMonitor);
        if (anomalyTracker == nullptr) {
            // The ALOGW for this invalid alert was already displayed in addAnomalyTracker().
            return false;
        }
        alertTrackerMap.insert(std::make_pair(alert.id(), allAnomalyTrackers.size()));
        allAnomalyTrackers.push_back(anomalyTracker);
    }
    for (int i = 0; i < config.subscription_size(); ++i) {
        const Subscription& subscription = config.subscription(i);
        if (subscription.rule_type() != Subscription::ALERT) {
            continue;
        }
        if (subscription.subscriber_information_case() ==
            Subscription::SubscriberInformationCase::SUBSCRIBER_INFORMATION_NOT_SET) {
            ALOGW("subscription \"%lld\" has no subscriber info.\"",
                (long long)subscription.id());
            return false;
        }
        const auto& itr = alertTrackerMap.find(subscription.rule_id());
        if (itr == alertTrackerMap.end()) {
            ALOGW("subscription \"%lld\" has unknown rule id: \"%lld\"",
                (long long)subscription.id(), (long long)subscription.rule_id());
            return false;
        }
        const int anomalyTrackerIndex = itr->second;
        allAnomalyTrackers[anomalyTrackerIndex]->addSubscription(subscription);
    }
    return true;
}

bool initAlarms(const StatsdConfig& config, const ConfigKey& key,
                const sp<AlarmMonitor>& periodicAlarmMonitor,
                const int64_t timeBaseNs, const int64_t currentTimeNs,
                vector<sp<AlarmTracker>>& allAlarmTrackers) {
    unordered_map<int64_t, int> alarmTrackerMap;
    int64_t startMillis = timeBaseNs / 1000 / 1000;
    int64_t currentTimeMillis = currentTimeNs / 1000 /1000;
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
            new AlarmTracker(startMillis, currentTimeMillis,
                             alarm, key, periodicAlarmMonitor));
    }
    for (int i = 0; i < config.subscription_size(); ++i) {
        const Subscription& subscription = config.subscription(i);
        if (subscription.rule_type() != Subscription::ALARM) {
            continue;
        }
        if (subscription.subscriber_information_case() ==
            Subscription::SubscriberInformationCase::SUBSCRIBER_INFORMATION_NOT_SET) {
            ALOGW("subscription \"%lld\" has no subscriber info.\"",
                (long long)subscription.id());
            return false;
        }
        const auto& itr = alarmTrackerMap.find(subscription.rule_id());
        if (itr == alarmTrackerMap.end()) {
            ALOGW("subscription \"%lld\" has unknown rule id: \"%lld\"",
                (long long)subscription.id(), (long long)subscription.rule_id());
            return false;
        }
        const int trackerIndex = itr->second;
        allAlarmTrackers[trackerIndex]->addSubscription(subscription);
    }
    return true;
}

bool initStatsdConfig(const ConfigKey& key, const StatsdConfig& config, UidMap& uidMap,
                      const sp<StatsPullerManager>& pullerManager,
                      const sp<AlarmMonitor>& anomalyAlarmMonitor,
                      const sp<AlarmMonitor>& periodicAlarmMonitor, const int64_t timeBaseNs,
                      const int64_t currentTimeNs, set<int>& allTagIds,
                      vector<sp<LogMatchingTracker>>& allAtomMatchers,
                      vector<sp<ConditionTracker>>& allConditionTrackers,
                      vector<sp<MetricProducer>>& allMetricProducers,
                      vector<sp<AnomalyTracker>>& allAnomalyTrackers,
                      vector<sp<AlarmTracker>>& allPeriodicAlarmTrackers,
                      unordered_map<int, std::vector<int>>& conditionToMetricMap,
                      unordered_map<int, std::vector<int>>& trackerToMetricMap,
                      unordered_map<int, std::vector<int>>& trackerToConditionMap,
                      unordered_map<int, std::vector<int>>& activationAtomTrackerToMetricMap,
                      unordered_map<int, std::vector<int>>& deactivationAtomTrackerToMetricMap,
                      unordered_map<int64_t, int>& alertTrackerMap,
                      vector<int>& metricsWithActivation,
                      std::set<int64_t>& noReportMetricIds) {
    unordered_map<int64_t, int> logTrackerMap;
    unordered_map<int64_t, int> conditionTrackerMap;
    unordered_map<int64_t, int> metricProducerMap;
    unordered_map<int64_t, int> stateAtomIdMap;
    unordered_map<int64_t, unordered_map<int, int64_t>> allStateGroupMaps;

    if (!initLogTrackers(config, uidMap, logTrackerMap, allAtomMatchers, allTagIds)) {
        ALOGE("initLogMatchingTrackers failed");
        return false;
    }
    VLOG("initLogMatchingTrackers succeed...");

    if (!initConditions(key, config, logTrackerMap, conditionTrackerMap, allConditionTrackers,
                        trackerToConditionMap)) {
        ALOGE("initConditionTrackers failed");
        return false;
    }

    if (!initStates(config, stateAtomIdMap, allStateGroupMaps)) {
        ALOGE("initStates failed");
        return false;
    }
    if (!initMetrics(key, config, timeBaseNs, currentTimeNs, pullerManager, logTrackerMap,
                     conditionTrackerMap, allAtomMatchers, stateAtomIdMap, allStateGroupMaps,
                     allConditionTrackers, allMetricProducers,
                     conditionToMetricMap, trackerToMetricMap, metricProducerMap,
                     noReportMetricIds, activationAtomTrackerToMetricMap,
                     deactivationAtomTrackerToMetricMap, metricsWithActivation)) {
        ALOGE("initMetricProducers failed");
        return false;
    }
    if (!initAlerts(config, metricProducerMap, alertTrackerMap, anomalyAlarmMonitor,
                    allMetricProducers, allAnomalyTrackers)) {
        ALOGE("initAlerts failed");
        return false;
    }
    if (!initAlarms(config, key, periodicAlarmMonitor,
                    timeBaseNs, currentTimeNs, allPeriodicAlarmTrackers)) {
        ALOGE("initAlarms failed");
        return false;
    }

    return true;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
