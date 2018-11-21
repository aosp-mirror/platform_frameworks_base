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

StatsdConfig CreateStatsdConfigForPushedEvent(const GaugeMetric::SamplingType sampling_type) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT"); // LogEvent defaults to UID of root.
    *config.add_atom_matcher() = CreateMoveToBackgroundAtomMatcher();
    *config.add_atom_matcher() = CreateMoveToForegroundAtomMatcher();

    auto atomMatcher = CreateSimpleAtomMatcher("", android::util::APP_START_OCCURRED);
    *config.add_atom_matcher() = atomMatcher;

    auto isInBackgroundPredicate = CreateIsInBackgroundPredicate();
    *isInBackgroundPredicate.mutable_simple_predicate()->mutable_dimensions() =
        CreateDimensions(android::util::ACTIVITY_FOREGROUND_STATE_CHANGED, {1 /* uid field */ });
    *config.add_predicate() = isInBackgroundPredicate;

    auto gaugeMetric = config.add_gauge_metric();
    gaugeMetric->set_id(123456);
    gaugeMetric->set_what(atomMatcher.id());
    gaugeMetric->set_condition(isInBackgroundPredicate.id());
    gaugeMetric->mutable_gauge_fields_filter()->set_include_all(false);
    gaugeMetric->set_sampling_type(sampling_type);
    auto fieldMatcher = gaugeMetric->mutable_gauge_fields_filter()->mutable_fields();
    fieldMatcher->set_field(android::util::APP_START_OCCURRED);
    fieldMatcher->add_child()->set_field(3);  // type (enum)
    fieldMatcher->add_child()->set_field(4);  // activity_name(str)
    fieldMatcher->add_child()->set_field(7);  // activity_start_msec(int64)
    *gaugeMetric->mutable_dimensions_in_what() =
        CreateDimensions(android::util::APP_START_OCCURRED, {1 /* uid field */ });
    gaugeMetric->set_bucket(FIVE_MINUTES);

    auto links = gaugeMetric->add_links();
    links->set_condition(isInBackgroundPredicate.id());
    auto dimensionWhat = links->mutable_fields_in_what();
    dimensionWhat->set_field(android::util::APP_START_OCCURRED);
    dimensionWhat->add_child()->set_field(1);  // uid field.
    auto dimensionCondition = links->mutable_fields_in_condition();
    dimensionCondition->set_field(android::util::ACTIVITY_FOREGROUND_STATE_CHANGED);
    dimensionCondition->add_child()->set_field(1);  // uid field.
    return config;
}

std::unique_ptr<LogEvent> CreateAppStartOccurredEvent(
    const int uid, const string& pkg_name, AppStartOccurred::TransitionType type,
    const string& activity_name, const string& calling_pkg_name, const bool is_instant_app,
    int64_t activity_start_msec, uint64_t timestampNs) {
    auto logEvent = std::make_unique<LogEvent>(
        android::util::APP_START_OCCURRED, timestampNs);
    logEvent->write(uid);
    logEvent->write(pkg_name);
    logEvent->write(type);
    logEvent->write(activity_name);
    logEvent->write(calling_pkg_name);
    logEvent->write(is_instant_app);
    logEvent->write(activity_start_msec);
    logEvent->init();
    return logEvent;
}

}  // namespace

