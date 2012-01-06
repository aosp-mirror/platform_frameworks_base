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


#include "rsdCore.h"
#include "rsdBcc.h"
#include "rsdRuntime.h"
#include "rsdAllocation.h"
#include "rsdFrameBufferObj.h"

#include "rsAllocation.h"

#include <GLES/gl.h>
#include <GLES2/gl2.h>
#include <GLES/glext.h>

using namespace android;
using namespace android::renderscript;



const static GLenum gFaceOrder[] = {
    GL_TEXTURE_CUBE_MAP_POSITIVE_X,
    GL_TEXTURE_CUBE_MAP_NEGATIVE_X,
    GL_TEXTURE_CUBE_MAP_POSITIVE_Y,
    GL_TEXTURE_CUBE_MAP_NEGATIVE_Y,
    GL_TEXTURE_CUBE_MAP_POSITIVE_Z,
    GL_TEXTURE_CUBE_MAP_NEGATIVE_Z
};


GLenum rsdTypeToGLType(RsDataType t) {
    switch (t) {
    case RS_TYPE_UNSIGNED_5_6_5:    return GL_UNSIGNED_SHORT_5_6_5;
    case RS_TYPE_UNSIGNED_5_5_5_1:  return GL_UNSIGNED_SHORT_5_5_5_1;
    case RS_TYPE_UNSIGNED_4_4_4_4:  return GL_UNSIGNED_SHORT_4_4_4_4;

    //case RS_TYPE_FLOAT_16:      return GL_HALF_FLOAT;
    case RS_TYPE_FLOAT_32:      return GL_FLOAT;
    case RS_TYPE_UNSIGNED_8:    return GL_UNSIGNED_BYTE;
    case RS_TYPE_UNSIGNED_16:   return GL_UNSIGNED_SHORT;
    case RS_TYPE_SIGNED_8:      return GL_BYTE;
    case RS_TYPE_SIGNED_16:     return GL_SHORT;
    default:    break;
    }
    return 0;
}

GLenum rsdKindToGLFormat(RsDataKind k) {
    switch (k) {
    case RS_KIND_PIXEL_L: return GL_LUMINANCE;
    case RS_KIND_PIXEL_A: return GL_ALPHA;
    case RS_KIND_PIXEL_LA: return GL_LUMINANCE_ALPHA;
    case RS_KIND_PIXEL_RGB: return GL_RGB;
    case RS_KIND_PIXEL_RGBA: return GL_RGBA;
    case RS_KIND_PIXEL_DEPTH: return GL_DEPTH_COMPONENT16;
    default: break;
    }
    return 0;
}


static void Update2DTexture(const Context *rsc, const Allocation *alloc, const void *ptr,
                            uint32_t xoff, uint32_t yoff, uint32_t lod,
                            RsAllocationCubemapFace face, uint32_t w, uint32_t h) {
    DrvAllocation *drv = (DrvAllocation *)alloc->mHal.drv;

    rsAssert(drv->textureID);
    RSD_CALL_GL(glBindTexture, drv->glTarget, drv->textureID);
    RSD_CALL_GL(glPixelStorei, GL_UNPACK_ALIGNMENT, 1);
    GLenum t = GL_TEXTURE_2D;
    if (alloc->mHal.state.hasFaces) {
        t = gFaceOrder[face];
    }
    RSD_CALL_GL(glTexSubImage2D, t, lod, xoff, yoff, w, h, drv->glFormat, drv->glType, ptr);
}


static void Upload2DTexture(const Context *rsc, const Allocation *alloc, bool isFirstUpload) {
    DrvAllocation *drv = (DrvAllocation *)alloc->mHal.drv;

    RSD_CALL_GL(glBindTexture, drv->glTarget, drv->textureID);
    RSD_CALL_GL(glPixelStorei, GL_UNPACK_ALIGNMENT, 1);

    uint32_t faceCount = 1;
    if (alloc->mHal.state.hasFaces) {
        faceCount = 6;
    }

    rsdGLCheckError(rsc, "Upload2DTexture 1 ");
    for (uint32_t face = 0; face < faceCount; face ++) {
        for (uint32_t lod = 0; lod < alloc->mHal.state.type->getLODCount(); lod++) {
            const uint8_t *p = (const uint8_t *)drv->mallocPtr;
            p += alloc->mHal.state.type->getLODFaceOffset(lod, (RsAllocationCubemapFace)face, 0, 0);

            GLenum t = GL_TEXTURE_2D;
            if (alloc->mHal.state.hasFaces) {
                t = gFaceOrder[face];
            }

            if (isFirstUpload) {
                RSD_CALL_GL(glTexImage2D, t, lod, drv->glFormat,
                             alloc->mHal.state.type->getLODDimX(lod),
                             alloc->mHal.state.type->getLODDimY(lod),
                             0, drv->glFormat, drv->glType, p);
            } else {
                RSD_CALL_GL(glTexSubImage2D, t, lod, 0, 0,
                                alloc->mHal.state.type->getLODDimX(lod),
                                alloc->mHal.state.type->getLODDimY(lod),
                                drv->glFormat, drv->glType, p);
            }
        }
    }

    if (alloc->mHal.state.mipmapControl == RS_ALLOCATION_MIPMAP_ON_SYNC_TO_TEXTURE) {
        RSD_CALL_GL(glGenerateMipmap, drv->glTarget);
    }
    rsdGLCheckError(rsc, "Upload2DTexture");
}

