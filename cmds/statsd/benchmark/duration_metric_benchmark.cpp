/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include <vector>
#include "benchmark/benchmark.h"
#include "FieldValue.h"
#include "HashableDimensionKey.h"
#include "logd/LogEvent.h"
#include "stats_log_util.h"
#include "metric_util.h"

namespace android {
namespace os {
namespace statsd {

using std::vector;

static StatsdConfig CreateDurationMetricConfig_NoLink_AND_CombinationCondition(
        DurationMetric::AggregationType aggregationType, bool addExtraDimensionInCondition) {
    StatsdConfig config;
    *config.add_atom_matcher() = CreateStartScheduledJobAtomMatcher();
    *config.add_atom_matcher() = CreateFinishScheduledJobAtomMatcher();
    *config.add_atom_matcher() = CreateSyncStartAtomMatcher();
    *config.add_atom_matcher() = CreateSyncEndAtomMatcher();
    *config.add_atom_matcher() = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = CreateScreenTurnedOffAtomMatcher();

    auto scheduledJobPredicate = CreateScheduledJobPredicate();
    auto dimensions = scheduledJobPredicate.mutable_simple_predicate()->mutable_dimensions();
    dimensions->set_field(android::util::SCHEDULED_JOB_STATE_CHANGED);
    dimensions->add_child()->set_field(2);  // job name field.

    auto screenIsOffPredicate = CreateScreenIsOffPredicate();

    auto isSyncingPredicate = CreateIsSyncingPredicate();
    auto syncDimension = isSyncingPredicate.mutable_simple_predicate()->mutable_dimensions();
    *syncDimension = CreateAttributionUidAndTagDimensions(android::util::SYNC_STATE_CHANGED,
                                                          {Position::FIRST});
    if (addExtraDimensionInCondition) {
        syncDimension->add_child()->set_field(2 /* name field*/);
    }

    *config.add_predicate() = scheduledJobPredicate;
    *config.add_predicate() = screenIsOffPredicate;
    *config.add_predicate() = isSyncingPredicate;
    auto combinationPredicate = config.add_predicate();
    combinationPredicate->set_id(StringToId("CombinationPredicate"));
    combinationPredicate->mutable_combination()->set_operation(LogicalOperation::AND);
    addPredicateToPredicateCombination(screenIsOffPredicate, combinationPredicate);
    addPredicateToPredicateCombination(isSyncingPredicate, combinationPredicate);

    auto metric = config.add_duration_metric();
    metric->set_bucket(FIVE_MINUTES);
    metric->set_id(StringToId("scheduledJob"));
    metric->set_what(scheduledJobPredicate.id());
    metric->set_condition(combinationPredicate->id());
    metric->set_aggregation_type(aggregationType);
    auto dimensionWhat = metric->mutable_dimensions_in_what();
    dimensionWhat->set_field(android::util::SCHEDULED_JOB_STATE_CHANGED);
    dimensionWhat->add_child()->set_field(2);  // job name field.
    *metric->mutable_dimensions_in_condition() = CreateAttributionUidAndTagDimensions(
            android::util::SYNC_STATE_CHANGED, {Position::FIRST});
    return config;
}

static StatsdConfig CreateDurationMetricConfig_Link_AND_CombinationCondition(
        DurationMetric::AggregationType aggregationType, bool addExtraDimensionInCondition) {
    StatsdConfig config;
    *config.add_atom_matcher() = CreateStartScheduledJobAtomMatcher();
    *config.add_atom_matcher() = CreateFinishScheduledJobAtomMatcher();
    *config.add_atom_matcher() = CreateSyncStartAtomMatcher();
    *config.add_atom_matcher() = CreateSyncEndAtomMatcher();
    *config.add_atom_matcher() = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = CreateScreenTurnedOffAtomMatcher();

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

    auto screenIsOffPredicate = CreateScreenIsOffPredicate();

    *config.add_predicate() = scheduledJobPredicate;
    *config.add_predicate() = screenIsOffPredicate;
    *config.add_predicate() = isSyncingPredicate;
    auto combinationPredicate = config.add_predicate();
    combinationPredicate->set_id(StringToId("CombinationPredicate"));
    combinationPredicate->mutable_combination()->set_operation(LogicalOperation::AND);
    addPredicateToPredicateCombination(screenIsOffPredicate, combinationPredicate);
    addPredicateToPredicateCombination(isSyncingPredicate, combinationPredicate);

    auto metric = config.add_duration_metric();
    metric->set_bucket(FIVE_MINUTES);
    metric->set_id(StringToId("scheduledJob"));
    metric->set_what(scheduledJobPredicate.id());
    metric->set_condition(combinationPredicate->id());
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

static void BM_DurationMetricNoLink(benchmark::State& state) {
    ConfigKey cfgKey;
    auto config = CreateDurationMetricConfig_NoLink_AND_CombinationCondition(
            DurationMetric::SUM, false);
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.duration_metric(0).bucket()) * 1000000LL;

    std::vector<std::unique_ptr<LogEvent>> events;

    events.push_back(CreateScreenStateChangedEvent(bucketStartTimeNs + 11,
                                                   android::view::DISPLAY_STATE_OFF));
    events.push_back(
            CreateScreenStateChangedEvent(bucketStartTimeNs + 40, android::view::DISPLAY_STATE_ON));

    events.push_back(CreateScreenStateChangedEvent(bucketStartTimeNs + 102,
                                                   android::view::DISPLAY_STATE_OFF));
    events.push_back(CreateScreenStateChangedEvent(bucketStartTimeNs + 450,
                                                   android::view::DISPLAY_STATE_ON));

    events.push_back(CreateScreenStateChangedEvent(bucketStartTimeNs + 650,
                                                   android::view::DISPLAY_STATE_OFF));
    events.push_back(CreateScreenStateChangedEvent(bucketStartTimeNs + bucketSizeNs + 100,
                                                   android::view::DISPLAY_STATE_ON));

    events.push_back(CreateScreenStateChangedEvent(bucketStartTimeNs + bucketSizeNs + 640,
                                                   android::view::DISPLAY_STATE_OFF));
    events.push_back(CreateScreenStateChangedEvent(bucketStartTimeNs + bucketSizeNs + 650,
                                                   android::view::DISPLAY_STATE_ON));

    vector<int> attributionUids1 = {9999};
    vector<string> attributionTags1 = {""};
    events.push_back(CreateStartScheduledJobEvent(bucketStartTimeNs + 2, attributionUids1,
                                                  attributionTags1, "job0"));
    events.push_back(CreateFinishScheduledJobEvent(bucketStartTimeNs + 101, attributionUids1,
                                                   attributionTags1, "job0"));

    events.push_back(CreateStartScheduledJobEvent(bucketStartTimeNs + 201, attributionUids1,
                                                  attributionTags1, "job2"));
    events.push_back(CreateFinishScheduledJobEvent(bucketStartTimeNs + 500, attributionUids1,
                                                   attributionTags1, "job2"));

    vector<int> attributionUids2 = {8888};
    events.push_back(CreateStartScheduledJobEvent(bucketStartTimeNs + 600, attributionUids2,
                                                  attributionTags1, "job2"));
    events.push_back(CreateFinishScheduledJobEvent(bucketStartTimeNs + bucketSizeNs + 850,
                                                   attributionUids2, attributionTags1, "job2"));

    events.push_back(CreateStartScheduledJobEvent(bucketStartTimeNs + bucketSizeNs + 600,
                                                  attributionUids2, attributionTags1, "job1"));
    events.push_back(CreateFinishScheduledJobEvent(bucketStartTimeNs + bucketSizeNs + 900,
                                                   attributionUids2, attributionTags1, "job1"));

    vector<int> attributionUids3 = {111, 222, 222};
    vector<string> attributionTags3 = {"App1", "GMSCoreModule1", "GMSCoreModule2"};
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 10, attributionUids3,
                                          attributionTags3, "ReadEmail"));
    events.push_back(CreateSyncEndEvent(bucketStartTimeNs + 50, attributionUids3, attributionTags3,
                                        "ReadEmail"));

    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 200, attributionUids3,
                                          attributionTags3, "ReadEmail"));
    events.push_back(CreateSyncEndEvent(bucketStartTimeNs + bucketSizeNs + 300, attributionUids3,
                                        attributionTags3, "ReadEmail"));

    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 400, attributionUids3,
                                          attributionTags3, "ReadDoc"));
    events.push_back(CreateSyncEndEvent(bucketStartTimeNs + bucketSizeNs - 1, attributionUids3,
                                        attributionTags3, "ReadDoc"));

    vector<int> attributionUids4 = {333, 222, 555};
    vector<string> attributionTags4 = {"App2", "GMSCoreModule1", "GMSCoreModule2"};
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 401, attributionUids4,
                                          attributionTags4, "ReadEmail"));
    events.push_back(CreateSyncEndEvent(bucketStartTimeNs + bucketSizeNs + 700, attributionUids4,
                                        attributionTags4, "ReadEmail"));
    sortLogEventsByTimestamp(&events);

    while (state.KeepRunning()) {
        auto processor = CreateStatsLogProcessor(
                bucketStartTimeNs / NS_PER_SEC, config, cfgKey);
        for (const auto& event : events) {
            processor->OnLogEvent(event.get());
        }
    }
}

