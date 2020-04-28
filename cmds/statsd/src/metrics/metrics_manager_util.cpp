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

#include "../condition/CombinationConditionTracker.h"
#include "../condition/SimpleConditionTracker.h"
#include "../condition/StateTracker.h"
#include "../external/StatsPullerManager.h"
#include "../matchers/CombinationLogMatchingTracker.h"
#include "../matchers/SimpleLogMatchingTracker.h"
#include "../matchers/EventMatcherWizard.h"
#include "../metrics/CountMetricProducer.h"
#include "../metrics/DurationMetricProducer.h"
#include "../metrics/EventMetricProducer.h"
#include "../metrics/GaugeMetricProducer.h"
#include "../metrics/ValueMetricProducer.h"

#include "stats_util.h"
#include "statslog.h"

using std::set;
using std::string;
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

/**
 * A StateTracker is built from a SimplePredicate which has only "start", and no "stop"
 * or "stop_all". The start must be an atom matcher that matches a state atom. It must
 * have dimension, the dimension must be the state atom's primary fields plus exclusive state
 * field. For example, the StateTracker is used in tracking UidProcessState and ScreenState.
 *
 */
bool isStateTracker(const SimplePredicate& simplePredicate, vector<Matcher>* primaryKeys) {
    // 1. must not have "stop". must have "dimension"
    if (!simplePredicate.has_stop() && simplePredicate.has_dimensions()) {
        auto it = android::util::AtomsInfo::kStateAtomsFieldOptions.find(
                simplePredicate.dimensions().field());
        // 2. must be based on a state atom.
        if (it != android::util::AtomsInfo::kStateAtomsFieldOptions.end()) {
            // 3. dimension must be primary fields + state field IN ORDER
            size_t expectedDimensionCount = it->second.primaryFields.size() + 1;
            vector<Matcher> dimensions;
            translateFieldMatcher(simplePredicate.dimensions(), &dimensions);
            if (dimensions.size() != expectedDimensionCount) {
                return false;
            }
            // 3.1 check the primary fields first.
            size_t index = 0;
            for (const auto& field : it->second.primaryFields) {
                Matcher matcher = getSimpleMatcher(it->first, field);
                if (!(matcher == dimensions[index])) {
                    return false;
                }
                primaryKeys->push_back(matcher);
                index++;
            }
            Matcher stateFieldMatcher =
                    getSimpleMatcher(it->first, it->second.exclusiveField);
            // 3.2 last dimension should be the exclusive field.
            if (!(dimensions.back() == stateFieldMatcher)) {
                return false;
            }
            return true;
        }
    }
    return false;
}  // namespace statsd

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
                vector<Matcher> primaryKeys;
                if (isStateTracker(condition.simple_predicate(), &primaryKeys)) {
                    allConditionTrackers.push_back(new StateTracker(key, condition.id(), index,
                                                                    condition.simple_predicate(),
                                                                    logTrackerMap, primaryKeys));
                } else {
                    allConditionTrackers.push_back(new SimpleConditionTracker(
                            key, condition.id(), index, condition.simple_predicate(),
                            logTrackerMap));
                }
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

bool initMetrics(const ConfigKey& key, const StatsdConfig& config, const int64_t timeBaseTimeNs,
                 const int64_t currentTimeNs, UidMap& uidMap,
                 const sp<StatsPullerManager>& pullerManager,
                 const unordered_map<int64_t, int>& logTrackerMap,
                 const unordered_map<int64_t, int>& conditionTrackerMap,
                 const vector<sp<LogMatchingTracker>>& allAtomMatchers,
                 vector<sp<ConditionTracker>>& allConditionTrackers,
                 vector<sp<MetricProducer>>& allMetricProducers,
                 unordered_map<int, std::vector<int>>& conditionToMetricMap,
                 unordered_map<int, std::vector<int>>& trackerToMetricMap,
                 unordered_map<int64_t, int>& metricMap, std::set<int64_t>& noReportMetricIds) {
    sp<ConditionWizard> wizard = new ConditionWizard(allConditionTrackers);
    sp<EventMatcherWizard> matcherWizard = new EventMatcherWizard(allAtomMatchers);
    const int allMetricsCount = config.count_metric_size() + config.duration_metric_size() +
                                config.event_metric_size() + config.value_metric_size();
    allMetricProducers.reserve(allMetricsCount);
    StatsPullerManager statsPullerManager;

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

        sp<MetricProducer> countProducer =
                new CountMetricProducer(key, metric, conditionIndex, wizard, timeBaseTimeNs, currentTimeNs);
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

        sp<MetricProducer> durationMetric = new DurationMetricProducer(
                key, metric, conditionIndex, trackerIndices[0], trackerIndices[1],
                trackerIndices[2], nesting, wizard, internalDimensions, timeBaseTimeNs, currentTimeNs);

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

        sp<MetricProducer> eventMetric =
                new EventMetricProducer(key, metric, conditionIndex, wizard, timeBaseTimeNs);

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
        int pullTagId = statsPullerManager.PullerForMatcherExists(atomTagId) ? atomTagId : -1;

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

        sp<MetricProducer> valueProducer = new ValueMetricProducer(
                key, metric, conditionIndex, wizard, trackerIndex, matcherWizard, pullTagId,
                timeBaseTimeNs, currentTimeNs, pullerManager);
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
        int pullTagId = statsPullerManager.PullerForMatcherExists(atomTagId) ? atomTagId : -1;

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

        sp<MetricProducer> gaugeProducer = new GaugeMetricProducer(
                key, metric, conditionIndex, wizard,
                trackerIndex, matcherWizard, pullTagId, triggerAtomId, atomTagId,
                timeBaseTimeNs, currentTimeNs, pullerManager);
        allMetricProducers.push_back(gaugeProducer);
    }
    for (int i = 0; i < config.no_report_metric_size(); ++i) {
        const auto no_report_metric = config.no_report_metric(i);
        if (metricMap.find(no_report_metric) == metricMap.end()) {
            ALOGW("no_report_metric %lld not exist", no_report_metric);
            return false;
        }
        noReportMetricIds.insert(no_report_metric);
    }
    for (const auto& it : allMetricProducers) {
        uidMap.addListener(it);
    }
    return true;
}

