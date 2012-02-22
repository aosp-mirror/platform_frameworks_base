/*
 * Copyright (C) 2008-2012 The Android Open Source Project
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

#include <utils/Log.h>
#include <malloc.h>
#include <string.h>

#include "RenderScript.h"
#include "Element.h"


const Element * Element::getSubElement(uint32_t index) {
    if (!mVisibleElementMap.size()) {
        ALOGE("Element contains no sub-elements");
        return NULL;
    }
    if (index >= mVisibleElementMap.size()) {
        ALOGE("Illegal sub-element index");
    }
    return mElements[mVisibleElementMap[index]];
}

const char * Element::getSubElementName(uint32_t index) {
    if (!mVisibleElementMap.size()) {
        ALOGE("Element contains no sub-elements");
    }
    if (index >= mVisibleElementMap.size()) {
        ALOGE("Illegal sub-element index");
    }
    return mElementNames[mVisibleElementMap[index]];
}

size_t Element::getSubElementArraySize(uint32_t index) {
    if (!mVisibleElementMap.size()) {
        ALOGE("Element contains no sub-elements");
    }
    if (index >= mVisibleElementMap.size()) {
        ALOGE("Illegal sub-element index");
    }
    return mArraySizes[mVisibleElementMap[index]];
}

uint32_t Element::getSubElementOffsetBytes(uint32_t index) {
    if (mVisibleElementMap.size()) {
        ALOGE("Element contains no sub-elements");
    }
    if (index >= mVisibleElementMap.size()) {
        ALOGE("Illegal sub-element index");
    }
    return mOffsetInBytes[mVisibleElementMap[index]];
}


#define CREATE_USER(N, T) const Element * Element::N(RenderScript *rs) { \
    return createUser(rs, RS_TYPE_##T); \
}
CREATE_USER(BOOLEAN, BOOLEAN);
CREATE_USER(U8, UNSIGNED_8);
CREATE_USER(I8, SIGNED_8);
CREATE_USER(U16, UNSIGNED_16);
CREATE_USER(I16, SIGNED_16);
CREATE_USER(U32, UNSIGNED_32);
CREATE_USER(I32, SIGNED_32);
CREATE_USER(U64, UNSIGNED_64);
CREATE_USER(I64, SIGNED_64);
CREATE_USER(F32, FLOAT_32);
CREATE_USER(F64, FLOAT_64);
CREATE_USER(ELEMENT, ELEMENT);
CREATE_USER(TYPE, TYPE);
CREATE_USER(ALLOCATION, ALLOCATION);
CREATE_USER(SAMPLER, SAMPLER);
CREATE_USER(SCRIPT, SCRIPT);
CREATE_USER(MESH, MESH);
CREATE_USER(PROGRAM_FRAGMENT, PROGRAM_FRAGMENT);
CREATE_USER(PROGRAM_VERTEX, PROGRAM_VERTEX);
CREATE_USER(PROGRAM_RASTER, PROGRAM_RASTER);
CREATE_USER(PROGRAM_STORE, PROGRAM_STORE);
CREATE_USER(MATRIX_4X4, MATRIX_4X4);
CREATE_USER(MATRIX_3X3, MATRIX_3X3);
CREATE_USER(MATRIX_2X2, MATRIX_2X2);

#define CREATE_PIXEL(N, T, K) const Element * Element::N(RenderScript *rs) { \
    return createPixel(rs, RS_TYPE_##T, RS_KIND_##K); \
}
CREATE_PIXEL(A_8, UNSIGNED_8, PIXEL_A);
CREATE_PIXEL(RGB_565, UNSIGNED_5_6_5, PIXEL_RGB);
CREATE_PIXEL(RGB_888, UNSIGNED_8, PIXEL_RGB);
CREATE_PIXEL(RGBA_4444, UNSIGNED_4_4_4_4, PIXEL_RGBA);
CREATE_PIXEL(RGBA_8888, UNSIGNED_8, PIXEL_RGBA);

#define CREATE_VECTOR(N, T) const Element * Element::N##_2(RenderScript *rs) { \
    return createVector(rs, RS_TYPE_##T, 2); \
} \
const Element * Element::N##_3(RenderScript *rs) { \
    return createVector(rs, RS_TYPE_##T, 3); \
} \
const Element * Element::N##_4(RenderScript *rs) { \
    return createVector(rs, RS_TYPE_##T, 4); \
}
CREATE_VECTOR(U8, UNSIGNED_8);
CREATE_VECTOR(I8, SIGNED_8);
CREATE_VECTOR(U16, UNSIGNED_16);
CREATE_VECTOR(I16, SIGNED_16);
CREATE_VECTOR(U32, UNSIGNED_32);
CREATE_VECTOR(I32, SIGNED_32);
CREATE_VECTOR(U64, UNSIGNED_64);
CREATE_VECTOR(I64, SIGNED_64);
CREATE_VECTOR(F32, FLOAT_32);
CREATE_VECTOR(F64, FLOAT_64);


void Element::updateVisibleSubElements() {
    if (!mElements.size()) {
        return;
    }
    mVisibleElementMap.clear();

    int noPaddingFieldCount = 0;
    size_t fieldCount = mElementNames.size();
    // Find out how many elements are not padding
    for (size_t ct = 0; ct < fieldCount; ct ++) {
        if (mElementNames[ct].string()[0] != '#') {
            noPaddingFieldCount ++;
        }
    }

    // Make a map that points us at non-padding elements
    for (size_t ct = 0; ct < fieldCount; ct ++) {
        if (mElementNames[ct].string()[0] != '#') {
            mVisibleElementMap.push((uint32_t)ct);
        }
    }
}

Element::Element(void *id, RenderScript *rs,
                 android::Vector<const Element *> &elements,
                 android::Vector<android::String8> &elementNames,
                 android::Vector<uint32_t> &arraySizes) : BaseObj(id, rs) {
    mSizeBytes = 0;
    mVectorSize = 1;
    mElements = elements;
    mArraySizes = arraySizes;
    mElementNames = elementNames;

    mType = RS_TYPE_NONE;
    mKind = RS_KIND_USER;

    for (size_t ct = 0; ct < mElements.size(); ct++ ) {
        mOffsetInBytes.push(mSizeBytes);
        mSizeBytes += mElements[ct]->mSizeBytes * mArraySizes[ct];
    }
    updateVisibleSubElements();
}


static uint32_t GetSizeInBytesForType(RsDataType dt) {
    switch(dt) {
    case RS_TYPE_NONE:
        return 0;
    case RS_TYPE_SIGNED_8:
    case RS_TYPE_UNSIGNED_8:
    case RS_TYPE_BOOLEAN:
        return 1;

    case RS_TYPE_FLOAT_16:
    case RS_TYPE_SIGNED_16:
    case RS_TYPE_UNSIGNED_16:
    case RS_TYPE_UNSIGNED_5_6_5:
    case RS_TYPE_UNSIGNED_5_5_5_1:
    case RS_TYPE_UNSIGNED_4_4_4_4:
        return 2;

    case RS_TYPE_FLOAT_32:
    case RS_TYPE_SIGNED_32:
    case RS_TYPE_UNSIGNED_32:
        return 4;

    case RS_TYPE_FLOAT_64:
    case RS_TYPE_SIGNED_64:
    case RS_TYPE_UNSIGNED_64:
        return 8;

    case RS_TYPE_MATRIX_4X4:
        return 16 * 4;
    case RS_TYPE_MATRIX_3X3:
        return 9 * 4;
    case RS_TYPE_MATRIX_2X2:
        return 4 * 4;

    case RS_TYPE_TYPE:
    case RS_TYPE_ALLOCATION:
    case RS_TYPE_SAMPLER:
    case RS_TYPE_SCRIPT:
    case RS_TYPE_MESH:
    case RS_TYPE_PROGRAM_FRAGMENT:
    case RS_TYPE_PROGRAM_VERTEX:
    case RS_TYPE_PROGRAM_RASTER:
    case RS_TYPE_PROGRAM_STORE:
        return 4;

    default:
        break;
    }

    ALOGE("Missing type %i", dt);
    return 0;
}

Element::Element(void *id, RenderScript *rs,
                 RsDataType dt, RsDataKind dk, bool norm, uint32_t size) :
    BaseObj(id, rs)
{
    uint32_t tsize = GetSizeInBytesForType(dt);
    if ((dt != RS_TYPE_UNSIGNED_5_6_5) &&
        (dt != RS_TYPE_UNSIGNED_4_4_4_4) &&
        (dt != RS_TYPE_UNSIGNED_5_5_5_1)) {
        if (size == 3) {
            mSizeBytes = tsize * 4;
        } else {
            mSizeBytes = tsize * size;
        }
    } else {
        mSizeBytes = tsize;
    }
    mType = dt;
    mKind = dk;
    mNormalized = norm;
    mVectorSize = size;
}

Element::~Element() {
}

   /*
    Element(int id, RenderScript rs) {
        super(id, rs);
    }
    */

