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

StatsdConfig CreateStatsdConfig(DurationMetric::AggregationType aggregationType) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT"); // LogEvent defaults to UID of root.
    *config.add_atom_matcher() = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = CreateAcquireWakelockAtomMatcher();
    *config.add_atom_matcher() = CreateReleaseWakelockAtomMatcher();

    auto screenIsOffPredicate = CreateScreenIsOffPredicate();
    *config.add_predicate() = screenIsOffPredicate;

    auto holdingWakelockPredicate = CreateHoldingWakelockPredicate();
    // The predicate is dimensioning by any attribution node and both by uid and tag.
    FieldMatcher dimensions = CreateAttributionUidAndTagDimensions(
            android::util::WAKELOCK_STATE_CHANGED, {Position::FIRST, Position::LAST});
    // Also slice by the wakelock tag
    dimensions.add_child()->set_field(3);  // The wakelock tag is set in field 3 of the wakelock.
    *holdingWakelockPredicate.mutable_simple_predicate()->mutable_dimensions() = dimensions;
    *config.add_predicate() = holdingWakelockPredicate;

    auto durationMetric = config.add_duration_metric();
    durationMetric->set_id(StringToId("WakelockDuration"));
    durationMetric->set_what(holdingWakelockPredicate.id());
    durationMetric->set_condition(screenIsOffPredicate.id());
    durationMetric->set_aggregation_type(aggregationType);
    // The metric is dimensioning by first attribution node and only by uid.
    *durationMetric->mutable_dimensions_in_what() =
        CreateAttributionUidDimensions(
            android::util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    durationMetric->set_bucket(FIVE_MINUTES);
    return config;
}

std::vector<int> attributionUids1 = {111, 222, 222};
std::vector<string> attributionTags1 = {"App1", "GMSCoreModule1", "GMSCoreModule2"};

std::vector<int> attributionUids2 = {111, 222, 222};
std::vector<string> attributionTags2 = {"App2", "GMSCoreModule1", "GMSCoreModule2"};

/*
Events:
Screen off is met from (200ns,1 min+500ns].
Acquire event for wl1 from 2ns to 1min+2ns
Acquire event for wl2 from 1min-10ns to 2min-15ns
*/
void FeedEvents(StatsdConfig config, sp<StatsLogProcessor> processor) {
    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.duration_metric(0).bucket()) * 1000000LL;

    auto screenTurnedOnEvent = CreateScreenStateChangedEvent(
            bucketStartTimeNs + 1, android::view::DisplayStateEnum::DISPLAY_STATE_ON);
    auto screenTurnedOffEvent = CreateScreenStateChangedEvent(
            bucketStartTimeNs + 200, android::view::DisplayStateEnum::DISPLAY_STATE_OFF);
    auto screenTurnedOnEvent2 =
            CreateScreenStateChangedEvent(bucketStartTimeNs + bucketSizeNs + 500,
                                          android::view::DisplayStateEnum::DISPLAY_STATE_ON);

    auto acquireEvent1 = CreateAcquireWakelockEvent(bucketStartTimeNs + 2, attributionUids1,
                                                    attributionTags1, "wl1");
    auto releaseEvent1 = CreateReleaseWakelockEvent(bucketStartTimeNs + bucketSizeNs + 2,
                                                    attributionUids1, attributionTags1, "wl1");
    auto acquireEvent2 = CreateAcquireWakelockEvent(bucketStartTimeNs + bucketSizeNs - 10,
                                                    attributionUids2, attributionTags2, "wl2");
    auto releaseEvent2 = CreateReleaseWakelockEvent(bucketStartTimeNs + 2 * bucketSizeNs - 15,
                                                    attributionUids2, attributionTags2, "wl2");

    std::vector<std::unique_ptr<LogEvent>> events;

    events.push_back(std::move(screenTurnedOnEvent));
    events.push_back(std::move(screenTurnedOffEvent));
    events.push_back(std::move(screenTurnedOnEvent2));
    events.push_back(std::move(acquireEvent1));
    events.push_back(std::move(acquireEvent2));
    events.push_back(std::move(releaseEvent1));
    events.push_back(std::move(releaseEvent2));

    sortLogEventsByTimestamp(&events);

    for (const auto& event : events) {
        processor->OnLogEvent(event.get());
    }
}

}  // namespace

TEST(WakelockDurationE2eTest, TestAggregatedPredicateDimensionsForSumDuration1) {
    ConfigKey cfgKey;
    auto config = CreateStatsdConfig(DurationMetric::SUM);
    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.duration_metric(0).bucket()) * 1000000LL;
    auto processor = CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);
    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());
    FeedEvents(config, processor);
    vector<uint8_t> buffer;
    ConfigMetricsReportList reports;
    processor->onDumpReport(cfgKey, bucketStartTimeNs + 2 * bucketSizeNs - 1, false, true, ADB_DUMP,
                            FAST, &buffer);
    EXPECT_TRUE(buffer.size() > 0);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);

    EXPECT_EQ(reports.reports_size(), 1);
    EXPECT_EQ(reports.reports(0).metrics_size(), 1);
    // Only 1 dimension output. The tag dimension in the predicate has been aggregated.
    EXPECT_EQ(reports.reports(0).metrics(0).duration_metrics().data_size(), 1);

    auto data = reports.reports(0).metrics(0).duration_metrics().data(0);
    // Validate dimension value.
    ValidateAttributionUidDimension(data.dimensions_in_what(),
                                    android::util::WAKELOCK_STATE_CHANGED, 111);
    // Validate bucket info.
    EXPECT_EQ(reports.reports(0).metrics(0).duration_metrics().data(0).bucket_info_size(), 1);
    data = reports.reports(0).metrics(0).duration_metrics().data(0);
    // The wakelock holding interval starts from the screen off event and to the end of the 1st
    // bucket.
    EXPECT_EQ((unsigned long long)data.bucket_info(0).duration_nanos(), bucketSizeNs - 200);
}

