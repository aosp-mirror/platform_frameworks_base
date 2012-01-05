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

#ifndef ANDROID_RSD_MESH_OBJ_H
#define ANDROID_RSD_MESH_OBJ_H

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {

    class Context;
    class Mesh;
    class Element;

}
}

#include "driver/rsdVertexArray.h"

// An element is a group of Components that occupies one cell in a structure.
class RsdMeshObj {
public:
    RsdMeshObj(const android::renderscript::Context *,
            const android::renderscript::Mesh *);
    ~RsdMeshObj();

    void renderPrimitiveRange(const android::renderscript::Context *,
                              uint32_t primIndex, uint32_t start, uint32_t len) const;

    bool init(const android::renderscript::Context *rsc);

protected:
    const android::renderscript::Mesh *mRSMesh;

    uint32_t *mGLPrimitives;
    void updateGLPrimitives(const android::renderscript::Context *rsc);

    bool isValidGLComponent(const android::renderscript::Element *elem, uint32_t fieldIdx);
    // Attribues that allow us to map to GL
    RsdVertexArray::Attrib *mAttribs;
    // This allows us to figure out which allocation the attribute
    // belongs to. In the event the allocation is uploaded to GL
    // buffer, it lets us properly map it
    uint32_t *mAttribAllocationIndex;
    uint32_t mAttribCount;
};

#endif //ANDROID_RSD_MESH_OBJ_H



