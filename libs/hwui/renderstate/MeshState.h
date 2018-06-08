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
#ifndef RENDERSTATE_MESHSTATE_H
#define RENDERSTATE_MESHSTATE_H

#include "Vertex.h"

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <memory>

namespace android {
namespace uirenderer {

class Program;

// Maximum number of quads that pre-allocated meshes can draw
const uint32_t kMaxNumberOfQuads = 2048;

// This array is never used directly but used as a memcpy source in the
// OpenGLRenderer constructor
const TextureVertex kUnitQuadVertices[] = {
        {0, 0, 0, 0}, {1, 0, 1, 0}, {0, 1, 0, 1}, {1, 1, 1, 1},
};

const GLsizei kVertexStride = sizeof(Vertex);
const GLsizei kAlphaVertexStride = sizeof(AlphaVertex);
const GLsizei kTextureVertexStride = sizeof(TextureVertex);
const GLsizei kColorTextureVertexStride = sizeof(ColorTextureVertex);

const GLsizei kMeshTextureOffset = 2 * sizeof(float);
const GLsizei kVertexAlphaOffset = 2 * sizeof(float);
const GLsizei kVertexAAWidthOffset = 2 * sizeof(float);
const GLsizei kVertexAALengthOffset = 3 * sizeof(float);
const GLsizei kUnitQuadCount = 4;

class MeshState {
private:
    friend class RenderState;

public:
    ~MeshState();
    void dump();
    ///////////////////////////////////////////////////////////////////////////////
    // Buffer objects
    ///////////////////////////////////////////////////////////////////////////////

    /**
     * Binds the specified VBO if needed. If buffer == 0, binds default simple textured quad.
     */
    void bindMeshBuffer(GLuint buffer);

    /**
     * Unbinds the current VBO if active.
     */
    void unbindMeshBuffer();

    void genOrUpdateMeshBuffer(GLuint* buffer, GLsizeiptr size, const void* data, GLenum usage);
    void updateMeshBufferSubData(GLuint buffer, GLintptr offset, GLsizeiptr size, const void* data);
    void deleteMeshBuffer(GLuint);

    ///////////////////////////////////////////////////////////////////////////////
    // Vertices
    ///////////////////////////////////////////////////////////////////////////////
    /**
     * Binds an attrib to the specified float vertex pointer.
     * Assumes a stride of gTextureVertexStride and a size of 2.
     */
    void bindPositionVertexPointer(const GLvoid* vertices, GLsizei stride = kTextureVertexStride);

    /**
     * Binds an attrib to the specified float vertex pointer.
     * Assumes a stride of gTextureVertexStride and a size of 2.
     */
    void bindTexCoordsVertexPointer(const GLvoid* vertices, GLsizei stride = kTextureVertexStride);

    /**
     * Resets the vertex pointers.
     */
    void resetVertexPointers();

    void enableTexCoordsVertexArray();
    void disableTexCoordsVertexArray();

    ///////////////////////////////////////////////////////////////////////////////
    // Indices
    ///////////////////////////////////////////////////////////////////////////////
    void bindIndicesBuffer(const GLuint buffer);
    void unbindIndicesBuffer();

    ///////////////////////////////////////////////////////////////////////////////
    // Getters - for use in Glop building
    ///////////////////////////////////////////////////////////////////////////////
    GLuint getUnitQuadVBO() { return mUnitQuadBuffer; }
    GLuint getQuadListIBO() { return mQuadListIndices; }

private:
    MeshState();

    GLuint mUnitQuadBuffer;

    GLuint mCurrentBuffer;
    GLuint mCurrentIndicesBuffer;
    GLuint mCurrentPixelBuffer;

    const void* mCurrentPositionPointer;
    GLsizei mCurrentPositionStride;
    const void* mCurrentTexCoordsPointer;
    GLsizei mCurrentTexCoordsStride;

    bool mTexCoordsArrayEnabled;

    // Global index buffer
    GLuint mQuadListIndices;
};

} /* namespace uirenderer */
} /* namespace android */

#endif  // RENDERSTATE_MESHSTATE_H