TEST(WakelockDurationE2eTest, TestAggregatedPredicateDimensionsForSumDuration2) {
    ConfigKey cfgKey;
    auto config = CreateStatsdConfig(DurationMetric::SUM);
    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.duration_metric(0).bucket()) * 1000000LL;
    auto processor = CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);
    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());
    FeedEvents(config, processor);
    vector<uint8_t> buffer;
    ConfigMetricsReportList reports;
    processor->onDumpReport(cfgKey, bucketStartTimeNs + 2 * bucketSizeNs + 1, false, true, ADB_DUMP,
                            FAST, &buffer);
    EXPECT_TRUE(buffer.size() > 0);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);
    EXPECT_EQ(reports.reports_size(), 1);
    EXPECT_EQ(reports.reports(0).metrics_size(), 1);
    EXPECT_EQ(reports.reports(0).metrics(0).duration_metrics().data_size(), 1);
    // Dump the report after the end of 2nd bucket.
    EXPECT_EQ(reports.reports(0).metrics(0).duration_metrics().data(0).bucket_info_size(), 2);
    auto data = reports.reports(0).metrics(0).duration_metrics().data(0);
    // Validate dimension value.
    ValidateAttributionUidDimension(data.dimensions_in_what(),
                                    android::util::WAKELOCK_STATE_CHANGED, 111);
    // Two output buckets.
    // The wakelock holding interval in the 1st bucket starts from the screen off event and to
    // the end of the 1st bucket.
    EXPECT_EQ((unsigned long long)data.bucket_info(0).duration_nanos(),
              bucketStartTimeNs + bucketSizeNs - (bucketStartTimeNs + 200));
    // The wakelock holding interval in the 2nd bucket starts at the beginning of the bucket and
    // ends at the second screen on event.
    EXPECT_EQ((unsigned long long)data.bucket_info(1).duration_nanos(), 500UL);
}

TEST(WakelockDurationE2eTest, TestAggregatedPredicateDimensionsForSumDuration3) {
    ConfigKey cfgKey;
    auto config = CreateStatsdConfig(DurationMetric::SUM);
    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.duration_metric(0).bucket()) * 1000000LL;
    auto processor = CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);
    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());
    FeedEvents(config, processor);
    vector<uint8_t> buffer;
    ConfigMetricsReportList reports;

    std::vector<std::unique_ptr<LogEvent>> events;
    events.push_back(
            CreateScreenStateChangedEvent(bucketStartTimeNs + 2 * bucketSizeNs + 90,
                                          android::view::DisplayStateEnum::DISPLAY_STATE_OFF));
    events.push_back(CreateAcquireWakelockEvent(bucketStartTimeNs + 2 * bucketSizeNs + 100,
                                                attributionUids1, attributionTags1, "wl3"));
    events.push_back(CreateReleaseWakelockEvent(bucketStartTimeNs + 5 * bucketSizeNs + 100,
                                                attributionUids1, attributionTags1, "wl3"));
    sortLogEventsByTimestamp(&events);
    for (const auto& event : events) {
        processor->OnLogEvent(event.get());
    }

    processor->onDumpReport(cfgKey, bucketStartTimeNs + 6 * bucketSizeNs + 1, false, true, ADB_DUMP,
                            FAST, &buffer);
    EXPECT_TRUE(buffer.size() > 0);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);
    EXPECT_EQ(reports.reports_size(), 1);
    EXPECT_EQ(reports.reports(0).metrics_size(), 1);
    EXPECT_EQ(reports.reports(0).metrics(0).duration_metrics().data_size(), 1);
    EXPECT_EQ(reports.reports(0).metrics(0).duration_metrics().data(0).bucket_info_size(), 6);
    auto data = reports.reports(0).metrics(0).duration_metrics().data(0);
    ValidateAttributionUidDimension(data.dimensions_in_what(),
                                    android::util::WAKELOCK_STATE_CHANGED, 111);
    // The last wakelock holding spans 4 buckets.
    EXPECT_EQ((unsigned long long)data.bucket_info(2).duration_nanos(), bucketSizeNs - 100);
    EXPECT_EQ((unsigned long long)data.bucket_info(3).duration_nanos(), bucketSizeNs);
    EXPECT_EQ((unsigned long long)data.bucket_info(4).duration_nanos(), bucketSizeNs);
    EXPECT_EQ((unsigned long long)data.bucket_info(5).duration_nanos(), 100UL);
}

