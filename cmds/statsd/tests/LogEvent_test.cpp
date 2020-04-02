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

#include <gtest/gtest.h>

#include "frameworks/base/cmds/statsd/src/atoms.pb.h"
#include "frameworks/base/core/proto/android/stats/launcher/launcher.pb.h"
#include "log/log_event_list.h"
#include "src/logd/LogEvent.h"
#include "stats_event.h"


#ifdef __ANDROID__

namespace android {
namespace os {
namespace statsd {

using std::string;
using std::vector;
using util::ProtoOutputStream;
using util::ProtoReader;

Field getField(int32_t tag, const vector<int32_t>& pos, int32_t depth, const vector<bool>& last) {
    Field f(tag, (int32_t*)pos.data(), depth);

    // For loop starts at 1 because the last field at depth 0 is not decorated.
    for (int i = 1; i < depth; i++) {
        if (last[i]) f.decorateLastPos(i);
    }

    return f;
}

TEST(LogEventTest, TestPrimitiveParsing) {
    AStatsEvent* event = AStatsEvent_obtain();
    AStatsEvent_setAtomId(event, 100);
    AStatsEvent_writeInt32(event, 10);
    AStatsEvent_writeInt64(event, 0x123456789);
    AStatsEvent_writeFloat(event, 2.0);
    AStatsEvent_writeBool(event, true);
    AStatsEvent_build(event);

    size_t size;
    uint8_t* buf = AStatsEvent_getBuffer(event, &size);

    LogEvent logEvent(/*uid=*/1000, /*pid=*/1001);
    EXPECT_TRUE(logEvent.parseBuffer(buf, size));

    EXPECT_EQ(100, logEvent.GetTagId());
    EXPECT_EQ(1000, logEvent.GetUid());
    EXPECT_EQ(1001, logEvent.GetPid());

    const vector<FieldValue>& values = logEvent.getValues();
    EXPECT_EQ(4, values.size());

    const FieldValue& int32Item = values[0];
    Field expectedField = getField(100, {1, 1, 1}, 0, {false, false, false});
    EXPECT_EQ(expectedField, int32Item.mField);
    EXPECT_EQ(Type::INT, int32Item.mValue.getType());
    EXPECT_EQ(10, int32Item.mValue.int_value);

    const FieldValue& int64Item = values[1];
    expectedField = getField(100, {2, 1, 1}, 0, {false, false, false});
    EXPECT_EQ(expectedField, int64Item.mField);
    EXPECT_EQ(Type::LONG, int64Item.mValue.getType());
    EXPECT_EQ(0x123456789, int64Item.mValue.long_value);

    const FieldValue& floatItem = values[2];
    expectedField = getField(100, {3, 1, 1}, 0, {false, false, false});
    EXPECT_EQ(expectedField, floatItem.mField);
    EXPECT_EQ(Type::FLOAT, floatItem.mValue.getType());
    EXPECT_EQ(2.0, floatItem.mValue.float_value);

    const FieldValue& boolItem = values[3];
    expectedField = getField(100, {4, 1, 1}, 0, {true, false, false});
    EXPECT_EQ(expectedField, boolItem.mField);
    EXPECT_EQ(Type::INT, boolItem.mValue.getType()); // FieldValue does not support boolean type
    EXPECT_EQ(1, boolItem.mValue.int_value);

    AStatsEvent_release(event);
}


TEST(LogEventTest, TestStringAndByteArrayParsing) {
    AStatsEvent* event = AStatsEvent_obtain();
    AStatsEvent_setAtomId(event, 100);
    string str = "test";
    AStatsEvent_writeString(event, str.c_str());
    AStatsEvent_writeByteArray(event, (uint8_t*)str.c_str(), str.length());
    AStatsEvent_build(event);

    size_t size;
    uint8_t* buf = AStatsEvent_getBuffer(event, &size);

    LogEvent logEvent(/*uid=*/ 1000, /*pid=*/ 1001);
    EXPECT_TRUE(logEvent.parseBuffer(buf, size));

    EXPECT_EQ(100, logEvent.GetTagId());
    EXPECT_EQ(1000, logEvent.GetUid());
    EXPECT_EQ(1001, logEvent.GetPid());

    const vector<FieldValue>& values = logEvent.getValues();
    EXPECT_EQ(2, values.size());

    const FieldValue& stringItem = values[0];
    Field expectedField = getField(100, {1, 1, 1}, 0, {false, false, false});
    EXPECT_EQ(expectedField, stringItem.mField);
    EXPECT_EQ(Type::STRING, stringItem.mValue.getType());
    EXPECT_EQ(str, stringItem.mValue.str_value);

    const FieldValue& storageItem = values[1];
    expectedField = getField(100, {2, 1, 1}, 0, {true, false, false});
    EXPECT_EQ(expectedField, storageItem.mField);
    EXPECT_EQ(Type::STORAGE, storageItem.mValue.getType());
    vector<uint8_t> expectedValue = {'t', 'e', 's', 't'};
    EXPECT_EQ(expectedValue, storageItem.mValue.storage_value);

    AStatsEvent_release(event);
}

TEST(LogEventTest, TestEmptyString) {
    AStatsEvent* event = AStatsEvent_obtain();
    AStatsEvent_setAtomId(event, 100);
    string empty = "";
    AStatsEvent_writeString(event, empty.c_str());
    AStatsEvent_build(event);

    size_t size;
    uint8_t* buf = AStatsEvent_getBuffer(event, &size);

    LogEvent logEvent(/*uid=*/ 1000, /*pid=*/ 1001);
    EXPECT_TRUE(logEvent.parseBuffer(buf, size));

    EXPECT_EQ(100, logEvent.GetTagId());
    EXPECT_EQ(1000, logEvent.GetUid());
    EXPECT_EQ(1001, logEvent.GetPid());

    const vector<FieldValue>& values = logEvent.getValues();
    EXPECT_EQ(1, values.size());

    const FieldValue& item = values[0];
    Field expectedField = getField(100, {1, 1, 1}, 0, {true, false, false});
    EXPECT_EQ(expectedField, item.mField);
    EXPECT_EQ(Type::STRING, item.mValue.getType());
    EXPECT_EQ(empty, item.mValue.str_value);

    AStatsEvent_release(event);
}

TEST(LogEventTest, TestByteArrayWithNullCharacter) {
    AStatsEvent* event = AStatsEvent_obtain();
    AStatsEvent_setAtomId(event, 100);
    uint8_t message[] = {'\t', 'e', '\0', 's', 't'};
    AStatsEvent_writeByteArray(event, message, 5);
    AStatsEvent_build(event);

    size_t size;
    uint8_t* buf = AStatsEvent_getBuffer(event, &size);

    LogEvent logEvent(/*uid=*/ 1000, /*pid=*/ 1001);
    EXPECT_TRUE(logEvent.parseBuffer(buf, size));

    EXPECT_EQ(100, logEvent.GetTagId());
    EXPECT_EQ(1000, logEvent.GetUid());
    EXPECT_EQ(1001, logEvent.GetPid());

    const vector<FieldValue>& values = logEvent.getValues();
    EXPECT_EQ(1, values.size());

    const FieldValue& item = values[0];
    Field expectedField = getField(100, {1, 1, 1}, 0, {true, false, false});
    EXPECT_EQ(expectedField, item.mField);
    EXPECT_EQ(Type::STORAGE, item.mValue.getType());
    vector<uint8_t> expectedValue(message, message + 5);
    EXPECT_EQ(expectedValue, item.mValue.storage_value);

    AStatsEvent_release(event);
}

TEST(LogEventTest, TestAttributionChain) {
    AStatsEvent* event = AStatsEvent_obtain();
    AStatsEvent_setAtomId(event, 100);

    string tag1 = "tag1";
    string tag2 = "tag2";

    uint32_t uids[] = {1001, 1002};
    const char* tags[] = {tag1.c_str(), tag2.c_str()};

    AStatsEvent_writeAttributionChain(event, uids, tags, 2);
    AStatsEvent_build(event);

    size_t size;
    uint8_t* buf = AStatsEvent_getBuffer(event, &size);

    LogEvent logEvent(/*uid=*/ 1000, /*pid=*/ 1001);
    EXPECT_TRUE(logEvent.parseBuffer(buf, size));

    EXPECT_EQ(100, logEvent.GetTagId());
    EXPECT_EQ(1000, logEvent.GetUid());
    EXPECT_EQ(1001, logEvent.GetPid());

    const vector<FieldValue>& values = logEvent.getValues();
    EXPECT_EQ(4, values.size()); // 2 per attribution node

    // Check first attribution node
    const FieldValue& uid1Item = values[0];
    Field expectedField = getField(100, {1, 1, 1}, 2, {true, false, false});
    EXPECT_EQ(expectedField, uid1Item.mField);
    EXPECT_EQ(Type::INT, uid1Item.mValue.getType());
    EXPECT_EQ(1001, uid1Item.mValue.int_value);

    const FieldValue& tag1Item = values[1];
    expectedField = getField(100, {1, 1, 2}, 2, {true, false, true});
    EXPECT_EQ(expectedField, tag1Item.mField);
    EXPECT_EQ(Type::STRING, tag1Item.mValue.getType());
    EXPECT_EQ(tag1, tag1Item.mValue.str_value);

    // Check second attribution nodes
    const FieldValue& uid2Item = values[2];
    expectedField = getField(100, {1, 2, 1}, 2, {true, true, false});
    EXPECT_EQ(expectedField, uid2Item.mField);
    EXPECT_EQ(Type::INT, uid2Item.mValue.getType());
    EXPECT_EQ(1002, uid2Item.mValue.int_value);

    const FieldValue& tag2Item = values[3];
    expectedField = getField(100, {1, 2, 2}, 2, {true, true, true});
    EXPECT_EQ(expectedField, tag2Item.mField);
    EXPECT_EQ(Type::STRING, tag2Item.mValue.getType());
    EXPECT_EQ(tag2, tag2Item.mValue.str_value);

    AStatsEvent_release(event);
}

void createIntWithBoolAnnotationLogEvent(LogEvent* logEvent, uint8_t annotationId,
                                         bool annotationValue) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, /*atomId=*/100);
    AStatsEvent_writeInt32(statsEvent, 10);
    AStatsEvent_addBoolAnnotation(statsEvent, annotationId, annotationValue);
    AStatsEvent_build(statsEvent);

