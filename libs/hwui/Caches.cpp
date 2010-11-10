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

#include "Caches.h"

namespace android {

#ifdef USE_OPENGL_RENDERER
using namespace uirenderer;
ANDROID_SINGLETON_STATIC_INSTANCE(Caches);
#endif

namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

Caches::Caches(): Singleton<Caches>(), blend(false), lastSrcMode(GL_ZERO),
        lastDstMode(GL_ZERO), currentProgram(NULL) {
    GLint maxTextureUnits;
    glGetIntegerv(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS, &maxTextureUnits);
    if (maxTextureUnits < REQUIRED_TEXTURE_UNITS_COUNT) {
        LOGW("At least %d texture units are required!", REQUIRED_TEXTURE_UNITS_COUNT);
    }

    glGenBuffers(1, &meshBuffer);
    glBindBuffer(GL_ARRAY_BUFFER, meshBuffer);
    glBufferData(GL_ARRAY_BUFFER, sizeof(gMeshVertices), gMeshVertices, GL_STATIC_DRAW);

    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTextureSize);

    mCurrentBuffer = meshBuffer;
    mRegionMesh = NULL;
}

Caches::~Caches() {
    delete[] mRegionMesh;
}

///////////////////////////////////////////////////////////////////////////////
// Debug
///////////////////////////////////////////////////////////////////////////////

void Caches::dumpMemoryUsage() {
    LOGD("Current memory usage / total memory usage (bytes):");
    LOGD("  TextureCache         %8d / %8d", textureCache.getSize(), textureCache.getMaxSize());
    LOGD("  LayerCache           %8d / %8d", layerCache.getSize(), layerCache.getMaxSize());
    LOGD("  GradientCache        %8d / %8d", gradientCache.getSize(), gradientCache.getMaxSize());
    LOGD("  PathCache            %8d / %8d", pathCache.getSize(), pathCache.getMaxSize());
    LOGD("  TextDropShadowCache  %8d / %8d", dropShadowCache.getSize(),
            dropShadowCache.getMaxSize());
    for (uint32_t i = 0; i < fontRenderer.getFontRendererCount(); i++) {
        const uint32_t size = fontRenderer.getFontRendererSize(i);
        LOGD("  FontRenderer %d       %8d / %8d", i, size, size);
    }
    LOGD("Other:");
    LOGD("  FboCache             %8d / %8d", fboCache.getSize(), fboCache.getMaxSize());
    LOGD("  PatchCache           %8d / %8d", patchCache.getSize(), patchCache.getMaxSize());

    uint32_t total = 0;
    total += textureCache.getSize();
    total += layerCache.getSize();
    total += gradientCache.getSize();
    total += pathCache.getSize();
    total += dropShadowCache.getSize();
    for (uint32_t i = 0; i < fontRenderer.getFontRendererCount(); i++) {
        total += fontRenderer.getFontRendererSize(i);
    }

    LOGD("Total memory usage:");
    LOGD("  %d bytes, %.2f MB", total, total / 1024.0f / 1024.0f);
    LOGD("\n");
}

///////////////////////////////////////////////////////////////////////////////
// VBO
///////////////////////////////////////////////////////////////////////////////

void Caches::bindMeshBuffer() {
    bindMeshBuffer(meshBuffer);
}

void Caches::bindMeshBuffer(const GLuint buffer) {
    if (mCurrentBuffer != buffer) {
        glBindBuffer(GL_ARRAY_BUFFER, buffer);
        mCurrentBuffer = buffer;
    }
}

void Caches::unbindMeshBuffer() {
    if (mCurrentBuffer) {
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        mCurrentBuffer = 0;
    }
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
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, mRegionMeshIndices);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, REGION_MESH_QUAD_COUNT * 6 * sizeof(uint16_t),
                regionIndices, GL_STATIC_DRAW);

        delete[] regionIndices;
    } else {
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, mRegionMeshIndices);
    }

    return mRegionMesh;
}

}; // namespace uirenderer
}; // namespace android
