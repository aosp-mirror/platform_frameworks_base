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
#include <ui/GraphicBuffer.h>

namespace android {

enum class PixelStorageType {
    External,
    Heap,
    Ashmem,
    Hardware,
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
    static sk_sp<Bitmap> allocateHeapBitmap(SkBitmap* bitmap);
    static sk_sp<Bitmap> allocateHeapBitmap(const SkImageInfo& info);

    static sk_sp<Bitmap> allocateHardwareBitmap(SkBitmap& bitmap);

    static sk_sp<Bitmap> allocateAshmemBitmap(SkBitmap* bitmap);
    static sk_sp<Bitmap> allocateAshmemBitmap(size_t allocSize, const SkImageInfo& info,
                                              size_t rowBytes);

    static sk_sp<Bitmap> createFrom(sp<GraphicBuffer> graphicBuffer);

    static sk_sp<Bitmap> createFrom(const SkImageInfo&, SkPixelRef&);

    Bitmap(void* address, size_t allocSize, const SkImageInfo& info, size_t rowBytes);
    Bitmap(void* address, void* context, FreeFunc freeFunc, const SkImageInfo& info,
           size_t rowBytes);
    Bitmap(void* address, int fd, size_t mappedSize, const SkImageInfo& info, size_t rowBytes);
    Bitmap(GraphicBuffer* buffer, const SkImageInfo& info);

    int rowBytesAsPixels() const {
        return rowBytes() >> mInfo.shiftPerPixel();
    }

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

    GraphicBuffer* graphicBuffer();

    /**
     * Creates or returns a cached SkImage and is safe to be invoked from either
     * the UI or RenderThread.
     *
     * @param outputColorFilter is a required param that will be populated by
     *     this function if the bitmap's colorspace is not sRGB. If populated the
     *     filter will convert colors from the bitmaps colorspace into sRGB. It
     *     is the callers responsibility to use this colorFilter when drawing
     *     this image into any destination that is presumed to be sRGB (i.e. a
     *     buffer that has no colorspace defined).
     */
    sk_sp<SkImage> makeImage(sk_sp<SkColorFilter>* outputColorFilter);

private:
    virtual ~Bitmap();
    void* getStorage() const;

    SkImageInfo mInfo;

    const PixelStorageType mPixelStorageType;

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
        struct {
            GraphicBuffer* buffer;
        } hardware;
    } mPixelStorage;

    sk_sp<SkImage> mImage;  // Cache is used only for HW Bitmaps with Skia pipeline.
};

}  // namespace android
