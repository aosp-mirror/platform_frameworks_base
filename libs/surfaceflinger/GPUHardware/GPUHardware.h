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

namespace android {

// ---------------------------------------------------------------------------

class GPUHardwareInterface : public RefBase
{
public:
    virtual void                revoke(int pid) = 0;
    virtual sp<MemoryDealer>    request(int pid) = 0;
    virtual status_t            request(const sp<IGPUCallback>& callback,
            ISurfaceComposer::gpu_info_t* gpu) = 0;

    virtual status_t            friendlyRevoke() = 0;
    virtual void                unconditionalRevoke() = 0;
    
    // used for debugging only...
    virtual sp<SimpleBestFitAllocator> getAllocator() const  = 0;
    virtual pid_t getOwner() const = 0;
};

// ---------------------------------------------------------------------------

class IMemory;
class MemoryHeapPmem;
class PMemHeap;

class GPUHardware : public GPUHardwareInterface
{
public:
            GPUHardware();
    virtual ~GPUHardware();
    
    virtual void                revoke(int pid);
    virtual sp<MemoryDealer>    request(int pid);
    virtual status_t            request(const sp<IGPUCallback>& callback,
            ISurfaceComposer::gpu_info_t* gpu);

    virtual status_t            friendlyRevoke();
    virtual void                unconditionalRevoke();
    
    // used for debugging only...
    virtual sp<SimpleBestFitAllocator> getAllocator() const;
    virtual pid_t getOwner() const { return mOwner; }
    
private:
    enum {
        NO_OWNER        = -1,
        SURFACE_FAILED  = -2
    };
    
    void requestLocked();
    void releaseLocked(bool dispose = false);
    void takeBackGPULocked();
    
    class GPUPart
    {
    public:
        bool surface;
        size_t reserved;
        GPUPart();
        ~GPUPart();
        const sp<PMemHeapInterface>& getHeap() const;
        const sp<MemoryHeapPmem>& getClientHeap() const;
        bool isValid() const;
        void clear();
        void set(const sp<PMemHeapInterface>& heap);
        bool promote();
        sp<IMemory> map(bool clear = false);
        void release(bool dispose);
    private:
        sp<PMemHeapInterface>   mHeap;
        wp<PMemHeapInterface>   mHeapWeak;
        sp<MemoryHeapPmem>      mClientHeap;
    };
    
    mutable Mutex   mLock;
    GPUPart         mHeap0; // SMI
    GPUPart         mHeap1; // EBI1
    GPUPart         mHeapR;
    sp<MemoryDealer> mAllocator;
    pid_t            mOwner;
    sp<IGPUCallback> mCallback;
    wp<SimpleBestFitAllocator> mAllocatorDebug;
    
    Condition       mCondition;
};

// ---------------------------------------------------------------------------
}; // namespace android

#endif // ANDROID_GPU_HARDWARE_H
