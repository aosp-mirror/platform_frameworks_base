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

Type::Type()
{
    mLODs = 0;
    mLODCount = 0;
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
    st->setDimX(stc->mX);
    st->setDimY(stc->mY);
    st->setDimZ(stc->mZ);
    st->setElement(stc->mElement.get());
    st->setDimLOD(stc->mLOD);
    st->setDimFaces(stc->mFaces);
    st->compute();

    stc->mAllTypes.add(st);

    return st;
}

void rsi_TypeDestroy(Context *rsc, RsType vst)
{
    TypeState * stc = &rsc->mStateType;
    Type * st = static_cast<Type *>(vst);

    for (size_t ct = 0; ct < stc->mAllTypes.size(); ct++) {
        if (stc->mAllTypes[ct] == st) {
            stc->mAllTypes.removeAt(ct);
            break;
        }
    }
    delete st;
}

}
}

