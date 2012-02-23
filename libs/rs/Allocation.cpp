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

#include "RenderScript.h"
#include "Element.h"
#include "Type.h"
#include "Allocation.h"


void * Allocation::getIDSafe() const {
    //if (mAdaptedAllocation != NULL) {
        //return mAdaptedAllocation.getID();
    //}
    return getID();
}

void Allocation::updateCacheInfo(const Type *t) {
    mCurrentDimX = t->getX();
    mCurrentDimY = t->getY();
    mCurrentDimZ = t->getZ();
    mCurrentCount = mCurrentDimX;
    if (mCurrentDimY > 1) {
        mCurrentCount *= mCurrentDimY;
    }
    if (mCurrentDimZ > 1) {
        mCurrentCount *= mCurrentDimZ;
    }
}

Allocation::Allocation(void *id, RenderScript *rs, const Type *t, uint32_t usage) : BaseObj(id, rs) {
    if ((usage & ~(RS_ALLOCATION_USAGE_SCRIPT |
                   RS_ALLOCATION_USAGE_GRAPHICS_TEXTURE |
                   RS_ALLOCATION_USAGE_GRAPHICS_VERTEX |
                   RS_ALLOCATION_USAGE_GRAPHICS_CONSTANTS |
                   RS_ALLOCATION_USAGE_GRAPHICS_RENDER_TARGET |
                   RS_ALLOCATION_USAGE_GRAPHICS_SURFACE_TEXTURE_INPUT_OPAQUE |
                   RS_ALLOCATION_USAGE_IO_INPUT |
                   RS_ALLOCATION_USAGE_IO_OUTPUT)) != 0) {
        ALOGE("Unknown usage specified.");
    }

    if ((usage & (RS_ALLOCATION_USAGE_GRAPHICS_SURFACE_TEXTURE_INPUT_OPAQUE |
                  RS_ALLOCATION_USAGE_IO_INPUT)) != 0) {
        mWriteAllowed = false;
        if ((usage & ~(RS_ALLOCATION_USAGE_GRAPHICS_SURFACE_TEXTURE_INPUT_OPAQUE |
                       RS_ALLOCATION_USAGE_IO_INPUT |
                       RS_ALLOCATION_USAGE_GRAPHICS_TEXTURE |
                       RS_ALLOCATION_USAGE_SCRIPT)) != 0) {
            ALOGE("Invalid usage combination.");
        }
    }

    mType = t;
    mUsage = usage;

    if (t != NULL) {
        updateCacheInfo(t);
    }
}

void Allocation::validateIsInt32() {
    RsDataType dt = mType->getElement()->getDataType();
    if ((dt == RS_TYPE_SIGNED_32) || (dt == RS_TYPE_UNSIGNED_32)) {
        return;
    }
    ALOGE("32 bit integer source does not match allocation type %i", dt);
}

void Allocation::validateIsInt16() {
    RsDataType dt = mType->getElement()->getDataType();
    if ((dt == RS_TYPE_SIGNED_16) || (dt == RS_TYPE_UNSIGNED_16)) {
        return;
    }
    ALOGE("16 bit integer source does not match allocation type %i", dt);
}

void Allocation::validateIsInt8() {
    RsDataType dt = mType->getElement()->getDataType();
    if ((dt == RS_TYPE_SIGNED_8) || (dt == RS_TYPE_UNSIGNED_8)) {
        return;
    }
    ALOGE("8 bit integer source does not match allocation type %i", dt);
}

void Allocation::validateIsFloat32() {
    RsDataType dt = mType->getElement()->getDataType();
    if (dt == RS_TYPE_FLOAT_32) {
        return;
    }
    ALOGE("32 bit float source does not match allocation type %i", dt);
}

void Allocation::validateIsObject() {
    RsDataType dt = mType->getElement()->getDataType();
    if ((dt == RS_TYPE_ELEMENT) ||
        (dt == RS_TYPE_TYPE) ||
        (dt == RS_TYPE_ALLOCATION) ||
        (dt == RS_TYPE_SAMPLER) ||
        (dt == RS_TYPE_SCRIPT) ||
        (dt == RS_TYPE_MESH) ||
        (dt == RS_TYPE_PROGRAM_FRAGMENT) ||
        (dt == RS_TYPE_PROGRAM_VERTEX) ||
        (dt == RS_TYPE_PROGRAM_RASTER) ||
        (dt == RS_TYPE_PROGRAM_STORE)) {
        return;
    }
    ALOGE("Object source does not match allocation type %i", dt);
}