static void UploadToTexture(const Context *rsc, const Allocation *alloc) {
    DrvAllocation *drv = (DrvAllocation *)alloc->mHal.drv;

    if (!drv->glType || !drv->glFormat) {
        return;
    }

    if (!alloc->getPtr()) {
        return;
    }

    bool isFirstUpload = false;

    if (!drv->textureID) {
        RSD_CALL_GL(glGenTextures, 1, &drv->textureID);
        isFirstUpload = true;
    }

    Upload2DTexture(rsc, alloc, isFirstUpload);

    if (!(alloc->mHal.state.usageFlags & RS_ALLOCATION_USAGE_SCRIPT)) {
        if (drv->mallocPtr) {
            free(drv->mallocPtr);
            drv->mallocPtr = NULL;
        }
    }
    rsdGLCheckError(rsc, "UploadToTexture");
}

static void AllocateRenderTarget(const Context *rsc, const Allocation *alloc) {
    DrvAllocation *drv = (DrvAllocation *)alloc->mHal.drv;

    if (!drv->glFormat) {
        return;
    }

    if (!drv->renderTargetID) {
        RSD_CALL_GL(glGenRenderbuffers, 1, &drv->renderTargetID);

        if (!drv->renderTargetID) {
            // This should generally not happen
            ALOGE("allocateRenderTarget failed to gen mRenderTargetID");
            rsc->dumpDebug();
            return;
        }
        RSD_CALL_GL(glBindRenderbuffer, GL_RENDERBUFFER, drv->renderTargetID);
        RSD_CALL_GL(glRenderbufferStorage, GL_RENDERBUFFER, drv->glFormat,
                              alloc->mHal.state.dimensionX, alloc->mHal.state.dimensionY);
    }
    rsdGLCheckError(rsc, "AllocateRenderTarget");
}

static void UploadToBufferObject(const Context *rsc, const Allocation *alloc) {
    DrvAllocation *drv = (DrvAllocation *)alloc->mHal.drv;

    rsAssert(!alloc->mHal.state.type->getDimY());
    rsAssert(!alloc->mHal.state.type->getDimZ());

    //alloc->mHal.state.usageFlags |= RS_ALLOCATION_USAGE_GRAPHICS_VERTEX;

    if (!drv->bufferID) {
        RSD_CALL_GL(glGenBuffers, 1, &drv->bufferID);
    }
    if (!drv->bufferID) {
        ALOGE("Upload to buffer object failed");
        drv->uploadDeferred = true;
        return;
    }
    RSD_CALL_GL(glBindBuffer, drv->glTarget, drv->bufferID);
    RSD_CALL_GL(glBufferData, drv->glTarget, alloc->mHal.state.type->getSizeBytes(),
                 drv->mallocPtr, GL_DYNAMIC_DRAW);
    RSD_CALL_GL(glBindBuffer, drv->glTarget, 0);
    rsdGLCheckError(rsc, "UploadToBufferObject");
}

