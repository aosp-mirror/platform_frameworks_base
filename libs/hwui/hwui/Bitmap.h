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
#pragma once

#include <SkBitmap.h>
#include <SkColorFilter.h>
#include <SkColorSpace.h>
#include <SkImage.h>
#include <SkImage.h>
#include <SkImageInfo.h>
#include <SkPixelRef.h>
#include <cutils/compiler.h>
#ifdef __ANDROID__ // Layoutlib does not support hardware acceleration
#include <android/hardware_buffer.h>
#endif

class SkWStream;

namespace android {

enum class PixelStorageType {
    External,
    Heap,
    Ashmem,
    Hardware,
};

// TODO: Find a better home for this. It's here because hwui/Bitmap is exported and CanvasTransform
// isn't, but cleanup should be done
enum class BitmapPalette {
    Unknown,
    Light,
    Dark,
};

namespace uirenderer {
namespace renderthread {
class RenderThread;
}
}

class PixelStorage;

typedef void (*FreeFunc)(void* addr, void* context);

class ANDROID_API Bitmap : public SkPixelRef {
public:
    /* The allocate factories not only construct the Bitmap object but also allocate the
     * backing store whose type is determined by the specific method that is called.
     *
     * The factories that accept SkBitmap* as a param will modify those params by
     * installing the returned bitmap as their SkPixelRef.
     *
     * The factories that accept const SkBitmap& as a param will copy the contents of the
     * provided bitmap into the newly allocated buffer.
     */
    static sk_sp<Bitmap> allocateAshmemBitmap(SkBitmap* bitmap);
    static sk_sp<Bitmap> allocateHardwareBitmap(const SkBitmap& bitmap);
    static sk_sp<Bitmap> allocateHeapBitmap(SkBitmap* bitmap);
    static sk_sp<Bitmap> allocateHeapBitmap(const SkImageInfo& info);

    /* The createFrom factories construct a new Bitmap object by wrapping the already allocated
     * memory that is provided as an input param.
     */
#ifdef __ANDROID__ // Layoutlib does not support hardware acceleration
    static sk_sp<Bitmap> createFrom(AHardwareBuffer* hardwareBuffer,
                                    sk_sp<SkColorSpace> colorSpace,
                                    BitmapPalette palette = BitmapPalette::Unknown);

    static sk_sp<Bitmap> createFrom(AHardwareBuffer* hardwareBuffer,
                                    SkColorType colorType,
                                    sk_sp<SkColorSpace> colorSpace,
                                    SkAlphaType alphaType,
                                    BitmapPalette palette);
#endif
    static sk_sp<Bitmap> createFrom(const SkImageInfo& info, size_t rowBytes, int fd, void* addr,
                                    size_t size, bool readOnly);
    static sk_sp<Bitmap> createFrom(const SkImageInfo&, SkPixelRef&);

    int rowBytesAsPixels() const { return rowBytes() >> mInfo.shiftPerPixel(); }

    void reconfigure(const SkImageInfo& info, size_t rowBytes);
    void reconfigure(const SkImageInfo& info);
    void setColorSpace(sk_sp<SkColorSpace> colorSpace);
    void setAlphaType(SkAlphaType alphaType);

    void getSkBitmap(SkBitmap* outBitmap);

    int getAshmemFd() const;
    size_t getAllocationByteCount() const;

    void setHasHardwareMipMap(bool hasMipMap);
    bool hasHardwareMipMap() const;

    bool isOpaque() const { return mInfo.isOpaque(); }
    SkColorType colorType() const { return mInfo.colorType(); }
    const SkImageInfo& info() const { return mInfo; }

    void getBounds(SkRect* bounds) const;

    bool isHardware() const { return mPixelStorageType == PixelStorageType::Hardware; }

    PixelStorageType pixelStorageType() const { return mPixelStorageType; }

#ifdef __ANDROID__ // Layoutlib does not support hardware acceleration
     AHardwareBuffer* hardwareBuffer();
#endif

    /**
     * Creates or returns a cached SkImage and is safe to be invoked from either
     * the UI or RenderThread.
     *
     */
    sk_sp<SkImage> makeImage();

    static BitmapPalette computePalette(const SkImageInfo& info, const void* addr, size_t rowBytes);

    static BitmapPalette computePalette(const SkBitmap& bitmap) {
        return computePalette(bitmap.info(), bitmap.getPixels(), bitmap.rowBytes());
    }

    BitmapPalette palette() {
        if (!isHardware() && mPaletteGenerationId != getGenerationID()) {
            mPalette = computePalette(info(), pixels(), rowBytes());
            mPaletteGenerationId = getGenerationID();
        }
        return mPalette;
    }

  // returns true if rowBytes * height can be represented by a positive int32_t value
  // and places that value in size.
  static bool computeAllocationSize(size_t rowBytes, int height, size_t* size);

  // These must match the int values of CompressFormat in Bitmap.java, as well as
  // AndroidBitmapCompressFormat.
  enum class JavaCompressFormat {
    Jpeg = 0,
    Png = 1,
    Webp = 2,
    WebpLossy = 3,
    WebpLossless = 4,
  };

  bool compress(JavaCompressFormat format, int32_t quality, SkWStream* stream);

  static bool compress(const SkBitmap& bitmap, JavaCompressFormat format,
                       int32_t quality, SkWStream* stream);
private:
    static sk_sp<Bitmap> allocateAshmemBitmap(size_t size, const SkImageInfo& i, size_t rowBytes);
    static sk_sp<Bitmap> allocateHeapBitmap(size_t size, const SkImageInfo& i, size_t rowBytes);

    Bitmap(void* address, size_t allocSize, const SkImageInfo& info, size_t rowBytes);
    Bitmap(void* address, void* context, FreeFunc freeFunc, const SkImageInfo& info,
           size_t rowBytes);
    Bitmap(void* address, int fd, size_t mappedSize, const SkImageInfo& info, size_t rowBytes);
#ifdef __ANDROID__ // Layoutlib does not support hardware acceleration
    Bitmap(AHardwareBuffer* buffer, const SkImageInfo& info, size_t rowBytes,
           BitmapPalette palette);

    // Common code for the two public facing createFrom(AHardwareBuffer*, ...)
    // methods.
    // bufferDesc is only used to compute rowBytes.
    static sk_sp<Bitmap> createFrom(AHardwareBuffer* hardwareBuffer, const SkImageInfo& info,
                                    const AHardwareBuffer_Desc& bufferDesc, BitmapPalette palette);
#endif

    virtual ~Bitmap();
    void* getStorage() const;

    SkImageInfo mInfo;

    const PixelStorageType mPixelStorageType;

    BitmapPalette mPalette = BitmapPalette::Unknown;
    uint32_t mPaletteGenerationId = -1;

    bool mHasHardwareMipMap = false;

    union {
        struct {
            void* address;
            void* context;
            FreeFunc freeFunc;
        } external;
        struct {
            void* address;
            int fd;
            size_t size;
        } ashmem;
        struct {
            void* address;
            size_t size;
        } heap;
#ifdef __ANDROID__ // Layoutlib does not support hardware acceleration
        struct {
            AHardwareBuffer* buffer;
        } hardware;
#endif
    } mPixelStorage;

    sk_sp<SkImage> mImage;  // Cache is used only for HW Bitmaps with Skia pipeline.
};

}  // namespace android
