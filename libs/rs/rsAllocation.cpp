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
#ifndef ANDROID_RS_SERIALIZE
#include <GLES/gl.h>
#include <GLES2/gl2.h>
#include <GLES/glext.h>
#endif //ANDROID_RS_SERIALIZE

using namespace android;
using namespace android::renderscript;

Allocation::Allocation(Context *rsc, const Type *type, uint32_t usages,
                       RsAllocationMipmapControl mc)
    : ObjectBase(rsc) {
    init(rsc, type);

    mHal.state.usageFlags = usages;
    mHal.state.mipmapControl = mc;

    allocScriptMemory();
    if (mHal.state.type->getElement()->getHasReferences()) {
        memset(mHal.state.mallocPtr, 0, mHal.state.type->getSizeBytes());
    }
    if (!mHal.state.mallocPtr) {
        LOGE("Allocation::Allocation, alloc failure");
    }
}


void Allocation::init(Context *rsc, const Type *type) {
    memset(&mHal, 0, sizeof(mHal));
    mHal.state.mipmapControl = RS_ALLOCATION_MIPMAP_NONE;

    mCpuWrite = false;
    mCpuRead = false;
    mGpuWrite = false;
    mGpuRead = false;

    mReadWriteRatio = 0;
    mUpdateSize = 0;

    mTextureID = 0;
    mBufferID = 0;
    mRenderTargetID = 0;
    mUploadDeferred = false;

    mUserBitmapCallback = NULL;
    mUserBitmapCallbackData = NULL;

    mHal.state.type.set(type);
    updateCache();
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
    if (mUserBitmapCallback != NULL) {
        mUserBitmapCallback(mUserBitmapCallbackData);
        mHal.state.mallocPtr = NULL;
    }
    freeScriptMemory();
#ifndef ANDROID_RS_SERIALIZE
    if (mBufferID) {
        // Causes a SW crash....
        //LOGV(" mBufferID %i", mBufferID);
        //glDeleteBuffers(1, &mBufferID);
        //mBufferID = 0;
    }
    if (mTextureID) {
        glDeleteTextures(1, &mTextureID);
        mTextureID = 0;
    }
    if (mRenderTargetID) {
        glDeleteRenderbuffers(1, &mRenderTargetID);
        mRenderTargetID = 0;
    }
#endif //ANDROID_RS_SERIALIZE
}

void Allocation::setCpuWritable(bool) {
}

void Allocation::setGpuWritable(bool) {
}

void Allocation::setCpuReadable(bool) {
}

void Allocation::setGpuReadable(bool) {
}

bool Allocation::fixAllocation() {
    return false;
}

void Allocation::deferredUploadToTexture(const Context *rsc) {
    mHal.state.usageFlags |= RS_ALLOCATION_USAGE_GRAPHICS_TEXTURE;
    mUploadDeferred = true;
}

void Allocation::deferredAllocateRenderTarget(const Context *rsc) {
    mHal.state.usageFlags |= RS_ALLOCATION_USAGE_GRAPHICS_RENDER_TARGET;
    mUploadDeferred = true;
}

uint32_t Allocation::getGLTarget() const {
#ifndef ANDROID_RS_SERIALIZE
    if (getIsTexture()) {
        if (mHal.state.type->getDimFaces()) {
            return GL_TEXTURE_CUBE_MAP;
        } else {
            return GL_TEXTURE_2D;
        }
    }
    if (getIsBufferObject()) {
        return GL_ARRAY_BUFFER;
    }
#endif //ANDROID_RS_SERIALIZE
    return 0;
}

void Allocation::allocScriptMemory() {
    rsAssert(!mHal.state.mallocPtr);
    mHal.state.mallocPtr = malloc(mHal.state.type->getSizeBytes());
}

void Allocation::freeScriptMemory() {
    if (mHal.state.mallocPtr) {
        free(mHal.state.mallocPtr);
        mHal.state.mallocPtr = NULL;
    }
}


