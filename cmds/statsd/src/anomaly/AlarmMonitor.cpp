/*
 * Copyright (C) 2017 The Android Open Source Project
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

#define DEBUG false
#include "Log.h"

#include "anomaly/AlarmMonitor.h"
#include "guardrail/StatsdStats.h"

namespace android {
namespace os {
namespace statsd {

AlarmMonitor::AlarmMonitor(
        uint32_t minDiffToUpdateRegisteredAlarmTimeSec,
        const std::function<void(const sp<IStatsCompanionService>&, int64_t)>& updateAlarm,
        const std::function<void(const sp<IStatsCompanionService>&)>& cancelAlarm)
    : mRegisteredAlarmTimeSec(0), mMinUpdateTimeSec(minDiffToUpdateRegisteredAlarmTimeSec),
      mUpdateAlarm(updateAlarm),
      mCancelAlarm(cancelAlarm) {}

AlarmMonitor::~AlarmMonitor() {}

void AlarmMonitor::setStatsCompanionService(sp<IStatsCompanionService> statsCompanionService) {
    std::lock_guard<std::mutex> lock(mLock);
    sp<IStatsCompanionService> tmpForLock = mStatsCompanionService;
    mStatsCompanionService = statsCompanionService;
    if (statsCompanionService == nullptr) {
        VLOG("Erasing link to statsCompanionService");
        return;
    }
    VLOG("Creating link to statsCompanionService");
    const sp<const InternalAlarm> top = mPq.top();
    if (top != nullptr) {
        updateRegisteredAlarmTime_l(top->timestampSec);
    }
}

void AlarmMonitor::add(sp<const InternalAlarm> alarm) {
    std::lock_guard<std::mutex> lock(mLock);
    if (alarm == nullptr) {
        ALOGW("Asked to add a null alarm.");
        return;
    }
    if (alarm->timestampSec < 1) {
        // forbidden since a timestamp 0 is used to indicate no alarm registered
        ALOGW("Asked to add a 0-time alarm.");
        return;
    }
    // TODO: Ensure that refractory period is respected.
    VLOG("Adding alarm with time %u", alarm->timestampSec);
    mPq.push(alarm);
    if (mRegisteredAlarmTimeSec < 1 ||
        alarm->timestampSec + mMinUpdateTimeSec < mRegisteredAlarmTimeSec) {
        updateRegisteredAlarmTime_l(alarm->timestampSec);
    }
}

void AlarmMonitor::remove(sp<const InternalAlarm> alarm) {
    std::lock_guard<std::mutex> lock(mLock);
    if (alarm == nullptr) {
        ALOGW("Asked to remove a null alarm.");
        return;
    }
    VLOG("Removing alarm with time %u", alarm->timestampSec);
    bool wasPresent = mPq.remove(alarm);
    if (!wasPresent) return;
    if (mPq.empty()) {
        VLOG("Queue is empty. Cancel any alarm.");
        cancelRegisteredAlarmTime_l();
        return;
    }
    uint32_t soonestAlarmTimeSec = mPq.top()->timestampSec;
    VLOG("Soonest alarm is %u", soonestAlarmTimeSec);
    if (soonestAlarmTimeSec > mRegisteredAlarmTimeSec + mMinUpdateTimeSec) {
        updateRegisteredAlarmTime_l(soonestAlarmTimeSec);
    }
}

// More efficient than repeatedly calling remove(mPq.top()) since it batches the
// updates to the registered alarm.
unordered_set<sp<const InternalAlarm>, SpHash<InternalAlarm>> AlarmMonitor::popSoonerThan(
        uint32_t timestampSec) {
    VLOG("Removing alarms with time <= %u", timestampSec);
    unordered_set<sp<const InternalAlarm>, SpHash<InternalAlarm>> oldAlarms;
    std::lock_guard<std::mutex> lock(mLock);

    for (sp<const InternalAlarm> t = mPq.top(); t != nullptr && t->timestampSec <= timestampSec;
        t = mPq.top()) {
        oldAlarms.insert(t);
        mPq.pop();  // remove t
    }
    // Always update registered alarm time (if anything has changed).
    if (!oldAlarms.empty()) {
        if (mPq.empty()) {
            VLOG("Queue is empty. Cancel any alarm.");
            cancelRegisteredAlarmTime_l();
        } else {
            // Always update the registered alarm in this case (unlike remove()).
            updateRegisteredAlarmTime_l(mPq.top()->timestampSec);
        }
    }
    return oldAlarms;
}

void AlarmMonitor::updateRegisteredAlarmTime_l(uint32_t timestampSec) {
    VLOG("Updating reg alarm time to %u", timestampSec);
    mRegisteredAlarmTimeSec = timestampSec;
    mUpdateAlarm(mStatsCompanionService, secToMs(mRegisteredAlarmTimeSec));
}

void AlarmMonitor::cancelRegisteredAlarmTime_l() {
    VLOG("Cancelling reg alarm.");
    mRegisteredAlarmTimeSec = 0;
    mCancelAlarm(mStatsCompanionService);
}

int64_t AlarmMonitor::secToMs(uint32_t timeSec) {
    return ((int64_t)timeSec) * 1000;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
