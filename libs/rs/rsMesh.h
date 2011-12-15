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
    struct Hal {
        mutable void *drv;

        struct State {
            // Contains vertex data
            // Position, normal, texcoord, etc could either be strided in one allocation
            // of provided separetely in multiple ones
            Allocation **vertexBuffers;
            uint32_t vertexBuffersCount;

            // indexBuffers[i] could be NULL, in which case only primitives[i] is used
            Allocation **indexBuffers;
            uint32_t indexBuffersCount;
            RsPrimitive *primitives;
            uint32_t primitivesCount;
        };
        State state;
    };
    Hal mHal;

    Mesh(Context *);
    Mesh(Context *, uint32_t vertexBuffersCount, uint32_t primitivesCount);
    ~Mesh();

    virtual void serialize(OStream *stream) const;
    virtual RsA3DClassID getClassId() const { return RS_A3D_CLASS_ID_MESH; }
    static Mesh *createFromStream(Context *rsc, IStream *stream);
    void init();

    void setVertexBuffer(Allocation *vb, uint32_t index) {
        mVertexBuffers[index].set(vb);
        mHal.state.vertexBuffers[index] = vb;
    }

    void setPrimitive(Allocation *idx, RsPrimitive prim, uint32_t index) {
        mIndexBuffers[index].set(idx);
        mHal.state.indexBuffers[index] = idx;
        mHal.state.primitives[index] = prim;
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
    ObjectBaseRef<Allocation> *mVertexBuffers;
    ObjectBaseRef<Allocation> *mIndexBuffers;
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



