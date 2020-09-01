// Copyright (C) 2018 The Android Open Source Project
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

#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <stdio.h>

#include <chrono>
#include <thread>
#include <vector>

#include "../metrics/metrics_test_helper.h"
#include "src/stats_log_util.h"
#include "stats_event.h"
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
int pullTagId = 10014;

bool pullSuccess;
vector<std::shared_ptr<LogEvent>> pullData;
long pullDelayNs;

class FakePuller : public StatsPuller {
public:
    FakePuller()
        : StatsPuller(pullTagId, /*coolDownNs=*/MillisToNano(10), /*timeoutNs=*/MillisToNano(5)){};

private:
    bool PullInternal(vector<std::shared_ptr<LogEvent>>* data) override {
        (*data) = pullData;
        sleep_for(std::chrono::nanoseconds(pullDelayNs));
        return pullSuccess;
    }
};

FakePuller puller;

std::unique_ptr<LogEvent> createSimpleEvent(int64_t eventTimeNs, int64_t value) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, pullTagId);
    AStatsEvent_overwriteTimestamp(statsEvent, eventTimeNs);
    AStatsEvent_writeInt64(statsEvent, value);

    std::unique_ptr<LogEvent> logEvent = std::make_unique<LogEvent>(/*uid=*/0, /*pid=*/0);
    parseStatsEventToLogEvent(statsEvent, logEvent.get());
    return logEvent;
}

class StatsPullerTest : public ::testing::Test {
public:
    StatsPullerTest() {
    }

    void SetUp() override {
        puller.ForceClearCache();
        pullSuccess = false;
        pullDelayNs = 0;
        pullData.clear();
    }
};

}  // Anonymous namespace.

TEST_F(StatsPullerTest, PullSuccess) {
    pullData.push_back(createSimpleEvent(1111L, 33));

    pullSuccess = true;

    vector<std::shared_ptr<LogEvent>> dataHolder;
    EXPECT_TRUE(puller.Pull(getElapsedRealtimeNs(), &dataHolder));
    ASSERT_EQ(1, dataHolder.size());
    EXPECT_EQ(pullTagId, dataHolder[0]->GetTagId());
    EXPECT_EQ(1111L, dataHolder[0]->GetElapsedTimestampNs());
    ASSERT_EQ(1, dataHolder[0]->size());
    EXPECT_EQ(33, dataHolder[0]->getValues()[0].mValue.int_value);

    sleep_for(std::chrono::milliseconds(11));

    pullData.clear();
    pullData.push_back(createSimpleEvent(2222L, 44));

    pullSuccess = true;

    EXPECT_TRUE(puller.Pull(getElapsedRealtimeNs(), &dataHolder));
    ASSERT_EQ(1, dataHolder.size());
    EXPECT_EQ(pullTagId, dataHolder[0]->GetTagId());
    EXPECT_EQ(2222L, dataHolder[0]->GetElapsedTimestampNs());
    ASSERT_EQ(1, dataHolder[0]->size());
    EXPECT_EQ(44, dataHolder[0]->getValues()[0].mValue.int_value);
}

TEST_F(StatsPullerTest, PullFailAfterSuccess) {
    pullData.push_back(createSimpleEvent(1111L, 33));

    pullSuccess = true;

    vector<std::shared_ptr<LogEvent>> dataHolder;
    EXPECT_TRUE(puller.Pull(getElapsedRealtimeNs(), &dataHolder));
    ASSERT_EQ(1, dataHolder.size());
    EXPECT_EQ(pullTagId, dataHolder[0]->GetTagId());
    EXPECT_EQ(1111L, dataHolder[0]->GetElapsedTimestampNs());
    ASSERT_EQ(1, dataHolder[0]->size());
    EXPECT_EQ(33, dataHolder[0]->getValues()[0].mValue.int_value);

    sleep_for(std::chrono::milliseconds(11));

    pullData.clear();
    pullData.push_back(createSimpleEvent(2222L, 44));

    pullSuccess = false;
    dataHolder.clear();
    EXPECT_FALSE(puller.Pull(getElapsedRealtimeNs(), &dataHolder));
    ASSERT_EQ(0, dataHolder.size());

    // Fails due to hitting the cool down.
    pullSuccess = true;
    dataHolder.clear();
    EXPECT_FALSE(puller.Pull(getElapsedRealtimeNs(), &dataHolder));
    ASSERT_EQ(0, dataHolder.size());
}

// Test pull takes longer than timeout, 2nd pull happens shorter than cooldown
TEST_F(StatsPullerTest, PullTakeTooLongAndPullFast) {
    pullData.push_back(createSimpleEvent(1111L, 33));
    pullSuccess = true;
    // timeout is 5ms
    pullDelayNs = MillisToNano(6);

    vector<std::shared_ptr<LogEvent>> dataHolder;
    EXPECT_FALSE(puller.Pull(getElapsedRealtimeNs(), &dataHolder));
    ASSERT_EQ(0, dataHolder.size());

    pullData.clear();
    pullData.push_back(createSimpleEvent(2222L, 44));
    pullDelayNs = 0;

    pullSuccess = true;
    dataHolder.clear();
    EXPECT_FALSE(puller.Pull(getElapsedRealtimeNs(), &dataHolder));
    ASSERT_EQ(0, dataHolder.size());
}

TEST_F(StatsPullerTest, PullFail) {
    pullData.push_back(createSimpleEvent(1111L, 33));

    pullSuccess = false;

    vector<std::shared_ptr<LogEvent>> dataHolder;
    EXPECT_FALSE(puller.Pull(getElapsedRealtimeNs(), &dataHolder));
    ASSERT_EQ(0, dataHolder.size());
}

