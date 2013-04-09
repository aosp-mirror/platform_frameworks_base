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

#define LOG_TAG "OpenGLRenderer"

#include "Debug.h"
#include "GammaFontRenderer.h"
#include "Properties.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Utils
///////////////////////////////////////////////////////////////////////////////

static int luminance(const SkPaint* paint) {
    uint32_t c = paint->getColor();
    const int r = (c >> 16) & 0xFF;
    const int g = (c >>  8) & 0xFF;
    const int b = (c      ) & 0xFF;
    return (r * 2 + g * 5 + b) >> 3;
}

///////////////////////////////////////////////////////////////////////////////
// Base class GammaFontRenderer
///////////////////////////////////////////////////////////////////////////////

GammaFontRenderer* GammaFontRenderer::createRenderer() {
    // Choose the best renderer
    char property[PROPERTY_VALUE_MAX];
    if (property_get(PROPERTY_TEXT_GAMMA_METHOD, property, DEFAULT_TEXT_GAMMA_METHOD) > 0) {
        if (!strcasecmp(property, "lookup")) {
            return new LookupGammaFontRenderer();
        } else if (!strcasecmp(property, "shader")) {
            return new ShaderGammaFontRenderer(false);
        } else if (!strcasecmp(property, "shader3")) {
            return new ShaderGammaFontRenderer(true);
        }
    }

    return new Lookup3GammaFontRenderer();
}

GammaFontRenderer::GammaFontRenderer() {
    // Get the renderer properties
    char property[PROPERTY_VALUE_MAX];

    // Get the gamma
    mGamma = DEFAULT_TEXT_GAMMA;
    if (property_get(PROPERTY_TEXT_GAMMA, property, NULL) > 0) {
        INIT_LOGD("  Setting text gamma to %s", property);
        mGamma = atof(property);
    } else {
        INIT_LOGD("  Using default text gamma of %.2f", DEFAULT_TEXT_GAMMA);
    }

    // Get the black gamma threshold
    mBlackThreshold = DEFAULT_TEXT_BLACK_GAMMA_THRESHOLD;
    if (property_get(PROPERTY_TEXT_BLACK_GAMMA_THRESHOLD, property, NULL) > 0) {
        INIT_LOGD("  Setting text black gamma threshold to %s", property);
        mBlackThreshold = atoi(property);
    } else {
        INIT_LOGD("  Using default text black gamma threshold of %d",
                DEFAULT_TEXT_BLACK_GAMMA_THRESHOLD);
    }

    // Get the white gamma threshold
    mWhiteThreshold = DEFAULT_TEXT_WHITE_GAMMA_THRESHOLD;
    if (property_get(PROPERTY_TEXT_WHITE_GAMMA_THRESHOLD, property, NULL) > 0) {
        INIT_LOGD("  Setting text white gamma threshold to %s", property);
        mWhiteThreshold = atoi(property);
    } else {
        INIT_LOGD("  Using default white black gamma threshold of %d",
                DEFAULT_TEXT_WHITE_GAMMA_THRESHOLD);
    }
}

GammaFontRenderer::~GammaFontRenderer() {
}

///////////////////////////////////////////////////////////////////////////////
// Shader-based renderer
///////////////////////////////////////////////////////////////////////////////

ShaderGammaFontRenderer::ShaderGammaFontRenderer(bool multiGamma): GammaFontRenderer() {
    INIT_LOGD("Creating shader gamma font renderer");
    mRenderer = NULL;
    mMultiGamma = multiGamma;
}

void ShaderGammaFontRenderer::describe(ProgramDescription& description,
        const SkPaint* paint) const {
    if (paint->getShader() == NULL) {
        if (mMultiGamma) {
            const int l = luminance(paint);

            if (l <= mBlackThreshold) {
                description.hasGammaCorrection = true;
                description.gamma = mGamma;
            } else if (l >= mWhiteThreshold) {
                description.hasGammaCorrection = true;
                description.gamma = 1.0f / mGamma;
            }
        } else {
            description.hasGammaCorrection = true;
            description.gamma = 1.0f / mGamma;
        }
    }
}

void ShaderGammaFontRenderer::setupProgram(ProgramDescription& description,
        Program* program) const {
    if (description.hasGammaCorrection) {
        glUniform1f(program->getUniform("gamma"), description.gamma);
    }
}

