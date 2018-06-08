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

#include "io/FileStream.h"

#include "android-base/file.h"
#include "android-base/macros.h"
#include "android-base/test_utils.h"

#include "test/Test.h"

using ::android::StringPiece;
using ::testing::Eq;
using ::testing::NotNull;
using ::testing::StrEq;

namespace aapt {
namespace io {

TEST(FileInputStreamTest, NextAndBackup) {
  std::string input = "this is a cool string";
  TemporaryFile file;
  ASSERT_THAT(TEMP_FAILURE_RETRY(write(file.fd, input.c_str(), input.size())), Eq(21));
  lseek64(file.fd, 0, SEEK_SET);

  // Use a small buffer size so that we can call Next() a few times.
  FileInputStream in(file.release(), 10u);
  ASSERT_FALSE(in.HadError());
  EXPECT_THAT(in.ByteCount(), Eq(0u));

  const char* buffer;
  size_t size;
  ASSERT_TRUE(in.Next(reinterpret_cast<const void**>(&buffer), &size)) << in.GetError();
  ASSERT_THAT(size, Eq(10u));
  ASSERT_THAT(buffer, NotNull());
  EXPECT_THAT(in.ByteCount(), Eq(10u));
  EXPECT_THAT(StringPiece(buffer, size), Eq("this is a "));

  ASSERT_TRUE(in.Next(reinterpret_cast<const void**>(&buffer), &size));
  ASSERT_THAT(size, Eq(10u));
  ASSERT_THAT(buffer, NotNull());
  EXPECT_THAT(in.ByteCount(), Eq(20u));
  EXPECT_THAT(StringPiece(buffer, size), Eq("cool strin"));

  in.BackUp(5u);
  EXPECT_THAT(in.ByteCount(), Eq(15u));

  ASSERT_TRUE(in.Next(reinterpret_cast<const void**>(&buffer), &size));
  ASSERT_THAT(size, Eq(5u));
  ASSERT_THAT(buffer, NotNull());
  ASSERT_THAT(in.ByteCount(), Eq(20u));
  EXPECT_THAT(StringPiece(buffer, size), Eq("strin"));

  // Backup 1 more than possible. Should clamp.
  in.BackUp(11u);
  EXPECT_THAT(in.ByteCount(), Eq(10u));

  ASSERT_TRUE(in.Next(reinterpret_cast<const void**>(&buffer), &size));
  ASSERT_THAT(size, Eq(10u));
  ASSERT_THAT(buffer, NotNull());
  ASSERT_THAT(in.ByteCount(), Eq(20u));
  EXPECT_THAT(StringPiece(buffer, size), Eq("cool strin"));

  ASSERT_TRUE(in.Next(reinterpret_cast<const void**>(&buffer), &size));
  ASSERT_THAT(size, Eq(1u));
  ASSERT_THAT(buffer, NotNull());
  ASSERT_THAT(in.ByteCount(), Eq(21u));
  EXPECT_THAT(StringPiece(buffer, size), Eq("g"));

  EXPECT_FALSE(in.Next(reinterpret_cast<const void**>(&buffer), &size));
  EXPECT_FALSE(in.HadError());
}

TEST(FileOutputStreamTest, NextAndBackup) {
  const std::string input = "this is a cool string";

  TemporaryFile file;

  FileOutputStream out(file.fd, 10u);
  ASSERT_FALSE(out.HadError());
  EXPECT_THAT(out.ByteCount(), Eq(0u));

  char* buffer;
  size_t size;
  ASSERT_TRUE(out.Next(reinterpret_cast<void**>(&buffer), &size));
  ASSERT_THAT(size, Eq(10u));
  ASSERT_THAT(buffer, NotNull());
  EXPECT_THAT(out.ByteCount(), Eq(10u));
  memcpy(buffer, input.c_str(), size);

  ASSERT_TRUE(out.Next(reinterpret_cast<void**>(&buffer), &size));
  ASSERT_THAT(size, Eq(10u));
  ASSERT_THAT(buffer, NotNull());
  EXPECT_THAT(out.ByteCount(), Eq(20u));
  memcpy(buffer, input.c_str() + 10u, size);

  ASSERT_TRUE(out.Next(reinterpret_cast<void**>(&buffer), &size));
  ASSERT_THAT(size, Eq(10u));
  ASSERT_THAT(buffer, NotNull());
  EXPECT_THAT(out.ByteCount(), Eq(30u));
  buffer[0] = input[20u];
  out.BackUp(size - 1);
  EXPECT_THAT(out.ByteCount(), Eq(21u));

  ASSERT_TRUE(out.Flush());

  lseek64(file.fd, 0, SEEK_SET);

  std::string actual;
  ASSERT_TRUE(android::base::ReadFdToString(file.fd, &actual));
  EXPECT_THAT(actual, StrEq(input));
}

}  // namespace io
}  // namespace aapt
