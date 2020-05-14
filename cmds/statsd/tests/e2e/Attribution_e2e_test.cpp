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
            util::WAKELOCK_STATE_CHANGED, {position});
    countMetric->set_bucket(FIVE_MINUTES);
    return config;
}

// GMS core node is in the middle.
std::vector<int> attributionUids1 = {111, 222, 333};
std::vector<string> attributionTags1 = {"App1", "GMSCoreModule1", "App3"};

// GMS core node is the last one.
std::vector<int> attributionUids2 = {111, 333, 222};
std::vector<string> attributionTags2 = {"App1", "App3", "GMSCoreModule1"};

// GMS core node is the first one.
std::vector<int> attributionUids3 = {222, 333};
std::vector<string> attributionTags3 = {"GMSCoreModule1", "App3"};

// Single GMS core node.
std::vector<int> attributionUids4 = {222};
std::vector<string> attributionTags4 = {"GMSCoreModule1"};

// GMS core has another uid.
std::vector<int> attributionUids5 = {111, 444, 333};
std::vector<string> attributionTags5 = {"App1", "GMSCoreModule2", "App3"};

// Multiple GMS core nodes.
std::vector<int> attributionUids6 = {444, 222};
std::vector<string> attributionTags6 = {"GMSCoreModule2", "GMSCoreModule1"};

// No GMS core nodes
std::vector<int> attributionUids7 = {111, 333};
std::vector<string> attributionTags7 = {"App1", "App3"};

std::vector<int> attributionUids8 = {111};
std::vector<string> attributionTags8 = {"App1"};

// GMS core node with isolated uid.
const int isolatedUid = 666;
std::vector<int> attributionUids9 = {isolatedUid};
std::vector<string> attributionTags9 = {"GMSCoreModule3"};

std::vector<int> attributionUids10 = {isolatedUid};
std::vector<string> attributionTags10 = {"GMSCoreModule1"};

}  // namespace

