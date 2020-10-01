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

#include "frameworks/base/cmds/statsd/src/statsd_metadata.pb.h"
#include "src/StatsLogProcessor.h"
#include "src/stats_log_util.h"
#include "tests/statsd_test_util.h"

#include <vector>

namespace android {
namespace os {
namespace statsd {

#ifdef __ANDROID__

namespace {

StatsdConfig CreateStatsdConfig(int num_buckets, int threshold, int refractory_period_sec) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT"); // LogEvent defaults to UID of root.
    auto wakelockAcquireMatcher = CreateAcquireWakelockAtomMatcher();

    *config.add_atom_matcher() = wakelockAcquireMatcher;

    auto countMetric = config.add_count_metric();
    countMetric->set_id(123456);
    countMetric->set_what(wakelockAcquireMatcher.id());
    *countMetric->mutable_dimensions_in_what() = CreateAttributionUidDimensions(
            util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    countMetric->set_bucket(FIVE_MINUTES);

    auto alert = config.add_alert();
    alert->set_id(StringToId("alert"));
    alert->set_metric_id(123456);
    alert->set_num_buckets(num_buckets);
    alert->set_refractory_period_secs(refractory_period_sec);
    alert->set_trigger_if_sum_gt(threshold);
    return config;
}

}  // namespace

TEST(AnomalyDetectionE2eTest, TestSlicedCountMetric_single_bucket) {
    const int num_buckets = 1;
    const int threshold = 3;
    const int refractory_period_sec = 10;
    auto config = CreateStatsdConfig(num_buckets, threshold, refractory_period_sec);
    const uint64_t alert_id = config.alert(0).id();

    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(config.count_metric(0).bucket()) * 1000000;

    ConfigKey cfgKey;
    auto processor = CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);
    ASSERT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());
    ASSERT_EQ(1u, processor->mMetricsManagers.begin()->second->mAllAnomalyTrackers.size());

    sp<AnomalyTracker> anomalyTracker =
            processor->mMetricsManagers.begin()->second->mAllAnomalyTrackers[0];

    std::vector<int> attributionUids1 = {111};
    std::vector<string> attributionTags1 = {"App1"};
    std::vector<int> attributionUids2 = {111, 222};
    std::vector<string> attributionTags2 = {"App1", "GMSCoreModule1"};
    std::vector<int> attributionUids3 = {111, 333};
    std::vector<string> attributionTags3 = {"App1", "App3"};
    std::vector<int> attributionUids4 = {222, 333};
    std::vector<string> attributionTags4 = {"GMSCoreModule1", "App3"};
    std::vector<int> attributionUids5 = {222};
    std::vector<string> attributionTags5 = {"GMSCoreModule1"};

    FieldValue fieldValue1(Field(util::WAKELOCK_STATE_CHANGED, (int32_t)0x02010101),
                           Value((int32_t)111));
    HashableDimensionKey whatKey1({fieldValue1});
    MetricDimensionKey dimensionKey1(whatKey1, DEFAULT_DIMENSION_KEY);

    FieldValue fieldValue2(Field(util::WAKELOCK_STATE_CHANGED, (int32_t)0x02010101),
                           Value((int32_t)222));
    HashableDimensionKey whatKey2({fieldValue2});
    MetricDimensionKey dimensionKey2(whatKey2, DEFAULT_DIMENSION_KEY);

    auto event = CreateAcquireWakelockEvent(bucketStartTimeNs + 2, attributionUids1,
                                            attributionTags1, "wl1");
    processor->OnLogEvent(event.get());
    EXPECT_EQ(0u, anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    event = CreateAcquireWakelockEvent(bucketStartTimeNs + 2, attributionUids4, attributionTags4,
                                       "wl2");
    processor->OnLogEvent(event.get());
    EXPECT_EQ(0u, anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey2));

    event = CreateAcquireWakelockEvent(bucketStartTimeNs + 3, attributionUids2, attributionTags2,
                                       "wl1");
    processor->OnLogEvent(event.get());
    EXPECT_EQ(0u, anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    event = CreateAcquireWakelockEvent(bucketStartTimeNs + 3, attributionUids5, attributionTags5,
                                       "wl2");
    processor->OnLogEvent(event.get());
    EXPECT_EQ(0u, anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey2));

    event = CreateAcquireWakelockEvent(bucketStartTimeNs + 4, attributionUids3, attributionTags3,
                                       "wl1");
    processor->OnLogEvent(event.get());
    EXPECT_EQ(0u, anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    event = CreateAcquireWakelockEvent(bucketStartTimeNs + 4, attributionUids5, attributionTags5,
                                       "wl2");
    processor->OnLogEvent(event.get());
    EXPECT_EQ(0u, anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey2));

