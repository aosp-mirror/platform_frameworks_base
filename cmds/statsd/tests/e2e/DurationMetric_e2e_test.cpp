// Copyright (C) 2019 The Android Open Source Project
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

TEST(DurationMetricE2eTest, TestOneBucket) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");  // LogEvent defaults to UID of root.

    auto screenOnMatcher = CreateScreenTurnedOnAtomMatcher();
    auto screenOffMatcher = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = screenOnMatcher;
    *config.add_atom_matcher() = screenOffMatcher;

    auto durationPredicate = CreateScreenIsOnPredicate();
    *config.add_predicate() = durationPredicate;

    int64_t metricId = 123456;
    auto durationMetric = config.add_duration_metric();
    durationMetric->set_id(metricId);
    durationMetric->set_what(durationPredicate.id());
    durationMetric->set_bucket(FIVE_MINUTES);
    durationMetric->set_aggregation_type(DurationMetric_AggregationType_SUM);

    const int64_t baseTimeNs = 0;                                   // 0:00
    const int64_t configAddedTimeNs = baseTimeNs + 1 * NS_PER_SEC;  // 0:01
    const int64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.duration_metric(0).bucket()) * 1000LL * 1000LL;

    int uid = 12345;
    int64_t cfgId = 98765;
    ConfigKey cfgKey(uid, cfgId);

    auto processor = CreateStatsLogProcessor(baseTimeNs, configAddedTimeNs, config, cfgKey);

    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    sp<MetricsManager> metricsManager = processor->mMetricsManagers.begin()->second;
    EXPECT_TRUE(metricsManager->isConfigValid());
    EXPECT_EQ(metricsManager->mAllMetricProducers.size(), 1);
    sp<MetricProducer> metricProducer = metricsManager->mAllMetricProducers[0];
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);

    std::unique_ptr<LogEvent> event;

    // Screen is off at start of bucket.
    event = CreateScreenStateChangedEvent(configAddedTimeNs,
                                          android::view::DISPLAY_STATE_OFF);  // 0:01
    processor->OnLogEvent(event.get());

    // Turn screen on.
    const int64_t durationStartNs = configAddedTimeNs + 10 * NS_PER_SEC;  // 0:11
    event = CreateScreenStateChangedEvent(durationStartNs, android::view::DISPLAY_STATE_ON);
    processor->OnLogEvent(event.get());

    // Turn off screen 30 seconds after turning on.
    const int64_t durationEndNs = durationStartNs + 30 * NS_PER_SEC;  // 0:41
    event = CreateScreenStateChangedEvent(durationEndNs, android::view::DISPLAY_STATE_OFF);
    processor->OnLogEvent(event.get());

    event = CreateScreenBrightnessChangedEvent(durationEndNs + 1 * NS_PER_SEC, 64);  // 0:42
    processor->OnLogEvent(event.get());

    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(cfgKey, configAddedTimeNs + bucketSizeNs + 1 * NS_PER_SEC, false, true,
                            ADB_DUMP, FAST, &buffer);  // 5:01
    EXPECT_TRUE(buffer.size() > 0);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStartEndTimestamp(&reports);
    EXPECT_EQ(1, reports.reports_size());
    EXPECT_EQ(1, reports.reports(0).metrics_size());
    EXPECT_EQ(metricId, reports.reports(0).metrics(0).metric_id());
    EXPECT_TRUE(reports.reports(0).metrics(0).has_duration_metrics());

    const StatsLogReport::DurationMetricDataWrapper& durationMetrics =
            reports.reports(0).metrics(0).duration_metrics();
    EXPECT_EQ(1, durationMetrics.data_size());

    auto data = durationMetrics.data(0);
    EXPECT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(durationEndNs - durationStartNs, data.bucket_info(0).duration_nanos());
    EXPECT_EQ(configAddedTimeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());
}

