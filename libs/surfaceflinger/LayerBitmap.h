/*
 * Copyright (C) 2007 The Android Open Source Project
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

#ifndef ANDROID_LAYER_BITMAP_H
#define ANDROID_LAYER_BITMAP_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/Atomic.h>
#include <ui/PixelFormat.h>
#include <ui/Rect.h>
#include <private/ui/SharedState.h>
#include <pixelflinger/pixelflinger.h>

class copybit_image_t;

namespace android {

// ---------------------------------------------------------------------------

class IMemory;
class MemoryDealer;
class LayerBitmap;

// ---------------------------------------------------------------------------

class LayerBitmap
{
public:

    enum {
        // erase memory to ensure security when necessary
        SECURE_BITS = 0x00000001
    };

                LayerBitmap();
                ~LayerBitmap();
    status_t    init(const sp<MemoryDealer>& allocator);

    status_t    setBits(uint32_t w, uint32_t h, uint32_t alignment,
                        PixelFormat format, uint32_t flags = 0);
    void        clear();

    status_t    getInfo(surface_info_t* info) const;
    status_t    resize(uint32_t w, uint32_t h);

    const GGLSurface& surface() const   { return mSurface; }
    Rect bounds() const                 { return Rect(width(), height()); }
    uint32_t width() const              { return surface().width; }
    uint32_t height() const             { return surface().height; }
    uint32_t stride() const             { return surface().stride; }
    PixelFormat pixelFormat() const     { return surface().format; }
    void* serverBits() const            { return surface().data; }
    size_t size() const;
    const sp<MemoryDealer>& getAllocator() const { return mAllocator; }
    void getBitmapSurface(copybit_image_t* img) const;

private:
    sp<MemoryDealer>        mAllocator;
    sp<IMemory>             mBitsMemory;
    uint32_t                mAllocFlags;
    ssize_t                 mOffset;
    GGLSurface              mSurface;
    size_t                  mSize;
    uint32_t                mAlignment;
};

}; // namespace android

#endif // ANDROID_LAYER_BITMAP_H
