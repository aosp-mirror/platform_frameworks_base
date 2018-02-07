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
#include <binder/IServiceManager.h>
#include <utils/RefBase.h>
#include <utils/threads.h>
#include <string>
#include <unordered_map>
#include <vector>
#include <list>
#include "PullDataReceiver.h"
#include "StatsPuller.h"
#include "logd/LogEvent.h"

namespace android {
namespace os {
namespace statsd {

typedef struct {
  // The field numbers of the fields that need to be summed when merging
  // isolated uid with host uid.
  std::vector<int> additiveFields;
  // The field numbers of the fields that can't be merged when merging
  // data belong to isolated uid and host uid.
  std::vector<int> nonAdditiveFields;
  // How long should the puller wait before doing an actual pull again. Default
  // 1 sec. Set this to 0 if this is handled elsewhere.
  long coolDownSec = 1;
  // The actual puller
  sp<StatsPuller> puller;
} PullAtomInfo;

class StatsPullerManagerImpl : public virtual RefBase {
public:
    static StatsPullerManagerImpl& GetInstance();

    void RegisterReceiver(int tagId, wp<PullDataReceiver> receiver, long intervalMs);

    void UnRegisterReceiver(int tagId, wp<PullDataReceiver> receiver);

    // Verify if we know how to pull for this matcher
    bool PullerForMatcherExists(int tagId) const;

    void OnAlarmFired();

    bool Pull(const int tagId, vector<std::shared_ptr<LogEvent>>* data);

    void SetTimeBaseSec(long timeBaseSec) {mTimeBaseSec = timeBaseSec;};

    int ForceClearPullerCache();

    int ClearPullerCacheIfNecessary(long timestampSec);

    const static std::map<int, PullAtomInfo> kAllPullAtomInfo;

   private:
    StatsPullerManagerImpl();

    // use this to update alarm
    sp<IStatsCompanionService> mStatsCompanionService = nullptr;

    sp<IStatsCompanionService> get_stats_companion_service();

    typedef struct {
        // pull_interval_sec : last_pull_time_sec
        std::pair<uint64_t, uint64_t> timeInfo;
        wp<PullDataReceiver> receiver;
    } ReceiverInfo;

    // mapping from simple matcher tagId to receivers
    std::map<int, std::list<ReceiverInfo>> mReceivers;

    Mutex mReceiversLock;

    long mCurrentPullingInterval;

    // for pulled metrics, it is important for the buckets to be aligned to multiple of smallest
    // bucket size. All pulled metrics start pulling based on this time, so that they can be
    // correctly attributed to the correct buckets.
    long mTimeBaseSec;
};

}  // namespace statsd
}  // namespace os
}  // namespace android