TEST(DurationMetricE2eTest, TestTwoBuckets) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");  // LogEvent defaults to UID of root.

    auto screenOnMatcher = CreateScreenTurnedOnAtomMatcher();
    auto screenOffMatcher = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = screenOnMatcher;
    *config.add_atom_matcher() = screenOffMatcher;

    auto durationPredicate = CreateScreenIsOnPredicate();
    *config.add_predicate() = durationPredicate;

    int64_t metricId = 123456;
    auto durationMetric = config.add_duration_metric();
    durationMetric->set_id(metricId);
    durationMetric->set_what(durationPredicate.id());
    durationMetric->set_bucket(FIVE_MINUTES);
    durationMetric->set_aggregation_type(DurationMetric_AggregationType_SUM);

    const int64_t baseTimeNs = 0;                                   // 0:00
    const int64_t configAddedTimeNs = baseTimeNs + 1 * NS_PER_SEC;  // 0:01
    const int64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.duration_metric(0).bucket()) * 1000LL * 1000LL;

    int uid = 12345;
    int64_t cfgId = 98765;
    ConfigKey cfgKey(uid, cfgId);

    auto processor = CreateStatsLogProcessor(baseTimeNs, configAddedTimeNs, config, cfgKey);

    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    sp<MetricsManager> metricsManager = processor->mMetricsManagers.begin()->second;
    EXPECT_TRUE(metricsManager->isConfigValid());
    EXPECT_EQ(metricsManager->mAllMetricProducers.size(), 1);
    sp<MetricProducer> metricProducer = metricsManager->mAllMetricProducers[0];
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);

    std::unique_ptr<LogEvent> event;

    // Screen is off at start of bucket.
    event = CreateScreenStateChangedEvent(configAddedTimeNs,
                                          android::view::DISPLAY_STATE_OFF);  // 0:01
    processor->OnLogEvent(event.get());

    // Turn screen on.
    const int64_t durationStartNs = configAddedTimeNs + 10 * NS_PER_SEC;  // 0:11
    event = CreateScreenStateChangedEvent(durationStartNs, android::view::DISPLAY_STATE_ON);
    processor->OnLogEvent(event.get());

    // Turn off screen 30 seconds after turning on.
    const int64_t durationEndNs = durationStartNs + 30 * NS_PER_SEC;  // 0:41
    event = CreateScreenStateChangedEvent(durationEndNs, android::view::DISPLAY_STATE_OFF);
    processor->OnLogEvent(event.get());

    event = CreateScreenBrightnessChangedEvent(durationEndNs + 1 * NS_PER_SEC, 64);  // 0:42
    processor->OnLogEvent(event.get());

    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(cfgKey, configAddedTimeNs + 2 * bucketSizeNs + 1 * NS_PER_SEC, false,
                            true, ADB_DUMP, FAST, &buffer);  // 10:01
    EXPECT_TRUE(buffer.size() > 0);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStartEndTimestamp(&reports);
    EXPECT_EQ(1, reports.reports_size());
    EXPECT_EQ(1, reports.reports(0).metrics_size());
    EXPECT_EQ(metricId, reports.reports(0).metrics(0).metric_id());
    EXPECT_TRUE(reports.reports(0).metrics(0).has_duration_metrics());

    const StatsLogReport::DurationMetricDataWrapper& durationMetrics =
            reports.reports(0).metrics(0).duration_metrics();
    EXPECT_EQ(1, durationMetrics.data_size());

    auto data = durationMetrics.data(0);
    EXPECT_EQ(1, data.bucket_info_size());

    auto bucketInfo = data.bucket_info(0);
    EXPECT_EQ(0, bucketInfo.bucket_num());
    EXPECT_EQ(durationEndNs - durationStartNs, bucketInfo.duration_nanos());
    EXPECT_EQ(configAddedTimeNs, bucketInfo.start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + bucketSizeNs, bucketInfo.end_bucket_elapsed_nanos());
}

