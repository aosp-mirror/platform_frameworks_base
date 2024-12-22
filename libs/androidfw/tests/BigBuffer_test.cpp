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

#include "androidfw/BigBuffer.h"

#include "gmock/gmock.h"
#include "gtest/gtest.h"

using ::testing::NotNull;

namespace android {

TEST(BigBufferTest, AllocateSingleBlock) {
  BigBuffer buffer(4);

  EXPECT_THAT(buffer.NextBlock<char>(2), NotNull());
  EXPECT_EQ(2u, buffer.size());
}

TEST(BigBufferTest, ReturnSameBlockIfNextAllocationFits) {
  BigBuffer buffer(16);

  char* b1 = buffer.NextBlock<char>(8);
  EXPECT_THAT(b1, NotNull());

  char* b2 = buffer.NextBlock<char>(4);
  EXPECT_THAT(b2, NotNull());

  EXPECT_EQ(b1 + 8, b2);
}

TEST(BigBufferTest, AllocateExactSizeBlockIfLargerThanBlockSize) {
  BigBuffer buffer(16);

  EXPECT_THAT(buffer.NextBlock<char>(32), NotNull());
  EXPECT_EQ(32u, buffer.size());
}

TEST(BigBufferTest, AppendAndMoveBlock) {
  BigBuffer buffer(16);

  uint32_t* b1 = buffer.NextBlock<uint32_t>();
  ASSERT_THAT(b1, NotNull());
  *b1 = 33;

  {
    BigBuffer buffer2(16);
    b1 = buffer2.NextBlock<uint32_t>();
    ASSERT_THAT(b1, NotNull());
    *b1 = 44;

    buffer.AppendBuffer(std::move(buffer2));
    EXPECT_EQ(0u, buffer2.size());  // NOLINT
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

  ASSERT_THAT(buffer.NextBlock<char>(2), NotNull());
  ASSERT_EQ(2u, buffer.size());
  buffer.Pad(2);
  ASSERT_EQ(4u, buffer.size());
  buffer.Align4();
  ASSERT_EQ(4u, buffer.size());
  buffer.Pad(2);
  ASSERT_EQ(6u, buffer.size());
  buffer.Align4();
  ASSERT_EQ(8u, buffer.size());
}

TEST(BigBufferTest, BackUpZeroed) {
  BigBuffer buffer(16);

  auto block = buffer.NextBlock<char>(2);
  ASSERT_TRUE(block != nullptr);
  ASSERT_EQ(2u, buffer.size());
  block[0] = 0x01;
  block[1] = 0x02;
  buffer.BackUp(1);
  ASSERT_EQ(1u, buffer.size());
  auto new_block = buffer.NextBlock<char>(1);
  ASSERT_TRUE(new_block != nullptr);
  ASSERT_EQ(2u, buffer.size());
  ASSERT_EQ(0, *new_block);
}

}  // namespace android
