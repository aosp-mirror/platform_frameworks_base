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

#include "DisplayListRenderer.h"
#include "GammaFontRenderer.h"
#include "LayerRenderer.h"
#include "Properties.h"
#include "renderstate/RenderState.h"
#include "ShadowTessellator.h"

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
    initProperties();
    initStaticProperties();
    initExtensions();
    initTempProperties();

    mDebugLevel = readDebugLevel();
    ALOGD_IF(mDebugLevel != kDebugDisabled,
            "Enabling debug mode %d", mDebugLevel);
}

bool Caches::init() {
    if (mInitialized) return false;

    ATRACE_NAME("Caches::init");

    mRegionMesh = nullptr;
    mProgram = nullptr;

    mFunctorsCount = 0;

    debugLayersUpdates = false;
    debugOverdraw = false;
    debugStencilClip = kStencilHide;

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

    if (mExtensions.hasDebugLabel() && (drawDeferDisabled || drawReorderDisabled)) {
        setLabel = glLabelObjectEXT;
        getLabel = glGetObjectLabelEXT;
    } else {
        setLabel = setLabelNull;
        getLabel = getLabelNull;
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

bool Caches::initProperties() {
    bool prevDebugLayersUpdates = debugLayersUpdates;
    bool prevDebugOverdraw = debugOverdraw;
    StencilClipDebug prevDebugStencilClip = debugStencilClip;

    char property[PROPERTY_VALUE_MAX];
    if (property_get(PROPERTY_DEBUG_LAYERS_UPDATES, property, nullptr) > 0) {
        INIT_LOGD("  Layers updates debug enabled: %s", property);
        debugLayersUpdates = !strcmp(property, "true");
    } else {
        debugLayersUpdates = false;
    }

    debugOverdraw = false;
    if (property_get(PROPERTY_DEBUG_OVERDRAW, property, nullptr) > 0) {
        INIT_LOGD("  Overdraw debug enabled: %s", property);
        if (!strcmp(property, "show")) {
            debugOverdraw = true;
            mOverdrawDebugColorSet = kColorSet_Default;
        } else if (!strcmp(property, "show_deuteranomaly")) {
            debugOverdraw = true;
            mOverdrawDebugColorSet = kColorSet_Deuteranomaly;
        }
    }

    // See Properties.h for valid values
    if (property_get(PROPERTY_DEBUG_STENCIL_CLIP, property, nullptr) > 0) {
        INIT_LOGD("  Stencil clip debug enabled: %s", property);
        if (!strcmp(property, "hide")) {
            debugStencilClip = kStencilHide;
        } else if (!strcmp(property, "highlight")) {
            debugStencilClip = kStencilShowHighlight;
        } else if (!strcmp(property, "region")) {
            debugStencilClip = kStencilShowRegion;
        }
    } else {
        debugStencilClip = kStencilHide;
    }

    if (property_get(PROPERTY_DISABLE_DRAW_DEFER, property, "false")) {
        drawDeferDisabled = !strcasecmp(property, "true");
        INIT_LOGD("  Draw defer %s", drawDeferDisabled ? "disabled" : "enabled");
    } else {
        drawDeferDisabled = false;
        INIT_LOGD("  Draw defer enabled");
    }

    if (property_get(PROPERTY_DISABLE_DRAW_REORDER, property, "false")) {
        drawReorderDisabled = !strcasecmp(property, "true");
        INIT_LOGD("  Draw reorder %s", drawReorderDisabled ? "disabled" : "enabled");
    } else {
        drawReorderDisabled = false;
        INIT_LOGD("  Draw reorder enabled");
    }

    return (prevDebugLayersUpdates != debugLayersUpdates)
            || (prevDebugOverdraw != debugOverdraw)
            || (prevDebugStencilClip != debugStencilClip);
}

void Caches::terminate() {
    if (!mInitialized) return;
    mRegionMesh.release();

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
    return sOverdrawColors[mOverdrawDebugColorSet][amount - 1];
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

    // We must stop tasks before clearing caches
    if (mode > kFlushMode_Layers) {
        tasks.stop();
    }

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
}

///////////////////////////////////////////////////////////////////////////////
// Tiling
///////////////////////////////////////////////////////////////////////////////

void Caches::startTiling(GLuint x, GLuint y, GLuint width, GLuint height, bool discard) {
    if (mExtensions.hasTiledRendering() && !debugOverdraw) {
        glStartTilingQCOM(x, y, width, height, (discard ? GL_NONE : GL_COLOR_BUFFER_BIT0_QCOM));
    }
}

void Caches::endTiling() {
    if (mExtensions.hasTiledRendering() && !debugOverdraw) {
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

void Caches::initTempProperties() {
    propertyLightRadius = -1.0f;
    propertyLightPosY = -1.0f;
    propertyLightPosZ = -1.0f;
    propertyAmbientRatio = -1.0f;
    propertyAmbientShadowStrength = -1;
    propertySpotShadowStrength = -1;
}

void Caches::setTempProperty(const char* name, const char* value) {
    ALOGD("setting property %s to %s", name, value);
    if (!strcmp(name, "ambientRatio")) {
        propertyAmbientRatio = fmin(fmax(atof(value), 0.0), 10.0);
        ALOGD("ambientRatio = %.2f", propertyAmbientRatio);
        return;
    } else if (!strcmp(name, "lightRadius")) {
        propertyLightRadius = fmin(fmax(atof(value), 0.0), 3000.0);
        ALOGD("lightRadius = %.2f", propertyLightRadius);
        return;
    } else if (!strcmp(name, "lightPosY")) {
        propertyLightPosY = fmin(fmax(atof(value), 0.0), 3000.0);
        ALOGD("lightPos Y = %.2f", propertyLightPosY);
        return;
    } else if (!strcmp(name, "lightPosZ")) {
        propertyLightPosZ = fmin(fmax(atof(value), 0.0), 3000.0);
        ALOGD("lightPos Z = %.2f", propertyLightPosZ);
        return;
    } else if (!strcmp(name, "ambientShadowStrength")) {
        propertyAmbientShadowStrength = atoi(value);
        ALOGD("ambient shadow strength = 0x%x out of 0xff", propertyAmbientShadowStrength);
        return;
    } else if (!strcmp(name, "spotShadowStrength")) {
        propertySpotShadowStrength = atoi(value);
        ALOGD("spot shadow strength = 0x%x out of 0xff", propertySpotShadowStrength);
        return;
    }
    ALOGD("    failed");
}

}; // namespace uirenderer
}; // namespace android
