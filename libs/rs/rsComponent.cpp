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

#include "rsComponent.h"

using namespace android;
using namespace android::renderscript;

Component::Component() {
    set(RS_TYPE_NONE, RS_KIND_USER, false, 1);
}

Component::~Component() {
}

void Component::set(RsDataType dt, RsDataKind dk, bool norm, uint32_t vecSize) {
    mType = dt;
    mKind = dk;
    mNormalized = norm;
    mVectorSize = vecSize;
    rsAssert(vecSize <= 4);

    mBits = 0;
    mTypeBits = 0;
    mIsFloat = false;
    mIsSigned = false;
    mIsPixel = false;

    switch (mKind) {
    case RS_KIND_PIXEL_L:
    case RS_KIND_PIXEL_A:
        mIsPixel = true;
        rsAssert(mVectorSize == 1);
        rsAssert(mNormalized == true);
        break;
    case RS_KIND_PIXEL_LA:
        mIsPixel = true;
        rsAssert(mVectorSize == 2);
        rsAssert(mNormalized == true);
        break;
    case RS_KIND_PIXEL_RGB:
        mIsPixel = true;
        rsAssert(mVectorSize == 3);
        rsAssert(mNormalized == true);
        break;
    case RS_KIND_PIXEL_RGBA:
        mIsPixel = true;
        rsAssert(mVectorSize == 4);
        rsAssert(mNormalized == true);
        break;
    default:
        break;
    }

    switch (mType) {
    case RS_TYPE_NONE:
        return;
    case RS_TYPE_UNSIGNED_5_6_5:
        mVectorSize = 3;
        mBits = 16;
        mNormalized = true;
        rsAssert(mKind == RS_KIND_PIXEL_RGB);
        return;
    case RS_TYPE_UNSIGNED_5_5_5_1:
        mVectorSize = 4;
        mBits = 16;
        mNormalized = true;
        rsAssert(mKind == RS_KIND_PIXEL_RGBA);
        return;
    case RS_TYPE_UNSIGNED_4_4_4_4:
        mVectorSize = 4;
        mBits = 16;
        mNormalized = true;
        rsAssert(mKind == RS_KIND_PIXEL_RGBA);
        return;

    case RS_TYPE_MATRIX_4X4:
        mTypeBits = 16 * 32;
        rsAssert(mVectorSize == 1);
        rsAssert(mNormalized == false);
        rsAssert(mKind == RS_KIND_USER);
        break;
    case RS_TYPE_MATRIX_3X3:
        mTypeBits = 9 * 32;
        rsAssert(mVectorSize == 1);
        rsAssert(mNormalized == false);
        rsAssert(mKind == RS_KIND_USER);
        break;
    case RS_TYPE_MATRIX_2X2:
        mTypeBits = 4 * 32;
        rsAssert(mVectorSize == 1);
        rsAssert(mNormalized == false);
        rsAssert(mKind == RS_KIND_USER);
        break;

    case RS_TYPE_ELEMENT:
    case RS_TYPE_TYPE:
    case RS_TYPE_ALLOCATION:
    case RS_TYPE_SAMPLER:
    case RS_TYPE_SCRIPT:
    case RS_TYPE_MESH:
    case RS_TYPE_PROGRAM_FRAGMENT:
    case RS_TYPE_PROGRAM_VERTEX:
    case RS_TYPE_PROGRAM_RASTER:
    case RS_TYPE_PROGRAM_STORE:
        rsAssert(mVectorSize == 1);
        rsAssert(mNormalized == false);
        rsAssert(mKind == RS_KIND_USER);
        mBits = 32;
        mTypeBits = 32;
        return;

    case RS_TYPE_FLOAT_16:
        mTypeBits = 16;
        mIsFloat = true;
        break;
    case RS_TYPE_FLOAT_32:
        mTypeBits = 32;
        mIsFloat = true;
        break;
    case RS_TYPE_FLOAT_64:
        mTypeBits = 64;
        mIsFloat = true;
        break;
    case RS_TYPE_SIGNED_8:
        mTypeBits = 8;
        mIsSigned = true;
        break;
    case RS_TYPE_SIGNED_16:
        mTypeBits = 16;
        mIsSigned = true;
        break;
    case RS_TYPE_SIGNED_32:
        mTypeBits = 32;
        mIsSigned = true;
        break;
    case RS_TYPE_SIGNED_64:
        mTypeBits = 64;
        mIsSigned = true;
        break;
    case RS_TYPE_UNSIGNED_8:
        mTypeBits = 8;
        break;
    case RS_TYPE_UNSIGNED_16:
        mTypeBits = 16;
        break;
    case RS_TYPE_UNSIGNED_32:
        mTypeBits = 32;
        break;
    case RS_TYPE_UNSIGNED_64:
        mTypeBits = 64;
        break;

    case RS_TYPE_BOOLEAN:
        mTypeBits = 8;
        break;
    }

    mBits = mTypeBits * mVectorSize;
}

bool Component::isReference() const {
    return (mType >= RS_TYPE_ELEMENT);
}

static const char * gTypeBasicStrings[] = {
    "NONE",
    "F16",
    "F32",
    "F64",
    "S8",
    "S16",
    "S32",
    "S64",
    "U8",
    "U16",
    "U32",
    "U64",
    "BOOLEAN",
    "UP_565",
    "UP_5551",
    "UP_4444",
    "MATRIX_4X4",
    "MATRIX_3X3",
    "MATRIX_2X2",
};

static const char * gTypeObjStrings[] = {
    "ELEMENT",
    "TYPE",
    "ALLOCATION",
    "SAMPLER",
    "SCRIPT",
    "MESH",
    "PROGRAM_FRAGMENT",
    "PROGRAM_VERTEX",
    "PROGRAM_RASTER",
    "PROGRAM_STORE",
};

static const char * gKindStrings[] = {
    "USER",
    "COLOR",
    "POSITION",
    "TEXTURE",
    "NORMAL",
    "INDEX",
    "POINT_SIZE",
    "PIXEL_L",
    "PIXEL_A",
    "PIXEL_LA",
    "PIXEL_RGB",
    "PIXEL_RGBA",
};

void Component::dumpLOGV(const char *prefix) const {
    if (mType >= RS_TYPE_ELEMENT) {
        LOGV("%s   Component: %s, %s, vectorSize=%i, bits=%i",
             prefix, gTypeObjStrings[mType - RS_TYPE_ELEMENT], gKindStrings[mKind], mVectorSize, mBits);
    } else {
        LOGV("%s   Component: %s, %s, vectorSize=%i, bits=%i",
             prefix, gTypeBasicStrings[mType], gKindStrings[mKind], mVectorSize, mBits);
    }
}

void Component::serialize(OStream *stream) const {
    stream->addU8((uint8_t)mType);
    stream->addU8((uint8_t)mKind);
    stream->addU8((uint8_t)(mNormalized ? 1 : 0));
    stream->addU32(mVectorSize);
}

void Component::loadFromStream(IStream *stream) {
    mType = (RsDataType)stream->loadU8();
    mKind = (RsDataKind)stream->loadU8();
    uint8_t temp = stream->loadU8();
    mNormalized = temp != 0;
    mVectorSize = stream->loadU32();

    set(mType, mKind, mNormalized, mVectorSize);
}




