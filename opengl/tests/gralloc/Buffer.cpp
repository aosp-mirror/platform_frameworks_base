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

#include <ui/PixelFormat.h>
#include <pixelflinger/pixelflinger.h>

#include "Buffer.h"
#include "BufferAllocator.h"


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
    err = allocator.alloc(w, h, format, reqUsage, &handle, &stride);
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
