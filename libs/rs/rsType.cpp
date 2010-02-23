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

Type::Type(Context *rsc) : ObjectBase(rsc)
{
    mAllocFile = __FILE__;
    mAllocLine = __LINE__;
    mLODs = 0;
    mLODCount = 0;
    clear();
}

Type::~Type()
{
    for (uint32_t ct = 0; ct < mRSC->mStateType.mTypes.size(); ct++) {
        if (mRSC->mStateType.mTypes[ct] == this) {
            mRSC->mStateType.mTypes.removeAt(ct);
            break;
        }
    }
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
        if (tx > 1) tx >>= 1;
        if (ty > 1) ty >>= 1;
        if (tz > 1) tz >>= 1;
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
    uint32_t userNum = 0;

    for (uint32_t ct=0; ct < getElement()->getFieldCount(); ct++) {
        const Component &c = getElement()->getField(ct)->getComponent();

        switch(c.getKind()) {
        case RS_KIND_USER:
            mGL.mUser[userNum].size = c.getVectorSize();
            mGL.mUser[userNum].offset = mElement->getFieldOffsetBytes(ct);
            mGL.mUser[userNum].type = c.getGLType();
            mGL.mUser[userNum].normalized = c.getType() != RS_TYPE_FLOAT_32;//c.getIsNormalized();
            mGL.mUser[userNum].name.setTo(getElement()->getFieldName(ct));
            userNum ++;
            break;

        case RS_KIND_POSITION:
            rsAssert(mGL.mVtx.size == 0);
            mGL.mVtx.size = c.getVectorSize();
            mGL.mVtx.offset = mElement->getFieldOffsetBytes(ct);
            mGL.mVtx.type = c.getGLType();
            mGL.mVtx.normalized = false;
            mGL.mVtx.name.setTo("Position");
            break;

        case RS_KIND_COLOR:
            rsAssert(mGL.mColor.size == 0);
            mGL.mColor.size = c.getVectorSize();
            mGL.mColor.offset = mElement->getFieldOffsetBytes(ct);
            mGL.mColor.type = c.getGLType();
            mGL.mColor.normalized = c.getType() != RS_TYPE_FLOAT_32;
            mGL.mColor.name.setTo("Color");
            break;

        case RS_KIND_NORMAL:
            rsAssert(mGL.mNorm.size == 0);
            mGL.mNorm.size = c.getVectorSize();
            mGL.mNorm.offset = mElement->getFieldOffsetBytes(ct);
            mGL.mNorm.type = c.getGLType();
            mGL.mNorm.normalized = false;
            mGL.mNorm.name.setTo("Normal");
            break;

        case RS_KIND_TEXTURE:
            rsAssert(mGL.mTex.size == 0);
            mGL.mTex.size = c.getVectorSize();
            mGL.mTex.offset = mElement->getFieldOffsetBytes(ct);
            mGL.mTex.type = c.getGLType();
            mGL.mTex.normalized = false;
            mGL.mTex.name.setTo("Texture");
            break;

        case RS_KIND_POINT_SIZE:
            rsAssert(!mGL.mPointSize.size);
            mGL.mPointSize.size = c.getVectorSize();
            mGL.mPointSize.offset = mElement->getFieldOffsetBytes(ct);
            mGL.mPointSize.type = c.getGLType();
            mGL.mPointSize.normalized = false;
            mGL.mPointSize.name.setTo("PointSize");
        break;

        default:
            break;
        }
    }
}

void Type::enableGLVertexBuffer(VertexArray *va) const
{
    // Note: We are only going to enable buffers and never disable them
    // here.  The reason is more than one Allocation may be used as a vertex
    // source.  So we cannot disable arrays that may have been in use by
    // another allocation.

    uint32_t stride = mElement->getSizeBytes();
    if (mGL.mVtx.size) {
        va->addLegacy(mGL.mVtx.type,
                      mGL.mVtx.size,
                      stride,
                      RS_KIND_POSITION,
                      false,
                      mGL.mVtx.offset);
    }

    if (mGL.mNorm.size) {
        va->addLegacy(mGL.mNorm.type,
                     3,
                     stride,
                     RS_KIND_NORMAL,
                     false,
                     mGL.mNorm.offset);
    }

    if (mGL.mColor.size) {
        va->addLegacy(mGL.mColor.type,
                     mGL.mColor.size,
                     stride,
                     RS_KIND_COLOR,
                     true,
                     mGL.mColor.offset);
    }

    if (mGL.mTex.size) {
        va->addLegacy(mGL.mTex.type,
                     mGL.mTex.size,
                     stride,
                     RS_KIND_TEXTURE,
                     false,
                     mGL.mTex.offset);
    }

    if (mGL.mPointSize.size) {
        va->addLegacy(mGL.mPointSize.type,
                     1,
                     stride,
                     RS_KIND_POINT_SIZE,
                     false,
                     mGL.mPointSize.offset);
    }

}

void Type::enableGLVertexBuffer2(VertexArray *va) const
{
    // Do legacy buffers
    enableGLVertexBuffer(va);

    uint32_t stride = mElement->getSizeBytes();
    for (uint32_t ct=0; ct < RS_MAX_ATTRIBS; ct++) {
        if (mGL.mUser[ct].size) {
            va->addUser(mGL.mUser[ct], stride);
        }
    }
}



void Type::dumpLOGV(const char *prefix) const
{
    char buf[1024];
    ObjectBase::dumpLOGV(prefix);
    LOGV("%s   Type: x=%i y=%i z=%i mip=%i face=%i", prefix, mDimX, mDimY, mDimZ, mDimLOD, mFaces);
    sprintf(buf, "%s element: ", prefix);
    mElement->dumpLOGV(buf);
}

bool Type::getIsNp2() const
{
    uint32_t x = getDimX();
    uint32_t y = getDimY();
    uint32_t z = getDimZ();

    if (x && (x & (x-1))) {
        return true;
    }
    if (y && (y & (y-1))) {
        return true;
    }
    if (z && (z & (z-1))) {
        return true;
    }
    return false;
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

    for (uint32_t ct=0; ct < stc->mTypes.size(); ct++) {
        Type *t = stc->mTypes[ct];
        if (t->getElement() != stc->mElement.get()) continue;
        if (t->getDimX() != stc->mX) continue;
        if (t->getDimY() != stc->mY) continue;
        if (t->getDimZ() != stc->mZ) continue;
        if (t->getDimLOD() != stc->mLOD) continue;
        if (t->getDimFaces() != stc->mFaces) continue;
        t->incUserRef();
        return t;
    }

    Type * st = new Type(rsc);
    st->incUserRef();
    st->setDimX(stc->mX);
    st->setDimY(stc->mY);
    st->setDimZ(stc->mZ);
    st->setElement(stc->mElement.get());
    st->setDimLOD(stc->mLOD);
    st->setDimFaces(stc->mFaces);
    st->compute();
    stc->mElement.clear();
    stc->mTypes.push(st);
    return st;
}


}
}

