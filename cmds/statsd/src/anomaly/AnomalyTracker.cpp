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

#define DEBUG false  // STOPSHIP if true
#include "Log.h"

#include "AnomalyTracker.h"
#include "external/Perfetto.h"
#include "guardrail/StatsdStats.h"
#include "subscriber/IncidentdReporter.h"
#include "subscriber/SubscriberReporter.h"

#include <statslog.h>
#include <time.h>

namespace android {
namespace os {
namespace statsd {

// TODO: Get rid of bucketNumbers, and return to the original circular array method.
AnomalyTracker::AnomalyTracker(const Alert& alert, const ConfigKey& configKey)
    : mAlert(alert), mConfigKey(configKey), mNumOfPastBuckets(mAlert.num_buckets() - 1) {
    VLOG("AnomalyTracker() called");
    if (mAlert.num_buckets() <= 0) {
        ALOGE("Cannot create AnomalyTracker with %lld buckets", (long long)mAlert.num_buckets());
        return;
    }
    if (!mAlert.has_trigger_if_sum_gt()) {
        ALOGE("Cannot create AnomalyTracker without threshold");
        return;
    }
    resetStorage();  // initialization
}

AnomalyTracker::~AnomalyTracker() {
    VLOG("~AnomalyTracker() called");
}

void AnomalyTracker::resetStorage() {
    VLOG("resetStorage() called.");
    mPastBuckets.clear();
    // Excludes the current bucket.
    mPastBuckets.resize(mNumOfPastBuckets);
    mSumOverPastBuckets.clear();
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

void AnomalyTracker::addPastBucket(const MetricDimensionKey& key, const int64_t& bucketValue,
                                   const int64_t& bucketNum) {
    if (mNumOfPastBuckets == 0) {
        return;
    }
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
    if (mNumOfPastBuckets == 0) {
        return;
    }
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

int64_t AnomalyTracker::getPastBucketValue(const MetricDimensionKey& key,
                                           const int64_t& bucketNum) const {
    if (mNumOfPastBuckets == 0) {
        return 0;
    }

    const auto& bucket = mPastBuckets[index(bucketNum)];
    if (bucket == nullptr) {
        return 0;
    }
    const auto& itr = bucket->find(key);
    return itr == bucket->end() ? 0 : itr->second;
}

int64_t AnomalyTracker::getSumOverPastBuckets(const MetricDimensionKey& key) const {
    const auto& itr = mSumOverPastBuckets.find(key);
    if (itr != mSumOverPastBuckets.end()) {
        return itr->second;
    }
    return 0;
}

bool AnomalyTracker::detectAnomaly(const int64_t& currentBucketNum, const MetricDimensionKey& key,
                                   const int64_t& currentBucketValue) {
    if (currentBucketNum > mMostRecentBucketNum + 1) {
        // TODO: This creates a needless 0 entry in mSumOverPastBuckets. Fix this.
        addPastBucket(key, 0, currentBucketNum - 1);
    }
    return mAlert.has_trigger_if_sum_gt() &&
           getSumOverPastBuckets(key) + currentBucketValue > mAlert.trigger_if_sum_gt();
}

void AnomalyTracker::declareAnomaly(const uint64_t& timestampNs, const MetricDimensionKey& key) {
    // TODO: Why receive timestamp? RefractoryPeriod should always be based on real time right now.
    if (isInRefractoryPeriod(timestampNs, key)) {
        VLOG("Skipping anomaly declaration since within refractory period");
        return;
    }
    mRefractoryPeriodEndsSec[key] = (timestampNs / NS_PER_SEC) + mAlert.refractory_period_secs();

    // TODO: If we had access to the bucket_size_millis, consider calling resetStorage()
    // if (mAlert.refractory_period_secs() > mNumOfPastBuckets * bucketSizeNs) { resetStorage(); }

    if (!mSubscriptions.empty()) {
        if (mAlert.has_id()) {
            ALOGI("An anomaly (%llu) has occurred! Informing subscribers.", mAlert.id());
            informSubscribers(key);
        } else {
            ALOGI("An anomaly (with no id) has occurred! Not informing any subscribers.");
        }
    } else {
        ALOGI("An anomaly has occurred! (But no subscriber for that alert.)");
    }

    StatsdStats::getInstance().noteAnomalyDeclared(mConfigKey, mAlert.id());

    // TODO: This should also take in the const MetricDimensionKey& key?
    android::util::stats_write(android::util::ANOMALY_DETECTED, mConfigKey.GetUid(),
                               mConfigKey.GetId(), mAlert.id());
}

void AnomalyTracker::detectAndDeclareAnomaly(const uint64_t& timestampNs,
                                             const int64_t& currBucketNum,
                                             const MetricDimensionKey& key,
                                             const int64_t& currentBucketValue) {
    if (detectAnomaly(currBucketNum, key, currentBucketValue)) {
        declareAnomaly(timestampNs, key);
    }
}

bool AnomalyTracker::isInRefractoryPeriod(const uint64_t& timestampNs,
                                          const MetricDimensionKey& key) {
    const auto& it = mRefractoryPeriodEndsSec.find(key);
    if (it != mRefractoryPeriodEndsSec.end()) {
        if ((timestampNs / NS_PER_SEC) <= it->second) {
            return true;
        } else {
            mRefractoryPeriodEndsSec.erase(key);
        }
    }
    return false;
}

void AnomalyTracker::informSubscribers(const MetricDimensionKey& key) {
    VLOG("informSubscribers called.");
    if (mSubscriptions.empty()) {
        ALOGE("Attempt to call with no subscribers.");
        return;
    }

    for (const Subscription& subscription : mSubscriptions) {
        switch (subscription.subscriber_information_case()) {
            case Subscription::SubscriberInformationCase::kIncidentdDetails:
                if (!GenerateIncidentReport(subscription.incidentd_details(), mAlert, mConfigKey)) {
                    ALOGW("Failed to generate incident report.");
                }
                break;
            case Subscription::SubscriberInformationCase::kPerfettoDetails:
                if (!CollectPerfettoTraceAndUploadToDropbox(subscription.perfetto_details())) {
                    ALOGW("Failed to generate prefetto traces.");
                }
                break;
            case Subscription::SubscriberInformationCase::kBroadcastSubscriberDetails:
                SubscriberReporter::getInstance().alertBroadcastSubscriber(mConfigKey, subscription,
                                                                           key);
                break;
            default:
                break;
        }
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android
