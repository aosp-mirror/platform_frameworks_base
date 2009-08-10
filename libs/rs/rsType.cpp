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
#include <GLES/gl.h>

using namespace android;
using namespace android::renderscript;

Type::Type()
{
    mLODs = 0;
    mLODCount = 0;
    memset(&mGL, 0, sizeof(mGL));
    clear();
}

Type::~Type()
{
    if (mLODs) {
        delete [] mLODs;
    }
}

void Type::clear()
{
    if (mLODs) {
        delete [] mLODs;
        mLODs = NULL;
    }
    mDimX = 0;
    mDimY = 0;
    mDimZ = 0;
    mDimLOD = 0;
    mFaces = false;
    mElement.clear();
}

TypeState::TypeState()
{
}

TypeState::~TypeState()
{
}

size_t Type::getOffsetForFace(uint32_t face) const
{
    rsAssert(mFaces);
    return 0;
}

void Type::compute()
{
    uint32_t oldLODCount = mLODCount;
    if (mDimLOD) {
        uint32_t l2x = rsFindHighBit(mDimX) + 1;
        uint32_t l2y = rsFindHighBit(mDimY) + 1;
        uint32_t l2z = rsFindHighBit(mDimZ) + 1;

        mLODCount = rsMax(l2x, l2y);
        mLODCount = rsMax(mLODCount, l2z);
    } else {
        mLODCount = 1;
    }
    if (mLODCount != oldLODCount) {
        delete [] mLODs;
        mLODs = new LOD[mLODCount];
    }

    uint32_t tx = mDimX;
    uint32_t ty = mDimY;
    uint32_t tz = mDimZ;
    size_t offset = 0;
    for (uint32_t lod=0; lod < mLODCount; lod++) {
        mLODs[lod].mX = tx;
        mLODs[lod].mY = ty;
        mLODs[lod].mZ = tz;
        mLODs[lod].mOffset = offset;
        offset += tx * rsMax(ty, 1u) * rsMax(tz, 1u) * mElement->getSizeBytes();
        tx = (tx + 1) >> 1;
        ty = (ty + 1) >> 1;
        tz = (tz + 1) >> 1;
    }

    // At this point the offset is the size of a mipmap chain;
    mMipChainSizeBytes = offset;

    if (mFaces) {
        offset *= 6;
    }
    mTotalSizeBytes = offset;

    makeGLComponents();
}

uint32_t Type::getLODOffset(uint32_t lod, uint32_t x) const
{
    uint32_t offset = mLODs[lod].mOffset;
    offset += x * mElement->getSizeBytes();
    return offset;
}

uint32_t Type::getLODOffset(uint32_t lod, uint32_t x, uint32_t y) const
{
    uint32_t offset = mLODs[lod].mOffset;
    offset += (x + y * mLODs[lod].mX) * mElement->getSizeBytes();
    return offset;
}

uint32_t Type::getLODOffset(uint32_t lod, uint32_t x, uint32_t y, uint32_t z) const
{
    uint32_t offset = mLODs[lod].mOffset;
    offset += (x + y*mLODs[lod].mX + z*mLODs[lod].mX*mLODs[lod].mY) * mElement->getSizeBytes();
    return offset;
}


void Type::makeGLComponents()
{
    uint32_t texNum = 0;
    memset(&mGL, 0, sizeof(mGL));

    for (uint32_t ct=0; ct < getElement()->getComponentCount(); ct++) {
        const Component *c = getElement()->getComponent(ct);

        switch(c->getKind()) {
        case Component::X:
            rsAssert(mGL.mVtx.size == 0);
            mGL.mVtx.size = 1;
            mGL.mVtx.offset = mElement->getComponentOffsetBytes(ct);
            mGL.mVtx.type = c->getGLType();
            break;
        case Component::Y:
            rsAssert(mGL.mVtx.size == 1);
            rsAssert(mGL.mVtx.type == c->getGLType());
            mGL.mVtx.size = 2;
            break;
        case Component::Z:
            rsAssert(mGL.mVtx.size == 2);
            rsAssert(mGL.mVtx.type == c->getGLType());
            mGL.mVtx.size = 3;
            break;
        case Component::W:
            rsAssert(mGL.mVtx.size == 4);
            rsAssert(mGL.mVtx.type == c->getGLType());
            mGL.mVtx.size = 4;
        break;

        case Component::RED:
            rsAssert(mGL.mColor.size == 0);
            mGL.mColor.size = 1;
            mGL.mColor.offset = mElement->getComponentOffsetBytes(ct);
            mGL.mColor.type = c->getGLType();
            break;
        case Component::GREEN:
            rsAssert(mGL.mColor.size == 1);
            rsAssert(mGL.mColor.type == c->getGLType());
            mGL.mColor.size = 2;
            break;
        case Component::BLUE:
            rsAssert(mGL.mColor.size == 2);
            rsAssert(mGL.mColor.type == c->getGLType());
            mGL.mColor.size = 3;
            break;
        case Component::ALPHA:
            rsAssert(mGL.mColor.size == 3);
            rsAssert(mGL.mColor.type == c->getGLType());
            mGL.mColor.size = 4;
        break;

        case Component::NX:
            rsAssert(mGL.mNorm.size == 0);
            mGL.mNorm.size = 1;
            mGL.mNorm.offset = mElement->getComponentOffsetBytes(ct);
            mGL.mNorm.type = c->getGLType();
        break;
        case Component::NY:
            rsAssert(mGL.mNorm.size == 1);
            rsAssert(mGL.mNorm.type == c->getGLType());
            mGL.mNorm.size = 2;
        break;
        case Component::NZ:
            rsAssert(mGL.mNorm.size == 2);
            rsAssert(mGL.mNorm.type == c->getGLType());
            mGL.mNorm.size = 3;
        break;

        case Component::S:
            if (mGL.mTex[texNum].size) {
                texNum++;
            }
            mGL.mTex[texNum].size = 1;
            mGL.mTex[texNum].offset = mElement->getComponentOffsetBytes(ct);
            mGL.mTex[texNum].type = c->getGLType();
        break;
        case Component::T:
            rsAssert(mGL.mTex[texNum].size == 1);
            rsAssert(mGL.mTex[texNum].type == c->getGLType());
            mGL.mTex[texNum].size = 2;
        break;
        case Component::R:
            rsAssert(mGL.mTex[texNum].size == 2);
            rsAssert(mGL.mTex[texNum].type == c->getGLType());
            mGL.mTex[texNum].size = 3;
        break;
        case Component::Q:
            rsAssert(mGL.mTex[texNum].size == 3);
            rsAssert(mGL.mTex[texNum].type == c->getGLType());
            mGL.mTex[texNum].size = 4;
        break;

        default:
            break;
        }
    }
}

