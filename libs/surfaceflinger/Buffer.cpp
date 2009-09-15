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

#include <stdlib.h>
#include <stdint.h>
#include <sys/types.h>

#include <utils/Errors.h>
#include <utils/Log.h>
#include <binder/MemoryBase.h>
#include <binder/IMemory.h>

#include <ui/PixelFormat.h>
#include <ui/Surface.h>
#include <pixelflinger/pixelflinger.h>

#include "Buffer.h"
#include "BufferAllocator.h"
#include "SurfaceFlinger.h"


namespace android {

// ===========================================================================
// Buffer and implementation of android_native_buffer_t
// ===========================================================================

Buffer::Buffer()
    : SurfaceBuffer(), mInitCheck(NO_ERROR),  mVStride(0)
{
}

Buffer::Buffer(uint32_t w, uint32_t h, PixelFormat format,
        uint32_t reqUsage, uint32_t flags)
    : SurfaceBuffer(), mInitCheck(NO_INIT), mVStride(0)
{
    mInitCheck = initSize(w, h, format, reqUsage, flags);
}

Buffer::~Buffer()
{
    if (handle) {
        BufferAllocator& allocator(BufferAllocator::get());
        allocator.free(handle);
    }
}

status_t Buffer::initCheck() const {
    return mInitCheck;
}

android_native_buffer_t* Buffer::getNativeBuffer() const
{
    return static_cast<android_native_buffer_t*>(const_cast<Buffer*>(this));
}

status_t Buffer::reallocate(uint32_t w, uint32_t h, PixelFormat f,
        uint32_t reqUsage, uint32_t flags)
{
    if (handle) {
        BufferAllocator& allocator(BufferAllocator::get());
        allocator.free(handle);
        handle = 0;
    }
    return initSize(w, h, f, reqUsage, flags);
}

status_t Buffer::initSize(uint32_t w, uint32_t h, PixelFormat format,
        uint32_t reqUsage, uint32_t flags)
{
    status_t err = NO_ERROR;

    BufferAllocator& allocator = BufferAllocator::get();
        
    /*
     *  buffers used for software rendering, but h/w composition
     *  are allocated with SW_READ_OFTEN | SW_WRITE_OFTEN | HW_TEXTURE
     *  
     *  buffers used for h/w rendering and h/w composition
     *  are allocated with  HW_RENDER | HW_TEXTURE
     *  
     *  buffers used with h/w rendering and either NPOT or no egl_image_ext
     *  are allocated with SW_READ_RARELY | HW_RENDER 
     *  
     */
    
    if (flags & Buffer::SECURE) {
        // secure buffer, don't store it into the GPU
        usage = BufferAllocator::USAGE_SW_READ_OFTEN | 
                BufferAllocator::USAGE_SW_WRITE_OFTEN;
    } else {
        // it's allowed to modify the usage flags here, but generally
        // the requested flags should be honored.
        usage = reqUsage | BufferAllocator::USAGE_HW_TEXTURE;
    }
    
    if (format == PIXEL_FORMAT_RGBX_8888)
        format = PIXEL_FORMAT_RGBA_8888;

    err = allocator.alloc(w, h, format, usage, &handle, &stride);
    if (err == NO_ERROR) {
        this->width  = w;
        this->height = h;
        this->format = format;
        mVStride = 0;
    }

    return err;
}

status_t Buffer::lock(GGLSurface* sur, uint32_t usage) 
{
    void* vaddr;
    status_t res = SurfaceBuffer::lock(usage, &vaddr);
    if (res == NO_ERROR && sur) {
        sur->version = sizeof(GGLSurface);
        sur->width = width;
        sur->height = height;
        sur->stride = stride;
        sur->format = format;
        sur->vstride = mVStride;
        sur->data = static_cast<GGLubyte*>(vaddr);
    }
    return res;
}

// ---------------------------------------------------------------------------

}; // namespace android