TEST(AttributionE2eTest, TestAttributionMatchAndSliceByFirstUid) {
    auto config = CreateStatsdConfig(Position::FIRST);
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(config.count_metric(0).bucket()) * 1000000;

    ConfigKey cfgKey;
    auto processor = CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);
    ASSERT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());

    // Here it assumes that GMS core has two uids.
    processor->getUidMap()->updateMap(
            1, {222, 444, 111, 333}, {1, 1, 2, 2},
            {String16("v1"), String16("v1"), String16("v2"), String16("v2")},
            {String16("com.android.gmscore"), String16("com.android.gmscore"), String16("app1"),
             String16("APP3")},
            {String16(""), String16(""), String16(""), String16("")});

    std::vector<std::unique_ptr<LogEvent>> events;
    // Events 1~4 are in the 1st bucket.
    events.push_back(CreateAcquireWakelockEvent(bucketStartTimeNs + 2, attributionUids1,
                                                attributionTags1, "wl1"));
    events.push_back(CreateAcquireWakelockEvent(bucketStartTimeNs + 200, attributionUids2,
                                                attributionTags2, "wl1"));
    events.push_back(CreateAcquireWakelockEvent(bucketStartTimeNs + bucketSizeNs - 1,
                                                attributionUids3, attributionTags3, "wl1"));
    events.push_back(CreateAcquireWakelockEvent(bucketStartTimeNs + bucketSizeNs, attributionUids4,
                                                attributionTags4, "wl1"));

    // Events 5~8 are in the 3rd bucket.
    events.push_back(CreateAcquireWakelockEvent(bucketStartTimeNs + 2 * bucketSizeNs + 1,
                                                attributionUids5, attributionTags5, "wl2"));
    events.push_back(CreateAcquireWakelockEvent(bucketStartTimeNs + 2 * bucketSizeNs + 100,
                                                attributionUids6, attributionTags6, "wl2"));
    events.push_back(CreateAcquireWakelockEvent(bucketStartTimeNs + 3 * bucketSizeNs - 2,
                                                attributionUids7, attributionTags7, "wl2"));
    events.push_back(CreateAcquireWakelockEvent(bucketStartTimeNs + 3 * bucketSizeNs,
                                                attributionUids8, attributionTags8, "wl2"));
    events.push_back(CreateAcquireWakelockEvent(bucketStartTimeNs + 3 * bucketSizeNs + 1,
                                                attributionUids9, attributionTags9, "wl2"));
    events.push_back(CreateAcquireWakelockEvent(bucketStartTimeNs + 3 * bucketSizeNs + 100,
                                                attributionUids9, attributionTags9, "wl2"));
    events.push_back(CreateIsolatedUidChangedEvent(bucketStartTimeNs + 3 * bucketSizeNs - 1, 222,
                                                   isolatedUid, true /*is_create*/));
    events.push_back(CreateIsolatedUidChangedEvent(bucketStartTimeNs + 3 * bucketSizeNs + 10, 222,
                                                   isolatedUid, false /*is_create*/));

    sortLogEventsByTimestamp(&events);

    for (const auto& event : events) {
        processor->OnLogEvent(event.get());
    }
    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(cfgKey, bucketStartTimeNs + 4 * bucketSizeNs + 1, false, true, ADB_DUMP,
                            FAST, &buffer);
    EXPECT_TRUE(buffer.size() > 0);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);
    ASSERT_EQ(reports.reports_size(), 1);
    ASSERT_EQ(reports.reports(0).metrics_size(), 1);

    StatsLogReport::CountMetricDataWrapper countMetrics;
    sortMetricDataByDimensionsValue(reports.reports(0).metrics(0).count_metrics(), &countMetrics);
    ASSERT_EQ(countMetrics.data_size(), 4);

    auto data = countMetrics.data(0);
    ValidateAttributionUidAndTagDimension(data.dimensions_in_what(),
                                          util::WAKELOCK_STATE_CHANGED, 111, "App1");
    ASSERT_EQ(data.bucket_info_size(), 2);
    EXPECT_EQ(data.bucket_info(0).count(), 2);
    EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs);
    EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(), bucketStartTimeNs + bucketSizeNs);
    EXPECT_EQ(data.bucket_info(1).count(), 1);
    EXPECT_EQ(data.bucket_info(1).start_bucket_elapsed_nanos(),
              bucketStartTimeNs + 2 * bucketSizeNs);
    EXPECT_EQ(data.bucket_info(1).end_bucket_elapsed_nanos(), bucketStartTimeNs + 3 * bucketSizeNs);

    data = countMetrics.data(1);
    ValidateAttributionUidAndTagDimension(data.dimensions_in_what(),
                                          util::WAKELOCK_STATE_CHANGED, 222,
                                          "GMSCoreModule1");
    ASSERT_EQ(data.bucket_info_size(), 2);
    EXPECT_EQ(data.bucket_info(0).count(), 1);
    EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs);
    EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(), bucketStartTimeNs + bucketSizeNs);
    EXPECT_EQ(data.bucket_info(1).count(), 1);
    EXPECT_EQ(data.bucket_info(1).start_bucket_elapsed_nanos(), bucketStartTimeNs + bucketSizeNs);
    EXPECT_EQ(data.bucket_info(1).end_bucket_elapsed_nanos(), bucketStartTimeNs + 2 * bucketSizeNs);

    data = countMetrics.data(2);
    ValidateAttributionUidAndTagDimension(data.dimensions_in_what(),
                                          util::WAKELOCK_STATE_CHANGED, 222,
                                          "GMSCoreModule3");
    ASSERT_EQ(data.bucket_info_size(), 1);
    EXPECT_EQ(data.bucket_info(0).count(), 1);
    EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(),
              bucketStartTimeNs + 3 * bucketSizeNs);
    EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(), bucketStartTimeNs + 4 * bucketSizeNs);

    data = countMetrics.data(3);
    ValidateAttributionUidAndTagDimension(data.dimensions_in_what(),
                                          util::WAKELOCK_STATE_CHANGED, 444,
                                          "GMSCoreModule2");
    ASSERT_EQ(data.bucket_info_size(), 1);
    EXPECT_EQ(data.bucket_info(0).count(), 1);
    EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(),
              bucketStartTimeNs + 2 * bucketSizeNs);
    EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(), bucketStartTimeNs + 3 * bucketSizeNs);
}

