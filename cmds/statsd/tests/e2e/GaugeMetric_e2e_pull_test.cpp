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

StatsdConfig CreateStatsdConfig(const GaugeMetric::SamplingType sampling_type) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT"); // LogEvent defaults to UID of root.
    auto temperatureAtomMatcher = CreateTemperatureAtomMatcher();
    *config.add_atom_matcher() = temperatureAtomMatcher;
    *config.add_atom_matcher() = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = CreateScreenTurnedOffAtomMatcher();

    auto screenIsOffPredicate = CreateScreenIsOffPredicate();
    *config.add_predicate() = screenIsOffPredicate;

    auto gaugeMetric = config.add_gauge_metric();
    gaugeMetric->set_id(123456);
    gaugeMetric->set_what(temperatureAtomMatcher.id());
    gaugeMetric->set_condition(screenIsOffPredicate.id());
    gaugeMetric->set_sampling_type(sampling_type);
    gaugeMetric->mutable_gauge_fields_filter()->set_include_all(true);
    *gaugeMetric->mutable_dimensions_in_what() =
        CreateDimensions(android::util::TEMPERATURE, {2/* sensor name field */ });
    gaugeMetric->set_bucket(FIVE_MINUTES);

    return config;
}

}  // namespace

TEST(GaugeMetricE2eTest, TestRandomSamplePulledEvents) {
    auto config = CreateStatsdConfig(GaugeMetric::RANDOM_ONE_SAMPLE);
    int64_t baseTimeNs = 10 * NS_PER_SEC;
    int64_t configAddedTimeNs = 10 * 60 * NS_PER_SEC + baseTimeNs;
    int64_t bucketSizeNs =
        TimeUnitToBucketSizeInMillis(config.gauge_metric(0).bucket()) * 1000000;

    ConfigKey cfgKey;
    auto processor = CreateStatsLogProcessor(
        baseTimeNs, configAddedTimeNs, config, cfgKey);
    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());

    int startBucketNum = processor->mMetricsManagers.begin()->second->
            mAllMetricProducers[0]->getCurrentBucketNum();
    EXPECT_GT(startBucketNum, (int64_t)0);

    // When creating the config, the gauge metric producer should register the alarm at the
    // end of the current bucket.
    EXPECT_EQ((size_t)1, StatsPullerManagerImpl::GetInstance().mReceivers.size());
    EXPECT_EQ(bucketSizeNs,
              StatsPullerManagerImpl::GetInstance().mReceivers.begin()->
                    second.front().intervalNs);
    int64_t& nextPullTimeNs = StatsPullerManagerImpl::GetInstance().mReceivers.begin()->
            second.front().nextPullTimeNs;
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
                            ADB_DUMP, &buffer);
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
    EXPECT_EQ(android::util::TEMPERATURE, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(2 /* sensor name field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_FALSE(data.dimensions_in_what().value_tuple().dimensions_value(0).value_str().empty());
    EXPECT_EQ(6, data.bucket_info_size());

    EXPECT_EQ(1, data.bucket_info(0).atom_size());
    EXPECT_EQ(1, data.bucket_info(0).elapsed_timestamp_nanos_size());
    EXPECT_EQ(configAddedTimeNs + 55, data.bucket_info(0).elapsed_timestamp_nanos(0));
    EXPECT_EQ(1, data.bucket_info(0).wall_clock_timestamp_nanos_size());
    EXPECT_EQ(baseTimeNs + 2 * bucketSizeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 3 * bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());
    EXPECT_FALSE(data.bucket_info(0).atom(0).temperature().sensor_name().empty());
    EXPECT_GT(data.bucket_info(0).atom(0).temperature().temperature_dc(), 0);

    EXPECT_EQ(1, data.bucket_info(1).atom_size());
    EXPECT_EQ(baseTimeNs + 3 * bucketSizeNs + 1,
              data.bucket_info(1).elapsed_timestamp_nanos(0));
    EXPECT_EQ(configAddedTimeNs + 55, data.bucket_info(0).elapsed_timestamp_nanos(0));
    EXPECT_EQ(baseTimeNs + 3 * bucketSizeNs, data.bucket_info(1).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 4 * bucketSizeNs, data.bucket_info(1).end_bucket_elapsed_nanos());
    EXPECT_FALSE(data.bucket_info(1).atom(0).temperature().sensor_name().empty());
    EXPECT_GT(data.bucket_info(1).atom(0).temperature().temperature_dc(), 0);

    EXPECT_EQ(1, data.bucket_info(2).atom_size());
    EXPECT_EQ(1, data.bucket_info(2).elapsed_timestamp_nanos_size());
    EXPECT_EQ(baseTimeNs + 4 * bucketSizeNs + 1,
              data.bucket_info(2).elapsed_timestamp_nanos(0));
    EXPECT_EQ(baseTimeNs + 4 * bucketSizeNs, data.bucket_info(2).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 5 * bucketSizeNs, data.bucket_info(2).end_bucket_elapsed_nanos());
    EXPECT_FALSE(data.bucket_info(2).atom(0).temperature().sensor_name().empty());
    EXPECT_GT(data.bucket_info(2).atom(0).temperature().temperature_dc(), 0);

    EXPECT_EQ(1, data.bucket_info(3).atom_size());
    EXPECT_EQ(1, data.bucket_info(3).elapsed_timestamp_nanos_size());
    EXPECT_EQ(baseTimeNs + 5 * bucketSizeNs + 1,
              data.bucket_info(3).elapsed_timestamp_nanos(0));
    EXPECT_EQ(baseTimeNs + 5 * bucketSizeNs, data.bucket_info(3).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 6 * bucketSizeNs, data.bucket_info(3).end_bucket_elapsed_nanos());
    EXPECT_FALSE(data.bucket_info(3).atom(0).temperature().sensor_name().empty());
    EXPECT_GT(data.bucket_info(3).atom(0).temperature().temperature_dc(), 0);

    EXPECT_EQ(1, data.bucket_info(4).atom_size());
    EXPECT_EQ(1, data.bucket_info(4).elapsed_timestamp_nanos_size());
    EXPECT_EQ(baseTimeNs + 7 * bucketSizeNs + 1,
              data.bucket_info(4).elapsed_timestamp_nanos(0));
    EXPECT_EQ(baseTimeNs + 7 * bucketSizeNs, data.bucket_info(4).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 8 * bucketSizeNs, data.bucket_info(4).end_bucket_elapsed_nanos());
    EXPECT_FALSE(data.bucket_info(4).atom(0).temperature().sensor_name().empty());
    EXPECT_GT(data.bucket_info(4).atom(0).temperature().temperature_dc(), 0);

    EXPECT_EQ(1, data.bucket_info(5).atom_size());
    EXPECT_EQ(1, data.bucket_info(5).elapsed_timestamp_nanos_size());
    EXPECT_EQ(baseTimeNs + 8 * bucketSizeNs + 2,
              data.bucket_info(5).elapsed_timestamp_nanos(0));
    EXPECT_EQ(baseTimeNs + 8 * bucketSizeNs, data.bucket_info(5).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 9 * bucketSizeNs, data.bucket_info(5).end_bucket_elapsed_nanos());
    EXPECT_FALSE(data.bucket_info(5).atom(0).temperature().sensor_name().empty());
    EXPECT_GT(data.bucket_info(5).atom(0).temperature().temperature_dc(), 0);
}

TEST(GaugeMetricE2eTest, TestAllConditionChangesSamplePulledEvents) {
    auto config = CreateStatsdConfig(GaugeMetric::ALL_CONDITION_CHANGES);
    int64_t baseTimeNs = 10 * NS_PER_SEC;
    int64_t configAddedTimeNs = 10 * 60 * NS_PER_SEC + baseTimeNs;
    int64_t bucketSizeNs =
        TimeUnitToBucketSizeInMillis(config.gauge_metric(0).bucket()) * 1000000;

    ConfigKey cfgKey;
    auto processor = CreateStatsLogProcessor(
        baseTimeNs, configAddedTimeNs, config, cfgKey);
    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());

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
                            ADB_DUMP, &buffer);
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
    EXPECT_EQ(android::util::TEMPERATURE, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(2 /* sensor name field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_FALSE(data.dimensions_in_what().value_tuple().dimensions_value(0).value_str().empty());
    EXPECT_EQ(3, data.bucket_info_size());

    EXPECT_EQ(1, data.bucket_info(0).atom_size());
    EXPECT_EQ(1, data.bucket_info(0).elapsed_timestamp_nanos_size());
    EXPECT_EQ(configAddedTimeNs + 55, data.bucket_info(0).elapsed_timestamp_nanos(0));
    EXPECT_EQ(1, data.bucket_info(0).wall_clock_timestamp_nanos_size());
    EXPECT_EQ(baseTimeNs + 2 * bucketSizeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 3 * bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());
    EXPECT_FALSE(data.bucket_info(0).atom(0).temperature().sensor_name().empty());
    EXPECT_GT(data.bucket_info(0).atom(0).temperature().temperature_dc(), 0);

    EXPECT_EQ(1, data.bucket_info(1).atom_size());
    EXPECT_EQ(baseTimeNs + 3 * bucketSizeNs + 100,
              data.bucket_info(1).elapsed_timestamp_nanos(0));
    EXPECT_EQ(configAddedTimeNs + 55, data.bucket_info(0).elapsed_timestamp_nanos(0));
    EXPECT_EQ(baseTimeNs + 3 * bucketSizeNs, data.bucket_info(1).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 4 * bucketSizeNs, data.bucket_info(1).end_bucket_elapsed_nanos());
    EXPECT_FALSE(data.bucket_info(1).atom(0).temperature().sensor_name().empty());
    EXPECT_GT(data.bucket_info(1).atom(0).temperature().temperature_dc(), 0);

    EXPECT_EQ(2, data.bucket_info(2).atom_size());
    EXPECT_EQ(2, data.bucket_info(2).elapsed_timestamp_nanos_size());
    EXPECT_EQ(baseTimeNs + 7 * bucketSizeNs + 1,
              data.bucket_info(2).elapsed_timestamp_nanos(0));
    EXPECT_EQ(baseTimeNs + 7 * bucketSizeNs + 10,
              data.bucket_info(2).elapsed_timestamp_nanos(1));
    EXPECT_EQ(baseTimeNs + 7 * bucketSizeNs, data.bucket_info(2).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 8 * bucketSizeNs, data.bucket_info(2).end_bucket_elapsed_nanos());
    EXPECT_FALSE(data.bucket_info(2).atom(0).temperature().sensor_name().empty());
    EXPECT_GT(data.bucket_info(2).atom(0).temperature().temperature_dc(), 0);
    EXPECT_FALSE(data.bucket_info(2).atom(1).temperature().sensor_name().empty());
    EXPECT_GT(data.bucket_info(2).atom(1).temperature().temperature_dc(), 0);
}


TEST(GaugeMetricE2eTest, TestRandomSamplePulledEvent_LateAlarm) {
    auto config = CreateStatsdConfig(GaugeMetric::RANDOM_ONE_SAMPLE);
    int64_t baseTimeNs = 10 * NS_PER_SEC;
    int64_t configAddedTimeNs = 10 * 60 * NS_PER_SEC + baseTimeNs;
    int64_t bucketSizeNs =
        TimeUnitToBucketSizeInMillis(config.gauge_metric(0).bucket()) * 1000000;

    ConfigKey cfgKey;
    auto processor = CreateStatsLogProcessor(
        baseTimeNs, configAddedTimeNs, config, cfgKey);
    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());

    int startBucketNum = processor->mMetricsManagers.begin()->second->
            mAllMetricProducers[0]->getCurrentBucketNum();
    EXPECT_GT(startBucketNum, (int64_t)0);

    // When creating the config, the gauge metric producer should register the alarm at the
    // end of the current bucket.
    EXPECT_EQ((size_t)1, StatsPullerManagerImpl::GetInstance().mReceivers.size());
    EXPECT_EQ(bucketSizeNs,
              StatsPullerManagerImpl::GetInstance().mReceivers.begin()->
                    second.front().intervalNs);
    int64_t& nextPullTimeNs = StatsPullerManagerImpl::GetInstance().mReceivers.begin()->
            second.front().nextPullTimeNs;
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
                            ADB_DUMP, &buffer);
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
    EXPECT_EQ(android::util::TEMPERATURE, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(2 /* sensor name field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_FALSE(data.dimensions_in_what().value_tuple().dimensions_value(0).value_str().empty());
    EXPECT_EQ(3, data.bucket_info_size());

    EXPECT_EQ(1, data.bucket_info(0).atom_size());
    EXPECT_EQ(1, data.bucket_info(0).elapsed_timestamp_nanos_size());
    EXPECT_EQ(configAddedTimeNs + 55, data.bucket_info(0).elapsed_timestamp_nanos(0));
    EXPECT_EQ(1, data.bucket_info(0).wall_clock_timestamp_nanos_size());
    EXPECT_EQ(baseTimeNs + 2 * bucketSizeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 3 * bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());
    EXPECT_FALSE(data.bucket_info(0).atom(0).temperature().sensor_name().empty());
    EXPECT_GT(data.bucket_info(0).atom(0).temperature().temperature_dc(), 0);

    EXPECT_EQ(1, data.bucket_info(1).atom_size());
    EXPECT_EQ(configAddedTimeNs + 3 * bucketSizeNs + 11,
              data.bucket_info(1).elapsed_timestamp_nanos(0));
    EXPECT_EQ(configAddedTimeNs + 55, data.bucket_info(0).elapsed_timestamp_nanos(0));
    EXPECT_EQ(baseTimeNs + 5 * bucketSizeNs, data.bucket_info(1).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 6 * bucketSizeNs, data.bucket_info(1).end_bucket_elapsed_nanos());
    EXPECT_FALSE(data.bucket_info(1).atom(0).temperature().sensor_name().empty());
    EXPECT_GT(data.bucket_info(1).atom(0).temperature().temperature_dc(), 0);

    EXPECT_EQ(1, data.bucket_info(2).atom_size());
    EXPECT_EQ(1, data.bucket_info(2).elapsed_timestamp_nanos_size());
    EXPECT_EQ(baseTimeNs + 6 * bucketSizeNs + 12,
              data.bucket_info(2).elapsed_timestamp_nanos(0));
    EXPECT_EQ(baseTimeNs + 6 * bucketSizeNs, data.bucket_info(2).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 7 * bucketSizeNs, data.bucket_info(2).end_bucket_elapsed_nanos());
    EXPECT_FALSE(data.bucket_info(2).atom(0).temperature().sensor_name().empty());
    EXPECT_GT(data.bucket_info(2).atom(0).temperature().temperature_dc(), 0);

}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif

}  // namespace statsd
}  // namespace os
}  // namespace android
