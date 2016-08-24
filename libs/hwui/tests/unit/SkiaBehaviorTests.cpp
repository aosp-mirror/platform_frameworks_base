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

#include "tests/common/TestUtils.h"

#include <gtest/gtest.h>
#include <SkShader.h>
#include <SkColorMatrixFilter.h>

using namespace android;
using namespace android::uirenderer;

/**
 * 1x1 bitmaps must not be optimized into solid color shaders, since HWUI can't
 * compose/render color shaders
 */
TEST(SkiaBehavior, CreateBitmapShader1x1) {
    SkBitmap origBitmap = TestUtils::createSkBitmap(1, 1);
    SkAutoTUnref<SkShader> s(SkShader::CreateBitmapShader(
            origBitmap,
            SkShader::kClamp_TileMode,
            SkShader::kRepeat_TileMode));

    SkBitmap bitmap;
    SkShader::TileMode xy[2];
    ASSERT_TRUE(s->isABitmap(&bitmap, nullptr, xy))
        << "1x1 bitmap shader must query as bitmap shader";
    EXPECT_EQ(SkShader::kClamp_TileMode, xy[0]);
    EXPECT_EQ(SkShader::kRepeat_TileMode, xy[1]);
    EXPECT_EQ(origBitmap.pixelRef(), bitmap.pixelRef());
}

TEST(SkiaBehavior, genIds) {
    SkBitmap bitmap = TestUtils::createSkBitmap(100, 100);
    uint32_t genId = bitmap.getGenerationID();
    bitmap.notifyPixelsChanged();
    EXPECT_NE(genId, bitmap.getGenerationID());
}

TEST(SkiaBehavior, lightingColorFilter_simplify) {
    {
        SkAutoTUnref<SkColorFilter> filter(
                SkColorMatrixFilter::CreateLightingFilter(0x11223344, 0));

        SkColor observedColor;
        SkXfermode::Mode observedMode;
        ASSERT_TRUE(filter->asColorMode(&observedColor, &observedMode));
        EXPECT_EQ(0xFF223344, observedColor);
        EXPECT_EQ(SkXfermode::Mode::kModulate_Mode, observedMode);
    }

    {
        SkAutoTUnref<SkColorFilter> failFilter(
                SkColorMatrixFilter::CreateLightingFilter(0x11223344, 0x1));
        EXPECT_FALSE(failFilter->asColorMode(nullptr, nullptr));
    }
}
