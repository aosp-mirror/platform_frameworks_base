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

#include <aidl/android/os/IPullAtomCallback.h>
#include <aidl/android/os/IStatsCompanionService.h>
#include <utils/RefBase.h>

#include <list>
#include <vector>

#include "PullDataReceiver.h"
#include "PullUidProvider.h"
#include "StatsPuller.h"
#include "guardrail/StatsdStats.h"
#include "logd/LogEvent.h"
#include "packages/UidMap.h"

using aidl::android::os::IPullAtomCallback;
using aidl::android::os::IStatsCompanionService;
using std::shared_ptr;

namespace android {
namespace os {
namespace statsd {

typedef struct PullerKey {
    // The uid of the process that registers this puller.
    const int uid = -1;
    // The atom that this puller is for.
    const int atomTag;

    bool operator<(const PullerKey& that) const {
        if (uid < that.uid) {
            return true;
        }
        if (uid > that.uid) {
            return false;
        }
        return atomTag < that.atomTag;
    };

    bool operator==(const PullerKey& that) const {
        return uid == that.uid && atomTag == that.atomTag;
    };
} PullerKey;

class StatsPullerManager : public virtual RefBase {
public:
    StatsPullerManager();

    virtual ~StatsPullerManager() {
    }


    // Registers a receiver for tagId. It will be pulled on the nextPullTimeNs
    // and then every intervalNs thereafter.
    virtual void RegisterReceiver(int tagId, const ConfigKey& configKey,
                                  wp<PullDataReceiver> receiver, int64_t nextPullTimeNs,
                                  int64_t intervalNs);

    // Stop listening on a tagId.
    virtual void UnRegisterReceiver(int tagId, const ConfigKey& configKey,
                                    wp<PullDataReceiver> receiver);

    // Registers a pull uid provider for the config key. When pulling atoms, it will be used to
    // determine which atoms to pull from.
    virtual void RegisterPullUidProvider(const ConfigKey& configKey, wp<PullUidProvider> provider);

    // Unregister a pull uid provider.
    virtual void UnregisterPullUidProvider(const ConfigKey& configKey);

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
    //   3) Either a PullUidProvider was not registered for the config, or the there was no puller
    //      registered for any of the uids for this atom.
    // If the metric wants to make any change to the data, like timestamps, they
    // should make a copy as this data may be shared with multiple metrics.
    virtual bool Pull(int tagId, const ConfigKey& configKey,
                      vector<std::shared_ptr<LogEvent>>* data, bool useUids = false);

    // Same as above, but directly specify the allowed uids to pull from.
    virtual bool Pull(int tagId, const vector<int32_t>& uids,
                      vector<std::shared_ptr<LogEvent>>* data, bool useUids = false);

    // Clear pull data cache immediately.
    int ForceClearPullerCache();

    // Clear pull data cache if it is beyond respective cool down time.
    int ClearPullerCacheIfNecessary(int64_t timestampNs);

    void SetStatsCompanionService(shared_ptr<IStatsCompanionService> statsCompanionService);

    void RegisterPullAtomCallback(const int uid, const int32_t atomTag, const int64_t coolDownNs,
                                  const int64_t timeoutNs, const vector<int32_t>& additiveFields,
                                  const shared_ptr<IPullAtomCallback>& callback,
                                  bool useUid = false);

    void UnregisterPullAtomCallback(const int uid, const int32_t atomTag, bool useUids = false);

    std::map<const PullerKey, sp<StatsPuller>> kAllPullAtomInfo;

private:
    const static int64_t kMinCoolDownNs = NS_PER_SEC;
    const static int64_t kMaxTimeoutNs = 10 * NS_PER_SEC;
    shared_ptr<IStatsCompanionService> mStatsCompanionService = nullptr;

    // A struct containing an atom id and a Config Key
    typedef struct ReceiverKey {
        const int atomTag;
        const ConfigKey configKey;

        inline bool operator<(const ReceiverKey& that) const {
            return atomTag == that.atomTag ? configKey < that.configKey : atomTag < that.atomTag;
        }
    } ReceiverKey;

    typedef struct {
        int64_t nextPullTimeNs;
        int64_t intervalNs;
        wp<PullDataReceiver> receiver;
    } ReceiverInfo;

    // mapping from Receiver Key to receivers
    std::map<ReceiverKey, std::list<ReceiverInfo>> mReceivers;

    // mapping from Config Key to the PullUidProvider for that config
    std::map<ConfigKey, wp<PullUidProvider>> mPullUidProviders;

    bool PullLocked(int tagId, const ConfigKey& configKey, vector<std::shared_ptr<LogEvent>>* data,
                    bool useUids = false);

    bool PullLocked(int tagId, const vector<int32_t>& uids, vector<std::shared_ptr<LogEvent>>* data,
                    bool useUids);

    // locks for data receiver and StatsCompanionService changes
    std::mutex mLock;

    void updateAlarmLocked();

    int64_t mNextPullTimeNs;

    // Death recipient that is triggered when the process holding the IPullAtomCallback has died.
    ::ndk::ScopedAIBinder_DeathRecipient mPullAtomCallbackDeathRecipient;

    /**
     * Death recipient callback that is called when a pull atom callback dies.
     * The cookie is a pointer to a PullAtomCallbackDeathCookie.
     */
    static void pullAtomCallbackDied(void* cookie);

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
