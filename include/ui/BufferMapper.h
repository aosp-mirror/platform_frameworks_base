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

#ifndef ANDROID_UI_BUFFER_MAPPER_H
#define ANDROID_UI_BUFFER_MAPPER_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/CallStack.h>
#include <utils/threads.h>
#include <utils/Singleton.h>
#include <utils/KeyedVector.h>

#include <hardware/gralloc.h>


struct gralloc_module_t;

namespace android {

// ---------------------------------------------------------------------------

class Rect;

class BufferMapper : public Singleton<BufferMapper>
{
public:
    static inline BufferMapper& get() { return getInstance(); }
    status_t map(buffer_handle_t handle, void** addr);
    status_t unmap(buffer_handle_t handle);
    status_t lock(buffer_handle_t handle, int usage, const Rect& bounds);
    status_t unlock(buffer_handle_t handle);
    
    // dumps information about the mapping of this handle
    void dump(buffer_handle_t handle);

private:
    friend class Singleton<BufferMapper>;
    BufferMapper();
    mutable Mutex mLock;
    gralloc_module_t const *mAllocMod;
    
    struct map_info_t {
        int count;
        KeyedVector<CallStack, int> callstacks;
    };
    KeyedVector<buffer_handle_t, map_info_t> mMapInfo;
    void logMapLocked(buffer_handle_t handle);
    void logUnmapLocked(buffer_handle_t handle);
};

// ---------------------------------------------------------------------------

}; // namespace android

#endif // ANDROID_UI_BUFFER_MAPPER_H

