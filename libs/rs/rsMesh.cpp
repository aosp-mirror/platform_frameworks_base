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

Mesh::Mesh(Context *rsc) : ObjectBase(rsc)
{
    mAllocFile = __FILE__;
    mAllocLine = __LINE__;
    mPrimitives = NULL;
    mPrimitivesCount = 0;
    mVertexBuffers = NULL;
    mVertexTypes = NULL;
    mVertexBufferCount = 0;
}

Mesh::~Mesh()
{
    if(mVertexTypes) {
        delete[] mVertexTypes;
    }

    if(mVertexBuffers) {
        delete[] mVertexBuffers;
    }

    if(mPrimitives) {
        for(uint32_t i = 0; i < mPrimitivesCount; i ++) {
            delete mPrimitives[i];
        }
        delete[] mPrimitives;
    }
}

void Mesh::render(Context *rsc) const
{
    for(uint32_t ct = 0; ct < mPrimitivesCount; ct ++) {
        renderPrimitive(rsc, ct);
    }
}

void Mesh::renderPrimitive(Context *rsc, uint32_t primIndex) const {
    if (primIndex >= mPrimitivesCount) {
        LOGE("Invalid primitive index");
        return;
    }

    Primitive_t *prim = mPrimitives[primIndex];

    if (prim->mIndexBuffer.get()) {
        renderPrimitiveRange(rsc, primIndex, 0, prim->mIndexBuffer->getType()->getDimX());
        return;
    }

    if (prim->mPrimitiveBuffer.get()) {
        renderPrimitiveRange(rsc, primIndex, 0, prim->mPrimitiveBuffer->getType()->getDimX());
        return;
    }

    renderPrimitiveRange(rsc, primIndex, 0, mVertexBuffers[0]->getType()->getDimX());
}

void Mesh::renderPrimitiveRange(Context *rsc, uint32_t primIndex, uint32_t start, uint32_t len) const
{
    if (len < 1 || primIndex >= mPrimitivesCount) {
        return;
    }

    rsc->checkError("Mesh::renderPrimitiveRange 1");
    VertexArray va;
    for (uint32_t ct=0; ct < mVertexBufferCount; ct++) {
        mVertexBuffers[ct]->uploadCheck(rsc);
        if (mVertexBuffers[ct]->getIsBufferObject()) {
            va.setActiveBuffer(mVertexBuffers[ct]->getBufferObjectID());
        } else {
            va.setActiveBuffer(mVertexBuffers[ct]->getPtr());
        }
        mVertexBuffers[ct]->getType()->enableGLVertexBuffer(&va);
    }
    va.setupGL2(rsc, &rsc->mStateVertexArray, &rsc->mShaderCache);

    rsc->checkError("Mesh::renderPrimitiveRange 2");
    Primitive_t *prim = mPrimitives[primIndex];
    if (prim->mIndexBuffer.get()) {
        prim->mIndexBuffer->uploadCheck(rsc);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, prim->mIndexBuffer->getBufferObjectID());
        glDrawElements(prim->mGLPrimitive, len, GL_UNSIGNED_SHORT, (uint16_t *)(start * 2));
    } else {
        glDrawArrays(prim->mGLPrimitive, start, len);
    }

    rsc->checkError("Mesh::renderPrimitiveRange");
}


void Mesh::uploadAll(Context *rsc)
{
    for (uint32_t ct = 0; ct < mVertexBufferCount; ct ++) {
        if (mVertexBuffers[ct].get()) {
            mVertexBuffers[ct]->deferedUploadToBufferObject(rsc);
        }
    }

    for (uint32_t ct = 0; ct < mPrimitivesCount; ct ++) {
        if (mPrimitives[ct]->mIndexBuffer.get()) {
            mPrimitives[ct]->mIndexBuffer->deferedUploadToBufferObject(rsc);
        }
        if (mPrimitives[ct]->mPrimitiveBuffer.get()) {
            mPrimitives[ct]->mPrimitiveBuffer->deferedUploadToBufferObject(rsc);
        }
    }

    rsc->checkError("Mesh::uploadAll");
}