    size_t size;
    uint8_t* buf = AStatsEvent_getBuffer(statsEvent, &size);
    EXPECT_TRUE(logEvent->parseBuffer(buf, size));

    AStatsEvent_release(statsEvent);
}

TEST(LogEventTest, TestAnnotationIdIsUid) {
    LogEvent event(/*uid=*/0, /*pid=*/0);
    createIntWithBoolAnnotationLogEvent(&event, ANNOTATION_ID_IS_UID, true);

    const vector<FieldValue>& values = event.getValues();
    EXPECT_EQ(values.size(), 1);
    EXPECT_EQ(event.getUidFieldIndex(), 0);
}

TEST(LogEventTest, TestAnnotationIdStateNested) {
    LogEvent event(/*uid=*/0, /*pid=*/0);
    createIntWithBoolAnnotationLogEvent(&event, ANNOTATION_ID_STATE_NESTED, true);

    const vector<FieldValue>& values = event.getValues();
    EXPECT_EQ(values.size(), 1);
    EXPECT_TRUE(values[0].mAnnotations.isNested());
}

void createIntWithIntAnnotationLogEvent(LogEvent* logEvent, uint8_t annotationId,
                                        int annotationValue) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, /*atomId=*/100);
    AStatsEvent_writeInt32(statsEvent, 10);
    AStatsEvent_addInt32Annotation(statsEvent, annotationId, annotationValue);
    AStatsEvent_build(statsEvent);

    size_t size;
    uint8_t* buf = AStatsEvent_getBuffer(statsEvent, &size);
    EXPECT_TRUE(logEvent->parseBuffer(buf, size));

    AStatsEvent_release(statsEvent);
}

