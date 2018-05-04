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

#include "Caches.h"

#include "GlLayer.h"
#include "Properties.h"
#include "ShadowTessellator.h"
#include "renderstate/RenderState.h"
#include "utils/GLUtils.h"

#include <cutils/properties.h>
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
        : mRenderState(&renderState)
        , mInitialized(false) {
    INIT_LOGD("Creating OpenGL renderer caches");
    init();
    initConstraints();
    initStaticProperties();
    initExtensions();
}

bool Caches::init() {
    if (mInitialized) return false;

    ATRACE_NAME("Caches::init");

    mRegionMesh = nullptr;
    mProgram = nullptr;

    mInitialized = true;

    mPixelBufferState = new PixelBufferState();
    mTextureState = new TextureState();
    mTextureState->constructTexture(*this);

    return true;
}

void Caches::initExtensions() {
    if (extensions().hasDebugMarker()) {
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
    maxTextureSize = DeviceInfo::get()->maxTextureSize();
}

void Caches::initStaticProperties() {
    // OpenGL ES 3.0+ specific features
    gpuPixelBuffersEnabled = extensions().hasPixelBufferObjects() &&
                             property_get_bool(PROPERTY_ENABLE_GPU_PIXEL_BUFFERS, true);
}

void Caches::terminate() {
    if (!mInitialized) return;
    mRegionMesh.reset(nullptr);

    mProgram = nullptr;

    clearGarbage();

    delete mPixelBufferState;
    mPixelBufferState = nullptr;
    delete mTextureState;
    mTextureState = nullptr;
    mInitialized = false;
}

void Caches::setProgram(const ProgramDescription& description) {
    // DEAD CODE
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
    static uint32_t sOverdrawColors[2][4] = {{0x2f0000ff, 0x2f00ff00, 0x3fff0000, 0x7fff0000},
                                             {0x2f0000ff, 0x4fffff00, 0x5fff8ad8, 0x7fff0000}};
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

void Caches::dumpMemoryUsage(String8& log) {
    uint32_t total = 0;
    log.appendFormat("Current memory usage / total memory usage (bytes):\n");
    if (mRenderState) {
        int memused = 0;
        for (std::set<Layer*>::iterator it = mRenderState->mActiveLayers.begin();
             it != mRenderState->mActiveLayers.end(); it++) {
            const Layer* layer = *it;
            LOG_ALWAYS_FATAL_IF(layer->getApi() != Layer::Api::OpenGL);
            const GlLayer* glLayer = static_cast<const GlLayer*>(layer);
            log.appendFormat("    GlLayer size %dx%d; texid=%u refs=%d\n", layer->getWidth(),
                             layer->getHeight(), glLayer->getTextureId(), layer->getStrongCount());
            memused += layer->getWidth() * layer->getHeight() * 4;
        }
        log.appendFormat("  Layers total   %8d (numLayers = %zu)\n", memused,
                         mRenderState->mActiveLayers.size());
        total += memused;
    }

    log.appendFormat("Total memory usage:\n");
    log.appendFormat("  %d bytes, %.2f MB\n", total, total / 1024.0f / 1024.0f);
}

///////////////////////////////////////////////////////////////////////////////
// Memory management
///////////////////////////////////////////////////////////////////////////////

void Caches::clearGarbage() {
}

void Caches::flush(FlushMode mode) {
    clearGarbage();
    glFinish();
    // Errors during cleanup should be considered non-fatal, dump them and
    // and move on. TODO: All errors or just errors like bad surface?
    GLUtils::dumpGLErrors();
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

};  // namespace uirenderer
};  // namespace android