void Allocation::syncAll(Context *rsc, RsAllocationUsageType src) {
    rsAssert(src == RS_ALLOCATION_USAGE_SCRIPT);

    if (getIsTexture()) {
        uploadToTexture(rsc);
    }
    if (getIsBufferObject()) {
        uploadToBufferObject(rsc);
    }
    if (getIsRenderTarget() && !getIsTexture()) {
        allocateRenderTarget(rsc);
    }

    mUploadDeferred = false;
}

void Allocation::uploadToTexture(const Context *rsc) {
#ifndef ANDROID_RS_SERIALIZE
    mHal.state.usageFlags |= RS_ALLOCATION_USAGE_GRAPHICS_TEXTURE;
    GLenum type = mHal.state.type->getElement()->getComponent().getGLType();
    GLenum format = mHal.state.type->getElement()->getComponent().getGLFormat();

    if (!type || !format) {
        return;
    }

    if (!mHal.state.mallocPtr) {
        return;
    }

    bool isFirstUpload = false;

    if (!mTextureID) {
        glGenTextures(1, &mTextureID);

        if (!mTextureID) {
            // This should not happen, however, its likely the cause of the
            // white sqare bug.
            // Force a crash to 1: restart the app, 2: make sure we get a bugreport.
            LOGE("Upload to texture failed to gen mTextureID");
            rsc->dumpDebug();
            mUploadDeferred = true;
            return;
        }
        isFirstUpload = true;
    }

    upload2DTexture(isFirstUpload);

    if (!(mHal.state.usageFlags & RS_ALLOCATION_USAGE_SCRIPT)) {
        freeScriptMemory();
    }

    rsc->checkError("Allocation::uploadToTexture");
#endif //ANDROID_RS_SERIALIZE
}

void Allocation::allocateRenderTarget(const Context *rsc) {
#ifndef ANDROID_RS_SERIALIZE
    mHal.state.usageFlags |= RS_ALLOCATION_USAGE_GRAPHICS_RENDER_TARGET;

    GLenum format = mHal.state.type->getElement()->getComponent().getGLFormat();
    if (!format) {
        return;
    }

    if (!mRenderTargetID) {
        glGenRenderbuffers(1, &mRenderTargetID);

        if (!mRenderTargetID) {
            // This should generally not happen
            LOGE("allocateRenderTarget failed to gen mRenderTargetID");
            rsc->dumpDebug();
            return;
        }
        glBindRenderbuffer(GL_RENDERBUFFER, mRenderTargetID);
        glRenderbufferStorage(GL_RENDERBUFFER, format,
                              mHal.state.type->getDimX(),
                              mHal.state.type->getDimY());
    }
#endif //ANDROID_RS_SERIALIZE
}

#ifndef ANDROID_RS_SERIALIZE
const static GLenum gFaceOrder[] = {
    GL_TEXTURE_CUBE_MAP_POSITIVE_X,
    GL_TEXTURE_CUBE_MAP_NEGATIVE_X,
    GL_TEXTURE_CUBE_MAP_POSITIVE_Y,
    GL_TEXTURE_CUBE_MAP_NEGATIVE_Y,
    GL_TEXTURE_CUBE_MAP_POSITIVE_Z,
    GL_TEXTURE_CUBE_MAP_NEGATIVE_Z
};
#endif //ANDROID_RS_SERIALIZE

void Allocation::update2DTexture(const void *ptr, uint32_t xoff, uint32_t yoff,
                                 uint32_t lod, RsAllocationCubemapFace face,
                                 uint32_t w, uint32_t h) {
#ifndef ANDROID_RS_SERIALIZE
    GLenum type = mHal.state.type->getElement()->getComponent().getGLType();
    GLenum format = mHal.state.type->getElement()->getComponent().getGLFormat();
    GLenum target = (GLenum)getGLTarget();
    rsAssert(mTextureID);
    glBindTexture(target, mTextureID);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    GLenum t = GL_TEXTURE_2D;
    if (mHal.state.hasFaces) {
        t = gFaceOrder[face];
    }
    glTexSubImage2D(t, lod, xoff, yoff, w, h, format, type, ptr);
#endif //ANDROID_RS_SERIALIZE
}

