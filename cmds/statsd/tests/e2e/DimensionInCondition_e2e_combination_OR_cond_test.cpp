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

StatsdConfig CreateCountMetric_NoLink_CombinationCondition_Config() {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT"); // LogEvent defaults to UID of root.
    auto screenBrightnessChangeAtomMatcher = CreateScreenBrightnessChangedAtomMatcher();
    *config.add_atom_matcher() = screenBrightnessChangeAtomMatcher;
    *config.add_atom_matcher() = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = CreateAcquireWakelockAtomMatcher();
    *config.add_atom_matcher() = CreateReleaseWakelockAtomMatcher();

    auto screenIsOffPredicate = CreateScreenIsOffPredicate();
    *config.add_predicate() = screenIsOffPredicate;

    auto holdingWakelockPredicate = CreateHoldingWakelockPredicate();
    // The predicate is dimensioning by any attribution node and both by uid and tag.
    *holdingWakelockPredicate.mutable_simple_predicate()->mutable_dimensions() =
            CreateAttributionUidAndTagDimensions(android::util::WAKELOCK_STATE_CHANGED,
                                                 {Position::FIRST});
    *config.add_predicate() = holdingWakelockPredicate;

    auto combinationPredicate = config.add_predicate();
    combinationPredicate->set_id(987654);
    combinationPredicate->mutable_combination()->set_operation(LogicalOperation::OR);
    addPredicateToPredicateCombination(screenIsOffPredicate, combinationPredicate);
    addPredicateToPredicateCombination(holdingWakelockPredicate, combinationPredicate);

    auto metric = config.add_count_metric();
    metric->set_id(StringToId("ScreenBrightnessChangeMetric"));
    metric->set_what(screenBrightnessChangeAtomMatcher.id());
    metric->set_condition(combinationPredicate->id());
    *metric->mutable_dimensions_in_what() =
            CreateDimensions(android::util::SCREEN_BRIGHTNESS_CHANGED, {1 /* level */});
    *metric->mutable_dimensions_in_condition() = CreateAttributionUidDimensions(
            android::util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    metric->set_bucket(FIVE_MINUTES);
    return config;
}

}  // namespace

