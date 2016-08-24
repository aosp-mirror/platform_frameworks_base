/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "util/BigBuffer.h"

#include <gtest/gtest.h>

namespace aapt {

TEST(BigBufferTest, AllocateSingleBlock) {
    BigBuffer buffer(4);

    EXPECT_NE(nullptr, buffer.nextBlock<char>(2));
    EXPECT_EQ(2u, buffer.size());
}

TEST(BigBufferTest, ReturnSameBlockIfNextAllocationFits) {
    BigBuffer buffer(16);

    char* b1 = buffer.nextBlock<char>(8);
    EXPECT_NE(nullptr, b1);

    char* b2 = buffer.nextBlock<char>(4);
    EXPECT_NE(nullptr, b2);

    EXPECT_EQ(b1 + 8, b2);
}

TEST(BigBufferTest, AllocateExactSizeBlockIfLargerThanBlockSize) {
    BigBuffer buffer(16);

    EXPECT_NE(nullptr, buffer.nextBlock<char>(32));
    EXPECT_EQ(32u, buffer.size());
}

TEST(BigBufferTest, AppendAndMoveBlock) {
    BigBuffer buffer(16);

    uint32_t* b1 = buffer.nextBlock<uint32_t>();
    ASSERT_NE(nullptr, b1);
    *b1 = 33;

    {
        BigBuffer buffer2(16);
        b1 = buffer2.nextBlock<uint32_t>();
        ASSERT_NE(nullptr, b1);
        *b1 = 44;

        buffer.appendBuffer(std::move(buffer2));
        EXPECT_EQ(0u, buffer2.size());
        EXPECT_EQ(buffer2.begin(), buffer2.end());
    }

    EXPECT_EQ(2 * sizeof(uint32_t), buffer.size());

    auto b = buffer.begin();
    ASSERT_NE(b, buffer.end());
    ASSERT_EQ(sizeof(uint32_t), b->size);
    ASSERT_EQ(33u, *reinterpret_cast<uint32_t*>(b->buffer.get()));
    ++b;

    ASSERT_NE(b, buffer.end());
    ASSERT_EQ(sizeof(uint32_t), b->size);
    ASSERT_EQ(44u, *reinterpret_cast<uint32_t*>(b->buffer.get()));
    ++b;

    ASSERT_EQ(b, buffer.end());
}

TEST(BigBufferTest, PadAndAlignProperly) {
    BigBuffer buffer(16);

    ASSERT_NE(buffer.nextBlock<char>(2), nullptr);
    ASSERT_EQ(2u, buffer.size());
    buffer.pad(2);
    ASSERT_EQ(4u, buffer.size());
    buffer.align4();
    ASSERT_EQ(4u, buffer.size());
    buffer.pad(2);
    ASSERT_EQ(6u, buffer.size());
    buffer.align4();
    ASSERT_EQ(8u, buffer.size());
}

} // namespace aapt
