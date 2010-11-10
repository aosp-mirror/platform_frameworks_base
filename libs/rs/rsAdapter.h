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

#ifndef ANDROID_RS_ADAPTER_H
#define ANDROID_RS_ADAPTER_H

#include "rsAllocation.h"

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {


class Adapter1D : public ObjectBase {

public:
    // By policy this allocation will hold a pointer to the type
    // but will not destroy it on destruction.
    Adapter1D(Context *);
    Adapter1D(Context *, Allocation *);
    void reset();
    void * getElement(uint32_t x);

    void setAllocation(Allocation *a) {mAllocation.set(a);}

    uint32_t getDimX() const {return mAllocation->getType()->getLODDimX(mLOD);}

    const Type * getBaseType() const {return mAllocation->getType();}

    inline void setY(uint32_t y) {mY = y;}
    inline void setZ(uint32_t z) {mZ = z;}
    inline void setLOD(uint32_t lod) {mLOD = lod;}
    inline void setFace(uint32_t face) {mFace = face;}
    //void setArray(uint32_t num, uint32_t value);

    void subData(uint32_t xoff, uint32_t count, const void *data);
    void data(const void *data);

    virtual void serialize(OStream *stream) const;
    virtual RsA3DClassID getClassId() const { return RS_A3D_CLASS_ID_ADAPTER_1D; }
    static Adapter1D *createFromStream(Context *rsc, IStream *stream);

protected:
    ObjectBaseRef<Allocation> mAllocation;
    uint32_t mY;
    uint32_t mZ;
    uint32_t mLOD;
    uint32_t mFace;
};

class Adapter2D : public ObjectBase {

public:
    // By policy this allocation will hold a pointer to the type
    // but will not destroy it on destruction.
    Adapter2D(Context *);
    Adapter2D(Context *, Allocation *);
    void reset();
    void * getElement(uint32_t x, uint32_t y) const;

    uint32_t getDimX() const {return mAllocation->getType()->getLODDimX(mLOD);}
    uint32_t getDimY() const {return mAllocation->getType()->getLODDimY(mLOD);}
    const Type * getBaseType() const {return mAllocation->getType();}

    void setAllocation(Allocation *a) {mAllocation.set(a);}
    inline void setZ(uint32_t z) {mZ = z;}
    inline void setLOD(uint32_t lod) {mLOD = lod;}
    inline void setFace(uint32_t face) {mFace = face;}
    //void setArray(uint32_t num, uint32_t value);

    void data(const void *data);
    void subData(uint32_t xoff, uint32_t yoff, uint32_t w, uint32_t h, const void *data);

    virtual void serialize(OStream *stream) const;
    virtual RsA3DClassID getClassId() const { return RS_A3D_CLASS_ID_ADAPTER_2D; }
    static Adapter2D *createFromStream(Context *rsc, IStream *stream);

protected:
    ObjectBaseRef<Allocation> mAllocation;
    uint32_t mZ;
    uint32_t mLOD;
    uint32_t mFace;
};

}
}
#endif