TEST(DimensionInConditionE2eTest, TestCreateCountMetric_NoLink_OR_CombinationCondition) {
    ConfigKey cfgKey;
    auto config = CreateCountMetric_NoLink_CombinationCondition_Config();
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.count_metric(0).bucket()) * 1000000LL;

    auto processor = CreateStatsLogProcessor(bucketStartTimeNs / NS_PER_SEC, config, cfgKey);
    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());

    std::vector<AttributionNodeInternal> attributions1 = {CreateAttribution(111, "App1"),
                                                          CreateAttribution(222, "GMSCoreModule1"),
                                                          CreateAttribution(222, "GMSCoreModule2")};

    std::vector<AttributionNodeInternal> attributions2 = {CreateAttribution(333, "App2"),
                                                          CreateAttribution(222, "GMSCoreModule1"),
                                                          CreateAttribution(555, "GMSCoreModule2")};

    std::vector<std::unique_ptr<LogEvent>> events;
    events.push_back(
            CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_ON, bucketStartTimeNs + 10));
    events.push_back(CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_OFF,
                                                   bucketStartTimeNs + 100));
    events.push_back(CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_ON,
                                                   bucketStartTimeNs + bucketSizeNs + 1));
    events.push_back(CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_OFF,
                                                   bucketStartTimeNs + 2 * bucketSizeNs - 10));

    events.push_back(CreateAcquireWakelockEvent(attributions1, "wl1", bucketStartTimeNs + 200));
    events.push_back(
            CreateReleaseWakelockEvent(attributions1, "wl1", bucketStartTimeNs + bucketSizeNs + 1));

    events.push_back(CreateAcquireWakelockEvent(attributions2, "wl2",
                                                bucketStartTimeNs + bucketSizeNs - 100));
    events.push_back(CreateReleaseWakelockEvent(attributions2, "wl2",
                                                bucketStartTimeNs + 2 * bucketSizeNs - 50));

    events.push_back(CreateScreenBrightnessChangedEvent(123, bucketStartTimeNs + 11));
    events.push_back(CreateScreenBrightnessChangedEvent(123, bucketStartTimeNs + 101));
    events.push_back(CreateScreenBrightnessChangedEvent(123, bucketStartTimeNs + 201));
    events.push_back(CreateScreenBrightnessChangedEvent(456, bucketStartTimeNs + 203));
    events.push_back(
            CreateScreenBrightnessChangedEvent(456, bucketStartTimeNs + bucketSizeNs - 99));
    events.push_back(CreateScreenBrightnessChangedEvent(456, bucketStartTimeNs + bucketSizeNs - 2));
    events.push_back(CreateScreenBrightnessChangedEvent(789, bucketStartTimeNs + bucketSizeNs - 1));
    events.push_back(CreateScreenBrightnessChangedEvent(456, bucketStartTimeNs + bucketSizeNs + 2));
    events.push_back(
            CreateScreenBrightnessChangedEvent(789, bucketStartTimeNs + 2 * bucketSizeNs - 11));
    events.push_back(
            CreateScreenBrightnessChangedEvent(789, bucketStartTimeNs + 2 * bucketSizeNs - 9));
    events.push_back(
            CreateScreenBrightnessChangedEvent(789, bucketStartTimeNs + 2 * bucketSizeNs - 1));

    sortLogEventsByTimestamp(&events);

    for (const auto& event : events) {
        processor->OnLogEvent(event.get());
    }

    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(cfgKey, bucketStartTimeNs + 2 * bucketSizeNs + 1, false, &buffer);
    EXPECT_TRUE(buffer.size() > 0);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));

    EXPECT_EQ(reports.reports_size(), 1);
    EXPECT_EQ(reports.reports(0).metrics_size(), 1);
    StatsLogReport::CountMetricDataWrapper countMetrics;
    sortMetricDataByDimensionsValue(reports.reports(0).metrics(0).count_metrics(), &countMetrics);

    EXPECT_EQ(countMetrics.data_size(), 7);
    auto data = countMetrics.data(0);
    EXPECT_EQ(data.bucket_info_size(), 1);
    EXPECT_EQ(data.bucket_info(0).count(), 1);
    EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs);
    EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(), bucketStartTimeNs + bucketSizeNs);
    EXPECT_EQ(data.dimensions_in_what().field(), android::util::SCREEN_BRIGHTNESS_CHANGED);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).field(), 1);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).value_int(), 123);
    EXPECT_FALSE(data.dimensions_in_condition().has_field());

    data = countMetrics.data(1);
    EXPECT_EQ(data.bucket_info_size(), 1);
    EXPECT_EQ(data.bucket_info(0).count(), 1);
    EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs);
    EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(), bucketStartTimeNs + bucketSizeNs);
    EXPECT_EQ(data.dimensions_in_what().field(), android::util::SCREEN_BRIGHTNESS_CHANGED);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).field(), 1);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).value_int(), 123);
    ValidateAttributionUidDimension(data.dimensions_in_condition(),
                                    android::util::WAKELOCK_STATE_CHANGED, 111);

    data = countMetrics.data(2);
    EXPECT_EQ(data.bucket_info_size(), 1);
    EXPECT_EQ(data.bucket_info(0).count(), 3);
    EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs);
    EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(), bucketStartTimeNs + bucketSizeNs);
    EXPECT_EQ(data.dimensions_in_what().field(), android::util::SCREEN_BRIGHTNESS_CHANGED);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).field(), 1);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).value_int(), 456);
    ValidateAttributionUidDimension(data.dimensions_in_condition(),
                                    android::util::WAKELOCK_STATE_CHANGED, 111);

    data = countMetrics.data(3);
    EXPECT_EQ(data.bucket_info_size(), 2);
    EXPECT_EQ(data.bucket_info(0).count(), 2);
    EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs);
    EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(), bucketStartTimeNs + bucketSizeNs);
    EXPECT_EQ(data.bucket_info(1).count(), 1);
    EXPECT_EQ(data.bucket_info(1).start_bucket_elapsed_nanos(), bucketStartTimeNs + bucketSizeNs);
    EXPECT_EQ(data.bucket_info(1).end_bucket_elapsed_nanos(), bucketStartTimeNs + 2 * bucketSizeNs);
    EXPECT_EQ(data.dimensions_in_what().field(), android::util::SCREEN_BRIGHTNESS_CHANGED);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).field(), 1);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).value_int(), 456);
    ValidateAttributionUidDimension(data.dimensions_in_condition(),
                                    android::util::WAKELOCK_STATE_CHANGED, 333);

    data = countMetrics.data(4);
    EXPECT_EQ(data.bucket_info_size(), 1);
    EXPECT_EQ(data.bucket_info(0).count(), 2);
    EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs + bucketSizeNs);
    EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(), bucketStartTimeNs + 2 * bucketSizeNs);
    EXPECT_EQ(data.dimensions_in_what().field(), android::util::SCREEN_BRIGHTNESS_CHANGED);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).field(), 1);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).value_int(), 789);
    EXPECT_FALSE(data.dimensions_in_condition().has_field());

    data = countMetrics.data(5);
    EXPECT_EQ(data.bucket_info_size(), 1);
    EXPECT_EQ(data.bucket_info(0).count(), 1);
    EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs);
    EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(), bucketStartTimeNs + bucketSizeNs);
    EXPECT_EQ(data.dimensions_in_what().field(), android::util::SCREEN_BRIGHTNESS_CHANGED);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).field(), 1);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).value_int(), 789);
    ValidateAttributionUidDimension(data.dimensions_in_condition(),
                                    android::util::WAKELOCK_STATE_CHANGED, 111);

    data = countMetrics.data(6);
    EXPECT_EQ(data.bucket_info_size(), 1);
    EXPECT_EQ(data.bucket_info(0).count(), 1);
    EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs);
    EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(), bucketStartTimeNs + bucketSizeNs);
    EXPECT_EQ(data.dimensions_in_what().field(), android::util::SCREEN_BRIGHTNESS_CHANGED);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).field(), 1);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).value_int(), 789);
    ValidateAttributionUidDimension(data.dimensions_in_condition(),
                                    android::util::WAKELOCK_STATE_CHANGED, 333);
}

