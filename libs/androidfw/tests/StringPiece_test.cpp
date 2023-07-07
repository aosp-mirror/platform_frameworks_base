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

}  // namespace android
