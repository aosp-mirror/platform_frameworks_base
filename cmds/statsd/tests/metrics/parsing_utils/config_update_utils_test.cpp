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

#include <gtest/gtest.h>
#include <private/android_filesystem_config.h>
#include <stdio.h>

#include <set>
#include <unordered_map>
#include <vector>

#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
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
vector<sp<LogMatchingTracker>> oldAtomMatchers;
unordered_map<int64_t, int> oldLogTrackerMap;
vector<sp<ConditionTracker>> oldConditionTrackers;
vector<sp<MetricProducer>> oldMetricProducers;
std::vector<sp<AnomalyTracker>> oldAnomalyTrackers;
std::vector<sp<AlarmTracker>> oldAlarmTrackers;
unordered_map<int, std::vector<int>> conditionToMetricMap;
unordered_map<int, std::vector<int>> trackerToMetricMap;
unordered_map<int, std::vector<int>> trackerToConditionMap;
unordered_map<int, std::vector<int>> activationAtomTrackerToMetricMap;
unordered_map<int, std::vector<int>> deactivationAtomTrackerToMetricMap;
unordered_map<int64_t, int> alertTrackerMap;
vector<int> metricsWithActivation;
std::set<int64_t> noReportMetricIds;

class ConfigUpdateTest : public ::testing::Test {
public:
    ConfigUpdateTest() {
    }

    void SetUp() override {
        allTagIds.clear();
        oldAtomMatchers.clear();
        oldLogTrackerMap.clear();
        oldConditionTrackers.clear();
        oldMetricProducers.clear();
        oldAnomalyTrackers.clear();
        oldAlarmTrackers.clear();
        conditionToMetricMap.clear();
        trackerToMetricMap.clear();
        trackerToConditionMap.clear();
        activationAtomTrackerToMetricMap.clear();
        deactivationAtomTrackerToMetricMap.clear();
        alertTrackerMap.clear();
        metricsWithActivation.clear();
        noReportMetricIds.clear();
    }
};

