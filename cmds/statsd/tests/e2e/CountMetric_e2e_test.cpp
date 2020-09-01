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
#include "src/state/StateTracker.h"
#include "tests/statsd_test_util.h"

namespace android {
namespace os {
namespace statsd {

#ifdef __ANDROID__

/**
 * Tests the initial condition and condition after the first log events for
 * count metrics with either a combination condition or simple condition.
 *
 * Metrics should be initialized with condition kUnknown (given that the
 * predicate is using the default InitialValue of UNKNOWN). The condition should
 * be updated to either kFalse or kTrue if a condition event is logged for all
 * children conditions.
 */
TEST(CountMetricE2eTest, TestInitialConditionChanges) {
    // Initialize config.
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");     // LogEvent defaults to UID of root.
    config.add_default_pull_packages("AID_ROOT");  // Fake puller is registered with root.

    auto syncStartMatcher = CreateSyncStartAtomMatcher();
    *config.add_atom_matcher() = syncStartMatcher;
    *config.add_atom_matcher() = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = CreateBatteryStateNoneMatcher();
    *config.add_atom_matcher() = CreateBatteryStateUsbMatcher();

    auto screenOnPredicate = CreateScreenIsOnPredicate();
    *config.add_predicate() = screenOnPredicate;

    auto deviceUnpluggedPredicate = CreateDeviceUnpluggedPredicate();
    *config.add_predicate() = deviceUnpluggedPredicate;

    auto screenOnOnBatteryPredicate = config.add_predicate();
    screenOnOnBatteryPredicate->set_id(StringToId("screenOnOnBatteryPredicate"));
    screenOnOnBatteryPredicate->mutable_combination()->set_operation(LogicalOperation::AND);
    addPredicateToPredicateCombination(screenOnPredicate, screenOnOnBatteryPredicate);
    addPredicateToPredicateCombination(deviceUnpluggedPredicate, screenOnOnBatteryPredicate);

    // CountSyncStartWhileScreenOnOnBattery (CombinationCondition)
    CountMetric* countMetric1 = config.add_count_metric();
    countMetric1->set_id(StringToId("CountSyncStartWhileScreenOnOnBattery"));
    countMetric1->set_what(syncStartMatcher.id());
    countMetric1->set_condition(screenOnOnBatteryPredicate->id());
    countMetric1->set_bucket(FIVE_MINUTES);

    // CountSyncStartWhileOnBattery (SimpleCondition)
    CountMetric* countMetric2 = config.add_count_metric();
    countMetric2->set_id(StringToId("CountSyncStartWhileOnBatterySliceScreen"));
    countMetric2->set_what(syncStartMatcher.id());
    countMetric2->set_condition(deviceUnpluggedPredicate.id());
    countMetric2->set_bucket(FIVE_MINUTES);

    const uint64_t bucketStartTimeNs = 10000000000;  // 0:10
    const uint64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.count_metric(0).bucket()) * 1000000LL;
    int uid = 12345;
    int64_t cfgId = 98765;
    ConfigKey cfgKey(uid, cfgId);
    auto processor = CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);

    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    sp<MetricsManager> metricsManager = processor->mMetricsManagers.begin()->second;
    EXPECT_TRUE(metricsManager->isConfigValid());
    EXPECT_EQ(2, metricsManager->mAllMetricProducers.size());

    sp<MetricProducer> metricProducer1 = metricsManager->mAllMetricProducers[0];
    sp<MetricProducer> metricProducer2 = metricsManager->mAllMetricProducers[1];

    EXPECT_EQ(ConditionState::kUnknown, metricProducer1->mCondition);
    EXPECT_EQ(ConditionState::kUnknown, metricProducer2->mCondition);

    auto screenOnEvent =
            CreateScreenStateChangedEvent(bucketStartTimeNs + 30, android::view::DISPLAY_STATE_ON);
    processor->OnLogEvent(screenOnEvent.get());
    EXPECT_EQ(ConditionState::kUnknown, metricProducer1->mCondition);
    EXPECT_EQ(ConditionState::kUnknown, metricProducer2->mCondition);

    auto pluggedUsbEvent = CreateBatteryStateChangedEvent(
            bucketStartTimeNs + 50, BatteryPluggedStateEnum::BATTERY_PLUGGED_USB);
    processor->OnLogEvent(pluggedUsbEvent.get());
    EXPECT_EQ(ConditionState::kFalse, metricProducer1->mCondition);
    EXPECT_EQ(ConditionState::kFalse, metricProducer2->mCondition);

