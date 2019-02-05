// Copyright (C) 2017 The Android Open Source Project
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

#include "src/logd/LogEvent.h"
#include <gtest/gtest.h>
#include <log/log_event_list.h>
#include "frameworks/base/cmds/statsd/src/atoms.pb.h"
#include "frameworks/base/core/proto/android/stats/launcher/launcher.pb.h"

#ifdef __ANDROID__

namespace android {
namespace os {
namespace statsd {

using std::string;
using util::ProtoOutputStream;

TEST(LogEventTest, TestLogParsing) {
    LogEvent event1(1, 2000);

    std::vector<AttributionNodeInternal> nodes;

    AttributionNodeInternal node1;
    node1.set_uid(1000);
    node1.set_tag("tag1");
    nodes.push_back(node1);

    AttributionNodeInternal node2;
    node2.set_uid(2000);
    node2.set_tag("tag2");
    nodes.push_back(node2);

    event1.write(nodes);
    event1.write("hello");
    event1.write((int32_t)10);
    event1.write((int64_t)20);
    event1.write((float)1.1);
    event1.init();

    const auto& items = event1.getValues();
    EXPECT_EQ((size_t)8, items.size());
    EXPECT_EQ(1, event1.GetTagId());

    const FieldValue& item0 = event1.getValues()[0];
    EXPECT_EQ(0x2010101, item0.mField.getField());
    EXPECT_EQ(Type::INT, item0.mValue.getType());
    EXPECT_EQ(1000, item0.mValue.int_value);

    const FieldValue& item1 = event1.getValues()[1];
    EXPECT_EQ(0x2010182, item1.mField.getField());
    EXPECT_EQ(Type::STRING, item1.mValue.getType());
    EXPECT_EQ("tag1", item1.mValue.str_value);

    const FieldValue& item2 = event1.getValues()[2];
    EXPECT_EQ(0x2018201, item2.mField.getField());
    EXPECT_EQ(Type::INT, item2.mValue.getType());
    EXPECT_EQ(2000, item2.mValue.int_value);

    const FieldValue& item3 = event1.getValues()[3];
    EXPECT_EQ(0x2018282, item3.mField.getField());
    EXPECT_EQ(Type::STRING, item3.mValue.getType());
    EXPECT_EQ("tag2", item3.mValue.str_value);

    const FieldValue& item4 = event1.getValues()[4];
    EXPECT_EQ(0x20000, item4.mField.getField());
    EXPECT_EQ(Type::STRING, item4.mValue.getType());
    EXPECT_EQ("hello", item4.mValue.str_value);

    const FieldValue& item5 = event1.getValues()[5];
    EXPECT_EQ(0x30000, item5.mField.getField());
    EXPECT_EQ(Type::INT, item5.mValue.getType());
    EXPECT_EQ(10, item5.mValue.int_value);

    const FieldValue& item6 = event1.getValues()[6];
    EXPECT_EQ(0x40000, item6.mField.getField());
    EXPECT_EQ(Type::LONG, item6.mValue.getType());
    EXPECT_EQ((int64_t)20, item6.mValue.long_value);

    const FieldValue& item7 = event1.getValues()[7];
    EXPECT_EQ(0x50000, item7.mField.getField());
    EXPECT_EQ(Type::FLOAT, item7.mValue.getType());
    EXPECT_EQ((float)1.1, item7.mValue.float_value);
}

TEST(LogEventTest, TestKeyValuePairsAtomParsing) {
    LogEvent event1(83, 2000, 1000);
    std::map<int32_t, int32_t> int_map;
    std::map<int32_t, int64_t> long_map;
    std::map<int32_t, std::string> string_map;
    std::map<int32_t, float> float_map;

    int_map[11] = 123;
    int_map[22] = 345;

    long_map[33] = 678L;
    long_map[44] = 890L;

    string_map[1] = "test2";
    string_map[2] = "test1";

    float_map[111] = 2.2f;
    float_map[222] = 1.1f;

    EXPECT_TRUE(event1.writeKeyValuePairs(0, // Logging side logs 0 uid.
                                          int_map,
                                          long_map,
                                          string_map,
                                          float_map));
    event1.init();

    EXPECT_EQ(83, event1.GetTagId());
    const auto& items = event1.getValues();
    EXPECT_EQ((size_t)17, items.size());

    const FieldValue& item0 = event1.getValues()[0];
    EXPECT_EQ(0x10000, item0.mField.getField());
    EXPECT_EQ(Type::INT, item0.mValue.getType());
    EXPECT_EQ(1000, item0.mValue.int_value);

    const FieldValue& item1 = event1.getValues()[1];
    EXPECT_EQ(0x2010201, item1.mField.getField());
    EXPECT_EQ(Type::INT, item1.mValue.getType());
    EXPECT_EQ(11, item1.mValue.int_value);

    const FieldValue& item2 = event1.getValues()[2];
    EXPECT_EQ(0x2010282, item2.mField.getField());
    EXPECT_EQ(Type::INT, item2.mValue.getType());
    EXPECT_EQ(123, item2.mValue.int_value);

    const FieldValue& item3 = event1.getValues()[3];
    EXPECT_EQ(0x2010301, item3.mField.getField());
    EXPECT_EQ(Type::INT, item3.mValue.getType());
    EXPECT_EQ(22, item3.mValue.int_value);

    const FieldValue& item4 = event1.getValues()[4];
    EXPECT_EQ(0x2010382, item4.mField.getField());
    EXPECT_EQ(Type::INT, item4.mValue.getType());
    EXPECT_EQ(345, item4.mValue.int_value);

    const FieldValue& item5 = event1.getValues()[5];
    EXPECT_EQ(0x2010401, item5.mField.getField());
    EXPECT_EQ(Type::INT, item5.mValue.getType());
    EXPECT_EQ(33, item5.mValue.int_value);

    const FieldValue& item6 = event1.getValues()[6];
    EXPECT_EQ(0x2010483, item6.mField.getField());
    EXPECT_EQ(Type::LONG, item6.mValue.getType());
    EXPECT_EQ(678L, item6.mValue.int_value);

    const FieldValue& item7 = event1.getValues()[7];
    EXPECT_EQ(0x2010501, item7.mField.getField());
    EXPECT_EQ(Type::INT, item7.mValue.getType());
    EXPECT_EQ(44, item7.mValue.int_value);

    const FieldValue& item8 = event1.getValues()[8];
    EXPECT_EQ(0x2010583, item8.mField.getField());
    EXPECT_EQ(Type::LONG, item8.mValue.getType());
    EXPECT_EQ(890L, item8.mValue.int_value);

    const FieldValue& item9 = event1.getValues()[9];
    EXPECT_EQ(0x2010601, item9.mField.getField());
    EXPECT_EQ(Type::INT, item9.mValue.getType());
    EXPECT_EQ(1, item9.mValue.int_value);

    const FieldValue& item10 = event1.getValues()[10];
    EXPECT_EQ(0x2010684, item10.mField.getField());
    EXPECT_EQ(Type::STRING, item10.mValue.getType());
    EXPECT_EQ("test2", item10.mValue.str_value);

    const FieldValue& item11 = event1.getValues()[11];
    EXPECT_EQ(0x2010701, item11.mField.getField());
    EXPECT_EQ(Type::INT, item11.mValue.getType());
    EXPECT_EQ(2, item11.mValue.int_value);

    const FieldValue& item12 = event1.getValues()[12];
    EXPECT_EQ(0x2010784, item12.mField.getField());
    EXPECT_EQ(Type::STRING, item12.mValue.getType());
    EXPECT_EQ("test1", item12.mValue.str_value);

    const FieldValue& item13 = event1.getValues()[13];
    EXPECT_EQ(0x2010801, item13.mField.getField());
    EXPECT_EQ(Type::INT, item13.mValue.getType());
    EXPECT_EQ(111, item13.mValue.int_value);

    const FieldValue& item14 = event1.getValues()[14];
    EXPECT_EQ(0x2010885, item14.mField.getField());
    EXPECT_EQ(Type::FLOAT, item14.mValue.getType());
    EXPECT_EQ(2.2f, item14.mValue.float_value);

    const FieldValue& item15 = event1.getValues()[15];
    EXPECT_EQ(0x2018901, item15.mField.getField());
    EXPECT_EQ(Type::INT, item15.mValue.getType());
    EXPECT_EQ(222, item15.mValue.int_value);

    const FieldValue& item16 = event1.getValues()[16];
    EXPECT_EQ(0x2018985, item16.mField.getField());
    EXPECT_EQ(Type::FLOAT, item16.mValue.getType());
    EXPECT_EQ(1.1f, item16.mValue.float_value);
}

TEST(LogEventTest, TestLogParsing2) {
    LogEvent event1(1, 2000);

    std::vector<AttributionNodeInternal> nodes;

    event1.write("hello");

    // repeated msg can be in the middle
    AttributionNodeInternal node1;
    node1.set_uid(1000);
    node1.set_tag("tag1");
    nodes.push_back(node1);

    AttributionNodeInternal node2;
    node2.set_uid(2000);
    node2.set_tag("tag2");
    nodes.push_back(node2);
    event1.write(nodes);

    event1.write((int32_t)10);
    event1.write((int64_t)20);
    event1.write((float)1.1);
    event1.init();

    const auto& items = event1.getValues();
    EXPECT_EQ((size_t)8, items.size());
    EXPECT_EQ(1, event1.GetTagId());

    const FieldValue& item = event1.getValues()[0];
    EXPECT_EQ(0x00010000, item.mField.getField());
    EXPECT_EQ(Type::STRING, item.mValue.getType());
    EXPECT_EQ("hello", item.mValue.str_value);

    const FieldValue& item0 = event1.getValues()[1];
    EXPECT_EQ(0x2020101, item0.mField.getField());
    EXPECT_EQ(Type::INT, item0.mValue.getType());
    EXPECT_EQ(1000, item0.mValue.int_value);

    const FieldValue& item1 = event1.getValues()[2];
    EXPECT_EQ(0x2020182, item1.mField.getField());
    EXPECT_EQ(Type::STRING, item1.mValue.getType());
    EXPECT_EQ("tag1", item1.mValue.str_value);

    const FieldValue& item2 = event1.getValues()[3];
    EXPECT_EQ(0x2028201, item2.mField.getField());
    EXPECT_EQ(Type::INT, item2.mValue.getType());
    EXPECT_EQ(2000, item2.mValue.int_value);

    const FieldValue& item3 = event1.getValues()[4];
    EXPECT_EQ(0x2028282, item3.mField.getField());
    EXPECT_EQ(Type::STRING, item3.mValue.getType());
    EXPECT_EQ("tag2", item3.mValue.str_value);

    const FieldValue& item5 = event1.getValues()[5];
    EXPECT_EQ(0x30000, item5.mField.getField());
    EXPECT_EQ(Type::INT, item5.mValue.getType());
    EXPECT_EQ(10, item5.mValue.int_value);

    const FieldValue& item6 = event1.getValues()[6];
    EXPECT_EQ(0x40000, item6.mField.getField());
    EXPECT_EQ(Type::LONG, item6.mValue.getType());
    EXPECT_EQ((int64_t)20, item6.mValue.long_value);

    const FieldValue& item7 = event1.getValues()[7];
    EXPECT_EQ(0x50000, item7.mField.getField());
    EXPECT_EQ(Type::FLOAT, item7.mValue.getType());
    EXPECT_EQ((float)1.1, item7.mValue.float_value);
}

TEST(LogEventTest, TestKeyValuePairsEvent) {
    std::map<int32_t, int32_t> int_map;
    std::map<int32_t, int64_t> long_map;
    std::map<int32_t, std::string> string_map;
    std::map<int32_t, float> float_map;

    int_map[11] = 123;
    int_map[22] = 345;

    long_map[33] = 678L;
    long_map[44] = 890L;

    string_map[1] = "test2";
    string_map[2] = "test1";

    float_map[111] = 2.2f;
    float_map[222] = 1.1f;

    LogEvent event1(83, 2000, 2001, 10001, int_map, long_map, string_map, float_map);
    event1.init();

    EXPECT_EQ(83, event1.GetTagId());
    EXPECT_EQ((int64_t)2000, event1.GetLogdTimestampNs());
    EXPECT_EQ((int64_t)2001, event1.GetElapsedTimestampNs());
    EXPECT_EQ((int64_t)10001, event1.GetUid());

    const auto& items = event1.getValues();
    EXPECT_EQ((size_t)17, items.size());

    const FieldValue& item0 = event1.getValues()[0];
    EXPECT_EQ(0x00010000, item0.mField.getField());
    EXPECT_EQ(Type::INT, item0.mValue.getType());
    EXPECT_EQ(10001, item0.mValue.int_value);

    const FieldValue& item1 = event1.getValues()[1];
    EXPECT_EQ(0x2020101, item1.mField.getField());
    EXPECT_EQ(Type::INT, item1.mValue.getType());
    EXPECT_EQ(11, item1.mValue.int_value);

    const FieldValue& item2 = event1.getValues()[2];
    EXPECT_EQ(0x2020182, item2.mField.getField());
    EXPECT_EQ(Type::INT, item2.mValue.getType());
    EXPECT_EQ(123, item2.mValue.int_value);

    const FieldValue& item3 = event1.getValues()[3];
    EXPECT_EQ(0x2020201, item3.mField.getField());
    EXPECT_EQ(Type::INT, item3.mValue.getType());
    EXPECT_EQ(22, item3.mValue.int_value);

    const FieldValue& item4 = event1.getValues()[4];
    EXPECT_EQ(0x2020282, item4.mField.getField());
    EXPECT_EQ(Type::INT, item4.mValue.getType());
    EXPECT_EQ(345, item4.mValue.int_value);

    const FieldValue& item5 = event1.getValues()[5];
    EXPECT_EQ(0x2020301, item5.mField.getField());
    EXPECT_EQ(Type::INT, item5.mValue.getType());
    EXPECT_EQ(33, item5.mValue.int_value);

    const FieldValue& item6 = event1.getValues()[6];
    EXPECT_EQ(0x2020383, item6.mField.getField());
    EXPECT_EQ(Type::LONG, item6.mValue.getType());
    EXPECT_EQ(678L, item6.mValue.long_value);

    const FieldValue& item7 = event1.getValues()[7];
    EXPECT_EQ(0x2020401, item7.mField.getField());
    EXPECT_EQ(Type::INT, item7.mValue.getType());
    EXPECT_EQ(44, item7.mValue.int_value);

    const FieldValue& item8 = event1.getValues()[8];
    EXPECT_EQ(0x2020483, item8.mField.getField());
    EXPECT_EQ(Type::LONG, item8.mValue.getType());
    EXPECT_EQ(890L, item8.mValue.long_value);

    const FieldValue& item9 = event1.getValues()[9];
    EXPECT_EQ(0x2020501, item9.mField.getField());
    EXPECT_EQ(Type::INT, item9.mValue.getType());
    EXPECT_EQ(1, item9.mValue.int_value);

    const FieldValue& item10 = event1.getValues()[10];
    EXPECT_EQ(0x2020584, item10.mField.getField());
    EXPECT_EQ(Type::STRING, item10.mValue.getType());
    EXPECT_EQ("test2", item10.mValue.str_value);

    const FieldValue& item11 = event1.getValues()[11];
    EXPECT_EQ(0x2020601, item11.mField.getField());
    EXPECT_EQ(Type::INT, item11.mValue.getType());
    EXPECT_EQ(2, item11.mValue.int_value);

    const FieldValue& item12 = event1.getValues()[12];
    EXPECT_EQ(0x2020684, item12.mField.getField());
    EXPECT_EQ(Type::STRING, item12.mValue.getType());
    EXPECT_EQ("test1", item12.mValue.str_value);

    const FieldValue& item13 = event1.getValues()[13];
    EXPECT_EQ(0x2020701, item13.mField.getField());
    EXPECT_EQ(Type::INT, item13.mValue.getType());
    EXPECT_EQ(111, item13.mValue.int_value);

    const FieldValue& item14 = event1.getValues()[14];
    EXPECT_EQ(0x2020785, item14.mField.getField());
    EXPECT_EQ(Type::FLOAT, item14.mValue.getType());
    EXPECT_EQ(2.2f, item14.mValue.float_value);

    const FieldValue& item15 = event1.getValues()[15];
    EXPECT_EQ(0x2028801, item15.mField.getField());
    EXPECT_EQ(Type::INT, item15.mValue.getType());
    EXPECT_EQ(222, item15.mValue.int_value);

    const FieldValue& item16 = event1.getValues()[16];
    EXPECT_EQ(0x2028885, item16.mField.getField());
    EXPECT_EQ(Type::FLOAT, item16.mValue.getType());
    EXPECT_EQ(1.1f, item16.mValue.float_value);
}

TEST(LogEventTest, TestStatsLogEventWrapperNoChain) {
    Parcel parcel;
    // tag id
    parcel.writeInt32(1);
    // elapsed realtime
    parcel.writeInt64(1111L);
    // wallclock time
    parcel.writeInt64(2222L);
    // no chain
    parcel.writeInt32(0);
    // 2 data
    parcel.writeInt32(2);
    // int 6
    parcel.writeInt32(1);
    parcel.writeInt32(6);
    // long 10
    parcel.writeInt32(2);
    parcel.writeInt64(10);
    parcel.setDataPosition(0);

    StatsLogEventWrapper statsLogEventWrapper;
    EXPECT_EQ(NO_ERROR, statsLogEventWrapper.readFromParcel(&parcel));
    EXPECT_EQ(1, statsLogEventWrapper.getTagId());
    EXPECT_EQ(1111L, statsLogEventWrapper.getElapsedRealTimeNs());
    EXPECT_EQ(2222L, statsLogEventWrapper.getWallClockTimeNs());
    EXPECT_EQ(0, statsLogEventWrapper.getWorkChains().size());
    EXPECT_EQ(2, statsLogEventWrapper.getElements().size());
    EXPECT_EQ(6, statsLogEventWrapper.getElements()[0].int_value);
    EXPECT_EQ(10L, statsLogEventWrapper.getElements()[1].long_value);
    LogEvent event(statsLogEventWrapper, -1);
    EXPECT_EQ(1, event.GetTagId());
    EXPECT_EQ(1111L, event.GetElapsedTimestampNs());
    EXPECT_EQ(2222L, event.GetLogdTimestampNs());
    EXPECT_EQ(2, event.size());
    EXPECT_EQ(6, event.getValues()[0].mValue.int_value);
    EXPECT_EQ(10, event.getValues()[1].mValue.long_value);
}

TEST(LogEventTest, TestStatsLogEventWrapperWithChain) {
    Parcel parcel;
    // tag id
    parcel.writeInt32(1);
    // elapsed realtime
    parcel.writeInt64(1111L);
    // wallclock time
    parcel.writeInt64(2222L);
    // 3 chains
    parcel.writeInt32(3);
    // chain1, 2 nodes (1, "tag1") (2, "tag2")
    parcel.writeInt32(2);
    parcel.writeInt32(1);
    parcel.writeString16(String16("tag1"));
    parcel.writeInt32(2);
    parcel.writeString16(String16("tag2"));
    // chain2, 1 node (3, "tag3")
    parcel.writeInt32(1);
    parcel.writeInt32(3);
    parcel.writeString16(String16("tag3"));
    // chain3, 2 nodes (4, "") (5, "")
    parcel.writeInt32(2);
    parcel.writeInt32(4);
    parcel.writeString16(String16(""));
    parcel.writeInt32(5);
    parcel.writeString16(String16(""));
    // 2 data
    parcel.writeInt32(2);
    // int 6
    parcel.writeInt32(1);
    parcel.writeInt32(6);
    // long 10
    parcel.writeInt32(2);
    parcel.writeInt64(10);
    parcel.setDataPosition(0);

    StatsLogEventWrapper statsLogEventWrapper;
    EXPECT_EQ(NO_ERROR, statsLogEventWrapper.readFromParcel(&parcel));
    EXPECT_EQ(1, statsLogEventWrapper.getTagId());
    EXPECT_EQ(1111L, statsLogEventWrapper.getElapsedRealTimeNs());
    EXPECT_EQ(2222L, statsLogEventWrapper.getWallClockTimeNs());
    EXPECT_EQ(3, statsLogEventWrapper.getWorkChains().size());
    EXPECT_EQ(2, statsLogEventWrapper.getWorkChains()[0].uids.size());
    EXPECT_EQ(1, statsLogEventWrapper.getWorkChains()[0].uids[0]);
    EXPECT_EQ(2, statsLogEventWrapper.getWorkChains()[0].uids[1]);
    EXPECT_EQ(2, statsLogEventWrapper.getWorkChains()[0].tags.size());
    EXPECT_EQ("tag1", statsLogEventWrapper.getWorkChains()[0].tags[0]);
    EXPECT_EQ("tag2", statsLogEventWrapper.getWorkChains()[0].tags[1]);
    EXPECT_EQ(1, statsLogEventWrapper.getWorkChains()[1].uids.size());
    EXPECT_EQ(3, statsLogEventWrapper.getWorkChains()[1].uids[0]);
    EXPECT_EQ(1, statsLogEventWrapper.getWorkChains()[1].tags.size());
    EXPECT_EQ("tag3", statsLogEventWrapper.getWorkChains()[1].tags[0]);
    EXPECT_EQ(2, statsLogEventWrapper.getElements().size());
    EXPECT_EQ(6, statsLogEventWrapper.getElements()[0].int_value);
    EXPECT_EQ(10L, statsLogEventWrapper.getElements()[1].long_value);
    EXPECT_EQ(2, statsLogEventWrapper.getWorkChains()[2].uids.size());
    EXPECT_EQ(4, statsLogEventWrapper.getWorkChains()[2].uids[0]);
    EXPECT_EQ(5, statsLogEventWrapper.getWorkChains()[2].uids[1]);
    EXPECT_EQ(2, statsLogEventWrapper.getWorkChains()[2].tags.size());
    EXPECT_EQ("", statsLogEventWrapper.getWorkChains()[2].tags[0]);
    EXPECT_EQ("", statsLogEventWrapper.getWorkChains()[2].tags[1]);

    LogEvent event(statsLogEventWrapper, -1);
    EXPECT_EQ(1, event.GetTagId());
    EXPECT_EQ(1111L, event.GetElapsedTimestampNs());
    EXPECT_EQ(2222L, event.GetLogdTimestampNs());
    EXPECT_EQ(2, event.size());
    EXPECT_EQ(6, event.getValues()[0].mValue.int_value);
    EXPECT_EQ(10, event.getValues()[1].mValue.long_value);

    LogEvent event1(statsLogEventWrapper, 0);

    EXPECT_EQ(1, event1.GetTagId());
    EXPECT_EQ(1111L, event1.GetElapsedTimestampNs());
    EXPECT_EQ(2222L, event1.GetLogdTimestampNs());
    EXPECT_EQ(6, event1.size());
    EXPECT_EQ(1, event1.getValues()[0].mValue.int_value);
    EXPECT_EQ(0x2010101, event1.getValues()[0].mField.getField());
    EXPECT_EQ("tag1", event1.getValues()[1].mValue.str_value);
    EXPECT_EQ(0x2010182, event1.getValues()[1].mField.getField());
    EXPECT_EQ(2, event1.getValues()[2].mValue.int_value);
    EXPECT_EQ(0x2010201, event1.getValues()[2].mField.getField());
    EXPECT_EQ("tag2", event1.getValues()[3].mValue.str_value);
    EXPECT_EQ(0x2018282, event1.getValues()[3].mField.getField());
    EXPECT_EQ(6, event1.getValues()[4].mValue.int_value);
    EXPECT_EQ(0x20000, event1.getValues()[4].mField.getField());
    EXPECT_EQ(10, event1.getValues()[5].mValue.long_value);
    EXPECT_EQ(0x30000, event1.getValues()[5].mField.getField());

    LogEvent event2(statsLogEventWrapper, 1);

    EXPECT_EQ(1, event2.GetTagId());
    EXPECT_EQ(1111L, event2.GetElapsedTimestampNs());
    EXPECT_EQ(2222L, event2.GetLogdTimestampNs());
    EXPECT_EQ(4, event2.size());
    EXPECT_EQ(3, event2.getValues()[0].mValue.int_value);
    EXPECT_EQ(0x2010101, event2.getValues()[0].mField.getField());
    EXPECT_EQ("tag3", event2.getValues()[1].mValue.str_value);
    EXPECT_EQ(0x2018182, event2.getValues()[1].mField.getField());
    EXPECT_EQ(6, event2.getValues()[2].mValue.int_value);
    EXPECT_EQ(0x20000, event2.getValues()[2].mField.getField());
    EXPECT_EQ(10, event2.getValues()[3].mValue.long_value);
    EXPECT_EQ(0x30000, event2.getValues()[3].mField.getField());

    LogEvent event3(statsLogEventWrapper, 2);

    EXPECT_EQ(1, event3.GetTagId());
    EXPECT_EQ(1111L, event3.GetElapsedTimestampNs());
    EXPECT_EQ(2222L, event3.GetLogdTimestampNs());
    EXPECT_EQ(6, event3.size());
    EXPECT_EQ(4, event3.getValues()[0].mValue.int_value);
    EXPECT_EQ(0x2010101, event3.getValues()[0].mField.getField());
    EXPECT_EQ("", event3.getValues()[1].mValue.str_value);
    EXPECT_EQ(0x2010182, event3.getValues()[1].mField.getField());
    EXPECT_EQ(5, event3.getValues()[2].mValue.int_value);
    EXPECT_EQ(0x2010201, event3.getValues()[2].mField.getField());
    EXPECT_EQ("", event3.getValues()[3].mValue.str_value);
    EXPECT_EQ(0x2018282, event3.getValues()[3].mField.getField());
    EXPECT_EQ(6, event3.getValues()[4].mValue.int_value);
    EXPECT_EQ(0x20000, event3.getValues()[4].mField.getField());
    EXPECT_EQ(10, event3.getValues()[5].mValue.long_value);
    EXPECT_EQ(0x30000, event3.getValues()[5].mField.getField());
}

TEST(LogEventTest, TestBinaryFieldAtom) {
    Atom launcherAtom;
    auto launcher_event = launcherAtom.mutable_launcher_event();
    launcher_event->set_action(stats::launcher::LauncherAction::LONGPRESS);
    launcher_event->set_src_state(stats::launcher::LauncherState::OVERVIEW);
    launcher_event->set_dst_state(stats::launcher::LauncherState::ALLAPPS);

    auto extension = launcher_event->mutable_extension();

    auto src_target = extension->add_src_target();
    src_target->set_type(stats::launcher::LauncherTarget_Type_ITEM_TYPE);
    src_target->set_item(stats::launcher::LauncherTarget_Item_FOLDER_ICON);

    auto dst_target = extension->add_dst_target();
    dst_target->set_type(stats::launcher::LauncherTarget_Type_ITEM_TYPE);
    dst_target->set_item(stats::launcher::LauncherTarget_Item_WIDGET);

    string extension_str;
    extension->SerializeToString(&extension_str);

    LogEvent event1(Atom::kLauncherEventFieldNumber, 1000);

    event1.write((int32_t)stats::launcher::LauncherAction::LONGPRESS);
    event1.write((int32_t)stats::launcher::LauncherState::OVERVIEW);
    event1.write((int64_t)stats::launcher::LauncherState::ALLAPPS);
    event1.write(extension_str);
    event1.init();

    ProtoOutputStream proto;
    event1.ToProto(proto);

    std::vector<uint8_t> outData;
    outData.resize(proto.size());
    size_t pos = 0;
    auto iter = proto.data();
    while (iter.readBuffer() != NULL) {
        size_t toRead = iter.currentToRead();
        std::memcpy(&(outData[pos]), iter.readBuffer(), toRead);
        pos += toRead;
        iter.rp()->move(toRead);
    }

    std::string result_str(outData.begin(), outData.end());
    std::string orig_str;
    launcherAtom.SerializeToString(&orig_str);

    EXPECT_EQ(orig_str, result_str);
}

TEST(LogEventTest, TestBinaryFieldAtom_empty) {
    Atom launcherAtom;
    auto launcher_event = launcherAtom.mutable_launcher_event();
    launcher_event->set_action(stats::launcher::LauncherAction::LONGPRESS);
    launcher_event->set_src_state(stats::launcher::LauncherState::OVERVIEW);
    launcher_event->set_dst_state(stats::launcher::LauncherState::ALLAPPS);

    // empty string.
    string extension_str;

    LogEvent event1(Atom::kLauncherEventFieldNumber, 1000);

    event1.write((int32_t)stats::launcher::LauncherAction::LONGPRESS);
    event1.write((int32_t)stats::launcher::LauncherState::OVERVIEW);
    event1.write((int64_t)stats::launcher::LauncherState::ALLAPPS);
    event1.write(extension_str);
    event1.init();

    ProtoOutputStream proto;
    event1.ToProto(proto);

    std::vector<uint8_t> outData;
    outData.resize(proto.size());
    size_t pos = 0;
    auto iter = proto.data();
    while (iter.readBuffer() != NULL) {
        size_t toRead = iter.currentToRead();
        std::memcpy(&(outData[pos]), iter.readBuffer(), toRead);
        pos += toRead;
        iter.rp()->move(toRead);
    }

    std::string result_str(outData.begin(), outData.end());
    std::string orig_str;
    launcherAtom.SerializeToString(&orig_str);

    EXPECT_EQ(orig_str, result_str);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