bool initConfig(const StatsdConfig& config) {
    return initStatsdConfig(key, config, uidMap, pullerManager, anomalyAlarmMonitor,
                            periodicAlarmMonitor, timeBaseNs, timeBaseNs, allTagIds,
                            oldAtomMatchers, oldLogTrackerMap, oldConditionTrackers,
                            oldMetricProducers, oldAnomalyTrackers, oldAlarmTrackers,
                            conditionToMetricMap, trackerToMetricMap, trackerToConditionMap,
                            activationAtomTrackerToMetricMap, deactivationAtomTrackerToMetricMap,
                            alertTrackerMap, metricsWithActivation, noReportMetricIds);
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
    unordered_map<int64_t, int> newLogTrackerMap;
    newLogTrackerMap[matcherId] = 0;
    EXPECT_TRUE(determineMatcherUpdateStatus(config, 0, oldLogTrackerMap, oldAtomMatchers,
                                             newLogTrackerMap, matchersToUpdate, cycleTracker));
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
    unordered_map<int64_t, int> newLogTrackerMap;
    newLogTrackerMap[matcherId] = 0;
    EXPECT_TRUE(determineMatcherUpdateStatus(newConfig, 0, oldLogTrackerMap, oldAtomMatchers,
                                             newLogTrackerMap, matchersToUpdate, cycleTracker));
    EXPECT_EQ(matchersToUpdate[0], UPDATE_REPLACE);
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
    unordered_map<int64_t, int> newLogTrackerMap;
    // Same matchers, different order, all should be preserved.
    *newConfig.add_atom_matcher() = matcher2;
    newLogTrackerMap[matcher2Id] = 0;
    *newConfig.add_atom_matcher() = matcher3;
    newLogTrackerMap[matcher3Id] = 1;
    *newConfig.add_atom_matcher() = matcher1;
    newLogTrackerMap[matcher1Id] = 2;

    vector<UpdateStatus> matchersToUpdate(3, UPDATE_UNKNOWN);
    vector<bool> cycleTracker(3, false);
    // Only update the combination. It should recurse the two child matchers and preserve all 3.
    EXPECT_TRUE(determineMatcherUpdateStatus(newConfig, 1, oldLogTrackerMap, oldAtomMatchers,
                                             newLogTrackerMap, matchersToUpdate, cycleTracker));
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
    unordered_map<int64_t, int> newLogTrackerMap;
    *newConfig.add_atom_matcher() = matcher2;
    newLogTrackerMap[matcher2Id] = 0;
    *newConfig.add_atom_matcher() = matcher3;
    newLogTrackerMap[matcher3Id] = 1;
    *newConfig.add_atom_matcher() = matcher1;
    newLogTrackerMap[matcher1Id] = 2;

    vector<UpdateStatus> matchersToUpdate(3, UPDATE_UNKNOWN);
    vector<bool> cycleTracker(3, false);
    // Only update the combination. The simple matchers should not be evaluated.
    EXPECT_TRUE(determineMatcherUpdateStatus(newConfig, 1, oldLogTrackerMap, oldAtomMatchers,
                                             newLogTrackerMap, matchersToUpdate, cycleTracker));
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
    unordered_map<int64_t, int> newLogTrackerMap;
    *newConfig.add_atom_matcher() = matcher2;
    newLogTrackerMap[matcher2Id] = 0;
    *newConfig.add_atom_matcher() = matcher3;
    newLogTrackerMap[matcher3Id] = 1;
    *newConfig.add_atom_matcher() = matcher1;
    newLogTrackerMap[matcher1Id] = 2;

    vector<UpdateStatus> matchersToUpdate(3, UPDATE_UNKNOWN);
    vector<bool> cycleTracker(3, false);
    // Only update the combination.
    EXPECT_TRUE(determineMatcherUpdateStatus(newConfig, 1, oldLogTrackerMap, oldAtomMatchers,
                                             newLogTrackerMap, matchersToUpdate, cycleTracker));
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
    unordered_map<int64_t, int> newLogTrackerMap;
    vector<sp<LogMatchingTracker>> newAtomMatchers;
    EXPECT_TRUE(updateLogTrackers(newConfig, uidMap, oldLogTrackerMap, oldAtomMatchers, newTagIds,
                                  newLogTrackerMap, newAtomMatchers));

    ASSERT_EQ(newTagIds.size(), 3);
    EXPECT_EQ(newTagIds.count(10), 1);
    EXPECT_EQ(newTagIds.count(111), 1);
    EXPECT_EQ(newTagIds.count(13), 1);

    ASSERT_EQ(newLogTrackerMap.size(), 6);
    EXPECT_EQ(newLogTrackerMap.at(combination3Id), 0);
    EXPECT_EQ(newLogTrackerMap.at(simple2Id), 1);
    EXPECT_EQ(newLogTrackerMap.at(combination2Id), 2);
    EXPECT_EQ(newLogTrackerMap.at(simple1Id), 3);
    EXPECT_EQ(newLogTrackerMap.at(simple4Id), 4);
    EXPECT_EQ(newLogTrackerMap.at(combination1Id), 5);

    ASSERT_EQ(newAtomMatchers.size(), 6);
    // Make sure all atom matchers are initialized:
    for (const sp<LogMatchingTracker>& tracker : newAtomMatchers) {
        EXPECT_TRUE(tracker->mInitialized);
    }
    // Make sure preserved atom matchers are the same.
    EXPECT_EQ(oldAtomMatchers[oldLogTrackerMap.at(simple1Id)],
              newAtomMatchers[newLogTrackerMap.at(simple1Id)]);
    EXPECT_EQ(oldAtomMatchers[oldLogTrackerMap.at(combination1Id)],
              newAtomMatchers[newLogTrackerMap.at(combination1Id)]);
    // Make sure replaced matchers are different.
    EXPECT_NE(oldAtomMatchers[oldLogTrackerMap.at(simple2Id)],
              newAtomMatchers[newLogTrackerMap.at(simple2Id)]);
    EXPECT_NE(oldAtomMatchers[oldLogTrackerMap.at(combination2Id)],
              newAtomMatchers[newLogTrackerMap.at(combination2Id)]);

    // Validation, make sure the matchers have the proper ids. Could do more checks here.
    EXPECT_EQ(newAtomMatchers[0]->getId(), combination3Id);
    EXPECT_EQ(newAtomMatchers[1]->getId(), simple2Id);
    EXPECT_EQ(newAtomMatchers[2]->getId(), combination2Id);
    EXPECT_EQ(newAtomMatchers[3]->getId(), simple1Id);
    EXPECT_EQ(newAtomMatchers[4]->getId(), simple4Id);
    EXPECT_EQ(newAtomMatchers[5]->getId(), combination1Id);
}

}  // namespace statsd
}  // namespace os
}  // namespace android

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
