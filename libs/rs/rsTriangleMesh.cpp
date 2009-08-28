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

TriangleMesh::TriangleMesh()
{
    mVertexElement = NULL;
    mIndexElement = NULL;
    mVertexData = NULL;
    mIndexData = NULL;
    mTriangleCount = 0;
    mVertexDataSize = 0;
    mIndexDataSize = 0;

    mBufferObjects[0] = 0;
    mBufferObjects[1] = 0;

    mOffsetCoord = 0;
    mOffsetTex = 0;
    mOffsetNorm = 0;

    mSizeCoord = 0;
    mSizeTex = 0;
    mSizeNorm = 0;

}

TriangleMesh::~TriangleMesh()
{
    free(mVertexData);
    free(mIndexData);
}



TriangleMeshContext::TriangleMeshContext()
{
    clear();
}

TriangleMeshContext::~TriangleMeshContext()
{
}

void TriangleMeshContext::clear()
{
    mVertexElement = NULL;
    mVertexSizeBits = 0;
    mIndexElement = NULL;
    mIndexSizeBits = 0;
    mTriangleCount = 0;
    mVertexData.clear();
    mIndexData.clear();
}

void TriangleMesh::analyzeElement()
{
    for (uint32_t ct=0; ct < mVertexElement->getComponentCount(); ct++) {
        const Component *c = mVertexElement->getComponent(ct);

        if (c->getKind() == Component::X) {
            rsAssert(mSizeCoord == 0);
            mSizeCoord = 1;
            mOffsetCoord = ct;
        }
        if (c->getKind() == Component::Y) {
            rsAssert(mSizeCoord == 1);
            mSizeCoord = 2;
        }
        if (c->getKind() == Component::Z) {
            rsAssert(mSizeCoord == 2);
            mSizeCoord = 3;
        }
        if (c->getKind() == Component::W) {
            rsAssert(mSizeCoord == 4);
            mSizeCoord = 4;
        }

        if (c->getKind() == Component::NX) {
            rsAssert(mSizeNorm == 0);
            mSizeNorm = 1;
            mOffsetNorm = ct;
        }
        if (c->getKind() == Component::NY) {
            rsAssert(mSizeNorm == 1);
            mSizeNorm = 2;
        }
        if (c->getKind() == Component::NZ) {
            rsAssert(mSizeNorm == 2);
            mSizeNorm = 3;
        }

        if (c->getKind() == Component::S) {
            rsAssert(mSizeTex == 0);
            mSizeTex = 1;
            mOffsetTex = ct;
        }
        if (c->getKind() == Component::T) {
            rsAssert(mSizeTex == 1);
            mSizeTex = 2;
        }
    }
    LOGV("TriangleMesh %i,%i  %i,%i  %i,%i", mSizeCoord, mOffsetCoord, mSizeNorm, mOffsetNorm, mSizeTex, mOffsetTex);

}