void Allocation::upload2DTexture(bool isFirstUpload) {
#ifndef ANDROID_RS_SERIALIZE
    GLenum type = mHal.state.type->getElement()->getComponent().getGLType();
    GLenum format = mHal.state.type->getElement()->getComponent().getGLFormat();

    GLenum target = (GLenum)getGLTarget();
    glBindTexture(target, mTextureID);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

    uint32_t faceCount = 1;
    if (mHal.state.hasFaces) {
        faceCount = 6;
    }

    for (uint32_t face = 0; face < faceCount; face ++) {
        for (uint32_t lod = 0; lod < mHal.state.type->getLODCount(); lod++) {
            const uint8_t *p = (const uint8_t *)mHal.state.mallocPtr;
            p += mHal.state.type->getLODFaceOffset(lod, (RsAllocationCubemapFace)face, 0, 0);

            GLenum t = GL_TEXTURE_2D;
            if (mHal.state.hasFaces) {
                t = gFaceOrder[face];
            }

            if (isFirstUpload) {
                glTexImage2D(t, lod, format,
                             mHal.state.type->getLODDimX(lod), mHal.state.type->getLODDimY(lod),
                             0, format, type, p);
            } else {
                glTexSubImage2D(t, lod, 0, 0,
                                mHal.state.type->getLODDimX(lod), mHal.state.type->getLODDimY(lod),
                                format, type, p);
            }
        }
    }

    if (mHal.state.mipmapControl == RS_ALLOCATION_MIPMAP_ON_SYNC_TO_TEXTURE) {
        glGenerateMipmap(target);
    }
#endif //ANDROID_RS_SERIALIZE
}

void Allocation::deferredUploadToBufferObject(const Context *rsc) {
    mHal.state.usageFlags |= RS_ALLOCATION_USAGE_GRAPHICS_VERTEX;
    mUploadDeferred = true;
}

void Allocation::uploadToBufferObject(const Context *rsc) {
#ifndef ANDROID_RS_SERIALIZE
    rsAssert(!mHal.state.type->getDimY());
    rsAssert(!mHal.state.type->getDimZ());

    mHal.state.usageFlags |= RS_ALLOCATION_USAGE_GRAPHICS_VERTEX;

    if (!mBufferID) {
        glGenBuffers(1, &mBufferID);
    }
    if (!mBufferID) {
        LOGE("Upload to buffer object failed");
        mUploadDeferred = true;
        return;
    }
    GLenum target = (GLenum)getGLTarget();
    glBindBuffer(target, mBufferID);
    glBufferData(target, mHal.state.type->getSizeBytes(), getPtr(), GL_DYNAMIC_DRAW);
    glBindBuffer(target, 0);
    rsc->checkError("Allocation::uploadToBufferObject");
#endif //ANDROID_RS_SERIALIZE
}

void Allocation::uploadCheck(Context *rsc) {
    if (mUploadDeferred) {
        syncAll(rsc, RS_ALLOCATION_USAGE_SCRIPT);
    }
}

void Allocation::read(void *data) {
    memcpy(data, mHal.state.mallocPtr, mHal.state.type->getSizeBytes());
}

void Allocation::data(Context *rsc, uint32_t xoff, uint32_t lod,
                         uint32_t count, const void *data, uint32_t sizeBytes) {
    uint32_t eSize = mHal.state.type->getElementSizeBytes();
    uint8_t * ptr = static_cast<uint8_t *>(mHal.state.mallocPtr);
    ptr += eSize * xoff;
    uint32_t size = count * eSize;

    if (size != sizeBytes) {
        LOGE("Allocation::subData called with mismatched size expected %i, got %i", size, sizeBytes);
        mHal.state.type->dumpLOGV("type info");
        return;
    }

    if (mHal.state.hasReferences) {
        incRefs(data, count);
        decRefs(ptr, count);
    }

    memcpy(ptr, data, size);
    sendDirty();
    mUploadDeferred = true;
}

