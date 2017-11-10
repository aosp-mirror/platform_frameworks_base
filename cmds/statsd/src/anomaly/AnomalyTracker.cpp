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

#define DEBUG true  // STOPSHIP if true
#include "Log.h"

#include "AnomalyTracker.h"

#include <time.h>

namespace android {
namespace os {
namespace statsd {

AnomalyTracker::AnomalyTracker(const Alert& alert, const int64_t& bucketSizeNs)
    : mAlert(alert),
      mBucketSizeNs(bucketSizeNs),
      mNumOfPastPackets(mAlert.number_of_buckets() - 1) {
    VLOG("AnomalyTracker() called");
    if (mAlert.number_of_buckets() <= 0) {
        ALOGE("Cannot create DiscreteAnomalyTracker with %lld buckets",
              (long long)mAlert.number_of_buckets());
        return;
    }
    if (mBucketSizeNs <= 0) {
        ALOGE("Cannot create DiscreteAnomalyTracker with bucket size %lld ",
              (long long)mBucketSizeNs);
        return;
    }
    if (!mAlert.has_trigger_if_sum_gt()) {
        ALOGE("Cannot create DiscreteAnomalyTracker without threshold");
        return;
    }
    reset(); // initialization
}

AnomalyTracker::~AnomalyTracker() {
    VLOG("~AnomalyTracker() called");
    stopAllAlarms();
}

void AnomalyTracker::reset() {
    VLOG("reset() called.");
    stopAllAlarms();
    mPastBuckets.clear();
    // Excludes the current bucket.
    mPastBuckets.resize(mNumOfPastPackets);
    mSumOverPastBuckets.clear();
    mMostRecentBucketNum = -1;
    mLastAlarmTimestampNs = -1;
}

size_t AnomalyTracker::index(int64_t bucketNum) const {
    return bucketNum % mNumOfPastPackets;
}

void AnomalyTracker::flushPastBuckets(const int64_t& latestPastBucketNum) {
    VLOG("addPastBucket() called.");
    if (latestPastBucketNum <= mMostRecentBucketNum - mNumOfPastPackets) {
        ALOGE("Cannot add a past bucket %lld units in past", (long long)latestPastBucketNum);
        return;
    }

    // The past packets are ancient. Empty out old mPastBuckets[i] values and reset
    // mSumOverPastBuckets.
    if (latestPastBucketNum - mMostRecentBucketNum >= mNumOfPastPackets) {
        mPastBuckets.clear();
        mPastBuckets.resize(mNumOfPastPackets);
        mSumOverPastBuckets.clear();
    } else {
        for (int64_t i = std::max(0LL, (long long)(mMostRecentBucketNum - mNumOfPastPackets + 1));
             i <= latestPastBucketNum - mNumOfPastPackets; i++) {
            const int idx = index(i);
            subtractBucketFromSum(mPastBuckets[idx]);
            mPastBuckets[idx] = nullptr;  // release (but not clear) the old bucket.
        }
    }

    // It is an update operation.
    if (latestPastBucketNum <= mMostRecentBucketNum &&
        latestPastBucketNum > mMostRecentBucketNum - mNumOfPastPackets) {
        subtractBucketFromSum(mPastBuckets[index(latestPastBucketNum)]);
    }
}

void AnomalyTracker::addPastBucket(const HashableDimensionKey& key, const int64_t& bucketValue,
                                   const int64_t& bucketNum) {
    flushPastBuckets(bucketNum);

    auto& bucket = mPastBuckets[index(bucketNum)];
    if (bucket == nullptr) {
        bucket = std::make_shared<DimToValMap>();
    }
    bucket->insert({key, bucketValue});
    addBucketToSum(bucket);
    mMostRecentBucketNum = std::max(mMostRecentBucketNum, bucketNum);
}

void AnomalyTracker::addPastBucket(std::shared_ptr<DimToValMap> bucketValues,
                                   const int64_t& bucketNum) {
    VLOG("addPastBucket() called.");
    flushPastBuckets(bucketNum);
    // Replace the oldest bucket with the new bucket we are adding.
    mPastBuckets[index(bucketNum)] = bucketValues;
    addBucketToSum(bucketValues);
    mMostRecentBucketNum = std::max(mMostRecentBucketNum, bucketNum);
}

void AnomalyTracker::subtractBucketFromSum(const shared_ptr<DimToValMap>& bucket) {
    if (bucket == nullptr) {
        return;
    }
    // For each dimension present in the bucket, subtract its value from its corresponding sum.
    for (const auto& keyValuePair : *bucket) {
        auto itr = mSumOverPastBuckets.find(keyValuePair.first);
        if (itr == mSumOverPastBuckets.end()) {
            continue;
        }
        itr->second -= keyValuePair.second;
        // TODO: No need to look up the object twice like this. Use a var.
        if (itr->second == 0) {
            mSumOverPastBuckets.erase(itr);
        }
    }
}

void AnomalyTracker::addBucketToSum(const shared_ptr<DimToValMap>& bucket) {
    if (bucket == nullptr) {
        return;
    }
    // For each dimension present in the bucket, add its value to its corresponding sum.
    for (const auto& keyValuePair : *bucket) {
        mSumOverPastBuckets[keyValuePair.first] += keyValuePair.second;
    }
}

int64_t AnomalyTracker::getPastBucketValue(const HashableDimensionKey& key,
                                           const int64_t& bucketNum) const {
    const auto& bucket = mPastBuckets[index(bucketNum)];
    if (bucket == nullptr) {
        return 0;
    }
    const auto& itr = bucket->find(key);
    return itr == bucket->end() ? 0 : itr->second;
}

int64_t AnomalyTracker::getSumOverPastBuckets(const HashableDimensionKey& key) const {
    const auto& itr = mSumOverPastBuckets.find(key);
    if (itr != mSumOverPastBuckets.end()) {
        return itr->second;
    }
    return 0;
}

bool AnomalyTracker::detectAnomaly(const int64_t& currentBucketNum,
                                   const DimToValMap& currentBucket) {
    if (currentBucketNum > mMostRecentBucketNum + 1) {
        addPastBucket(nullptr, currentBucketNum - 1);
    }
    for (auto itr = currentBucket.begin(); itr != currentBucket.end(); itr++) {
        if (itr->second + getSumOverPastBuckets(itr->first) > mAlert.trigger_if_sum_gt()) {
            return true;
        }
    }
    // In theory, we also need to check the dimsions not in the current bucket. In single-thread
    // mode, usually we could avoid the following loops.
    for (auto itr = mSumOverPastBuckets.begin(); itr != mSumOverPastBuckets.end(); itr++) {
        if (itr->second > mAlert.trigger_if_sum_gt()) {
            return true;
        }
    }
    return false;
}

bool AnomalyTracker::detectAnomaly(const int64_t& currentBucketNum, const HashableDimensionKey& key,
                                   const int64_t& currentBucketValue) {
    if (currentBucketNum > mMostRecentBucketNum + 1) {
        addPastBucket(key, 0, currentBucketNum - 1);
    }
    return getSumOverPastBuckets(key) + currentBucketValue > mAlert.trigger_if_sum_gt();
}

void AnomalyTracker::declareAnomaly(const uint64_t& timestamp) {
    if (mLastAlarmTimestampNs >= 0 &&
        timestamp - mLastAlarmTimestampNs <= mAlert.refractory_period_secs() * NS_PER_SEC) {
        VLOG("Skipping anomaly check since within refractory period");
        return;
    }
    // TODO(guardrail): Consider guarding against too short refractory periods.
    mLastAlarmTimestampNs = timestamp;

    if (mAlert.has_incidentd_details()) {
        // TODO: Can construct a name based on the criteria (and/or relay the criteria).
        ALOGW("An anomaly (nameless) has occurred! Informing incidentd.");
        // TODO: Send incidentd_details.name and incidentd_details.incidentd_sections to incidentd
    } else {
        ALOGW("An anomaly has occurred! (But informing incidentd not requested.)");
    }
}

void AnomalyTracker::declareAnomalyIfAlarmExpired(const HashableDimensionKey& dimensionKey,
                                                  const uint64_t& timestamp) {
    auto itr = mAlarms.find(dimensionKey);
    if (itr == mAlarms.end()) {
        return;
    }

    if (itr->second != nullptr &&
        static_cast<uint32_t>(timestamp / NS_PER_SEC) >= itr->second->timestampSec) {
        declareAnomaly(timestamp);
        stopAlarm(dimensionKey);
    }
}

void AnomalyTracker::detectAndDeclareAnomaly(const uint64_t& timestamp,
                                             const int64_t& currBucketNum,
                                             const HashableDimensionKey& key,
                                             const int64_t& currentBucketValue) {
    if (detectAnomaly(currBucketNum, key, currentBucketValue)) {
        declareAnomaly(timestamp);
    }
}

void AnomalyTracker::detectAndDeclareAnomaly(const uint64_t& timestamp,
                                             const int64_t& currBucketNum,
                                             const DimToValMap& currentBucket) {
    if (detectAnomaly(currBucketNum, currentBucket)) {
        declareAnomaly(timestamp);
    }
}

void AnomalyTracker::startAlarm(const HashableDimensionKey& dimensionKey,
                                const uint64_t& timestamp) {
    sp<const AnomalyAlarm> alarm = new AnomalyAlarm{static_cast<uint32_t>(timestamp / NS_PER_SEC)};
    mAlarms.insert({dimensionKey, alarm});
    if (mAnomalyMonitor != nullptr) {
        mAnomalyMonitor->add(alarm);
    }
}

void AnomalyTracker::stopAlarm(const HashableDimensionKey& dimensionKey) {
    auto itr = mAlarms.find(dimensionKey);
    if (itr != mAlarms.end()) {
        mAlarms.erase(dimensionKey);
    }
    if (mAnomalyMonitor != nullptr) {
        mAnomalyMonitor->remove(itr->second);
    }
}

void AnomalyTracker::stopAllAlarms() {
    std::set<HashableDimensionKey> keys;
    for (auto itr = mAlarms.begin(); itr != mAlarms.end(); ++itr) {
        keys.insert(itr->first);
    }
    for (auto key : keys) {
        stopAlarm(key);
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android
