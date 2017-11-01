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

#include "androidfw/StringPiece.h"

#include <algorithm>
#include <string>
#include <vector>

#include "TestHelpers.h"

namespace android {

TEST(StringPieceTest, CompareNonNullTerminatedPiece) {
  StringPiece a("hello world", 5);
  StringPiece b("hello moon", 5);
  EXPECT_EQ(a, b);

  StringPiece16 a16(u"hello world", 5);
  StringPiece16 b16(u"hello moon", 5);
  EXPECT_EQ(a16, b16);
}

TEST(StringPieceTest, PiecesHaveCorrectSortOrder) {
  std::string testing("testing");
  std::string banana("banana");
  std::string car("car");

  EXPECT_TRUE(StringPiece(testing) > banana);
  EXPECT_TRUE(StringPiece(testing) > car);
  EXPECT_TRUE(StringPiece(banana) < testing);
  EXPECT_TRUE(StringPiece(banana) < car);
  EXPECT_TRUE(StringPiece(car) < testing);
  EXPECT_TRUE(StringPiece(car) > banana);
}

TEST(StringPieceTest, PiecesHaveCorrectSortOrderUtf8) {
  std::string testing("testing");
  std::string banana("banana");
  std::string car("car");

  EXPECT_TRUE(StringPiece(testing) > banana);
  EXPECT_TRUE(StringPiece(testing) > car);
  EXPECT_TRUE(StringPiece(banana) < testing);
  EXPECT_TRUE(StringPiece(banana) < car);
  EXPECT_TRUE(StringPiece(car) < testing);
  EXPECT_TRUE(StringPiece(car) > banana);
}

TEST(StringPieceTest, ContainsOtherStringPiece) {
  StringPiece text("I am a leaf on the wind.");
  StringPiece start_needle("I am");
  StringPiece end_needle("wind.");
  StringPiece middle_needle("leaf");
  StringPiece empty_needle("");
  StringPiece missing_needle("soar");
  StringPiece long_needle("This string is longer than the text.");

  EXPECT_TRUE(text.contains(start_needle));
  EXPECT_TRUE(text.contains(end_needle));
  EXPECT_TRUE(text.contains(middle_needle));
  EXPECT_TRUE(text.contains(empty_needle));
  EXPECT_FALSE(text.contains(missing_needle));
  EXPECT_FALSE(text.contains(long_needle));

  StringPiece16 text16(u"I am a leaf on the wind.");
  StringPiece16 start_needle16(u"I am");
  StringPiece16 end_needle16(u"wind.");
  StringPiece16 middle_needle16(u"leaf");
  StringPiece16 empty_needle16(u"");
  StringPiece16 missing_needle16(u"soar");
  StringPiece16 long_needle16(u"This string is longer than the text.");

  EXPECT_TRUE(text16.contains(start_needle16));
  EXPECT_TRUE(text16.contains(end_needle16));
  EXPECT_TRUE(text16.contains(middle_needle16));
  EXPECT_TRUE(text16.contains(empty_needle16));
  EXPECT_FALSE(text16.contains(missing_needle16));
  EXPECT_FALSE(text16.contains(long_needle16));
}

}  // namespace android