bool initAlerts(const StatsdConfig& config,
                const unordered_map<int64_t, int>& metricProducerMap,
                const sp<AlarmMonitor>& anomalyAlarmMonitor,
                vector<sp<MetricProducer>>& allMetricProducers,
                vector<sp<AnomalyTracker>>& allAnomalyTrackers) {
    unordered_map<int64_t, int> anomalyTrackerMap;
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
        anomalyTrackerMap.insert(std::make_pair(alert.id(), allAnomalyTrackers.size()));
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
        const auto& itr = anomalyTrackerMap.find(subscription.rule_id());
        if (itr == anomalyTrackerMap.end()) {
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

bool initMetricActivations(const ConfigKey& key, const StatsdConfig& config,
                           const int64_t currentTimeNs,
                           const unordered_map<int64_t, int> &logEventTrackerMap,
                           const unordered_map<int64_t, int> &metricProducerMap,
                           vector<sp<MetricProducer>>& allMetricProducers,
                           unordered_map<int, std::vector<int>>& activationAtomTrackerToMetricMap,
                           unordered_map<int, std::vector<int>>& deactivationAtomTrackerToMetricMap,
                           vector<int>& metricsWithActivation) {
    for (int i = 0; i < config.metric_activation_size(); ++i) {
        const MetricActivation& metric_activation = config.metric_activation(i);
        auto itr = metricProducerMap.find(metric_activation.metric_id());
        if (itr == metricProducerMap.end()) {
            ALOGE("Metric id not found in metric activation: %lld",
                (long long)metric_activation.metric_id());
            return false;
        }
        const int metricTrackerIndex = itr->second;
        if (metricTrackerIndex < 0 || metricTrackerIndex >= (int)allMetricProducers.size()) {
            ALOGE("Invalid metric tracker index.");
            return false;
        }
        const sp<MetricProducer>& metric = allMetricProducers[metricTrackerIndex];
        metricsWithActivation.push_back(metricTrackerIndex);
        for (int j = 0; j < metric_activation.event_activation_size(); ++j) {
            const EventActivation& activation = metric_activation.event_activation(j);
            auto logTrackerIt = logEventTrackerMap.find(activation.atom_matcher_id());
            if (logTrackerIt == logEventTrackerMap.end()) {
                ALOGE("Atom matcher not found for event activation.");
                return false;
            }
            const int atomMatcherIndex = logTrackerIt->second;
            activationAtomTrackerToMetricMap[atomMatcherIndex].push_back(
                metricTrackerIndex);

            ActivationType activationType;
            if (activation.has_activation_type()) {
                activationType = activation.activation_type();
            } else {
                activationType = metric_activation.activation_type();
            }

            if (activation.has_deactivation_atom_matcher_id()) {
                auto deactivationAtomMatcherIt =
                        logEventTrackerMap.find(activation.deactivation_atom_matcher_id());
                if (deactivationAtomMatcherIt == logEventTrackerMap.end()) {
                    ALOGE("Atom matcher not found for event deactivation.");
                    return false;
                }
                const int deactivationMatcherIndex = deactivationAtomMatcherIt->second;
                deactivationAtomTrackerToMetricMap[deactivationMatcherIndex]
                        .push_back(metricTrackerIndex);
                metric->addActivation(atomMatcherIndex, activationType, activation.ttl_seconds(),
                                      deactivationMatcherIndex);
            } else {
                metric->addActivation(atomMatcherIndex, activationType, activation.ttl_seconds());
            }
        }
    }
    return true;
}

void prepareFirstBucket(const vector<sp<MetricProducer>>& allMetricProducers) {
    for (const auto& metric: allMetricProducers) {
        metric->prepareFirstBucket();
    }
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
                      vector<int>& metricsWithActivation,
                      std::set<int64_t>& noReportMetricIds) {
    unordered_map<int64_t, int> logTrackerMap;
    unordered_map<int64_t, int> conditionTrackerMap;
    unordered_map<int64_t, int> metricProducerMap;

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

    if (!initMetrics(key, config, timeBaseNs, currentTimeNs, uidMap, pullerManager, logTrackerMap,
                     conditionTrackerMap, allAtomMatchers, allConditionTrackers, allMetricProducers,
                     conditionToMetricMap, trackerToMetricMap, metricProducerMap,
                     noReportMetricIds)) {
        ALOGE("initMetricProducers failed");
        return false;
    }
    if (!initAlerts(config, metricProducerMap, anomalyAlarmMonitor, allMetricProducers,
                    allAnomalyTrackers)) {
        ALOGE("initAlerts failed");
        return false;
    }
    if (!initAlarms(config, key, periodicAlarmMonitor,
                    timeBaseNs, currentTimeNs, allPeriodicAlarmTrackers)) {
        ALOGE("initAlarms failed");
        return false;
    }
    if (!initMetricActivations(key, config, currentTimeNs, logTrackerMap, metricProducerMap,
            allMetricProducers, activationAtomTrackerToMetricMap,
            deactivationAtomTrackerToMetricMap, metricsWithActivation)) {
        ALOGE("initMetricActivations failed");
        return false;
    }

    prepareFirstBucket(allMetricProducers);

    return true;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
