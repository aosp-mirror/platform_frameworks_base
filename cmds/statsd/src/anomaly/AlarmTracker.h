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

#pragma once

#include <gtest/gtest_prod.h>

#include "AlarmMonitor.h"
#include "config/ConfigKey.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"  // Alarm

#include <stdlib.h>
#include <utils/RefBase.h>

namespace android {
namespace os {
namespace statsd {

class AlarmTracker : public virtual RefBase {
public:
    AlarmTracker(const int64_t startMillis,
                 const int64_t currentMillis,
                 const Alarm& alarm, const ConfigKey& configKey,
                 const sp<AlarmMonitor>& subscriberAlarmMonitor);

    virtual ~AlarmTracker();

    void onAlarmFired();

    void addSubscription(const Subscription& subscription);

    void informAlarmsFired(const int64_t& timestampNs,
            unordered_set<sp<const InternalAlarm>, SpHash<InternalAlarm>>& firedAlarms);

protected:
    // For test only. Returns the alarm timestamp in seconds. Otherwise returns 0.
    inline int32_t getAlarmTimestampSec() const {
        return mInternalAlarm == nullptr ? 0 : mInternalAlarm->timestampSec;
    }

    int64_t findNextAlarmSec(int64_t currentTimeMillis);

    // statsd_config.proto Alarm message that defines this tracker.
    const Alarm mAlarmConfig;

    // A reference to the Alarm's config key.
    const ConfigKey mConfigKey;

    // The subscriptions that depend on this alarm.
    std::vector<Subscription> mSubscriptions;

    // Alarm monitor.
    sp<AlarmMonitor> mAlarmMonitor;

    // The current expected alarm time in seconds.
    int64_t mAlarmSec;

    // The current alarm.
    sp<const InternalAlarm> mInternalAlarm;

    FRIEND_TEST(AlarmTrackerTest, TestTriggerTimestamp);
    FRIEND_TEST(AlarmE2eTest, TestMultipleAlarms);
};

}  // namespace statsd
}  // namespace os
}  // namespace android
