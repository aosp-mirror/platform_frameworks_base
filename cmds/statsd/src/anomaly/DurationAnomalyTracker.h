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

#pragma once

#include "AlarmMonitor.h"
#include "AnomalyTracker.h"

namespace android {
namespace os {
namespace statsd {

using std::unordered_map;

class DurationAnomalyTracker : public virtual AnomalyTracker {
public:
    DurationAnomalyTracker(const Alert& alert, const ConfigKey& configKey,
                           const sp<AlarmMonitor>& alarmMonitor);

    virtual ~DurationAnomalyTracker();

    // Sets an alarm for the given timestamp.
    // Replaces previous alarm if one already exists.
    void startAlarm(const MetricDimensionKey& dimensionKey, const int64_t& eventTime);

    // Stops the alarm.
    // If it should have already fired, but hasn't yet (e.g. because the AlarmManager is delayed),
    // declare the anomaly now.
    void stopAlarm(const MetricDimensionKey& dimensionKey, const int64_t& timestampNs);

    // Stop all the alarms owned by this tracker. Does not declare any anomalies.
    void cancelAllAlarms();

    // Declares an anomaly for each alarm in firedAlarms that belongs to this DurationAnomalyTracker
    // and removes it from firedAlarms. The AlarmMonitor is not informed.
    // Note that this will generally be called from a different thread from the other functions;
    // the caller is responsible for thread safety.
    void informAlarmsFired(const int64_t& timestampNs,
            unordered_set<sp<const InternalAlarm>, SpHash<InternalAlarm>>& firedAlarms) override;

protected:
    // Returns the alarm timestamp in seconds for the query dimension if it exists. Otherwise
    // returns 0.
    uint32_t getAlarmTimestampSec(const MetricDimensionKey& dimensionKey) const override {
        auto it = mAlarms.find(dimensionKey);
        return it == mAlarms.end() ? 0 : it->second->timestampSec;
    }

    // The alarms owned by this tracker. The alarm monitor also shares the alarm pointers when they
    // are still active.
    std::unordered_map<MetricDimensionKey, sp<const InternalAlarm>> mAlarms;

    // Anomaly alarm monitor.
    sp<AlarmMonitor> mAlarmMonitor;

    FRIEND_TEST(OringDurationTrackerTest, TestPredictAnomalyTimestamp);
    FRIEND_TEST(OringDurationTrackerTest, TestAnomalyDetectionExpiredAlarm);
    FRIEND_TEST(OringDurationTrackerTest, TestAnomalyDetectionFiredAlarm);
    FRIEND_TEST(MaxDurationTrackerTest, TestAnomalyDetection);
    FRIEND_TEST(MaxDurationTrackerTest, TestAnomalyPredictedTimestamp);
    FRIEND_TEST(MaxDurationTrackerTest, TestAnomalyPredictedTimestamp_UpdatedOnStop);
};

}  // namespace statsd
}  // namespace os
}  // namespace android
