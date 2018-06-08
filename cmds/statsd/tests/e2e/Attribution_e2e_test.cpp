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

#include <iostream>
#include <vector>

namespace android {
namespace os {
namespace statsd {

#ifdef __ANDROID__

namespace {

StatsdConfig CreateStatsdConfig(const Position position) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT"); // LogEvent defaults to UID of root.
    auto wakelockAcquireMatcher = CreateAcquireWakelockAtomMatcher();
    auto attributionNodeMatcher =
        wakelockAcquireMatcher.mutable_simple_atom_matcher()->add_field_value_matcher();
    attributionNodeMatcher->set_field(1);
    attributionNodeMatcher->set_position(Position::ANY);
    auto uidMatcher = attributionNodeMatcher->mutable_matches_tuple()->add_field_value_matcher();
    uidMatcher->set_field(1);  // uid field.
    uidMatcher->set_eq_string("com.android.gmscore");

    *config.add_atom_matcher() = wakelockAcquireMatcher;

    auto countMetric = config.add_count_metric();
    countMetric->set_id(123456);
    countMetric->set_what(wakelockAcquireMatcher.id());
    *countMetric->mutable_dimensions_in_what() =
        CreateAttributionUidAndTagDimensions(
            android::util::WAKELOCK_STATE_CHANGED, {position});
    countMetric->set_bucket(FIVE_MINUTES);
    return config;
}

}  // namespace

TEST(AttributionE2eTest, TestAttributionMatchAndSliceByFirstUid) {
    auto config = CreateStatsdConfig(Position::FIRST);
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs =
        TimeUnitToBucketSizeInMillis(config.count_metric(0).bucket()) * 1000000;

    ConfigKey cfgKey;
    auto processor = CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);
    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());

    // Here it assumes that GMS core has two uids.
    processor->getUidMap()->updateMap(
            1, {222, 444, 111, 333}, {1, 1, 2, 2},
            {String16("com.android.gmscore"), String16("com.android.gmscore"), String16("app1"),
             String16("APP3")});

    // GMS core node is in the middle.
    std::vector<AttributionNodeInternal> attributions1 = {CreateAttribution(111, "App1"),
                                                          CreateAttribution(222, "GMSCoreModule1"),
                                                          CreateAttribution(333, "App3")};

    // GMS core node is the last one.
    std::vector<AttributionNodeInternal> attributions2 = {CreateAttribution(111, "App1"),
                                                          CreateAttribution(333, "App3"),
                                                          CreateAttribution(222, "GMSCoreModule1")};

    // GMS core node is the first one.
    std::vector<AttributionNodeInternal> attributions3 = {CreateAttribution(222, "GMSCoreModule1"),
                                                          CreateAttribution(333, "App3")};

    // Single GMS core node.
    std::vector<AttributionNodeInternal> attributions4 = {CreateAttribution(222, "GMSCoreModule1")};

    // GMS core has another uid.
    std::vector<AttributionNodeInternal> attributions5 = {CreateAttribution(111, "App1"),
                                                          CreateAttribution(444, "GMSCoreModule2"),
                                                          CreateAttribution(333, "App3")};

    // Multiple GMS core nodes.
    std::vector<AttributionNodeInternal> attributions6 = {CreateAttribution(444, "GMSCoreModule2"),
                                                          CreateAttribution(222, "GMSCoreModule1")};

    // No GMS core nodes.
    std::vector<AttributionNodeInternal> attributions7 = {CreateAttribution(111, "App1"),
                                                          CreateAttribution(333, "App3")};
    std::vector<AttributionNodeInternal> attributions8 = {CreateAttribution(111, "App1")};

    // GMS core node with isolated uid.
    const int isolatedUid = 666;
    std::vector<AttributionNodeInternal> attributions9 = {
            CreateAttribution(isolatedUid, "GMSCoreModule3")};