void Mesh::updateGLPrimitives()
{
    for(uint32_t i = 0; i < mPrimitivesCount; i ++) {
        switch(mPrimitives[i]->mPrimitive) {
            case RS_PRIMITIVE_POINT:          mPrimitives[i]->mGLPrimitive = GL_POINTS; break;
            case RS_PRIMITIVE_LINE:           mPrimitives[i]->mGLPrimitive = GL_LINES; break;
            case RS_PRIMITIVE_LINE_STRIP:     mPrimitives[i]->mGLPrimitive = GL_LINE_STRIP; break;
            case RS_PRIMITIVE_TRIANGLE:       mPrimitives[i]->mGLPrimitive = GL_TRIANGLES; break;
            case RS_PRIMITIVE_TRIANGLE_STRIP: mPrimitives[i]->mGLPrimitive = GL_TRIANGLE_STRIP; break;
            case RS_PRIMITIVE_TRIANGLE_FAN:   mPrimitives[i]->mGLPrimitive = GL_TRIANGLE_FAN; break;
        }
    }
}

void Mesh::serialize(OStream *stream) const
{
    // Need to identify ourselves
    stream->addU32((uint32_t)getClassId());

    String8 name(getName());
    stream->addString(&name);

    // Store number of vertex streams
    stream->addU32(mVertexBufferCount);
    for(uint32_t vCount = 0; vCount < mVertexBufferCount; vCount ++) {
        mVertexBuffers[vCount]->serialize(stream);
    }

    stream->addU32(mPrimitivesCount);
    // Store the primitives
    for (uint32_t pCount = 0; pCount < mPrimitivesCount; pCount ++) {
        Primitive_t * prim = mPrimitives[pCount];

        stream->addU8((uint8_t)prim->mPrimitive);

        if(prim->mIndexBuffer.get()) {
            stream->addU32(1);
            prim->mIndexBuffer->serialize(stream);
        }
        else {
            stream->addU32(0);
        }

        if(prim->mPrimitiveBuffer.get()) {
            stream->addU32(1);
            prim->mPrimitiveBuffer->serialize(stream);
        }
        else {
            stream->addU32(0);
        }
    }
}

Mesh *Mesh::createFromStream(Context *rsc, IStream *stream)
{
    // First make sure we are reading the correct object
    RsA3DClassID classID = (RsA3DClassID)stream->loadU32();
    if(classID != RS_A3D_CLASS_ID_MESH) {
        LOGE("mesh loading skipped due to invalid class id");
        return NULL;
    }

    Mesh * mesh = new Mesh(rsc);

    String8 name;
    stream->loadString(&name);
    mesh->setName(name.string(), name.size());

    mesh->mVertexBufferCount = stream->loadU32();
    if(mesh->mVertexBufferCount) {
        mesh->mVertexBuffers = new ObjectBaseRef<Allocation>[mesh->mVertexBufferCount];

        for(uint32_t vCount = 0; vCount < mesh->mVertexBufferCount; vCount ++) {
            Allocation *vertexAlloc = Allocation::createFromStream(rsc, stream);
            mesh->mVertexBuffers[vCount].set(vertexAlloc);
        }
    }

    mesh->mPrimitivesCount = stream->loadU32();
    if(mesh->mPrimitivesCount) {
        mesh->mPrimitives = new Primitive_t *[mesh->mPrimitivesCount];

        // load all primitives
        for (uint32_t pCount = 0; pCount < mesh->mPrimitivesCount; pCount ++) {
            Primitive_t * prim = new Primitive_t;
            mesh->mPrimitives[pCount] = prim;

            prim->mPrimitive = (RsPrimitive)stream->loadU8();

            // Check to see if the index buffer was stored
            uint32_t isIndexPresent = stream->loadU32();
            if(isIndexPresent) {
                Allocation *indexAlloc = Allocation::createFromStream(rsc, stream);
                prim->mIndexBuffer.set(indexAlloc);
            }

            // Check to see if the primitive buffer was stored
            uint32_t isPrimitivePresent = stream->loadU32();
            if(isPrimitivePresent) {
                Allocation *primitiveAlloc = Allocation::createFromStream(rsc, stream);
                prim->mPrimitiveBuffer.set(primitiveAlloc);
            }
        }
    }

    mesh->updateGLPrimitives();
    mesh->uploadAll(rsc);

    return mesh;
}


