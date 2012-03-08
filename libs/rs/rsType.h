/*
 * Copyright (C) 2011 The Android Open Source Project
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

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {
/*****************************************************************************
 * CAUTION
 *
 * Any layout changes for this class may require a corresponding change to be
 * made to frameworks/compile/libbcc/lib/ScriptCRT/rs_core.c, which contains
 * a partial copy of the information below.
 *
 *****************************************************************************/

class Type : public ObjectBase {
public:
    struct Hal {
        mutable void *drv;

        struct State {
            const Element * element;

            // Size of the structure in the various dimensions.  A missing Dimension is
            // specified as a 0 and not a 1.
            uint32_t dimX;
            uint32_t dimY;
            uint32_t dimZ;
            uint32_t *lodDimX;
            uint32_t *lodDimY;
            uint32_t *lodDimZ;
            uint32_t *lodOffset;
            uint32_t lodCount;
            bool faces;
        };
        State state;
    };
    Hal mHal;

    Type * createTex2D(const Element *, size_t w, size_t h, bool mip);

    size_t getOffsetForFace(uint32_t face) const;

    size_t getSizeBytes() const {return mTotalSizeBytes;}
    size_t getElementSizeBytes() const {return mElement->getSizeBytes();}
    const Element * getElement() const {return mElement.get();}

    uint32_t getDimX() const {return mHal.state.dimX;}
    uint32_t getDimY() const {return mHal.state.dimY;}
    uint32_t getDimZ() const {return mHal.state.dimZ;}
    bool getDimLOD() const {return mDimLOD;}
    bool getDimFaces() const {return mHal.state.faces;}

    uint32_t getLODDimX(uint32_t lod) const {
        rsAssert(lod < mHal.state.lodCount);
        return mHal.state.lodDimX[lod];
    }
    uint32_t getLODDimY(uint32_t lod) const {
        rsAssert(lod < mHal.state.lodCount);
        return mHal.state.lodDimY[lod];
    }
    uint32_t getLODDimZ(uint32_t lod) const {
        rsAssert(lod < mHal.state.lodCount);
        return mHal.state.lodDimZ[lod];
    }
    uint32_t getLODOffset(uint32_t lod) const {
        rsAssert(lod < mHal.state.lodCount);
        return mHal.state.lodOffset[lod];
    }
    uint32_t getLODOffset(uint32_t lod, uint32_t x) const;
    uint32_t getLODOffset(uint32_t lod, uint32_t x, uint32_t y) const;
    uint32_t getLODOffset(uint32_t lod, uint32_t x, uint32_t y, uint32_t z) const;

    uint32_t getLODFaceOffset(uint32_t lod, RsAllocationCubemapFace face,
                              uint32_t x, uint32_t y) const;

    uint32_t getLODCount() const {return mHal.state.lodCount;}
    bool getIsNp2() const;

    void clear();
    void compute();

    void dumpLOGV(const char *prefix) const;
    virtual void serialize(OStream *stream) const;
    virtual RsA3DClassID getClassId() const { return RS_A3D_CLASS_ID_TYPE; }
    static Type *createFromStream(Context *rsc, IStream *stream);

    ObjectBaseRef<Type> cloneAndResize1D(Context *rsc, uint32_t dimX) const;
    ObjectBaseRef<Type> cloneAndResize2D(Context *rsc, uint32_t dimX, uint32_t dimY) const;

    static ObjectBaseRef<Type> getTypeRef(Context *rsc, const Element *e,
                                          uint32_t dimX, uint32_t dimY, uint32_t dimZ,
                                          bool dimLOD, bool dimFaces);

    static Type* getType(Context *rsc, const Element *e,
                         uint32_t dimX, uint32_t dimY, uint32_t dimZ,
                         bool dimLOD, bool dimFaces) {
        ObjectBaseRef<Type> type = getTypeRef(rsc, e, dimX, dimY, dimZ, dimLOD, dimFaces);
        type->incUserRef();
        return type.get();
    }

protected:
    void makeLODTable();
    bool mDimLOD;

    // Internal structure from most to least significant.
    // * Array dimensions
    // * Faces
    // * Mipmaps
    // * xyz

    ObjectBaseRef<const Element> mElement;

    // count of mipmap levels, 0 indicates no mipmapping

    size_t mMipChainSizeBytes;
    size_t mTotalSizeBytes;
protected:
    virtual void preDestroy() const;
    virtual ~Type();

private:
    Type(Context *);
    Type(const Type &);
};


class TypeState {
public:
    TypeState();
    ~TypeState();

    // Cache of all existing types.
    Vector<Type *> mTypes;
};


}
}
#endif //ANDROID_STRUCTURED_TYPE