void Allocation::updateFromNative() {
    BaseObj::updateFromNative();

    const void *typeID = rsaAllocationGetType(mRS->mContext, getID());
    if(typeID != NULL) {
        const Type *old = mType;
        Type *t = new Type((void *)typeID, mRS);
        t->updateFromNative();
        updateCacheInfo(t);
        mType = t;
        delete old;
    }
}

void Allocation::syncAll(RsAllocationUsageType srcLocation) {
    switch (srcLocation) {
    case RS_ALLOCATION_USAGE_SCRIPT:
    case RS_ALLOCATION_USAGE_GRAPHICS_CONSTANTS:
    case RS_ALLOCATION_USAGE_GRAPHICS_TEXTURE:
    case RS_ALLOCATION_USAGE_GRAPHICS_VERTEX:
        break;
    default:
        ALOGE("Source must be exactly one usage type.");
    }
    rsAllocationSyncAll(mRS->mContext, getIDSafe(), srcLocation);
}

void Allocation::ioSendOutput() {
    if ((mUsage & RS_ALLOCATION_USAGE_IO_OUTPUT) == 0) {
        ALOGE("Can only send buffer if IO_OUTPUT usage specified.");
    }
    rsAllocationIoSend(mRS->mContext, getID());
}

void Allocation::ioGetInput() {
    if ((mUsage & RS_ALLOCATION_USAGE_IO_INPUT) == 0) {
        ALOGE("Can only send buffer if IO_OUTPUT usage specified.");
    }
    rsAllocationIoReceive(mRS->mContext, getID());
}

/*
void copyFrom(BaseObj[] d) {
    mRS.validate();
    validateIsObject();
    if (d.length != mCurrentCount) {
        ALOGE("Array size mismatch, allocation sizeX = " +
                                             mCurrentCount + ", array length = " + d.length);
    }
    int i[] = new int[d.length];
    for (int ct=0; ct < d.length; ct++) {
        i[ct] = d[ct].getID();
    }
    copy1DRangeFromUnchecked(0, mCurrentCount, i);
}
*/


/*
void Allocation::setFromFieldPacker(int xoff, FieldPacker fp) {
    mRS.validate();
    int eSize = mType.mElement.getSizeBytes();
    final byte[] data = fp.getData();

    int count = data.length / eSize;
    if ((eSize * count) != data.length) {
        ALOGE("Field packer length " + data.length +
                                           " not divisible by element size " + eSize + ".");
    }
    copy1DRangeFromUnchecked(xoff, count, data);
}

void setFromFieldPacker(int xoff, int component_number, FieldPacker fp) {
    mRS.validate();
    if (component_number >= mType.mElement.mElements.length) {
        ALOGE("Component_number " + component_number + " out of range.");
    }
    if(xoff < 0) {
        ALOGE("Offset must be >= 0.");
    }

    final byte[] data = fp.getData();
    int eSize = mType.mElement.mElements[component_number].getSizeBytes();
    eSize *= mType.mElement.mArraySizes[component_number];

    if (data.length != eSize) {
        ALOGE("Field packer sizelength " + data.length +
                                           " does not match component size " + eSize + ".");
    }

    mRS.nAllocationElementData1D(getIDSafe(), xoff, mSelectedLOD,
                                 component_number, data, data.length);
}
*/

void Allocation::generateMipmaps() {
    rsAllocationGenerateMipmaps(mRS->mContext, getID());
}

void Allocation::copy1DRangeFromUnchecked(uint32_t off, size_t count, const void *data, size_t dataLen) {
    if(count < 1) {
        ALOGE("Count must be >= 1.");
        return;
    }
    if((off + count) > mCurrentCount) {
        ALOGE("Overflow, Available count %zu, got %zu at offset %zu.", mCurrentCount, count, off);
        return;
    }
    if((count * mType->getElement()->getSizeBytes()) > dataLen) {
        ALOGE("Array too small for allocation type.");
        return;
    }

    rsAllocation1DData(mRS->mContext, getIDSafe(), off, mSelectedLOD, count, data, dataLen);
}

void Allocation::copy1DRangeFrom(uint32_t off, size_t count, const int32_t *d, size_t dataLen) {
    validateIsInt32();
    copy1DRangeFromUnchecked(off, count, d, dataLen);
}