TEST(WakelockDurationE2eTest, TestAggregatedPredicateDimensionsForMaxDuration1) {
    ConfigKey cfgKey;
    auto config = CreateStatsdConfig(DurationMetric::MAX_SPARSE);
    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.duration_metric(0).bucket()) * 1000000LL;
    auto processor = CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);
    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());
    FeedEvents(config, processor);
    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(cfgKey, bucketStartTimeNs + 2 * bucketSizeNs - 1, false, true, ADB_DUMP,
                            FAST, &buffer);
    EXPECT_TRUE(buffer.size() > 0);

    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);

    EXPECT_EQ(reports.reports_size(), 1);

    // When using ProtoOutputStream, if nothing written to a sub msg, it won't be treated as
    // one. It was previsouly 1 because we had a fake onDumpReport which calls add_metric() by
    // itself.
    EXPECT_EQ(1, reports.reports(0).metrics_size());
    EXPECT_EQ(0, reports.reports(0).metrics(0).duration_metrics().data_size());
}

TEST(WakelockDurationE2eTest, TestAggregatedPredicateDimensionsForMaxDuration2) {
    ConfigKey cfgKey;
    auto config = CreateStatsdConfig(DurationMetric::MAX_SPARSE);
    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.duration_metric(0).bucket()) * 1000000LL;
    auto processor = CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);
    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());
    FeedEvents(config, processor);
    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(cfgKey, bucketStartTimeNs + 2 * bucketSizeNs + 1, false, true, ADB_DUMP,
                            FAST, &buffer);
    EXPECT_TRUE(buffer.size() > 0);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);
    EXPECT_EQ(reports.reports_size(), 1);
    EXPECT_EQ(reports.reports(0).metrics_size(), 1);
    EXPECT_EQ(reports.reports(0).metrics(0).duration_metrics().data_size(), 1);
    // Dump the report after the end of 2nd bucket. One dimension with one bucket.
    EXPECT_EQ(reports.reports(0).metrics(0).duration_metrics().data(0).bucket_info_size(), 1);
    auto data = reports.reports(0).metrics(0).duration_metrics().data(0);
    // Validate dimension value.
    ValidateAttributionUidDimension(data.dimensions_in_what(),
                                    android::util::WAKELOCK_STATE_CHANGED, 111);
    // The max is acquire event for wl1 to screen off start.
    EXPECT_EQ((unsigned long long)data.bucket_info(0).duration_nanos(), bucketSizeNs + 2 - 200);
}

TEST(WakelockDurationE2eTest, TestAggregatedPredicateDimensionsForMaxDuration3) {
    ConfigKey cfgKey;
    auto config = CreateStatsdConfig(DurationMetric::MAX_SPARSE);
    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.duration_metric(0).bucket()) * 1000000LL;
    auto processor = CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);
    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());
    FeedEvents(config, processor);
    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;

    std::vector<std::unique_ptr<LogEvent>> events;
    events.push_back(
            CreateScreenStateChangedEvent(bucketStartTimeNs + 2 * bucketSizeNs + 90,
                                          android::view::DisplayStateEnum::DISPLAY_STATE_OFF));
    events.push_back(CreateAcquireWakelockEvent(bucketStartTimeNs + 2 * bucketSizeNs + 100,
                                                attributionUids1, attributionTags1, "wl3"));
    events.push_back(CreateReleaseWakelockEvent(bucketStartTimeNs + 5 * bucketSizeNs + 100,
                                                attributionUids1, attributionTags1, "wl3"));
    sortLogEventsByTimestamp(&events);
    for (const auto& event : events) {
        processor->OnLogEvent(event.get());
    }

    processor->onDumpReport(cfgKey, bucketStartTimeNs + 6 * bucketSizeNs + 1, false, true, ADB_DUMP,
                            FAST, &buffer);
    EXPECT_TRUE(buffer.size() > 0);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);
    EXPECT_EQ(reports.reports_size(), 1);
    EXPECT_EQ(reports.reports(0).metrics_size(), 1);
    EXPECT_EQ(reports.reports(0).metrics(0).duration_metrics().data_size(), 1);
    EXPECT_EQ(reports.reports(0).metrics(0).duration_metrics().data(0).bucket_info_size(), 2);
    auto data = reports.reports(0).metrics(0).duration_metrics().data(0);
    ValidateAttributionUidDimension(data.dimensions_in_what(),
                                    android::util::WAKELOCK_STATE_CHANGED, 111);
    // The last wakelock holding spans 4 buckets.
    EXPECT_EQ((unsigned long long)data.bucket_info(1).duration_nanos(), 3 * bucketSizeNs);
    EXPECT_EQ((unsigned long long)data.bucket_info(1).start_bucket_elapsed_nanos(),
              bucketStartTimeNs + 5 * bucketSizeNs);
    EXPECT_EQ((unsigned long long)data.bucket_info(1).end_bucket_elapsed_nanos(),
              bucketStartTimeNs + 6 * bucketSizeNs);
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif

}  // namespace statsd
}  // namespace os
}  // namespace android
