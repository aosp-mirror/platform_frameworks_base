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

#include <aidl/android/os/IStatsCompanionService.h>
#include <utils/RefBase.h>
#include <mutex>
#include <vector>
#include "packages/UidMap.h"

#include "guardrail/StatsdStats.h"
#include "logd/LogEvent.h"
#include "puller_util.h"

using aidl::android::os::IStatsCompanionService;
using std::shared_ptr;

namespace android {
namespace os {
namespace statsd {

class StatsPuller : public virtual RefBase {
public:
    explicit StatsPuller(const int tagId,
                         const int64_t coolDownNs = NS_PER_SEC,
                         const int64_t pullTimeoutNs = StatsdStats::kPullMaxDelayNs,
                         const std::vector<int> additiveFields = std::vector<int>());

    virtual ~StatsPuller() {}

    // Pulls the most recent data.
    // The data may be served from cache if consecutive pulls come within
    // predefined cooldown time.
    // Returns true if the pull was successful.
    // Returns false when
    //   1) the pull fails
    //   2) pull takes longer than mPullTimeoutNs (intrinsic to puller)
    // If a metric wants to make any change to the data, like timestamps, it
    // should make a copy as this data may be shared with multiple metrics.
    bool Pull(const int64_t eventTimeNs, std::vector<std::shared_ptr<LogEvent>>* data);

    // Clear cache immediately
    int ForceClearCache();

    // Clear cache if elapsed time is more than cooldown time
    int ClearCacheIfNecessary(int64_t timestampNs);

    static void SetUidMap(const sp<UidMap>& uidMap);

    virtual void SetStatsCompanionService(
            shared_ptr<IStatsCompanionService> statsCompanionService) {};

protected:
    const int mTagId;

    // Max time allowed to pull this atom.
    // We cannot reliably kill a pull thread. So we don't terminate the puller.
    // The data is discarded if the pull takes longer than this and mHasGoodData
    // marked as false.
    const int64_t mPullTimeoutNs = StatsdStats::kPullMaxDelayNs;

private:
    mutable std::mutex mLock;

    // Real puller impl.
    virtual bool PullInternal(std::vector<std::shared_ptr<LogEvent>>* data) = 0;

    bool mHasGoodData = false;

    // Minimum time before this puller does actual pull again.
    // Pullers can cause significant impact to system health and battery.
    // So that we don't pull too frequently.
    // If a pull request comes before cooldown, a cached version from previous pull
    // will be returned.
    const int64_t mCoolDownNs = 1 * NS_PER_SEC;

    // The field numbers of the fields that need to be summed when merging
    // isolated uid with host uid.
    const std::vector<int> mAdditiveFields;

    int64_t mLastPullTimeNs;

    // All pulls happen due to an event (app upgrade, bucket boundary, condition change, etc).
    // If multiple pulls need to be done at the same event time, we will always use the cache after
    // the first pull.
    int64_t mLastEventTimeNs;

    // Cache of data from last pull. If next request comes before cool down finishes,
    // cached data will be returned.
    // Cached data is cleared when
    //   1) A pull fails
    //   2) A new pull request comes after cooldown time.
    //   3) clearCache is called.
    std::vector<std::shared_ptr<LogEvent>> mCachedData;

    int clearCache();

    int clearCacheLocked();

    static sp<UidMap> mUidMap;
};

}  // namespace statsd
}  // namespace os
}  // namespace android
