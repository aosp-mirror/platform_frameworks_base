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
#include <android/util/protobuf.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

using namespace android::util;

TEST(ProtobufTest, All) {
    EXPECT_EQ(read_wire_type(UINT32_C(17)), 1);
    EXPECT_EQ(read_field_id(UINT32_C(17)), 2);
    EXPECT_EQ(get_varint_size(UINT64_C(234134)), 3);
    EXPECT_EQ(get_varint_size(UINT64_C(-1)), 10);

    constexpr uint8_t UNSET_BYTE = 0xAB;

    uint8_t buf[11];
    memset(buf, UNSET_BYTE, sizeof(buf));
    EXPECT_EQ(write_raw_varint(buf, UINT64_C(150)) - buf, 2);
    EXPECT_EQ(buf[0], 0x96);
    EXPECT_EQ(buf[1], 0x01);
    EXPECT_EQ(buf[2], UNSET_BYTE);

    memset(buf, UNSET_BYTE, sizeof(buf));
    EXPECT_EQ(write_raw_varint(buf, UINT64_C(-2)) - buf, 10);
    EXPECT_EQ(buf[0], 0xfe);
    for (int i = 1; i < 9; i++) {
        EXPECT_EQ(buf[i], 0xff);
    }
    EXPECT_EQ(buf[9], 0x01);
    EXPECT_EQ(buf[10], UNSET_BYTE);

    uint8_t header[20];
    memset(header, UNSET_BYTE, sizeof(header));
    EXPECT_EQ(write_length_delimited_tag_header(header, 3, 150) - header, 3);
    EXPECT_EQ(header[0], 26);
    EXPECT_EQ(header[1], 0x96);
    EXPECT_EQ(header[2], 0x01);
    EXPECT_EQ(header[3], UNSET_BYTE);
}