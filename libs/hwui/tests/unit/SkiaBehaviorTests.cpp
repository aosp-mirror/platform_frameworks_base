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

#include <SkColorMatrixFilter.h>
#include <SkColorSpace.h>
#include <SkImagePriv.h>
#include <SkPathOps.h>
#include <SkShader.h>
#include <gtest/gtest.h>

using namespace android;
using namespace android::uirenderer;

SkBitmap createSkBitmap(int width, int height) {
    SkBitmap bitmap;
    SkImageInfo info = SkImageInfo::Make(width, height, kN32_SkColorType, kPremul_SkAlphaType);
    bitmap.setInfo(info);
    bitmap.allocPixels(info);
    return bitmap;
}

TEST(SkiaBehavior, genIds) {
    SkBitmap bitmap = createSkBitmap(100, 100);
    uint32_t genId = bitmap.getGenerationID();
    bitmap.notifyPixelsChanged();
    EXPECT_NE(genId, bitmap.getGenerationID());
}

TEST(SkiaBehavior, lightingColorFilter_simplify) {
    {
        sk_sp<SkColorFilter> filter(SkColorMatrixFilter::MakeLightingFilter(0x11223344, 0));

        SkColor observedColor;
        SkBlendMode observedMode;
        ASSERT_TRUE(filter->asAColorMode(&observedColor, &observedMode));
        EXPECT_EQ(0xFF223344, observedColor);
        EXPECT_EQ(SkBlendMode::kModulate, observedMode);
    }

    {
        sk_sp<SkColorFilter> failFilter(SkColorMatrixFilter::MakeLightingFilter(0x11223344, 0x1));
        EXPECT_FALSE(failFilter->asAColorMode(nullptr, nullptr));
    }
}

TEST(SkiaBehavior, porterDuffCreateIsCached) {
    SkPaint paint;
    paint.setBlendMode(SkBlendMode::kOverlay);
    auto expected = paint.asBlendMode();
    paint.setBlendMode(SkBlendMode::kClear);
    ASSERT_NE(expected, paint.asBlendMode());
    paint.setBlendMode(SkBlendMode::kOverlay);
    ASSERT_EQ(expected, paint.asBlendMode());
}

TEST(SkiaBehavior, pathIntersection) {
    SkPath p0, p1, result;
    p0.addRect(SkRect::MakeXYWH(-5.0f, 0.0f, 1080.0f, 242.0f));
    p1.addRect(SkRect::MakeXYWH(0.0f, 0.0f, 1080.0f, 242.0f));
    Op(p0, p1, kIntersect_SkPathOp, &result);
    SkRect resultRect;
    ASSERT_TRUE(result.isRect(&resultRect));
    ASSERT_EQ(SkRect::MakeXYWH(0.0f, 0.0f, 1075.0f, 242.0f), resultRect);
}

TEST(SkiaBehavior, srgbColorSpaceIsSingleton) {
    sk_sp<SkColorSpace> sRGB1 = SkColorSpace::MakeSRGB();
    sk_sp<SkColorSpace> sRGB2 = SkColorSpace::MakeSRGB();
    ASSERT_EQ(sRGB1.get(), sRGB2.get());
}

