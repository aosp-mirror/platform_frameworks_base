/*
 * Copyright (C) 2023 The Android Open Source Project
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

#include "androidfw/ApkParsing.h"

#include "android-base/test_utils.h"

#include "TestHelpers.h"

using ::testing::Eq;
using ::testing::IsNull;
using ::testing::NotNull;

namespace android {
TEST(ApkParsingTest, ValidArm64Path) {
  const char* path = "lib/arm64-v8a/library.so";
  auto lastSlash = util::ValidLibraryPathLastSlash(path, false, false);
  ASSERT_THAT(lastSlash, NotNull());
  ASSERT_THAT(lastSlash, Eq(path + 13));
}

TEST(ApkParsingTest, ValidArm64PathButSuppressed) {
  const char* path = "lib/arm64-v8a/library.so";
  auto lastSlash = util::ValidLibraryPathLastSlash(path, true, false);
  ASSERT_THAT(lastSlash, IsNull());
}

TEST(ApkParsingTest, ValidArm32Path) {
  const char* path = "lib/armeabi-v7a/library.so";
  auto lastSlash = util::ValidLibraryPathLastSlash(path, false, false);
  ASSERT_THAT(lastSlash, NotNull());
  ASSERT_THAT(lastSlash, Eq(path + 15));
}

TEST(ApkParsingTest, InvalidMustStartWithLib) {
  const char* path = "lib/arm64-v8a/random.so";
  auto lastSlash = util::ValidLibraryPathLastSlash(path, false, false);
  ASSERT_THAT(lastSlash, IsNull());
}

TEST(ApkParsingTest, InvalidMustEndInSo) {
  const char* path = "lib/arm64-v8a/library.txt";
  auto lastSlash = util::ValidLibraryPathLastSlash(path, false, false);
  ASSERT_THAT(lastSlash, IsNull());
}

TEST(ApkParsingTest, InvalidCharacter) {
  const char* path = "lib/arm64-v8a/lib#.so";
  auto lastSlash = util::ValidLibraryPathLastSlash(path, false, false);
  ASSERT_THAT(lastSlash, IsNull());
}

TEST(ApkParsingTest, InvalidSubdirectories) {
  const char* path = "lib/arm64-v8a/anything/library.so";
  auto lastSlash = util::ValidLibraryPathLastSlash(path, false, false);
  ASSERT_THAT(lastSlash, IsNull());
}

TEST(ApkParsingTest, InvalidFileAtRoot) {
  const char* path = "lib/library.so";
  auto lastSlash = util::ValidLibraryPathLastSlash(path, false, false);
  ASSERT_THAT(lastSlash, IsNull());
}

TEST(ApkParsingTest, InvalidPrefix) {
  const char* path = "assets/libhello.so";
  auto lastSlash = util::ValidLibraryPathLastSlash(path, false, false);
  ASSERT_THAT(lastSlash, IsNull());
}
}