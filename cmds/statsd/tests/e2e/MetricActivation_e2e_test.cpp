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
        android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED);
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
        android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED);
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
        android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED);
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
        android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED);
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
        android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED);
    countMetric->mutable_dimensions_in_what()->add_child()->set_field(1);  // uid field

    int64_t metricId2 = 234567;
    countMetric = config.add_count_metric();
    countMetric->set_id(metricId2);
    countMetric->set_what(foregroundMatcher.id());
    countMetric->set_bucket(FIVE_MINUTES);
    countMetric->mutable_dimensions_in_what()->set_field(
        android::util::ACTIVITY_FOREGROUND_STATE_CHANGED);
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

    int64_t bucketStartTimeNs = NS_PER_SEC * 10; // 10 secs
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
    StatsLogProcessor processor(m, pullerManager, anomalyAlarmMonitor, subscriberAlarmMonitor,
            bucketStartTimeNs, [](const ConfigKey& key) { return true; },
            [&uid, &broadcastCount, &activeConfigsBroadcast](const int& broadcastUid,
                    const vector<int64_t>& activeConfigs) {
                broadcastCount++;
                EXPECT_EQ(broadcastUid, uid);
                activeConfigsBroadcast.clear();
                activeConfigsBroadcast.insert(activeConfigsBroadcast.end(),
                        activeConfigs.begin(), activeConfigs.end());
                return true;
            });

    processor.OnConfigUpdated(bucketStartTimeNs, cfgKey, config);

    EXPECT_EQ(processor.mMetricsManagers.size(), 1u);
    sp<MetricsManager> metricsManager = processor.mMetricsManagers.begin()->second;
    EXPECT_TRUE(metricsManager->isConfigValid());
    EXPECT_EQ(metricsManager->mAllMetricProducers.size(), 1);
    sp<MetricProducer> metricProducer = metricsManager->mAllMetricProducers[0];
    auto& eventActivationMap = metricProducer->mEventActivationMap;

    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    // Two activations: one is triggered by battery saver mode (tracker index 0), the other is
    // triggered by screen on event (tracker index 2).
    EXPECT_EQ(eventActivationMap.size(), 2u);
    EXPECT_TRUE(eventActivationMap.find(0) != eventActivationMap.end());
    EXPECT_TRUE(eventActivationMap.find(2) != eventActivationMap.end());
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, 0);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, 0);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);

    std::unique_ptr<LogEvent> event;

    event = CreateAppCrashEvent(111, bucketStartTimeNs + 5);
    processor.OnLogEvent(event.get());
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 0);

    // Activated by battery save mode.
    event = CreateBatterySaverOnEvent(bucketStartTimeNs + 10);
    processor.OnLogEvent(event.get());
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 1);
    EXPECT_EQ(activeConfigsBroadcast.size(), 1);
    EXPECT_EQ(activeConfigsBroadcast[0], cfgId);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, 0);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);

    // First processed event.
    event = CreateAppCrashEvent(222, bucketStartTimeNs + 15);
    processor.OnLogEvent(event.get());

    // Activated by screen on event.
    event = CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_ON,
                                          bucketStartTimeNs + 20);
    processor.OnLogEvent(event.get());
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
    event = CreateAppCrashEvent(333, bucketStartTimeNs + NS_PER_SEC * 60 * 2 + 25);
    processor.OnLogEvent(event.get());
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
    event = CreateAppCrashEvent(444, bucketStartTimeNs + NS_PER_SEC * 60 * 5 + 25);
    processor.OnLogEvent(event.get());

    // All activations expired.
    event = CreateAppCrashEvent(555, bucketStartTimeNs + NS_PER_SEC * 60 * 8);
    processor.OnLogEvent(event.get());
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    // New broadcast since the config is no longer active.
    EXPECT_EQ(broadcastCount, 2);
    EXPECT_EQ(activeConfigsBroadcast.size(), 0);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + 20);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);

    // Re-activate metric via screen on.
    event = CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_ON,
                                          bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    processor.OnLogEvent(event.get());
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 3);
    EXPECT_EQ(activeConfigsBroadcast.size(), 1);
    EXPECT_EQ(activeConfigsBroadcast[0], cfgId);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);

    // 4th processed event.
    event = CreateAppCrashEvent(666, bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 1);
    processor.OnLogEvent(event.get());

    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor.onDumpReport(cfgKey, bucketStartTimeNs + NS_PER_SEC * 60 * 15 + 1, false, true,
                            ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(buffer.size() > 0);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStartEndTimestamp(&reports);
    EXPECT_EQ(1, reports.reports_size());
    EXPECT_EQ(1, reports.reports(0).metrics_size());
    EXPECT_EQ(4, reports.reports(0).metrics(0).count_metrics().data_size());

    StatsLogReport::CountMetricDataWrapper countMetrics;
    sortMetricDataByDimensionsValue(
            reports.reports(0).metrics(0).count_metrics(), &countMetrics);
    EXPECT_EQ(4, countMetrics.data_size());

    auto data = countMetrics.data(0);
    EXPECT_EQ(android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(222, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    EXPECT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(1);
    EXPECT_EQ(android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(333, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    EXPECT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(2);
    EXPECT_EQ(android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(444, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    EXPECT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    // Partial bucket as metric is deactivated.
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + NS_PER_SEC * 60 * 8,
              data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(3);
    EXPECT_EQ(android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(666, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    EXPECT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs,
              data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + 3 * bucketSizeNs,
              data.bucket_info(0).end_bucket_elapsed_nanos());
}

TEST(MetricActivationE2eTest, TestCountMetricWithOneDeactivation) {
    auto config = CreateStatsdConfigWithOneDeactivation();

    int64_t bucketStartTimeNs = NS_PER_SEC * 10; // 10 secs
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
    StatsLogProcessor processor(m, pullerManager, anomalyAlarmMonitor, subscriberAlarmMonitor,
            bucketStartTimeNs, [](const ConfigKey& key) { return true; },
            [&uid, &broadcastCount, &activeConfigsBroadcast](const int& broadcastUid,
                    const vector<int64_t>& activeConfigs) {
                broadcastCount++;
                EXPECT_EQ(broadcastUid, uid);
                activeConfigsBroadcast.clear();
                activeConfigsBroadcast.insert(activeConfigsBroadcast.end(),
                        activeConfigs.begin(), activeConfigs.end());
                return true;
            });

    processor.OnConfigUpdated(bucketStartTimeNs, cfgKey, config);

    EXPECT_EQ(processor.mMetricsManagers.size(), 1u);
    sp<MetricsManager> metricsManager = processor.mMetricsManagers.begin()->second;
    EXPECT_TRUE(metricsManager->isConfigValid());
    EXPECT_EQ(metricsManager->mAllMetricProducers.size(), 1);
    sp<MetricProducer> metricProducer = metricsManager->mAllMetricProducers[0];
    auto& eventActivationMap = metricProducer->mEventActivationMap;
    auto& eventDeactivationMap = metricProducer->mEventDeactivationMap;

    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    // Two activations: one is triggered by battery saver mode (tracker index 0), the other is
    // triggered by screen on event (tracker index 2).
    EXPECT_EQ(eventActivationMap.size(), 2u);
    EXPECT_TRUE(eventActivationMap.find(0) != eventActivationMap.end());
    EXPECT_TRUE(eventActivationMap.find(2) != eventActivationMap.end());
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, 0);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, 0);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap.size(), 1u);
    EXPECT_TRUE(eventDeactivationMap.find(3) != eventDeactivationMap.end());
    EXPECT_EQ(eventDeactivationMap[3].size(), 1u);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);

    std::unique_ptr<LogEvent> event;

    event = CreateAppCrashEvent(111, bucketStartTimeNs + 5);
    processor.OnLogEvent(event.get());
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 0);

    // Activated by battery save mode.
    event = CreateBatterySaverOnEvent(bucketStartTimeNs + 10);
    processor.OnLogEvent(event.get());
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 1);
    EXPECT_EQ(activeConfigsBroadcast.size(), 1);
    EXPECT_EQ(activeConfigsBroadcast[0], cfgId);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, 0);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);

    // First processed event.
    event = CreateAppCrashEvent(222, bucketStartTimeNs + 15);
    processor.OnLogEvent(event.get());

    // Activated by screen on event.
    event = CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_ON,
                                          bucketStartTimeNs + 20);
    processor.OnLogEvent(event.get());
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
    event = CreateAppCrashEvent(333, bucketStartTimeNs + NS_PER_SEC * 60 * 2 + 25);
    processor.OnLogEvent(event.get());
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
    event = CreateAppCrashEvent(444, bucketStartTimeNs + NS_PER_SEC * 60 * 5 + 25);
    processor.OnLogEvent(event.get());

    // All activations expired.
    event = CreateAppCrashEvent(555, bucketStartTimeNs + NS_PER_SEC * 60 * 8);
    processor.OnLogEvent(event.get());
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    // New broadcast since the config is no longer active.
    EXPECT_EQ(broadcastCount, 2);
    EXPECT_EQ(activeConfigsBroadcast.size(), 0);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + 20);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);

    // Re-activate metric via screen on.
    event = CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_ON,
                                          bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    processor.OnLogEvent(event.get());
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 3);
    EXPECT_EQ(activeConfigsBroadcast.size(), 1);
    EXPECT_EQ(activeConfigsBroadcast[0], cfgId);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);

    // 4th processed event.
    event = CreateAppCrashEvent(666, bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 1);
    processor.OnLogEvent(event.get());

    // Re-enable battery saver mode activation.
    event = CreateBatterySaverOnEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 15);
    processor.OnLogEvent(event.get());
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 3);
    EXPECT_EQ(activeConfigsBroadcast.size(), 1);
    EXPECT_EQ(activeConfigsBroadcast[0], cfgId);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 15);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);

    // 5th processed event.
    event = CreateAppCrashEvent(777, bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 40);
    processor.OnLogEvent(event.get());

    // Cancel battery saver mode activation.
    event = CreateScreenBrightnessChangedEvent(64, bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 60);
    processor.OnLogEvent(event.get());
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 3);
    EXPECT_EQ(activeConfigsBroadcast.size(), 1);
    EXPECT_EQ(activeConfigsBroadcast[0], cfgId);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 15);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);

    // Screen-on activation expired.
    event = CreateAppCrashEvent(888, bucketStartTimeNs + NS_PER_SEC * 60 * 13);
    processor.OnLogEvent(event.get());
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    // New broadcast since the config is no longer active.
    EXPECT_EQ(broadcastCount, 4);
    EXPECT_EQ(activeConfigsBroadcast.size(), 0);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 15);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);

    event = CreateAppCrashEvent(999, bucketStartTimeNs + NS_PER_SEC * 60 * 14 + 1);
    processor.OnLogEvent(event.get());

    // Re-enable battery saver mode activation.
    event = CreateBatterySaverOnEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 15 + 15);
    processor.OnLogEvent(event.get());
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 5);
    EXPECT_EQ(activeConfigsBroadcast.size(), 1);
    EXPECT_EQ(activeConfigsBroadcast[0], cfgId);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 15 + 15);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);

    // Cancel battery saver mode activation.
    event = CreateScreenBrightnessChangedEvent(140, bucketStartTimeNs + NS_PER_SEC * 60 * 16);
    processor.OnLogEvent(event.get());
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 6);
    EXPECT_EQ(activeConfigsBroadcast.size(), 0);
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
    EXPECT_EQ(1, reports.reports_size());
    EXPECT_EQ(1, reports.reports(0).metrics_size());
    EXPECT_EQ(5, reports.reports(0).metrics(0).count_metrics().data_size());

    StatsLogReport::CountMetricDataWrapper countMetrics;
    sortMetricDataByDimensionsValue(
            reports.reports(0).metrics(0).count_metrics(), &countMetrics);
    EXPECT_EQ(5, countMetrics.data_size());

    auto data = countMetrics.data(0);
    EXPECT_EQ(android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(222, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    EXPECT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(1);
    EXPECT_EQ(android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(333, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    EXPECT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(2);
    EXPECT_EQ(android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(444, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    EXPECT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    // Partial bucket as metric is deactivated.
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + NS_PER_SEC * 60 * 8,
              data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(3);
    EXPECT_EQ(android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(666, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    EXPECT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs,
              data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + NS_PER_SEC * 60 * 13,
              data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(4);
    EXPECT_EQ(android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(777, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    EXPECT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs,
              data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + NS_PER_SEC * 60 * 13,
              data.bucket_info(0).end_bucket_elapsed_nanos());
}

TEST(MetricActivationE2eTest, TestCountMetricWithTwoDeactivations) {
    auto config = CreateStatsdConfigWithTwoDeactivations();

    int64_t bucketStartTimeNs = NS_PER_SEC * 10; // 10 secs
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
    StatsLogProcessor processor(m, pullerManager, anomalyAlarmMonitor, subscriberAlarmMonitor,
            bucketStartTimeNs, [](const ConfigKey& key) { return true; },
            [&uid, &broadcastCount, &activeConfigsBroadcast](const int& broadcastUid,
                    const vector<int64_t>& activeConfigs) {
                broadcastCount++;
                EXPECT_EQ(broadcastUid, uid);
                activeConfigsBroadcast.clear();
                activeConfigsBroadcast.insert(activeConfigsBroadcast.end(),
                        activeConfigs.begin(), activeConfigs.end());
                return true;
            });

    processor.OnConfigUpdated(bucketStartTimeNs, cfgKey, config);

    EXPECT_EQ(processor.mMetricsManagers.size(), 1u);
    sp<MetricsManager> metricsManager = processor.mMetricsManagers.begin()->second;
    EXPECT_TRUE(metricsManager->isConfigValid());
    EXPECT_EQ(metricsManager->mAllMetricProducers.size(), 1);
    sp<MetricProducer> metricProducer = metricsManager->mAllMetricProducers[0];
    auto& eventActivationMap = metricProducer->mEventActivationMap;
    auto& eventDeactivationMap = metricProducer->mEventDeactivationMap;

    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    // Two activations: one is triggered by battery saver mode (tracker index 0), the other is
    // triggered by screen on event (tracker index 2).
    EXPECT_EQ(eventActivationMap.size(), 2u);
    EXPECT_TRUE(eventActivationMap.find(0) != eventActivationMap.end());
    EXPECT_TRUE(eventActivationMap.find(2) != eventActivationMap.end());
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, 0);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, 0);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap.size(), 2u);
    EXPECT_TRUE(eventDeactivationMap.find(3) != eventDeactivationMap.end());
    EXPECT_TRUE(eventDeactivationMap.find(4) != eventDeactivationMap.end());
    EXPECT_EQ(eventDeactivationMap[3].size(), 1u);
    EXPECT_EQ(eventDeactivationMap[4].size(), 1u);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);
    EXPECT_EQ(eventDeactivationMap[4][0], eventActivationMap[2]);

    std::unique_ptr<LogEvent> event;

    event = CreateAppCrashEvent(111, bucketStartTimeNs + 5);
    processor.OnLogEvent(event.get());
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 0);

    // Activated by battery save mode.
    event = CreateBatterySaverOnEvent(bucketStartTimeNs + 10);
    processor.OnLogEvent(event.get());
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 1);
    EXPECT_EQ(activeConfigsBroadcast.size(), 1);
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
    event = CreateAppCrashEvent(222, bucketStartTimeNs + 15);
    processor.OnLogEvent(event.get());

    // Activated by screen on event.
    event = CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_ON,
                                          bucketStartTimeNs + 20);
    processor.OnLogEvent(event.get());
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
    event = CreateAppCrashEvent(333, bucketStartTimeNs + NS_PER_SEC * 60 * 2 + 25);
    processor.OnLogEvent(event.get());
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
    event = CreateAppCrashEvent(444, bucketStartTimeNs + NS_PER_SEC * 60 * 5 + 25);
    processor.OnLogEvent(event.get());

    // All activations expired.
    event = CreateAppCrashEvent(555, bucketStartTimeNs + NS_PER_SEC * 60 * 8);
    processor.OnLogEvent(event.get());
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    // New broadcast since the config is no longer active.
    EXPECT_EQ(broadcastCount, 2);
    EXPECT_EQ(activeConfigsBroadcast.size(), 0);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + 20);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);
    EXPECT_EQ(eventDeactivationMap[4][0], eventActivationMap[2]);

    // Re-activate metric via screen on.
    event = CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_ON,
                                          bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    processor.OnLogEvent(event.get());
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 3);
    EXPECT_EQ(activeConfigsBroadcast.size(), 1);
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
    event = CreateAppCrashEvent(666, bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 1);
    processor.OnLogEvent(event.get());

    // Re-enable battery saver mode activation.
    event = CreateBatterySaverOnEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 15);
    processor.OnLogEvent(event.get());
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 3);
    EXPECT_EQ(activeConfigsBroadcast.size(), 1);
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
    event = CreateAppCrashEvent(777, bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 40);
    processor.OnLogEvent(event.get());

    // Cancel battery saver mode and screen on activation.
    event = CreateScreenBrightnessChangedEvent(64, bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 60);
    processor.OnLogEvent(event.get());
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    // New broadcast since the config is no longer active.
    EXPECT_EQ(broadcastCount, 4);
    EXPECT_EQ(activeConfigsBroadcast.size(), 0);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 15);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);
    EXPECT_EQ(eventDeactivationMap[4][0], eventActivationMap[2]);

    // Screen-on activation expired.
    event = CreateAppCrashEvent(888, bucketStartTimeNs + NS_PER_SEC * 60 * 13);
    processor.OnLogEvent(event.get());
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 4);
    EXPECT_EQ(activeConfigsBroadcast.size(), 0);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 15);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);
    EXPECT_EQ(eventDeactivationMap[4][0], eventActivationMap[2]);

    event = CreateAppCrashEvent(999, bucketStartTimeNs + NS_PER_SEC * 60 * 14 + 1);
    processor.OnLogEvent(event.get());

    // Re-enable battery saver mode activation.
    event = CreateBatterySaverOnEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 15 + 15);
    processor.OnLogEvent(event.get());
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 5);
    EXPECT_EQ(activeConfigsBroadcast.size(), 1);
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
    event = CreateScreenBrightnessChangedEvent(140, bucketStartTimeNs + NS_PER_SEC * 60 * 16);
    processor.OnLogEvent(event.get());
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 6);
    EXPECT_EQ(activeConfigsBroadcast.size(), 0);
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
    EXPECT_EQ(1, reports.reports_size());
    EXPECT_EQ(1, reports.reports(0).metrics_size());
    EXPECT_EQ(5, reports.reports(0).metrics(0).count_metrics().data_size());

    StatsLogReport::CountMetricDataWrapper countMetrics;
    sortMetricDataByDimensionsValue(
            reports.reports(0).metrics(0).count_metrics(), &countMetrics);
    EXPECT_EQ(5, countMetrics.data_size());

    auto data = countMetrics.data(0);
    EXPECT_EQ(android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(222, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    EXPECT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(1);
    EXPECT_EQ(android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(333, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    EXPECT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(2);
    EXPECT_EQ(android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(444, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    EXPECT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    // Partial bucket as metric is deactivated.
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + NS_PER_SEC * 60 * 8,
              data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(3);
    EXPECT_EQ(android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(666, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    EXPECT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs,
              data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + NS_PER_SEC * 60 * 11,
              data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(4);
    EXPECT_EQ(android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(777, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    EXPECT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs,
              data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + NS_PER_SEC * 60 * 11,
              data.bucket_info(0).end_bucket_elapsed_nanos());
}

TEST(MetricActivationE2eTest, TestCountMetricWithSameDeactivation) {
    auto config = CreateStatsdConfigWithSameDeactivations();

    int64_t bucketStartTimeNs = NS_PER_SEC * 10; // 10 secs
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
    StatsLogProcessor processor(m, pullerManager, anomalyAlarmMonitor, subscriberAlarmMonitor,
            bucketStartTimeNs, [](const ConfigKey& key) { return true; },
            [&uid, &broadcastCount, &activeConfigsBroadcast](const int& broadcastUid,
                    const vector<int64_t>& activeConfigs) {
                broadcastCount++;
                EXPECT_EQ(broadcastUid, uid);
                activeConfigsBroadcast.clear();
                activeConfigsBroadcast.insert(activeConfigsBroadcast.end(),
                        activeConfigs.begin(), activeConfigs.end());
                return true;
            });

    processor.OnConfigUpdated(bucketStartTimeNs, cfgKey, config);

    EXPECT_EQ(processor.mMetricsManagers.size(), 1u);
    sp<MetricsManager> metricsManager = processor.mMetricsManagers.begin()->second;
    EXPECT_TRUE(metricsManager->isConfigValid());
    EXPECT_EQ(metricsManager->mAllMetricProducers.size(), 1);
    sp<MetricProducer> metricProducer = metricsManager->mAllMetricProducers[0];
    auto& eventActivationMap = metricProducer->mEventActivationMap;
    auto& eventDeactivationMap = metricProducer->mEventDeactivationMap;

    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    // Two activations: one is triggered by battery saver mode (tracker index 0), the other is
    // triggered by screen on event (tracker index 2).
    EXPECT_EQ(eventActivationMap.size(), 2u);
    EXPECT_TRUE(eventActivationMap.find(0) != eventActivationMap.end());
    EXPECT_TRUE(eventActivationMap.find(2) != eventActivationMap.end());
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, 0);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, 0);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap.size(), 1u);
    EXPECT_TRUE(eventDeactivationMap.find(3) != eventDeactivationMap.end());
    EXPECT_EQ(eventDeactivationMap[3].size(), 2u);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);
    EXPECT_EQ(eventDeactivationMap[3][1], eventActivationMap[2]);
    EXPECT_EQ(broadcastCount, 0);

    std::unique_ptr<LogEvent> event;

    // Event that should be ignored.
    event = CreateAppCrashEvent(111, bucketStartTimeNs + 1);
    processor.OnLogEvent(event.get());

    // Activate metric via screen on for 2 minutes.
    event = CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_ON, bucketStartTimeNs + 10);
    processor.OnLogEvent(event.get());
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 1);
    EXPECT_EQ(activeConfigsBroadcast.size(), 1);
    EXPECT_EQ(activeConfigsBroadcast[0], cfgId);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + 10);

    // 1st processed event.
    event = CreateAppCrashEvent(222, bucketStartTimeNs + 15);
    processor.OnLogEvent(event.get());

    // Enable battery saver mode activation for 5 minutes.
    event = CreateBatterySaverOnEvent(bucketStartTimeNs + NS_PER_SEC * 60 + 10);
    processor.OnLogEvent(event.get());
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 1);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 + 10);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, bucketStartTimeNs + 10);

    // 2nd processed event.
    event = CreateAppCrashEvent(333, bucketStartTimeNs + NS_PER_SEC * 60 + 40);
    processor.OnLogEvent(event.get());

    // Cancel battery saver mode and screen on activation.
    int64_t firstDeactivation = bucketStartTimeNs + NS_PER_SEC * 61;
    event = CreateScreenBrightnessChangedEvent(64, firstDeactivation);
    processor.OnLogEvent(event.get());
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    // New broadcast since the config is no longer active.
    EXPECT_EQ(broadcastCount, 2);
    EXPECT_EQ(activeConfigsBroadcast.size(), 0);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);

    // Should be ignored
    event = CreateAppCrashEvent(444, bucketStartTimeNs + NS_PER_SEC * 61 + 80);
    processor.OnLogEvent(event.get());

    // Re-enable battery saver mode activation.
    event = CreateBatterySaverOnEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 15);
    processor.OnLogEvent(event.get());
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 3);
    EXPECT_EQ(activeConfigsBroadcast.size(), 1);
    EXPECT_EQ(activeConfigsBroadcast[0], cfgId);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 15);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);

    // 3rd processed event.
    event = CreateAppCrashEvent(555, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 80);
    processor.OnLogEvent(event.get());

    // Cancel battery saver mode activation.
    int64_t secondDeactivation = bucketStartTimeNs + NS_PER_SEC * 60 * 13;
    event = CreateScreenBrightnessChangedEvent(140, secondDeactivation);
    processor.OnLogEvent(event.get());
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 4);
    EXPECT_EQ(activeConfigsBroadcast.size(), 0);
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);

    // Should be ignored.
    event = CreateAppCrashEvent(666, bucketStartTimeNs + NS_PER_SEC * 60 * 13 + 80);
    processor.OnLogEvent(event.get());

    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor.onDumpReport(cfgKey, bucketStartTimeNs + NS_PER_SEC * 60 * 15 + 1, false, true,
                            ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(buffer.size() > 0);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStartEndTimestamp(&reports);
    EXPECT_EQ(1, reports.reports_size());
    EXPECT_EQ(1, reports.reports(0).metrics_size());
    EXPECT_EQ(3, reports.reports(0).metrics(0).count_metrics().data_size());

    StatsLogReport::CountMetricDataWrapper countMetrics;
    sortMetricDataByDimensionsValue(
            reports.reports(0).metrics(0).count_metrics(), &countMetrics);
    EXPECT_EQ(3, countMetrics.data_size());

    auto data = countMetrics.data(0);
    EXPECT_EQ(android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(222, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    EXPECT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(firstDeactivation, data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(1);
    EXPECT_EQ(android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(333, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    EXPECT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(firstDeactivation, data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(2);
    EXPECT_EQ(android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(555, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    EXPECT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    // Partial bucket as metric is deactivated.
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(secondDeactivation, data.bucket_info(0).end_bucket_elapsed_nanos());
}

TEST(MetricActivationE2eTest, TestCountMetricWithTwoMetricsTwoDeactivations) {
    auto config = CreateStatsdConfigWithTwoMetricsTwoDeactivations();

    int64_t bucketStartTimeNs = NS_PER_SEC * 10; // 10 secs
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
    StatsLogProcessor processor(m, pullerManager, anomalyAlarmMonitor, subscriberAlarmMonitor,
            bucketStartTimeNs, [](const ConfigKey& key) { return true; },
            [&uid, &broadcastCount, &activeConfigsBroadcast](const int& broadcastUid,
                    const vector<int64_t>& activeConfigs) {
                broadcastCount++;
                EXPECT_EQ(broadcastUid, uid);
                activeConfigsBroadcast.clear();
                activeConfigsBroadcast.insert(activeConfigsBroadcast.end(),
                        activeConfigs.begin(), activeConfigs.end());
                return true;
            });

    processor.OnConfigUpdated(bucketStartTimeNs, cfgKey, config);

    EXPECT_EQ(processor.mMetricsManagers.size(), 1u);
    sp<MetricsManager> metricsManager = processor.mMetricsManagers.begin()->second;
    EXPECT_TRUE(metricsManager->isConfigValid());
    EXPECT_EQ(metricsManager->mAllMetricProducers.size(), 2);
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
    EXPECT_EQ(eventActivationMap.size(), 2u);
    EXPECT_TRUE(eventActivationMap.find(0) != eventActivationMap.end());
    EXPECT_TRUE(eventActivationMap.find(2) != eventActivationMap.end());
    EXPECT_EQ(eventActivationMap[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0]->start_ns, 0);
    EXPECT_EQ(eventActivationMap[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, 0);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap.size(), 2u);
    EXPECT_TRUE(eventDeactivationMap.find(3) != eventDeactivationMap.end());
    EXPECT_TRUE(eventDeactivationMap.find(4) != eventDeactivationMap.end());
    EXPECT_EQ(eventDeactivationMap[3].size(), 1u);
    EXPECT_EQ(eventDeactivationMap[4].size(), 1u);
    EXPECT_EQ(eventDeactivationMap[3][0], eventActivationMap[0]);
    EXPECT_EQ(eventDeactivationMap[4][0], eventActivationMap[2]);

    EXPECT_EQ(eventActivationMap2.size(), 2u);
    EXPECT_TRUE(eventActivationMap2.find(0) != eventActivationMap2.end());
    EXPECT_TRUE(eventActivationMap2.find(2) != eventActivationMap2.end());
    EXPECT_EQ(eventActivationMap2[0]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap2[0]->start_ns, 0);
    EXPECT_EQ(eventActivationMap2[0]->ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap2[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap2[2]->start_ns, 0);
    EXPECT_EQ(eventActivationMap2[2]->ttl_ns, 60 * 2 * NS_PER_SEC);
    EXPECT_EQ(eventDeactivationMap2.size(), 2u);
    EXPECT_TRUE(eventDeactivationMap2.find(3) != eventDeactivationMap2.end());
    EXPECT_TRUE(eventDeactivationMap2.find(4) != eventDeactivationMap2.end());
    EXPECT_EQ(eventDeactivationMap[3].size(), 1u);
    EXPECT_EQ(eventDeactivationMap[4].size(), 1u);
    EXPECT_EQ(eventDeactivationMap2[3][0], eventActivationMap2[0]);
    EXPECT_EQ(eventDeactivationMap2[4][0], eventActivationMap2[2]);

    std::unique_ptr<LogEvent> event;

    event = CreateAppCrashEvent(111, bucketStartTimeNs + 5);
    processor.OnLogEvent(event.get());
    event = CreateMoveToForegroundEvent(1111, bucketStartTimeNs + 5);
    processor.OnLogEvent(event.get());
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    EXPECT_FALSE(metricProducer2->mIsActive);
    EXPECT_EQ(broadcastCount, 0);

    // Activated by battery save mode.
    event = CreateBatterySaverOnEvent(bucketStartTimeNs + 10);
    processor.OnLogEvent(event.get());
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_EQ(broadcastCount, 1);
    EXPECT_EQ(activeConfigsBroadcast.size(), 1);
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
    event = CreateAppCrashEvent(222, bucketStartTimeNs + 15);
    processor.OnLogEvent(event.get());
    event = CreateMoveToForegroundEvent(2222, bucketStartTimeNs + 15);
    processor.OnLogEvent(event.get());

    // Activated by screen on event.
    event = CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_ON,
                                          bucketStartTimeNs + 20);
    processor.OnLogEvent(event.get());
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
    event = CreateAppCrashEvent(333, bucketStartTimeNs + NS_PER_SEC * 60 * 2 + 25);
    processor.OnLogEvent(event.get());
    event = CreateMoveToForegroundEvent(3333, bucketStartTimeNs + NS_PER_SEC * 60 * 2 + 25);
    processor.OnLogEvent(event.get());
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
    event = CreateAppCrashEvent(444, bucketStartTimeNs + NS_PER_SEC * 60 * 5 + 25);
    processor.OnLogEvent(event.get());
    event = CreateMoveToForegroundEvent(4444, bucketStartTimeNs + NS_PER_SEC * 60 * 5 + 25);
    processor.OnLogEvent(event.get());

    // All activations expired.
    event = CreateAppCrashEvent(555, bucketStartTimeNs + NS_PER_SEC * 60 * 8);
    processor.OnLogEvent(event.get());
    event = CreateMoveToForegroundEvent(5555, bucketStartTimeNs + NS_PER_SEC * 60 * 8);
    processor.OnLogEvent(event.get());
    EXPECT_FALSE(metricsManager->isActive());
    // New broadcast since the config is no longer active.
    EXPECT_EQ(broadcastCount, 2);
    EXPECT_EQ(activeConfigsBroadcast.size(), 0);
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
    event = CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_ON,
                                          bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    processor.OnLogEvent(event.get());
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_EQ(broadcastCount, 3);
    EXPECT_EQ(activeConfigsBroadcast.size(), 1);
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
    event = CreateAppCrashEvent(666, bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 1);
    processor.OnLogEvent(event.get());
    event = CreateMoveToForegroundEvent(6666, bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 1);
    processor.OnLogEvent(event.get());

    // Re-enable battery saver mode activation.
    event = CreateBatterySaverOnEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 15);
    processor.OnLogEvent(event.get());
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_EQ(broadcastCount, 3);
    EXPECT_EQ(activeConfigsBroadcast.size(), 1);
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
    event = CreateAppCrashEvent(777, bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 40);
    processor.OnLogEvent(event.get());
    event = CreateMoveToForegroundEvent(7777, bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 40);
    processor.OnLogEvent(event.get());

    // Cancel battery saver mode and screen on activation.
    event = CreateScreenBrightnessChangedEvent(64, bucketStartTimeNs + NS_PER_SEC * 60 * 11 + 60);
    processor.OnLogEvent(event.get());
    EXPECT_FALSE(metricsManager->isActive());
    // New broadcast since the config is no longer active.
    EXPECT_EQ(broadcastCount, 4);
    EXPECT_EQ(activeConfigsBroadcast.size(), 0);
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
    event = CreateAppCrashEvent(888, bucketStartTimeNs + NS_PER_SEC * 60 * 13);
    processor.OnLogEvent(event.get());
    event = CreateMoveToForegroundEvent(8888, bucketStartTimeNs + NS_PER_SEC * 60 * 13);
    processor.OnLogEvent(event.get());
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_EQ(broadcastCount, 4);
    EXPECT_EQ(activeConfigsBroadcast.size(), 0);
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

    event = CreateAppCrashEvent(999, bucketStartTimeNs + NS_PER_SEC * 60 * 14 + 1);
    processor.OnLogEvent(event.get());
    event = CreateMoveToForegroundEvent(9999, bucketStartTimeNs + NS_PER_SEC * 60 * 14 + 1);
    processor.OnLogEvent(event.get());

    // Re-enable battery saver mode activation.
    event = CreateBatterySaverOnEvent(bucketStartTimeNs + NS_PER_SEC * 60 * 15 + 15);
    processor.OnLogEvent(event.get());
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_EQ(broadcastCount, 5);
    EXPECT_EQ(activeConfigsBroadcast.size(), 1);
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
    event = CreateScreenBrightnessChangedEvent(140, bucketStartTimeNs + NS_PER_SEC * 60 * 16);
    processor.OnLogEvent(event.get());
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_EQ(broadcastCount, 6);
    EXPECT_EQ(activeConfigsBroadcast.size(), 0);
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
    EXPECT_EQ(1, reports.reports_size());
    EXPECT_EQ(2, reports.reports(0).metrics_size());
    EXPECT_EQ(5, reports.reports(0).metrics(0).count_metrics().data_size());
    EXPECT_EQ(5, reports.reports(0).metrics(1).count_metrics().data_size());

    StatsLogReport::CountMetricDataWrapper countMetrics;

    sortMetricDataByDimensionsValue(
            reports.reports(0).metrics(0).count_metrics(), &countMetrics);
    EXPECT_EQ(5, countMetrics.data_size());

    auto data = countMetrics.data(0);
    EXPECT_EQ(android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(222, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    EXPECT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(1);
    EXPECT_EQ(android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(333, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    EXPECT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(2);
    EXPECT_EQ(android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(444, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    EXPECT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    // Partial bucket as metric is deactivated.
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + NS_PER_SEC * 60 * 8,
              data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(3);
    EXPECT_EQ(android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(666, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    EXPECT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs,
              data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + NS_PER_SEC * 60 * 11,
              data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(4);
    EXPECT_EQ(android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(777, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    EXPECT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs,
              data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + NS_PER_SEC * 60 * 11,
              data.bucket_info(0).end_bucket_elapsed_nanos());


   countMetrics.clear_data();
    sortMetricDataByDimensionsValue(
            reports.reports(0).metrics(1).count_metrics(), &countMetrics);
    EXPECT_EQ(5, countMetrics.data_size());

    data = countMetrics.data(0);
    EXPECT_EQ(android::util::ACTIVITY_FOREGROUND_STATE_CHANGED, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(2222, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    EXPECT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(1);
    EXPECT_EQ(android::util::ACTIVITY_FOREGROUND_STATE_CHANGED, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(3333, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    EXPECT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(2);
    EXPECT_EQ(android::util::ACTIVITY_FOREGROUND_STATE_CHANGED, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(4444, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    EXPECT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    // Partial bucket as metric is deactivated.
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + NS_PER_SEC * 60 * 8,
              data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(3);
    EXPECT_EQ(android::util::ACTIVITY_FOREGROUND_STATE_CHANGED, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(6666, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    EXPECT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs,
              data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + NS_PER_SEC * 60 * 11,
              data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(4);
    EXPECT_EQ(android::util::ACTIVITY_FOREGROUND_STATE_CHANGED, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* uid field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_EQ(7777, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
    EXPECT_EQ(1, data.bucket_info_size());
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
