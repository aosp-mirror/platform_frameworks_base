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
#include "rs_hal.h"


using namespace android;
using namespace android::renderscript;

Allocation::Allocation(Context *rsc, const Type *type, uint32_t usages,
                       RsAllocationMipmapControl mc)
    : ObjectBase(rsc) {

    memset(&mHal, 0, sizeof(mHal));
    mHal.state.mipmapControl = RS_ALLOCATION_MIPMAP_NONE;
    mHal.state.usageFlags = usages;
    mHal.state.mipmapControl = mc;

    mHal.state.type.set(type);
    updateCache();
}

Allocation * Allocation::createAllocation(Context *rsc, const Type *type, uint32_t usages,
                              RsAllocationMipmapControl mc) {
    Allocation *a = new Allocation(rsc, type, usages, mc);

    if (!rsc->mHal.funcs.allocation.init(rsc, a, type->getElement()->getHasReferences())) {
        rsc->setError(RS_ERROR_FATAL_DRIVER, "Allocation::Allocation, alloc failure");
        delete a;
        return NULL;
    }
    return a;
}

void Allocation::updateCache() {
    const Type *type = mHal.state.type.get();
    mHal.state.dimensionX = type->getDimX();
    mHal.state.dimensionY = type->getDimY();
    mHal.state.dimensionZ = type->getDimZ();
    mHal.state.hasFaces = type->getDimFaces();
    mHal.state.hasMipmaps = type->getDimLOD();
    mHal.state.elementSizeBytes = type->getElementSizeBytes();
    mHal.state.hasReferences = mHal.state.type->getElement()->getHasReferences();
}

Allocation::~Allocation() {
    freeChildrenUnlocked();
    mRSC->mHal.funcs.allocation.destroy(mRSC, this);
}

void Allocation::syncAll(Context *rsc, RsAllocationUsageType src) {
    rsc->mHal.funcs.allocation.syncAll(rsc, this, src);
}

void Allocation::read(void *data) {
    memcpy(data, getPtr(), mHal.state.type->getSizeBytes());
}

void Allocation::data(Context *rsc, uint32_t xoff, uint32_t lod,
                         uint32_t count, const void *data, uint32_t sizeBytes) {
    const uint32_t eSize = mHal.state.type->getElementSizeBytes();

    if ((count * eSize) != sizeBytes) {
        LOGE("Allocation::subData called with mismatched size expected %i, got %i",
             (count * eSize), sizeBytes);
        mHal.state.type->dumpLOGV("type info");
        return;
    }

    rsc->mHal.funcs.allocation.data1D(rsc, this, xoff, lod, count, data, sizeBytes);
    sendDirty(rsc);
}

void Allocation::data(Context *rsc, uint32_t xoff, uint32_t yoff, uint32_t lod, RsAllocationCubemapFace face,
             uint32_t w, uint32_t h, const void *data, uint32_t sizeBytes) {
    const uint32_t eSize = mHal.state.elementSizeBytes;
    const uint32_t lineSize = eSize * w;

    //LOGE("data2d %p,  %i %i %i %i %i %i %p %i", this, xoff, yoff, lod, face, w, h, data, sizeBytes);

    if ((lineSize * h) != sizeBytes) {
        LOGE("Allocation size mismatch, expected %i, got %i", (lineSize * h), sizeBytes);
        rsAssert(!"Allocation::subData called with mismatched size");
        return;
    }

    rsc->mHal.funcs.allocation.data2D(rsc, this, xoff, yoff, lod, face, w, h, data, sizeBytes);
    sendDirty(rsc);
}

void Allocation::data(Context *rsc, uint32_t xoff, uint32_t yoff, uint32_t zoff,
                      uint32_t lod, RsAllocationCubemapFace face,
                      uint32_t w, uint32_t h, uint32_t d, const void *data, uint32_t sizeBytes) {
}

void Allocation::elementData(Context *rsc, uint32_t x, const void *data,
                                uint32_t cIdx, uint32_t sizeBytes) {
    uint32_t eSize = mHal.state.elementSizeBytes;

    if (cIdx >= mHal.state.type->getElement()->getFieldCount()) {
        LOGE("Error Allocation::subElementData component %i out of range.", cIdx);
        rsc->setError(RS_ERROR_BAD_VALUE, "subElementData component out of range.");
        return;
    }

    if (x >= mHal.state.dimensionX) {
        LOGE("Error Allocation::subElementData X offset %i out of range.", x);
        rsc->setError(RS_ERROR_BAD_VALUE, "subElementData X offset out of range.");
        return;
    }

    const Element * e = mHal.state.type->getElement()->getField(cIdx);
    if (sizeBytes != e->getSizeBytes()) {
        LOGE("Error Allocation::subElementData data size %i does not match field size %zu.", sizeBytes, e->getSizeBytes());
        rsc->setError(RS_ERROR_BAD_VALUE, "subElementData bad size.");
        return;
    }

    rsc->mHal.funcs.allocation.elementData1D(rsc, this, x, data, cIdx, sizeBytes);
    sendDirty(rsc);
}

