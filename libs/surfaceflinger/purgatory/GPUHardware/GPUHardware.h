/*
 * Copyright (C) 2008 The Android Open Source Project
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

#ifndef ANDROID_GPU_HARDWARE_H
#define ANDROID_GPU_HARDWARE_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/RefBase.h>
#include <utils/threads.h>
#include <utils/KeyedVector.h>

#include <ui/ISurfaceComposer.h>

namespace android {

// ---------------------------------------------------------------------------

class IGPUCallback;

class GPUHardwareInterface : public virtual RefBase
{
public:
    virtual void                revoke(int pid) = 0;
    virtual sp<MemoryDealer>    request(int pid) = 0;
    virtual status_t            request(int pid, const sp<IGPUCallback>& callback,
            ISurfaceComposer::gpu_info_t* gpu) = 0;

    virtual status_t            friendlyRevoke() = 0;
    
    // used for debugging only...
    virtual sp<SimpleBestFitAllocator> getAllocator() const  = 0;
    virtual pid_t getOwner() const = 0;
    virtual void unconditionalRevoke() = 0;
};

// ---------------------------------------------------------------------------

class GPUFactory
{    
public:
    // the gpu factory
    static sp<GPUHardwareInterface> getGPU();
};

// ---------------------------------------------------------------------------
}; // namespace android

#endif // ANDROID_GPU_HARDWARE_H
