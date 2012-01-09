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

#include "rsContext.h"

using namespace android;
using namespace android::renderscript;

Mesh::Mesh(Context *rsc) : ObjectBase(rsc) {
    mHal.drv = NULL;
    mHal.state.primitives = NULL;
    mHal.state.primitivesCount = 0;
    mHal.state.indexBuffers = NULL;
    mHal.state.indexBuffersCount = 0;
    mHal.state.vertexBuffers = NULL;
    mHal.state.vertexBuffersCount = 0;
    mInitialized = false;

    mVertexBuffers = NULL;
    mIndexBuffers = NULL;
}

Mesh::Mesh(Context *rsc,
           uint32_t vertexBuffersCount,
           uint32_t primitivesCount) : ObjectBase(rsc) {
    mHal.drv = NULL;
    mHal.state.primitivesCount = primitivesCount;
    mHal.state.indexBuffersCount = primitivesCount;
    mHal.state.primitives = new RsPrimitive[mHal.state.primitivesCount];
    mHal.state.indexBuffers = new Allocation *[mHal.state.indexBuffersCount];
    for (uint32_t i = 0; i < mHal.state.primitivesCount; i ++) {
        mHal.state.primitives[i] = RS_PRIMITIVE_POINT;
    }
    for (uint32_t i = 0; i < mHal.state.indexBuffersCount; i ++) {
        mHal.state.indexBuffers[i] = NULL;
    }
    mHal.state.vertexBuffersCount = vertexBuffersCount;
    mHal.state.vertexBuffers = new Allocation *[mHal.state.vertexBuffersCount];
    for (uint32_t i = 0; i < mHal.state.vertexBuffersCount; i ++) {
        mHal.state.vertexBuffers[i] = NULL;
    }

    mVertexBuffers = new ObjectBaseRef<Allocation>[mHal.state.vertexBuffersCount];
    mIndexBuffers = new ObjectBaseRef<Allocation>[mHal.state.primitivesCount];
}

Mesh::~Mesh() {
#ifndef ANDROID_RS_SERIALIZE
    mRSC->mHal.funcs.mesh.destroy(mRSC, this);
#endif

    delete[] mHal.state.vertexBuffers;
    delete[] mHal.state.primitives;
    delete[] mHal.state.indexBuffers;

    delete[] mVertexBuffers;
    delete[] mIndexBuffers;
}

void Mesh::init() {
#ifndef ANDROID_RS_SERIALIZE
    mRSC->mHal.funcs.mesh.init(mRSC, this);
#endif
}

void Mesh::serialize(OStream *stream) const {
    // Need to identify ourselves
    stream->addU32((uint32_t)getClassId());

    String8 name(getName());
    stream->addString(&name);

    // Store number of vertex streams
    stream->addU32(mHal.state.vertexBuffersCount);
    for (uint32_t vCount = 0; vCount < mHal.state.vertexBuffersCount; vCount ++) {
        mHal.state.vertexBuffers[vCount]->serialize(stream);
    }

    stream->addU32(mHal.state.primitivesCount);
    // Store the primitives
    for (uint32_t pCount = 0; pCount < mHal.state.primitivesCount; pCount ++) {
        stream->addU8((uint8_t)mHal.state.primitives[pCount]);

        if (mHal.state.indexBuffers[pCount]) {
            stream->addU32(1);
            mHal.state.indexBuffers[pCount]->serialize(stream);
        } else {
            stream->addU32(0);
        }
    }
}

