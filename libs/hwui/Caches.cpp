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

    currentBuffer = meshBuffer;
}

/**
 * Binds the VBO used to render simple textured quads.
 */
void Caches::bindMeshBuffer() {
    bindMeshBuffer(meshBuffer);
}

/**
 * Binds the specified VBO.
 */
void Caches::bindMeshBuffer(const GLuint buffer) {
    if (currentBuffer != buffer) {
        glBindBuffer(GL_ARRAY_BUFFER, buffer);
        currentBuffer = buffer;
    }
}

/**
 * Unbinds the VBO used to render simple textured quads.
 */
void Caches::unbindMeshBuffer() {
    if (currentBuffer) {
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        currentBuffer = 0;
    }
}

}; // namespace uirenderer
}; // namespace android