bool rsdAllocationInit(const Context *rsc, Allocation *alloc, bool forceZero) {
    DrvAllocation *drv = (DrvAllocation *)calloc(1, sizeof(DrvAllocation));
    if (!drv) {
        return false;
    }

    void * ptr = malloc(alloc->mHal.state.type->getSizeBytes());
    if (!ptr) {
        free(drv);
        return false;
    }

    drv->glTarget = GL_NONE;
    if (alloc->mHal.state.usageFlags & RS_ALLOCATION_USAGE_GRAPHICS_TEXTURE) {
        if (alloc->mHal.state.hasFaces) {
            drv->glTarget = GL_TEXTURE_CUBE_MAP;
        } else {
            drv->glTarget = GL_TEXTURE_2D;
        }
    } else {
        if (alloc->mHal.state.usageFlags & RS_ALLOCATION_USAGE_GRAPHICS_VERTEX) {
            drv->glTarget = GL_ARRAY_BUFFER;
        }
    }

    drv->glType = rsdTypeToGLType(alloc->mHal.state.type->getElement()->getComponent().getType());
    drv->glFormat = rsdKindToGLFormat(alloc->mHal.state.type->getElement()->getComponent().getKind());


    alloc->mHal.drvState.mallocPtr = ptr;
    drv->mallocPtr = (uint8_t *)ptr;
    alloc->mHal.drv = drv;
    if (forceZero) {
        memset(ptr, 0, alloc->mHal.state.type->getSizeBytes());
    }

    if (alloc->mHal.state.usageFlags & ~RS_ALLOCATION_USAGE_SCRIPT) {
        drv->uploadDeferred = true;
    }

    drv->readBackFBO = NULL;

    return true;
}

void rsdAllocationDestroy(const Context *rsc, Allocation *alloc) {
    DrvAllocation *drv = (DrvAllocation *)alloc->mHal.drv;

    if (drv->bufferID) {
        // Causes a SW crash....
        //ALOGV(" mBufferID %i", mBufferID);
        //glDeleteBuffers(1, &mBufferID);
        //mBufferID = 0;
    }
    if (drv->textureID) {
        RSD_CALL_GL(glDeleteTextures, 1, &drv->textureID);
        drv->textureID = 0;
    }
    if (drv->renderTargetID) {
        RSD_CALL_GL(glDeleteRenderbuffers, 1, &drv->renderTargetID);
        drv->renderTargetID = 0;
    }

    if (drv->mallocPtr) {
        free(drv->mallocPtr);
        drv->mallocPtr = NULL;
    }
    if (drv->readBackFBO != NULL) {
        delete drv->readBackFBO;
        drv->readBackFBO = NULL;
    }
    free(drv);
    alloc->mHal.drv = NULL;
}

void rsdAllocationResize(const Context *rsc, const Allocation *alloc,
                         const Type *newType, bool zeroNew) {
    DrvAllocation *drv = (DrvAllocation *)alloc->mHal.drv;

    drv->mallocPtr = (uint8_t *)realloc(drv->mallocPtr, newType->getSizeBytes());

    // fixme
    ((Allocation *)alloc)->mHal.drvState.mallocPtr = drv->mallocPtr;

    const uint32_t oldDimX = alloc->mHal.state.dimensionX;
    const uint32_t dimX = newType->getDimX();

    if (dimX > oldDimX) {
        const Element *e = alloc->mHal.state.type->getElement();
        uint32_t stride = e->getSizeBytes();
        memset(((uint8_t *)drv->mallocPtr) + stride * oldDimX, 0, stride * (dimX - oldDimX));
    }
}

static void rsdAllocationSyncFromFBO(const Context *rsc, const Allocation *alloc) {
    if (!alloc->getIsScript()) {
        return; // nothing to sync
    }

    RsdHal *dc = (RsdHal *)rsc->mHal.drv;
    RsdFrameBufferObj *lastFbo = dc->gl.currentFrameBuffer;

    DrvAllocation *drv = (DrvAllocation *)alloc->mHal.drv;
    if (!drv->textureID && !drv->renderTargetID) {
        return; // nothing was rendered here yet, so nothing to sync
    }
    if (drv->readBackFBO == NULL) {
        drv->readBackFBO = new RsdFrameBufferObj();
        drv->readBackFBO->setColorTarget(drv, 0);
        drv->readBackFBO->setDimensions(alloc->getType()->getDimX(),
                                        alloc->getType()->getDimY());
    }

    // Bind the framebuffer object so we can read back from it
    drv->readBackFBO->setActive(rsc);

    // Do the readback
    RSD_CALL_GL(glReadPixels, 0, 0, alloc->getType()->getDimX(), alloc->getType()->getDimY(),
                 drv->glFormat, drv->glType, alloc->getPtr());

    // Revert framebuffer to its original
    lastFbo->setActive(rsc);
}


