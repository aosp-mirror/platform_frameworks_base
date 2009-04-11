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
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/types.h>
#include <sys/stat.h>

#include <utils/Errors.h>
#include <utils/threads.h>
#include <utils/Log.h>

#include <ui/BufferMapper.h>
#include <ui/Rect.h>

#include <EGL/android_natives.h>

#include <hardware/gralloc.h>

// ---------------------------------------------------------------------------
// enable mapping debugging
#define DEBUG_MAPPINGS           1
// never remove mappings from the list
#define DEBUG_MAPPINGS_KEEP_ALL  1
// ---------------------------------------------------------------------------

namespace android {
// ---------------------------------------------------------------------------

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

status_t BufferMapper::map(buffer_handle_t handle, void** addr)
{
    Mutex::Autolock _l(mLock);
    status_t err = mAllocMod->map(mAllocMod, handle, addr);
    LOGW_IF(err, "map(...) failed %d (%s)", err, strerror(-err));
#if DEBUG_MAPPINGS
    if (err == NO_ERROR)
        logMapLocked(handle);
#endif
    return err;
}

status_t BufferMapper::unmap(buffer_handle_t handle)
{
    Mutex::Autolock _l(mLock);
    status_t err = mAllocMod->unmap(mAllocMod, handle);
    LOGW_IF(err, "unmap(...) failed %d (%s)", err, strerror(-err));
#if DEBUG_MAPPINGS
    if (err == NO_ERROR)
        logUnmapLocked(handle);
#endif
    return err;
}

status_t BufferMapper::lock(buffer_handle_t handle, int usage, const Rect& bounds)
{
    status_t err = mAllocMod->lock(mAllocMod, handle, usage,
            bounds.left, bounds.top, bounds.width(), bounds.height());
    LOGW_IF(err, "unlock(...) failed %d (%s)", err, strerror(-err));
    return err;
}

status_t BufferMapper::unlock(buffer_handle_t handle)
{
    status_t err = mAllocMod->unlock(mAllocMod, handle);
    LOGW_IF(err, "unlock(...) failed %d (%s)", err, strerror(-err));
    return err;
}

void BufferMapper::logMapLocked(buffer_handle_t handle)
{
    CallStack stack;
    stack.update(2);
    
    map_info_t info;
    ssize_t index = mMapInfo.indexOfKey(handle);
    if (index >= 0) {
        info = mMapInfo.valueAt(index);
    }
    
    ssize_t stackIndex = info.callstacks.indexOfKey(stack);
    if (stackIndex >= 0) {
        info.callstacks.editValueAt(stackIndex) += 1;
    } else {
        info.callstacks.add(stack, 1);
    }
    
    if (index < 0) {
        info.count = 1;
        mMapInfo.add(handle, info);
    } else {
        info.count++;
        mMapInfo.replaceValueAt(index, info);
    }
}

void BufferMapper::logUnmapLocked(buffer_handle_t handle)
{    
    ssize_t index = mMapInfo.indexOfKey(handle);
    if (index < 0) {
        LOGE("unmapping %p which doesn't exist!", handle);
        return;
    }
    
    map_info_t& info = mMapInfo.editValueAt(index);
    info.count--;
    if (info.count == 0) {
#if DEBUG_MAPPINGS_KEEP_ALL
        info.callstacks.clear();
#else
        mMapInfo.removeItemsAt(index, 1);
#endif
    }
}

void BufferMapper::dump(buffer_handle_t handle)
{
    Mutex::Autolock _l(mLock);
    ssize_t index = mMapInfo.indexOfKey(handle);
    if (index < 0) {
        LOGD("handle %p is not mapped through BufferMapper", handle);
        return;
    }
    
    const map_info_t& info = mMapInfo.valueAt(index);
    LOGD("dumping buffer_handle_t %p mappings (count=%d)", handle, info.count);
    for (size_t i=0 ; i<info.callstacks.size() ; i++) {
        LOGD("#%d, count=%d", i, info.callstacks.valueAt(i));
        info.callstacks.keyAt(i).dump();
    }
}

// ---------------------------------------------------------------------------
}; // namespace android