TEST(DurationMetricE2eTest, TestWithActivation) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");  // LogEvent defaults to UID of root.

    auto screenOnMatcher = CreateScreenTurnedOnAtomMatcher();
    auto screenOffMatcher = CreateScreenTurnedOffAtomMatcher();
    auto crashMatcher = CreateProcessCrashAtomMatcher();
    *config.add_atom_matcher() = screenOnMatcher;
    *config.add_atom_matcher() = screenOffMatcher;
    *config.add_atom_matcher() = crashMatcher;

    auto durationPredicate = CreateScreenIsOnPredicate();
    *config.add_predicate() = durationPredicate;

    int64_t metricId = 123456;
    auto durationMetric = config.add_duration_metric();
    durationMetric->set_id(metricId);
    durationMetric->set_what(durationPredicate.id());
    durationMetric->set_bucket(FIVE_MINUTES);
    durationMetric->set_aggregation_type(DurationMetric_AggregationType_SUM);

    auto metric_activation1 = config.add_metric_activation();
    metric_activation1->set_metric_id(metricId);
    auto event_activation1 = metric_activation1->add_event_activation();
    event_activation1->set_atom_matcher_id(crashMatcher.id());
    event_activation1->set_ttl_seconds(30);  // 30 secs.

    const int64_t bucketStartTimeNs = 10000000000;
    const int64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.duration_metric(0).bucket()) * 1000LL * 1000LL;

    int uid = 12345;
    int64_t cfgId = 98765;
    ConfigKey cfgKey(uid, cfgId);

    sp<UidMap> m = new UidMap();
    sp<StatsPullerManager> pullerManager = new StatsPullerManager();
    sp<AlarmMonitor> anomalyAlarmMonitor;
    sp<AlarmMonitor> subscriberAlarmMonitor;
    vector<int64_t> activeConfigsBroadcast;

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

    processor.OnConfigUpdated(bucketStartTimeNs, cfgKey, config);  // 0:00

    EXPECT_EQ(processor.mMetricsManagers.size(), 1u);
    sp<MetricsManager> metricsManager = processor.mMetricsManagers.begin()->second;
    EXPECT_TRUE(metricsManager->isConfigValid());
    EXPECT_EQ(metricsManager->mAllMetricProducers.size(), 1);
    sp<MetricProducer> metricProducer = metricsManager->mAllMetricProducers[0];
    auto& eventActivationMap = metricProducer->mEventActivationMap;

    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    EXPECT_EQ(eventActivationMap.size(), 1u);
    EXPECT_TRUE(eventActivationMap.find(2) != eventActivationMap.end());
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, 0);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, event_activation1->ttl_seconds() * NS_PER_SEC);

    std::unique_ptr<LogEvent> event;

    // Turn screen off.
    event = CreateScreenStateChangedEvent(bucketStartTimeNs + 2 * NS_PER_SEC,
                                          android::view::DISPLAY_STATE_OFF);  // 0:02
    processor.OnLogEvent(event.get(), bucketStartTimeNs + 2 * NS_PER_SEC);

    // Turn screen on.
    const int64_t durationStartNs = bucketStartTimeNs + 5 * NS_PER_SEC;  // 0:05
    event = CreateScreenStateChangedEvent(durationStartNs, android::view::DISPLAY_STATE_ON);
    processor.OnLogEvent(event.get(), durationStartNs);

    // Activate metric.
    const int64_t activationStartNs = bucketStartTimeNs + 5 * NS_PER_SEC;  // 0:10
    const int64_t activationEndNs =
            activationStartNs + event_activation1->ttl_seconds() * NS_PER_SEC;  // 0:40
    event = CreateAppCrashEvent(activationStartNs, 111);
    processor.OnLogEvent(event.get(), activationStartNs);
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 1);
    EXPECT_EQ(activeConfigsBroadcast.size(), 1);
    EXPECT_EQ(activeConfigsBroadcast[0], cfgId);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, activationStartNs);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, event_activation1->ttl_seconds() * NS_PER_SEC);

    // Expire activation.
    const int64_t expirationNs = activationEndNs + 7 * NS_PER_SEC;
    event = CreateScreenBrightnessChangedEvent(expirationNs, 64);  // 0:47
    processor.OnLogEvent(event.get(), expirationNs);
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 2);
    EXPECT_EQ(activeConfigsBroadcast.size(), 0);
    EXPECT_EQ(eventActivationMap.size(), 1u);
    EXPECT_TRUE(eventActivationMap.find(2) != eventActivationMap.end());
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, activationStartNs);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, event_activation1->ttl_seconds() * NS_PER_SEC);

    // Turn off screen 10 seconds after activation expiration.
    const int64_t durationEndNs = activationEndNs + 10 * NS_PER_SEC;  // 0:50
    event = CreateScreenStateChangedEvent(durationEndNs, android::view::DISPLAY_STATE_OFF);
    processor.OnLogEvent(event.get(), durationEndNs);

    // Turn screen on.
    const int64_t duration2StartNs = durationEndNs + 5 * NS_PER_SEC;  // 0:55
    event = CreateScreenStateChangedEvent(duration2StartNs, android::view::DISPLAY_STATE_ON);
    processor.OnLogEvent(event.get(), duration2StartNs);

    // Turn off screen.
    const int64_t duration2EndNs = duration2StartNs + 10 * NS_PER_SEC;  // 1:05
    event = CreateScreenStateChangedEvent(duration2EndNs, android::view::DISPLAY_STATE_OFF);
    processor.OnLogEvent(event.get(), duration2EndNs);

    // Activate metric.
    const int64_t activation2StartNs = duration2EndNs + 5 * NS_PER_SEC;  // 1:10
    const int64_t activation2EndNs =
            activation2StartNs + event_activation1->ttl_seconds() * NS_PER_SEC;  // 1:40
    event = CreateAppCrashEvent(activation2StartNs, 211);
    processor.OnLogEvent(event.get(), activation2StartNs);
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(broadcastCount, 3);
    EXPECT_EQ(activeConfigsBroadcast.size(), 1);
    EXPECT_EQ(activeConfigsBroadcast[0], cfgId);
    EXPECT_EQ(eventActivationMap[2]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[2]->start_ns, activation2StartNs);
    EXPECT_EQ(eventActivationMap[2]->ttl_ns, event_activation1->ttl_seconds() * NS_PER_SEC);

    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor.onDumpReport(cfgKey, bucketStartTimeNs + bucketSizeNs + 1 * NS_PER_SEC, false, true,
                           ADB_DUMP, FAST, &buffer);  // 5:01
    EXPECT_TRUE(buffer.size() > 0);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStartEndTimestamp(&reports);
    EXPECT_EQ(1, reports.reports_size());
    EXPECT_EQ(1, reports.reports(0).metrics_size());
    EXPECT_EQ(metricId, reports.reports(0).metrics(0).metric_id());
    EXPECT_TRUE(reports.reports(0).metrics(0).has_duration_metrics());

    const StatsLogReport::DurationMetricDataWrapper& durationMetrics =
            reports.reports(0).metrics(0).duration_metrics();
    EXPECT_EQ(1, durationMetrics.data_size());

    auto data = durationMetrics.data(0);
    EXPECT_EQ(1, data.bucket_info_size());

    auto bucketInfo = data.bucket_info(0);
    EXPECT_EQ(0, bucketInfo.bucket_num());
    EXPECT_EQ(bucketStartTimeNs, bucketInfo.start_bucket_elapsed_nanos());
    EXPECT_EQ(expirationNs, bucketInfo.end_bucket_elapsed_nanos());
    EXPECT_EQ(expirationNs - durationStartNs, bucketInfo.duration_nanos());
}

