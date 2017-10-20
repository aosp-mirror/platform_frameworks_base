// Copyright (C) 2017 The Android Open Source Project
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

#include <gtest/gtest.h>

#include "src/condition/ConditionTracker.h"
#include "src/matchers/LogMatchingTracker.h"
#include "src/metrics/CountMetricProducer.h"
#include "src/metrics/MetricProducer.h"
#include "src/metrics/metrics_manager_util.h"

#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"

#include <stdio.h>
#include <set>
#include <unordered_map>
#include <vector>

using namespace android::os::statsd;
using android::sp;
using std::set;
using std::unordered_map;
using std::vector;

#ifdef __ANDROID__

// TODO: ADD MORE TEST CASES.

StatsdConfig buildGoodConfig() {
    StatsdConfig config;
    config.set_config_id(12345L);

    LogEntryMatcher* eventMatcher = config.add_log_entry_matcher();
    eventMatcher->set_name("SCREEN_IS_ON");

    SimpleLogEntryMatcher* simpleLogEntryMatcher = eventMatcher->mutable_simple_log_entry_matcher();
    simpleLogEntryMatcher->set_tag(2 /*SCREEN_STATE_CHANGE*/);
    simpleLogEntryMatcher->add_key_value_matcher()->mutable_key_matcher()->set_key(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE*/);
    simpleLogEntryMatcher->mutable_key_value_matcher(0)->set_eq_int(
            2 /*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_ON*/);

    eventMatcher = config.add_log_entry_matcher();
    eventMatcher->set_name("SCREEN_IS_OFF");

    simpleLogEntryMatcher = eventMatcher->mutable_simple_log_entry_matcher();
    simpleLogEntryMatcher->set_tag(2 /*SCREEN_STATE_CHANGE*/);
    simpleLogEntryMatcher->add_key_value_matcher()->mutable_key_matcher()->set_key(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE*/);
    simpleLogEntryMatcher->mutable_key_value_matcher(0)->set_eq_int(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_OFF*/);

    eventMatcher = config.add_log_entry_matcher();
    eventMatcher->set_name("SCREEN_ON_OR_OFF");

    LogEntryMatcher_Combination* combination = eventMatcher->mutable_combination();
    combination->set_operation(LogicalOperation::OR);
    combination->add_matcher("SCREEN_IS_ON");
    combination->add_matcher("SCREEN_IS_OFF");

    return config;
}

StatsdConfig buildCircleMatchers() {
    StatsdConfig config;
    config.set_config_id(12345L);

    LogEntryMatcher* eventMatcher = config.add_log_entry_matcher();
    eventMatcher->set_name("SCREEN_IS_ON");

    SimpleLogEntryMatcher* simpleLogEntryMatcher = eventMatcher->mutable_simple_log_entry_matcher();
    simpleLogEntryMatcher->set_tag(2 /*SCREEN_STATE_CHANGE*/);
    simpleLogEntryMatcher->add_key_value_matcher()->mutable_key_matcher()->set_key(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE*/);
    simpleLogEntryMatcher->mutable_key_value_matcher(0)->set_eq_int(
            2 /*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_ON*/);

    eventMatcher = config.add_log_entry_matcher();
    eventMatcher->set_name("SCREEN_ON_OR_OFF");

    LogEntryMatcher_Combination* combination = eventMatcher->mutable_combination();
    combination->set_operation(LogicalOperation::OR);
    combination->add_matcher("SCREEN_IS_ON");
    // Circle dependency
    combination->add_matcher("SCREEN_ON_OR_OFF");

    return config;
}

StatsdConfig buildMissingMatchers() {
    StatsdConfig config;
    config.set_config_id(12345L);

    LogEntryMatcher* eventMatcher = config.add_log_entry_matcher();
    eventMatcher->set_name("SCREEN_IS_ON");

    SimpleLogEntryMatcher* simpleLogEntryMatcher = eventMatcher->mutable_simple_log_entry_matcher();
    simpleLogEntryMatcher->set_tag(2 /*SCREEN_STATE_CHANGE*/);
    simpleLogEntryMatcher->add_key_value_matcher()->mutable_key_matcher()->set_key(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE*/);
    simpleLogEntryMatcher->mutable_key_value_matcher(0)->set_eq_int(
            2 /*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_ON*/);

    eventMatcher = config.add_log_entry_matcher();
    eventMatcher->set_name("SCREEN_ON_OR_OFF");

    LogEntryMatcher_Combination* combination = eventMatcher->mutable_combination();
    combination->set_operation(LogicalOperation::OR);
    combination->add_matcher("SCREEN_IS_ON");
    // undefined matcher
    combination->add_matcher("ABC");

    return config;
}

