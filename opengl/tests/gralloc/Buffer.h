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

#include <hardware/gralloc.h>

#include <utils/Atomic.h>

#include <private/ui/SurfaceBuffer.h>
#include <ui/PixelFormat.h>
#include <ui/Rect.h>

#include <pixelflinger/pixelflinger.h>

struct android_native_buffer_t;

namespace android {

// ===========================================================================
// Buffer
// ===========================================================================

class NativeBuffer;

class Buffer : public SurfaceBuffer
{
public:
    enum {
        DONT_CLEAR  = 0x00000001,
        SECURE      = 0x00000004
    };

    Buffer();

    // creates w * h buffer
    Buffer(uint32_t w, uint32_t h, PixelFormat format,
            uint32_t reqUsage, uint32_t flags = 0);

    // return status
    status_t initCheck() const;

    uint32_t getWidth() const           { return width; }
    uint32_t getHeight() const          { return height; }
    uint32_t getStride() const          { return stride; }
    uint32_t getUsage() const           { return usage; }
    PixelFormat getPixelFormat() const  { return format; }
    Rect getBounds() const              { return Rect(width, height); }

    status_t lock(GGLSurface* surface, uint32_t usage);

    android_native_buffer_t* getNativeBuffer() const;

    status_t reallocate(uint32_t w, uint32_t h, PixelFormat f,
            uint32_t reqUsage, uint32_t flags);

private:
    friend class LightRefBase<Buffer>;
    Buffer(const Buffer& rhs);
    virtual ~Buffer();
    Buffer& operator = (const Buffer& rhs);
    const Buffer& operator = (const Buffer& rhs) const;

    status_t initSize(uint32_t w, uint32_t h, PixelFormat format,
            uint32_t reqUsage, uint32_t flags);

    ssize_t     mInitCheck;
    uint32_t    mVStride;
};

}; // namespace android

#endif // ANDROID_LAYER_BITMAP_H