void Element::updateFromNative() {
    BaseObj::updateFromNative();
/*
    // we will pack mType; mKind; mNormalized; mVectorSize; NumSubElements
    int[] dataBuffer = new int[5];
    mRS.nElementGetNativeData(getID(), dataBuffer);

    mNormalized = dataBuffer[2] == 1 ? true : false;
    mVectorSize = dataBuffer[3];
    mSize = 0;
    for (DataType dt: DataType.values()) {
        if(dt.mID == dataBuffer[0]){
            mType = dt;
            mSize = mType.mSize * mVectorSize;
        }
    }
    for (DataKind dk: DataKind.values()) {
        if(dk.mID == dataBuffer[1]){
            mKind = dk;
        }
    }

    int numSubElements = dataBuffer[4];
    if(numSubElements > 0) {
        mElements = new Element[numSubElements];
        mElementNames = new String[numSubElements];
        mArraySizes = new int[numSubElements];
        mOffsetInBytes = new int[numSubElements];

        int[] subElementIds = new int[numSubElements];
        mRS.nElementGetSubElements(getID(), subElementIds, mElementNames, mArraySizes);
        for(int i = 0; i < numSubElements; i ++) {
            mElements[i] = new Element(subElementIds[i], mRS);
            mElements[i].updateFromNative();
            mOffsetInBytes[i] = mSize;
            mSize += mElements[i].mSize * mArraySizes[i];
        }
    }
    */
    updateVisibleSubElements();
}

