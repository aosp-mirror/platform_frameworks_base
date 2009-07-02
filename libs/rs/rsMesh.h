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

#ifndef ANDROID_RS_MESH_H
#define ANDROID_RS_MESH_H


#include "RenderScript.h"

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {


// An element is a group of Components that occupies one cell in a structure.
class Mesh : public ObjectBase
{
public:
    Mesh();
    ~Mesh();

    struct VertexSource_t
    {
        const Element * mVertexElement;
        void * mVertexData;
        size_t mVertexDataSize;

        size_t mOffsetCoord;
        size_t mOffsetTex;
        size_t mOffsetNorm;
    
        size_t mSizeCoord;
        size_t mSizeTex;
        size_t mSizeNorm;

        uint32_t mBufferObject;
    };

    struct Primitive_t
    {
        RsPrimitive mType;
        const Element * mIndexElement;
        void * mVertexData;
        size_t mIndexDataSize;

        uint32_t mBufferObject;
    };

    VertexSource_t * mSources;
    Primitive_t * mPrimitives;
    uint32_t mPrimitiveCount;

    void analyzeElement();
protected:
};

class MeshContext
{
public:
    MeshContext();
    ~MeshContext();

};


}
}
#endif //ANDROID_RS_TRIANGLE_MESH_H


