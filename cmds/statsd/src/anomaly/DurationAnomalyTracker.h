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

#include "AnomalyMonitor.h"
#include "AnomalyTracker.h"

namespace android {
namespace os {
namespace statsd {

using std::unordered_map;

class DurationAnomalyTracker : public virtual AnomalyTracker {
public:
    DurationAnomalyTracker(const Alert& alert, const ConfigKey& configKey);

    virtual ~DurationAnomalyTracker();

    // Starts the alarm at the given timestamp.
    void startAlarm(const MetricDimensionKey& dimensionKey, const uint64_t& eventTime);

    // Stops the alarm.
    void stopAlarm(const MetricDimensionKey& dimensionKey);

    // Stop all the alarms owned by this tracker.
    void stopAllAlarms();

    // Init the AnomalyMonitor which is shared across anomaly trackers.
    void setAnomalyMonitor(const sp<AnomalyMonitor>& anomalyMonitor) override {
        mAnomalyMonitor = anomalyMonitor;
    }

    // Declares the anomaly when the alarm expired given the current timestamp.
    void declareAnomalyIfAlarmExpired(const MetricDimensionKey& dimensionKey,
                                      const uint64_t& timestampNs);

    // Declares an anomaly for each alarm in firedAlarms that belongs to this DurationAnomalyTracker
    // and removes it from firedAlarms.
    // Note that this will generally be called from a different thread from the other functions;
    // the caller is responsible for thread safety.
    void informAlarmsFired(
            const uint64_t& timestampNs,
            unordered_set<sp<const AnomalyAlarm>, SpHash<AnomalyAlarm>>& firedAlarms) override;

protected:
    // The alarms owned by this tracker. The alarm monitor also shares the alarm pointers when they
    // are still active.
    std::unordered_map<MetricDimensionKey, sp<const AnomalyAlarm>> mAlarms;

    // Anomaly alarm monitor.
    sp<AnomalyMonitor> mAnomalyMonitor;

    // Resets all bucket data. For use when all the data gets stale.
    void resetStorage() override;

    FRIEND_TEST(OringDurationTrackerTest, TestPredictAnomalyTimestamp);
    FRIEND_TEST(OringDurationTrackerTest, TestAnomalyDetectionExpiredAlarm);
    FRIEND_TEST(OringDurationTrackerTest, TestAnomalyDetectionFiredAlarm);
    FRIEND_TEST(MaxDurationTrackerTest, TestAnomalyDetection);
    FRIEND_TEST(MaxDurationTrackerTest, TestAnomalyDetection);
};

}  // namespace statsd
}  // namespace os
}  // namespace android
