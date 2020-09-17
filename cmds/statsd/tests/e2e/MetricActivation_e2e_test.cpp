// Copyright (C) 2018 The Android Open Source Project
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

#include "src/StatsLogProcessor.h"
#include "src/stats_log_util.h"
#include "tests/statsd_test_util.h"

#include <vector>

namespace android {
namespace os {
namespace statsd {

#ifdef __ANDROID__

namespace {

StatsdConfig CreateStatsdConfig() {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT"); // LogEvent defaults to UID of root.
    auto saverModeMatcher = CreateBatterySaverModeStartAtomMatcher();
    auto crashMatcher = CreateProcessCrashAtomMatcher();
    auto screenOnMatcher = CreateScreenTurnedOnAtomMatcher();

    *config.add_atom_matcher() = saverModeMatcher;
    *config.add_atom_matcher() = crashMatcher;
    *config.add_atom_matcher() = screenOnMatcher;

    int64_t metricId = 123456;
    auto countMetric = config.add_count_metric();
    countMetric->set_id(metricId);
    countMetric->set_what(crashMatcher.id());
    countMetric->set_bucket(FIVE_MINUTES);
    countMetric->mutable_dimensions_in_what()->set_field(
        util::PROCESS_LIFE_CYCLE_STATE_CHANGED);
    countMetric->mutable_dimensions_in_what()->add_child()->set_field(1);  // uid field

    auto metric_activation1 = config.add_metric_activation();
    metric_activation1->set_metric_id(metricId);
    auto event_activation1 = metric_activation1->add_event_activation();
    event_activation1->set_atom_matcher_id(saverModeMatcher.id());
    event_activation1->set_ttl_seconds(60 * 6);  // 6 minutes
    auto event_activation2 = metric_activation1->add_event_activation();
    event_activation2->set_atom_matcher_id(screenOnMatcher.id());
    event_activation2->set_ttl_seconds(60 * 2);  // 2 minutes

    return config;
}

StatsdConfig CreateStatsdConfigWithOneDeactivation() {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT"); // LogEvent defaults to UID of root.
    auto saverModeMatcher = CreateBatterySaverModeStartAtomMatcher();
    auto crashMatcher = CreateProcessCrashAtomMatcher();
    auto screenOnMatcher = CreateScreenTurnedOnAtomMatcher();
    auto brightnessChangedMatcher = CreateScreenBrightnessChangedAtomMatcher();

    *config.add_atom_matcher() = saverModeMatcher;
    *config.add_atom_matcher() = crashMatcher;
    *config.add_atom_matcher() = screenOnMatcher;
    *config.add_atom_matcher() = brightnessChangedMatcher;

    int64_t metricId = 123456;
    auto countMetric = config.add_count_metric();
    countMetric->set_id(metricId);
    countMetric->set_what(crashMatcher.id());
    countMetric->set_bucket(FIVE_MINUTES);
    countMetric->mutable_dimensions_in_what()->set_field(
        util::PROCESS_LIFE_CYCLE_STATE_CHANGED);
    countMetric->mutable_dimensions_in_what()->add_child()->set_field(1);  // uid field

    auto metric_activation1 = config.add_metric_activation();
    metric_activation1->set_metric_id(metricId);
    auto event_activation1 = metric_activation1->add_event_activation();
    event_activation1->set_atom_matcher_id(saverModeMatcher.id());
    event_activation1->set_ttl_seconds(60 * 6);  // 6 minutes
    event_activation1->set_deactivation_atom_matcher_id(brightnessChangedMatcher.id());
    auto event_activation2 = metric_activation1->add_event_activation();
    event_activation2->set_atom_matcher_id(screenOnMatcher.id());
    event_activation2->set_ttl_seconds(60 * 2);  // 2 minutes

    return config;
}

StatsdConfig CreateStatsdConfigWithTwoDeactivations() {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT"); // LogEvent defaults to UID of root.
    auto saverModeMatcher = CreateBatterySaverModeStartAtomMatcher();
    auto crashMatcher = CreateProcessCrashAtomMatcher();
    auto screenOnMatcher = CreateScreenTurnedOnAtomMatcher();
    auto brightnessChangedMatcher = CreateScreenBrightnessChangedAtomMatcher();
    auto brightnessChangedMatcher2 = CreateScreenBrightnessChangedAtomMatcher();
    brightnessChangedMatcher2.set_id(StringToId("ScreenBrightnessChanged2"));

    *config.add_atom_matcher() = saverModeMatcher;
    *config.add_atom_matcher() = crashMatcher;
    *config.add_atom_matcher() = screenOnMatcher;
    *config.add_atom_matcher() = brightnessChangedMatcher;
    *config.add_atom_matcher() = brightnessChangedMatcher2;

    int64_t metricId = 123456;
    auto countMetric = config.add_count_metric();
    countMetric->set_id(metricId);
    countMetric->set_what(crashMatcher.id());
    countMetric->set_bucket(FIVE_MINUTES);
    countMetric->mutable_dimensions_in_what()->set_field(
        util::PROCESS_LIFE_CYCLE_STATE_CHANGED);
    countMetric->mutable_dimensions_in_what()->add_child()->set_field(1);  // uid field

    auto metric_activation1 = config.add_metric_activation();
    metric_activation1->set_metric_id(metricId);
    auto event_activation1 = metric_activation1->add_event_activation();
    event_activation1->set_atom_matcher_id(saverModeMatcher.id());
    event_activation1->set_ttl_seconds(60 * 6);  // 6 minutes
    event_activation1->set_deactivation_atom_matcher_id(brightnessChangedMatcher.id());
    auto event_activation2 = metric_activation1->add_event_activation();
    event_activation2->set_atom_matcher_id(screenOnMatcher.id());
    event_activation2->set_ttl_seconds(60 * 2);  // 2 minutes
    event_activation2->set_deactivation_atom_matcher_id(brightnessChangedMatcher2.id());

    return config;
}

StatsdConfig CreateStatsdConfigWithSameDeactivations() {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT"); // LogEvent defaults to UID of root.
    auto saverModeMatcher = CreateBatterySaverModeStartAtomMatcher();
    auto crashMatcher = CreateProcessCrashAtomMatcher();
    auto screenOnMatcher = CreateScreenTurnedOnAtomMatcher();
    auto brightnessChangedMatcher = CreateScreenBrightnessChangedAtomMatcher();

    *config.add_atom_matcher() = saverModeMatcher;
    *config.add_atom_matcher() = crashMatcher;
    *config.add_atom_matcher() = screenOnMatcher;
    *config.add_atom_matcher() = brightnessChangedMatcher;

    int64_t metricId = 123456;
    auto countMetric = config.add_count_metric();
    countMetric->set_id(metricId);
    countMetric->set_what(crashMatcher.id());
    countMetric->set_bucket(FIVE_MINUTES);
    countMetric->mutable_dimensions_in_what()->set_field(
        util::PROCESS_LIFE_CYCLE_STATE_CHANGED);
    countMetric->mutable_dimensions_in_what()->add_child()->set_field(1);  // uid field

    auto metric_activation1 = config.add_metric_activation();
    metric_activation1->set_metric_id(metricId);
    auto event_activation1 = metric_activation1->add_event_activation();
    event_activation1->set_atom_matcher_id(saverModeMatcher.id());
    event_activation1->set_ttl_seconds(60 * 6);  // 6 minutes
    event_activation1->set_deactivation_atom_matcher_id(brightnessChangedMatcher.id());
    auto event_activation2 = metric_activation1->add_event_activation();
    event_activation2->set_atom_matcher_id(screenOnMatcher.id());
    event_activation2->set_ttl_seconds(60 * 2);  // 2 minutes
    event_activation2->set_deactivation_atom_matcher_id(brightnessChangedMatcher.id());

    return config;
}

StatsdConfig CreateStatsdConfigWithTwoMetricsTwoDeactivations() {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT"); // LogEvent defaults to UID of root.
    auto saverModeMatcher = CreateBatterySaverModeStartAtomMatcher();
    auto crashMatcher = CreateProcessCrashAtomMatcher();
    auto foregroundMatcher = CreateMoveToForegroundAtomMatcher();
    auto screenOnMatcher = CreateScreenTurnedOnAtomMatcher();
    auto brightnessChangedMatcher = CreateScreenBrightnessChangedAtomMatcher();
    auto brightnessChangedMatcher2 = CreateScreenBrightnessChangedAtomMatcher();
    brightnessChangedMatcher2.set_id(StringToId("ScreenBrightnessChanged2"));

    *config.add_atom_matcher() = saverModeMatcher;
    *config.add_atom_matcher() = crashMatcher;
    *config.add_atom_matcher() = screenOnMatcher;
    *config.add_atom_matcher() = brightnessChangedMatcher;
    *config.add_atom_matcher() = brightnessChangedMatcher2;
    *config.add_atom_matcher() = foregroundMatcher;

    int64_t metricId = 123456;
    auto countMetric = config.add_count_metric();
    countMetric->set_id(metricId);
    countMetric->set_what(crashMatcher.id());
    countMetric->set_bucket(FIVE_MINUTES);
    countMetric->mutable_dimensions_in_what()->set_field(
        util::PROCESS_LIFE_CYCLE_STATE_CHANGED);
    countMetric->mutable_dimensions_in_what()->add_child()->set_field(1);  // uid field

    int64_t metricId2 = 234567;
    countMetric = config.add_count_metric();
    countMetric->set_id(metricId2);
    countMetric->set_what(foregroundMatcher.id());
    countMetric->set_bucket(FIVE_MINUTES);
    countMetric->mutable_dimensions_in_what()->set_field(
        util::ACTIVITY_FOREGROUND_STATE_CHANGED);
    countMetric->mutable_dimensions_in_what()->add_child()->set_field(1);  // uid field

    auto metric_activation1 = config.add_metric_activation();
    metric_activation1->set_metric_id(metricId);
    auto event_activation1 = metric_activation1->add_event_activation();
    event_activation1->set_atom_matcher_id(saverModeMatcher.id());
    event_activation1->set_ttl_seconds(60 * 6);  // 6 minutes
    event_activation1->set_deactivation_atom_matcher_id(brightnessChangedMatcher.id());
    auto event_activation2 = metric_activation1->add_event_activation();
    event_activation2->set_atom_matcher_id(screenOnMatcher.id());
    event_activation2->set_ttl_seconds(60 * 2);  // 2 minutes
    event_activation2->set_deactivation_atom_matcher_id(brightnessChangedMatcher2.id());

    metric_activation1 = config.add_metric_activation();
    metric_activation1->set_metric_id(metricId2);
    event_activation1 = metric_activation1->add_event_activation();
    event_activation1->set_atom_matcher_id(saverModeMatcher.id());
    event_activation1->set_ttl_seconds(60 * 6);  // 6 minutes
    event_activation1->set_deactivation_atom_matcher_id(brightnessChangedMatcher.id());
    event_activation2 = metric_activation1->add_event_activation();
    event_activation2->set_atom_matcher_id(screenOnMatcher.id());
    event_activation2->set_ttl_seconds(60 * 2);  // 2 minutes
    event_activation2->set_deactivation_atom_matcher_id(brightnessChangedMatcher2.id());

    return config;
}

}  // namespace

TEST(MetricActivationE2eTest, TestCountMetric) {
    auto config = CreateStatsdConfig();

    int64_t bucketStartTimeNs = NS_PER_SEC * 10;  // 10 secs
    int64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.count_metric(0).bucket()) * 1000LL * 1000LL;

