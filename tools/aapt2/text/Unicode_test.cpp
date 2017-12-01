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

#include "text/Unicode.h"

#include "test/Test.h"

using ::testing::Each;
using ::testing::Eq;
using ::testing::ResultOf;

namespace aapt {
namespace text {

TEST(UnicodeTest, IsXidStart) {
  std::u32string valid_input = U"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZˮø";
  EXPECT_THAT(valid_input, Each(ResultOf(IsXidStart, Eq(true))));

  std::u32string invalid_input = U"$;\'/<>+=-.{}[]()\\|?@#%^&*!~`\",1234567890_";
  EXPECT_THAT(invalid_input, Each(ResultOf(IsXidStart, Eq(false))));
}

TEST(UnicodeTest, IsXidContinue) {
  std::u32string valid_input = U"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890_ˮø";
  EXPECT_THAT(valid_input, Each(ResultOf(IsXidContinue, Eq(true))));

  std::u32string invalid_input = U"$;\'/<>+=-.{}[]()\\|?@#%^&*!~`\",";
  EXPECT_THAT(invalid_input, Each(ResultOf(IsXidContinue, Eq(false))));
}

TEST(UnicodeTest, IsJavaIdentifier) {
  EXPECT_TRUE(IsJavaIdentifier("FøøBar_12"));
  EXPECT_TRUE(IsJavaIdentifier("Føø$Bar"));
  EXPECT_TRUE(IsJavaIdentifier("_FøøBar"));
  EXPECT_TRUE(IsJavaIdentifier("$Føø$Bar"));

  EXPECT_FALSE(IsJavaIdentifier("12FøøBar"));
  EXPECT_FALSE(IsJavaIdentifier(".Hello"));
}

TEST(UnicodeTest, IsValidResourceEntryName) {
  EXPECT_TRUE(IsJavaIdentifier("FøøBar"));
  EXPECT_TRUE(IsValidResourceEntryName("FøøBar_12"));
  EXPECT_TRUE(IsValidResourceEntryName("Føø.Bar"));
  EXPECT_TRUE(IsValidResourceEntryName("Føø-Bar"));
  EXPECT_TRUE(IsValidResourceEntryName("_FøøBar"));

  EXPECT_FALSE(IsValidResourceEntryName("12FøøBar"));
  EXPECT_FALSE(IsValidResourceEntryName("Føø$Bar"));
  EXPECT_FALSE(IsValidResourceEntryName("Føø/Bar"));
  EXPECT_FALSE(IsValidResourceEntryName("Føø:Bar"));
  EXPECT_FALSE(IsValidResourceEntryName("Føø;Bar"));
  EXPECT_FALSE(IsValidResourceEntryName("0_resource_name_obfuscated"));
}

}  // namespace text
}  // namespace aapt
