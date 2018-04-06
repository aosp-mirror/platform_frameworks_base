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

#define DEBUG true  // STOPSHIP if true
#include "Log.h"

#include "anomaly/AlarmTracker.h"
#include "anomaly/subscriber_util.h"
#include "HashableDimensionKey.h"
#include "stats_util.h"
#include "storage/StorageManager.h"

#include <statslog.h>
#include <time.h>

namespace android {
namespace os {
namespace statsd {

AlarmTracker::AlarmTracker(const int64_t startMillis,
                           const int64_t currentMillis,
                           const Alarm& alarm, const ConfigKey& configKey,
                           const sp<AlarmMonitor>& alarmMonitor)
    : mAlarmConfig(alarm),
      mConfigKey(configKey),
      mAlarmMonitor(alarmMonitor) {
    VLOG("AlarmTracker() called");
    mAlarmSec = (startMillis + mAlarmConfig.offset_millis()) / MS_PER_SEC;
    // startMillis is the time statsd is created. We need to find the 1st alarm timestamp after
    // the config is added to statsd.
    mAlarmSec = findNextAlarmSec(currentMillis / MS_PER_SEC);  // round up
    mInternalAlarm = new InternalAlarm{static_cast<uint32_t>(mAlarmSec)};
    VLOG("AlarmTracker sets the periodic alarm at: %lld", (long long)mAlarmSec);
    if (mAlarmMonitor != nullptr) {
        mAlarmMonitor->add(mInternalAlarm);
    }
}

AlarmTracker::~AlarmTracker() {
    VLOG("~AlarmTracker() called");
    if (mInternalAlarm != nullptr && mAlarmMonitor != nullptr) {
        mAlarmMonitor->remove(mInternalAlarm);
    }
}

void AlarmTracker::addSubscription(const Subscription& subscription) {
    mSubscriptions.push_back(subscription);
}

int64_t AlarmTracker::findNextAlarmSec(int64_t currentTimeSec) {
    if (currentTimeSec <= mAlarmSec) {
        return mAlarmSec;
    }
    int64_t periodsForward =
        ((currentTimeSec - mAlarmSec) * MS_PER_SEC - 1) / mAlarmConfig.period_millis() + 1;
    return mAlarmSec + periodsForward * mAlarmConfig.period_millis() / MS_PER_SEC;
}

void AlarmTracker::informAlarmsFired(
        const int64_t& timestampNs,
        unordered_set<sp<const InternalAlarm>, SpHash<InternalAlarm>>& firedAlarms) {
    if (firedAlarms.empty() || mInternalAlarm == nullptr ||
        firedAlarms.find(mInternalAlarm) == firedAlarms.end()) {
        return;
    }
    if (!mSubscriptions.empty()) {
        VLOG("AlarmTracker triggers the subscribers.");
        triggerSubscribers(mAlarmConfig.id(), DEFAULT_METRIC_DIMENSION_KEY, mConfigKey,
                           mSubscriptions);
    }
    firedAlarms.erase(mInternalAlarm);
    mAlarmSec = findNextAlarmSec((timestampNs-1) / NS_PER_SEC + 1); // round up
    mInternalAlarm = new InternalAlarm{static_cast<uint32_t>(mAlarmSec)};
    VLOG("AlarmTracker sets the periodic alarm at: %lld", (long long)mAlarmSec);
    if (mAlarmMonitor != nullptr) {
        mAlarmMonitor->add(mInternalAlarm);
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android
