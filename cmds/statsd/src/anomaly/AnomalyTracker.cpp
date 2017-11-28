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

#include <android/os/IIncidentManager.h>
#include <android/os/IncidentReportArgs.h>
#include <binder/IServiceManager.h>
#include <time.h>

namespace android {
namespace os {
namespace statsd {

// TODO: Separate DurationAnomalyTracker as a separate subclass and let each MetricProducer
//       decide and let which one it wants.
// TODO: Get rid of bucketNumbers, and return to the original circular array method.
AnomalyTracker::AnomalyTracker(const Alert& alert)
    : mAlert(alert),
      mNumOfPastBuckets(mAlert.number_of_buckets() - 1) {
    VLOG("AnomalyTracker() called");
    if (mAlert.number_of_buckets() <= 0) {
        ALOGE("Cannot create AnomalyTracker with %lld buckets",
              (long long)mAlert.number_of_buckets());
        return;
    }
    if (!mAlert.has_trigger_if_sum_gt()) {
        ALOGE("Cannot create AnomalyTracker without threshold");
        return;
    }
    resetStorage(); // initialization
}

AnomalyTracker::~AnomalyTracker() {
    VLOG("~AnomalyTracker() called");
    stopAllAlarms();
}

void AnomalyTracker::resetStorage() {
    VLOG("resetStorage() called.");
    mPastBuckets.clear();
    // Excludes the current bucket.
    mPastBuckets.resize(mNumOfPastBuckets);
    mSumOverPastBuckets.clear();

    if (!mAlarms.empty()) VLOG("AnomalyTracker.resetStorage() called but mAlarms is NOT empty!");
}

size_t AnomalyTracker::index(int64_t bucketNum) const {
    return bucketNum % mNumOfPastBuckets;
}

void AnomalyTracker::flushPastBuckets(const int64_t& latestPastBucketNum) {
    VLOG("addPastBucket() called.");
    if (latestPastBucketNum <= mMostRecentBucketNum - mNumOfPastBuckets) {
        ALOGE("Cannot add a past bucket %lld units in past", (long long)latestPastBucketNum);
        return;
    }

    // The past packets are ancient. Empty out old mPastBuckets[i] values and reset
    // mSumOverPastBuckets.
    if (latestPastBucketNum - mMostRecentBucketNum >= mNumOfPastBuckets) {
        mPastBuckets.clear();
        mPastBuckets.resize(mNumOfPastBuckets);
        mSumOverPastBuckets.clear();
    } else {
        for (int64_t i = std::max(0LL, (long long)(mMostRecentBucketNum - mNumOfPastBuckets + 1));
             i <= latestPastBucketNum - mNumOfPastBuckets; i++) {
            const int idx = index(i);
            subtractBucketFromSum(mPastBuckets[idx]);
            mPastBuckets[idx] = nullptr;  // release (but not clear) the old bucket.
        }
    }

    // It is an update operation.
    if (latestPastBucketNum <= mMostRecentBucketNum &&
        latestPastBucketNum > mMostRecentBucketNum - mNumOfPastBuckets) {
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
    return mAlert.has_trigger_if_sum_gt()
            && getSumOverPastBuckets(key) + currentBucketValue > mAlert.trigger_if_sum_gt();
}

void AnomalyTracker::declareAnomaly(const uint64_t& timestampNs) {
    // TODO: This should also take in the const HashableDimensionKey& key, to pass
    //       more details to incidentd and to make mRefractoryPeriodEndsSec key-specific.
    // TODO: Why receive timestamp? RefractoryPeriod should always be based on real time right now.
    if (isInRefractoryPeriod(timestampNs)) {
        VLOG("Skipping anomaly declaration since within refractory period");
        return;
    }
    // TODO(guardrail): Consider guarding against too short refractory periods.
    mLastAlarmTimestampNs = timestampNs;


    // TODO: If we had access to the bucket_size_millis, consider calling resetStorage()
    // if (mAlert.refractory_period_secs() > mNumOfPastBuckets * bucketSizeNs) { resetStorage(); }

    if (mAlert.has_incidentd_details()) {
        if (mAlert.has_name()) {
            ALOGW("An anomaly (%s) has occurred! Informing incidentd.",
                  mAlert.name().c_str());
        } else {
            // TODO: Can construct a name based on the criteria (and/or relay the criteria).
            ALOGW("An anomaly (nameless) has occurred! Informing incidentd.");
        }
        informIncidentd();
    } else {
        ALOGW("An anomaly has occurred! (But informing incidentd not requested.)");
    }
}

void AnomalyTracker::declareAnomalyIfAlarmExpired(const HashableDimensionKey& dimensionKey,
                                                  const uint64_t& timestampNs) {
    auto itr = mAlarms.find(dimensionKey);
    if (itr == mAlarms.end()) {
        return;
    }

    if (itr->second != nullptr &&
        static_cast<uint32_t>(timestampNs / NS_PER_SEC) >= itr->second->timestampSec) {
        declareAnomaly(timestampNs);
        stopAlarm(dimensionKey);
    }
}

void AnomalyTracker::detectAndDeclareAnomaly(const uint64_t& timestampNs,
                                             const int64_t& currBucketNum,
                                             const HashableDimensionKey& key,
                                             const int64_t& currentBucketValue) {
    if (detectAnomaly(currBucketNum, key, currentBucketValue)) {
        declareAnomaly(timestampNs);
    }
}

void AnomalyTracker::detectAndDeclareAnomaly(const uint64_t& timestampNs,
                                             const int64_t& currBucketNum,
                                             const DimToValMap& currentBucket) {
    if (detectAnomaly(currBucketNum, currentBucket)) {
        declareAnomaly(timestampNs);
    }
}

void AnomalyTracker::startAlarm(const HashableDimensionKey& dimensionKey,
                                const uint64_t& timestampNs) {
    uint32_t timestampSec = static_cast<uint32_t>(timestampNs / NS_PER_SEC);
    if (isInRefractoryPeriod(timestampNs)) {
        VLOG("Skipping setting anomaly alarm since it'd fall in the refractory period");
        return;
    }

    sp<const AnomalyAlarm> alarm = new AnomalyAlarm{timestampSec};
    mAlarms.insert({dimensionKey, alarm});
    if (mAnomalyMonitor != nullptr) {
        mAnomalyMonitor->add(alarm);
    }
}

void AnomalyTracker::stopAlarm(const HashableDimensionKey& dimensionKey) {
    auto itr = mAlarms.find(dimensionKey);
    if (itr != mAlarms.end()) {
        mAlarms.erase(dimensionKey);
        if (mAnomalyMonitor != nullptr) {
            mAnomalyMonitor->remove(itr->second);
        }
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

bool AnomalyTracker::isInRefractoryPeriod(const uint64_t& timestampNs) {
    return mLastAlarmTimestampNs >= 0 &&
            timestampNs - mLastAlarmTimestampNs <= mAlert.refractory_period_secs() * NS_PER_SEC;
}

void AnomalyTracker::informAlarmsFired(const uint64_t& timestampNs,
        unordered_set<sp<const AnomalyAlarm>, SpHash<AnomalyAlarm>>& firedAlarms) {

    if (firedAlarms.empty() || mAlarms.empty()) return;
    // Find the intersection of firedAlarms and mAlarms.
    // The for loop is inefficient, since it loops over all keys, but that's okay since it is very
    // seldomly called. The alternative would be having AnomalyAlarms store information about the
    // AnomalyTracker and key, but that's a lot of data overhead to speed up something that is
    // rarely ever called.
    unordered_map<HashableDimensionKey, sp<const AnomalyAlarm>> matchedAlarms;
    for (const auto& kv : mAlarms) {
        if (firedAlarms.count(kv.second) > 0) {
            matchedAlarms.insert({kv.first, kv.second});
        }
    }

    // Now declare each of these alarms to have fired.
    for (const auto& kv : matchedAlarms) {
        declareAnomaly(timestampNs /* TODO: , kv.first */);
        mAlarms.erase(kv.first);
        firedAlarms.erase(kv.second); // No one else can also own it, so we're done with it.
    }
}

void AnomalyTracker::informIncidentd() {
    VLOG("informIncidentd called.");
    if (!mAlert.has_incidentd_details()) {
        ALOGE("Attempted to call incidentd without any incidentd_details.");
        return;
    }
    sp<IIncidentManager> service = interface_cast<IIncidentManager>(
            defaultServiceManager()->getService(android::String16("incident")));
    if (service == NULL) {
        ALOGW("Couldn't get the incident service.");
        return;
    }

    IncidentReportArgs incidentReport;
    const Alert::IncidentdDetails& details = mAlert.incidentd_details();
    for (int i = 0; i < details.section_size(); i++) {
        incidentReport.addSection(details.section(i));
    }
    // TODO: Pass in mAlert.name() into the addHeader?

    service->reportIncident(incidentReport);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
