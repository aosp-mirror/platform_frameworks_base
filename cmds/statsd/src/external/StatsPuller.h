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

#include <android/os/IStatsCompanionService.h>
#include <utils/RefBase.h>
#include <mutex>
#include <vector>
#include "packages/UidMap.h"

#include "guardrail/StatsdStats.h"
#include "logd/LogEvent.h"
#include "puller_util.h"

namespace android {
namespace os {
namespace statsd {

class StatsPuller : public virtual RefBase {
public:
    explicit StatsPuller(const int tagId);

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
    bool Pull(std::vector<std::shared_ptr<LogEvent>>* data);

    // Clear cache immediately
    int ForceClearCache();

    // Clear cache if elapsed time is more than cooldown time
    int ClearCacheIfNecessary(int64_t timestampNs);

    static void SetUidMap(const sp<UidMap>& uidMap);

    virtual void SetStatsCompanionService(sp<IStatsCompanionService> statsCompanionService){};

protected:
    const int mTagId;

private:
    mutable std::mutex mLock;

    // Real puller impl.
    virtual bool PullInternal(std::vector<std::shared_ptr<LogEvent>>* data) = 0;

    bool mHasGoodData = false;

    int64_t mLastPullTimeNs;

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