void Allocation::data(Context *rsc, uint32_t xoff, uint32_t yoff, uint32_t lod, RsAllocationCubemapFace face,
             uint32_t w, uint32_t h, const void *data, uint32_t sizeBytes) {
    uint32_t eSize = mHal.state.elementSizeBytes;
    uint32_t lineSize = eSize * w;
    uint32_t destW = mHal.state.dimensionX;

    //LOGE("data2d %p,  %i %i %i %i %i %i %p %i", this, xoff, yoff, lod, face, w, h, data, sizeBytes);

    if ((lineSize * h) != sizeBytes) {
        LOGE("Allocation size mismatch, expected %i, got %i", (lineSize * h), sizeBytes);
        rsAssert(!"Allocation::subData called with mismatched size");
        return;
    }

    if (mHal.state.mallocPtr) {
        const uint8_t *src = static_cast<const uint8_t *>(data);
        uint8_t *dst = static_cast<uint8_t *>(mHal.state.mallocPtr);
        dst += mHal.state.type->getLODFaceOffset(lod, face, xoff, yoff);

        //LOGE("            %p  %p  %i  ", dst, src, eSize);
        for (uint32_t line=yoff; line < (yoff+h); line++) {
            if (mHal.state.hasReferences) {
                incRefs(src, w);
                decRefs(dst, w);
            }
            memcpy(dst, src, lineSize);
            src += lineSize;
            dst += destW * eSize;
        }
        sendDirty();
        mUploadDeferred = true;
    } else {
        update2DTexture(data, xoff, yoff, lod, face, w, h);
    }
}

void Allocation::data(Context *rsc, uint32_t xoff, uint32_t yoff, uint32_t zoff,
                      uint32_t lod, RsAllocationCubemapFace face,
                      uint32_t w, uint32_t h, uint32_t d, const void *data, uint32_t sizeBytes) {
}

void Allocation::elementData(Context *rsc, uint32_t x, const void *data,
                                uint32_t cIdx, uint32_t sizeBytes) {
    uint32_t eSize = mHal.state.elementSizeBytes;
    uint8_t * ptr = static_cast<uint8_t *>(mHal.state.mallocPtr);
    ptr += eSize * x;

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
    ptr += mHal.state.type->getElement()->getFieldOffsetBytes(cIdx);

    if (sizeBytes != e->getSizeBytes()) {
        LOGE("Error Allocation::subElementData data size %i does not match field size %zu.", sizeBytes, e->getSizeBytes());
        rsc->setError(RS_ERROR_BAD_VALUE, "subElementData bad size.");
        return;
    }

    if (e->getHasReferences()) {
        e->incRefs(data);
        e->decRefs(ptr);
    }

    memcpy(ptr, data, sizeBytes);
    sendDirty();
    mUploadDeferred = true;
}

void Allocation::elementData(Context *rsc, uint32_t x, uint32_t y,
                                const void *data, uint32_t cIdx, uint32_t sizeBytes) {
    uint32_t eSize = mHal.state.elementSizeBytes;
    uint8_t * ptr = static_cast<uint8_t *>(mHal.state.mallocPtr);
    ptr += eSize * (x + y * mHal.state.dimensionX);

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
    ptr += mHal.state.type->getElement()->getFieldOffsetBytes(cIdx);

    if (sizeBytes != e->getSizeBytes()) {
        LOGE("Error Allocation::subElementData data size %i does not match field size %zu.", sizeBytes, e->getSizeBytes());
        rsc->setError(RS_ERROR_BAD_VALUE, "subElementData bad size.");
        return;
    }

    if (e->getHasReferences()) {
        e->incRefs(data);
        e->decRefs(ptr);
    }

    memcpy(ptr, data, sizeBytes);
    sendDirty();
    mUploadDeferred = true;
}

void Allocation::addProgramToDirty(const Program *p) {
#ifndef ANDROID_RS_SERIALIZE
    mToDirtyList.push(p);
#endif //ANDROID_RS_SERIALIZE
}

