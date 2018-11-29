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

int uidAtomTagId = android::util::CPU_CLUSTER_TIME;
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

TEST(puller_util, MergeNoDimension) {
  vector<shared_ptr<LogEvent>> inputData;
  shared_ptr<LogEvent> event = make_shared<LogEvent>(uidAtomTagId, timestamp);
  // 30->22->31
  event->write(isolatedUid);
  event->write(hostNonAdditiveData);
  event->write(isolatedAdditiveData);
  event->init();
  inputData.push_back(event);

  // 20->22->21
  event = make_shared<LogEvent>(uidAtomTagId, timestamp);
  event->write(hostUid);
  event->write(hostNonAdditiveData);
  event->write(hostAdditiveData);
  event->init();
  inputData.push_back(event);

  sp<MockUidMap> uidMap = new NaggyMock<MockUidMap>();
  EXPECT_CALL(*uidMap, getHostUidOrSelf(isolatedUid))
      .WillRepeatedly(Return(hostUid));
  EXPECT_CALL(*uidMap, getHostUidOrSelf(Ne(isolatedUid)))
      .WillRepeatedly(ReturnArg<0>());
  mapAndMergeIsolatedUidsToHostUid(inputData, uidMap, uidAtomTagId);

  vector<vector<int>> actual;
  extractIntoVector(inputData, actual);
  vector<int> expectedV1 = {20, 22, 52};
  EXPECT_EQ(1, (int)actual.size());
  EXPECT_THAT(actual, Contains(expectedV1));
}

TEST(puller_util, MergeWithDimension) {
  vector<shared_ptr<LogEvent>> inputData;
  shared_ptr<LogEvent> event = make_shared<LogEvent>(uidAtomTagId, timestamp);
  // 30->32->31
  event->write(isolatedUid);
  event->write(isolatedNonAdditiveData);
  event->write(isolatedAdditiveData);
  event->init();
  inputData.push_back(event);

  // 20->32->21
  event = make_shared<LogEvent>(uidAtomTagId, timestamp);
  event->write(hostUid);
  event->write(isolatedNonAdditiveData);
  event->write(hostAdditiveData);
  event->init();
  inputData.push_back(event);

  // 20->22->21
  event = make_shared<LogEvent>(uidAtomTagId, timestamp);
  event->write(hostUid);
  event->write(hostNonAdditiveData);
  event->write(hostAdditiveData);
  event->init();
  inputData.push_back(event);

  sp<MockUidMap> uidMap = new NaggyMock<MockUidMap>();
  EXPECT_CALL(*uidMap, getHostUidOrSelf(isolatedUid))
      .WillRepeatedly(Return(hostUid));
  EXPECT_CALL(*uidMap, getHostUidOrSelf(Ne(isolatedUid)))
      .WillRepeatedly(ReturnArg<0>());
  mapAndMergeIsolatedUidsToHostUid(inputData, uidMap, uidAtomTagId);

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
  shared_ptr<LogEvent> event = make_shared<LogEvent>(uidAtomTagId, timestamp);
  // 20->32->31
  event->write(hostUid);
  event->write(isolatedNonAdditiveData);
  event->write(isolatedAdditiveData);
  event->init();
  inputData.push_back(event);

  // 20->22->21
  event = make_shared<LogEvent>(uidAtomTagId, timestamp);
  event->write(hostUid);
  event->write(hostNonAdditiveData);
  event->write(hostAdditiveData);
  event->init();
  inputData.push_back(event);

  sp<MockUidMap> uidMap = new NaggyMock<MockUidMap>();
  EXPECT_CALL(*uidMap, getHostUidOrSelf(isolatedUid))
      .WillRepeatedly(Return(hostUid));
  EXPECT_CALL(*uidMap, getHostUidOrSelf(Ne(isolatedUid)))
      .WillRepeatedly(ReturnArg<0>());
  mapAndMergeIsolatedUidsToHostUid(inputData, uidMap, uidAtomTagId);

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
  shared_ptr<LogEvent> event = make_shared<LogEvent>(uidAtomTagId, timestamp);
  // 30->32->31
  event->write(hostUid);
  event->write(isolatedNonAdditiveData);
  event->write(isolatedAdditiveData);
  event->init();
  inputData.push_back(event);

  // 30->22->21
  event = make_shared<LogEvent>(uidAtomTagId, timestamp);
  event->write(hostUid);
  event->write(hostNonAdditiveData);
  event->write(hostAdditiveData);
  event->init();
  inputData.push_back(event);

  sp<MockUidMap> uidMap = new NaggyMock<MockUidMap>();
  EXPECT_CALL(*uidMap, getHostUidOrSelf(isolatedUid))
      .WillRepeatedly(Return(hostUid));
  EXPECT_CALL(*uidMap, getHostUidOrSelf(Ne(isolatedUid)))
      .WillRepeatedly(ReturnArg<0>());
  mapAndMergeIsolatedUidsToHostUid(inputData, uidMap, uidAtomTagId);

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
  shared_ptr<LogEvent> event = make_shared<LogEvent>(uidAtomTagId, timestamp);
  // 30->32->31
  event->write(isolatedUid);
  event->write(isolatedNonAdditiveData);
  event->write(isolatedAdditiveData);
  event->init();
  inputData.push_back(event);

  // 31->32->21
  event = make_shared<LogEvent>(uidAtomTagId, timestamp);
  event->write(isolatedUid + 1);
  event->write(isolatedNonAdditiveData);
  event->write(hostAdditiveData);
  event->init();
  inputData.push_back(event);

  // 20->32->21
  event = make_shared<LogEvent>(uidAtomTagId, timestamp);
  event->write(hostUid);
  event->write(isolatedNonAdditiveData);
  event->write(hostAdditiveData);
  event->init();
  inputData.push_back(event);

  sp<MockUidMap> uidMap = new NaggyMock<MockUidMap>();
  EXPECT_CALL(*uidMap, getHostUidOrSelf(_)).WillRepeatedly(Return(hostUid));
  mapAndMergeIsolatedUidsToHostUid(inputData, uidMap, uidAtomTagId);

  vector<vector<int>> actual;
  extractIntoVector(inputData, actual);
  vector<int> expectedV1 = {20, 32, 73};
  EXPECT_EQ(1, (int)actual.size());
  EXPECT_THAT(actual, Contains(expectedV1));
}

TEST(puller_util, NoNeedToMerge) {
  vector<shared_ptr<LogEvent>> inputData;
  shared_ptr<LogEvent> event =
      make_shared<LogEvent>(nonUidAtomTagId, timestamp);
  // 32
  event->write(isolatedNonAdditiveData);
  event->init();
  inputData.push_back(event);

  event = make_shared<LogEvent>(nonUidAtomTagId, timestamp);
  // 22
  event->write(hostNonAdditiveData);
  event->init();
  inputData.push_back(event);

  sp<MockUidMap> uidMap = new NaggyMock<MockUidMap>();
  mapAndMergeIsolatedUidsToHostUid(inputData, uidMap, nonUidAtomTagId);

  EXPECT_EQ(2, (int)inputData.size());
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