const Element * Element::createUser(RenderScript *rs, RsDataType dt) {
    ALOGE("createUser %p %i", rs, dt);
    void * id = rsElementCreate(rs->mContext, dt, RS_KIND_USER, false, 1);
    return new Element(id, rs, dt, RS_KIND_USER, false, 1);
}

const Element * Element::createVector(RenderScript *rs, RsDataType dt, uint32_t size) {
    if (size < 2 || size > 4) {
        ALOGE("Vector size out of range 2-4.");
        return NULL;
    }
    void *id = rsElementCreate(rs->mContext, dt, RS_KIND_USER, false, size);
    return new Element(id, rs, dt, RS_KIND_USER, false, size);
}

const Element * Element::createPixel(RenderScript *rs, RsDataType dt, RsDataKind dk) {
    ALOGE("createPixel %p %i %i", rs, dt, dk);
    if (!(dk == RS_KIND_PIXEL_L ||
          dk == RS_KIND_PIXEL_A ||
          dk == RS_KIND_PIXEL_LA ||
          dk == RS_KIND_PIXEL_RGB ||
          dk == RS_KIND_PIXEL_RGBA ||
          dk == RS_KIND_PIXEL_DEPTH)) {
        ALOGE("Unsupported DataKind");
        return NULL;
    }
    if (!(dt == RS_TYPE_UNSIGNED_8 ||
          dt == RS_TYPE_UNSIGNED_16 ||
          dt == RS_TYPE_UNSIGNED_5_6_5 ||
          dt == RS_TYPE_UNSIGNED_4_4_4_4 ||
          dt == RS_TYPE_UNSIGNED_5_5_5_1)) {
        ALOGE("Unsupported DataType");
        return NULL;
    }
    if (dt == RS_TYPE_UNSIGNED_5_6_5 && dk != RS_KIND_PIXEL_RGB) {
        ALOGE("Bad kind and type combo");
        return NULL;
    }
    if (dt == RS_TYPE_UNSIGNED_5_5_5_1 && dk != RS_KIND_PIXEL_RGBA) {
        ALOGE("Bad kind and type combo");
        return NULL;
    }
    if (dt == RS_TYPE_UNSIGNED_4_4_4_4 && dk != RS_KIND_PIXEL_RGBA) {
        ALOGE("Bad kind and type combo");
        return NULL;
    }
    if (dt == RS_TYPE_UNSIGNED_16 && dk != RS_KIND_PIXEL_DEPTH) {
        ALOGE("Bad kind and type combo");
        return NULL;
    }

    int size = 1;
    switch (dk) {
    case RS_KIND_PIXEL_LA:
        size = 2;
        break;
    case RS_KIND_PIXEL_RGB:
        size = 3;
        break;
    case RS_KIND_PIXEL_RGBA:
        size = 4;
        break;
    case RS_KIND_PIXEL_DEPTH:
        size = 2;
        break;
    default:
        break;
    }

    void * id = rsElementCreate(rs->mContext, dt, dk, true, size);
    return new Element(id, rs, dt, dk, true, size);
}

