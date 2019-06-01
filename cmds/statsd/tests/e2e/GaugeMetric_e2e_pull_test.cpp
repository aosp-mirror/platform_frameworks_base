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

#include "src/StatsLogProcessor.h"
#include "src/stats_log_util.h"
#include "tests/statsd_test_util.h"

#include <vector>

namespace android {
namespace os {
namespace statsd {

#ifdef __ANDROID__

namespace {

const int64_t metricId = 123456;

StatsdConfig CreateStatsdConfig(const GaugeMetric::SamplingType sampling_type,
                                bool useCondition = true) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT"); // LogEvent defaults to UID of root.
    auto atomMatcher = CreateSimpleAtomMatcher("TestMatcher", android::util::SUBSYSTEM_SLEEP_STATE);
    *config.add_atom_matcher() = atomMatcher;
    *config.add_atom_matcher() = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = CreateScreenTurnedOffAtomMatcher();

    auto screenIsOffPredicate = CreateScreenIsOffPredicate();
    *config.add_predicate() = screenIsOffPredicate;

    auto gaugeMetric = config.add_gauge_metric();
    gaugeMetric->set_id(metricId);
    gaugeMetric->set_what(atomMatcher.id());
    if (useCondition) {
        gaugeMetric->set_condition(screenIsOffPredicate.id());
    }
    gaugeMetric->set_sampling_type(sampling_type);
    gaugeMetric->mutable_gauge_fields_filter()->set_include_all(true);
    *gaugeMetric->mutable_dimensions_in_what() =
            CreateDimensions(android::util::SUBSYSTEM_SLEEP_STATE, {1 /* subsystem name */});
    gaugeMetric->set_bucket(FIVE_MINUTES);
    gaugeMetric->set_max_pull_delay_sec(INT_MAX);
    config.set_hash_strings_in_metric_report(false);

    return config;
}

}  // namespace

TEST(GaugeMetricE2eTest, TestRandomSamplePulledEvents) {
    auto config = CreateStatsdConfig(GaugeMetric::RANDOM_ONE_SAMPLE);
    int64_t baseTimeNs = getElapsedRealtimeNs();
    int64_t configAddedTimeNs = 10 * 60 * NS_PER_SEC + baseTimeNs;
    int64_t bucketSizeNs =
        TimeUnitToBucketSizeInMillis(config.gauge_metric(0).bucket()) * 1000000;

    ConfigKey cfgKey;
    auto processor = CreateStatsLogProcessor(
        baseTimeNs, configAddedTimeNs, config, cfgKey);
    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());
    processor->mPullerManager->ForceClearPullerCache();

    int startBucketNum = processor->mMetricsManagers.begin()->second->
            mAllMetricProducers[0]->getCurrentBucketNum();
    EXPECT_GT(startBucketNum, (int64_t)0);

    // When creating the config, the gauge metric producer should register the alarm at the
    // end of the current bucket.
    EXPECT_EQ((size_t)1, processor->mPullerManager->mReceivers.size());
    EXPECT_EQ(bucketSizeNs,
              processor->mPullerManager->mReceivers.begin()->second.front().intervalNs);
    int64_t& nextPullTimeNs =
            processor->mPullerManager->mReceivers.begin()->second.front().nextPullTimeNs;
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + bucketSizeNs, nextPullTimeNs);

