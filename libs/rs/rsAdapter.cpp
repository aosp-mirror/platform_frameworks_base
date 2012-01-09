
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

Adapter1D::Adapter1D(Context *rsc) : ObjectBase(rsc) {
    reset();
}

Adapter1D::Adapter1D(Context *rsc, Allocation *a) : ObjectBase(rsc) {
    reset();
    setAllocation(a);
}

void Adapter1D::reset() {
    mY = 0;
    mZ = 0;
    mLOD = 0;
    mFace = 0;
}

void * Adapter1D::getElement(uint32_t x) {
    rsAssert(mAllocation.get());
    rsAssert(mAllocation->getPtr());
    rsAssert(mAllocation->getType());
    uint8_t * ptr = static_cast<uint8_t *>(mAllocation->getPtr());
    ptr += mAllocation->getType()->getLODOffset(mLOD, x, mY);
    return ptr;
}

void Adapter1D::subData(uint32_t xoff, uint32_t count, const void *data) {
    if (mAllocation.get() && mAllocation.get()->getType()) {
        void *ptr = getElement(xoff);
        count *= mAllocation.get()->getType()->getElementSizeBytes();
        memcpy(ptr, data, count);
    }
}

void Adapter1D::data(const void *data) {
    memcpy(getElement(0),
           data,
           mAllocation.get()->getType()->getSizeBytes());
}

void Adapter1D::serialize(OStream *stream) const {
}

Adapter1D *Adapter1D::createFromStream(Context *rsc, IStream *stream) {
    return NULL;
}

namespace android {
namespace renderscript {

RsAdapter1D rsi_Adapter1DCreate(Context *rsc) {
    Adapter1D *a = new Adapter1D(rsc);
    a->incUserRef();
    return a;
}

void rsi_Adapter1DBindAllocation(Context *rsc, RsAdapter1D va, RsAllocation valloc) {
    Adapter1D * a = static_cast<Adapter1D *>(va);
    Allocation * alloc = static_cast<Allocation *>(valloc);
    a->setAllocation(alloc);
}

void rsi_Adapter1DSetConstraint(Context *rsc, RsAdapter1D va, RsDimension dim, uint32_t value) {
    Adapter1D * a = static_cast<Adapter1D *>(va);
    switch (dim) {
    case RS_DIMENSION_X:
        rsAssert(!"Cannot contrain X in an 1D adapter");
        return;
    case RS_DIMENSION_Y:
        a->setY(value);
        break;
    case RS_DIMENSION_Z:
        a->setZ(value);
        break;
    case RS_DIMENSION_LOD:
        a->setLOD(value);
        break;
    case RS_DIMENSION_FACE:
        a->setFace(value);
        break;
    default:
        rsAssert(!"Unimplemented constraint");
        return;
    }
}

void rsi_Adapter1DSubData(Context *rsc, RsAdapter1D va, uint32_t xoff, uint32_t count, const void *data) {
    Adapter1D * a = static_cast<Adapter1D *>(va);
    a->subData(xoff, count, data);
}

void rsi_Adapter1DData(Context *rsc, RsAdapter1D va, const void *data) {
    Adapter1D * a = static_cast<Adapter1D *>(va);
    a->data(data);
}

}
}

//////////////////////////

Adapter2D::Adapter2D(Context *rsc) : ObjectBase(rsc) {
    reset();
}

Adapter2D::Adapter2D(Context *rsc, Allocation *a) : ObjectBase(rsc) {
    reset();
    setAllocation(a);
}

void Adapter2D::reset() {
    mZ = 0;
    mLOD = 0;
    mFace = 0;
}

void * Adapter2D::getElement(uint32_t x, uint32_t y) const {
    rsAssert(mAllocation.get());
    rsAssert(mAllocation->getPtr());
    rsAssert(mAllocation->getType());
    if (mFace != 0 && !mAllocation->getType()->getDimFaces()) {
        ALOGE("Adapter wants cubemap face, but allocation has none");
        return NULL;
    }

    uint8_t * ptr = static_cast<uint8_t *>(mAllocation->getPtr());
    ptr += mAllocation->getType()->getLODOffset(mLOD, x, y);

    if (mFace != 0) {
        uint32_t totalSizeBytes = mAllocation->getType()->getSizeBytes();
        uint32_t faceOffset = totalSizeBytes / 6;
        ptr += faceOffset * mFace;
    }
    return ptr;
}

void Adapter2D::subData(uint32_t xoff, uint32_t yoff, uint32_t w, uint32_t h, const void *data) {
    rsAssert(mAllocation.get());
    rsAssert(mAllocation->getPtr());
    rsAssert(mAllocation->getType());

    uint32_t eSize = mAllocation.get()->getType()->getElementSizeBytes();
    uint32_t lineSize = eSize * w;

    const uint8_t *src = static_cast<const uint8_t *>(data);
    for (uint32_t line=yoff; line < (yoff+h); line++) {
        memcpy(getElement(xoff, line), src, lineSize);
        src += lineSize;
    }
}

void Adapter2D::data(const void *data) {
    memcpy(getElement(0,0),
           data,
           mAllocation.get()->getType()->getSizeBytes());
}

void Adapter2D::serialize(OStream *stream) const {
}

Adapter2D *Adapter2D::createFromStream(Context *rsc, IStream *stream) {
    return NULL;
}


namespace android {
namespace renderscript {

RsAdapter2D rsi_Adapter2DCreate(Context *rsc) {
    Adapter2D *a = new Adapter2D(rsc);
    a->incUserRef();
    return a;
}

void rsi_Adapter2DBindAllocation(Context *rsc, RsAdapter2D va, RsAllocation valloc) {
    Adapter2D * a = static_cast<Adapter2D *>(va);
    Allocation * alloc = static_cast<Allocation *>(valloc);
    a->setAllocation(alloc);
}

void rsi_Adapter2DSetConstraint(Context *rsc, RsAdapter2D va, RsDimension dim, uint32_t value) {
    Adapter2D * a = static_cast<Adapter2D *>(va);
    switch (dim) {
    case RS_DIMENSION_X:
        rsAssert(!"Cannot contrain X in an 2D adapter");
        return;
    case RS_DIMENSION_Y:
        rsAssert(!"Cannot contrain Y in an 2D adapter");
        break;
    case RS_DIMENSION_Z:
        a->setZ(value);
        break;
    case RS_DIMENSION_LOD:
        a->setLOD(value);
        break;
    case RS_DIMENSION_FACE:
        a->setFace(value);
        break;
    default:
        rsAssert(!"Unimplemented constraint");
        return;
    }
}

void rsi_Adapter2DData(Context *rsc, RsAdapter2D va, const void *data) {
    Adapter2D * a = static_cast<Adapter2D *>(va);
    a->data(data);
}

void rsi_Adapter2DSubData(Context *rsc, RsAdapter2D va, uint32_t xoff, uint32_t yoff, uint32_t w, uint32_t h, const void *data) {
    Adapter2D * a = static_cast<Adapter2D *>(va);
    a->subData(xoff, yoff, w, h, data);
}

}
}