    std::vector<std::unique_ptr<LogEvent>> events;
    // Events 1~4 are in the 1st bucket.
    events.push_back(CreateAcquireWakelockEvent(
        attributions1, "wl1", bucketStartTimeNs + 2));
    events.push_back(CreateAcquireWakelockEvent(
        attributions2, "wl1", bucketStartTimeNs + 200));
    events.push_back(CreateAcquireWakelockEvent(
        attributions3, "wl1", bucketStartTimeNs + bucketSizeNs - 1));
    events.push_back(CreateAcquireWakelockEvent(
        attributions4, "wl1", bucketStartTimeNs + bucketSizeNs));

    // Events 5~8 are in the 3rd bucket.
    events.push_back(CreateAcquireWakelockEvent(
        attributions5, "wl2", bucketStartTimeNs + 2 * bucketSizeNs + 1));
    events.push_back(CreateAcquireWakelockEvent(
        attributions6, "wl2", bucketStartTimeNs + 2 * bucketSizeNs + 100));
    events.push_back(CreateAcquireWakelockEvent(
        attributions7, "wl2", bucketStartTimeNs + 3 * bucketSizeNs - 2));
    events.push_back(CreateAcquireWakelockEvent(
        attributions8, "wl2", bucketStartTimeNs + 3 * bucketSizeNs));
    events.push_back(CreateAcquireWakelockEvent(
        attributions9, "wl2", bucketStartTimeNs + 3 * bucketSizeNs + 1));
    events.push_back(CreateAcquireWakelockEvent(
        attributions9, "wl2", bucketStartTimeNs + 3 * bucketSizeNs + 100));
    events.push_back(CreateIsolatedUidChangedEvent(
        isolatedUid, 222, true/* is_create*/, bucketStartTimeNs + 3 * bucketSizeNs - 1));
    events.push_back(CreateIsolatedUidChangedEvent(
        isolatedUid, 222, false/* is_create*/, bucketStartTimeNs + 3 * bucketSizeNs + 10));

    sortLogEventsByTimestamp(&events);

    for (const auto& event : events) {
        processor->OnLogEvent(event.get());
    }
    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(cfgKey, bucketStartTimeNs + 4 * bucketSizeNs + 1, false, ADB_DUMP,
                            &buffer);
    EXPECT_TRUE(buffer.size() > 0);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);
    EXPECT_EQ(reports.reports_size(), 1);
    EXPECT_EQ(reports.reports(0).metrics_size(), 1);

    StatsLogReport::CountMetricDataWrapper countMetrics;
    sortMetricDataByDimensionsValue(reports.reports(0).metrics(0).count_metrics(), &countMetrics);
    EXPECT_EQ(countMetrics.data_size(), 4);

    auto data = countMetrics.data(0);
    ValidateAttributionUidAndTagDimension(
        data.dimensions_in_what(), android::util::WAKELOCK_STATE_CHANGED, 111,
            "App1");
    EXPECT_EQ(data.bucket_info_size(), 2);
    EXPECT_EQ(data.bucket_info(0).count(), 2);
    EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs);
    EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(), bucketStartTimeNs + bucketSizeNs);
    EXPECT_EQ(data.bucket_info(1).count(), 1);
    EXPECT_EQ(data.bucket_info(1).start_bucket_elapsed_nanos(), bucketStartTimeNs + 2 * bucketSizeNs);
    EXPECT_EQ(data.bucket_info(1).end_bucket_elapsed_nanos(), bucketStartTimeNs + 3 * bucketSizeNs);

    data = countMetrics.data(1);
    ValidateAttributionUidAndTagDimension(
        data.dimensions_in_what(), android::util::WAKELOCK_STATE_CHANGED, 222,
            "GMSCoreModule1");
    EXPECT_EQ(data.bucket_info_size(), 2);
    EXPECT_EQ(data.bucket_info(0).count(), 1);
    EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs);
    EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(), bucketStartTimeNs + bucketSizeNs);
    EXPECT_EQ(data.bucket_info(1).count(), 1);
    EXPECT_EQ(data.bucket_info(1).start_bucket_elapsed_nanos(), bucketStartTimeNs + bucketSizeNs);
    EXPECT_EQ(data.bucket_info(1).end_bucket_elapsed_nanos(), bucketStartTimeNs + 2 * bucketSizeNs);

    data = countMetrics.data(2);
    ValidateAttributionUidAndTagDimension(
        data.dimensions_in_what(), android::util::WAKELOCK_STATE_CHANGED, 222,
            "GMSCoreModule3");
    EXPECT_EQ(data.bucket_info_size(), 1);
    EXPECT_EQ(data.bucket_info(0).count(), 1);
    EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs + 3 * bucketSizeNs);
    EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(), bucketStartTimeNs + 4 * bucketSizeNs);

    data = countMetrics.data(3);
    ValidateAttributionUidAndTagDimension(
        data.dimensions_in_what(), android::util::WAKELOCK_STATE_CHANGED, 444,
            "GMSCoreModule2");
    EXPECT_EQ(data.bucket_info_size(), 1);
    EXPECT_EQ(data.bucket_info(0).count(), 1);
    EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs + 2 * bucketSizeNs);
    EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(), bucketStartTimeNs + 3 * bucketSizeNs);
}

