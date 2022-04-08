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
#ifdef __ANDROID__  // Layoutlib does not support render thread
#include "renderthread/RenderProxy.h"
#endif
#include "utils/Color.h"
#include <utils/Trace.h>

#ifndef _WIN32
#include <sys/mman.h>
#endif

#include <cutils/ashmem.h>
#include <log/log.h>

#ifndef _WIN32
#include <binder/IServiceManager.h>
#endif
#include <ui/PixelFormat.h>

#include <SkCanvas.h>
#include <SkImagePriv.h>
#include <SkWebpEncoder.h>
#include <SkHighContrastFilter.h>
#include <limits>

namespace android {

bool Bitmap::computeAllocationSize(size_t rowBytes, int height, size_t* size) {
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
    if (!Bitmap::computeAllocationSize(rowBytes, bitmap->height(), &size)) {
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
#ifdef __ANDROID__
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
#else
    return Bitmap::allocateHeapBitmap(size, info, rowBytes);
#endif
}

sk_sp<Bitmap> Bitmap::allocateHardwareBitmap(const SkBitmap& bitmap) {
#ifdef __ANDROID__ // Layoutlib does not support hardware acceleration
    return uirenderer::HardwareBitmapUploader::allocateHardwareBitmap(bitmap);
#else
    return Bitmap::allocateHeapBitmap(bitmap.info());
#endif
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


#ifdef __ANDROID__ // Layoutlib does not support hardware acceleration
sk_sp<Bitmap> Bitmap::createFrom(AHardwareBuffer* hardwareBuffer, sk_sp<SkColorSpace> colorSpace,
                                 BitmapPalette palette) {
    AHardwareBuffer_Desc bufferDesc;
    AHardwareBuffer_describe(hardwareBuffer, &bufferDesc);
    SkImageInfo info = uirenderer::BufferDescriptionToImageInfo(bufferDesc, colorSpace);
    return createFrom(hardwareBuffer, info, bufferDesc, palette);
}

sk_sp<Bitmap> Bitmap::createFrom(AHardwareBuffer* hardwareBuffer, SkColorType colorType,
                                 sk_sp<SkColorSpace> colorSpace, SkAlphaType alphaType,
                                 BitmapPalette palette) {
    AHardwareBuffer_Desc bufferDesc;
    AHardwareBuffer_describe(hardwareBuffer, &bufferDesc);
    SkImageInfo info = SkImageInfo::Make(bufferDesc.width, bufferDesc.height,
                                         colorType, alphaType, colorSpace);
    return createFrom(hardwareBuffer, info, bufferDesc, palette);
}

sk_sp<Bitmap> Bitmap::createFrom(AHardwareBuffer* hardwareBuffer, const SkImageInfo& info,
                                 const AHardwareBuffer_Desc& bufferDesc, BitmapPalette palette) {
    // If the stride is 0 we have to use the width as an approximation (eg, compressed buffer)
    const auto bufferStride = bufferDesc.stride > 0 ? bufferDesc.stride : bufferDesc.width;
    const size_t rowBytes = info.bytesPerPixel() * bufferStride;
    return sk_sp<Bitmap>(new Bitmap(hardwareBuffer, info, rowBytes, palette));
}
#endif

sk_sp<Bitmap> Bitmap::createFrom(const SkImageInfo& info, size_t rowBytes, int fd, void* addr,
                                 size_t size, bool readOnly) {
#ifdef _WIN32 // ashmem not implemented on Windows
     return nullptr;
#else
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
#endif
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

#ifdef __ANDROID__ // Layoutlib does not support hardware acceleration
Bitmap::Bitmap(AHardwareBuffer* buffer, const SkImageInfo& info, size_t rowBytes,
               BitmapPalette palette)
        : SkPixelRef(info.width(), info.height(), nullptr, rowBytes)
        , mInfo(validateAlpha(info))
        , mPixelStorageType(PixelStorageType::Hardware)
        , mPalette(palette)
        , mPaletteGenerationId(getGenerationID()) {
    mPixelStorage.hardware.buffer = buffer;
    AHardwareBuffer_acquire(buffer);
    setImmutable();  // HW bitmaps are always immutable
    mImage = SkImage::MakeFromAHardwareBuffer(buffer, mInfo.alphaType(), mInfo.refColorSpace());
}
#endif

Bitmap::~Bitmap() {
    switch (mPixelStorageType) {
        case PixelStorageType::External:
            mPixelStorage.external.freeFunc(mPixelStorage.external.address,
                                            mPixelStorage.external.context);
            break;
        case PixelStorageType::Ashmem:
#ifndef _WIN32 // ashmem not implemented on Windows
            munmap(mPixelStorage.ashmem.address, mPixelStorage.ashmem.size);
#endif
            close(mPixelStorage.ashmem.fd);
            break;
        case PixelStorageType::Heap:
            free(mPixelStorage.heap.address);
#ifdef __ANDROID__
            mallopt(M_PURGE, 0);
#endif
            break;
        case PixelStorageType::Hardware:
#ifdef __ANDROID__ // Layoutlib does not support hardware acceleration
            auto buffer = mPixelStorage.hardware.buffer;
            AHardwareBuffer_release(buffer);
            mPixelStorage.hardware.buffer = nullptr;
#endif
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
#ifdef __ANDROID__ // Layoutlib does not support hardware acceleration
    if (isHardware()) {
        outBitmap->allocPixels(mInfo);
        uirenderer::renderthread::RenderProxy::copyHWBitmapInto(this, outBitmap);
        return;
    }
#endif
    outBitmap->setInfo(mInfo, rowBytes());
    outBitmap->setPixelRef(sk_ref_sp(this), 0, 0);
}

void Bitmap::getBounds(SkRect* bounds) const {
    SkASSERT(bounds);
    bounds->setIWH(width(), height());
}

#ifdef __ANDROID__ // Layoutlib does not support hardware acceleration
AHardwareBuffer* Bitmap::hardwareBuffer() {
    if (isHardware()) {
        return mPixelStorage.hardware.buffer;
    }
    return nullptr;
}
#endif

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

bool Bitmap::compress(JavaCompressFormat format, int32_t quality, SkWStream* stream) {
    SkBitmap skbitmap;
    getSkBitmap(&skbitmap);
    return compress(skbitmap, format, quality, stream);
}

bool Bitmap::compress(const SkBitmap& bitmap, JavaCompressFormat format,
                      int32_t quality, SkWStream* stream) {
    if (bitmap.colorType() == kAlpha_8_SkColorType) {
        // None of the JavaCompressFormats have a sensible way to compress an
        // ALPHA_8 Bitmap. SkPngEncoder will compress one, but it uses a non-
        // standard format that most decoders do not understand, so this is
        // likely not useful.
        return false;
    }

    SkEncodedImageFormat fm;
    switch (format) {
        case JavaCompressFormat::Jpeg:
            fm = SkEncodedImageFormat::kJPEG;
            break;
        case JavaCompressFormat::Png:
            fm = SkEncodedImageFormat::kPNG;
            break;
        case JavaCompressFormat::Webp:
            fm = SkEncodedImageFormat::kWEBP;
            break;
        case JavaCompressFormat::WebpLossy:
        case JavaCompressFormat::WebpLossless: {
            SkWebpEncoder::Options options;
            options.fQuality = quality;
            options.fCompression = format == JavaCompressFormat::WebpLossy ?
                    SkWebpEncoder::Compression::kLossy : SkWebpEncoder::Compression::kLossless;
            return SkWebpEncoder::Encode(stream, bitmap.pixmap(), options);
        }
    }

    return SkEncodeImage(stream, bitmap, fm, quality);
}
}  // namespace android