MeshContext::MeshContext()
{
}

MeshContext::~MeshContext()
{
}

namespace android {
namespace renderscript {

RsMesh rsi_MeshCreate(Context *rsc, uint32_t vtxCount, uint32_t idxCount)
{
    Mesh *sm = new Mesh(rsc);
    sm->incUserRef();

    sm->mPrimitivesCount = idxCount;
    sm->mPrimitives = new Mesh::Primitive_t *[sm->mPrimitivesCount];
    for(uint32_t ct = 0; ct < idxCount; ct ++) {
        sm->mPrimitives[ct] = new Mesh::Primitive_t;
    }

    sm->mVertexBufferCount = vtxCount;
    sm->mVertexBuffers = new ObjectBaseRef<Allocation>[vtxCount];
    sm->mVertexTypes = new ObjectBaseRef<const Type>[vtxCount];

    return sm;
}

void rsi_MeshBindVertex(Context *rsc, RsMesh mv, RsAllocation va, uint32_t slot)
{
    Mesh *sm = static_cast<Mesh *>(mv);
    rsAssert(slot < sm->mVertexBufferCount);

    sm->mVertexBuffers[slot].set((Allocation *)va);
}

void rsi_MeshBindIndex(Context *rsc, RsMesh mv, RsAllocation va, uint32_t primType, uint32_t slot)
{
    Mesh *sm = static_cast<Mesh *>(mv);
    rsAssert(slot < sm->mPrimitivesCount);

    sm->mPrimitives[slot]->mIndexBuffer.set((Allocation *)va);
    sm->mPrimitives[slot]->mPrimitive = (RsPrimitive)primType;
    sm->updateGLPrimitives();
}

void rsi_MeshBindPrimitive(Context *rsc, RsMesh mv, RsAllocation va, uint32_t primType, uint32_t slot)
{
    Mesh *sm = static_cast<Mesh *>(mv);
    rsAssert(slot < sm->mPrimitivesCount);

    sm->mPrimitives[slot]->mPrimitiveBuffer.set((Allocation *)va);
    sm->mPrimitives[slot]->mPrimitive = (RsPrimitive)primType;
    sm->updateGLPrimitives();
}


// Route all the simple mesh through mesh

RsMesh rsi_SimpleMeshCreate(Context *rsc, RsType prim, RsType idx, RsType *vtx, uint32_t vtxCount, uint32_t primType)
{
    Mesh *sm = new Mesh(rsc);
    sm->incUserRef();

    sm->mPrimitivesCount = 1;
    sm->mPrimitives = new Mesh::Primitive_t *[sm->mPrimitivesCount];
    sm->mPrimitives[0] = new Mesh::Primitive_t;

    sm->mPrimitives[0]->mIndexType.set((const Type *)idx);
    sm->mPrimitives[0]->mPrimitiveType.set((const Type *)prim);
    sm->mPrimitives[0]->mPrimitive = (RsPrimitive)primType;
    sm->updateGLPrimitives();

    sm->mVertexBufferCount = vtxCount;
    sm->mVertexTypes = new ObjectBaseRef<const Type>[vtxCount];
    sm->mVertexBuffers = new ObjectBaseRef<Allocation>[vtxCount];
    for (uint32_t ct=0; ct < vtxCount; ct++) {
        sm->mVertexTypes[ct].set((const Type *)vtx[ct]);
    }

    return sm;
}

void rsi_SimpleMeshBindVertex(Context *rsc, RsMesh mv, RsAllocation va, uint32_t slot)
{
    Mesh *sm = static_cast<Mesh *>(mv);
    rsAssert(slot < sm->mVertexBufferCount);

    sm->mVertexBuffers[slot].set((Allocation *)va);
}

void rsi_SimpleMeshBindIndex(Context *rsc, RsMesh mv, RsAllocation va)
{
    Mesh *sm = static_cast<Mesh *>(mv);
    sm->mPrimitives[0]->mIndexBuffer.set((Allocation *)va);
}

void rsi_SimpleMeshBindPrimitive(Context *rsc, RsMesh mv, RsAllocation va)
{
    Mesh *sm = static_cast<Mesh *>(mv);
    sm->mPrimitives[0]->mPrimitiveBuffer.set((Allocation *)va);
}




}}
