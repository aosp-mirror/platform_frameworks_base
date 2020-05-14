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

    *config.add_atom_matcher() = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = CreateScreenTurnedOffAtomMatcher();

    *config.add_atom_matcher() = CreateSyncStartAtomMatcher();
    *config.add_atom_matcher() = CreateSyncEndAtomMatcher();

    *config.add_atom_matcher() = CreateMoveToBackgroundAtomMatcher();
    *config.add_atom_matcher() = CreateMoveToForegroundAtomMatcher();

    auto appCrashMatcher = CreateProcessCrashAtomMatcher();
    *config.add_atom_matcher() = appCrashMatcher;

    auto screenIsOffPredicate = CreateScreenIsOffPredicate();

    auto isSyncingPredicate = CreateIsSyncingPredicate();
    auto syncDimension = isSyncingPredicate.mutable_simple_predicate()->mutable_dimensions();
    *syncDimension = CreateAttributionUidDimensions(
        util::SYNC_STATE_CHANGED, {Position::FIRST});
    syncDimension->add_child()->set_field(2 /* name field*/);

    auto isInBackgroundPredicate = CreateIsInBackgroundPredicate();
    *isInBackgroundPredicate.mutable_simple_predicate()->mutable_dimensions() =
        CreateDimensions(util::ACTIVITY_FOREGROUND_STATE_CHANGED, {1 /* uid field */ });

    *config.add_predicate() = screenIsOffPredicate;
    *config.add_predicate() = isSyncingPredicate;
    *config.add_predicate() = isInBackgroundPredicate;

    auto combinationPredicate = config.add_predicate();
    combinationPredicate->set_id(StringToId("combinationPredicate"));
    combinationPredicate->mutable_combination()->set_operation(LogicalOperation::AND);
    addPredicateToPredicateCombination(screenIsOffPredicate, combinationPredicate);
    addPredicateToPredicateCombination(isSyncingPredicate, combinationPredicate);
    addPredicateToPredicateCombination(isInBackgroundPredicate, combinationPredicate);

    auto countMetric = config.add_count_metric();
    countMetric->set_id(StringToId("AppCrashes"));
    countMetric->set_what(appCrashMatcher.id());
    countMetric->set_condition(combinationPredicate->id());
    // The metric is dimensioning by uid only.
    *countMetric->mutable_dimensions_in_what() =
        CreateDimensions(util::PROCESS_LIFE_CYCLE_STATE_CHANGED, {1});
    countMetric->set_bucket(FIVE_MINUTES);

    // Links between crash atom and condition of app is in syncing.
    auto links = countMetric->add_links();
    links->set_condition(isSyncingPredicate.id());
    auto dimensionWhat = links->mutable_fields_in_what();
    dimensionWhat->set_field(util::PROCESS_LIFE_CYCLE_STATE_CHANGED);
    dimensionWhat->add_child()->set_field(1);  // uid field.
    *links->mutable_fields_in_condition() = CreateAttributionUidDimensions(
            util::SYNC_STATE_CHANGED, {Position::FIRST});

    // Links between crash atom and condition of app is in background.
    links = countMetric->add_links();
    links->set_condition(isInBackgroundPredicate.id());
    dimensionWhat = links->mutable_fields_in_what();
    dimensionWhat->set_field(util::PROCESS_LIFE_CYCLE_STATE_CHANGED);
    dimensionWhat->add_child()->set_field(1);  // uid field.
    auto dimensionCondition = links->mutable_fields_in_condition();
    dimensionCondition->set_field(util::ACTIVITY_FOREGROUND_STATE_CHANGED);
    dimensionCondition->add_child()->set_field(1);  // uid field.
    return config;
}
}  // namespace