void Allocation::elementData(Context *rsc, uint32_t x, uint32_t y,
                                const void *data, uint32_t cIdx, uint32_t sizeBytes) {
    uint32_t eSize = mHal.state.elementSizeBytes;

    if (x >= mHal.state.dimensionX) {
        LOGE("Error Allocation::subElementData X offset %i out of range.", x);
        rsc->setError(RS_ERROR_BAD_VALUE, "subElementData X offset out of range.");
        return;
    }

    if (y >= mHal.state.dimensionY) {
        LOGE("Error Allocation::subElementData X offset %i out of range.", x);
        rsc->setError(RS_ERROR_BAD_VALUE, "subElementData X offset out of range.");
        return;
    }

    if (cIdx >= mHal.state.type->getElement()->getFieldCount()) {
        LOGE("Error Allocation::subElementData component %i out of range.", cIdx);
        rsc->setError(RS_ERROR_BAD_VALUE, "subElementData component out of range.");
        return;
    }

    const Element * e = mHal.state.type->getElement()->getField(cIdx);

    if (sizeBytes != e->getSizeBytes()) {
        LOGE("Error Allocation::subElementData data size %i does not match field size %zu.", sizeBytes, e->getSizeBytes());
        rsc->setError(RS_ERROR_BAD_VALUE, "subElementData bad size.");
        return;
    }

    rsc->mHal.funcs.allocation.elementData2D(rsc, this, x, y, data, cIdx, sizeBytes);
    sendDirty(rsc);
}

void Allocation::addProgramToDirty(const Program *p) {
    mToDirtyList.push(p);
}

void Allocation::removeProgramToDirty(const Program *p) {
    for (size_t ct=0; ct < mToDirtyList.size(); ct++) {
        if (mToDirtyList[ct] == p) {
            mToDirtyList.removeAt(ct);
            return;
        }
    }
    rsAssert(0);
}

void Allocation::dumpLOGV(const char *prefix) const {
    ObjectBase::dumpLOGV(prefix);

    String8 s(prefix);
    s.append(" type ");
    if (mHal.state.type.get()) {
        mHal.state.type->dumpLOGV(s.string());
    }

    LOGV("%s allocation ptr=%p  mUsageFlags=0x04%x, mMipmapControl=0x%04x",
         prefix, getPtr(), mHal.state.usageFlags, mHal.state.mipmapControl);
}

void Allocation::serialize(OStream *stream) const {
    // Need to identify ourselves
    stream->addU32((uint32_t)getClassId());

    String8 name(getName());
    stream->addString(&name);

    // First thing we need to serialize is the type object since it will be needed
    // to initialize the class
    mHal.state.type->serialize(stream);

    uint32_t dataSize = mHal.state.type->getSizeBytes();
    // Write how much data we are storing
    stream->addU32(dataSize);
    // Now write the data
    stream->addByteArray(getPtr(), dataSize);
}

Allocation *Allocation::createFromStream(Context *rsc, IStream *stream) {
    // First make sure we are reading the correct object
    RsA3DClassID classID = (RsA3DClassID)stream->loadU32();
    if (classID != RS_A3D_CLASS_ID_ALLOCATION) {
        LOGE("allocation loading skipped due to invalid class id\n");
        return NULL;
    }

    String8 name;
    stream->loadString(&name);

    Type *type = Type::createFromStream(rsc, stream);
    if (!type) {
        return NULL;
    }
    type->compute();

    // Number of bytes we wrote out for this allocation
    uint32_t dataSize = stream->loadU32();
    if (dataSize != type->getSizeBytes()) {
        LOGE("failed to read allocation because numbytes written is not the same loaded type wants\n");
        ObjectBase::checkDelete(type);
        return NULL;
    }

    Allocation *alloc = Allocation::createAllocation(rsc, type, RS_ALLOCATION_USAGE_SCRIPT);
    alloc->setName(name.string(), name.size());
    type->decUserRef();

    uint32_t count = dataSize / type->getElementSizeBytes();

    // Read in all of our allocation data
    alloc->data(rsc, 0, 0, count, stream->getPtr() + stream->getPos(), dataSize);
    stream->reset(stream->getPos() + dataSize);

    return alloc;
}

