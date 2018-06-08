/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "GammaFontRenderer.h"
#include "tests/common/TestUtils.h"

using namespace android::uirenderer;

static bool isZero(uint8_t* data, int size) {
    for (int i = 0; i < size; i++) {
        if (data[i]) return false;
    }
    return true;
}

RENDERTHREAD_OPENGL_PIPELINE_TEST(FontRenderer, renderDropShadow) {
    SkPaint paint;
    paint.setTextSize(10);
    paint.setTextEncoding(SkPaint::kGlyphID_TextEncoding);
    GammaFontRenderer gammaFontRenderer;
    FontRenderer& fontRenderer = gammaFontRenderer.getFontRenderer();
    fontRenderer.setFont(&paint, SkMatrix::I());

    std::vector<glyph_t> glyphs;
    std::vector<float> positions;
    float totalAdvance;
    Rect bounds;
    TestUtils::layoutTextUnscaled(paint, "This is a test", &glyphs, &positions, &totalAdvance,
                                  &bounds);

    for (int radius : {28, 20, 2}) {
        auto result = fontRenderer.renderDropShadow(&paint, glyphs.data(), glyphs.size(), radius,
                                                    positions.data());
        ASSERT_NE(nullptr, result.image);
        EXPECT_FALSE(isZero(result.image, result.width * result.height));
        EXPECT_LE(bounds.getWidth() + radius * 2, (int)result.width);
        EXPECT_LE(bounds.getHeight() + radius * 2, (int)result.height);
        delete result.image;
    }
}