    auto screenOffEvent = CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_OFF,
                                                        configAddedTimeNs + 55);
    processor->OnLogEvent(screenOffEvent.get());

    // Pulling alarm arrives on time and reset the sequential pulling alarm.
    processor->informPullAlarmFired(nextPullTimeNs + 1);
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + 2 * bucketSizeNs, nextPullTimeNs);

    auto screenOnEvent = CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_ON,
                                                       configAddedTimeNs + bucketSizeNs + 10);
    processor->OnLogEvent(screenOnEvent.get());

    screenOffEvent = CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_OFF,
                                                   configAddedTimeNs + bucketSizeNs + 100);
    processor->OnLogEvent(screenOffEvent.get());

    processor->informPullAlarmFired(nextPullTimeNs + 1);
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + 3 * bucketSizeNs,
              nextPullTimeNs);

    processor->informPullAlarmFired(nextPullTimeNs + 1);
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + 4 * bucketSizeNs, nextPullTimeNs);

    screenOnEvent = CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_ON,
                                                  configAddedTimeNs + 3 * bucketSizeNs + 2);
    processor->OnLogEvent(screenOnEvent.get());

    processor->informPullAlarmFired(nextPullTimeNs + 3);
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + 5 * bucketSizeNs, nextPullTimeNs);

    screenOffEvent = CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_OFF,
                                                  configAddedTimeNs + 5 * bucketSizeNs + 1);
    processor->OnLogEvent(screenOffEvent.get());

    processor->informPullAlarmFired(nextPullTimeNs + 2);
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + 6 * bucketSizeNs, nextPullTimeNs);

    processor->informPullAlarmFired(nextPullTimeNs + 2);

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
    StatsLogReport::GaugeMetricDataWrapper gaugeMetrics;
    sortMetricDataByDimensionsValue(
            reports.reports(0).metrics(0).gauge_metrics(), &gaugeMetrics);
    EXPECT_GT((int)gaugeMetrics.data_size(), 1);

    auto data = gaugeMetrics.data(0);
    EXPECT_EQ(android::util::SUBSYSTEM_SLEEP_STATE, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* subsystem name field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_FALSE(data.dimensions_in_what().value_tuple().dimensions_value(0).value_str().empty());
    EXPECT_EQ(6, data.bucket_info_size());

    EXPECT_EQ(1, data.bucket_info(0).atom_size());
    EXPECT_EQ(1, data.bucket_info(0).elapsed_timestamp_nanos_size());
    EXPECT_EQ(configAddedTimeNs + 55, data.bucket_info(0).elapsed_timestamp_nanos(0));
    EXPECT_EQ(0, data.bucket_info(0).wall_clock_timestamp_nanos_size());
    EXPECT_EQ(baseTimeNs + 2 * bucketSizeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 3 * bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());
    EXPECT_TRUE(data.bucket_info(0).atom(0).subsystem_sleep_state().subsystem_name().empty());
    EXPECT_GT(data.bucket_info(0).atom(0).subsystem_sleep_state().time_millis(), 0);

    EXPECT_EQ(1, data.bucket_info(1).atom_size());
    EXPECT_EQ(baseTimeNs + 3 * bucketSizeNs + 1,
              data.bucket_info(1).elapsed_timestamp_nanos(0));
    EXPECT_EQ(baseTimeNs + 3 * bucketSizeNs + 1, data.bucket_info(1).elapsed_timestamp_nanos(0));
    EXPECT_EQ(baseTimeNs + 3 * bucketSizeNs, data.bucket_info(1).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 4 * bucketSizeNs, data.bucket_info(1).end_bucket_elapsed_nanos());
    EXPECT_TRUE(data.bucket_info(1).atom(0).subsystem_sleep_state().subsystem_name().empty());
    EXPECT_GT(data.bucket_info(1).atom(0).subsystem_sleep_state().time_millis(), 0);

    EXPECT_EQ(1, data.bucket_info(2).atom_size());
    EXPECT_EQ(1, data.bucket_info(2).elapsed_timestamp_nanos_size());
    EXPECT_EQ(baseTimeNs + 4 * bucketSizeNs + 1,
              data.bucket_info(2).elapsed_timestamp_nanos(0));
    EXPECT_EQ(baseTimeNs + 4 * bucketSizeNs, data.bucket_info(2).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 5 * bucketSizeNs, data.bucket_info(2).end_bucket_elapsed_nanos());
    EXPECT_TRUE(data.bucket_info(2).atom(0).subsystem_sleep_state().subsystem_name().empty());
    EXPECT_GT(data.bucket_info(2).atom(0).subsystem_sleep_state().time_millis(), 0);

    EXPECT_EQ(1, data.bucket_info(3).atom_size());
    EXPECT_EQ(1, data.bucket_info(3).elapsed_timestamp_nanos_size());
    EXPECT_EQ(baseTimeNs + 5 * bucketSizeNs + 1,
              data.bucket_info(3).elapsed_timestamp_nanos(0));
    EXPECT_EQ(baseTimeNs + 5 * bucketSizeNs, data.bucket_info(3).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 6 * bucketSizeNs, data.bucket_info(3).end_bucket_elapsed_nanos());
    EXPECT_TRUE(data.bucket_info(3).atom(0).subsystem_sleep_state().subsystem_name().empty());
    EXPECT_GT(data.bucket_info(3).atom(0).subsystem_sleep_state().time_millis(), 0);

    EXPECT_EQ(1, data.bucket_info(4).atom_size());
    EXPECT_EQ(1, data.bucket_info(4).elapsed_timestamp_nanos_size());
    EXPECT_EQ(baseTimeNs + 7 * bucketSizeNs + 1,
              data.bucket_info(4).elapsed_timestamp_nanos(0));
    EXPECT_EQ(baseTimeNs + 7 * bucketSizeNs, data.bucket_info(4).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 8 * bucketSizeNs, data.bucket_info(4).end_bucket_elapsed_nanos());
    EXPECT_TRUE(data.bucket_info(4).atom(0).subsystem_sleep_state().subsystem_name().empty());
    EXPECT_GT(data.bucket_info(4).atom(0).subsystem_sleep_state().time_millis(), 0);

    EXPECT_EQ(1, data.bucket_info(5).atom_size());
    EXPECT_EQ(1, data.bucket_info(5).elapsed_timestamp_nanos_size());
    EXPECT_EQ(baseTimeNs + 8 * bucketSizeNs + 2,
              data.bucket_info(5).elapsed_timestamp_nanos(0));
    EXPECT_EQ(baseTimeNs + 8 * bucketSizeNs, data.bucket_info(5).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 9 * bucketSizeNs, data.bucket_info(5).end_bucket_elapsed_nanos());
    EXPECT_TRUE(data.bucket_info(5).atom(0).subsystem_sleep_state().subsystem_name().empty());
    EXPECT_GT(data.bucket_info(5).atom(0).subsystem_sleep_state().time_millis(), 0);
}

