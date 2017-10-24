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

#include "CountAnomalyTracker.h"

#include <time.h>

namespace android {
namespace os {
namespace statsd {

CountAnomalyTracker::CountAnomalyTracker(const Alert& alert)
    : mAlert(alert),
      mNumPastBuckets(alert.number_of_buckets() > 0 ? alert.number_of_buckets() - 1 : 0),
      mPastBuckets(mNumPastBuckets > 0 ? (new int[mNumPastBuckets]) : nullptr) {

    VLOG("CountAnomalyTracker() called");
    if (alert.number_of_buckets() < 1) {
        ALOGE("Cannot create CountAnomalyTracker with %d buckets", alert.number_of_buckets());
    }
    reset(); // initialization
}

CountAnomalyTracker::~CountAnomalyTracker() {
    VLOG("~CountAnomalyTracker() called");
}

void CountAnomalyTracker::addPastBucket(int pastBucketCount,
                                        time_t numberOfBucketsAgo) {
    VLOG("addPastBucket() called.");
    if (numberOfBucketsAgo < 1) {
        ALOGE("Cannot add a past bucket %ld units in past", numberOfBucketsAgo);
        return;
    }
    // If past bucket was ancient, just empty out all past info.
    // This always applies if mNumPastBuckets == 0 (i.e. store no past buckets).
    if (numberOfBucketsAgo > (time_t) mNumPastBuckets) {
        reset();
        return;
    }

    // Empty out old mPastBuckets[i] values and update mSumPastCounters.
    for (size_t i = mOldestBucketIndex;
                        i < mOldestBucketIndex + numberOfBucketsAgo; i++) {
        mSumPastCounters -= mPastBuckets[index(i)];
        mPastBuckets[index(i)] = 0;
    }

    // Replace the oldest bucket with the new bucket we are adding.
    mPastBuckets[mOldestBucketIndex] = pastBucketCount;
    mSumPastCounters += pastBucketCount;

    // Advance the oldest bucket index by numberOfBucketsAgo units.
    mOldestBucketIndex = index(mOldestBucketIndex + numberOfBucketsAgo);

    // TODO: Once dimensions are added to mSumPastCounters:
    // iterate through mSumPastCounters and remove any entries that are 0.
}

void CountAnomalyTracker::reset() {
    VLOG("reset() called.");
    for (size_t i = 0; i < mNumPastBuckets; i++) {
        mPastBuckets[i] = 0;
    }
    mSumPastCounters = 0;
    mOldestBucketIndex = 0;
}

void CountAnomalyTracker::checkAnomaly(int currentCount) {
    // Skip the check if in refractory period.
    if (time(nullptr) < mRefractoryPeriodEndsSec) {
        VLOG("Skipping anomaly check since within refractory period");
        return;
    }

    // TODO: Remove these extremely verbose debugging log.
    VLOG("Checking whether %d + %d > %lld",
         mSumPastCounters, currentCount, mAlert.trigger_if_sum_gt());

    // Note that this works even if mNumPastBuckets < 1 (since then
    // mSumPastCounters = 0 so the comparison is based only on currentCount).
    if (mAlert.has_trigger_if_sum_gt() &&
            mSumPastCounters + currentCount > mAlert.trigger_if_sum_gt()) {
        declareAnomaly();
    }
}

void CountAnomalyTracker::declareAnomaly() {
    // TODO(guardrail): Consider guarding against too short refractory periods.
    time_t currTime = time(nullptr);
    mRefractoryPeriodEndsSec = currTime + mAlert.refractory_period_secs();

    // TODO: If we had access to the bucket_size_millis, consider calling reset()
    // if (mAlert.refractory_period_secs() > mNumPastBuckets * bucket_size_millis * 1000).

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