TEST(DurationMetricE2eTest, TestWithCondition) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");  // LogEvent defaults to UID of root.
    *config.add_atom_matcher() = CreateAcquireWakelockAtomMatcher();
    *config.add_atom_matcher() = CreateReleaseWakelockAtomMatcher();
    *config.add_atom_matcher() = CreateMoveToBackgroundAtomMatcher();
    *config.add_atom_matcher() = CreateMoveToForegroundAtomMatcher();

    auto holdingWakelockPredicate = CreateHoldingWakelockPredicate();
    *config.add_predicate() = holdingWakelockPredicate;

    auto isInBackgroundPredicate = CreateIsInBackgroundPredicate();
    *config.add_predicate() = isInBackgroundPredicate;

    auto durationMetric = config.add_duration_metric();
    durationMetric->set_id(StringToId("WakelockDuration"));
    durationMetric->set_what(holdingWakelockPredicate.id());
    durationMetric->set_condition(isInBackgroundPredicate.id());
    durationMetric->set_aggregation_type(DurationMetric::SUM);
    durationMetric->set_bucket(FIVE_MINUTES);

    ConfigKey cfgKey;
    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.duration_metric(0).bucket()) * 1000000LL;
    auto processor = CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);
    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    sp<MetricsManager> metricsManager = processor->mMetricsManagers.begin()->second;
    EXPECT_TRUE(metricsManager->isConfigValid());
    EXPECT_EQ(metricsManager->mAllMetricProducers.size(), 1);
    sp<MetricProducer> metricProducer = metricsManager->mAllMetricProducers[0];
    auto& eventActivationMap = metricProducer->mEventActivationMap;
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_TRUE(eventActivationMap.empty());

    int appUid = 123;
    vector<int> attributionUids1 = {appUid};
    vector<string> attributionTags1 = {"App1"};

    auto event = CreateAcquireWakelockEvent(bucketStartTimeNs + 10 * NS_PER_SEC, attributionUids1,
                                            attributionTags1,
                                            "wl1");  // 0:10
    processor->OnLogEvent(event.get());

    event = CreateMoveToBackgroundEvent(bucketStartTimeNs + 22 * NS_PER_SEC, appUid);  // 0:22
    processor->OnLogEvent(event.get());

    event = CreateMoveToForegroundEvent(bucketStartTimeNs + (3 * 60 + 15) * NS_PER_SEC,
                                        appUid);  // 3:15
    processor->OnLogEvent(event.get());

    event = CreateReleaseWakelockEvent(bucketStartTimeNs + 4 * 60 * NS_PER_SEC, attributionUids1,
                                       attributionTags1,
                                       "wl1");  // 4:00
    processor->OnLogEvent(event.get());

    vector<uint8_t> buffer;
    ConfigMetricsReportList reports;
    processor->onDumpReport(cfgKey, bucketStartTimeNs + bucketSizeNs + 1, false, true, ADB_DUMP,
                            FAST, &buffer);
    EXPECT_GT(buffer.size(), 0);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);

    EXPECT_EQ(1, reports.reports_size());
    EXPECT_EQ(1, reports.reports(0).metrics_size());
    EXPECT_EQ(1, reports.reports(0).metrics(0).duration_metrics().data_size());

    auto data = reports.reports(0).metrics(0).duration_metrics().data(0);

    // Validate bucket info.
    EXPECT_EQ(1, data.bucket_info_size());

    auto bucketInfo = data.bucket_info(0);
    EXPECT_EQ(bucketStartTimeNs, bucketInfo.start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, bucketInfo.end_bucket_elapsed_nanos());
    EXPECT_EQ((2 * 60 + 53) * NS_PER_SEC, bucketInfo.duration_nanos());
}

