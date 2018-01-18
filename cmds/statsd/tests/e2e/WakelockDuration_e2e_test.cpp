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
    *config.add_atom_matcher() = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = CreateAcquireWakelockAtomMatcher();
    *config.add_atom_matcher() = CreateReleaseWakelockAtomMatcher();

    auto screenIsOffPredicate = CreateScreenIsOffPredicate();
    *config.add_predicate() = screenIsOffPredicate;

    auto holdingWakelockPredicate = CreateHoldingWakelockPredicate();
    // The predicate is dimensioning by any attribution node and both by uid and tag.
    *holdingWakelockPredicate.mutable_simple_predicate()->mutable_dimensions() =
        CreateAttributionUidAndTagDimensions(
            android::util::WAKELOCK_STATE_CHANGED, {Position::FIRST, Position::LAST});
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
    durationMetric->set_bucket(ONE_MINUTE);
    return config;
}

}  // namespace

TEST(WakelockDurationE2eTest, TestAggregatedPredicateDimensions) {
    ConfigKey cfgKey;
    for (auto aggregationType : { DurationMetric::SUM, DurationMetric::MAX_SPARSE }) {
        auto config = CreateStatsdConfig(aggregationType);
        uint64_t bucketStartTimeNs = 10000000000;
        uint64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.duration_metric(0).bucket()) * 1000000LL;

        auto processor = CreateStatsLogProcessor(bucketStartTimeNs / NS_PER_SEC, config, cfgKey);
        EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
        EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());

        auto screenTurnedOnEvent =
            CreateScreenStateChangedEvent(ScreenStateChanged::STATE_ON, bucketStartTimeNs + 1);
        auto screenTurnedOffEvent =
            CreateScreenStateChangedEvent(ScreenStateChanged::STATE_OFF, bucketStartTimeNs + 200);
        auto screenTurnedOnEvent2 =
            CreateScreenStateChangedEvent(ScreenStateChanged::STATE_ON,
                                          bucketStartTimeNs + bucketSizeNs + 500);

        std::vector<AttributionNode> attributions1 =
            {CreateAttribution(111, "App1"), CreateAttribution(222, "GMSCoreModule1"),
             CreateAttribution(222, "GMSCoreModule2")};

        std::vector<AttributionNode> attributions2 =
            {CreateAttribution(111, "App2"), CreateAttribution(222, "GMSCoreModule1"),
             CreateAttribution(222, "GMSCoreModule2")};

        auto acquireEvent1 = CreateAcquireWakelockEvent(
            attributions1, "wl1", bucketStartTimeNs + 2);
        auto acquireEvent2 = CreateAcquireWakelockEvent(
            attributions2, "wl2", bucketStartTimeNs + bucketSizeNs - 10);

        auto releaseEvent1 = CreateReleaseWakelockEvent(
            attributions1, "wl1", bucketStartTimeNs + bucketSizeNs + 2);
        auto releaseEvent2 = CreateReleaseWakelockEvent(
            attributions2, "wl2", bucketStartTimeNs + 2 * bucketSizeNs - 15);


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

        ConfigMetricsReportList reports;
        processor->onDumpReport(cfgKey, bucketStartTimeNs + 2 * bucketSizeNs - 1, &reports);
        EXPECT_EQ(reports.reports_size(), 1);
        EXPECT_EQ(reports.reports(0).metrics_size(), 1);
        // Only 1 dimension output. The tag dimension in the predicate has been aggregated.
        EXPECT_EQ(reports.reports(0).metrics(0).duration_metrics().data_size(), 1);

        auto data = reports.reports(0).metrics(0).duration_metrics().data(0);
        // Validate dimension value.
        ValidateAttributionUidDimension(
            data.dimensions_in_what(),
            android::util::WAKELOCK_STATE_CHANGED, 111);
        // Validate bucket info.
        EXPECT_EQ(reports.reports(0).metrics(0).duration_metrics().data(0).bucket_info_size(), 1);
        data = reports.reports(0).metrics(0).duration_metrics().data(0);
        // The wakelock holding interval starts from the screen off event and to the end of the 1st
        // bucket.
        EXPECT_EQ((unsigned long long)data.bucket_info(0).duration_nanos(), bucketSizeNs - 200);

        reports.Clear();
        processor->onDumpReport(cfgKey, bucketStartTimeNs + 2 * bucketSizeNs + 1, &reports);
        EXPECT_EQ(reports.reports_size(), 1);
        EXPECT_EQ(reports.reports(0).metrics_size(), 1);
        EXPECT_EQ(reports.reports(0).metrics(0).duration_metrics().data_size(), 1);
        // Dump the report after the end of 2nd bucket.
        EXPECT_EQ(reports.reports(0).metrics(0).duration_metrics().data(0).bucket_info_size(), 2);
        data = reports.reports(0).metrics(0).duration_metrics().data(0);
        // Validate dimension value.
        ValidateAttributionUidDimension(
            data.dimensions_in_what(), android::util::WAKELOCK_STATE_CHANGED, 111);
        // Two output buckets.
        // The wakelock holding interval in the 1st bucket starts from the screen off event and to
        // the end of the 1st bucket.
        EXPECT_EQ((unsigned long long)data.bucket_info(0).duration_nanos(),
            bucketStartTimeNs + bucketSizeNs - (bucketStartTimeNs + 200));
        // The wakelock holding interval in the 2nd bucket starts at the beginning of the bucket and
        // ends at the second screen on event.
        EXPECT_EQ((unsigned long long)data.bucket_info(1).duration_nanos(), 500UL);

        events.clear();
        events.push_back(CreateScreenStateChangedEvent(
            ScreenStateChanged::STATE_OFF, bucketStartTimeNs + 2 * bucketSizeNs + 90));
        events.push_back(CreateAcquireWakelockEvent(
            attributions1, "wl3", bucketStartTimeNs + 2 * bucketSizeNs + 100));
        events.push_back(CreateReleaseWakelockEvent(
            attributions1, "wl3", bucketStartTimeNs + 5 * bucketSizeNs + 100));
        sortLogEventsByTimestamp(&events);
        for (const auto& event : events) {
            processor->OnLogEvent(event.get());
        }
        reports.Clear();
        processor->onDumpReport(cfgKey, bucketStartTimeNs + 6 * bucketSizeNs + 1, &reports);
        EXPECT_EQ(reports.reports_size(), 1);
        EXPECT_EQ(reports.reports(0).metrics_size(), 1);
        EXPECT_EQ(reports.reports(0).metrics(0).duration_metrics().data_size(), 1);
        EXPECT_EQ(reports.reports(0).metrics(0).duration_metrics().data(0).bucket_info_size(), 6);
        data = reports.reports(0).metrics(0).duration_metrics().data(0);
        ValidateAttributionUidDimension(
            data.dimensions_in_what(), android::util::WAKELOCK_STATE_CHANGED, 111);
        // The last wakelock holding spans 4 buckets.
        EXPECT_EQ((unsigned long long)data.bucket_info(2).duration_nanos(), bucketSizeNs - 100);
        EXPECT_EQ((unsigned long long)data.bucket_info(3).duration_nanos(), bucketSizeNs);
        EXPECT_EQ((unsigned long long)data.bucket_info(4).duration_nanos(), bucketSizeNs);
        EXPECT_EQ((unsigned long long)data.bucket_info(5).duration_nanos(), 100UL);
    }
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif

}  // namespace statsd
}  // namespace os
}  // namespace android