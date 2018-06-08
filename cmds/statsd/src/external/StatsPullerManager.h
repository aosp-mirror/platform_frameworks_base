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

#include "StatsPullerManagerImpl.h"

namespace android {
namespace os {
namespace statsd {

class StatsPullerManager {
 public:
    virtual ~StatsPullerManager() {}

    virtual void RegisterReceiver(int tagId, wp<PullDataReceiver> receiver, int64_t nextPullTimeNs,
                                  int64_t intervalNs) {
        mPullerManager.RegisterReceiver(tagId, receiver, nextPullTimeNs, intervalNs);
    };

    virtual void UnRegisterReceiver(int tagId, wp <PullDataReceiver> receiver) {
        mPullerManager.UnRegisterReceiver(tagId, receiver);
    };

    // Verify if we know how to pull for this matcher
    bool PullerForMatcherExists(int tagId) {
        return mPullerManager.PullerForMatcherExists(tagId);
    }

    void OnAlarmFired(const int64_t currentTimeNs) {
        mPullerManager.OnAlarmFired(currentTimeNs);
    }

    virtual bool Pull(const int tagId, const int64_t timesNs,
                      vector<std::shared_ptr<LogEvent>>* data) {
        return mPullerManager.Pull(tagId, timesNs, data);
    }

    int ForceClearPullerCache() {
        return mPullerManager.ForceClearPullerCache();
    }

    void SetStatsCompanionService(sp<IStatsCompanionService> statsCompanionService) {
        mPullerManager.SetStatsCompanionService(statsCompanionService);
    }

    int ClearPullerCacheIfNecessary(int64_t timestampNs) {
        return mPullerManager.ClearPullerCacheIfNecessary(timestampNs);
    }

 private:
    StatsPullerManagerImpl
        & mPullerManager = StatsPullerManagerImpl::GetInstance();
};

}  // namespace statsd
}  // namespace os
}  // namespace android