// If we want to test multiple dump data, we must do it in separate tests, because in the e2e tests,
// we should use the real API which will clear the data after dump data is called.
TEST(MetricConditionLinkE2eTest, TestMultiplePredicatesAndLinks1) {
    auto config = CreateStatsdConfig();
    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.count_metric(0).bucket()) * 1000000LL;

    ConfigKey cfgKey;
    auto processor = CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);
    ASSERT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());

    int appUid = 123;
    auto crashEvent1 = CreateAppCrashEvent(bucketStartTimeNs + 1, appUid);
    auto crashEvent2 = CreateAppCrashEvent(bucketStartTimeNs + 201, appUid);
    auto crashEvent3 = CreateAppCrashEvent(bucketStartTimeNs + 2 * bucketSizeNs - 101, appUid);

    auto crashEvent4 = CreateAppCrashEvent(bucketStartTimeNs + 51, appUid);
    auto crashEvent5 = CreateAppCrashEvent(bucketStartTimeNs + bucketSizeNs + 299, appUid);
    auto crashEvent6 = CreateAppCrashEvent(bucketStartTimeNs + bucketSizeNs + 2001, appUid);

    auto crashEvent7 = CreateAppCrashEvent(bucketStartTimeNs + 16, appUid);
    auto crashEvent8 = CreateAppCrashEvent(bucketStartTimeNs + bucketSizeNs + 249, appUid);

    auto crashEvent9 = CreateAppCrashEvent(bucketStartTimeNs + bucketSizeNs + 351, appUid);
    auto crashEvent10 = CreateAppCrashEvent(bucketStartTimeNs + 2 * bucketSizeNs - 2, appUid);

    auto screenTurnedOnEvent = CreateScreenStateChangedEvent(
            bucketStartTimeNs + 2, android::view::DisplayStateEnum::DISPLAY_STATE_ON);
    auto screenTurnedOffEvent = CreateScreenStateChangedEvent(
            bucketStartTimeNs + 200, android::view::DisplayStateEnum::DISPLAY_STATE_OFF);
    auto screenTurnedOnEvent2 =
            CreateScreenStateChangedEvent(bucketStartTimeNs + 2 * bucketSizeNs - 100,
                                          android::view::DisplayStateEnum::DISPLAY_STATE_ON);

    std::vector<int> attributionUids = {appUid, appUid + 1};
    std::vector<string> attributionTags = {"App1", "GMSCoreModule1"};

    auto syncOnEvent1 = CreateSyncStartEvent(bucketStartTimeNs + 50, attributionUids,
                                             attributionTags, "ReadEmail");
    auto syncOffEvent1 = CreateSyncEndEvent(bucketStartTimeNs + bucketSizeNs + 300, attributionUids,
                                            attributionTags, "ReadEmail");
    auto syncOnEvent2 = CreateSyncStartEvent(bucketStartTimeNs + bucketSizeNs + 2000,
                                             attributionUids, attributionTags, "ReadDoc");

    auto moveToBackgroundEvent1 = CreateMoveToBackgroundEvent(bucketStartTimeNs + 15, appUid);
    auto moveToForegroundEvent1 =
            CreateMoveToForegroundEvent(bucketStartTimeNs + bucketSizeNs + 250, appUid);

    auto moveToBackgroundEvent2 =
            CreateMoveToBackgroundEvent(bucketStartTimeNs + bucketSizeNs + 350, appUid);
    auto moveToForegroundEvent2 =
            CreateMoveToForegroundEvent(bucketStartTimeNs + 2 * bucketSizeNs - 1, appUid);

    /*
                    bucket #1                               bucket #2


       |      |   |  |                      |   |          |        |   |   |     (crashEvents)
    |-------------------------------------|-----------------------------------|---------

             |                                           |                        (MoveToBkground)

                                             |                               |    (MoveToForeground)

                |                                                 |                (SyncIsOn)
                                                  |                                (SyncIsOff)
          |                                                               |        (ScreenIsOn)
                   |                                                               (ScreenIsOff)
    */
    std::vector<std::unique_ptr<LogEvent>> events;
    events.push_back(std::move(crashEvent1));
    events.push_back(std::move(crashEvent2));
    events.push_back(std::move(crashEvent3));
    events.push_back(std::move(crashEvent4));
    events.push_back(std::move(crashEvent5));
    events.push_back(std::move(crashEvent6));
    events.push_back(std::move(crashEvent7));
    events.push_back(std::move(crashEvent8));
    events.push_back(std::move(crashEvent9));
    events.push_back(std::move(crashEvent10));
    events.push_back(std::move(screenTurnedOnEvent));
    events.push_back(std::move(screenTurnedOffEvent));
    events.push_back(std::move(screenTurnedOnEvent2));
    events.push_back(std::move(syncOnEvent1));
    events.push_back(std::move(syncOffEvent1));
    events.push_back(std::move(syncOnEvent2));
    events.push_back(std::move(moveToBackgroundEvent1));
    events.push_back(std::move(moveToForegroundEvent1));
    events.push_back(std::move(moveToBackgroundEvent2));
    events.push_back(std::move(moveToForegroundEvent2));

    sortLogEventsByTimestamp(&events);

    for (const auto& event : events) {
        processor->OnLogEvent(event.get());
    }
    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(cfgKey, bucketStartTimeNs + 2 * bucketSizeNs - 1, false, true, ADB_DUMP,
                            FAST, &buffer);
    EXPECT_TRUE(buffer.size() > 0);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);
    ASSERT_EQ(reports.reports_size(), 1);
    ASSERT_EQ(reports.reports(0).metrics_size(), 1);
    ASSERT_EQ(reports.reports(0).metrics(0).count_metrics().data_size(), 1);
    ASSERT_EQ(reports.reports(0).metrics(0).count_metrics().data(0).bucket_info_size(), 1);
    EXPECT_EQ(reports.reports(0).metrics(0).count_metrics().data(0).bucket_info(0).count(), 1);
    auto data = reports.reports(0).metrics(0).count_metrics().data(0);
    // Validate dimension value.
    EXPECT_EQ(data.dimensions_in_what().field(), util::PROCESS_LIFE_CYCLE_STATE_CHANGED);
    ASSERT_EQ(data.dimensions_in_what().value_tuple().dimensions_value_size(), 1);
    // Uid field.
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).field(), 1);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).value_int(), appUid);
}

