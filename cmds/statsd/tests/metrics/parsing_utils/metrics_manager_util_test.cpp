// Copyright (C) 2020 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "src/metrics/parsing_utils/metrics_manager_util.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <private/android_filesystem_config.h>
#include <stdio.h>

#include <set>
#include <unordered_map>
#include <vector>

#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "src/condition/ConditionTracker.h"
#include "src/matchers/AtomMatchingTracker.h"
#include "src/metrics/CountMetricProducer.h"
#include "src/metrics/GaugeMetricProducer.h"
#include "src/metrics/MetricProducer.h"
#include "src/metrics/ValueMetricProducer.h"
#include "src/state/StateManager.h"
#include "tests/metrics/metrics_test_helper.h"
#include "tests/statsd_test_util.h"

using namespace testing;
using android::sp;
using android::os::statsd::Predicate;
using std::map;
using std::set;
using std::unordered_map;
using std::vector;

#ifdef __ANDROID__

namespace android {
namespace os {
namespace statsd {

namespace {
const ConfigKey kConfigKey(0, 12345);
const long kAlertId = 3;

const long timeBaseSec = 1000;

StatsdConfig buildGoodConfig() {
    StatsdConfig config;
    config.set_id(12345);

    AtomMatcher* eventMatcher = config.add_atom_matcher();
    eventMatcher->set_id(StringToId("SCREEN_IS_ON"));

    SimpleAtomMatcher* simpleAtomMatcher = eventMatcher->mutable_simple_atom_matcher();
    simpleAtomMatcher->set_atom_id(2 /*SCREEN_STATE_CHANGE*/);
    simpleAtomMatcher->add_field_value_matcher()->set_field(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE*/);
    simpleAtomMatcher->mutable_field_value_matcher(0)->set_eq_int(
            2 /*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_ON*/);

    eventMatcher = config.add_atom_matcher();
    eventMatcher->set_id(StringToId("SCREEN_IS_OFF"));

    simpleAtomMatcher = eventMatcher->mutable_simple_atom_matcher();
    simpleAtomMatcher->set_atom_id(2 /*SCREEN_STATE_CHANGE*/);
    simpleAtomMatcher->add_field_value_matcher()->set_field(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE*/);
    simpleAtomMatcher->mutable_field_value_matcher(0)->set_eq_int(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_OFF*/);

    eventMatcher = config.add_atom_matcher();
    eventMatcher->set_id(StringToId("SCREEN_ON_OR_OFF"));

    AtomMatcher_Combination* combination = eventMatcher->mutable_combination();
    combination->set_operation(LogicalOperation::OR);
    combination->add_matcher(StringToId("SCREEN_IS_ON"));
    combination->add_matcher(StringToId("SCREEN_IS_OFF"));

    CountMetric* metric = config.add_count_metric();
    metric->set_id(3);
    metric->set_what(StringToId("SCREEN_IS_ON"));
    metric->set_bucket(ONE_MINUTE);
    metric->mutable_dimensions_in_what()->set_field(2 /*SCREEN_STATE_CHANGE*/);
    metric->mutable_dimensions_in_what()->add_child()->set_field(1);

    config.add_no_report_metric(3);

    auto alert = config.add_alert();
    alert->set_id(kAlertId);
    alert->set_metric_id(3);
    alert->set_num_buckets(10);
    alert->set_refractory_period_secs(100);
    alert->set_trigger_if_sum_gt(100);
    return config;
}

StatsdConfig buildCircleMatchers() {
    StatsdConfig config;
    config.set_id(12345);

    AtomMatcher* eventMatcher = config.add_atom_matcher();
    eventMatcher->set_id(StringToId("SCREEN_IS_ON"));

    SimpleAtomMatcher* simpleAtomMatcher = eventMatcher->mutable_simple_atom_matcher();
    simpleAtomMatcher->set_atom_id(2 /*SCREEN_STATE_CHANGE*/);
    simpleAtomMatcher->add_field_value_matcher()->set_field(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE*/);
    simpleAtomMatcher->mutable_field_value_matcher(0)->set_eq_int(
            2 /*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_ON*/);

    eventMatcher = config.add_atom_matcher();
    eventMatcher->set_id(StringToId("SCREEN_ON_OR_OFF"));

    AtomMatcher_Combination* combination = eventMatcher->mutable_combination();
    combination->set_operation(LogicalOperation::OR);
    combination->add_matcher(StringToId("SCREEN_IS_ON"));
    // Circle dependency
    combination->add_matcher(StringToId("SCREEN_ON_OR_OFF"));

    return config;
}

StatsdConfig buildAlertWithUnknownMetric() {
    StatsdConfig config;
    config.set_id(12345);

    AtomMatcher* eventMatcher = config.add_atom_matcher();
    eventMatcher->set_id(StringToId("SCREEN_IS_ON"));

    CountMetric* metric = config.add_count_metric();
    metric->set_id(3);
    metric->set_what(StringToId("SCREEN_IS_ON"));
    metric->set_bucket(ONE_MINUTE);
    metric->mutable_dimensions_in_what()->set_field(2 /*SCREEN_STATE_CHANGE*/);
    metric->mutable_dimensions_in_what()->add_child()->set_field(1);

    auto alert = config.add_alert();
    alert->set_id(3);
    alert->set_metric_id(2);
    alert->set_num_buckets(10);
    alert->set_refractory_period_secs(100);
    alert->set_trigger_if_sum_gt(100);
    return config;
}

StatsdConfig buildMissingMatchers() {
    StatsdConfig config;
    config.set_id(12345);

    AtomMatcher* eventMatcher = config.add_atom_matcher();
    eventMatcher->set_id(StringToId("SCREEN_IS_ON"));

    SimpleAtomMatcher* simpleAtomMatcher = eventMatcher->mutable_simple_atom_matcher();
    simpleAtomMatcher->set_atom_id(2 /*SCREEN_STATE_CHANGE*/);
    simpleAtomMatcher->add_field_value_matcher()->set_field(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE*/);
    simpleAtomMatcher->mutable_field_value_matcher(0)->set_eq_int(
            2 /*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_ON*/);

    eventMatcher = config.add_atom_matcher();
    eventMatcher->set_id(StringToId("SCREEN_ON_OR_OFF"));

    AtomMatcher_Combination* combination = eventMatcher->mutable_combination();
    combination->set_operation(LogicalOperation::OR);
    combination->add_matcher(StringToId("SCREEN_IS_ON"));
    // undefined matcher
    combination->add_matcher(StringToId("ABC"));

    return config;
}

StatsdConfig buildMissingPredicate() {
    StatsdConfig config;
    config.set_id(12345);

    CountMetric* metric = config.add_count_metric();
    metric->set_id(3);
    metric->set_what(StringToId("SCREEN_EVENT"));
    metric->set_bucket(ONE_MINUTE);
    metric->set_condition(StringToId("SOME_CONDITION"));

    AtomMatcher* eventMatcher = config.add_atom_matcher();
    eventMatcher->set_id(StringToId("SCREEN_EVENT"));

    SimpleAtomMatcher* simpleAtomMatcher = eventMatcher->mutable_simple_atom_matcher();
    simpleAtomMatcher->set_atom_id(2);

    return config;
}

StatsdConfig buildDimensionMetricsWithMultiTags() {
    StatsdConfig config;
    config.set_id(12345);

    AtomMatcher* eventMatcher = config.add_atom_matcher();
    eventMatcher->set_id(StringToId("BATTERY_VERY_LOW"));
    SimpleAtomMatcher* simpleAtomMatcher = eventMatcher->mutable_simple_atom_matcher();
    simpleAtomMatcher->set_atom_id(2);

    eventMatcher = config.add_atom_matcher();
    eventMatcher->set_id(StringToId("BATTERY_VERY_VERY_LOW"));
    simpleAtomMatcher = eventMatcher->mutable_simple_atom_matcher();
    simpleAtomMatcher->set_atom_id(3);

    eventMatcher = config.add_atom_matcher();
    eventMatcher->set_id(StringToId("BATTERY_LOW"));

    AtomMatcher_Combination* combination = eventMatcher->mutable_combination();
    combination->set_operation(LogicalOperation::OR);
    combination->add_matcher(StringToId("BATTERY_VERY_LOW"));
    combination->add_matcher(StringToId("BATTERY_VERY_VERY_LOW"));

    // Count process state changes, slice by uid, while SCREEN_IS_OFF
    CountMetric* metric = config.add_count_metric();
    metric->set_id(3);
    metric->set_what(StringToId("BATTERY_LOW"));
    metric->set_bucket(ONE_MINUTE);
    // This case is interesting. We want to dimension across two atoms.
    metric->mutable_dimensions_in_what()->add_child()->set_field(1);

    auto alert = config.add_alert();
    alert->set_id(kAlertId);
    alert->set_metric_id(3);
    alert->set_num_buckets(10);
    alert->set_refractory_period_secs(100);
    alert->set_trigger_if_sum_gt(100);
    return config;
}

StatsdConfig buildCirclePredicates() {
    StatsdConfig config;
    config.set_id(12345);

    AtomMatcher* eventMatcher = config.add_atom_matcher();
    eventMatcher->set_id(StringToId("SCREEN_IS_ON"));

    SimpleAtomMatcher* simpleAtomMatcher = eventMatcher->mutable_simple_atom_matcher();
    simpleAtomMatcher->set_atom_id(2 /*SCREEN_STATE_CHANGE*/);
    simpleAtomMatcher->add_field_value_matcher()->set_field(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE*/);
    simpleAtomMatcher->mutable_field_value_matcher(0)->set_eq_int(
            2 /*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_ON*/);

    eventMatcher = config.add_atom_matcher();
    eventMatcher->set_id(StringToId("SCREEN_IS_OFF"));

    simpleAtomMatcher = eventMatcher->mutable_simple_atom_matcher();
    simpleAtomMatcher->set_atom_id(2 /*SCREEN_STATE_CHANGE*/);
    simpleAtomMatcher->add_field_value_matcher()->set_field(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE*/);
    simpleAtomMatcher->mutable_field_value_matcher(0)->set_eq_int(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_OFF*/);

    auto condition = config.add_predicate();
    condition->set_id(StringToId("SCREEN_IS_ON"));
    SimplePredicate* simplePredicate = condition->mutable_simple_predicate();
    simplePredicate->set_start(StringToId("SCREEN_IS_ON"));
    simplePredicate->set_stop(StringToId("SCREEN_IS_OFF"));

    condition = config.add_predicate();
    condition->set_id(StringToId("SCREEN_IS_EITHER_ON_OFF"));

    Predicate_Combination* combination = condition->mutable_combination();
    combination->set_operation(LogicalOperation::OR);
    combination->add_predicate(StringToId("SCREEN_IS_ON"));
    combination->add_predicate(StringToId("SCREEN_IS_EITHER_ON_OFF"));

    return config;
}

StatsdConfig buildConfigWithDifferentPredicates() {
    StatsdConfig config;
    config.set_id(12345);

    auto pulledAtomMatcher =
            CreateSimpleAtomMatcher("SUBSYSTEM_SLEEP", util::SUBSYSTEM_SLEEP_STATE);
    *config.add_atom_matcher() = pulledAtomMatcher;
    auto screenOnAtomMatcher = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = screenOnAtomMatcher;
    auto screenOffAtomMatcher = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = screenOffAtomMatcher;
    auto batteryNoneAtomMatcher = CreateBatteryStateNoneMatcher();
    *config.add_atom_matcher() = batteryNoneAtomMatcher;
    auto batteryUsbAtomMatcher = CreateBatteryStateUsbMatcher();
    *config.add_atom_matcher() = batteryUsbAtomMatcher;

    // Simple condition with InitialValue set to default (unknown).
    auto screenOnUnknownPredicate = CreateScreenIsOnPredicate();
    *config.add_predicate() = screenOnUnknownPredicate;

    // Simple condition with InitialValue set to false.
    auto screenOnFalsePredicate = config.add_predicate();
    screenOnFalsePredicate->set_id(StringToId("ScreenIsOnInitialFalse"));
    SimplePredicate* simpleScreenOnFalsePredicate =
            screenOnFalsePredicate->mutable_simple_predicate();
    simpleScreenOnFalsePredicate->set_start(screenOnAtomMatcher.id());
    simpleScreenOnFalsePredicate->set_stop(screenOffAtomMatcher.id());
    simpleScreenOnFalsePredicate->set_initial_value(SimplePredicate_InitialValue_FALSE);

    // Simple condition with InitialValue set to false.
    auto onBatteryFalsePredicate = config.add_predicate();
    onBatteryFalsePredicate->set_id(StringToId("OnBatteryInitialFalse"));
    SimplePredicate* simpleOnBatteryFalsePredicate =
            onBatteryFalsePredicate->mutable_simple_predicate();
    simpleOnBatteryFalsePredicate->set_start(batteryNoneAtomMatcher.id());
    simpleOnBatteryFalsePredicate->set_stop(batteryUsbAtomMatcher.id());
    simpleOnBatteryFalsePredicate->set_initial_value(SimplePredicate_InitialValue_FALSE);

    // Combination condition with both simple condition InitialValues set to false.
    auto screenOnFalseOnBatteryFalsePredicate = config.add_predicate();
    screenOnFalseOnBatteryFalsePredicate->set_id(StringToId("ScreenOnFalseOnBatteryFalse"));
    screenOnFalseOnBatteryFalsePredicate->mutable_combination()->set_operation(
            LogicalOperation::AND);
    addPredicateToPredicateCombination(*screenOnFalsePredicate,
                                       screenOnFalseOnBatteryFalsePredicate);
    addPredicateToPredicateCombination(*onBatteryFalsePredicate,
                                       screenOnFalseOnBatteryFalsePredicate);

    // Combination condition with one simple condition InitialValue set to unknown and one set to
    // false.
    auto screenOnUnknownOnBatteryFalsePredicate = config.add_predicate();
    screenOnUnknownOnBatteryFalsePredicate->set_id(StringToId("ScreenOnUnknowneOnBatteryFalse"));
    screenOnUnknownOnBatteryFalsePredicate->mutable_combination()->set_operation(
            LogicalOperation::AND);
    addPredicateToPredicateCombination(screenOnUnknownPredicate,
                                       screenOnUnknownOnBatteryFalsePredicate);
    addPredicateToPredicateCombination(*onBatteryFalsePredicate,
                                       screenOnUnknownOnBatteryFalsePredicate);

    // Simple condition metric with initial value false.
    ValueMetric* metric1 = config.add_value_metric();
    metric1->set_id(StringToId("ValueSubsystemSleepWhileScreenOnInitialFalse"));
    metric1->set_what(pulledAtomMatcher.id());
    *metric1->mutable_value_field() =
            CreateDimensions(util::SUBSYSTEM_SLEEP_STATE, {4 /* time sleeping field */});
    metric1->set_bucket(FIVE_MINUTES);
    metric1->set_condition(screenOnFalsePredicate->id());

    // Simple condition metric with initial value unknown.
    ValueMetric* metric2 = config.add_value_metric();
    metric2->set_id(StringToId("ValueSubsystemSleepWhileScreenOnInitialUnknown"));
    metric2->set_what(pulledAtomMatcher.id());
    *metric2->mutable_value_field() =
            CreateDimensions(util::SUBSYSTEM_SLEEP_STATE, {4 /* time sleeping field */});
    metric2->set_bucket(FIVE_MINUTES);
    metric2->set_condition(screenOnUnknownPredicate.id());

    // Combination condition metric with initial values false and false.
    ValueMetric* metric3 = config.add_value_metric();
    metric3->set_id(StringToId("ValueSubsystemSleepWhileScreenOnFalseDeviceUnpluggedFalse"));
    metric3->set_what(pulledAtomMatcher.id());
    *metric3->mutable_value_field() =
            CreateDimensions(util::SUBSYSTEM_SLEEP_STATE, {4 /* time sleeping field */});
    metric3->set_bucket(FIVE_MINUTES);
    metric3->set_condition(screenOnFalseOnBatteryFalsePredicate->id());

    // Combination condition metric with initial values unknown and false.
    ValueMetric* metric4 = config.add_value_metric();
    metric4->set_id(StringToId("ValueSubsystemSleepWhileScreenOnUnknownDeviceUnpluggedFalse"));
    metric4->set_what(pulledAtomMatcher.id());
    *metric4->mutable_value_field() =
            CreateDimensions(util::SUBSYSTEM_SLEEP_STATE, {4 /* time sleeping field */});
    metric4->set_bucket(FIVE_MINUTES);
    metric4->set_condition(screenOnUnknownOnBatteryFalsePredicate->id());

    return config;
}
}  // anonymous namespace

TEST(MetricsManagerTest, TestInitialConditions) {
    sp<UidMap> uidMap = new UidMap();
    sp<StatsPullerManager> pullerManager = new StatsPullerManager();
    sp<AlarmMonitor> anomalyAlarmMonitor;
    sp<AlarmMonitor> periodicAlarmMonitor;
    StatsdConfig config = buildConfigWithDifferentPredicates();
    set<int> allTagIds;
    vector<sp<AtomMatchingTracker>> allAtomMatchingTrackers;
    unordered_map<int64_t, int> atomMatchingTrackerMap;
    vector<sp<ConditionTracker>> allConditionTrackers;
    unordered_map<int64_t, int> conditionTrackerMap;
    vector<sp<MetricProducer>> allMetricProducers;
    unordered_map<int64_t, int> metricProducerMap;
    std::vector<sp<AnomalyTracker>> allAnomalyTrackers;
    std::vector<sp<AlarmTracker>> allAlarmTrackers;
    unordered_map<int, std::vector<int>> conditionToMetricMap;
    unordered_map<int, std::vector<int>> trackerToMetricMap;
    unordered_map<int, std::vector<int>> trackerToConditionMap;
    unordered_map<int, std::vector<int>> activationAtomTrackerToMetricMap;
    unordered_map<int, std::vector<int>> deactivationAtomTrackerToMetricMap;
    unordered_map<int64_t, int> alertTrackerMap;
    vector<int> metricsWithActivation;
    map<int64_t, uint64_t> stateProtoHashes;
    std::set<int64_t> noReportMetricIds;

    EXPECT_TRUE(initStatsdConfig(
            kConfigKey, config, uidMap, pullerManager, anomalyAlarmMonitor, periodicAlarmMonitor,
            timeBaseSec, timeBaseSec, allTagIds, allAtomMatchingTrackers, atomMatchingTrackerMap,
            allConditionTrackers, conditionTrackerMap, allMetricProducers, metricProducerMap,
            allAnomalyTrackers, allAlarmTrackers, conditionToMetricMap, trackerToMetricMap,
            trackerToConditionMap, activationAtomTrackerToMetricMap,
            deactivationAtomTrackerToMetricMap, alertTrackerMap, metricsWithActivation,
            stateProtoHashes, noReportMetricIds));
    ASSERT_EQ(4u, allMetricProducers.size());
    ASSERT_EQ(5u, allConditionTrackers.size());

    ConditionKey queryKey;
    vector<ConditionState> conditionCache(5, ConditionState::kNotEvaluated);

    allConditionTrackers[3]->isConditionMet(queryKey, allConditionTrackers, false, conditionCache);
    allConditionTrackers[4]->isConditionMet(queryKey, allConditionTrackers, false, conditionCache);
    EXPECT_EQ(ConditionState::kUnknown, conditionCache[0]);
    EXPECT_EQ(ConditionState::kFalse, conditionCache[1]);
    EXPECT_EQ(ConditionState::kFalse, conditionCache[2]);
    EXPECT_EQ(ConditionState::kFalse, conditionCache[3]);
    EXPECT_EQ(ConditionState::kUnknown, conditionCache[4]);

    EXPECT_EQ(ConditionState::kFalse, allMetricProducers[0]->mCondition);
    EXPECT_EQ(ConditionState::kUnknown, allMetricProducers[1]->mCondition);
    EXPECT_EQ(ConditionState::kFalse, allMetricProducers[2]->mCondition);
    EXPECT_EQ(ConditionState::kUnknown, allMetricProducers[3]->mCondition);
}

TEST(MetricsManagerTest, TestGoodConfig) {
    sp<UidMap> uidMap = new UidMap();
    sp<StatsPullerManager> pullerManager = new StatsPullerManager();
    sp<AlarmMonitor> anomalyAlarmMonitor;
    sp<AlarmMonitor> periodicAlarmMonitor;
    StatsdConfig config = buildGoodConfig();
    set<int> allTagIds;
    vector<sp<AtomMatchingTracker>> allAtomMatchingTrackers;
    unordered_map<int64_t, int> atomMatchingTrackerMap;
    vector<sp<ConditionTracker>> allConditionTrackers;
    unordered_map<int64_t, int> conditionTrackerMap;
    vector<sp<MetricProducer>> allMetricProducers;
    unordered_map<int64_t, int> metricProducerMap;
    std::vector<sp<AnomalyTracker>> allAnomalyTrackers;
    std::vector<sp<AlarmTracker>> allAlarmTrackers;
    unordered_map<int, std::vector<int>> conditionToMetricMap;
    unordered_map<int, std::vector<int>> trackerToMetricMap;
    unordered_map<int, std::vector<int>> trackerToConditionMap;
    unordered_map<int, std::vector<int>> activationAtomTrackerToMetricMap;
    unordered_map<int, std::vector<int>> deactivationAtomTrackerToMetricMap;
    unordered_map<int64_t, int> alertTrackerMap;
    vector<int> metricsWithActivation;
    map<int64_t, uint64_t> stateProtoHashes;
    std::set<int64_t> noReportMetricIds;

    EXPECT_TRUE(initStatsdConfig(
            kConfigKey, config, uidMap, pullerManager, anomalyAlarmMonitor, periodicAlarmMonitor,
            timeBaseSec, timeBaseSec, allTagIds, allAtomMatchingTrackers, atomMatchingTrackerMap,
            allConditionTrackers, conditionTrackerMap, allMetricProducers, metricProducerMap,
            allAnomalyTrackers, allAlarmTrackers, conditionToMetricMap, trackerToMetricMap,
            trackerToConditionMap, activationAtomTrackerToMetricMap,
            deactivationAtomTrackerToMetricMap, alertTrackerMap, metricsWithActivation,
            stateProtoHashes, noReportMetricIds));
    ASSERT_EQ(1u, allMetricProducers.size());
    EXPECT_THAT(metricProducerMap, UnorderedElementsAre(Pair(config.count_metric(0).id(), 0)));
    ASSERT_EQ(1u, allAnomalyTrackers.size());
    ASSERT_EQ(1u, noReportMetricIds.size());
    ASSERT_EQ(1u, alertTrackerMap.size());
    EXPECT_NE(alertTrackerMap.find(kAlertId), alertTrackerMap.end());
    EXPECT_EQ(alertTrackerMap.find(kAlertId)->second, 0);
}

TEST(MetricsManagerTest, TestDimensionMetricsWithMultiTags) {
    sp<UidMap> uidMap = new UidMap();
    sp<StatsPullerManager> pullerManager = new StatsPullerManager();
    sp<AlarmMonitor> anomalyAlarmMonitor;
    sp<AlarmMonitor> periodicAlarmMonitor;
    StatsdConfig config = buildDimensionMetricsWithMultiTags();
    set<int> allTagIds;
    vector<sp<AtomMatchingTracker>> allAtomMatchingTrackers;
    unordered_map<int64_t, int> atomMatchingTrackerMap;
    vector<sp<ConditionTracker>> allConditionTrackers;
    unordered_map<int64_t, int> conditionTrackerMap;
    vector<sp<MetricProducer>> allMetricProducers;
    unordered_map<int64_t, int> metricProducerMap;
    std::vector<sp<AnomalyTracker>> allAnomalyTrackers;
    std::vector<sp<AlarmTracker>> allAlarmTrackers;
    unordered_map<int, std::vector<int>> conditionToMetricMap;
    unordered_map<int, std::vector<int>> trackerToMetricMap;
    unordered_map<int, std::vector<int>> trackerToConditionMap;
    unordered_map<int, std::vector<int>> activationAtomTrackerToMetricMap;
    unordered_map<int, std::vector<int>> deactivationAtomTrackerToMetricMap;
    unordered_map<int64_t, int> alertTrackerMap;
    vector<int> metricsWithActivation;
    map<int64_t, uint64_t> stateProtoHashes;
    std::set<int64_t> noReportMetricIds;

    EXPECT_FALSE(initStatsdConfig(
            kConfigKey, config, uidMap, pullerManager, anomalyAlarmMonitor, periodicAlarmMonitor,
            timeBaseSec, timeBaseSec, allTagIds, allAtomMatchingTrackers, atomMatchingTrackerMap,
            allConditionTrackers, conditionTrackerMap, allMetricProducers, metricProducerMap,
            allAnomalyTrackers, allAlarmTrackers, conditionToMetricMap, trackerToMetricMap,
            trackerToConditionMap, activationAtomTrackerToMetricMap,
            deactivationAtomTrackerToMetricMap, alertTrackerMap, metricsWithActivation,
            stateProtoHashes, noReportMetricIds));
}

TEST(MetricsManagerTest, TestCircleLogMatcherDependency) {
    sp<UidMap> uidMap = new UidMap();
    sp<StatsPullerManager> pullerManager = new StatsPullerManager();
    sp<AlarmMonitor> anomalyAlarmMonitor;
    sp<AlarmMonitor> periodicAlarmMonitor;
    StatsdConfig config = buildCircleMatchers();
    set<int> allTagIds;
    vector<sp<AtomMatchingTracker>> allAtomMatchingTrackers;
    unordered_map<int64_t, int> atomMatchingTrackerMap;
    vector<sp<ConditionTracker>> allConditionTrackers;
    unordered_map<int64_t, int> conditionTrackerMap;
    vector<sp<MetricProducer>> allMetricProducers;
    unordered_map<int64_t, int> metricProducerMap;
    std::vector<sp<AnomalyTracker>> allAnomalyTrackers;
    std::vector<sp<AlarmTracker>> allAlarmTrackers;
    unordered_map<int, std::vector<int>> conditionToMetricMap;
    unordered_map<int, std::vector<int>> trackerToMetricMap;
    unordered_map<int, std::vector<int>> trackerToConditionMap;
    unordered_map<int, std::vector<int>> activationAtomTrackerToMetricMap;
    unordered_map<int, std::vector<int>> deactivationAtomTrackerToMetricMap;
    unordered_map<int64_t, int> alertTrackerMap;
    vector<int> metricsWithActivation;
    map<int64_t, uint64_t> stateProtoHashes;
    std::set<int64_t> noReportMetricIds;

    EXPECT_FALSE(initStatsdConfig(
            kConfigKey, config, uidMap, pullerManager, anomalyAlarmMonitor, periodicAlarmMonitor,
            timeBaseSec, timeBaseSec, allTagIds, allAtomMatchingTrackers, atomMatchingTrackerMap,
            allConditionTrackers, conditionTrackerMap, allMetricProducers, metricProducerMap,
            allAnomalyTrackers, allAlarmTrackers, conditionToMetricMap, trackerToMetricMap,
            trackerToConditionMap, activationAtomTrackerToMetricMap,
            deactivationAtomTrackerToMetricMap, alertTrackerMap, metricsWithActivation,
            stateProtoHashes, noReportMetricIds));
}

TEST(MetricsManagerTest, TestMissingMatchers) {
    sp<UidMap> uidMap = new UidMap();
    sp<StatsPullerManager> pullerManager = new StatsPullerManager();
    sp<AlarmMonitor> anomalyAlarmMonitor;
    sp<AlarmMonitor> periodicAlarmMonitor;
    StatsdConfig config = buildMissingMatchers();
    set<int> allTagIds;
    vector<sp<AtomMatchingTracker>> allAtomMatchingTrackers;
    unordered_map<int64_t, int> atomMatchingTrackerMap;
    vector<sp<ConditionTracker>> allConditionTrackers;
    unordered_map<int64_t, int> conditionTrackerMap;
    vector<sp<MetricProducer>> allMetricProducers;
    unordered_map<int64_t, int> metricProducerMap;
    std::vector<sp<AnomalyTracker>> allAnomalyTrackers;
    std::vector<sp<AlarmTracker>> allAlarmTrackers;
    unordered_map<int, std::vector<int>> conditionToMetricMap;
    unordered_map<int, std::vector<int>> trackerToMetricMap;
    unordered_map<int, std::vector<int>> trackerToConditionMap;
    unordered_map<int, std::vector<int>> activationAtomTrackerToMetricMap;
    unordered_map<int, std::vector<int>> deactivationAtomTrackerToMetricMap;
    unordered_map<int64_t, int> alertTrackerMap;
    vector<int> metricsWithActivation;
    map<int64_t, uint64_t> stateProtoHashes;
    std::set<int64_t> noReportMetricIds;
    EXPECT_FALSE(initStatsdConfig(
            kConfigKey, config, uidMap, pullerManager, anomalyAlarmMonitor, periodicAlarmMonitor,
            timeBaseSec, timeBaseSec, allTagIds, allAtomMatchingTrackers, atomMatchingTrackerMap,
            allConditionTrackers, conditionTrackerMap, allMetricProducers, metricProducerMap,
            allAnomalyTrackers, allAlarmTrackers, conditionToMetricMap, trackerToMetricMap,
            trackerToConditionMap, activationAtomTrackerToMetricMap,
            deactivationAtomTrackerToMetricMap, alertTrackerMap, metricsWithActivation,
            stateProtoHashes, noReportMetricIds));
}

TEST(MetricsManagerTest, TestMissingPredicate) {
    sp<UidMap> uidMap = new UidMap();
    sp<StatsPullerManager> pullerManager = new StatsPullerManager();
    sp<AlarmMonitor> anomalyAlarmMonitor;
    sp<AlarmMonitor> periodicAlarmMonitor;
    StatsdConfig config = buildMissingPredicate();
    set<int> allTagIds;
    vector<sp<AtomMatchingTracker>> allAtomMatchingTrackers;
    unordered_map<int64_t, int> atomMatchingTrackerMap;
    vector<sp<ConditionTracker>> allConditionTrackers;
    unordered_map<int64_t, int> conditionTrackerMap;
    vector<sp<MetricProducer>> allMetricProducers;
    unordered_map<int64_t, int> metricProducerMap;
    std::vector<sp<AnomalyTracker>> allAnomalyTrackers;
    std::vector<sp<AlarmTracker>> allAlarmTrackers;
    unordered_map<int, std::vector<int>> conditionToMetricMap;
    unordered_map<int, std::vector<int>> trackerToMetricMap;
    unordered_map<int, std::vector<int>> trackerToConditionMap;
    unordered_map<int, std::vector<int>> activationAtomTrackerToMetricMap;
    unordered_map<int, std::vector<int>> deactivationAtomTrackerToMetricMap;
    unordered_map<int64_t, int> alertTrackerMap;
    vector<int> metricsWithActivation;
    map<int64_t, uint64_t> stateProtoHashes;
    std::set<int64_t> noReportMetricIds;
    EXPECT_FALSE(initStatsdConfig(
            kConfigKey, config, uidMap, pullerManager, anomalyAlarmMonitor, periodicAlarmMonitor,
            timeBaseSec, timeBaseSec, allTagIds, allAtomMatchingTrackers, atomMatchingTrackerMap,
            allConditionTrackers, conditionTrackerMap, allMetricProducers, metricProducerMap,
            allAnomalyTrackers, allAlarmTrackers, conditionToMetricMap, trackerToMetricMap,
            trackerToConditionMap, activationAtomTrackerToMetricMap,
            deactivationAtomTrackerToMetricMap, alertTrackerMap, metricsWithActivation,
            stateProtoHashes, noReportMetricIds));
}

TEST(MetricsManagerTest, TestCirclePredicateDependency) {
    sp<UidMap> uidMap = new UidMap();
    sp<StatsPullerManager> pullerManager = new StatsPullerManager();
    sp<AlarmMonitor> anomalyAlarmMonitor;
    sp<AlarmMonitor> periodicAlarmMonitor;
    StatsdConfig config = buildCirclePredicates();
    set<int> allTagIds;
    vector<sp<AtomMatchingTracker>> allAtomMatchingTrackers;
    unordered_map<int64_t, int> atomMatchingTrackerMap;
    vector<sp<ConditionTracker>> allConditionTrackers;
    unordered_map<int64_t, int> conditionTrackerMap;
    vector<sp<MetricProducer>> allMetricProducers;
    unordered_map<int64_t, int> metricProducerMap;
    std::vector<sp<AnomalyTracker>> allAnomalyTrackers;
    std::vector<sp<AlarmTracker>> allAlarmTrackers;
    unordered_map<int, std::vector<int>> conditionToMetricMap;
    unordered_map<int, std::vector<int>> trackerToMetricMap;
    unordered_map<int, std::vector<int>> trackerToConditionMap;
    unordered_map<int, std::vector<int>> activationAtomTrackerToMetricMap;
    unordered_map<int, std::vector<int>> deactivationAtomTrackerToMetricMap;
    unordered_map<int64_t, int> alertTrackerMap;
    vector<int> metricsWithActivation;
    map<int64_t, uint64_t> stateProtoHashes;
    std::set<int64_t> noReportMetricIds;

    EXPECT_FALSE(initStatsdConfig(
            kConfigKey, config, uidMap, pullerManager, anomalyAlarmMonitor, periodicAlarmMonitor,
            timeBaseSec, timeBaseSec, allTagIds, allAtomMatchingTrackers, atomMatchingTrackerMap,
            allConditionTrackers, conditionTrackerMap, allMetricProducers, metricProducerMap,
            allAnomalyTrackers, allAlarmTrackers, conditionToMetricMap, trackerToMetricMap,
            trackerToConditionMap, activationAtomTrackerToMetricMap,
            deactivationAtomTrackerToMetricMap, alertTrackerMap, metricsWithActivation,
            stateProtoHashes, noReportMetricIds));
}

TEST(MetricsManagerTest, testAlertWithUnknownMetric) {
    sp<UidMap> uidMap = new UidMap();
    sp<StatsPullerManager> pullerManager = new StatsPullerManager();
    sp<AlarmMonitor> anomalyAlarmMonitor;
    sp<AlarmMonitor> periodicAlarmMonitor;
    StatsdConfig config = buildAlertWithUnknownMetric();
    set<int> allTagIds;
    vector<sp<AtomMatchingTracker>> allAtomMatchingTrackers;
    unordered_map<int64_t, int> atomMatchingTrackerMap;
    vector<sp<ConditionTracker>> allConditionTrackers;
    unordered_map<int64_t, int> conditionTrackerMap;
    vector<sp<MetricProducer>> allMetricProducers;
    unordered_map<int64_t, int> metricProducerMap;
    std::vector<sp<AnomalyTracker>> allAnomalyTrackers;
    std::vector<sp<AlarmTracker>> allAlarmTrackers;
    unordered_map<int, std::vector<int>> conditionToMetricMap;
    unordered_map<int, std::vector<int>> trackerToMetricMap;
    unordered_map<int, std::vector<int>> trackerToConditionMap;
    unordered_map<int, std::vector<int>> activationAtomTrackerToMetricMap;
    unordered_map<int, std::vector<int>> deactivationAtomTrackerToMetricMap;
    unordered_map<int64_t, int> alertTrackerMap;
    vector<int> metricsWithActivation;
    map<int64_t, uint64_t> stateProtoHashes;
    std::set<int64_t> noReportMetricIds;

    EXPECT_FALSE(initStatsdConfig(
            kConfigKey, config, uidMap, pullerManager, anomalyAlarmMonitor, periodicAlarmMonitor,
            timeBaseSec, timeBaseSec, allTagIds, allAtomMatchingTrackers, atomMatchingTrackerMap,
            allConditionTrackers, conditionTrackerMap, allMetricProducers, metricProducerMap,
            allAnomalyTrackers, allAlarmTrackers, conditionToMetricMap, trackerToMetricMap,
            trackerToConditionMap, activationAtomTrackerToMetricMap,
            deactivationAtomTrackerToMetricMap, alertTrackerMap, metricsWithActivation,
            stateProtoHashes, noReportMetricIds));
}

TEST(MetricsManagerTest, TestCreateAtomMatchingTrackerInvalidMatcher) {
    sp<UidMap> uidMap = new UidMap();
    AtomMatcher matcher;
    // Matcher has no contents_case (simple/combination), so it is invalid.
    matcher.set_id(21);
    EXPECT_EQ(createAtomMatchingTracker(matcher, 0, uidMap), nullptr);
}

TEST(MetricsManagerTest, TestCreateAtomMatchingTrackerSimple) {
    int index = 1;
    int64_t id = 123;
    sp<UidMap> uidMap = new UidMap();
    AtomMatcher matcher;
    matcher.set_id(id);
    SimpleAtomMatcher* simpleAtomMatcher = matcher.mutable_simple_atom_matcher();
    simpleAtomMatcher->set_atom_id(util::SCREEN_STATE_CHANGED);
    simpleAtomMatcher->add_field_value_matcher()->set_field(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE*/);
    simpleAtomMatcher->mutable_field_value_matcher(0)->set_eq_int(
            android::view::DisplayStateEnum::DISPLAY_STATE_ON);

    sp<AtomMatchingTracker> tracker = createAtomMatchingTracker(matcher, index, uidMap);
    EXPECT_NE(tracker, nullptr);

    EXPECT_TRUE(tracker->mInitialized);
    EXPECT_EQ(tracker->getId(), id);
    EXPECT_EQ(tracker->mIndex, index);
    const set<int>& atomIds = tracker->getAtomIds();
    ASSERT_EQ(atomIds.size(), 1);
    EXPECT_EQ(atomIds.count(util::SCREEN_STATE_CHANGED), 1);
}

TEST(MetricsManagerTest, TestCreateAtomMatchingTrackerCombination) {
    int index = 1;
    int64_t id = 123;
    sp<UidMap> uidMap = new UidMap();
    AtomMatcher matcher;
    matcher.set_id(id);
    AtomMatcher_Combination* combination = matcher.mutable_combination();
    combination->set_operation(LogicalOperation::OR);
    combination->add_matcher(123);
    combination->add_matcher(223);

    sp<AtomMatchingTracker> tracker = createAtomMatchingTracker(matcher, index, uidMap);
    EXPECT_NE(tracker, nullptr);

    // Combination matchers need to be initialized first.
    EXPECT_FALSE(tracker->mInitialized);
    EXPECT_EQ(tracker->getId(), id);
    EXPECT_EQ(tracker->mIndex, index);
    const set<int>& atomIds = tracker->getAtomIds();
    ASSERT_EQ(atomIds.size(), 0);
}

TEST(MetricsManagerTest, TestCreateConditionTrackerInvalid) {
    const ConfigKey key(123, 456);
    // Predicate has no contents_case (simple/combination), so it is invalid.
    Predicate predicate;
    predicate.set_id(21);
    unordered_map<int64_t, int> atomTrackerMap;
    EXPECT_EQ(createConditionTracker(key, predicate, 0, atomTrackerMap), nullptr);
}

TEST(MetricsManagerTest, TestCreateConditionTrackerSimple) {
    int index = 1;
    int64_t id = 987;
    const ConfigKey key(123, 456);

    int startMatcherIndex = 2, stopMatcherIndex = 0, stopAllMatcherIndex = 1;
    int64_t startMatcherId = 246, stopMatcherId = 153, stopAllMatcherId = 975;

    Predicate predicate;
    predicate.set_id(id);
    SimplePredicate* simplePredicate = predicate.mutable_simple_predicate();
    simplePredicate->set_start(startMatcherId);
    simplePredicate->set_stop(stopMatcherId);
    simplePredicate->set_stop_all(stopAllMatcherId);

    unordered_map<int64_t, int> atomTrackerMap;
    atomTrackerMap[startMatcherId] = startMatcherIndex;
    atomTrackerMap[stopMatcherId] = stopMatcherIndex;
    atomTrackerMap[stopAllMatcherId] = stopAllMatcherIndex;

    sp<ConditionTracker> tracker = createConditionTracker(key, predicate, index, atomTrackerMap);
    EXPECT_EQ(tracker->getConditionId(), id);
    EXPECT_EQ(tracker->isSliced(), false);
    EXPECT_TRUE(tracker->IsSimpleCondition());
    const set<int>& interestedMatchers = tracker->getAtomMatchingTrackerIndex();
    ASSERT_EQ(interestedMatchers.size(), 3);
    ASSERT_EQ(interestedMatchers.count(startMatcherIndex), 1);
    ASSERT_EQ(interestedMatchers.count(stopMatcherIndex), 1);
    ASSERT_EQ(interestedMatchers.count(stopAllMatcherIndex), 1);
}

TEST(MetricsManagerTest, TestCreateConditionTrackerCombination) {
    int index = 1;
    int64_t id = 987;
    const ConfigKey key(123, 456);

    Predicate predicate;
    predicate.set_id(id);
    Predicate_Combination* combinationPredicate = predicate.mutable_combination();
    combinationPredicate->set_operation(LogicalOperation::AND);
    combinationPredicate->add_predicate(888);
    combinationPredicate->add_predicate(777);

    // Combination conditions must be initialized to set most state.
    unordered_map<int64_t, int> atomTrackerMap;
    sp<ConditionTracker> tracker = createConditionTracker(key, predicate, index, atomTrackerMap);
    EXPECT_EQ(tracker->getConditionId(), id);
    EXPECT_FALSE(tracker->IsSimpleCondition());
}

}  // namespace statsd
}  // namespace os
}  // namespace android

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
