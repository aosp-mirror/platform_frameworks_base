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

#include "external/puller_util.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <stdio.h>

#include <vector>

#include "../metrics/metrics_test_helper.h"
#include "stats_event.h"
#include "statslog.h"

#ifdef __ANDROID__

namespace android {
namespace os {
namespace statsd {

using namespace testing;
using std::make_shared;
using std::shared_ptr;
using std::vector;
using testing::Contains;
/*
 * Test merge isolated and host uid
 */
namespace {
int uidAtomTagId = android::util::CPU_CLUSTER_TIME;
const vector<int> uidAdditiveFields = {3};
int nonUidAtomTagId = android::util::SYSTEM_UPTIME;
int timestamp = 1234;
int isolatedUid = 30;
int isolatedAdditiveData = 31;
int isolatedNonAdditiveData = 32;
int hostUid = 20;
int hostAdditiveData = 21;
int hostNonAdditiveData = 22;

void extractIntoVector(vector<shared_ptr<LogEvent>> events,
                      vector<vector<int>>& ret) {
  ret.clear();
  status_t err;
  for (const auto& event : events) {
    vector<int> vec;
    vec.push_back(event->GetInt(1, &err));
    vec.push_back(event->GetInt(2, &err));
    vec.push_back(event->GetInt(3, &err));
    ret.push_back(vec);
  }
}

std::shared_ptr<LogEvent> makeUidLogEvent(uint64_t timestampNs, int uid, int data1, int data2) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, uidAtomTagId);
    AStatsEvent_overwriteTimestamp(statsEvent, timestampNs);

    AStatsEvent_writeInt32(statsEvent, uid);
    AStatsEvent_writeInt32(statsEvent, data1);
    AStatsEvent_writeInt32(statsEvent, data2);
    AStatsEvent_build(statsEvent);

    size_t size;
    uint8_t* buf = AStatsEvent_getBuffer(statsEvent, &size);

    std::shared_ptr<LogEvent> logEvent = std::make_unique<LogEvent>(/*uid=*/0, /*pid=*/0);
    logEvent->parseBuffer(buf, size);
    AStatsEvent_release(statsEvent);
    return logEvent;
}

std::shared_ptr<LogEvent> makeNonUidAtomLogEvent(uint64_t timestampNs, int data1) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, nonUidAtomTagId);
    AStatsEvent_overwriteTimestamp(statsEvent, timestampNs);

    AStatsEvent_writeInt32(statsEvent, data1);
    AStatsEvent_build(statsEvent);

    size_t size;
    uint8_t* buf = AStatsEvent_getBuffer(statsEvent, &size);

    std::shared_ptr<LogEvent> logEvent = std::make_unique<LogEvent>(/*uid=*/0, /*pid=*/0);
    logEvent->parseBuffer(buf, size);
    AStatsEvent_release(statsEvent);
    return logEvent;
}

}  // anonymous namespace

TEST(puller_util, MergeNoDimension) {
    vector<shared_ptr<LogEvent>> inputData;

    // 30->22->31
    inputData.push_back(
            makeUidLogEvent(timestamp, isolatedUid, hostNonAdditiveData, isolatedAdditiveData));

    // 20->22->21
    inputData.push_back(makeUidLogEvent(timestamp, hostUid, hostNonAdditiveData, hostAdditiveData));

    sp<MockUidMap> uidMap = new NaggyMock<MockUidMap>();
    EXPECT_CALL(*uidMap, getHostUidOrSelf(isolatedUid)).WillRepeatedly(Return(hostUid));
    EXPECT_CALL(*uidMap, getHostUidOrSelf(Ne(isolatedUid))).WillRepeatedly(ReturnArg<0>());
    mapAndMergeIsolatedUidsToHostUid(inputData, uidMap, uidAtomTagId, uidAdditiveFields);

    vector<vector<int>> actual;
    extractIntoVector(inputData, actual);
    vector<int> expectedV1 = {20, 22, 52};
    EXPECT_EQ(1, (int)actual.size());
    EXPECT_THAT(actual, Contains(expectedV1));
}

TEST(puller_util, MergeWithDimension) {
    vector<shared_ptr<LogEvent>> inputData;

    // 30->32->31
    inputData.push_back(
            makeUidLogEvent(timestamp, isolatedUid, isolatedNonAdditiveData, isolatedAdditiveData));

    // 20->32->21
    inputData.push_back(
            makeUidLogEvent(timestamp, hostUid, isolatedNonAdditiveData, hostAdditiveData));

    // 20->22->21
    inputData.push_back(makeUidLogEvent(timestamp, hostUid, hostNonAdditiveData, hostAdditiveData));

    sp<MockUidMap> uidMap = new NaggyMock<MockUidMap>();
    EXPECT_CALL(*uidMap, getHostUidOrSelf(isolatedUid)).WillRepeatedly(Return(hostUid));
    EXPECT_CALL(*uidMap, getHostUidOrSelf(Ne(isolatedUid))).WillRepeatedly(ReturnArg<0>());
    mapAndMergeIsolatedUidsToHostUid(inputData, uidMap, uidAtomTagId, uidAdditiveFields);

    vector<vector<int>> actual;
    extractIntoVector(inputData, actual);
    vector<int> expectedV1 = {20, 22, 21};
    vector<int> expectedV2 = {20, 32, 52};
    EXPECT_EQ(2, (int)actual.size());
    EXPECT_THAT(actual, Contains(expectedV1));
    EXPECT_THAT(actual, Contains(expectedV2));
}