namespace {

StatsdConfig CreateCountMetric_Link_CombinationCondition() {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT"); // LogEvent defaults to UID of root.
    auto appCrashMatcher = CreateProcessCrashAtomMatcher();
    *config.add_atom_matcher() = appCrashMatcher;
    *config.add_atom_matcher() = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = CreateSyncStartAtomMatcher();
    *config.add_atom_matcher() = CreateSyncEndAtomMatcher();

    auto screenIsOffPredicate = CreateScreenIsOffPredicate();
    auto isSyncingPredicate = CreateIsSyncingPredicate();
    auto syncDimension = isSyncingPredicate.mutable_simple_predicate()->mutable_dimensions();
    *syncDimension = CreateAttributionUidAndTagDimensions(android::util::SYNC_STATE_CHANGED,
                                                          {Position::FIRST});
    syncDimension->add_child()->set_field(2 /* name field*/);

    *config.add_predicate() = screenIsOffPredicate;
    *config.add_predicate() = isSyncingPredicate;
    auto combinationPredicate = config.add_predicate();
    combinationPredicate->set_id(987654);
    combinationPredicate->mutable_combination()->set_operation(LogicalOperation::OR);
    addPredicateToPredicateCombination(screenIsOffPredicate, combinationPredicate);
    addPredicateToPredicateCombination(isSyncingPredicate, combinationPredicate);

    auto metric = config.add_count_metric();
    metric->set_bucket(FIVE_MINUTES);
    metric->set_id(StringToId("AppCrashMetric"));
    metric->set_what(appCrashMatcher.id());
    metric->set_condition(combinationPredicate->id());
    *metric->mutable_dimensions_in_what() =
            CreateDimensions(android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED, {1 /* uid */});
    *metric->mutable_dimensions_in_condition() = CreateAttributionUidAndTagDimensions(
            android::util::SYNC_STATE_CHANGED, {Position::FIRST});

    // Links between crash atom and condition of app is in syncing.
    auto links = metric->add_links();
    links->set_condition(isSyncingPredicate.id());
    auto dimensionWhat = links->mutable_fields_in_what();
    dimensionWhat->set_field(android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED);
    dimensionWhat->add_child()->set_field(1);  // uid field.
    *links->mutable_fields_in_condition() =
            CreateAttributionUidDimensions(android::util::SYNC_STATE_CHANGED, {Position::FIRST});
    return config;
}

}  // namespace

