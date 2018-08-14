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

StatsdConfig CreateStatsdConfig() {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT"); // LogEvent defaults to UID of root.
    auto temperatureAtomMatcher = CreateTemperatureAtomMatcher();
    *config.add_atom_matcher() = temperatureAtomMatcher;
    *config.add_atom_matcher() = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = CreateScreenTurnedOffAtomMatcher();

    auto screenIsOffPredicate = CreateScreenIsOffPredicate();
    *config.add_predicate() = screenIsOffPredicate;

    auto valueMetric = config.add_value_metric();
    valueMetric->set_id(123456);
    valueMetric->set_what(temperatureAtomMatcher.id());
    valueMetric->set_condition(screenIsOffPredicate.id());
    *valueMetric->mutable_value_field() =
        CreateDimensions(android::util::TEMPERATURE, {3/* temperature degree field */ });
    *valueMetric->mutable_dimensions_in_what() =
        CreateDimensions(android::util::TEMPERATURE, {2/* sensor name field */ });
    valueMetric->set_bucket(FIVE_MINUTES);
    valueMetric->set_use_absolute_value_on_reset(true);
    return config;
}

}  // namespace

TEST(ValueMetricE2eTest, TestPulledEvents) {
    auto config = CreateStatsdConfig();
    int64_t baseTimeNs = 10 * NS_PER_SEC;
    int64_t configAddedTimeNs = 10 * 60 * NS_PER_SEC + baseTimeNs;
    int64_t bucketSizeNs =
        TimeUnitToBucketSizeInMillis(config.value_metric(0).bucket()) * 1000000;

    ConfigKey cfgKey;
    auto processor = CreateStatsLogProcessor(
        baseTimeNs, configAddedTimeNs, config, cfgKey);
    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());
    processor->mStatsPullerManager.ForceClearPullerCache();

    int startBucketNum = processor->mMetricsManagers.begin()->second->
            mAllMetricProducers[0]->getCurrentBucketNum();
    EXPECT_GT(startBucketNum, (int64_t)0);

    // When creating the config, the gauge metric producer should register the alarm at the
    // end of the current bucket.
    EXPECT_EQ((size_t)1, StatsPullerManagerImpl::GetInstance().mReceivers.size());
    EXPECT_EQ(bucketSizeNs,
              StatsPullerManagerImpl::GetInstance().mReceivers.begin()->
                    second.front().intervalNs);
    int64_t& expectedPullTimeNs = StatsPullerManagerImpl::GetInstance().mReceivers.begin()->
            second.front().nextPullTimeNs;
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + bucketSizeNs, expectedPullTimeNs);

    auto screenOffEvent = CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_OFF,
                                                        configAddedTimeNs + 55);
    processor->OnLogEvent(screenOffEvent.get());

    auto screenOnEvent = CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_ON,
                                                       configAddedTimeNs + 65);
    processor->OnLogEvent(screenOnEvent.get());

    screenOffEvent = CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_OFF,
                                                   configAddedTimeNs + 75);
    processor->OnLogEvent(screenOffEvent.get());

    // Pulling alarm arrives on time and reset the sequential pulling alarm.
    processor->informPullAlarmFired(expectedPullTimeNs + 1);
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + 2 * bucketSizeNs, expectedPullTimeNs);

    processor->informPullAlarmFired(expectedPullTimeNs + 1);

    screenOnEvent = CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_ON,
                                                       configAddedTimeNs + 2 * bucketSizeNs + 15);
    processor->OnLogEvent(screenOnEvent.get());

    processor->informPullAlarmFired(expectedPullTimeNs + 1);

    processor->informPullAlarmFired(expectedPullTimeNs + 1);

    screenOffEvent = CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_OFF,
                                                   configAddedTimeNs + 4 * bucketSizeNs + 11);
    processor->OnLogEvent(screenOffEvent.get());

    processor->informPullAlarmFired(expectedPullTimeNs + 1);

    processor->informPullAlarmFired(expectedPullTimeNs + 1);

    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(cfgKey, configAddedTimeNs + 7 * bucketSizeNs + 10, false, ADB_DUMP,
                            &buffer);
    EXPECT_TRUE(buffer.size() > 0);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);
    EXPECT_EQ(1, reports.reports_size());
    EXPECT_EQ(1, reports.reports(0).metrics_size());
    StatsLogReport::ValueMetricDataWrapper valueMetrics;
    sortMetricDataByDimensionsValue(
            reports.reports(0).metrics(0).value_metrics(), &valueMetrics);
    EXPECT_GT((int)valueMetrics.data_size(), 1);

    auto data = valueMetrics.data(0);
    EXPECT_EQ(android::util::TEMPERATURE, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(2 /* sensor name field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_FALSE(data.dimensions_in_what().value_tuple().dimensions_value(0).value_str().empty());
    EXPECT_EQ(5, data.bucket_info_size());

    EXPECT_EQ(baseTimeNs + 2 * bucketSizeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 3 * bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());
    EXPECT_TRUE(data.bucket_info(0).has_value());

    EXPECT_EQ(baseTimeNs + 3 * bucketSizeNs, data.bucket_info(1).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 4 * bucketSizeNs, data.bucket_info(1).end_bucket_elapsed_nanos());
    EXPECT_TRUE(data.bucket_info(1).has_value());

    EXPECT_EQ(baseTimeNs + 4 * bucketSizeNs, data.bucket_info(2).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 5 * bucketSizeNs, data.bucket_info(2).end_bucket_elapsed_nanos());
    EXPECT_TRUE(data.bucket_info(2).has_value());

    EXPECT_EQ(baseTimeNs + 6 * bucketSizeNs, data.bucket_info(3).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 7 * bucketSizeNs, data.bucket_info(3).end_bucket_elapsed_nanos());
    EXPECT_TRUE(data.bucket_info(3).has_value());

    EXPECT_EQ(baseTimeNs + 7 * bucketSizeNs, data.bucket_info(4).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 8 * bucketSizeNs, data.bucket_info(4).end_bucket_elapsed_nanos());
    EXPECT_TRUE(data.bucket_info(4).has_value());
}

TEST(ValueMetricE2eTest, TestPulledEvents_LateAlarm) {
    auto config = CreateStatsdConfig();
    int64_t baseTimeNs = 10 * NS_PER_SEC;
    int64_t configAddedTimeNs = 10 * 60 * NS_PER_SEC + baseTimeNs;
    int64_t bucketSizeNs =
        TimeUnitToBucketSizeInMillis(config.value_metric(0).bucket()) * 1000000;

    ConfigKey cfgKey;
    auto processor = CreateStatsLogProcessor(
        baseTimeNs, configAddedTimeNs, config, cfgKey);
    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());
    processor->mStatsPullerManager.ForceClearPullerCache();

    int startBucketNum = processor->mMetricsManagers.begin()->second->
            mAllMetricProducers[0]->getCurrentBucketNum();
    EXPECT_GT(startBucketNum, (int64_t)0);

    // When creating the config, the gauge metric producer should register the alarm at the
    // end of the current bucket.
    EXPECT_EQ((size_t)1, StatsPullerManagerImpl::GetInstance().mReceivers.size());
    EXPECT_EQ(bucketSizeNs,
              StatsPullerManagerImpl::GetInstance().mReceivers.begin()->
                    second.front().intervalNs);
    int64_t& expectedPullTimeNs = StatsPullerManagerImpl::GetInstance().mReceivers.begin()->
            second.front().nextPullTimeNs;
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + bucketSizeNs, expectedPullTimeNs);

    // Screen off/on/off events.
    auto screenOffEvent = CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_OFF,
                                                        configAddedTimeNs + 55);
    processor->OnLogEvent(screenOffEvent.get());

    auto screenOnEvent = CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_ON,
                                                       configAddedTimeNs + 65);
    processor->OnLogEvent(screenOnEvent.get());

    screenOffEvent = CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_OFF,
                                                   configAddedTimeNs + 75);
    processor->OnLogEvent(screenOffEvent.get());

    // Pulling alarm arrives late by 2 buckets and 1 ns.
    processor->informPullAlarmFired(expectedPullTimeNs + 2 * bucketSizeNs + 1);
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + 4 * bucketSizeNs, expectedPullTimeNs);

    screenOnEvent = CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_ON,
                                                       configAddedTimeNs + 4 * bucketSizeNs + 65);
    processor->OnLogEvent(screenOnEvent.get());

    // Pulling alarm arrives late by one bucket size + 21ns.
    processor->informPullAlarmFired(expectedPullTimeNs + bucketSizeNs + 21);
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + 6 * bucketSizeNs, expectedPullTimeNs);

    screenOffEvent = CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_OFF,
                                                   configAddedTimeNs + 6 * bucketSizeNs + 31);
    processor->OnLogEvent(screenOffEvent.get());

    processor->informPullAlarmFired(expectedPullTimeNs + bucketSizeNs + 21);
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + 8 * bucketSizeNs, expectedPullTimeNs);

    processor->informPullAlarmFired(expectedPullTimeNs + 1);
    EXPECT_EQ(baseTimeNs + startBucketNum * bucketSizeNs + 9 * bucketSizeNs, expectedPullTimeNs);

    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(cfgKey, configAddedTimeNs + 9 * bucketSizeNs + 10, false, ADB_DUMP,
                            &buffer);
    EXPECT_TRUE(buffer.size() > 0);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);
    EXPECT_EQ(1, reports.reports_size());
    EXPECT_EQ(1, reports.reports(0).metrics_size());
    StatsLogReport::ValueMetricDataWrapper valueMetrics;
    sortMetricDataByDimensionsValue(
            reports.reports(0).metrics(0).value_metrics(), &valueMetrics);
    EXPECT_GT((int)valueMetrics.data_size(), 1);

    auto data = valueMetrics.data(0);
    EXPECT_EQ(android::util::TEMPERATURE, data.dimensions_in_what().field());
    EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
    EXPECT_EQ(2 /* sensor name field */,
              data.dimensions_in_what().value_tuple().dimensions_value(0).field());
    EXPECT_FALSE(data.dimensions_in_what().value_tuple().dimensions_value(0).value_str().empty());
    EXPECT_EQ(3, data.bucket_info_size());

    EXPECT_EQ(baseTimeNs + 2 * bucketSizeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 3 * bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());
    EXPECT_TRUE(data.bucket_info(0).has_value());

    EXPECT_EQ(baseTimeNs + 8 * bucketSizeNs, data.bucket_info(1).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 9 * bucketSizeNs, data.bucket_info(1).end_bucket_elapsed_nanos());
    EXPECT_TRUE(data.bucket_info(1).has_value());

    EXPECT_EQ(baseTimeNs + 9 * bucketSizeNs, data.bucket_info(2).start_bucket_elapsed_nanos());
    EXPECT_EQ(baseTimeNs + 10 * bucketSizeNs, data.bucket_info(2).end_bucket_elapsed_nanos());
    EXPECT_TRUE(data.bucket_info(2).has_value());
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif

}  // namespace statsd
}  // namespace os
}  // namespace android
