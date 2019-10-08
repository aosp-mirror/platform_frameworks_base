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

StatsdConfig CreateDurationMetricConfig_NoLink_SimpleCondition(
        DurationMetric::AggregationType aggregationType, bool addExtraDimensionInCondition) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT"); // LogEvent defaults to UID of root.
    *config.add_atom_matcher() = CreateStartScheduledJobAtomMatcher();
    *config.add_atom_matcher() = CreateFinishScheduledJobAtomMatcher();
    *config.add_atom_matcher() = CreateSyncStartAtomMatcher();
    *config.add_atom_matcher() = CreateSyncEndAtomMatcher();

    auto scheduledJobPredicate = CreateScheduledJobPredicate();
    auto dimensions = scheduledJobPredicate.mutable_simple_predicate()->mutable_dimensions();
    dimensions->set_field(android::util::SCHEDULED_JOB_STATE_CHANGED);
    dimensions->add_child()->set_field(2);  // job name field.

    auto isSyncingPredicate = CreateIsSyncingPredicate();
    auto syncDimension = isSyncingPredicate.mutable_simple_predicate()->mutable_dimensions();
    *syncDimension = CreateAttributionUidAndTagDimensions(android::util::SYNC_STATE_CHANGED,
                                                          {Position::FIRST});
    if (addExtraDimensionInCondition) {
        syncDimension->add_child()->set_field(2 /* name field*/);
    }

    *config.add_predicate() = scheduledJobPredicate;
    *config.add_predicate() = isSyncingPredicate;

    auto metric = config.add_duration_metric();
    metric->set_bucket(FIVE_MINUTES);
    metric->set_id(StringToId("scheduledJob"));
    metric->set_what(scheduledJobPredicate.id());
    metric->set_condition(isSyncingPredicate.id());
    metric->set_aggregation_type(aggregationType);
    auto dimensionWhat = metric->mutable_dimensions_in_what();
    dimensionWhat->set_field(android::util::SCHEDULED_JOB_STATE_CHANGED);
    dimensionWhat->add_child()->set_field(2);  // job name field.
    *metric->mutable_dimensions_in_condition() = CreateAttributionUidAndTagDimensions(
            android::util::SYNC_STATE_CHANGED, {Position::FIRST});
    return config;
}

}  // namespace

