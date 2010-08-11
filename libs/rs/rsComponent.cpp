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

#ifndef ANDROID_RS_BUILD_FOR_HOST
#include <GLES/gl.h>
#else
#include <OpenGL/gl.h>
#endif

using namespace android;
using namespace android::renderscript;

Component::Component()
{
    set(RS_TYPE_NONE, RS_KIND_USER, false, 1);
}

Component::~Component()
{
}

void Component::set(RsDataType dt, RsDataKind dk, bool norm, uint32_t vecSize)
{
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

    switch(mKind) {
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

    switch(mType) {
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

bool Component::isReference() const
{
    return (mType >= RS_TYPE_ELEMENT);
}



uint32_t Component::getGLType() const
{
    switch (mType) {
    case RS_TYPE_UNSIGNED_5_6_5:    return GL_UNSIGNED_SHORT_5_6_5;
    case RS_TYPE_UNSIGNED_5_5_5_1:  return GL_UNSIGNED_SHORT_5_5_5_1;
    case RS_TYPE_UNSIGNED_4_4_4_4:  return GL_UNSIGNED_SHORT_4_4_4_4;

    //case RS_TYPE_FLOAT_16:      return GL_HALF_FLOAT;
    case RS_TYPE_FLOAT_32:      return GL_FLOAT;
    case RS_TYPE_UNSIGNED_8:    return GL_UNSIGNED_BYTE;
    case RS_TYPE_UNSIGNED_16:   return GL_UNSIGNED_SHORT;
    case RS_TYPE_SIGNED_8:      return GL_BYTE;
    case RS_TYPE_SIGNED_16:     return GL_SHORT;
    default:    break;
    }

    return 0;
}

uint32_t Component::getGLFormat() const
{
    switch (mKind) {
    case RS_KIND_PIXEL_L: return GL_LUMINANCE;
    case RS_KIND_PIXEL_A: return GL_ALPHA;
    case RS_KIND_PIXEL_LA: return GL_LUMINANCE_ALPHA;
    case RS_KIND_PIXEL_RGB: return GL_RGB;
    case RS_KIND_PIXEL_RGBA: return GL_RGBA;
    default: break;
    }
    return 0;
}

String8 Component::getGLSLType() const
{
    if (mType == RS_TYPE_SIGNED_32) {
        switch(mVectorSize) {
        case 1: return String8("int");
        case 2: return String8("ivec2");
        case 3: return String8("ivec3");
        case 4: return String8("ivec4");
        }
    }
    if (mType == RS_TYPE_FLOAT_32) {
        switch(mVectorSize) {
        case 1: return String8("float");
        case 2: return String8("vec2");
        case 3: return String8("vec3");
        case 4: return String8("vec4");
        }
    }
    return String8();
}

static const char * gTypeStrings[] = {
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

void Component::dumpLOGV(const char *prefix) const
{
    LOGV("%s   Component: %s, %s, vectorSize=%i, bits=%i",
         prefix, gTypeStrings[mType], gKindStrings[mKind], mVectorSize, mBits);
}

void Component::serialize(OStream *stream) const
{
    stream->addU8((uint8_t)mType);
    stream->addU8((uint8_t)mKind);
    stream->addU8((uint8_t)(mNormalized ? 1 : 0));
    stream->addU32(mVectorSize);
}

void Component::loadFromStream(IStream *stream)
{
    mType = (RsDataType)stream->loadU8();
    mKind = (RsDataKind)stream->loadU8();
    uint8_t temp = stream->loadU8();
    mNormalized = temp != 0;
    mVectorSize = stream->loadU32();

    set(mType, mKind, mNormalized, mVectorSize);
}




