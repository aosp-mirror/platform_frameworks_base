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

#include <AnomalyMonitor.h>

#include <binder/IServiceManager.h>
#include <cutils/log.h>

namespace statsd {

AnomalyMonitor::AnomalyMonitor(uint32_t minDiffToUpdateRegisteredAlarmTimeSec)
        : mRegisteredAlarmTimeSec(0),
          mMinUpdateTimeSec(minDiffToUpdateRegisteredAlarmTimeSec) {
}

AnomalyMonitor::~AnomalyMonitor() {
}

void AnomalyMonitor::add(sp<const AnomalyAlarm> alarm) {
    if (alarm == nullptr) {
        ALOGW("Asked to add a null alarm.");
        return;
    }
    if (alarm->timestampSec < 1) {
        // forbidden since a timestamp 0 is used to indicate no alarm registered
        ALOGW("Asked to add a 0-time alarm.");
        return;
    }
    std::lock_guard<std::mutex> lock(mLock);
    // TODO: Ensure that refractory period is respected.
    if (DEBUG) ALOGD("Adding alarm with time %u", alarm->timestampSec);
    mPq.push(alarm);
    if (mRegisteredAlarmTimeSec < 1 ||
            alarm->timestampSec + mMinUpdateTimeSec < mRegisteredAlarmTimeSec) {
        updateRegisteredAlarmTime(alarm->timestampSec);
    }
}

void AnomalyMonitor::remove(sp<const AnomalyAlarm> alarm) {
    if (alarm == nullptr) {
        ALOGW("Asked to remove a null alarm.");
        return;
    }
    std::lock_guard<std::mutex> lock(mLock);
    if (DEBUG) ALOGD("Removing alarm with time %u", alarm->timestampSec);
    // TODO: make priority queue able to have items removed from it !!!
    // mPq.remove(alarm);
    if (mPq.empty()) {
        if (DEBUG) ALOGD("Queue is empty. Cancel any alarm.");
        mRegisteredAlarmTimeSec = 0;
        // TODO: Make this resistant to doing work when companion is not ready yet
        sp<IStatsCompanionService> statsCompanionService = getStatsCompanion_l();
        if (statsCompanionService != nullptr) {
            statsCompanionService->cancelAnomalyAlarm();
        }
        return;
    }
    uint32_t soonestAlarmTimeSec = mPq.top()->timestampSec;
    if (DEBUG) ALOGD("Soonest alarm is %u", soonestAlarmTimeSec);
    if (soonestAlarmTimeSec > mRegisteredAlarmTimeSec + mMinUpdateTimeSec) {
        updateRegisteredAlarmTime(soonestAlarmTimeSec);
    }
}

void AnomalyMonitor::updateRegisteredAlarmTime(uint32_t timestampSec) {
    if (DEBUG) ALOGD("Updating reg alarm time to %u", timestampSec);
    mRegisteredAlarmTimeSec = timestampSec;
    sp<IStatsCompanionService> statsCompanionService = getStatsCompanion_l();
    if (statsCompanionService != nullptr) {
        statsCompanionService->setAnomalyAlarm(secToMs(mRegisteredAlarmTimeSec));
    }
}

sp<IStatsCompanionService> AnomalyMonitor::getStatsCompanion_l() {
    if (mStatsCompanion != nullptr) {
        return mStatsCompanion;
    }
    // Get statscompanion service from service manager
    const sp<IServiceManager> sm(defaultServiceManager());
    if (sm != nullptr) {
        const String16 name("statscompanion");
        mStatsCompanion =
                interface_cast<IStatsCompanionService>(sm->checkService(name));
        if (mStatsCompanion == nullptr) {
            ALOGW("statscompanion service unavailable!");
            return nullptr;
        }
    }
    return mStatsCompanion;
}

int64_t AnomalyMonitor::secToMs(uint32_t timeSec) {
    return ((int64_t) timeSec) * 1000;
}

}  // namespace statsd