TEST(DimensionInConditionE2eTest, TestCreateCountMetric_Link_OR_CombinationCondition) {
    ConfigKey cfgKey;
    auto config = CreateCountMetric_Link_CombinationCondition();
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.count_metric(0).bucket()) * 1000000LL;

    auto processor = CreateStatsLogProcessor(bucketStartTimeNs / NS_PER_SEC, config, cfgKey);
    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());
    std::vector<AttributionNodeInternal> attributions1 = {CreateAttribution(111, "App1"),
                                                          CreateAttribution(222, "GMSCoreModule1"),
                                                          CreateAttribution(222, "GMSCoreModule2")};

    std::vector<AttributionNodeInternal> attributions2 = {CreateAttribution(333, "App2"),
                                                          CreateAttribution(222, "GMSCoreModule1"),
                                                          CreateAttribution(555, "GMSCoreModule2")};

    std::vector<std::unique_ptr<LogEvent>> events;

    events.push_back(CreateAppCrashEvent(111, bucketStartTimeNs + 11));
    events.push_back(CreateAppCrashEvent(111, bucketStartTimeNs + 101));
    events.push_back(CreateAppCrashEvent(222, bucketStartTimeNs + 101));

    events.push_back(CreateAppCrashEvent(222, bucketStartTimeNs + 201));
    events.push_back(CreateAppCrashEvent(111, bucketStartTimeNs + 211));
    events.push_back(CreateAppCrashEvent(333, bucketStartTimeNs + 211));

    events.push_back(CreateAppCrashEvent(111, bucketStartTimeNs + 401));
    events.push_back(CreateAppCrashEvent(333, bucketStartTimeNs + 401));
    events.push_back(CreateAppCrashEvent(555, bucketStartTimeNs + 401));

    events.push_back(CreateAppCrashEvent(111, bucketStartTimeNs + bucketSizeNs + 301));
    events.push_back(CreateAppCrashEvent(333, bucketStartTimeNs + bucketSizeNs + 301));

    events.push_back(CreateAppCrashEvent(777, bucketStartTimeNs + bucketSizeNs + 701));

    events.push_back(
            CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_ON, bucketStartTimeNs + 10));
    events.push_back(CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_OFF,
                                                   bucketStartTimeNs + 100));
    events.push_back(CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_ON,
                                                   bucketStartTimeNs + 202));
    events.push_back(CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_OFF,
                                                   bucketStartTimeNs + bucketSizeNs + 700));

    events.push_back(CreateSyncStartEvent(attributions1, "ReadEmail", bucketStartTimeNs + 200));
    events.push_back(
            CreateSyncEndEvent(attributions1, "ReadEmail", bucketStartTimeNs + bucketSizeNs + 300));

    events.push_back(CreateSyncStartEvent(attributions1, "ReadDoc", bucketStartTimeNs + 400));
    events.push_back(
            CreateSyncEndEvent(attributions1, "ReadDoc", bucketStartTimeNs + bucketSizeNs - 1));

    events.push_back(CreateSyncStartEvent(attributions2, "ReadEmail", bucketStartTimeNs + 400));
    events.push_back(
            CreateSyncEndEvent(attributions2, "ReadEmail", bucketStartTimeNs + bucketSizeNs + 600));

    sortLogEventsByTimestamp(&events);

    for (const auto& event : events) {
        processor->OnLogEvent(event.get());
    }

    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(cfgKey, bucketStartTimeNs + 2 * bucketSizeNs + 1, false, &buffer);
    EXPECT_TRUE(buffer.size() > 0);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));

    EXPECT_EQ(reports.reports_size(), 1);
    EXPECT_EQ(reports.reports(0).metrics_size(), 1);
    StatsLogReport::CountMetricDataWrapper countMetrics;
    sortMetricDataByDimensionsValue(reports.reports(0).metrics(0).count_metrics(), &countMetrics);

    EXPECT_EQ(countMetrics.data_size(), 5);
    auto data = countMetrics.data(0);
    EXPECT_EQ(data.dimensions_in_what().field(), android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).field(), 1);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).value_int(), 111);
    EXPECT_FALSE(data.dimensions_in_condition().has_field());
    EXPECT_EQ(data.bucket_info_size(), 1);
    EXPECT_EQ(data.bucket_info(0).count(), 1);
    EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs);
    EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(), bucketStartTimeNs + bucketSizeNs);

    data = countMetrics.data(1);
    EXPECT_EQ(data.dimensions_in_what().field(), android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).field(), 1);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).value_int(), 111);
    ValidateAttributionUidAndTagDimension(data.dimensions_in_condition(),
                                          android::util::SYNC_STATE_CHANGED, 111, "App1");
    EXPECT_EQ(data.bucket_info_size(), 1);
    EXPECT_EQ(data.bucket_info(0).count(), 2);
    EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs);
    EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(), bucketStartTimeNs + bucketSizeNs);

    data = countMetrics.data(2);
    EXPECT_EQ(data.dimensions_in_what().field(), android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).field(), 1);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).value_int(), 222);
    EXPECT_FALSE(data.dimensions_in_condition().has_field());
    EXPECT_EQ(data.bucket_info_size(), 1);
    EXPECT_EQ(data.bucket_info(0).count(), 2);
    EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs);
    EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(), bucketStartTimeNs + bucketSizeNs);

    data = countMetrics.data(3);
    EXPECT_EQ(data.dimensions_in_what().field(), android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).field(), 1);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).value_int(), 333);
    ValidateAttributionUidAndTagDimension(data.dimensions_in_condition(),
                                          android::util::SYNC_STATE_CHANGED, 333, "App2");
    EXPECT_EQ(data.bucket_info_size(), 2);
    EXPECT_EQ(data.bucket_info(0).count(), 1);
    EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs);
    EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(), bucketStartTimeNs + bucketSizeNs);
    EXPECT_EQ(data.bucket_info(1).count(), 1);
    EXPECT_EQ(data.bucket_info(1).start_bucket_elapsed_nanos(), bucketStartTimeNs + bucketSizeNs);
    EXPECT_EQ(data.bucket_info(1).end_bucket_elapsed_nanos(), bucketStartTimeNs + 2 * bucketSizeNs);

    data = countMetrics.data(4);
    EXPECT_EQ(data.dimensions_in_what().field(), android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).field(), 1);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).value_int(), 777);
    EXPECT_FALSE(data.dimensions_in_condition().has_field());
    EXPECT_EQ(data.bucket_info_size(), 1);
    EXPECT_EQ(data.bucket_info(0).count(), 1);
    EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs + bucketSizeNs);
    EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(), bucketStartTimeNs + 2 * bucketSizeNs);
}

namespace {

StatsdConfig CreateDurationMetricConfig_NoLink_CombinationCondition(
        DurationMetric::AggregationType aggregationType) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT"); // LogEvent defaults to UID of root.
    *config.add_atom_matcher() = CreateBatterySaverModeStartAtomMatcher();
    *config.add_atom_matcher() = CreateBatterySaverModeStopAtomMatcher();
    *config.add_atom_matcher() = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = CreateSyncStartAtomMatcher();
    *config.add_atom_matcher() = CreateSyncEndAtomMatcher();

