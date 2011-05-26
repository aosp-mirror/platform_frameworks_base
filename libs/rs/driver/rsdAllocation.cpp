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


static void Update2DTexture(const Allocation *alloc, const void *ptr, uint32_t xoff, uint32_t yoff,
                     uint32_t lod, RsAllocationCubemapFace face,
                     uint32_t w, uint32_t h) {
    DrvAllocation *drv = (DrvAllocation *)alloc->mHal.drv;

    const GLenum type = alloc->mHal.state.type->getElement()->getComponent().getGLType();
    const GLenum format = alloc->mHal.state.type->getElement()->getComponent().getGLFormat();
    rsAssert(drv->textureID);
    glBindTexture(drv->glTarget, drv->textureID);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    GLenum t = GL_TEXTURE_2D;
    if (alloc->mHal.state.hasFaces) {
        t = gFaceOrder[face];
    }
    glTexSubImage2D(t, lod, xoff, yoff, w, h, format, type, ptr);
}


static void Upload2DTexture(const Context *rsc, const Allocation *alloc, bool isFirstUpload) {
    DrvAllocation *drv = (DrvAllocation *)alloc->mHal.drv;

    GLenum type = alloc->mHal.state.type->getElement()->getComponent().getGLType();
    GLenum format = alloc->mHal.state.type->getElement()->getComponent().getGLFormat();

    glBindTexture(drv->glTarget, drv->textureID);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

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
                glTexImage2D(t, lod, format,
                             alloc->mHal.state.type->getLODDimX(lod),
                             alloc->mHal.state.type->getLODDimY(lod),
                             0, format, type, p);
            } else {
                glTexSubImage2D(t, lod, 0, 0,
                                alloc->mHal.state.type->getLODDimX(lod),
                                alloc->mHal.state.type->getLODDimY(lod),
                                format, type, p);
            }
        }
    }

    if (alloc->mHal.state.mipmapControl == RS_ALLOCATION_MIPMAP_ON_SYNC_TO_TEXTURE) {
        glGenerateMipmap(drv->glTarget);
    }
    rsdGLCheckError(rsc, "Upload2DTexture");
}

static void UploadToTexture(const Context *rsc, const Allocation *alloc) {
    DrvAllocation *drv = (DrvAllocation *)alloc->mHal.drv;

    GLenum type = alloc->mHal.state.type->getElement()->getComponent().getGLType();
    GLenum format = alloc->mHal.state.type->getElement()->getComponent().getGLFormat();

    if (!type || !format) {
        return;
    }

    if (!alloc->getPtr()) {
        return;
    }

    bool isFirstUpload = false;

    if (!drv->textureID) {
        glGenTextures(1, &drv->textureID);
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

    GLenum format = alloc->mHal.state.type->getElement()->getComponent().getGLFormat();
    if (!format) {
        return;
    }

    if (!drv->renderTargetID) {
        glGenRenderbuffers(1, &drv->renderTargetID);

        if (!drv->renderTargetID) {
            // This should generally not happen
            LOGE("allocateRenderTarget failed to gen mRenderTargetID");
            rsc->dumpDebug();
            return;
        }
        glBindRenderbuffer(GL_RENDERBUFFER, drv->renderTargetID);
        glRenderbufferStorage(GL_RENDERBUFFER, format,
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
        glGenBuffers(1, &drv->bufferID);
    }
    if (!drv->bufferID) {
        LOGE("Upload to buffer object failed");
        drv->uploadDeferred = true;
        return;
    }
    glBindBuffer(drv->glTarget, drv->bufferID);
    glBufferData(drv->glTarget, alloc->mHal.state.type->getSizeBytes(),
                 drv->mallocPtr, GL_DYNAMIC_DRAW);
    glBindBuffer(drv->glTarget, 0);
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

    alloc->mHal.drvState.mallocPtr = ptr;
    drv->mallocPtr = (uint8_t *)ptr;
    alloc->mHal.drv = drv;
    if (forceZero) {
        memset(ptr, 0, alloc->mHal.state.type->getSizeBytes());
    }

    if (alloc->mHal.state.usageFlags & ~RS_ALLOCATION_USAGE_SCRIPT) {
        drv->uploadDeferred = true;
    }
    return true;
}

void rsdAllocationDestroy(const Context *rsc, Allocation *alloc) {
    DrvAllocation *drv = (DrvAllocation *)alloc->mHal.drv;

    if (drv->bufferID) {
        // Causes a SW crash....
        //LOGV(" mBufferID %i", mBufferID);
        //glDeleteBuffers(1, &mBufferID);
        //mBufferID = 0;
    }
    if (drv->textureID) {
        glDeleteTextures(1, &drv->textureID);
        drv->textureID = 0;
    }
    if (drv->renderTargetID) {
        glDeleteRenderbuffers(1, &drv->renderTargetID);
        drv->renderTargetID = 0;
    }

    if (drv->mallocPtr) {
        free(drv->mallocPtr);
        drv->mallocPtr = NULL;
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



void rsdAllocationSyncAll(const Context *rsc, const Allocation *alloc,
                         RsAllocationUsageType src) {
    DrvAllocation *drv = (DrvAllocation *)alloc->mHal.drv;

    if (!drv->uploadDeferred) {
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
        Update2DTexture(alloc, data, xoff, yoff, lod, face, w, h);
    }
}

void rsdAllocationData3D(const Context *rsc, const Allocation *alloc,
                         uint32_t xoff, uint32_t yoff, uint32_t zoff,
                         uint32_t lod, RsAllocationCubemapFace face,
                         uint32_t w, uint32_t h, uint32_t d, const void *data, uint32_t sizeBytes) {

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


