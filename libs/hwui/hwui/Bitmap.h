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
#include <SkColorSpace.h>
#include <SkColorTable.h>
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
    static sk_sp<Bitmap> allocateHeapBitmap(SkBitmap* bitmap, SkColorTable* ctable);
    static sk_sp<Bitmap> allocateHeapBitmap(const SkImageInfo& info);

    static sk_sp<Bitmap> allocateHardwareBitmap(SkBitmap& bitmap);

    static sk_sp<Bitmap> allocateAshmemBitmap(SkBitmap* bitmap, SkColorTable* ctable);
    static sk_sp<Bitmap> allocateAshmemBitmap(size_t allocSize, const SkImageInfo& info,
        size_t rowBytes, SkColorTable* ctable);

    static sk_sp<Bitmap> createFrom(sp<GraphicBuffer> graphicBuffer);

    static sk_sp<Bitmap> createFrom(const SkImageInfo&, SkPixelRef&);

    static sk_sp<Bitmap> allocateHardwareBitmap(uirenderer::renderthread::RenderThread&,
            SkBitmap& bitmap);

    Bitmap(void* address, size_t allocSize, const SkImageInfo& info, size_t rowBytes,
            SkColorTable* ctable);
    Bitmap(void* address, void* context, FreeFunc freeFunc,
            const SkImageInfo& info, size_t rowBytes, SkColorTable* ctable);
    Bitmap(void* address, int fd, size_t mappedSize, const SkImageInfo& info,
            size_t rowBytes, SkColorTable* ctable);

    int width() const { return info().width(); }
    int height() const { return info().height(); }

    // Can't mark as override since SkPixelRef::rowBytes isn't virtual
    // but that's OK since we just want Bitmap to be able to rely
    // on calling rowBytes() on an unlocked pixelref, which it will be
    // doing on a Bitmap type, not a SkPixelRef, so static
    // dispatching will do what we want.
    size_t rowBytes() const { return mRowBytes; }

    int rowBytesAsPixels() const {
        return mRowBytes >> info().shiftPerPixel();
    }

    void reconfigure(const SkImageInfo& info, size_t rowBytes, SkColorTable* ctable);
    void reconfigure(const SkImageInfo& info);
    void setColorSpace(sk_sp<SkColorSpace> colorSpace);
    void setAlphaType(SkAlphaType alphaType);

    void getSkBitmap(SkBitmap* outBitmap);

    // Ugly hack: in case of hardware bitmaps, it sets nullptr as pixels pointer
    // so it would crash if anyone tries to render this bitmap.
    void getSkBitmapForShaders(SkBitmap* outBitmap);

    int getAshmemFd() const;
    size_t getAllocationByteCount() const;

    void setHasHardwareMipMap(bool hasMipMap);
    bool hasHardwareMipMap() const;

    bool isOpaque() const {return info().isOpaque(); }
    SkColorType colorType() const { return info().colorType(); }
    void getBounds(SkRect* bounds) const;

    bool readyToDraw() const {
        return this->colorType() != kIndex_8_SkColorType || mColorTable;
    }

    bool isHardware() const {
        return mPixelStorageType == PixelStorageType::Hardware;
    }

    GraphicBuffer* graphicBuffer();
protected:
    virtual bool onNewLockPixels(LockRec* rec) override;
    virtual void onUnlockPixels() override { };
    virtual size_t getAllocatedSizeInBytes() const override;
private:
    Bitmap(GraphicBuffer* buffer, const SkImageInfo& info);
    virtual ~Bitmap();
    void* getStorage() const;

    PixelStorageType mPixelStorageType;

    size_t mRowBytes = 0;
    sk_sp<SkColorTable> mColorTable;
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
};

} //namespace android