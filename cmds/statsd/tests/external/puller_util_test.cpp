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
#include "FieldValue.h"
#include "annotations.h"
#include "stats_event.h"
#include "tests/statsd_test_util.h"

#ifdef __ANDROID__

namespace android {
namespace os {
namespace statsd {

using namespace testing;
using std::shared_ptr;
using std::vector;
/*
 * Test merge isolated and host uid
 */
namespace {
const int uidAtomTagId = 100;
const vector<int> additiveFields = {3};
const int nonUidAtomTagId = 200;
const int timestamp = 1234;
const int isolatedUid1 = 30;
const int isolatedUid2 = 40;
const int isolatedNonAdditiveData = 32;
const int isolatedAdditiveData = 31;
const int hostUid = 20;
const int hostNonAdditiveData = 22;
const int hostAdditiveData = 21;
const int attributionAtomTagId = 300;

sp<MockUidMap> makeMockUidMap() {
    return makeMockUidMapForOneHost(hostUid, {isolatedUid1, isolatedUid2});
}

}  // anonymous namespace

TEST(PullerUtilTest, MergeNoDimension) {
    vector<shared_ptr<LogEvent>> data = {
            // 30->22->31
            makeUidLogEvent(uidAtomTagId, timestamp, isolatedUid1, hostNonAdditiveData,
                            isolatedAdditiveData),

            // 20->22->21
            makeUidLogEvent(uidAtomTagId, timestamp, hostUid, hostNonAdditiveData,
                            hostAdditiveData),
    };

    sp<MockUidMap> uidMap = makeMockUidMap();
    mapAndMergeIsolatedUidsToHostUid(data, uidMap, uidAtomTagId, additiveFields);

    ASSERT_EQ(1, (int)data.size());
    const vector<FieldValue>* actualFieldValues = &data[0]->getValues();
    ASSERT_EQ(3, actualFieldValues->size());
    EXPECT_EQ(hostUid, actualFieldValues->at(0).mValue.int_value);
    EXPECT_EQ(hostNonAdditiveData, actualFieldValues->at(1).mValue.int_value);
    EXPECT_EQ(isolatedAdditiveData + hostAdditiveData, actualFieldValues->at(2).mValue.int_value);
}

TEST(PullerUtilTest, MergeWithDimension) {
    vector<shared_ptr<LogEvent>> data = {
            // 30->32->31
            makeUidLogEvent(uidAtomTagId, timestamp, isolatedUid1, isolatedNonAdditiveData,
                            isolatedAdditiveData),

            // 20->32->21
            makeUidLogEvent(uidAtomTagId, timestamp, hostUid, isolatedNonAdditiveData,
                            hostAdditiveData),

            // 20->22->21
            makeUidLogEvent(uidAtomTagId, timestamp, hostUid, hostNonAdditiveData,
                            hostAdditiveData),
    };

    sp<MockUidMap> uidMap = makeMockUidMap();
    mapAndMergeIsolatedUidsToHostUid(data, uidMap, uidAtomTagId, additiveFields);

    ASSERT_EQ(2, (int)data.size());

    const vector<FieldValue>* actualFieldValues = &data[0]->getValues();
    ASSERT_EQ(3, actualFieldValues->size());
    EXPECT_EQ(hostUid, actualFieldValues->at(0).mValue.int_value);
    EXPECT_EQ(hostNonAdditiveData, actualFieldValues->at(1).mValue.int_value);
    EXPECT_EQ(hostAdditiveData, actualFieldValues->at(2).mValue.int_value);

    actualFieldValues = &data[1]->getValues();
    ASSERT_EQ(3, actualFieldValues->size());
    EXPECT_EQ(hostUid, actualFieldValues->at(0).mValue.int_value);
    EXPECT_EQ(isolatedNonAdditiveData, actualFieldValues->at(1).mValue.int_value);
    EXPECT_EQ(hostAdditiveData + isolatedAdditiveData, actualFieldValues->at(2).mValue.int_value);
}

TEST(PullerUtilTest, NoMergeHostUidOnly) {
    vector<shared_ptr<LogEvent>> data = {
            // 20->32->31
            makeUidLogEvent(uidAtomTagId, timestamp, hostUid, isolatedNonAdditiveData,
                            isolatedAdditiveData),

            // 20->22->21
            makeUidLogEvent(uidAtomTagId, timestamp, hostUid, hostNonAdditiveData,
                            hostAdditiveData),
    };

    sp<MockUidMap> uidMap = makeMockUidMap();
    mapAndMergeIsolatedUidsToHostUid(data, uidMap, uidAtomTagId, additiveFields);

    ASSERT_EQ(2, (int)data.size());

    const vector<FieldValue>* actualFieldValues = &data[0]->getValues();
    ASSERT_EQ(3, actualFieldValues->size());
    EXPECT_EQ(hostUid, actualFieldValues->at(0).mValue.int_value);
    EXPECT_EQ(hostNonAdditiveData, actualFieldValues->at(1).mValue.int_value);
    EXPECT_EQ(hostAdditiveData, actualFieldValues->at(2).mValue.int_value);

    actualFieldValues = &data[1]->getValues();
    ASSERT_EQ(3, actualFieldValues->size());
    EXPECT_EQ(hostUid, actualFieldValues->at(0).mValue.int_value);
    EXPECT_EQ(isolatedNonAdditiveData, actualFieldValues->at(1).mValue.int_value);
    EXPECT_EQ(isolatedAdditiveData, actualFieldValues->at(2).mValue.int_value);
}

TEST(PullerUtilTest, IsolatedUidOnly) {
    vector<shared_ptr<LogEvent>> data = {
            // 30->32->31
            makeUidLogEvent(uidAtomTagId, timestamp, isolatedUid1, isolatedNonAdditiveData,
                            isolatedAdditiveData),

            // 30->22->21
            makeUidLogEvent(uidAtomTagId, timestamp, isolatedUid1, hostNonAdditiveData,
                            hostAdditiveData),
    };

    sp<MockUidMap> uidMap = makeMockUidMap();
    mapAndMergeIsolatedUidsToHostUid(data, uidMap, uidAtomTagId, additiveFields);

    ASSERT_EQ(2, (int)data.size());

    // 20->32->31
    const vector<FieldValue>* actualFieldValues = &data[0]->getValues();
    ASSERT_EQ(3, actualFieldValues->size());
    EXPECT_EQ(hostUid, actualFieldValues->at(0).mValue.int_value);
    EXPECT_EQ(hostNonAdditiveData, actualFieldValues->at(1).mValue.int_value);
    EXPECT_EQ(hostAdditiveData, actualFieldValues->at(2).mValue.int_value);

    // 20->22->21
    actualFieldValues = &data[1]->getValues();
    ASSERT_EQ(3, actualFieldValues->size());
    EXPECT_EQ(hostUid, actualFieldValues->at(0).mValue.int_value);
    EXPECT_EQ(isolatedNonAdditiveData, actualFieldValues->at(1).mValue.int_value);
    EXPECT_EQ(isolatedAdditiveData, actualFieldValues->at(2).mValue.int_value);
}

TEST(PullerUtilTest, MultipleIsolatedUidToOneHostUid) {
    vector<shared_ptr<LogEvent>> data = {
            // 30->32->31
            makeUidLogEvent(uidAtomTagId, timestamp, isolatedUid1, isolatedNonAdditiveData,
                            isolatedAdditiveData),

            // 31->32->21
            makeUidLogEvent(uidAtomTagId, timestamp, isolatedUid2, isolatedNonAdditiveData,
                            hostAdditiveData),

            // 20->32->21
            makeUidLogEvent(uidAtomTagId, timestamp, hostUid, isolatedNonAdditiveData,
                            hostAdditiveData),
    };

    sp<MockUidMap> uidMap = makeMockUidMap();
    mapAndMergeIsolatedUidsToHostUid(data, uidMap, uidAtomTagId, additiveFields);

    ASSERT_EQ(1, (int)data.size());

    const vector<FieldValue>* actualFieldValues = &data[0]->getValues();
    ASSERT_EQ(3, actualFieldValues->size());
    EXPECT_EQ(hostUid, actualFieldValues->at(0).mValue.int_value);
    EXPECT_EQ(isolatedNonAdditiveData, actualFieldValues->at(1).mValue.int_value);
    EXPECT_EQ(isolatedAdditiveData + hostAdditiveData + hostAdditiveData,
              actualFieldValues->at(2).mValue.int_value);
}

TEST(PullerUtilTest, NoNeedToMerge) {
    vector<shared_ptr<LogEvent>> data = {
            // 32->31
            CreateTwoValueLogEvent(nonUidAtomTagId, timestamp, isolatedNonAdditiveData,
                                   isolatedAdditiveData),

            // 22->21
            CreateTwoValueLogEvent(nonUidAtomTagId, timestamp, hostNonAdditiveData,
                                   hostAdditiveData),

    };

    sp<MockUidMap> uidMap = makeMockUidMap();
    mapAndMergeIsolatedUidsToHostUid(data, uidMap, nonUidAtomTagId, {} /*no additive fields*/);

    ASSERT_EQ(2, (int)data.size());

    const vector<FieldValue>* actualFieldValues = &data[0]->getValues();
    ASSERT_EQ(2, actualFieldValues->size());
    EXPECT_EQ(isolatedNonAdditiveData, actualFieldValues->at(0).mValue.int_value);
    EXPECT_EQ(isolatedAdditiveData, actualFieldValues->at(1).mValue.int_value);

    actualFieldValues = &data[1]->getValues();
    ASSERT_EQ(2, actualFieldValues->size());
    EXPECT_EQ(hostNonAdditiveData, actualFieldValues->at(0).mValue.int_value);
    EXPECT_EQ(hostAdditiveData, actualFieldValues->at(1).mValue.int_value);
}

TEST(PullerUtilTest, MergeNoDimensionAttributionChain) {
    vector<shared_ptr<LogEvent>> data = {
            // 30->tag1->400->tag2->22->31
            makeAttributionLogEvent(attributionAtomTagId, timestamp, {isolatedUid1, 400},
                                    {"tag1", "tag2"}, hostNonAdditiveData, isolatedAdditiveData),

            // 20->tag1->400->tag2->22->21
            makeAttributionLogEvent(attributionAtomTagId, timestamp, {hostUid, 400},
                                    {"tag1", "tag2"}, hostNonAdditiveData, hostAdditiveData),
    };

    sp<MockUidMap> uidMap = makeMockUidMap();
    mapAndMergeIsolatedUidsToHostUid(data, uidMap, attributionAtomTagId, additiveFields);

    ASSERT_EQ(1, (int)data.size());
    const vector<FieldValue>* actualFieldValues = &data[0]->getValues();
    ASSERT_EQ(6, actualFieldValues->size());
    EXPECT_EQ(hostUid, actualFieldValues->at(0).mValue.int_value);
    EXPECT_EQ("tag1", actualFieldValues->at(1).mValue.str_value);
    EXPECT_EQ(400, actualFieldValues->at(2).mValue.int_value);
    EXPECT_EQ("tag2", actualFieldValues->at(3).mValue.str_value);
    EXPECT_EQ(hostNonAdditiveData, actualFieldValues->at(4).mValue.int_value);
    EXPECT_EQ(isolatedAdditiveData + hostAdditiveData, actualFieldValues->at(5).mValue.int_value);
}

TEST(PullerUtilTest, MergeWithDimensionAttributionChain) {
    vector<shared_ptr<LogEvent>> data = {
            // 200->tag1->30->tag2->32->31
            makeAttributionLogEvent(attributionAtomTagId, timestamp, {200, isolatedUid1},
                                    {"tag1", "tag2"}, isolatedNonAdditiveData,
                                    isolatedAdditiveData),

            // 200->tag1->20->tag2->32->21
            makeAttributionLogEvent(attributionAtomTagId, timestamp, {200, hostUid},
                                    {"tag1", "tag2"}, isolatedNonAdditiveData, hostAdditiveData),

            // 200->tag1->20->tag2->22->21
            makeAttributionLogEvent(attributionAtomTagId, timestamp, {200, hostUid},
                                    {"tag1", "tag2"}, hostNonAdditiveData, hostAdditiveData),
    };

    sp<MockUidMap> uidMap = makeMockUidMap();
    mapAndMergeIsolatedUidsToHostUid(data, uidMap, attributionAtomTagId, additiveFields);

    ASSERT_EQ(2, (int)data.size());

    const vector<FieldValue>* actualFieldValues = &data[0]->getValues();
    ASSERT_EQ(6, actualFieldValues->size());
    EXPECT_EQ(200, actualFieldValues->at(0).mValue.int_value);
    EXPECT_EQ("tag1", actualFieldValues->at(1).mValue.str_value);
    EXPECT_EQ(hostUid, actualFieldValues->at(2).mValue.int_value);
    EXPECT_EQ("tag2", actualFieldValues->at(3).mValue.str_value);
    EXPECT_EQ(hostNonAdditiveData, actualFieldValues->at(4).mValue.int_value);
    EXPECT_EQ(hostAdditiveData, actualFieldValues->at(5).mValue.int_value);

    actualFieldValues = &data[1]->getValues();
    ASSERT_EQ(6, actualFieldValues->size());
    EXPECT_EQ(200, actualFieldValues->at(0).mValue.int_value);
    EXPECT_EQ("tag1", actualFieldValues->at(1).mValue.str_value);
    EXPECT_EQ(hostUid, actualFieldValues->at(2).mValue.int_value);
    EXPECT_EQ("tag2", actualFieldValues->at(3).mValue.str_value);
    EXPECT_EQ(isolatedNonAdditiveData, actualFieldValues->at(4).mValue.int_value);
    EXPECT_EQ(hostAdditiveData + isolatedAdditiveData, actualFieldValues->at(5).mValue.int_value);
}

TEST(PullerUtilTest, NoMergeHostUidOnlyAttributionChain) {
    vector<shared_ptr<LogEvent>> data = {
            // 20->tag1->400->tag2->32->31
            makeAttributionLogEvent(attributionAtomTagId, timestamp, {hostUid, 400},
                                    {"tag1", "tag2"}, isolatedNonAdditiveData,
                                    isolatedAdditiveData),

            // 20->tag1->400->tag2->22->21
            makeAttributionLogEvent(attributionAtomTagId, timestamp, {hostUid, 400},
                                    {"tag1", "tag2"}, hostNonAdditiveData, hostAdditiveData),
    };

    sp<MockUidMap> uidMap = makeMockUidMap();
    mapAndMergeIsolatedUidsToHostUid(data, uidMap, attributionAtomTagId, additiveFields);

    ASSERT_EQ(2, (int)data.size());

    const vector<FieldValue>* actualFieldValues = &data[0]->getValues();
    ASSERT_EQ(6, actualFieldValues->size());
    EXPECT_EQ(hostUid, actualFieldValues->at(0).mValue.int_value);
    EXPECT_EQ("tag1", actualFieldValues->at(1).mValue.str_value);
    EXPECT_EQ(400, actualFieldValues->at(2).mValue.int_value);
    EXPECT_EQ("tag2", actualFieldValues->at(3).mValue.str_value);
    EXPECT_EQ(hostNonAdditiveData, actualFieldValues->at(4).mValue.int_value);
    EXPECT_EQ(hostAdditiveData, actualFieldValues->at(5).mValue.int_value);

    actualFieldValues = &data[1]->getValues();
    ASSERT_EQ(6, actualFieldValues->size());
    EXPECT_EQ(hostUid, actualFieldValues->at(0).mValue.int_value);
    EXPECT_EQ("tag1", actualFieldValues->at(1).mValue.str_value);
    EXPECT_EQ(400, actualFieldValues->at(2).mValue.int_value);
    EXPECT_EQ("tag2", actualFieldValues->at(3).mValue.str_value);
    EXPECT_EQ(isolatedNonAdditiveData, actualFieldValues->at(4).mValue.int_value);
    EXPECT_EQ(isolatedAdditiveData, actualFieldValues->at(5).mValue.int_value);
}

TEST(PullerUtilTest, IsolatedUidOnlyAttributionChain) {
    vector<shared_ptr<LogEvent>> data = {
            // 30->tag1->400->tag2->32->31
            makeAttributionLogEvent(attributionAtomTagId, timestamp, {isolatedUid1, 400},
                                    {"tag1", "tag2"}, isolatedNonAdditiveData,
                                    isolatedAdditiveData),

            // 30->tag1->400->tag2->22->21
            makeAttributionLogEvent(attributionAtomTagId, timestamp, {isolatedUid1, 400},
                                    {"tag1", "tag2"}, hostNonAdditiveData, hostAdditiveData),
    };

    sp<MockUidMap> uidMap = makeMockUidMap();
    mapAndMergeIsolatedUidsToHostUid(data, uidMap, attributionAtomTagId, additiveFields);

    ASSERT_EQ(2, (int)data.size());

    // 20->tag1->400->tag2->32->31
    const vector<FieldValue>* actualFieldValues = &data[0]->getValues();
    ASSERT_EQ(6, actualFieldValues->size());
    EXPECT_EQ(hostUid, actualFieldValues->at(0).mValue.int_value);
    EXPECT_EQ("tag1", actualFieldValues->at(1).mValue.str_value);
    EXPECT_EQ(400, actualFieldValues->at(2).mValue.int_value);
    EXPECT_EQ("tag2", actualFieldValues->at(3).mValue.str_value);
    EXPECT_EQ(hostNonAdditiveData, actualFieldValues->at(4).mValue.int_value);
    EXPECT_EQ(hostAdditiveData, actualFieldValues->at(5).mValue.int_value);

    // 20->tag1->400->tag2->22->21
    actualFieldValues = &data[1]->getValues();
    ASSERT_EQ(6, actualFieldValues->size());
    EXPECT_EQ(hostUid, actualFieldValues->at(0).mValue.int_value);
    EXPECT_EQ("tag1", actualFieldValues->at(1).mValue.str_value);
    EXPECT_EQ(400, actualFieldValues->at(2).mValue.int_value);
    EXPECT_EQ("tag2", actualFieldValues->at(3).mValue.str_value);
    EXPECT_EQ(isolatedNonAdditiveData, actualFieldValues->at(4).mValue.int_value);
    EXPECT_EQ(isolatedAdditiveData, actualFieldValues->at(5).mValue.int_value);
}

TEST(PullerUtilTest, MultipleIsolatedUidToOneHostUidAttributionChain) {
    vector<shared_ptr<LogEvent>> data = {
            // 30->tag1->400->tag2->32->31
            makeAttributionLogEvent(attributionAtomTagId, timestamp, {isolatedUid1, 400},
                                    {"tag1", "tag2"}, isolatedNonAdditiveData,
                                    isolatedAdditiveData),

            // 31->tag1->400->tag2->32->21
            makeAttributionLogEvent(attributionAtomTagId, timestamp, {isolatedUid2, 400},
                                    {"tag1", "tag2"}, isolatedNonAdditiveData, hostAdditiveData),

            // 20->tag1->400->tag2->32->21
            makeAttributionLogEvent(attributionAtomTagId, timestamp, {hostUid, 400},
                                    {"tag1", "tag2"}, isolatedNonAdditiveData, hostAdditiveData),
    };

    sp<MockUidMap> uidMap = makeMockUidMap();
    mapAndMergeIsolatedUidsToHostUid(data, uidMap, attributionAtomTagId, additiveFields);

    ASSERT_EQ(1, (int)data.size());

    const vector<FieldValue>* actualFieldValues = &data[0]->getValues();
    ASSERT_EQ(6, actualFieldValues->size());
    EXPECT_EQ(hostUid, actualFieldValues->at(0).mValue.int_value);
    EXPECT_EQ("tag1", actualFieldValues->at(1).mValue.str_value);
    EXPECT_EQ(400, actualFieldValues->at(2).mValue.int_value);
    EXPECT_EQ("tag2", actualFieldValues->at(3).mValue.str_value);
    EXPECT_EQ(isolatedNonAdditiveData, actualFieldValues->at(4).mValue.int_value);
    EXPECT_EQ(isolatedAdditiveData + hostAdditiveData + hostAdditiveData,
              actualFieldValues->at(5).mValue.int_value);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