void ShaderGammaFontRenderer::endPrecaching() {
    if (mRenderer) {
        mRenderer->endPrecaching();
    }
}

///////////////////////////////////////////////////////////////////////////////
// Lookup-based renderer
///////////////////////////////////////////////////////////////////////////////

LookupGammaFontRenderer::LookupGammaFontRenderer(): GammaFontRenderer() {
    INIT_LOGD("Creating lookup gamma font renderer");

    // Compute the gamma tables
    const float gamma = 1.0f / mGamma;

    for (uint32_t i = 0; i <= 255; i++) {
        mGammaTable[i] = uint8_t((float)::floor(pow(i / 255.0f, gamma) * 255.0f + 0.5f));
    }

    mRenderer = NULL;
}

void LookupGammaFontRenderer::endPrecaching() {
    if (mRenderer) {
        mRenderer->endPrecaching();
    }
}

///////////////////////////////////////////////////////////////////////////////
// Lookup-based renderer, using 3 different correction tables
///////////////////////////////////////////////////////////////////////////////

Lookup3GammaFontRenderer::Lookup3GammaFontRenderer(): GammaFontRenderer() {
    INIT_LOGD("Creating lookup3 gamma font renderer");

    // Compute the gamma tables
    const float blackGamma = mGamma;
    const float whiteGamma = 1.0f / mGamma;

    for (uint32_t i = 0; i <= 255; i++) {
        const float v = i / 255.0f;
        const float black = pow(v, blackGamma);
        const float white = pow(v, whiteGamma);

        mGammaTable[i] = i;
        mGammaTable[256 + i] = uint8_t((float)::floor(black * 255.0f + 0.5f));
        mGammaTable[512 + i] = uint8_t((float)::floor(white * 255.0f + 0.5f));
    }

    memset(mRenderers, 0, sizeof(FontRenderer*) * kGammaCount);
    memset(mRenderersUsageCount, 0, sizeof(uint32_t) * kGammaCount);
}

Lookup3GammaFontRenderer::~Lookup3GammaFontRenderer() {
    for (int i = 0; i < kGammaCount; i++) {
        delete mRenderers[i];
    }
}

void Lookup3GammaFontRenderer::endPrecaching() {
    for (int i = 0; i < kGammaCount; i++) {
        if (mRenderers[i]) {
            mRenderers[i]->endPrecaching();
        }
    }
}

void Lookup3GammaFontRenderer::clear() {
    for (int i = 0; i < kGammaCount; i++) {
        delete mRenderers[i];
        mRenderers[i] = NULL;
    }
}

void Lookup3GammaFontRenderer::flush() {
    int count = 0;
    int min = -1;
    uint32_t minCount = UINT_MAX;

    for (int i = 0; i < kGammaCount; i++) {
        if (mRenderers[i]) {
            count++;
            if (mRenderersUsageCount[i] < minCount) {
                minCount = mRenderersUsageCount[i];
                min = i;
            }
        }
    }

    if (count <= 1 || min < 0) return;

    delete mRenderers[min];
    mRenderers[min] = NULL;

    // Also eliminate the caches for large glyphs, as they consume significant memory
    for (int i = 0; i < kGammaCount; ++i) {
        if (mRenderers[i]) {
            mRenderers[i]->flushLargeCaches();
        }
    }
}

FontRenderer* Lookup3GammaFontRenderer::getRenderer(Gamma gamma) {
    FontRenderer* renderer = mRenderers[gamma];
    if (!renderer) {
        renderer = new FontRenderer();
        mRenderers[gamma] = renderer;
        renderer->setGammaTable(&mGammaTable[gamma * 256]);
    }
    mRenderersUsageCount[gamma]++;
    return renderer;
}

FontRenderer& Lookup3GammaFontRenderer::getFontRenderer(const SkPaint* paint) {
    if (paint->getShader() == NULL) {
        const int l = luminance(paint);

        if (l <= mBlackThreshold) {
            return *getRenderer(kGammaBlack);
        } else if (l >= mWhiteThreshold) {
            return *getRenderer(kGammaWhite);
        }
    }
    return *getRenderer(kGammaDefault);
}

}; // namespace uirenderer
}; // namespace android
