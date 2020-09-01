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
#include "StatsPullerManager.h"
#include "guardrail/StatsdStats.h"
#include "puller_util.h"
#include "stats_log_util.h"

namespace android {
namespace os {
namespace statsd {

using std::lock_guard;

sp<UidMap> StatsPuller::mUidMap = nullptr;
void StatsPuller::SetUidMap(const sp<UidMap>& uidMap) { mUidMap = uidMap; }

StatsPuller::StatsPuller(const int tagId, const int64_t coolDownNs, const int64_t pullTimeoutNs,
                         const std::vector<int> additiveFields)
    : mTagId(tagId),
      mPullTimeoutNs(pullTimeoutNs),
      mCoolDownNs(coolDownNs),
      mAdditiveFields(additiveFields),
      mLastPullTimeNs(0),
      mLastEventTimeNs(0) {
}

bool StatsPuller::Pull(const int64_t eventTimeNs, std::vector<std::shared_ptr<LogEvent>>* data) {
    lock_guard<std::mutex> lock(mLock);
    const int64_t elapsedTimeNs = getElapsedRealtimeNs();
    const int64_t systemUptimeMillis = getSystemUptimeMillis();
    StatsdStats::getInstance().notePull(mTagId);
    const bool shouldUseCache =
            (mLastEventTimeNs == eventTimeNs) || (elapsedTimeNs - mLastPullTimeNs < mCoolDownNs);
    if (shouldUseCache) {
        if (mHasGoodData) {
            (*data) = mCachedData;
            StatsdStats::getInstance().notePullFromCache(mTagId);

        }
        return mHasGoodData;
    }
    if (mLastPullTimeNs > 0) {
        StatsdStats::getInstance().updateMinPullIntervalSec(
                mTagId, (elapsedTimeNs - mLastPullTimeNs) / NS_PER_SEC);
    }
    mCachedData.clear();
    mLastPullTimeNs = elapsedTimeNs;
    mLastEventTimeNs = eventTimeNs;
    mHasGoodData = PullInternal(&mCachedData);
    if (!mHasGoodData) {
        return mHasGoodData;
    }
    const int64_t pullElapsedDurationNs = getElapsedRealtimeNs() - elapsedTimeNs;
    const int64_t pullSystemUptimeDurationMillis = getSystemUptimeMillis() - systemUptimeMillis;
    StatsdStats::getInstance().notePullTime(mTagId, pullElapsedDurationNs);
    const bool pullTimeOut = pullElapsedDurationNs > mPullTimeoutNs;
    if (pullTimeOut) {
        // Something went wrong. Discard the data.
        mCachedData.clear();
        mHasGoodData = false;
        StatsdStats::getInstance().notePullTimeout(
                mTagId, pullSystemUptimeDurationMillis, NanoToMillis(pullElapsedDurationNs));
        ALOGW("Pull for atom %d exceeds timeout %lld nano seconds.", mTagId,
              (long long)pullElapsedDurationNs);
        return mHasGoodData;
    }

    if (mCachedData.size() > 0) {
        mapAndMergeIsolatedUidsToHostUid(mCachedData, mUidMap, mTagId, mAdditiveFields);
    }

    if (mCachedData.empty()) {
        VLOG("Data pulled is empty");
        StatsdStats::getInstance().noteEmptyData(mTagId);
    }

    (*data) = mCachedData;
    return mHasGoodData;
}

int StatsPuller::ForceClearCache() {
    return clearCache();
}

int StatsPuller::clearCache() {
    lock_guard<std::mutex> lock(mLock);
    return clearCacheLocked();
}

int StatsPuller::clearCacheLocked() {
    int ret = mCachedData.size();
    mCachedData.clear();
    mLastPullTimeNs = 0;
    mLastEventTimeNs = 0;
    return ret;
}

int StatsPuller::ClearCacheIfNecessary(int64_t timestampNs) {
    if (timestampNs - mLastPullTimeNs > mCoolDownNs) {
        return clearCache();
    } else {
        return 0;
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android