Mesh *Mesh::createFromStream(Context *rsc, IStream *stream) {
    // First make sure we are reading the correct object
    RsA3DClassID classID = (RsA3DClassID)stream->loadU32();
    if (classID != RS_A3D_CLASS_ID_MESH) {
        ALOGE("mesh loading skipped due to invalid class id");
        return NULL;
    }

    String8 name;
    stream->loadString(&name);

    uint32_t vertexBuffersCount = stream->loadU32();
    ObjectBaseRef<Allocation> *vertexBuffers = NULL;
    if (vertexBuffersCount) {
        vertexBuffers = new ObjectBaseRef<Allocation>[vertexBuffersCount];

        for (uint32_t vCount = 0; vCount < vertexBuffersCount; vCount ++) {
            Allocation *vertexAlloc = Allocation::createFromStream(rsc, stream);
            vertexBuffers[vCount].set(vertexAlloc);
        }
    }

    uint32_t primitivesCount = stream->loadU32();
    ObjectBaseRef<Allocation> *indexBuffers = NULL;
    RsPrimitive *primitives = NULL;
    if (primitivesCount) {
        indexBuffers = new ObjectBaseRef<Allocation>[primitivesCount];
        primitives = new RsPrimitive[primitivesCount];

        // load all primitives
        for (uint32_t pCount = 0; pCount < primitivesCount; pCount ++) {
            primitives[pCount] = (RsPrimitive)stream->loadU8();

            // Check to see if the index buffer was stored
            uint32_t isIndexPresent = stream->loadU32();
            if (isIndexPresent) {
                Allocation *indexAlloc = Allocation::createFromStream(rsc, stream);
                indexBuffers[pCount].set(indexAlloc);
            }
        }
    }

    Mesh *mesh = new Mesh(rsc, vertexBuffersCount, primitivesCount);
    mesh->setName(name.string(), name.size());
    for (uint32_t vCount = 0; vCount < vertexBuffersCount; vCount ++) {
        mesh->setVertexBuffer(vertexBuffers[vCount].get(), vCount);
    }
    for (uint32_t pCount = 0; pCount < primitivesCount; pCount ++) {
        mesh->setPrimitive(indexBuffers[pCount].get(), primitives[pCount], pCount);
    }

    // Cleanup
    if (vertexBuffersCount) {
        delete[] vertexBuffers;
    }
    if (primitivesCount) {
        delete[] indexBuffers;
        delete[] primitives;
    }

#ifndef ANDROID_RS_SERIALIZE
    mesh->init();
    mesh->uploadAll(rsc);
#endif
    return mesh;
}

void Mesh::render(Context *rsc) const {
    for (uint32_t ct = 0; ct < mHal.state.primitivesCount; ct ++) {
        renderPrimitive(rsc, ct);
    }
}

void Mesh::renderPrimitive(Context *rsc, uint32_t primIndex) const {
    if (primIndex >= mHal.state.primitivesCount) {
        ALOGE("Invalid primitive index");
        return;
    }

    if (mHal.state.indexBuffers[primIndex]) {
        renderPrimitiveRange(rsc, primIndex, 0, mHal.state.indexBuffers[primIndex]->getType()->getDimX());
        return;
    }

    renderPrimitiveRange(rsc, primIndex, 0, mHal.state.vertexBuffers[0]->getType()->getDimX());
}

void Mesh::renderPrimitiveRange(Context *rsc, uint32_t primIndex, uint32_t start, uint32_t len) const {
    if (len < 1 || primIndex >= mHal.state.primitivesCount) {
        ALOGE("Invalid mesh or parameters");
        return;
    }

    mRSC->mHal.funcs.mesh.draw(mRSC, this, primIndex, start, len);
}

void Mesh::uploadAll(Context *rsc) {
    for (uint32_t ct = 0; ct < mHal.state.vertexBuffersCount; ct ++) {
        if (mHal.state.vertexBuffers[ct]) {
            rsc->mHal.funcs.allocation.markDirty(rsc, mHal.state.vertexBuffers[ct]);
        }
    }

    for (uint32_t ct = 0; ct < mHal.state.primitivesCount; ct ++) {
        if (mHal.state.indexBuffers[ct]) {
            rsc->mHal.funcs.allocation.markDirty(rsc, mHal.state.indexBuffers[ct]);
        }
    }
}

