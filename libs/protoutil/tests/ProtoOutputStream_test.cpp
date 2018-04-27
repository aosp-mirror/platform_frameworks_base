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

#include <android-base/file.h>
#include <android-base/test_utils.h>
#include <android/util/protobuf.h>
#include <android/util/ProtoOutputStream.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include "frameworks/base/libs/protoutil/tests/test.pb.h"

using namespace android::base;
using namespace android::util;
using ::testing::StrEq;

static std::string flushToString(ProtoOutputStream* proto) {
    TemporaryFile tf;
    std::string content;

    EXPECT_NE(tf.fd, -1);
    EXPECT_TRUE(proto->flush(tf.fd));
    EXPECT_TRUE(ReadFileToString(tf.path, &content));
    return content;
}

static std::string iterateToString(ProtoOutputStream* proto) {
    std::string content;
    content.reserve(proto->size());
    auto iter = proto->data();
    while (iter.hasNext()) {
        content.push_back(iter.next());
    }
    return content;
}

TEST(ProtoOutputStreamTest, Primitives) {
    std::string s = "hello";
    const char b[5] = { 'a', 'p', 'p', 'l', 'e' };

    ProtoOutputStream proto;
    EXPECT_TRUE(proto.write(FIELD_TYPE_INT32 | PrimitiveProto::kValInt32FieldNumber, 123));
    EXPECT_TRUE(proto.write(FIELD_TYPE_INT64 | PrimitiveProto::kValInt64FieldNumber, -1LL));
    EXPECT_TRUE(proto.write(FIELD_TYPE_FLOAT | PrimitiveProto::kValFloatFieldNumber, -23.5f));
    EXPECT_TRUE(proto.write(FIELD_TYPE_DOUBLE | PrimitiveProto::kValDoubleFieldNumber, 324.5));
    EXPECT_TRUE(proto.write(FIELD_TYPE_UINT32 | PrimitiveProto::kValUint32FieldNumber, 3424));
    EXPECT_TRUE(proto.write(FIELD_TYPE_UINT64 | PrimitiveProto::kValUint64FieldNumber, 57LL));
    EXPECT_TRUE(proto.write(FIELD_TYPE_FIXED32 | PrimitiveProto::kValFixed32FieldNumber, -20));
    EXPECT_TRUE(proto.write(FIELD_TYPE_FIXED64 | PrimitiveProto::kValFixed64FieldNumber, -37LL));
    EXPECT_TRUE(proto.write(FIELD_TYPE_BOOL | PrimitiveProto::kValBoolFieldNumber, true));
    EXPECT_TRUE(proto.write(FIELD_TYPE_STRING | PrimitiveProto::kValStringFieldNumber, s));
    EXPECT_TRUE(proto.write(FIELD_TYPE_BYTES | PrimitiveProto::kValBytesFieldNumber, b, 5));
    EXPECT_TRUE(proto.write(FIELD_TYPE_SFIXED32 | PrimitiveProto::kValSfixed32FieldNumber, 63));
    EXPECT_TRUE(proto.write(FIELD_TYPE_SFIXED64 | PrimitiveProto::kValSfixed64FieldNumber, -54));
    EXPECT_TRUE(proto.write(FIELD_TYPE_SINT32 | PrimitiveProto::kValSint32FieldNumber, -533));
    EXPECT_TRUE(proto.write(FIELD_TYPE_SINT64 | PrimitiveProto::kValSint64FieldNumber, -61224762453LL));
    EXPECT_TRUE(proto.write(FIELD_TYPE_ENUM | PrimitiveProto::kValEnumFieldNumber, 2));

    PrimitiveProto primitives;
    ASSERT_TRUE(primitives.ParseFromString(flushToString(&proto)));
    EXPECT_EQ(primitives.val_int32(), 123);
    EXPECT_EQ(primitives.val_int64(), -1);
    EXPECT_EQ(primitives.val_float(), -23.5f);
    EXPECT_EQ(primitives.val_double(), 324.5f);
    EXPECT_EQ(primitives.val_uint32(), 3424);
    EXPECT_EQ(primitives.val_uint64(), 57);
    EXPECT_EQ(primitives.val_fixed32(), -20);
    EXPECT_EQ(primitives.val_fixed64(), -37);
    EXPECT_EQ(primitives.val_bool(), true);
    EXPECT_THAT(primitives.val_string(), StrEq(s.c_str()));
    EXPECT_THAT(primitives.val_bytes(), StrEq("apple"));
    EXPECT_EQ(primitives.val_sfixed32(), 63);
    EXPECT_EQ(primitives.val_sfixed64(), -54);
    EXPECT_EQ(primitives.val_sint32(), -533);
    EXPECT_EQ(primitives.val_sint64(), -61224762453LL);
    EXPECT_EQ(primitives.val_enum(), PrimitiveProto_Count_TWO);
}

