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

#include "metrics_test_helper.h"
#include "src/metrics/EventMetricProducer.h"

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

TEST(EventMetricProducerTest, TestNoCondition) {
    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t eventStartTimeNs = bucketStartTimeNs + 1;
    uint64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;

    EventMetric metric;
    metric.set_name("1");

    LogEvent event1(1 /*tag id*/, bucketStartTimeNs + 1);
    LogEvent event2(1 /*tag id*/, bucketStartTimeNs + 2);

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    EventMetricProducer eventProducer(metric, -1 /*-1 meaning no condition*/, wizard,
                                      bucketStartTimeNs);

    eventProducer.onMatchedLogEvent(1 /*matcher index*/, event1, false /*pulled*/);
    eventProducer.onMatchedLogEvent(1 /*matcher index*/, event2, false /*pulled*/);

    // TODO: get the report and check the content after the ProtoOutputStream change is done.
    // eventProducer.onDumpReport();
}

TEST(EventMetricProducerTest, TestEventsWithNonSlicedCondition) {
    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t eventStartTimeNs = bucketStartTimeNs + 1;
    uint64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;

    EventMetric metric;
    metric.set_name("1");
    metric.set_condition("SCREEN_ON");

    LogEvent event1(1, bucketStartTimeNs + 1);
    LogEvent event2(1, bucketStartTimeNs + 10);

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    EventMetricProducer eventProducer(metric, 1, wizard, bucketStartTimeNs);

    eventProducer.onConditionChanged(true /*condition*/, bucketStartTimeNs);
    eventProducer.onMatchedLogEvent(1 /*matcher index*/, event1, false /*pulled*/);

    eventProducer.onConditionChanged(false /*condition*/, bucketStartTimeNs + 2);

    eventProducer.onMatchedLogEvent(1 /*matcher index*/, event2, false /*pulled*/);

    // TODO: get the report and check the content after the ProtoOutputStream change is done.
    // eventProducer.onDumpReport();
}

TEST(EventMetricProducerTest, TestEventsWithSlicedCondition) {
    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;

    EventMetric metric;
    metric.set_name("1");
    metric.set_condition("APP_IN_BACKGROUND_PER_UID_AND_SCREEN_ON");
    EventConditionLink* link = metric.add_links();
    link->set_condition("APP_IN_BACKGROUND_PER_UID");
    link->add_key_in_main()->set_key(1);
    link->add_key_in_condition()->set_key(2);

    LogEvent event1(1, bucketStartTimeNs + 1);
    event1.write("111");  // uid
    event1.init();
    ConditionKey key1;
    key1["APP_IN_BACKGROUND_PER_UID"] = "2:111|";

    LogEvent event2(1, bucketStartTimeNs + 10);
    event2.write("222");  // uid
    event2.init();
    ConditionKey key2;
    key2["APP_IN_BACKGROUND_PER_UID"] = "2:222|";

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    EXPECT_CALL(*wizard, query(_, key1)).WillOnce(Return(ConditionState::kFalse));

    EXPECT_CALL(*wizard, query(_, key2)).WillOnce(Return(ConditionState::kTrue));

    EventMetricProducer eventProducer(metric, 1, wizard, bucketStartTimeNs);

    eventProducer.onMatchedLogEvent(1 /*matcher index*/, event1, false /*pulled*/);
    eventProducer.onMatchedLogEvent(1 /*matcher index*/, event2, false /*pulled*/);

    // TODO: get the report and check the content after the ProtoOutputStream change is done.
    // eventProducer.onDumpReport();
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