void Type::enableGLVertexBuffer() const
{
    // Note: We are only going to enable buffers and never disable them
    // here.  The reasonis more than one Allocation may be used as a vertex
    // source.  So we cannot disable arrays that may have been in use by
    // another allocation.

    uint32_t stride = mElement->getSizeBytes();
    if (mGL.mVtx.size) {
        glEnableClientState(GL_VERTEX_ARRAY);
        glVertexPointer(mGL.mVtx.size,
                        mGL.mVtx.type,
                        stride,
                        (void *)mGL.mVtx.offset);
    }

    if (mGL.mNorm.size) {
        glEnableClientState(GL_NORMAL_ARRAY);
        rsAssert(mGL.mNorm.size == 3);
        glNormalPointer(mGL.mNorm.size,
                        stride,
                        (void *)mGL.mNorm.offset);
    }

    if (mGL.mColor.size) {
        glEnableClientState(GL_COLOR_ARRAY);
        glColorPointer(mGL.mColor.size,
                       mGL.mColor.type,
                       stride,
                       (void *)mGL.mColor.offset);
    }

    for (uint32_t ct=0; ct < RS_MAX_TEXTURE; ct++) {
        if (mGL.mTex[ct].size) {
            glClientActiveTexture(GL_TEXTURE0 + ct);
            glEnableClientState(GL_TEXTURE_COORD_ARRAY);
            glTexCoordPointer(mGL.mTex[ct].size,
                              mGL.mTex[ct].type,
                              stride,
                              (void *)mGL.mTex[ct].offset);
        }
    }
    glClientActiveTexture(GL_TEXTURE0);

}


//////////////////////////////////////////////////
//
namespace android {
namespace renderscript {

void rsi_TypeBegin(Context *rsc, RsElement vse)
{
    TypeState * stc = &rsc->mStateType;

    stc->mX = 0;
    stc->mY = 0;
    stc->mZ = 0;
    stc->mLOD = false;
    stc->mFaces = false;
    stc->mElement.set(static_cast<const Element *>(vse));
}

void rsi_TypeAdd(Context *rsc, RsDimension dim, size_t value)
{
    TypeState * stc = &rsc->mStateType;

    if (dim < 0) {
        //error
        return;
    }


    switch (dim) {
    case RS_DIMENSION_X:
        stc->mX = value;
        return;
    case RS_DIMENSION_Y:
        stc->mY = value;
        return;
    case RS_DIMENSION_Z:
        stc->mZ = value;
        return;
    case RS_DIMENSION_FACE:
        stc->mFaces = (value != 0);
        return;
    case RS_DIMENSION_LOD:
        stc->mLOD = (value != 0);
        return;
    default:
        break;
    }


    int32_t arrayNum = dim - RS_DIMENSION_ARRAY_0;
    if ((dim < 0) || (dim > RS_DIMENSION_MAX)) {
        LOGE("rsTypeAdd: Bad dimension");
        //error
        return;
    }

    // todo: implement array support

}

RsType rsi_TypeCreate(Context *rsc)
{
    TypeState * stc = &rsc->mStateType;

    Type * st = new Type();
    st->incRef();
    st->setDimX(stc->mX);
    st->setDimY(stc->mY);
    st->setDimZ(stc->mZ);
    st->setElement(stc->mElement.get());
    st->setDimLOD(stc->mLOD);
    st->setDimFaces(stc->mFaces);
    st->compute();

    return st;
}

void rsi_TypeDestroy(Context *rsc, RsType vst)
{
    Type * st = static_cast<Type *>(vst);
    st->decRef();
}

}
}

