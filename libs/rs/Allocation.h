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

#ifndef __ANDROID_ALLOCATION_H__
#define __ANDROID_ALLOCATION_H__

#include <pthread.h>
#include <rs.h>

#include "RenderScript.h"
#include "Type.h"
#include "Element.h"

class Allocation : public BaseObj {
protected:
    const Type *mType;
    uint32_t mUsage;
    Allocation *mAdaptedAllocation;

    bool mConstrainedLOD;
    bool mConstrainedFace;
    bool mConstrainedY;
    bool mConstrainedZ;
    bool mReadAllowed;
    bool mWriteAllowed;
    uint32_t mSelectedY;
    uint32_t mSelectedZ;
    uint32_t mSelectedLOD;
    RsAllocationCubemapFace mSelectedFace;

    uint32_t mCurrentDimX;
    uint32_t mCurrentDimY;
    uint32_t mCurrentDimZ;
    uint32_t mCurrentCount;


    void * getIDSafe() const;
    void updateCacheInfo(const Type *t);

    Allocation(void *id, RenderScript *rs, const Type *t, uint32_t usage);

    void validateIsInt32();
    void validateIsInt16();
    void validateIsInt8();
    void validateIsFloat32();
    void validateIsObject();

    virtual void updateFromNative();

    void validate2DRange(uint32_t xoff, uint32_t yoff, uint32_t w, uint32_t h);

public:
    const Type * getType() {
        return mType;
    }

    void syncAll(RsAllocationUsageType srcLocation);
    void ioSendOutput();
    void ioGetInput();

    //void copyFrom(BaseObj[] d);
    //void copyFromUnchecked(int[] d);
    //void copyFromUnchecked(short[] d);
    //void copyFromUnchecked(byte[] d);
    //void copyFromUnchecked(float[] d);
    //void copyFrom(int[] d);
    //void copyFrom(short[] d);
    //void copyFrom(byte[] d);
    //void copyFrom(float[] d);
    //void setFromFieldPacker(int xoff, FieldPacker fp);
    //void setFromFieldPacker(int xoff, int component_number, FieldPacker fp);
    void generateMipmaps();
    void copy1DRangeFromUnchecked(uint32_t off, size_t count, const void *data, size_t dataLen);
    void copy1DRangeFrom(uint32_t off, size_t count, const int32_t* d, size_t dataLen);
    void copy1DRangeFrom(uint32_t off, size_t count, const int16_t* d, size_t dataLen);
    void copy1DRangeFrom(uint32_t off, size_t count, const int8_t* d, size_t dataLen);
    void copy1DRangeFrom(uint32_t off, size_t count, const float* d, size_t dataLen);
    void copy1DRangeFrom(uint32_t off, size_t count, const Allocation *data, uint32_t dataOff);

    void copy2DRangeFrom(uint32_t xoff, uint32_t yoff, uint32_t w, uint32_t h,
                         const int32_t *data, size_t dataLen);
    void copy2DRangeFrom(uint32_t xoff, uint32_t yoff, uint32_t w, uint32_t h,
                         const int16_t *data, size_t dataLen);
    void copy2DRangeFrom(uint32_t xoff, uint32_t yoff, uint32_t w, uint32_t h,
                         const int8_t *data, size_t dataLen);
    void copy2DRangeFrom(uint32_t xoff, uint32_t yoff, uint32_t w, uint32_t h,
                         const float *data, size_t dataLen);
    void copy2DRangeFrom(uint32_t xoff, uint32_t yoff, uint32_t w, uint32_t h,
                         const Allocation *data, size_t dataLen,
                         uint32_t dataXoff, uint32_t dataYoff);

    //void copyTo(byte[] d);
    //void copyTo(short[] d);
    //void copyTo(int[] d);
    //void copyTo(float[] d);
    void resize(int dimX);
    void resize(int dimX, int dimY);

    static Allocation *createTyped(RenderScript *rs, const Type *type,
                                   RsAllocationMipmapControl mips, uint32_t usage);
    static Allocation *createTyped(RenderScript *rs, const Type *type,
                                   RsAllocationMipmapControl mips, uint32_t usage, void * pointer);

    static Allocation *createTyped(RenderScript *rs, const Type *type,
                                   uint32_t usage = RS_ALLOCATION_USAGE_SCRIPT);
    static Allocation *createSized(RenderScript *rs, const Element *e, size_t count,
                                   uint32_t usage = RS_ALLOCATION_USAGE_SCRIPT);
    //SurfaceTexture *getSurfaceTexture();
    //void setSurfaceTexture(SurfaceTexture *sur);

};

#endif