TEST(DimensionInConditionE2eTest, TestDurationMetric_NoLink_SimpleCondition) {
    for (bool isDimensionInConditionSubSetOfConditionTrackerDimension : {true, false}) {
        for (auto aggregationType : {DurationMetric::SUM, DurationMetric::MAX_SPARSE}) {
            ConfigKey cfgKey;
            auto config = CreateDurationMetricConfig_NoLink_SimpleCondition(
                    aggregationType, isDimensionInConditionSubSetOfConditionTrackerDimension);
            int64_t bucketStartTimeNs = 10000000000;
            int64_t bucketSizeNs =
                    TimeUnitToBucketSizeInMillis(config.duration_metric(0).bucket()) * 1000000LL;

            auto processor = CreateStatsLogProcessor(
                    bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);
            EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
            EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());

            std::vector<AttributionNodeInternal> attributions1 = {
                    CreateAttribution(111, "App1"), CreateAttribution(222, "GMSCoreModule1"),
                    CreateAttribution(222, "GMSCoreModule2")};

            std::vector<AttributionNodeInternal> attributions2 = {
                    CreateAttribution(333, "App2"), CreateAttribution(222, "GMSCoreModule1"),
                    CreateAttribution(555, "GMSCoreModule2")};

            std::vector<std::unique_ptr<LogEvent>> events;

            events.push_back(CreateStartScheduledJobEvent(
                    {CreateAttribution(9999, "")}, "job0", bucketStartTimeNs + 1));
            events.push_back(CreateFinishScheduledJobEvent(
                    {CreateAttribution(9999, "")}, "job0",bucketStartTimeNs + 101));

            events.push_back(CreateStartScheduledJobEvent(
                    {CreateAttribution(9999, "")}, "job2", bucketStartTimeNs + 201));
            events.push_back(CreateFinishScheduledJobEvent(
                    {CreateAttribution(9999, "")}, "job2",bucketStartTimeNs + 500));

            events.push_back(CreateStartScheduledJobEvent(
                    {CreateAttribution(8888, "")}, "job2", bucketStartTimeNs + 600));
            events.push_back(CreateFinishScheduledJobEvent(
                    {CreateAttribution(8888, "")}, "job2",bucketStartTimeNs + bucketSizeNs + 850));

            events.push_back(CreateStartScheduledJobEvent(
                    {CreateAttribution(8888, "")}, "job1", bucketStartTimeNs + bucketSizeNs + 600));
            events.push_back(CreateFinishScheduledJobEvent(
                    {CreateAttribution(8888, "")}, "job1", bucketStartTimeNs + bucketSizeNs + 900));

            events.push_back(CreateSyncStartEvent(attributions1, "ReadEmail",
                                                  bucketStartTimeNs + 10));
            events.push_back(CreateSyncEndEvent(attributions1, "ReadEmail",
                                                bucketStartTimeNs + 50));

            events.push_back(CreateSyncStartEvent(attributions1, "ReadEmail",
                                                  bucketStartTimeNs + 200));
            events.push_back(CreateSyncEndEvent(attributions1, "ReadEmail",
                                                bucketStartTimeNs + bucketSizeNs + 300));

            events.push_back(CreateSyncStartEvent(attributions1, "ReadDoc",
                                                  bucketStartTimeNs + 400));
            events.push_back(CreateSyncEndEvent(attributions1, "ReadDoc",
                                                bucketStartTimeNs + bucketSizeNs - 1));

            events.push_back(CreateSyncStartEvent(attributions2, "ReadEmail",
                                                  bucketStartTimeNs + 401));
            events.push_back(CreateSyncEndEvent(attributions2, "ReadEmail",
                                                bucketStartTimeNs + bucketSizeNs + 700));

            sortLogEventsByTimestamp(&events);

            for (const auto& event : events) {
                processor->OnLogEvent(event.get());
            }

            ConfigMetricsReportList reports;
            vector<uint8_t> buffer;
            processor->onDumpReport(cfgKey, bucketStartTimeNs + 2 * bucketSizeNs + 1, false,
                                    true, ADB_DUMP, FAST, &buffer);
            EXPECT_TRUE(buffer.size() > 0);
            EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
            backfillDimensionPath(&reports);
            backfillStringInReport(&reports);
            backfillStartEndTimestamp(&reports);

            EXPECT_EQ(reports.reports_size(), 1);
            EXPECT_EQ(reports.reports(0).metrics_size(), 1);
            StatsLogReport::DurationMetricDataWrapper metrics;
            sortMetricDataByDimensionsValue(
                    reports.reports(0).metrics(0).duration_metrics(), &metrics);
            if (aggregationType == DurationMetric::SUM) {
                EXPECT_EQ(metrics.data_size(), 4);
                auto data = metrics.data(0);
                EXPECT_EQ(data.dimensions_in_what().field(),
                          android::util::SCHEDULED_JOB_STATE_CHANGED);
                EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).field(),
                          2);  // job name field
                EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).value_str(),
                          "job0");  // job name
                ValidateAttributionUidAndTagDimension(data.dimensions_in_condition(),
                                                      android::util::SYNC_STATE_CHANGED, 111, "App1");
                EXPECT_EQ(data.bucket_info_size(), 1);
                EXPECT_EQ(data.bucket_info(0).duration_nanos(), 40);
                EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(),
                          bucketStartTimeNs);
                EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(),
                    bucketStartTimeNs + bucketSizeNs);

                data = metrics.data(1);
                EXPECT_EQ(data.dimensions_in_what().field(),
                          android::util::SCHEDULED_JOB_STATE_CHANGED);
                EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).field(),
                          2);  // job name field
                EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).value_str(),
                          "job1");  // job name
                ValidateAttributionUidAndTagDimension(data.dimensions_in_condition(),
                                                      android::util::SYNC_STATE_CHANGED, 333, "App2");
                EXPECT_EQ(data.bucket_info_size(), 1);
                EXPECT_EQ(data.bucket_info(0).duration_nanos(), 100);
                EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(),
                          bucketStartTimeNs + bucketSizeNs);
                EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(),
                    bucketStartTimeNs + 2 * bucketSizeNs);

                data = metrics.data(2);
                EXPECT_EQ(data.dimensions_in_what().field(),
                          android::util::SCHEDULED_JOB_STATE_CHANGED);
                EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).field(),
                          2);  // job name field
                EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).value_str(),
                          "job2");  // job name
                ValidateAttributionUidAndTagDimension(data.dimensions_in_condition(),
                                                      android::util::SYNC_STATE_CHANGED, 111, "App1");
                EXPECT_EQ(data.bucket_info_size(), 2);
                EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs);
                EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(),
                          bucketStartTimeNs + bucketSizeNs);
                EXPECT_EQ(data.bucket_info(0).duration_nanos(), 500 - 201 + bucketSizeNs - 600);
                EXPECT_EQ(data.bucket_info(1).duration_nanos(), 300);
                EXPECT_EQ(data.bucket_info(1).start_bucket_elapsed_nanos(),
                          bucketStartTimeNs + bucketSizeNs);
                EXPECT_EQ(data.bucket_info(1).end_bucket_elapsed_nanos(),
                          bucketStartTimeNs + 2 * bucketSizeNs);

                data = metrics.data(3);
                EXPECT_EQ(data.dimensions_in_what().field(),
                          android::util::SCHEDULED_JOB_STATE_CHANGED);
                EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).field(),
                          2);  // job name field
                EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).value_str(),
                          "job2");  // job name
                ValidateAttributionUidAndTagDimension(data.dimensions_in_condition(),
                                                      android::util::SYNC_STATE_CHANGED, 333, "App2");
                EXPECT_EQ(data.bucket_info_size(), 2);
                EXPECT_EQ(data.bucket_info(0).duration_nanos(), 500 - 401 + bucketSizeNs - 600);
                EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs);
                EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(),
                          bucketStartTimeNs + bucketSizeNs);
                EXPECT_EQ(data.bucket_info(1).duration_nanos(), 700);
                EXPECT_EQ(data.bucket_info(1).start_bucket_elapsed_nanos(),
                          bucketStartTimeNs + bucketSizeNs);
                EXPECT_EQ(data.bucket_info(1).end_bucket_elapsed_nanos(),
                          bucketStartTimeNs + 2 * bucketSizeNs);
            } else {
                EXPECT_EQ(metrics.data_size(), 4);
                auto data = metrics.data(0);
                EXPECT_EQ(data.dimensions_in_what().field(),
                          android::util::SCHEDULED_JOB_STATE_CHANGED);
                EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).field(),
                          2);  // job name field
                EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).value_str(),
                          "job0");  // job name
                ValidateAttributionUidAndTagDimension(data.dimensions_in_condition(),
                                                      android::util::SYNC_STATE_CHANGED, 111, "App1");
                EXPECT_EQ(data.bucket_info_size(), 1);
                EXPECT_EQ(data.bucket_info(0).duration_nanos(), 40);
                EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(),
                          bucketStartTimeNs);
                EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(),
                    bucketStartTimeNs + bucketSizeNs);

                data = metrics.data(1);
                EXPECT_EQ(data.dimensions_in_what().field(),
                          android::util::SCHEDULED_JOB_STATE_CHANGED);
                EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).field(),
                          2);  // job name field
                EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).value_str(),
                          "job1");  // job name
                ValidateAttributionUidAndTagDimension(data.dimensions_in_condition(),
                                                      android::util::SYNC_STATE_CHANGED, 333, "App2");
                EXPECT_EQ(data.bucket_info_size(), 1);
                EXPECT_EQ(data.bucket_info(0).duration_nanos(), 100);
                EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(),
                          bucketStartTimeNs + bucketSizeNs);
                EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(),
                    bucketStartTimeNs + 2 * bucketSizeNs);

                data = metrics.data(2);
                EXPECT_EQ(data.dimensions_in_what().field(),
                          android::util::SCHEDULED_JOB_STATE_CHANGED);
                EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).field(),
                          2);  // job name field
                EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).value_str(),
                          "job2");  // job name
                ValidateAttributionUidAndTagDimension(data.dimensions_in_condition(),
                                                      android::util::SYNC_STATE_CHANGED, 111, "App1");
                EXPECT_EQ(data.bucket_info_size(), 2);
                EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs);
                EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(),
                          bucketStartTimeNs + bucketSizeNs);
                EXPECT_EQ(data.bucket_info(0).duration_nanos(), 500 - 201);
                EXPECT_EQ(data.bucket_info(1).duration_nanos(), bucketSizeNs - 600 + 300);
                EXPECT_EQ(data.bucket_info(1).start_bucket_elapsed_nanos(),
                          bucketStartTimeNs + bucketSizeNs);
                EXPECT_EQ(data.bucket_info(1).end_bucket_elapsed_nanos(),
                          bucketStartTimeNs + 2 * bucketSizeNs);

                data = metrics.data(3);
                EXPECT_EQ(data.dimensions_in_what().field(),
                          android::util::SCHEDULED_JOB_STATE_CHANGED);
                EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).field(),
                          2);  // job name field
                EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value(0).value_str(),
                          "job2");  // job name
                ValidateAttributionUidAndTagDimension(data.dimensions_in_condition(),
                                                      android::util::SYNC_STATE_CHANGED, 333, "App2");
                EXPECT_EQ(data.bucket_info_size(), 2);
                EXPECT_EQ(data.bucket_info(0).duration_nanos(), 500 - 401 );
                EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs);
                EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(),
                          bucketStartTimeNs + bucketSizeNs);
                EXPECT_EQ(data.bucket_info(1).duration_nanos(), bucketSizeNs - 600 + 700);
                EXPECT_EQ(data.bucket_info(1).start_bucket_elapsed_nanos(),
                          bucketStartTimeNs + bucketSizeNs);
                EXPECT_EQ(data.bucket_info(1).end_bucket_elapsed_nanos(),
                          bucketStartTimeNs + 2 * bucketSizeNs);
            }
        }
    }
}