TEST(AttributionE2eTest, TestAttributionMatchAndSliceByChain) {
    auto config = CreateStatsdConfig(Position::ALL);
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(config.count_metric(0).bucket()) * 1000000;

    ConfigKey cfgKey;
    auto processor = CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);
    ASSERT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());

    // Here it assumes that GMS core has two uids.
    processor->getUidMap()->updateMap(
            1, {222, 444, 111, 333}, {1, 1, 2, 2},
            {String16("v1"), String16("v1"), String16("v2"), String16("v2")},
            {String16("com.android.gmscore"), String16("com.android.gmscore"), String16("app1"),
             String16("APP3")},
            {String16(""), String16(""), String16(""), String16("")});

    std::vector<std::unique_ptr<LogEvent>> events;
    // Events 1~4 are in the 1st bucket.
    events.push_back(CreateAcquireWakelockEvent(bucketStartTimeNs + 2, attributionUids1,
                                                attributionTags1, "wl1"));
    events.push_back(CreateAcquireWakelockEvent(bucketStartTimeNs + 200, attributionUids2,
                                                attributionTags2, "wl1"));
    events.push_back(CreateAcquireWakelockEvent(bucketStartTimeNs + bucketSizeNs - 1,
                                                attributionUids3, attributionTags3, "wl1"));
    events.push_back(CreateAcquireWakelockEvent(bucketStartTimeNs + bucketSizeNs, attributionUids4,
                                                attributionTags4, "wl1"));

    // Events 5~8 are in the 3rd bucket.
    events.push_back(CreateAcquireWakelockEvent(bucketStartTimeNs + 2 * bucketSizeNs + 1,
                                                attributionUids5, attributionTags5, "wl2"));
    events.push_back(CreateAcquireWakelockEvent(bucketStartTimeNs + 2 * bucketSizeNs + 100,
                                                attributionUids6, attributionTags6, "wl2"));
    events.push_back(CreateAcquireWakelockEvent(bucketStartTimeNs + 3 * bucketSizeNs - 2,
                                                attributionUids7, attributionTags7, "wl2"));
    events.push_back(CreateAcquireWakelockEvent(bucketStartTimeNs + 3 * bucketSizeNs,
                                                attributionUids8, attributionTags8, "wl2"));
    events.push_back(CreateAcquireWakelockEvent(bucketStartTimeNs + 3 * bucketSizeNs + 1,
                                                attributionUids10, attributionTags10, "wl2"));
    events.push_back(CreateAcquireWakelockEvent(bucketStartTimeNs + 3 * bucketSizeNs + 100,
                                                attributionUids10, attributionTags10, "wl2"));
    events.push_back(CreateIsolatedUidChangedEvent(bucketStartTimeNs + 3 * bucketSizeNs - 1, 222,
                                                   isolatedUid, true /*is_create*/));
    events.push_back(CreateIsolatedUidChangedEvent(bucketStartTimeNs + 3 * bucketSizeNs + 10, 222,
                                                   isolatedUid, false /*is_create*/));

    sortLogEventsByTimestamp(&events);

    for (const auto& event : events) {
        processor->OnLogEvent(event.get());
    }
    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(cfgKey, bucketStartTimeNs + 4 * bucketSizeNs + 1, false, true, ADB_DUMP,
                            FAST, &buffer);
    EXPECT_TRUE(buffer.size() > 0);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);
    ASSERT_EQ(reports.reports_size(), 1);
    ASSERT_EQ(reports.reports(0).metrics_size(), 1);

    StatsLogReport::CountMetricDataWrapper countMetrics;
    sortMetricDataByDimensionsValue(reports.reports(0).metrics(0).count_metrics(), &countMetrics);
    ASSERT_EQ(countMetrics.data_size(), 6);

    auto data = countMetrics.data(0);
    ValidateAttributionUidAndTagDimension(data.dimensions_in_what(),
                                          util::WAKELOCK_STATE_CHANGED, 222,
                                          "GMSCoreModule1");
    ASSERT_EQ(2, data.bucket_info_size());
    EXPECT_EQ(1, data.bucket_info(0).count());
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());
    EXPECT_EQ(1, data.bucket_info(1).count());
    EXPECT_EQ(bucketStartTimeNs + 3 * bucketSizeNs,
              data.bucket_info(1).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + 4 * bucketSizeNs, data.bucket_info(1).end_bucket_elapsed_nanos());

    data = countMetrics.data(1);
    ValidateUidDimension(data.dimensions_in_what(), 0, util::WAKELOCK_STATE_CHANGED, 222);
    ValidateAttributionUidAndTagDimension(data.dimensions_in_what(), 0,
                                          util::WAKELOCK_STATE_CHANGED, 222,
                                          "GMSCoreModule1");
    ValidateUidDimension(data.dimensions_in_what(), 1, util::WAKELOCK_STATE_CHANGED, 333);
    ValidateAttributionUidAndTagDimension(data.dimensions_in_what(), 1,
                                          util::WAKELOCK_STATE_CHANGED, 333, "App3");
    ASSERT_EQ(data.bucket_info_size(), 1);
    EXPECT_EQ(data.bucket_info(0).count(), 1);
    EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs);
    EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(), bucketStartTimeNs + bucketSizeNs);

    data = countMetrics.data(2);
    ValidateUidDimension(data.dimensions_in_what(), 0, util::WAKELOCK_STATE_CHANGED, 444);
    ValidateAttributionUidAndTagDimension(data.dimensions_in_what(), 0,
                                          util::WAKELOCK_STATE_CHANGED, 444,
                                          "GMSCoreModule2");
    ValidateUidDimension(data.dimensions_in_what(), 1, util::WAKELOCK_STATE_CHANGED, 222);
    ValidateAttributionUidAndTagDimension(data.dimensions_in_what(), 1,
                                          util::WAKELOCK_STATE_CHANGED, 222,
                                          "GMSCoreModule1");
    ASSERT_EQ(data.bucket_info_size(), 1);
    EXPECT_EQ(data.bucket_info(0).count(), 1);
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs,
              data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + 3 * bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(3);
    ValidateUidDimension(data.dimensions_in_what(), 0, util::WAKELOCK_STATE_CHANGED, 111);
    ValidateAttributionUidAndTagDimension(data.dimensions_in_what(), 0,
                                          util::WAKELOCK_STATE_CHANGED, 111, "App1");
    ValidateUidDimension(data.dimensions_in_what(), 1, util::WAKELOCK_STATE_CHANGED, 222);
    ValidateAttributionUidAndTagDimension(data.dimensions_in_what(), 1,
                                          util::WAKELOCK_STATE_CHANGED, 222,
                                          "GMSCoreModule1");
    ValidateUidDimension(data.dimensions_in_what(), 2, util::WAKELOCK_STATE_CHANGED, 333);
    ValidateAttributionUidAndTagDimension(data.dimensions_in_what(), 2,
                                          util::WAKELOCK_STATE_CHANGED, 333, "App3");
    ASSERT_EQ(data.bucket_info_size(), 1);
    EXPECT_EQ(data.bucket_info(0).count(), 1);
    EXPECT_EQ(bucketStartTimeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(4);
    ValidateUidDimension(data.dimensions_in_what(), 0, util::WAKELOCK_STATE_CHANGED, 111);
    ValidateAttributionUidAndTagDimension(data.dimensions_in_what(), 0,
                                          util::WAKELOCK_STATE_CHANGED, 111, "App1");
    ValidateUidDimension(data.dimensions_in_what(), 1, util::WAKELOCK_STATE_CHANGED, 333);
    ValidateAttributionUidAndTagDimension(data.dimensions_in_what(), 1,
                                          util::WAKELOCK_STATE_CHANGED, 333, "App3");
    ValidateUidDimension(data.dimensions_in_what(), 2, util::WAKELOCK_STATE_CHANGED, 222);
    ValidateAttributionUidAndTagDimension(data.dimensions_in_what(), 2,
                                          util::WAKELOCK_STATE_CHANGED, 222,
                                          "GMSCoreModule1");
    ASSERT_EQ(data.bucket_info_size(), 1);
    EXPECT_EQ(data.bucket_info(0).count(), 1);
    EXPECT_EQ(bucketStartTimeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());

    data = countMetrics.data(5);
    ValidateUidDimension(data.dimensions_in_what(), 0, util::WAKELOCK_STATE_CHANGED, 111);
    ValidateAttributionUidAndTagDimension(data.dimensions_in_what(), 0,
                                          util::WAKELOCK_STATE_CHANGED, 111, "App1");
    ValidateUidDimension(data.dimensions_in_what(), 1, util::WAKELOCK_STATE_CHANGED, 444);
    ValidateAttributionUidAndTagDimension(data.dimensions_in_what(), 1,
                                          util::WAKELOCK_STATE_CHANGED, 444,
                                          "GMSCoreModule2");
    ValidateUidDimension(data.dimensions_in_what(), 2, util::WAKELOCK_STATE_CHANGED, 333);
    ValidateAttributionUidAndTagDimension(data.dimensions_in_what(), 2,
                                          util::WAKELOCK_STATE_CHANGED, 333, "App3");
    ASSERT_EQ(data.bucket_info_size(), 1);
    EXPECT_EQ(data.bucket_info(0).count(), 1);
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs,
              data.bucket_info(0).start_bucket_elapsed_nanos());
    EXPECT_EQ(bucketStartTimeNs + 3 * bucketSizeNs, data.bucket_info(0).end_bucket_elapsed_nanos());
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif

}  // namespace statsd
}  // namespace os
}  // namespace android