namespace android {
namespace renderscript {

void rsi_TriangleMeshBegin(Context *rsc, RsElement vertex, RsElement index)
{
    TriangleMeshContext *tmc = &rsc->mStateTriangleMesh;

    tmc->clear();
    tmc->mVertexElement = static_cast<Element *>(vertex);
    tmc->mVertexSizeBits = tmc->mVertexElement->getSizeBits();
    tmc->mIndexElement = static_cast<Element *>(index);
    tmc->mIndexSizeBits = tmc->mIndexElement->getSizeBits();

    assert(!(tmc->mVertexSizeBits & 0x7));
    assert(!(tmc->mIndexSizeBits & 0x7));
}

void rsi_TriangleMeshAddVertex(Context *rsc, const void *data)
{
    TriangleMeshContext *tmc = &rsc->mStateTriangleMesh;

    // todo: Make this efficient.
    for (uint32_t ct = 0; (ct * 8) < tmc->mVertexSizeBits; ct++) {
        tmc->mVertexData.add(static_cast<const uint8_t *>(data) [ct]);
    }
}

void rsi_TriangleMeshAddTriangle(Context *rsc, uint32_t idx1, uint32_t idx2, uint32_t idx3)
{
    TriangleMeshContext *tmc = &rsc->mStateTriangleMesh;

    // todo: Make this efficient.
    switch(tmc->mIndexSizeBits) {
    case 16:
        tmc->mIndexData.add(idx1);
        tmc->mIndexData.add(idx2);
        tmc->mIndexData.add(idx3);
        break;
    default:
        assert(0);
    }

    tmc->mTriangleCount++;
}

RsTriangleMesh rsi_TriangleMeshCreate(Context *rsc)
{
    TriangleMeshContext *tmc = &rsc->mStateTriangleMesh;

    TriangleMesh * tm = new TriangleMesh();
    if (!tm) {
        LOGE("rsTriangleMeshCreate: Error OUT OF MEMORY");
        // error
        return 0;
    }

    tm->mTriangleCount = tmc->mTriangleCount;
    tm->mIndexDataSize = tmc->mIndexData.size() * tmc->mIndexSizeBits >> 3;
    tm->mVertexDataSize = tmc->mVertexData.size();
    tm->mIndexElement = tmc->mIndexElement;
    tm->mVertexElement = tmc->mVertexElement;

    tm->mIndexData = malloc(tm->mIndexDataSize);
    tm->mVertexData = malloc(tm->mVertexDataSize);
    if (!tm->mIndexData || !tm->mVertexData) {
        LOGE("rsTriangleMeshCreate: Error OUT OF MEMORY");
        delete tm;
        return 0;
    }

    memcpy(tm->mVertexData, tmc->mVertexData.array(), tm->mVertexDataSize);
    memcpy(tm->mIndexData, tmc->mIndexData.array(), tm->mIndexDataSize);
    tm->analyzeElement();

    tm->incUserRef();
    return tm;
}

void rsi_TriangleMeshDestroy(Context *rsc, RsTriangleMesh vtm)
{
    TriangleMeshContext *tmc = &rsc->mStateTriangleMesh;
    TriangleMesh * tm = static_cast<TriangleMesh *>(vtm);

    free(tm->mIndexData);
    free(tm->mVertexData);
    delete tm;
}



void rsi_TriangleMeshRenderRange(Context *rsc, RsTriangleMesh vtm, uint32_t first, uint32_t count)
{
    TriangleMesh * tm = static_cast<TriangleMesh *>(vtm);

    rsc->setupCheck();

    if (!tm->mBufferObjects[0]) {
        glGenBuffers(2, &tm->mBufferObjects[0]);

        glBindBuffer(GL_ARRAY_BUFFER, tm->mBufferObjects[0]);
        glBufferData(GL_ARRAY_BUFFER, tm->mVertexDataSize, tm->mVertexData, GL_STATIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, tm->mBufferObjects[1]);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, tm->mIndexDataSize, tm->mIndexData, GL_STATIC_DRAW);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    if (first >= tm->mTriangleCount) {
        return;
    }
    if (count >= (tm->mTriangleCount - first)) {
        count = tm->mTriangleCount - first;
    }
    if (!count) {
        return;
    }

    const float *f = (const float *)tm->mVertexData;

    glBindBuffer(GL_ARRAY_BUFFER, tm->mBufferObjects[0]);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, tm->mBufferObjects[1]);

    glEnableClientState(GL_VERTEX_ARRAY);
    glVertexPointer(tm->mSizeCoord,
                    GL_FLOAT,
                    tm->mVertexElement->getSizeBytes(),
                    (void *)tm->mVertexElement->getComponentOffsetBytes(tm->mOffsetCoord));

    if (tm->mSizeTex) {
        glEnableClientState(GL_TEXTURE_COORD_ARRAY);
        glTexCoordPointer(tm->mSizeTex,
                          GL_FLOAT,
                          tm->mVertexElement->getSizeBytes(),
                          (void *)tm->mVertexElement->getComponentOffsetBytes(tm->mOffsetTex));
    } else {
        glDisableClientState(GL_TEXTURE_COORD_ARRAY);
    }

    if (tm->mSizeNorm) {
        glEnableClientState(GL_NORMAL_ARRAY);
        glNormalPointer(GL_FLOAT,
                        tm->mVertexElement->getSizeBytes(),
                        (void *)tm->mVertexElement->getComponentOffsetBytes(tm->mOffsetNorm));
    } else {
        glDisableClientState(GL_NORMAL_ARRAY);
    }

    glDrawElements(GL_TRIANGLES, count * 3, GL_UNSIGNED_SHORT, (GLvoid *)(first * 3 * 2));

    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
}

void rsi_TriangleMeshRender(Context *rsc, RsTriangleMesh vtm)
{
    rsi_TriangleMeshRenderRange(rsc, vtm, 0, 0xffffff);
}

}}