    // Fired alarm and refractory period end timestamp updated.
    event = CreateAcquireWakelockEvent(bucketStartTimeNs + 5, attributionUids1, attributionTags1,
                                       "wl1");
    processor->OnLogEvent(event.get());
    EXPECT_EQ(refractory_period_sec + bucketStartTimeNs / NS_PER_SEC + 1,
              anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    event = CreateAcquireWakelockEvent(bucketStartTimeNs + 100, attributionUids1, attributionTags1,
                                       "wl1");
    processor->OnLogEvent(event.get());
    EXPECT_EQ(refractory_period_sec + bucketStartTimeNs / NS_PER_SEC + 1,
              anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    event = CreateAcquireWakelockEvent(bucketStartTimeNs + bucketSizeNs - 1, attributionUids1,
                                       attributionTags1, "wl1");
    processor->OnLogEvent(event.get());
    EXPECT_EQ(refractory_period_sec + (bucketStartTimeNs + bucketSizeNs - 1) / NS_PER_SEC + 1,
              anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    event = CreateAcquireWakelockEvent(bucketStartTimeNs + bucketSizeNs + 1, attributionUids1,
                                       attributionTags1, "wl1");
    processor->OnLogEvent(event.get());
    EXPECT_EQ(refractory_period_sec + (bucketStartTimeNs + bucketSizeNs - 1) / NS_PER_SEC + 1,
              anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    event = CreateAcquireWakelockEvent(bucketStartTimeNs + bucketSizeNs + 1, attributionUids4,
                                       attributionTags4, "wl2");
    processor->OnLogEvent(event.get());
    EXPECT_EQ(0u, anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey2));

    event = CreateAcquireWakelockEvent(bucketStartTimeNs + bucketSizeNs + 2, attributionUids5,
                                       attributionTags5, "wl2");
    processor->OnLogEvent(event.get());
    EXPECT_EQ(0u, anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey2));

    event = CreateAcquireWakelockEvent(bucketStartTimeNs + bucketSizeNs + 3, attributionUids5,
                                       attributionTags5, "wl2");
    processor->OnLogEvent(event.get());
    EXPECT_EQ(0u, anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey2));

    event = CreateAcquireWakelockEvent(bucketStartTimeNs + bucketSizeNs + 4, attributionUids5,
                                       attributionTags5, "wl2");
    processor->OnLogEvent(event.get());
    EXPECT_EQ(refractory_period_sec + (bucketStartTimeNs + bucketSizeNs + 4) / NS_PER_SEC + 1,
              anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey2));
}

TEST(AnomalyDetectionE2eTest, TestSlicedCountMetric_multiple_buckets) {
    const int num_buckets = 3;
    const int threshold = 3;
    const int refractory_period_sec = 10;
    auto config = CreateStatsdConfig(num_buckets, threshold, refractory_period_sec);
    const uint64_t alert_id = config.alert(0).id();

    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(config.count_metric(0).bucket()) * 1000000;

    ConfigKey cfgKey;
    auto processor = CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);
    ASSERT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());
    ASSERT_EQ(1u, processor->mMetricsManagers.begin()->second->mAllAnomalyTrackers.size());

    sp<AnomalyTracker> anomalyTracker =
            processor->mMetricsManagers.begin()->second->mAllAnomalyTrackers[0];

    std::vector<int> attributionUids1 = {111};
    std::vector<string> attributionTags1 = {"App1"};
    std::vector<int> attributionUids2 = {111, 222};
    std::vector<string> attributionTags2 = {"App1", "GMSCoreModule1"};

    FieldValue fieldValue1(Field(util::WAKELOCK_STATE_CHANGED, (int32_t)0x02010101),
                           Value((int32_t)111));
    HashableDimensionKey whatKey1({fieldValue1});
    MetricDimensionKey dimensionKey1(whatKey1, DEFAULT_DIMENSION_KEY);

    auto event = CreateAcquireWakelockEvent(bucketStartTimeNs + 2, attributionUids1,
                                            attributionTags1, "wl1");
    processor->OnLogEvent(event.get());
    EXPECT_EQ(0u, anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    event = CreateAcquireWakelockEvent(bucketStartTimeNs + 3, attributionUids2, attributionTags2,
                                       "wl1");
    processor->OnLogEvent(event.get());
    EXPECT_EQ(0u, anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    // Fired alarm and refractory period end timestamp updated.
    event = CreateAcquireWakelockEvent(bucketStartTimeNs + 4, attributionUids1, attributionTags1,
                                       "wl1");
    processor->OnLogEvent(event.get());
    EXPECT_EQ(0u, anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    event = CreateAcquireWakelockEvent(bucketStartTimeNs + bucketSizeNs + 1, attributionUids1,
                                       attributionTags1, "wl1");
    processor->OnLogEvent(event.get());
    EXPECT_EQ(refractory_period_sec + (bucketStartTimeNs + bucketSizeNs + 1) / NS_PER_SEC + 1,
              anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    event = CreateAcquireWakelockEvent(bucketStartTimeNs + bucketSizeNs + 2, attributionUids2,
                                       attributionTags2, "wl1");
    processor->OnLogEvent(event.get());
    EXPECT_EQ(refractory_period_sec + (bucketStartTimeNs + bucketSizeNs + 1) / NS_PER_SEC + 1,
              anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    event = CreateAcquireWakelockEvent(bucketStartTimeNs + 3 * bucketSizeNs + 1, attributionUids2,
                                       attributionTags2, "wl1");
    processor->OnLogEvent(event.get());
    EXPECT_EQ(refractory_period_sec + (bucketStartTimeNs + bucketSizeNs + 1) / NS_PER_SEC + 1,
              anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    event = CreateAcquireWakelockEvent(bucketStartTimeNs + 3 * bucketSizeNs + 2, attributionUids2,
                                       attributionTags2, "wl1");
    processor->OnLogEvent(event.get());
    EXPECT_EQ(refractory_period_sec + (bucketStartTimeNs + 3 * bucketSizeNs + 2) / NS_PER_SEC + 1,
              anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));
}

TEST(AnomalyDetectionE2eTest, TestCountMetric_save_refractory_to_disk_no_data_written) {
    const int num_buckets = 1;
    const int threshold = 0;
    const int refractory_period_sec = 86400 * 365; // 1 year
    auto config = CreateStatsdConfig(num_buckets, threshold, refractory_period_sec);
    const int64_t alert_id = config.alert(0).id();

    int64_t bucketStartTimeNs = 10000000000;

    int configUid = 2000;
    int64_t configId = 1000;
    ConfigKey cfgKey(configUid, configId);
    auto processor = CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);
    ASSERT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());
    ASSERT_EQ(1u, processor->mMetricsManagers.begin()->second->mAllAnomalyTrackers.size());

    metadata::StatsMetadataList result;
    int64_t mockWallClockNs = 1584991200 * NS_PER_SEC;
    int64_t mockElapsedTimeNs = bucketStartTimeNs + 5000 * NS_PER_SEC;
    processor->WriteMetadataToProto(mockWallClockNs, mockElapsedTimeNs, &result);

    ASSERT_EQ(result.stats_metadata_size(), 0);
}

TEST(AnomalyDetectionE2eTest, TestCountMetric_save_refractory_to_disk) {
    const int num_buckets = 1;
    const int threshold = 0;
    const int refractory_period_sec = 86400 * 365; // 1 year
    auto config = CreateStatsdConfig(num_buckets, threshold, refractory_period_sec);
    const int64_t alert_id = config.alert(0).id();

    int64_t bucketStartTimeNs = 10000000000;

    int configUid = 2000;
    int64_t configId = 1000;
    ConfigKey cfgKey(configUid, configId);
    auto processor = CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);
    ASSERT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());
    ASSERT_EQ(1u, processor->mMetricsManagers.begin()->second->mAllAnomalyTrackers.size());

    sp<AnomalyTracker> anomalyTracker =
            processor->mMetricsManagers.begin()->second->mAllAnomalyTrackers[0];

    std::vector<int> attributionUids1 = {111};
    std::vector<string> attributionTags1 = {"App1"};
    std::vector<int> attributionUids2 = {111, 222};
    std::vector<string> attributionTags2 = {"App1", "GMSCoreModule1"};

    FieldValue fieldValue1(Field(util::WAKELOCK_STATE_CHANGED, (int32_t)0x02010101),
                           Value((int32_t)111));
    HashableDimensionKey whatKey1({fieldValue1});
    MetricDimensionKey dimensionKey1(whatKey1, DEFAULT_DIMENSION_KEY);

    auto event = CreateAcquireWakelockEvent(bucketStartTimeNs + 2, attributionUids1,
                                            attributionTags1, "wl1");
    processor->OnLogEvent(event.get());
    EXPECT_EQ(refractory_period_sec + (bucketStartTimeNs + 2) / NS_PER_SEC + 1,
              anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    metadata::StatsMetadataList result;
    int64_t mockWallClockNs = 1584991200 * NS_PER_SEC;
    int64_t mockElapsedTimeNs = bucketStartTimeNs + 5000 * NS_PER_SEC;
    processor->WriteMetadataToProto(mockWallClockNs, mockElapsedTimeNs, &result);

    metadata::StatsMetadata statsMetadata = result.stats_metadata(0);
    ASSERT_EQ(result.stats_metadata_size(), 1);
    EXPECT_EQ(statsMetadata.config_key().config_id(), configId);
    EXPECT_EQ(statsMetadata.config_key().uid(), configUid);

    metadata::AlertMetadata alertMetadata = statsMetadata.alert_metadata(0);
    ASSERT_EQ(statsMetadata.alert_metadata_size(), 1);
    EXPECT_EQ(alertMetadata.alert_id(), alert_id);
    metadata::AlertDimensionKeyedData keyedData = alertMetadata.alert_dim_keyed_data(0);
    ASSERT_EQ(alertMetadata.alert_dim_keyed_data_size(), 1);
    EXPECT_EQ(keyedData.last_refractory_ends_sec(),
              anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1) -
              mockElapsedTimeNs / NS_PER_SEC +
              mockWallClockNs / NS_PER_SEC);

    metadata::MetricDimensionKey metadataDimKey = keyedData.dimension_key();
    metadata::FieldValue dimKeyInWhat = metadataDimKey.dimension_key_in_what(0);
    EXPECT_EQ(dimKeyInWhat.field().tag(), fieldValue1.mField.getTag());
    EXPECT_EQ(dimKeyInWhat.field().field(), fieldValue1.mField.getField());
    EXPECT_EQ(dimKeyInWhat.value_int(), fieldValue1.mValue.int_value);
}

