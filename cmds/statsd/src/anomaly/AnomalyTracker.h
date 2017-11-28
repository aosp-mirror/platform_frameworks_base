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

#include <gtest/gtest_prod.h>
#include "AnomalyMonitor.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"  // Alert
#include "stats_util.h"  // HashableDimensionKey and DimToValMap

#include <memory> // unique_ptr
#include <stdlib.h>
#include <utils/RefBase.h>

namespace android {
namespace os {
namespace statsd {

using std::unordered_map;
using std::shared_ptr;

// Does NOT allow negative values.
class AnomalyTracker : public virtual RefBase {
public:
    AnomalyTracker(const Alert& alert);

    virtual ~AnomalyTracker();

    // Adds a bucket.
    // Bucket index starts from 0.
    void addPastBucket(std::shared_ptr<DimToValMap> bucketValues, const int64_t& bucketNum);
    void addPastBucket(const HashableDimensionKey& key, const int64_t& bucketValue,
                       const int64_t& bucketNum);

    // Returns true if detected anomaly for the existing buckets on one or more dimension keys.
    bool detectAnomaly(const int64_t& currBucketNum, const DimToValMap& currentBucket);
    bool detectAnomaly(const int64_t& currBucketNum, const HashableDimensionKey& key,
                       const int64_t& currentBucketValue);

    // Informs incidentd about the detected alert.
    void declareAnomaly(const uint64_t& timestampNs);

    // Detects the alert and informs the incidentd when applicable.
    void detectAndDeclareAnomaly(const uint64_t& timestampNs, const int64_t& currBucketNum,
                                 const DimToValMap& currentBucket);
    void detectAndDeclareAnomaly(const uint64_t& timestampNs, const int64_t& currBucketNum,
                                 const HashableDimensionKey& key,
                                 const int64_t& currentBucketValue);

    // Starts the alarm at the given timestamp.
    void startAlarm(const HashableDimensionKey& dimensionKey, const uint64_t& eventTime);
    // Stops the alarm.
    void stopAlarm(const HashableDimensionKey& dimensionKey);

    // Stop all the alarms owned by this tracker.
    void stopAllAlarms();

    // Init the anmaly monitor which is shared across anomaly trackers.
    inline void setAnomalyMonitor(const sp<AnomalyMonitor>& anomalyMonitor) {
        mAnomalyMonitor = anomalyMonitor;
    }

    // Declares the anomaly when the alarm expired given the current timestamp.
    void declareAnomalyIfAlarmExpired(const HashableDimensionKey& dimensionKey,
                                      const uint64_t& timestampNs);

    // Helper function to return the sum value of past buckets at given dimension.
    int64_t getSumOverPastBuckets(const HashableDimensionKey& key) const;

    // Helper function to return the value for a past bucket.
    int64_t getPastBucketValue(const HashableDimensionKey& key, const int64_t& bucketNum) const;

    // Returns the anomaly threshold.
    inline int64_t getAnomalyThreshold() const {
        return mAlert.trigger_if_sum_gt();
    }

    // Helper function to return the last alarm timestamp.
    inline int64_t getLastAlarmTimestampNs() const {
        return mLastAlarmTimestampNs;
    }

    inline int getNumOfPastBuckets() const {
        return mNumOfPastBuckets;
    }

    // Declares an anomaly for each alarm in firedAlarms that belongs to this AnomalyTracker,
    // and removes it from firedAlarms. Does NOT remove the alarm from the AnomalyMonitor.
    // TODO: This will actually be called from a different thread, so make it thread-safe!
    // TODO: Consider having AnomalyMonitor have a reference to each relevant MetricProducer
    //       instead of calling it from a chain starting at StatsLogProcessor.
    void informAlarmsFired(const uint64_t& timestampNs,
            unordered_set<sp<const AnomalyAlarm>, SpHash<AnomalyAlarm>>& firedAlarms);

protected:
    void flushPastBuckets(const int64_t& currBucketNum);
    // statsd_config.proto Alert message that defines this tracker.
    const Alert mAlert;

    // Number of past buckets. One less than the total number of buckets needed
    // for the anomaly detection (since the current bucket is not in the past).
    int mNumOfPastBuckets;

    // The alarms owned by this tracker. The alarm monitor also shares the alarm pointers when they
    // are still active.
    std::unordered_map<HashableDimensionKey, sp<const AnomalyAlarm>> mAlarms;

    // Anomaly alarm monitor.
    sp<AnomalyMonitor> mAnomalyMonitor;

    // The exisiting bucket list.
    std::vector<shared_ptr<DimToValMap>> mPastBuckets;

    // Sum over all existing buckets cached in mPastBuckets.
    DimToValMap mSumOverPastBuckets;

    // The bucket number of the last added bucket.
    int64_t mMostRecentBucketNum = -1;

    // The timestamp when the last anomaly was declared.
    int64_t mLastAlarmTimestampNs = -1;

    // Add the information in the given bucket to mSumOverPastBuckets.
    void addBucketToSum(const shared_ptr<DimToValMap>& bucket);

    // Subtract the information in the given bucket from mSumOverPastBuckets
    // and remove any items with value 0.
    void subtractBucketFromSum(const shared_ptr<DimToValMap>& bucket);

    bool isInRefractoryPeriod(const uint64_t& timestampNs);

    // Calculates the corresponding bucket index within the circular array.
    size_t index(int64_t bucketNum) const;

    // Resets all bucket data. For use when all the data gets stale.
    void resetStorage();

    // Informs the incident service that an anomaly has occurred.
    void informIncidentd();

    FRIEND_TEST(AnomalyTrackerTest, TestConsecutiveBuckets);
    FRIEND_TEST(AnomalyTrackerTest, TestSparseBuckets);
    FRIEND_TEST(GaugeMetricProducerTest, TestAnomalyDetection);
    FRIEND_TEST(CountMetricProducerTest, TestAnomalyDetection);
    FRIEND_TEST(OringDurationTrackerTest, TestPredictAnomalyTimestamp);
    FRIEND_TEST(OringDurationTrackerTest, TestAnomalyDetection);
    FRIEND_TEST(MaxDurationTrackerTest, TestAnomalyDetection);
    FRIEND_TEST(MaxDurationTrackerTest, TestAnomalyDetection);
    FRIEND_TEST(OringDurationTrackerTest, TestAnomalyDetection);
};

}  // namespace statsd
}  // namespace os
}  // namespace android
