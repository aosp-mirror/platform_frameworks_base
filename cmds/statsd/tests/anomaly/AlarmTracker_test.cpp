// Copyright (C) 2018 The Android Open Source Project
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

#include "src/anomaly/AlarmTracker.h"

#include <gtest/gtest.h>
#include <log/log_time.h>
#include <stdio.h>
#include <vector>

using namespace testing;
using android::sp;
using std::set;
using std::shared_ptr;
using std::unordered_map;
using std::vector;

#ifdef __ANDROID__

namespace android {
namespace os {
namespace statsd {

const ConfigKey kConfigKey(0, 12345);

TEST(AlarmTrackerTest, TestTriggerTimestamp) {
    sp<AlarmMonitor> subscriberAlarmMonitor =
        new AlarmMonitor(100,
                         [](const shared_ptr<IStatsCompanionService>&, int64_t){},
                         [](const shared_ptr<IStatsCompanionService>&){});
    Alarm alarm;
    alarm.set_offset_millis(15 * MS_PER_SEC);
    alarm.set_period_millis(60 * 60 * MS_PER_SEC);  // 1hr
    int64_t startMillis = 100000000 * MS_PER_SEC;
    int64_t nextAlarmTime = startMillis / MS_PER_SEC + 15;
    AlarmTracker tracker(startMillis, startMillis, alarm, kConfigKey, subscriberAlarmMonitor);

    EXPECT_EQ(tracker.mAlarmSec, nextAlarmTime);

    uint64_t currentTimeSec = startMillis / MS_PER_SEC + 10;
    std::unordered_set<sp<const InternalAlarm>, SpHash<InternalAlarm>> firedAlarmSet =
        subscriberAlarmMonitor->popSoonerThan(static_cast<uint32_t>(currentTimeSec));
    EXPECT_TRUE(firedAlarmSet.empty());
    tracker.informAlarmsFired(currentTimeSec * NS_PER_SEC, firedAlarmSet);
    EXPECT_EQ(tracker.mAlarmSec, nextAlarmTime);
    EXPECT_EQ(tracker.getAlarmTimestampSec(), nextAlarmTime);

    currentTimeSec = startMillis / MS_PER_SEC + 7000;
    nextAlarmTime = startMillis / MS_PER_SEC + 15 + 2 * 60 * 60;
    firedAlarmSet = subscriberAlarmMonitor->popSoonerThan(static_cast<uint32_t>(currentTimeSec));
    ASSERT_EQ(firedAlarmSet.size(), 1u);
    tracker.informAlarmsFired(currentTimeSec * NS_PER_SEC, firedAlarmSet);
    EXPECT_TRUE(firedAlarmSet.empty());
    EXPECT_EQ(tracker.mAlarmSec, nextAlarmTime);
    EXPECT_EQ(tracker.getAlarmTimestampSec(), nextAlarmTime);

    // Alarm fires exactly on time.
    currentTimeSec = startMillis / MS_PER_SEC + 15 + 2 * 60 * 60;
    nextAlarmTime = startMillis / MS_PER_SEC + 15 + 3 * 60 * 60;
    firedAlarmSet = subscriberAlarmMonitor->popSoonerThan(static_cast<uint32_t>(currentTimeSec));
    ASSERT_EQ(firedAlarmSet.size(), 1u);
    tracker.informAlarmsFired(currentTimeSec * NS_PER_SEC, firedAlarmSet);
    EXPECT_TRUE(firedAlarmSet.empty());
    EXPECT_EQ(tracker.mAlarmSec, nextAlarmTime);
    EXPECT_EQ(tracker.getAlarmTimestampSec(), nextAlarmTime);

    // Alarm fires exactly 1 period late.
    currentTimeSec = startMillis / MS_PER_SEC + 15 + 4 * 60 * 60;
    nextAlarmTime = startMillis / MS_PER_SEC + 15 + 5 * 60 * 60;
    firedAlarmSet = subscriberAlarmMonitor->popSoonerThan(static_cast<uint32_t>(currentTimeSec));
    ASSERT_EQ(firedAlarmSet.size(), 1u);
    tracker.informAlarmsFired(currentTimeSec * NS_PER_SEC, firedAlarmSet);
    EXPECT_TRUE(firedAlarmSet.empty());
    EXPECT_EQ(tracker.mAlarmSec, nextAlarmTime);
    EXPECT_EQ(tracker.getAlarmTimestampSec(), nextAlarmTime);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