    auto inBatterySaverModePredicate = CreateBatterySaverModePredicate();

    auto screenIsOffPredicate = CreateScreenIsOffPredicate();
    auto isSyncingPredicate = CreateIsSyncingPredicate();
    auto syncDimension = isSyncingPredicate.mutable_simple_predicate()->mutable_dimensions();
    *syncDimension = CreateAttributionUidAndTagDimensions(android::util::SYNC_STATE_CHANGED,
                                                          {Position::FIRST});
    syncDimension->add_child()->set_field(2 /* name field */);

    *config.add_predicate() = inBatterySaverModePredicate;
    *config.add_predicate() = screenIsOffPredicate;
    *config.add_predicate() = isSyncingPredicate;
    auto combinationPredicate = config.add_predicate();
    combinationPredicate->set_id(987654);
    combinationPredicate->mutable_combination()->set_operation(LogicalOperation::OR);
    addPredicateToPredicateCombination(screenIsOffPredicate, combinationPredicate);
    addPredicateToPredicateCombination(isSyncingPredicate, combinationPredicate);

    auto metric = config.add_duration_metric();
    metric->set_bucket(FIVE_MINUTES);
    metric->set_id(StringToId("BatterySaverModeDurationMetric"));
    metric->set_what(inBatterySaverModePredicate.id());
    metric->set_condition(combinationPredicate->id());
    metric->set_aggregation_type(aggregationType);
    *metric->mutable_dimensions_in_condition() = CreateAttributionUidAndTagDimensions(
            android::util::SYNC_STATE_CHANGED, {Position::FIRST});
    return config;
}

}  // namespace