TEST(puller_util, NoMergeHostUidOnly) {
    vector<shared_ptr<LogEvent>> inputData;

    // 20->32->31
    inputData.push_back(
            makeUidLogEvent(timestamp, hostUid, isolatedNonAdditiveData, isolatedAdditiveData));

    // 20->22->21
    inputData.push_back(makeUidLogEvent(timestamp, hostUid, hostNonAdditiveData, hostAdditiveData));

    sp<MockUidMap> uidMap = new NaggyMock<MockUidMap>();
    EXPECT_CALL(*uidMap, getHostUidOrSelf(isolatedUid)).WillRepeatedly(Return(hostUid));
    EXPECT_CALL(*uidMap, getHostUidOrSelf(Ne(isolatedUid))).WillRepeatedly(ReturnArg<0>());
    mapAndMergeIsolatedUidsToHostUid(inputData, uidMap, uidAtomTagId, uidAdditiveFields);

    // 20->32->31
    // 20->22->21
    vector<vector<int>> actual;
    extractIntoVector(inputData, actual);
    vector<int> expectedV1 = {20, 32, 31};
    vector<int> expectedV2 = {20, 22, 21};
    EXPECT_EQ(2, (int)actual.size());
    EXPECT_THAT(actual, Contains(expectedV1));
    EXPECT_THAT(actual, Contains(expectedV2));
}

TEST(puller_util, IsolatedUidOnly) {
    vector<shared_ptr<LogEvent>> inputData;

    // 30->32->31
    inputData.push_back(
            makeUidLogEvent(timestamp, hostUid, isolatedNonAdditiveData, isolatedAdditiveData));

    // 30->22->21
    inputData.push_back(makeUidLogEvent(timestamp, hostUid, hostNonAdditiveData, hostAdditiveData));

    sp<MockUidMap> uidMap = new NaggyMock<MockUidMap>();
    EXPECT_CALL(*uidMap, getHostUidOrSelf(isolatedUid)).WillRepeatedly(Return(hostUid));
    EXPECT_CALL(*uidMap, getHostUidOrSelf(Ne(isolatedUid))).WillRepeatedly(ReturnArg<0>());
    mapAndMergeIsolatedUidsToHostUid(inputData, uidMap, uidAtomTagId, uidAdditiveFields);

    // 20->32->31
    // 20->22->21
    vector<vector<int>> actual;
    extractIntoVector(inputData, actual);
    vector<int> expectedV1 = {20, 32, 31};
    vector<int> expectedV2 = {20, 22, 21};
    EXPECT_EQ(2, (int)actual.size());
    EXPECT_THAT(actual, Contains(expectedV1));
    EXPECT_THAT(actual, Contains(expectedV2));
}

TEST(puller_util, MultipleIsolatedUidToOneHostUid) {
    vector<shared_ptr<LogEvent>> inputData;

    // 30->32->31
    inputData.push_back(
            makeUidLogEvent(timestamp, isolatedUid, isolatedNonAdditiveData, isolatedAdditiveData));

    // 31->32->21
    inputData.push_back(
            makeUidLogEvent(timestamp, isolatedUid + 1, isolatedNonAdditiveData, hostAdditiveData));

    // 20->32->21
    inputData.push_back(
            makeUidLogEvent(timestamp, hostUid, isolatedNonAdditiveData, hostAdditiveData));

    sp<MockUidMap> uidMap = new NaggyMock<MockUidMap>();
    EXPECT_CALL(*uidMap, getHostUidOrSelf(_)).WillRepeatedly(Return(hostUid));
    mapAndMergeIsolatedUidsToHostUid(inputData, uidMap, uidAtomTagId, uidAdditiveFields);

    vector<vector<int>> actual;
    extractIntoVector(inputData, actual);
    vector<int> expectedV1 = {20, 32, 73};
    EXPECT_EQ(1, (int)actual.size());
    EXPECT_THAT(actual, Contains(expectedV1));
}

TEST(puller_util, NoNeedToMerge) {
    vector<shared_ptr<LogEvent>> inputData;

    // 32
    inputData.push_back(makeNonUidAtomLogEvent(timestamp, isolatedNonAdditiveData));

    // 22
    inputData.push_back(makeNonUidAtomLogEvent(timestamp, hostNonAdditiveData));

    sp<MockUidMap> uidMap = new NaggyMock<MockUidMap>();
    mapAndMergeIsolatedUidsToHostUid(inputData, uidMap, nonUidAtomTagId, {} /*no additive fields*/);

    EXPECT_EQ(2, (int)inputData.size());
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