namespace {

StatsdConfig createDurationMetric_Link_SimpleConditionConfig(
        DurationMetric::AggregationType aggregationType, bool addExtraDimensionInCondition) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT"); // LogEvent defaults to UID of root.
    *config.add_atom_matcher() = CreateStartScheduledJobAtomMatcher();
    *config.add_atom_matcher() = CreateFinishScheduledJobAtomMatcher();
    *config.add_atom_matcher() = CreateSyncStartAtomMatcher();
    *config.add_atom_matcher() = CreateSyncEndAtomMatcher();

    auto scheduledJobPredicate = CreateScheduledJobPredicate();
    auto dimensions = scheduledJobPredicate.mutable_simple_predicate()->mutable_dimensions();
    *dimensions = CreateAttributionUidDimensions(
                android::util::SCHEDULED_JOB_STATE_CHANGED, {Position::FIRST});
    dimensions->add_child()->set_field(2);  // job name field.

    auto isSyncingPredicate = CreateIsSyncingPredicate();
    auto syncDimension = isSyncingPredicate.mutable_simple_predicate()->mutable_dimensions();
    *syncDimension = CreateAttributionUidDimensions(
            android::util::SYNC_STATE_CHANGED, {Position::FIRST});
    if (addExtraDimensionInCondition) {
        syncDimension->add_child()->set_field(2 /* name field*/);
    }

