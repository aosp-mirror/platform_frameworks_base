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

#define LOG_TAG "AnomalyMonitor"
#define DEBUG true

#include "AnomalyMonitor.h"

#include <cutils/log.h>

namespace android {
namespace os {
namespace statsd {

AnomalyMonitor::AnomalyMonitor(uint32_t minDiffToUpdateRegisteredAlarmTimeSec)
        : mRegisteredAlarmTimeSec(0),
          mMinUpdateTimeSec(minDiffToUpdateRegisteredAlarmTimeSec) {
}

AnomalyMonitor::~AnomalyMonitor() {
}

void AnomalyMonitor::setStatsCompanionService(sp<IStatsCompanionService> statsCompanionService) {
    std::lock_guard<std::mutex> lock(mLock);
    sp<IStatsCompanionService> tmpForLock = mStatsCompanionService;
    mStatsCompanionService = statsCompanionService;
    if (statsCompanionService == nullptr) {
        if (DEBUG) ALOGD("Erasing link to statsCompanionService");
        return;
    }
    if (DEBUG) ALOGD("Creating link to statsCompanionService");
    const sp<const AnomalyAlarm> top = mPq.top();
    if (top != nullptr) {
        updateRegisteredAlarmTime_l(top->timestampSec);
    }
}

void AnomalyMonitor::add(sp<const AnomalyAlarm> alarm) {
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
    if (DEBUG) ALOGD("Adding alarm with time %u", alarm->timestampSec);
    mPq.push(alarm);
    if (mRegisteredAlarmTimeSec < 1 ||
            alarm->timestampSec + mMinUpdateTimeSec < mRegisteredAlarmTimeSec) {
        updateRegisteredAlarmTime_l(alarm->timestampSec);
    }
}

void AnomalyMonitor::remove(sp<const AnomalyAlarm> alarm) {
    std::lock_guard<std::mutex> lock(mLock);
    if (alarm == nullptr) {
        ALOGW("Asked to remove a null alarm.");
        return;
    }
    if (DEBUG) ALOGD("Removing alarm with time %u", alarm->timestampSec);
    mPq.remove(alarm);
    if (mPq.empty()) {
        if (DEBUG) ALOGD("Queue is empty. Cancel any alarm.");
        mRegisteredAlarmTimeSec = 0;
        if (mStatsCompanionService != nullptr) {
            mStatsCompanionService->cancelAnomalyAlarm();
        }
        return;
    }
    uint32_t soonestAlarmTimeSec = mPq.top()->timestampSec;
    if (DEBUG) ALOGD("Soonest alarm is %u", soonestAlarmTimeSec);
    if (soonestAlarmTimeSec > mRegisteredAlarmTimeSec + mMinUpdateTimeSec) {
        updateRegisteredAlarmTime_l(soonestAlarmTimeSec);
    }
}

void AnomalyMonitor::updateRegisteredAlarmTime_l(uint32_t timestampSec) {
    if (DEBUG) ALOGD("Updating reg alarm time to %u", timestampSec);
    mRegisteredAlarmTimeSec = timestampSec;
    if (mStatsCompanionService != nullptr) {
        mStatsCompanionService->setAnomalyAlarm(secToMs(mRegisteredAlarmTimeSec));
    }
}

int64_t AnomalyMonitor::secToMs(uint32_t timeSec) {
    return ((int64_t) timeSec) * 1000;
}

} // namespace statsd
} // namespace os
} // namespace android
