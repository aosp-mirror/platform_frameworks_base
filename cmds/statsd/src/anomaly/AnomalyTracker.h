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

#include <memory>  // unique_ptr

#include <stdlib.h>

#include <gtest/gtest_prod.h>
#include <utils/RefBase.h>

#include "AlarmMonitor.h"
#include "config/ConfigKey.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"  // Alert
#include "stats_util.h"  // HashableDimensionKey and DimToValMap

namespace android {
namespace os {
namespace statsd {

using std::shared_ptr;
using std::unordered_map;

// Does NOT allow negative values.
class AnomalyTracker : public virtual RefBase {
public:
    AnomalyTracker(const Alert& alert, const ConfigKey& configKey);

    virtual ~AnomalyTracker();

    // Add subscriptions that depend on this alert.
    void addSubscription(const Subscription& subscription) {
        mSubscriptions.push_back(subscription);
    }

    // Adds a bucket for the given bucketNum (index starting at 0).
    // If a bucket for bucketNum already exists, it will be replaced.
    // Also, advances to bucketNum (if not in the past), effectively filling any intervening
    // buckets with 0s.
    void addPastBucket(std::shared_ptr<DimToValMap> bucket, const int64_t& bucketNum);

    // Inserts (or replaces) the bucket entry for the given bucketNum at the given key to be the
    // given bucketValue. If the bucket does not exist, it will be created.
    // Also, advances to bucketNum (if not in the past), effectively filling any intervening
    // buckets with 0s.
    void addPastBucket(const MetricDimensionKey& key, const int64_t& bucketValue,
                       const int64_t& bucketNum);

    // Returns true if, based on past buckets plus the new currentBucketValue (which generally
    // represents the partially-filled current bucket), an anomaly has happened.
    // Also advances to currBucketNum-1.
    bool detectAnomaly(const int64_t& currBucketNum, const MetricDimensionKey& key,
                       const int64_t& currentBucketValue);

    // Informs incidentd about the detected alert.
    void declareAnomaly(const int64_t& timestampNs, int64_t metricId, const MetricDimensionKey& key,
                        int64_t metricValue);

    // Detects if, based on past buckets plus the new currentBucketValue (which generally
    // represents the partially-filled current bucket), an anomaly has happened, and if so,
    // declares an anomaly and informs relevant subscribers.
    // Also advances to currBucketNum-1.
    void detectAndDeclareAnomaly(const int64_t& timestampNs, const int64_t& currBucketNum,
                                 int64_t metricId, const MetricDimensionKey& key,
                                 const int64_t& currentBucketValue);

    // Init the AlarmMonitor which is shared across anomaly trackers.
    virtual void setAlarmMonitor(const sp<AlarmMonitor>& alarmMonitor) {
        return; // Base AnomalyTracker class has no need for the AlarmMonitor.
    }

    // Returns the sum of all past bucket values for the given dimension key.
    int64_t getSumOverPastBuckets(const MetricDimensionKey& key) const;

    // Returns the value for a past bucket, or 0 if that bucket doesn't exist.
    int64_t getPastBucketValue(const MetricDimensionKey& key, const int64_t& bucketNum) const;

    // Returns the anomaly threshold set in the configuration.
    inline int64_t getAnomalyThreshold() const {
        return mAlert.trigger_if_sum_gt();
    }

    // Returns the refractory period ending timestamp (in seconds) for the given key.
    // Before this moment, any detected anomaly will be ignored.
    // If there is no stored refractory period ending timestamp, returns 0.
    uint32_t getRefractoryPeriodEndsSec(const MetricDimensionKey& key) const {
        const auto& it = mRefractoryPeriodEndsSec.find(key);
        return it != mRefractoryPeriodEndsSec.end() ? it->second : 0;
    }

    // Returns the (constant) number of past buckets this anomaly tracker can store.
    inline int getNumOfPastBuckets() const {
        return mNumOfPastBuckets;
    }

