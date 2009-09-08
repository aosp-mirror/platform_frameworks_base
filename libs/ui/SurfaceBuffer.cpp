/*
 * Copyright (C) 2009 The Android Open Source Project
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

#define LOG_TAG "SurfaceBuffer"

#include <stdint.h>
#include <errno.h>
#include <sys/types.h>

#include <utils/Errors.h>
#include <utils/Log.h>
#include <binder/Parcel.h>

#include <ui/BufferMapper.h>
#include <ui/Rect.h>
#include <private/ui/SurfaceBuffer.h>

namespace android {

// ============================================================================
//  SurfaceBuffer
// ============================================================================

SurfaceBuffer::SurfaceBuffer() 
    : BASE(), mOwner(false), mBufferMapper(BufferMapper::get()), mIndex(-1)
{
    width  = 
    height = 
    stride = 
    format = 
    usage  = 0;
    handle = NULL;
}

SurfaceBuffer::SurfaceBuffer(const Parcel& data) 
    : BASE(), mOwner(true), mBufferMapper(BufferMapper::get())
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

SurfaceBuffer::~SurfaceBuffer()
{
    if (handle && mOwner) {
        native_handle_close(handle);
        native_handle_delete(const_cast<native_handle*>(handle));
    }
}

status_t SurfaceBuffer::lock(uint32_t usage, void** vaddr)
{
    const Rect lockBounds(width, height);
    status_t res = lock(usage, lockBounds, vaddr);
    return res;
}

status_t SurfaceBuffer::lock(uint32_t usage, const Rect& rect, void** vaddr)
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

status_t SurfaceBuffer::unlock()
{
    status_t res = getBufferMapper().unlock(handle);
    return res;
}

status_t SurfaceBuffer::writeToParcel(Parcel* reply, 
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


void SurfaceBuffer::setIndex(int index) {
    mIndex = index;
}

int SurfaceBuffer::getIndex() const {
    return mIndex;
}


}; // namespace android

