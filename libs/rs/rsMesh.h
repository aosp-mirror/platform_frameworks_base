/*
 * Copyright (C) 2011 The Android Open Source Project
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
class Mesh : public ObjectBase {
public:
    Mesh(Context *);
    Mesh(Context *, uint32_t vertexBuffersCount, uint32_t primitivesCount);
    ~Mesh();

    // Either mIndexBuffer, mPrimitiveBuffer or both could have a NULL reference
    // If both are null, mPrimitive only would be used to render the mesh
    struct Primitive_t {
        ObjectBaseRef<Allocation> mIndexBuffer;
        RsPrimitive mPrimitive;
    };

    virtual void serialize(OStream *stream) const;
    virtual RsA3DClassID getClassId() const { return RS_A3D_CLASS_ID_MESH; }
    static Mesh *createFromStream(Context *rsc, IStream *stream);
    void init();

    struct Hal {
        mutable void *drv;

        struct State {
            // Contains vertex data
            // Position, normal, texcoord, etc could either be strided in one allocation
            // of provided separetely in multiple ones
            ObjectBaseRef<Allocation> *vertexBuffers;
            uint32_t vertexBuffersCount;

            Primitive_t ** primitives;
            uint32_t primitivesCount;
        };
        State state;
    };
    Hal mHal;

    void setVertexBuffer(Allocation *vb, uint32_t index) {
        mHal.state.vertexBuffers[index].set(vb);
    }

    void setPrimitive(Allocation *idx, RsPrimitive prim, uint32_t index) {
        mHal.state.primitives[index]->mIndexBuffer.set(idx);
        mHal.state.primitives[index]->mPrimitive = prim;
    }

    void render(Context *) const;
    void renderPrimitive(Context *, uint32_t primIndex) const;
    void renderPrimitiveRange(Context *, uint32_t primIndex, uint32_t start, uint32_t len) const;
    void uploadAll(Context *);

    // Bounding volumes
    float mBBoxMin[3];
    float mBBoxMax[3];
    void computeBBox();
protected:
    bool mInitialized;
};

class MeshContext {
public:
    MeshContext() {
    }
    ~MeshContext() {
    }
};

}
}
#endif //ANDROID_RS_MESH_H



