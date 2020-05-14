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

    auto alarm = config.add_alarm();
    alarm->set_id(123456);
    alarm->set_offset_millis(TimeUnitToBucketSizeInMillis(TEN_MINUTES));
    alarm->set_period_millis(TimeUnitToBucketSizeInMillis(ONE_HOUR));

    alarm = config.add_alarm();
    alarm->set_id(654321);
    alarm->set_offset_millis(TimeUnitToBucketSizeInMillis(FIVE_MINUTES));
    alarm->set_period_millis(TimeUnitToBucketSizeInMillis(THIRTY_MINUTES));
    return config;
}

}  // namespace

TEST(AlarmE2eTest, TestMultipleAlarms) {
    auto config = CreateStatsdConfig();
    int64_t bucketStartTimeNs = 10000000000;

    ConfigKey cfgKey;
    auto processor = CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);
    ASSERT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());
    ASSERT_EQ(2u, processor->mMetricsManagers.begin()->second->mAllPeriodicAlarmTrackers.size());

    auto alarmTracker1 = processor->mMetricsManagers.begin()->second->mAllPeriodicAlarmTrackers[0];
    auto alarmTracker2 = processor->mMetricsManagers.begin()->second->mAllPeriodicAlarmTrackers[1];

    int64_t alarmTimestampSec0 = bucketStartTimeNs / NS_PER_SEC + 10 * 60;
    int64_t alarmTimestampSec1 = bucketStartTimeNs / NS_PER_SEC + 5 * 60;
    EXPECT_EQ(alarmTimestampSec0, alarmTracker1->getAlarmTimestampSec());
    EXPECT_EQ(alarmTimestampSec1, alarmTracker2->getAlarmTimestampSec());

    // Alarm fired.
    const int64_t alarmFiredTimestampSec0 = alarmTimestampSec1 + 5;
    auto alarmSet = processor->getPeriodicAlarmMonitor()->popSoonerThan(
            static_cast<uint32_t>(alarmFiredTimestampSec0));
    ASSERT_EQ(1u, alarmSet.size());
    processor->onPeriodicAlarmFired(alarmFiredTimestampSec0 * NS_PER_SEC, alarmSet);
    EXPECT_EQ(alarmTimestampSec0, alarmTracker1->getAlarmTimestampSec());
    EXPECT_EQ(alarmTimestampSec1 + 30 * 60, alarmTracker2->getAlarmTimestampSec());

    // Alarms fired very late.
    const int64_t alarmFiredTimestampSec1 = alarmTimestampSec0 + 2 * 60 * 60 + 125;
    alarmSet = processor->getPeriodicAlarmMonitor()->popSoonerThan(
            static_cast<uint32_t>(alarmFiredTimestampSec1));
    ASSERT_EQ(2u, alarmSet.size());
    processor->onPeriodicAlarmFired(alarmFiredTimestampSec1 * NS_PER_SEC, alarmSet);
    EXPECT_EQ(alarmTimestampSec0 + 60 * 60 * 3, alarmTracker1->getAlarmTimestampSec());
    EXPECT_EQ(alarmTimestampSec1 + 30 * 60 * 5, alarmTracker2->getAlarmTimestampSec());
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif

}  // namespace statsd
}  // namespace os
}  // namespace android