void rsdAllocationSyncAll(const Context *rsc, const Allocation *alloc,
                         RsAllocationUsageType src) {
    DrvAllocation *drv = (DrvAllocation *)alloc->mHal.drv;

    if (src == RS_ALLOCATION_USAGE_GRAPHICS_RENDER_TARGET) {
        if(!alloc->getIsRenderTarget()) {
            rsc->setError(RS_ERROR_FATAL_DRIVER,
                          "Attempting to sync allocation from render target, "
                          "for non-render target allocation");
        } else if (alloc->getType()->getElement()->getKind() != RS_KIND_PIXEL_RGBA) {
            rsc->setError(RS_ERROR_FATAL_DRIVER, "Cannot only sync from RGBA"
                                                 "render target");
        } else {
            rsdAllocationSyncFromFBO(rsc, alloc);
        }
        return;
    }

    rsAssert(src == RS_ALLOCATION_USAGE_SCRIPT);

    if (alloc->mHal.state.usageFlags & RS_ALLOCATION_USAGE_GRAPHICS_TEXTURE) {
        UploadToTexture(rsc, alloc);
    } else {
        if (alloc->mHal.state.usageFlags & RS_ALLOCATION_USAGE_GRAPHICS_RENDER_TARGET) {
            AllocateRenderTarget(rsc, alloc);
        }
    }
    if (alloc->mHal.state.usageFlags & RS_ALLOCATION_USAGE_GRAPHICS_VERTEX) {
        UploadToBufferObject(rsc, alloc);
    }

    drv->uploadDeferred = false;
}

void rsdAllocationMarkDirty(const Context *rsc, const Allocation *alloc) {
    DrvAllocation *drv = (DrvAllocation *)alloc->mHal.drv;
    drv->uploadDeferred = true;
}

void rsdAllocationData1D(const Context *rsc, const Allocation *alloc,
                         uint32_t xoff, uint32_t lod, uint32_t count,
                         const void *data, uint32_t sizeBytes) {
    DrvAllocation *drv = (DrvAllocation *)alloc->mHal.drv;

    const uint32_t eSize = alloc->mHal.state.type->getElementSizeBytes();
    uint8_t * ptr = drv->mallocPtr;
    ptr += eSize * xoff;
    uint32_t size = count * eSize;

    if (alloc->mHal.state.hasReferences) {
        alloc->incRefs(data, count);
        alloc->decRefs(ptr, count);
    }

    memcpy(ptr, data, size);
    drv->uploadDeferred = true;
}

void rsdAllocationData2D(const Context *rsc, const Allocation *alloc,
                         uint32_t xoff, uint32_t yoff, uint32_t lod, RsAllocationCubemapFace face,
                         uint32_t w, uint32_t h, const void *data, uint32_t sizeBytes) {
    DrvAllocation *drv = (DrvAllocation *)alloc->mHal.drv;

    uint32_t eSize = alloc->mHal.state.elementSizeBytes;
    uint32_t lineSize = eSize * w;
    uint32_t destW = alloc->mHal.state.dimensionX;

    if (drv->mallocPtr) {
        const uint8_t *src = static_cast<const uint8_t *>(data);
        uint8_t *dst = drv->mallocPtr;
        dst += alloc->mHal.state.type->getLODFaceOffset(lod, face, xoff, yoff);

        for (uint32_t line=yoff; line < (yoff+h); line++) {
            if (alloc->mHal.state.hasReferences) {
                alloc->incRefs(src, w);
                alloc->decRefs(dst, w);
            }
            memcpy(dst, src, lineSize);
            src += lineSize;
            dst += destW * eSize;
        }
        drv->uploadDeferred = true;
    } else {
        Update2DTexture(rsc, alloc, data, xoff, yoff, lod, face, w, h);
    }
}

void rsdAllocationData3D(const Context *rsc, const Allocation *alloc,
                         uint32_t xoff, uint32_t yoff, uint32_t zoff,
                         uint32_t lod, RsAllocationCubemapFace face,
                         uint32_t w, uint32_t h, uint32_t d, const void *data, uint32_t sizeBytes) {

}

void rsdAllocationData1D_alloc(const android::renderscript::Context *rsc,
                               const android::renderscript::Allocation *dstAlloc,
                               uint32_t dstXoff, uint32_t dstLod, uint32_t count,
                               const android::renderscript::Allocation *srcAlloc,
                               uint32_t srcXoff, uint32_t srcLod) {
}

uint8_t *getOffsetPtr(const android::renderscript::Allocation *alloc,
                      uint32_t xoff, uint32_t yoff, uint32_t lod,
                      RsAllocationCubemapFace face) {
    uint8_t *ptr = static_cast<uint8_t *>(alloc->getPtr());
    ptr += alloc->getType()->getLODOffset(lod, xoff, yoff);

    if (face != 0) {
        uint32_t totalSizeBytes = alloc->getType()->getSizeBytes();
        uint32_t faceOffset = totalSizeBytes / 6;
        ptr += faceOffset * (uint32_t)face;
    }
    return ptr;
}


