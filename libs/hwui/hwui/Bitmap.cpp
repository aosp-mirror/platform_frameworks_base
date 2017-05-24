/*
 * Copyright (C) 2015 The Android Open Source Project
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
#include "Bitmap.h"

#include "Caches.h"
#include "renderthread/EglManager.h"
#include "renderthread/RenderThread.h"
#include "renderthread/RenderProxy.h"
#include "utils/Color.h"

#include <sys/mman.h>

#include <log/log.h>
#include <cutils/ashmem.h>

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>

#include <private/gui/ComposerService.h>
#include <binder/IServiceManager.h>
#include <ui/PixelFormat.h>

#include <SkCanvas.h>

namespace android {

static bool computeAllocationSize(size_t rowBytes, int height, size_t* size) {
    int32_t rowBytes32 = SkToS32(rowBytes);
    int64_t bigSize = (int64_t) height * rowBytes32;
    if (rowBytes32 < 0 || !sk_64_isS32(bigSize)) {
        return false; // allocation will be too large
    }

    *size = sk_64_asS32(bigSize);
    return true;
}

typedef sk_sp<Bitmap> (*AllocPixeRef)(size_t allocSize, const SkImageInfo& info, size_t rowBytes,
        SkColorTable* ctable);

static sk_sp<Bitmap> allocateBitmap(SkBitmap* bitmap, SkColorTable* ctable, AllocPixeRef alloc) {
    const SkImageInfo& info = bitmap->info();
    if (info.colorType() == kUnknown_SkColorType) {
        LOG_ALWAYS_FATAL("unknown bitmap configuration");
        return nullptr;
    }

    size_t size;

    // we must respect the rowBytes value already set on the bitmap instead of
    // attempting to compute our own.
    const size_t rowBytes = bitmap->rowBytes();
    if (!computeAllocationSize(rowBytes, bitmap->height(), &size)) {
        return nullptr;
    }

    auto wrapper = alloc(size, info, rowBytes, ctable);
    if (wrapper) {
        wrapper->getSkBitmap(bitmap);
        // since we're already allocated, we lockPixels right away
        // HeapAllocator behaves this way too
        bitmap->lockPixels();
    }
    return wrapper;
}

sk_sp<Bitmap> Bitmap::allocateAshmemBitmap(SkBitmap* bitmap, SkColorTable* ctable) {
   return allocateBitmap(bitmap, ctable, &Bitmap::allocateAshmemBitmap);
}

static sk_sp<Bitmap> allocateHeapBitmap(size_t size, const SkImageInfo& info, size_t rowBytes,
        SkColorTable* ctable) {
    void* addr = calloc(size, 1);
    if (!addr) {
        return nullptr;
    }
    return sk_sp<Bitmap>(new Bitmap(addr, size, info, rowBytes, ctable));
}

#define FENCE_TIMEOUT 2000000000

// TODO: handle SRGB sanely
static PixelFormat internalFormatToPixelFormat(GLint internalFormat) {
    switch (internalFormat) {
    case GL_LUMINANCE:
        return PIXEL_FORMAT_RGBA_8888;
    case GL_SRGB8_ALPHA8:
        return PIXEL_FORMAT_RGBA_8888;
    case GL_RGBA:
        return PIXEL_FORMAT_RGBA_8888;
    case GL_RGB:
        return PIXEL_FORMAT_RGB_565;
    case GL_RGBA16F:
        return PIXEL_FORMAT_RGBA_FP16;
    default:
        LOG_ALWAYS_FATAL("Unsupported bitmap colorType: %d", internalFormat);
        return PIXEL_FORMAT_UNKNOWN;
    }
}

class AutoEglFence {
public:
    AutoEglFence(EGLDisplay display)
            : mDisplay(display) {
        fence = eglCreateSyncKHR(mDisplay, EGL_SYNC_FENCE_KHR, NULL);
    }

    ~AutoEglFence() {
        if (fence != EGL_NO_SYNC_KHR) {
            eglDestroySyncKHR(mDisplay, fence);
        }
    }

    EGLSyncKHR fence = EGL_NO_SYNC_KHR;
private:
    EGLDisplay mDisplay = EGL_NO_DISPLAY;
};

class AutoEglImage {
public:
    AutoEglImage(EGLDisplay display, EGLClientBuffer clientBuffer)
            : mDisplay(display) {
        EGLint imageAttrs[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
        image = eglCreateImageKHR(display, EGL_NO_CONTEXT,
                EGL_NATIVE_BUFFER_ANDROID, clientBuffer, imageAttrs);
    }

    ~AutoEglImage() {
        if (image != EGL_NO_IMAGE_KHR) {
            eglDestroyImageKHR(mDisplay, image);
        }
    }

    EGLImageKHR image = EGL_NO_IMAGE_KHR;
private:
    EGLDisplay mDisplay = EGL_NO_DISPLAY;
};

class AutoGlTexture {
public:
    AutoGlTexture(uirenderer::Caches& caches)
            : mCaches(caches) {
        glGenTextures(1, &mTexture);
        caches.textureState().bindTexture(mTexture);
    }

    ~AutoGlTexture() {
        mCaches.textureState().deleteTexture(mTexture);
    }

private:
    uirenderer::Caches& mCaches;
    GLuint mTexture = 0;
};

static bool uploadBitmapToGraphicBuffer(uirenderer::Caches& caches, SkBitmap& bitmap,
        GraphicBuffer& buffer, GLint format, GLint type) {
    SkAutoLockPixels alp(bitmap);
    EGLDisplay display = eglGetCurrentDisplay();
    LOG_ALWAYS_FATAL_IF(display == EGL_NO_DISPLAY,
                "Failed to get EGL_DEFAULT_DISPLAY! err=%s",
                uirenderer::renderthread::EglManager::eglErrorString());
    // We use an EGLImage to access the content of the GraphicBuffer
    // The EGL image is later bound to a 2D texture
    EGLClientBuffer clientBuffer = (EGLClientBuffer) buffer.getNativeBuffer();
    AutoEglImage autoImage(display, clientBuffer);
    if (autoImage.image == EGL_NO_IMAGE_KHR) {
        ALOGW("Could not create EGL image, err =%s",
                uirenderer::renderthread::EglManager::eglErrorString());
        return false;
    }
    AutoGlTexture glTexture(caches);
    glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, autoImage.image);

    GL_CHECKPOINT(MODERATE);

    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, bitmap.width(), bitmap.height(),
            format, type, bitmap.getPixels());

    GL_CHECKPOINT(MODERATE);

    // The fence is used to wait for the texture upload to finish
    // properly. We cannot rely on glFlush() and glFinish() as
    // some drivers completely ignore these API calls
    AutoEglFence autoFence(display);
    if (autoFence.fence == EGL_NO_SYNC_KHR) {
        LOG_ALWAYS_FATAL("Could not create sync fence %#x", eglGetError());
        return false;
    }
    // The flag EGL_SYNC_FLUSH_COMMANDS_BIT_KHR will trigger a
    // pipeline flush (similar to what a glFlush() would do.)
    EGLint waitStatus = eglClientWaitSyncKHR(display, autoFence.fence,
            EGL_SYNC_FLUSH_COMMANDS_BIT_KHR, FENCE_TIMEOUT);
    if (waitStatus != EGL_CONDITION_SATISFIED_KHR) {
        LOG_ALWAYS_FATAL("Failed to wait for the fence %#x", eglGetError());
        return false;
    }
    return true;
}

sk_sp<Bitmap> Bitmap::allocateHardwareBitmap(uirenderer::renderthread::RenderThread& renderThread,
        SkBitmap& skBitmap) {
    renderThread.eglManager().initialize();
    uirenderer::Caches& caches = uirenderer::Caches::getInstance();

    const SkImageInfo& info = skBitmap.info();
    if (info.colorType() == kUnknown_SkColorType || info.colorType() == kAlpha_8_SkColorType) {
        ALOGW("unable to create hardware bitmap of colortype: %d", info.colorType());
        return nullptr;
    }

    bool needSRGB = uirenderer::transferFunctionCloseToSRGB(skBitmap.info().colorSpace());
    bool hasLinearBlending = caches.extensions().hasLinearBlending();
    GLint format, type, internalFormat;
    uirenderer::Texture::colorTypeToGlFormatAndType(caches, skBitmap.colorType(),
            needSRGB && hasLinearBlending, &internalFormat, &format, &type);

    PixelFormat pixelFormat = internalFormatToPixelFormat(internalFormat);
    sp<GraphicBuffer> buffer = new GraphicBuffer(info.width(), info.height(), pixelFormat,
            GraphicBuffer::USAGE_HW_TEXTURE |
            GraphicBuffer::USAGE_SW_WRITE_NEVER |
            GraphicBuffer::USAGE_SW_READ_NEVER,
            std::string("Bitmap::allocateHardwareBitmap pid [") + std::to_string(getpid()) + "]");

    status_t error = buffer->initCheck();
    if (error < 0) {
        ALOGW("createGraphicBuffer() failed in GraphicBuffer.create()");
        return nullptr;
    }

    SkBitmap bitmap;
    if (CC_UNLIKELY(uirenderer::Texture::hasUnsupportedColorType(skBitmap.info(),
            hasLinearBlending))) {
        sk_sp<SkColorSpace> sRGB = SkColorSpace::MakeSRGB();
        bitmap = uirenderer::Texture::uploadToN32(skBitmap, hasLinearBlending, std::move(sRGB));
    } else {
        bitmap = skBitmap;
    }

    if (!uploadBitmapToGraphicBuffer(caches, bitmap, *buffer, format, type)) {
        return nullptr;
    }
    return sk_sp<Bitmap>(new Bitmap(buffer.get(), bitmap.info()));
}

sk_sp<Bitmap> Bitmap::allocateHardwareBitmap(SkBitmap& bitmap) {
    return uirenderer::renderthread::RenderProxy::allocateHardwareBitmap(bitmap);
}

sk_sp<Bitmap> Bitmap::allocateHeapBitmap(SkBitmap* bitmap, SkColorTable* ctable) {
   return allocateBitmap(bitmap, ctable, &android::allocateHeapBitmap);
}

sk_sp<Bitmap> Bitmap::allocateHeapBitmap(const SkImageInfo& info) {
    size_t size;
    if (!computeAllocationSize(info.minRowBytes(), info.height(), &size)) {
        LOG_ALWAYS_FATAL("trying to allocate too large bitmap");
        return nullptr;
    }
    return android::allocateHeapBitmap(size, info, info.minRowBytes(), nullptr);
}

sk_sp<Bitmap> Bitmap::allocateAshmemBitmap(size_t size, const SkImageInfo& info,
        size_t rowBytes, SkColorTable* ctable) {
    // Create new ashmem region with read/write priv
    int fd = ashmem_create_region("bitmap", size);
    if (fd < 0) {
        return nullptr;
    }

    void* addr = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (addr == MAP_FAILED) {
        close(fd);
        return nullptr;
    }

    if (ashmem_set_prot_region(fd, PROT_READ) < 0) {
        munmap(addr, size);
        close(fd);
        return nullptr;
    }
    return sk_sp<Bitmap>(new Bitmap(addr, fd, size, info, rowBytes, ctable));
}

void FreePixelRef(void* addr, void* context) {
    auto pixelRef = (SkPixelRef*) context;
    pixelRef->unlockPixels();
    pixelRef->unref();
}

sk_sp<Bitmap> Bitmap::createFrom(const SkImageInfo& info, SkPixelRef& pixelRef) {
    pixelRef.ref();
    pixelRef.lockPixels();
    return sk_sp<Bitmap>(new Bitmap((void*) pixelRef.pixels(), (void*) &pixelRef, FreePixelRef,
            info, pixelRef.rowBytes(), pixelRef.colorTable()));
}

sk_sp<Bitmap> Bitmap::createFrom(sp<GraphicBuffer> graphicBuffer) {
    PixelFormat format = graphicBuffer->getPixelFormat();
    if (!graphicBuffer.get() ||
            (format != PIXEL_FORMAT_RGBA_8888 && format != PIXEL_FORMAT_RGBA_FP16)) {
        return nullptr;
    }
    SkImageInfo info = SkImageInfo::Make(graphicBuffer->getWidth(), graphicBuffer->getHeight(),
            kRGBA_8888_SkColorType, kPremul_SkAlphaType,
            SkColorSpace::MakeSRGB());
    return sk_sp<Bitmap>(new Bitmap(graphicBuffer.get(), info));
}

void Bitmap::setColorSpace(sk_sp<SkColorSpace> colorSpace) {
    // TODO: See todo in reconfigure() below
    SkImageInfo* myInfo = const_cast<SkImageInfo*>(&this->info());
    *myInfo = info().makeColorSpace(std::move(colorSpace));
}

void Bitmap::reconfigure(const SkImageInfo& newInfo, size_t rowBytes, SkColorTable* ctable) {
    if (kIndex_8_SkColorType != newInfo.colorType()) {
        ctable = nullptr;
    }
    mRowBytes = rowBytes;
    if (mColorTable.get() != ctable) {
        mColorTable.reset(SkSafeRef(ctable));
    }

    // Need to validate the alpha type to filter against the color type
    // to prevent things like a non-opaque RGB565 bitmap
    SkAlphaType alphaType;
    LOG_ALWAYS_FATAL_IF(!SkColorTypeValidateAlphaType(
            newInfo.colorType(), newInfo.alphaType(), &alphaType),
            "Failed to validate alpha type!");

    // Dirty hack is dirty
    // TODO: Figure something out here, Skia's current design makes this
    // really hard to work with. Skia really, really wants immutable objects,
    // but with the nested-ref-count hackery going on that's just not
    // feasible without going insane trying to figure it out
    SkImageInfo* myInfo = const_cast<SkImageInfo*>(&this->info());
    *myInfo = newInfo;
    changeAlphaType(alphaType);

    // Docs say to only call this in the ctor, but we're going to call
    // it anyway even if this isn't always the ctor.
    // TODO: Fix this too as part of the above TODO
    setPreLocked(getStorage(), mRowBytes, mColorTable.get());
}

Bitmap::Bitmap(void* address, size_t size, const SkImageInfo& info, size_t rowBytes, SkColorTable* ctable)
            : SkPixelRef(info)
            , mPixelStorageType(PixelStorageType::Heap) {
    mPixelStorage.heap.address = address;
    mPixelStorage.heap.size = size;
    reconfigure(info, rowBytes, ctable);
}

Bitmap::Bitmap(void* address, void* context, FreeFunc freeFunc,
                const SkImageInfo& info, size_t rowBytes, SkColorTable* ctable)
            : SkPixelRef(info)
            , mPixelStorageType(PixelStorageType::External) {
    mPixelStorage.external.address = address;
    mPixelStorage.external.context = context;
    mPixelStorage.external.freeFunc = freeFunc;
    reconfigure(info, rowBytes, ctable);
}

Bitmap::Bitmap(void* address, int fd, size_t mappedSize,
                const SkImageInfo& info, size_t rowBytes, SkColorTable* ctable)
            : SkPixelRef(info)
            , mPixelStorageType(PixelStorageType::Ashmem) {
    mPixelStorage.ashmem.address = address;
    mPixelStorage.ashmem.fd = fd;
    mPixelStorage.ashmem.size = mappedSize;
    reconfigure(info, rowBytes, ctable);
}

Bitmap::Bitmap(GraphicBuffer* buffer, const SkImageInfo& info)
        : SkPixelRef(info)
        , mPixelStorageType(PixelStorageType::Hardware) {
    mPixelStorage.hardware.buffer = buffer;
    buffer->incStrong(buffer);
    mRowBytes = bytesPerPixel(buffer->getPixelFormat()) * buffer->getStride();
}

Bitmap::~Bitmap() {
    switch (mPixelStorageType) {
    case PixelStorageType::External:
        mPixelStorage.external.freeFunc(mPixelStorage.external.address,
                mPixelStorage.external.context);
        break;
    case PixelStorageType::Ashmem:
        munmap(mPixelStorage.ashmem.address, mPixelStorage.ashmem.size);
        close(mPixelStorage.ashmem.fd);
        break;
    case PixelStorageType::Heap:
        free(mPixelStorage.heap.address);
        break;
    case PixelStorageType::Hardware:
        auto buffer = mPixelStorage.hardware.buffer;
        buffer->decStrong(buffer);
        mPixelStorage.hardware.buffer = nullptr;
        break;

    }

    android::uirenderer::renderthread::RenderProxy::onBitmapDestroyed(getStableID());
}

bool Bitmap::hasHardwareMipMap() const {
    return mHasHardwareMipMap;
}

void Bitmap::setHasHardwareMipMap(bool hasMipMap) {
    mHasHardwareMipMap = hasMipMap;
}

void* Bitmap::getStorage() const {
    switch (mPixelStorageType) {
    case PixelStorageType::External:
        return mPixelStorage.external.address;
    case PixelStorageType::Ashmem:
        return mPixelStorage.ashmem.address;
    case PixelStorageType::Heap:
        return mPixelStorage.heap.address;
    case PixelStorageType::Hardware:
        return nullptr;
    }
}

bool Bitmap::onNewLockPixels(LockRec* rec) {
    rec->fPixels = getStorage();
    rec->fRowBytes = mRowBytes;
    rec->fColorTable = mColorTable.get();
    return true;
}

size_t Bitmap::getAllocatedSizeInBytes() const {
    return info().getSafeSize(mRowBytes);
}

int Bitmap::getAshmemFd() const {
    switch (mPixelStorageType) {
    case PixelStorageType::Ashmem:
        return mPixelStorage.ashmem.fd;
    default:
        return -1;
    }
}

size_t Bitmap::getAllocationByteCount() const {
    switch (mPixelStorageType) {
    case PixelStorageType::Heap:
        return mPixelStorage.heap.size;
    default:
        return rowBytes() * height();
    }
}

void Bitmap::reconfigure(const SkImageInfo& info) {
    reconfigure(info, info.minRowBytes(), nullptr);
}

void Bitmap::setAlphaType(SkAlphaType alphaType) {
    if (!SkColorTypeValidateAlphaType(info().colorType(), alphaType, &alphaType)) {
        return;
    }

    changeAlphaType(alphaType);
}

void Bitmap::getSkBitmap(SkBitmap* outBitmap) {
    outBitmap->setHasHardwareMipMap(mHasHardwareMipMap);
    if (isHardware()) {
        if (uirenderer::Properties::isSkiaEnabled()) {
            // TODO: add color correctness for Skia pipeline - pass null color space for now
            outBitmap->allocPixels(SkImageInfo::Make(info().width(), info().height(),
                    info().colorType(), info().alphaType(), nullptr));
        } else {
            outBitmap->allocPixels(info());
        }
        uirenderer::renderthread::RenderProxy::copyGraphicBufferInto(graphicBuffer(), outBitmap);
        return;
    }
    outBitmap->setInfo(info(), rowBytes());
    outBitmap->setPixelRef(this);
}

void Bitmap::getSkBitmapForShaders(SkBitmap* outBitmap) {
    if (isHardware() && uirenderer::Properties::isSkiaEnabled()) {
        getSkBitmap(outBitmap);
    } else {
        outBitmap->setInfo(info(), rowBytes());
        outBitmap->setPixelRef(this);
        outBitmap->setHasHardwareMipMap(mHasHardwareMipMap);
    }
}

void Bitmap::getBounds(SkRect* bounds) const {
    SkASSERT(bounds);
    bounds->set(0, 0, SkIntToScalar(info().width()), SkIntToScalar(info().height()));
}

GraphicBuffer* Bitmap::graphicBuffer() {
    if (isHardware()) {
        return mPixelStorage.hardware.buffer;
    }
    return nullptr;
}

} // namespace android
