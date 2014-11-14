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

#include <algorithm>
#include <gtest/gtest.h>
#include <string>
#include <vector>

#include "StringPiece.h"

namespace aapt {

TEST(StringPieceTest, CompareNonNullTerminatedPiece) {
    StringPiece a("hello world", 5);
    StringPiece b("hello moon", 5);
    EXPECT_EQ(a, b);

    StringPiece16 a16(u"hello world", 5);
    StringPiece16 b16(u"hello moon", 5);
    EXPECT_EQ(a16, b16);
}

TEST(StringPieceTest, PiecesHaveCorrectSortOrder) {
    std::u16string testing(u"testing");
    std::u16string banana(u"banana");
    std::u16string car(u"car");

    EXPECT_TRUE(StringPiece16(testing) > banana);
    EXPECT_TRUE(StringPiece16(testing) > car);
    EXPECT_TRUE(StringPiece16(banana) < testing);
    EXPECT_TRUE(StringPiece16(banana) < car);
    EXPECT_TRUE(StringPiece16(car) < testing);
    EXPECT_TRUE(StringPiece16(car) > banana);
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

} // namespace aapt
