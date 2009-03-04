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

#define LOG_TAG "SurfaceFlinger"

#include <stdlib.h>
#include <stdint.h>
#include <sys/types.h>

#include <cutils/memory.h>
#include <utils/Errors.h>
#include <utils/Log.h>
#include <utils/MemoryDealer.h>
#include <utils/IMemory.h>
#include <ui/PixelFormat.h>
#include <pixelflinger/pixelflinger.h>

#include "LayerBitmap.h"
#include "SurfaceFlinger.h"
#include "VRamHeap.h"


namespace android {

// ---------------------------------------------------------------------------

LayerBitmap::LayerBitmap()
    : mAllocFlags(0), mOffset(0), mSize(-1U), mAlignment(2)
{
    memset(&mSurface, 0, sizeof(mSurface));
}

LayerBitmap::~LayerBitmap()
{
    mSurface.data = 0;
}

status_t LayerBitmap::init(const sp<MemoryDealer>& allocator)
{
    if (mAllocator != NULL)
        return BAD_VALUE;
    mAllocator = allocator;
    return NO_ERROR;
}

status_t LayerBitmap::setBits(uint32_t w, uint32_t h, uint32_t alignment, 
        PixelFormat format, uint32_t flags)
{
    const sp<MemoryDealer>& allocator(mAllocator);
    if (allocator == NULL)
        return NO_INIT;

    if (UNLIKELY(w == mSurface.width && h == mSurface.height &&
            format == mSurface.format))
    { // same format and size, do nothing.
        return NO_ERROR;
    }

    PixelFormatInfo info;
    getPixelFormatInfo(format, &info);

    uint32_t allocFlags = MemoryDealer::PAGE_ALIGNED;
    const uint32_t align = 4; // must match GL_UNPACK_ALIGNMENT
    const uint32_t Bpp = info.bytesPerPixel;
    uint32_t stride = (w + (alignment-1)) & ~(alignment-1);
    stride = ((stride * Bpp + (align-1)) & ~(align-1)) / Bpp;
    size_t size = info.getScanlineSize(stride) * h;
    if (allocFlags & MemoryDealer::PAGE_ALIGNED) {
        size_t pagesize = getpagesize();
        size = (size + (pagesize-1)) & ~(pagesize-1);
    }

    /* FIXME: we should be able to have a h/v stride because the user of the
     * surface might have stride limitation (for instance h/w codecs often do)
     */
    int32_t vstride = 0;

    mAlignment = alignment;
    mAllocFlags = allocFlags;
    mOffset = 0;
    if (mSize != size) {
        // would be nice to have a reallocate() api
        mBitsMemory.clear(); // free-memory
        mBitsMemory = allocator->allocate(size, allocFlags);
        mSize = size;
    } else {
        // don't erase memory if we didn't have to reallocate
        flags &= ~SECURE_BITS;
    }
    if (mBitsMemory != 0) {
        mOffset = mBitsMemory->offset();
        mSurface.data = static_cast<GGLubyte*>(mBitsMemory->pointer());
        mSurface.version = sizeof(GGLSurface);
        mSurface.width  = w;
        mSurface.height = h;
        mSurface.stride = stride;
        mSurface.vstride = vstride;
        mSurface.format = format;
        if (flags & SECURE_BITS)
            clear();
    }

    if (mBitsMemory==0 || mSurface.data==0) {
        LOGE("not enough memory for layer bitmap size=%u", size);
        allocator->dump("LayerBitmap");
        mSurface.data = 0;
        mSize = -1U;
        return NO_MEMORY;
    }
    return NO_ERROR;
}

void LayerBitmap::clear()
{
    // NOTE: this memset should not be necessary, at least for
    // opaque surface. However, for security reasons it's better to keep it
    // (in the case of pmem, it's possible that the memory contains old
    // data)
    if (mSurface.data) {
        memset(mSurface.data, 0, mSize);
        //if (bytesPerPixel(mSurface.format) == 4) {
        //    android_memset32((uint32_t*)mSurface.data, 0xFF0000FF, mSize);
        //} else  {
        //    android_memset16((uint16_t*)mSurface.data, 0xF800, mSize);
        //}
    }
}

status_t LayerBitmap::getInfo(surface_info_t* info) const
{
    if (mSurface.data == 0) {
        memset(info, 0, sizeof(surface_info_t));
        info->bits_offset = NO_MEMORY;
        return NO_MEMORY;
    }
    info->w     = uint16_t(width());
    info->h     = uint16_t(height());
    info->stride= uint16_t(stride());
    info->bpr   = uint16_t(stride() * bytesPerPixel(pixelFormat()));
    info->format= uint8_t(pixelFormat());
    info->flags = surface_info_t::eBufferDirty;
    info->bits_offset = ssize_t(mOffset);
    return NO_ERROR;
}

status_t LayerBitmap::resize(uint32_t w, uint32_t h)
{
    int err = setBits(w, h, mAlignment, pixelFormat(), SECURE_BITS);
    return err;
}

size_t LayerBitmap::size() const
{
    return mSize;
}

void LayerBitmap::getBitmapSurface(copybit_image_t* img) const
{
    const sp<IMemoryHeap>& mh(getAllocator()->getMemoryHeap());
    void* sbase = mh->base();
    const GGLSurface& t(surface());
    img->w = t.stride  ?: t.width;
    img->h = t.vstride ?: t.height;
    img->format = t.format;
    img->offset = intptr_t(t.data) - intptr_t(sbase);
    img->base = sbase;
    img->fd = mh->heapID();
}

// ---------------------------------------------------------------------------

}; // namespace android