void rsdAllocationData2D_alloc_script(const android::renderscript::Context *rsc,
                                      const android::renderscript::Allocation *dstAlloc,
                                      uint32_t dstXoff, uint32_t dstYoff, uint32_t dstLod,
                                      RsAllocationCubemapFace dstFace, uint32_t w, uint32_t h,
                                      const android::renderscript::Allocation *srcAlloc,
                                      uint32_t srcXoff, uint32_t srcYoff, uint32_t srcLod,
                                      RsAllocationCubemapFace srcFace) {
    uint32_t elementSize = dstAlloc->getType()->getElementSizeBytes();
    for (uint32_t i = 0; i < h; i ++) {
        uint8_t *dstPtr = getOffsetPtr(dstAlloc, dstXoff, dstYoff + i, dstLod, dstFace);
        uint8_t *srcPtr = getOffsetPtr(srcAlloc, srcXoff, srcYoff + i, srcLod, srcFace);
        memcpy(dstPtr, srcPtr, w * elementSize);

        //ALOGE("COPIED dstXoff(%u), dstYoff(%u), dstLod(%u), dstFace(%u), w(%u), h(%u), srcXoff(%u), srcYoff(%u), srcLod(%u), srcFace(%u)",
        //     dstXoff, dstYoff, dstLod, dstFace, w, h, srcXoff, srcYoff, srcLod, srcFace);
    }
}

void rsdAllocationData2D_alloc(const android::renderscript::Context *rsc,
                               const android::renderscript::Allocation *dstAlloc,
                               uint32_t dstXoff, uint32_t dstYoff, uint32_t dstLod,
                               RsAllocationCubemapFace dstFace, uint32_t w, uint32_t h,
                               const android::renderscript::Allocation *srcAlloc,
                               uint32_t srcXoff, uint32_t srcYoff, uint32_t srcLod,
                               RsAllocationCubemapFace srcFace) {
    if (!dstAlloc->getIsScript() && !srcAlloc->getIsScript()) {
        rsc->setError(RS_ERROR_FATAL_DRIVER, "Non-script allocation copies not "
                                             "yet implemented.");
        return;
    }
    rsdAllocationData2D_alloc_script(rsc, dstAlloc, dstXoff, dstYoff,
                                     dstLod, dstFace, w, h, srcAlloc,
                                     srcXoff, srcYoff, srcLod, srcFace);
}

void rsdAllocationData3D_alloc(const android::renderscript::Context *rsc,
                               const android::renderscript::Allocation *dstAlloc,
                               uint32_t dstXoff, uint32_t dstYoff, uint32_t dstZoff,
                               uint32_t dstLod, RsAllocationCubemapFace dstFace,
                               uint32_t w, uint32_t h, uint32_t d,
                               const android::renderscript::Allocation *srcAlloc,
                               uint32_t srcXoff, uint32_t srcYoff, uint32_t srcZoff,
                               uint32_t srcLod, RsAllocationCubemapFace srcFace) {
}

void rsdAllocationElementData1D(const Context *rsc, const Allocation *alloc,
                                uint32_t x,
                                const void *data, uint32_t cIdx, uint32_t sizeBytes) {
    DrvAllocation *drv = (DrvAllocation *)alloc->mHal.drv;

    uint32_t eSize = alloc->mHal.state.elementSizeBytes;
    uint8_t * ptr = drv->mallocPtr;
    ptr += eSize * x;

    const Element * e = alloc->mHal.state.type->getElement()->getField(cIdx);
    ptr += alloc->mHal.state.type->getElement()->getFieldOffsetBytes(cIdx);

    if (alloc->mHal.state.hasReferences) {
        e->incRefs(data);
        e->decRefs(ptr);
    }

    memcpy(ptr, data, sizeBytes);
    drv->uploadDeferred = true;
}

void rsdAllocationElementData2D(const Context *rsc, const Allocation *alloc,
                                uint32_t x, uint32_t y,
                                const void *data, uint32_t cIdx, uint32_t sizeBytes) {
    DrvAllocation *drv = (DrvAllocation *)alloc->mHal.drv;

    uint32_t eSize = alloc->mHal.state.elementSizeBytes;
    uint8_t * ptr = drv->mallocPtr;
    ptr += eSize * (x + y * alloc->mHal.state.dimensionX);

    const Element * e = alloc->mHal.state.type->getElement()->getField(cIdx);
    ptr += alloc->mHal.state.type->getElement()->getFieldOffsetBytes(cIdx);

    if (alloc->mHal.state.hasReferences) {
        e->incRefs(data);
        e->decRefs(ptr);
    }

    memcpy(ptr, data, sizeBytes);
    drv->uploadDeferred = true;
}


