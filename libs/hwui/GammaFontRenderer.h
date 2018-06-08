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

#include "FontRenderer.h"

namespace android {
namespace uirenderer {

class GammaFontRenderer {
public:
    GammaFontRenderer();

    void clear() { mRenderer.reset(nullptr); }

    void flush() {
        if (mRenderer) {
            mRenderer->flushLargeCaches();
        }
    }

    FontRenderer& getFontRenderer() {
        if (!mRenderer) {
            const uint8_t* table = nullptr;
#ifndef ANDROID_ENABLE_LINEAR_BLENDING
            table = &mGammaTable[0];
#endif
            mRenderer.reset(new FontRenderer(table));
        }
        return *mRenderer;
    }

    void dumpMemoryUsage(String8& log) const {
        if (mRenderer) {
            mRenderer->dumpMemoryUsage(log);
        } else {
            log.appendFormat("FontRenderer doesn't exist.\n");
        }
    }

    uint32_t getSize() const { return mRenderer ? mRenderer->getSize() : 0; }

    void endPrecaching();

private:
    std::unique_ptr<FontRenderer> mRenderer;
#ifndef ANDROID_ENABLE_LINEAR_BLENDING
    uint8_t mGammaTable[256];
#endif
};

};  // namespace uirenderer
};  // namespace android

#endif  // ANDROID_HWUI_GAMMA_FONT_RENDERER_H