void Mesh::computeBBox() {
    float *posPtr = NULL;
    uint32_t vectorSize = 0;
    uint32_t stride = 0;
    uint32_t numVerts = 0;
    // First we need to find the position ptr and stride
    for (uint32_t ct=0; ct < mHal.state.vertexBuffersCount; ct++) {
        const Type *bufferType = mHal.state.vertexBuffers[ct]->getType();
        const Element *bufferElem = bufferType->getElement();

        for (uint32_t ct=0; ct < bufferElem->getFieldCount(); ct++) {
            if (strcmp(bufferElem->getFieldName(ct), "position") == 0) {
                vectorSize = bufferElem->getField(ct)->getComponent().getVectorSize();
                stride = bufferElem->getSizeBytes() / sizeof(float);
                uint32_t offset = bufferElem->getFieldOffsetBytes(ct);
                posPtr = (float*)((uint8_t*)mHal.state.vertexBuffers[ct]->getPtr() + offset);
                numVerts = bufferType->getDimX();
                break;
            }
        }
        if (posPtr) {
            break;
        }
    }

    mBBoxMin[0] = mBBoxMin[1] = mBBoxMin[2] = 1e6;
    mBBoxMax[0] = mBBoxMax[1] = mBBoxMax[2] = -1e6;
    if (!posPtr) {
        ALOGE("Unable to compute bounding box");
        mBBoxMin[0] = mBBoxMin[1] = mBBoxMin[2] = 0.0f;
        mBBoxMax[0] = mBBoxMax[1] = mBBoxMax[2] = 0.0f;
        return;
    }

    for (uint32_t i = 0; i < numVerts; i ++) {
        for (uint32_t v = 0; v < vectorSize; v ++) {
            mBBoxMin[v] = rsMin(mBBoxMin[v], posPtr[v]);
            mBBoxMax[v] = rsMax(mBBoxMax[v], posPtr[v]);
        }
        posPtr += stride;
    }
}

namespace android {
namespace renderscript {

RsMesh rsi_MeshCreate(Context *rsc,
                      RsAllocation * vtx, size_t vtxCount,
                      RsAllocation * idx, size_t idxCount,
                      uint32_t * primType, size_t primTypeCount) {
    rsAssert(idxCount == primTypeCount);
    Mesh *sm = new Mesh(rsc, vtxCount, idxCount);
    sm->incUserRef();

    for (uint32_t i = 0; i < vtxCount; i ++) {
        sm->setVertexBuffer((Allocation*)vtx[i], i);
    }

    for (uint32_t i = 0; i < idxCount; i ++) {
        sm->setPrimitive((Allocation*)idx[i], (RsPrimitive)primType[i], i);
    }

    sm->init();

    return sm;
}

}}

void rsaMeshGetVertexBufferCount(RsContext con, RsMesh mv, int32_t *numVtx) {
    Mesh *sm = static_cast<Mesh *>(mv);
    *numVtx = sm->mHal.state.vertexBuffersCount;
}

void rsaMeshGetIndexCount(RsContext con, RsMesh mv, int32_t *numIdx) {
    Mesh *sm = static_cast<Mesh *>(mv);
    *numIdx = sm->mHal.state.primitivesCount;
}

void rsaMeshGetVertices(RsContext con, RsMesh mv, RsAllocation *vtxData, uint32_t vtxDataCount) {
    Mesh *sm = static_cast<Mesh *>(mv);
    rsAssert(vtxDataCount == sm->mHal.state.vertexBuffersCount);

    for (uint32_t ct = 0; ct < vtxDataCount; ct ++) {
        vtxData[ct] = sm->mHal.state.vertexBuffers[ct];
        sm->mHal.state.vertexBuffers[ct]->incUserRef();
    }
}

void rsaMeshGetIndices(RsContext con, RsMesh mv, RsAllocation *va, uint32_t *primType, uint32_t idxDataCount) {
    Mesh *sm = static_cast<Mesh *>(mv);
    rsAssert(idxDataCount == sm->mHal.state.primitivesCount);

    for (uint32_t ct = 0; ct < idxDataCount; ct ++) {
        va[ct] = sm->mHal.state.indexBuffers[ct];
        primType[ct] = sm->mHal.state.primitives[ct];
        if (sm->mHal.state.indexBuffers[ct]) {
            sm->mHal.state.indexBuffers[ct]->incUserRef();
        }
    }
}
