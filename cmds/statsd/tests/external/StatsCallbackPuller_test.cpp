// Copyright (C) 2019 The Android Open Source Project
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

#include "src/external/StatsCallbackPuller.h"

#include <android/os/BnPullAtomCallback.h>
#include <android/os/IPullAtomResultReceiver.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <stdio.h>

#include <chrono>
#include <thread>
#include <vector>

#include "../metrics/metrics_test_helper.h"
#include "src/stats_log_util.h"
#include "tests/statsd_test_util.h"

#ifdef __ANDROID__

namespace android {
namespace os {
namespace statsd {

using namespace testing;
using std::make_shared;
using std::shared_ptr;
using std::vector;
using std::this_thread::sleep_for;
using testing::Contains;

namespace {
int pullTagId = -12;
bool pullSuccess;
vector<int64_t> values;
int64_t pullDelayNs;
int64_t pullTimeoutNs;
int64_t pullCoolDownNs;
std::thread pullThread;

stats_event* createSimpleEvent(int64_t value) {
    stats_event* event = stats_event_obtain();
    stats_event_set_atom_id(event, pullTagId);
    stats_event_write_int64(event, value);
    stats_event_build(event);
    return event;
}

void executePull(const sp<IPullAtomResultReceiver>& resultReceiver) {
    // Convert stats_events into StatsEventParcels.
    std::vector<android::util::StatsEventParcel> parcels;
    for (int i = 0; i < values.size(); i++) {
        stats_event* event = createSimpleEvent(values[i]);
        size_t size;
        uint8_t* buffer = stats_event_get_buffer(event, &size);

        android::util::StatsEventParcel p;
        // vector.assign() creates a copy, but this is inevitable unless
        // stats_event.h/c uses a vector as opposed to a buffer.
        p.buffer.assign(buffer, buffer + size);
        parcels.push_back(std::move(p));
        stats_event_release(event);
    }

    sleep_for(std::chrono::nanoseconds(pullDelayNs));
    resultReceiver->pullFinished(pullTagId, pullSuccess, parcels);
}

class FakePullAtomCallback : public BnPullAtomCallback {
public:
    binder::Status onPullAtom(int atomTag,
                              const sp<IPullAtomResultReceiver>& resultReceiver) override {
        // Force pull to happen in separate thread to simulate binder.
        pullThread = std::thread(executePull, resultReceiver);
        return binder::Status::ok();
    }
};

class StatsCallbackPullerTest : public ::testing::Test {
public:
    StatsCallbackPullerTest() {
    }

    void SetUp() override {
        pullSuccess = false;
        pullDelayNs = 0;
        values.clear();
        pullTimeoutNs = 10000000000LL;  // 10 seconds.
        pullCoolDownNs = 1000000000;    // 1 second.
    }

    void TearDown() override {
        if (pullThread.joinable()) {
            pullThread.join();
        }
        values.clear();
    }
};
}  // Anonymous namespace.

TEST_F(StatsCallbackPullerTest, PullSuccess) {
    sp<FakePullAtomCallback> cb = new FakePullAtomCallback();
    int64_t value = 43;
    pullSuccess = true;
    values.push_back(value);

    StatsCallbackPuller puller(pullTagId, cb, pullTimeoutNs);

    vector<std::shared_ptr<LogEvent>> dataHolder;
    int64_t startTimeNs = getElapsedRealtimeNs();
    EXPECT_TRUE(puller.PullInternal(&dataHolder));
    int64_t endTimeNs = getElapsedRealtimeNs();

    EXPECT_EQ(1, dataHolder.size());
    EXPECT_EQ(pullTagId, dataHolder[0]->GetTagId());
    EXPECT_LT(startTimeNs, dataHolder[0]->GetElapsedTimestampNs());
    EXPECT_GT(endTimeNs, dataHolder[0]->GetElapsedTimestampNs());
    EXPECT_EQ(1, dataHolder[0]->size());
    EXPECT_EQ(value, dataHolder[0]->getValues()[0].mValue.int_value);
}

TEST_F(StatsCallbackPullerTest, PullFail) {
    sp<FakePullAtomCallback> cb = new FakePullAtomCallback();
    pullSuccess = false;
    int64_t value = 1234;
    values.push_back(value);

    StatsCallbackPuller puller(pullTagId, cb, pullTimeoutNs);

    vector<std::shared_ptr<LogEvent>> dataHolder;
    EXPECT_FALSE(puller.PullInternal(&dataHolder));
    EXPECT_EQ(0, dataHolder.size());
}

TEST_F(StatsCallbackPullerTest, PullTimeout) {
    sp<FakePullAtomCallback> cb = new FakePullAtomCallback();
    pullSuccess = true;
    pullDelayNs = 500000000;  // 500ms.
    pullTimeoutNs = 10000;    // 10 microseconds.
    int64_t value = 4321;
    values.push_back(value);

    StatsCallbackPuller puller(pullTagId, cb, pullTimeoutNs);

    vector<std::shared_ptr<LogEvent>> dataHolder;
    int64_t startTimeNs = getElapsedRealtimeNs();
    // Returns true to let StatsPuller code evaluate the timeout.
    EXPECT_TRUE(puller.PullInternal(&dataHolder));
    int64_t endTimeNs = getElapsedRealtimeNs();
    int64_t actualPullDurationNs = endTimeNs - startTimeNs;

    // Pull should take at least the timeout amount of time, but should stop early because the delay
    // is bigger.
    EXPECT_LT(pullTimeoutNs, actualPullDurationNs);
    EXPECT_GT(pullDelayNs, actualPullDurationNs);
    EXPECT_EQ(0, dataHolder.size());

    // Let the pull return and make sure that the dataHolder is not modified.
    pullThread.join();
    EXPECT_EQ(0, dataHolder.size());
}

// Register a puller and ensure that the timeout logic works.
TEST_F(StatsCallbackPullerTest, RegisterAndTimeout) {
    sp<FakePullAtomCallback> cb = new FakePullAtomCallback();
    pullSuccess = true;
    pullDelayNs = 500000000;  // 500 ms.
    pullTimeoutNs = 10000;    // 10 microsseconds.
    int64_t value = 4321;
    values.push_back(value);

    StatsPullerManager pullerManager;
    pullerManager.RegisterPullAtomCallback(/*uid=*/-1, pullTagId, pullCoolDownNs, pullTimeoutNs,
                                           vector<int32_t>(), cb);
    vector<std::shared_ptr<LogEvent>> dataHolder;
    int64_t startTimeNs = getElapsedRealtimeNs();
    // Returns false, since StatsPuller code will evaluate the timeout.
    EXPECT_FALSE(pullerManager.Pull(pullTagId, &dataHolder));
    int64_t endTimeNs = getElapsedRealtimeNs();
    int64_t actualPullDurationNs = endTimeNs - startTimeNs;

    // Pull should take at least the timeout amount of time, but should stop early because the delay
    // is bigger.
    EXPECT_LT(pullTimeoutNs, actualPullDurationNs);
    EXPECT_GT(pullDelayNs, actualPullDurationNs);
    EXPECT_EQ(0, dataHolder.size());

    // Let the pull return and make sure that the dataHolder is not modified.
    pullThread.join();
    EXPECT_EQ(0, dataHolder.size());
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
