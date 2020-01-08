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

#include "HardwareBitmapUploader.h"
#include "Properties.h"
#include "renderthread/RenderProxy.h"
#include "utils/Color.h"
#include <utils/Trace.h>

#include <sys/mman.h>

#include <cutils/ashmem.h>
#include <log/log.h>

#include <binder/IServiceManager.h>
#include <private/gui/ComposerService.h>
#include <ui/PixelFormat.h>

#include <SkCanvas.h>
#include <SkImagePriv.h>

#include <SkHighContrastFilter.h>
#include <limits>

namespace android {

// returns true if rowBytes * height can be represented by a positive int32_t value
// and places that value in size.
static bool computeAllocationSize(size_t rowBytes, int height, size_t* size) {
    return 0 <= height && height <= std::numeric_limits<size_t>::max() &&
           !__builtin_mul_overflow(rowBytes, (size_t)height, size) &&
           *size <= std::numeric_limits<int32_t>::max();
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

sk_sp<Bitmap> Bitmap::allocateHardwareBitmap(const SkBitmap& bitmap) {
    return uirenderer::HardwareBitmapUploader::allocateHardwareBitmap(bitmap);
}

sk_sp<Bitmap> Bitmap::allocateHeapBitmap(SkBitmap* bitmap) {
    return allocateBitmap(bitmap, &Bitmap::allocateHeapBitmap);
}

sk_sp<Bitmap> Bitmap::allocateHeapBitmap(const SkImageInfo& info) {
    size_t size;
    if (!computeAllocationSize(info.minRowBytes(), info.height(), &size)) {
        LOG_ALWAYS_FATAL("trying to allocate too large bitmap");
        return nullptr;
    }
    return allocateHeapBitmap(size, info, info.minRowBytes());
}

sk_sp<Bitmap> Bitmap::allocateHeapBitmap(size_t size, const SkImageInfo& info, size_t rowBytes) {
    void* addr = calloc(size, 1);
    if (!addr) {
        return nullptr;
    }
    return sk_sp<Bitmap>(new Bitmap(addr, size, info, rowBytes));
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


sk_sp<Bitmap> Bitmap::createFrom(sp<GraphicBuffer> graphicBuffer, SkColorType colorType,
                                 sk_sp<SkColorSpace> colorSpace, SkAlphaType alphaType,
                                 BitmapPalette palette) {
    SkImageInfo info = SkImageInfo::Make(graphicBuffer->getWidth(), graphicBuffer->getHeight(),
                                         colorType, alphaType, colorSpace);
    return sk_sp<Bitmap>(new Bitmap(graphicBuffer.get(), info, palette));
}

sk_sp<Bitmap> Bitmap::createFrom(const SkImageInfo& info, size_t rowBytes, int fd, void* addr,
                                 size_t size, bool readOnly) {
    if (info.colorType() == kUnknown_SkColorType) {
        LOG_ALWAYS_FATAL("unknown bitmap configuration");
        return nullptr;
    }

    if (!addr) {
        // Map existing ashmem region if not already mapped.
        int flags = readOnly ? (PROT_READ) : (PROT_READ | PROT_WRITE);
        size = ashmem_get_size_region(fd);
        addr = mmap(NULL, size, flags, MAP_SHARED, fd, 0);
        if (addr == MAP_FAILED) {
            return nullptr;
        }
    }

    sk_sp<Bitmap> bitmap(new Bitmap(addr, fd, size, info, rowBytes));
    if (readOnly) {
        bitmap->setImmutable();
    }
    return bitmap;
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

Bitmap::Bitmap(GraphicBuffer* buffer, const SkImageInfo& info, BitmapPalette palette)
        : SkPixelRef(info.width(), info.height(), nullptr,
                     bytesPerPixel(buffer->getPixelFormat()) * (buffer->getStride() > 0 ? buffer->getStride() : buffer->getWidth()))
        , mInfo(validateAlpha(info))
        , mPixelStorageType(PixelStorageType::Hardware)
        , mPalette(palette)
        , mPaletteGenerationId(getGenerationID()) {
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
            mallopt(M_PURGE, 0);
            break;
        case PixelStorageType::Hardware:
            auto buffer = mPixelStorage.hardware.buffer;
            buffer->decStrong(buffer);
            mPixelStorage.hardware.buffer = nullptr;
            break;
    }
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
        case PixelStorageType::Ashmem:
            return mPixelStorage.ashmem.size;
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
    if (isHardware()) {
        outBitmap->allocPixels(mInfo);
        uirenderer::renderthread::RenderProxy::copyHWBitmapInto(this, outBitmap);
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

sk_sp<SkImage> Bitmap::makeImage() {
    sk_sp<SkImage> image = mImage;
    if (!image) {
        SkASSERT(!isHardware());
        SkBitmap skiaBitmap;
        skiaBitmap.setInfo(info(), rowBytes());
        skiaBitmap.setPixelRef(sk_ref_sp(this), 0, 0);
        // Note we don't cache in this case, because the raster image holds a pointer to this Bitmap
        // internally and ~Bitmap won't be invoked.
        // TODO: refactor Bitmap to not derive from SkPixelRef, which would allow caching here.
        image = SkMakeImageFromRasterBitmap(skiaBitmap, kNever_SkCopyPixelsMode);
    }
    return image;
}

class MinMaxAverage {
public:
    void add(float sample) {
        if (mCount == 0) {
            mMin = sample;
            mMax = sample;
        } else {
            mMin = std::min(mMin, sample);
            mMax = std::max(mMax, sample);
        }
        mTotal += sample;
        mCount++;
    }

    float average() { return mTotal / mCount; }

    float min() { return mMin; }

    float max() { return mMax; }

    float delta() { return mMax - mMin; }

private:
    float mMin = 0.0f;
    float mMax = 0.0f;
    float mTotal = 0.0f;
    int mCount = 0;
};

BitmapPalette Bitmap::computePalette(const SkImageInfo& info, const void* addr, size_t rowBytes) {
    ATRACE_CALL();

    SkPixmap pixmap{info, addr, rowBytes};

    // TODO: This calculation of converting to HSV & tracking min/max is probably overkill
    // Experiment with something simpler since we just want to figure out if it's "color-ful"
    // and then the average perceptual lightness.

    MinMaxAverage hue, saturation, value;
    int sampledCount = 0;

    // Sample a grid of 100 pixels to get an overall estimation of the colors in play
    const int x_step = std::max(1, pixmap.width() / 10);
    const int y_step = std::max(1, pixmap.height() / 10);
    for (int x = 0; x < pixmap.width(); x += x_step) {
        for (int y = 0; y < pixmap.height(); y += y_step) {
            SkColor color = pixmap.getColor(x, y);
            if (!info.isOpaque() && SkColorGetA(color) < 75) {
                continue;
            }

            sampledCount++;
            float hsv[3];
            SkColorToHSV(color, hsv);
            hue.add(hsv[0]);
            saturation.add(hsv[1]);
            value.add(hsv[2]);
        }
    }

    // TODO: Tune the coverage threshold
    if (sampledCount < 5) {
        ALOGV("Not enough samples, only found %d for image sized %dx%d, format = %d, alpha = %d",
              sampledCount, info.width(), info.height(), (int)info.colorType(),
              (int)info.alphaType());
        return BitmapPalette::Unknown;
    }

    ALOGV("samples = %d, hue [min = %f, max = %f, avg = %f]; saturation [min = %f, max = %f, avg = "
          "%f]",
          sampledCount, hue.min(), hue.max(), hue.average(), saturation.min(), saturation.max(),
          saturation.average());

    if (hue.delta() <= 20 && saturation.delta() <= .1f) {
        if (value.average() >= .5f) {
            return BitmapPalette::Light;
        } else {
            return BitmapPalette::Dark;
        }
    }
    return BitmapPalette::Unknown;
}

}  // namespace android
