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
#include "renderthread/RenderProxy.h"
#include "renderthread/RenderThread.h"
#include "utils/Color.h"

#include <sys/mman.h>

#include <cutils/ashmem.h>
#include <log/log.h>

#include <binder/IServiceManager.h>
#include <private/gui/ComposerService.h>
#include <ui/PixelFormat.h>

#include <SkCanvas.h>
#include <SkImagePriv.h>
#include <SkToSRGBColorFilter.h>

namespace android {

static bool computeAllocationSize(size_t rowBytes, int height, size_t* size) {
    int32_t rowBytes32 = SkToS32(rowBytes);
    int64_t bigSize = (int64_t)height * rowBytes32;
    if (rowBytes32 < 0 || !sk_64_isS32(bigSize)) {
        return false;  // allocation will be too large
    }

    *size = sk_64_asS32(bigSize);
    return true;
}

typedef sk_sp<Bitmap> (*AllocPixelRef)(size_t allocSize, const SkImageInfo& info, size_t rowBytes);

static sk_sp<Bitmap> allocateBitmap(SkBitmap* bitmap, AllocPixelRef alloc) {
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

    auto wrapper = alloc(size, info, rowBytes);
    if (wrapper) {
        wrapper->getSkBitmap(bitmap);
    }
    return wrapper;
}

sk_sp<Bitmap> Bitmap::allocateAshmemBitmap(SkBitmap* bitmap) {
    return allocateBitmap(bitmap, &Bitmap::allocateAshmemBitmap);
}

static sk_sp<Bitmap> allocateHeapBitmap(size_t size, const SkImageInfo& info, size_t rowBytes) {
    void* addr = calloc(size, 1);
    if (!addr) {
        return nullptr;
    }
    return sk_sp<Bitmap>(new Bitmap(addr, size, info, rowBytes));
}

sk_sp<Bitmap> Bitmap::allocateHardwareBitmap(SkBitmap& bitmap) {
    return uirenderer::renderthread::RenderProxy::allocateHardwareBitmap(bitmap);
}

sk_sp<Bitmap> Bitmap::allocateHeapBitmap(SkBitmap* bitmap) {
    return allocateBitmap(bitmap, &android::allocateHeapBitmap);
}

sk_sp<Bitmap> Bitmap::allocateHeapBitmap(const SkImageInfo& info) {
    size_t size;
    if (!computeAllocationSize(info.minRowBytes(), info.height(), &size)) {
        LOG_ALWAYS_FATAL("trying to allocate too large bitmap");
        return nullptr;
    }
    return android::allocateHeapBitmap(size, info, info.minRowBytes());
}

sk_sp<Bitmap> Bitmap::allocateAshmemBitmap(size_t size, const SkImageInfo& info, size_t rowBytes) {
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
    return sk_sp<Bitmap>(new Bitmap(addr, fd, size, info, rowBytes));
}

void FreePixelRef(void* addr, void* context) {
    auto pixelRef = (SkPixelRef*)context;
    pixelRef->unref();
}

sk_sp<Bitmap> Bitmap::createFrom(const SkImageInfo& info, SkPixelRef& pixelRef) {
    pixelRef.ref();
    return sk_sp<Bitmap>(new Bitmap((void*)pixelRef.pixels(), (void*)&pixelRef, FreePixelRef, info,
                                    pixelRef.rowBytes()));
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
    mInfo = mInfo.makeColorSpace(std::move(colorSpace));
}

static SkImageInfo validateAlpha(const SkImageInfo& info) {
    // Need to validate the alpha type to filter against the color type
    // to prevent things like a non-opaque RGB565 bitmap
    SkAlphaType alphaType;
    LOG_ALWAYS_FATAL_IF(
            !SkColorTypeValidateAlphaType(info.colorType(), info.alphaType(), &alphaType),
            "Failed to validate alpha type!");
    return info.makeAlphaType(alphaType);
}

void Bitmap::reconfigure(const SkImageInfo& newInfo, size_t rowBytes) {
    mInfo = validateAlpha(newInfo);

    // Dirty hack is dirty
    // TODO: Figure something out here, Skia's current design makes this
    // really hard to work with. Skia really, really wants immutable objects,
    // but with the nested-ref-count hackery going on that's just not
    // feasible without going insane trying to figure it out
    this->android_only_reset(mInfo.width(), mInfo.height(), rowBytes);
}

Bitmap::Bitmap(void* address, size_t size, const SkImageInfo& info, size_t rowBytes)
        : SkPixelRef(info.width(), info.height(), address, rowBytes)
        , mInfo(validateAlpha(info))
        , mPixelStorageType(PixelStorageType::Heap) {
    mPixelStorage.heap.address = address;
    mPixelStorage.heap.size = size;
}

Bitmap::Bitmap(void* address, void* context, FreeFunc freeFunc, const SkImageInfo& info,
               size_t rowBytes)
        : SkPixelRef(info.width(), info.height(), address, rowBytes)
        , mInfo(validateAlpha(info))
        , mPixelStorageType(PixelStorageType::External) {
    mPixelStorage.external.address = address;
    mPixelStorage.external.context = context;
    mPixelStorage.external.freeFunc = freeFunc;
}

Bitmap::Bitmap(void* address, int fd, size_t mappedSize, const SkImageInfo& info, size_t rowBytes)
        : SkPixelRef(info.width(), info.height(), address, rowBytes)
        , mInfo(validateAlpha(info))
        , mPixelStorageType(PixelStorageType::Ashmem) {
    mPixelStorage.ashmem.address = address;
    mPixelStorage.ashmem.fd = fd;
    mPixelStorage.ashmem.size = mappedSize;
}

Bitmap::Bitmap(GraphicBuffer* buffer, const SkImageInfo& info)
        : SkPixelRef(info.width(), info.height(), nullptr,
                     bytesPerPixel(buffer->getPixelFormat()) * buffer->getStride())
        , mInfo(validateAlpha(info))
        , mPixelStorageType(PixelStorageType::Hardware) {
    mPixelStorage.hardware.buffer = buffer;
    buffer->incStrong(buffer);
    setImmutable();  // HW bitmaps are always immutable
    mImage = SkImage::MakeFromAHardwareBuffer(reinterpret_cast<AHardwareBuffer*>(buffer),
            mInfo.alphaType(), mInfo.refColorSpace());
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
    reconfigure(info, info.minRowBytes());
}

void Bitmap::setAlphaType(SkAlphaType alphaType) {
    if (!SkColorTypeValidateAlphaType(info().colorType(), alphaType, &alphaType)) {
        return;
    }

    mInfo = mInfo.makeAlphaType(alphaType);
}

void Bitmap::getSkBitmap(SkBitmap* outBitmap) {
    outBitmap->setHasHardwareMipMap(mHasHardwareMipMap);
    if (isHardware()) {
            outBitmap->allocPixels(SkImageInfo::Make(info().width(), info().height(),
                                                     info().colorType(), info().alphaType(),
                                                     nullptr));
        uirenderer::renderthread::RenderProxy::copyGraphicBufferInto(graphicBuffer(), outBitmap);
        return;
    }
    outBitmap->setInfo(mInfo, rowBytes());
    outBitmap->setPixelRef(sk_ref_sp(this), 0, 0);
}

void Bitmap::getBounds(SkRect* bounds) const {
    SkASSERT(bounds);
    bounds->set(0, 0, SkIntToScalar(width()), SkIntToScalar(height()));
}

GraphicBuffer* Bitmap::graphicBuffer() {
    if (isHardware()) {
        return mPixelStorage.hardware.buffer;
    }
    return nullptr;
}

sk_sp<SkImage> Bitmap::makeImage(sk_sp<SkColorFilter>* outputColorFilter) {
    sk_sp<SkImage> image = mImage;
    if (!image) {
        SkASSERT(!isHardware());
        SkBitmap skiaBitmap;
        skiaBitmap.setInfo(info(), rowBytes());
        skiaBitmap.setPixelRef(sk_ref_sp(this), 0, 0);
        skiaBitmap.setHasHardwareMipMap(mHasHardwareMipMap);
        // Note we don't cache in this case, because the raster image holds a pointer to this Bitmap
        // internally and ~Bitmap won't be invoked.
        // TODO: refactor Bitmap to not derive from SkPixelRef, which would allow caching here.
        image = SkMakeImageFromRasterBitmap(skiaBitmap, kNever_SkCopyPixelsMode);
    }
    if (image->colorSpace() != nullptr && !image->colorSpace()->isSRGB()) {
        *outputColorFilter = SkToSRGBColorFilter::Make(image->refColorSpace());
    }
    return image;
}

}  // namespace android
