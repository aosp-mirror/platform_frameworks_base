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

#include <android/binder_interface_utils.h>
#include <gtest/gtest.h>

#include "src/StatsLogProcessor.h"
#include "src/stats_log_util.h"
#include "tests/statsd_test_util.h"

#include <vector>

using ::ndk::SharedRefBase;

namespace android {
namespace os {
namespace statsd {

#ifdef __ANDROID__

namespace {

const int64_t metricId = 123456;

StatsdConfig CreateStatsdConfig(bool useCondition = true) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT"); // LogEvent defaults to UID of root.
    auto pulledAtomMatcher =
            CreateSimpleAtomMatcher("TestMatcher", android::util::SUBSYSTEM_SLEEP_STATE);
    *config.add_atom_matcher() = pulledAtomMatcher;
    *config.add_atom_matcher() = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = CreateScreenTurnedOffAtomMatcher();

    auto screenIsOffPredicate = CreateScreenIsOffPredicate();
    *config.add_predicate() = screenIsOffPredicate;

    auto valueMetric = config.add_value_metric();
    valueMetric->set_id(metricId);
    valueMetric->set_what(pulledAtomMatcher.id());
    if (useCondition) {
        valueMetric->set_condition(screenIsOffPredicate.id());
    }
    *valueMetric->mutable_value_field() =
            CreateDimensions(android::util::SUBSYSTEM_SLEEP_STATE, {4 /* time sleeping field */});
    *valueMetric->mutable_dimensions_in_what() =
            CreateDimensions(android::util::SUBSYSTEM_SLEEP_STATE, {1 /* subsystem name */});
    valueMetric->set_bucket(FIVE_MINUTES);
    valueMetric->set_use_absolute_value_on_reset(true);
    valueMetric->set_skip_zero_diff_output(false);
    valueMetric->set_max_pull_delay_sec(INT_MAX);
    return config;
}

}  // namespace

TEST(ValueMetricE2eTest, TestPulledEvents) {
    auto config = CreateStatsdConfig();
    int64_t baseTimeNs = getElapsedRealtimeNs();
    int64_t configAddedTimeNs = 10 * 60 * NS_PER_SEC + baseTimeNs;
    int64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(config.value_metric(0).bucket()) * 1000000;

    ConfigKey cfgKey;
    auto processor = CreateStatsLogProcessor(baseTimeNs, configAddedTimeNs, config, cfgKey,
                                             SharedRefBase::make<FakeSubsystemSleepCallback>(),
                                             android::util::SUBSYSTEM_SLEEP_STATE);
    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());
    processor->mPullerManager->ForceClearPullerCache();

    int startBucketNum = processor->mMetricsManagers.begin()
                                 ->second->mAllMetricProducers[0]
                                 ->getCurrentBucketNum();
    EXPECT_GT(startBucketNum, (int64_t)0);

    // When creating the config, the value metric producer should register the alarm at the
    // end of the current bucket.
    EXPECT_EQ((size_t)1, processor->mPullerManager->mReceivers.size());
    EXPECT_EQ(bucketSizeNs,
              processor->mPullerManager->mReceivers.begin()->second.front().intervalNs);
    int64_t& expectedPullTimeNs =
            processor->mPullerManager->mReceivers.begin()->second.front().nextPullTimeNs;
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + bucketSizeNs, expectedPullTimeNs);

    auto screenOffEvent =
            CreateScreenStateChangedEvent(configAddedTimeNs + 55, android::view::DISPLAY_STATE_OFF);
    processor->OnLogEvent(screenOffEvent.get());

    auto screenOnEvent =
            CreateScreenStateChangedEvent(configAddedTimeNs + 65, android::view::DISPLAY_STATE_ON);
    processor->OnLogEvent(screenOnEvent.get());

    screenOffEvent =
            CreateScreenStateChangedEvent(configAddedTimeNs + 75, android::view::DISPLAY_STATE_OFF);
    processor->OnLogEvent(screenOffEvent.get());

