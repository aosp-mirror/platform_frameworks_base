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

#ifndef __ANDROID_ELEMENT_H__
#define __ANDROID_ELEMENT_H__

#include <rs.h>
#include "RenderScript.h"
#include "BaseObj.h"

class Element : public BaseObj {
public:
    /**
     * Return if a element is too complex for use as a data source for a Mesh or
     * a Program.
     *
     * @return boolean
     */
    bool isComplex();

    /**
    * @hide
    * @return number of sub-elements in this element
    */
    size_t getSubElementCount() {
        return mVisibleElementMap.size();
    }

    /**
    * @hide
    * @param index index of the sub-element to return
    * @return sub-element in this element at given index
    */
    const Element * getSubElement(uint32_t index);

    /**
    * @hide
    * @param index index of the sub-element
    * @return sub-element in this element at given index
    */
    const char * getSubElementName(uint32_t index);

    /**
    * @hide
    * @param index index of the sub-element
    * @return array size of sub-element in this element at given index
    */
    size_t getSubElementArraySize(uint32_t index);

    /**
    * @hide
    * @param index index of the sub-element
    * @return offset in bytes of sub-element in this element at given index
    */
    uint32_t getSubElementOffsetBytes(uint32_t index);

    /**
    * @hide
    * @return element data type
    */
    RsDataType getDataType() const {
        return mType;
    }

    /**
    * @hide
    * @return element data kind
    */
    RsDataKind getDataKind() const {
        return mKind;
    }

    size_t getSizeBytes() const {
        return mSizeBytes;
    }


    static const Element * BOOLEAN(RenderScript *rs);
    static const Element * U8(RenderScript *rs);
    static const Element * I8(RenderScript *rs);
    static const Element * U16(RenderScript *rs);
    static const Element * I16(RenderScript *rs);
    static const Element * U32(RenderScript *rs);
    static const Element * I32(RenderScript *rs);
    static const Element * U64(RenderScript *rs);
    static const Element * I64(RenderScript *rs);
    static const Element * F32(RenderScript *rs);
    static const Element * F64(RenderScript *rs);
    static const Element * ELEMENT(RenderScript *rs);
    static const Element * TYPE(RenderScript *rs);
    static const Element * ALLOCATION(RenderScript *rs);
    static const Element * SAMPLER(RenderScript *rs);
    static const Element * SCRIPT(RenderScript *rs);
    static const Element * MESH(RenderScript *rs);
    static const Element * PROGRAM_FRAGMENT(RenderScript *rs);
    static const Element * PROGRAM_VERTEX(RenderScript *rs);
    static const Element * PROGRAM_RASTER(RenderScript *rs);
    static const Element * PROGRAM_STORE(RenderScript *rs);

    static const Element * A_8(RenderScript *rs);
    static const Element * RGB_565(RenderScript *rs);
    static const Element * RGB_888(RenderScript *rs);
    static const Element * RGBA_5551(RenderScript *rs);
    static const Element * RGBA_4444(RenderScript *rs);
    static const Element * RGBA_8888(RenderScript *rs);

    static const Element * F32_2(RenderScript *rs);
    static const Element * F32_3(RenderScript *rs);
    static const Element * F32_4(RenderScript *rs);
    static const Element * F64_2(RenderScript *rs);
    static const Element * F64_3(RenderScript *rs);
    static const Element * F64_4(RenderScript *rs);
    static const Element * U8_2(RenderScript *rs);
    static const Element * U8_3(RenderScript *rs);
    static const Element * U8_4(RenderScript *rs);
    static const Element * I8_2(RenderScript *rs);
    static const Element * I8_3(RenderScript *rs);
    static const Element * I8_4(RenderScript *rs);
    static const Element * U16_2(RenderScript *rs);
    static const Element * U16_3(RenderScript *rs);
    static const Element * U16_4(RenderScript *rs);
    static const Element * I16_2(RenderScript *rs);
    static const Element * I16_3(RenderScript *rs);
    static const Element * I16_4(RenderScript *rs);
    static const Element * U32_2(RenderScript *rs);
    static const Element * U32_3(RenderScript *rs);
    static const Element * U32_4(RenderScript *rs);
    static const Element * I32_2(RenderScript *rs);
    static const Element * I32_3(RenderScript *rs);
    static const Element * I32_4(RenderScript *rs);
    static const Element * U64_2(RenderScript *rs);
    static const Element * U64_3(RenderScript *rs);
    static const Element * U64_4(RenderScript *rs);
    static const Element * I64_2(RenderScript *rs);
    static const Element * I64_3(RenderScript *rs);
    static const Element * I64_4(RenderScript *rs);
    static const Element * MATRIX_4X4(RenderScript *rs);
    static const Element * MATRIX_3X3(RenderScript *rs);
    static const Element * MATRIX_2X2(RenderScript *rs);

    Element(void *id, RenderScript *rs,
            android::Vector<const Element *> &elements,
            android::Vector<android::String8> &elementNames,
            android::Vector<uint32_t> &arraySizes);
    Element(void *id, RenderScript *rs, RsDataType dt, RsDataKind dk, bool norm, uint32_t size);
    Element(RenderScript *rs);
    virtual ~Element();

    void updateFromNative();
    static const Element * createUser(RenderScript *rs, RsDataType dt);
    static const Element * createVector(RenderScript *rs, RsDataType dt, uint32_t size);
    static const Element * createPixel(RenderScript *rs, RsDataType dt, RsDataKind dk);
    bool isCompatible(const Element *e);

    class Builder {
    private:
        RenderScript *mRS;
        android::Vector<const Element *> mElements;
        android::Vector<android::String8> mElementNames;
        android::Vector<uint32_t> mArraySizes;
        bool mSkipPadding;

    public:
        Builder(RenderScript *rs);
        ~Builder();
        void add(const Element *, android::String8 &name, uint32_t arraySize = 1);
        const Element * create();
    };

private:
    void updateVisibleSubElements();

    android::Vector<const Element *> mElements;
    android::Vector<android::String8> mElementNames;
    android::Vector<uint32_t> mArraySizes;
    android::Vector<uint32_t> mVisibleElementMap;
    android::Vector<uint32_t> mOffsetInBytes;

    RsDataType mType;
    RsDataKind mKind;
    bool mNormalized;
    size_t mSizeBytes;
    size_t mVectorSize;
};

#endif
