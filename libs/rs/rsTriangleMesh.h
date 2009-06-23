/*
 * Copyright (C) 2009 The Android Open Source Project
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

#ifndef ANDROID_RS_TRIANGLE_MESH_H
#define ANDROID_RS_TRIANGLE_MESH_H


#include "RenderScript.h"

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {


// An element is a group of Components that occupies one cell in a structure.
class TriangleMesh : public ObjectBase
{
public:
    TriangleMesh();
    ~TriangleMesh();

    const Element * mVertexElement;
    const Element * mIndexElement;

    void * mVertexData;
    void * mIndexData;

    size_t mVertexDataSize;
    size_t mIndexDataSize;
    uint32_t mTriangleCount;

    size_t mOffsetCoord;
    size_t mOffsetTex;
    size_t mOffsetNorm;

    size_t mSizeCoord;
    size_t mSizeTex;
    size_t mSizeNorm;

    // GL buffer info
    uint32_t mBufferObjects[2];

    void analyzeElement();
protected:
};

class TriangleMeshContext
{
public:
    TriangleMeshContext();
    ~TriangleMeshContext();

    const Element * mVertexElement;
    const Element * mIndexElement;
    size_t mVertexSizeBits;
    size_t mIndexSizeBits;

    Vector<uint8_t> mVertexData; 
    Vector<uint16_t> mIndexData; 

    uint32_t mTriangleCount;

    void clear();
};


}
}
#endif //ANDROID_RS_TRIANGLE_MESH_H

