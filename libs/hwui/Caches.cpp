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

#include "Caches.h"

#include "GammaFontRenderer.h"
#include "LayerRenderer.h"
#include "Properties.h"
#include "renderstate/RenderState.h"
#include "ShadowTessellator.h"
#include "utils/GLUtils.h"

#include <utils/Log.h>
#include <utils/String8.h>

namespace android {
namespace uirenderer {

Caches* Caches::sInstance = nullptr;

///////////////////////////////////////////////////////////////////////////////
// Macros
///////////////////////////////////////////////////////////////////////////////

#if DEBUG_CACHE_FLUSH
    #define FLUSH_LOGD(...) ALOGD(__VA_ARGS__)
#else
    #define FLUSH_LOGD(...)
#endif

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

Caches::Caches(RenderState& renderState)
        : gradientCache(mExtensions)
        , patchCache(renderState)
        , programCache(mExtensions)
        , dither(*this)
        , mRenderState(&renderState)
        , mInitialized(false) {
    INIT_LOGD("Creating OpenGL renderer caches");
    init();
    initFont();
    initConstraints();
    initStaticProperties();
    initExtensions();
}

bool Caches::init() {
    if (mInitialized) return false;

    ATRACE_NAME("Caches::init");

    mRegionMesh = nullptr;
    mProgram = nullptr;

    mFunctorsCount = 0;

    patchCache.init();

    mInitialized = true;

    mPixelBufferState = new PixelBufferState();
    mTextureState = new TextureState();

    return true;
}

void Caches::initFont() {
    fontRenderer = GammaFontRenderer::createRenderer();
}

void Caches::initExtensions() {
    if (mExtensions.hasDebugMarker()) {
        eventMark = glInsertEventMarkerEXT;

        startMark = glPushGroupMarkerEXT;
        endMark = glPopGroupMarkerEXT;
    } else {
        eventMark = eventMarkNull;
        startMark = startMarkNull;
        endMark = endMarkNull;
    }
}

void Caches::initConstraints() {
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTextureSize);
}

void Caches::initStaticProperties() {
    gpuPixelBuffersEnabled = false;

    // OpenGL ES 3.0+ specific features
    if (mExtensions.hasPixelBufferObjects()) {
        char property[PROPERTY_VALUE_MAX];
        if (property_get(PROPERTY_ENABLE_GPU_PIXEL_BUFFERS, property, "true") > 0) {
            gpuPixelBuffersEnabled = !strcmp(property, "true");
        }
    }
}

void Caches::terminate() {
    if (!mInitialized) return;
    mRegionMesh.reset(nullptr);

    fboCache.clear();

    programCache.clear();
    mProgram = nullptr;

    patchCache.clear();

    clearGarbage();

    delete mPixelBufferState;
    mPixelBufferState = nullptr;
    delete mTextureState;
    mTextureState = nullptr;
    mInitialized = false;
}

void Caches::setProgram(const ProgramDescription& description) {
    setProgram(programCache.get(description));
}

void Caches::setProgram(Program* program) {
    if (!program || !program->isInUse()) {
        if (mProgram) {
            mProgram->remove();
        }
        if (program) {
            program->use();
        }
        mProgram = program;
    }
}

///////////////////////////////////////////////////////////////////////////////
// Debug
///////////////////////////////////////////////////////////////////////////////

uint32_t Caches::getOverdrawColor(uint32_t amount) const {
    static uint32_t sOverdrawColors[2][4] = {
            { 0x2f0000ff, 0x2f00ff00, 0x3fff0000, 0x7fff0000 },
            { 0x2f0000ff, 0x4fffff00, 0x5fff8ad8, 0x7fff0000 }
    };
    if (amount < 1) amount = 1;
    if (amount > 4) amount = 4;

    int overdrawColorIndex = static_cast<int>(Properties::overdrawColorSet);
    return sOverdrawColors[overdrawColorIndex][amount - 1];
}

void Caches::dumpMemoryUsage() {
    String8 stringLog;
    dumpMemoryUsage(stringLog);
    ALOGD("%s", stringLog.string());
}

void Caches::dumpMemoryUsage(String8 &log) {
    uint32_t total = 0;
    log.appendFormat("Current memory usage / total memory usage (bytes):\n");
    log.appendFormat("  TextureCache         %8d / %8d\n",
            textureCache.getSize(), textureCache.getMaxSize());
    log.appendFormat("  LayerCache           %8d / %8d (numLayers = %zu)\n",
            layerCache.getSize(), layerCache.getMaxSize(), layerCache.getCount());
    if (mRenderState) {
        int memused = 0;
        for (std::set<Layer*>::iterator it = mRenderState->mActiveLayers.begin();
                it != mRenderState->mActiveLayers.end(); it++) {
            const Layer* layer = *it;
            log.appendFormat("    Layer size %dx%d; isTextureLayer()=%d; texid=%u fbo=%u; refs=%d\n",
                    layer->getWidth(), layer->getHeight(),
                    layer->isTextureLayer(), layer->getTextureId(),
                    layer->getFbo(), layer->getStrongCount());
            memused += layer->getWidth() * layer->getHeight() * 4;
        }
        log.appendFormat("  Layers total   %8d (numLayers = %zu)\n",
                memused, mRenderState->mActiveLayers.size());
        total += memused;
    }
    log.appendFormat("  RenderBufferCache    %8d / %8d\n",
            renderBufferCache.getSize(), renderBufferCache.getMaxSize());
    log.appendFormat("  GradientCache        %8d / %8d\n",
            gradientCache.getSize(), gradientCache.getMaxSize());
    log.appendFormat("  PathCache            %8d / %8d\n",
            pathCache.getSize(), pathCache.getMaxSize());
    log.appendFormat("  TessellationCache    %8d / %8d\n",
            tessellationCache.getSize(), tessellationCache.getMaxSize());
    log.appendFormat("  TextDropShadowCache  %8d / %8d\n", dropShadowCache.getSize(),
            dropShadowCache.getMaxSize());
    log.appendFormat("  PatchCache           %8d / %8d\n",
            patchCache.getSize(), patchCache.getMaxSize());
    for (uint32_t i = 0; i < fontRenderer->getFontRendererCount(); i++) {
        const uint32_t sizeA8 = fontRenderer->getFontRendererSize(i, GL_ALPHA);
        const uint32_t sizeRGBA = fontRenderer->getFontRendererSize(i, GL_RGBA);
        log.appendFormat("  FontRenderer %d A8    %8d / %8d\n", i, sizeA8, sizeA8);
        log.appendFormat("  FontRenderer %d RGBA  %8d / %8d\n", i, sizeRGBA, sizeRGBA);
        log.appendFormat("  FontRenderer %d total %8d / %8d\n", i, sizeA8 + sizeRGBA,
                sizeA8 + sizeRGBA);
    }
    log.appendFormat("Other:\n");
    log.appendFormat("  FboCache             %8d / %8d\n",
            fboCache.getSize(), fboCache.getMaxSize());

    total += textureCache.getSize();
    total += renderBufferCache.getSize();
    total += gradientCache.getSize();
    total += pathCache.getSize();
    total += tessellationCache.getSize();
    total += dropShadowCache.getSize();
    total += patchCache.getSize();
    for (uint32_t i = 0; i < fontRenderer->getFontRendererCount(); i++) {
        total += fontRenderer->getFontRendererSize(i, GL_ALPHA);
        total += fontRenderer->getFontRendererSize(i, GL_RGBA);
    }

    log.appendFormat("Total memory usage:\n");
    log.appendFormat("  %d bytes, %.2f MB\n", total, total / 1024.0f / 1024.0f);
}

///////////////////////////////////////////////////////////////////////////////
// Memory management
///////////////////////////////////////////////////////////////////////////////

void Caches::clearGarbage() {
    textureCache.clearGarbage();
    pathCache.clearGarbage();
    patchCache.clearGarbage();
}

void Caches::flush(FlushMode mode) {
    FLUSH_LOGD("Flushing caches (mode %d)", mode);

    switch (mode) {
        case kFlushMode_Full:
            textureCache.clear();
            patchCache.clear();
            dropShadowCache.clear();
            gradientCache.clear();
            fontRenderer->clear();
            fboCache.clear();
            dither.clear();
            // fall through
        case kFlushMode_Moderate:
            fontRenderer->flush();
            textureCache.flush();
            pathCache.clear();
            tessellationCache.clear();
            // fall through
        case kFlushMode_Layers:
            layerCache.clear();
            renderBufferCache.clear();
            break;
    }

    clearGarbage();
    glFinish();
    // Errors during cleanup should be considered non-fatal, dump them and
    // and move on. TODO: All errors or just errors like bad surface?
    GLUtils::dumpGLErrors();
}

///////////////////////////////////////////////////////////////////////////////
// Tiling
///////////////////////////////////////////////////////////////////////////////

void Caches::startTiling(GLuint x, GLuint y, GLuint width, GLuint height, bool discard) {
    if (mExtensions.hasTiledRendering() && !Properties::debugOverdraw) {
        glStartTilingQCOM(x, y, width, height, (discard ? GL_NONE : GL_COLOR_BUFFER_BIT0_QCOM));
    }
}

void Caches::endTiling() {
    if (mExtensions.hasTiledRendering() && !Properties::debugOverdraw) {
        glEndTilingQCOM(GL_COLOR_BUFFER_BIT0_QCOM);
    }
}

bool Caches::hasRegisteredFunctors() {
    return mFunctorsCount > 0;
}

void Caches::registerFunctors(uint32_t functorCount) {
    mFunctorsCount += functorCount;
}

void Caches::unregisterFunctors(uint32_t functorCount) {
    if (functorCount > mFunctorsCount) {
        mFunctorsCount = 0;
    } else {
        mFunctorsCount -= functorCount;
    }
}

///////////////////////////////////////////////////////////////////////////////
// Regions
///////////////////////////////////////////////////////////////////////////////

TextureVertex* Caches::getRegionMesh() {
    // Create the mesh, 2 triangles and 4 vertices per rectangle in the region
    if (!mRegionMesh) {
        mRegionMesh.reset(new TextureVertex[kMaxNumberOfQuads * 4]);
    }

    return mRegionMesh.get();
}

///////////////////////////////////////////////////////////////////////////////
// Temporary Properties
///////////////////////////////////////////////////////////////////////////////

}; // namespace uirenderer
}; // namespace android