TEST(DimensionInConditionE2eTest, TestDurationMetric_NoLink_OR_CombinationCondition) {
    for (auto aggregationType : { DurationMetric::MAX_SPARSE, DurationMetric::SUM}) {
        ConfigKey cfgKey;
        auto config = CreateDurationMetricConfig_NoLink_CombinationCondition(aggregationType);
        int64_t bucketStartTimeNs = 10000000000;
        int64_t bucketSizeNs =
                TimeUnitToBucketSizeInMillis(config.duration_metric(0).bucket()) * 1000000LL;

        auto processor = CreateStatsLogProcessor(bucketStartTimeNs / NS_PER_SEC, config, cfgKey);
        EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
        EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());

        std::vector<AttributionNodeInternal> attributions1 = {
                CreateAttribution(111, "App1"), CreateAttribution(222, "GMSCoreModule1"),
                CreateAttribution(222, "GMSCoreModule2")};

        std::vector<AttributionNodeInternal> attributions2 = {
                CreateAttribution(333, "App2"), CreateAttribution(222, "GMSCoreModule1"),
                CreateAttribution(555, "GMSCoreModule2")};

        std::vector<std::unique_ptr<LogEvent>> events;

        events.push_back(CreateBatterySaverOffEvent(bucketStartTimeNs + 1));
        events.push_back(CreateBatterySaverOnEvent(bucketStartTimeNs + 101));
        events.push_back(CreateBatterySaverOffEvent(bucketStartTimeNs + 110));

        events.push_back(CreateBatterySaverOnEvent(bucketStartTimeNs + 201));
        events.push_back(CreateBatterySaverOffEvent(bucketStartTimeNs + 500));

        events.push_back(CreateBatterySaverOnEvent(bucketStartTimeNs + 600));
        events.push_back(CreateBatterySaverOffEvent(bucketStartTimeNs + bucketSizeNs + 850));

        events.push_back(CreateBatterySaverOnEvent(bucketStartTimeNs + bucketSizeNs + 870));
        events.push_back(CreateBatterySaverOffEvent(bucketStartTimeNs + bucketSizeNs + 900));

        events.push_back(CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_ON,
                                                       bucketStartTimeNs + 10));
        events.push_back(CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_OFF,
                                                       bucketStartTimeNs + 100));
        events.push_back(CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_ON,
                                                       bucketStartTimeNs + 202));
        events.push_back(CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_OFF,
                                                       bucketStartTimeNs + bucketSizeNs + 800));

        events.push_back(CreateSyncStartEvent(attributions1, "ReadEmail", bucketStartTimeNs + 200));
        events.push_back(CreateSyncEndEvent(attributions1, "ReadEmail",
                                            bucketStartTimeNs + bucketSizeNs + 300));

        events.push_back(CreateSyncStartEvent(attributions1, "ReadDoc", bucketStartTimeNs + 400));
        events.push_back(
                CreateSyncEndEvent(attributions1, "ReadDoc", bucketStartTimeNs + bucketSizeNs - 1));

        events.push_back(CreateSyncStartEvent(attributions2, "ReadEmail", bucketStartTimeNs + 401));
        events.push_back(CreateSyncEndEvent(attributions2, "ReadEmail",
                                            bucketStartTimeNs + bucketSizeNs + 700));

        sortLogEventsByTimestamp(&events);

        for (const auto& event : events) {
            processor->OnLogEvent(event.get());
        }

        ConfigMetricsReportList reports;
        vector<uint8_t> buffer;
        processor->onDumpReport(cfgKey, bucketStartTimeNs + 2 * bucketSizeNs + 1, false, &buffer);
        EXPECT_TRUE(buffer.size() > 0);
        EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));

        EXPECT_EQ(reports.reports_size(), 1);
        EXPECT_EQ(reports.reports(0).metrics_size(), 1);
        StatsLogReport::DurationMetricDataWrapper metrics;
        sortMetricDataByDimensionsValue(reports.reports(0).metrics(0).duration_metrics(), &metrics);

        EXPECT_EQ(metrics.data_size(), 3);
        auto data = metrics.data(0);
        EXPECT_FALSE(data.dimensions_in_what().has_field());
        EXPECT_FALSE(data.dimensions_in_condition().has_field());
        if (aggregationType == DurationMetric::SUM) {
            EXPECT_EQ(data.bucket_info_size(), 2);
            EXPECT_EQ(data.bucket_info(0).duration_nanos(), 9);
            EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs);
            EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(),
                      bucketStartTimeNs + bucketSizeNs);
            EXPECT_EQ(data.bucket_info(1).duration_nanos(), 30);
            EXPECT_EQ(data.bucket_info(1).start_bucket_elapsed_nanos(),
                      bucketStartTimeNs + bucketSizeNs);
            EXPECT_EQ(data.bucket_info(1).end_bucket_elapsed_nanos(),
                      bucketStartTimeNs + 2 * bucketSizeNs);
        } else {
            EXPECT_EQ(data.bucket_info_size(), 2);
            EXPECT_EQ(data.bucket_info(0).duration_nanos(), 9);
            EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs);
            EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(),
                      bucketStartTimeNs + bucketSizeNs);
            EXPECT_EQ(data.bucket_info(1).duration_nanos(), 30);
            EXPECT_EQ(data.bucket_info(1).start_bucket_elapsed_nanos(),
                      bucketStartTimeNs + bucketSizeNs);
            EXPECT_EQ(data.bucket_info(1).end_bucket_elapsed_nanos(),
                      bucketStartTimeNs + 2 * bucketSizeNs);
        }

        data = metrics.data(1);
        EXPECT_FALSE(data.dimensions_in_what().has_field());
        ValidateAttributionUidAndTagDimension(data.dimensions_in_condition(),
                                              android::util::SYNC_STATE_CHANGED, 111, "App1");
        EXPECT_EQ(data.bucket_info_size(), 2);

        if (aggregationType == DurationMetric::SUM) {
            EXPECT_EQ(data.bucket_info(0).duration_nanos(), 500 - 201 + bucketSizeNs - 600);
            EXPECT_EQ(data.bucket_info(1).duration_nanos(), 300);
        } else {
            EXPECT_EQ(data.bucket_info(0).duration_nanos(), 500 - 201);
            EXPECT_EQ(data.bucket_info(1).duration_nanos(), bucketSizeNs - 300);
        }
        EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs);
        EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(),
                  bucketStartTimeNs + bucketSizeNs);
        EXPECT_EQ(data.bucket_info(1).start_bucket_elapsed_nanos(),
                  bucketStartTimeNs + bucketSizeNs);
        EXPECT_EQ(data.bucket_info(1).end_bucket_elapsed_nanos(),
                  bucketStartTimeNs + 2 * bucketSizeNs);

        data = metrics.data(2);
        EXPECT_FALSE(data.dimensions_in_what().has_field());
        ValidateAttributionUidAndTagDimension(data.dimensions_in_condition(),
                                              android::util::SYNC_STATE_CHANGED, 333, "App2");
        EXPECT_EQ(data.bucket_info_size(), 2);
        if (aggregationType == DurationMetric::SUM) {
            EXPECT_EQ(data.bucket_info(0).duration_nanos(), 500 - 401 + bucketSizeNs - 600);
            EXPECT_EQ(data.bucket_info(1).duration_nanos(), 700);
        } else {
            EXPECT_EQ(data.bucket_info(0).duration_nanos(), 500 - 401);
            EXPECT_EQ(data.bucket_info(1).duration_nanos(), bucketSizeNs + 700 - 600);
        }
        EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs);
        EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(),
                  bucketStartTimeNs + bucketSizeNs);
        EXPECT_EQ(data.bucket_info(1).start_bucket_elapsed_nanos(),
                  bucketStartTimeNs + bucketSizeNs);
        EXPECT_EQ(data.bucket_info(1).end_bucket_elapsed_nanos(),
                  bucketStartTimeNs + 2 * bucketSizeNs);
    }
}