    // Pulling alarm arrives on time and reset the sequential pulling alarm.
    processor->informPullAlarmFired(expectedPullTimeNs + 1);
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + 2 * bucketSizeNs, expectedPullTimeNs);

    processor->informPullAlarmFired(expectedPullTimeNs + 1);

    screenOnEvent = CreateScreenStateChangedEvent(configAddedTimeNs + 2 * bucketSizeNs + 15,
                                                  android::view::DISPLAY_STATE_ON);
    processor->OnLogEvent(screenOnEvent.get());

    processor->informPullAlarmFired(expectedPullTimeNs + 1);

    processor->informPullAlarmFired(expectedPullTimeNs + 1);

    screenOffEvent = CreateScreenStateChangedEvent(configAddedTimeNs + 4 * bucketSizeNs + 11,
                                                   android::view::DISPLAY_STATE_OFF);
    processor->OnLogEvent(screenOffEvent.get());

    processor->informPullAlarmFired(expectedPullTimeNs + 1);

    processor->informPullAlarmFired(expectedPullTimeNs + 1);

    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(cfgKey, configAddedTimeNs + 7 * bucketSizeNs + 10, false, true,
                            ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(buffer.size() > 0);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);
    EXPECT_EQ(1, reports.reports_size());
    EXPECT_EQ(1, reports.reports(0).metrics_size());
    StatsLogReport::ValueMetricDataWrapper valueMetrics;
    sortMetricDataByDimensionsValue(reports.reports(0).metrics(0).value_metrics(), &valueMetrics);
    EXPECT_GT((int)valueMetrics.data_size(), 1);

    auto data = valueMetrics.data(0);
    EXPECT_EQ(android::util::SUBSYSTEM_SLEEP_STATE, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* subsystem name field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_FALSE(data.dimensions_in_what().value_tuple().dimensions_value(0).value_str().empty());
    // We have 4 buckets, the first one was incomplete since the condition was unknown.
    EXPECT_EQ(4, data.bucket_info_size());

    EXPECT_EQ(baseTimeNs + 3 * bucketSizeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 4 * bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());
    EXPECT_EQ(1, data.bucket_info(0).values_size());

    EXPECT_EQ(baseTimeNs + 4 * bucketSizeNs, data.bucket_info(1).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 5 * bucketSizeNs, data.bucket_info(1).end_bucket_elapsed_nanos());
    EXPECT_EQ(1, data.bucket_info(1).values_size());

    EXPECT_EQ(baseTimeNs + 6 * bucketSizeNs, data.bucket_info(2).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 7 * bucketSizeNs, data.bucket_info(2).end_bucket_elapsed_nanos());
    EXPECT_EQ(1, data.bucket_info(2).values_size());

    EXPECT_EQ(baseTimeNs + 7 * bucketSizeNs, data.bucket_info(3).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 8 * bucketSizeNs, data.bucket_info(3).end_bucket_elapsed_nanos());
    EXPECT_EQ(1, data.bucket_info(3).values_size());
}

