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
#include "Program.h"

namespace android {
namespace uirenderer {

class GammaFontRenderer {
public:
    virtual ~GammaFontRenderer();

    virtual void clear() = 0;
    virtual void flush() = 0;

    virtual FontRenderer& getFontRenderer(const SkPaint* paint) = 0;

    virtual uint32_t getFontRendererCount() const = 0;
    virtual uint32_t getFontRendererSize(uint32_t fontRenderer, GLenum format) const = 0;

    virtual void describe(ProgramDescription& description, const SkPaint* paint) const = 0;
    virtual void setupProgram(ProgramDescription& description, Program* program) const = 0;

    virtual void endPrecaching() = 0;

    static GammaFontRenderer* createRenderer();

protected:
    GammaFontRenderer();

    int mBlackThreshold;
    int mWhiteThreshold;

    float mGamma;
};

class ShaderGammaFontRenderer: public GammaFontRenderer {
public:
    ~ShaderGammaFontRenderer() {
        delete mRenderer;
    }

    void clear() {
        delete mRenderer;
        mRenderer = NULL;
    }

    void flush() {
        if (mRenderer) {
            mRenderer->flushLargeCaches();
        }
    }

    FontRenderer& getFontRenderer(const SkPaint* paint) {
        if (!mRenderer) {
            mRenderer = new FontRenderer;
        }
        return *mRenderer;
    }

    uint32_t getFontRendererCount() const {
        return 1;
    }

    uint32_t getFontRendererSize(uint32_t fontRenderer, GLenum format) const {
        return mRenderer ? mRenderer->getCacheSize(format) : 0;
    }

    void describe(ProgramDescription& description, const SkPaint* paint) const;
    void setupProgram(ProgramDescription& description, Program* program) const;

    void endPrecaching();

private:
    ShaderGammaFontRenderer(bool multiGamma);

    FontRenderer* mRenderer;
    bool mMultiGamma;

    friend class GammaFontRenderer;
};

class LookupGammaFontRenderer: public GammaFontRenderer {
public:
    ~LookupGammaFontRenderer() {
        delete mRenderer;
    }

    void clear() {
        delete mRenderer;
        mRenderer = NULL;
    }

    void flush() {
        if (mRenderer) {
            mRenderer->flushLargeCaches();
        }
    }

    FontRenderer& getFontRenderer(const SkPaint* paint) {
        if (!mRenderer) {
            mRenderer = new FontRenderer;
            mRenderer->setGammaTable(&mGammaTable[0]);
        }
        return *mRenderer;
    }

    uint32_t getFontRendererCount() const {
        return 1;
    }

    uint32_t getFontRendererSize(uint32_t fontRenderer, GLenum format) const {
        return mRenderer ? mRenderer->getCacheSize(format) : 0;
    }

    void describe(ProgramDescription& description, const SkPaint* paint) const {
    }

    void setupProgram(ProgramDescription& description, Program* program) const {
    }

    void endPrecaching();

private:
    LookupGammaFontRenderer();

    FontRenderer* mRenderer;
    uint8_t mGammaTable[256];

    friend class GammaFontRenderer;
};

class Lookup3GammaFontRenderer: public GammaFontRenderer {
public:
    ~Lookup3GammaFontRenderer();

    void clear();
    void flush();

    FontRenderer& getFontRenderer(const SkPaint* paint);

    uint32_t getFontRendererCount() const {
        return kGammaCount;
    }

    uint32_t getFontRendererSize(uint32_t fontRenderer, GLenum format) const {
        if (fontRenderer >= kGammaCount) return 0;

        FontRenderer* renderer = mRenderers[fontRenderer];
        if (!renderer) return 0;

        return renderer->getCacheSize(format);
    }

    void describe(ProgramDescription& description, const SkPaint* paint) const {
    }

    void setupProgram(ProgramDescription& description, Program* program) const {
    }

    void endPrecaching();

private:
    Lookup3GammaFontRenderer();

    enum Gamma {
        kGammaDefault = 0,
        kGammaBlack = 1,
        kGammaWhite = 2,
        kGammaCount = 3
    };

    FontRenderer* getRenderer(Gamma gamma);

    uint32_t mRenderersUsageCount[kGammaCount];
    FontRenderer* mRenderers[kGammaCount];

    uint8_t mGammaTable[256 * kGammaCount];

    friend class GammaFontRenderer;
};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_GAMMA_FONT_RENDERER_H
