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

#define LOG_TAG "GraphicBufferMapper"

#include <stdint.h>
#ifdef HAVE_ANDROID_OS      // just want PAGE_SIZE define
# include <asm/page.h>
#else
# include <sys/user.h>
#endif
#include <errno.h>
#include <sys/mman.h>

#include <cutils/ashmem.h>

#include <utils/Errors.h>
#include <utils/Log.h>

#include <ui/GraphicBufferMapper.h>
#include <ui/Rect.h>

#include <hardware/gralloc.h>

#include <private/ui/sw_gralloc_handle.h>


namespace android {
// ---------------------------------------------------------------------------

ANDROID_SINGLETON_STATIC_INSTANCE( GraphicBufferMapper )

GraphicBufferMapper::GraphicBufferMapper()
    : mAllocMod(0)
{
    hw_module_t const* module;
    int err = hw_get_module(GRALLOC_HARDWARE_MODULE_ID, &module);
    LOGE_IF(err, "FATAL: can't find the %s module", GRALLOC_HARDWARE_MODULE_ID);
    if (err == 0) {
        mAllocMod = (gralloc_module_t const *)module;
    }
}

status_t GraphicBufferMapper::registerBuffer(buffer_handle_t handle)
{
    status_t err;
    if (sw_gralloc_handle_t::validate(handle) < 0) {
        err = mAllocMod->registerBuffer(mAllocMod, handle);
    } else {
        err = sw_gralloc_handle_t::registerBuffer((sw_gralloc_handle_t*)handle);
    }
    LOGW_IF(err, "registerBuffer(%p) failed %d (%s)",
            handle, err, strerror(-err));
    return err;
}

status_t GraphicBufferMapper::unregisterBuffer(buffer_handle_t handle)
{
    status_t err;
    if (sw_gralloc_handle_t::validate(handle) < 0) {
        err = mAllocMod->unregisterBuffer(mAllocMod, handle);
    } else {
        err = sw_gralloc_handle_t::unregisterBuffer((sw_gralloc_handle_t*)handle);
    }
    LOGW_IF(err, "unregisterBuffer(%p) failed %d (%s)",
            handle, err, strerror(-err));
    return err;
}

status_t GraphicBufferMapper::lock(buffer_handle_t handle, 
        int usage, const Rect& bounds, void** vaddr)
{
    status_t err;
    if (sw_gralloc_handle_t::validate(handle) < 0) {
        err = mAllocMod->lock(mAllocMod, handle, usage,
                bounds.left, bounds.top, bounds.width(), bounds.height(),
                vaddr);
    } else {
        err = sw_gralloc_handle_t::lock((sw_gralloc_handle_t*)handle, usage,
                bounds.left, bounds.top, bounds.width(), bounds.height(),
                vaddr);
    }
    LOGW_IF(err, "lock(...) failed %d (%s)", err, strerror(-err));
    return err;
}

status_t GraphicBufferMapper::unlock(buffer_handle_t handle)
{
    status_t err;
    if (sw_gralloc_handle_t::validate(handle) < 0) {
        err = mAllocMod->unlock(mAllocMod, handle);
    } else {
        err = sw_gralloc_handle_t::unlock((sw_gralloc_handle_t*)handle);
    }
    LOGW_IF(err, "unlock(...) failed %d (%s)", err, strerror(-err));
    return err;
}

// ---------------------------------------------------------------------------

status_t sw_gralloc_handle_t::alloc(uint32_t w, uint32_t h, int format,
        int usage, buffer_handle_t* pHandle, int32_t* pStride)
{
    int align = 4;
    int bpp = 0;
    switch (format) {
        case HAL_PIXEL_FORMAT_RGBA_8888:
        case HAL_PIXEL_FORMAT_RGBX_8888:
        case HAL_PIXEL_FORMAT_BGRA_8888:
            bpp = 4;
            break;
        case HAL_PIXEL_FORMAT_RGB_888:
            bpp = 3;
            break;
        case HAL_PIXEL_FORMAT_RGB_565:
        case HAL_PIXEL_FORMAT_RGBA_5551:
        case HAL_PIXEL_FORMAT_RGBA_4444:
            bpp = 2;
            break;
        default:
            return -EINVAL;
    }
    size_t bpr = (w*bpp + (align-1)) & ~(align-1);
    size_t size = bpr * h;
    size_t stride = bpr / bpp;
    size = (size + (PAGE_SIZE-1)) & ~(PAGE_SIZE-1);
    
    int fd = ashmem_create_region("sw-gralloc-buffer", size);
    if (fd < 0) {
        LOGE("ashmem_create_region(size=%d) failed (%s)",
                size, strerror(-errno));
        return -errno;
    }
    
    int prot = PROT_READ;
    if (usage & GRALLOC_USAGE_SW_WRITE_MASK)
        prot |= PROT_WRITE;
    
    if (ashmem_set_prot_region(fd, prot) < 0) {
        LOGE("ashmem_set_prot_region(fd=%d, prot=%x) failed (%s)",
                fd, prot, strerror(-errno));
        close(fd);
        return -errno;
    }

    void* base = mmap(0, size, prot, MAP_SHARED, fd, 0);
    if (base == MAP_FAILED) {
        LOGE("alloc mmap(fd=%d, size=%d, prot=%x) failed (%s)",
                fd, size, prot, strerror(-errno));
        close(fd);
        return -errno;
    }

    sw_gralloc_handle_t* hnd = new sw_gralloc_handle_t();
    hnd->fd = fd;
    hnd->size = size;
    hnd->base = intptr_t(base);
    hnd->prot = prot;
    *pStride = stride;
    *pHandle = hnd; 
    
    return NO_ERROR;
}

status_t sw_gralloc_handle_t::free(sw_gralloc_handle_t* hnd)
{
    if (hnd->base) {
        munmap((void*)hnd->base, hnd->size);
    }
    if (hnd->fd >= 0) {
        close(hnd->fd);
    }
    delete hnd;    
    return NO_ERROR;
}

status_t sw_gralloc_handle_t::registerBuffer(sw_gralloc_handle_t* hnd)
{
    if (hnd->pid != getpid()) {
        void* base = mmap(0, hnd->size, hnd->prot, MAP_SHARED, hnd->fd, 0);
        if (base == MAP_FAILED) {
            LOGE("registerBuffer mmap(fd=%d, size=%d, prot=%x) failed (%s)",
                    hnd->fd, hnd->size, hnd->prot, strerror(-errno));
            return -errno;
        }
        hnd->base = intptr_t(base);
    }
    return NO_ERROR;
}

status_t sw_gralloc_handle_t::unregisterBuffer(sw_gralloc_handle_t* hnd)
{
    if (hnd->pid != getpid()) {
        if (hnd->base) {
            munmap((void*)hnd->base, hnd->size);
        }
        hnd->base = 0;
    }
    return NO_ERROR;
}

status_t sw_gralloc_handle_t::lock(sw_gralloc_handle_t* hnd, int usage,
        int l, int t, int w, int h, void** vaddr)
{
    *vaddr = (void*)hnd->base;
    return NO_ERROR;
}

status_t sw_gralloc_handle_t::unlock(sw_gralloc_handle_t* hnd)
{
    return NO_ERROR;
}

// ---------------------------------------------------------------------------
}; // namespace android
