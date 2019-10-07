/*
 * Copyright (C) 2019, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <gtest/gtest.h>

#include "src/StatsLogProcessor.h"
#include "src/state/StateManager.h"
#include "tests/statsd_test_util.h"

namespace android {
namespace os {
namespace statsd {

#ifdef __ANDROID__

TEST(CountMetricE2eTest, TestWithSimpleState) {
    // Initialize config
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");  // LogEvent defaults to UID of root.

    auto syncStartMatcher = CreateSyncStartAtomMatcher();
    *config.add_atom_matcher() = syncStartMatcher;

    auto state = CreateScreenState();
    *config.add_state() = state;

    // Create count metric that slices by screen state
    int64_t metricId = 123456;
    auto countMetric = config.add_count_metric();
    countMetric->set_id(metricId);
    countMetric->set_what(syncStartMatcher.id());
    countMetric->set_bucket(TimeUnit::ONE_MINUTE);
    countMetric->add_slice_by_state(state.id());

    // Initialize StatsLogProcessor
    const int64_t baseTimeNs = 0;                                   // 0:00
    const int64_t configAddedTimeNs = baseTimeNs + 1 * NS_PER_SEC;  // 0:01
    const int64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.count_metric(0).bucket()) * 1000LL * 1000LL;

    int uid = 12345;
    int64_t cfgId = 98765;
    ConfigKey cfgKey(uid, cfgId);

    auto processor = CreateStatsLogProcessor(baseTimeNs, configAddedTimeNs, config, cfgKey);

    // Check that StateTrackers were properly initialized.
    EXPECT_EQ(1, StateManager::getInstance().getStateTrackersCount());
    EXPECT_EQ(1,
              StateManager::getInstance().getListenersCount(android::util::SCREEN_STATE_CHANGED));

    // Check that CountMetricProducer was initialized correctly.
    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    sp<MetricsManager> metricsManager = processor->mMetricsManagers.begin()->second;
    EXPECT_TRUE(metricsManager->isConfigValid());
    EXPECT_EQ(metricsManager->mAllMetricProducers.size(), 1);
    sp<MetricProducer> metricProducer = metricsManager->mAllMetricProducers[0];
    EXPECT_EQ(metricProducer->mSlicedStateAtoms.size(), 1);
    EXPECT_EQ(metricProducer->mSlicedStateAtoms.at(0), android::util::SCREEN_STATE_CHANGED);
    EXPECT_EQ(metricProducer->mStateGroupMap.size(), 0);
}

TEST(CountMetricE2eTest, TestWithMappedState) {
    // Initialize config
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");  // LogEvent defaults to UID of root.

    auto syncStartMatcher = CreateSyncStartAtomMatcher();
    *config.add_atom_matcher() = syncStartMatcher;

    auto state = CreateScreenStateWithOnOffMap();
    *config.add_state() = state;

    // Create count metric that slices by screen state with on/off map
    int64_t metricId = 123456;
    auto countMetric = config.add_count_metric();
    countMetric->set_id(metricId);
    countMetric->set_what(syncStartMatcher.id());
    countMetric->set_bucket(TimeUnit::ONE_MINUTE);
    countMetric->add_slice_by_state(state.id());

    // Initialize StatsLogProcessor
    const int64_t baseTimeNs = 0;                                   // 0:00
    const int64_t configAddedTimeNs = baseTimeNs + 1 * NS_PER_SEC;  // 0:01
    const int64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.count_metric(0).bucket()) * 1000LL * 1000LL;

    int uid = 12345;
    int64_t cfgId = 98765;
    ConfigKey cfgKey(uid, cfgId);

    auto processor = CreateStatsLogProcessor(baseTimeNs, configAddedTimeNs, config, cfgKey);

    // Check that StateTrackers were properly initialized.
    EXPECT_EQ(1, StateManager::getInstance().getStateTrackersCount());
    EXPECT_EQ(1,
              StateManager::getInstance().getListenersCount(android::util::SCREEN_STATE_CHANGED));

    // Check that CountMetricProducer was initialized correctly.
    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    sp<MetricsManager> metricsManager = processor->mMetricsManagers.begin()->second;
    EXPECT_TRUE(metricsManager->isConfigValid());
    EXPECT_EQ(metricsManager->mAllMetricProducers.size(), 1);
    sp<MetricProducer> metricProducer = metricsManager->mAllMetricProducers[0];
    EXPECT_EQ(metricProducer->mSlicedStateAtoms.size(), 1);
    EXPECT_EQ(metricProducer->mSlicedStateAtoms.at(0), android::util::SCREEN_STATE_CHANGED);
    EXPECT_EQ(metricProducer->mStateGroupMap.size(), 1);

    StateMap map = state.map();
    for (auto group : map.group()) {
        for (auto value : group.value()) {
            EXPECT_EQ(metricProducer->mStateGroupMap[android::util::SCREEN_STATE_CHANGED][value],
                      group.group_id());
        }
    }
}

TEST(CountMetricE2eTest, TestWithMultipleStates) {
    // Initialize config
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");  // LogEvent defaults to UID of root.

    auto syncStartMatcher = CreateSyncStartAtomMatcher();
    *config.add_atom_matcher() = syncStartMatcher;

    auto state1 = CreateScreenStateWithOnOffMap();
    *config.add_state() = state1;
    auto state2 = CreateUidProcessState();
    *config.add_state() = state2;

    // Create count metric that slices by screen state with on/off map
    int64_t metricId = 123456;
    auto countMetric = config.add_count_metric();
    countMetric->set_id(metricId);
    countMetric->set_what(syncStartMatcher.id());
    countMetric->set_bucket(TimeUnit::ONE_MINUTE);
    countMetric->add_slice_by_state(state1.id());
    countMetric->add_slice_by_state(state2.id());

    // Initialize StatsLogProcessor
    const int64_t baseTimeNs = 0;                                   // 0:00
    const int64_t configAddedTimeNs = baseTimeNs + 1 * NS_PER_SEC;  // 0:01
    const int64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.count_metric(0).bucket()) * 1000LL * 1000LL;

    int uid = 12345;
    int64_t cfgId = 98765;
    ConfigKey cfgKey(uid, cfgId);

    auto processor = CreateStatsLogProcessor(baseTimeNs, configAddedTimeNs, config, cfgKey);

    // Check that StateTrackers were properly initialized.
    EXPECT_EQ(2, StateManager::getInstance().getStateTrackersCount());
    EXPECT_EQ(1,
              StateManager::getInstance().getListenersCount(android::util::SCREEN_STATE_CHANGED));
    EXPECT_EQ(1, StateManager::getInstance().getListenersCount(
                         android::util::UID_PROCESS_STATE_CHANGED));

    // Check that CountMetricProducer was initialized correctly.
    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    sp<MetricsManager> metricsManager = processor->mMetricsManagers.begin()->second;
    EXPECT_TRUE(metricsManager->isConfigValid());
    EXPECT_EQ(metricsManager->mAllMetricProducers.size(), 1);
    sp<MetricProducer> metricProducer = metricsManager->mAllMetricProducers[0];
    EXPECT_EQ(metricProducer->mSlicedStateAtoms.size(), 2);
    EXPECT_EQ(metricProducer->mSlicedStateAtoms.at(0), android::util::SCREEN_STATE_CHANGED);
    EXPECT_EQ(metricProducer->mSlicedStateAtoms.at(1), android::util::UID_PROCESS_STATE_CHANGED);
    EXPECT_EQ(metricProducer->mStateGroupMap.size(), 1);

    StateMap map = state1.map();
    for (auto group : map.group()) {
        for (auto value : group.value()) {
            EXPECT_EQ(metricProducer->mStateGroupMap[android::util::SCREEN_STATE_CHANGED][value],
                      group.group_id());
        }
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
