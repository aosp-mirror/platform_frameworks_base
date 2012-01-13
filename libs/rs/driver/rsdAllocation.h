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

#ifndef RSD_ALLOCATION_H
#define RSD_ALLOCATION_H

#include <rs_hal.h>
#include <rsRuntime.h>

#include <GLES/gl.h>
#include <GLES2/gl2.h>

class RsdFrameBufferObj;

struct DrvAllocation {
    // Is this a legal structure to be used as a texture source.
    // Initially this will require 1D or 2D and color data
    uint32_t textureID;

    // Is this a legal structure to be used as a vertex source.
    // Initially this will require 1D and x(yzw).  Additional per element data
    // is allowed.
    uint32_t bufferID;

    // Is this a legal structure to be used as an FBO render target
    uint32_t renderTargetID;

    uint8_t * mallocPtr;

    GLenum glTarget;
    GLenum glType;
    GLenum glFormat;

    bool uploadDeferred;

    RsdFrameBufferObj * readBackFBO;
};

GLenum rsdTypeToGLType(RsDataType t);
GLenum rsdKindToGLFormat(RsDataKind k);


bool rsdAllocationInit(const android::renderscript::Context *rsc,
                       android::renderscript::Allocation *alloc,
                       bool forceZero);
void rsdAllocationDestroy(const android::renderscript::Context *rsc,
                          android::renderscript::Allocation *alloc);

void rsdAllocationResize(const android::renderscript::Context *rsc,
                         const android::renderscript::Allocation *alloc,
                         const android::renderscript::Type *newType, bool zeroNew);
void rsdAllocationSyncAll(const android::renderscript::Context *rsc,
                          const android::renderscript::Allocation *alloc,
                          RsAllocationUsageType src);
void rsdAllocationMarkDirty(const android::renderscript::Context *rsc,
                            const android::renderscript::Allocation *alloc);
int32_t rsdAllocationInitSurfaceTexture(const android::renderscript::Context *rsc,
                                        const android::renderscript::Allocation *alloc);

void rsdAllocationData1D(const android::renderscript::Context *rsc,
                         const android::renderscript::Allocation *alloc,
                         uint32_t xoff, uint32_t lod, uint32_t count,
                         const void *data, uint32_t sizeBytes);
void rsdAllocationData2D(const android::renderscript::Context *rsc,
                         const android::renderscript::Allocation *alloc,
                         uint32_t xoff, uint32_t yoff, uint32_t lod, RsAllocationCubemapFace face,
                         uint32_t w, uint32_t h,
                         const void *data, uint32_t sizeBytes);
void rsdAllocationData3D(const android::renderscript::Context *rsc,
                         const android::renderscript::Allocation *alloc,
                         uint32_t xoff, uint32_t yoff, uint32_t zoff,
                         uint32_t lod, RsAllocationCubemapFace face,
                         uint32_t w, uint32_t h, uint32_t d, const void *data, uint32_t sizeBytes);

void rsdAllocationData1D_alloc(const android::renderscript::Context *rsc,
                               const android::renderscript::Allocation *dstAlloc,
                               uint32_t dstXoff, uint32_t dstLod, uint32_t count,
                               const android::renderscript::Allocation *srcAlloc,
                               uint32_t srcXoff, uint32_t srcLod);
void rsdAllocationData2D_alloc(const android::renderscript::Context *rsc,
                               const android::renderscript::Allocation *dstAlloc,
                               uint32_t dstXoff, uint32_t dstYoff, uint32_t dstLod,
                               RsAllocationCubemapFace dstFace, uint32_t w, uint32_t h,
                               const android::renderscript::Allocation *srcAlloc,
                               uint32_t srcXoff, uint32_t srcYoff, uint32_t srcLod,
                               RsAllocationCubemapFace srcFace);
void rsdAllocationData3D_alloc(const android::renderscript::Context *rsc,
                               const android::renderscript::Allocation *dstAlloc,
                               uint32_t dstXoff, uint32_t dstYoff, uint32_t dstZoff,
                               uint32_t dstLod, RsAllocationCubemapFace dstFace,
                               uint32_t w, uint32_t h, uint32_t d,
                               const android::renderscript::Allocation *srcAlloc,
                               uint32_t srcXoff, uint32_t srcYoff, uint32_t srcZoff,
                               uint32_t srcLod, RsAllocationCubemapFace srcFace);

void rsdAllocationElementData1D(const android::renderscript::Context *rsc,
                                const android::renderscript::Allocation *alloc,
                                uint32_t x,
                                const void *data, uint32_t elementOff, uint32_t sizeBytes);
void rsdAllocationElementData2D(const android::renderscript::Context *rsc,
                                const android::renderscript::Allocation *alloc,
                                uint32_t x, uint32_t y,
                                const void *data, uint32_t elementOff, uint32_t sizeBytes);




#endif