StatsdConfig buildCircleConditions() {
    StatsdConfig config;
    config.set_config_id(12345L);

    LogEntryMatcher* eventMatcher = config.add_log_entry_matcher();
    eventMatcher->set_name("SCREEN_IS_ON");

    SimpleLogEntryMatcher* simpleLogEntryMatcher = eventMatcher->mutable_simple_log_entry_matcher();
    simpleLogEntryMatcher->set_tag(2 /*SCREEN_STATE_CHANGE*/);
    simpleLogEntryMatcher->add_key_value_matcher()->mutable_key_matcher()->set_key(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE*/);
    simpleLogEntryMatcher->mutable_key_value_matcher(0)->set_eq_int(
            2 /*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_ON*/);

    eventMatcher = config.add_log_entry_matcher();
    eventMatcher->set_name("SCREEN_IS_OFF");

    simpleLogEntryMatcher = eventMatcher->mutable_simple_log_entry_matcher();
    simpleLogEntryMatcher->set_tag(2 /*SCREEN_STATE_CHANGE*/);
    simpleLogEntryMatcher->add_key_value_matcher()->mutable_key_matcher()->set_key(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE*/);
    simpleLogEntryMatcher->mutable_key_value_matcher(0)->set_eq_int(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_OFF*/);

    Condition* condition = config.add_condition();
    condition->set_name("SCREEN_IS_ON");
    SimpleCondition* simpleCondition = condition->mutable_simple_condition();
    simpleCondition->set_start("SCREEN_IS_ON");
    simpleCondition->set_stop("SCREEN_IS_OFF");

    condition = config.add_condition();
    condition->set_name("SCREEN_IS_EITHER_ON_OFF");

    Condition_Combination* combination = condition->mutable_combination();
    combination->set_operation(LogicalOperation::OR);
    combination->add_condition("SCREEN_IS_ON");
    combination->add_condition("SCREEN_IS_EITHER_ON_OFF");

    return config;
}

TEST(MetricsManagerTest, TestGoodConfig) {
    StatsdConfig config = buildGoodConfig();
    set<int> allTagIds;
    vector<sp<LogMatchingTracker>> allLogEntryMatchers;
    vector<sp<ConditionTracker>> allConditionTrackers;
    vector<sp<MetricProducer>> allMetricProducers;
    unordered_map<int, std::vector<int>> conditionToMetricMap;
    unordered_map<int, std::vector<int>> trackerToMetricMap;
    unordered_map<int, std::vector<int>> trackerToConditionMap;

    EXPECT_TRUE(initStatsdConfig(config, allTagIds, allLogEntryMatchers, allConditionTrackers,
                                 allMetricProducers, conditionToMetricMap, trackerToMetricMap,
                                 trackerToConditionMap));
}

TEST(MetricsManagerTest, TestCircleLogMatcherDependency) {
    StatsdConfig config = buildCircleMatchers();
    set<int> allTagIds;
    vector<sp<LogMatchingTracker>> allLogEntryMatchers;
    vector<sp<ConditionTracker>> allConditionTrackers;
    vector<sp<MetricProducer>> allMetricProducers;
    unordered_map<int, std::vector<int>> conditionToMetricMap;
    unordered_map<int, std::vector<int>> trackerToMetricMap;
    unordered_map<int, std::vector<int>> trackerToConditionMap;

    EXPECT_FALSE(initStatsdConfig(config, allTagIds, allLogEntryMatchers, allConditionTrackers,
                                  allMetricProducers, conditionToMetricMap, trackerToMetricMap,
                                  trackerToConditionMap));
}

TEST(MetricsManagerTest, TestMissingMatchers) {
    StatsdConfig config = buildMissingMatchers();
    set<int> allTagIds;
    vector<sp<LogMatchingTracker>> allLogEntryMatchers;
    vector<sp<ConditionTracker>> allConditionTrackers;
    vector<sp<MetricProducer>> allMetricProducers;
    unordered_map<int, std::vector<int>> conditionToMetricMap;
    unordered_map<int, std::vector<int>> trackerToMetricMap;
    unordered_map<int, std::vector<int>> trackerToConditionMap;

    EXPECT_FALSE(initStatsdConfig(config, allTagIds, allLogEntryMatchers, allConditionTrackers,
                                  allMetricProducers, conditionToMetricMap, trackerToMetricMap,
                                  trackerToConditionMap));
}

TEST(MetricsManagerTest, TestCircleConditionDependency) {
    StatsdConfig config = buildCircleConditions();
    set<int> allTagIds;
    vector<sp<LogMatchingTracker>> allLogEntryMatchers;
    vector<sp<ConditionTracker>> allConditionTrackers;
    vector<sp<MetricProducer>> allMetricProducers;
    unordered_map<int, std::vector<int>> conditionToMetricMap;
    unordered_map<int, std::vector<int>> trackerToMetricMap;
    unordered_map<int, std::vector<int>> trackerToConditionMap;

    EXPECT_FALSE(initStatsdConfig(config, allTagIds, allLogEntryMatchers, allConditionTrackers,
                                  allMetricProducers, conditionToMetricMap, trackerToMetricMap,
                                  trackerToConditionMap));
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