void Allocation::copy1DRangeFrom(uint32_t off, size_t count, const int16_t *d, size_t dataLen) {
    validateIsInt16();
    copy1DRangeFromUnchecked(off, count, d, dataLen);
}

void Allocation::copy1DRangeFrom(uint32_t off, size_t count, const int8_t *d, size_t dataLen) {
    validateIsInt8();
    copy1DRangeFromUnchecked(off, count, d, dataLen);
}

void Allocation::copy1DRangeFrom(uint32_t off, size_t count, const float *d, size_t dataLen) {
    validateIsFloat32();
    copy1DRangeFromUnchecked(off, count, d, dataLen);
}

void Allocation::copy1DRangeFrom(uint32_t off, size_t count, const Allocation *data, uint32_t dataOff) {
    rsAllocationCopy2DRange(mRS->mContext, getIDSafe(), off, 0,
                            mSelectedLOD, mSelectedFace,
                            count, 1, data->getIDSafe(), dataOff, 0,
                            data->mSelectedLOD, data->mSelectedFace);
}

void Allocation::validate2DRange(uint32_t xoff, uint32_t yoff, uint32_t w, uint32_t h) {
    if (mAdaptedAllocation != NULL) {

    } else {
        if (((xoff + w) > mCurrentDimX) || ((yoff + h) > mCurrentDimY)) {
            ALOGE("Updated region larger than allocation.");
        }
    }
}

void Allocation::copy2DRangeFrom(uint32_t xoff, uint32_t yoff, uint32_t w, uint32_t h,
                                 const int8_t *data, size_t dataLen) {
    validate2DRange(xoff, yoff, w, h);
    rsAllocation2DData(mRS->mContext, getIDSafe(), xoff, yoff, mSelectedLOD, mSelectedFace,
                       w, h, data, dataLen);
}

void Allocation::copy2DRangeFrom(uint32_t xoff, uint32_t yoff, uint32_t w, uint32_t h,
                                 const int16_t *data, size_t dataLen) {
    validate2DRange(xoff, yoff, w, h);
    rsAllocation2DData(mRS->mContext, getIDSafe(), xoff, yoff, mSelectedLOD, mSelectedFace,
                       w, h, data, dataLen);
}

void Allocation::copy2DRangeFrom(uint32_t xoff, uint32_t yoff, uint32_t w, uint32_t h,
                                 const int32_t *data, size_t dataLen) {
    validate2DRange(xoff, yoff, w, h);
    rsAllocation2DData(mRS->mContext, getIDSafe(), xoff, yoff, mSelectedLOD, mSelectedFace,
                       w, h, data, dataLen);
}

void Allocation::copy2DRangeFrom(uint32_t xoff, uint32_t yoff, uint32_t w, uint32_t h,
                                 const float *data, size_t dataLen) {
    validate2DRange(xoff, yoff, w, h);
    rsAllocation2DData(mRS->mContext, getIDSafe(), xoff, yoff, mSelectedLOD, mSelectedFace,
                       w, h, data, dataLen);
}

void Allocation::copy2DRangeFrom(uint32_t xoff, uint32_t yoff, uint32_t w, uint32_t h,
                                 const Allocation *data, size_t dataLen,
                                 uint32_t dataXoff, uint32_t dataYoff) {
    validate2DRange(xoff, yoff, w, h);
    rsAllocationCopy2DRange(mRS->mContext, getIDSafe(), xoff, yoff,
                            mSelectedLOD, mSelectedFace,
                            w, h, data->getIDSafe(), dataXoff, dataYoff,
                            data->mSelectedLOD, data->mSelectedFace);
}