TEST(GaugeMetricE2eTest, TestConditionChangeToTrueSamplePulledEvents) {
    auto config = CreateStatsdConfig(GaugeMetric::CONDITION_CHANGE_TO_TRUE);
    int64_t baseTimeNs = getElapsedRealtimeNs();
    int64_t configAddedTimeNs = 10 * 60 * NS_PER_SEC + baseTimeNs;
    int64_t bucketSizeNs =
        TimeUnitToBucketSizeInMillis(config.gauge_metric(0).bucket()) * 1000000;

    ConfigKey cfgKey;
    auto processor = CreateStatsLogProcessor(
        baseTimeNs, configAddedTimeNs, config, cfgKey);
    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());
    processor->mPullerManager->ForceClearPullerCache();

    int startBucketNum = processor->mMetricsManagers.begin()->second->
            mAllMetricProducers[0]->getCurrentBucketNum();
    EXPECT_GT(startBucketNum, (int64_t)0);

    auto screenOffEvent = CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_OFF,
                                                        configAddedTimeNs + 55);
    processor->OnLogEvent(screenOffEvent.get());

    auto screenOnEvent = CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_ON,
                                                       configAddedTimeNs + bucketSizeNs + 10);
    processor->OnLogEvent(screenOnEvent.get());

    screenOffEvent = CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_OFF,
                                                   configAddedTimeNs + bucketSizeNs + 100);
    processor->OnLogEvent(screenOffEvent.get());

    screenOnEvent = CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_ON,
                                                  configAddedTimeNs + 3 * bucketSizeNs + 2);
    processor->OnLogEvent(screenOnEvent.get());

    screenOffEvent = CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_OFF,
                                                  configAddedTimeNs + 5 * bucketSizeNs + 1);
    processor->OnLogEvent(screenOffEvent.get());
    screenOnEvent = CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_ON,
                                                  configAddedTimeNs + 5 * bucketSizeNs + 3);
    processor->OnLogEvent(screenOnEvent.get());
    screenOffEvent = CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_OFF,
                                                  configAddedTimeNs + 5 * bucketSizeNs + 10);
    processor->OnLogEvent(screenOffEvent.get());

    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(cfgKey, configAddedTimeNs + 8 * bucketSizeNs + 10, false, true,
                            ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(buffer.size() > 0);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);
    EXPECT_EQ(1, reports.reports_size());
    EXPECT_EQ(1, reports.reports(0).metrics_size());
    StatsLogReport::GaugeMetricDataWrapper gaugeMetrics;
    sortMetricDataByDimensionsValue(
            reports.reports(0).metrics(0).gauge_metrics(), &gaugeMetrics);
    EXPECT_GT((int)gaugeMetrics.data_size(), 1);

    auto data = gaugeMetrics.data(0);
    EXPECT_EQ(android::util::SUBSYSTEM_SLEEP_STATE, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* subsystem name field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_FALSE(data.dimensions_in_what().value_tuple().dimensions_value(0).value_str().empty());
    EXPECT_EQ(3, data.bucket_info_size());

    EXPECT_EQ(1, data.bucket_info(0).atom_size());
    EXPECT_EQ(1, data.bucket_info(0).elapsed_timestamp_nanos_size());
    EXPECT_EQ(configAddedTimeNs + 55, data.bucket_info(0).elapsed_timestamp_nanos(0));
    EXPECT_EQ(0, data.bucket_info(0).wall_clock_timestamp_nanos_size());
    EXPECT_EQ(baseTimeNs + 2 * bucketSizeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 3 * bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());
    EXPECT_TRUE(data.bucket_info(0).atom(0).subsystem_sleep_state().subsystem_name().empty());
    EXPECT_GT(data.bucket_info(0).atom(0).subsystem_sleep_state().time_millis(), 0);

    EXPECT_EQ(1, data.bucket_info(1).atom_size());
    EXPECT_EQ(baseTimeNs + 3 * bucketSizeNs + 100,
              data.bucket_info(1).elapsed_timestamp_nanos(0));
    EXPECT_EQ(configAddedTimeNs + 55, data.bucket_info(0).elapsed_timestamp_nanos(0));
    EXPECT_EQ(baseTimeNs + 3 * bucketSizeNs, data.bucket_info(1).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 4 * bucketSizeNs, data.bucket_info(1).end_bucket_elapsed_nanos());
    EXPECT_TRUE(data.bucket_info(1).atom(0).subsystem_sleep_state().subsystem_name().empty());
    EXPECT_GT(data.bucket_info(1).atom(0).subsystem_sleep_state().time_millis(), 0);

    EXPECT_EQ(2, data.bucket_info(2).atom_size());
    EXPECT_EQ(2, data.bucket_info(2).elapsed_timestamp_nanos_size());
    EXPECT_EQ(baseTimeNs + 7 * bucketSizeNs + 1,
              data.bucket_info(2).elapsed_timestamp_nanos(0));
    EXPECT_EQ(baseTimeNs + 7 * bucketSizeNs + 10,
              data.bucket_info(2).elapsed_timestamp_nanos(1));
    EXPECT_EQ(baseTimeNs + 7 * bucketSizeNs, data.bucket_info(2).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 8 * bucketSizeNs, data.bucket_info(2).end_bucket_elapsed_nanos());
    EXPECT_TRUE(data.bucket_info(2).atom(0).subsystem_sleep_state().subsystem_name().empty());
    EXPECT_GT(data.bucket_info(2).atom(0).subsystem_sleep_state().time_millis(), 0);
    EXPECT_TRUE(data.bucket_info(2).atom(1).subsystem_sleep_state().subsystem_name().empty());
    EXPECT_GT(data.bucket_info(2).atom(1).subsystem_sleep_state().time_millis(), 0);
}