    int uid = 12345;
    int64_t cfgId = 98765;
    ConfigKey cfgKey(uid, cfgId);

    sp<UidMap> m = new UidMap();
    sp<StatsPullerManager> pullerManager = new StatsPullerManager();
    sp<AlarmMonitor> anomalyAlarmMonitor;
    sp<AlarmMonitor> subscriberAlarmMonitor;
    vector<int64_t> activeConfigsBroadcast;

    long timeBase1 = 1;
    int broadcastCount = 0;
    StatsLogProcessor processor(
            m, pullerManager, anomalyAlarmMonitor, subscriberAlarmMonitor, bucketStartTimeNs,
            [](const ConfigKey& key) { return true; },
            [&uid, &broadcastCount, &activeConfigsBroadcast](const int& broadcastUid,
                                                             const vector<int64_t>& activeConfigs) {
                broadcastCount++;
                EXPECT_EQ(broadcastUid, uid);
                activeConfigsBroadcast.clear();
                activeConfigsBroadcast.insert(activeConfigsBroadcast.end(), activeConfigs.begin(),
                                              activeConfigs.end());
                return true;
            });

    processor.OnConfigUpdated(bucketStartTimeNs, cfgKey, config);

    ASSERT_EQ(processor.mMetricsManagers.size(), 1u);
    sp<MetricsManager> metricsManager = processor.mMetricsManagers.begin()->second;
    EXPECT_TRUE(metricsManager->isConfigValid());
    ASSERT_EQ(metricsManager->mAllMetricProducers.size(), 1);
    sp<MetricProducer> metricProducer = metricsManager->mAllMetricProducers[0];
    auto& eventActivationMap = metricProducer->mEventActivationMap;

    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    // Two activations: one is triggered by battery saver mode (tracker index 0), the other is
    // triggered by screen on event (tracker index 2).
    ASSERT_EQ(eventActivationMap.size(), 2u);
    EXPECT_TRUE(eventActivationMap.find(0) != eventActivationMap.end());
    EXPECT_TRUE(eventActivationMap.find(2) != eventActivationMap.end());
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, 0);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, 0);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);

    std::unique_ptr<LogEvent> event;

    event = CreateAppCrashEvent(bucketStartTimeNs + 5, 111);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + 5);
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 0);

    // Activated by battery save mode.
    event = CreateBatterySaverOnEvent(bucketStartTimeNs + 10);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + 10);
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 1);
    ASSERT_EQ(activeConfigsBroadcast.size(), 1);
    EXPECT_EQ(activeConfigsBroadcast[0], cfgId);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, 0);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);

    // First processed event.
    event = CreateAppCrashEvent(bucketStartTimeNs + 15, 222);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + 15);

    // Activated by screen on event.
    event = CreateScreenStateChangedEvent(bucketStartTimeNs + 20, android::view::DISPLAY_STATE_ON);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + 20);
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + 20);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);

    // 2nd processed event.
    // The activation by screen_on event expires, but the one by battery save mode is still active.
    event = CreateAppCrashEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 2 + 25, 333);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 2 + 25);
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + 20);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    // No new broadcast since the config should still be active.
    EXPECT_EQ(broadcastCount, 1);

    // 3rd processed event.
    event = CreateAppCrashEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 5 + 25, 444);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 5 + 25);

    // All activations expired.
    event = CreateAppCrashEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 8, 555);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 8);
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    // New broadcast since the config is no longer active.
    EXPECT_EQ(broadcastCount, 2);
    ASSERT_EQ(activeConfigsBroadcast.size(), 0);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + 20);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);

    // Re-activate metric via screen on.
    event = CreateScreenStateChangedEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10,
                                          android::view::DISPLAY_STATE_ON);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 3);
    ASSERT_EQ(activeConfigsBroadcast.size(), 1);
    EXPECT_EQ(activeConfigsBroadcast[0], cfgId);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);

    // 4th processed event.
    event = CreateAppCrashEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 1, 666);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 1);

    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor.onDumpReport(cfgKey, bucketStartTimeNs + NS_PER_SEC * 60 * 15 + 1, false, true,
                           ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(buffer.size() > 0);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStartEndTimestamp(&reports);
    ASSERT_EQ(1, reports.reports_size());
    ASSERT_EQ(1, reports.reports(0).metrics_size());

    StatsLogReport::CountMetricDataWrapper countMetrics;
    sortMetricDataByDimensionsValue(reports.reports(0).metrics(0).count_metrics(), &countMetrics);
    ASSERT_EQ(4, countMetrics.data_size());

    auto data = countMetrics.data(0);
    EXPECT_EQ(util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    ASSERT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(222, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(1);
    EXPECT_EQ(util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    ASSERT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(333, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(2);
    EXPECT_EQ(util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    ASSERT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(444, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    // Partial bucket as metric is deactivated.
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + NS_PER_SEC * 60 * 8,
              data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(3);
    EXPECT_EQ(util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    ASSERT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(666, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs,
              data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + 3 * bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());
}

TEST(MetricActivationE2eTest, TestCountMetricWithOneDeactivation) {
    auto config = CreateStatsdConfigWithOneDeactivation();

    int64_t bucketStartTimeNs = NS_PER_SEC * 10;  // 10 secs
    int64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.count_metric(0).bucket()) * 1000LL * 1000LL;

    int uid = 12345;
    int64_t cfgId = 98765;
    ConfigKey cfgKey(uid, cfgId);

    sp<UidMap> m = new UidMap();
    sp<StatsPullerManager> pullerManager = new StatsPullerManager();
    sp<AlarmMonitor> anomalyAlarmMonitor;
    sp<AlarmMonitor> subscriberAlarmMonitor;
    vector<int64_t> activeConfigsBroadcast;

    long timeBase1 = 1;
    int broadcastCount = 0;
    StatsLogProcessor processor(
            m, pullerManager, anomalyAlarmMonitor, subscriberAlarmMonitor, bucketStartTimeNs,
            [](const ConfigKey& key) { return true; },
            [&uid, &broadcastCount, &activeConfigsBroadcast](const int& broadcastUid,
                                                             const vector<int64_t>& activeConfigs) {
                broadcastCount++;
                EXPECT_EQ(broadcastUid, uid);
                activeConfigsBroadcast.clear();
                activeConfigsBroadcast.insert(activeConfigsBroadcast.end(), activeConfigs.begin(),
                                              activeConfigs.end());
                return true;
            });

    processor.OnConfigUpdated(bucketStartTimeNs, cfgKey, config);

    ASSERT_EQ(processor.mMetricsManagers.size(), 1u);
    sp<MetricsManager> metricsManager = processor.mMetricsManagers.begin()->second;
    EXPECT_TRUE(metricsManager->isConfigValid());
    ASSERT_EQ(metricsManager->mAllMetricProducers.size(), 1);
    sp<MetricProducer> metricProducer = metricsManager->mAllMetricProducers[0];
    auto& eventActivationMap = metricProducer->mEventActivationMap;
    auto& eventDeactivationMap = metricProducer->mEventDeactivationMap;

    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    // Two activations: one is triggered by battery saver mode (tracker index 0), the other is
    // triggered by screen on event (tracker index 2).
    ASSERT_EQ(eventActivationMap.size(), 2u);
    EXPECT_TRUE(eventActivationMap.find(0) != eventActivationMap.end());
    EXPECT_TRUE(eventActivationMap.find(2) != eventActivationMap.end());
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, 0);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, 0);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    ASSERT_EQ(eventDeactivationMap.size(), 1u);
    EXPECT_TRUE(eventDeactivationMap.find(3) != eventDeactivationMap.end());
    ASSERT_EQ(eventDeactivationMap[3].size(), 1u);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);

    std::unique_ptr<LogEvent> event;

    event = CreateAppCrashEvent(bucketStartTimeNs + 5, 111);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + 5);
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 0);

    // Activated by battery save mode.
    event = CreateBatterySaverOnEvent(bucketStartTimeNs + 10);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + 10);
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 1);
    ASSERT_EQ(activeConfigsBroadcast.size(), 1);
    EXPECT_EQ(activeConfigsBroadcast[0], cfgId);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, 0);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);

    // First processed event.
    event = CreateAppCrashEvent(bucketStartTimeNs + 15, 222);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + 15);

    // Activated by screen on event.
    event = CreateScreenStateChangedEvent(bucketStartTimeNs + 20, android::view::DISPLAY_STATE_ON);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + 20);
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + 20);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);

    // 2nd processed event.
    // The activation by screen_on event expires, but the one by battery save mode is still active.
    event = CreateAppCrashEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 2 + 25, 333);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 2 + 25);
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + 20);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);
    // No new broadcast since the config should still be active.
    EXPECT_EQ(broadcastCount, 1);

    // 3rd processed event.
    event = CreateAppCrashEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 5 + 25, 444);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 5 + 25);

    // All activations expired.
    event = CreateAppCrashEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 8, 555);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 8);
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    // New broadcast since the config is no longer active.
    EXPECT_EQ(broadcastCount, 2);
    ASSERT_EQ(activeConfigsBroadcast.size(), 0);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + 20);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);

    // Re-activate metric via screen on.
    event = CreateScreenStateChangedEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10,
                                          android::view::DISPLAY_STATE_ON);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 3);
    ASSERT_EQ(activeConfigsBroadcast.size(), 1);
    EXPECT_EQ(activeConfigsBroadcast[0], cfgId);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);

    // 4th processed event.
    event = CreateAppCrashEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 1, 666);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 1);

    // Re-enable battery saver mode activation.
    event = CreateBatterySaverOnEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 15);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 15);
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 3);
    ASSERT_EQ(activeConfigsBroadcast.size(), 1);
    EXPECT_EQ(activeConfigsBroadcast[0], cfgId);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 15);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);

    // 5th processed event.
    event = CreateAppCrashEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 40, 777);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 40);

    // Cancel battery saver mode activation.
    event = CreateScreenBrightnessChangedEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 60, 64);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 60);
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 3);
    ASSERT_EQ(activeConfigsBroadcast.size(), 1);
    EXPECT_EQ(activeConfigsBroadcast[0], cfgId);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 15);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);

    // Screen-on activation expired.
    event = CreateAppCrashEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 13, 888);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 13);
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    // New broadcast since the config is no longer active.
    EXPECT_EQ(broadcastCount, 4);
    ASSERT_EQ(activeConfigsBroadcast.size(), 0);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 15);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);

    event = CreateAppCrashEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 14 + 1, 999);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 14 + 1);

    // Re-enable battery saver mode activation.
    event = CreateBatterySaverOnEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 15 + 15);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 15 + 15);
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 5);
    ASSERT_EQ(activeConfigsBroadcast.size(), 1);
    EXPECT_EQ(activeConfigsBroadcast[0], cfgId);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 15 + 15);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);

    // Cancel battery saver mode activation.
    event = CreateScreenBrightnessChangedEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 16, 140);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 16);
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 6);
    ASSERT_EQ(activeConfigsBroadcast.size(), 0);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 15 + 15);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);

    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor.onDumpReport(cfgKey, bucketStartTimeNs + NS_PER_SEC * 60 * 15 + 1, false, true,
                           ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(buffer.size() > 0);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStartEndTimestamp(&reports);
    ASSERT_EQ(1, reports.reports_size());
    ASSERT_EQ(1, reports.reports(0).metrics_size());

    StatsLogReport::CountMetricDataWrapper countMetrics;
    sortMetricDataByDimensionsValue(reports.reports(0).metrics(0).count_metrics(), &countMetrics);
    ASSERT_EQ(5, countMetrics.data_size());

    auto data = countMetrics.data(0);
    EXPECT_EQ(util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    ASSERT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(222, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(1);
    EXPECT_EQ(util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    ASSERT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(333, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(2);
    EXPECT_EQ(util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    ASSERT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(444, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    // Partial bucket as metric is deactivated.
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + NS_PER_SEC * 60 * 8,
              data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(3);
    EXPECT_EQ(util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    ASSERT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(666, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs,
              data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + NS_PER_SEC * 60 * 13,
              data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(4);
    EXPECT_EQ(util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    ASSERT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(777, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs,
              data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + NS_PER_SEC * 60 * 13,
              data.bucket_info(0).end_bucket_elapsed_nanos());
}

TEST(MetricActivationE2eTest, TestCountMetricWithTwoDeactivations) {
    auto config = CreateStatsdConfigWithTwoDeactivations();

    int64_t bucketStartTimeNs = NS_PER_SEC * 10;  // 10 secs
    int64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.count_metric(0).bucket()) * 1000LL * 1000LL;

    int uid = 12345;
    int64_t cfgId = 98765;
    ConfigKey cfgKey(uid, cfgId);

    sp<UidMap> m = new UidMap();
    sp<StatsPullerManager> pullerManager = new StatsPullerManager();
    sp<AlarmMonitor> anomalyAlarmMonitor;
    sp<AlarmMonitor> subscriberAlarmMonitor;
    vector<int64_t> activeConfigsBroadcast;

    long timeBase1 = 1;
    int broadcastCount = 0;
    StatsLogProcessor processor(
            m, pullerManager, anomalyAlarmMonitor, subscriberAlarmMonitor, bucketStartTimeNs,
            [](const ConfigKey& key) { return true; },
            [&uid, &broadcastCount, &activeConfigsBroadcast](const int& broadcastUid,
                                                             const vector<int64_t>& activeConfigs) {
                broadcastCount++;
                EXPECT_EQ(broadcastUid, uid);
                activeConfigsBroadcast.clear();
                activeConfigsBroadcast.insert(activeConfigsBroadcast.end(), activeConfigs.begin(),
                                              activeConfigs.end());
                return true;
            });

    processor.OnConfigUpdated(bucketStartTimeNs, cfgKey, config);

    ASSERT_EQ(processor.mMetricsManagers.size(), 1u);
    sp<MetricsManager> metricsManager = processor.mMetricsManagers.begin()->second;
    EXPECT_TRUE(metricsManager->isConfigValid());
    ASSERT_EQ(metricsManager->mAllMetricProducers.size(), 1);
    sp<MetricProducer> metricProducer = metricsManager->mAllMetricProducers[0];
    auto& eventActivationMap = metricProducer->mEventActivationMap;
    auto& eventDeactivationMap = metricProducer->mEventDeactivationMap;

    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    // Two activations: one is triggered by battery saver mode (tracker index 0), the other is
    // triggered by screen on event (tracker index 2).
    ASSERT_EQ(eventActivationMap.size(), 2u);
    EXPECT_TRUE(eventActivationMap.find(0) != eventActivationMap.end());
    EXPECT_TRUE(eventActivationMap.find(2) != eventActivationMap.end());
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, 0);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, 0);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    ASSERT_EQ(eventDeactivationMap.size(), 2u);
    EXPECT_TRUE(eventDeactivationMap.find(3) != eventDeactivationMap.end());
    EXPECT_TRUE(eventDeactivationMap.find(4) != eventDeactivationMap.end());
    ASSERT_EQ(eventDeactivationMap[3].size(), 1u);
    ASSERT_EQ(eventDeactivationMap[4].size(), 1u);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);
    EXPECT_EQ(eventDeactivationMap[4][0], eventActivationMap[2]);

    std::unique_ptr<LogEvent> event;

    event = CreateAppCrashEvent(bucketStartTimeNs + 5, 111);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + 5);
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 0);

    // Activated by battery save mode.
    event = CreateBatterySaverOnEvent(bucketStartTimeNs + 10);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + 10);
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 1);
    ASSERT_EQ(activeConfigsBroadcast.size(), 1);
    EXPECT_EQ(activeConfigsBroadcast[0], cfgId);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, 0);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);
    EXPECT_EQ(eventDeactivationMap[4][0], eventActivationMap[2]);

    // First processed event.
    event = CreateAppCrashEvent(bucketStartTimeNs + 15, 222);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + 15);

    // Activated by screen on event.
    event = CreateScreenStateChangedEvent(bucketStartTimeNs + 20, android::view::DISPLAY_STATE_ON);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + 20);
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + 20);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);
    EXPECT_EQ(eventDeactivationMap[4][0], eventActivationMap[2]);

    // 2nd processed event.
    // The activation by screen_on event expires, but the one by battery save mode is still active.
    event = CreateAppCrashEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 2 + 25, 333);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 2 + 25);
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + 20);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);
    EXPECT_EQ(eventDeactivationMap[4][0], eventActivationMap[2]);
    // No new broadcast since the config should still be active.
    EXPECT_EQ(broadcastCount, 1);

    // 3rd processed event.
    event = CreateAppCrashEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 5 + 25, 444);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 5 + 25);

    // All activations expired.
    event = CreateAppCrashEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 8, 555);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 8);
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    // New broadcast since the config is no longer active.
    EXPECT_EQ(broadcastCount, 2);
    ASSERT_EQ(activeConfigsBroadcast.size(), 0);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + 20);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);
    EXPECT_EQ(eventDeactivationMap[4][0], eventActivationMap[2]);

    // Re-activate metric via screen on.
    event = CreateScreenStateChangedEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10,
                                          android::view::DISPLAY_STATE_ON);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 3);
    ASSERT_EQ(activeConfigsBroadcast.size(), 1);
    EXPECT_EQ(activeConfigsBroadcast[0], cfgId);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);
    EXPECT_EQ(eventDeactivationMap[4][0], eventActivationMap[2]);

    // 4th processed event.
    event = CreateAppCrashEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 1, 666);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 1);

    // Re-enable battery saver mode activation.
    event = CreateBatterySaverOnEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 15);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 15);
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 3);
    ASSERT_EQ(activeConfigsBroadcast.size(), 1);
    EXPECT_EQ(activeConfigsBroadcast[0], cfgId);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 15);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);
    EXPECT_EQ(eventDeactivationMap[4][0], eventActivationMap[2]);

    // 5th processed event.
    event = CreateAppCrashEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 40, 777);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 40);

    // Cancel battery saver mode and screen on activation.
    event = CreateScreenBrightnessChangedEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 60, 64);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 60);
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    // New broadcast since the config is no longer active.
    EXPECT_EQ(broadcastCount, 4);
    ASSERT_EQ(activeConfigsBroadcast.size(), 0);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 15);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);
    EXPECT_EQ(eventDeactivationMap[4][0], eventActivationMap[2]);

    // Screen-on activation expired.
    event = CreateAppCrashEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 13, 888);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 13);
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 4);
    ASSERT_EQ(activeConfigsBroadcast.size(), 0);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 15);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);
    EXPECT_EQ(eventDeactivationMap[4][0], eventActivationMap[2]);

    event = CreateAppCrashEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 14 + 1, 999);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 14 + 1);

    // Re-enable battery saver mode activation.
    event = CreateBatterySaverOnEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 15 + 15);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 15 + 15);
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 5);
    ASSERT_EQ(activeConfigsBroadcast.size(), 1);
    EXPECT_EQ(activeConfigsBroadcast[0], cfgId);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 15 + 15);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);
    EXPECT_EQ(eventDeactivationMap[4][0], eventActivationMap[2]);

    // Cancel battery saver mode and screen on activation.
    event = CreateScreenBrightnessChangedEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 16, 140);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 16);
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 6);
    ASSERT_EQ(activeConfigsBroadcast.size(), 0);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 15 + 15);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);
    EXPECT_EQ(eventDeactivationMap[4][0], eventActivationMap[2]);

    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor.onDumpReport(cfgKey, bucketStartTimeNs + NS_PER_SEC * 60 * 15 + 1, false, true,
                           ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(buffer.size() > 0);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStartEndTimestamp(&reports);
    ASSERT_EQ(1, reports.reports_size());
    ASSERT_EQ(1, reports.reports(0).metrics_size());

    StatsLogReport::CountMetricDataWrapper countMetrics;
    sortMetricDataByDimensionsValue(reports.reports(0).metrics(0).count_metrics(), &countMetrics);
    ASSERT_EQ(5, countMetrics.data_size());

    auto data = countMetrics.data(0);
    EXPECT_EQ(util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    ASSERT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(222, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(1);
    EXPECT_EQ(util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    ASSERT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(333, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(2);
    EXPECT_EQ(util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    ASSERT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(444, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    // Partial bucket as metric is deactivated.
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + NS_PER_SEC * 60 * 8,
              data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(3);
    EXPECT_EQ(util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    ASSERT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(666, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs,
              data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + NS_PER_SEC * 60 * 11,
              data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(4);
    EXPECT_EQ(util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    ASSERT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(777, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs,
              data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + NS_PER_SEC * 60 * 11,
              data.bucket_info(0).end_bucket_elapsed_nanos());
}

TEST(MetricActivationE2eTest, TestCountMetricWithSameDeactivation) {
    auto config = CreateStatsdConfigWithSameDeactivations();

    int64_t bucketStartTimeNs = NS_PER_SEC * 10;  // 10 secs
    int64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.count_metric(0).bucket()) * 1000LL * 1000LL;

    int uid = 12345;
    int64_t cfgId = 98765;
    ConfigKey cfgKey(uid, cfgId);

    sp<UidMap> m = new UidMap();
    sp<StatsPullerManager> pullerManager = new StatsPullerManager();
    sp<AlarmMonitor> anomalyAlarmMonitor;
    sp<AlarmMonitor> subscriberAlarmMonitor;
    vector<int64_t> activeConfigsBroadcast;

    long timeBase1 = 1;
    int broadcastCount = 0;
    StatsLogProcessor processor(
            m, pullerManager, anomalyAlarmMonitor, subscriberAlarmMonitor, bucketStartTimeNs,
            [](const ConfigKey& key) { return true; },
            [&uid, &broadcastCount, &activeConfigsBroadcast](const int& broadcastUid,
                                                             const vector<int64_t>& activeConfigs) {
                broadcastCount++;
                EXPECT_EQ(broadcastUid, uid);
                activeConfigsBroadcast.clear();
                activeConfigsBroadcast.insert(activeConfigsBroadcast.end(), activeConfigs.begin(),
                                              activeConfigs.end());
                return true;
            });

    processor.OnConfigUpdated(bucketStartTimeNs, cfgKey, config);

    ASSERT_EQ(processor.mMetricsManagers.size(), 1u);
    sp<MetricsManager> metricsManager = processor.mMetricsManagers.begin()->second;
    EXPECT_TRUE(metricsManager->isConfigValid());
    ASSERT_EQ(metricsManager->mAllMetricProducers.size(), 1);
    sp<MetricProducer> metricProducer = metricsManager->mAllMetricProducers[0];
    auto& eventActivationMap = metricProducer->mEventActivationMap;
    auto& eventDeactivationMap = metricProducer->mEventDeactivationMap;

    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    // Two activations: one is triggered by battery saver mode (tracker index 0), the other is
    // triggered by screen on event (tracker index 2).
    ASSERT_EQ(eventActivationMap.size(), 2u);
    EXPECT_TRUE(eventActivationMap.find(0) != eventActivationMap.end());
    EXPECT_TRUE(eventActivationMap.find(2) != eventActivationMap.end());
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, 0);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, 0);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    ASSERT_EQ(eventDeactivationMap.size(), 1u);
    EXPECT_TRUE(eventDeactivationMap.find(3) != eventDeactivationMap.end());
    ASSERT_EQ(eventDeactivationMap[3].size(), 2u);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);
    EXPECT_EQ(eventDeactivationMap[3][1], eventActivationMap[2]);
    EXPECT_EQ(broadcastCount, 0);

    std::unique_ptr<LogEvent> event;

    // Event that should be ignored.
    event = CreateAppCrashEvent(bucketStartTimeNs + 1, 111);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + 1);

    // Activate metric via screen on for 2 minutes.
    event = CreateScreenStateChangedEvent(bucketStartTimeNs + 10, android::view::DISPLAY_STATE_ON);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + 10);
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 1);
    ASSERT_EQ(activeConfigsBroadcast.size(), 1);
    EXPECT_EQ(activeConfigsBroadcast[0], cfgId);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + 10);

    // 1st processed event.
    event = CreateAppCrashEvent(bucketStartTimeNs + 15, 222);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + 15);

    // Enable battery saver mode activation for 5 minutes.
    event = CreateBatterySaverOnEvent(bucketStartTimeNs + NS_PER_SEC * 60 + 10);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 + 10);
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 1);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 + 10);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + 10);

    // 2nd processed event.
    event = CreateAppCrashEvent(bucketStartTimeNs + NS_PER_SEC * 60 + 40, 333);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 + 40);

    // Cancel battery saver mode and screen on activation.
    int64_t firstDeactivation = bucketStartTimeNs + NS_PER_SEC * 61;
    event = CreateScreenBrightnessChangedEvent(firstDeactivation, 64);
    processor.OnLogEvent(event.get(), firstDeactivation);
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    // New broadcast since the config is no longer active.
    EXPECT_EQ(broadcastCount, 2);
    ASSERT_EQ(activeConfigsBroadcast.size(), 0);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);

    // Should be ignored
    event = CreateAppCrashEvent(bucketStartTimeNs + NS_PER_SEC * 61 + 80, 444);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 61 + 80);

    // Re-enable battery saver mode activation.
    event = CreateBatterySaverOnEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 15);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 15);
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 3);
    ASSERT_EQ(activeConfigsBroadcast.size(), 1);
    EXPECT_EQ(activeConfigsBroadcast[0], cfgId);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 15);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);

    // 3rd processed event.
    event = CreateAppCrashEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 80, 555);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 80);

    // Cancel battery saver mode activation.
    int64_t secondDeactivation = bucketStartTimeNs + NS_PER_SEC * 60 * 13;
    event = CreateScreenBrightnessChangedEvent(secondDeactivation, 140);
    processor.OnLogEvent(event.get(), secondDeactivation);
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 4);
    ASSERT_EQ(activeConfigsBroadcast.size(), 0);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);

    // Should be ignored.
    event = CreateAppCrashEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 13 + 80, 666);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 13 + 80);

    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor.onDumpReport(cfgKey, bucketStartTimeNs + NS_PER_SEC * 60 * 15 + 1, false, true,
                           ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(buffer.size() > 0);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStartEndTimestamp(&reports);
    ASSERT_EQ(1, reports.reports_size());
    ASSERT_EQ(1, reports.reports(0).metrics_size());

    StatsLogReport::CountMetricDataWrapper countMetrics;
    sortMetricDataByDimensionsValue(reports.reports(0).metrics(0).count_metrics(), &countMetrics);
    ASSERT_EQ(3, countMetrics.data_size());

    auto data = countMetrics.data(0);
    EXPECT_EQ(util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    ASSERT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(222, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(firstDeactivation, data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(1);
    EXPECT_EQ(util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    ASSERT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(333, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(firstDeactivation, data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(2);
    EXPECT_EQ(util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    ASSERT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(555, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    // Partial bucket as metric is deactivated.
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs,
              data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(secondDeactivation, data.bucket_info(0).end_bucket_elapsed_nanos());
}

TEST(MetricActivationE2eTest, TestCountMetricWithTwoMetricsTwoDeactivations) {
    auto config = CreateStatsdConfigWithTwoMetricsTwoDeactivations();

    int64_t bucketStartTimeNs = NS_PER_SEC * 10;  // 10 secs
    int64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.count_metric(0).bucket()) * 1000LL * 1000LL;

    int uid = 12345;
    int64_t cfgId = 98765;
    ConfigKey cfgKey(uid, cfgId);

    sp<UidMap> m = new UidMap();
    sp<StatsPullerManager> pullerManager = new StatsPullerManager();
    sp<AlarmMonitor> anomalyAlarmMonitor;
    sp<AlarmMonitor> subscriberAlarmMonitor;
    vector<int64_t> activeConfigsBroadcast;

    long timeBase1 = 1;
    int broadcastCount = 0;
    StatsLogProcessor processor(
            m, pullerManager, anomalyAlarmMonitor, subscriberAlarmMonitor, bucketStartTimeNs,
            [](const ConfigKey& key) { return true; },
            [&uid, &broadcastCount, &activeConfigsBroadcast](const int& broadcastUid,
                                                             const vector<int64_t>& activeConfigs) {
                broadcastCount++;
                EXPECT_EQ(broadcastUid, uid);
                activeConfigsBroadcast.clear();
                activeConfigsBroadcast.insert(activeConfigsBroadcast.end(), activeConfigs.begin(),
                                              activeConfigs.end());
                return true;
            });

    processor.OnConfigUpdated(bucketStartTimeNs, cfgKey, config);

    ASSERT_EQ(processor.mMetricsManagers.size(), 1u);
    sp<MetricsManager> metricsManager = processor.mMetricsManagers.begin()->second;
    EXPECT_TRUE(metricsManager->isConfigValid());
    ASSERT_EQ(metricsManager->mAllMetricProducers.size(), 2);
    sp<MetricProducer> metricProducer = metricsManager->mAllMetricProducers[0];
    auto& eventActivationMap = metricProducer->mEventActivationMap;
    auto& eventDeactivationMap = metricProducer->mEventDeactivationMap;
    sp<MetricProducer> metricProducer2 = metricsManager->mAllMetricProducers[1];
    auto& eventActivationMap2 = metricProducer2->mEventActivationMap;
    auto& eventDeactivationMap2 = metricProducer2->mEventDeactivationMap;

    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    EXPECT_FALSE(metricProducer2->mIsActive);
    // Two activations: one is triggered by battery saver mode (tracker index 0), the other is
    // triggered by screen on event (tracker index 2).
    ASSERT_EQ(eventActivationMap.size(), 2u);
    EXPECT_TRUE(eventActivationMap.find(0) != eventActivationMap.end());
    EXPECT_TRUE(eventActivationMap.find(2) != eventActivationMap.end());
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, 0);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, 0);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    ASSERT_EQ(eventDeactivationMap.size(), 2u);
    EXPECT_TRUE(eventDeactivationMap.find(3) != eventDeactivationMap.end());
    EXPECT_TRUE(eventDeactivationMap.find(4) != eventDeactivationMap.end());
    ASSERT_EQ(eventDeactivationMap[3].size(), 1u);
    ASSERT_EQ(eventDeactivationMap[4].size(), 1u);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);
    EXPECT_EQ(eventDeactivationMap[4][0], eventActivationMap[2]);

    ASSERT_EQ(eventActivationMap2.size(), 2u);
    EXPECT_TRUE(eventActivationMap2.find(0) != eventActivationMap2.end());
    EXPECT_TRUE(eventActivationMap2.find(2) != eventActivationMap2.end());
    EXPECT_EQ(eventActivationMap2[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap2[0]->start_ns, 0);
    EXPECT_EQ(eventActivationMap2[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap2[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap2[2]->start_ns, 0);
    EXPECT_EQ(eventActivationMap2[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    ASSERT_EQ(eventDeactivationMap2.size(), 2u);
    EXPECT_TRUE(eventDeactivationMap2.find(3) != eventDeactivationMap2.end());
    EXPECT_TRUE(eventDeactivationMap2.find(4) != eventDeactivationMap2.end());
    ASSERT_EQ(eventDeactivationMap[3].size(), 1u);
    ASSERT_EQ(eventDeactivationMap[4].size(), 1u);
    EXPECT_EQ(eventDeactivationMap2[3][0], eventActivationMap2[0]);
    EXPECT_EQ(eventDeactivationMap2[4][0], eventActivationMap2[2]);

    std::unique_ptr<LogEvent> event;

    event = CreateAppCrashEvent(bucketStartTimeNs + 5, 111);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + 5);
    event = CreateMoveToForegroundEvent(bucketStartTimeNs + 5, 1111);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + 5);
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    EXPECT_FALSE(metricProducer2->mIsActive);
    EXPECT_EQ(broadcastCount, 0);

    // Activated by battery save mode.
    event = CreateBatterySaverOnEvent(bucketStartTimeNs + 10);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + 10);
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_EQ(broadcastCount, 1);
    ASSERT_EQ(activeConfigsBroadcast.size(), 1);
    EXPECT_EQ(activeConfigsBroadcast[0], cfgId);
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, 0);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);
    EXPECT_EQ(eventDeactivationMap[4][0], eventActivationMap[2]);
    EXPECT_TRUE(metricProducer2->mIsActive);
    EXPECT_EQ(eventActivationMap2[0]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap2[0]->start_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap2[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap2[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap2[2]->start_ns, 0);
    EXPECT_EQ(eventActivationMap2[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap2[3][0], eventActivationMap2[0]);
    EXPECT_EQ(eventDeactivationMap2[4][0], eventActivationMap2[2]);

    // First processed event.
    event = CreateAppCrashEvent(bucketStartTimeNs + 15, 222);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + 15);
    event = CreateMoveToForegroundEvent(bucketStartTimeNs + 15, 2222);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + 15);

    // Activated by screen on event.
    event = CreateScreenStateChangedEvent(bucketStartTimeNs + 20, android::view::DISPLAY_STATE_ON);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + 20);
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + 20);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);
    EXPECT_EQ(eventDeactivationMap[4][0], eventActivationMap[2]);
    EXPECT_TRUE(metricProducer2->mIsActive);
    EXPECT_EQ(eventActivationMap2[0]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap2[0]->start_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap2[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap2[2]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap2[2]->start_ns, bucketStartTimeNs + 20);
    EXPECT_EQ(eventActivationMap2[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap2[3][0], eventActivationMap2[0]);
    EXPECT_EQ(eventDeactivationMap2[4][0], eventActivationMap2[2]);

    // 2nd processed event.
    // The activation by screen_on event expires, but the one by battery save mode is still active.
    event = CreateAppCrashEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 2 + 25, 333);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 2 + 25);
    event = CreateMoveToForegroundEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 2 + 25, 3333);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 2 + 25);
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + 20);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);
    EXPECT_EQ(eventDeactivationMap[4][0], eventActivationMap[2]);
    EXPECT_TRUE(metricProducer2->mIsActive);
    EXPECT_EQ(eventActivationMap2[0]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap2[0]->start_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap2[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap2[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap2[2]->start_ns, bucketStartTimeNs + 20);
    EXPECT_EQ(eventActivationMap2[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap2[3][0], eventActivationMap2[0]);
    EXPECT_EQ(eventDeactivationMap2[4][0], eventActivationMap2[2]);
    // No new broadcast since the config should still be active.
    EXPECT_EQ(broadcastCount, 1);

    // 3rd processed event.
    event = CreateAppCrashEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 5 + 25, 444);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 5 + 25);
    event = CreateMoveToForegroundEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 5 + 25, 4444);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 5 + 25);

    // All activations expired.
    event = CreateAppCrashEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 8, 555);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 8);
    event = CreateMoveToForegroundEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 8, 5555);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 8);
    EXPECT_FALSE(metricsManager->isActive());
    // New broadcast since the config is no longer active.
    EXPECT_EQ(broadcastCount, 2);
    ASSERT_EQ(activeConfigsBroadcast.size(), 0);
    EXPECT_FALSE(metricProducer->mIsActive);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + 20);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);
    EXPECT_EQ(eventDeactivationMap[4][0], eventActivationMap[2]);
    EXPECT_FALSE(metricProducer2->mIsActive);
    EXPECT_EQ(eventActivationMap2[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap2[0]->start_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap2[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap2[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap2[2]->start_ns, bucketStartTimeNs + 20);
    EXPECT_EQ(eventActivationMap2[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap2[3][0], eventActivationMap2[0]);
    EXPECT_EQ(eventDeactivationMap2[4][0], eventActivationMap2[2]);

    // Re-activate metric via screen on.
    event = CreateScreenStateChangedEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10,
                                          android::view::DISPLAY_STATE_ON);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_EQ(broadcastCount, 3);
    ASSERT_EQ(activeConfigsBroadcast.size(), 1);
    EXPECT_EQ(activeConfigsBroadcast[0], cfgId);
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);
    EXPECT_EQ(eventDeactivationMap[4][0], eventActivationMap[2]);
    EXPECT_TRUE(metricProducer2->mIsActive);
    EXPECT_EQ(eventActivationMap2[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap2[0]->start_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap2[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap2[2]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap2[2]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_EQ(eventActivationMap2[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap2[3][0], eventActivationMap2[0]);
    EXPECT_EQ(eventDeactivationMap2[4][0], eventActivationMap2[2]);

    // 4th processed event.
    event = CreateAppCrashEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 1, 666);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 1);
    event = CreateMoveToForegroundEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 1, 6666);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 1);

    // Re-enable battery saver mode activation.
    event = CreateBatterySaverOnEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 15);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 15);
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_EQ(broadcastCount, 3);
    ASSERT_EQ(activeConfigsBroadcast.size(), 1);
    EXPECT_EQ(activeConfigsBroadcast[0], cfgId);
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 15);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);
    EXPECT_EQ(eventDeactivationMap[4][0], eventActivationMap[2]);
    EXPECT_TRUE(metricProducer2->mIsActive);
    EXPECT_EQ(eventActivationMap2[0]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap2[0]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 15);
    EXPECT_EQ(eventActivationMap2[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap2[2]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap2[2]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_EQ(eventActivationMap2[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap2[3][0], eventActivationMap2[0]);
    EXPECT_EQ(eventDeactivationMap2[4][0], eventActivationMap2[2]);

    // 5th processed event.
    event = CreateAppCrashEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 40, 777);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 40);
    event = CreateMoveToForegroundEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 40, 7777);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 40);

    // Cancel battery saver mode and screen on activation.
    event = CreateScreenBrightnessChangedEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 60, 64);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 60);
    EXPECT_FALSE(metricsManager->isActive());
    // New broadcast since the config is no longer active.
    EXPECT_EQ(broadcastCount, 4);
    ASSERT_EQ(activeConfigsBroadcast.size(), 0);
    EXPECT_FALSE(metricProducer->mIsActive);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 15);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);
    EXPECT_EQ(eventDeactivationMap[4][0], eventActivationMap[2]);
    EXPECT_FALSE(metricProducer2->mIsActive);
    EXPECT_EQ(eventActivationMap2[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap2[0]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 15);
    EXPECT_EQ(eventActivationMap2[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap2[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap2[2]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_EQ(eventActivationMap2[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap2[3][0], eventActivationMap2[0]);
    EXPECT_EQ(eventDeactivationMap2[4][0], eventActivationMap2[2]);

    // Screen-on activation expired.
    event = CreateAppCrashEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 13, 888);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 13);
    event = CreateMoveToForegroundEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 13, 8888);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 13);
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_EQ(broadcastCount, 4);
    ASSERT_EQ(activeConfigsBroadcast.size(), 0);
    EXPECT_FALSE(metricProducer->mIsActive);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 15);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);
    EXPECT_EQ(eventDeactivationMap[4][0], eventActivationMap[2]);
    EXPECT_FALSE(metricProducer2->mIsActive);
    EXPECT_EQ(eventActivationMap2[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap2[0]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 15);
    EXPECT_EQ(eventActivationMap2[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap2[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap2[2]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_EQ(eventActivationMap2[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap2[3][0], eventActivationMap2[0]);
    EXPECT_EQ(eventDeactivationMap2[4][0], eventActivationMap2[2]);

    event = CreateAppCrashEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 14 + 1, 999);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 14 + 1);
    event = CreateMoveToForegroundEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 14 + 1, 9999);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 14 + 1);

    // Re-enable battery saver mode activation.
    event = CreateBatterySaverOnEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 15 + 15);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 15 + 15);
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_EQ(broadcastCount, 5);
    ASSERT_EQ(activeConfigsBroadcast.size(), 1);
    EXPECT_EQ(activeConfigsBroadcast[0], cfgId);
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 15 + 15);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);
    EXPECT_EQ(eventDeactivationMap[4][0], eventActivationMap[2]);
    EXPECT_TRUE(metricProducer2->mIsActive);
    EXPECT_EQ(eventActivationMap2[0]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap2[0]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 15 + 15);
    EXPECT_EQ(eventActivationMap2[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap2[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap2[2]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_EQ(eventActivationMap2[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap2[3][0], eventActivationMap2[0]);
    EXPECT_EQ(eventDeactivationMap2[4][0], eventActivationMap2[2]);

    // Cancel battery saver mode and screen on activation.
    event = CreateScreenBrightnessChangedEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 16, 140);
    processor.OnLogEvent(event.get(), bucketStartTimeNs + NS_PER_SEC * 60 * 16);
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_EQ(broadcastCount, 6);
    ASSERT_EQ(activeConfigsBroadcast.size(), 0);
    EXPECT_FALSE(metricProducer->mIsActive);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 15 + 15);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);
    EXPECT_EQ(eventDeactivationMap[4][0], eventActivationMap[2]);
    EXPECT_FALSE(metricProducer2->mIsActive);
    EXPECT_EQ(eventActivationMap2[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap2[0]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 15 + 15);
    EXPECT_EQ(eventActivationMap2[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap2[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap2[2]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_EQ(eventActivationMap2[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap2[3][0], eventActivationMap2[0]);
    EXPECT_EQ(eventDeactivationMap2[4][0], eventActivationMap2[2]);

    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor.onDumpReport(cfgKey, bucketStartTimeNs + NS_PER_SEC * 60 * 15 + 1, false, true,
                           ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(buffer.size() > 0);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStartEndTimestamp(&reports);
    ASSERT_EQ(1, reports.reports_size());
    ASSERT_EQ(2, reports.reports(0).metrics_size());

    StatsLogReport::CountMetricDataWrapper countMetrics;

    sortMetricDataByDimensionsValue(reports.reports(0).metrics(0).count_metrics(), &countMetrics);
    ASSERT_EQ(5, countMetrics.data_size());

    auto data = countMetrics.data(0);
    EXPECT_EQ(util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    ASSERT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(222, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(1);
    EXPECT_EQ(util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    ASSERT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(333, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(2);
    EXPECT_EQ(util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    ASSERT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(444, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    // Partial bucket as metric is deactivated.
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + NS_PER_SEC * 60 * 8,
              data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(3);
    EXPECT_EQ(util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    ASSERT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(666, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs,
              data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + NS_PER_SEC * 60 * 11,
              data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(4);
    EXPECT_EQ(util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    ASSERT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(777, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs,
              data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + NS_PER_SEC * 60 * 11,
              data.bucket_info(0).end_bucket_elapsed_nanos());

    countMetrics.clear_data();
    sortMetricDataByDimensionsValue(reports.reports(0).metrics(1).count_metrics(), &countMetrics);
    ASSERT_EQ(5, countMetrics.data_size());

    data = countMetrics.data(0);
    EXPECT_EQ(util::ACTIVITY_FOREGROUND_STATE_CHANGED, data.dimensions_in_what().field());
    ASSERT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(2222, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(1);
    EXPECT_EQ(util::ACTIVITY_FOREGROUND_STATE_CHANGED, data.dimensions_in_what().field());
    ASSERT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(3333, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(2);
    EXPECT_EQ(util::ACTIVITY_FOREGROUND_STATE_CHANGED, data.dimensions_in_what().field());
    ASSERT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(4444, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    // Partial bucket as metric is deactivated.
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + NS_PER_SEC * 60 * 8,
              data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(3);
    EXPECT_EQ(util::ACTIVITY_FOREGROUND_STATE_CHANGED, data.dimensions_in_what().field());
    ASSERT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(6666, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs,
              data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + NS_PER_SEC * 60 * 11,
              data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(4);
    EXPECT_EQ(util::ACTIVITY_FOREGROUND_STATE_CHANGED, data.dimensions_in_what().field());
    ASSERT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(7777, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs,
              data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + NS_PER_SEC * 60 * 11,
              data.bucket_info(0).end_bucket_elapsed_nanos());
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif

}  // namespace statsd
}  // namespace os
}  // namespace android
