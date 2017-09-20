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

#ifndef ANOMALY_MONITOR_H
#define ANOMALY_MONITOR_H

#include <indexed_priority_queue.h>
#include <android/os/IStatsCompanionService.h>
#include <utils/RefBase.h>

#include <queue>
#include <vector>

using namespace android;
using namespace android::os;

namespace android {
namespace os {
namespace statsd {

/**
 * Represents an alarm, associated with some aggregate metric, holding a
 * projected time at which the metric is expected to exceed its anomaly
 * threshold.
 * Timestamps are in seconds since epoch in a uint32, so will fail in year 2106.
 */
struct AnomalyAlarm : public RefBase {
    AnomalyAlarm(uint32_t timestampSec) : timestampSec(timestampSec) {
    }

    const uint32_t timestampSec;

    /** AnomalyAlarm a is smaller (higher priority) than b if its timestamp is sooner. */
    struct SmallerTimestamp {
        bool operator()(sp<const AnomalyAlarm> a, sp<const AnomalyAlarm> b) const {
            return (a->timestampSec < b->timestampSec);
        }
    };
};

/**
 * Manages alarms for Anomaly Detection.
 */
class AnomalyMonitor : public RefBase {
 public:
    /**
     * @param minDiffToUpdateRegisteredAlarmTimeSec If the soonest alarm differs
     * from the registered alarm by more than this amount, update the registered
     * alarm.
     */
    AnomalyMonitor(uint32_t minDiffToUpdateRegisteredAlarmTimeSec);
    ~AnomalyMonitor();

    /**
     * Tells AnomalyMonitor what IStatsCompanionService to use and, if
     * applicable, immediately registers an existing alarm with it.
     * If nullptr, AnomalyMonitor will continue to add/remove alarms, but won't
     * update IStatsCompanionService (until such time as it is set non-null).
     */
    void setStatsCompanionService(sp<IStatsCompanionService> statsCompanionService);

    /**
     * Adds the given alarm (reference) to the queue.
     */
    void add(sp<const AnomalyAlarm> alarm);

    /**
     * Removes the given alarm (reference) from the queue.
     * Note that alarm comparison is reference-based; if another alarm exists
     * with the same timestampSec, that alarm will still remain in the queue.
     */
    void remove(sp<const AnomalyAlarm> alarm);

    /**
     * Returns the projected alarm timestamp that is registered with
     * StatsCompanionService. This may not be equal to the soonest alarm,
     * but should be within minDiffToUpdateRegisteredAlarmTimeSec of it.
     */
    uint32_t getRegisteredAlarmTimeSec() const {
        return mRegisteredAlarmTimeSec;
    }

 private:
    std::mutex mLock;

    /**
     * Timestamp (seconds since epoch) of the alarm registered with
     * StatsCompanionService. This, in general, may not be equal to the soonest
     * alarm stored in mPq, but should be within minUpdateTimeSec of it.
     * A value of 0 indicates that no alarm is currently registered.
     */
    uint32_t mRegisteredAlarmTimeSec;

    /**
     * Priority queue of alarms, prioritized by soonest alarm.timestampSec.
     */
    indexed_priority_queue<AnomalyAlarm, AnomalyAlarm::SmallerTimestamp> mPq;

    /**
     * Binder interface for communicating with StatsCompanionService.
     */
    sp<IStatsCompanionService> mStatsCompanionService = nullptr;

    /**
     * Amount by which the soonest projected alarm must differ from
     * mRegisteredAlarmTimeSec before updateRegisteredAlarmTime_l is called.
     */
    uint32_t mMinUpdateTimeSec;

    /**
     * Updates the alarm registered with StatsCompanionService to the given time.
     * Also correspondingly updates mRegisteredAlarmTimeSec.
     */
    void updateRegisteredAlarmTime_l(uint32_t timestampSec);

    /** Converts uint32 timestamp in seconds to a Java long in msec. */
    int64_t secToMs(uint32_t timeSec);
};

} // namespace statsd
} // namespace os
} // namespace android

#endif // ANOMALY_MONITOR_H