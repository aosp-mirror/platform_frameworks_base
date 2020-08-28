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
#include "src/metrics/parsing_utils/metrics_manager_util.h"
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
unordered_map<int, std::vector<int>> conditionToMetricMap;
unordered_map<int, std::vector<int>> trackerToMetricMap;
unordered_map<int, std::vector<int>> trackerToConditionMap;
unordered_map<int, std::vector<int>> activationAtomTrackerToMetricMap;
unordered_map<int, std::vector<int>> deactivationAtomTrackerToMetricMap;
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
        conditionToMetricMap.clear();
        trackerToMetricMap.clear();
        trackerToConditionMap.clear();
        activationAtomTrackerToMetricMap.clear();
        deactivationAtomTrackerToMetricMap.clear();
        alertTrackerMap.clear();
        metricsWithActivation.clear();
        oldStateHashes.clear();
        noReportMetricIds.clear();
    }
};

bool initConfig(const StatsdConfig& config) {
    return initStatsdConfig(
            key, config, uidMap, pullerManager, anomalyAlarmMonitor, periodicAlarmMonitor,
            timeBaseNs, timeBaseNs, allTagIds, oldAtomMatchingTrackers, oldAtomMatchingTrackerMap,
            oldConditionTrackers, oldConditionTrackerMap, oldMetricProducers, oldMetricProducerMap,
            oldAnomalyTrackers, oldAlarmTrackers, conditionToMetricMap, trackerToMetricMap,
            trackerToConditionMap, activationAtomTrackerToMetricMap,
            deactivationAtomTrackerToMetricMap, alertTrackerMap, metricsWithActivation,
            oldStateHashes, noReportMetricIds);
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

    // Change the condition of simple1 to true.
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

    // New combination matcher. Should have an initial condition of true since it is NOT(simple1).
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
}  // namespace statsd
}  // namespace os
}  // namespace android

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
