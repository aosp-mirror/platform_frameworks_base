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

#define LOG_TAG "GraphicBuffer"

#include <stdlib.h>
#include <stdint.h>
#include <sys/types.h>

#include <utils/Errors.h>
#include <utils/Log.h>

#include <ui/GraphicBuffer.h>
#include <ui/GraphicBufferAllocator.h>
#include <ui/GraphicBufferMapper.h>
#include <ui/PixelFormat.h>

#include <pixelflinger/pixelflinger.h>

namespace android {

// ===========================================================================
// Buffer and implementation of ANativeWindowBuffer
// ===========================================================================

GraphicBuffer::GraphicBuffer()
    : BASE(), mOwner(ownData), mBufferMapper(GraphicBufferMapper::get()),
      mInitCheck(NO_ERROR), mIndex(-1)
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
      mInitCheck(NO_ERROR), mIndex(-1)
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
      mInitCheck(NO_ERROR), mIndex(-1)
{
    width  = w;
    height = h;
    stride = inStride;
    format = inFormat;
    usage  = inUsage;
    handle = inHandle;
}

GraphicBuffer::GraphicBuffer(ANativeWindowBuffer* buffer, bool keepOwnership)
    : BASE(), mOwner(keepOwnership ? ownHandle : ownNone),
      mBufferMapper(GraphicBufferMapper::get()),
      mInitCheck(NO_ERROR), mIndex(-1), mWrappedBuffer(buffer)
{
    width  = buffer->width;
    height = buffer->height;
    stride = buffer->stride;
    format = buffer->format;
    usage  = buffer->usage;
    handle = buffer->handle;
}

GraphicBuffer::~GraphicBuffer()
{
    if (handle) {
        free_handle();
    }
}

void GraphicBuffer::free_handle()
{
    if (mOwner == ownHandle) {
        mBufferMapper.unregisterBuffer(handle);
        native_handle_close(handle);
        native_handle_delete(const_cast<native_handle*>(handle));
    } else if (mOwner == ownData) {
        GraphicBufferAllocator& allocator(GraphicBufferAllocator::get());
        allocator.free(handle);
    }
    mWrappedBuffer = 0;
}

status_t GraphicBuffer::initCheck() const {
    return mInitCheck;
}

void GraphicBuffer::dumpAllocationsToSystemLog()
{
    GraphicBufferAllocator::dumpToSystemLog();
}

ANativeWindowBuffer* GraphicBuffer::getNativeBuffer() const
{
    return static_cast<ANativeWindowBuffer*>(
            const_cast<GraphicBuffer*>(this));
}

status_t GraphicBuffer::reallocate(uint32_t w, uint32_t h, PixelFormat f,
        uint32_t reqUsage)
{
    if (mOwner != ownData)
        return INVALID_OPERATION;

    if (handle && w==width && h==height && f==format && reqUsage==usage)
        return NO_ERROR;

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
    GraphicBufferAllocator& allocator = GraphicBufferAllocator::get();
    status_t err = allocator.alloc(w, h, format, reqUsage, &handle, &stride);
    if (err == NO_ERROR) {
        this->width  = w;
        this->height = h;
        this->format = format;
        this->usage  = reqUsage;
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
        ALOGE("locking pixels (%d,%d,%d,%d) outside of buffer (w=%d, h=%d)",
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
        sur->data = static_cast<GGLubyte*>(vaddr);
    }
    return res;
}

size_t GraphicBuffer::getFlattenedSize() const {
    return (8 + (handle ? handle->numInts : 0))*sizeof(int);
}

size_t GraphicBuffer::getFdCount() const {
    return handle ? handle->numFds : 0;
}

status_t GraphicBuffer::flatten(void* buffer, size_t size,
        int fds[], size_t count) const
{
    size_t sizeNeeded = GraphicBuffer::getFlattenedSize();
    if (size < sizeNeeded) return NO_MEMORY;

    size_t fdCountNeeded = GraphicBuffer::getFdCount();
    if (count < fdCountNeeded) return NO_MEMORY;

    int* buf = static_cast<int*>(buffer);
    buf[0] = 'GBFR';
    buf[1] = width;
    buf[2] = height;
    buf[3] = stride;
    buf[4] = format;
    buf[5] = usage;
    buf[6] = 0;
    buf[7] = 0;

    if (handle) {
        buf[6] = handle->numFds;
        buf[7] = handle->numInts;
        native_handle_t const* const h = handle;
        memcpy(fds,     h->data,             h->numFds*sizeof(int));
        memcpy(&buf[8], h->data + h->numFds, h->numInts*sizeof(int));
    }

    return NO_ERROR;
}

status_t GraphicBuffer::unflatten(void const* buffer, size_t size,
        int fds[], size_t count)
{
    if (size < 8*sizeof(int)) return NO_MEMORY;

    int const* buf = static_cast<int const*>(buffer);
    if (buf[0] != 'GBFR') return BAD_TYPE;

    const size_t numFds  = buf[6];
    const size_t numInts = buf[7];

    const size_t sizeNeeded = (8 + numInts) * sizeof(int);
    if (size < sizeNeeded) return NO_MEMORY;

    size_t fdCountNeeded = 0;
    if (count < fdCountNeeded) return NO_MEMORY;

    if (handle) {
        // free previous handle if any
        free_handle();
    }

    if (numFds || numInts) {
        width  = buf[1];
        height = buf[2];
        stride = buf[3];
        format = buf[4];
        usage  = buf[5];
        native_handle* h = native_handle_create(numFds, numInts);
        memcpy(h->data,          fds,     numFds*sizeof(int));
        memcpy(h->data + numFds, &buf[8], numInts*sizeof(int));
        handle = h;
    } else {
        width = height = stride = format = usage = 0;
        handle = NULL;
    }

    mOwner = ownHandle;

    if (handle != 0) {
        mBufferMapper.registerBuffer(handle);
    }

    return NO_ERROR;
}


void GraphicBuffer::setIndex(int index) {
    mIndex = index;
}

int GraphicBuffer::getIndex() const {
    return mIndex;
}

// ---------------------------------------------------------------------------

}; // namespace android
