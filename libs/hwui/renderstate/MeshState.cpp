/*
 * Copyright (C) 2015 The Android Open Source Project
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
#include "renderstate/MeshState.h"

#include "Program.h"

namespace android {
namespace uirenderer {

MeshState::MeshState()
        : mCurrentIndicesBuffer(0)
        , mCurrentPixelBuffer(0)
        , mCurrentPositionPointer(this)
        , mCurrentPositionStride(0)
        , mCurrentTexCoordsPointer(this)
        , mCurrentTexCoordsStride(0)
        , mTexCoordsArrayEnabled(false)
        , mQuadListIndices(0) {
    glGenBuffers(1, &mUnitQuadBuffer);
    glBindBuffer(GL_ARRAY_BUFFER, mUnitQuadBuffer);
    glBufferData(GL_ARRAY_BUFFER, sizeof(kUnitQuadVertices), kUnitQuadVertices, GL_STATIC_DRAW);
    mCurrentBuffer = mUnitQuadBuffer;

    uint16_t regionIndices[kMaxNumberOfQuads * 6];
    for (uint32_t i = 0; i < kMaxNumberOfQuads; i++) {
        uint16_t quad = i * 4;
        int index = i * 6;
        regionIndices[index] = quad;          // top-left
        regionIndices[index + 1] = quad + 1;  // top-right
        regionIndices[index + 2] = quad + 2;  // bottom-left
        regionIndices[index + 3] = quad + 2;  // bottom-left
        regionIndices[index + 4] = quad + 1;  // top-right
        regionIndices[index + 5] = quad + 3;  // bottom-right
    }
    glGenBuffers(1, &mQuadListIndices);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, mQuadListIndices);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER, sizeof(regionIndices), regionIndices, GL_STATIC_DRAW);
    mCurrentIndicesBuffer = mQuadListIndices;

    // position attribute always enabled
    glEnableVertexAttribArray(Program::kBindingPosition);
}

MeshState::~MeshState() {
    glDeleteBuffers(1, &mUnitQuadBuffer);
    mCurrentBuffer = 0;

    glDeleteBuffers(1, &mQuadListIndices);
    mQuadListIndices = 0;
}

void MeshState::dump() {
    ALOGD("MeshState VBOs: unitQuad %d, current %d", mUnitQuadBuffer, mCurrentBuffer);
    ALOGD("MeshState IBOs: quadList %d, current %d", mQuadListIndices, mCurrentIndicesBuffer);
    ALOGD("MeshState vertices: vertex data %p, stride %d", mCurrentPositionPointer,
          mCurrentPositionStride);
    ALOGD("MeshState texCoord: data %p, stride %d", mCurrentTexCoordsPointer,
          mCurrentTexCoordsStride);
}

///////////////////////////////////////////////////////////////////////////////
// Buffer Objects
///////////////////////////////////////////////////////////////////////////////

void MeshState::bindMeshBuffer(GLuint buffer) {
    if (mCurrentBuffer != buffer) {
        glBindBuffer(GL_ARRAY_BUFFER, buffer);
        mCurrentBuffer = buffer;

        // buffer has changed, so invalidate cached vertex pos/texcoord pointers
        resetVertexPointers();
    }
}

void MeshState::unbindMeshBuffer() {
    return bindMeshBuffer(0);
}

void MeshState::genOrUpdateMeshBuffer(GLuint* buffer, GLsizeiptr size, const void* data,
                                      GLenum usage) {
    if (!*buffer) {
        glGenBuffers(1, buffer);
    }
    bindMeshBuffer(*buffer);
    glBufferData(GL_ARRAY_BUFFER, size, data, usage);
}

void MeshState::updateMeshBufferSubData(GLuint buffer, GLintptr offset, GLsizeiptr size,
                                        const void* data) {
    bindMeshBuffer(buffer);
    glBufferSubData(GL_ARRAY_BUFFER, offset, size, data);
}

void MeshState::deleteMeshBuffer(GLuint buffer) {
    if (buffer == mCurrentBuffer) {
        // GL defines that deleting the currently bound VBO rebinds to 0 (no VBO).
        // Reflect this in our cached value.
        mCurrentBuffer = 0;
    }
    glDeleteBuffers(1, &buffer);
}

///////////////////////////////////////////////////////////////////////////////
// Vertices
///////////////////////////////////////////////////////////////////////////////

void MeshState::bindPositionVertexPointer(const GLvoid* vertices, GLsizei stride) {
    // update pos coords if !current vbo, since vertices may point into mutable memory (e.g. stack)
    if (mCurrentBuffer == 0 || vertices != mCurrentPositionPointer ||
        stride != mCurrentPositionStride) {
        glVertexAttribPointer(Program::kBindingPosition, 2, GL_FLOAT, GL_FALSE, stride, vertices);
        mCurrentPositionPointer = vertices;
        mCurrentPositionStride = stride;
    }
}

void MeshState::bindTexCoordsVertexPointer(const GLvoid* vertices, GLsizei stride) {
    // update tex coords if !current vbo, since vertices may point into mutable memory (e.g. stack)
    if (mCurrentBuffer == 0 || vertices != mCurrentTexCoordsPointer ||
        stride != mCurrentTexCoordsStride) {
        glVertexAttribPointer(Program::kBindingTexCoords, 2, GL_FLOAT, GL_FALSE, stride, vertices);
        mCurrentTexCoordsPointer = vertices;
        mCurrentTexCoordsStride = stride;
    }
}

void MeshState::resetVertexPointers() {
    mCurrentPositionPointer = this;
    mCurrentTexCoordsPointer = this;
}

void MeshState::enableTexCoordsVertexArray() {
    if (!mTexCoordsArrayEnabled) {
        glEnableVertexAttribArray(Program::kBindingTexCoords);
        mCurrentTexCoordsPointer = this;
        mTexCoordsArrayEnabled = true;
    }
}

void MeshState::disableTexCoordsVertexArray() {
    if (mTexCoordsArrayEnabled) {
        glDisableVertexAttribArray(Program::kBindingTexCoords);
        mTexCoordsArrayEnabled = false;
    }
}

///////////////////////////////////////////////////////////////////////////////
// Indices
///////////////////////////////////////////////////////////////////////////////

void MeshState::bindIndicesBuffer(const GLuint buffer) {
    if (mCurrentIndicesBuffer != buffer) {
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, buffer);
        mCurrentIndicesBuffer = buffer;
    }
}

void MeshState::unbindIndicesBuffer() {
    if (mCurrentIndicesBuffer) {
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
        mCurrentIndicesBuffer = 0;
    }
}

} /* namespace uirenderer */
} /* namespace android */