    *config.add_predicate() = scheduledJobPredicate;
    *config.add_predicate() = isSyncingPredicate;

    auto metric = config.add_duration_metric();
    metric->set_bucket(FIVE_MINUTES);
    metric->set_id(StringToId("scheduledJob"));
    metric->set_what(scheduledJobPredicate.id());
    metric->set_condition(isSyncingPredicate.id());
    metric->set_aggregation_type(aggregationType);
    *metric->mutable_dimensions_in_what() = CreateAttributionUidDimensions(
            android::util::SCHEDULED_JOB_STATE_CHANGED, {Position::FIRST});

    auto links = metric->add_links();
    links->set_condition(isSyncingPredicate.id());
    *links->mutable_fields_in_what() =
            CreateAttributionUidDimensions(
                android::util::SCHEDULED_JOB_STATE_CHANGED, {Position::FIRST});
    *links->mutable_fields_in_condition() =
            CreateAttributionUidDimensions(android::util::SYNC_STATE_CHANGED, {Position::FIRST});
    return config;
}

}  // namespace

TEST(DimensionInConditionE2eTest, TestDurationMetric_Link_SimpleCondition) {
    for (bool isFullLink : {true, false}) {
        for (auto aggregationType : {DurationMetric::SUM, DurationMetric::MAX_SPARSE}) {
            ConfigKey cfgKey;
            auto config = createDurationMetric_Link_SimpleConditionConfig(
                    aggregationType, !isFullLink);
            int64_t bucketStartTimeNs = 10000000000;
            int64_t bucketSizeNs =
                    TimeUnitToBucketSizeInMillis(config.duration_metric(0).bucket()) * 1000000LL;

            auto processor = CreateStatsLogProcessor(
                    bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);
            EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
            EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());

            std::vector<AttributionNodeInternal> attributions1 = {
                    CreateAttribution(111, "App1"), CreateAttribution(222, "GMSCoreModule1"),
                    CreateAttribution(222, "GMSCoreModule2")};

            std::vector<AttributionNodeInternal> attributions2 = {
                    CreateAttribution(333, "App2"), CreateAttribution(222, "GMSCoreModule1"),
                    CreateAttribution(555, "GMSCoreModule2")};

            std::vector<AttributionNodeInternal> attributions3 = {
                    CreateAttribution(444, "App3"), CreateAttribution(222, "GMSCoreModule1"),
                    CreateAttribution(555, "GMSCoreModule2")};

            std::vector<std::unique_ptr<LogEvent>> events;

            events.push_back(CreateStartScheduledJobEvent(
                    {CreateAttribution(111, "App1")}, "job1", bucketStartTimeNs + 1));
            events.push_back(CreateFinishScheduledJobEvent(
                    {CreateAttribution(111, "App1")}, "job1",bucketStartTimeNs + 101));

            events.push_back(CreateStartScheduledJobEvent(
                    {CreateAttribution(333, "App2")}, "job2", bucketStartTimeNs + 201));
            events.push_back(CreateFinishScheduledJobEvent(
                    {CreateAttribution(333, "App2")}, "job2",bucketStartTimeNs + 500));
            events.push_back(CreateStartScheduledJobEvent(
                    {CreateAttribution(333, "App2")}, "job2", bucketStartTimeNs + 600));
            events.push_back(
                CreateFinishScheduledJobEvent({CreateAttribution(333, "App2")}, "job2",
                                               bucketStartTimeNs + bucketSizeNs + 850));

            events.push_back(
                CreateStartScheduledJobEvent({CreateAttribution(444, "App3")}, "job3",
                                             bucketStartTimeNs + bucketSizeNs - 2));
            events.push_back(
                CreateFinishScheduledJobEvent({CreateAttribution(444, "App3")}, "job3",
                                              bucketStartTimeNs + bucketSizeNs + 900));

            events.push_back(CreateSyncStartEvent(attributions1, "ReadEmail",
                                                  bucketStartTimeNs + 50));
            events.push_back(CreateSyncEndEvent(attributions1, "ReadEmail",
                                                bucketStartTimeNs + 110));

            events.push_back(CreateSyncStartEvent(attributions2, "ReadEmail",
                                                  bucketStartTimeNs + 300));
            events.push_back(CreateSyncEndEvent(attributions2, "ReadEmail",
                                                bucketStartTimeNs + bucketSizeNs + 700));
            events.push_back(CreateSyncStartEvent(attributions2, "ReadDoc",
                                                  bucketStartTimeNs + 400));
            events.push_back(CreateSyncEndEvent(attributions2, "ReadDoc",
                                                bucketStartTimeNs + bucketSizeNs - 1));

            events.push_back(CreateSyncStartEvent(attributions3, "ReadDoc",
                                                  bucketStartTimeNs + 550));
            events.push_back(CreateSyncEndEvent(attributions3, "ReadDoc",
                                                bucketStartTimeNs + 800));
            events.push_back(CreateSyncStartEvent(attributions3, "ReadDoc",
                                                  bucketStartTimeNs + bucketSizeNs - 1));
            events.push_back(CreateSyncEndEvent(attributions3, "ReadDoc",
                                                bucketStartTimeNs + bucketSizeNs + 700));

            sortLogEventsByTimestamp(&events);

            for (const auto& event : events) {
                processor->OnLogEvent(event.get());
            }

            ConfigMetricsReportList reports;
            vector<uint8_t> buffer;
            processor->onDumpReport(cfgKey, bucketStartTimeNs + 2 * bucketSizeNs + 1, false,
                                    true, ADB_DUMP, FAST, &buffer);
            EXPECT_TRUE(buffer.size() > 0);
            EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
            backfillDimensionPath(&reports);
            backfillStringInReport(&reports);
            backfillStartEndTimestamp(&reports);

            EXPECT_EQ(reports.reports_size(), 1);
            EXPECT_EQ(reports.reports(0).metrics_size(), 1);
            StatsLogReport::DurationMetricDataWrapper metrics;
            sortMetricDataByDimensionsValue(
                    reports.reports(0).metrics(0).duration_metrics(), &metrics);

            if (aggregationType == DurationMetric::SUM) {
                EXPECT_EQ(metrics.data_size(), 3);
                auto data = metrics.data(0);
                ValidateAttributionUidDimension(
                    data.dimensions_in_what(), android::util::SCHEDULED_JOB_STATE_CHANGED, 111);
                EXPECT_EQ(data.bucket_info_size(), 1);
                EXPECT_EQ(data.bucket_info(0).duration_nanos(), 101 - 50);
                EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(),
                          bucketStartTimeNs);
                EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(),
                    bucketStartTimeNs + bucketSizeNs);

                data = metrics.data(1);
                ValidateAttributionUidDimension(
                    data.dimensions_in_what(), android::util::SCHEDULED_JOB_STATE_CHANGED, 333);
                EXPECT_EQ(data.bucket_info_size(), 2);
                EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs);
                EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(),
                          bucketStartTimeNs + bucketSizeNs);
                EXPECT_EQ(data.bucket_info(0).duration_nanos(), 500 - 300 + bucketSizeNs - 600);
                EXPECT_EQ(data.bucket_info(1).duration_nanos(), 700);
                EXPECT_EQ(data.bucket_info(1).start_bucket_elapsed_nanos(),
                          bucketStartTimeNs + bucketSizeNs);
                EXPECT_EQ(data.bucket_info(1).end_bucket_elapsed_nanos(),
                          bucketStartTimeNs + 2 * bucketSizeNs);

                data = metrics.data(2);
                ValidateAttributionUidDimension(
                    data.dimensions_in_what(), android::util::SCHEDULED_JOB_STATE_CHANGED, 444);
                EXPECT_EQ(data.bucket_info_size(), 2);
                EXPECT_EQ(data.bucket_info(0).duration_nanos(), 1);
                EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs);
                EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(),
                          bucketStartTimeNs + bucketSizeNs);
                EXPECT_EQ(data.bucket_info(1).duration_nanos(), 700);
                EXPECT_EQ(data.bucket_info(1).start_bucket_elapsed_nanos(),
                          bucketStartTimeNs + bucketSizeNs);
                EXPECT_EQ(data.bucket_info(1).end_bucket_elapsed_nanos(),
                          bucketStartTimeNs + 2 * bucketSizeNs);
            } else {
                EXPECT_EQ(metrics.data_size(), 3);
                auto data = metrics.data(0);
                ValidateAttributionUidDimension(
                    data.dimensions_in_what(), android::util::SCHEDULED_JOB_STATE_CHANGED, 111);
                EXPECT_EQ(data.bucket_info_size(), 1);
                EXPECT_EQ(data.bucket_info(0).duration_nanos(), 101 - 50);
                EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(),
                          bucketStartTimeNs);
                EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(),
                    bucketStartTimeNs + bucketSizeNs);

                data = metrics.data(1);
                ValidateAttributionUidDimension(
                    data.dimensions_in_what(), android::util::SCHEDULED_JOB_STATE_CHANGED, 333);
                EXPECT_EQ(data.bucket_info_size(), 2);
                EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs);
                EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(),
                          bucketStartTimeNs + bucketSizeNs);
                EXPECT_EQ(data.bucket_info(0).duration_nanos(), 500 - 300);
                EXPECT_EQ(data.bucket_info(1).duration_nanos(), bucketSizeNs - 600 + 700);
                EXPECT_EQ(data.bucket_info(1).start_bucket_elapsed_nanos(),
                          bucketStartTimeNs + bucketSizeNs);
                EXPECT_EQ(data.bucket_info(1).end_bucket_elapsed_nanos(),
                          bucketStartTimeNs + 2 * bucketSizeNs);

                data = metrics.data(2);
                ValidateAttributionUidDimension(
                    data.dimensions_in_what(), android::util::SCHEDULED_JOB_STATE_CHANGED, 444);
                EXPECT_EQ(data.bucket_info_size(), 1);
                EXPECT_EQ(data.bucket_info(0).duration_nanos(), 701);
                EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(),
                          bucketStartTimeNs + bucketSizeNs);
                EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(),
                          bucketStartTimeNs + 2 * bucketSizeNs);
            }
        }
    }
}

