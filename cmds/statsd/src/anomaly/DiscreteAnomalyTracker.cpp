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

#include "DiscreteAnomalyTracker.h"

#include <time.h>

namespace android {
namespace os {
namespace statsd {

DiscreteAnomalyTracker::DiscreteAnomalyTracker(const Alert& alert) : mAlert(alert) {
    VLOG("DiscreteAnomalyTracker() called");
    if (mAlert.number_of_buckets() <= 0) {
        ALOGE("Cannot create DiscreteAnomalyTracker with %lld buckets",
              (long long)mAlert.number_of_buckets());
        return;
    }
    mPastBuckets.resize(mAlert.number_of_buckets());
    reset(); // initialization
}

DiscreteAnomalyTracker::~DiscreteAnomalyTracker() {
    VLOG("~DiscreteAnomalyTracker() called");
}

void DiscreteAnomalyTracker::reset() {
    VLOG("reset() called.");
    mPastBuckets.clear();
    mPastBuckets.resize(mAlert.number_of_buckets());
    mSumOverPastBuckets.clear();
    mCurrentBucketIndex = -1;
    mLastAlarmAtBucketIndex = -1;
    mAnomalyDeclared = 0;
}

size_t DiscreteAnomalyTracker::index(int64_t bucketNum) {
    return bucketNum % mAlert.number_of_buckets();
}

void DiscreteAnomalyTracker::addOrUpdateBucket(std::shared_ptr<const DimToValMap> BucketValues,
                                               int64_t bucketIndex) {
    VLOG("addPastBucket() called.");
    if (bucketIndex <= mCurrentBucketIndex - mAlert.number_of_buckets()) {
        ALOGE("Cannot add a past bucket %lld units in past", (long long)bucketIndex);
        return;
    }

    // Empty out old mPastBuckets[i] values and update mSumOverPastBuckets.
    if (bucketIndex - mCurrentBucketIndex >= mAlert.number_of_buckets()) {
        mPastBuckets.clear();
        mPastBuckets.resize(mAlert.number_of_buckets());
        mSumOverPastBuckets.clear();
    } else {
        for (int64_t i = std::max(
                     0LL, (long long)(mCurrentBucketIndex - mAlert.number_of_buckets() + 1));
             i < bucketIndex - mAlert.number_of_buckets(); i++) {
            const int idx = index(i);
            subtractBucketFromSum(mPastBuckets[idx]);
            mPastBuckets[idx] = nullptr;  // release (but not clear) the old bucket.
        }
    }
    subtractBucketFromSum(mPastBuckets[index(bucketIndex)]);
    mPastBuckets[index(bucketIndex)] = nullptr;  // release (but not clear) the old bucket.

    // Replace the oldest bucket with the new bucket we are adding.
    mPastBuckets[index(bucketIndex)] = BucketValues;
    addBucketToSum(BucketValues);

    mCurrentBucketIndex = std::max(mCurrentBucketIndex, bucketIndex);
}

void DiscreteAnomalyTracker::subtractBucketFromSum(const shared_ptr<const DimToValMap>& bucket) {
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

void DiscreteAnomalyTracker::addBucketToSum(const shared_ptr<const DimToValMap>& bucket) {
    if (bucket == nullptr) {
        return;
    }
    // For each dimension present in the bucket, add its value to its corresponding sum.
    for (const auto& keyValuePair : *bucket) {
        mSumOverPastBuckets[keyValuePair.first] += keyValuePair.second;
    }
}

bool DiscreteAnomalyTracker::detectAnomaly() {
    for (auto itr = mSumOverPastBuckets.begin(); itr != mSumOverPastBuckets.end(); itr++) {
        if (mAlert.has_trigger_if_sum_gt() && itr->second > mAlert.trigger_if_sum_gt()) {
            return true;
        }
    }
    return false;
}

void DiscreteAnomalyTracker::declareAndDeclareAnomaly() {
    if (detectAnomaly()) {
        declareAnomaly();
    }
}

void DiscreteAnomalyTracker::declareAnomaly() {
    if (mLastAlarmAtBucketIndex >= 0 && mCurrentBucketIndex - mLastAlarmAtBucketIndex <=
                                        (long long)mAlert.refractory_period_in_buckets()) {
        VLOG("Skipping anomaly check since within refractory period");
        return;
    }
    mAnomalyDeclared++;
    // TODO(guardrail): Consider guarding against too short refractory periods.
    mLastAlarmAtBucketIndex = mCurrentBucketIndex;

    if (mAlert.has_incidentd_details()) {
        const Alert_IncidentdDetails& incident = mAlert.incidentd_details();
        if (incident.has_alert_name()) {
            ALOGW("An anomaly (%s) has occurred! Informing incidentd.",
                  incident.alert_name().c_str());
        } else {
            // TODO: Can construct a name based on the criteria (and/or relay the criteria).
            ALOGW("An anomaly (nameless) has occurred! Informing incidentd.");
        }
        // TODO: Send incidentd_details.name and incidentd_details.incidentd_sections to incidentd
    } else {
        ALOGW("An anomaly has occurred! (But informing incidentd not requested.)");
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android