/*
void copyTo(byte[] d) {
    validateIsInt8();
    mRS.validate();
    mRS.nAllocationRead(getID(), d);
}

void copyTo(short[] d) {
    validateIsInt16();
    mRS.validate();
    mRS.nAllocationRead(getID(), d);
}

void copyTo(int[] d) {
    validateIsInt32();
    mRS.validate();
    mRS.nAllocationRead(getID(), d);
}

void copyTo(float[] d) {
    validateIsFloat32();
    mRS.validate();
    mRS.nAllocationRead(getID(), d);
}

void resize(int dimX) {
    if ((mType.getY() > 0)|| (mType.getZ() > 0) || mType.hasFaces() || mType.hasMipmaps()) {
        throw new RSInvalidStateException("Resize only support for 1D allocations at this time.");
    }
    mRS.nAllocationResize1D(getID(), dimX);
    mRS.finish();  // Necessary because resize is fifoed and update is async.

    int typeID = mRS.nAllocationGetType(getID());
    mType = new Type(typeID, mRS);
    mType.updateFromNative();
    updateCacheInfo(mType);
}

void resize(int dimX, int dimY) {
    if ((mType.getZ() > 0) || mType.hasFaces() || mType.hasMipmaps()) {
        throw new RSInvalidStateException(
            "Resize only support for 2D allocations at this time.");
    }
    if (mType.getY() == 0) {
        throw new RSInvalidStateException(
            "Resize only support for 2D allocations at this time.");
    }
    mRS.nAllocationResize2D(getID(), dimX, dimY);
    mRS.finish();  // Necessary because resize is fifoed and update is async.

    int typeID = mRS.nAllocationGetType(getID());
    mType = new Type(typeID, mRS);
    mType.updateFromNative();
    updateCacheInfo(mType);
}
*/


Allocation *Allocation::createTyped(RenderScript *rs, const Type *type,
                        RsAllocationMipmapControl mips, uint32_t usage) {
    void *id = rsAllocationCreateTyped(rs->mContext, type->getID(), mips, usage, 0);
    if (id == 0) {
        ALOGE("Allocation creation failed.");
        return NULL;
    }
    return new Allocation(id, rs, type, usage);
}

Allocation *Allocation::createTyped(RenderScript *rs, const Type *type,
                                    RsAllocationMipmapControl mips, uint32_t usage, void *pointer) {
    void *id = rsAllocationCreateTyped(rs->mContext, type->getID(), mips, usage, (uint32_t)pointer);
    if (id == 0) {
        ALOGE("Allocation creation failed.");
    }
    return new Allocation(id, rs, type, usage);
}

Allocation *Allocation::createTyped(RenderScript *rs, const Type *type, uint32_t usage) {
    return createTyped(rs, type, RS_ALLOCATION_MIPMAP_NONE, usage);
}

Allocation *Allocation::createSized(RenderScript *rs, const Element *e, size_t count, uint32_t usage) {
    Type::Builder b(rs, e);
    b.setX(count);
    const Type *t = b.create();

    void *id = rsAllocationCreateTyped(rs->mContext, t->getID(), RS_ALLOCATION_MIPMAP_NONE, usage, 0);
    if (id == 0) {
        ALOGE("Allocation creation failed.");
    }
    return new Allocation(id, rs, t, usage);
}


/*
SurfaceTexture getSurfaceTexture() {
    if ((mUsage & USAGE_GRAPHICS_SURFACE_TEXTURE_INPUT_OPAQUE) == 0) {
        throw new RSInvalidStateException("Allocation is not a surface texture.");
    }

    int id = mRS.nAllocationGetSurfaceTextureID(getID());
    return new SurfaceTexture(id);

}

void setSurfaceTexture(SurfaceTexture sur) {
    if ((mUsage & USAGE_IO_OUTPUT) == 0) {
        throw new RSInvalidStateException("Allocation is not USAGE_IO_OUTPUT.");
    }

    mRS.validate();
    mRS.nAllocationSetSurfaceTexture(getID(), sur);
}


static Allocation createFromBitmapResource(RenderScript rs,
                                                  Resources res,
                                                  int id,
                                                  MipmapControl mips,
                                                  int usage) {

    rs.validate();
    Bitmap b = BitmapFactory.decodeResource(res, id);
    Allocation alloc = createFromBitmap(rs, b, mips, usage);
    b.recycle();
    return alloc;
}

static Allocation createFromBitmapResource(RenderScript rs,
                                                  Resources res,
                                                  int id) {
    return createFromBitmapResource(rs, res, id,
                                    MipmapControl.MIPMAP_NONE,
                                    USAGE_GRAPHICS_TEXTURE);
}

static Allocation createFromString(RenderScript rs,
                                          String str,
                                          int usage) {
    rs.validate();
    byte[] allocArray = NULL;
    try {
        allocArray = str.getBytes("UTF-8");
        Allocation alloc = Allocation.createSized(rs, Element.U8(rs), allocArray.length, usage);
        alloc.copyFrom(allocArray);
        return alloc;
    }
    catch (Exception e) {
        throw new RSRuntimeException("Could not convert string to utf-8.");
    }
}
*/