    auto pluggedNoneEvent = CreateBatteryStateChangedEvent(
            bucketStartTimeNs + 70, BatteryPluggedStateEnum::BATTERY_PLUGGED_NONE);
    processor->OnLogEvent(pluggedNoneEvent.get());
    EXPECT_EQ(ConditionState::kTrue, metricProducer1->mCondition);
    EXPECT_EQ(ConditionState::kTrue, metricProducer2->mCondition);
}

/**
* Test a count metric that has one slice_by_state with no primary fields.
*
* Once the CountMetricProducer is initialized, it has one atom id in
* mSlicedStateAtoms and no entries in mStateGroupMap.

* One StateTracker tracks the state atom, and it has one listener which is the
* CountMetricProducer that was initialized.
*/
TEST(CountMetricE2eTest, TestSlicedState) {
    // Initialize config.
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");  // LogEvent defaults to UID of root.

    auto syncStartMatcher = CreateSyncStartAtomMatcher();
    *config.add_atom_matcher() = syncStartMatcher;

    auto state = CreateScreenState();
    *config.add_state() = state;

    // Create count metric that slices by screen state.
    int64_t metricId = 123456;
    auto countMetric = config.add_count_metric();
    countMetric->set_id(metricId);
    countMetric->set_what(syncStartMatcher.id());
    countMetric->set_bucket(TimeUnit::FIVE_MINUTES);
    countMetric->add_slice_by_state(state.id());

    // Initialize StatsLogProcessor.
    const uint64_t bucketStartTimeNs = 10000000000;  // 0:10
    const uint64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.count_metric(0).bucket()) * 1000000LL;
    int uid = 12345;
    int64_t cfgId = 98765;
    ConfigKey cfgKey(uid, cfgId);
    auto processor = CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);

    // Check that CountMetricProducer was initialized correctly.
    ASSERT_EQ(processor->mMetricsManagers.size(), 1u);
    sp<MetricsManager> metricsManager = processor->mMetricsManagers.begin()->second;
    EXPECT_TRUE(metricsManager->isConfigValid());
    ASSERT_EQ(metricsManager->mAllMetricProducers.size(), 1);
    sp<MetricProducer> metricProducer = metricsManager->mAllMetricProducers[0];
    ASSERT_EQ(metricProducer->mSlicedStateAtoms.size(), 1);
    EXPECT_EQ(metricProducer->mSlicedStateAtoms.at(0), SCREEN_STATE_ATOM_ID);
    ASSERT_EQ(metricProducer->mStateGroupMap.size(), 0);

    // Check that StateTrackers were initialized correctly.
    EXPECT_EQ(1, StateManager::getInstance().getStateTrackersCount());
    EXPECT_EQ(1, StateManager::getInstance().getListenersCount(SCREEN_STATE_ATOM_ID));

    /*
               bucket #1                      bucket #2
    |     1     2     3     4     5     6     7     8     9     10 (minutes)
    |-----------------------------|-----------------------------|--
            x                x         x    x        x      x       (syncStartEvents)
          |                                       |                 (ScreenIsOnEvent)
                   |     |                                          (ScreenIsOffEvent)
                                                        |           (ScreenDozeEvent)
    */
    // Initialize log events - first bucket.
    std::vector<int> attributionUids1 = {123};
    std::vector<string> attributionTags1 = {"App1"};

    std::vector<std::unique_ptr<LogEvent>> events;
    events.push_back(CreateScreenStateChangedEvent(
            bucketStartTimeNs + 50 * NS_PER_SEC,
            android::view::DisplayStateEnum::DISPLAY_STATE_ON));  // 1:00
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 75 * NS_PER_SEC, attributionUids1,
                                          attributionTags1, "sync_name"));  // 1:25
    events.push_back(CreateScreenStateChangedEvent(
            bucketStartTimeNs + 150 * NS_PER_SEC,
            android::view::DisplayStateEnum::DISPLAY_STATE_OFF));  // 2:40
    events.push_back(CreateScreenStateChangedEvent(
            bucketStartTimeNs + 200 * NS_PER_SEC,
            android::view::DisplayStateEnum::DISPLAY_STATE_OFF));  // 3:30
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 250 * NS_PER_SEC, attributionUids1,
                                          attributionTags1, "sync_name"));  // 4:20

    // Initialize log events - second bucket.
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 350 * NS_PER_SEC, attributionUids1,
                                          attributionTags1, "sync_name"));  // 6:00
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 400 * NS_PER_SEC, attributionUids1,
                                          attributionTags1, "sync_name"));  // 6:50
    events.push_back(CreateScreenStateChangedEvent(
            bucketStartTimeNs + 450 * NS_PER_SEC,
            android::view::DisplayStateEnum::DISPLAY_STATE_ON));  // 7:40
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 475 * NS_PER_SEC, attributionUids1,
                                          attributionTags1, "sync_name"));  // 8:05
    events.push_back(CreateScreenStateChangedEvent(
            bucketStartTimeNs + 500 * NS_PER_SEC,
            android::view::DisplayStateEnum::DISPLAY_STATE_UNKNOWN));  // 8:30
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 520 * NS_PER_SEC, attributionUids1,
                                          attributionTags1, "sync_name"));  // 8:50

    // Send log events to StatsLogProcessor.
    for (auto& event : events) {
        processor->OnLogEvent(event.get());
    }

    // Check dump report.
    vector<uint8_t> buffer;
    ConfigMetricsReportList reports;
    processor->onDumpReport(cfgKey, bucketStartTimeNs + bucketSizeNs * 2 + 1, false, true, ADB_DUMP,
                            FAST, &buffer);
    ASSERT_GT(buffer.size(), 0);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);

    ASSERT_EQ(1, reports.reports_size());
    ASSERT_EQ(1, reports.reports(0).metrics_size());
    EXPECT_TRUE(reports.reports(0).metrics(0).has_count_metrics());
    StatsLogReport::CountMetricDataWrapper countMetrics;
    sortMetricDataByDimensionsValue(reports.reports(0).metrics(0).count_metrics(), &countMetrics);
    ASSERT_EQ(3, countMetrics.data_size());

    // For each CountMetricData, check StateValue info is correct and buckets
    // have correct counts.
    auto data = countMetrics.data(0);
    ASSERT_EQ(1, data.slice_by_state_size());
    EXPECT_EQ(SCREEN_STATE_ATOM_ID, data.slice_by_state(0).atom_id());
    EXPECT_TRUE(data.slice_by_state(0).has_value());
    EXPECT_EQ(android::view::DisplayStateEnum::DISPLAY_STATE_UNKNOWN,
              data.slice_by_state(0).value());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());

    data = countMetrics.data(1);
    ASSERT_EQ(1, data.slice_by_state_size());
    EXPECT_EQ(SCREEN_STATE_ATOM_ID, data.slice_by_state(0).atom_id());
    EXPECT_TRUE(data.slice_by_state(0).has_value());
    EXPECT_EQ(android::view::DisplayStateEnum::DISPLAY_STATE_OFF, data.slice_by_state(0).value());
    ASSERT_EQ(2, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(2, data.bucket_info(1).count());

    data = countMetrics.data(2);
    ASSERT_EQ(1, data.slice_by_state_size());
    EXPECT_EQ(SCREEN_STATE_ATOM_ID, data.slice_by_state(0).atom_id());
    EXPECT_TRUE(data.slice_by_state(0).has_value());
    EXPECT_EQ(android::view::DisplayStateEnum::DISPLAY_STATE_ON, data.slice_by_state(0).value());
    ASSERT_EQ(2, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(1, data.bucket_info(1).count());
}

/**
 * Test a count metric that has one slice_by_state with a mapping and no
 * primary fields.
 *
 * Once the CountMetricProducer is initialized, it has one atom id in
 * mSlicedStateAtoms and has one entry per state value in mStateGroupMap.
 *
 * One StateTracker tracks the state atom, and it has one listener which is the
 * CountMetricProducer that was initialized.
 */
TEST(CountMetricE2eTest, TestSlicedStateWithMap) {
    // Initialize config.
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");  // LogEvent defaults to UID of root.

    auto syncStartMatcher = CreateSyncStartAtomMatcher();
    *config.add_atom_matcher() = syncStartMatcher;

    int64_t screenOnId = 4444;
    int64_t screenOffId = 9876;
    auto state = CreateScreenStateWithOnOffMap(screenOnId, screenOffId);
    *config.add_state() = state;

    // Create count metric that slices by screen state with on/off map.
    int64_t metricId = 123456;
    auto countMetric = config.add_count_metric();
    countMetric->set_id(metricId);
    countMetric->set_what(syncStartMatcher.id());
    countMetric->set_bucket(TimeUnit::FIVE_MINUTES);
    countMetric->add_slice_by_state(state.id());

    // Initialize StatsLogProcessor.
    const uint64_t bucketStartTimeNs = 10000000000;  // 0:10
    const uint64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.count_metric(0).bucket()) * 1000000LL;
    int uid = 12345;
    int64_t cfgId = 98765;
    ConfigKey cfgKey(uid, cfgId);
    auto processor = CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);

    // Check that StateTrackers were initialized correctly.
    EXPECT_EQ(1, StateManager::getInstance().getStateTrackersCount());
    EXPECT_EQ(1, StateManager::getInstance().getListenersCount(SCREEN_STATE_ATOM_ID));

    // Check that CountMetricProducer was initialized correctly.
    ASSERT_EQ(processor->mMetricsManagers.size(), 1u);
    sp<MetricsManager> metricsManager = processor->mMetricsManagers.begin()->second;
    EXPECT_TRUE(metricsManager->isConfigValid());
    ASSERT_EQ(metricsManager->mAllMetricProducers.size(), 1);
    sp<MetricProducer> metricProducer = metricsManager->mAllMetricProducers[0];
    ASSERT_EQ(metricProducer->mSlicedStateAtoms.size(), 1);
    EXPECT_EQ(metricProducer->mSlicedStateAtoms.at(0), SCREEN_STATE_ATOM_ID);
    ASSERT_EQ(metricProducer->mStateGroupMap.size(), 1);

    StateMap map = state.map();
    for (auto group : map.group()) {
        for (auto value : group.value()) {
            EXPECT_EQ(metricProducer->mStateGroupMap.at(SCREEN_STATE_ATOM_ID).at(value),
                      group.group_id());
        }
    }

    /*
               bucket #1                      bucket #2
    |     1     2     3     4     5     6     7     8     9     10 (minutes)
    |-----------------------------|-----------------------------|--
      x   x     x       x    x   x      x         x         x       (syncStartEvents)
     -----------------------------------------------------------SCREEN_OFF events
             |                  |                                   (ScreenStateOffEvent = 1)
       |                  |                                         (ScreenStateDozeEvent = 3)
                                                |                   (ScreenStateDozeSuspendEvent =
    4)
     -----------------------------------------------------------SCREEN_ON events
                   |                                       |        (ScreenStateOnEvent = 2)
                      |                                             (ScreenStateVrEvent = 5)
                                            |                       (ScreenStateOnSuspendEvent = 6)
    */
    // Initialize log events - first bucket.
    std::vector<int> attributionUids1 = {123};
    std::vector<string> attributionTags1 = {"App1"};

    std::vector<std::unique_ptr<LogEvent>> events;
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 20 * NS_PER_SEC, attributionUids1,
                                          attributionTags1, "sync_name"));  // 0:30
    events.push_back(CreateScreenStateChangedEvent(
            bucketStartTimeNs + 30 * NS_PER_SEC,
            android::view::DisplayStateEnum::DISPLAY_STATE_DOZE));  // 0:40
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 60 * NS_PER_SEC, attributionUids1,
                                          attributionTags1, "sync_name"));  // 1:10
    events.push_back(CreateScreenStateChangedEvent(
            bucketStartTimeNs + 90 * NS_PER_SEC,
            android::view::DisplayStateEnum::DISPLAY_STATE_OFF));  // 1:40
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 120 * NS_PER_SEC, attributionUids1,
                                          attributionTags1, "sync_name"));  // 2:10
    events.push_back(CreateScreenStateChangedEvent(
            bucketStartTimeNs + 150 * NS_PER_SEC,
            android::view::DisplayStateEnum::DISPLAY_STATE_ON));  // 2:40
    events.push_back(CreateScreenStateChangedEvent(
            bucketStartTimeNs + 180 * NS_PER_SEC,
            android::view::DisplayStateEnum::DISPLAY_STATE_VR));  // 3:10
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 200 * NS_PER_SEC, attributionUids1,
                                          attributionTags1, "sync_name"));  // 3:30
    events.push_back(CreateScreenStateChangedEvent(
            bucketStartTimeNs + 210 * NS_PER_SEC,
            android::view::DisplayStateEnum::DISPLAY_STATE_DOZE));  // 3:40
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 250 * NS_PER_SEC, attributionUids1,
                                          attributionTags1, "sync_name"));  // 4:20
    events.push_back(CreateScreenStateChangedEvent(
            bucketStartTimeNs + 280 * NS_PER_SEC,
            android::view::DisplayStateEnum::DISPLAY_STATE_OFF));  // 4:50
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 285 * NS_PER_SEC, attributionUids1,
                                          attributionTags1, "sync_name"));  // 4:55

    // Initialize log events - second bucket.
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 360 * NS_PER_SEC, attributionUids1,
                                          attributionTags1, "sync_name"));  // 6:10
    events.push_back(CreateScreenStateChangedEvent(
            bucketStartTimeNs + 390 * NS_PER_SEC,
            android::view::DisplayStateEnum::DISPLAY_STATE_ON_SUSPEND));  // 6:40
    events.push_back(CreateScreenStateChangedEvent(
            bucketStartTimeNs + 430 * NS_PER_SEC,
            android::view::DisplayStateEnum::DISPLAY_STATE_DOZE_SUSPEND));  // 7:20
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 440 * NS_PER_SEC, attributionUids1,
                                          attributionTags1, "sync_name"));  // 7:30
    events.push_back(CreateScreenStateChangedEvent(
            bucketStartTimeNs + 540 * NS_PER_SEC,
            android::view::DisplayStateEnum::DISPLAY_STATE_ON));  // 9:10
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 570 * NS_PER_SEC, attributionUids1,
                                          attributionTags1, "sync_name"));  // 9:40

    // Send log events to StatsLogProcessor.
    for (auto& event : events) {
        processor->OnLogEvent(event.get());
    }

    // Check dump report.
    vector<uint8_t> buffer;
    ConfigMetricsReportList reports;
    processor->onDumpReport(cfgKey, bucketStartTimeNs + bucketSizeNs * 2 + 1, false, true, ADB_DUMP,
                            FAST, &buffer);
    ASSERT_GT(buffer.size(), 0);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);

    ASSERT_EQ(1, reports.reports_size());
    ASSERT_EQ(1, reports.reports(0).metrics_size());
    EXPECT_TRUE(reports.reports(0).metrics(0).has_count_metrics());
    StatsLogReport::CountMetricDataWrapper countMetrics;
    sortMetricDataByDimensionsValue(reports.reports(0).metrics(0).count_metrics(), &countMetrics);
    ASSERT_EQ(3, countMetrics.data_size());

    // For each CountMetricData, check StateValue info is correct and buckets
    // have correct counts.
    auto data = countMetrics.data(0);
    ASSERT_EQ(1, data.slice_by_state_size());
    EXPECT_EQ(SCREEN_STATE_ATOM_ID, data.slice_by_state(0).atom_id());
    EXPECT_TRUE(data.slice_by_state(0).has_value());
    EXPECT_EQ(-1 /* StateTracker::kStateUnknown */, data.slice_by_state(0).value());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());

    data = countMetrics.data(1);
    ASSERT_EQ(1, data.slice_by_state_size());
    EXPECT_EQ(SCREEN_STATE_ATOM_ID, data.slice_by_state(0).atom_id());
    EXPECT_TRUE(data.slice_by_state(0).has_group_id());
    EXPECT_EQ(screenOnId, data.slice_by_state(0).group_id());
    ASSERT_EQ(2, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(1, data.bucket_info(1).count());

    data = countMetrics.data(2);
    ASSERT_EQ(1, data.slice_by_state_size());
    EXPECT_EQ(SCREEN_STATE_ATOM_ID, data.slice_by_state(0).atom_id());
    EXPECT_TRUE(data.slice_by_state(0).has_group_id());
    EXPECT_EQ(screenOffId, data.slice_by_state(0).group_id());
    ASSERT_EQ(2, data.bucket_info_size());
    EXPECT_EQ(4, data.bucket_info(0).count());
    EXPECT_EQ(2, data.bucket_info(1).count());
}

