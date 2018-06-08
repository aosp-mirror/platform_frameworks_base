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
#include "metrics_test_helper.h"
#include "tests/statsd_test_util.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <stdio.h>
#include <vector>

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

TEST(EventMetricProducerTest, TestNoCondition) {
    int64_t bucketStartTimeNs = 10000000000;
    int64_t eventStartTimeNs = bucketStartTimeNs + 1;
    int64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;

    EventMetric metric;
    metric.set_id(1);

    LogEvent event1(1 /*tag id*/, bucketStartTimeNs + 1);
    LogEvent event2(1 /*tag id*/, bucketStartTimeNs + 2);

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    EventMetricProducer eventProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard,
                                      bucketStartTimeNs);

    eventProducer.onMatchedLogEvent(1 /*matcher index*/, event1);
    eventProducer.onMatchedLogEvent(1 /*matcher index*/, event2);

    // TODO: get the report and check the content after the ProtoOutputStream change is done.
    // eventProducer.onDumpReport();
}

TEST(EventMetricProducerTest, TestEventsWithNonSlicedCondition) {
    int64_t bucketStartTimeNs = 10000000000;
    int64_t eventStartTimeNs = bucketStartTimeNs + 1;
    int64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;

    EventMetric metric;
    metric.set_id(1);
    metric.set_condition(StringToId("SCREEN_ON"));

    LogEvent event1(1, bucketStartTimeNs + 1);
    LogEvent event2(1, bucketStartTimeNs + 10);

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    EventMetricProducer eventProducer(kConfigKey, metric, 1, wizard, bucketStartTimeNs);

    eventProducer.onConditionChanged(true /*condition*/, bucketStartTimeNs);
    eventProducer.onMatchedLogEvent(1 /*matcher index*/, event1);

    eventProducer.onConditionChanged(false /*condition*/, bucketStartTimeNs + 2);

    eventProducer.onMatchedLogEvent(1 /*matcher index*/, event2);

    // TODO: get the report and check the content after the ProtoOutputStream change is done.
    // eventProducer.onDumpReport();
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

    LogEvent event1(tagId, bucketStartTimeNs + 1);
    EXPECT_TRUE(event1.write("111"));
    event1.init();
    ConditionKey key1;
    key1[StringToId("APP_IN_BACKGROUND_PER_UID")] = {getMockedDimensionKey(conditionTagId, 2, "111")};

    LogEvent event2(tagId, bucketStartTimeNs + 10);
    EXPECT_TRUE(event2.write("222"));
    event2.init();
    ConditionKey key2;
    key2[StringToId("APP_IN_BACKGROUND_PER_UID")] = {getMockedDimensionKey(conditionTagId, 2, "222")};

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    EXPECT_CALL(*wizard, query(_, key1, _, _, _, _)).WillOnce(Return(ConditionState::kFalse));

    EXPECT_CALL(*wizard, query(_, key2, _, _, _, _)).WillOnce(Return(ConditionState::kTrue));

    EventMetricProducer eventProducer(kConfigKey, metric, 1, wizard, bucketStartTimeNs);

    eventProducer.onMatchedLogEvent(1 /*matcher index*/, event1);
    eventProducer.onMatchedLogEvent(1 /*matcher index*/, event2);

    // TODO: get the report and check the content after the ProtoOutputStream change is done.
    // eventProducer.onDumpReport();
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
