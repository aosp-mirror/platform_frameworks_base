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

#include "BufferAllocator.h"
#include "LayerBitmap.h"
#include "SurfaceFlinger.h"


namespace android {

// ===========================================================================
// Buffer and implementation of android_native_buffer_t
// ===========================================================================

Buffer::Buffer(uint32_t w, uint32_t h, PixelFormat format,
        uint32_t reqUsage, uint32_t flags)
    : SurfaceBuffer(), mInitCheck(NO_INIT), mFlags(flags), 
    mVStride(0)
{
    this->format = format;
    if (w>0 && h>0) {
        mInitCheck = initSize(w, h, reqUsage);
    }
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

status_t Buffer::initSize(uint32_t w, uint32_t h, uint32_t reqUsage)
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
    
    if (mFlags & Buffer::SECURE) {
        // secure buffer, don't store it into the GPU
        usage = BufferAllocator::USAGE_SW_READ_OFTEN | 
                BufferAllocator::USAGE_SW_WRITE_OFTEN;
    } else {
        // it's allowed to modify the usage flags here, but generally
        // the requested flags should be honored.
        usage = reqUsage | BufferAllocator::USAGE_HW_TEXTURE;
    }

    err = allocator.alloc(w, h, format, usage, &handle, &stride);
    
    if (err == NO_ERROR) {
        width  = w;
        height = h;
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

// ===========================================================================
// LayerBitmap
// ===========================================================================

LayerBitmap::LayerBitmap()
    : mInfo(0), mWidth(0), mHeight(0)
{
}

LayerBitmap::~LayerBitmap()
{
}

status_t LayerBitmap::init(surface_info_t* info,
        uint32_t w, uint32_t h, PixelFormat format, uint32_t flags)
{
    if (info == NULL)
        return BAD_VALUE;
    
    mFormat = format;
    mFlags = flags;
    mWidth = w;
    mHeight = h;

    mInfo = info;
    memset(info, 0, sizeof(surface_info_t));
    info->flags = surface_info_t::eNeedNewBuffer;
    
    // init the buffer, but don't trigger an allocation
    mBuffer = new Buffer(0, 0, format, flags);
    return NO_ERROR;
}

status_t LayerBitmap::setSize(uint32_t w, uint32_t h)
{
    Mutex::Autolock _l(mLock);
    if ((w != mWidth) || (h != mHeight)) {
        mWidth  = w;
        mHeight = h;
        // this will signal the client that it needs to asks us for a new buffer
        mInfo->flags = surface_info_t::eNeedNewBuffer;
    }
    return NO_ERROR;
}

sp<Buffer> LayerBitmap::allocate(uint32_t reqUsage)
{
    Mutex::Autolock _l(mLock);
    surface_info_t* info = mInfo;
    mBuffer.clear(); // free buffer before allocating a new one
    sp<Buffer> buffer = new Buffer(mWidth, mHeight, mFormat, reqUsage, mFlags);
    status_t err = buffer->initCheck();
    if (LIKELY(err == NO_ERROR)) {
        info->flags  = surface_info_t::eBufferDirty;
        info->status = NO_ERROR;
    } else {
        memset(info, 0, sizeof(surface_info_t));
        info->status = NO_MEMORY;
    }
    mBuffer = buffer;
    return buffer;
}

status_t LayerBitmap::free()
{
    mBuffer.clear();
    mWidth = 0;
    mHeight = 0;
    return NO_ERROR;
}


// ---------------------------------------------------------------------------

}; // namespace android