TEST_F(StatsPullerTest, PullTakeTooLong) {
    pullData.push_back(createSimpleEvent(1111L, 33));

    pullSuccess = true;
    pullDelayNs = MillisToNano(6);

    vector<std::shared_ptr<LogEvent>> dataHolder;
    EXPECT_FALSE(puller.Pull(getElapsedRealtimeNs(), &dataHolder));
    ASSERT_EQ(0, dataHolder.size());
}

TEST_F(StatsPullerTest, PullTooFast) {
    pullData.push_back(createSimpleEvent(1111L, 33));

    pullSuccess = true;

    vector<std::shared_ptr<LogEvent>> dataHolder;
    EXPECT_TRUE(puller.Pull(getElapsedRealtimeNs(), &dataHolder));
    ASSERT_EQ(1, dataHolder.size());
    EXPECT_EQ(pullTagId, dataHolder[0]->GetTagId());
    EXPECT_EQ(1111L, dataHolder[0]->GetElapsedTimestampNs());
    ASSERT_EQ(1, dataHolder[0]->size());
    EXPECT_EQ(33, dataHolder[0]->getValues()[0].mValue.int_value);

    pullData.clear();
    pullData.push_back(createSimpleEvent(2222L, 44));

    pullSuccess = true;

    dataHolder.clear();
    EXPECT_TRUE(puller.Pull(getElapsedRealtimeNs(), &dataHolder));
    ASSERT_EQ(1, dataHolder.size());
    EXPECT_EQ(pullTagId, dataHolder[0]->GetTagId());
    EXPECT_EQ(1111L, dataHolder[0]->GetElapsedTimestampNs());
    ASSERT_EQ(1, dataHolder[0]->size());
    EXPECT_EQ(33, dataHolder[0]->getValues()[0].mValue.int_value);
}

TEST_F(StatsPullerTest, PullFailsAndTooFast) {
    pullData.push_back(createSimpleEvent(1111L, 33));

    pullSuccess = false;

    vector<std::shared_ptr<LogEvent>> dataHolder;
    EXPECT_FALSE(puller.Pull(getElapsedRealtimeNs(), &dataHolder));
    ASSERT_EQ(0, dataHolder.size());

    pullData.clear();
    pullData.push_back(createSimpleEvent(2222L, 44));

    pullSuccess = true;

    EXPECT_FALSE(puller.Pull(getElapsedRealtimeNs(), &dataHolder));
    ASSERT_EQ(0, dataHolder.size());
}

TEST_F(StatsPullerTest, PullSameEventTime) {
    pullData.push_back(createSimpleEvent(1111L, 33));

    pullSuccess = true;
    int64_t eventTimeNs = getElapsedRealtimeNs();

    vector<std::shared_ptr<LogEvent>> dataHolder;
    EXPECT_TRUE(puller.Pull(eventTimeNs, &dataHolder));
    ASSERT_EQ(1, dataHolder.size());
    EXPECT_EQ(pullTagId, dataHolder[0]->GetTagId());
    EXPECT_EQ(1111L, dataHolder[0]->GetElapsedTimestampNs());
    ASSERT_EQ(1, dataHolder[0]->size());
    EXPECT_EQ(33, dataHolder[0]->getValues()[0].mValue.int_value);

    pullData.clear();
    pullData.push_back(createSimpleEvent(2222L, 44));

    // Sleep to ensure the cool down expires.
    sleep_for(std::chrono::milliseconds(11));
    pullSuccess = true;

    dataHolder.clear();
    EXPECT_TRUE(puller.Pull(eventTimeNs, &dataHolder));
    ASSERT_EQ(1, dataHolder.size());
    EXPECT_EQ(pullTagId, dataHolder[0]->GetTagId());
    EXPECT_EQ(1111L, dataHolder[0]->GetElapsedTimestampNs());
    ASSERT_EQ(1, dataHolder[0]->size());
    EXPECT_EQ(33, dataHolder[0]->getValues()[0].mValue.int_value);
}

// Test pull takes longer than timeout, 2nd pull happens at same event time
TEST_F(StatsPullerTest, PullTakeTooLongAndPullSameEventTime) {
    pullData.push_back(createSimpleEvent(1111L, 33));
    pullSuccess = true;
    int64_t eventTimeNs = getElapsedRealtimeNs();
    // timeout is 5ms
    pullDelayNs = MillisToNano(6);

    vector<std::shared_ptr<LogEvent>> dataHolder;
    EXPECT_FALSE(puller.Pull(eventTimeNs, &dataHolder));
    ASSERT_EQ(0, dataHolder.size());

    // Sleep to ensure the cool down expires. 6ms is taken by the delay, so only 5 is needed here.
    sleep_for(std::chrono::milliseconds(5));

    pullData.clear();
    pullData.push_back(createSimpleEvent(2222L, 44));
    pullDelayNs = 0;

    pullSuccess = true;
    dataHolder.clear();
    EXPECT_FALSE(puller.Pull(eventTimeNs, &dataHolder));
    ASSERT_EQ(0, dataHolder.size());
}

TEST_F(StatsPullerTest, PullFailsAndPullSameEventTime) {
    pullData.push_back(createSimpleEvent(1111L, 33));

    pullSuccess = false;
    int64_t eventTimeNs = getElapsedRealtimeNs();

    vector<std::shared_ptr<LogEvent>> dataHolder;
    EXPECT_FALSE(puller.Pull(eventTimeNs, &dataHolder));
    ASSERT_EQ(0, dataHolder.size());

    // Sleep to ensure the cool down expires.
    sleep_for(std::chrono::milliseconds(11));

    pullData.clear();
    pullData.push_back(createSimpleEvent(2222L, 44));

    pullSuccess = true;

    EXPECT_FALSE(puller.Pull(eventTimeNs, &dataHolder));
    ASSERT_EQ(0, dataHolder.size());
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
