// Copyright (C) 2020 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "src/external/StatsPullerManager.h"

#include <aidl/android/os/IPullAtomResultReceiver.h>
#include <aidl/android/util/StatsEventParcel.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include "stats_event.h"
#include "tests/statsd_test_util.h"

using aidl::android::util::StatsEventParcel;
using ::ndk::SharedRefBase;
using std::make_shared;
using std::shared_ptr;
using std::vector;

namespace android {
namespace os {
namespace statsd {

namespace {

int pullTagId1 = 10101;
int pullTagId2 = 10102;
int uid1 = 9999;
int uid2 = 8888;
ConfigKey configKey(50, 12345);
ConfigKey badConfigKey(60, 54321);
int unregisteredUid = 98765;
int64_t coolDownNs = NS_PER_SEC;
int64_t timeoutNs = NS_PER_SEC / 2;

AStatsEvent* createSimpleEvent(int32_t atomId, int32_t value) {
    AStatsEvent* event = AStatsEvent_obtain();
    AStatsEvent_setAtomId(event, atomId);
    AStatsEvent_writeInt32(event, value);
    AStatsEvent_build(event);
    return event;
}

class FakePullAtomCallback : public BnPullAtomCallback {
public:
    FakePullAtomCallback(int32_t uid) : mUid(uid){};
    Status onPullAtom(int atomTag,
                      const shared_ptr<IPullAtomResultReceiver>& resultReceiver) override {
        vector<StatsEventParcel> parcels;
        AStatsEvent* event = createSimpleEvent(atomTag, mUid);
        size_t size;
        uint8_t* buffer = AStatsEvent_getBuffer(event, &size);

        StatsEventParcel p;
        // vector.assign() creates a copy, but this is inevitable unless
        // stats_event.h/c uses a vector as opposed to a buffer.
        p.buffer.assign(buffer, buffer + size);
        parcels.push_back(std::move(p));
        AStatsEvent_release(event);
        resultReceiver->pullFinished(atomTag, /*success*/ true, parcels);
        return Status::ok();
    }
    int32_t mUid;
};

class FakePullUidProvider : public PullUidProvider {
public:
    vector<int32_t> getPullAtomUids(int atomId) override {
        if (atomId == pullTagId1) {
            return {uid2, uid1};
        } else if (atomId == pullTagId2) {
            return {uid2};
        }
        return {};
    }
};

sp<StatsPullerManager> createPullerManagerAndRegister() {
    sp<StatsPullerManager> pullerManager = new StatsPullerManager();
    shared_ptr<FakePullAtomCallback> cb1 = SharedRefBase::make<FakePullAtomCallback>(uid1);
    pullerManager->RegisterPullAtomCallback(uid1, pullTagId1, coolDownNs, timeoutNs, {}, cb1, true);
    shared_ptr<FakePullAtomCallback> cb2 = SharedRefBase::make<FakePullAtomCallback>(uid2);
    pullerManager->RegisterPullAtomCallback(uid2, pullTagId1, coolDownNs, timeoutNs, {}, cb2, true);
    pullerManager->RegisterPullAtomCallback(uid1, pullTagId2, coolDownNs, timeoutNs, {}, cb1, true);
    return pullerManager;
}
}  // anonymous namespace

TEST(StatsPullerManagerTest, TestPullInvalidUid) {
    sp<StatsPullerManager> pullerManager = createPullerManagerAndRegister();

    vector<shared_ptr<LogEvent>> data;
    EXPECT_FALSE(pullerManager->Pull(pullTagId1, {unregisteredUid}, &data, true));
}

TEST(StatsPullerManagerTest, TestPullChoosesCorrectUid) {
    sp<StatsPullerManager> pullerManager = createPullerManagerAndRegister();

    vector<shared_ptr<LogEvent>> data;
    EXPECT_TRUE(pullerManager->Pull(pullTagId1, {uid1}, &data, true));
    ASSERT_EQ(data.size(), 1);
    EXPECT_EQ(data[0]->GetTagId(), pullTagId1);
    ASSERT_EQ(data[0]->getValues().size(), 1);
    EXPECT_EQ(data[0]->getValues()[0].mValue.int_value, uid1);
}

TEST(StatsPullerManagerTest, TestPullInvalidConfigKey) {
    sp<StatsPullerManager> pullerManager = createPullerManagerAndRegister();
    sp<FakePullUidProvider> uidProvider = new FakePullUidProvider();
    pullerManager->RegisterPullUidProvider(configKey, uidProvider);

    vector<shared_ptr<LogEvent>> data;
    EXPECT_FALSE(pullerManager->Pull(pullTagId1, badConfigKey, &data, true));
}

TEST(StatsPullerManagerTest, TestPullConfigKeyGood) {
    sp<StatsPullerManager> pullerManager = createPullerManagerAndRegister();
    sp<FakePullUidProvider> uidProvider = new FakePullUidProvider();
    pullerManager->RegisterPullUidProvider(configKey, uidProvider);

    vector<shared_ptr<LogEvent>> data;
    EXPECT_TRUE(pullerManager->Pull(pullTagId1, configKey, &data, true));
    EXPECT_EQ(data[0]->GetTagId(), pullTagId1);
    ASSERT_EQ(data[0]->getValues().size(), 1);
    EXPECT_EQ(data[0]->getValues()[0].mValue.int_value, uid2);
}

TEST(StatsPullerManagerTest, TestPullConfigKeyNoPullerWithUid) {
    sp<StatsPullerManager> pullerManager = createPullerManagerAndRegister();
    sp<FakePullUidProvider> uidProvider = new FakePullUidProvider();
    pullerManager->RegisterPullUidProvider(configKey, uidProvider);

    vector<shared_ptr<LogEvent>> data;
    EXPECT_FALSE(pullerManager->Pull(pullTagId2, configKey, &data, true));
}

}  // namespace statsd
}  // namespace os
}  // namespace android