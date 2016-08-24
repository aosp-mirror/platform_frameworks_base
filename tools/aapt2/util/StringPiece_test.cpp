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

#include "util/StringPiece.h"

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

TEST(StringPieceTest, ContainsOtherStringPiece) {
    StringPiece text("I am a leaf on the wind.");
    StringPiece startNeedle("I am");
    StringPiece endNeedle("wind.");
    StringPiece middleNeedle("leaf");
    StringPiece emptyNeedle("");
    StringPiece missingNeedle("soar");
    StringPiece longNeedle("This string is longer than the text.");

    EXPECT_TRUE(text.contains(startNeedle));
    EXPECT_TRUE(text.contains(endNeedle));
    EXPECT_TRUE(text.contains(middleNeedle));
    EXPECT_TRUE(text.contains(emptyNeedle));
    EXPECT_FALSE(text.contains(missingNeedle));
    EXPECT_FALSE(text.contains(longNeedle));

    StringPiece16 text16(u"I am a leaf on the wind.");
    StringPiece16 startNeedle16(u"I am");
    StringPiece16 endNeedle16(u"wind.");
    StringPiece16 middleNeedle16(u"leaf");
    StringPiece16 emptyNeedle16(u"");
    StringPiece16 missingNeedle16(u"soar");
    StringPiece16 longNeedle16(u"This string is longer than the text.");

    EXPECT_TRUE(text16.contains(startNeedle16));
    EXPECT_TRUE(text16.contains(endNeedle16));
    EXPECT_TRUE(text16.contains(middleNeedle16));
    EXPECT_TRUE(text16.contains(emptyNeedle16));
    EXPECT_FALSE(text16.contains(missingNeedle16));
    EXPECT_FALSE(text16.contains(longNeedle16));
}

} // namespace aapt
