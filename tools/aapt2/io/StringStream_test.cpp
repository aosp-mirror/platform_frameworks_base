/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "io/StringStream.h"

#include "io/Util.h"

#include "test/Test.h"

using ::android::StringPiece;
using ::testing::Eq;
using ::testing::NotNull;
using ::testing::StrEq;

namespace aapt {
namespace io {

TEST(StringInputStreamTest, OneCallToNextShouldReturnEntireBuffer) {
  constexpr const size_t kCount = 1000;
  std::string input;
  input.resize(kCount, 0x7f);
  input[0] = 0x00;
  input[kCount - 1] = 0xff;
  StringInputStream in(input);

  const char* buffer;
  size_t size;
  ASSERT_TRUE(in.Next(reinterpret_cast<const void**>(&buffer), &size));
  ASSERT_THAT(size, Eq(kCount));
  ASSERT_THAT(buffer, NotNull());

  EXPECT_THAT(buffer[0], Eq(0x00));
  EXPECT_THAT(buffer[kCount - 1], Eq('\xff'));

  EXPECT_FALSE(in.Next(reinterpret_cast<const void**>(&buffer), &size));
  EXPECT_FALSE(in.HadError());
}

TEST(StringInputStreamTest, BackUp) {
  std::string input = "hello this is a string";
  StringInputStream in(input);

  const char* buffer;
  size_t size;
  ASSERT_TRUE(in.Next(reinterpret_cast<const void**>(&buffer), &size));
  ASSERT_THAT(size, Eq(input.size()));
  ASSERT_THAT(buffer, NotNull());
  EXPECT_THAT(in.ByteCount(), Eq(input.size()));

  in.BackUp(6u);
  EXPECT_THAT(in.ByteCount(), Eq(input.size() - 6u));

  ASSERT_TRUE(in.Next(reinterpret_cast<const void**>(&buffer), &size));
  ASSERT_THAT(size, Eq(6u));
  ASSERT_THAT(buffer, NotNull());
  ASSERT_THAT(buffer, StrEq("string"));
  EXPECT_THAT(in.ByteCount(), Eq(input.size()));
}

TEST(StringOutputStreamTest, NextAndBackUp) {
  std::string input = "hello this is a string";
  std::string output;

  StringInputStream in(input);
  StringOutputStream out(&output, 10u);
  ASSERT_TRUE(Copy(&out, &in));
  out.Flush();
  EXPECT_THAT(output, StrEq(input));
}

}  // namespace io
}  // namespace aapt
