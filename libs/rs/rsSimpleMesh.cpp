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

#include "rsContext.h"

using namespace android;
using namespace android::renderscript;

#include <GLES/gl.h>
#include <GLES/glext.h>

SimpleMesh::SimpleMesh(Context *rsc) : ObjectBase(rsc)
{
    mAllocFile = __FILE__;
    mAllocLine = __LINE__;
}

SimpleMesh::~SimpleMesh()
{
    delete[] mVertexTypes;
    delete[] mVertexBuffers;
}

void SimpleMesh::render(Context *rsc) const
{
    if (mPrimitiveType.get()) {
        renderRange(rsc, 0, mPrimitiveType->getDimX());
        return;
    }

    if (mIndexType.get()) {
        renderRange(rsc, 0, mIndexType->getDimX());
        return;
    }

    renderRange(rsc, 0, mVertexTypes[0]->getDimX());
}

void SimpleMesh::renderRange(Context *rsc, uint32_t start, uint32_t len) const
{
    if (len < 1) {
        return;
    }

    rsc->checkError("SimpleMesh::renderRange 1");
    VertexArray va;
    if (rsc->checkVersion2_0()) {
        for (uint32_t ct=0; ct < mVertexTypeCount; ct++) {
            mVertexBuffers[ct]->uploadCheck(rsc);
            va.setActiveBuffer(mVertexBuffers[ct]->getBufferObjectID());
            mVertexTypes[ct]->enableGLVertexBuffer2(&va);
        }
        va.setupGL2(rsc, &rsc->mStateVertexArray, &rsc->mShaderCache);
    } else {
        for (uint32_t ct=0; ct < mVertexTypeCount; ct++) {
            mVertexBuffers[ct]->uploadCheck(rsc);
            va.setActiveBuffer(mVertexBuffers[ct]->getBufferObjectID());
            mVertexTypes[ct]->enableGLVertexBuffer(&va);
        }
        va.setupGL(rsc, 0);
    }

    rsc->checkError("SimpleMesh::renderRange 2");
    if (mIndexType.get()) {
        mIndexBuffer->uploadCheck(rsc);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, mIndexBuffer->getBufferObjectID());
        glDrawElements(mGLPrimitive, len, GL_UNSIGNED_SHORT, (uint16_t *)(start * 2));
    } else {
        glDrawArrays(mGLPrimitive, start, len);
    }

    rsc->checkError("SimpleMesh::renderRange");
}

void SimpleMesh::uploadAll(Context *rsc)
{
    for (uint32_t ct=0; ct < mVertexTypeCount; ct++) {
        if (mVertexBuffers[ct].get()) {
            mVertexBuffers[ct]->deferedUploadToBufferObject(rsc);
        }
    }
    if (mIndexBuffer.get()) {
        mIndexBuffer->deferedUploadToBufferObject(rsc);
    }
    if (mPrimitiveBuffer.get()) {
        mPrimitiveBuffer->deferedUploadToBufferObject(rsc);
    }
    rsc->checkError("SimpleMesh::uploadAll");
}


SimpleMeshContext::SimpleMeshContext()
{
}

SimpleMeshContext::~SimpleMeshContext()
{
}


namespace android {
namespace renderscript {


RsSimpleMesh rsi_SimpleMeshCreate(Context *rsc, RsType prim, RsType idx, RsType *vtx, uint32_t vtxCount, uint32_t primType)
{
    SimpleMesh *sm = new SimpleMesh(rsc);
    sm->incUserRef();

    sm->mIndexType.set((const Type *)idx);
    sm->mPrimitiveType.set((const Type *)prim);

    sm->mVertexTypeCount = vtxCount;
    sm->mVertexTypes = new ObjectBaseRef<const Type>[vtxCount];
    sm->mVertexBuffers = new ObjectBaseRef<Allocation>[vtxCount];
    for (uint32_t ct=0; ct < vtxCount; ct++) {
        sm->mVertexTypes[ct].set((const Type *)vtx[ct]);
    }

    sm->mPrimitive = (RsPrimitive)primType;
    switch(sm->mPrimitive) {
    case RS_PRIMITIVE_POINT:          sm->mGLPrimitive = GL_POINTS; break;
    case RS_PRIMITIVE_LINE:           sm->mGLPrimitive = GL_LINES; break;
    case RS_PRIMITIVE_LINE_STRIP:     sm->mGLPrimitive = GL_LINE_STRIP; break;
    case RS_PRIMITIVE_TRIANGLE:       sm->mGLPrimitive = GL_TRIANGLES; break;
    case RS_PRIMITIVE_TRIANGLE_STRIP: sm->mGLPrimitive = GL_TRIANGLE_STRIP; break;
    case RS_PRIMITIVE_TRIANGLE_FAN:   sm->mGLPrimitive = GL_TRIANGLE_FAN; break;
    }
    return sm;
}

void rsi_SimpleMeshBindVertex(Context *rsc, RsSimpleMesh mv, RsAllocation va, uint32_t slot)
{
    SimpleMesh *sm = static_cast<SimpleMesh *>(mv);
    rsAssert(slot < sm->mVertexTypeCount);

    sm->mVertexBuffers[slot].set((Allocation *)va);
}

void rsi_SimpleMeshBindIndex(Context *rsc, RsSimpleMesh mv, RsAllocation va)
{
    SimpleMesh *sm = static_cast<SimpleMesh *>(mv);
    sm->mIndexBuffer.set((Allocation *)va);
}

void rsi_SimpleMeshBindPrimitive(Context *rsc, RsSimpleMesh mv, RsAllocation va)
{
    SimpleMesh *sm = static_cast<SimpleMesh *>(mv);
    sm->mPrimitiveBuffer.set((Allocation *)va);
}




}}