/**
* Test a count metric that has one slice_by_state with a primary field.

* Once the CountMetricProducer is initialized, it should have one
* MetricStateLink stored. State querying using a non-empty primary key
* should also work as intended.
*/
TEST(CountMetricE2eTest, TestSlicedStateWithPrimaryFields) {
    // Initialize config.
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");  // LogEvent defaults to UID of root.

    auto appCrashMatcher =
            CreateSimpleAtomMatcher("APP_CRASH_OCCURRED", util::APP_CRASH_OCCURRED);
    *config.add_atom_matcher() = appCrashMatcher;

    auto state = CreateUidProcessState();
    *config.add_state() = state;

    // Create count metric that slices by uid process state.
    int64_t metricId = 123456;
    auto countMetric = config.add_count_metric();
    countMetric->set_id(metricId);
    countMetric->set_what(appCrashMatcher.id());
    countMetric->set_bucket(TimeUnit::FIVE_MINUTES);
    countMetric->add_slice_by_state(state.id());
    MetricStateLink* stateLink = countMetric->add_state_link();
    stateLink->set_state_atom_id(UID_PROCESS_STATE_ATOM_ID);
    auto fieldsInWhat = stateLink->mutable_fields_in_what();
    *fieldsInWhat = CreateDimensions(util::APP_CRASH_OCCURRED, {1 /*uid*/});
    auto fieldsInState = stateLink->mutable_fields_in_state();
    *fieldsInState = CreateDimensions(UID_PROCESS_STATE_ATOM_ID, {1 /*uid*/});

    // Initialize StatsLogProcessor.
    const uint64_t bucketStartTimeNs = 10000000000;  // 0:10
    const uint64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.count_metric(0).bucket()) * 1000000LL;
    int uid = 12345;
    int64_t cfgId = 98765;
    ConfigKey cfgKey(uid, cfgId);
    auto processor = CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);

    // Check that StateTrackers were initialized correctly.
    EXPECT_EQ(1, StateManager::getInstance().getStateTrackersCount());
    EXPECT_EQ(1, StateManager::getInstance().getListenersCount(UID_PROCESS_STATE_ATOM_ID));

    // Check that CountMetricProducer was initialized correctly.
    ASSERT_EQ(processor->mMetricsManagers.size(), 1u);
    sp<MetricsManager> metricsManager = processor->mMetricsManagers.begin()->second;
    EXPECT_TRUE(metricsManager->isConfigValid());
    ASSERT_EQ(metricsManager->mAllMetricProducers.size(), 1);
    sp<MetricProducer> metricProducer = metricsManager->mAllMetricProducers[0];
    ASSERT_EQ(metricProducer->mSlicedStateAtoms.size(), 1);
    EXPECT_EQ(metricProducer->mSlicedStateAtoms.at(0), UID_PROCESS_STATE_ATOM_ID);
    ASSERT_EQ(metricProducer->mStateGroupMap.size(), 0);
    ASSERT_EQ(metricProducer->mMetric2StateLinks.size(), 1);

    /*
    NOTE: "1" or "2" represents the uid associated with the state/app crash event
               bucket #1               bucket #2
    |    1    2    3    4    5    6    7    8    9    10
    |------------------------|-------------------------|--
      1  1    1      1   1  2     1        1        2    (AppCrashEvents)
     -----------------------------------------------------PROCESS STATE events
           1               2                             (TopEvent = 1002)
                       1             1                   (ForegroundServiceEvent = 1003)
                                         2               (ImportantBackgroundEvent = 1006)
       1          1                               1      (ImportantForegroundEvent = 1005)

    Based on the diagram above, an AppCrashEvent querying for process state value would return:
    - StateTracker::kStateUnknown
    - Important foreground
    - Top
    - Important foreground
    - Foreground service
    - Top (both the app crash and state still have matching uid = 2)

    - Foreground service
    - Foreground service
    - Important background
    */
    // Initialize log events - first bucket.
    std::vector<std::unique_ptr<LogEvent>> events;
    events.push_back(
            CreateAppCrashOccurredEvent(bucketStartTimeNs + 20 * NS_PER_SEC, 1 /*uid*/));  // 0:30
    events.push_back(CreateUidProcessStateChangedEvent(
            bucketStartTimeNs + 30 * NS_PER_SEC, 1 /*uid*/,
            android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_FOREGROUND));  // 0:40
    events.push_back(
            CreateAppCrashOccurredEvent(bucketStartTimeNs + 60 * NS_PER_SEC, 1 /*uid*/));  // 1:10
    events.push_back(CreateUidProcessStateChangedEvent(
            bucketStartTimeNs + 90 * NS_PER_SEC, 1 /*uid*/,
            android::app::ProcessStateEnum::PROCESS_STATE_TOP));  // 1:40
    events.push_back(
            CreateAppCrashOccurredEvent(bucketStartTimeNs + 120 * NS_PER_SEC, 1 /*uid*/));  // 2:10
    events.push_back(CreateUidProcessStateChangedEvent(
            bucketStartTimeNs + 150 * NS_PER_SEC, 1 /*uid*/,
            android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_FOREGROUND));  // 2:40
    events.push_back(
            CreateAppCrashOccurredEvent(bucketStartTimeNs + 200 * NS_PER_SEC, 1 /*uid*/));  // 3:30
    events.push_back(CreateUidProcessStateChangedEvent(
            bucketStartTimeNs + 210 * NS_PER_SEC, 1 /*uid*/,
            android::app::ProcessStateEnum::PROCESS_STATE_FOREGROUND_SERVICE));  // 3:40
    events.push_back(
            CreateAppCrashOccurredEvent(bucketStartTimeNs + 250 * NS_PER_SEC, 1 /*uid*/));  // 4:20
    events.push_back(CreateUidProcessStateChangedEvent(
            bucketStartTimeNs + 280 * NS_PER_SEC, 2 /*uid*/,
            android::app::ProcessStateEnum::PROCESS_STATE_TOP));  // 4:50
    events.push_back(
            CreateAppCrashOccurredEvent(bucketStartTimeNs + 285 * NS_PER_SEC, 2 /*uid*/));  // 4:55

    // Initialize log events - second bucket.
    events.push_back(
            CreateAppCrashOccurredEvent(bucketStartTimeNs + 360 * NS_PER_SEC, 1 /*uid*/));  // 6:10
    events.push_back(CreateUidProcessStateChangedEvent(
            bucketStartTimeNs + 390 * NS_PER_SEC, 1 /*uid*/,
            android::app::ProcessStateEnum::PROCESS_STATE_FOREGROUND_SERVICE));  // 6:40
    events.push_back(CreateUidProcessStateChangedEvent(
            bucketStartTimeNs + 430 * NS_PER_SEC, 2 /*uid*/,
            android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_BACKGROUND));  // 7:20
    events.push_back(
            CreateAppCrashOccurredEvent(bucketStartTimeNs + 440 * NS_PER_SEC, 1 /*uid*/));  // 7:30
    events.push_back(CreateUidProcessStateChangedEvent(
            bucketStartTimeNs + 540 * NS_PER_SEC, 1 /*uid*/,
            android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_FOREGROUND));  // 9:10
    events.push_back(
            CreateAppCrashOccurredEvent(bucketStartTimeNs + 570 * NS_PER_SEC, 2 /*uid*/));  // 9:40

    // Send log events to StatsLogProcessor.
    for (auto& event : events) {
        processor->OnLogEvent(event.get());
    }

    // Check dump report.
    vector<uint8_t> buffer;
    ConfigMetricsReportList reports;
    processor->onDumpReport(cfgKey, bucketStartTimeNs + bucketSizeNs * 2 + 1, false, true, ADB_DUMP,
                            FAST, &buffer);
    ASSERT_GT(buffer.size(), 0);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);

    ASSERT_EQ(1, reports.reports_size());
    ASSERT_EQ(1, reports.reports(0).metrics_size());
    EXPECT_TRUE(reports.reports(0).metrics(0).has_count_metrics());
    StatsLogReport::CountMetricDataWrapper countMetrics;
    sortMetricDataByDimensionsValue(reports.reports(0).metrics(0).count_metrics(), &countMetrics);
    ASSERT_EQ(5, countMetrics.data_size());

    // For each CountMetricData, check StateValue info is correct and buckets
    // have correct counts.
    auto data = countMetrics.data(0);
    ASSERT_EQ(1, data.slice_by_state_size());
    EXPECT_EQ(UID_PROCESS_STATE_ATOM_ID, data.slice_by_state(0).atom_id());
    EXPECT_TRUE(data.slice_by_state(0).has_value());
    EXPECT_EQ(-1 /* StateTracker::kStateUnknown */, data.slice_by_state(0).value());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());

    data = countMetrics.data(1);
    ASSERT_EQ(1, data.slice_by_state_size());
    EXPECT_EQ(UID_PROCESS_STATE_ATOM_ID, data.slice_by_state(0).atom_id());
    EXPECT_TRUE(data.slice_by_state(0).has_value());
    EXPECT_EQ(android::app::PROCESS_STATE_TOP, data.slice_by_state(0).value());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(2, data.bucket_info(0).count());

    data = countMetrics.data(2);
    ASSERT_EQ(1, data.slice_by_state_size());
    EXPECT_EQ(UID_PROCESS_STATE_ATOM_ID, data.slice_by_state(0).atom_id());
    EXPECT_TRUE(data.slice_by_state(0).has_value());
    EXPECT_EQ(android::app::PROCESS_STATE_FOREGROUND_SERVICE, data.slice_by_state(0).value());
    ASSERT_EQ(2, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(2, data.bucket_info(1).count());

    data = countMetrics.data(3);
    ASSERT_EQ(1, data.slice_by_state_size());
    EXPECT_EQ(UID_PROCESS_STATE_ATOM_ID, data.slice_by_state(0).atom_id());
    EXPECT_TRUE(data.slice_by_state(0).has_value());
    EXPECT_EQ(android::app::PROCESS_STATE_IMPORTANT_FOREGROUND, data.slice_by_state(0).value());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(2, data.bucket_info(0).count());

    data = countMetrics.data(4);
    ASSERT_EQ(1, data.slice_by_state_size());
    EXPECT_EQ(UID_PROCESS_STATE_ATOM_ID, data.slice_by_state(0).atom_id());
    EXPECT_TRUE(data.slice_by_state(0).has_value());
    EXPECT_EQ(android::app::PROCESS_STATE_IMPORTANT_BACKGROUND, data.slice_by_state(0).value());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
}

