/*
 * Copyright (C) 2025 The Android Open Source Project
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

#include <gtest/gtest.h>

#include "hwui/DrawTextFunctor.h"

using namespace android;

namespace {

void testHighContrastInnerTextColor(float originalL, float originalA, float originalB,
                                    float expectedL, float expectedA, float expectedB) {
    uirenderer::Lab color = {originalL, originalA, originalB};
    adjustHighContrastInnerTextColor(&color);
    EXPECT_FLOAT_EQ(color.L, expectedL);
    EXPECT_FLOAT_EQ(color.a, expectedA);
    EXPECT_FLOAT_EQ(color.b, expectedB);
}

TEST(DrawTextFunctorTest, BlackUnaffected) {
    testHighContrastInnerTextColor(0, 0, 0, 0, 0, 0);
}

TEST(DrawTextFunctorTest, WhiteUnaffected) {
    testHighContrastInnerTextColor(100, 0, 0, 100, 0, 0);
}

TEST(DrawTextFunctorTest, DarkGrayPushedToWhite) {
    testHighContrastInnerTextColor(10, 0, 0, 0, 0, 0);
    testHighContrastInnerTextColor(20, 0, 0, 0, 0, 0);
}

TEST(DrawTextFunctorTest, LightGrayPushedToWhite) {
    testHighContrastInnerTextColor(80, 0, 0, 100, 0, 0);
    testHighContrastInnerTextColor(90, 0, 0, 100, 0, 0);
}

TEST(DrawTextFunctorTest, MiddleDarkGrayPushedToDarkGray) {
    testHighContrastInnerTextColor(41, 0, 0, 20, 0, 0);
    testHighContrastInnerTextColor(49, 0, 0, 20, 0, 0);
}

TEST(DrawTextFunctorTest, MiddleLightGrayPushedToLightGray) {
    testHighContrastInnerTextColor(51, 0, 0, 80, 0, 0);
    testHighContrastInnerTextColor(59, 0, 0, 80, 0, 0);
}

TEST(DrawTextFunctorTest, PaleColorTreatedAsGrayscaleAndPushedToWhite) {
    testHighContrastInnerTextColor(75, 5, -5, 100, 0, 0);
    testHighContrastInnerTextColor(85, -6, 8, 100, 0, 0);
}

TEST(DrawTextFunctorTest, PaleColorTreatedAsGrayscaleAndPushedToBlack) {
    testHighContrastInnerTextColor(25, 5, -5, 0, 0, 0);
    testHighContrastInnerTextColor(35, -6, 8, 0, 0, 0);
}

TEST(DrawTextFunctorTest, ColorfulColorIsLightened) {
    testHighContrastInnerTextColor(70, 100, -100, 90, 100, -100);
}

TEST(DrawTextFunctorTest, ColorfulLightColorIsUntouched) {
    testHighContrastInnerTextColor(95, 100, -100, 95, 100, -100);
}

TEST(DrawTextFunctorTest, ColorfulColorIsDarkened) {
    testHighContrastInnerTextColor(30, 100, -100, 20, 100, -100);
}

TEST(DrawTextFunctorTest, ColorfulDarkColorIsUntouched) {
    testHighContrastInnerTextColor(5, 100, -100, 5, 100, -100);
}

}  // namespace
