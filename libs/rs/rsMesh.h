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
    Mesh(Context *);
    ~Mesh();

    // Contains vertex data
    // Position, normal, texcoord, etc could either be strided in one allocation
    // of provided separetely in multiple ones
    ObjectBaseRef<Allocation> *mVertexBuffers;
    ObjectBaseRef<const Type> *mVertexTypes;
    uint32_t mVertexBufferCount;

    // Either mIndexBuffer, mPrimitiveBuffer or both could have a NULL reference
    // If both are null, mPrimitive only would be used to render the mesh
    struct Primitive_t
    {
        ObjectBaseRef<Allocation> mIndexBuffer;
        ObjectBaseRef<Allocation> mPrimitiveBuffer;
        ObjectBaseRef<const Type> mIndexType;
        ObjectBaseRef<const Type> mPrimitiveType;

        RsPrimitive mPrimitive;
        uint32_t mGLPrimitive;
    };

    Primitive_t ** mPrimitives;
    uint32_t mPrimitivesCount;

    void render(Context *) const;
    void renderPrimitive(Context *, uint32_t primIndex) const;
    void renderPrimitiveRange(Context *, uint32_t primIndex, uint32_t start, uint32_t len) const;
    void uploadAll(Context *);
    void updateGLPrimitives();

    virtual void serialize(OStream *stream) const;
    virtual RsA3DClassID getClassId() const { return RS_A3D_CLASS_ID_MESH; }
    static Mesh *createFromStream(Context *rsc, IStream *stream);

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