TEST(GaugeMetricE2eTest, TestRandomSamplePulledEvent_LateAlarm) {
    auto config = CreateStatsdConfig(GaugeMetric::RANDOM_ONE_SAMPLE);
    int64_t baseTimeNs = getElapsedRealtimeNs();
    int64_t configAddedTimeNs = 10 * 60 * NS_PER_SEC + baseTimeNs;
    int64_t bucketSizeNs =
        TimeUnitToBucketSizeInMillis(config.gauge_metric(0).bucket()) * 1000000;

    ConfigKey cfgKey;
    auto processor = CreateStatsLogProcessor(
        baseTimeNs, configAddedTimeNs, config, cfgKey);
    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());
    processor->mPullerManager->ForceClearPullerCache();

    int startBucketNum = processor->mMetricsManagers.begin()->second->
            mAllMetricProducers[0]->getCurrentBucketNum();
    EXPECT_GT(startBucketNum, (int64_t)0);

    // When creating the config, the gauge metric producer should register the alarm at the
    // end of the current bucket.
    EXPECT_EQ((size_t)1, processor->mPullerManager->mReceivers.size());
    EXPECT_EQ(bucketSizeNs,
              processor->mPullerManager->mReceivers.begin()->second.front().intervalNs);
    int64_t& nextPullTimeNs =
            processor->mPullerManager->mReceivers.begin()->second.front().nextPullTimeNs;
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + bucketSizeNs, nextPullTimeNs);

    auto screenOffEvent = CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_OFF,
                                                        configAddedTimeNs + 55);
    processor->OnLogEvent(screenOffEvent.get());

    auto screenOnEvent = CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_ON,
                                                       configAddedTimeNs + bucketSizeNs + 10);
    processor->OnLogEvent(screenOnEvent.get());

    // Pulling alarm arrives one bucket size late.
    processor->informPullAlarmFired(nextPullTimeNs + bucketSizeNs);
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + 3 * bucketSizeNs, nextPullTimeNs);

    screenOffEvent = CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_OFF,
                                                   configAddedTimeNs + 3 * bucketSizeNs + 11);
    processor->OnLogEvent(screenOffEvent.get());

    // Pulling alarm arrives more than one bucket size late.
    processor->informPullAlarmFired(nextPullTimeNs + bucketSizeNs + 12);
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + 5 * bucketSizeNs, nextPullTimeNs);

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
    StatsLogReport::GaugeMetricDataWrapper gaugeMetrics;
    sortMetricDataByDimensionsValue(
            reports.reports(0).metrics(0).gauge_metrics(), &gaugeMetrics);
    EXPECT_GT((int)gaugeMetrics.data_size(), 1);

    auto data = gaugeMetrics.data(0);
    EXPECT_EQ(android::util::SUBSYSTEM_SLEEP_STATE, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* subsystem name field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_FALSE(data.dimensions_in_what().value_tuple().dimensions_value(0).value_str().empty());
    EXPECT_EQ(3, data.bucket_info_size());

    EXPECT_EQ(1, data.bucket_info(0).atom_size());
    EXPECT_EQ(1, data.bucket_info(0).elapsed_timestamp_nanos_size());
    EXPECT_EQ(configAddedTimeNs + 55, data.bucket_info(0).elapsed_timestamp_nanos(0));
    EXPECT_EQ(baseTimeNs + 2 * bucketSizeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 3 * bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());
    EXPECT_TRUE(data.bucket_info(0).atom(0).subsystem_sleep_state().subsystem_name().empty());
    EXPECT_GT(data.bucket_info(0).atom(0).subsystem_sleep_state().time_millis(), 0);

    EXPECT_EQ(1, data.bucket_info(1).atom_size());
    EXPECT_EQ(configAddedTimeNs + 3 * bucketSizeNs + 11,
              data.bucket_info(1).elapsed_timestamp_nanos(0));
    EXPECT_EQ(configAddedTimeNs + 55, data.bucket_info(0).elapsed_timestamp_nanos(0));
    EXPECT_EQ(baseTimeNs + 5 * bucketSizeNs, data.bucket_info(1).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 6 * bucketSizeNs, data.bucket_info(1).end_bucket_elapsed_nanos());
    EXPECT_TRUE(data.bucket_info(1).atom(0).subsystem_sleep_state().subsystem_name().empty());
    EXPECT_GT(data.bucket_info(1).atom(0).subsystem_sleep_state().time_millis(), 0);

    EXPECT_EQ(1, data.bucket_info(2).atom_size());
    EXPECT_EQ(1, data.bucket_info(2).elapsed_timestamp_nanos_size());
    EXPECT_EQ(baseTimeNs + 6 * bucketSizeNs + 12,
              data.bucket_info(2).elapsed_timestamp_nanos(0));
    EXPECT_EQ(baseTimeNs + 6 * bucketSizeNs, data.bucket_info(2).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 7 * bucketSizeNs, data.bucket_info(2).end_bucket_elapsed_nanos());
    EXPECT_TRUE(data.bucket_info(2).atom(0).subsystem_sleep_state().subsystem_name().empty());
    EXPECT_GT(data.bucket_info(2).atom(0).subsystem_sleep_state().time_millis(), 0);
}

