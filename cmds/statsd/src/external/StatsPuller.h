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
#include <utils/String16.h>
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
    StatsPuller(const int tagId);

    virtual ~StatsPuller() {}

    bool Pull(const int64_t timeNs, std::vector<std::shared_ptr<LogEvent>>* data);

    // Clear cache immediately
    int ForceClearCache();

    // Clear cache if elapsed time is more than cooldown time
    int ClearCacheIfNecessary(int64_t timestampNs);

    static void SetUidMap(const sp<UidMap>& uidMap);

    virtual void SetStatsCompanionService(sp<IStatsCompanionService> statsCompanionService){};

protected:
    // The atom tag id this puller pulls
    const int mTagId;

private:
    mutable std::mutex mLock;
    // Minimum time before this puller does actual pull again.
    // If a pull request comes before cooldown, a cached version from purevious pull
    // will be returned.
    // The actual value should be determined by individual pullers.
    int64_t mCoolDownNs;
    // For puller stats
    int64_t mMinPullIntervalNs = LONG_MAX;

    virtual bool PullInternal(std::vector<std::shared_ptr<LogEvent>>* data) = 0;

    // Cache of data from last pull. If next request comes before cool down finishes,
    // cached data will be returned.
    std::vector<std::shared_ptr<LogEvent>> mCachedData;

    int64_t mLastPullTimeNs;

    int clearCache();

    static sp<UidMap> mUidMap;
};

}  // namespace statsd
}  // namespace os
}  // namespace android
