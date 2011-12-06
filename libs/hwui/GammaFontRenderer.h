/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef ANDROID_HWUI_GAMMA_FONT_RENDERER_H
#define ANDROID_HWUI_GAMMA_FONT_RENDERER_H

#include <SkPaint.h>

#include "FontRenderer.h"

namespace android {
namespace uirenderer {

struct GammaFontRenderer {
    GammaFontRenderer();
    ~GammaFontRenderer();

    enum Gamma {
        kGammaDefault = 0,
        kGammaBlack = 1,
        kGammaWhite = 2,
        kGammaCount = 3
    };

    void clear();
    void flush();

    FontRenderer& getFontRenderer(const SkPaint* paint);

    uint32_t getFontRendererCount() const {
        return kGammaCount;
    }

    uint32_t getFontRendererSize(uint32_t fontRenderer) const {
        if (fontRenderer >= kGammaCount) return 0;

        FontRenderer* renderer = mRenderers[fontRenderer];
        if (!renderer) return 0;

        return renderer->getCacheSize();
    }

private:
    FontRenderer* getRenderer(Gamma gamma);

    uint32_t mRenderersUsageCount[kGammaCount];
    FontRenderer* mRenderers[kGammaCount];

    int mBlackThreshold;
    int mWhiteThreshold;

    uint8_t mGammaTable[256 * kGammaCount];
};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_GAMMA_FONT_RENDERER_H