namespace {

StatsdConfig CreateDurationMetricConfig_Link_CombinationCondition(
        DurationMetric::AggregationType aggregationType) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT"); // LogEvent defaults to UID of root.
    *config.add_atom_matcher() = CreateMoveToBackgroundAtomMatcher();
    *config.add_atom_matcher() = CreateMoveToForegroundAtomMatcher();
    *config.add_atom_matcher() = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = CreateSyncStartAtomMatcher();
    *config.add_atom_matcher() = CreateSyncEndAtomMatcher();

    auto screenIsOffPredicate = CreateScreenIsOffPredicate();
    auto isSyncingPredicate = CreateIsSyncingPredicate();
    auto syncDimension = isSyncingPredicate.mutable_simple_predicate()->mutable_dimensions();
    *syncDimension = CreateAttributionUidAndTagDimensions(android::util::SYNC_STATE_CHANGED,
                                                          {Position::FIRST});
    syncDimension->add_child()->set_field(2 /* name field */);

    auto isInBackgroundPredicate = CreateIsInBackgroundPredicate();
    *isInBackgroundPredicate.mutable_simple_predicate()->mutable_dimensions() =
            CreateDimensions(android::util::ACTIVITY_FOREGROUND_STATE_CHANGED, {1 /* uid field */});

    *config.add_predicate() = screenIsOffPredicate;
    *config.add_predicate() = isSyncingPredicate;
    *config.add_predicate() = isInBackgroundPredicate;
    auto combinationPredicate = config.add_predicate();
    combinationPredicate->set_id(987654);
    combinationPredicate->mutable_combination()->set_operation(LogicalOperation::OR);
    addPredicateToPredicateCombination(screenIsOffPredicate, combinationPredicate);
    addPredicateToPredicateCombination(isSyncingPredicate, combinationPredicate);

    auto metric = config.add_duration_metric();
    metric->set_bucket(FIVE_MINUTES);
    metric->set_id(StringToId("AppInBackgroundMetric"));
    metric->set_what(isInBackgroundPredicate.id());
    metric->set_condition(combinationPredicate->id());
    metric->set_aggregation_type(aggregationType);
    *metric->mutable_dimensions_in_what() =
            CreateDimensions(android::util::ACTIVITY_FOREGROUND_STATE_CHANGED, {1 /* uid field */});
    *metric->mutable_dimensions_in_condition() = CreateAttributionUidAndTagDimensions(
            android::util::SYNC_STATE_CHANGED, {Position::FIRST});

    // Links between crash atom and condition of app is in syncing.
    auto links = metric->add_links();
    links->set_condition(isSyncingPredicate.id());
    auto dimensionWhat = links->mutable_fields_in_what();
    dimensionWhat->set_field(android::util::ACTIVITY_FOREGROUND_STATE_CHANGED);
    dimensionWhat->add_child()->set_field(1);  // uid field.
    *links->mutable_fields_in_condition() =
            CreateAttributionUidDimensions(android::util::SYNC_STATE_CHANGED, {Position::FIRST});
    return config;
}

}  // namespace