    // Declares an anomaly for each alarm in firedAlarms that belongs to this AnomalyTracker,
    // and removes it from firedAlarms. Does NOT remove the alarm from the AlarmMonitor.
    virtual void informAlarmsFired(const int64_t& timestampNs,
            unordered_set<sp<const InternalAlarm>, SpHash<InternalAlarm>>& firedAlarms) {
        return; // The base AnomalyTracker class doesn't have alarms.
    }

protected:
    // For testing only.
    // Returns the alarm timestamp in seconds for the query dimension if it exists. Otherwise
    // returns 0.
    virtual uint32_t getAlarmTimestampSec(const MetricDimensionKey& dimensionKey) const {
        return 0;   // The base AnomalyTracker class doesn't have alarms.
    }

    // statsd_config.proto Alert message that defines this tracker.
    const Alert mAlert;

    // The subscriptions that depend on this alert.
    std::vector<Subscription> mSubscriptions;

    // A reference to the Alert's config key.
    const ConfigKey mConfigKey;

    // Number of past buckets. One less than the total number of buckets needed
    // for the anomaly detection (since the current bucket is not in the past).
    const int mNumOfPastBuckets;

    // Values for each of the past mNumOfPastBuckets buckets. Always of size mNumOfPastBuckets.
    // mPastBuckets[i] can be null, meaning that no data is present in that bucket.
    std::vector<shared_ptr<DimToValMap>> mPastBuckets;

    // Cached sum over all existing buckets in mPastBuckets.
    // Its buckets never contain entries of 0.
    DimToValMap mSumOverPastBuckets;

    // The bucket number of the last added bucket.
    int64_t mMostRecentBucketNum = -1;

    // Map from each dimension to the timestamp that its refractory period (if this anomaly was
    // declared for that dimension) ends, in seconds. From this moment and onwards, anomalies
    // can be declared again.
    // Entries may be, but are not guaranteed to be, removed after the period is finished.
    unordered_map<MetricDimensionKey, uint32_t> mRefractoryPeriodEndsSec;

    // Advances mMostRecentBucketNum to bucketNum, deleting any data that is now too old.
    // Specifically, since it is now too old, removes the data for
    //   [mMostRecentBucketNum - mNumOfPastBuckets + 1, bucketNum - mNumOfPastBuckets].
    void advanceMostRecentBucketTo(const int64_t& bucketNum);

    // Add the information in the given bucket to mSumOverPastBuckets.
    void addBucketToSum(const shared_ptr<DimToValMap>& bucket);

    // Subtract the information in the given bucket from mSumOverPastBuckets
    // and remove any items with value 0.
    void subtractBucketFromSum(const shared_ptr<DimToValMap>& bucket);

    // From mSumOverPastBuckets[key], subtracts bucketValue, removing it if it is now 0.
    void subtractValueFromSum(const MetricDimensionKey& key, const int64_t& bucketValue);

    // Returns true if in the refractory period, else false.
    bool isInRefractoryPeriod(const int64_t& timestampNs, const MetricDimensionKey& key) const;

    // Calculates the corresponding bucket index within the circular array.
    // Requires bucketNum >= 0.
    size_t index(int64_t bucketNum) const;

    // Resets all bucket data. For use when all the data gets stale.
    virtual void resetStorage();

    // Informs the subscribers (incidentd, perfetto, broadcasts, etc) that an anomaly has occurred.
    void informSubscribers(const MetricDimensionKey& key, int64_t metricId, int64_t metricValue);

    FRIEND_TEST(AnomalyTrackerTest, TestConsecutiveBuckets);
    FRIEND_TEST(AnomalyTrackerTest, TestSparseBuckets);
    FRIEND_TEST(GaugeMetricProducerTest, TestAnomalyDetection);
    FRIEND_TEST(CountMetricProducerTest, TestAnomalyDetectionUnSliced);
    FRIEND_TEST(AnomalyDetectionE2eTest, TestDurationMetric_SUM_single_bucket);
    FRIEND_TEST(AnomalyDetectionE2eTest, TestDurationMetric_SUM_multiple_buckets);
    FRIEND_TEST(AnomalyDetectionE2eTest, TestDurationMetric_SUM_long_refractory_period);
};

}  // namespace statsd
}  // namespace os
}  // namespace android
