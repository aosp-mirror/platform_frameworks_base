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

#include <binder/Parcel.h>

#include <utils/Errors.h>
#include <utils/Log.h>

#include <ui/GraphicBuffer.h>
#include <ui/GraphicBufferAllocator.h>
#include <ui/GraphicBufferMapper.h>
#include <ui/PixelFormat.h>

#include <pixelflinger/pixelflinger.h>

namespace android {

// ===========================================================================
// Buffer and implementation of android_native_buffer_t
// ===========================================================================

GraphicBuffer::GraphicBuffer()
    : BASE(), mOwner(ownData), mBufferMapper(GraphicBufferMapper::get()),
      mInitCheck(NO_ERROR),  mVStride(0), mIndex(-1)
{
    width  = 
    height = 
    stride = 
    format = 
    usage  = 0;
    handle = NULL;
}

GraphicBuffer::GraphicBuffer(uint32_t w, uint32_t h, 
        PixelFormat reqFormat, uint32_t reqUsage)
    : BASE(), mOwner(ownData), mBufferMapper(GraphicBufferMapper::get()),
      mInitCheck(NO_ERROR),  mVStride(0), mIndex(-1)
{
    width  = 
    height = 
    stride = 
    format = 
    usage  = 0;
    handle = NULL;
    mInitCheck = initSize(w, h, reqFormat, reqUsage);
}

GraphicBuffer::GraphicBuffer(uint32_t w, uint32_t h,
        PixelFormat inFormat, uint32_t inUsage,
        uint32_t inStride, native_handle_t* inHandle, bool keepOwnership)
    : BASE(), mOwner(keepOwnership ? ownHandle : ownNone),
      mBufferMapper(GraphicBufferMapper::get()),
      mInitCheck(NO_ERROR),  mVStride(0), mIndex(-1)
{
    width  = w;
    height = h;
    stride = inStride;
    format = inFormat;
    usage  = inUsage;
    handle = inHandle;
}

GraphicBuffer::GraphicBuffer(const Parcel& data) 
    : BASE(), mOwner(ownHandle), mBufferMapper(GraphicBufferMapper::get()),
      mInitCheck(NO_ERROR),  mVStride(0), mIndex(-1)
{
    // we own the handle in this case
    width  = data.readInt32();
    if (width < 0) {
        width = height = stride = format = usage = 0;
        handle = 0;
    } else {
        height = data.readInt32();
        stride = data.readInt32();
        format = data.readInt32();
        usage  = data.readInt32();
        handle = data.readNativeHandle();
    }
}

GraphicBuffer::~GraphicBuffer()
{
    if (handle) {
        if (mOwner == ownHandle) {
            native_handle_close(handle);
            native_handle_delete(const_cast<native_handle*>(handle));
        } else if (mOwner == ownData) {
            GraphicBufferAllocator& allocator(GraphicBufferAllocator::get());
            allocator.free(handle);
        }
    }
}

status_t GraphicBuffer::initCheck() const {
    return mInitCheck;
}

android_native_buffer_t* GraphicBuffer::getNativeBuffer() const
{
    return static_cast<android_native_buffer_t*>(
            const_cast<GraphicBuffer*>(this));
}

status_t GraphicBuffer::reallocate(uint32_t w, uint32_t h, PixelFormat f,
        uint32_t reqUsage)
{
    if (mOwner != ownData)
        return INVALID_OPERATION;

    if (handle) {
        GraphicBufferAllocator& allocator(GraphicBufferAllocator::get());
        allocator.free(handle);
        handle = 0;
    }
    return initSize(w, h, f, reqUsage);
}

status_t GraphicBuffer::initSize(uint32_t w, uint32_t h, PixelFormat format,
        uint32_t reqUsage)
{
    if (format == PIXEL_FORMAT_RGBX_8888)
        format = PIXEL_FORMAT_RGBA_8888;

    GraphicBufferAllocator& allocator = GraphicBufferAllocator::get();
    status_t err = allocator.alloc(w, h, format, reqUsage, &handle, &stride);
    if (err == NO_ERROR) {
        this->width  = w;
        this->height = h;
        this->format = format;
        this->usage  = reqUsage;
        mVStride = 0;
    }
    return err;
}

status_t GraphicBuffer::lock(uint32_t usage, void** vaddr)
{
    const Rect lockBounds(width, height);
    status_t res = lock(usage, lockBounds, vaddr);
    return res;
}

status_t GraphicBuffer::lock(uint32_t usage, const Rect& rect, void** vaddr)
{
    if (rect.left < 0 || rect.right  > this->width || 
        rect.top  < 0 || rect.bottom > this->height) {
        LOGE("locking pixels (%d,%d,%d,%d) outside of buffer (w=%d, h=%d)",
                rect.left, rect.top, rect.right, rect.bottom, 
                this->width, this->height);
        return BAD_VALUE;
    }
    status_t res = getBufferMapper().lock(handle, usage, rect, vaddr);
    return res;
}

status_t GraphicBuffer::unlock()
{
    status_t res = getBufferMapper().unlock(handle);
    return res;
}

status_t GraphicBuffer::lock(GGLSurface* sur, uint32_t usage) 
{
    void* vaddr;
    status_t res = GraphicBuffer::lock(usage, &vaddr);
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


status_t GraphicBuffer::writeToParcel(Parcel* reply, 
        android_native_buffer_t const* buffer)
{
    if (buffer == NULL)
        return BAD_VALUE;

    if (buffer->width < 0 || buffer->height < 0)
        return BAD_VALUE;

    status_t err = NO_ERROR;
    if (buffer->handle == NULL) {
        // this buffer doesn't have a handle
        reply->writeInt32(NO_MEMORY);
    } else {
        reply->writeInt32(buffer->width);
        reply->writeInt32(buffer->height);
        reply->writeInt32(buffer->stride);
        reply->writeInt32(buffer->format);
        reply->writeInt32(buffer->usage);
        err = reply->writeNativeHandle(buffer->handle);
    }
    return err;
}


void GraphicBuffer::setIndex(int index) {
    mIndex = index;
}

int GraphicBuffer::getIndex() const {
    return mIndex;
}

void GraphicBuffer::setVerticalStride(uint32_t vstride) {
    mVStride = vstride;
}

uint32_t GraphicBuffer::getVerticalStride() const {
    return mVStride;
}

// ---------------------------------------------------------------------------

}; // namespace android
