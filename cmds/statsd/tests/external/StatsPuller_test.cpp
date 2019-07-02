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

// cooldown time 1sec.
int pullTagId = 10014;

bool pullSuccess;
vector<std::shared_ptr<LogEvent>> pullData;
long pullDelayNs;

class FakePuller : public StatsPuller {
public:
    FakePuller() : StatsPuller(pullTagId){};

private:
    bool PullInternal(vector<std::shared_ptr<LogEvent>>* data) override {
        (*data) = pullData;
        sleep_for(std::chrono::nanoseconds(pullDelayNs));
        return pullSuccess;
    }
};

FakePuller puller;

shared_ptr<LogEvent> createSimpleEvent(int64_t eventTimeNs, int64_t value) {
    shared_ptr<LogEvent> event = make_shared<LogEvent>(pullTagId, eventTimeNs);
    event->write(value);
    event->init();
    return event;
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

TEST_F(StatsPullerTest, PullSucces) {
    pullData.push_back(createSimpleEvent(1111L, 33));

    pullSuccess = true;

    vector<std::shared_ptr<LogEvent>> dataHolder;
    EXPECT_TRUE(puller.Pull(&dataHolder));
    EXPECT_EQ(1, dataHolder.size());
    EXPECT_EQ(pullTagId, dataHolder[0]->GetTagId());
    EXPECT_EQ(1111L, dataHolder[0]->GetElapsedTimestampNs());
    EXPECT_EQ(1, dataHolder[0]->size());
    EXPECT_EQ(33, dataHolder[0]->getValues()[0].mValue.int_value);

    sleep_for(std::chrono::seconds(1));

    pullData.clear();
    pullData.push_back(createSimpleEvent(2222L, 44));

    pullSuccess = true;

    EXPECT_TRUE(puller.Pull(&dataHolder));
    EXPECT_EQ(1, dataHolder.size());
    EXPECT_EQ(pullTagId, dataHolder[0]->GetTagId());
    EXPECT_EQ(2222L, dataHolder[0]->GetElapsedTimestampNs());
    EXPECT_EQ(1, dataHolder[0]->size());
    EXPECT_EQ(44, dataHolder[0]->getValues()[0].mValue.int_value);
}

TEST_F(StatsPullerTest, PullFailAfterSuccess) {
    pullData.push_back(createSimpleEvent(1111L, 33));

    pullSuccess = true;

    vector<std::shared_ptr<LogEvent>> dataHolder;
    EXPECT_TRUE(puller.Pull(&dataHolder));
    EXPECT_EQ(1, dataHolder.size());
    EXPECT_EQ(pullTagId, dataHolder[0]->GetTagId());
    EXPECT_EQ(1111L, dataHolder[0]->GetElapsedTimestampNs());
    EXPECT_EQ(1, dataHolder[0]->size());
    EXPECT_EQ(33, dataHolder[0]->getValues()[0].mValue.int_value);

    sleep_for(std::chrono::seconds(1));

    pullData.clear();
    pullData.push_back(createSimpleEvent(2222L, 44));

    pullSuccess = false;
    dataHolder.clear();
    EXPECT_FALSE(puller.Pull(&dataHolder));
    EXPECT_EQ(0, dataHolder.size());

    pullSuccess = true;
    dataHolder.clear();
    EXPECT_FALSE(puller.Pull(&dataHolder));
    EXPECT_EQ(0, dataHolder.size());
}

// Test pull takes longer than timeout, 2nd pull happens shorter than cooldown
TEST_F(StatsPullerTest, PullTakeTooLongAndPullFast) {
    pullData.push_back(createSimpleEvent(1111L, 33));
    pullSuccess = true;
    // timeout is 0.5
    pullDelayNs = (long)(0.8 * NS_PER_SEC);

    vector<std::shared_ptr<LogEvent>> dataHolder;
    EXPECT_FALSE(puller.Pull(&dataHolder));
    EXPECT_EQ(0, dataHolder.size());

    pullData.clear();
    pullData.push_back(createSimpleEvent(2222L, 44));

    pullSuccess = true;
    dataHolder.clear();
    EXPECT_FALSE(puller.Pull(&dataHolder));
    EXPECT_EQ(0, dataHolder.size());
}

TEST_F(StatsPullerTest, PullFail) {
    pullData.push_back(createSimpleEvent(1111L, 33));

    pullSuccess = false;

    vector<std::shared_ptr<LogEvent>> dataHolder;
    EXPECT_FALSE(puller.Pull(&dataHolder));
    EXPECT_EQ(0, dataHolder.size());
}

TEST_F(StatsPullerTest, PullTakeTooLong) {
    pullData.push_back(createSimpleEvent(1111L, 33));

    pullSuccess = true;
    pullDelayNs = NS_PER_SEC;

    vector<std::shared_ptr<LogEvent>> dataHolder;
    EXPECT_FALSE(puller.Pull(&dataHolder));
    EXPECT_EQ(0, dataHolder.size());
}

TEST_F(StatsPullerTest, PullTooFast) {
    pullData.push_back(createSimpleEvent(1111L, 33));

    pullSuccess = true;

    vector<std::shared_ptr<LogEvent>> dataHolder;
    EXPECT_TRUE(puller.Pull(&dataHolder));
    EXPECT_EQ(1, dataHolder.size());
    EXPECT_EQ(pullTagId, dataHolder[0]->GetTagId());
    EXPECT_EQ(1111L, dataHolder[0]->GetElapsedTimestampNs());
    EXPECT_EQ(1, dataHolder[0]->size());
    EXPECT_EQ(33, dataHolder[0]->getValues()[0].mValue.int_value);

    pullData.clear();
    pullData.push_back(createSimpleEvent(2222L, 44));

    pullSuccess = true;

    dataHolder.clear();
    EXPECT_TRUE(puller.Pull(&dataHolder));
    EXPECT_EQ(1, dataHolder.size());
    EXPECT_EQ(pullTagId, dataHolder[0]->GetTagId());
    EXPECT_EQ(1111L, dataHolder[0]->GetElapsedTimestampNs());
    EXPECT_EQ(1, dataHolder[0]->size());
    EXPECT_EQ(33, dataHolder[0]->getValues()[0].mValue.int_value);
}

TEST_F(StatsPullerTest, PullFailsAndTooFast) {
    pullData.push_back(createSimpleEvent(1111L, 33));

    pullSuccess = false;

    vector<std::shared_ptr<LogEvent>> dataHolder;
    EXPECT_FALSE(puller.Pull(&dataHolder));
    EXPECT_EQ(0, dataHolder.size());

    pullData.clear();
    pullData.push_back(createSimpleEvent(2222L, 44));

    pullSuccess = true;

    EXPECT_FALSE(puller.Pull(&dataHolder));
    EXPECT_EQ(0, dataHolder.size());
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
