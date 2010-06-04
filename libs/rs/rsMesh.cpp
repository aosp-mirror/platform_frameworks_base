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
    mVerticies = NULL;
    mVerticiesCount = 0;
    mPrimitives = NULL;
    mPrimitivesCount = 0;
}

Mesh::~Mesh()
{
}

void Mesh::serialize(OStream *stream) const
{
    // Need to identify ourselves
    stream->addU32((uint32_t)getClassId());

    String8 name(getName());
    stream->addString(&name);

    stream->addU32(mVerticiesCount);

    for(uint32_t vCount = 0; vCount < mVerticiesCount; vCount ++) {
        Verticies_t *verts = mVerticies[vCount];

        stream->addU32(verts->mAllocationCount);

        for (uint32_t aCount = 0; aCount < verts->mAllocationCount; aCount++) {
            verts->mAllocations[aCount]->serialize(stream);
        }
        stream->addU32(verts->mVertexDataSize);

        stream->addU32(verts->mOffsetCoord);
        stream->addU32(verts->mOffsetTex);
        stream->addU32(verts->mOffsetNorm);

        stream->addU32(verts->mSizeCoord);
        stream->addU32(verts->mSizeTex);
        stream->addU32(verts->mSizeNorm );
    }

    stream->addU32(mPrimitivesCount);
    // Store the primitives
    for (uint32_t pCount = 0; pCount < mPrimitivesCount; pCount ++) {
        Primitive_t * prim = mPrimitives[pCount];

        stream->addU8((uint8_t)prim->mType);

        // We store the index to the vertices
        // So iterate over our vertices to find which one we point to
        uint32_t vertexIndex = 0;
        for(uint32_t vCount = 0; vCount < mVerticiesCount; vCount ++) {
            if(prim->mVerticies == mVerticies[vCount]) {
                vertexIndex = vCount;
                break;
            }
        }
        stream->addU32(vertexIndex);

        stream->addU32(prim->mIndexCount);
        for (uint32_t ct = 0; ct < prim->mIndexCount; ct++) {
            stream->addU16(prim->mIndicies[ct]);
        }

        stream->addU32(prim->mRestartCounts);
        for (uint32_t ct = 0; ct < prim->mRestartCounts; ct++) {
            stream->addU16(prim->mRestarts[ct]);
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

    mesh->mVerticiesCount = stream->loadU32();
    if(mesh->mVerticiesCount) {
        mesh->mVerticies = new Verticies_t *[mesh->mVerticiesCount];
    }
    else {
        mesh->mVerticies = NULL;
    }

    for(uint32_t vCount = 0; vCount < mesh->mVerticiesCount; vCount ++) {
        Verticies_t *verts = new Verticies_t();
        // Store our vertices one the mesh
        mesh->mVerticies[vCount] = verts;

        verts->mAllocationCount = stream->loadU32();
        verts->mAllocations = new Allocation *[verts->mAllocationCount];

        LOGE("processChunk_Verticies count %i", verts->mAllocationCount);
        for (uint32_t aCount = 0; aCount < verts->mAllocationCount; aCount++) {
            verts->mAllocations[aCount] = Allocation::createFromStream(rsc, stream);
        }
        verts->mVertexDataSize = stream->loadU32();

        verts->mOffsetCoord = stream->loadU32();
        verts->mOffsetTex = stream->loadU32();
        verts->mOffsetNorm = stream->loadU32();

        verts->mSizeCoord = stream->loadU32();
        verts->mSizeTex = stream->loadU32();
        verts->mSizeNorm = stream->loadU32();
    }

    mesh->mPrimitivesCount = stream->loadU32();
    if(mesh->mPrimitivesCount) {
        mesh->mPrimitives = new Primitive_t *[mesh->mPrimitivesCount];
    }
    else {
        mesh->mPrimitives = NULL;
    }

    // load all primitives
    for (uint32_t pCount = 0; pCount < mesh->mPrimitivesCount; pCount ++) {
        Primitive_t * prim = new Primitive_t;
        mesh->mPrimitives[pCount] = prim;

        prim->mType = (RsPrimitive)stream->loadU8();

        // We store the index to the vertices
        uint32_t vertexIndex = stream->loadU32();
        if(vertexIndex < mesh->mVerticiesCount) {
            prim->mVerticies = mesh->mVerticies[vertexIndex];
        }
        else {
            prim->mVerticies = NULL;
        }

        prim->mIndexCount = stream->loadU32();
        if(prim->mIndexCount){
            prim->mIndicies = new uint16_t[prim->mIndexCount];
            for (uint32_t ct = 0; ct < prim->mIndexCount; ct++) {
                prim->mIndicies[ct] = stream->loadU16();
            }
        }
        else {
            prim->mIndicies = NULL;
        }

        prim->mRestartCounts = stream->loadU32();
        if (prim->mRestartCounts) {
            prim->mRestarts = new uint16_t[prim->mRestartCounts];
            for (uint32_t ct = 0; ct < prim->mRestartCounts; ct++) {
                prim->mRestarts[ct] = stream->loadU16();

            }
        }
        else {
            prim->mRestarts = NULL;
        }

    }

    return mesh;
}


MeshContext::MeshContext()
{
}

MeshContext::~MeshContext()
{
}