void Allocation::removeProgramToDirty(const Program *p) {
#ifndef ANDROID_RS_SERIALIZE
    for (size_t ct=0; ct < mToDirtyList.size(); ct++) {
        if (mToDirtyList[ct] == p) {
            mToDirtyList.removeAt(ct);
            return;
        }
    }
    rsAssert(0);
#endif //ANDROID_RS_SERIALIZE
}

void Allocation::dumpLOGV(const char *prefix) const {
    ObjectBase::dumpLOGV(prefix);

    String8 s(prefix);
    s.append(" type ");
    if (mHal.state.type.get()) {
        mHal.state.type->dumpLOGV(s.string());
    }

    LOGV("%s allocation ptr=%p mCpuWrite=%i, mCpuRead=%i, mGpuWrite=%i, mGpuRead=%i",
          prefix, mHal.state.mallocPtr, mCpuWrite, mCpuRead, mGpuWrite, mGpuRead);

    LOGV("%s allocation mUsageFlags=0x04%x, mMipmapControl=0x%04x, mTextureID=%i, mBufferID=%i",
          prefix, mHal.state.usageFlags, mHal.state.mipmapControl, mTextureID, mBufferID);
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
    stream->addByteArray(mHal.state.mallocPtr, dataSize);
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

    Allocation *alloc = new Allocation(rsc, type, RS_ALLOCATION_USAGE_SCRIPT);
    alloc->setName(name.string(), name.size());

    uint32_t count = dataSize / type->getElementSizeBytes();

    // Read in all of our allocation data
    alloc->data(rsc, 0, 0, count, stream->getPtr() + stream->getPos(), dataSize);
    stream->reset(stream->getPos() + dataSize);

    return alloc;
}