TEST(LogEventTest, TestPrimaryFieldAnnotation) {
    LogEvent event(/*uid=*/0, /*pid=*/0);
    createIntWithIntAnnotationLogEvent(&event, ANNOTATION_ID_STATE_OPTION,
                                       STATE_OPTION_PRIMARY_FIELD);

    const vector<FieldValue>& values = event.getValues();
    EXPECT_EQ(values.size(), 1);
    EXPECT_TRUE(values[0].mAnnotations.isPrimaryField());
}

TEST(LogEventTest, TestExclusiveStateAnnotation) {
    LogEvent event(/*uid=*/0, /*pid=*/0);
    createIntWithIntAnnotationLogEvent(&event, ANNOTATION_ID_STATE_OPTION,
                                       STATE_OPTION_EXCLUSIVE_STATE);

    const vector<FieldValue>& values = event.getValues();
    EXPECT_EQ(values.size(), 1);
    EXPECT_TRUE(values[0].mAnnotations.isExclusiveState());
}

TEST(LogEventTest, TestPrimaryFieldFirstUidAnnotation) {
    // Event has 10 ints and then an attribution chain
    int numInts = 10;
    int firstUidInChainIndex = numInts;
    string tag1 = "tag1";
    string tag2 = "tag2";
    uint32_t uids[] = {1001, 1002};
    const char* tags[] = {tag1.c_str(), tag2.c_str()};

    // Construct AStatsEvent
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, 100);
    for (int i = 0; i < numInts; i++) {
        AStatsEvent_writeInt32(statsEvent, 10);
    }
    AStatsEvent_writeAttributionChain(statsEvent, uids, tags, 2);
    AStatsEvent_addInt32Annotation(statsEvent, ANNOTATION_ID_STATE_OPTION,
                                   STATE_OPTION_PRIMARY_FIELD_FIRST_UID);
    AStatsEvent_build(statsEvent);

    // Construct LogEvent
    size_t size;
    uint8_t* buf = AStatsEvent_getBuffer(statsEvent, &size);
    LogEvent logEvent(/*uid=*/0, /*pid=*/0);
    EXPECT_TRUE(logEvent.parseBuffer(buf, size));
    AStatsEvent_release(statsEvent);

    // Check annotation
    const vector<FieldValue>& values = logEvent.getValues();
    EXPECT_EQ(values.size(), numInts + 4);
    EXPECT_TRUE(values[firstUidInChainIndex].mAnnotations.isPrimaryField());
}

TEST(LogEventTest, TestResetStateAnnotation) {
    int32_t resetState = 10;
    LogEvent event(/*uid=*/0, /*pid=*/0);
    createIntWithIntAnnotationLogEvent(&event, ANNOTATION_ID_RESET_STATE, resetState);

    const vector<FieldValue>& values = event.getValues();
    EXPECT_EQ(values.size(), 1);
    EXPECT_EQ(values[0].mAnnotations.getResetState(), resetState);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
