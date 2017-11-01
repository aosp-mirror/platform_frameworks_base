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

#ifndef DURATION_TRACKER_H
#define DURATION_TRACKER_H

#include "condition/ConditionWizard.h"
#include "stats_util.h"

#include "frameworks/base/cmds/statsd/src/stats_log.pb.h"

namespace android {
namespace os {
namespace statsd {

enum DurationState {
    kStopped = 0,  // The event is stopped.
    kStarted = 1,  // The event is on going.
    kPaused = 2,   // The event is started, but condition is false, clock is paused. When condition
                   // turns to true, kPaused will become kStarted.
};

// Hold duration information for one atom level duration in current on-going bucket.
struct DurationInfo {
    DurationState state;
    // most recent start time.
    int64_t lastStartTime;
    // existing duration in current bucket.
    int64_t lastDuration;
    // TODO: Optimize the way we track sliced condition in duration metrics.
    // cache the HashableDimensionKeys we need to query the condition for this duration event.
    ConditionKey conditionKeys;

    DurationInfo() : state(kStopped), lastStartTime(0), lastDuration(0){};
};

class DurationTracker {
public:
    DurationTracker(sp<ConditionWizard> wizard, int conditionIndex, uint64_t currentBucketStartNs,
                    uint64_t bucketSizeNs, std::vector<DurationBucketInfo>& bucket)
        : mWizard(wizard),
          mConditionTrackerIndex(conditionIndex),
          mCurrentBucketStartTimeNs(currentBucketStartNs),
          mBucketSizeNs(bucketSizeNs),
          mBucket(bucket),
          mDuration(0){};
    virtual ~DurationTracker(){};
    virtual void noteStart(const HashableDimensionKey& key, bool condition,
                           const uint64_t eventTime, const ConditionKey& conditionKey) = 0;
    virtual void noteStop(const HashableDimensionKey& key, const uint64_t eventTime) = 0;
    virtual void noteStopAll(const uint64_t eventTime) = 0;
    virtual void onSlicedConditionMayChange(const uint64_t timestamp) = 0;
    virtual void onConditionChanged(bool condition, const uint64_t timestamp) = 0;
    // Flush stale buckets if needed, and return true if the tracker has no on-going duration
    // events, so that the owner can safely remove the tracker.
    virtual bool flushIfNeeded(uint64_t timestampNs) = 0;

protected:
    sp<ConditionWizard> mWizard;

    int mConditionTrackerIndex;

    uint64_t mCurrentBucketStartTimeNs;

    int64_t mBucketSizeNs;

    std::vector<DurationBucketInfo>& mBucket;  // where to write output

    int64_t mDuration;  // current recorded duration result
};

}  // namespace statsd
}  // namespace os
}  // namespace android

#endif  // DURATION_TRACKER_H