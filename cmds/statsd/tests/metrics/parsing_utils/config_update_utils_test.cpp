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

#include "src/metrics/parsing_utils/config_update_utils.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <private/android_filesystem_config.h>
#include <stdio.h>

#include <set>
#include <unordered_map>
#include <vector>

#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "src/condition/CombinationConditionTracker.h"
#include "src/condition/SimpleConditionTracker.h"
#include "src/matchers/CombinationAtomMatchingTracker.h"
#include "src/metrics/DurationMetricProducer.h"
#include "src/metrics/GaugeMetricProducer.h"
#include "src/metrics/ValueMetricProducer.h"
#include "src/metrics/parsing_utils/metrics_manager_util.h"
#include "tests/statsd_test_util.h"

using namespace testing;
using android::sp;
using android::os::statsd::Predicate;
using std::map;
using std::nullopt;
using std::optional;
using std::set;
using std::unordered_map;
using std::vector;

#ifdef __ANDROID__

namespace android {
namespace os {
namespace statsd {

namespace {

ConfigKey key(123, 456);
const int64_t timeBaseNs = 1000;
sp<UidMap> uidMap = new UidMap();
sp<StatsPullerManager> pullerManager = new StatsPullerManager();
sp<AlarmMonitor> anomalyAlarmMonitor;
sp<AlarmMonitor> periodicAlarmMonitor;
set<int> allTagIds;
vector<sp<AtomMatchingTracker>> oldAtomMatchingTrackers;
unordered_map<int64_t, int> oldAtomMatchingTrackerMap;
vector<sp<ConditionTracker>> oldConditionTrackers;
unordered_map<int64_t, int> oldConditionTrackerMap;
vector<sp<MetricProducer>> oldMetricProducers;
unordered_map<int64_t, int> oldMetricProducerMap;
std::vector<sp<AnomalyTracker>> oldAnomalyTrackers;
std::vector<sp<AlarmTracker>> oldAlarmTrackers;
unordered_map<int, std::vector<int>> tmpConditionToMetricMap;
unordered_map<int, std::vector<int>> tmpTrackerToMetricMap;
unordered_map<int, std::vector<int>> tmpTrackerToConditionMap;
unordered_map<int, std::vector<int>> tmpActivationAtomTrackerToMetricMap;
unordered_map<int, std::vector<int>> tmpDeactivationAtomTrackerToMetricMap;
unordered_map<int64_t, int> alertTrackerMap;
vector<int> metricsWithActivation;
map<int64_t, uint64_t> oldStateHashes;
std::set<int64_t> noReportMetricIds;

class ConfigUpdateTest : public ::testing::Test {
public:
    ConfigUpdateTest() {
    }