TEST(ValueMetricE2eTest, TestPulledEvents_LateAlarm) {
    auto config = CreateStatsdConfig();
    int64_t baseTimeNs = getElapsedRealtimeNs();
    // 10 mins == 2 bucket durations.
    int64_t configAddedTimeNs = 10 * 60 * NS_PER_SEC + baseTimeNs;
    int64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(config.value_metric(0).bucket()) * 1000000;

    ConfigKey cfgKey;
    auto processor = CreateStatsLogProcessor(baseTimeNs, configAddedTimeNs, config, cfgKey,
                                             SharedRefBase::make<FakeSubsystemSleepCallback>(),
                                             android::util::SUBSYSTEM_SLEEP_STATE);
    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());
    processor->mPullerManager->ForceClearPullerCache();

    int startBucketNum = processor->mMetricsManagers.begin()
                                 ->second->mAllMetricProducers[0]
                                 ->getCurrentBucketNum();
    EXPECT_GT(startBucketNum, (int64_t)0);

    // When creating the config, the value metric producer should register the alarm at the
    // end of the current bucket.
    EXPECT_EQ((size_t)1, processor->mPullerManager->mReceivers.size());
    EXPECT_EQ(bucketSizeNs,
              processor->mPullerManager->mReceivers.begin()->second.front().intervalNs);
    int64_t& expectedPullTimeNs =
            processor->mPullerManager->mReceivers.begin()->second.front().nextPullTimeNs;
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + bucketSizeNs, expectedPullTimeNs);

    // Screen off/on/off events.
    auto screenOffEvent =
            CreateScreenStateChangedEvent(configAddedTimeNs + 55, android::view::DISPLAY_STATE_OFF);
    processor->OnLogEvent(screenOffEvent.get());

    auto screenOnEvent =
            CreateScreenStateChangedEvent(configAddedTimeNs + 65, android::view::DISPLAY_STATE_ON);
    processor->OnLogEvent(screenOnEvent.get());

    screenOffEvent =
            CreateScreenStateChangedEvent(configAddedTimeNs + 75, android::view::DISPLAY_STATE_OFF);
    processor->OnLogEvent(screenOffEvent.get());

    // Pulling alarm arrives late by 2 buckets and 1 ns. 2 buckets late is too far away in the
    // future, data will be skipped.
    processor->informPullAlarmFired(expectedPullTimeNs + 2 * bucketSizeNs + 1);
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + 4 * bucketSizeNs, expectedPullTimeNs);

    // This screen state change will start a new bucket.
    screenOnEvent = CreateScreenStateChangedEvent(configAddedTimeNs + 4 * bucketSizeNs + 65,
                                                  android::view::DISPLAY_STATE_ON);
    processor->OnLogEvent(screenOnEvent.get());

    // The alarm is delayed but we already created a bucket thanks to the screen state condition.
    // This bucket does not have to be skipped since the alarm arrives in time for the next bucket.
    processor->informPullAlarmFired(expectedPullTimeNs + bucketSizeNs + 21);
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + 6 * bucketSizeNs, expectedPullTimeNs);

    screenOffEvent = CreateScreenStateChangedEvent(configAddedTimeNs + 6 * bucketSizeNs + 31,
                                                   android::view::DISPLAY_STATE_OFF);
    processor->OnLogEvent(screenOffEvent.get());

    processor->informPullAlarmFired(expectedPullTimeNs + bucketSizeNs + 21);
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + 8 * bucketSizeNs, expectedPullTimeNs);

    processor->informPullAlarmFired(expectedPullTimeNs + 1);
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + 9 * bucketSizeNs, expectedPullTimeNs);

    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(cfgKey, configAddedTimeNs + 9 * bucketSizeNs + 10, false, true,
                            ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(buffer.size() > 0);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);
    EXPECT_EQ(1, reports.reports_size());
    EXPECT_EQ(1, reports.reports(0).metrics_size());
    StatsLogReport::ValueMetricDataWrapper valueMetrics;
    sortMetricDataByDimensionsValue(reports.reports(0).metrics(0).value_metrics(), &valueMetrics);
    EXPECT_GT((int)valueMetrics.data_size(), 1);

    auto data = valueMetrics.data(0);
    EXPECT_EQ(android::util::SUBSYSTEM_SLEEP_STATE, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* subsystem name field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_FALSE(data.dimensions_in_what().value_tuple().dimensions_value(0).value_str().empty());
    EXPECT_EQ(3, data.bucket_info_size());

    EXPECT_EQ(baseTimeNs + 5 * bucketSizeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 6 * bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());
    EXPECT_EQ(1, data.bucket_info(0).values_size());

    EXPECT_EQ(baseTimeNs + 8 * bucketSizeNs, data.bucket_info(1).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 9 * bucketSizeNs, data.bucket_info(1).end_bucket_elapsed_nanos());
    EXPECT_EQ(1, data.bucket_info(1).values_size());

    EXPECT_EQ(baseTimeNs + 9 * bucketSizeNs, data.bucket_info(2).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 10 * bucketSizeNs, data.bucket_info(2).end_bucket_elapsed_nanos());
    EXPECT_EQ(1, data.bucket_info(2).values_size());
}