TEST(AttributionE2eTest, TestAttributionMatchAndSliceByChain) {
    auto config = CreateStatsdConfig(Position::ALL);
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs =
        TimeUnitToBucketSizeInMillis(config.count_metric(0).bucket()) * 1000000;

    ConfigKey cfgKey;
    auto processor = CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);
    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());

    // Here it assumes that GMS core has two uids.
    processor->getUidMap()->updateMap(
            1, {222, 444, 111, 333}, {1, 1, 2, 2},
            {String16("com.android.gmscore"), String16("com.android.gmscore"), String16("app1"),
             String16("APP3")});

    // GMS core node is in the middle.
    std::vector<AttributionNodeInternal> attributions1 = {CreateAttribution(111, "App1"),
                                                          CreateAttribution(222, "GMSCoreModule1"),
                                                          CreateAttribution(333, "App3")};

    // GMS core node is the last one.
    std::vector<AttributionNodeInternal> attributions2 = {CreateAttribution(111, "App1"),
                                                          CreateAttribution(333, "App3"),
                                                          CreateAttribution(222, "GMSCoreModule1")};

    // GMS core node is the first one.
    std::vector<AttributionNodeInternal> attributions3 = {CreateAttribution(222, "GMSCoreModule1"),
                                                          CreateAttribution(333, "App3")};

    // Single GMS core node.
    std::vector<AttributionNodeInternal> attributions4 = {CreateAttribution(222, "GMSCoreModule1")};

    // GMS core has another uid.
    std::vector<AttributionNodeInternal> attributions5 = {CreateAttribution(111, "App1"),
                                                          CreateAttribution(444, "GMSCoreModule2"),
                                                          CreateAttribution(333, "App3")};

    // Multiple GMS core nodes.
    std::vector<AttributionNodeInternal> attributions6 = {CreateAttribution(444, "GMSCoreModule2"),
                                                          CreateAttribution(222, "GMSCoreModule1")};

    // No GMS core nodes.
    std::vector<AttributionNodeInternal> attributions7 = {CreateAttribution(111, "App1"),
                                                          CreateAttribution(333, "App3")};
    std::vector<AttributionNodeInternal> attributions8 = {CreateAttribution(111, "App1")};

    // GMS core node with isolated uid.
    const int isolatedUid = 666;
    std::vector<AttributionNodeInternal> attributions9 = {
            CreateAttribution(isolatedUid, "GMSCoreModule1")};

    std::vector<std::unique_ptr<LogEvent>> events;
    // Events 1~4 are in the 1st bucket.
    events.push_back(CreateAcquireWakelockEvent(
        attributions1, "wl1", bucketStartTimeNs + 2));
    events.push_back(CreateAcquireWakelockEvent(
        attributions2, "wl1", bucketStartTimeNs + 200));
    events.push_back(CreateAcquireWakelockEvent(
        attributions3, "wl1", bucketStartTimeNs + bucketSizeNs - 1));
    events.push_back(CreateAcquireWakelockEvent(
        attributions4, "wl1", bucketStartTimeNs + bucketSizeNs));

    // Events 5~8 are in the 3rd bucket.
    events.push_back(CreateAcquireWakelockEvent(
        attributions5, "wl2", bucketStartTimeNs + 2 * bucketSizeNs + 1));
    events.push_back(CreateAcquireWakelockEvent(
        attributions6, "wl2", bucketStartTimeNs + 2 * bucketSizeNs + 100));
    events.push_back(CreateAcquireWakelockEvent(
        attributions7, "wl2", bucketStartTimeNs + 3 * bucketSizeNs - 2));
    events.push_back(CreateAcquireWakelockEvent(
        attributions8, "wl2", bucketStartTimeNs + 3 * bucketSizeNs));
    events.push_back(CreateAcquireWakelockEvent(
        attributions9, "wl2", bucketStartTimeNs + 3 * bucketSizeNs + 1));
    events.push_back(CreateAcquireWakelockEvent(
        attributions9, "wl2", bucketStartTimeNs + 3 * bucketSizeNs + 100));
    events.push_back(CreateIsolatedUidChangedEvent(
        isolatedUid, 222, true/* is_create*/, bucketStartTimeNs + 3 * bucketSizeNs - 1));
    events.push_back(CreateIsolatedUidChangedEvent(
        isolatedUid, 222, false/* is_create*/, bucketStartTimeNs + 3 * bucketSizeNs + 10));

    sortLogEventsByTimestamp(&events);

    for (const auto& event : events) {
        processor->OnLogEvent(event.get());
    }
    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(cfgKey, bucketStartTimeNs + 4 * bucketSizeNs + 1, false, ADB_DUMP,
                            &buffer);
    EXPECT_TRUE(buffer.size() > 0);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);
    EXPECT_EQ(reports.reports_size(), 1);
    EXPECT_EQ(reports.reports(0).metrics_size(), 1);

    StatsLogReport::CountMetricDataWrapper countMetrics;
    sortMetricDataByDimensionsValue(reports.reports(0).metrics(0).count_metrics(), &countMetrics);
    EXPECT_EQ(countMetrics.data_size(), 6);

    auto data = countMetrics.data(0);
    ValidateAttributionUidAndTagDimension(
        data.dimensions_in_what(), android::util::WAKELOCK_STATE_CHANGED, 222, "GMSCoreModule1");
    EXPECT_EQ(2, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs,
              data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs,
              data.bucket_info(0).end_bucket_elapsed_nanos());
    EXPECT_EQ(1, data.bucket_info(1).count());
    EXPECT_EQ(bucketStartTimeNs + 3 * bucketSizeNs,
              data.bucket_info(1).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + 4 * bucketSizeNs,
              data.bucket_info(1).end_bucket_elapsed_nanos());

    data = countMetrics.data(1);
    ValidateUidDimension(
        data.dimensions_in_what(), 0, android::util::WAKELOCK_STATE_CHANGED, 222);
    ValidateAttributionUidAndTagDimension(
        data.dimensions_in_what(), 0, android::util::WAKELOCK_STATE_CHANGED, 222, "GMSCoreModule1");
    ValidateUidDimension(
        data.dimensions_in_what(), 1, android::util::WAKELOCK_STATE_CHANGED, 333);
    ValidateAttributionUidAndTagDimension(
        data.dimensions_in_what(), 1, android::util::WAKELOCK_STATE_CHANGED, 333, "App3");
    EXPECT_EQ(data.bucket_info_size(), 1);
    EXPECT_EQ(data.bucket_info(0).count(), 1);
    EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs);
    EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(), bucketStartTimeNs + bucketSizeNs);

    data = countMetrics.data(2);
    ValidateUidDimension(
        data.dimensions_in_what(), 0, android::util::WAKELOCK_STATE_CHANGED, 444);
    ValidateAttributionUidAndTagDimension(
        data.dimensions_in_what(), 0, android::util::WAKELOCK_STATE_CHANGED, 444, "GMSCoreModule2");
    ValidateUidDimension(
        data.dimensions_in_what(), 1, android::util::WAKELOCK_STATE_CHANGED, 222);
    ValidateAttributionUidAndTagDimension(
        data.dimensions_in_what(), 1, android::util::WAKELOCK_STATE_CHANGED, 222, "GMSCoreModule1");
    EXPECT_EQ(data.bucket_info_size(), 1);
    EXPECT_EQ(data.bucket_info(0).count(), 1);
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs,
              data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + 3 * bucketSizeNs,
              data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(3);
    ValidateUidDimension(
        data.dimensions_in_what(), 0, android::util::WAKELOCK_STATE_CHANGED, 111);
    ValidateAttributionUidAndTagDimension(
        data.dimensions_in_what(), 0, android::util::WAKELOCK_STATE_CHANGED, 111, "App1");
    ValidateUidDimension(
        data.dimensions_in_what(), 1, android::util::WAKELOCK_STATE_CHANGED, 222);
    ValidateAttributionUidAndTagDimension(
        data.dimensions_in_what(), 1, android::util::WAKELOCK_STATE_CHANGED, 222, "GMSCoreModule1");
    ValidateUidDimension(
        data.dimensions_in_what(), 2, android::util::WAKELOCK_STATE_CHANGED, 333);
    ValidateAttributionUidAndTagDimension(
        data.dimensions_in_what(), 2, android::util::WAKELOCK_STATE_CHANGED, 333, "App3");
    EXPECT_EQ(data.bucket_info_size(), 1);
    EXPECT_EQ(data.bucket_info(0).count(), 1);
    EXPECT_EQ(bucketStartTimeNs,
              data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs,
              data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(4);
    ValidateUidDimension(
        data.dimensions_in_what(), 0, android::util::WAKELOCK_STATE_CHANGED, 111);
    ValidateAttributionUidAndTagDimension(
        data.dimensions_in_what(), 0, android::util::WAKELOCK_STATE_CHANGED, 111, "App1");
    ValidateUidDimension(
        data.dimensions_in_what(), 1, android::util::WAKELOCK_STATE_CHANGED, 333);
    ValidateAttributionUidAndTagDimension(
        data.dimensions_in_what(), 1, android::util::WAKELOCK_STATE_CHANGED, 333, "App3");
    ValidateUidDimension(
        data.dimensions_in_what(), 2, android::util::WAKELOCK_STATE_CHANGED, 222);
    ValidateAttributionUidAndTagDimension(
        data.dimensions_in_what(), 2, android::util::WAKELOCK_STATE_CHANGED, 222, "GMSCoreModule1");
    EXPECT_EQ(data.bucket_info_size(), 1);
    EXPECT_EQ(data.bucket_info(0).count(), 1);
    EXPECT_EQ(bucketStartTimeNs,
              data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs,
              data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(5);
    ValidateUidDimension(
        data.dimensions_in_what(), 0, android::util::WAKELOCK_STATE_CHANGED, 111);
    ValidateAttributionUidAndTagDimension(
        data.dimensions_in_what(), 0, android::util::WAKELOCK_STATE_CHANGED, 111, "App1");
    ValidateUidDimension(
        data.dimensions_in_what(), 1, android::util::WAKELOCK_STATE_CHANGED, 444);
    ValidateAttributionUidAndTagDimension(
        data.dimensions_in_what(), 1, android::util::WAKELOCK_STATE_CHANGED, 444, "GMSCoreModule2");
    ValidateUidDimension(
        data.dimensions_in_what(), 2, android::util::WAKELOCK_STATE_CHANGED, 333);
    ValidateAttributionUidAndTagDimension(
        data.dimensions_in_what(), 2, android::util::WAKELOCK_STATE_CHANGED, 333, "App3");
    EXPECT_EQ(data.bucket_info_size(), 1);
    EXPECT_EQ(data.bucket_info(0).count(), 1);
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
