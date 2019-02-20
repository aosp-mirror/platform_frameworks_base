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
    auto crashMatcher = CreateProcessCrashAtomMatcher();
    auto screenOnMatcher = CreateScreenTurnedOnAtomMatcher();
    auto saverModeMatcher = CreateBatterySaverModeStartAtomMatcher();

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

}  // namespace

TEST(MetricActivationE2eTest, TestCountMetric) {
    auto config = CreateStatsdConfig();

    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(config.count_metric(0).bucket()) * 1000000;

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
    EXPECT_EQ(eventActivationMap[0].state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0].activation_ns, 0);
    EXPECT_EQ(eventActivationMap[0].ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2].state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2].activation_ns, 0);
    EXPECT_EQ(eventActivationMap[2].ttl_ns, 60 * 2 * NS_PER_SEC);

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
    EXPECT_EQ(eventActivationMap[0].state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[0].activation_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap[0].ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2].state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2].activation_ns, 0);
    EXPECT_EQ(eventActivationMap[2].ttl_ns, 60 * 2 * NS_PER_SEC);

    // First processed event.
    event = CreateAppCrashEvent(222, bucketStartTimeNs + 15);
    processor.OnLogEvent(event.get());

    // Activated by screen on event.
    event = CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_ON,
                                          bucketStartTimeNs + 20);
    processor.OnLogEvent(event.get());
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(eventActivationMap[0].state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[0].activation_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap[0].ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2].state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[2].activation_ns, bucketStartTimeNs + 20);
    EXPECT_EQ(eventActivationMap[2].ttl_ns, 60 * 2 * NS_PER_SEC);

    // 2nd processed event.
    // The activation by screen_on event expires, but the one by battery save mode is still active.
    event = CreateAppCrashEvent(333, bucketStartTimeNs + NS_PER_SEC * 60 * 2 + 25);
    processor.OnLogEvent(event.get());
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(eventActivationMap[0].state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[0].activation_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap[0].ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2].state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2].activation_ns, bucketStartTimeNs + 20);
    EXPECT_EQ(eventActivationMap[2].ttl_ns, 60 * 2 * NS_PER_SEC);
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
    EXPECT_EQ(eventActivationMap[0].state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0].activation_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap[0].ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2].state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2].activation_ns, bucketStartTimeNs + 20);
    EXPECT_EQ(eventActivationMap[2].ttl_ns, 60 * 2 * NS_PER_SEC);

    // Re-activate.
    event = CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_ON,
                                          bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    processor.OnLogEvent(event.get());
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 3);
    EXPECT_EQ(activeConfigsBroadcast.size(), 1);
    EXPECT_EQ(activeConfigsBroadcast[0], cfgId);
    EXPECT_EQ(eventActivationMap[0].state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[0].activation_ns, bucketStartTimeNs + 10);
    EXPECT_EQ(eventActivationMap[0].ttl_ns, 60 * 6 * NS_PER_SEC);
    EXPECT_EQ(eventActivationMap[2].state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[2].activation_ns, bucketStartTimeNs + NS_PER_SEC * 60 * 10 + 10);
    EXPECT_EQ(eventActivationMap[2].ttl_ns, 60 * 2 * NS_PER_SEC);

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


#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif

}  // namespace statsd
}  // namespace os
}  // namespace android