bool Element::isCompatible(const Element *e) {
    // Try strict BaseObj equality to start with.
    if (this == e) {
        return true;
    }

    // Ignore mKind because it is allowed to be different (user vs. pixel).
    // We also ignore mNormalized because it can be different. The mType
    // field must be non-null since we require name equivalence for
    // user-created Elements.
    return ((mSizeBytes == e->mSizeBytes) &&
            (mType != NULL) &&
            (mType == e->mType) &&
            (mVectorSize == e->mVectorSize));
}

Element::Builder::Builder(RenderScript *rs) {
    mRS = rs;
    mSkipPadding = false;
}

void Element::Builder::add(const Element *e, android::String8 &name, uint32_t arraySize) {
    // Skip padding fields after a vector 3 type.
    if (mSkipPadding) {
        const char *s1 = "#padding_";
        const char *s2 = name;
        size_t len = strlen(s1);
        if (strlen(s2) >= len) {
            if (!memcmp(s1, s2, len)) {
                mSkipPadding = false;
                return;
            }
        }
    }

    if (e->mVectorSize == 3) {
        mSkipPadding = true;
    } else {
        mSkipPadding = false;
    }

    mElements.add(e);
    mElementNames.add(name);
    mArraySizes.add(arraySize);
}

const Element * Element::Builder::create() {
    size_t fieldCount = mElements.size();
    const char ** nameArray = (const char **)calloc(fieldCount, sizeof(char *));
    size_t* sizeArray = (size_t*)calloc(fieldCount, sizeof(size_t));

    for (size_t ct = 0; ct < fieldCount; ct++) {
        nameArray[ct] = mElementNames[ct].string();
        sizeArray[ct] = mElementNames[ct].length();
    }

    void *id = rsElementCreate2(mRS->mContext,
                                (RsElement *)mElements.array(), fieldCount,
                                nameArray, fieldCount * sizeof(size_t),  sizeArray,
                                (const uint32_t *)mArraySizes.array(), fieldCount);


    free(nameArray);
    free(sizeArray);

    Element *e = new Element(id, mRS, mElements, mElementNames, mArraySizes);
    return e;
}

