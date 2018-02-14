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

#include "StatsPuller.h"
#include "guardrail/StatsdStats.h"
#include "puller_util.h"
#include "stats_log_util.h"

namespace android {
namespace os {
namespace statsd {

using std::lock_guard;

sp<UidMap> StatsPuller::mUidMap = nullptr;
void StatsPuller::SetUidMap(const sp<UidMap>& uidMap) { mUidMap = uidMap; }

// ValueMetric has a minimum bucket size of 10min so that we don't pull too frequently
StatsPuller::StatsPuller(const int tagId)
    : mTagId(tagId) {
    if (StatsdStats::kPullerCooldownMap.find(tagId) == StatsdStats::kPullerCooldownMap.end()) {
        mCoolDownSec = StatsdStats::kDefaultPullerCooldown;
    } else {
        mCoolDownSec = StatsdStats::kPullerCooldownMap[tagId];
    }
    VLOG("Puller for tag %d created. Cooldown set to %ld", mTagId, mCoolDownSec);
}

bool StatsPuller::Pull(std::vector<std::shared_ptr<LogEvent>>* data) {
    lock_guard<std::mutex> lock(mLock);
    StatsdStats::getInstance().notePull(mTagId);
    long curTime = getElapsedRealtimeSec();
    if (curTime - mLastPullTimeSec < mCoolDownSec) {
        (*data) = mCachedData;
        StatsdStats::getInstance().notePullFromCache(mTagId);
        return true;
    }
    if (mMinPullIntervalSec > curTime - mLastPullTimeSec) {
        mMinPullIntervalSec = curTime - mLastPullTimeSec;
        StatsdStats::getInstance().updateMinPullIntervalSec(mTagId, mMinPullIntervalSec);
    }
    mCachedData.clear();
    mLastPullTimeSec = curTime;
    bool ret = PullInternal(&mCachedData);
    if (ret) {
      mergeIsolatedUidsToHostUid(mCachedData, mUidMap, mTagId);
      (*data) = mCachedData;
    }
    return ret;
}

int StatsPuller::ForceClearCache() {
    return clearCache();
}

int StatsPuller::clearCache() {
    lock_guard<std::mutex> lock(mLock);
    int ret = mCachedData.size();
    mCachedData.clear();
    mLastPullTimeSec = 0;
    return ret;
}

int StatsPuller::ClearCacheIfNecessary(long timestampSec) {
    if (timestampSec - mLastPullTimeSec > mCoolDownSec) {
        return clearCache();
    } else {
        return 0;
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android