TEST(ValueMetricE2eTest, TestPulledEvents_WithActivation) {
    auto config = CreateStatsdConfig(false);
    int64_t baseTimeNs = getElapsedRealtimeNs();
    int64_t configAddedTimeNs = 10 * 60 * NS_PER_SEC + baseTimeNs;
    int64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(config.value_metric(0).bucket()) * 1000000;

    auto batterySaverStartMatcher = CreateBatterySaverModeStartAtomMatcher();
    *config.add_atom_matcher() = batterySaverStartMatcher;
    const int64_t ttlNs = 2 * bucketSizeNs;  // Two buckets.
    auto metric_activation = config.add_metric_activation();
    metric_activation->set_metric_id(metricId);
    metric_activation->set_activation_type(ACTIVATE_IMMEDIATELY);
    auto event_activation = metric_activation->add_event_activation();
    event_activation->set_atom_matcher_id(batterySaverStartMatcher.id());
    event_activation->set_ttl_seconds(ttlNs / 1000000000);

    ConfigKey cfgKey;
    auto processor = CreateStatsLogProcessor(baseTimeNs, configAddedTimeNs, config, cfgKey,
                                             SharedRefBase::make<FakeSubsystemSleepCallback>(),
                                             android::util::SUBSYSTEM_SLEEP_STATE);
    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());
    processor->mPullerManager->ForceClearPullerCache();

    int startBucketNum = processor->mMetricsManagers.begin()
                                 ->second->mAllMetricProducers[0]
                                 ->getCurrentBucketNum();
    EXPECT_GT(startBucketNum, (int64_t)0);
    EXPECT_FALSE(processor->mMetricsManagers.begin()->second->mAllMetricProducers[0]->isActive());

    // When creating the config, the value metric producer should register the alarm at the
    // end of the current bucket.
    EXPECT_EQ((size_t)1, processor->mPullerManager->mReceivers.size());
    EXPECT_EQ(bucketSizeNs,
              processor->mPullerManager->mReceivers.begin()->second.front().intervalNs);
    int64_t& expectedPullTimeNs =
            processor->mPullerManager->mReceivers.begin()->second.front().nextPullTimeNs;
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + bucketSizeNs, expectedPullTimeNs);

    // Pulling alarm arrives on time and reset the sequential pulling alarm.
    processor->informPullAlarmFired(expectedPullTimeNs + 1);  // 15 mins + 1 ns.
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + 2 * bucketSizeNs, expectedPullTimeNs);
    EXPECT_FALSE(processor->mMetricsManagers.begin()->second->mAllMetricProducers[0]->isActive());

    // Activate the metric. A pull occurs here
    const int64_t activationNs = configAddedTimeNs + bucketSizeNs + (2 * 1000 * 1000);  // 2 millis.
    auto batterySaverOnEvent = CreateBatterySaverOnEvent(activationNs);
    processor->OnLogEvent(batterySaverOnEvent.get());  // 15 mins + 2 ms.
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->mAllMetricProducers[0]->isActive());

    processor->informPullAlarmFired(expectedPullTimeNs + 1);  // 20 mins + 1 ns.
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + 3 * bucketSizeNs, expectedPullTimeNs);

    processor->informPullAlarmFired(expectedPullTimeNs + 2);  // 25 mins + 2 ns.
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + 4 * bucketSizeNs, expectedPullTimeNs);

    // Create random event to deactivate metric.
    auto deactivationEvent = CreateScreenBrightnessChangedEvent(activationNs + ttlNs + 1, 50);
    processor->OnLogEvent(deactivationEvent.get());
    EXPECT_FALSE(processor->mMetricsManagers.begin()->second->mAllMetricProducers[0]->isActive());

    processor->informPullAlarmFired(expectedPullTimeNs + 3);
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + 5 * bucketSizeNs, expectedPullTimeNs);

    processor->informPullAlarmFired(expectedPullTimeNs + 4);
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + 6 * bucketSizeNs, expectedPullTimeNs);

    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(cfgKey, configAddedTimeNs + 7 * bucketSizeNs + 10, false, true,
                            ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(buffer.size() > 0);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);
    EXPECT_EQ(1, reports.reports_size());
    EXPECT_EQ(1, reports.reports(0).metrics_size());
    StatsLogReport::ValueMetricDataWrapper valueMetrics;
    sortMetricDataByDimensionsValue(reports.reports(0).metrics(0).value_metrics(), &valueMetrics);
    EXPECT_GT((int)valueMetrics.data_size(), 0);

    auto data = valueMetrics.data(0);
    EXPECT_EQ(android::util::SUBSYSTEM_SLEEP_STATE, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* subsystem name field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_FALSE(data.dimensions_in_what().value_tuple().dimensions_value(0).value_str().empty());
    // We have 2 full buckets, the two surrounding the activation are dropped.
    EXPECT_EQ(2, data.bucket_info_size());

    auto bucketInfo = data.bucket_info(0);
    EXPECT_EQ(baseTimeNs + 3 * bucketSizeNs, bucketInfo.start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 4 * bucketSizeNs, bucketInfo.end_bucket_elapsed_nanos());
    EXPECT_EQ(1, bucketInfo.values_size());

    bucketInfo = data.bucket_info(1);
    EXPECT_EQ(baseTimeNs + 4 * bucketSizeNs, bucketInfo.start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 5 * bucketSizeNs, bucketInfo.end_bucket_elapsed_nanos());
    EXPECT_EQ(1, bucketInfo.values_size());
}