TEST(GaugeMetricE2eTest, TestMultipleFieldsForPushedEvent) {
    for (const auto& sampling_type :
            { GaugeMetric::FIRST_N_SAMPLES, GaugeMetric:: RANDOM_ONE_SAMPLE }) {
        auto config = CreateStatsdConfigForPushedEvent(sampling_type);
        int64_t bucketStartTimeNs = 10000000000;
        int64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.gauge_metric(0).bucket()) * 1000000;

        ConfigKey cfgKey;
        auto processor = CreateStatsLogProcessor(
                bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);
        EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
        EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());

        int appUid1 = 123;
        int appUid2 = 456;
        std::vector<std::unique_ptr<LogEvent>> events;
        events.push_back(CreateMoveToBackgroundEvent(appUid1, bucketStartTimeNs + 15));
        events.push_back(CreateMoveToForegroundEvent(
                appUid1, bucketStartTimeNs + bucketSizeNs + 250));
        events.push_back(CreateMoveToBackgroundEvent(
                appUid1, bucketStartTimeNs + bucketSizeNs + 350));
        events.push_back(CreateMoveToForegroundEvent(
            appUid1, bucketStartTimeNs + 2 * bucketSizeNs + 100));


        events.push_back(CreateAppStartOccurredEvent(
            appUid1, "app1", AppStartOccurred::WARM, "activity_name1", "calling_pkg_name1",
            true /*is_instant_app*/, 101 /*activity_start_msec*/, bucketStartTimeNs + 10));
        events.push_back(CreateAppStartOccurredEvent(
            appUid1, "app1", AppStartOccurred::HOT, "activity_name2", "calling_pkg_name2",
            true /*is_instant_app*/, 102 /*activity_start_msec*/, bucketStartTimeNs + 20));
        events.push_back(CreateAppStartOccurredEvent(
            appUid1, "app1", AppStartOccurred::COLD, "activity_name3", "calling_pkg_name3",
            true /*is_instant_app*/, 103 /*activity_start_msec*/, bucketStartTimeNs + 30));
        events.push_back(CreateAppStartOccurredEvent(
            appUid1, "app1", AppStartOccurred::WARM, "activity_name4", "calling_pkg_name4",
            true /*is_instant_app*/, 104 /*activity_start_msec*/,
            bucketStartTimeNs + bucketSizeNs + 30));
        events.push_back(CreateAppStartOccurredEvent(
            appUid1, "app1", AppStartOccurred::COLD, "activity_name5", "calling_pkg_name5",
            true /*is_instant_app*/, 105 /*activity_start_msec*/,
            bucketStartTimeNs + 2 * bucketSizeNs));
        events.push_back(CreateAppStartOccurredEvent(
            appUid1, "app1", AppStartOccurred::HOT, "activity_name6", "calling_pkg_name6",
            false /*is_instant_app*/, 106 /*activity_start_msec*/,
            bucketStartTimeNs + 2 * bucketSizeNs + 10));

        events.push_back(CreateMoveToBackgroundEvent(
                appUid2, bucketStartTimeNs + bucketSizeNs + 10));
        events.push_back(CreateAppStartOccurredEvent(
            appUid2, "app2", AppStartOccurred::COLD, "activity_name7", "calling_pkg_name7",
            true /*is_instant_app*/, 201 /*activity_start_msec*/,
            bucketStartTimeNs + 2 * bucketSizeNs + 10));

        sortLogEventsByTimestamp(&events);

        for (const auto& event : events) {
            processor->OnLogEvent(event.get());
        }
        ConfigMetricsReportList reports;
        vector<uint8_t> buffer;
        processor->onDumpReport(cfgKey, bucketStartTimeNs + 3 * bucketSizeNs, false, true,
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
        EXPECT_EQ(2, gaugeMetrics.data_size());

        auto data = gaugeMetrics.data(0);
        EXPECT_EQ(android::util::APP_START_OCCURRED, data.dimensions_in_what().field());
        EXPECT_EQ(1, data.dimensions_in_what().value_tuple().dimensions_value_size());
        EXPECT_EQ(1 /* uid field */,
                  data.dimensions_in_what().value_tuple().dimensions_value(0).field());
        EXPECT_EQ(appUid1, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
        EXPECT_EQ(3, data.bucket_info_size());
        if (sampling_type == GaugeMetric::FIRST_N_SAMPLES) {
            EXPECT_EQ(2, data.bucket_info(0).atom_size());
            EXPECT_EQ(2, data.bucket_info(0).elapsed_timestamp_nanos_size());
            EXPECT_EQ(0, data.bucket_info(0).wall_clock_timestamp_nanos_size());
            EXPECT_EQ(bucketStartTimeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
            EXPECT_EQ(bucketStartTimeNs + bucketSizeNs,
                      data.bucket_info(0).end_bucket_elapsed_nanos());
            EXPECT_EQ(AppStartOccurred::HOT,
                      data.bucket_info(0).atom(0).app_start_occurred().type());
            EXPECT_EQ("activity_name2",
                      data.bucket_info(0).atom(0).app_start_occurred().activity_name());
            EXPECT_EQ(102L,
                      data.bucket_info(0).atom(0).app_start_occurred().activity_start_millis());
            EXPECT_EQ(AppStartOccurred::COLD,
                      data.bucket_info(0).atom(1).app_start_occurred().type());
            EXPECT_EQ("activity_name3",
                      data.bucket_info(0).atom(1).app_start_occurred().activity_name());
            EXPECT_EQ(103L,
                      data.bucket_info(0).atom(1).app_start_occurred().activity_start_millis());

            EXPECT_EQ(1, data.bucket_info(1).atom_size());
            EXPECT_EQ(1, data.bucket_info(1).elapsed_timestamp_nanos_size());
            EXPECT_EQ(bucketStartTimeNs + bucketSizeNs,
                      data.bucket_info(1).start_bucket_elapsed_nanos());
            EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs,
                      data.bucket_info(1).end_bucket_elapsed_nanos());
            EXPECT_EQ(AppStartOccurred::WARM,
                      data.bucket_info(1).atom(0).app_start_occurred().type());
            EXPECT_EQ("activity_name4",
                      data.bucket_info(1).atom(0).app_start_occurred().activity_name());
            EXPECT_EQ(104L,
                      data.bucket_info(1).atom(0).app_start_occurred().activity_start_millis());

            EXPECT_EQ(2, data.bucket_info(2).atom_size());
            EXPECT_EQ(2, data.bucket_info(2).elapsed_timestamp_nanos_size());
            EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs,
                      data.bucket_info(2).start_bucket_elapsed_nanos());
            EXPECT_EQ(bucketStartTimeNs + 3 * bucketSizeNs,
                      data.bucket_info(2).end_bucket_elapsed_nanos());
            EXPECT_EQ(AppStartOccurred::COLD,
                      data.bucket_info(2).atom(0).app_start_occurred().type());
            EXPECT_EQ("activity_name5",
                      data.bucket_info(2).atom(0).app_start_occurred().activity_name());
            EXPECT_EQ(105L,
                      data.bucket_info(2).atom(0).app_start_occurred().activity_start_millis());
            EXPECT_EQ(AppStartOccurred::HOT,
                      data.bucket_info(2).atom(1).app_start_occurred().type());
            EXPECT_EQ("activity_name6",
                      data.bucket_info(2).atom(1).app_start_occurred().activity_name());
            EXPECT_EQ(106L,
                      data.bucket_info(2).atom(1).app_start_occurred().activity_start_millis());
        } else {
            EXPECT_EQ(1, data.bucket_info(0).atom_size());
            EXPECT_EQ(1, data.bucket_info(0).elapsed_timestamp_nanos_size());
            EXPECT_EQ(bucketStartTimeNs, data.bucket_info(0).start_bucket_elapsed_nanos());
            EXPECT_EQ(bucketStartTimeNs + bucketSizeNs,
                      data.bucket_info(0).end_bucket_elapsed_nanos());
            EXPECT_EQ(AppStartOccurred::HOT,
                      data.bucket_info(0).atom(0).app_start_occurred().type());
            EXPECT_EQ("activity_name2",
                      data.bucket_info(0).atom(0).app_start_occurred().activity_name());
            EXPECT_EQ(102L,
                      data.bucket_info(0).atom(0).app_start_occurred().activity_start_millis());

            EXPECT_EQ(1, data.bucket_info(1).atom_size());
            EXPECT_EQ(1, data.bucket_info(1).elapsed_timestamp_nanos_size());
            EXPECT_EQ(bucketStartTimeNs + bucketSizeNs,
                      data.bucket_info(1).start_bucket_elapsed_nanos());
            EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs,
                      data.bucket_info(1).end_bucket_elapsed_nanos());
            EXPECT_EQ(AppStartOccurred::WARM,
                      data.bucket_info(1).atom(0).app_start_occurred().type());
            EXPECT_EQ("activity_name4",
                      data.bucket_info(1).atom(0).app_start_occurred().activity_name());
            EXPECT_EQ(104L,
                      data.bucket_info(1).atom(0).app_start_occurred().activity_start_millis());

            EXPECT_EQ(1, data.bucket_info(2).atom_size());
            EXPECT_EQ(1, data.bucket_info(2).elapsed_timestamp_nanos_size());
            EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs,
                      data.bucket_info(2).start_bucket_elapsed_nanos());
            EXPECT_EQ(bucketStartTimeNs + 3 * bucketSizeNs,
                      data.bucket_info(2).end_bucket_elapsed_nanos());
            EXPECT_EQ(AppStartOccurred::COLD,
                      data.bucket_info(2).atom(0).app_start_occurred().type());
            EXPECT_EQ("activity_name5",
                      data.bucket_info(2).atom(0).app_start_occurred().activity_name());
            EXPECT_EQ(105L,
                      data.bucket_info(2).atom(0).app_start_occurred().activity_start_millis());
        }

        data = gaugeMetrics.data(1);

        EXPECT_EQ(data.dimensions_in_what().field(), android::util::APP_START_OCCURRED);
        EXPECT_EQ(data.dimensions_in_what().value_tuple().dimensions_value_size(), 1);
        EXPECT_EQ(1 /* uid field */,
                  data.dimensions_in_what().value_tuple().dimensions_value(0).field());
        EXPECT_EQ(appUid2, data.dimensions_in_what().value_tuple().dimensions_value(0).value_int());
        EXPECT_EQ(1, data.bucket_info_size());
        EXPECT_EQ(1, data.bucket_info(0).atom_size());
        EXPECT_EQ(1, data.bucket_info(0).elapsed_timestamp_nanos_size());
        EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs,
                  data.bucket_info(0).start_bucket_elapsed_nanos());
        EXPECT_EQ(bucketStartTimeNs + 3 * bucketSizeNs,
                  data.bucket_info(0).end_bucket_elapsed_nanos());
        EXPECT_EQ(AppStartOccurred::COLD, data.bucket_info(0).atom(0).app_start_occurred().type());
        EXPECT_EQ("activity_name7",
                  data.bucket_info(0).atom(0).app_start_occurred().activity_name());
        EXPECT_EQ(201L, data.bucket_info(0).atom(0).app_start_occurred().activity_start_millis());
    }
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif

}  // namespace statsd
}  // namespace os
}  // namespace android