void Allocation::sendDirty(const Context *rsc) const {
    for (size_t ct=0; ct < mToDirtyList.size(); ct++) {
        mToDirtyList[ct]->forceDirty();
    }
    mRSC->mHal.funcs.allocation.markDirty(rsc, this);
}

void Allocation::incRefs(const void *ptr, size_t ct, size_t startOff) const {
    const uint8_t *p = static_cast<const uint8_t *>(ptr);
    const Element *e = mHal.state.type->getElement();
    uint32_t stride = e->getSizeBytes();

    p += stride * startOff;
    while (ct > 0) {
        e->incRefs(p);
        ct --;
        p += stride;
    }
}

void Allocation::decRefs(const void *ptr, size_t ct, size_t startOff) const {
    if (!mHal.state.hasReferences || !getIsScript()) {
        return;
    }
    const uint8_t *p = static_cast<const uint8_t *>(ptr);
    const Element *e = mHal.state.type->getElement();
    uint32_t stride = e->getSizeBytes();

    p += stride * startOff;
    while (ct > 0) {
        e->decRefs(p);
        ct --;
        p += stride;
    }
}

void Allocation::freeChildrenUnlocked () {
    decRefs(getPtr(), mHal.state.type->getSizeBytes() / mHal.state.type->getElementSizeBytes(), 0);
}

bool Allocation::freeChildren() {
    if (mHal.state.hasReferences) {
        incSysRef();
        freeChildrenUnlocked();
        return decSysRef();
    }
    return false;
}

void Allocation::copyRange1D(Context *rsc, const Allocation *src, int32_t srcOff, int32_t destOff, int32_t len) {
}

void Allocation::resize1D(Context *rsc, uint32_t dimX) {
    uint32_t oldDimX = mHal.state.dimensionX;
    if (dimX == oldDimX) {
        return;
    }

    ObjectBaseRef<Type> t = mHal.state.type->cloneAndResize1D(rsc, dimX);
    if (dimX < oldDimX) {
        decRefs(getPtr(), oldDimX - dimX, dimX);
    }
    rsc->mHal.funcs.allocation.resize(rsc, this, t.get(), mHal.state.hasReferences);
    mHal.state.type.set(t.get());
    updateCache();
}

void Allocation::resize2D(Context *rsc, uint32_t dimX, uint32_t dimY) {
    LOGE("not implemented");
}

/////////////////
//

