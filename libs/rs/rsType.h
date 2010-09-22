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

#ifndef ANDROID_STRUCTURED_TYPE_H
#define ANDROID_STRUCTURED_TYPE_H

#include "rsElement.h"
#include "rsVertexArray.h"

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {


class Type : public ObjectBase
{
public:
    Type(Context *);
    virtual ~Type();

    Type * createTex2D(const Element *, size_t w, size_t h, bool mip);


    size_t getOffsetForFace(uint32_t face) const;

    size_t getSizeBytes() const {return mTotalSizeBytes;}
    size_t getElementSizeBytes() const {return mElement->getSizeBytes();}
    const Element * getElement() const {return mElement.get();}

    uint32_t getDimX() const {return mDimX;}
    uint32_t getDimY() const {return mDimY;}
    uint32_t getDimZ() const {return mDimZ;}
    uint32_t getDimLOD() const {return mDimLOD;}
    bool getDimFaces() const {return mFaces;}

    uint32_t getLODDimX(uint32_t lod) const {rsAssert(lod < mLODCount); return mLODs[lod].mX;}
    uint32_t getLODDimY(uint32_t lod) const {rsAssert(lod < mLODCount); return mLODs[lod].mY;}
    uint32_t getLODDimZ(uint32_t lod) const {rsAssert(lod < mLODCount); return mLODs[lod].mZ;}
    uint32_t getLODOffset(uint32_t lod) const {rsAssert(lod < mLODCount); return mLODs[lod].mOffset;}

    uint32_t getLODOffset(uint32_t lod, uint32_t x) const;
    uint32_t getLODOffset(uint32_t lod, uint32_t x, uint32_t y) const;
    uint32_t getLODOffset(uint32_t lod, uint32_t x, uint32_t y, uint32_t z) const;

    uint32_t getLODCount() const {return mLODCount;}
    bool getIsNp2() const;


    void setElement(const Element *e) {mElement.set(e);}
    void setDimX(uint32_t v) {mDimX = v;}
    void setDimY(uint32_t v) {mDimY = v;}
    void setDimZ(uint32_t v) {mDimZ = v;}
    void setDimFaces(bool v) {mFaces = v;}
    void setDimLOD(bool v) {mDimLOD = v;}


    void clear();
    void compute();

    void enableGLVertexBuffer(class VertexArray *) const;

    void dumpLOGV(const char *prefix) const;
    virtual void serialize(OStream *stream) const;
    virtual RsA3DClassID getClassId() const { return RS_A3D_CLASS_ID_TYPE; }
    static Type *createFromStream(Context *rsc, IStream *stream);

protected:
    struct LOD {
        size_t mX;
        size_t mY;
        size_t mZ;
        size_t mOffset;
    };

    void makeLODTable();

    // Internal structure from most to least significant.
    // * Array dimensions
    // * Faces
    // * Mipmaps
    // * xyz

    ObjectBaseRef<const Element> mElement;

    // Size of the structure in the various dimensions.  A missing Dimension is
    // specified as a 0 and not a 1.
    size_t mDimX;
    size_t mDimY;
    size_t mDimZ;
    bool mDimLOD;
    bool mFaces;

    // A list of array dimensions.  The count is the number of array dimensions and the
    // sizes is a per array size.
    //Vector<size_t> mDimArraysSizes;

    // count of mipmap levels, 0 indicates no mipmapping

    size_t mMipChainSizeBytes;
    size_t mTotalSizeBytes;
    LOD *mLODs;
    uint32_t mLODCount;

    VertexArray::Attrib mAttribs[RS_MAX_ATTRIBS];
    void makeGLComponents();

private:
    Type(const Type &);
};


class TypeState {
public:
    TypeState();
    ~TypeState();

    size_t mX;
    size_t mY;
    size_t mZ;
    uint32_t mLOD;
    bool mFaces;
    ObjectBaseRef<const Element> mElement;


    // Cache of all existing types.
    Vector<Type *> mTypes;
};


}
}
#endif //ANDROID_STRUCTURED_TYPE
