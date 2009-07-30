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

#define LOG_TAG "BufferMapper"

#include <stdint.h>
#include <errno.h>

#include <utils/Errors.h>
#include <utils/Log.h>

#include <ui/BufferMapper.h>
#include <ui/Rect.h>

#include <hardware/gralloc.h>


namespace android {
// ---------------------------------------------------------------------------

ANDROID_SINGLETON_STATIC_INSTANCE( BufferMapper )

BufferMapper::BufferMapper()
    : mAllocMod(0)
{
    hw_module_t const* module;
    int err = hw_get_module(GRALLOC_HARDWARE_MODULE_ID, &module);
    LOGE_IF(err, "FATAL: can't find the %s module", GRALLOC_HARDWARE_MODULE_ID);
    if (err == 0) {
        mAllocMod = (gralloc_module_t const *)module;
    }
}

status_t BufferMapper::registerBuffer(buffer_handle_t handle)
{
    status_t err = mAllocMod->registerBuffer(mAllocMod, handle);
    LOGW_IF(err, "registerBuffer(%p) failed %d (%s)",
            handle, err, strerror(-err));
    return err;
}

status_t BufferMapper::unregisterBuffer(buffer_handle_t handle)
{
    status_t err = mAllocMod->unregisterBuffer(mAllocMod, handle);
    LOGW_IF(err, "unregisterBuffer(%p) failed %d (%s)",
            handle, err, strerror(-err));
    return err;
}

status_t BufferMapper::lock(buffer_handle_t handle, 
        int usage, const Rect& bounds, void** vaddr)
{
    status_t err = mAllocMod->lock(mAllocMod, handle, usage,
            bounds.left, bounds.top, bounds.width(), bounds.height(), vaddr);
    LOGW_IF(err, "lock(...) failed %d (%s)", err, strerror(-err));
    return err;
}

status_t BufferMapper::unlock(buffer_handle_t handle)
{
    status_t err = mAllocMod->unlock(mAllocMod, handle);
    LOGW_IF(err, "unlock(...) failed %d (%s)", err, strerror(-err));
    return err;
}

// ---------------------------------------------------------------------------
}; // namespace android