namespace android {
namespace renderscript {

static void AllocationGenerateScriptMips(RsContext con, RsAllocation va);

static void mip565(const Adapter2D &out, const Adapter2D &in) {
    uint32_t w = out.getDimX();
    uint32_t h = out.getDimY();

    for (uint32_t y=0; y < h; y++) {
        uint16_t *oPtr = static_cast<uint16_t *>(out.getElement(0, y));
        const uint16_t *i1 = static_cast<uint16_t *>(in.getElement(0, y*2));
        const uint16_t *i2 = static_cast<uint16_t *>(in.getElement(0, y*2+1));

        for (uint32_t x=0; x < w; x++) {
            *oPtr = rsBoxFilter565(i1[0], i1[1], i2[0], i2[1]);
            oPtr ++;
            i1 += 2;
            i2 += 2;
        }
    }
}

static void mip8888(const Adapter2D &out, const Adapter2D &in) {
    uint32_t w = out.getDimX();
    uint32_t h = out.getDimY();

    for (uint32_t y=0; y < h; y++) {
        uint32_t *oPtr = static_cast<uint32_t *>(out.getElement(0, y));
        const uint32_t *i1 = static_cast<uint32_t *>(in.getElement(0, y*2));
        const uint32_t *i2 = static_cast<uint32_t *>(in.getElement(0, y*2+1));

        for (uint32_t x=0; x < w; x++) {
            *oPtr = rsBoxFilter8888(i1[0], i1[1], i2[0], i2[1]);
            oPtr ++;
            i1 += 2;
            i2 += 2;
        }
    }
}

static void mip8(const Adapter2D &out, const Adapter2D &in) {
    uint32_t w = out.getDimX();
    uint32_t h = out.getDimY();

    for (uint32_t y=0; y < h; y++) {
        uint8_t *oPtr = static_cast<uint8_t *>(out.getElement(0, y));
        const uint8_t *i1 = static_cast<uint8_t *>(in.getElement(0, y*2));
        const uint8_t *i2 = static_cast<uint8_t *>(in.getElement(0, y*2+1));

        for (uint32_t x=0; x < w; x++) {
            *oPtr = (uint8_t)(((uint32_t)i1[0] + i1[1] + i2[0] + i2[1]) * 0.25f);
            oPtr ++;
            i1 += 2;
            i2 += 2;
        }
    }
}

static void mip(const Adapter2D &out, const Adapter2D &in) {
    switch (out.getBaseType()->getElement()->getSizeBits()) {
    case 32:
        mip8888(out, in);
        break;
    case 16:
        mip565(out, in);
        break;
    case 8:
        mip8(out, in);
        break;
    }
}

void rsi_AllocationSyncAll(Context *rsc, RsAllocation va, RsAllocationUsageType src) {
    Allocation *a = static_cast<Allocation *>(va);
    a->sendDirty(rsc);
    a->syncAll(rsc, src);
}

void rsi_AllocationGenerateMipmaps(Context *rsc, RsAllocation va) {
    Allocation *texAlloc = static_cast<Allocation *>(va);
    AllocationGenerateScriptMips(rsc, texAlloc);
}

void rsi_AllocationCopyToBitmap(Context *rsc, RsAllocation va, void *data, size_t dataLen) {
    Allocation *texAlloc = static_cast<Allocation *>(va);
    const Type * t = texAlloc->getType();

    size_t s = t->getDimX() * t->getDimY() * t->getElementSizeBytes();
    if (s != dataLen) {
        rsc->setError(RS_ERROR_BAD_VALUE, "Bitmap size didn't match allocation size");
        return;
    }

    memcpy(data, texAlloc->getPtr(), s);
}

void rsi_Allocation1DData(Context *rsc, RsAllocation va, uint32_t xoff, uint32_t lod,
                          uint32_t count, const void *data, size_t sizeBytes) {
    Allocation *a = static_cast<Allocation *>(va);
    a->data(rsc, xoff, lod, count, data, sizeBytes);
}

void rsi_Allocation2DElementData(Context *rsc, RsAllocation va, uint32_t x, uint32_t y, uint32_t lod, RsAllocationCubemapFace face,
                                 const void *data, size_t eoff, uint32_t sizeBytes) { // TODO: this seems wrong, eoff and sizeBytes may be swapped
    Allocation *a = static_cast<Allocation *>(va);
    a->elementData(rsc, x, y, data, eoff, sizeBytes);
}

void rsi_Allocation1DElementData(Context *rsc, RsAllocation va, uint32_t x, uint32_t lod,
                                 const void *data, size_t eoff, uint32_t sizeBytes) { // TODO: this seems wrong, eoff and sizeBytes may be swapped
    Allocation *a = static_cast<Allocation *>(va);
    a->elementData(rsc, x, data, eoff, sizeBytes);
}

void rsi_Allocation2DData(Context *rsc, RsAllocation va, uint32_t xoff, uint32_t yoff, uint32_t lod, RsAllocationCubemapFace face,
                          uint32_t w, uint32_t h, const void *data, size_t sizeBytes) {
    Allocation *a = static_cast<Allocation *>(va);
    a->data(rsc, xoff, yoff, lod, face, w, h, data, sizeBytes);
}

void rsi_AllocationRead(Context *rsc, RsAllocation va, void *data, size_t data_length) {
    Allocation *a = static_cast<Allocation *>(va);
    a->read(data);
}

void rsi_AllocationResize1D(Context *rsc, RsAllocation va, uint32_t dimX) {
    Allocation *a = static_cast<Allocation *>(va);
    a->resize1D(rsc, dimX);
}

void rsi_AllocationResize2D(Context *rsc, RsAllocation va, uint32_t dimX, uint32_t dimY) {
    Allocation *a = static_cast<Allocation *>(va);
    a->resize2D(rsc, dimX, dimY);
}

static void AllocationGenerateScriptMips(RsContext con, RsAllocation va) {
    Context *rsc = static_cast<Context *>(con);
    Allocation *texAlloc = static_cast<Allocation *>(va);
    uint32_t numFaces = texAlloc->getType()->getDimFaces() ? 6 : 1;
    for (uint32_t face = 0; face < numFaces; face ++) {
        Adapter2D adapt(rsc, texAlloc);
        Adapter2D adapt2(rsc, texAlloc);
        adapt.setFace(face);
        adapt2.setFace(face);
        for (uint32_t lod=0; lod < (texAlloc->getType()->getLODCount() -1); lod++) {
            adapt.setLOD(lod);
            adapt2.setLOD(lod + 1);
            mip(adapt2, adapt);
        }
    }
}

RsAllocation rsi_AllocationCreateTyped(Context *rsc, RsType vtype,
                                       RsAllocationMipmapControl mips,
                                       uint32_t usages) {
    Allocation * alloc = Allocation::createAllocation(rsc, static_cast<Type *>(vtype), usages, mips);
    if (!alloc) {
        return NULL;
    }
    alloc->incUserRef();
    return alloc;
}

RsAllocation rsi_AllocationCreateFromBitmap(Context *rsc, RsType vtype,
                                            RsAllocationMipmapControl mips,
                                            const void *data, size_t data_length, uint32_t usages) {
    Type *t = static_cast<Type *>(vtype);

    RsAllocation vTexAlloc = rsi_AllocationCreateTyped(rsc, vtype, mips, usages);
    Allocation *texAlloc = static_cast<Allocation *>(vTexAlloc);
    if (texAlloc == NULL) {
        LOGE("Memory allocation failure");
        return NULL;
    }

    memcpy(texAlloc->getPtr(), data, t->getDimX() * t->getDimY() * t->getElementSizeBytes());
    if (mips == RS_ALLOCATION_MIPMAP_FULL) {
        AllocationGenerateScriptMips(rsc, texAlloc);
    }

    texAlloc->sendDirty(rsc);
    return texAlloc;
}

RsAllocation rsi_AllocationCubeCreateFromBitmap(Context *rsc, RsType vtype,
                                                RsAllocationMipmapControl mips,
                                                const void *data, size_t data_length, uint32_t usages) {
    Type *t = static_cast<Type *>(vtype);

    // Cubemap allocation's faces should be Width by Width each.
    // Source data should have 6 * Width by Width pixels
    // Error checking is done in the java layer
    RsAllocation vTexAlloc = rsi_AllocationCreateTyped(rsc, vtype, mips, usages);
    Allocation *texAlloc = static_cast<Allocation *>(vTexAlloc);
    if (texAlloc == NULL) {
        LOGE("Memory allocation failure");
        return NULL;
    }

    uint32_t faceSize = t->getDimX();
    uint32_t strideBytes = faceSize * 6 * t->getElementSizeBytes();
    uint32_t copySize = faceSize * t->getElementSizeBytes();

    uint8_t *sourcePtr = (uint8_t*)data;
    for (uint32_t face = 0; face < 6; face ++) {
        Adapter2D faceAdapter(rsc, texAlloc);
        faceAdapter.setFace(face);

        for (uint32_t dI = 0; dI < faceSize; dI ++) {
            memcpy(faceAdapter.getElement(0, dI), sourcePtr + strideBytes * dI, copySize);
        }

        // Move the data pointer to the next cube face
        sourcePtr += copySize;
    }

    if (mips == RS_ALLOCATION_MIPMAP_FULL) {
        AllocationGenerateScriptMips(rsc, texAlloc);
    }

    texAlloc->sendDirty(rsc);
    return texAlloc;
}

void rsi_AllocationCopy2DRange(Context *rsc,
                               RsAllocation dstAlloc,
                               uint32_t dstXoff, uint32_t dstYoff,
                               uint32_t dstMip, uint32_t dstFace,
                               uint32_t width, uint32_t height,
                               RsAllocation srcAlloc,
                               uint32_t srcXoff, uint32_t srcYoff,
                               uint32_t srcMip, uint32_t srcFace) {
    Allocation *dst = static_cast<Allocation *>(dstAlloc);
    Allocation *src= static_cast<Allocation *>(srcAlloc);
    rsc->mHal.funcs.allocation.allocData2D(rsc, dst, dstXoff, dstYoff, dstMip,
                                           (RsAllocationCubemapFace)dstFace,
                                           width, height,
                                           src, srcXoff, srcYoff,srcMip,
                                           (RsAllocationCubemapFace)srcFace);
}

}
}

const void * rsaAllocationGetType(RsContext con, RsAllocation va) {
    Allocation *a = static_cast<Allocation *>(va);
    a->getType()->incUserRef();

    return a->getType();
}