TEST(CountMetricE2eTest, TestMultipleSlicedStates) {
    // Initialize config.
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");  // LogEvent defaults to UID of root.

    auto appCrashMatcher =
            CreateSimpleAtomMatcher("APP_CRASH_OCCURRED", util::APP_CRASH_OCCURRED);
    *config.add_atom_matcher() = appCrashMatcher;

    int64_t screenOnId = 4444;
    int64_t screenOffId = 9876;
    auto state1 = CreateScreenStateWithOnOffMap(screenOnId, screenOffId);
    *config.add_state() = state1;
    auto state2 = CreateUidProcessState();
    *config.add_state() = state2;

    // Create count metric that slices by screen state with on/off map and
    // slices by uid process state.
    int64_t metricId = 123456;
    auto countMetric = config.add_count_metric();
    countMetric->set_id(metricId);
    countMetric->set_what(appCrashMatcher.id());
    countMetric->set_bucket(TimeUnit::FIVE_MINUTES);
    countMetric->add_slice_by_state(state1.id());
    countMetric->add_slice_by_state(state2.id());
    MetricStateLink* stateLink = countMetric->add_state_link();
    stateLink->set_state_atom_id(UID_PROCESS_STATE_ATOM_ID);
    auto fieldsInWhat = stateLink->mutable_fields_in_what();
    *fieldsInWhat = CreateDimensions(util::APP_CRASH_OCCURRED, {1 /*uid*/});
    auto fieldsInState = stateLink->mutable_fields_in_state();
    *fieldsInState = CreateDimensions(UID_PROCESS_STATE_ATOM_ID, {1 /*uid*/});

    // Initialize StatsLogProcessor.
    const uint64_t bucketStartTimeNs = 10000000000;  // 0:10
    const uint64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.count_metric(0).bucket()) * 1000000LL;
    int uid = 12345;
    int64_t cfgId = 98765;
    ConfigKey cfgKey(uid, cfgId);
    auto processor = CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);

    // Check that StateTrackers were properly initialized.
    EXPECT_EQ(2, StateManager::getInstance().getStateTrackersCount());
    EXPECT_EQ(1, StateManager::getInstance().getListenersCount(SCREEN_STATE_ATOM_ID));
    EXPECT_EQ(1, StateManager::getInstance().getListenersCount(UID_PROCESS_STATE_ATOM_ID));

    // Check that CountMetricProducer was initialized correctly.
    ASSERT_EQ(processor->mMetricsManagers.size(), 1u);
    sp<MetricsManager> metricsManager = processor->mMetricsManagers.begin()->second;
    EXPECT_TRUE(metricsManager->isConfigValid());
    ASSERT_EQ(metricsManager->mAllMetricProducers.size(), 1);
    sp<MetricProducer> metricProducer = metricsManager->mAllMetricProducers[0];
    ASSERT_EQ(metricProducer->mSlicedStateAtoms.size(), 2);
    EXPECT_EQ(metricProducer->mSlicedStateAtoms.at(0), SCREEN_STATE_ATOM_ID);
    EXPECT_EQ(metricProducer->mSlicedStateAtoms.at(1), UID_PROCESS_STATE_ATOM_ID);
    ASSERT_EQ(metricProducer->mStateGroupMap.size(), 1);
    ASSERT_EQ(metricProducer->mMetric2StateLinks.size(), 1);

    StateMap map = state1.map();
    for (auto group : map.group()) {
        for (auto value : group.value()) {
            EXPECT_EQ(metricProducer->mStateGroupMap.at(SCREEN_STATE_ATOM_ID).at(value),
                      group.group_id());
        }
    }

    /*
                 bucket #1                      bucket #2
      |    1    2    3    4    5    6    7    8    9    10 (minutes)
      |------------------------|------------------------|--
        1  1    1     1    1  2     1        1         2   (AppCrashEvents)
       ---------------------------------------------------SCREEN_OFF events
             |                              |              (ScreenOffEvent = 1)
         |              |                                  (ScreenDozeEvent = 3)
       ---------------------------------------------------SCREEN_ON events
                   |                              |        (ScreenOnEvent = 2)
                                        |                  (ScreenOnSuspendEvent = 6)
       ---------------------------------------------------PROCESS STATE events
             1               2                             (TopEvent = 1002)
                                      1                    (ForegroundServiceEvent = 1003)
                                            2              (ImportantBackgroundEvent = 1006)
       1          1                                   1    (ImportantForegroundEvent = 1005)

       Based on the diagram above, Screen State / Process State pairs for each
       AppCrashEvent are:
       - StateTracker::kStateUnknown / important foreground
       - off / important foreground
       - off / Top
       - on / important foreground
       - off / important foreground
       - off / top

       - off / important foreground
       - off / foreground service
       - on / important background

      */
    // Initialize log events - first bucket.
    std::vector<std::unique_ptr<LogEvent>> events;
    events.push_back(CreateUidProcessStateChangedEvent(
            bucketStartTimeNs + 5 * NS_PER_SEC, 1 /*uid*/,
            android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_FOREGROUND));  // 0:15
    events.push_back(
            CreateAppCrashOccurredEvent(bucketStartTimeNs + 20 * NS_PER_SEC, 1 /*uid*/));  // 0:30
    events.push_back(CreateScreenStateChangedEvent(
            bucketStartTimeNs + 30 * NS_PER_SEC,
            android::view::DisplayStateEnum::DISPLAY_STATE_DOZE));  // 0:40
    events.push_back(
            CreateAppCrashOccurredEvent(bucketStartTimeNs + 60 * NS_PER_SEC, 1 /*uid*/));  // 1:10
    events.push_back(CreateUidProcessStateChangedEvent(
            bucketStartTimeNs + 90 * NS_PER_SEC, 1 /*uid*/,
            android::app::ProcessStateEnum::PROCESS_STATE_TOP));  // 1:40
    events.push_back(CreateScreenStateChangedEvent(
            bucketStartTimeNs + 90 * NS_PER_SEC,
            android::view::DisplayStateEnum::DISPLAY_STATE_OFF));  // 1:40
    events.push_back(
            CreateAppCrashOccurredEvent(bucketStartTimeNs + 120 * NS_PER_SEC, 1 /*uid*/));  // 2:10
    events.push_back(CreateUidProcessStateChangedEvent(
            bucketStartTimeNs + 150 * NS_PER_SEC, 1 /*uid*/,
            android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_FOREGROUND));  // 2:40
    events.push_back(CreateScreenStateChangedEvent(
            bucketStartTimeNs + 160 * NS_PER_SEC,
            android::view::DisplayStateEnum::DISPLAY_STATE_ON));  // 2:50
    events.push_back(
            CreateAppCrashOccurredEvent(bucketStartTimeNs + 200 * NS_PER_SEC, 1 /*uid*/));  // 3:30
    events.push_back(CreateScreenStateChangedEvent(
            bucketStartTimeNs + 210 * NS_PER_SEC,
            android::view::DisplayStateEnum::DISPLAY_STATE_DOZE));  // 3:40
    events.push_back(
            CreateAppCrashOccurredEvent(bucketStartTimeNs + 250 * NS_PER_SEC, 1 /*uid*/));  // 4:20
    events.push_back(CreateUidProcessStateChangedEvent(
            bucketStartTimeNs + 280 * NS_PER_SEC, 2 /*uid*/,
            android::app::ProcessStateEnum::PROCESS_STATE_TOP));  // 4:50
    events.push_back(
            CreateAppCrashOccurredEvent(bucketStartTimeNs + 285 * NS_PER_SEC, 2 /*uid*/));  // 4:55

    // Initialize log events - second bucket.
    events.push_back(
            CreateAppCrashOccurredEvent(bucketStartTimeNs + 360 * NS_PER_SEC, 1 /*uid*/));  // 6:10
    events.push_back(CreateUidProcessStateChangedEvent(
            bucketStartTimeNs + 380 * NS_PER_SEC, 1 /*uid*/,
            android::app::ProcessStateEnum::PROCESS_STATE_FOREGROUND_SERVICE));  // 6:30
    events.push_back(CreateScreenStateChangedEvent(
            bucketStartTimeNs + 390 * NS_PER_SEC,
            android::view::DisplayStateEnum::DISPLAY_STATE_ON_SUSPEND));  // 6:40
    events.push_back(CreateUidProcessStateChangedEvent(
            bucketStartTimeNs + 420 * NS_PER_SEC, 2 /*uid*/,
            android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_BACKGROUND));  // 7:10
    events.push_back(CreateScreenStateChangedEvent(
            bucketStartTimeNs + 440 * NS_PER_SEC,
            android::view::DisplayStateEnum::DISPLAY_STATE_OFF));  // 7:30
    events.push_back(
            CreateAppCrashOccurredEvent(bucketStartTimeNs + 450 * NS_PER_SEC, 1 /*uid*/));  // 7:40
    events.push_back(CreateScreenStateChangedEvent(
            bucketStartTimeNs + 520 * NS_PER_SEC,
            android::view::DisplayStateEnum::DISPLAY_STATE_ON));  // 8:50
    events.push_back(CreateUidProcessStateChangedEvent(
            bucketStartTimeNs + 540 * NS_PER_SEC, 1 /*uid*/,
            android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_FOREGROUND));  // 9:10
    events.push_back(
            CreateAppCrashOccurredEvent(bucketStartTimeNs + 570 * NS_PER_SEC, 2 /*uid*/));  // 9:40

    // Send log events to StatsLogProcessor.
    for (auto& event : events) {
        processor->OnLogEvent(event.get());
    }

    // Check dump report.
    vector<uint8_t> buffer;
    ConfigMetricsReportList reports;
    processor->onDumpReport(cfgKey, bucketStartTimeNs + bucketSizeNs * 2 + 1, false, true, ADB_DUMP,
                            FAST, &buffer);
    ASSERT_GT(buffer.size(), 0);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);

    ASSERT_EQ(1, reports.reports_size());
    ASSERT_EQ(1, reports.reports(0).metrics_size());
    EXPECT_TRUE(reports.reports(0).metrics(0).has_count_metrics());
    StatsLogReport::CountMetricDataWrapper countMetrics;
    sortMetricDataByDimensionsValue(reports.reports(0).metrics(0).count_metrics(), &countMetrics);
    ASSERT_EQ(6, countMetrics.data_size());

    // For each CountMetricData, check StateValue info is correct and buckets
    // have correct counts.
    auto data = countMetrics.data(0);
    ASSERT_EQ(2, data.slice_by_state_size());
    EXPECT_EQ(SCREEN_STATE_ATOM_ID, data.slice_by_state(0).atom_id());
    EXPECT_TRUE(data.slice_by_state(0).has_value());
    EXPECT_EQ(-1, data.slice_by_state(0).value());
    EXPECT_EQ(UID_PROCESS_STATE_ATOM_ID, data.slice_by_state(1).atom_id());
    EXPECT_TRUE(data.slice_by_state(1).has_value());
    EXPECT_EQ(android::app::PROCESS_STATE_IMPORTANT_FOREGROUND, data.slice_by_state(1).value());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());

    data = countMetrics.data(1);
    ASSERT_EQ(2, data.slice_by_state_size());
    EXPECT_EQ(SCREEN_STATE_ATOM_ID, data.slice_by_state(0).atom_id());
    EXPECT_TRUE(data.slice_by_state(0).has_group_id());
    EXPECT_EQ(screenOnId, data.slice_by_state(0).group_id());
    EXPECT_EQ(UID_PROCESS_STATE_ATOM_ID, data.slice_by_state(1).atom_id());
    EXPECT_TRUE(data.slice_by_state(1).has_value());
    EXPECT_EQ(android::app::PROCESS_STATE_IMPORTANT_FOREGROUND, data.slice_by_state(1).value());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());

    data = countMetrics.data(2);
    ASSERT_EQ(2, data.slice_by_state_size());
    EXPECT_EQ(SCREEN_STATE_ATOM_ID, data.slice_by_state(0).atom_id());
    EXPECT_TRUE(data.slice_by_state(0).has_group_id());
    EXPECT_EQ(screenOnId, data.slice_by_state(0).group_id());
    EXPECT_EQ(UID_PROCESS_STATE_ATOM_ID, data.slice_by_state(1).atom_id());
    EXPECT_TRUE(data.slice_by_state(1).has_value());
    EXPECT_EQ(android::app::PROCESS_STATE_IMPORTANT_BACKGROUND, data.slice_by_state(1).value());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());

    data = countMetrics.data(3);
    ASSERT_EQ(2, data.slice_by_state_size());
    EXPECT_EQ(SCREEN_STATE_ATOM_ID, data.slice_by_state(0).atom_id());
    EXPECT_TRUE(data.slice_by_state(0).has_group_id());
    EXPECT_EQ(screenOffId, data.slice_by_state(0).group_id());
    EXPECT_EQ(UID_PROCESS_STATE_ATOM_ID, data.slice_by_state(1).atom_id());
    EXPECT_TRUE(data.slice_by_state(1).has_value());
    EXPECT_EQ(android::app::PROCESS_STATE_TOP, data.slice_by_state(1).value());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(2, data.bucket_info(0).count());

    data = countMetrics.data(4);
    ASSERT_EQ(2, data.slice_by_state_size());
    EXPECT_EQ(SCREEN_STATE_ATOM_ID, data.slice_by_state(0).atom_id());
    EXPECT_TRUE(data.slice_by_state(0).has_group_id());
    EXPECT_EQ(screenOffId, data.slice_by_state(0).group_id());
    EXPECT_EQ(UID_PROCESS_STATE_ATOM_ID, data.slice_by_state(1).atom_id());
    EXPECT_TRUE(data.slice_by_state(1).has_value());
    EXPECT_EQ(android::app::PROCESS_STATE_FOREGROUND_SERVICE, data.slice_by_state(1).value());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());

    data = countMetrics.data(5);
    ASSERT_EQ(2, data.slice_by_state_size());
    EXPECT_EQ(SCREEN_STATE_ATOM_ID, data.slice_by_state(0).atom_id());
    EXPECT_TRUE(data.slice_by_state(0).has_group_id());
    EXPECT_EQ(screenOffId, data.slice_by_state(0).group_id());
    EXPECT_EQ(UID_PROCESS_STATE_ATOM_ID, data.slice_by_state(1).atom_id());
    EXPECT_TRUE(data.slice_by_state(1).has_value());
    EXPECT_EQ(android::app::PROCESS_STATE_IMPORTANT_FOREGROUND, data.slice_by_state(1).value());
    ASSERT_EQ(2, data.bucket_info_size());
    EXPECT_EQ(2, data.bucket_info(0).count());
    EXPECT_EQ(1, data.bucket_info(1).count());
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