/**
 * Test initialization of a simple value metric that is sliced by a state.
 *
 * ValueCpuUserTimePerScreenState
 */
TEST(ValueMetricE2eTest, TestInitWithSlicedState) {
    // Create config.
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");  // LogEvent defaults to UID of root.

    auto pulledAtomMatcher =
            CreateSimpleAtomMatcher("TestMatcher", android::util::SUBSYSTEM_SLEEP_STATE);
    *config.add_atom_matcher() = pulledAtomMatcher;

    auto screenState = CreateScreenState();
    *config.add_state() = screenState;

    // Create value metric that slices by screen state without a map.
    int64_t metricId = 123456;
    auto valueMetric = config.add_value_metric();
    valueMetric->set_id(metricId);
    valueMetric->set_bucket(TimeUnit::FIVE_MINUTES);
    valueMetric->set_what(pulledAtomMatcher.id());
    *valueMetric->mutable_value_field() =
            CreateDimensions(android::util::CPU_TIME_PER_UID, {2 /* user_time_micros */});
    valueMetric->add_slice_by_state(screenState.id());
    valueMetric->set_max_pull_delay_sec(INT_MAX);

    // Initialize StatsLogProcessor.
    const uint64_t bucketStartTimeNs = 10000000000;  // 0:10
    const uint64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.value_metric(0).bucket()) * 1000000LL;
    int uid = 12345;
    int64_t cfgId = 98765;
    ConfigKey cfgKey(uid, cfgId);

    auto processor = CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);

    // Check that StateTrackers were initialized correctly.
    EXPECT_EQ(1, StateManager::getInstance().getStateTrackersCount());
    EXPECT_EQ(1, StateManager::getInstance().getListenersCount(SCREEN_STATE_ATOM_ID));

    // Check that ValueMetricProducer was initialized correctly.
    EXPECT_EQ(1U, processor->mMetricsManagers.size());
    sp<MetricsManager> metricsManager = processor->mMetricsManagers.begin()->second;
    EXPECT_TRUE(metricsManager->isConfigValid());
    EXPECT_EQ(1, metricsManager->mAllMetricProducers.size());
    sp<MetricProducer> metricProducer = metricsManager->mAllMetricProducers[0];
    EXPECT_EQ(1, metricProducer->mSlicedStateAtoms.size());
    EXPECT_EQ(SCREEN_STATE_ATOM_ID, metricProducer->mSlicedStateAtoms.at(0));
    EXPECT_EQ(0, metricProducer->mStateGroupMap.size());
}

/**
 * Test initialization of a value metric that is sliced by state and has
 * dimensions_in_what.
 *
 * ValueCpuUserTimePerUidPerUidProcessState
 */
