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
#include <android/os/IStatsPullerCallback.h>
#include <binder/IServiceManager.h>
#include <utils/RefBase.h>
#include <utils/threads.h>
#include <list>
#include <string>
#include <unordered_map>
#include <vector>
#include "PullDataReceiver.h"
#include "StatsPuller.h"
#include "guardrail/StatsdStats.h"
#include "logd/LogEvent.h"

namespace android {
namespace os {
namespace statsd {

typedef struct {
    // The field numbers of the fields that need to be summed when merging
    // isolated uid with host uid.
    std::vector<int> additiveFields;
    // Minimum time before this puller does actual pull again.
    // Pullers can cause significant impact to system health and battery.
    // So that we don't pull too frequently.
    // If a pull request comes before cooldown, a cached version from previous pull
    // will be returned.
    int64_t coolDownNs = 1 * NS_PER_SEC;
    // The actual puller
    sp<StatsPuller> puller;
    // Max time allowed to pull this atom.
    // We cannot reliably kill a pull thread. So we don't terminate the puller.
    // The data is discarded if the pull takes longer than this and mHasGoodData
    // marked as false.
    int64_t pullTimeoutNs = StatsdStats::kPullMaxDelayNs;
} PullAtomInfo;

class StatsPullerManager : public virtual RefBase {
public:
    StatsPullerManager();

    virtual ~StatsPullerManager() {
    }

    // Registers a receiver for tagId. It will be pulled on the nextPullTimeNs
    // and then every intervalNs thereafter.
    virtual void RegisterReceiver(int tagId, wp<PullDataReceiver> receiver, int64_t nextPullTimeNs,
                                  int64_t intervalNs);

    // Stop listening on a tagId.
    virtual void UnRegisterReceiver(int tagId, wp<PullDataReceiver> receiver);

    // Verify if we know how to pull for this matcher
    bool PullerForMatcherExists(int tagId) const;

    void OnAlarmFired(int64_t elapsedTimeNs);

    // Pulls the most recent data.
    // The data may be served from cache if consecutive pulls come within
    // mCoolDownNs.
    // Returns true if the pull was successful.
    // Returns false when
    //   1) the pull fails
    //   2) pull takes longer than mPullTimeoutNs (intrinsic to puller)
    // If the metric wants to make any change to the data, like timestamps, they
    // should make a copy as this data may be shared with multiple metrics.
    virtual bool Pull(int tagId, vector<std::shared_ptr<LogEvent>>* data);

    // Clear pull data cache immediately.
    int ForceClearPullerCache();

    // Clear pull data cache if it is beyond respective cool down time.
    int ClearPullerCacheIfNecessary(int64_t timestampNs);

    void SetStatsCompanionService(sp<IStatsCompanionService> statsCompanionService);

    void RegisterPullerCallback(int32_t atomTag,
                                const sp<IStatsPullerCallback>& callback);

    void UnregisterPullerCallback(int32_t atomTag);

    static std::map<int, PullAtomInfo> kAllPullAtomInfo;

private:
    sp<IStatsCompanionService> mStatsCompanionService = nullptr;

    typedef struct {
        int64_t nextPullTimeNs;
        int64_t intervalNs;
        wp<PullDataReceiver> receiver;
    } ReceiverInfo;

    // mapping from simple matcher tagId to receivers
    std::map<int, std::list<ReceiverInfo>> mReceivers;

    // locks for data receiver and StatsCompanionService changes
    Mutex mLock;

    void updateAlarmLocked();

    int64_t mNextPullTimeNs;

    FRIEND_TEST(GaugeMetricE2eTest, TestRandomSamplePulledEvents);
    FRIEND_TEST(GaugeMetricE2eTest, TestRandomSamplePulledEvent_LateAlarm);
    FRIEND_TEST(GaugeMetricE2eTest, TestRandomSamplePulledEventsWithActivation);
    FRIEND_TEST(GaugeMetricE2eTest, TestRandomSamplePulledEventsNoCondition);
    FRIEND_TEST(ValueMetricE2eTest, TestPulledEvents);
    FRIEND_TEST(ValueMetricE2eTest, TestPulledEvents_LateAlarm);
    FRIEND_TEST(ValueMetricE2eTest, TestPulledEvents_WithActivation);
};

}  // namespace statsd
}  // namespace os
}  // namespace android