void Allocation::sendDirty() const {
#ifndef ANDROID_RS_SERIALIZE
    for (size_t ct=0; ct < mToDirtyList.size(); ct++) {
        mToDirtyList[ct]->forceDirty();
    }
#endif //ANDROID_RS_SERIALIZE
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

void Allocation::copyRange1D(Context *rsc, const Allocation *src, int32_t srcOff, int32_t destOff, int32_t len) {
}

void Allocation::resize1D(Context *rsc, uint32_t dimX) {
    Type *t = mHal.state.type->cloneAndResize1D(rsc, dimX);

    uint32_t oldDimX = mHal.state.dimensionX;
    if (dimX == oldDimX) {
        return;
    }

    if (dimX < oldDimX) {
        decRefs(mHal.state.mallocPtr, oldDimX - dimX, dimX);
    }
    mHal.state.mallocPtr = realloc(mHal.state.mallocPtr, t->getSizeBytes());

    if (dimX > oldDimX) {
        const Element *e = mHal.state.type->getElement();
        uint32_t stride = e->getSizeBytes();
        memset(((uint8_t *)mHal.state.mallocPtr) + stride * oldDimX, 0, stride * (dimX - oldDimX));
    }

    mHal.state.type.set(t);
    updateCache();
}

void Allocation::resize2D(Context *rsc, uint32_t dimX, uint32_t dimY) {
    LOGE("not implemented");
}

/////////////////
//
#ifndef ANDROID_RS_SERIALIZE

static void rsaAllocationGenerateScriptMips(RsContext con, RsAllocation va);

namespace android {
namespace renderscript {

void rsi_AllocationUploadToTexture(Context *rsc, RsAllocation va, bool genmip, uint32_t baseMipLevel) {
    Allocation *alloc = static_cast<Allocation *>(va);
    alloc->deferredUploadToTexture(rsc);
}

void rsi_AllocationUploadToBufferObject(Context *rsc, RsAllocation va) {
    Allocation *alloc = static_cast<Allocation *>(va);
    alloc->deferredUploadToBufferObject(rsc);
}

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
    a->syncAll(rsc, src);
    a->sendDirty();
}

void rsi_AllocationGenerateMipmaps(Context *rsc, RsAllocation va) {
    Allocation *texAlloc = static_cast<Allocation *>(va);
    rsaAllocationGenerateScriptMips(rsc, texAlloc);
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
                          uint32_t count, const void *data, uint32_t sizeBytes) {
    Allocation *a = static_cast<Allocation *>(va);
    a->data(rsc, xoff, lod, count, data, sizeBytes);
}

void rsi_Allocation2DElementData(Context *rsc, RsAllocation va, uint32_t x, uint32_t y, uint32_t lod, RsAllocationCubemapFace face,
                                 const void *data, uint32_t eoff, uint32_t sizeBytes) {
    Allocation *a = static_cast<Allocation *>(va);
    a->elementData(rsc, x, y, data, eoff, sizeBytes);
}

void rsi_Allocation1DElementData(Context *rsc, RsAllocation va, uint32_t x, uint32_t lod,
                                 const void *data, uint32_t eoff, uint32_t sizeBytes) {
    Allocation *a = static_cast<Allocation *>(va);
    a->elementData(rsc, x, data, eoff, sizeBytes);
}

void rsi_Allocation2DData(Context *rsc, RsAllocation va, uint32_t xoff, uint32_t yoff, uint32_t lod, RsAllocationCubemapFace face,
                          uint32_t w, uint32_t h, const void *data, uint32_t sizeBytes) {
    Allocation *a = static_cast<Allocation *>(va);
    a->data(rsc, xoff, yoff, lod, face, w, h, data, sizeBytes);
}

void rsi_AllocationRead(Context *rsc, RsAllocation va, void *data) {
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

}
}

static void rsaAllocationGenerateScriptMips(RsContext con, RsAllocation va) {
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

const void * rsaAllocationGetType(RsContext con, RsAllocation va) {
    Allocation *a = static_cast<Allocation *>(va);
    a->getType()->incUserRef();

    return a->getType();
}

RsAllocation rsaAllocationCreateTyped(RsContext con, RsType vtype,
                                      RsAllocationMipmapControl mips,
                                      uint32_t usages) {
    Context *rsc = static_cast<Context *>(con);
    Allocation * alloc = new Allocation(rsc, static_cast<Type *>(vtype), usages, mips);
    alloc->incUserRef();
    return alloc;
}

RsAllocation rsaAllocationCreateFromBitmap(RsContext con, RsType vtype,
                                           RsAllocationMipmapControl mips,
                                           const void *data, uint32_t usages) {
    Context *rsc = static_cast<Context *>(con);
    Type *t = static_cast<Type *>(vtype);

    RsAllocation vTexAlloc = rsaAllocationCreateTyped(rsc, vtype, mips, usages);
    Allocation *texAlloc = static_cast<Allocation *>(vTexAlloc);
    if (texAlloc == NULL) {
        LOGE("Memory allocation failure");
        return NULL;
    }

    memcpy(texAlloc->getPtr(), data, t->getDimX() * t->getDimY() * t->getElementSizeBytes());
    if (mips == RS_ALLOCATION_MIPMAP_FULL) {
        rsaAllocationGenerateScriptMips(rsc, texAlloc);
    }

    texAlloc->deferredUploadToTexture(rsc);
    return texAlloc;
}

RsAllocation rsaAllocationCubeCreateFromBitmap(RsContext con, RsType vtype,
                                               RsAllocationMipmapControl mips,
                                               const void *data, uint32_t usages) {
    Context *rsc = static_cast<Context *>(con);
    Type *t = static_cast<Type *>(vtype);

    // Cubemap allocation's faces should be Width by Width each.
    // Source data should have 6 * Width by Width pixels
    // Error checking is done in the java layer
    RsAllocation vTexAlloc = rsaAllocationCreateTyped(rsc, t, mips, usages);
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
        rsaAllocationGenerateScriptMips(rsc, texAlloc);
    }

    texAlloc->deferredUploadToTexture(rsc);
    return texAlloc;
}

#endif //ANDROID_RS_SERIALIZE
