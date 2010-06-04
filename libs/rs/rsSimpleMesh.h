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

#ifndef ANDROID_RS_SIMPLE_MESH_H
#define ANDROID_RS_SIMPLE_MESH_H


#include "RenderScript.h"

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {


// An element is a group of Components that occupies one cell in a structure.
class SimpleMesh : public ObjectBase
{
public:
    SimpleMesh(Context *);
    ~SimpleMesh();

    ObjectBaseRef<const Type> mIndexType;
    ObjectBaseRef<const Type> mPrimitiveType;
    ObjectBaseRef<const Type> *mVertexTypes;
    uint32_t mVertexTypeCount;

    ObjectBaseRef<Allocation> mIndexBuffer;
    ObjectBaseRef<Allocation> mPrimitiveBuffer;
    ObjectBaseRef<Allocation> *mVertexBuffers;

    RsPrimitive mPrimitive;
    uint32_t mGLPrimitive;


    void render(Context *) const;
    void renderRange(Context *, uint32_t start, uint32_t len) const;
    void uploadAll(Context *);
    void updateGLPrimitive();

    virtual void serialize(OStream *stream) const;
    virtual RsA3DClassID getClassId() const { return RS_A3D_CLASS_ID_SIMPLE_MESH; }
    static SimpleMesh *createFromStream(Context *rsc, IStream *stream);

protected:
};

class SimpleMeshContext
{
public:
    SimpleMeshContext();
    ~SimpleMeshContext();


};


}
}
#endif //ANDROID_RS_SIMPLE_MESH_H

