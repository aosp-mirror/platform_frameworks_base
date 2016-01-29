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
#include "utils/Blur.h"
#include "tests/common/TestUtils.h"

#include <SkBlurDrawLooper.h>
#include <SkPaint.h>

using namespace android;
using namespace android::uirenderer;

RENDERTHREAD_TEST(TextDropShadowCache, addRemove) {
    GammaFontRenderer gammaFontRenderer;
    FontRenderer& fontRenderer = gammaFontRenderer.getFontRenderer();
    TextDropShadowCache cache(5000);
    cache.setFontRenderer(fontRenderer);

    SkPaint paint;
    paint.setLooper(SkBlurDrawLooper::Create((SkColor)0xFFFFFFFF,
            Blur::convertRadiusToSigma(10), 10, 10))->unref();
    std::string msg("This is a test");
    std::unique_ptr<float[]> positions(new float[msg.length()]);
    for (size_t i = 0; i < msg.length(); i++) {
        positions[i] = i * 10.0f;
    }
    fontRenderer.setFont(&paint, SkMatrix::I());
    ShadowTexture* texture = cache.get(&paint, msg.c_str(), msg.length(),
            10.0f, positions.get());
    ASSERT_TRUE(texture);
    ASSERT_FALSE(texture->cleanup);
    ASSERT_EQ((uint32_t) texture->objectSize(), cache.getSize());
    ASSERT_TRUE(cache.getSize());
    cache.clear();
    ASSERT_EQ(cache.getSize(), 0u);
}