TEST(ValueMetricE2eTest, TestInitWithSlicedState_WithDimensions) {
    // Create config.
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");  // LogEvent defaults to UID of root.

    auto cpuTimePerUidMatcher =
            CreateSimpleAtomMatcher("CpuTimePerUidMatcher", android::util::CPU_TIME_PER_UID);
    *config.add_atom_matcher() = cpuTimePerUidMatcher;

    auto uidProcessState = CreateUidProcessState();
    *config.add_state() = uidProcessState;

    // Create value metric that slices by screen state with a complete map.
    int64_t metricId = 123456;
    auto valueMetric = config.add_value_metric();
    valueMetric->set_id(metricId);
    valueMetric->set_bucket(TimeUnit::FIVE_MINUTES);
    valueMetric->set_what(cpuTimePerUidMatcher.id());
    *valueMetric->mutable_value_field() =
            CreateDimensions(android::util::CPU_TIME_PER_UID, {2 /* user_time_micros */});
    *valueMetric->mutable_dimensions_in_what() =
            CreateDimensions(android::util::CPU_TIME_PER_UID, {1 /* uid */});
    valueMetric->add_slice_by_state(uidProcessState.id());
    MetricStateLink* stateLink = valueMetric->add_state_link();
    stateLink->set_state_atom_id(UID_PROCESS_STATE_ATOM_ID);
    auto fieldsInWhat = stateLink->mutable_fields_in_what();
    *fieldsInWhat = CreateDimensions(android::util::CPU_TIME_PER_UID, {1 /* uid */});
    auto fieldsInState = stateLink->mutable_fields_in_state();
    *fieldsInState = CreateDimensions(UID_PROCESS_STATE_ATOM_ID, {1 /* uid */});
    valueMetric->set_max_pull_delay_sec(INT_MAX);

    // Initialize StatsLogProcessor.
    const uint64_t bucketStartTimeNs = 10000000000;  // 0:10
    int uid = 12345;
    int64_t cfgId = 98765;
    ConfigKey cfgKey(uid, cfgId);

    auto processor = CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);

    // Check that StateTrackers were initialized correctly.
    EXPECT_EQ(1, StateManager::getInstance().getStateTrackersCount());
    EXPECT_EQ(1, StateManager::getInstance().getListenersCount(UID_PROCESS_STATE_ATOM_ID));

    // Check that ValueMetricProducer was initialized correctly.
    EXPECT_EQ(1U, processor->mMetricsManagers.size());
    sp<MetricsManager> metricsManager = processor->mMetricsManagers.begin()->second;
    EXPECT_TRUE(metricsManager->isConfigValid());
    EXPECT_EQ(1, metricsManager->mAllMetricProducers.size());
    sp<MetricProducer> metricProducer = metricsManager->mAllMetricProducers[0];
    EXPECT_EQ(1, metricProducer->mSlicedStateAtoms.size());
    EXPECT_EQ(UID_PROCESS_STATE_ATOM_ID, metricProducer->mSlicedStateAtoms.at(0));
    EXPECT_EQ(0, metricProducer->mStateGroupMap.size());
}

/**
 * Test initialization of a value metric that is sliced by state and has
 * dimensions_in_what.
 *
 * ValueCpuUserTimePerUidPerUidProcessState
 */
TEST(ValueMetricE2eTest, TestInitWithSlicedState_WithIncorrectDimensions) {
    // Create config.
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");  // LogEvent defaults to UID of root.

    auto cpuTimePerUidMatcher =
            CreateSimpleAtomMatcher("CpuTimePerUidMatcher", android::util::CPU_TIME_PER_UID);
    *config.add_atom_matcher() = cpuTimePerUidMatcher;

    auto uidProcessState = CreateUidProcessState();
    *config.add_state() = uidProcessState;

    // Create value metric that slices by screen state with a complete map.
    int64_t metricId = 123456;
    auto valueMetric = config.add_value_metric();
    valueMetric->set_id(metricId);
    valueMetric->set_bucket(TimeUnit::FIVE_MINUTES);
    valueMetric->set_what(cpuTimePerUidMatcher.id());
    *valueMetric->mutable_value_field() =
            CreateDimensions(android::util::CPU_TIME_PER_UID, {2 /* user_time_micros */});
    valueMetric->add_slice_by_state(uidProcessState.id());
    MetricStateLink* stateLink = valueMetric->add_state_link();
    stateLink->set_state_atom_id(UID_PROCESS_STATE_ATOM_ID);
    auto fieldsInWhat = stateLink->mutable_fields_in_what();
    *fieldsInWhat = CreateDimensions(android::util::CPU_TIME_PER_UID, {1 /* uid */});
    auto fieldsInState = stateLink->mutable_fields_in_state();
    *fieldsInState = CreateDimensions(UID_PROCESS_STATE_ATOM_ID, {1 /* uid */});
    valueMetric->set_max_pull_delay_sec(INT_MAX);

    // Initialize StatsLogProcessor.
    const uint64_t bucketStartTimeNs = 10000000000;  // 0:10
    int uid = 12345;
    int64_t cfgId = 98765;
    ConfigKey cfgKey(uid, cfgId);
    auto processor = CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);

    // No StateTrackers are initialized.
    EXPECT_EQ(0, StateManager::getInstance().getStateTrackersCount());

    // Config initialization fails.
    EXPECT_EQ(0, processor->mMetricsManagers.size());
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif

}  // namespace statsd
}  // namespace os
}  // namespace android
