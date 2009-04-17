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
#define DEBUG_MAPPINGS           0
// never remove mappings from the list
#define DEBUG_MAPPINGS_KEEP_ALL  0
// ---------------------------------------------------------------------------

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

status_t BufferMapper::map(buffer_handle_t handle, void** addr, const void* id)
{
    Mutex::Autolock _l(mLock);
    status_t err = mAllocMod->map(mAllocMod, handle, addr);
    LOGW_IF(err, "map(...) failed %d (%s)", err, strerror(-err));
#if DEBUG_MAPPINGS
    if (err == NO_ERROR)
        logMapLocked(handle, id);
#endif
    return err;
}

status_t BufferMapper::unmap(buffer_handle_t handle, const void* id)
{
    Mutex::Autolock _l(mLock);
    status_t err = mAllocMod->unmap(mAllocMod, handle);
    LOGW_IF(err, "unmap(...) failed %d (%s)", err, strerror(-err));
#if DEBUG_MAPPINGS
    if (err == NO_ERROR)
        logUnmapLocked(handle, id);
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

void BufferMapper::logMapLocked(buffer_handle_t handle, const void* id)
{
    CallStack stack;
    stack.update(2);
    
    map_info_t info;
    info.id = id;
    info.stack = stack;
    
    ssize_t index = mMapInfo.indexOfKey(handle);
    if (index >= 0) {
        Vector<map_info_t>& infos = mMapInfo.editValueAt(index);
        infos.add(info);
    } else {
        Vector<map_info_t> infos;
        infos.add(info);
        mMapInfo.add(handle, infos);
    }
}

void BufferMapper::logUnmapLocked(buffer_handle_t handle, const void* id)
{    
    ssize_t index = mMapInfo.indexOfKey(handle);
    if (index < 0) {
        LOGE("unmapping %p which doesn't exist in our map!", handle);
        return;
    }
    
    Vector<map_info_t>& infos = mMapInfo.editValueAt(index);
    ssize_t count = infos.size();
    for (int i=0 ; i<count ; ) {
        if (infos[i].id == id) {
            infos.removeAt(i);
            --count;
        } else {
            ++i;
        }
    }
    if (count == 0) {
        mMapInfo.removeItemsAt(index, 1);
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
    
    const Vector<map_info_t>& infos = mMapInfo.valueAt(index);
    ssize_t count = infos.size();
    for (int i=0 ; i<count ; i++) {
        LOGD("#%d", i);
        infos[i].stack.dump();
    }
}

// ---------------------------------------------------------------------------
}; // namespace android
