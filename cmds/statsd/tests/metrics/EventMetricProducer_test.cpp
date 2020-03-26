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

#include "src/metrics/EventMetricProducer.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <stdio.h>

#include <vector>

#include "metrics_test_helper.h"
#include "stats_event.h"
#include "tests/statsd_test_util.h"

using namespace testing;
using android::sp;
using std::set;
using std::unordered_map;
using std::vector;

#ifdef __ANDROID__

namespace android {
namespace os {
namespace statsd {

const ConfigKey kConfigKey(0, 12345);

namespace {
void makeLogEvent(LogEvent* logEvent, int32_t atomId, int64_t timestampNs, string str) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, atomId);
    AStatsEvent_overwriteTimestamp(statsEvent, timestampNs);

    AStatsEvent_writeString(statsEvent, str.c_str());
    AStatsEvent_build(statsEvent);

    size_t size;
    uint8_t* buf = AStatsEvent_getBuffer(statsEvent, &size);
    logEvent->parseBuffer(buf, size);
    AStatsEvent_release(statsEvent);
}
}  // anonymous namespace

TEST(EventMetricProducerTest, TestNoCondition) {
    int64_t bucketStartTimeNs = 10000000000;
    int64_t eventStartTimeNs = bucketStartTimeNs + 1;
    int64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;

    EventMetric metric;
    metric.set_id(1);

    LogEvent event1(/*uid=*/0, /*pid=*/0);
    CreateNoValuesLogEvent(&event1, 1 /*tagId*/, bucketStartTimeNs + 1);

    LogEvent event2(/*uid=*/0, /*pid=*/0);
    CreateNoValuesLogEvent(&event2, 1 /*tagId*/, bucketStartTimeNs + 2);

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    EventMetricProducer eventProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard,
                                      bucketStartTimeNs);

    eventProducer.onMatchedLogEvent(1 /*matcher index*/, event1);
    eventProducer.onMatchedLogEvent(1 /*matcher index*/, event2);

    // Check dump report content.
    ProtoOutputStream output;
    std::set<string> strSet;
    eventProducer.onDumpReport(bucketStartTimeNs + 20, true /*include current partial bucket*/,
                               true /*erase data*/, FAST, &strSet, &output);

    StatsLogReport report = outputStreamToProto(&output);
    EXPECT_TRUE(report.has_event_metrics());
    EXPECT_EQ(2, report.event_metrics().data_size());
    EXPECT_EQ(bucketStartTimeNs + 1, report.event_metrics().data(0).elapsed_timestamp_nanos());
    EXPECT_EQ(bucketStartTimeNs + 2, report.event_metrics().data(1).elapsed_timestamp_nanos());
}

TEST(EventMetricProducerTest, TestEventsWithNonSlicedCondition) {
    int64_t bucketStartTimeNs = 10000000000;
    int64_t eventStartTimeNs = bucketStartTimeNs + 1;
    int64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;

    EventMetric metric;
    metric.set_id(1);
    metric.set_condition(StringToId("SCREEN_ON"));

    LogEvent event1(/*uid=*/0, /*pid=*/0);
    CreateNoValuesLogEvent(&event1, 1 /*tagId*/, bucketStartTimeNs + 1);

    LogEvent event2(/*uid=*/0, /*pid=*/0);
    CreateNoValuesLogEvent(&event2, 1 /*tagId*/, bucketStartTimeNs + 10);

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    EventMetricProducer eventProducer(kConfigKey, metric, 1, wizard, bucketStartTimeNs);

    eventProducer.onConditionChanged(true /*condition*/, bucketStartTimeNs);
    eventProducer.onMatchedLogEvent(1 /*matcher index*/, event1);

    eventProducer.onConditionChanged(false /*condition*/, bucketStartTimeNs + 2);

    eventProducer.onMatchedLogEvent(1 /*matcher index*/, event2);

    // Check dump report content.
    ProtoOutputStream output;
    std::set<string> strSet;
    eventProducer.onDumpReport(bucketStartTimeNs + 20, true /*include current partial bucket*/,
                               true /*erase data*/, FAST, &strSet, &output);

    StatsLogReport report = outputStreamToProto(&output);
    EXPECT_TRUE(report.has_event_metrics());
    EXPECT_EQ(1, report.event_metrics().data_size());
    EXPECT_EQ(bucketStartTimeNs + 1, report.event_metrics().data(0).elapsed_timestamp_nanos());
}

TEST(EventMetricProducerTest, TestEventsWithSlicedCondition) {
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;

    int tagId = 1;
    int conditionTagId = 2;

    EventMetric metric;
    metric.set_id(1);
    metric.set_condition(StringToId("APP_IN_BACKGROUND_PER_UID_AND_SCREEN_ON"));
    MetricConditionLink* link = metric.add_links();
    link->set_condition(StringToId("APP_IN_BACKGROUND_PER_UID"));
    buildSimpleAtomFieldMatcher(tagId, 1, link->mutable_fields_in_what());
    buildSimpleAtomFieldMatcher(conditionTagId, 2, link->mutable_fields_in_condition());

    LogEvent event1(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event1, 1 /*tagId*/, bucketStartTimeNs + 1, "111");
    ConditionKey key1;
    key1[StringToId("APP_IN_BACKGROUND_PER_UID")] = {
            getMockedDimensionKey(conditionTagId, 2, "111")};

    LogEvent event2(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event2, 1 /*tagId*/, bucketStartTimeNs + 10, "222");
    ConditionKey key2;
    key2[StringToId("APP_IN_BACKGROUND_PER_UID")] = {
            getMockedDimensionKey(conditionTagId, 2, "222")};

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    // Condition is false for first event.
    EXPECT_CALL(*wizard, query(_, key1, _)).WillOnce(Return(ConditionState::kFalse));
    // Condition is true for second event.
    EXPECT_CALL(*wizard, query(_, key2, _)).WillOnce(Return(ConditionState::kTrue));

    EventMetricProducer eventProducer(kConfigKey, metric, 1, wizard, bucketStartTimeNs);

    eventProducer.onMatchedLogEvent(1 /*matcher index*/, event1);
    eventProducer.onMatchedLogEvent(1 /*matcher index*/, event2);

    // Check dump report content.
    ProtoOutputStream output;
    std::set<string> strSet;
    eventProducer.onDumpReport(bucketStartTimeNs + 20, true /*include current partial bucket*/,
                               true /*erase data*/, FAST, &strSet, &output);

    StatsLogReport report = outputStreamToProto(&output);
    EXPECT_TRUE(report.has_event_metrics());
    EXPECT_EQ(1, report.event_metrics().data_size());
    EXPECT_EQ(bucketStartTimeNs + 10, report.event_metrics().data(0).elapsed_timestamp_nanos());
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