TEST(ProtoOutputStreamTest, Complex) {
    std::string name1 = "cat";
    std::string name2 = "dog";
    const char data1[6] = { 'f', 'u', 'n', 'n', 'y', '!' };
    const char data2[4] = { 'f', 'o', 'o', 'd' };

    ProtoOutputStream proto;
    EXPECT_TRUE(proto.write(FIELD_TYPE_INT32 | ComplexProto::kIntsFieldNumber, 23));
    EXPECT_TRUE(proto.write(FIELD_TYPE_INT32 | ComplexProto::kIntsFieldNumber, 101));
    EXPECT_TRUE(proto.write(FIELD_TYPE_INT32 | ComplexProto::kIntsFieldNumber, -72));
    uint64_t token1 = proto.start(FIELD_TYPE_MESSAGE | ComplexProto::kLogsFieldNumber);
    EXPECT_TRUE(proto.write(FIELD_TYPE_INT32 | ComplexProto::Log::kIdFieldNumber, 12));
    EXPECT_TRUE(proto.write(FIELD_TYPE_STRING | ComplexProto::Log::kNameFieldNumber, name1));
    // specify the length to test the write(id, bytes, length) function.
    EXPECT_TRUE(proto.write(FIELD_TYPE_BYTES | ComplexProto::Log::kDataFieldNumber, data1, 5));
    proto.end(token1);
    uint64_t token2 = proto.start(FIELD_TYPE_MESSAGE | ComplexProto::kLogsFieldNumber);
    EXPECT_TRUE(proto.write(FIELD_TYPE_INT32 | ComplexProto::Log::kIdFieldNumber, 98));
    EXPECT_TRUE(proto.write(FIELD_TYPE_STRING | ComplexProto::Log::kNameFieldNumber, name2));
    EXPECT_TRUE(proto.write(FIELD_TYPE_BYTES | ComplexProto::Log::kDataFieldNumber, data2, 4));
    proto.end(token2);

    ComplexProto complex;
    ASSERT_TRUE(complex.ParseFromString(iterateToString(&proto)));
    EXPECT_EQ(complex.ints_size(), 3);
    EXPECT_EQ(complex.ints(0), 23);
    EXPECT_EQ(complex.ints(1), 101);
    EXPECT_EQ(complex.ints(2), -72);
    EXPECT_EQ(complex.logs_size(), 2);
    ComplexProto::Log log1 = complex.logs(0);
    EXPECT_EQ(log1.id(), 12);
    EXPECT_THAT(log1.name(), StrEq(name1.c_str()));
    EXPECT_THAT(log1.data(), StrEq("funny")); // should not contain '!'
    ComplexProto::Log log2 = complex.logs(1);
    EXPECT_EQ(log2.id(), 98);
    EXPECT_THAT(log2.name(), StrEq(name2.c_str()));
    EXPECT_THAT(log2.data(), StrEq("food"));
}