TEST(DimensionInConditionE2eTest, TestDurationMetric_Link_OR_CombinationCondition) {
    for (auto aggregationType : {DurationMetric::SUM, DurationMetric::MAX_SPARSE}) {
        ConfigKey cfgKey;
        auto config = CreateDurationMetricConfig_Link_CombinationCondition(aggregationType);
        int64_t bucketStartTimeNs = 10000000000;
        int64_t bucketSizeNs =
                TimeUnitToBucketSizeInMillis(config.duration_metric(0).bucket()) * 1000000LL;

        auto processor = CreateStatsLogProcessor(bucketStartTimeNs / NS_PER_SEC, config, cfgKey);
        EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
        EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());

        std::vector<AttributionNodeInternal> attributions1 = {
                CreateAttribution(111, "App1"), CreateAttribution(222, "GMSCoreModule1"),
                CreateAttribution(222, "GMSCoreModule2")};

        std::vector<AttributionNodeInternal> attributions2 = {
                CreateAttribution(333, "App2"), CreateAttribution(222, "GMSCoreModule1"),
                CreateAttribution(555, "GMSCoreModule2")};

        std::vector<std::unique_ptr<LogEvent>> events;

        events.push_back(CreateMoveToBackgroundEvent(111, bucketStartTimeNs + 101));
        events.push_back(CreateMoveToForegroundEvent(111, bucketStartTimeNs + 110));

        events.push_back(CreateMoveToBackgroundEvent(111, bucketStartTimeNs + 201));
        events.push_back(CreateMoveToForegroundEvent(111, bucketStartTimeNs + bucketSizeNs + 100));

        events.push_back(CreateMoveToBackgroundEvent(333, bucketStartTimeNs + 399));
        events.push_back(CreateMoveToForegroundEvent(333, bucketStartTimeNs + bucketSizeNs + 800));

        events.push_back(CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_ON,
                                                       bucketStartTimeNs + 10));
        events.push_back(CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_OFF,
                                                       bucketStartTimeNs + 100));
        events.push_back(CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_ON,
                                                       bucketStartTimeNs + 202));
        events.push_back(CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_OFF,
                                                       bucketStartTimeNs + bucketSizeNs + 801));

        events.push_back(CreateSyncStartEvent(attributions1, "ReadEmail", bucketStartTimeNs + 200));
        events.push_back(CreateSyncEndEvent(attributions1, "ReadEmail",
                                            bucketStartTimeNs + bucketSizeNs + 300));

        events.push_back(CreateSyncStartEvent(attributions1, "ReadDoc", bucketStartTimeNs + 400));
        events.push_back(
                CreateSyncEndEvent(attributions1, "ReadDoc", bucketStartTimeNs + bucketSizeNs - 1));

        events.push_back(CreateSyncStartEvent(attributions2, "ReadEmail", bucketStartTimeNs + 401));
        events.push_back(CreateSyncEndEvent(attributions2, "ReadEmail",
                                            bucketStartTimeNs + bucketSizeNs + 700));

        sortLogEventsByTimestamp(&events);

        for (const auto& event : events) {
            processor->OnLogEvent(event.get());
        }

        ConfigMetricsReportList reports;
        vector<uint8_t> buffer;
        processor->onDumpReport(cfgKey, bucketStartTimeNs + 2 * bucketSizeNs + 1, false, &buffer);
        EXPECT_TRUE(buffer.size() > 0);
        EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));

        EXPECT_EQ(reports.reports_size(), 1);
        EXPECT_EQ(reports.reports(0).metrics_size(), 1);
        StatsLogReport::DurationMetricDataWrapper metrics;
        sortMetricDataByDimensionsValue(reports.reports(0).metrics(0).duration_metrics(), &metrics);

        EXPECT_EQ(metrics.data_size(), 3);
        auto data = metrics.data(0);
        EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).field(), 1);
        EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).value_int(), 111);
        EXPECT_FALSE(data.dimensions_in_condition().has_field());
        EXPECT_EQ(data.bucket_info_size(), 1);
        EXPECT_EQ(data.bucket_info(0).duration_nanos(), 9);
        EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs);
        EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(), bucketStartTimeNs + bucketSizeNs);

        data = metrics.data(1);
        EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).field(), 1);
        EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).value_int(), 111);
        ValidateAttributionUidAndTagDimension(data.dimensions_in_condition(),
                                              android::util::SYNC_STATE_CHANGED, 111, "App1");
        if (aggregationType == DurationMetric::SUM) {
            EXPECT_EQ(data.bucket_info_size(), 2);
            EXPECT_EQ(data.bucket_info(0).duration_nanos(), bucketSizeNs - 201);
            EXPECT_EQ(data.bucket_info(1).duration_nanos(), 100);
            EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs);
            EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(),
                      bucketStartTimeNs + bucketSizeNs);
            EXPECT_EQ(data.bucket_info(1).start_bucket_elapsed_nanos(),
                      bucketStartTimeNs + bucketSizeNs);
            EXPECT_EQ(data.bucket_info(1).end_bucket_elapsed_nanos(),
                      bucketStartTimeNs + 2 * bucketSizeNs);
        } else {
            EXPECT_EQ(data.bucket_info_size(), 1);
            EXPECT_EQ(data.bucket_info(0).duration_nanos(), bucketSizeNs + 100 - 201);
            EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(),
                      bucketStartTimeNs + bucketSizeNs);
            EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(),
                      bucketStartTimeNs + 2 * bucketSizeNs);
        }

        data = metrics.data(2);
        EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).field(), 1);
        EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).value_int(), 333);
        ValidateAttributionUidAndTagDimension(data.dimensions_in_condition(),
                                              android::util::SYNC_STATE_CHANGED, 333, "App2");
        if (aggregationType == DurationMetric::SUM) {
            EXPECT_EQ(data.bucket_info_size(), 2);
            EXPECT_EQ(data.bucket_info(0).duration_nanos(), bucketSizeNs - 401);
            EXPECT_EQ(data.bucket_info(1).duration_nanos(), 700);
            EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs);
            EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(),
                      bucketStartTimeNs + bucketSizeNs);
            EXPECT_EQ(data.bucket_info(1).start_bucket_elapsed_nanos(),
                      bucketStartTimeNs + bucketSizeNs);
            EXPECT_EQ(data.bucket_info(1).end_bucket_elapsed_nanos(),
                      bucketStartTimeNs + 2 * bucketSizeNs);
        } else {
            EXPECT_EQ(data.bucket_info_size(), 1);
            EXPECT_EQ(data.bucket_info(0).duration_nanos(), bucketSizeNs + 299);
            EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(),
                      bucketStartTimeNs + bucketSizeNs);
            EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(),
                      bucketStartTimeNs + 2 * bucketSizeNs);
        }
    }
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif

}  // namespace statsd
}  // namespace os
}  // namespace android