TEST(AnomalyDetectionE2eTest, TestCountMetric_load_refractory_from_disk) {
    const int num_buckets = 1;
    const int threshold = 0;
    const int refractory_period_sec = 86400 * 365; // 1 year
    auto config = CreateStatsdConfig(num_buckets, threshold, refractory_period_sec);
    const int64_t alert_id = config.alert(0).id();

    int64_t bucketStartTimeNs = 10000000000;

    int configUid = 2000;
    int64_t configId = 1000;
    ConfigKey cfgKey(configUid, configId);
    auto processor = CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);
    ASSERT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());
    ASSERT_EQ(1u, processor->mMetricsManagers.begin()->second->mAllAnomalyTrackers.size());

    sp<AnomalyTracker> anomalyTracker =
            processor->mMetricsManagers.begin()->second->mAllAnomalyTrackers[0];

    std::vector<int> attributionUids1 = {111};
    std::vector<string> attributionTags1 = {"App1"};
    std::vector<int> attributionUids2 = {111, 222};
    std::vector<string> attributionTags2 = {"App1", "GMSCoreModule1"};

    FieldValue fieldValue1(Field(util::WAKELOCK_STATE_CHANGED, (int32_t)0x02010101),
                           Value((int32_t)111));
    HashableDimensionKey whatKey1({fieldValue1});
    MetricDimensionKey dimensionKey1(whatKey1, DEFAULT_DIMENSION_KEY);

    auto event = CreateAcquireWakelockEvent(bucketStartTimeNs + 2, attributionUids1,
                                            attributionTags1, "wl1");
    processor->OnLogEvent(event.get());
    EXPECT_EQ(refractory_period_sec + (bucketStartTimeNs + 2) / NS_PER_SEC + 1,
              anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    int64_t mockWallClockNs = 1584991200 * NS_PER_SEC;
    int64_t mockElapsedTimeNs = bucketStartTimeNs + 5000 * NS_PER_SEC;
    processor->SaveMetadataToDisk(mockWallClockNs, mockElapsedTimeNs);

    auto processor2 = CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);
    int64_t mockElapsedTimeSinceBoot = 10 * NS_PER_SEC;
    processor2->LoadMetadataFromDisk(mockWallClockNs, mockElapsedTimeSinceBoot);

    sp<AnomalyTracker> anomalyTracker2 =
                processor2->mMetricsManagers.begin()->second->mAllAnomalyTrackers[0];
    EXPECT_EQ(anomalyTracker2->getRefractoryPeriodEndsSec(dimensionKey1) -
              mockElapsedTimeSinceBoot / NS_PER_SEC,
              anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1) -
              mockElapsedTimeNs / NS_PER_SEC);
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif

}  // namespace statsd
}  // namespace os
}  // namespace android
