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
#include <android/util/EncodedBuffer.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

using namespace android::util;

constexpr size_t TEST_CHUNK_SIZE = 16UL;
constexpr size_t TEST_CHUNK_HALF_SIZE = TEST_CHUNK_SIZE / 2;
constexpr size_t TEST_CHUNK_3X_SIZE = 3 * TEST_CHUNK_SIZE;

static void expectPointer(EncodedBuffer::Pointer* p, size_t pos) {
    EXPECT_EQ(p->pos(), pos);
    EXPECT_EQ(p->index(), pos / TEST_CHUNK_SIZE);
    EXPECT_EQ(p->offset(), pos % TEST_CHUNK_SIZE);
}

TEST(EncodedBufferTest, WriteSimple) {
    EncodedBuffer buffer(TEST_CHUNK_SIZE);
    EXPECT_EQ(buffer.size(), 0UL);
    expectPointer(buffer.wp(), 0);
    EXPECT_EQ(buffer.currentToWrite(), TEST_CHUNK_SIZE);
    for (size_t i = 0; i < TEST_CHUNK_HALF_SIZE; i++) {
        buffer.writeRawByte(50 + i);
    }
    EXPECT_EQ(buffer.size(), TEST_CHUNK_HALF_SIZE);
    expectPointer(buffer.wp(), TEST_CHUNK_HALF_SIZE);
    EXPECT_EQ(buffer.currentToWrite(), TEST_CHUNK_HALF_SIZE);
    for (size_t i = 0; i < TEST_CHUNK_SIZE; i++) {
        buffer.writeRawByte(80 + i);
    }
    EXPECT_EQ(buffer.size(), TEST_CHUNK_SIZE + TEST_CHUNK_HALF_SIZE);
    expectPointer(buffer.wp(), TEST_CHUNK_SIZE + TEST_CHUNK_HALF_SIZE);
    EXPECT_EQ(buffer.currentToWrite(), TEST_CHUNK_HALF_SIZE);

    // verifies the buffer's data
    expectPointer(buffer.ep(), 0);
    for (size_t i = 0; i < TEST_CHUNK_HALF_SIZE; i++) {
        EXPECT_EQ(buffer.readRawByte(), 50 + i);
    }
    for (size_t i = 0; i < TEST_CHUNK_SIZE; i++) {
        EXPECT_EQ(buffer.readRawByte(), 80 + i);
    }

    // clears the buffer
    buffer.clear();
    EXPECT_EQ(buffer.size(), 0UL);
    expectPointer(buffer.wp(), 0);
}

TEST(EncodedBufferTest, WriteVarint) {
    EncodedBuffer buffer(TEST_CHUNK_SIZE);
    size_t expected_buffer_size = 0;
    EXPECT_EQ(buffer.writeRawVarint32(13), 1);
    expected_buffer_size += 1;
    EXPECT_EQ(buffer.size(), expected_buffer_size);
    EXPECT_EQ(buffer.writeRawVarint32(UINT32_C(-1)), 5);
    expected_buffer_size += 5;
    EXPECT_EQ(buffer.size(), expected_buffer_size);

    EXPECT_EQ(buffer.writeRawVarint64(200), 2);
    expected_buffer_size += 2;
    EXPECT_EQ(buffer.size(), expected_buffer_size);
    EXPECT_EQ(buffer.writeRawVarint64(UINT64_C(-1)), 10);
    expected_buffer_size += 10;
    EXPECT_EQ(buffer.size(), expected_buffer_size);

    buffer.writeRawFixed32(UINT32_C(-1));
    expected_buffer_size += 4;
    EXPECT_EQ(buffer.size(), expected_buffer_size);
    buffer.writeRawFixed64(UINT64_C(-1));
    expected_buffer_size += 8;
    EXPECT_EQ(buffer.size(), expected_buffer_size);

    EXPECT_EQ(buffer.writeHeader(32, 2), 2);
    expected_buffer_size += 2;
    EXPECT_EQ(buffer.size(), expected_buffer_size);

    // verify data are correctly written to the buffer.
    expectPointer(buffer.ep(), 0);
    EXPECT_EQ(buffer.readRawVarint(), UINT32_C(13));
    EXPECT_EQ(buffer.readRawVarint(), UINT32_C(-1));
    EXPECT_EQ(buffer.readRawVarint(), UINT64_C(200));
    EXPECT_EQ(buffer.readRawVarint(), UINT64_C(-1));
    EXPECT_EQ(buffer.readRawFixed32(), UINT32_C(-1));
    EXPECT_EQ(buffer.readRawFixed64(), UINT64_C(-1));
    EXPECT_EQ(buffer.readRawVarint(), UINT64_C((32 << 3) + 2));
    expectPointer(buffer.ep(), expected_buffer_size);
}

TEST(EncodedBufferTest, Edit) {
    EncodedBuffer buffer(TEST_CHUNK_SIZE);
    buffer.writeRawFixed64(0xdeadbeefdeadbeef);
    EXPECT_EQ(buffer.readRawFixed64(), UINT64_C(0xdeadbeefdeadbeef));

    buffer.editRawFixed32(4, 0x12345678);
    // fixed 64 is little endian order.
    buffer.ep()->rewind(); // rewind ep for readRawFixed64 from 0
    EXPECT_EQ(buffer.readRawFixed64(), UINT64_C(0x12345678deadbeef));

    buffer.wp()->rewind();
    expectPointer(buffer.wp(), 0);
    buffer.copy(4, 3);
    buffer.ep()->rewind(); // rewind ep for readRawFixed64 from 0
    EXPECT_EQ(buffer.readRawFixed64(), UINT64_C(0x12345678de345678));
}

TEST(EncodedBufferTest, ReadSimple) {
    EncodedBuffer buffer(TEST_CHUNK_SIZE);
    for (size_t i = 0; i < TEST_CHUNK_3X_SIZE; i++) {
        buffer.writeRawByte(i);
    }
    auto iter = buffer.begin();
    EXPECT_EQ(iter.size(), TEST_CHUNK_3X_SIZE);
    EXPECT_EQ(iter.bytesRead(), 0);

    expectPointer(iter.rp(), 0);
    while (iter.readBuffer() != NULL) {
        iter.rp()->move(iter.currentToRead());
    }
    EXPECT_EQ(iter.bytesRead(), TEST_CHUNK_3X_SIZE);
    expectPointer(iter.rp(), TEST_CHUNK_3X_SIZE);

    iter.rp()->rewind();
    expectPointer(iter.rp(), 0);
    uint8_t val = 0;
    while (iter.hasNext()) {
        EXPECT_EQ(iter.next(), val);
        val++;
    }
    EXPECT_EQ(iter.bytesRead(), TEST_CHUNK_3X_SIZE);
    expectPointer(iter.rp(), TEST_CHUNK_3X_SIZE);
}

TEST(EncodedBufferTest, ReadVarint) {
    EncodedBuffer buffer;
    uint64_t val = UINT64_C(1522865904593);
    size_t len = buffer.writeRawVarint64(val);
    auto iter = buffer.begin();
    EXPECT_EQ(iter.size(), len);
    EXPECT_EQ(iter.readRawVarint(), val);
}
