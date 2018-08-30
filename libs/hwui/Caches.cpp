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

Caches::Caches(RenderState& renderState) : mInitialized(false) {
    INIT_LOGD("Creating OpenGL renderer caches");
    init();
    initStaticProperties();
}

bool Caches::init() {
    if (mInitialized) return false;

    ATRACE_NAME("Caches::init");

    mRegionMesh = nullptr;

    mInitialized = true;

    mPixelBufferState = new PixelBufferState();
    mTextureState = new TextureState();
    mTextureState->constructTexture(*this);

    return true;
}

void Caches::initStaticProperties() {
    // OpenGL ES 3.0+ specific features
    gpuPixelBuffersEnabled = extensions().hasPixelBufferObjects() &&
                             property_get_bool(PROPERTY_ENABLE_GPU_PIXEL_BUFFERS, true);
}

void Caches::terminate() {
    if (!mInitialized) return;
    mRegionMesh.reset(nullptr);

    clearGarbage();

    delete mPixelBufferState;
    mPixelBufferState = nullptr;
    delete mTextureState;
    mTextureState = nullptr;
    mInitialized = false;
}

///////////////////////////////////////////////////////////////////////////////
// Memory management
///////////////////////////////////////////////////////////////////////////////

void Caches::clearGarbage() {}

void Caches::flush(FlushMode mode) {
    clearGarbage();
    glFinish();
    // Errors during cleanup should be considered non-fatal, dump them and
    // and move on. TODO: All errors or just errors like bad surface?
    GLUtils::dumpGLErrors();
}

};  // namespace uirenderer
};  // namespace android