TEST(ProtoOutputStreamTest, Reusability) {
    ProtoOutputStream proto;
    EXPECT_TRUE(proto.write(FIELD_TYPE_INT32 | ComplexProto::kIntsFieldNumber, 32));
    EXPECT_TRUE(proto.write(FIELD_TYPE_INT32 | ComplexProto::kIntsFieldNumber, 15));
    EXPECT_EQ(proto.bytesWritten(), 4);
    EXPECT_EQ(proto.size(), 4);
    // Can't write to proto after compact
    EXPECT_FALSE(proto.write(FIELD_TYPE_INT32 | ComplexProto::kIntsFieldNumber, 94));

    ComplexProto beforeClear;
    ASSERT_TRUE(beforeClear.ParseFromString(flushToString(&proto)));
    EXPECT_EQ(beforeClear.ints_size(), 2);
    EXPECT_EQ(beforeClear.ints(0), 32);
    EXPECT_EQ(beforeClear.ints(1), 15);

    proto.clear();
    EXPECT_EQ(proto.bytesWritten(), 0);
    EXPECT_TRUE(proto.write(FIELD_TYPE_INT32 | ComplexProto::kIntsFieldNumber, 1076));

    ComplexProto afterClear;
    ASSERT_TRUE(afterClear.ParseFromString(flushToString(&proto)));
    EXPECT_EQ(afterClear.ints_size(), 1);
    EXPECT_EQ(afterClear.ints(0), 1076);
}

TEST(ProtoOutputStreamTest, AdvancedEncoding) {
    ProtoOutputStream proto;
    proto.writeRawVarint((ComplexProto::kIntsFieldNumber << FIELD_ID_SHIFT) + WIRE_TYPE_VARINT);
    proto.writeRawVarint(UINT64_C(-123809234));
    proto.writeLengthDelimitedHeader(ComplexProto::kLogsFieldNumber, 8);
    proto.writeRawByte((ComplexProto::Log::kDataFieldNumber << FIELD_ID_SHIFT) + WIRE_TYPE_LENGTH_DELIMITED);
    proto.writeRawByte(6);
    proto.writeRawByte('b');
    proto.writeRawByte('a');
    proto.writeRawByte('n');
    proto.writeRawByte('a');
    proto.writeRawByte('n');
    proto.writeRawByte('a');
    uint64_t token = proto.start(FIELD_TYPE_MESSAGE | ComplexProto::kLogsFieldNumber);
    proto.write(FIELD_TYPE_INT32 | ComplexProto::Log::kIdFieldNumber, 14);
    proto.end(token);

    ComplexProto complex;
    ASSERT_TRUE(complex.ParseFromString(flushToString(&proto)));
    EXPECT_EQ(complex.ints_size(), 1);
    EXPECT_EQ(complex.ints(0), UINT64_C(-123809234));
    EXPECT_EQ(complex.logs_size(), 2);
    ComplexProto::Log log1 = complex.logs(0);
    EXPECT_FALSE(log1.has_id());
    EXPECT_FALSE(log1.has_name());
    EXPECT_THAT(log1.data(), StrEq("banana"));
    ComplexProto::Log log2 = complex.logs(1);
    EXPECT_EQ(log2.id(), 14);
    EXPECT_FALSE(log2.has_name());
    EXPECT_FALSE(log2.has_data());
}

TEST(ProtoOutputStreamTest, InvalidTypes) {
    ProtoOutputStream proto;
    EXPECT_FALSE(proto.write(FIELD_TYPE_UNKNOWN | PrimitiveProto::kValInt32FieldNumber, 790));
    EXPECT_FALSE(proto.write(FIELD_TYPE_ENUM | PrimitiveProto::kValEnumFieldNumber, 234.34));
    EXPECT_FALSE(proto.write(FIELD_TYPE_BOOL | PrimitiveProto::kValBoolFieldNumber, 18.73f));
    EXPECT_EQ(proto.size(), 0);
}