TEST(MetricConditionLinkE2eTest, TestMultiplePredicatesAndLinks2) {
    auto config = CreateStatsdConfig();
    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.count_metric(0).bucket()) * 1000000LL;

    ConfigKey cfgKey;
    auto processor = CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);
    ASSERT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());

    int appUid = 123;
    auto crashEvent1 = CreateAppCrashEvent(bucketStartTimeNs + 1, appUid);
    auto crashEvent2 = CreateAppCrashEvent(bucketStartTimeNs + 201, appUid);
    auto crashEvent3 = CreateAppCrashEvent(bucketStartTimeNs + 2 * bucketSizeNs - 101, appUid);

    auto crashEvent4 = CreateAppCrashEvent(bucketStartTimeNs + 51, appUid);
    auto crashEvent5 = CreateAppCrashEvent(bucketStartTimeNs + bucketSizeNs + 299, appUid);
    auto crashEvent6 = CreateAppCrashEvent(bucketStartTimeNs + bucketSizeNs + 2001, appUid);

    auto crashEvent7 = CreateAppCrashEvent(bucketStartTimeNs + 16, appUid);
    auto crashEvent8 = CreateAppCrashEvent(bucketStartTimeNs + bucketSizeNs + 249, appUid);

    auto crashEvent9 = CreateAppCrashEvent(bucketStartTimeNs + bucketSizeNs + 351, appUid);
    auto crashEvent10 = CreateAppCrashEvent(bucketStartTimeNs + 2 * bucketSizeNs - 2, appUid);

    auto screenTurnedOnEvent = CreateScreenStateChangedEvent(
            bucketStartTimeNs + 2, android::view::DisplayStateEnum::DISPLAY_STATE_ON);
    auto screenTurnedOffEvent = CreateScreenStateChangedEvent(
            bucketStartTimeNs + 200, android::view::DisplayStateEnum::DISPLAY_STATE_OFF);
    auto screenTurnedOnEvent2 =
            CreateScreenStateChangedEvent(bucketStartTimeNs + 2 * bucketSizeNs - 100,
                                          android::view::DisplayStateEnum::DISPLAY_STATE_ON);

    std::vector<int> attributionUids = {appUid, appUid + 1};
    std::vector<string> attributionTags = {"App1", "GMSCoreModule1"};

    auto syncOnEvent1 = CreateSyncStartEvent(bucketStartTimeNs + 50, attributionUids,
                                             attributionTags, "ReadEmail");
    auto syncOffEvent1 = CreateSyncEndEvent(bucketStartTimeNs + bucketSizeNs + 300, attributionUids,
                                            attributionTags, "ReadEmail");
    auto syncOnEvent2 = CreateSyncStartEvent(bucketStartTimeNs + bucketSizeNs + 2000,
                                             attributionUids, attributionTags, "ReadDoc");

    auto moveToBackgroundEvent1 = CreateMoveToBackgroundEvent(bucketStartTimeNs + 15, appUid);
    auto moveToForegroundEvent1 =
            CreateMoveToForegroundEvent(bucketStartTimeNs + bucketSizeNs + 250, appUid);

    auto moveToBackgroundEvent2 =
            CreateMoveToBackgroundEvent(bucketStartTimeNs + bucketSizeNs + 350, appUid);
    auto moveToForegroundEvent2 =
            CreateMoveToForegroundEvent(bucketStartTimeNs + 2 * bucketSizeNs - 1, appUid);

    /*
                    bucket #1                               bucket #2


       |      |   |  |                      |   |          |        |   |   |     (crashEvents)
    |-------------------------------------|-----------------------------------|---------

             |                                           |                        (MoveToBkground)

                                             |                               |    (MoveToForeground)

                |                                                 |                (SyncIsOn)
                                                  |                                (SyncIsOff)
          |                                                               |        (ScreenIsOn)
                   |                                                               (ScreenIsOff)
    */
    std::vector<std::unique_ptr<LogEvent>> events;
    events.push_back(std::move(crashEvent1));
    events.push_back(std::move(crashEvent2));
    events.push_back(std::move(crashEvent3));
    events.push_back(std::move(crashEvent4));
    events.push_back(std::move(crashEvent5));
    events.push_back(std::move(crashEvent6));
    events.push_back(std::move(crashEvent7));
    events.push_back(std::move(crashEvent8));
    events.push_back(std::move(crashEvent9));
    events.push_back(std::move(crashEvent10));
    events.push_back(std::move(screenTurnedOnEvent));
    events.push_back(std::move(screenTurnedOffEvent));
    events.push_back(std::move(screenTurnedOnEvent2));
    events.push_back(std::move(syncOnEvent1));
    events.push_back(std::move(syncOffEvent1));
    events.push_back(std::move(syncOnEvent2));
    events.push_back(std::move(moveToBackgroundEvent1));
    events.push_back(std::move(moveToForegroundEvent1));
    events.push_back(std::move(moveToBackgroundEvent2));
    events.push_back(std::move(moveToForegroundEvent2));

    sortLogEventsByTimestamp(&events);

    for (const auto& event : events) {
        processor->OnLogEvent(event.get());
    }
    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;

    processor->onDumpReport(cfgKey, bucketStartTimeNs + 2 * bucketSizeNs + 1, false, true, ADB_DUMP,
                            FAST, &buffer);
    EXPECT_TRUE(buffer.size() > 0);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);
    ASSERT_EQ(reports.reports_size(), 1);
    ASSERT_EQ(reports.reports(0).metrics_size(), 1);
    ASSERT_EQ(reports.reports(0).metrics(0).count_metrics().data_size(), 1);
    ASSERT_EQ(reports.reports(0).metrics(0).count_metrics().data(0).bucket_info_size(), 2);
    EXPECT_EQ(reports.reports(0).metrics(0).count_metrics().data(0).bucket_info(0).count(), 1);
    EXPECT_EQ(reports.reports(0).metrics(0).count_metrics().data(0).bucket_info(1).count(), 3);
    auto data = reports.reports(0).metrics(0).count_metrics().data(0);
    // Validate dimension value.
    EXPECT_EQ(data.dimensions_in_what().field(), util::PROCESS_LIFE_CYCLE_STATE_CHANGED);
    ASSERT_EQ(data.dimensions_in_what().value_tuple().dimensions_value_size(), 1);
    // Uid field.
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).field(), 1);
    EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).value_int(), appUid);
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif

}  // namespace statsd
}  // namespace os
}  // namespace android