TEST(DurationMetricE2eTest, TestWithSlicedCondition) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");  // LogEvent defaults to UID of root.
    auto screenOnMatcher = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = CreateAcquireWakelockAtomMatcher();
    *config.add_atom_matcher() = CreateReleaseWakelockAtomMatcher();
    *config.add_atom_matcher() = CreateMoveToBackgroundAtomMatcher();
    *config.add_atom_matcher() = CreateMoveToForegroundAtomMatcher();

    auto holdingWakelockPredicate = CreateHoldingWakelockPredicate();
    // The predicate is dimensioning by first attribution node by uid.
    FieldMatcher dimensions = CreateAttributionUidDimensions(android::util::WAKELOCK_STATE_CHANGED,
                                                             {Position::FIRST});
    *holdingWakelockPredicate.mutable_simple_predicate()->mutable_dimensions() = dimensions;
    *config.add_predicate() = holdingWakelockPredicate;

    auto isInBackgroundPredicate = CreateIsInBackgroundPredicate();
    *isInBackgroundPredicate.mutable_simple_predicate()->mutable_dimensions() =
            CreateDimensions(android::util::ACTIVITY_FOREGROUND_STATE_CHANGED, {Position::FIRST});
    *config.add_predicate() = isInBackgroundPredicate;

    auto durationMetric = config.add_duration_metric();
    durationMetric->set_id(StringToId("WakelockDuration"));
    durationMetric->set_what(holdingWakelockPredicate.id());
    durationMetric->set_condition(isInBackgroundPredicate.id());
    durationMetric->set_aggregation_type(DurationMetric::SUM);
    // The metric is dimensioning by first attribution node and only by uid.
    *durationMetric->mutable_dimensions_in_what() = CreateAttributionUidDimensions(
            android::util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    durationMetric->set_bucket(FIVE_MINUTES);

    // Links between wakelock state atom and condition of app is in background.
    auto links = durationMetric->add_links();
    links->set_condition(isInBackgroundPredicate.id());
    auto dimensionWhat = links->mutable_fields_in_what();
    dimensionWhat->set_field(android::util::WAKELOCK_STATE_CHANGED);
    dimensionWhat->add_child()->set_field(1);  // uid field.
    *links->mutable_fields_in_condition() = CreateAttributionUidDimensions(
            android::util::ACTIVITY_FOREGROUND_STATE_CHANGED, {Position::FIRST});

    ConfigKey cfgKey;
    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.duration_metric(0).bucket()) * 1000000LL;
    auto processor = CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);
    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    sp<MetricsManager> metricsManager = processor->mMetricsManagers.begin()->second;
    EXPECT_TRUE(metricsManager->isConfigValid());
    EXPECT_EQ(metricsManager->mAllMetricProducers.size(), 1);
    sp<MetricProducer> metricProducer = metricsManager->mAllMetricProducers[0];
    auto& eventActivationMap = metricProducer->mEventActivationMap;
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_TRUE(eventActivationMap.empty());

    int appUid = 123;
    std::vector<int> attributionUids1 = {appUid};
    std::vector<string> attributionTags1 = {"App1"};

    auto event = CreateAcquireWakelockEvent(bucketStartTimeNs + 10 * NS_PER_SEC, attributionUids1,
                                            attributionTags1, "wl1");  // 0:10
    processor->OnLogEvent(event.get());

    event = CreateMoveToBackgroundEvent(bucketStartTimeNs + 22 * NS_PER_SEC, appUid);  // 0:22
    processor->OnLogEvent(event.get());

    event = CreateReleaseWakelockEvent(bucketStartTimeNs + 60 * NS_PER_SEC, attributionUids1,
                                       attributionTags1, "wl1");  // 1:00
    processor->OnLogEvent(event.get());

    event = CreateMoveToForegroundEvent(bucketStartTimeNs + (3 * 60 + 15) * NS_PER_SEC,
                                        appUid);  // 3:15
    processor->OnLogEvent(event.get());

    vector<uint8_t> buffer;
    ConfigMetricsReportList reports;
    processor->onDumpReport(cfgKey, bucketStartTimeNs + bucketSizeNs + 1, false, true, ADB_DUMP,
                            FAST, &buffer);
    EXPECT_GT(buffer.size(), 0);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);

    EXPECT_EQ(1, reports.reports_size());
    EXPECT_EQ(1, reports.reports(0).metrics_size());
    EXPECT_EQ(1, reports.reports(0).metrics(0).duration_metrics().data_size());

    auto data = reports.reports(0).metrics(0).duration_metrics().data(0);
    // Validate dimension value.
    ValidateAttributionUidDimension(data.dimensions_in_what(),
                                    android::util::WAKELOCK_STATE_CHANGED, appUid);
    // Validate bucket info.
    EXPECT_EQ(1, data.bucket_info_size());

    auto bucketInfo = data.bucket_info(0);
    EXPECT_EQ(bucketStartTimeNs, bucketInfo.start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, bucketInfo.end_bucket_elapsed_nanos());
    EXPECT_EQ(38 * NS_PER_SEC, bucketInfo.duration_nanos());
}

