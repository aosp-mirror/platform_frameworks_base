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

#include <utils/Log.h>
#include <utils/String8.h>

#include "Caches.h"
#include "DisplayListRenderer.h"
#include "Properties.h"
#include "LayerRenderer.h"

namespace android {

#ifdef USE_OPENGL_RENDERER
using namespace uirenderer;
ANDROID_SINGLETON_STATIC_INSTANCE(Caches);
#endif

namespace uirenderer {

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

Caches::Caches(): Singleton<Caches>(), mInitialized(false) {
    init();
    initExtensions();
    initConstraints();

    mDebugLevel = readDebugLevel();
    ALOGD("Enabling debug mode %d", mDebugLevel);

#if RENDER_LAYERS_AS_REGIONS
    INIT_LOGD("Layers will be composited as regions");
#endif
}

void Caches::init() {
    if (mInitialized) return;

    glGenBuffers(1, &meshBuffer);
    glBindBuffer(GL_ARRAY_BUFFER, meshBuffer);
    glBufferData(GL_ARRAY_BUFFER, sizeof(gMeshVertices), gMeshVertices, GL_STATIC_DRAW);

    mCurrentBuffer = meshBuffer;
    mCurrentIndicesBuffer = 0;
    mCurrentPositionPointer = this;
    mCurrentTexCoordsPointer = this;

    mTexCoordsArrayEnabled = false;

    mScissorX = mScissorY = mScissorWidth = mScissorHeight = 0;

    glActiveTexture(gTextureUnits[0]);
    mTextureUnit = 0;

    mRegionMesh = NULL;

    blend = false;
    lastSrcMode = GL_ZERO;
    lastDstMode = GL_ZERO;
    currentProgram = NULL;

    mInitialized = true;
}

void Caches::initExtensions() {
    if (extensions.hasDebugMarker()) {
        eventMark = glInsertEventMarkerEXT;
        startMark = glPushGroupMarkerEXT;
        endMark = glPopGroupMarkerEXT;
    } else {
        eventMark = eventMarkNull;
        startMark = startMarkNull;
        endMark = endMarkNull;
    }

    if (extensions.hasDebugLabel()) {
        setLabel = glLabelObjectEXT;
        getLabel = glGetObjectLabelEXT;
    } else {
        setLabel = setLabelNull;
        getLabel = getLabelNull;
    }
}

void Caches::initConstraints() {
    GLint maxTextureUnits;
    glGetIntegerv(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS, &maxTextureUnits);
    if (maxTextureUnits < REQUIRED_TEXTURE_UNITS_COUNT) {
        ALOGW("At least %d texture units are required!", REQUIRED_TEXTURE_UNITS_COUNT);
    }

    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTextureSize);
}

void Caches::terminate() {
    if (!mInitialized) return;

    glDeleteBuffers(1, &meshBuffer);
    mCurrentBuffer = 0;

    glDeleteBuffers(1, &mRegionMeshIndices);
    delete[] mRegionMesh;
    mRegionMesh = NULL;

    fboCache.clear();

    programCache.clear();
    currentProgram = NULL;

    mInitialized = false;
}

///////////////////////////////////////////////////////////////////////////////
// Debug
///////////////////////////////////////////////////////////////////////////////

void Caches::dumpMemoryUsage() {
    String8 stringLog;
    dumpMemoryUsage(stringLog);
    ALOGD("%s", stringLog.string());
}

void Caches::dumpMemoryUsage(String8 &log) {
    log.appendFormat("Current memory usage / total memory usage (bytes):\n");
    log.appendFormat("  TextureCache         %8d / %8d\n",
            textureCache.getSize(), textureCache.getMaxSize());
    log.appendFormat("  LayerCache           %8d / %8d\n",
            layerCache.getSize(), layerCache.getMaxSize());
    log.appendFormat("  GradientCache        %8d / %8d\n",
            gradientCache.getSize(), gradientCache.getMaxSize());
    log.appendFormat("  PathCache            %8d / %8d\n",
            pathCache.getSize(), pathCache.getMaxSize());
    log.appendFormat("  CircleShapeCache     %8d / %8d\n",
            circleShapeCache.getSize(), circleShapeCache.getMaxSize());
    log.appendFormat("  OvalShapeCache       %8d / %8d\n",
            ovalShapeCache.getSize(), ovalShapeCache.getMaxSize());
    log.appendFormat("  RoundRectShapeCache  %8d / %8d\n",
            roundRectShapeCache.getSize(), roundRectShapeCache.getMaxSize());
    log.appendFormat("  RectShapeCache       %8d / %8d\n",
            rectShapeCache.getSize(), rectShapeCache.getMaxSize());
    log.appendFormat("  ArcShapeCache        %8d / %8d\n",
            arcShapeCache.getSize(), arcShapeCache.getMaxSize());
    log.appendFormat("  TextDropShadowCache  %8d / %8d\n", dropShadowCache.getSize(),
            dropShadowCache.getMaxSize());
    for (uint32_t i = 0; i < fontRenderer.getFontRendererCount(); i++) {
        const uint32_t size = fontRenderer.getFontRendererSize(i);
        log.appendFormat("  FontRenderer %d       %8d / %8d\n", i, size, size);
    }
    log.appendFormat("Other:\n");
    log.appendFormat("  FboCache             %8d / %8d\n",
            fboCache.getSize(), fboCache.getMaxSize());
    log.appendFormat("  PatchCache           %8d / %8d\n",
            patchCache.getSize(), patchCache.getMaxSize());

    uint32_t total = 0;
    total += textureCache.getSize();
    total += layerCache.getSize();
    total += gradientCache.getSize();
    total += pathCache.getSize();
    total += dropShadowCache.getSize();
    total += roundRectShapeCache.getSize();
    total += circleShapeCache.getSize();
    total += ovalShapeCache.getSize();
    total += rectShapeCache.getSize();
    total += arcShapeCache.getSize();
    for (uint32_t i = 0; i < fontRenderer.getFontRendererCount(); i++) {
        total += fontRenderer.getFontRendererSize(i);
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

    Mutex::Autolock _l(mGarbageLock);

    size_t count = mLayerGarbage.size();
    for (size_t i = 0; i < count; i++) {
        Layer* layer = mLayerGarbage.itemAt(i);
        LayerRenderer::destroyLayer(layer);
    }
    mLayerGarbage.clear();

    count = mDisplayListGarbage.size();
    for (size_t i = 0; i < count; i++) {
        DisplayList* displayList = mDisplayListGarbage.itemAt(i);
        delete displayList;
    }
    mDisplayListGarbage.clear();
}

void Caches::deleteLayerDeferred(Layer* layer) {
    Mutex::Autolock _l(mGarbageLock);
    mLayerGarbage.push(layer);
}

void Caches::deleteDisplayListDeferred(DisplayList* displayList) {
    Mutex::Autolock _l(mGarbageLock);
    mDisplayListGarbage.push(displayList);
}

void Caches::flush(FlushMode mode) {
    FLUSH_LOGD("Flushing caches (mode %d)", mode);

    clearGarbage();

    switch (mode) {
        case kFlushMode_Full:
            textureCache.clear();
            patchCache.clear();
            dropShadowCache.clear();
            gradientCache.clear();
            fontRenderer.clear();
            // fall through
        case kFlushMode_Moderate:
            fontRenderer.flush();
            textureCache.flush();
            pathCache.clear();
            roundRectShapeCache.clear();
            circleShapeCache.clear();
            ovalShapeCache.clear();
            rectShapeCache.clear();
            arcShapeCache.clear();
            // fall through
        case kFlushMode_Layers:
            layerCache.clear();
            break;
    }
}

///////////////////////////////////////////////////////////////////////////////
// VBO
///////////////////////////////////////////////////////////////////////////////

bool Caches::bindMeshBuffer() {
    return bindMeshBuffer(meshBuffer);
}

bool Caches::bindMeshBuffer(const GLuint buffer) {
    if (mCurrentBuffer != buffer) {
        glBindBuffer(GL_ARRAY_BUFFER, buffer);
        mCurrentBuffer = buffer;
        return true;
    }
    return false;
}

bool Caches::unbindMeshBuffer() {
    if (mCurrentBuffer) {
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        mCurrentBuffer = 0;
        return true;
    }
    return false;
}

bool Caches::bindIndicesBuffer(const GLuint buffer) {
    if (mCurrentIndicesBuffer != buffer) {
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, buffer);
        mCurrentIndicesBuffer = buffer;
        return true;
    }
    return false;
}

bool Caches::unbindIndicesBuffer() {
    if (mCurrentIndicesBuffer) {
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
        mCurrentIndicesBuffer = 0;
        return true;
    }
    return false;
}

void Caches::bindPositionVertexPointer(bool force, GLuint slot, GLvoid* vertices, GLsizei stride) {
    if (force || vertices != mCurrentPositionPointer) {
        glVertexAttribPointer(slot, 2, GL_FLOAT, GL_FALSE, stride, vertices);
        mCurrentPositionPointer = vertices;
    }
}

void Caches::bindTexCoordsVertexPointer(bool force, GLuint slot, GLvoid* vertices) {
    if (force || vertices != mCurrentTexCoordsPointer) {
        glVertexAttribPointer(slot, 2, GL_FLOAT, GL_FALSE, gMeshStride, vertices);
        mCurrentTexCoordsPointer = vertices;
    }
}

void Caches::resetVertexPointers() {
    mCurrentPositionPointer = this;
    mCurrentTexCoordsPointer = this;
}

void Caches::resetTexCoordsVertexPointer() {
    mCurrentTexCoordsPointer = this;
}

void Caches::enableTexCoordsVertexArray() {
    if (!mTexCoordsArrayEnabled) {
        glEnableVertexAttribArray(Program::kBindingTexCoords);
        mCurrentTexCoordsPointer = this;
        mTexCoordsArrayEnabled = true;
    }
}

void Caches::disbaleTexCoordsVertexArray() {
    if (mTexCoordsArrayEnabled) {
        glDisableVertexAttribArray(Program::kBindingTexCoords);
        mTexCoordsArrayEnabled = false;
    }
}

void Caches::activeTexture(GLuint textureUnit) {
    if (mTextureUnit != textureUnit) {
        glActiveTexture(gTextureUnits[textureUnit]);
        mTextureUnit = textureUnit;
    }
}

void Caches::setScissor(GLint x, GLint y, GLint width, GLint height) {
    if (x != mScissorX || y != mScissorY || width != mScissorWidth || height != mScissorHeight) {
        glScissor(x, y, width, height);

        mScissorX = x;
        mScissorY = y;
        mScissorWidth = width;
        mScissorHeight = height;
    }
}

void Caches::resetScissor() {
    mScissorX = mScissorY = mScissorWidth = mScissorHeight = 0;
}

TextureVertex* Caches::getRegionMesh() {
    // Create the mesh, 2 triangles and 4 vertices per rectangle in the region
    if (!mRegionMesh) {
        mRegionMesh = new TextureVertex[REGION_MESH_QUAD_COUNT * 4];

        uint16_t* regionIndices = new uint16_t[REGION_MESH_QUAD_COUNT * 6];
        for (int i = 0; i < REGION_MESH_QUAD_COUNT; i++) {
            uint16_t quad = i * 4;
            int index = i * 6;
            regionIndices[index    ] = quad;       // top-left
            regionIndices[index + 1] = quad + 1;   // top-right
            regionIndices[index + 2] = quad + 2;   // bottom-left
            regionIndices[index + 3] = quad + 2;   // bottom-left
            regionIndices[index + 4] = quad + 1;   // top-right
            regionIndices[index + 5] = quad + 3;   // bottom-right
        }

        glGenBuffers(1, &mRegionMeshIndices);
        bindIndicesBuffer(mRegionMeshIndices);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, REGION_MESH_QUAD_COUNT * 6 * sizeof(uint16_t),
                regionIndices, GL_STATIC_DRAW);

        delete[] regionIndices;
    } else {
        bindIndicesBuffer(mRegionMeshIndices);
    }

    return mRegionMesh;
}

}; // namespace uirenderer
}; // namespace android
