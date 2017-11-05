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

#include <benchmark/benchmark.h>

#include "GammaFontRenderer.h"
#include "tests/common/TestUtils.h"

#include <SkPaint.h>

using namespace android;
using namespace android::uirenderer;

void BM_FontRenderer_precache_cachehits(benchmark::State& state) {
    TestUtils::runOnRenderThread([&state](renderthread::RenderThread& thread) {
        SkPaint paint;
        paint.setTextSize(20);
        paint.setTextEncoding(SkPaint::kGlyphID_TextEncoding);
        GammaFontRenderer gammaFontRenderer;
        FontRenderer& fontRenderer = gammaFontRenderer.getFontRenderer();
        fontRenderer.setFont(&paint, SkMatrix::I());

        std::vector<glyph_t> glyphs;
        std::vector<float> positions;
        float totalAdvance;
        uirenderer::Rect bounds;
        TestUtils::layoutTextUnscaled(paint, "This is a test", &glyphs, &positions, &totalAdvance,
                                      &bounds);

        fontRenderer.precache(&paint, glyphs.data(), glyphs.size(), SkMatrix::I());

        while (state.KeepRunning()) {
            fontRenderer.precache(&paint, glyphs.data(), glyphs.size(), SkMatrix::I());
        }
    });
}
BENCHMARK(BM_FontRenderer_precache_cachehits);
