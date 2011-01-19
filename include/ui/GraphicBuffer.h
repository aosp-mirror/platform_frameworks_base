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

#ifndef ANDROID_GRAPHIC_BUFFER_H
#define ANDROID_GRAPHIC_BUFFER_H

#include <stdint.h>
#include <sys/types.h>

#include <ui/android_native_buffer.h>
#include <ui/PixelFormat.h>
#include <ui/Rect.h>
#include <utils/Flattenable.h>
#include <pixelflinger/pixelflinger.h>

struct android_native_buffer_t;

namespace android {

class GraphicBufferMapper;

// ===========================================================================
// GraphicBuffer
// ===========================================================================

class GraphicBuffer
    : public EGLNativeBase<
        android_native_buffer_t, 
        GraphicBuffer, 
        LightRefBase<GraphicBuffer> >, public Flattenable
{
public:

    enum {
        USAGE_SW_READ_NEVER     = GRALLOC_USAGE_SW_READ_NEVER,
        USAGE_SW_READ_RARELY    = GRALLOC_USAGE_SW_READ_RARELY,
        USAGE_SW_READ_OFTEN     = GRALLOC_USAGE_SW_READ_OFTEN,
        USAGE_SW_READ_MASK      = GRALLOC_USAGE_SW_READ_MASK,
        
        USAGE_SW_WRITE_NEVER    = GRALLOC_USAGE_SW_WRITE_NEVER,
        USAGE_SW_WRITE_RARELY   = GRALLOC_USAGE_SW_WRITE_RARELY,
        USAGE_SW_WRITE_OFTEN    = GRALLOC_USAGE_SW_WRITE_OFTEN,
        USAGE_SW_WRITE_MASK     = GRALLOC_USAGE_SW_WRITE_MASK,

        USAGE_SOFTWARE_MASK     = USAGE_SW_READ_MASK|USAGE_SW_WRITE_MASK,

        USAGE_PROTECTED         = GRALLOC_USAGE_PROTECTED,

        USAGE_HW_TEXTURE        = GRALLOC_USAGE_HW_TEXTURE,
        USAGE_HW_RENDER         = GRALLOC_USAGE_HW_RENDER,
        USAGE_HW_2D             = GRALLOC_USAGE_HW_2D,
        USAGE_HW_MASK           = GRALLOC_USAGE_HW_MASK
    };

    GraphicBuffer();

    // creates w * h buffer
    GraphicBuffer(uint32_t w, uint32_t h, PixelFormat format, uint32_t usage);

    // create a buffer from an existing handle
    GraphicBuffer(uint32_t w, uint32_t h, PixelFormat format, uint32_t usage,
            uint32_t stride, native_handle_t* handle, bool keepOwnership);

    // create a buffer from an existing android_native_buffer_t
    GraphicBuffer(android_native_buffer_t* buffer, bool keepOwnership);

    // return status
    status_t initCheck() const;

    uint32_t getWidth() const           { return width; }
    uint32_t getHeight() const          { return height; }
    uint32_t getStride() const          { return stride; }
    uint32_t getUsage() const           { return usage; }
    PixelFormat getPixelFormat() const  { return format; }
    Rect getBounds() const              { return Rect(width, height); }
    
    status_t reallocate(uint32_t w, uint32_t h, PixelFormat f, uint32_t usage);

    status_t lock(uint32_t usage, void** vaddr);
    status_t lock(uint32_t usage, const Rect& rect, void** vaddr);
    status_t lock(GGLSurface* surface, uint32_t usage);
    status_t unlock();

    android_native_buffer_t* getNativeBuffer() const;
    
    void setIndex(int index);
    int getIndex() const;

    // for debugging
    static void dumpAllocationsToSystemLog();

private:
    virtual ~GraphicBuffer();

    enum {
        ownNone   = 0,
        ownHandle = 1,
        ownData   = 2,
    };

    inline const GraphicBufferMapper& getBufferMapper() const {
        return mBufferMapper;
    }
    inline GraphicBufferMapper& getBufferMapper() {
        return mBufferMapper;
    }
    uint8_t mOwner;

private:
    friend class Surface;
    friend class BpSurface;
    friend class BnSurface;
    friend class SurfaceTextureClient;
    friend class LightRefBase<GraphicBuffer>;
    GraphicBuffer(const GraphicBuffer& rhs);
    GraphicBuffer& operator = (const GraphicBuffer& rhs);
    const GraphicBuffer& operator = (const GraphicBuffer& rhs) const;

    status_t initSize(uint32_t w, uint32_t h, PixelFormat format, 
            uint32_t usage);

    void free_handle();

    // Flattenable interface
    size_t getFlattenedSize() const;
    size_t getFdCount() const;
    status_t flatten(void* buffer, size_t size,
            int fds[], size_t count) const;
    status_t unflatten(void const* buffer, size_t size,
            int fds[], size_t count);


    GraphicBufferMapper& mBufferMapper;
    ssize_t mInitCheck;
    int mIndex;

    // If we're wrapping another buffer then this reference will make sure it
    // doesn't get freed.
    sp<android_native_buffer_t> mWrappedBuffer;
};

}; // namespace android

#endif // ANDROID_GRAPHIC_BUFFER_H