TEST(DurationMetricE2eTest, TestWithActivationAndSlicedCondition) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");  // LogEvent defaults to UID of root.
    auto screenOnMatcher = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = CreateAcquireWakelockAtomMatcher();
    *config.add_atom_matcher() = CreateReleaseWakelockAtomMatcher();
    *config.add_atom_matcher() = CreateMoveToBackgroundAtomMatcher();
    *config.add_atom_matcher() = CreateMoveToForegroundAtomMatcher();
    *config.add_atom_matcher() = screenOnMatcher;

    auto holdingWakelockPredicate = CreateHoldingWakelockPredicate();
    // The predicate is dimensioning by first attribution node by uid.
    FieldMatcher dimensions = CreateAttributionUidDimensions(android::util::WAKELOCK_STATE_CHANGED,
                                                             {Position::FIRST});
    *holdingWakelockPredicate.mutable_simple_predicate()->mutable_dimensions() = dimensions;
    *config.add_predicate() = holdingWakelockPredicate;

    auto isInBackgroundPredicate = CreateIsInBackgroundPredicate();
    *isInBackgroundPredicate.mutable_simple_predicate()->mutable_dimensions() =
            CreateDimensions(android::util::ACTIVITY_FOREGROUND_STATE_CHANGED, {Position::FIRST});
    *config.add_predicate() = isInBackgroundPredicate;

    auto durationMetric = config.add_duration_metric();
    durationMetric->set_id(StringToId("WakelockDuration"));
    durationMetric->set_what(holdingWakelockPredicate.id());
    durationMetric->set_condition(isInBackgroundPredicate.id());
    durationMetric->set_aggregation_type(DurationMetric::SUM);
    // The metric is dimensioning by first attribution node and only by uid.
    *durationMetric->mutable_dimensions_in_what() = CreateAttributionUidDimensions(
            android::util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    durationMetric->set_bucket(FIVE_MINUTES);

    // Links between wakelock state atom and condition of app is in background.
    auto links = durationMetric->add_links();
    links->set_condition(isInBackgroundPredicate.id());
    auto dimensionWhat = links->mutable_fields_in_what();
    dimensionWhat->set_field(android::util::WAKELOCK_STATE_CHANGED);
    dimensionWhat->add_child()->set_field(1);  // uid field.
    *links->mutable_fields_in_condition() = CreateAttributionUidDimensions(
            android::util::ACTIVITY_FOREGROUND_STATE_CHANGED, {Position::FIRST});

    auto metric_activation1 = config.add_metric_activation();
    metric_activation1->set_metric_id(durationMetric->id());
    auto event_activation1 = metric_activation1->add_event_activation();
    event_activation1->set_atom_matcher_id(screenOnMatcher.id());
    event_activation1->set_ttl_seconds(60 * 2);  // 2 minutes.

    ConfigKey cfgKey;
    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.duration_metric(0).bucket()) * 1000000LL;
    auto processor = CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);
    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    sp<MetricsManager> metricsManager = processor->mMetricsManagers.begin()->second;
    EXPECT_TRUE(metricsManager->isConfigValid());
    EXPECT_EQ(metricsManager->mAllMetricProducers.size(), 1);
    sp<MetricProducer> metricProducer = metricsManager->mAllMetricProducers[0];
    auto& eventActivationMap = metricProducer->mEventActivationMap;
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    EXPECT_EQ(eventActivationMap.size(), 1u);
    EXPECT_TRUE(eventActivationMap.find(4) != eventActivationMap.end());
    EXPECT_EQ(eventActivationMap[4]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[4]->start_ns, 0);
    EXPECT_EQ(eventActivationMap[4]->ttl_ns, event_activation1->ttl_seconds() * NS_PER_SEC);

    int appUid = 123;
    std::vector<int> attributionUids1 = {appUid};
    std::vector<string> attributionTags1 = {"App1"};

    auto event = CreateAcquireWakelockEvent(bucketStartTimeNs + 10 * NS_PER_SEC, attributionUids1,
                                            attributionTags1, "wl1");  // 0:10
    processor->OnLogEvent(event.get());
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    EXPECT_EQ(eventActivationMap[4]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[4]->start_ns, 0);
    EXPECT_EQ(eventActivationMap[4]->ttl_ns, event_activation1->ttl_seconds() * NS_PER_SEC);

    event = CreateMoveToBackgroundEvent(bucketStartTimeNs + 22 * NS_PER_SEC, appUid);  // 0:22
    processor->OnLogEvent(event.get());
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    EXPECT_EQ(eventActivationMap[4]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[4]->start_ns, 0);
    EXPECT_EQ(eventActivationMap[4]->ttl_ns, event_activation1->ttl_seconds() * NS_PER_SEC);

    const int64_t durationStartNs = bucketStartTimeNs + 30 * NS_PER_SEC;  // 0:30
    event = CreateScreenStateChangedEvent(durationStartNs, android::view::DISPLAY_STATE_ON);
    processor->OnLogEvent(event.get());
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(eventActivationMap[4]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[4]->start_ns, durationStartNs);
    EXPECT_EQ(eventActivationMap[4]->ttl_ns, event_activation1->ttl_seconds() * NS_PER_SEC);

    const int64_t durationEndNs =
            durationStartNs + (event_activation1->ttl_seconds() + 30) * NS_PER_SEC;  // 3:00
    event = CreateAppCrashEvent(durationEndNs, 333);
    processor->OnLogEvent(event.get());
    EXPECT_FALSE(metricsManager->isActive());
    EXPECT_FALSE(metricProducer->mIsActive);
    EXPECT_EQ(eventActivationMap[4]->state, ActivationState::kNotActive);
    EXPECT_EQ(eventActivationMap[4]->start_ns, durationStartNs);
    EXPECT_EQ(eventActivationMap[4]->ttl_ns, event_activation1->ttl_seconds() * NS_PER_SEC);

    event = CreateMoveToForegroundEvent(bucketStartTimeNs + (3 * 60 + 15) * NS_PER_SEC,
                                        appUid);  // 3:15
    processor->OnLogEvent(event.get());

    event = CreateReleaseWakelockEvent(bucketStartTimeNs + (4 * 60 + 17) * NS_PER_SEC,
                                       attributionUids1, attributionTags1, "wl1");  // 4:17
    processor->OnLogEvent(event.get());

    event = CreateMoveToBackgroundEvent(bucketStartTimeNs + (4 * 60 + 20) * NS_PER_SEC,
                                        appUid);  // 4:20
    processor->OnLogEvent(event.get());

    event = CreateAcquireWakelockEvent(bucketStartTimeNs + (4 * 60 + 25) * NS_PER_SEC,
                                       attributionUids1, attributionTags1, "wl1");  // 4:25
    processor->OnLogEvent(event.get());

    const int64_t duration2StartNs = bucketStartTimeNs + (4 * 60 + 30) * NS_PER_SEC;  // 4:30
    event = CreateScreenStateChangedEvent(duration2StartNs, android::view::DISPLAY_STATE_ON);
    processor->OnLogEvent(event.get());
    EXPECT_TRUE(metricsManager->isActive());
    EXPECT_TRUE(metricProducer->mIsActive);
    EXPECT_EQ(eventActivationMap[4]->state, ActivationState::kActive);
    EXPECT_EQ(eventActivationMap[4]->start_ns, duration2StartNs);
    EXPECT_EQ(eventActivationMap[4]->ttl_ns, event_activation1->ttl_seconds() * NS_PER_SEC);

    vector<uint8_t> buffer;
    ConfigMetricsReportList reports;
    processor->onDumpReport(cfgKey, bucketStartTimeNs + bucketSizeNs + 1, false, true, ADB_DUMP,
                            FAST, &buffer);
    EXPECT_GT(buffer.size(), 0);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);

    EXPECT_EQ(1, reports.reports_size());
    EXPECT_EQ(1, reports.reports(0).metrics_size());
    EXPECT_EQ(1, reports.reports(0).metrics(0).duration_metrics().data_size());

    auto data = reports.reports(0).metrics(0).duration_metrics().data(0);
    // Validate dimension value.
    ValidateAttributionUidDimension(data.dimensions_in_what(),
                                    android::util::WAKELOCK_STATE_CHANGED, appUid);
    // Validate bucket info.
    EXPECT_EQ(2, data.bucket_info_size());

    auto bucketInfo = data.bucket_info(0);
    EXPECT_EQ(bucketStartTimeNs, bucketInfo.start_bucket_elapsed_nanos());
    EXPECT_EQ(durationEndNs, bucketInfo.end_bucket_elapsed_nanos());
    EXPECT_EQ(durationEndNs - durationStartNs, bucketInfo.duration_nanos());

    bucketInfo = data.bucket_info(1);
    EXPECT_EQ(durationEndNs, bucketInfo.start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, bucketInfo.end_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs - duration2StartNs, bucketInfo.duration_nanos());
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif

}  // namespace statsd
}  // namespace os
}  // namespace android