BENCHMARK(BM_DurationMetricNoLink);


static void BM_DurationMetricLink(benchmark::State& state) {
    ConfigKey cfgKey;
    auto config = CreateDurationMetricConfig_Link_AND_CombinationCondition(
        DurationMetric::SUM, false);
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.duration_metric(0).bucket()) * 1000000LL;

    std::vector<std::unique_ptr<LogEvent>> events;

    events.push_back(CreateScreenStateChangedEvent(bucketStartTimeNs + 55,
                                                   android::view::DISPLAY_STATE_OFF));
    events.push_back(CreateScreenStateChangedEvent(bucketStartTimeNs + 120,
                                                   android::view::DISPLAY_STATE_ON));
    events.push_back(CreateScreenStateChangedEvent(bucketStartTimeNs + 121,
                                                   android::view::DISPLAY_STATE_OFF));
    events.push_back(CreateScreenStateChangedEvent(bucketStartTimeNs + 450,
                                                   android::view::DISPLAY_STATE_ON));

    events.push_back(CreateScreenStateChangedEvent(bucketStartTimeNs + 501,
                                                   android::view::DISPLAY_STATE_OFF));
    events.push_back(CreateScreenStateChangedEvent(bucketStartTimeNs + bucketSizeNs + 100,
                                                   android::view::DISPLAY_STATE_ON));

    vector<int> attributionUids1 = {111};
    vector<string> attributionTags1 = {"App1"};
    events.push_back(CreateStartScheduledJobEvent(bucketStartTimeNs + 1, attributionUids1,
                                                  attributionTags1, "job1"));
    events.push_back(CreateFinishScheduledJobEvent(bucketStartTimeNs + 101, attributionUids1,
                                                   attributionTags1, "job1"));

    vector<int> attributionUids2 = {333};
    vector<string> attributionTags2 = {"App2"};
    events.push_back(CreateStartScheduledJobEvent(bucketStartTimeNs + 201, attributionUids2,
                                                  attributionTags2, "job2"));
    events.push_back(CreateFinishScheduledJobEvent(bucketStartTimeNs + 500, attributionUids2,
                                                   attributionTags2, "job2"));
    events.push_back(CreateStartScheduledJobEvent(bucketStartTimeNs + 600, attributionUids2,
                                                  attributionTags2, "job2"));
    events.push_back(CreateFinishScheduledJobEvent(bucketStartTimeNs + bucketSizeNs + 850,
                                                   attributionUids2, attributionTags2, "job2"));

    vector<int> attributionUids3 = {444};
    vector<string> attributionTags3 = {"App3"};
    events.push_back(CreateStartScheduledJobEvent(bucketStartTimeNs + bucketSizeNs - 2,
                                                  attributionUids3, attributionTags3, "job3"));
    events.push_back(CreateFinishScheduledJobEvent(bucketStartTimeNs + bucketSizeNs + 900,
                                                   attributionUids3, attributionTags3, "job3"));

    vector<int> attributionUids4 = {111, 222, 222};
    vector<string> attributionTags4 = {"App1", "GMSCoreModule1", "GMSCoreModule2"};
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 50, attributionUids4,
                                          attributionTags4, "ReadEmail"));
    events.push_back(CreateSyncEndEvent(bucketStartTimeNs + 110, attributionUids4, attributionTags4,
                                        "ReadEmail"));

    vector<int> attributionUids5 = {333, 222, 555};
    vector<string> attributionTags5 = {"App2", "GMSCoreModule1", "GMSCoreModule2"};
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 300, attributionUids5,
                                          attributionTags5, "ReadEmail"));
    events.push_back(CreateSyncEndEvent(bucketStartTimeNs + bucketSizeNs + 700, attributionUids5,
                                        attributionTags5, "ReadEmail"));
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 400, attributionUids5,
                                          attributionTags5, "ReadDoc"));
    events.push_back(CreateSyncEndEvent(bucketStartTimeNs + bucketSizeNs - 1, attributionUids5,
                                        attributionTags5, "ReadDoc"));

    vector<int> attributionUids6 = {444, 222, 555};
    vector<string> attributionTags6 = {"App3", "GMSCoreModule1", "GMSCoreModule2"};
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 550, attributionUids6,
                                          attributionTags6, "ReadDoc"));
    events.push_back(CreateSyncEndEvent(bucketStartTimeNs + 800, attributionUids6, attributionTags6,
                                        "ReadDoc"));
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + bucketSizeNs - 1, attributionUids6,
                                          attributionTags6, "ReadDoc"));
    events.push_back(CreateSyncEndEvent(bucketStartTimeNs + bucketSizeNs + 700, attributionUids6,
                                        attributionTags6, "ReadDoc"));
    sortLogEventsByTimestamp(&events);

    while (state.KeepRunning()) {
        auto processor = CreateStatsLogProcessor(
                bucketStartTimeNs / NS_PER_SEC, config, cfgKey);
        for (const auto& event : events) {
            processor->OnLogEvent(event.get());
        }
    }
}

BENCHMARK(BM_DurationMetricLink);

}  //  namespace statsd
}  //  namespace os
}  //  namespace android
