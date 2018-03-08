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


    std::vector<AttributionNodeInternal> attributions1 = {
            CreateAttribution(111, "App1"), CreateAttribution(222, "GMSCoreModule1"),
            CreateAttribution(222, "GMSCoreModule2")};

    std::vector<AttributionNodeInternal> attributions2 = {
            CreateAttribution(333, "App2"), CreateAttribution(222, "GMSCoreModule1"),
            CreateAttribution(555, "GMSCoreModule2")};

    std::vector<std::unique_ptr<LogEvent>> events;

    events.push_back(CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_OFF,
                                                   bucketStartTimeNs + 11));
    events.push_back(CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_ON,
                                                   bucketStartTimeNs + 40));

    events.push_back(CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_OFF,
                                                   bucketStartTimeNs + 102));
    events.push_back(CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_ON,
                                                   bucketStartTimeNs + 450));

    events.push_back(CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_OFF,
                                                   bucketStartTimeNs + 650));
    events.push_back(CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_ON,
                                                   bucketStartTimeNs + bucketSizeNs + 100));

    events.push_back(CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_OFF,
                                                   bucketStartTimeNs + bucketSizeNs + 640));
    events.push_back(CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_ON,
                                                   bucketStartTimeNs + bucketSizeNs + 650));

    events.push_back(CreateStartScheduledJobEvent(
            {CreateAttribution(9999, "")}, "job0", bucketStartTimeNs + 2));
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

    events.push_back(CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_OFF,
                                                   bucketStartTimeNs + 55));
    events.push_back(CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_ON,
                                                   bucketStartTimeNs + 120));
    events.push_back(CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_OFF,
                                                   bucketStartTimeNs + 121));
    events.push_back(CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_ON,
                                                   bucketStartTimeNs + 450));

    events.push_back(CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_OFF,
                                                   bucketStartTimeNs + 501));
    events.push_back(CreateScreenStateChangedEvent(android::view::DISPLAY_STATE_ON,
                                                   bucketStartTimeNs + bucketSizeNs + 100));

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
            {CreateAttribution(333, "App2")}, "job2",
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