namespace {

StatsdConfig createDurationMetric_PartialLink_SimpleConditionConfig(
        DurationMetric::AggregationType aggregationType) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT"); // LogEvent defaults to UID of root.
    *config.add_atom_matcher() = CreateStartScheduledJobAtomMatcher();
    *config.add_atom_matcher() = CreateFinishScheduledJobAtomMatcher();
    *config.add_atom_matcher() = CreateSyncStartAtomMatcher();
    *config.add_atom_matcher() = CreateSyncEndAtomMatcher();

    auto scheduledJobPredicate = CreateScheduledJobPredicate();
    auto dimensions = scheduledJobPredicate.mutable_simple_predicate()->mutable_dimensions();
    *dimensions = CreateAttributionUidDimensions(
                android::util::SCHEDULED_JOB_STATE_CHANGED, {Position::FIRST});
    dimensions->add_child()->set_field(2);  // job name field.

    auto isSyncingPredicate = CreateIsSyncingPredicate();
    auto syncDimension = isSyncingPredicate.mutable_simple_predicate()->mutable_dimensions();
    *syncDimension = CreateAttributionUidDimensions(
            android::util::SYNC_STATE_CHANGED, {Position::FIRST});
    syncDimension->add_child()->set_field(2 /* name field*/);

    *config.add_predicate() = scheduledJobPredicate;
    *config.add_predicate() = isSyncingPredicate;

