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

#include "ShadowTessellator.h"

namespace android {
namespace uirenderer {

MeshState::MeshState()
        : mCurrentPositionPointer(this)
        , mCurrentPositionStride(0)
        , mCurrentTexCoordsPointer(this)
        , mCurrentTexCoordsStride(0)
        , mTexCoordsArrayEnabled(false) {

    glGenBuffers(1, &meshBuffer);
    glBindBuffer(GL_ARRAY_BUFFER, meshBuffer);
    glBufferData(GL_ARRAY_BUFFER, sizeof(kMeshVertices), kMeshVertices, GL_STATIC_DRAW);

    mCurrentBuffer = meshBuffer;
    mCurrentIndicesBuffer = 0;
    mCurrentPixelBuffer = 0;

    mQuadListIndices = 0;
    mShadowStripsIndices = 0;

    // position attribute always enabled
    glEnableVertexAttribArray(Program::kBindingPosition);
}

MeshState::~MeshState() {
    glDeleteBuffers(1, &meshBuffer);
    mCurrentBuffer = 0;

    glDeleteBuffers(1, &mQuadListIndices);
    mQuadListIndices = 0;

    glDeleteBuffers(1, &mShadowStripsIndices);
    mShadowStripsIndices = 0;
}

///////////////////////////////////////////////////////////////////////////////
// Buffer Objects
///////////////////////////////////////////////////////////////////////////////

bool MeshState::bindMeshBuffer() {
    return bindMeshBuffer(meshBuffer);
}

bool MeshState::bindMeshBuffer(GLuint buffer) {
    if (!buffer) buffer = meshBuffer;
    if (mCurrentBuffer != buffer) {
        glBindBuffer(GL_ARRAY_BUFFER, buffer);
        mCurrentBuffer = buffer;
        return true;
    }
    return false;
}

bool MeshState::unbindMeshBuffer() {
    if (mCurrentBuffer) {
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        mCurrentBuffer = 0;
        return true;
    }
    return false;
}

///////////////////////////////////////////////////////////////////////////////
// Vertices
///////////////////////////////////////////////////////////////////////////////

void MeshState::bindPositionVertexPointer(bool force, const GLvoid* vertices, GLsizei stride) {
    if (force || vertices != mCurrentPositionPointer || stride != mCurrentPositionStride) {
        glVertexAttribPointer(Program::kBindingPosition, 2, GL_FLOAT, GL_FALSE, stride, vertices);
        mCurrentPositionPointer = vertices;
        mCurrentPositionStride = stride;
    }
}

void MeshState::bindTexCoordsVertexPointer(bool force, const GLvoid* vertices, GLsizei stride) {
    if (force || vertices != mCurrentTexCoordsPointer || stride != mCurrentTexCoordsStride) {
        glVertexAttribPointer(Program::kBindingTexCoords, 2, GL_FLOAT, GL_FALSE, stride, vertices);
        mCurrentTexCoordsPointer = vertices;
        mCurrentTexCoordsStride = stride;
    }
}

void MeshState::resetVertexPointers() {
    mCurrentPositionPointer = this;
    mCurrentTexCoordsPointer = this;
}

void MeshState::resetTexCoordsVertexPointer() {
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

bool MeshState::bindIndicesBufferInternal(const GLuint buffer) {
    if (mCurrentIndicesBuffer != buffer) {
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, buffer);
        mCurrentIndicesBuffer = buffer;
        return true;
    }
    return false;
}

bool MeshState::bindQuadIndicesBuffer() {
    if (!mQuadListIndices) {
        std::unique_ptr<uint16_t[]> regionIndices(new uint16_t[kMaxNumberOfQuads * 6]);
        for (uint32_t i = 0; i < kMaxNumberOfQuads; i++) {
            uint16_t quad = i * 4;
            int index = i * 6;
            regionIndices[index    ] = quad;       // top-left
            regionIndices[index + 1] = quad + 1;   // top-right
            regionIndices[index + 2] = quad + 2;   // bottom-left
            regionIndices[index + 3] = quad + 2;   // bottom-left
            regionIndices[index + 4] = quad + 1;   // top-right
            regionIndices[index + 5] = quad + 3;   // bottom-right
        }

        glGenBuffers(1, &mQuadListIndices);
        bool force = bindIndicesBufferInternal(mQuadListIndices);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, kMaxNumberOfQuads * 6 * sizeof(uint16_t),
                regionIndices.get(), GL_STATIC_DRAW);
        return force;
    }

    return bindIndicesBufferInternal(mQuadListIndices);
}

bool MeshState::bindShadowIndicesBuffer() {
    if (!mShadowStripsIndices) {
        std::unique_ptr<uint16_t[]> shadowIndices(new uint16_t[MAX_SHADOW_INDEX_COUNT]);
        ShadowTessellator::generateShadowIndices(shadowIndices.get());
        glGenBuffers(1, &mShadowStripsIndices);
        bool force = bindIndicesBufferInternal(mShadowStripsIndices);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, MAX_SHADOW_INDEX_COUNT * sizeof(uint16_t),
            shadowIndices.get(), GL_STATIC_DRAW);
        return force;
    }

    return bindIndicesBufferInternal(mShadowStripsIndices);
}

bool MeshState::unbindIndicesBuffer() {
    if (mCurrentIndicesBuffer) {
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
        mCurrentIndicesBuffer = 0;
        return true;
    }
    return false;
}

} /* namespace uirenderer */
} /* namespace android */