TEST(GaugeMetricE2eTest, TestRandomSamplePulledEventsWithActivation) {
    auto config = CreateStatsdConfig(GaugeMetric::RANDOM_ONE_SAMPLE, /*useCondition=*/false);

    int64_t baseTimeNs = getElapsedRealtimeNs();
    int64_t configAddedTimeNs = 10 * 60 * NS_PER_SEC + baseTimeNs;
    int64_t bucketSizeNs =
        TimeUnitToBucketSizeInMillis(config.gauge_metric(0).bucket()) * 1000000;

    auto batterySaverStartMatcher = CreateBatterySaverModeStartAtomMatcher();
    *config.add_atom_matcher() = batterySaverStartMatcher;
    const int64_t ttlNs = 2 * bucketSizeNs; // Two buckets.
    auto metric_activation = config.add_metric_activation();
    metric_activation->set_metric_id(metricId);
    metric_activation->set_activation_type(ACTIVATE_IMMEDIATELY);
    auto event_activation = metric_activation->add_event_activation();
    event_activation->set_atom_matcher_id(batterySaverStartMatcher.id());
    event_activation->set_ttl_seconds(ttlNs / 1000000000);

    ConfigKey cfgKey;
    auto processor = CreateStatsLogProcessor(
        baseTimeNs, configAddedTimeNs, config, cfgKey);
    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());
    processor->mPullerManager->ForceClearPullerCache();

    int startBucketNum = processor->mMetricsManagers.begin()->second->
            mAllMetricProducers[0]->getCurrentBucketNum();
    EXPECT_GT(startBucketNum, (int64_t)0);
    EXPECT_FALSE(processor->mMetricsManagers.begin()->second->mAllMetricProducers[0]->isActive());

    // When creating the config, the gauge metric producer should register the alarm at the
    // end of the current bucket.
    EXPECT_EQ((size_t)1, processor->mPullerManager->mReceivers.size());
    EXPECT_EQ(bucketSizeNs,
              processor->mPullerManager->mReceivers.begin()->second.front().intervalNs);
    int64_t& nextPullTimeNs =
            processor->mPullerManager->mReceivers.begin()->second.front().nextPullTimeNs;
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + bucketSizeNs, nextPullTimeNs);

    // Pulling alarm arrives on time and reset the sequential pulling alarm.
    // Event should not be kept.
    processor->informPullAlarmFired(nextPullTimeNs + 1); // 15 mins + 1 ns.
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + 2 * bucketSizeNs, nextPullTimeNs);
    EXPECT_FALSE(processor->mMetricsManagers.begin()->second->mAllMetricProducers[0]->isActive());

    // Activate the metric. A pull occurs upon activation.
    const int64_t activationNs = configAddedTimeNs + bucketSizeNs + (2 * 1000 * 1000); // 2 millis.
    auto batterySaverOnEvent = CreateBatterySaverOnEvent(activationNs);
    processor->OnLogEvent(batterySaverOnEvent.get()); // 15 mins + 2 ms.
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->mAllMetricProducers[0]->isActive());

    // This event should be kept. 2 total.
    processor->informPullAlarmFired(nextPullTimeNs + 1); // 20 mins + 1 ns.
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + 3 * bucketSizeNs,
              nextPullTimeNs);

    // This event should be kept. 3 total.
    processor->informPullAlarmFired(nextPullTimeNs + 2); // 25 mins + 2 ns.
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + 4 * bucketSizeNs, nextPullTimeNs);

    // Create random event to deactivate metric.
    auto deactivationEvent = CreateScreenBrightnessChangedEvent(50, activationNs + ttlNs + 1);
    processor->OnLogEvent(deactivationEvent.get());
    EXPECT_FALSE(processor->mMetricsManagers.begin()->second->mAllMetricProducers[0]->isActive());

    // Event should not be kept. 3 total.
    processor->informPullAlarmFired(nextPullTimeNs + 3);
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + 5 * bucketSizeNs, nextPullTimeNs);

    processor->informPullAlarmFired(nextPullTimeNs + 2);

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
    StatsLogReport::GaugeMetricDataWrapper gaugeMetrics;
    sortMetricDataByDimensionsValue(
            reports.reports(0).metrics(0).gauge_metrics(), &gaugeMetrics);
    EXPECT_GT((int)gaugeMetrics.data_size(), 0);

    auto data = gaugeMetrics.data(0);
    EXPECT_EQ(android::util::SUBSYSTEM_SLEEP_STATE, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* subsystem name field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_FALSE(data.dimensions_in_what().value_tuple().dimensions_value(0).value_str().empty());
    EXPECT_EQ(3, data.bucket_info_size());

    auto bucketInfo = data.bucket_info(0);
    EXPECT_EQ(1, bucketInfo.atom_size());
    EXPECT_EQ(1, bucketInfo.elapsed_timestamp_nanos_size());
    EXPECT_EQ(activationNs, bucketInfo.elapsed_timestamp_nanos(0));
    EXPECT_EQ(0, bucketInfo.wall_clock_timestamp_nanos_size());
    EXPECT_EQ(baseTimeNs + 3 * bucketSizeNs, bucketInfo.start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 4 * bucketSizeNs, bucketInfo.end_bucket_elapsed_nanos());
    EXPECT_TRUE(bucketInfo.atom(0).subsystem_sleep_state().subsystem_name().empty());
    EXPECT_GT(bucketInfo.atom(0).subsystem_sleep_state().time_millis(), 0);

    bucketInfo = data.bucket_info(1);
    EXPECT_EQ(1, bucketInfo.atom_size());
    EXPECT_EQ(1, bucketInfo.elapsed_timestamp_nanos_size());
    EXPECT_EQ(baseTimeNs + 4 * bucketSizeNs + 1, bucketInfo.elapsed_timestamp_nanos(0));
    EXPECT_EQ(0, bucketInfo.wall_clock_timestamp_nanos_size());
    EXPECT_EQ(baseTimeNs + 4 * bucketSizeNs, bucketInfo.start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 5 * bucketSizeNs, bucketInfo.end_bucket_elapsed_nanos());
    EXPECT_TRUE(bucketInfo.atom(0).subsystem_sleep_state().subsystem_name().empty());
    EXPECT_GT(bucketInfo.atom(0).subsystem_sleep_state().time_millis(), 0);

    bucketInfo = data.bucket_info(2);
    EXPECT_EQ(1, bucketInfo.atom_size());
    EXPECT_EQ(1, bucketInfo.elapsed_timestamp_nanos_size());
    EXPECT_EQ(baseTimeNs + 5 * bucketSizeNs + 2, bucketInfo.elapsed_timestamp_nanos(0));
    EXPECT_EQ(0, bucketInfo.wall_clock_timestamp_nanos_size());
    EXPECT_EQ(MillisToNano(NanoToMillis(baseTimeNs + 5 * bucketSizeNs)),
            bucketInfo.start_bucket_elapsed_nanos());
    EXPECT_EQ(MillisToNano(NanoToMillis(activationNs + ttlNs + 1)),
            bucketInfo.end_bucket_elapsed_nanos());
    EXPECT_TRUE(bucketInfo.atom(0).subsystem_sleep_state().subsystem_name().empty());
    EXPECT_GT(bucketInfo.atom(0).subsystem_sleep_state().time_millis(), 0);
}