    auto metric = config.add_duration_metric();
    metric->set_bucket(FIVE_MINUTES);
    metric->set_id(StringToId("scheduledJob"));
    metric->set_what(scheduledJobPredicate.id());
    metric->set_condition(isSyncingPredicate.id());
    metric->set_aggregation_type(aggregationType);
    *metric->mutable_dimensions_in_what() = CreateAttributionUidDimensions(
            android::util::SCHEDULED_JOB_STATE_CHANGED, {Position::FIRST});
    *metric->mutable_dimensions_in_condition() = *syncDimension;

    auto links = metric->add_links();
    links->set_condition(isSyncingPredicate.id());
    *links->mutable_fields_in_what() =
            CreateAttributionUidDimensions(
                android::util::SCHEDULED_JOB_STATE_CHANGED, {Position::FIRST});
    *links->mutable_fields_in_condition() =
            CreateAttributionUidDimensions(android::util::SYNC_STATE_CHANGED, {Position::FIRST});
    return config;
}

}  // namespace

TEST(DimensionInConditionE2eTest, TestDurationMetric_PartialLink_SimpleCondition) {
    for (auto aggregationType : {DurationMetric::SUM, DurationMetric::MAX_SPARSE}) {
        ConfigKey cfgKey;
        auto config = createDurationMetric_PartialLink_SimpleConditionConfig(
                aggregationType);
        int64_t bucketStartTimeNs = 10000000000;
        int64_t bucketSizeNs =
                TimeUnitToBucketSizeInMillis(config.duration_metric(0).bucket()) * 1000000LL;

        auto processor = CreateStatsLogProcessor(
                bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);
        EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
        EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());

        std::vector<AttributionNodeInternal> attributions1 = {
                CreateAttribution(111, "App1"), CreateAttribution(222, "GMSCoreModule1"),
                CreateAttribution(222, "GMSCoreModule2")};

        std::vector<AttributionNodeInternal> attributions2 = {
                CreateAttribution(333, "App2"), CreateAttribution(222, "GMSCoreModule1"),
                CreateAttribution(555, "GMSCoreModule2")};

        std::vector<AttributionNodeInternal> attributions3 = {
                CreateAttribution(444, "App3"), CreateAttribution(222, "GMSCoreModule1"),
                CreateAttribution(555, "GMSCoreModule2")};

        std::vector<std::unique_ptr<LogEvent>> events;

        events.push_back(CreateStartScheduledJobEvent(
                {CreateAttribution(111, "App1")}, "job1", bucketStartTimeNs + 1));
        events.push_back(CreateFinishScheduledJobEvent(
                {CreateAttribution(111, "App1")}, "job1",bucketStartTimeNs + 101));

        events.push_back(CreateStartScheduledJobEvent(
                {CreateAttribution(333, "App2")}, "job2", bucketStartTimeNs + 201));
        events.push_back(CreateFinishScheduledJobEvent(
                {CreateAttribution(333, "App2")}, "job2",bucketStartTimeNs + 500));
        events.push_back(CreateStartScheduledJobEvent(
                {CreateAttribution(333, "App2")}, "job2", bucketStartTimeNs + 600));
        events.push_back(CreateFinishScheduledJobEvent(
                {CreateAttribution(333, "App2")}, "job2", bucketStartTimeNs + bucketSizeNs + 850));

        events.push_back(
            CreateStartScheduledJobEvent({CreateAttribution(444, "App3")}, "job3",
                                         bucketStartTimeNs + bucketSizeNs - 2));
        events.push_back(
            CreateFinishScheduledJobEvent({CreateAttribution(444, "App3")}, "job3",
                                          bucketStartTimeNs + bucketSizeNs + 900));

        events.push_back(CreateSyncStartEvent(attributions1, "ReadEmail",
                                              bucketStartTimeNs + 50));
        events.push_back(CreateSyncEndEvent(attributions1, "ReadEmail",
                                            bucketStartTimeNs + 110));

        events.push_back(CreateSyncStartEvent(attributions2, "ReadEmail",
                                              bucketStartTimeNs + 300));
        events.push_back(CreateSyncEndEvent(attributions2, "ReadEmail",
                                            bucketStartTimeNs + bucketSizeNs + 700));
        events.push_back(CreateSyncStartEvent(attributions2, "ReadDoc",
                                              bucketStartTimeNs + 400));
        events.push_back(CreateSyncEndEvent(attributions2, "ReadDoc",
                                            bucketStartTimeNs + bucketSizeNs - 1));

        events.push_back(CreateSyncStartEvent(attributions3, "ReadDoc",
                                              bucketStartTimeNs + 550));
        events.push_back(CreateSyncEndEvent(attributions3, "ReadDoc",
                                            bucketStartTimeNs + 800));
        events.push_back(CreateSyncStartEvent(attributions3, "ReadDoc",
                                              bucketStartTimeNs + bucketSizeNs - 1));
        events.push_back(CreateSyncEndEvent(attributions3, "ReadDoc",
                                            bucketStartTimeNs + bucketSizeNs + 700));

        sortLogEventsByTimestamp(&events);

        for (const auto& event : events) {
            processor->OnLogEvent(event.get());
        }

        ConfigMetricsReportList reports;
        vector<uint8_t> buffer;
        processor->onDumpReport(cfgKey, bucketStartTimeNs + 2 * bucketSizeNs + 1, false, true,
                                ADB_DUMP, FAST, &buffer);
        EXPECT_TRUE(buffer.size() > 0);
        EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
        backfillDimensionPath(&reports);
        backfillStringInReport(&reports);
        backfillStartEndTimestamp(&reports);

        EXPECT_EQ(reports.reports_size(), 1);
        EXPECT_EQ(reports.reports(0).metrics_size(), 1);
        StatsLogReport::DurationMetricDataWrapper metrics;
        sortMetricDataByDimensionsValue(
                reports.reports(0).metrics(0).duration_metrics(), &metrics);

        if (aggregationType == DurationMetric::SUM) {
            EXPECT_EQ(4, metrics.data_size());
            auto data = metrics.data(0);
            ValidateAttributionUidDimension(
                data.dimensions_in_what(), android::util::SCHEDULED_JOB_STATE_CHANGED, 111);
            ValidateAttributionUidDimension(
                data.dimensions_in_condition(), android::util::SYNC_STATE_CHANGED, 111);
            EXPECT_EQ("ReadEmail",
                      data.dimensions_in_condition().value_tuple().dimensions_value(1).value_str());
            EXPECT_EQ(data.bucket_info_size(), 1);
            EXPECT_EQ(data.bucket_info(0).duration_nanos(), 101 - 50);
            EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(),
                      bucketStartTimeNs);
            EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(),
                bucketStartTimeNs + bucketSizeNs);

            data = metrics.data(1);
            ValidateAttributionUidDimension(
                data.dimensions_in_what(), android::util::SCHEDULED_JOB_STATE_CHANGED, 333);
            ValidateAttributionUidDimension(
                data.dimensions_in_condition(), android::util::SYNC_STATE_CHANGED, 333);
            EXPECT_EQ("ReadDoc",
                      data.dimensions_in_condition().value_tuple().dimensions_value(1).value_str());
            EXPECT_EQ(data.bucket_info_size(), 1);
            EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs);
            EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(),
                      bucketStartTimeNs + bucketSizeNs);
            EXPECT_EQ(data.bucket_info(0).duration_nanos(), bucketSizeNs - 1 - 400 - 100);

            data = metrics.data(2);
            ValidateAttributionUidDimension(
                data.dimensions_in_what(), android::util::SCHEDULED_JOB_STATE_CHANGED, 333);
            ValidateAttributionUidDimension(
                data.dimensions_in_condition(), android::util::SYNC_STATE_CHANGED, 333);
            EXPECT_EQ("ReadEmail",
                      data.dimensions_in_condition().value_tuple().dimensions_value(1).value_str());
            EXPECT_EQ(data.bucket_info_size(), 2);
            EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs);
            EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(),
                      bucketStartTimeNs + bucketSizeNs);
            EXPECT_EQ(data.bucket_info(0).duration_nanos(), 500 - 300 + bucketSizeNs - 600);
            EXPECT_EQ(data.bucket_info(1).duration_nanos(), 700);
            EXPECT_EQ(data.bucket_info(1).start_bucket_elapsed_nanos(),
                      bucketStartTimeNs + bucketSizeNs);
            EXPECT_EQ(data.bucket_info(1).end_bucket_elapsed_nanos(),
                      bucketStartTimeNs + 2 * bucketSizeNs);

            data = metrics.data(3);
            ValidateAttributionUidDimension(
                data.dimensions_in_what(), android::util::SCHEDULED_JOB_STATE_CHANGED, 444);
            ValidateAttributionUidDimension(
                data.dimensions_in_condition(), android::util::SYNC_STATE_CHANGED, 444);
            EXPECT_EQ("ReadDoc",
                      data.dimensions_in_condition().value_tuple().dimensions_value(1).value_str());
            EXPECT_EQ(data.bucket_info_size(), 2);
            EXPECT_EQ(data.bucket_info(0).duration_nanos(), 1);
            EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs);
            EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(),
                      bucketStartTimeNs + bucketSizeNs);
            EXPECT_EQ(data.bucket_info(1).duration_nanos(), 700);
            EXPECT_EQ(data.bucket_info(1).start_bucket_elapsed_nanos(),
                      bucketStartTimeNs + bucketSizeNs);
            EXPECT_EQ(data.bucket_info(1).end_bucket_elapsed_nanos(),
                      bucketStartTimeNs + 2 * bucketSizeNs);
        } else {
            EXPECT_EQ(metrics.data_size(), 4);
            auto data = metrics.data(0);
            ValidateAttributionUidDimension(
                data.dimensions_in_what(), android::util::SCHEDULED_JOB_STATE_CHANGED, 111);
            ValidateAttributionUidDimension(
                data.dimensions_in_condition(), android::util::SYNC_STATE_CHANGED, 111);
            EXPECT_EQ("ReadEmail",
                      data.dimensions_in_condition().value_tuple().dimensions_value(1).value_str());
            EXPECT_EQ(data.bucket_info_size(), 1);
            EXPECT_EQ(data.bucket_info(0).duration_nanos(), 101 - 50);
            EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(),
                      bucketStartTimeNs);
            EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(),
                bucketStartTimeNs + bucketSizeNs);

            data = metrics.data(1);
            ValidateAttributionUidDimension(
                data.dimensions_in_what(), android::util::SCHEDULED_JOB_STATE_CHANGED, 333);
            ValidateAttributionUidDimension(
                data.dimensions_in_condition(), android::util::SYNC_STATE_CHANGED, 333);
            EXPECT_EQ("ReadDoc",
                      data.dimensions_in_condition().value_tuple().dimensions_value(1).value_str());
            EXPECT_EQ(data.bucket_info_size(), 2);
            EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs);
            EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(),
                      bucketStartTimeNs + bucketSizeNs);
            EXPECT_EQ(data.bucket_info(0).duration_nanos(), 100);
            EXPECT_EQ(data.bucket_info(1).start_bucket_elapsed_nanos(),
                      bucketStartTimeNs + bucketSizeNs);
            EXPECT_EQ(data.bucket_info(1).end_bucket_elapsed_nanos(),
                      bucketStartTimeNs + 2 * bucketSizeNs);
            EXPECT_EQ(data.bucket_info(1).duration_nanos(), bucketSizeNs - 1 - 600);

            data = metrics.data(2);
            ValidateAttributionUidDimension(
                data.dimensions_in_what(), android::util::SCHEDULED_JOB_STATE_CHANGED, 333);
            ValidateAttributionUidDimension(
                data.dimensions_in_condition(), android::util::SYNC_STATE_CHANGED, 333);
            EXPECT_EQ("ReadEmail",
                      data.dimensions_in_condition().value_tuple().dimensions_value(1).value_str());
            EXPECT_EQ(data.bucket_info_size(), 2);
            EXPECT_EQ(data.bucket_info(0).start_bucket_elapsed_nanos(), bucketStartTimeNs);
            EXPECT_EQ(data.bucket_info(0).end_bucket_elapsed_nanos(),
                      bucketStartTimeNs + bucketSizeNs);
            EXPECT_EQ(data.bucket_info(0).duration_nanos(), 500 - 300);
            EXPECT_EQ(data.bucket_info(1).duration_nanos(), bucketSizeNs - 600 + 700);
            EXPECT_EQ(data.bucket_info(1).start_bucket_elapsed_nanos(),
                      bucketStartTimeNs + bucketSizeNs);
            EXPECT_EQ(data.bucket_info(1).end_bucket_elapsed_nanos(),
                      bucketStartTimeNs + 2 * bucketSizeNs);

            data = metrics.data(3);
            ValidateAttributionUidDimension(
                data.dimensions_in_what(), android::util::SCHEDULED_JOB_STATE_CHANGED, 444);
            ValidateAttributionUidDimension(
                data.dimensions_in_condition(), android::util::SYNC_STATE_CHANGED, 444);
            EXPECT_EQ("ReadDoc",
                      data.dimensions_in_condition().value_tuple().dimensions_value(1).value_str());
            EXPECT_EQ(data.bucket_info_size(), 1);
            EXPECT_EQ(data.bucket_info(0).duration_nanos(), 701);
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
