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
#include "TextDropShadowCache.h"
#include "tests/common/TestUtils.h"
#include "utils/Blur.h"

#include <SkPaint.h>

using namespace android;
using namespace android::uirenderer;

RENDERTHREAD_OPENGL_PIPELINE_TEST(TextDropShadowCache, addRemove) {
    SkPaint paint;
    paint.setTextSize(20);
    paint.setTextEncoding(SkPaint::kGlyphID_TextEncoding);

    GammaFontRenderer gammaFontRenderer;
    FontRenderer& fontRenderer = gammaFontRenderer.getFontRenderer();
    fontRenderer.setFont(&paint, SkMatrix::I());
    TextDropShadowCache cache(MB(5));
    cache.setFontRenderer(fontRenderer);

    std::vector<glyph_t> glyphs;
    std::vector<float> positions;
    float totalAdvance;
    uirenderer::Rect bounds;
    TestUtils::layoutTextUnscaled(paint, "This is a test", &glyphs, &positions, &totalAdvance,
                                  &bounds);
    EXPECT_TRUE(bounds.contains(5, -10, 100, 0)) << "Expect input to be nontrivially sized";

    ShadowTexture* texture = cache.get(&paint, glyphs.data(), glyphs.size(), 10, positions.data());

    ASSERT_TRUE(texture);
    ASSERT_FALSE(texture->cleanup);
    ASSERT_EQ((uint32_t)texture->objectSize(), cache.getSize());
    ASSERT_TRUE(cache.getSize());
    cache.clear();
    ASSERT_EQ(cache.getSize(), 0u);
}
