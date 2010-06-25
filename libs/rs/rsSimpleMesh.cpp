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

#ifndef ANDROID_RS_BUILD_FOR_HOST
#include "rsContext.h"

#include <GLES/gl.h>
#include <GLES2/gl2.h>
#include <GLES/glext.h>
#else
#include "rsContextHostStub.h"

#include <OpenGL/gl.h>
#include <OpenGl/glext.h>
#endif

using namespace android;
using namespace android::renderscript;




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
    for (uint32_t ct=0; ct < mVertexTypeCount; ct++) {
        mVertexBuffers[ct]->uploadCheck(rsc);
        if (mVertexBuffers[ct]->getIsBufferObject()) {
            va.setActiveBuffer(mVertexBuffers[ct]->getBufferObjectID());
        } else {
            va.setActiveBuffer(mVertexBuffers[ct]->getPtr());
        }
        mVertexTypes[ct]->enableGLVertexBuffer(&va);
    }
    va.setupGL2(rsc, &rsc->mStateVertexArray, &rsc->mShaderCache);

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

void SimpleMesh::updateGLPrimitive()
{
    switch(mPrimitive) {
        case RS_PRIMITIVE_POINT:          mGLPrimitive = GL_POINTS; break;
        case RS_PRIMITIVE_LINE:           mGLPrimitive = GL_LINES; break;
        case RS_PRIMITIVE_LINE_STRIP:     mGLPrimitive = GL_LINE_STRIP; break;
        case RS_PRIMITIVE_TRIANGLE:       mGLPrimitive = GL_TRIANGLES; break;
        case RS_PRIMITIVE_TRIANGLE_STRIP: mGLPrimitive = GL_TRIANGLE_STRIP; break;
        case RS_PRIMITIVE_TRIANGLE_FAN:   mGLPrimitive = GL_TRIANGLE_FAN; break;
    }
}

void SimpleMesh::serialize(OStream *stream) const
{
    // Need to identify ourselves
    stream->addU32((uint32_t)getClassId());

    String8 name(getName());
    stream->addString(&name);

    // Add primitive type
    stream->addU8((uint8_t)mPrimitive);

    // And now serialize the allocations
    mIndexBuffer->serialize(stream);

    // We need to indicate if the primitive buffer is present
    if(mPrimitiveBuffer.get() != NULL) {
        // Write if the primitive buffer is present
        stream->addU32(1);
        mPrimitiveBuffer->serialize(stream);
    }
    else {
        // No buffer present, will need this when we read
        stream->addU32(0);
    }

    // Store number of vertex streams
    stream->addU32(mVertexTypeCount);
    for(uint32_t vCount = 0; vCount < mVertexTypeCount; vCount ++) {
        mVertexBuffers[vCount]->serialize(stream);
    }
}

SimpleMesh *SimpleMesh::createFromStream(Context *rsc, IStream *stream)
{
    // First make sure we are reading the correct object
    RsA3DClassID classID = (RsA3DClassID)stream->loadU32();
    if(classID != RS_A3D_CLASS_ID_SIMPLE_MESH) {
        LOGE("simple mesh loading skipped due to invalid class id");
        return NULL;
    }

    SimpleMesh * mesh = new SimpleMesh(rsc);

    String8 name;
    stream->loadString(&name);
    mesh->setName(name.string(), name.size());

    mesh->mPrimitive = (RsPrimitive)stream->loadU8();
    mesh->updateGLPrimitive();

    Allocation *indexAlloc = Allocation::createFromStream(rsc, stream);
    const Type *indexType = indexAlloc->getType();
    mesh->mIndexBuffer.set(indexAlloc);
    mesh->mIndexType.set(indexType);

    bool isPrimitivePresent = stream->loadU32() != 0;
    if(isPrimitivePresent) {
        mesh->mPrimitiveBuffer.set(Allocation::createFromStream(rsc, stream));
        mesh->mPrimitiveType.set(mesh->mPrimitiveBuffer->getType());
    }

    mesh->mVertexTypeCount = stream->loadU32();
    if(mesh->mVertexTypeCount) {
        mesh->mVertexTypes = new ObjectBaseRef<const Type>[mesh->mVertexTypeCount];
        mesh->mVertexBuffers = new ObjectBaseRef<Allocation>[mesh->mVertexTypeCount];

        for(uint32_t vCount = 0; vCount < mesh->mVertexTypeCount; vCount ++) {
            Allocation *vertexAlloc = Allocation::createFromStream(rsc, stream);
            const Type *vertexType = vertexAlloc->getType();
            mesh->mVertexBuffers[vCount].set(vertexAlloc);
            mesh->mVertexTypes[vCount].set(vertexType);
        }
    }

    mesh->uploadAll(rsc);

    return mesh;
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
    sm->updateGLPrimitive();
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