    void SetUp() override {
        allTagIds.clear();
        oldAtomMatchingTrackers.clear();
        oldAtomMatchingTrackerMap.clear();
        oldConditionTrackers.clear();
        oldConditionTrackerMap.clear();
        oldMetricProducers.clear();
        oldMetricProducerMap.clear();
        oldAnomalyTrackers.clear();
        oldAlarmTrackers.clear();
        tmpConditionToMetricMap.clear();
        tmpTrackerToMetricMap.clear();
        tmpTrackerToConditionMap.clear();
        tmpActivationAtomTrackerToMetricMap.clear();
        tmpDeactivationAtomTrackerToMetricMap.clear();
        alertTrackerMap.clear();
        metricsWithActivation.clear();
        oldStateHashes.clear();
        noReportMetricIds.clear();
        StateManager::getInstance().clear();
    }
};

bool initConfig(const StatsdConfig& config) {
    return initStatsdConfig(
            key, config, uidMap, pullerManager, anomalyAlarmMonitor, periodicAlarmMonitor,
            timeBaseNs, timeBaseNs, allTagIds, oldAtomMatchingTrackers, oldAtomMatchingTrackerMap,
            oldConditionTrackers, oldConditionTrackerMap, oldMetricProducers, oldMetricProducerMap,
            oldAnomalyTrackers, oldAlarmTrackers, tmpConditionToMetricMap, tmpTrackerToMetricMap,
            tmpTrackerToConditionMap, tmpActivationAtomTrackerToMetricMap,
            tmpDeactivationAtomTrackerToMetricMap, alertTrackerMap, metricsWithActivation,
            oldStateHashes, noReportMetricIds);
}

EventMetric createEventMetric(string name, int64_t what, optional<int64_t> condition) {
    EventMetric metric;
    metric.set_id(StringToId(name));
    metric.set_what(what);
    if (condition) {
        metric.set_condition(condition.value());
    }
    return metric;
}

CountMetric createCountMetric(string name, int64_t what, optional<int64_t> condition,
                              vector<int64_t> states) {
    CountMetric metric;
    metric.set_id(StringToId(name));
    metric.set_what(what);
    metric.set_bucket(TEN_MINUTES);
    if (condition) {
        metric.set_condition(condition.value());
    }
    for (const int64_t state : states) {
        metric.add_slice_by_state(state);
    }
    return metric;
}

GaugeMetric createGaugeMetric(string name, int64_t what, GaugeMetric::SamplingType samplingType,
                              optional<int64_t> condition, optional<int64_t> triggerEvent) {
    GaugeMetric metric;
    metric.set_id(StringToId(name));
    metric.set_what(what);
    metric.set_bucket(TEN_MINUTES);
    metric.set_sampling_type(samplingType);
    if (condition) {
        metric.set_condition(condition.value());
    }
    if (triggerEvent) {
        metric.set_trigger_event(triggerEvent.value());
    }
    metric.mutable_gauge_fields_filter()->set_include_all(true);
    return metric;
}

DurationMetric createDurationMetric(string name, int64_t what, optional<int64_t> condition,
                                    vector<int64_t> states) {
    DurationMetric metric;
    metric.set_id(StringToId(name));
    metric.set_what(what);
    metric.set_bucket(TEN_MINUTES);
    if (condition) {
        metric.set_condition(condition.value());
    }
    for (const int64_t state : states) {
        metric.add_slice_by_state(state);
    }
    return metric;
}

ValueMetric createValueMetric(string name, const AtomMatcher& what, optional<int64_t> condition,
                              vector<int64_t> states) {
    ValueMetric metric;
    metric.set_id(StringToId(name));
    metric.set_what(what.id());
    metric.set_bucket(TEN_MINUTES);
    metric.mutable_value_field()->set_field(what.simple_atom_matcher().atom_id());
    metric.mutable_value_field()->add_child()->set_field(2);
    if (condition) {
        metric.set_condition(condition.value());
    }
    for (const int64_t state : states) {
        metric.add_slice_by_state(state);
    }
    return metric;
}
}  // anonymous namespace

TEST_F(ConfigUpdateTest, TestSimpleMatcherPreserve) {
    StatsdConfig config;
    AtomMatcher matcher = CreateSimpleAtomMatcher("TEST", /*atom=*/10);
    int64_t matcherId = matcher.id();
    *config.add_atom_matcher() = matcher;

    // Create an initial config.
    EXPECT_TRUE(initConfig(config));

    vector<UpdateStatus> matchersToUpdate(1, UPDATE_UNKNOWN);
    vector<bool> cycleTracker(1, false);
    unordered_map<int64_t, int> newAtomMatchingTrackerMap;
    newAtomMatchingTrackerMap[matcherId] = 0;
    EXPECT_TRUE(determineMatcherUpdateStatus(config, 0, oldAtomMatchingTrackerMap,
                                             oldAtomMatchingTrackers, newAtomMatchingTrackerMap,
                                             matchersToUpdate, cycleTracker));
    EXPECT_EQ(matchersToUpdate[0], UPDATE_PRESERVE);
}

TEST_F(ConfigUpdateTest, TestSimpleMatcherReplace) {
    StatsdConfig config;
    AtomMatcher matcher = CreateSimpleAtomMatcher("TEST", /*atom=*/10);
    *config.add_atom_matcher() = matcher;

    EXPECT_TRUE(initConfig(config));

    StatsdConfig newConfig;
    // Same id, different atom, so should be replaced.
    AtomMatcher newMatcher = CreateSimpleAtomMatcher("TEST", /*atom=*/11);
    int64_t matcherId = newMatcher.id();
    EXPECT_EQ(matcherId, matcher.id());
    *newConfig.add_atom_matcher() = newMatcher;

    vector<UpdateStatus> matchersToUpdate(1, UPDATE_UNKNOWN);
    vector<bool> cycleTracker(1, false);
    unordered_map<int64_t, int> newAtomMatchingTrackerMap;
    newAtomMatchingTrackerMap[matcherId] = 0;
    EXPECT_TRUE(determineMatcherUpdateStatus(newConfig, 0, oldAtomMatchingTrackerMap,
                                             oldAtomMatchingTrackers, newAtomMatchingTrackerMap,
                                             matchersToUpdate, cycleTracker));
    EXPECT_EQ(matchersToUpdate[0], UPDATE_REPLACE);
}

TEST_F(ConfigUpdateTest, TestSimpleMatcherNew) {
    StatsdConfig config;
    AtomMatcher matcher = CreateSimpleAtomMatcher("TEST", /*atom=*/10);
    *config.add_atom_matcher() = matcher;

    EXPECT_TRUE(initConfig(config));

    StatsdConfig newConfig;
    // Different id, so should be a new matcher.
    AtomMatcher newMatcher = CreateSimpleAtomMatcher("DIFFERENT_NAME", /*atom=*/10);
    int64_t matcherId = newMatcher.id();
    EXPECT_NE(matcherId, matcher.id());
    *newConfig.add_atom_matcher() = newMatcher;

    vector<UpdateStatus> matchersToUpdate(1, UPDATE_UNKNOWN);
    vector<bool> cycleTracker(1, false);
    unordered_map<int64_t, int> newAtomMatchingTrackerMap;
    newAtomMatchingTrackerMap[matcherId] = 0;
    EXPECT_TRUE(determineMatcherUpdateStatus(newConfig, 0, oldAtomMatchingTrackerMap,
                                             oldAtomMatchingTrackers, newAtomMatchingTrackerMap,
                                             matchersToUpdate, cycleTracker));
    EXPECT_EQ(matchersToUpdate[0], UPDATE_NEW);
}

TEST_F(ConfigUpdateTest, TestCombinationMatcherPreserve) {
    StatsdConfig config;
    AtomMatcher matcher1 = CreateSimpleAtomMatcher("TEST1", /*atom=*/10);
    int64_t matcher1Id = matcher1.id();
    *config.add_atom_matcher() = matcher1;

    AtomMatcher matcher2 = CreateSimpleAtomMatcher("TEST2", /*atom=*/11);
    *config.add_atom_matcher() = matcher2;
    int64_t matcher2Id = matcher2.id();

    AtomMatcher matcher3;
    matcher3.set_id(StringToId("TEST3"));
    AtomMatcher_Combination* combination = matcher3.mutable_combination();
    combination->set_operation(LogicalOperation::OR);
    combination->add_matcher(matcher1Id);
    combination->add_matcher(matcher2Id);
    int64_t matcher3Id = matcher3.id();
    *config.add_atom_matcher() = matcher3;

    EXPECT_TRUE(initConfig(config));

    StatsdConfig newConfig;
    unordered_map<int64_t, int> newAtomMatchingTrackerMap;
    // Same matchers, different order, all should be preserved.
    *newConfig.add_atom_matcher() = matcher2;
    newAtomMatchingTrackerMap[matcher2Id] = 0;
    *newConfig.add_atom_matcher() = matcher3;
    newAtomMatchingTrackerMap[matcher3Id] = 1;
    *newConfig.add_atom_matcher() = matcher1;
    newAtomMatchingTrackerMap[matcher1Id] = 2;

    vector<UpdateStatus> matchersToUpdate(3, UPDATE_UNKNOWN);
    vector<bool> cycleTracker(3, false);
    // Only update the combination. It should recurse the two child matchers and preserve all 3.
    EXPECT_TRUE(determineMatcherUpdateStatus(newConfig, 1, oldAtomMatchingTrackerMap,
                                             oldAtomMatchingTrackers, newAtomMatchingTrackerMap,
                                             matchersToUpdate, cycleTracker));
    EXPECT_EQ(matchersToUpdate[0], UPDATE_PRESERVE);
    EXPECT_EQ(matchersToUpdate[1], UPDATE_PRESERVE);
    EXPECT_EQ(matchersToUpdate[2], UPDATE_PRESERVE);
}

TEST_F(ConfigUpdateTest, TestCombinationMatcherReplace) {
    StatsdConfig config;
    AtomMatcher matcher1 = CreateSimpleAtomMatcher("TEST1", /*atom=*/10);
    int64_t matcher1Id = matcher1.id();
    *config.add_atom_matcher() = matcher1;

    AtomMatcher matcher2 = CreateSimpleAtomMatcher("TEST2", /*atom=*/11);
    *config.add_atom_matcher() = matcher2;
    int64_t matcher2Id = matcher2.id();

    AtomMatcher matcher3;
    matcher3.set_id(StringToId("TEST3"));
    AtomMatcher_Combination* combination = matcher3.mutable_combination();
    combination->set_operation(LogicalOperation::OR);
    combination->add_matcher(matcher1Id);
    combination->add_matcher(matcher2Id);
    int64_t matcher3Id = matcher3.id();
    *config.add_atom_matcher() = matcher3;

    EXPECT_TRUE(initConfig(config));

    // Change the logical operation of the combination matcher, causing a replacement.
    matcher3.mutable_combination()->set_operation(LogicalOperation::AND);

    StatsdConfig newConfig;
    unordered_map<int64_t, int> newAtomMatchingTrackerMap;
    *newConfig.add_atom_matcher() = matcher2;
    newAtomMatchingTrackerMap[matcher2Id] = 0;
    *newConfig.add_atom_matcher() = matcher3;
    newAtomMatchingTrackerMap[matcher3Id] = 1;
    *newConfig.add_atom_matcher() = matcher1;
    newAtomMatchingTrackerMap[matcher1Id] = 2;

    vector<UpdateStatus> matchersToUpdate(3, UPDATE_UNKNOWN);
    vector<bool> cycleTracker(3, false);
    // Only update the combination. The simple matchers should not be evaluated.
    EXPECT_TRUE(determineMatcherUpdateStatus(newConfig, 1, oldAtomMatchingTrackerMap,
                                             oldAtomMatchingTrackers, newAtomMatchingTrackerMap,
                                             matchersToUpdate, cycleTracker));
    EXPECT_EQ(matchersToUpdate[0], UPDATE_UNKNOWN);
    EXPECT_EQ(matchersToUpdate[1], UPDATE_REPLACE);
    EXPECT_EQ(matchersToUpdate[2], UPDATE_UNKNOWN);
}

TEST_F(ConfigUpdateTest, TestCombinationMatcherDepsChange) {
    StatsdConfig config;
    AtomMatcher matcher1 = CreateSimpleAtomMatcher("TEST1", /*atom=*/10);
    int64_t matcher1Id = matcher1.id();
    *config.add_atom_matcher() = matcher1;

    AtomMatcher matcher2 = CreateSimpleAtomMatcher("TEST2", /*atom=*/11);
    *config.add_atom_matcher() = matcher2;
    int64_t matcher2Id = matcher2.id();

    AtomMatcher matcher3;
    matcher3.set_id(StringToId("TEST3"));
    AtomMatcher_Combination* combination = matcher3.mutable_combination();
    combination->set_operation(LogicalOperation::OR);
    combination->add_matcher(matcher1Id);
    combination->add_matcher(matcher2Id);
    int64_t matcher3Id = matcher3.id();
    *config.add_atom_matcher() = matcher3;

    EXPECT_TRUE(initConfig(config));

    // Change a dependency of matcher 3.
    matcher2.mutable_simple_atom_matcher()->set_atom_id(12);

    StatsdConfig newConfig;
    unordered_map<int64_t, int> newAtomMatchingTrackerMap;
    *newConfig.add_atom_matcher() = matcher2;
    newAtomMatchingTrackerMap[matcher2Id] = 0;
    *newConfig.add_atom_matcher() = matcher3;
    newAtomMatchingTrackerMap[matcher3Id] = 1;
    *newConfig.add_atom_matcher() = matcher1;
    newAtomMatchingTrackerMap[matcher1Id] = 2;

    vector<UpdateStatus> matchersToUpdate(3, UPDATE_UNKNOWN);
    vector<bool> cycleTracker(3, false);
    // Only update the combination.
    EXPECT_TRUE(determineMatcherUpdateStatus(newConfig, 1, oldAtomMatchingTrackerMap,
                                             oldAtomMatchingTrackers, newAtomMatchingTrackerMap,
                                             matchersToUpdate, cycleTracker));
    // Matcher 2 and matcher3 must be reevaluated. Matcher 1 might, but does not need to be.
    EXPECT_EQ(matchersToUpdate[0], UPDATE_REPLACE);
    EXPECT_EQ(matchersToUpdate[1], UPDATE_REPLACE);
}

TEST_F(ConfigUpdateTest, TestUpdateMatchers) {
    StatsdConfig config;
    // Will be preserved.
    AtomMatcher simple1 = CreateSimpleAtomMatcher("SIMPLE1", /*atom=*/10);
    int64_t simple1Id = simple1.id();
    *config.add_atom_matcher() = simple1;

    // Will be replaced.
    AtomMatcher simple2 = CreateSimpleAtomMatcher("SIMPLE2", /*atom=*/11);
    *config.add_atom_matcher() = simple2;
    int64_t simple2Id = simple2.id();

    // Will be removed.
    AtomMatcher simple3 = CreateSimpleAtomMatcher("SIMPLE3", /*atom=*/12);
    *config.add_atom_matcher() = simple3;
    int64_t simple3Id = simple3.id();

    // Will be preserved.
    AtomMatcher combination1;
    combination1.set_id(StringToId("combination1"));
    AtomMatcher_Combination* combination = combination1.mutable_combination();
    combination->set_operation(LogicalOperation::NOT);
    combination->add_matcher(simple1Id);
    int64_t combination1Id = combination1.id();
    *config.add_atom_matcher() = combination1;

    // Will be replaced since it depends on simple2.
    AtomMatcher combination2;
    combination2.set_id(StringToId("combination2"));
    combination = combination2.mutable_combination();
    combination->set_operation(LogicalOperation::AND);
    combination->add_matcher(simple1Id);
    combination->add_matcher(simple2Id);
    int64_t combination2Id = combination2.id();
    *config.add_atom_matcher() = combination2;

    EXPECT_TRUE(initConfig(config));

    // Change simple2, causing simple2 and combination2 to be replaced.
    simple2.mutable_simple_atom_matcher()->set_atom_id(111);

    // 2 new matchers: simple4 and combination3:
    AtomMatcher simple4 = CreateSimpleAtomMatcher("SIMPLE4", /*atom=*/13);
    int64_t simple4Id = simple4.id();

    AtomMatcher combination3;
    combination3.set_id(StringToId("combination3"));
    combination = combination3.mutable_combination();
    combination->set_operation(LogicalOperation::AND);
    combination->add_matcher(simple4Id);
    combination->add_matcher(simple2Id);
    int64_t combination3Id = combination3.id();

    StatsdConfig newConfig;
    *newConfig.add_atom_matcher() = combination3;
    *newConfig.add_atom_matcher() = simple2;
    *newConfig.add_atom_matcher() = combination2;
    *newConfig.add_atom_matcher() = simple1;
    *newConfig.add_atom_matcher() = simple4;
    *newConfig.add_atom_matcher() = combination1;

    set<int> newTagIds;
    unordered_map<int64_t, int> newAtomMatchingTrackerMap;
    vector<sp<AtomMatchingTracker>> newAtomMatchingTrackers;
    set<int64_t> replacedMatchers;
    EXPECT_TRUE(updateAtomMatchingTrackers(
            newConfig, uidMap, oldAtomMatchingTrackerMap, oldAtomMatchingTrackers, newTagIds,
            newAtomMatchingTrackerMap, newAtomMatchingTrackers, replacedMatchers));

    ASSERT_EQ(newTagIds.size(), 3);
    EXPECT_EQ(newTagIds.count(10), 1);
    EXPECT_EQ(newTagIds.count(111), 1);
    EXPECT_EQ(newTagIds.count(13), 1);

    ASSERT_EQ(newAtomMatchingTrackerMap.size(), 6);
    EXPECT_EQ(newAtomMatchingTrackerMap.at(combination3Id), 0);
    EXPECT_EQ(newAtomMatchingTrackerMap.at(simple2Id), 1);
    EXPECT_EQ(newAtomMatchingTrackerMap.at(combination2Id), 2);
    EXPECT_EQ(newAtomMatchingTrackerMap.at(simple1Id), 3);
    EXPECT_EQ(newAtomMatchingTrackerMap.at(simple4Id), 4);
    EXPECT_EQ(newAtomMatchingTrackerMap.at(combination1Id), 5);

    ASSERT_EQ(newAtomMatchingTrackers.size(), 6);
    // Make sure all atom matchers are initialized:
    for (const sp<AtomMatchingTracker>& tracker : newAtomMatchingTrackers) {
        EXPECT_TRUE(tracker->mInitialized);
    }
    // Make sure preserved atom matchers are the same.
    EXPECT_EQ(oldAtomMatchingTrackers[oldAtomMatchingTrackerMap.at(simple1Id)],
              newAtomMatchingTrackers[newAtomMatchingTrackerMap.at(simple1Id)]);
    EXPECT_EQ(oldAtomMatchingTrackers[oldAtomMatchingTrackerMap.at(combination1Id)],
              newAtomMatchingTrackers[newAtomMatchingTrackerMap.at(combination1Id)]);
    // Make sure replaced matchers are different.
    EXPECT_NE(oldAtomMatchingTrackers[oldAtomMatchingTrackerMap.at(simple2Id)],
              newAtomMatchingTrackers[newAtomMatchingTrackerMap.at(simple2Id)]);
    EXPECT_NE(oldAtomMatchingTrackers[oldAtomMatchingTrackerMap.at(combination2Id)],
              newAtomMatchingTrackers[newAtomMatchingTrackerMap.at(combination2Id)]);

    // Validation, make sure the matchers have the proper ids/indices. Could do more checks here.
    EXPECT_EQ(newAtomMatchingTrackers[0]->getId(), combination3Id);
    EXPECT_EQ(newAtomMatchingTrackers[0]->mIndex, 0);
    EXPECT_EQ(newAtomMatchingTrackers[1]->getId(), simple2Id);
    EXPECT_EQ(newAtomMatchingTrackers[1]->mIndex, 1);
    EXPECT_EQ(newAtomMatchingTrackers[2]->getId(), combination2Id);
    EXPECT_EQ(newAtomMatchingTrackers[2]->mIndex, 2);
    EXPECT_EQ(newAtomMatchingTrackers[3]->getId(), simple1Id);
    EXPECT_EQ(newAtomMatchingTrackers[3]->mIndex, 3);
    EXPECT_EQ(newAtomMatchingTrackers[4]->getId(), simple4Id);
    EXPECT_EQ(newAtomMatchingTrackers[4]->mIndex, 4);
    EXPECT_EQ(newAtomMatchingTrackers[5]->getId(), combination1Id);
    EXPECT_EQ(newAtomMatchingTrackers[5]->mIndex, 5);

    // Verify child indices of Combination Matchers are correct.
    CombinationAtomMatchingTracker* combinationTracker1 =
            static_cast<CombinationAtomMatchingTracker*>(newAtomMatchingTrackers[5].get());
    vector<int>* childMatchers = &combinationTracker1->mChildren;
    EXPECT_EQ(childMatchers->size(), 1);
    EXPECT_NE(std::find(childMatchers->begin(), childMatchers->end(), 3), childMatchers->end());

    CombinationAtomMatchingTracker* combinationTracker2 =
            static_cast<CombinationAtomMatchingTracker*>(newAtomMatchingTrackers[2].get());
    childMatchers = &combinationTracker2->mChildren;
    EXPECT_EQ(childMatchers->size(), 2);
    EXPECT_NE(std::find(childMatchers->begin(), childMatchers->end(), 1), childMatchers->end());
    EXPECT_NE(std::find(childMatchers->begin(), childMatchers->end(), 3), childMatchers->end());

    CombinationAtomMatchingTracker* combinationTracker3 =
            static_cast<CombinationAtomMatchingTracker*>(newAtomMatchingTrackers[0].get());
    childMatchers = &combinationTracker3->mChildren;
    EXPECT_EQ(childMatchers->size(), 2);
    EXPECT_NE(std::find(childMatchers->begin(), childMatchers->end(), 1), childMatchers->end());
    EXPECT_NE(std::find(childMatchers->begin(), childMatchers->end(), 4), childMatchers->end());

    // Expect replacedMatchers to have simple2 and combination2
    ASSERT_EQ(replacedMatchers.size(), 2);
    EXPECT_NE(replacedMatchers.find(simple2Id), replacedMatchers.end());
    EXPECT_NE(replacedMatchers.find(combination2Id), replacedMatchers.end());
}

TEST_F(ConfigUpdateTest, TestSimpleConditionPreserve) {
    StatsdConfig config;
    AtomMatcher startMatcher = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = startMatcher;
    AtomMatcher stopMatcher = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = stopMatcher;

    Predicate predicate = CreateScreenIsOnPredicate();
    *config.add_predicate() = predicate;

    // Create an initial config.
    EXPECT_TRUE(initConfig(config));

    set<int64_t> replacedMatchers;
    vector<UpdateStatus> conditionsToUpdate(1, UPDATE_UNKNOWN);
    vector<bool> cycleTracker(1, false);
    unordered_map<int64_t, int> newConditionTrackerMap;
    newConditionTrackerMap[predicate.id()] = 0;
    EXPECT_TRUE(determineConditionUpdateStatus(config, 0, oldConditionTrackerMap,
                                               oldConditionTrackers, newConditionTrackerMap,
                                               replacedMatchers, conditionsToUpdate, cycleTracker));
    EXPECT_EQ(conditionsToUpdate[0], UPDATE_PRESERVE);
}

TEST_F(ConfigUpdateTest, TestSimpleConditionReplace) {
    StatsdConfig config;
    AtomMatcher startMatcher = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = startMatcher;
    AtomMatcher stopMatcher = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = stopMatcher;

    Predicate predicate = CreateScreenIsOnPredicate();
    *config.add_predicate() = predicate;

    EXPECT_TRUE(initConfig(config));

    // Modify the predicate.
    config.mutable_predicate(0)->mutable_simple_predicate()->set_count_nesting(true);

    set<int64_t> replacedMatchers;
    vector<UpdateStatus> conditionsToUpdate(1, UPDATE_UNKNOWN);
    vector<bool> cycleTracker(1, false);
    unordered_map<int64_t, int> newConditionTrackerMap;
    newConditionTrackerMap[predicate.id()] = 0;
    EXPECT_TRUE(determineConditionUpdateStatus(config, 0, oldConditionTrackerMap,
                                               oldConditionTrackers, newConditionTrackerMap,
                                               replacedMatchers, conditionsToUpdate, cycleTracker));
    EXPECT_EQ(conditionsToUpdate[0], UPDATE_REPLACE);
}

TEST_F(ConfigUpdateTest, TestSimpleConditionDepsChange) {
    StatsdConfig config;
    AtomMatcher startMatcher = CreateScreenTurnedOnAtomMatcher();
    int64_t startMatcherId = startMatcher.id();
    *config.add_atom_matcher() = startMatcher;
    AtomMatcher stopMatcher = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = stopMatcher;

    Predicate predicate = CreateScreenIsOnPredicate();
    *config.add_predicate() = predicate;

    EXPECT_TRUE(initConfig(config));

    // Start matcher was replaced.
    set<int64_t> replacedMatchers;
    replacedMatchers.insert(startMatcherId);

    vector<UpdateStatus> conditionsToUpdate(1, UPDATE_UNKNOWN);
    vector<bool> cycleTracker(1, false);
    unordered_map<int64_t, int> newConditionTrackerMap;
    newConditionTrackerMap[predicate.id()] = 0;
    EXPECT_TRUE(determineConditionUpdateStatus(config, 0, oldConditionTrackerMap,
                                               oldConditionTrackers, newConditionTrackerMap,
                                               replacedMatchers, conditionsToUpdate, cycleTracker));
    EXPECT_EQ(conditionsToUpdate[0], UPDATE_REPLACE);
}

TEST_F(ConfigUpdateTest, TestCombinationConditionPreserve) {
    StatsdConfig config;
    AtomMatcher screenOnMatcher = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = screenOnMatcher;
    AtomMatcher screenOffMatcher = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = screenOffMatcher;

    Predicate simple1 = CreateScreenIsOnPredicate();
    *config.add_predicate() = simple1;
    Predicate simple2 = CreateScreenIsOffPredicate();
    *config.add_predicate() = simple2;

    Predicate combination1;
    combination1.set_id(StringToId("COMBINATION1"));
    Predicate_Combination* combinationInternal = combination1.mutable_combination();
    combinationInternal->set_operation(LogicalOperation::NAND);
    combinationInternal->add_predicate(simple1.id());
    combinationInternal->add_predicate(simple2.id());
    *config.add_predicate() = combination1;

    EXPECT_TRUE(initConfig(config));

    // Same predicates, different order
    StatsdConfig newConfig;
    unordered_map<int64_t, int> newConditionTrackerMap;
    *newConfig.add_predicate() = combination1;
    newConditionTrackerMap[combination1.id()] = 0;
    *newConfig.add_predicate() = simple2;
    newConditionTrackerMap[simple2.id()] = 1;
    *newConfig.add_predicate() = simple1;
    newConditionTrackerMap[simple1.id()] = 2;

    set<int64_t> replacedMatchers;
    vector<UpdateStatus> conditionsToUpdate(3, UPDATE_UNKNOWN);
    vector<bool> cycleTracker(3, false);
    // Only update the combination. It should recurse the two child predicates and preserve all 3.
    EXPECT_TRUE(determineConditionUpdateStatus(newConfig, 0, oldConditionTrackerMap,
                                               oldConditionTrackers, newConditionTrackerMap,
                                               replacedMatchers, conditionsToUpdate, cycleTracker));
    EXPECT_EQ(conditionsToUpdate[0], UPDATE_PRESERVE);
    EXPECT_EQ(conditionsToUpdate[1], UPDATE_PRESERVE);
    EXPECT_EQ(conditionsToUpdate[2], UPDATE_PRESERVE);
}

TEST_F(ConfigUpdateTest, TestCombinationConditionReplace) {
    StatsdConfig config;
    AtomMatcher screenOnMatcher = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = screenOnMatcher;
    AtomMatcher screenOffMatcher = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = screenOffMatcher;

    Predicate simple1 = CreateScreenIsOnPredicate();
    *config.add_predicate() = simple1;
    Predicate simple2 = CreateScreenIsOffPredicate();
    *config.add_predicate() = simple2;

    Predicate combination1;
    combination1.set_id(StringToId("COMBINATION1"));
    Predicate_Combination* combinationInternal = combination1.mutable_combination();
    combinationInternal->set_operation(LogicalOperation::NAND);
    combinationInternal->add_predicate(simple1.id());
    combinationInternal->add_predicate(simple2.id());
    *config.add_predicate() = combination1;

    EXPECT_TRUE(initConfig(config));

    // Changing the logical operation changes the predicate definition, so it should be replaced.
    combination1.mutable_combination()->set_operation(LogicalOperation::OR);

    StatsdConfig newConfig;
    unordered_map<int64_t, int> newConditionTrackerMap;
    *newConfig.add_predicate() = combination1;
    newConditionTrackerMap[combination1.id()] = 0;
    *newConfig.add_predicate() = simple2;
    newConditionTrackerMap[simple2.id()] = 1;
    *newConfig.add_predicate() = simple1;
    newConditionTrackerMap[simple1.id()] = 2;

    set<int64_t> replacedMatchers;
    vector<UpdateStatus> conditionsToUpdate(3, UPDATE_UNKNOWN);
    vector<bool> cycleTracker(3, false);
    // Only update the combination. The simple conditions should not be evaluated.
    EXPECT_TRUE(determineConditionUpdateStatus(newConfig, 0, oldConditionTrackerMap,
                                               oldConditionTrackers, newConditionTrackerMap,
                                               replacedMatchers, conditionsToUpdate, cycleTracker));
    EXPECT_EQ(conditionsToUpdate[0], UPDATE_REPLACE);
    EXPECT_EQ(conditionsToUpdate[1], UPDATE_UNKNOWN);
    EXPECT_EQ(conditionsToUpdate[2], UPDATE_UNKNOWN);
}

TEST_F(ConfigUpdateTest, TestCombinationConditionDepsChange) {
    StatsdConfig config;
    AtomMatcher screenOnMatcher = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = screenOnMatcher;
    AtomMatcher screenOffMatcher = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = screenOffMatcher;

    Predicate simple1 = CreateScreenIsOnPredicate();
    *config.add_predicate() = simple1;
    Predicate simple2 = CreateScreenIsOffPredicate();
    *config.add_predicate() = simple2;

    Predicate combination1;
    combination1.set_id(StringToId("COMBINATION1"));
    Predicate_Combination* combinationInternal = combination1.mutable_combination();
    combinationInternal->set_operation(LogicalOperation::NAND);
    combinationInternal->add_predicate(simple1.id());
    combinationInternal->add_predicate(simple2.id());
    *config.add_predicate() = combination1;

    EXPECT_TRUE(initConfig(config));

    simple2.mutable_simple_predicate()->set_count_nesting(false);

    StatsdConfig newConfig;
    unordered_map<int64_t, int> newConditionTrackerMap;
    *newConfig.add_predicate() = combination1;
    newConditionTrackerMap[combination1.id()] = 0;
    *newConfig.add_predicate() = simple2;
    newConditionTrackerMap[simple2.id()] = 1;
    *newConfig.add_predicate() = simple1;
    newConditionTrackerMap[simple1.id()] = 2;

    set<int64_t> replacedMatchers;
    vector<UpdateStatus> conditionsToUpdate(3, UPDATE_UNKNOWN);
    vector<bool> cycleTracker(3, false);
    // Only update the combination. Simple2 and combination1 must be evaluated.
    EXPECT_TRUE(determineConditionUpdateStatus(newConfig, 0, oldConditionTrackerMap,
                                               oldConditionTrackers, newConditionTrackerMap,
                                               replacedMatchers, conditionsToUpdate, cycleTracker));
    EXPECT_EQ(conditionsToUpdate[0], UPDATE_REPLACE);
    EXPECT_EQ(conditionsToUpdate[1], UPDATE_REPLACE);
}

TEST_F(ConfigUpdateTest, TestUpdateConditions) {
    StatsdConfig config;
    // Add atom matchers. These are mostly needed for initStatsdConfig
    AtomMatcher matcher1 = CreateScreenTurnedOnAtomMatcher();
    int64_t matcher1Id = matcher1.id();
    *config.add_atom_matcher() = matcher1;

    AtomMatcher matcher2 = CreateScreenTurnedOffAtomMatcher();
    int64_t matcher2Id = matcher2.id();
    *config.add_atom_matcher() = matcher2;

    AtomMatcher matcher3 = CreateStartScheduledJobAtomMatcher();
    int64_t matcher3Id = matcher3.id();
    *config.add_atom_matcher() = matcher3;

    AtomMatcher matcher4 = CreateFinishScheduledJobAtomMatcher();
    int64_t matcher4Id = matcher4.id();
    *config.add_atom_matcher() = matcher4;

    AtomMatcher matcher5 = CreateBatterySaverModeStartAtomMatcher();
    int64_t matcher5Id = matcher5.id();
    *config.add_atom_matcher() = matcher5;

    AtomMatcher matcher6 = CreateBatterySaverModeStopAtomMatcher();
    int64_t matcher6Id = matcher6.id();
    *config.add_atom_matcher() = matcher6;

    // Add the predicates.
    // Will be preserved.
    Predicate simple1 = CreateScreenIsOnPredicate();
    int64_t simple1Id = simple1.id();
    *config.add_predicate() = simple1;

    // Will be preserved.
    Predicate simple2 = CreateScheduledJobPredicate();
    int64_t simple2Id = simple2.id();
    *config.add_predicate() = simple2;

    // Will be replaced.
    Predicate simple3 = CreateBatterySaverModePredicate();
    int64_t simple3Id = simple3.id();
    *config.add_predicate() = simple3;

    // Will be preserved
    Predicate combination1;
    combination1.set_id(StringToId("COMBINATION1"));
    combination1.mutable_combination()->set_operation(LogicalOperation::AND);
    combination1.mutable_combination()->add_predicate(simple1Id);
    combination1.mutable_combination()->add_predicate(simple2Id);
    int64_t combination1Id = combination1.id();
    *config.add_predicate() = combination1;

    // Will be replaced since simple3 will be replaced.
    Predicate combination2;
    combination2.set_id(StringToId("COMBINATION2"));
    combination2.mutable_combination()->set_operation(LogicalOperation::OR);
    combination2.mutable_combination()->add_predicate(simple1Id);
    combination2.mutable_combination()->add_predicate(simple3Id);
    int64_t combination2Id = combination2.id();
    *config.add_predicate() = combination2;

    // Will be removed.
    Predicate combination3;
    combination3.set_id(StringToId("COMBINATION3"));
    combination3.mutable_combination()->set_operation(LogicalOperation::NOT);
    combination3.mutable_combination()->add_predicate(simple2Id);
    int64_t combination3Id = combination3.id();
    *config.add_predicate() = combination3;

    EXPECT_TRUE(initConfig(config));

    // Mark marcher 5 as replaced. Causes simple3, and therefore combination2 to be replaced.
    set<int64_t> replacedMatchers;
    replacedMatchers.insert(matcher6Id);

    // Change the condition of simple1 to false.
    ASSERT_EQ(oldConditionTrackers[0]->getConditionId(), simple1Id);
    LogEvent event(/*uid=*/0, /*pid=*/0);  // Empty event is fine since there are no dimensions.
    // Mark the stop matcher as matched, condition should be false.
    vector<MatchingState> eventMatcherValues(6, MatchingState::kNotMatched);
    eventMatcherValues[1] = MatchingState::kMatched;
    vector<ConditionState> tmpConditionCache(6, ConditionState::kNotEvaluated);
    vector<bool> conditionChangeCache(6, false);
    oldConditionTrackers[0]->evaluateCondition(event, eventMatcherValues, oldConditionTrackers,
                                               tmpConditionCache, conditionChangeCache);
    EXPECT_EQ(tmpConditionCache[0], ConditionState::kFalse);
    EXPECT_EQ(conditionChangeCache[0], true);

    // New combination predicate. Should have an initial condition of true since it is NOT(simple1).
    Predicate combination4;
    combination4.set_id(StringToId("COMBINATION4"));
    combination4.mutable_combination()->set_operation(LogicalOperation::NOT);
    combination4.mutable_combination()->add_predicate(simple1Id);
    int64_t combination4Id = combination4.id();
    *config.add_predicate() = combination4;

    // Map the matchers in reverse order to force the indices to change.
    std::unordered_map<int64_t, int> newAtomMatchingTrackerMap;
    const int matcher6Index = 0;
    newAtomMatchingTrackerMap[matcher6Id] = 0;
    const int matcher5Index = 1;
    newAtomMatchingTrackerMap[matcher5Id] = 1;
    const int matcher4Index = 2;
    newAtomMatchingTrackerMap[matcher4Id] = 2;
    const int matcher3Index = 3;
    newAtomMatchingTrackerMap[matcher3Id] = 3;
    const int matcher2Index = 4;
    newAtomMatchingTrackerMap[matcher2Id] = 4;
    const int matcher1Index = 5;
    newAtomMatchingTrackerMap[matcher1Id] = 5;

    StatsdConfig newConfig;
    *newConfig.add_predicate() = simple3;
    const int simple3Index = 0;
    *newConfig.add_predicate() = combination2;
    const int combination2Index = 1;
    *newConfig.add_predicate() = combination4;
    const int combination4Index = 2;
    *newConfig.add_predicate() = simple2;
    const int simple2Index = 3;
    *newConfig.add_predicate() = combination1;
    const int combination1Index = 4;
    *newConfig.add_predicate() = simple1;
    const int simple1Index = 5;

    unordered_map<int64_t, int> newConditionTrackerMap;
    vector<sp<ConditionTracker>> newConditionTrackers;
    unordered_map<int, vector<int>> trackerToConditionMap;
    std::vector<ConditionState> conditionCache;
    std::set<int64_t> replacedConditions;
    EXPECT_TRUE(updateConditions(key, newConfig, newAtomMatchingTrackerMap, replacedMatchers,
                                 oldConditionTrackerMap, oldConditionTrackers,
                                 newConditionTrackerMap, newConditionTrackers,
                                 trackerToConditionMap, conditionCache, replacedConditions));

    unordered_map<int64_t, int> expectedConditionTrackerMap = {
            {simple1Id, simple1Index},           {simple2Id, simple2Index},
            {simple3Id, simple3Index},           {combination1Id, combination1Index},
            {combination2Id, combination2Index}, {combination4Id, combination4Index},
    };
    EXPECT_THAT(newConditionTrackerMap, ContainerEq(expectedConditionTrackerMap));

    ASSERT_EQ(newConditionTrackers.size(), 6);
    // Make sure all conditions are initialized:
    for (const sp<ConditionTracker>& tracker : newConditionTrackers) {
        EXPECT_TRUE(tracker->mInitialized);
    }

    // Make sure preserved conditions are the same.
    EXPECT_EQ(oldConditionTrackers[oldConditionTrackerMap.at(simple1Id)],
              newConditionTrackers[newConditionTrackerMap.at(simple1Id)]);
    EXPECT_EQ(oldConditionTrackers[oldConditionTrackerMap.at(simple2Id)],
              newConditionTrackers[newConditionTrackerMap.at(simple2Id)]);
    EXPECT_EQ(oldConditionTrackers[oldConditionTrackerMap.at(combination1Id)],
              newConditionTrackers[newConditionTrackerMap.at(combination1Id)]);

    // Make sure replaced conditions are different and included in replacedConditions.
    EXPECT_NE(oldConditionTrackers[oldConditionTrackerMap.at(simple3Id)],
              newConditionTrackers[newConditionTrackerMap.at(simple3Id)]);
    EXPECT_NE(oldConditionTrackers[oldConditionTrackerMap.at(combination2Id)],
              newConditionTrackers[newConditionTrackerMap.at(combination2Id)]);
    EXPECT_THAT(replacedConditions, ContainerEq(set({simple3Id, combination2Id})));

    // Verify the trackerToConditionMap
    ASSERT_EQ(trackerToConditionMap.size(), 6);
    const vector<int>& matcher1Conditions = trackerToConditionMap[matcher1Index];
    EXPECT_THAT(matcher1Conditions, UnorderedElementsAre(simple1Index, combination1Index,
                                                         combination2Index, combination4Index));
    const vector<int>& matcher2Conditions = trackerToConditionMap[matcher2Index];
    EXPECT_THAT(matcher2Conditions, UnorderedElementsAre(simple1Index, combination1Index,
                                                         combination2Index, combination4Index));
    const vector<int>& matcher3Conditions = trackerToConditionMap[matcher3Index];
    EXPECT_THAT(matcher3Conditions, UnorderedElementsAre(simple2Index, combination1Index));
    const vector<int>& matcher4Conditions = trackerToConditionMap[matcher4Index];
    EXPECT_THAT(matcher4Conditions, UnorderedElementsAre(simple2Index, combination1Index));
    const vector<int>& matcher5Conditions = trackerToConditionMap[matcher5Index];
    EXPECT_THAT(matcher5Conditions, UnorderedElementsAre(simple3Index, combination2Index));
    const vector<int>& matcher6Conditions = trackerToConditionMap[matcher6Index];
    EXPECT_THAT(matcher6Conditions, UnorderedElementsAre(simple3Index, combination2Index));

    // Verify the conditionCache. Specifically, simple1 is false and combination4 is true.
    ASSERT_EQ(conditionCache.size(), 6);
    EXPECT_EQ(conditionCache[simple1Index], ConditionState::kFalse);
    EXPECT_EQ(conditionCache[simple2Index], ConditionState::kUnknown);
    EXPECT_EQ(conditionCache[simple3Index], ConditionState::kUnknown);
    EXPECT_EQ(conditionCache[combination1Index], ConditionState::kUnknown);
    EXPECT_EQ(conditionCache[combination2Index], ConditionState::kUnknown);
    EXPECT_EQ(conditionCache[combination4Index], ConditionState::kTrue);

    // Verify tracker indices/ids are correct.
    EXPECT_EQ(newConditionTrackers[simple1Index]->getConditionId(), simple1Id);
    EXPECT_EQ(newConditionTrackers[simple1Index]->mIndex, simple1Index);
    EXPECT_TRUE(newConditionTrackers[simple1Index]->IsSimpleCondition());
    EXPECT_EQ(newConditionTrackers[simple2Index]->getConditionId(), simple2Id);
    EXPECT_EQ(newConditionTrackers[simple2Index]->mIndex, simple2Index);
    EXPECT_TRUE(newConditionTrackers[simple2Index]->IsSimpleCondition());
    EXPECT_EQ(newConditionTrackers[simple3Index]->getConditionId(), simple3Id);
    EXPECT_EQ(newConditionTrackers[simple3Index]->mIndex, simple3Index);
    EXPECT_TRUE(newConditionTrackers[simple3Index]->IsSimpleCondition());
    EXPECT_EQ(newConditionTrackers[combination1Index]->getConditionId(), combination1Id);
    EXPECT_EQ(newConditionTrackers[combination1Index]->mIndex, combination1Index);
    EXPECT_FALSE(newConditionTrackers[combination1Index]->IsSimpleCondition());
    EXPECT_EQ(newConditionTrackers[combination2Index]->getConditionId(), combination2Id);
    EXPECT_EQ(newConditionTrackers[combination2Index]->mIndex, combination2Index);
    EXPECT_FALSE(newConditionTrackers[combination2Index]->IsSimpleCondition());
    EXPECT_EQ(newConditionTrackers[combination4Index]->getConditionId(), combination4Id);
    EXPECT_EQ(newConditionTrackers[combination4Index]->mIndex, combination4Index);
    EXPECT_FALSE(newConditionTrackers[combination4Index]->IsSimpleCondition());

    // Verify preserved trackers have indices updated.
    SimpleConditionTracker* simpleTracker1 =
            static_cast<SimpleConditionTracker*>(newConditionTrackers[simple1Index].get());
    EXPECT_EQ(simpleTracker1->mStartLogMatcherIndex, matcher1Index);
    EXPECT_EQ(simpleTracker1->mStopLogMatcherIndex, matcher2Index);
    EXPECT_EQ(simpleTracker1->mStopAllLogMatcherIndex, -1);

    SimpleConditionTracker* simpleTracker2 =
            static_cast<SimpleConditionTracker*>(newConditionTrackers[simple2Index].get());
    EXPECT_EQ(simpleTracker2->mStartLogMatcherIndex, matcher3Index);
    EXPECT_EQ(simpleTracker2->mStopLogMatcherIndex, matcher4Index);
    EXPECT_EQ(simpleTracker2->mStopAllLogMatcherIndex, -1);

    CombinationConditionTracker* combinationTracker1 = static_cast<CombinationConditionTracker*>(
            newConditionTrackers[combination1Index].get());
    EXPECT_THAT(combinationTracker1->mChildren, UnorderedElementsAre(simple1Index, simple2Index));
    EXPECT_THAT(combinationTracker1->mUnSlicedChildren,
                UnorderedElementsAre(simple1Index, simple2Index));
    EXPECT_THAT(combinationTracker1->mSlicedChildren, IsEmpty());
}

TEST_F(ConfigUpdateTest, TestEventMetricPreserve) {
    StatsdConfig config;
    AtomMatcher startMatcher = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = startMatcher;
    AtomMatcher stopMatcher = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = stopMatcher;
    AtomMatcher whatMatcher = CreateScreenBrightnessChangedAtomMatcher();
    *config.add_atom_matcher() = whatMatcher;

    Predicate predicate = CreateScreenIsOnPredicate();
    *config.add_predicate() = predicate;

    EventMetric* metric = config.add_event_metric();
    metric->set_id(12345);
    metric->set_what(whatMatcher.id());
    metric->set_condition(predicate.id());

    // Create an initial config.
    EXPECT_TRUE(initConfig(config));

    unordered_map<int64_t, int> metricToActivationMap;
    vector<UpdateStatus> metricsToUpdate(1, UPDATE_UNKNOWN);
    EXPECT_TRUE(determineAllMetricUpdateStatuses(config, oldMetricProducerMap, oldMetricProducers,
                                                 metricToActivationMap,
                                                 /*replacedMatchers*/ {}, /*replacedConditions=*/{},
                                                 /*replacedStates=*/{}, metricsToUpdate));
    EXPECT_EQ(metricsToUpdate[0], UPDATE_PRESERVE);
}

TEST_F(ConfigUpdateTest, TestEventMetricActivationAdded) {
    StatsdConfig config;
    AtomMatcher startMatcher = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = startMatcher;
    AtomMatcher stopMatcher = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = stopMatcher;
    AtomMatcher whatMatcher = CreateScreenBrightnessChangedAtomMatcher();
    *config.add_atom_matcher() = whatMatcher;

    Predicate predicate = CreateScreenIsOnPredicate();
    *config.add_predicate() = predicate;

    EventMetric* metric = config.add_event_metric();
    metric->set_id(12345);
    metric->set_what(whatMatcher.id());
    metric->set_condition(predicate.id());

    // Create an initial config.
    EXPECT_TRUE(initConfig(config));

    // Add a metric activation, which should change the proto, causing replacement.
    MetricActivation* activation = config.add_metric_activation();
    activation->set_metric_id(12345);
    EventActivation* eventActivation = activation->add_event_activation();
    eventActivation->set_atom_matcher_id(startMatcher.id());
    eventActivation->set_ttl_seconds(5);

    unordered_map<int64_t, int> metricToActivationMap = {{12345, 0}};
    vector<UpdateStatus> metricsToUpdate(1, UPDATE_UNKNOWN);
    EXPECT_TRUE(determineAllMetricUpdateStatuses(config, oldMetricProducerMap, oldMetricProducers,
                                                 metricToActivationMap,
                                                 /*replacedMatchers*/ {}, /*replacedConditions=*/{},
                                                 /*replacedStates=*/{}, metricsToUpdate));
    EXPECT_EQ(metricsToUpdate[0], UPDATE_REPLACE);
}

TEST_F(ConfigUpdateTest, TestEventMetricWhatChanged) {
    StatsdConfig config;
    AtomMatcher startMatcher = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = startMatcher;
    AtomMatcher stopMatcher = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = stopMatcher;
    AtomMatcher whatMatcher = CreateScreenBrightnessChangedAtomMatcher();
    *config.add_atom_matcher() = whatMatcher;

    Predicate predicate = CreateScreenIsOnPredicate();
    *config.add_predicate() = predicate;

    EventMetric* metric = config.add_event_metric();
    metric->set_id(12345);
    metric->set_what(whatMatcher.id());
    metric->set_condition(predicate.id());

    // Create an initial config.
    EXPECT_TRUE(initConfig(config));

    unordered_map<int64_t, int> metricToActivationMap;
    vector<UpdateStatus> metricsToUpdate(1, UPDATE_UNKNOWN);
    EXPECT_TRUE(determineAllMetricUpdateStatuses(
            config, oldMetricProducerMap, oldMetricProducers, metricToActivationMap,
            /*replacedMatchers*/ {whatMatcher.id()}, /*replacedConditions=*/{},
            /*replacedStates=*/{}, metricsToUpdate));
    EXPECT_EQ(metricsToUpdate[0], UPDATE_REPLACE);
}

TEST_F(ConfigUpdateTest, TestEventMetricConditionChanged) {
    StatsdConfig config;
    AtomMatcher startMatcher = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = startMatcher;
    AtomMatcher stopMatcher = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = stopMatcher;
    AtomMatcher whatMatcher = CreateScreenBrightnessChangedAtomMatcher();
    *config.add_atom_matcher() = whatMatcher;

    Predicate predicate = CreateScreenIsOnPredicate();
    *config.add_predicate() = predicate;

    EventMetric* metric = config.add_event_metric();
    metric->set_id(12345);
    metric->set_what(whatMatcher.id());
    metric->set_condition(predicate.id());

    // Create an initial config.
    EXPECT_TRUE(initConfig(config));

    unordered_map<int64_t, int> metricToActivationMap;
    vector<UpdateStatus> metricsToUpdate(1, UPDATE_UNKNOWN);
    EXPECT_TRUE(determineAllMetricUpdateStatuses(
            config, oldMetricProducerMap, oldMetricProducers, metricToActivationMap,
            /*replacedMatchers*/ {}, /*replacedConditions=*/{predicate.id()},
            /*replacedStates=*/{}, metricsToUpdate));
    EXPECT_EQ(metricsToUpdate[0], UPDATE_REPLACE);
}

TEST_F(ConfigUpdateTest, TestMetricConditionLinkDepsChanged) {
    StatsdConfig config;
    AtomMatcher startMatcher = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = startMatcher;
    AtomMatcher stopMatcher = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = stopMatcher;
    AtomMatcher whatMatcher = CreateScreenBrightnessChangedAtomMatcher();
    *config.add_atom_matcher() = whatMatcher;

    Predicate predicate = CreateScreenIsOnPredicate();
    *config.add_predicate() = predicate;

    Predicate linkPredicate = CreateScreenIsOffPredicate();
    *config.add_predicate() = linkPredicate;

    EventMetric* metric = config.add_event_metric();
    metric->set_id(12345);
    metric->set_what(whatMatcher.id());
    metric->set_condition(predicate.id());
    // Doesn't make sense as a real metric definition, but suffices as a separate predicate
    // From the one in the condition.
    MetricConditionLink* link = metric->add_links();
    link->set_condition(linkPredicate.id());

    // Create an initial config.
    EXPECT_TRUE(initConfig(config));

    unordered_map<int64_t, int> metricToActivationMap;
    vector<UpdateStatus> metricsToUpdate(1, UPDATE_UNKNOWN);
    EXPECT_TRUE(determineAllMetricUpdateStatuses(
            config, oldMetricProducerMap, oldMetricProducers, metricToActivationMap,
            /*replacedMatchers*/ {}, /*replacedConditions=*/{linkPredicate.id()},
            /*replacedStates=*/{}, metricsToUpdate));
    EXPECT_EQ(metricsToUpdate[0], UPDATE_REPLACE);
}

TEST_F(ConfigUpdateTest, TestEventMetricActivationDepsChange) {
    StatsdConfig config;
    AtomMatcher startMatcher = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = startMatcher;
    AtomMatcher stopMatcher = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = stopMatcher;
    AtomMatcher whatMatcher = CreateScreenBrightnessChangedAtomMatcher();
    *config.add_atom_matcher() = whatMatcher;

    Predicate predicate = CreateScreenIsOnPredicate();
    *config.add_predicate() = predicate;

    EventMetric* metric = config.add_event_metric();
    metric->set_id(12345);
    metric->set_what(whatMatcher.id());
    metric->set_condition(predicate.id());

    MetricActivation* activation = config.add_metric_activation();
    activation->set_metric_id(12345);
    EventActivation* eventActivation = activation->add_event_activation();
    eventActivation->set_atom_matcher_id(startMatcher.id());
    eventActivation->set_ttl_seconds(5);

    // Create an initial config.
    EXPECT_TRUE(initConfig(config));

    unordered_map<int64_t, int> metricToActivationMap = {{12345, 0}};
    vector<UpdateStatus> metricsToUpdate(1, UPDATE_UNKNOWN);
    EXPECT_TRUE(determineAllMetricUpdateStatuses(
            config, oldMetricProducerMap, oldMetricProducers, metricToActivationMap,
            /*replacedMatchers*/ {startMatcher.id()}, /*replacedConditions=*/{},
            /*replacedStates=*/{}, metricsToUpdate));
    EXPECT_EQ(metricsToUpdate[0], UPDATE_REPLACE);
}

TEST_F(ConfigUpdateTest, TestCountMetricPreserve) {
    StatsdConfig config;
    AtomMatcher startMatcher = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = startMatcher;
    AtomMatcher stopMatcher = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = stopMatcher;
    AtomMatcher whatMatcher = CreateScreenBrightnessChangedAtomMatcher();
    *config.add_atom_matcher() = whatMatcher;

    Predicate predicate = CreateScreenIsOnPredicate();
    *config.add_predicate() = predicate;
    State sliceState = CreateScreenState();
    *config.add_state() = sliceState;

    CountMetric* metric = config.add_count_metric();
    metric->set_id(12345);
    metric->set_what(whatMatcher.id());
    metric->set_condition(predicate.id());
    metric->add_slice_by_state(sliceState.id());
    metric->set_bucket(ONE_HOUR);

    // Create an initial config.
    EXPECT_TRUE(initConfig(config));

    unordered_map<int64_t, int> metricToActivationMap;
    vector<UpdateStatus> metricsToUpdate(1, UPDATE_UNKNOWN);
    EXPECT_TRUE(determineAllMetricUpdateStatuses(config, oldMetricProducerMap, oldMetricProducers,
                                                 metricToActivationMap,
                                                 /*replacedMatchers*/ {}, /*replacedConditions=*/{},
                                                 /*replacedStates=*/{}, metricsToUpdate));
    EXPECT_EQ(metricsToUpdate[0], UPDATE_PRESERVE);
}

TEST_F(ConfigUpdateTest, TestCountMetricDefinitionChange) {
    StatsdConfig config;
    AtomMatcher startMatcher = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = startMatcher;
    AtomMatcher stopMatcher = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = stopMatcher;
    AtomMatcher whatMatcher = CreateScreenBrightnessChangedAtomMatcher();
    *config.add_atom_matcher() = whatMatcher;

    Predicate predicate = CreateScreenIsOnPredicate();
    *config.add_predicate() = predicate;

    CountMetric* metric = config.add_count_metric();
    metric->set_id(12345);
    metric->set_what(whatMatcher.id());
    metric->set_condition(predicate.id());
    metric->set_bucket(ONE_HOUR);

    // Create an initial config.
    EXPECT_TRUE(initConfig(config));

    // Change bucket size, which should change the proto, causing replacement.
    metric->set_bucket(TEN_MINUTES);

    unordered_map<int64_t, int> metricToActivationMap;
    vector<UpdateStatus> metricsToUpdate(1, UPDATE_UNKNOWN);
    EXPECT_TRUE(determineAllMetricUpdateStatuses(config, oldMetricProducerMap, oldMetricProducers,
                                                 metricToActivationMap,
                                                 /*replacedMatchers*/ {}, /*replacedConditions=*/{},
                                                 /*replacedStates=*/{}, metricsToUpdate));
    EXPECT_EQ(metricsToUpdate[0], UPDATE_REPLACE);
}

TEST_F(ConfigUpdateTest, TestCountMetricWhatChanged) {
    StatsdConfig config;
    AtomMatcher startMatcher = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = startMatcher;
    AtomMatcher stopMatcher = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = stopMatcher;
    AtomMatcher whatMatcher = CreateScreenBrightnessChangedAtomMatcher();
    *config.add_atom_matcher() = whatMatcher;

    Predicate predicate = CreateScreenIsOnPredicate();
    *config.add_predicate() = predicate;

    CountMetric* metric = config.add_count_metric();
    metric->set_id(12345);
    metric->set_what(whatMatcher.id());
    metric->set_condition(predicate.id());
    metric->set_bucket(ONE_HOUR);

    // Create an initial config.
    EXPECT_TRUE(initConfig(config));

    unordered_map<int64_t, int> metricToActivationMap;
    vector<UpdateStatus> metricsToUpdate(1, UPDATE_UNKNOWN);
    EXPECT_TRUE(determineAllMetricUpdateStatuses(
            config, oldMetricProducerMap, oldMetricProducers, metricToActivationMap,
            /*replacedMatchers*/ {whatMatcher.id()}, /*replacedConditions=*/{},
            /*replacedStates=*/{}, metricsToUpdate));
    EXPECT_EQ(metricsToUpdate[0], UPDATE_REPLACE);
}

TEST_F(ConfigUpdateTest, TestCountMetricConditionChanged) {
    StatsdConfig config;
    AtomMatcher startMatcher = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = startMatcher;
    AtomMatcher stopMatcher = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = stopMatcher;
    AtomMatcher whatMatcher = CreateScreenBrightnessChangedAtomMatcher();
    *config.add_atom_matcher() = whatMatcher;

    Predicate predicate = CreateScreenIsOnPredicate();
    *config.add_predicate() = predicate;

    CountMetric* metric = config.add_count_metric();
    metric->set_id(12345);
    metric->set_what(whatMatcher.id());
    metric->set_condition(predicate.id());
    metric->set_bucket(ONE_HOUR);

    // Create an initial config.
    EXPECT_TRUE(initConfig(config));

    unordered_map<int64_t, int> metricToActivationMap;
    vector<UpdateStatus> metricsToUpdate(1, UPDATE_UNKNOWN);
    EXPECT_TRUE(determineAllMetricUpdateStatuses(
            config, oldMetricProducerMap, oldMetricProducers, metricToActivationMap,
            /*replacedMatchers*/ {}, /*replacedConditions=*/{predicate.id()},
            /*replacedStates=*/{}, metricsToUpdate));
    EXPECT_EQ(metricsToUpdate[0], UPDATE_REPLACE);
}

TEST_F(ConfigUpdateTest, TestCountMetricStateChanged) {
    StatsdConfig config;
    AtomMatcher startMatcher = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = startMatcher;
    AtomMatcher stopMatcher = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = stopMatcher;
    AtomMatcher whatMatcher = CreateScreenBrightnessChangedAtomMatcher();
    *config.add_atom_matcher() = whatMatcher;

    State sliceState = CreateScreenState();
    *config.add_state() = sliceState;

    CountMetric* metric = config.add_count_metric();
    metric->set_id(12345);
    metric->set_what(whatMatcher.id());
    metric->add_slice_by_state(sliceState.id());
    metric->set_bucket(ONE_HOUR);

    // Create an initial config.
    EXPECT_TRUE(initConfig(config));

    unordered_map<int64_t, int> metricToActivationMap;
    vector<UpdateStatus> metricsToUpdate(1, UPDATE_UNKNOWN);
    EXPECT_TRUE(determineAllMetricUpdateStatuses(
            config, oldMetricProducerMap, oldMetricProducers, metricToActivationMap,
            /*replacedMatchers*/ {}, /*replacedConditions=*/{},
            /*replacedStates=*/{sliceState.id()}, metricsToUpdate));
    EXPECT_EQ(metricsToUpdate[0], UPDATE_REPLACE);
}

TEST_F(ConfigUpdateTest, TestGaugeMetricPreserve) {
    StatsdConfig config;
    AtomMatcher startMatcher = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = startMatcher;
    AtomMatcher stopMatcher = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = stopMatcher;
    AtomMatcher whatMatcher = CreateScreenBrightnessChangedAtomMatcher();
    *config.add_atom_matcher() = whatMatcher;

    Predicate predicate = CreateScreenIsOnPredicate();
    *config.add_predicate() = predicate;

    *config.add_gauge_metric() = createGaugeMetric(
            "GAUGE1", whatMatcher.id(), GaugeMetric::RANDOM_ONE_SAMPLE, predicate.id(), nullopt);

    EXPECT_TRUE(initConfig(config));

    unordered_map<int64_t, int> metricToActivationMap;
    vector<UpdateStatus> metricsToUpdate(1, UPDATE_UNKNOWN);
    EXPECT_TRUE(determineAllMetricUpdateStatuses(config, oldMetricProducerMap, oldMetricProducers,
                                                 metricToActivationMap,
                                                 /*replacedMatchers*/ {}, /*replacedConditions=*/{},
                                                 /*replacedStates=*/{}, metricsToUpdate));
    EXPECT_EQ(metricsToUpdate[0], UPDATE_PRESERVE);
}

TEST_F(ConfigUpdateTest, TestGaugeMetricDefinitionChange) {
    StatsdConfig config;
    AtomMatcher whatMatcher = CreateScreenBrightnessChangedAtomMatcher();
    *config.add_atom_matcher() = whatMatcher;

    *config.add_gauge_metric() = createGaugeMetric(
            "GAUGE1", whatMatcher.id(), GaugeMetric::RANDOM_ONE_SAMPLE, nullopt, nullopt);

    EXPECT_TRUE(initConfig(config));

    // Change split bucket on app upgrade, which should change the proto, causing replacement.
    config.mutable_gauge_metric(0)->set_split_bucket_for_app_upgrade(false);

    unordered_map<int64_t, int> metricToActivationMap;
    vector<UpdateStatus> metricsToUpdate(1, UPDATE_UNKNOWN);
    EXPECT_TRUE(determineAllMetricUpdateStatuses(config, oldMetricProducerMap, oldMetricProducers,
                                                 metricToActivationMap,
                                                 /*replacedMatchers*/ {}, /*replacedConditions=*/{},
                                                 /*replacedStates=*/{}, metricsToUpdate));
    EXPECT_EQ(metricsToUpdate[0], UPDATE_REPLACE);
}

TEST_F(ConfigUpdateTest, TestGaugeMetricWhatChanged) {
    StatsdConfig config;
    AtomMatcher whatMatcher = CreateScreenBrightnessChangedAtomMatcher();
    *config.add_atom_matcher() = whatMatcher;

    *config.add_gauge_metric() = createGaugeMetric(
            "GAUGE1", whatMatcher.id(), GaugeMetric::RANDOM_ONE_SAMPLE, nullopt, nullopt);

    EXPECT_TRUE(initConfig(config));

    unordered_map<int64_t, int> metricToActivationMap;
    vector<UpdateStatus> metricsToUpdate(1, UPDATE_UNKNOWN);
    EXPECT_TRUE(determineAllMetricUpdateStatuses(
            config, oldMetricProducerMap, oldMetricProducers, metricToActivationMap,
            /*replacedMatchers*/ {whatMatcher.id()}, /*replacedConditions=*/{},
            /*replacedStates=*/{}, metricsToUpdate));
    EXPECT_EQ(metricsToUpdate[0], UPDATE_REPLACE);
}

TEST_F(ConfigUpdateTest, TestGaugeMetricConditionChanged) {
    StatsdConfig config;
    AtomMatcher startMatcher = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = startMatcher;
    AtomMatcher stopMatcher = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = stopMatcher;
    AtomMatcher whatMatcher = CreateScreenBrightnessChangedAtomMatcher();
    *config.add_atom_matcher() = whatMatcher;

    Predicate predicate = CreateScreenIsOnPredicate();
    *config.add_predicate() = predicate;

    *config.add_gauge_metric() = createGaugeMetric(
            "GAUGE1", whatMatcher.id(), GaugeMetric::RANDOM_ONE_SAMPLE, predicate.id(), nullopt);

    EXPECT_TRUE(initConfig(config));

    unordered_map<int64_t, int> metricToActivationMap;
    vector<UpdateStatus> metricsToUpdate(1, UPDATE_UNKNOWN);
    EXPECT_TRUE(determineAllMetricUpdateStatuses(
            config, oldMetricProducerMap, oldMetricProducers, metricToActivationMap,
            /*replacedMatchers*/ {}, /*replacedConditions=*/{predicate.id()},
            /*replacedStates=*/{}, metricsToUpdate));
    EXPECT_EQ(metricsToUpdate[0], UPDATE_REPLACE);
}

TEST_F(ConfigUpdateTest, TestGaugeMetricTriggerEventChanged) {
    StatsdConfig config;
    AtomMatcher triggerEvent = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = triggerEvent;
    AtomMatcher whatMatcher = CreateTemperatureAtomMatcher();
    *config.add_atom_matcher() = whatMatcher;

    *config.add_gauge_metric() = createGaugeMetric(
            "GAUGE1", whatMatcher.id(), GaugeMetric::FIRST_N_SAMPLES, nullopt, triggerEvent.id());

    EXPECT_TRUE(initConfig(config));

    unordered_map<int64_t, int> metricToActivationMap;
    vector<UpdateStatus> metricsToUpdate(1, UPDATE_UNKNOWN);
    EXPECT_TRUE(determineAllMetricUpdateStatuses(
            config, oldMetricProducerMap, oldMetricProducers, metricToActivationMap,
            /*replacedMatchers*/ {triggerEvent.id()}, /*replacedConditions=*/{},
            /*replacedStates=*/{}, metricsToUpdate));
    EXPECT_EQ(metricsToUpdate[0], UPDATE_REPLACE);
}

TEST_F(ConfigUpdateTest, TestDurationMetricPreserve) {
    StatsdConfig config;
    AtomMatcher startMatcher = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = startMatcher;
    AtomMatcher stopMatcher = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = stopMatcher;

    Predicate what = CreateScreenIsOnPredicate();
    *config.add_predicate() = what;
    Predicate condition = CreateScreenIsOffPredicate();
    *config.add_predicate() = condition;

    State sliceState = CreateScreenState();
    *config.add_state() = sliceState;

    *config.add_duration_metric() =
            createDurationMetric("DURATION1", what.id(), condition.id(), {sliceState.id()});
    EXPECT_TRUE(initConfig(config));

    unordered_map<int64_t, int> metricToActivationMap;
    vector<UpdateStatus> metricsToUpdate(1, UPDATE_UNKNOWN);
    EXPECT_TRUE(determineAllMetricUpdateStatuses(config, oldMetricProducerMap, oldMetricProducers,
                                                 metricToActivationMap,
                                                 /*replacedMatchers*/ {}, /*replacedConditions=*/{},
                                                 /*replacedStates=*/{}, metricsToUpdate));
    EXPECT_EQ(metricsToUpdate[0], UPDATE_PRESERVE);
}

TEST_F(ConfigUpdateTest, TestDurationMetricDefinitionChange) {
    StatsdConfig config;
    AtomMatcher startMatcher = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = startMatcher;
    AtomMatcher stopMatcher = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = stopMatcher;

    Predicate what = CreateScreenIsOnPredicate();
    *config.add_predicate() = what;

    *config.add_duration_metric() = createDurationMetric("DURATION1", what.id(), nullopt, {});
    EXPECT_TRUE(initConfig(config));

    config.mutable_duration_metric(0)->set_aggregation_type(DurationMetric::MAX_SPARSE);

    unordered_map<int64_t, int> metricToActivationMap;
    vector<UpdateStatus> metricsToUpdate(1, UPDATE_UNKNOWN);
    EXPECT_TRUE(determineAllMetricUpdateStatuses(config, oldMetricProducerMap, oldMetricProducers,
                                                 metricToActivationMap, /*replacedMatchers*/ {},
                                                 /*replacedConditions=*/{}, /*replacedStates=*/{},
                                                 metricsToUpdate));
    EXPECT_EQ(metricsToUpdate[0], UPDATE_REPLACE);
}

TEST_F(ConfigUpdateTest, TestDurationMetricWhatChanged) {
    StatsdConfig config;
    AtomMatcher startMatcher = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = startMatcher;
    AtomMatcher stopMatcher = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = stopMatcher;

    Predicate what = CreateScreenIsOnPredicate();
    *config.add_predicate() = what;

    *config.add_duration_metric() = createDurationMetric("DURATION1", what.id(), nullopt, {});
    EXPECT_TRUE(initConfig(config));

    unordered_map<int64_t, int> metricToActivationMap;
    vector<UpdateStatus> metricsToUpdate(1, UPDATE_UNKNOWN);
    EXPECT_TRUE(determineAllMetricUpdateStatuses(
            config, oldMetricProducerMap, oldMetricProducers, metricToActivationMap,
            /*replacedMatchers*/ {}, /*replacedConditions=*/{what.id()},
            /*replacedStates=*/{}, metricsToUpdate));
    EXPECT_EQ(metricsToUpdate[0], UPDATE_REPLACE);
}

TEST_F(ConfigUpdateTest, TestDurationMetricConditionChanged) {
    StatsdConfig config;
    AtomMatcher startMatcher = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = startMatcher;
    AtomMatcher stopMatcher = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = stopMatcher;

    Predicate what = CreateScreenIsOnPredicate();
    *config.add_predicate() = what;
    Predicate condition = CreateScreenIsOffPredicate();
    *config.add_predicate() = condition;

    *config.add_duration_metric() = createDurationMetric("DURATION", what.id(), condition.id(), {});
    EXPECT_TRUE(initConfig(config));

    unordered_map<int64_t, int> metricToActivationMap;
    vector<UpdateStatus> metricsToUpdate(1, UPDATE_UNKNOWN);
    EXPECT_TRUE(determineAllMetricUpdateStatuses(
            config, oldMetricProducerMap, oldMetricProducers, metricToActivationMap,
            /*replacedMatchers*/ {}, /*replacedConditions=*/{condition.id()},
            /*replacedStates=*/{}, metricsToUpdate));
    EXPECT_EQ(metricsToUpdate[0], UPDATE_REPLACE);
}

TEST_F(ConfigUpdateTest, TestDurationMetricStateChange) {
    StatsdConfig config;
    AtomMatcher startMatcher = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = startMatcher;
    AtomMatcher stopMatcher = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = stopMatcher;

    Predicate what = CreateScreenIsOnPredicate();
    *config.add_predicate() = what;

    State sliceState = CreateScreenState();
    *config.add_state() = sliceState;

    *config.add_duration_metric() =
            createDurationMetric("DURATION1", what.id(), nullopt, {sliceState.id()});
    EXPECT_TRUE(initConfig(config));

    unordered_map<int64_t, int> metricToActivationMap;
    vector<UpdateStatus> metricsToUpdate(1, UPDATE_UNKNOWN);
    EXPECT_TRUE(determineAllMetricUpdateStatuses(
            config, oldMetricProducerMap, oldMetricProducers, metricToActivationMap,
            /*replacedMatchers*/ {}, /*replacedConditions=*/{},
            /*replacedStates=*/{sliceState.id()}, metricsToUpdate));
    EXPECT_EQ(metricsToUpdate[0], UPDATE_REPLACE);
}

TEST_F(ConfigUpdateTest, TestValueMetricPreserve) {
    StatsdConfig config;
    AtomMatcher startMatcher = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = startMatcher;
    AtomMatcher stopMatcher = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = stopMatcher;
    AtomMatcher whatMatcher = CreateTemperatureAtomMatcher();
    *config.add_atom_matcher() = whatMatcher;

    Predicate predicate = CreateScreenIsOnPredicate();
    *config.add_predicate() = predicate;
    State sliceState = CreateScreenState();
    *config.add_state() = sliceState;

    *config.add_value_metric() =
            createValueMetric("VALUE1", whatMatcher, predicate.id(), {sliceState.id()});
    EXPECT_TRUE(initConfig(config));

    unordered_map<int64_t, int> metricToActivationMap;
    vector<UpdateStatus> metricsToUpdate(1, UPDATE_UNKNOWN);
    EXPECT_TRUE(determineAllMetricUpdateStatuses(config, oldMetricProducerMap, oldMetricProducers,
                                                 metricToActivationMap,
                                                 /*replacedMatchers*/ {}, /*replacedConditions=*/{},
                                                 /*replacedStates=*/{}, metricsToUpdate));
    EXPECT_EQ(metricsToUpdate[0], UPDATE_PRESERVE);
}

TEST_F(ConfigUpdateTest, TestValueMetricDefinitionChange) {
    StatsdConfig config;
    AtomMatcher whatMatcher = CreateScreenBrightnessChangedAtomMatcher();
    *config.add_atom_matcher() = whatMatcher;

    *config.add_value_metric() = createValueMetric("VALUE1", whatMatcher, nullopt, {});
    EXPECT_TRUE(initConfig(config));

    // Change skip zero diff output, which should change the proto, causing replacement.
    config.mutable_value_metric(0)->set_skip_zero_diff_output(true);

    unordered_map<int64_t, int> metricToActivationMap;
    vector<UpdateStatus> metricsToUpdate(1, UPDATE_UNKNOWN);
    EXPECT_TRUE(determineAllMetricUpdateStatuses(config, oldMetricProducerMap, oldMetricProducers,
                                                 metricToActivationMap,
                                                 /*replacedMatchers*/ {}, /*replacedConditions=*/{},
                                                 /*replacedStates=*/{}, metricsToUpdate));
    EXPECT_EQ(metricsToUpdate[0], UPDATE_REPLACE);
}

TEST_F(ConfigUpdateTest, TestValueMetricWhatChanged) {
    StatsdConfig config;
    AtomMatcher whatMatcher = CreateTemperatureAtomMatcher();
    *config.add_atom_matcher() = whatMatcher;

    *config.add_value_metric() = createValueMetric("VALUE1", whatMatcher, nullopt, {});
    EXPECT_TRUE(initConfig(config));

    unordered_map<int64_t, int> metricToActivationMap;
    vector<UpdateStatus> metricsToUpdate(1, UPDATE_UNKNOWN);
    EXPECT_TRUE(determineAllMetricUpdateStatuses(
            config, oldMetricProducerMap, oldMetricProducers, metricToActivationMap,
            /*replacedMatchers*/ {whatMatcher.id()}, /*replacedConditions=*/{},
            /*replacedStates=*/{}, metricsToUpdate));
    EXPECT_EQ(metricsToUpdate[0], UPDATE_REPLACE);
}

TEST_F(ConfigUpdateTest, TestValueMetricConditionChanged) {
    StatsdConfig config;
    AtomMatcher startMatcher = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = startMatcher;
    AtomMatcher stopMatcher = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = stopMatcher;
    AtomMatcher whatMatcher = CreateTemperatureAtomMatcher();
    *config.add_atom_matcher() = whatMatcher;

    Predicate predicate = CreateScreenIsOnPredicate();
    *config.add_predicate() = predicate;

    *config.add_value_metric() = createValueMetric("VALUE1", whatMatcher, predicate.id(), {});
    EXPECT_TRUE(initConfig(config));

    unordered_map<int64_t, int> metricToActivationMap;
    vector<UpdateStatus> metricsToUpdate(1, UPDATE_UNKNOWN);
    EXPECT_TRUE(determineAllMetricUpdateStatuses(
            config, oldMetricProducerMap, oldMetricProducers, metricToActivationMap,
            /*replacedMatchers*/ {}, /*replacedConditions=*/{predicate.id()},
            /*replacedStates=*/{}, metricsToUpdate));
    EXPECT_EQ(metricsToUpdate[0], UPDATE_REPLACE);
}

TEST_F(ConfigUpdateTest, TestValueMetricStateChanged) {
    StatsdConfig config;
    AtomMatcher whatMatcher = CreateScreenBrightnessChangedAtomMatcher();
    *config.add_atom_matcher() = whatMatcher;

    State sliceState = CreateScreenState();
    *config.add_state() = sliceState;

    *config.add_value_metric() =
            createValueMetric("VALUE1", whatMatcher, nullopt, {sliceState.id()});
    EXPECT_TRUE(initConfig(config));

    unordered_map<int64_t, int> metricToActivationMap;
    vector<UpdateStatus> metricsToUpdate(1, UPDATE_UNKNOWN);
    EXPECT_TRUE(determineAllMetricUpdateStatuses(
            config, oldMetricProducerMap, oldMetricProducers, metricToActivationMap,
            /*replacedMatchers*/ {}, /*replacedConditions=*/{},
            /*replacedStates=*/{sliceState.id()}, metricsToUpdate));
    EXPECT_EQ(metricsToUpdate[0], UPDATE_REPLACE);
}

TEST_F(ConfigUpdateTest, TestUpdateEventMetrics) {
    StatsdConfig config;

    // Add atom matchers/predicates. These are mostly needed for initStatsdConfig
    AtomMatcher matcher1 = CreateScreenTurnedOnAtomMatcher();
    int64_t matcher1Id = matcher1.id();
    *config.add_atom_matcher() = matcher1;

    AtomMatcher matcher2 = CreateScreenTurnedOffAtomMatcher();
    int64_t matcher2Id = matcher2.id();
    *config.add_atom_matcher() = matcher2;

    AtomMatcher matcher3 = CreateStartScheduledJobAtomMatcher();
    int64_t matcher3Id = matcher3.id();
    *config.add_atom_matcher() = matcher3;

    AtomMatcher matcher4 = CreateFinishScheduledJobAtomMatcher();
    int64_t matcher4Id = matcher4.id();
    *config.add_atom_matcher() = matcher4;

    AtomMatcher matcher5 = CreateBatterySaverModeStartAtomMatcher();
    int64_t matcher5Id = matcher5.id();
    *config.add_atom_matcher() = matcher5;

    Predicate predicate1 = CreateScreenIsOnPredicate();
    int64_t predicate1Id = predicate1.id();
    *config.add_predicate() = predicate1;

    Predicate predicate2 = CreateScheduledJobPredicate();
    int64_t predicate2Id = predicate2.id();
    *config.add_predicate() = predicate2;

    // Add a few event metrics.
    // Will be preserved.
    EventMetric event1 = createEventMetric("EVENT1", matcher1Id, predicate2Id);
    int64_t event1Id = event1.id();
    *config.add_event_metric() = event1;

    // Will be replaced.
    EventMetric event2 = createEventMetric("EVENT2", matcher2Id, nullopt);
    int64_t event2Id = event2.id();
    *config.add_event_metric() = event2;

    // Will be replaced.
    EventMetric event3 = createEventMetric("EVENT3", matcher3Id, nullopt);
    int64_t event3Id = event3.id();
    *config.add_event_metric() = event3;

    MetricActivation event3Activation;
    event3Activation.set_metric_id(event3Id);
    EventActivation* eventActivation = event3Activation.add_event_activation();
    eventActivation->set_atom_matcher_id(matcher5Id);
    eventActivation->set_ttl_seconds(5);
    *config.add_metric_activation() = event3Activation;

    // Will be replaced.
    EventMetric event4 = createEventMetric("EVENT4", matcher4Id, predicate1Id);
    int64_t event4Id = event4.id();
    *config.add_event_metric() = event4;

    // Will be deleted.
    EventMetric event5 = createEventMetric("EVENT5", matcher5Id, nullopt);
    int64_t event5Id = event5.id();
    *config.add_event_metric() = event5;

    EXPECT_TRUE(initConfig(config));

    // Used later to ensure the condition wizard is replaced. Get it before doing the update.
    sp<ConditionWizard> oldConditionWizard = oldMetricProducers[0]->mWizard;
    EXPECT_EQ(oldConditionWizard->getStrongCount(), oldMetricProducers.size() + 1);

    // Add a condition to event2, causing it to be replaced.
    event2.set_condition(predicate1Id);

    // Mark matcher 5 as replaced. Causes event3 to be replaced.
    set<int64_t> replacedMatchers;
    replacedMatchers.insert(matcher5Id);

    // Mark predicate 1 as replaced. Causes event4 to be replaced.
    set<int64_t> replacedConditions;
    replacedConditions.insert(predicate1Id);

    // Fake that predicate 2 is true.
    ASSERT_EQ(oldMetricProducers[0]->getMetricId(), event1Id);
    oldMetricProducers[0]->onConditionChanged(true, /*timestamp=*/0);
    EXPECT_EQ(oldMetricProducers[0]->mCondition, ConditionState::kTrue);

    // New event metric. Should have an initial condition of true since it depends on predicate2.
    EventMetric event6 = createEventMetric("EVENT6", matcher3Id, predicate2Id);
    int64_t event6Id = event6.id();
    MetricActivation event6Activation;
    event6Activation.set_metric_id(event6Id);
    eventActivation = event6Activation.add_event_activation();
    eventActivation->set_atom_matcher_id(matcher5Id);
    eventActivation->set_ttl_seconds(20);

    // Map the matchers and predicates in reverse order to force the indices to change.
    std::unordered_map<int64_t, int> newAtomMatchingTrackerMap;
    const int matcher5Index = 0;
    newAtomMatchingTrackerMap[matcher5Id] = 0;
    const int matcher4Index = 1;
    newAtomMatchingTrackerMap[matcher4Id] = 1;
    const int matcher3Index = 2;
    newAtomMatchingTrackerMap[matcher3Id] = 2;
    const int matcher2Index = 3;
    newAtomMatchingTrackerMap[matcher2Id] = 3;
    const int matcher1Index = 4;
    newAtomMatchingTrackerMap[matcher1Id] = 4;
    // Use the existing matchers. A bit hacky, but saves code and we don't rely on them.
    vector<sp<AtomMatchingTracker>> newAtomMatchingTrackers(5);
    std::reverse_copy(oldAtomMatchingTrackers.begin(), oldAtomMatchingTrackers.end(),
                      newAtomMatchingTrackers.begin());

    std::unordered_map<int64_t, int> newConditionTrackerMap;
    const int predicate2Index = 0;
    newConditionTrackerMap[predicate2Id] = 0;
    const int predicate1Index = 1;
    newConditionTrackerMap[predicate1Id] = 1;
    // Use the existing conditionTrackers. A bit hacky, but saves code and we don't rely on them.
    vector<sp<ConditionTracker>> newConditionTrackers(2);
    std::reverse_copy(oldConditionTrackers.begin(), oldConditionTrackers.end(),
                      newConditionTrackers.begin());
    // Fake that predicate2 is true.
    vector<ConditionState> conditionCache = {ConditionState::kTrue, ConditionState::kUnknown};

    StatsdConfig newConfig;
    *newConfig.add_event_metric() = event6;
    const int event6Index = 0;
    *newConfig.add_event_metric() = event3;
    const int event3Index = 1;
    *newConfig.add_event_metric() = event1;
    const int event1Index = 2;
    *newConfig.add_event_metric() = event4;
    const int event4Index = 3;
    *newConfig.add_event_metric() = event2;
    const int event2Index = 4;
    *newConfig.add_metric_activation() = event3Activation;
    *newConfig.add_metric_activation() = event6Activation;

    // Output data structures to validate.
    unordered_map<int64_t, int> newMetricProducerMap;
    vector<sp<MetricProducer>> newMetricProducers;
    unordered_map<int, vector<int>> conditionToMetricMap;
    unordered_map<int, vector<int>> trackerToMetricMap;
    set<int64_t> noReportMetricIds;
    unordered_map<int, vector<int>> activationAtomTrackerToMetricMap;
    unordered_map<int, vector<int>> deactivationAtomTrackerToMetricMap;
    vector<int> metricsWithActivation;
    EXPECT_TRUE(updateMetrics(
            key, newConfig, /*timeBaseNs=*/123, /*currentTimeNs=*/12345, new StatsPullerManager(),
            oldAtomMatchingTrackerMap, newAtomMatchingTrackerMap, replacedMatchers,
            newAtomMatchingTrackers, newConditionTrackerMap, replacedConditions,
            newConditionTrackers, conditionCache, /*stateAtomIdMap=*/{}, /*allStateGroupMaps=*/{},
            /*replacedStates=*/{}, oldMetricProducerMap, oldMetricProducers, newMetricProducerMap,
            newMetricProducers, conditionToMetricMap, trackerToMetricMap, noReportMetricIds,
            activationAtomTrackerToMetricMap, deactivationAtomTrackerToMetricMap,
            metricsWithActivation));

    unordered_map<int64_t, int> expectedMetricProducerMap = {
            {event1Id, event1Index}, {event2Id, event2Index}, {event3Id, event3Index},
            {event4Id, event4Index}, {event6Id, event6Index},
    };
    EXPECT_THAT(newMetricProducerMap, ContainerEq(expectedMetricProducerMap));

    // Make sure preserved metrics are the same.
    ASSERT_EQ(newMetricProducers.size(), 5);
    EXPECT_EQ(oldMetricProducers[oldMetricProducerMap.at(event1Id)],
              newMetricProducers[newMetricProducerMap.at(event1Id)]);

    // Make sure replaced metrics are different.
    EXPECT_NE(oldMetricProducers[oldMetricProducerMap.at(event2Id)],
              newMetricProducers[newMetricProducerMap.at(event2Id)]);
    EXPECT_NE(oldMetricProducers[oldMetricProducerMap.at(event3Id)],
              newMetricProducers[newMetricProducerMap.at(event3Id)]);
    EXPECT_NE(oldMetricProducers[oldMetricProducerMap.at(event4Id)],
              newMetricProducers[newMetricProducerMap.at(event4Id)]);

    // Verify the conditionToMetricMap.
    ASSERT_EQ(conditionToMetricMap.size(), 2);
    const vector<int>& condition1Metrics = conditionToMetricMap[predicate1Index];
    EXPECT_THAT(condition1Metrics, UnorderedElementsAre(event2Index, event4Index));
    const vector<int>& condition2Metrics = conditionToMetricMap[predicate2Index];
    EXPECT_THAT(condition2Metrics, UnorderedElementsAre(event1Index, event6Index));

    // Verify the trackerToMetricMap.
    ASSERT_EQ(trackerToMetricMap.size(), 4);
    const vector<int>& matcher1Metrics = trackerToMetricMap[matcher1Index];
    EXPECT_THAT(matcher1Metrics, UnorderedElementsAre(event1Index));
    const vector<int>& matcher2Metrics = trackerToMetricMap[matcher2Index];
    EXPECT_THAT(matcher2Metrics, UnorderedElementsAre(event2Index));
    const vector<int>& matcher3Metrics = trackerToMetricMap[matcher3Index];
    EXPECT_THAT(matcher3Metrics, UnorderedElementsAre(event3Index, event6Index));
    const vector<int>& matcher4Metrics = trackerToMetricMap[matcher4Index];
    EXPECT_THAT(matcher4Metrics, UnorderedElementsAre(event4Index));

    // Verify event activation/deactivation maps.
    ASSERT_EQ(activationAtomTrackerToMetricMap.size(), 1);
    EXPECT_THAT(activationAtomTrackerToMetricMap[matcher5Index],
                UnorderedElementsAre(event3Index, event6Index));
    ASSERT_EQ(deactivationAtomTrackerToMetricMap.size(), 0);
    ASSERT_EQ(metricsWithActivation.size(), 2);
    EXPECT_THAT(metricsWithActivation, UnorderedElementsAre(event3Index, event6Index));

    // Verify tracker indices/ids/conditions are correct.
    EXPECT_EQ(newMetricProducers[event1Index]->getMetricId(), event1Id);
    EXPECT_EQ(newMetricProducers[event1Index]->mConditionTrackerIndex, predicate2Index);
    EXPECT_EQ(newMetricProducers[event1Index]->mCondition, ConditionState::kTrue);
    EXPECT_EQ(newMetricProducers[event2Index]->getMetricId(), event2Id);
    EXPECT_EQ(newMetricProducers[event2Index]->mConditionTrackerIndex, predicate1Index);
    EXPECT_EQ(newMetricProducers[event2Index]->mCondition, ConditionState::kUnknown);
    EXPECT_EQ(newMetricProducers[event3Index]->getMetricId(), event3Id);
    EXPECT_EQ(newMetricProducers[event3Index]->mConditionTrackerIndex, -1);
    EXPECT_EQ(newMetricProducers[event3Index]->mCondition, ConditionState::kTrue);
    EXPECT_EQ(newMetricProducers[event4Index]->getMetricId(), event4Id);
    EXPECT_EQ(newMetricProducers[event4Index]->mConditionTrackerIndex, predicate1Index);
    EXPECT_EQ(newMetricProducers[event4Index]->mCondition, ConditionState::kUnknown);
    EXPECT_EQ(newMetricProducers[event6Index]->getMetricId(), event6Id);
    EXPECT_EQ(newMetricProducers[event6Index]->mConditionTrackerIndex, predicate2Index);
    EXPECT_EQ(newMetricProducers[event6Index]->mCondition, ConditionState::kTrue);

    sp<ConditionWizard> newConditionWizard = newMetricProducers[0]->mWizard;
    EXPECT_NE(newConditionWizard, oldConditionWizard);
    EXPECT_EQ(newConditionWizard->getStrongCount(), newMetricProducers.size() + 1);
    oldMetricProducers.clear();
    // Only reference to the old wizard should be the one in the test.
    EXPECT_EQ(oldConditionWizard->getStrongCount(), 1);
}

TEST_F(ConfigUpdateTest, TestUpdateCountMetrics) {
    StatsdConfig config;

    // Add atom matchers/predicates/states. These are mostly needed for initStatsdConfig.
    AtomMatcher matcher1 = CreateScreenTurnedOnAtomMatcher();
    int64_t matcher1Id = matcher1.id();
    *config.add_atom_matcher() = matcher1;

    AtomMatcher matcher2 = CreateScreenTurnedOffAtomMatcher();
    int64_t matcher2Id = matcher2.id();
    *config.add_atom_matcher() = matcher2;

    AtomMatcher matcher3 = CreateStartScheduledJobAtomMatcher();
    int64_t matcher3Id = matcher3.id();
    *config.add_atom_matcher() = matcher3;

    AtomMatcher matcher4 = CreateFinishScheduledJobAtomMatcher();
    int64_t matcher4Id = matcher4.id();
    *config.add_atom_matcher() = matcher4;

    AtomMatcher matcher5 = CreateBatterySaverModeStartAtomMatcher();
    int64_t matcher5Id = matcher5.id();
    *config.add_atom_matcher() = matcher5;

    Predicate predicate1 = CreateScreenIsOnPredicate();
    int64_t predicate1Id = predicate1.id();
    *config.add_predicate() = predicate1;

    State state1 = CreateScreenStateWithOnOffMap(0x123, 0x321);
    int64_t state1Id = state1.id();
    *config.add_state() = state1;

    State state2 = CreateScreenState();
    int64_t state2Id = state2.id();
    *config.add_state() = state2;

    // Add a few count metrics.
    // Will be preserved.
    CountMetric count1 = createCountMetric("COUNT1", matcher1Id, predicate1Id, {state1Id});
    int64_t count1Id = count1.id();
    *config.add_count_metric() = count1;

    // Will be replaced.
    CountMetric count2 = createCountMetric("COUNT2", matcher2Id, nullopt, {});
    int64_t count2Id = count2.id();
    *config.add_count_metric() = count2;

    // Will be replaced.
    CountMetric count3 = createCountMetric("COUNT3", matcher3Id, nullopt, {});
    int64_t count3Id = count3.id();
    *config.add_count_metric() = count3;

    // Will be replaced.
    CountMetric count4 = createCountMetric("COUNT4", matcher4Id, nullopt, {state2Id});
    int64_t count4Id = count4.id();
    *config.add_count_metric() = count4;

    // Will be deleted.
    CountMetric count5 = createCountMetric("COUNT5", matcher5Id, nullopt, {});
    int64_t count5Id = count5.id();
    *config.add_count_metric() = count5;

    EXPECT_TRUE(initConfig(config));

    // Change bucket size of count2, causing it to be replaced.
    count2.set_bucket(ONE_HOUR);

    // Mark matcher 3 as replaced. Causes count3 to be replaced.
    set<int64_t> replacedMatchers;
    replacedMatchers.insert(matcher3Id);

    // Mark state 2 as replaced and change the state to be about a different atom.
    // Causes count4 to be replaced.
    set<int64_t> replacedStates;
    replacedStates.insert(state2Id);
    state2.set_atom_id(util::BATTERY_SAVER_MODE_STATE_CHANGED);

    // Fake that predicate 1 is true for count metric 1.
    ASSERT_EQ(oldMetricProducers[0]->getMetricId(), count1Id);
    oldMetricProducers[0]->onConditionChanged(true, /*timestamp=*/0);
    EXPECT_EQ(oldMetricProducers[0]->mCondition, ConditionState::kTrue);

    EXPECT_EQ(StateManager::getInstance().getStateTrackersCount(), 1);
    // Tell the StateManager that the screen is on.
    unique_ptr<LogEvent> event =
            CreateScreenStateChangedEvent(0, android::view::DisplayStateEnum::DISPLAY_STATE_ON);
    StateManager::getInstance().onLogEvent(*event);

    // New count metric. Should have an initial condition of true since it depends on predicate1.
    CountMetric count6 = createCountMetric("EVENT6", matcher2Id, predicate1Id, {state1Id});
    int64_t count6Id = count6.id();

    // Map the matchers and predicates in reverse order to force the indices to change.
    std::unordered_map<int64_t, int> newAtomMatchingTrackerMap;
    const int matcher5Index = 0;
    newAtomMatchingTrackerMap[matcher5Id] = 0;
    const int matcher4Index = 1;
    newAtomMatchingTrackerMap[matcher4Id] = 1;
    const int matcher3Index = 2;
    newAtomMatchingTrackerMap[matcher3Id] = 2;
    const int matcher2Index = 3;
    newAtomMatchingTrackerMap[matcher2Id] = 3;
    const int matcher1Index = 4;
    newAtomMatchingTrackerMap[matcher1Id] = 4;
    // Use the existing matchers. A bit hacky, but saves code and we don't rely on them.
    vector<sp<AtomMatchingTracker>> newAtomMatchingTrackers(5);
    std::reverse_copy(oldAtomMatchingTrackers.begin(), oldAtomMatchingTrackers.end(),
                      newAtomMatchingTrackers.begin());

    std::unordered_map<int64_t, int> newConditionTrackerMap;
    const int predicate1Index = 0;
    newConditionTrackerMap[predicate1Id] = 0;
    // Use the existing conditionTrackers. A bit hacky, but saves code and we don't rely on them.
    vector<sp<ConditionTracker>> newConditionTrackers(1);
    std::reverse_copy(oldConditionTrackers.begin(), oldConditionTrackers.end(),
                      newConditionTrackers.begin());
    // Fake that predicate1 is true for all new metrics.
    vector<ConditionState> conditionCache = {ConditionState::kTrue};

    StatsdConfig newConfig;
    *newConfig.add_count_metric() = count6;
    const int count6Index = 0;
    *newConfig.add_count_metric() = count3;
    const int count3Index = 1;
    *newConfig.add_count_metric() = count1;
    const int count1Index = 2;
    *newConfig.add_count_metric() = count4;
    const int count4Index = 3;
    *newConfig.add_count_metric() = count2;
    const int count2Index = 4;

    *newConfig.add_state() = state1;
    *newConfig.add_state() = state2;

    unordered_map<int64_t, int> stateAtomIdMap;
    unordered_map<int64_t, unordered_map<int, int64_t>> allStateGroupMaps;
    map<int64_t, uint64_t> stateProtoHashes;
    EXPECT_TRUE(initStates(newConfig, stateAtomIdMap, allStateGroupMaps, stateProtoHashes));
    EXPECT_EQ(stateAtomIdMap[state2Id], util::BATTERY_SAVER_MODE_STATE_CHANGED);

    // Output data structures to validate.
    unordered_map<int64_t, int> newMetricProducerMap;
    vector<sp<MetricProducer>> newMetricProducers;
    unordered_map<int, vector<int>> conditionToMetricMap;
    unordered_map<int, vector<int>> trackerToMetricMap;
    set<int64_t> noReportMetricIds;
    unordered_map<int, vector<int>> activationAtomTrackerToMetricMap;
    unordered_map<int, vector<int>> deactivationAtomTrackerToMetricMap;
    vector<int> metricsWithActivation;
    EXPECT_TRUE(updateMetrics(
            key, newConfig, /*timeBaseNs=*/123, /*currentTimeNs=*/12345, new StatsPullerManager(),
            oldAtomMatchingTrackerMap, newAtomMatchingTrackerMap, replacedMatchers,
            newAtomMatchingTrackers, newConditionTrackerMap, /*replacedConditions=*/{},
            newConditionTrackers, conditionCache, stateAtomIdMap, allStateGroupMaps, replacedStates,
            oldMetricProducerMap, oldMetricProducers, newMetricProducerMap, newMetricProducers,
            conditionToMetricMap, trackerToMetricMap, noReportMetricIds,
            activationAtomTrackerToMetricMap, deactivationAtomTrackerToMetricMap,
            metricsWithActivation));

    unordered_map<int64_t, int> expectedMetricProducerMap = {
            {count1Id, count1Index}, {count2Id, count2Index}, {count3Id, count3Index},
            {count4Id, count4Index}, {count6Id, count6Index},
    };
    EXPECT_THAT(newMetricProducerMap, ContainerEq(expectedMetricProducerMap));

    // Make sure preserved metrics are the same.
    ASSERT_EQ(newMetricProducers.size(), 5);
    EXPECT_EQ(oldMetricProducers[oldMetricProducerMap.at(count1Id)],
              newMetricProducers[newMetricProducerMap.at(count1Id)]);

    // Make sure replaced metrics are different.
    EXPECT_NE(oldMetricProducers[oldMetricProducerMap.at(count2Id)],
              newMetricProducers[newMetricProducerMap.at(count2Id)]);
    EXPECT_NE(oldMetricProducers[oldMetricProducerMap.at(count3Id)],
              newMetricProducers[newMetricProducerMap.at(count3Id)]);
    EXPECT_NE(oldMetricProducers[oldMetricProducerMap.at(count4Id)],
              newMetricProducers[newMetricProducerMap.at(count4Id)]);

    // Verify the conditionToMetricMap.
    ASSERT_EQ(conditionToMetricMap.size(), 1);
    const vector<int>& condition1Metrics = conditionToMetricMap[predicate1Index];
    EXPECT_THAT(condition1Metrics, UnorderedElementsAre(count1Index, count6Index));

    // Verify the trackerToMetricMap.
    ASSERT_EQ(trackerToMetricMap.size(), 4);
    const vector<int>& matcher1Metrics = trackerToMetricMap[matcher1Index];
    EXPECT_THAT(matcher1Metrics, UnorderedElementsAre(count1Index));
    const vector<int>& matcher2Metrics = trackerToMetricMap[matcher2Index];
    EXPECT_THAT(matcher2Metrics, UnorderedElementsAre(count2Index, count6Index));
    const vector<int>& matcher3Metrics = trackerToMetricMap[matcher3Index];
    EXPECT_THAT(matcher3Metrics, UnorderedElementsAre(count3Index));
    const vector<int>& matcher4Metrics = trackerToMetricMap[matcher4Index];
    EXPECT_THAT(matcher4Metrics, UnorderedElementsAre(count4Index));

    // Verify event activation/deactivation maps.
    ASSERT_EQ(activationAtomTrackerToMetricMap.size(), 0);
    ASSERT_EQ(deactivationAtomTrackerToMetricMap.size(), 0);
    ASSERT_EQ(metricsWithActivation.size(), 0);

    // Verify tracker indices/ids/conditions/states are correct.
    EXPECT_EQ(newMetricProducers[count1Index]->getMetricId(), count1Id);
    EXPECT_EQ(newMetricProducers[count1Index]->mConditionTrackerIndex, predicate1Index);
    EXPECT_EQ(newMetricProducers[count1Index]->mCondition, ConditionState::kTrue);
    EXPECT_THAT(newMetricProducers[count1Index]->getSlicedStateAtoms(),
                UnorderedElementsAre(util::SCREEN_STATE_CHANGED));
    EXPECT_EQ(newMetricProducers[count2Index]->getMetricId(), count2Id);
    EXPECT_EQ(newMetricProducers[count2Index]->mConditionTrackerIndex, -1);
    EXPECT_EQ(newMetricProducers[count2Index]->mCondition, ConditionState::kTrue);
    EXPECT_TRUE(newMetricProducers[count2Index]->getSlicedStateAtoms().empty());
    EXPECT_EQ(newMetricProducers[count3Index]->getMetricId(), count3Id);
    EXPECT_EQ(newMetricProducers[count3Index]->mConditionTrackerIndex, -1);
    EXPECT_EQ(newMetricProducers[count3Index]->mCondition, ConditionState::kTrue);
    EXPECT_TRUE(newMetricProducers[count3Index]->getSlicedStateAtoms().empty());
    EXPECT_EQ(newMetricProducers[count4Index]->getMetricId(), count4Id);
    EXPECT_EQ(newMetricProducers[count4Index]->mConditionTrackerIndex, -1);
    EXPECT_EQ(newMetricProducers[count4Index]->mCondition, ConditionState::kTrue);
    EXPECT_THAT(newMetricProducers[count4Index]->getSlicedStateAtoms(),
                UnorderedElementsAre(util::BATTERY_SAVER_MODE_STATE_CHANGED));
    EXPECT_EQ(newMetricProducers[count6Index]->getMetricId(), count6Id);
    EXPECT_EQ(newMetricProducers[count6Index]->mConditionTrackerIndex, predicate1Index);
    EXPECT_EQ(newMetricProducers[count6Index]->mCondition, ConditionState::kTrue);
    EXPECT_THAT(newMetricProducers[count6Index]->getSlicedStateAtoms(),
                UnorderedElementsAre(util::SCREEN_STATE_CHANGED));

    oldMetricProducers.clear();
    // Ensure that the screen state StateTracker did not get deleted and replaced.
    EXPECT_EQ(StateManager::getInstance().getStateTrackersCount(), 2);
    FieldValue screenState;
    StateManager::getInstance().getStateValue(util::SCREEN_STATE_CHANGED, DEFAULT_DIMENSION_KEY,
                                              &screenState);
    EXPECT_EQ(screenState.mValue.int_value, android::view::DisplayStateEnum::DISPLAY_STATE_ON);
}

TEST_F(ConfigUpdateTest, TestUpdateGaugeMetrics) {
    StatsdConfig config;

    // Add atom matchers/predicates/states. These are mostly needed for initStatsdConfig.
    AtomMatcher matcher1 = CreateScreenTurnedOnAtomMatcher();
    int64_t matcher1Id = matcher1.id();
    *config.add_atom_matcher() = matcher1;

    AtomMatcher matcher2 = CreateScreenTurnedOffAtomMatcher();
    int64_t matcher2Id = matcher2.id();
    *config.add_atom_matcher() = matcher2;

    AtomMatcher matcher3 = CreateStartScheduledJobAtomMatcher();
    int64_t matcher3Id = matcher3.id();
    *config.add_atom_matcher() = matcher3;

    AtomMatcher matcher4 = CreateTemperatureAtomMatcher();
    int64_t matcher4Id = matcher4.id();
    *config.add_atom_matcher() = matcher4;

    AtomMatcher matcher5 = CreateSimpleAtomMatcher("SubsystemSleep", util::SUBSYSTEM_SLEEP_STATE);
    int64_t matcher5Id = matcher5.id();
    *config.add_atom_matcher() = matcher5;

    Predicate predicate1 = CreateScreenIsOnPredicate();
    int64_t predicate1Id = predicate1.id();
    *config.add_predicate() = predicate1;

    // Add a few gauge metrics.
    // Will be preserved.
    GaugeMetric gauge1 = createGaugeMetric("GAUGE1", matcher4Id, GaugeMetric::FIRST_N_SAMPLES,
                                           predicate1Id, matcher1Id);
    int64_t gauge1Id = gauge1.id();
    *config.add_gauge_metric() = gauge1;

    // Will be replaced.
    GaugeMetric gauge2 =
            createGaugeMetric("GAUGE2", matcher1Id, GaugeMetric::FIRST_N_SAMPLES, nullopt, nullopt);
    int64_t gauge2Id = gauge2.id();
    *config.add_gauge_metric() = gauge2;

    // Will be replaced.
    GaugeMetric gauge3 = createGaugeMetric("GAUGE3", matcher5Id, GaugeMetric::FIRST_N_SAMPLES,
                                           nullopt, matcher3Id);
    int64_t gauge3Id = gauge3.id();
    *config.add_gauge_metric() = gauge3;

    // Will be replaced.
    GaugeMetric gauge4 = createGaugeMetric("GAUGE4", matcher3Id, GaugeMetric::RANDOM_ONE_SAMPLE,
                                           predicate1Id, nullopt);
    int64_t gauge4Id = gauge4.id();
    *config.add_gauge_metric() = gauge4;

    // Will be deleted.
    GaugeMetric gauge5 =
            createGaugeMetric("GAUGE5", matcher2Id, GaugeMetric::RANDOM_ONE_SAMPLE, nullopt, {});
    int64_t gauge5Id = gauge5.id();
    *config.add_gauge_metric() = gauge5;

    EXPECT_TRUE(initConfig(config));

    // Used later to ensure the condition wizard is replaced. Get it before doing the update.
    sp<EventMatcherWizard> oldMatcherWizard =
            static_cast<GaugeMetricProducer*>(oldMetricProducers[0].get())->mEventMatcherWizard;
    EXPECT_EQ(oldMatcherWizard->getStrongCount(), 6);

    // Change gauge2, causing it to be replaced.
    gauge2.set_max_num_gauge_atoms_per_bucket(50);

    // Mark matcher 3 as replaced. Causes gauge3 and gauge4 to be replaced.
    set<int64_t> replacedMatchers = {matcher3Id};

    // New gauge metric.
    GaugeMetric gauge6 = createGaugeMetric("GAUGE6", matcher5Id, GaugeMetric::FIRST_N_SAMPLES,
                                           predicate1Id, matcher3Id);
    int64_t gauge6Id = gauge6.id();

    // Map the matchers and predicates in reverse order to force the indices to change.
    std::unordered_map<int64_t, int> newAtomMatchingTrackerMap;
    const int matcher5Index = 0;
    newAtomMatchingTrackerMap[matcher5Id] = 0;
    const int matcher4Index = 1;
    newAtomMatchingTrackerMap[matcher4Id] = 1;
    const int matcher3Index = 2;
    newAtomMatchingTrackerMap[matcher3Id] = 2;
    const int matcher2Index = 3;
    newAtomMatchingTrackerMap[matcher2Id] = 3;
    const int matcher1Index = 4;
    newAtomMatchingTrackerMap[matcher1Id] = 4;
    // Use the existing matchers. A bit hacky, but saves code and we don't rely on them.
    vector<sp<AtomMatchingTracker>> newAtomMatchingTrackers(5);
    std::reverse_copy(oldAtomMatchingTrackers.begin(), oldAtomMatchingTrackers.end(),
                      newAtomMatchingTrackers.begin());

    std::unordered_map<int64_t, int> newConditionTrackerMap;
    const int predicate1Index = 0;
    newConditionTrackerMap[predicate1Id] = 0;
    // Use the existing conditionTrackers. A bit hacky, but saves code and we don't rely on them.
    vector<sp<ConditionTracker>> newConditionTrackers(1);
    std::reverse_copy(oldConditionTrackers.begin(), oldConditionTrackers.end(),
                      newConditionTrackers.begin());
    // Say that predicate1 is unknown since the initial condition never changed.
    vector<ConditionState> conditionCache = {ConditionState::kUnknown};

    StatsdConfig newConfig;
    *newConfig.add_gauge_metric() = gauge6;
    const int gauge6Index = 0;
    *newConfig.add_gauge_metric() = gauge3;
    const int gauge3Index = 1;
    *newConfig.add_gauge_metric() = gauge1;
    const int gauge1Index = 2;
    *newConfig.add_gauge_metric() = gauge4;
    const int gauge4Index = 3;
    *newConfig.add_gauge_metric() = gauge2;
    const int gauge2Index = 4;

    // Output data structures to validate.
    unordered_map<int64_t, int> newMetricProducerMap;
    vector<sp<MetricProducer>> newMetricProducers;
    unordered_map<int, vector<int>> conditionToMetricMap;
    unordered_map<int, vector<int>> trackerToMetricMap;
    set<int64_t> noReportMetricIds;
    unordered_map<int, vector<int>> activationAtomTrackerToMetricMap;
    unordered_map<int, vector<int>> deactivationAtomTrackerToMetricMap;
    vector<int> metricsWithActivation;
    EXPECT_TRUE(updateMetrics(
            key, newConfig, /*timeBaseNs=*/123, /*currentTimeNs=*/12345, new StatsPullerManager(),
            oldAtomMatchingTrackerMap, newAtomMatchingTrackerMap, replacedMatchers,
            newAtomMatchingTrackers, newConditionTrackerMap, /*replacedConditions=*/{},
            newConditionTrackers, conditionCache, /*stateAtomIdMap=*/{}, /*allStateGroupMaps=*/{},
            /*replacedStates=*/{}, oldMetricProducerMap, oldMetricProducers, newMetricProducerMap,
            newMetricProducers, conditionToMetricMap, trackerToMetricMap, noReportMetricIds,
            activationAtomTrackerToMetricMap, deactivationAtomTrackerToMetricMap,
            metricsWithActivation));

    unordered_map<int64_t, int> expectedMetricProducerMap = {
            {gauge1Id, gauge1Index}, {gauge2Id, gauge2Index}, {gauge3Id, gauge3Index},
            {gauge4Id, gauge4Index}, {gauge6Id, gauge6Index},
    };
    EXPECT_THAT(newMetricProducerMap, ContainerEq(expectedMetricProducerMap));

    // Make sure preserved metrics are the same.
    ASSERT_EQ(newMetricProducers.size(), 5);
    EXPECT_EQ(oldMetricProducers[oldMetricProducerMap.at(gauge1Id)],
              newMetricProducers[newMetricProducerMap.at(gauge1Id)]);

    // Make sure replaced metrics are different.
    EXPECT_NE(oldMetricProducers[oldMetricProducerMap.at(gauge2Id)],
              newMetricProducers[newMetricProducerMap.at(gauge2Id)]);
    EXPECT_NE(oldMetricProducers[oldMetricProducerMap.at(gauge3Id)],
              newMetricProducers[newMetricProducerMap.at(gauge3Id)]);
    EXPECT_NE(oldMetricProducers[oldMetricProducerMap.at(gauge4Id)],
              newMetricProducers[newMetricProducerMap.at(gauge4Id)]);

    // Verify the conditionToMetricMap.
    ASSERT_EQ(conditionToMetricMap.size(), 1);
    const vector<int>& condition1Metrics = conditionToMetricMap[predicate1Index];
    EXPECT_THAT(condition1Metrics, UnorderedElementsAre(gauge1Index, gauge4Index, gauge6Index));

    // Verify the trackerToMetricMap.
    ASSERT_EQ(trackerToMetricMap.size(), 4);
    const vector<int>& matcher1Metrics = trackerToMetricMap[matcher1Index];
    EXPECT_THAT(matcher1Metrics, UnorderedElementsAre(gauge1Index, gauge2Index));
    const vector<int>& matcher3Metrics = trackerToMetricMap[matcher3Index];
    EXPECT_THAT(matcher3Metrics, UnorderedElementsAre(gauge3Index, gauge4Index, gauge6Index));
    const vector<int>& matcher4Metrics = trackerToMetricMap[matcher4Index];
    EXPECT_THAT(matcher4Metrics, UnorderedElementsAre(gauge1Index));
    const vector<int>& matcher5Metrics = trackerToMetricMap[matcher5Index];
    EXPECT_THAT(matcher5Metrics, UnorderedElementsAre(gauge3Index, gauge6Index));

    // Verify event activation/deactivation maps.
    ASSERT_EQ(activationAtomTrackerToMetricMap.size(), 0);
    ASSERT_EQ(deactivationAtomTrackerToMetricMap.size(), 0);
    ASSERT_EQ(metricsWithActivation.size(), 0);

    // Verify tracker indices/ids/conditions/states are correct.
    GaugeMetricProducer* gaugeProducer1 =
            static_cast<GaugeMetricProducer*>(newMetricProducers[gauge1Index].get());
    EXPECT_EQ(gaugeProducer1->getMetricId(), gauge1Id);
    EXPECT_EQ(gaugeProducer1->mConditionTrackerIndex, predicate1Index);
    EXPECT_EQ(gaugeProducer1->mCondition, ConditionState::kUnknown);
    EXPECT_EQ(gaugeProducer1->mWhatMatcherIndex, matcher4Index);
    GaugeMetricProducer* gaugeProducer2 =
            static_cast<GaugeMetricProducer*>(newMetricProducers[gauge2Index].get());
    EXPECT_EQ(gaugeProducer2->getMetricId(), gauge2Id);
    EXPECT_EQ(gaugeProducer2->mConditionTrackerIndex, -1);
    EXPECT_EQ(gaugeProducer2->mCondition, ConditionState::kTrue);
    EXPECT_EQ(gaugeProducer2->mWhatMatcherIndex, matcher1Index);
    GaugeMetricProducer* gaugeProducer3 =
            static_cast<GaugeMetricProducer*>(newMetricProducers[gauge3Index].get());
    EXPECT_EQ(gaugeProducer3->getMetricId(), gauge3Id);
    EXPECT_EQ(gaugeProducer3->mConditionTrackerIndex, -1);
    EXPECT_EQ(gaugeProducer3->mCondition, ConditionState::kTrue);
    EXPECT_EQ(gaugeProducer3->mWhatMatcherIndex, matcher5Index);
    GaugeMetricProducer* gaugeProducer4 =
            static_cast<GaugeMetricProducer*>(newMetricProducers[gauge4Index].get());
    EXPECT_EQ(gaugeProducer4->getMetricId(), gauge4Id);
    EXPECT_EQ(gaugeProducer4->mConditionTrackerIndex, predicate1Index);
    EXPECT_EQ(gaugeProducer4->mCondition, ConditionState::kUnknown);
    EXPECT_EQ(gaugeProducer4->mWhatMatcherIndex, matcher3Index);
    GaugeMetricProducer* gaugeProducer6 =
            static_cast<GaugeMetricProducer*>(newMetricProducers[gauge6Index].get());
    EXPECT_EQ(gaugeProducer6->getMetricId(), gauge6Id);
    EXPECT_EQ(gaugeProducer6->mConditionTrackerIndex, predicate1Index);
    EXPECT_EQ(gaugeProducer6->mCondition, ConditionState::kUnknown);
    EXPECT_EQ(gaugeProducer6->mWhatMatcherIndex, matcher5Index);

    sp<EventMatcherWizard> newMatcherWizard = gaugeProducer1->mEventMatcherWizard;
    EXPECT_NE(newMatcherWizard, oldMatcherWizard);
    EXPECT_EQ(newMatcherWizard->getStrongCount(), 6);
    oldMetricProducers.clear();
    // Only reference to the old wizard should be the one in the test.
    EXPECT_EQ(oldMatcherWizard->getStrongCount(), 1);
}

TEST_F(ConfigUpdateTest, TestUpdateDurationMetrics) {
    StatsdConfig config;
    // Add atom matchers/predicates/states. These are mostly needed for initStatsdConfig.
    AtomMatcher matcher1 = CreateScreenTurnedOnAtomMatcher();
    int64_t matcher1Id = matcher1.id();
    *config.add_atom_matcher() = matcher1;

    AtomMatcher matcher2 = CreateScreenTurnedOffAtomMatcher();
    int64_t matcher2Id = matcher2.id();
    *config.add_atom_matcher() = matcher2;

    AtomMatcher matcher3 = CreateAcquireWakelockAtomMatcher();
    int64_t matcher3Id = matcher3.id();
    *config.add_atom_matcher() = matcher3;

    AtomMatcher matcher4 = CreateReleaseWakelockAtomMatcher();
    int64_t matcher4Id = matcher4.id();
    *config.add_atom_matcher() = matcher4;

    AtomMatcher matcher5 = CreateMoveToForegroundAtomMatcher();
    int64_t matcher5Id = matcher5.id();
    *config.add_atom_matcher() = matcher5;

    AtomMatcher matcher6 = CreateMoveToBackgroundAtomMatcher();
    int64_t matcher6Id = matcher6.id();
    *config.add_atom_matcher() = matcher6;

    AtomMatcher matcher7 = CreateBatteryStateNoneMatcher();
    int64_t matcher7Id = matcher7.id();
    *config.add_atom_matcher() = matcher7;

    AtomMatcher matcher8 = CreateBatteryStateUsbMatcher();
    int64_t matcher8Id = matcher8.id();
    *config.add_atom_matcher() = matcher8;

    Predicate predicate1 = CreateScreenIsOnPredicate();
    int64_t predicate1Id = predicate1.id();
    *config.add_predicate() = predicate1;

    Predicate predicate2 = CreateScreenIsOffPredicate();
    int64_t predicate2Id = predicate2.id();
    *config.add_predicate() = predicate2;

    Predicate predicate3 = CreateDeviceUnpluggedPredicate();
    int64_t predicate3Id = predicate3.id();
    *config.add_predicate() = predicate3;

    Predicate predicate4 = CreateIsInBackgroundPredicate();
    *predicate4.mutable_simple_predicate()->mutable_dimensions() =
            CreateDimensions(util::ACTIVITY_FOREGROUND_STATE_CHANGED, {1});
    int64_t predicate4Id = predicate4.id();
    *config.add_predicate() = predicate4;

    Predicate predicate5 = CreateHoldingWakelockPredicate();
    *predicate5.mutable_simple_predicate()->mutable_dimensions() =
            CreateAttributionUidDimensions(util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    predicate5.mutable_simple_predicate()->set_stop_all(matcher7Id);
    int64_t predicate5Id = predicate5.id();
    *config.add_predicate() = predicate5;

    State state1 = CreateScreenStateWithOnOffMap(0x123, 0x321);
    int64_t state1Id = state1.id();
    *config.add_state() = state1;

    State state2 = CreateScreenState();
    int64_t state2Id = state2.id();
    *config.add_state() = state2;

    // Add a few duration metrics.
    // Will be preserved.
    DurationMetric duration1 =
            createDurationMetric("DURATION1", predicate5Id, predicate4Id, {state2Id});
    *duration1.mutable_dimensions_in_what() =
            CreateAttributionUidDimensions(util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    MetricConditionLink* link = duration1.add_links();
    link->set_condition(predicate4Id);
    *link->mutable_fields_in_what() =
            CreateAttributionUidDimensions(util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    *link->mutable_fields_in_condition() =
            CreateDimensions(util::ACTIVITY_FOREGROUND_STATE_CHANGED, {1} /*uid field*/);
    int64_t duration1Id = duration1.id();
    *config.add_duration_metric() = duration1;

    // Will be replaced.
    DurationMetric duration2 = createDurationMetric("DURATION2", predicate1Id, nullopt, {});
    int64_t duration2Id = duration2.id();
    *config.add_duration_metric() = duration2;

    // Will be replaced.
    DurationMetric duration3 = createDurationMetric("DURATION3", predicate3Id, nullopt, {state1Id});
    int64_t duration3Id = duration3.id();
    *config.add_duration_metric() = duration3;

    // Will be replaced.
    DurationMetric duration4 = createDurationMetric("DURATION4", predicate3Id, predicate2Id, {});
    int64_t duration4Id = duration4.id();
    *config.add_duration_metric() = duration4;

    // Will be deleted.
    DurationMetric duration5 = createDurationMetric("DURATION5", predicate2Id, nullopt, {});
    int64_t duration5Id = duration5.id();
    *config.add_duration_metric() = duration5;

    EXPECT_TRUE(initConfig(config));

    // Make some sliced conditions true.
    int uid1 = 10;
    int uid2 = 11;
    vector<MatchingState> matchingStates(8, MatchingState::kNotMatched);
    matchingStates[2] = kMatched;
    vector<ConditionState> conditionCache(5, ConditionState::kNotEvaluated);
    vector<bool> changedCache(5, false);
    unique_ptr<LogEvent> event = CreateAcquireWakelockEvent(timeBaseNs + 3, {uid1}, {"tag"}, "wl1");
    oldConditionTrackers[4]->evaluateCondition(*event.get(), matchingStates, oldConditionTrackers,
                                               conditionCache, changedCache);
    EXPECT_TRUE(oldConditionTrackers[4]->isSliced());
    EXPECT_TRUE(changedCache[4]);
    EXPECT_EQ(conditionCache[4], ConditionState::kTrue);
    oldMetricProducers[0]->onMatchedLogEvent(2, *event.get());

    fill(conditionCache.begin(), conditionCache.end(), ConditionState::kNotEvaluated);
    fill(changedCache.begin(), changedCache.end(), false);
    event = CreateAcquireWakelockEvent(timeBaseNs + 3, {uid2}, {"tag"}, "wl2");
    oldConditionTrackers[4]->evaluateCondition(*event.get(), matchingStates, oldConditionTrackers,
                                               conditionCache, changedCache);
    EXPECT_TRUE(changedCache[4]);
    EXPECT_EQ(conditionCache[4], ConditionState::kTrue);
    oldMetricProducers[0]->onMatchedLogEvent(2, *event.get());

    // Used later to ensure the condition wizard is replaced. Get it before doing the update.
    // The duration trackers have a pointer to the wizard, and 2 trackers were created above.
    sp<ConditionWizard> oldConditionWizard = oldMetricProducers[0]->mWizard;
    EXPECT_EQ(oldConditionWizard->getStrongCount(), 8);

    // Replace predicate1, predicate3, and state1. Causes duration2/3/4 to be replaced.
    set<int64_t> replacedConditions({predicate1Id, predicate2Id});
    set<int64_t> replacedStates({state1Id});

    // New duration metric.
    DurationMetric duration6 = createDurationMetric("DURATION6", predicate4Id, predicate5Id, {});
    *duration6.mutable_dimensions_in_what() =
            CreateDimensions(util::ACTIVITY_FOREGROUND_STATE_CHANGED, {1} /*uid field*/);
    link = duration6.add_links();
    link->set_condition(predicate5Id);
    *link->mutable_fields_in_what() =
            CreateDimensions(util::ACTIVITY_FOREGROUND_STATE_CHANGED, {1} /*uid field*/);
    *link->mutable_fields_in_condition() =
            CreateAttributionUidDimensions(util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    int64_t duration6Id = duration6.id();

    // Map the matchers and predicates in reverse order to force the indices to change.
    const int matcher8Index = 0, matcher7Index = 1, matcher6Index = 2, matcher5Index = 3,
              matcher4Index = 4, matcher3Index = 5, matcher2Index = 6, matcher1Index = 7;
    std::unordered_map<int64_t, int> newAtomMatchingTrackerMap({{matcher8Id, matcher8Index},
                                                                {matcher7Id, matcher7Index},
                                                                {matcher6Id, matcher6Index},
                                                                {matcher5Id, matcher5Index},
                                                                {matcher4Id, matcher4Index},
                                                                {matcher3Id, matcher3Index},
                                                                {matcher2Id, matcher2Index},
                                                                {matcher1Id, matcher1Index}});
    // Use the existing matchers. A bit hacky, but saves code and we don't rely on them.
    vector<sp<AtomMatchingTracker>> newAtomMatchingTrackers(8);
    reverse_copy(oldAtomMatchingTrackers.begin(), oldAtomMatchingTrackers.end(),
                 newAtomMatchingTrackers.begin());

    const int predicate5Index = 0, predicate4Index = 1, predicate3Index = 2, predicate2Index = 3,
              predicate1Index = 4;
    std::unordered_map<int64_t, int> newConditionTrackerMap({
            {predicate5Id, predicate5Index},
            {predicate4Id, predicate4Index},
            {predicate3Id, predicate3Index},
            {predicate2Id, predicate2Index},
            {predicate1Id, predicate1Index},
    });
    // Use the existing conditionTrackers and reinitialize them to get the initial condition cache.
    vector<sp<ConditionTracker>> newConditionTrackers(5);
    reverse_copy(oldConditionTrackers.begin(), oldConditionTrackers.end(),
                 newConditionTrackers.begin());
    vector<Predicate> conditionProtos(5);
    reverse_copy(config.predicate().begin(), config.predicate().end(), conditionProtos.begin());
    for (int i = 0; i < newConditionTrackers.size(); i++) {
        EXPECT_TRUE(newConditionTrackers[i]->onConfigUpdated(
                conditionProtos, i, newConditionTrackers, newAtomMatchingTrackerMap,
                newConditionTrackerMap));
    }
    vector<bool> cycleTracker(5, false);
    fill(conditionCache.begin(), conditionCache.end(), ConditionState::kNotEvaluated);
    for (int i = 0; i < newConditionTrackers.size(); i++) {
        EXPECT_TRUE(newConditionTrackers[i]->init(conditionProtos, newConditionTrackers,
                                                  newConditionTrackerMap, cycleTracker,
                                                  conditionCache));
    }
    // Predicate5 should be true since 2 uids have wakelocks
    EXPECT_EQ(conditionCache, vector({kTrue, kUnknown, kUnknown, kUnknown, kUnknown}));

    StatsdConfig newConfig;
    *newConfig.add_duration_metric() = duration6;
    const int duration6Index = 0;
    *newConfig.add_duration_metric() = duration3;
    const int duration3Index = 1;
    *newConfig.add_duration_metric() = duration1;
    const int duration1Index = 2;
    *newConfig.add_duration_metric() = duration4;
    const int duration4Index = 3;
    *newConfig.add_duration_metric() = duration2;
    const int duration2Index = 4;

    for (const Predicate& predicate : conditionProtos) {
        *newConfig.add_predicate() = predicate;
    }
    *newConfig.add_state() = state1;
    *newConfig.add_state() = state2;
    unordered_map<int64_t, int> stateAtomIdMap;
    unordered_map<int64_t, unordered_map<int, int64_t>> allStateGroupMaps;
    map<int64_t, uint64_t> stateProtoHashes;
    EXPECT_TRUE(initStates(newConfig, stateAtomIdMap, allStateGroupMaps, stateProtoHashes));

    // Output data structures to validate.
    unordered_map<int64_t, int> newMetricProducerMap;
    vector<sp<MetricProducer>> newMetricProducers;
    unordered_map<int, vector<int>> conditionToMetricMap;
    unordered_map<int, vector<int>> trackerToMetricMap;
    set<int64_t> noReportMetricIds;
    unordered_map<int, vector<int>> activationAtomTrackerToMetricMap;
    unordered_map<int, vector<int>> deactivationAtomTrackerToMetricMap;
    vector<int> metricsWithActivation;
    EXPECT_TRUE(updateMetrics(
            key, newConfig, /*timeBaseNs=*/123, /*currentTimeNs=*/12345, new StatsPullerManager(),
            oldAtomMatchingTrackerMap, newAtomMatchingTrackerMap, /*replacedMatchers=*/{},
            newAtomMatchingTrackers, newConditionTrackerMap, replacedConditions,
            newConditionTrackers, conditionCache, stateAtomIdMap, allStateGroupMaps, replacedStates,
            oldMetricProducerMap, oldMetricProducers, newMetricProducerMap, newMetricProducers,
            conditionToMetricMap, trackerToMetricMap, noReportMetricIds,
            activationAtomTrackerToMetricMap, deactivationAtomTrackerToMetricMap,
            metricsWithActivation));

    unordered_map<int64_t, int> expectedMetricProducerMap = {
            {duration1Id, duration1Index}, {duration2Id, duration2Index},
            {duration3Id, duration3Index}, {duration4Id, duration4Index},
            {duration6Id, duration6Index},
    };
    EXPECT_THAT(newMetricProducerMap, ContainerEq(expectedMetricProducerMap));

    // Make sure preserved metrics are the same.
    ASSERT_EQ(newMetricProducers.size(), 5);
    EXPECT_EQ(oldMetricProducers[oldMetricProducerMap.at(duration1Id)],
              newMetricProducers[newMetricProducerMap.at(duration1Id)]);

    // Make sure replaced metrics are different.
    EXPECT_NE(oldMetricProducers[oldMetricProducerMap.at(duration2Id)],
              newMetricProducers[newMetricProducerMap.at(duration2Id)]);
    EXPECT_NE(oldMetricProducers[oldMetricProducerMap.at(duration3Id)],
              newMetricProducers[newMetricProducerMap.at(duration3Id)]);
    EXPECT_NE(oldMetricProducers[oldMetricProducerMap.at(duration4Id)],
              newMetricProducers[newMetricProducerMap.at(duration4Id)]);

    // Verify the conditionToMetricMap. Note that the "what" is not in this map.
    ASSERT_EQ(conditionToMetricMap.size(), 3);
    const vector<int>& condition2Metrics = conditionToMetricMap[predicate2Index];
    EXPECT_THAT(condition2Metrics, UnorderedElementsAre(duration4Index));
    const vector<int>& condition4Metrics = conditionToMetricMap[predicate4Index];
    EXPECT_THAT(condition4Metrics, UnorderedElementsAre(duration1Index));
    const vector<int>& condition5Metrics = conditionToMetricMap[predicate5Index];
    EXPECT_THAT(condition5Metrics, UnorderedElementsAre(duration6Index));

    // Verify the trackerToMetricMap. The start/stop/stopall indices from the "what" should be here.
    ASSERT_EQ(trackerToMetricMap.size(), 8);
    const vector<int>& matcher1Metrics = trackerToMetricMap[matcher1Index];
    EXPECT_THAT(matcher1Metrics, UnorderedElementsAre(duration2Index));
    const vector<int>& matcher2Metrics = trackerToMetricMap[matcher2Index];
    EXPECT_THAT(matcher2Metrics, UnorderedElementsAre(duration2Index));
    const vector<int>& matcher3Metrics = trackerToMetricMap[matcher3Index];
    EXPECT_THAT(matcher3Metrics, UnorderedElementsAre(duration1Index));
    const vector<int>& matcher4Metrics = trackerToMetricMap[matcher4Index];
    EXPECT_THAT(matcher4Metrics, UnorderedElementsAre(duration1Index));
    const vector<int>& matcher5Metrics = trackerToMetricMap[matcher5Index];
    EXPECT_THAT(matcher5Metrics, UnorderedElementsAre(duration6Index));
    const vector<int>& matcher6Metrics = trackerToMetricMap[matcher6Index];
    EXPECT_THAT(matcher6Metrics, UnorderedElementsAre(duration6Index));
    const vector<int>& matcher7Metrics = trackerToMetricMap[matcher7Index];
    EXPECT_THAT(matcher7Metrics,
                UnorderedElementsAre(duration1Index, duration3Index, duration4Index));
    const vector<int>& matcher8Metrics = trackerToMetricMap[matcher8Index];
    EXPECT_THAT(matcher8Metrics, UnorderedElementsAre(duration3Index, duration4Index));

    // Verify event activation/deactivation maps.
    ASSERT_EQ(activationAtomTrackerToMetricMap.size(), 0);
    ASSERT_EQ(deactivationAtomTrackerToMetricMap.size(), 0);
    ASSERT_EQ(metricsWithActivation.size(), 0);

    // Verify tracker indices/ids/conditions are correct.
    DurationMetricProducer* durationProducer1 =
            static_cast<DurationMetricProducer*>(newMetricProducers[duration1Index].get());
    EXPECT_EQ(durationProducer1->getMetricId(), duration1Id);
    EXPECT_EQ(durationProducer1->mConditionTrackerIndex, predicate4Index);
    EXPECT_EQ(durationProducer1->mCondition, ConditionState::kUnknown);
    EXPECT_EQ(durationProducer1->mStartIndex, matcher3Index);
    EXPECT_EQ(durationProducer1->mStopIndex, matcher4Index);
    EXPECT_EQ(durationProducer1->mStopAllIndex, matcher7Index);
    EXPECT_EQ(durationProducer1->mCurrentSlicedDurationTrackerMap.size(), 2);
    for (const auto& durationTrackerIt : durationProducer1->mCurrentSlicedDurationTrackerMap) {
        EXPECT_EQ(durationTrackerIt.second->mConditionTrackerIndex, predicate4Index);
    }
    DurationMetricProducer* durationProducer2 =
            static_cast<DurationMetricProducer*>(newMetricProducers[duration2Index].get());
    EXPECT_EQ(durationProducer2->getMetricId(), duration2Id);
    EXPECT_EQ(durationProducer2->mConditionTrackerIndex, -1);
    EXPECT_EQ(durationProducer2->mCondition, ConditionState::kTrue);
    EXPECT_EQ(durationProducer2->mStartIndex, matcher1Index);
    EXPECT_EQ(durationProducer2->mStopIndex, matcher2Index);
    EXPECT_EQ(durationProducer2->mStopAllIndex, -1);
    DurationMetricProducer* durationProducer3 =
            static_cast<DurationMetricProducer*>(newMetricProducers[duration3Index].get());
    EXPECT_EQ(durationProducer3->getMetricId(), duration3Id);
    EXPECT_EQ(durationProducer3->mConditionTrackerIndex, -1);
    EXPECT_EQ(durationProducer3->mCondition, ConditionState::kTrue);
    EXPECT_EQ(durationProducer3->mStartIndex, matcher7Index);
    EXPECT_EQ(durationProducer3->mStopIndex, matcher8Index);
    EXPECT_EQ(durationProducer3->mStopAllIndex, -1);
    DurationMetricProducer* durationProducer4 =
            static_cast<DurationMetricProducer*>(newMetricProducers[duration4Index].get());
    EXPECT_EQ(durationProducer4->getMetricId(), duration4Id);
    EXPECT_EQ(durationProducer4->mConditionTrackerIndex, predicate2Index);
    EXPECT_EQ(durationProducer4->mCondition, ConditionState::kUnknown);
    EXPECT_EQ(durationProducer4->mStartIndex, matcher7Index);
    EXPECT_EQ(durationProducer4->mStopIndex, matcher8Index);
    EXPECT_EQ(durationProducer4->mStopAllIndex, -1);
    DurationMetricProducer* durationProducer6 =
            static_cast<DurationMetricProducer*>(newMetricProducers[duration6Index].get());
    EXPECT_EQ(durationProducer6->getMetricId(), duration6Id);
    EXPECT_EQ(durationProducer6->mConditionTrackerIndex, predicate5Index);
    // TODO(b/167491517): should this be unknown since the condition is sliced?
    EXPECT_EQ(durationProducer6->mCondition, ConditionState::kTrue);
    EXPECT_EQ(durationProducer6->mStartIndex, matcher6Index);
    EXPECT_EQ(durationProducer6->mStopIndex, matcher5Index);
    EXPECT_EQ(durationProducer6->mStopAllIndex, -1);

    sp<ConditionWizard> newConditionWizard = newMetricProducers[0]->mWizard;
    EXPECT_NE(newConditionWizard, oldConditionWizard);
    EXPECT_EQ(newConditionWizard->getStrongCount(), 8);
    oldMetricProducers.clear();
    // Only reference to the old wizard should be the one in the test.
    EXPECT_EQ(oldConditionWizard->getStrongCount(), 1);
}

TEST_F(ConfigUpdateTest, TestUpdateValueMetrics) {
    StatsdConfig config;

    // Add atom matchers/predicates/states. These are mostly needed for initStatsdConfig.
    AtomMatcher matcher1 = CreateScreenTurnedOnAtomMatcher();
    int64_t matcher1Id = matcher1.id();
    *config.add_atom_matcher() = matcher1;

    AtomMatcher matcher2 = CreateScreenTurnedOffAtomMatcher();
    int64_t matcher2Id = matcher2.id();
    *config.add_atom_matcher() = matcher2;

    AtomMatcher matcher3 = CreateStartScheduledJobAtomMatcher();
    int64_t matcher3Id = matcher3.id();
    *config.add_atom_matcher() = matcher3;

    AtomMatcher matcher4 = CreateTemperatureAtomMatcher();
    int64_t matcher4Id = matcher4.id();
    *config.add_atom_matcher() = matcher4;

    AtomMatcher matcher5 = CreateSimpleAtomMatcher("SubsystemSleep", util::SUBSYSTEM_SLEEP_STATE);
    int64_t matcher5Id = matcher5.id();
    *config.add_atom_matcher() = matcher5;

    Predicate predicate1 = CreateScreenIsOnPredicate();
    int64_t predicate1Id = predicate1.id();
    *config.add_predicate() = predicate1;

    Predicate predicate2 = CreateScreenIsOffPredicate();
    int64_t predicate2Id = predicate2.id();
    *config.add_predicate() = predicate2;

    State state1 = CreateScreenStateWithOnOffMap(0x123, 0x321);
    int64_t state1Id = state1.id();
    *config.add_state() = state1;

    State state2 = CreateScreenState();
    int64_t state2Id = state2.id();
    *config.add_state() = state2;

    // Add a few value metrics.
    // Note that these will not work as "real" metrics since the value field is always 2.
    // Will be preserved.
    ValueMetric value1 = createValueMetric("VALUE1", matcher4, predicate1Id, {state1Id});
    int64_t value1Id = value1.id();
    *config.add_value_metric() = value1;

    // Will be replaced - definition change.
    ValueMetric value2 = createValueMetric("VALUE2", matcher1, nullopt, {});
    int64_t value2Id = value2.id();
    *config.add_value_metric() = value2;

    // Will be replaced - condition change.
    ValueMetric value3 = createValueMetric("VALUE3", matcher5, predicate2Id, {});
    int64_t value3Id = value3.id();
    *config.add_value_metric() = value3;

    // Will be replaced - state change.
    ValueMetric value4 = createValueMetric("VALUE4", matcher3, nullopt, {state2Id});
    int64_t value4Id = value4.id();
    *config.add_value_metric() = value4;

    // Will be deleted.
    ValueMetric value5 = createValueMetric("VALUE5", matcher2, nullopt, {});
    int64_t value5Id = value5.id();
    *config.add_value_metric() = value5;

    EXPECT_TRUE(initConfig(config));

    // Used later to ensure the condition wizard is replaced. Get it before doing the update.
    sp<EventMatcherWizard> oldMatcherWizard =
            static_cast<ValueMetricProducer*>(oldMetricProducers[0].get())->mEventMatcherWizard;
    EXPECT_EQ(oldMatcherWizard->getStrongCount(), 6);

    // Change value2, causing it to be replaced.
    value2.set_aggregation_type(ValueMetric::AVG);

    // Mark predicate 2 as replaced. Causes value3 to be replaced.
    set<int64_t> replacedConditions = {predicate2Id};

    // Mark state 2 as replaced. Causes value4 to be replaced.
    set<int64_t> replacedStates = {state2Id};

    // New value metric.
    ValueMetric value6 = createValueMetric("VALUE6", matcher5, predicate1Id, {state1Id});
    int64_t value6Id = value6.id();

    // Map the matchers and predicates in reverse order to force the indices to change.
    std::unordered_map<int64_t, int> newAtomMatchingTrackerMap;
    const int matcher5Index = 0;
    newAtomMatchingTrackerMap[matcher5Id] = 0;
    const int matcher4Index = 1;
    newAtomMatchingTrackerMap[matcher4Id] = 1;
    const int matcher3Index = 2;
    newAtomMatchingTrackerMap[matcher3Id] = 2;
    const int matcher2Index = 3;
    newAtomMatchingTrackerMap[matcher2Id] = 3;
    const int matcher1Index = 4;
    newAtomMatchingTrackerMap[matcher1Id] = 4;
    // Use the existing matchers. A bit hacky, but saves code and we don't rely on them.
    vector<sp<AtomMatchingTracker>> newAtomMatchingTrackers(5);
    std::reverse_copy(oldAtomMatchingTrackers.begin(), oldAtomMatchingTrackers.end(),
                      newAtomMatchingTrackers.begin());

    std::unordered_map<int64_t, int> newConditionTrackerMap;
    const int predicate2Index = 0;
    newConditionTrackerMap[predicate2Id] = 0;
    const int predicate1Index = 1;
    newConditionTrackerMap[predicate1Id] = 1;
    // Use the existing conditionTrackers. A bit hacky, but saves code and we don't rely on them.
    vector<sp<ConditionTracker>> newConditionTrackers(2);
    std::reverse_copy(oldConditionTrackers.begin(), oldConditionTrackers.end(),
                      newConditionTrackers.begin());
    // Say that predicate1 & predicate2 is unknown since the initial condition never changed.
    vector<ConditionState> conditionCache = {ConditionState::kUnknown, ConditionState::kUnknown};

    StatsdConfig newConfig;
    *newConfig.add_value_metric() = value6;
    const int value6Index = 0;
    *newConfig.add_value_metric() = value3;
    const int value3Index = 1;
    *newConfig.add_value_metric() = value1;
    const int value1Index = 2;
    *newConfig.add_value_metric() = value4;
    const int value4Index = 3;
    *newConfig.add_value_metric() = value2;
    const int value2Index = 4;

    *newConfig.add_state() = state1;
    *newConfig.add_state() = state2;

    unordered_map<int64_t, int> stateAtomIdMap;
    unordered_map<int64_t, unordered_map<int, int64_t>> allStateGroupMaps;
    map<int64_t, uint64_t> stateProtoHashes;
    EXPECT_TRUE(initStates(newConfig, stateAtomIdMap, allStateGroupMaps, stateProtoHashes));

    // Output data structures to validate.
    unordered_map<int64_t, int> newMetricProducerMap;
    vector<sp<MetricProducer>> newMetricProducers;
    unordered_map<int, vector<int>> conditionToMetricMap;
    unordered_map<int, vector<int>> trackerToMetricMap;
    set<int64_t> noReportMetricIds;
    unordered_map<int, vector<int>> activationAtomTrackerToMetricMap;
    unordered_map<int, vector<int>> deactivationAtomTrackerToMetricMap;
    vector<int> metricsWithActivation;
    EXPECT_TRUE(updateMetrics(
            key, newConfig, /*timeBaseNs=*/123, /*currentTimeNs=*/12345, new StatsPullerManager(),
            oldAtomMatchingTrackerMap, newAtomMatchingTrackerMap, /*replacedMatchers=*/{},
            newAtomMatchingTrackers, newConditionTrackerMap, replacedConditions,
            newConditionTrackers, conditionCache, stateAtomIdMap, allStateGroupMaps, replacedStates,
            oldMetricProducerMap, oldMetricProducers, newMetricProducerMap, newMetricProducers,
            conditionToMetricMap, trackerToMetricMap, noReportMetricIds,
            activationAtomTrackerToMetricMap, deactivationAtomTrackerToMetricMap,
            metricsWithActivation));

    unordered_map<int64_t, int> expectedMetricProducerMap = {
            {value1Id, value1Index}, {value2Id, value2Index}, {value3Id, value3Index},
            {value4Id, value4Index}, {value6Id, value6Index},
    };
    EXPECT_THAT(newMetricProducerMap, ContainerEq(expectedMetricProducerMap));

    // Make sure preserved metrics are the same.
    ASSERT_EQ(newMetricProducers.size(), 5);
    EXPECT_EQ(oldMetricProducers[oldMetricProducerMap.at(value1Id)],
              newMetricProducers[newMetricProducerMap.at(value1Id)]);

    // Make sure replaced metrics are different.
    EXPECT_NE(oldMetricProducers[oldMetricProducerMap.at(value2Id)],
              newMetricProducers[newMetricProducerMap.at(value2Id)]);
    EXPECT_NE(oldMetricProducers[oldMetricProducerMap.at(value3Id)],
              newMetricProducers[newMetricProducerMap.at(value3Id)]);
    EXPECT_NE(oldMetricProducers[oldMetricProducerMap.at(value4Id)],
              newMetricProducers[newMetricProducerMap.at(value4Id)]);

    // Verify the conditionToMetricMap.
    ASSERT_EQ(conditionToMetricMap.size(), 2);
    const vector<int>& condition1Metrics = conditionToMetricMap[predicate1Index];
    EXPECT_THAT(condition1Metrics, UnorderedElementsAre(value1Index, value6Index));
    const vector<int>& condition2Metrics = conditionToMetricMap[predicate2Index];
    EXPECT_THAT(condition2Metrics, UnorderedElementsAre(value3Index));

    // Verify the trackerToMetricMap.
    ASSERT_EQ(trackerToMetricMap.size(), 4);
    const vector<int>& matcher1Metrics = trackerToMetricMap[matcher1Index];
    EXPECT_THAT(matcher1Metrics, UnorderedElementsAre(value2Index));
    const vector<int>& matcher3Metrics = trackerToMetricMap[matcher3Index];
    EXPECT_THAT(matcher3Metrics, UnorderedElementsAre(value4Index));
    const vector<int>& matcher4Metrics = trackerToMetricMap[matcher4Index];
    EXPECT_THAT(matcher4Metrics, UnorderedElementsAre(value1Index));
    const vector<int>& matcher5Metrics = trackerToMetricMap[matcher5Index];
    EXPECT_THAT(matcher5Metrics, UnorderedElementsAre(value3Index, value6Index));

    // Verify event activation/deactivation maps.
    ASSERT_EQ(activationAtomTrackerToMetricMap.size(), 0);
    ASSERT_EQ(deactivationAtomTrackerToMetricMap.size(), 0);
    ASSERT_EQ(metricsWithActivation.size(), 0);

    // Verify tracker indices/ids/conditions/states are correct.
    ValueMetricProducer* valueProducer1 =
            static_cast<ValueMetricProducer*>(newMetricProducers[value1Index].get());
    EXPECT_EQ(valueProducer1->getMetricId(), value1Id);
    EXPECT_EQ(valueProducer1->mConditionTrackerIndex, predicate1Index);
    EXPECT_EQ(valueProducer1->mCondition, ConditionState::kUnknown);
    EXPECT_EQ(valueProducer1->mWhatMatcherIndex, matcher4Index);
    ValueMetricProducer* valueProducer2 =
            static_cast<ValueMetricProducer*>(newMetricProducers[value2Index].get());
    EXPECT_EQ(valueProducer2->getMetricId(), value2Id);
    EXPECT_EQ(valueProducer2->mConditionTrackerIndex, -1);
    EXPECT_EQ(valueProducer2->mCondition, ConditionState::kTrue);
    EXPECT_EQ(valueProducer2->mWhatMatcherIndex, matcher1Index);
    ValueMetricProducer* valueProducer3 =
            static_cast<ValueMetricProducer*>(newMetricProducers[value3Index].get());
    EXPECT_EQ(valueProducer3->getMetricId(), value3Id);
    EXPECT_EQ(valueProducer3->mConditionTrackerIndex, predicate2Index);
    EXPECT_EQ(valueProducer3->mCondition, ConditionState::kUnknown);
    EXPECT_EQ(valueProducer3->mWhatMatcherIndex, matcher5Index);
    ValueMetricProducer* valueProducer4 =
            static_cast<ValueMetricProducer*>(newMetricProducers[value4Index].get());
    EXPECT_EQ(valueProducer4->getMetricId(), value4Id);
    EXPECT_EQ(valueProducer4->mConditionTrackerIndex, -1);
    EXPECT_EQ(valueProducer4->mCondition, ConditionState::kTrue);
    EXPECT_EQ(valueProducer4->mWhatMatcherIndex, matcher3Index);
    ValueMetricProducer* valueProducer6 =
            static_cast<ValueMetricProducer*>(newMetricProducers[value6Index].get());
    EXPECT_EQ(valueProducer6->getMetricId(), value6Id);
    EXPECT_EQ(valueProducer6->mConditionTrackerIndex, predicate1Index);
    EXPECT_EQ(valueProducer6->mCondition, ConditionState::kUnknown);
    EXPECT_EQ(valueProducer6->mWhatMatcherIndex, matcher5Index);

    sp<EventMatcherWizard> newMatcherWizard = valueProducer1->mEventMatcherWizard;
    EXPECT_NE(newMatcherWizard, oldMatcherWizard);
    EXPECT_EQ(newMatcherWizard->getStrongCount(), 6);
    oldMetricProducers.clear();
    // Only reference to the old wizard should be the one in the test.
    EXPECT_EQ(oldMatcherWizard->getStrongCount(), 1);
}

TEST_F(ConfigUpdateTest, TestUpdateMetricActivations) {
    StatsdConfig config;
    // Add atom matchers
    AtomMatcher matcher1 = CreateScreenTurnedOnAtomMatcher();
    int64_t matcher1Id = matcher1.id();
    *config.add_atom_matcher() = matcher1;

    AtomMatcher matcher2 = CreateScreenTurnedOffAtomMatcher();
    int64_t matcher2Id = matcher2.id();
    *config.add_atom_matcher() = matcher2;

    AtomMatcher matcher3 = CreateStartScheduledJobAtomMatcher();
    int64_t matcher3Id = matcher3.id();
    *config.add_atom_matcher() = matcher3;

    AtomMatcher matcher4 = CreateFinishScheduledJobAtomMatcher();
    int64_t matcher4Id = matcher4.id();
    *config.add_atom_matcher() = matcher4;

    // Add an event metric with multiple activations.
    EventMetric event1 = createEventMetric("EVENT1", matcher1Id, nullopt);
    int64_t event1Id = event1.id();
    *config.add_event_metric() = event1;

    int64_t matcher2TtlSec = 2, matcher3TtlSec = 3, matcher4TtlSec = 4;
    MetricActivation metricActivation;
    metricActivation.set_metric_id(event1Id);
    EventActivation* activation = metricActivation.add_event_activation();
    activation->set_atom_matcher_id(matcher2Id);
    activation->set_ttl_seconds(matcher2TtlSec);
    activation->set_activation_type(ACTIVATE_IMMEDIATELY);
    activation->set_deactivation_atom_matcher_id(matcher1Id);
    activation = metricActivation.add_event_activation();
    activation->set_atom_matcher_id(matcher3Id);
    activation->set_ttl_seconds(matcher3TtlSec);
    activation->set_activation_type(ACTIVATE_ON_BOOT);
    activation->set_deactivation_atom_matcher_id(matcher1Id);
    activation = metricActivation.add_event_activation();
    activation->set_atom_matcher_id(matcher4Id);
    activation->set_ttl_seconds(matcher4TtlSec);
    activation->set_activation_type(ACTIVATE_IMMEDIATELY);
    activation->set_deactivation_atom_matcher_id(matcher2Id);
    *config.add_metric_activation() = metricActivation;

    EXPECT_TRUE(initConfig(config));

    // Activate some of the event activations.
    ASSERT_EQ(oldMetricProducers[0]->getMetricId(), event1Id);
    int64_t matcher2StartNs = 12345;
    oldMetricProducers[0]->activate(oldAtomMatchingTrackerMap[matcher2Id], matcher2StartNs);
    int64_t matcher3StartNs = 23456;
    oldMetricProducers[0]->activate(oldAtomMatchingTrackerMap[matcher3Id], matcher3StartNs);
    EXPECT_TRUE(oldMetricProducers[0]->isActive());

    // Map the matchers and predicates in reverse order to force the indices to change.
    std::unordered_map<int64_t, int> newAtomMatchingTrackerMap;
    const int matcher4Index = 0;
    newAtomMatchingTrackerMap[matcher4Id] = 0;
    const int matcher3Index = 1;
    newAtomMatchingTrackerMap[matcher3Id] = 1;
    const int matcher2Index = 2;
    newAtomMatchingTrackerMap[matcher2Id] = 2;
    const int matcher1Index = 3;
    newAtomMatchingTrackerMap[matcher1Id] = 3;
    // Use the existing matchers. A bit hacky, but saves code and we don't rely on them.
    vector<sp<AtomMatchingTracker>> newAtomMatchingTrackers(4);
    std::reverse_copy(oldAtomMatchingTrackers.begin(), oldAtomMatchingTrackers.end(),
                      newAtomMatchingTrackers.begin());
    set<int64_t> replacedMatchers;

    unordered_map<int64_t, int> newConditionTrackerMap;
    vector<sp<ConditionTracker>> newConditionTrackers;
    set<int64_t> replacedConditions;
    vector<ConditionState> conditionCache;
    unordered_map<int64_t, int> newMetricProducerMap;
    vector<sp<MetricProducer>> newMetricProducers;
    unordered_map<int, vector<int>> conditionToMetricMap;
    unordered_map<int, vector<int>> trackerToMetricMap;
    set<int64_t> noReportMetricIds;
    unordered_map<int, vector<int>> activationAtomTrackerToMetricMap;
    unordered_map<int, vector<int>> deactivationAtomTrackerToMetricMap;
    vector<int> metricsWithActivation;
    EXPECT_TRUE(updateMetrics(
            key, config, /*timeBaseNs=*/123, /*currentTimeNs=*/12345, new StatsPullerManager(),
            oldAtomMatchingTrackerMap, newAtomMatchingTrackerMap, replacedMatchers,
            newAtomMatchingTrackers, newConditionTrackerMap, replacedConditions,
            newConditionTrackers, conditionCache, /*stateAtomIdMap=*/{}, /*allStateGroupMaps=*/{},
            /*replacedStates=*/{}, oldMetricProducerMap, oldMetricProducers, newMetricProducerMap,
            newMetricProducers, conditionToMetricMap, trackerToMetricMap, noReportMetricIds,
            activationAtomTrackerToMetricMap, deactivationAtomTrackerToMetricMap,
            metricsWithActivation));

    // Verify event activation/deactivation maps.
    ASSERT_EQ(activationAtomTrackerToMetricMap.size(), 3);
    EXPECT_THAT(activationAtomTrackerToMetricMap[matcher2Index], UnorderedElementsAre(0));
    EXPECT_THAT(activationAtomTrackerToMetricMap[matcher3Index], UnorderedElementsAre(0));
    EXPECT_THAT(activationAtomTrackerToMetricMap[matcher4Index], UnorderedElementsAre(0));
    ASSERT_EQ(deactivationAtomTrackerToMetricMap.size(), 2);
    EXPECT_THAT(deactivationAtomTrackerToMetricMap[matcher1Index], UnorderedElementsAre(0, 0));
    EXPECT_THAT(deactivationAtomTrackerToMetricMap[matcher2Index], UnorderedElementsAre(0));
    ASSERT_EQ(metricsWithActivation.size(), 1);
    EXPECT_THAT(metricsWithActivation, UnorderedElementsAre(0));

    // Verify mEventActivation and mEventDeactivation map of the producer.
    sp<MetricProducer> producer = newMetricProducers[0];
    EXPECT_TRUE(producer->isActive());
    ASSERT_EQ(producer->mEventActivationMap.size(), 3);
    shared_ptr<Activation> matcher2Activation = producer->mEventActivationMap[matcher2Index];
    EXPECT_EQ(matcher2Activation->ttl_ns, matcher2TtlSec * NS_PER_SEC);
    EXPECT_EQ(matcher2Activation->activationType, ACTIVATE_IMMEDIATELY);
    EXPECT_EQ(matcher2Activation->state, kActive);
    EXPECT_EQ(matcher2Activation->start_ns, matcher2StartNs);
    shared_ptr<Activation> matcher3Activation = producer->mEventActivationMap[matcher3Index];
    EXPECT_EQ(matcher3Activation->ttl_ns, matcher3TtlSec * NS_PER_SEC);
    EXPECT_EQ(matcher3Activation->activationType, ACTIVATE_ON_BOOT);
    EXPECT_EQ(matcher3Activation->state, kActiveOnBoot);
    shared_ptr<Activation> matcher4Activation = producer->mEventActivationMap[matcher4Index];
    EXPECT_EQ(matcher4Activation->ttl_ns, matcher4TtlSec * NS_PER_SEC);
    EXPECT_EQ(matcher4Activation->activationType, ACTIVATE_IMMEDIATELY);
    EXPECT_EQ(matcher4Activation->state, kNotActive);

    ASSERT_EQ(producer->mEventDeactivationMap.size(), 2);
    EXPECT_THAT(producer->mEventDeactivationMap[matcher1Index],
                UnorderedElementsAre(matcher2Activation, matcher3Activation));
    EXPECT_THAT(producer->mEventDeactivationMap[matcher2Index],
                UnorderedElementsAre(matcher4Activation));
}

TEST_F(ConfigUpdateTest, TestUpdateMetricsMultipleTypes) {
    StatsdConfig config;
    // Add atom matchers/predicates/states. These are mostly needed for initStatsdConfig
    AtomMatcher matcher1 = CreateScreenTurnedOnAtomMatcher();
    int64_t matcher1Id = matcher1.id();
    *config.add_atom_matcher() = matcher1;

    AtomMatcher matcher2 = CreateScreenTurnedOffAtomMatcher();
    int64_t matcher2Id = matcher2.id();
    *config.add_atom_matcher() = matcher2;

    AtomMatcher matcher3 = CreateTemperatureAtomMatcher();
    int64_t matcher3Id = matcher3.id();
    *config.add_atom_matcher() = matcher3;

    Predicate predicate1 = CreateScreenIsOnPredicate();
    int64_t predicate1Id = predicate1.id();
    *config.add_predicate() = predicate1;

    // Add a few count metrics.
    // Will be preserved.
    CountMetric countMetric = createCountMetric("COUNT1", matcher1Id, predicate1Id, {});
    int64_t countMetricId = countMetric.id();
    *config.add_count_metric() = countMetric;

    // Will be replaced since matcher2 is replaced.
    EventMetric eventMetric = createEventMetric("EVENT1", matcher2Id, nullopt);
    int64_t eventMetricId = eventMetric.id();
    *config.add_event_metric() = eventMetric;

    // Will be replaced because the definition changes - a predicate is added.
    GaugeMetric gaugeMetric = createGaugeMetric("GAUGE1", matcher3Id,
                                                GaugeMetric::RANDOM_ONE_SAMPLE, nullopt, nullopt);
    int64_t gaugeMetricId = gaugeMetric.id();
    *config.add_gauge_metric() = gaugeMetric;

    // Preserved.
    ValueMetric valueMetric = createValueMetric("VALUE1", matcher3, predicate1Id, {});
    int64_t valueMetricId = valueMetric.id();
    *config.add_value_metric() = valueMetric;

    // Preserved.
    DurationMetric durationMetric = createDurationMetric("DURATION1", predicate1Id, nullopt, {});
    int64_t durationMetricId = durationMetric.id();
    *config.add_duration_metric() = durationMetric;

    EXPECT_TRUE(initConfig(config));

    // Used later to ensure the condition wizard is replaced. Get it before doing the update.
    sp<ConditionWizard> oldConditionWizard = oldMetricProducers[0]->mWizard;
    EXPECT_EQ(oldConditionWizard->getStrongCount(), 6);

    // Mark matcher 2 as replaced. Causes eventMetric to be replaced.
    set<int64_t> replacedMatchers;
    replacedMatchers.insert(matcher2Id);

    // Add predicate1 as a predicate on gaugeMetric, causing it to be replaced.
    gaugeMetric.set_condition(predicate1Id);

    // Map the matchers and predicates in reverse order to force the indices to change.
    std::unordered_map<int64_t, int> newAtomMatchingTrackerMap;
    const int matcher3Index = 0;
    newAtomMatchingTrackerMap[matcher3Id] = 0;
    const int matcher2Index = 1;
    newAtomMatchingTrackerMap[matcher2Id] = 1;
    const int matcher1Index = 2;
    newAtomMatchingTrackerMap[matcher1Id] = 2;
    // Use the existing matchers. A bit hacky, but saves code and we don't rely on them.
    vector<sp<AtomMatchingTracker>> newAtomMatchingTrackers(3);
    std::reverse_copy(oldAtomMatchingTrackers.begin(), oldAtomMatchingTrackers.end(),
                      newAtomMatchingTrackers.begin());

    std::unordered_map<int64_t, int> newConditionTrackerMap;
    const int predicate1Index = 0;
    newConditionTrackerMap[predicate1Id] = 0;
    // Use the existing conditionTrackers. A bit hacky, but saves code and we don't rely on them.
    vector<sp<ConditionTracker>> newConditionTrackers(1);
    std::reverse_copy(oldConditionTrackers.begin(), oldConditionTrackers.end(),
                      newConditionTrackers.begin());
    vector<ConditionState> conditionCache = {ConditionState::kUnknown};

    // The order matters. we parse in the order of: count, duration, event, value, gauge.
    StatsdConfig newConfig;
    *newConfig.add_count_metric() = countMetric;
    const int countMetricIndex = 0;
    *newConfig.add_duration_metric() = durationMetric;
    const int durationMetricIndex = 1;
    *newConfig.add_event_metric() = eventMetric;
    const int eventMetricIndex = 2;
    *newConfig.add_value_metric() = valueMetric;
    const int valueMetricIndex = 3;
    *newConfig.add_gauge_metric() = gaugeMetric;
    const int gaugeMetricIndex = 4;

    // Add the predicate since duration metric needs it.
    *newConfig.add_predicate() = predicate1;

    // Output data structures to validate.
    unordered_map<int64_t, int> newMetricProducerMap;
    vector<sp<MetricProducer>> newMetricProducers;
    unordered_map<int, vector<int>> conditionToMetricMap;
    unordered_map<int, vector<int>> trackerToMetricMap;
    set<int64_t> noReportMetricIds;
    unordered_map<int, vector<int>> activationAtomTrackerToMetricMap;
    unordered_map<int, vector<int>> deactivationAtomTrackerToMetricMap;
    vector<int> metricsWithActivation;
    EXPECT_TRUE(updateMetrics(
            key, newConfig, /*timeBaseNs=*/123, /*currentTimeNs=*/12345, new StatsPullerManager(),
            oldAtomMatchingTrackerMap, newAtomMatchingTrackerMap, replacedMatchers,
            newAtomMatchingTrackers, newConditionTrackerMap, /*replacedConditions=*/{},
            newConditionTrackers, conditionCache, /*stateAtomIdMap*/ {}, /*allStateGroupMaps=*/{},
            /*replacedStates=*/{}, oldMetricProducerMap, oldMetricProducers, newMetricProducerMap,
            newMetricProducers, conditionToMetricMap, trackerToMetricMap, noReportMetricIds,
            activationAtomTrackerToMetricMap, deactivationAtomTrackerToMetricMap,
            metricsWithActivation));

    unordered_map<int64_t, int> expectedMetricProducerMap = {
            {countMetricId, countMetricIndex}, {durationMetricId, durationMetricIndex},
            {eventMetricId, eventMetricIndex}, {valueMetricId, valueMetricIndex},
            {gaugeMetricId, gaugeMetricIndex},
    };
    EXPECT_THAT(newMetricProducerMap, ContainerEq(expectedMetricProducerMap));

    // Make sure preserved metrics are the same.
    ASSERT_EQ(newMetricProducers.size(), 5);
    EXPECT_EQ(oldMetricProducers[oldMetricProducerMap.at(countMetricId)],
              newMetricProducers[newMetricProducerMap.at(countMetricId)]);
    EXPECT_EQ(oldMetricProducers[oldMetricProducerMap.at(durationMetricId)],
              newMetricProducers[newMetricProducerMap.at(durationMetricId)]);
    EXPECT_EQ(oldMetricProducers[oldMetricProducerMap.at(valueMetricId)],
              newMetricProducers[newMetricProducerMap.at(valueMetricId)]);

    // Make sure replaced metrics are different.
    EXPECT_NE(oldMetricProducers[oldMetricProducerMap.at(eventMetricId)],
              newMetricProducers[newMetricProducerMap.at(eventMetricId)]);
    EXPECT_NE(oldMetricProducers[oldMetricProducerMap.at(gaugeMetricId)],
              newMetricProducers[newMetricProducerMap.at(gaugeMetricId)]);

    // Verify the conditionToMetricMap.
    ASSERT_EQ(conditionToMetricMap.size(), 1);
    const vector<int>& condition1Metrics = conditionToMetricMap[predicate1Index];
    EXPECT_THAT(condition1Metrics,
                UnorderedElementsAre(countMetricIndex, gaugeMetricIndex, valueMetricIndex));

    // Verify the trackerToMetricMap.
    ASSERT_EQ(trackerToMetricMap.size(), 3);
    const vector<int>& matcher1Metrics = trackerToMetricMap[matcher1Index];
    EXPECT_THAT(matcher1Metrics, UnorderedElementsAre(countMetricIndex, durationMetricIndex));
    const vector<int>& matcher2Metrics = trackerToMetricMap[matcher2Index];
    EXPECT_THAT(matcher2Metrics, UnorderedElementsAre(eventMetricIndex, durationMetricIndex));
    const vector<int>& matcher3Metrics = trackerToMetricMap[matcher3Index];
    EXPECT_THAT(matcher3Metrics, UnorderedElementsAre(gaugeMetricIndex, valueMetricIndex));

    // Verify event activation/deactivation maps.
    ASSERT_EQ(activationAtomTrackerToMetricMap.size(), 0);
    ASSERT_EQ(deactivationAtomTrackerToMetricMap.size(), 0);
    ASSERT_EQ(metricsWithActivation.size(), 0);

    // Verify tracker indices/ids/conditions are correct.
    EXPECT_EQ(newMetricProducers[countMetricIndex]->getMetricId(), countMetricId);
    EXPECT_EQ(newMetricProducers[countMetricIndex]->mConditionTrackerIndex, predicate1Index);
    EXPECT_EQ(newMetricProducers[countMetricIndex]->mCondition, ConditionState::kUnknown);
    EXPECT_EQ(newMetricProducers[durationMetricIndex]->getMetricId(), durationMetricId);
    EXPECT_EQ(newMetricProducers[durationMetricIndex]->mConditionTrackerIndex, -1);
    EXPECT_EQ(newMetricProducers[durationMetricIndex]->mCondition, ConditionState::kTrue);
    EXPECT_EQ(newMetricProducers[eventMetricIndex]->getMetricId(), eventMetricId);
    EXPECT_EQ(newMetricProducers[eventMetricIndex]->mConditionTrackerIndex, -1);
    EXPECT_EQ(newMetricProducers[eventMetricIndex]->mCondition, ConditionState::kTrue);
    EXPECT_EQ(newMetricProducers[gaugeMetricIndex]->getMetricId(), gaugeMetricId);
    EXPECT_EQ(newMetricProducers[gaugeMetricIndex]->mConditionTrackerIndex, predicate1Index);
    EXPECT_EQ(newMetricProducers[gaugeMetricIndex]->mCondition, ConditionState::kUnknown);

    sp<ConditionWizard> newConditionWizard = newMetricProducers[0]->mWizard;
    EXPECT_NE(newConditionWizard, oldConditionWizard);
    EXPECT_EQ(newConditionWizard->getStrongCount(), 6);
    oldMetricProducers.clear();
    // Only reference to the old wizard should be the one in the test.
    EXPECT_EQ(oldConditionWizard->getStrongCount(), 1);
}
}  // namespace statsd
}  // namespace os
}  // namespace android

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