TEST(GaugeMetricE2eTest, TestRandomSamplePulledEventsNoCondition) {
    auto config = CreateStatsdConfig(GaugeMetric::RANDOM_ONE_SAMPLE, /*useCondition=*/false);

    int64_t baseTimeNs = getElapsedRealtimeNs();
    int64_t configAddedTimeNs = 10 * 60 * NS_PER_SEC + baseTimeNs;
    int64_t bucketSizeNs =
        TimeUnitToBucketSizeInMillis(config.gauge_metric(0).bucket()) * 1000000;

    ConfigKey cfgKey;
    auto processor = CreateStatsLogProcessor(
        baseTimeNs, configAddedTimeNs, config, cfgKey);
    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());
    processor->mPullerManager->ForceClearPullerCache();

    int startBucketNum = processor->mMetricsManagers.begin()->second->
            mAllMetricProducers[0]->getCurrentBucketNum();
    EXPECT_GT(startBucketNum, (int64_t)0);

    // When creating the config, the gauge metric producer should register the alarm at the
    // end of the current bucket.
    EXPECT_EQ((size_t)1, processor->mPullerManager->mReceivers.size());
    EXPECT_EQ(bucketSizeNs,
              processor->mPullerManager->mReceivers.begin()->second.front().intervalNs);
    int64_t& nextPullTimeNs =
            processor->mPullerManager->mReceivers.begin()->second.front().nextPullTimeNs;
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + bucketSizeNs, nextPullTimeNs);

    // Pulling alarm arrives on time and reset the sequential pulling alarm.
    processor->informPullAlarmFired(nextPullTimeNs + 1);
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + 2 * bucketSizeNs, nextPullTimeNs);

    processor->informPullAlarmFired(nextPullTimeNs + 4);
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + 3 * bucketSizeNs,
              nextPullTimeNs);

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
    StatsLogReport::GaugeMetricDataWrapper gaugeMetrics;
    sortMetricDataByDimensionsValue(
            reports.reports(0).metrics(0).gauge_metrics(), &gaugeMetrics);
    EXPECT_GT((int)gaugeMetrics.data_size(), 0);

    auto data = gaugeMetrics.data(0);
    EXPECT_EQ(android::util::SUBSYSTEM_SLEEP_STATE, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(1 /* subsystem name field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_FALSE(data.dimensions_in_what().value_tuple().dimensions_value(0).value_str().empty());
    EXPECT_EQ(3, data.bucket_info_size());

    EXPECT_EQ(1, data.bucket_info(0).atom_size());
    EXPECT_EQ(1, data.bucket_info(0).elapsed_timestamp_nanos_size());
    EXPECT_EQ(configAddedTimeNs, data.bucket_info(0).elapsed_timestamp_nanos(0));
    EXPECT_EQ(0, data.bucket_info(0).wall_clock_timestamp_nanos_size());
    EXPECT_EQ(baseTimeNs + 2 * bucketSizeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 3 * bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());
    EXPECT_TRUE(data.bucket_info(0).atom(0).subsystem_sleep_state().subsystem_name().empty());
    EXPECT_GT(data.bucket_info(0).atom(0).subsystem_sleep_state().time_millis(), 0);

    EXPECT_EQ(1, data.bucket_info(1).atom_size());
    EXPECT_EQ(1, data.bucket_info(1).elapsed_timestamp_nanos_size());
    EXPECT_EQ(baseTimeNs + 3 * bucketSizeNs + 1, data.bucket_info(1).elapsed_timestamp_nanos(0));
    EXPECT_EQ(0, data.bucket_info(1).wall_clock_timestamp_nanos_size());
    EXPECT_EQ(baseTimeNs + 3 * bucketSizeNs, data.bucket_info(1).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 4 * bucketSizeNs, data.bucket_info(1).end_bucket_elapsed_nanos());
    EXPECT_TRUE(data.bucket_info(1).atom(0).subsystem_sleep_state().subsystem_name().empty());
    EXPECT_GT(data.bucket_info(1).atom(0).subsystem_sleep_state().time_millis(), 0);

    EXPECT_EQ(1, data.bucket_info(2).atom_size());
    EXPECT_EQ(1, data.bucket_info(2).elapsed_timestamp_nanos_size());
    EXPECT_EQ(baseTimeNs + 4 * bucketSizeNs + 4, data.bucket_info(2).elapsed_timestamp_nanos(0));
    EXPECT_EQ(0, data.bucket_info(2).wall_clock_timestamp_nanos_size());
    EXPECT_EQ(baseTimeNs + 4 * bucketSizeNs, data.bucket_info(2).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 5 * bucketSizeNs, data.bucket_info(2).end_bucket_elapsed_nanos());
    EXPECT_TRUE(data.bucket_info(2).atom(0).subsystem_sleep_state().subsystem_name().empty());
    EXPECT_GT(data.bucket_info(2).atom(0).subsystem_sleep_state().time_millis(), 0);
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif

}  // namespace statsd
}  // namespace os
}  // namespace android
