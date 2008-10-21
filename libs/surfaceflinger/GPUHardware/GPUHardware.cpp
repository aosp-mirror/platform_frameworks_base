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

#define LOG_TAG "SurfaceFlinger"

#include <stdlib.h>
#include <stdio.h>
#include <stdint.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <math.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/ioctl.h>

#include <cutils/log.h>
#include <cutils/properties.h>

#include <utils/MemoryDealer.h>
#include <utils/MemoryBase.h>
#include <utils/MemoryHeapPmem.h>
#include <utils/MemoryHeapBase.h>
#include <utils/IPCThreadState.h>
#include <utils/StopWatch.h>

#include <ui/ISurfaceComposer.h>

#include "VRamHeap.h"
#include "GPUHardware.h"

#if HAVE_ANDROID_OS
#include <linux/android_pmem.h>
#endif

#include "GPUHardware/GPUHardware.h"

/* 
 * This file manages the GPU if there is one. The intent is that this code
 * needs to be different for every devce. Currently there is no abstraction,
 * but in the long term, this code needs to be refactored so that API and
 * implementation are separated.
 * 
 * In this particular implementation, the GPU, its memory and register are
 * managed here. Clients (such as OpenGL ES) request the GPU when then need
 * it and are given a revokable heap containing the registers on memory. 
 * 
 */

namespace android {
// ---------------------------------------------------------------------------

// size reserved for GPU surfaces
// 1200 KB fits exactly:
//  - two 320*480 16-bits double-buffered surfaces
//  - one 320*480 32-bits double-buffered surface
//  - one 320*240 16-bits double-bufferd, 4x anti-aliased surface
static const int GPU_RESERVED_SIZE  = 1200 * 1024;

static const int GPUR_SIZE          = 1 * 1024 * 1024;

// ---------------------------------------------------------------------------

/* 
 * GPUHandle is a special IMemory given to the client. It represents their
 * handle to the GPU. Once they give it up, they loose GPU access, or if
 * they explicitely revoke their acces through the binder code 1000.
 * In both cases, this triggers a callback to revoke()
 * first, and then actually powers down the chip.
 * 
 * In the case of a misbehaving app, GPUHardware can ask for an immediate
 * release of the GPU to the target process which should answer by calling
 * code 1000 on GPUHandle. If it doesn't in a timely manner, the GPU will
 * be revoked from under their feet.
 * 
 * We should never hold a strong reference on GPUHandle. In practice this
 * shouldn't be a big issue though because clients should use code 1000 and
 * not rely on the dtor being called.
 * 
 */

class GPUHandle : public BnMemory
{
public:
            GPUHandle(const sp<GPUHardware>& gpu, const sp<IMemoryHeap>& heap)
                : mGPU(gpu), mClientHeap(heap) {
            }
    virtual ~GPUHandle();
    virtual sp<IMemoryHeap> getMemory(ssize_t* offset, size_t* size) const;
    virtual status_t onTransact(
            uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags);
    void setOwner(int owner) { mOwner = owner; }
private:
    void revokeNotification();
    wp<GPUHardware> mGPU;
    sp<IMemoryHeap> mClientHeap;
    int mOwner;
};

GPUHandle::~GPUHandle() { 
    //LOGD("GPUHandle %p released, revoking GPU", this);
    revokeNotification(); 
}

void GPUHandle::revokeNotification()  {
    sp<GPUHardware> hw(mGPU.promote());
    if (hw != 0) {
        hw->revoke(mOwner);
    }
}
sp<IMemoryHeap> GPUHandle::getMemory(ssize_t* offset, size_t* size) const
{
    if (offset) *offset = 0;
    if (size)   *size = mClientHeap !=0 ? mClientHeap->virtualSize() : 0;
    return mClientHeap;
}
status_t GPUHandle::onTransact(
        uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    status_t err = BnMemory::onTransact(code, data, reply, flags);
    if (err == UNKNOWN_TRANSACTION && code == 1000) {
        int callingPid = IPCThreadState::self()->getCallingPid();
        //LOGD("pid %d voluntarily revoking gpu", callingPid);
        if (callingPid == mOwner) {
            revokeNotification();
            // we've revoked the GPU, don't do it again later when we
            // are destroyed.
            mGPU.clear();
        } else {
            LOGW("%d revoking someone else's gpu? (owner=%d)",
                    callingPid, mOwner);            
        }
        err = NO_ERROR;
    }
    return err;
}

// ---------------------------------------------------------------------------

class MemoryHeapRegs : public MemoryHeapPmem 
{
public:
            MemoryHeapRegs(const wp<GPUHardware>& gpu, const sp<MemoryHeapBase>& heap);
    virtual ~MemoryHeapRegs();
    sp<IMemory> mapMemory(size_t offset, size_t size);
    virtual void revoke();
private:
    wp<GPUHardware> mGPU;
};

MemoryHeapRegs::MemoryHeapRegs(const wp<GPUHardware>& gpu, const sp<MemoryHeapBase>& heap)
    :  MemoryHeapPmem(heap), mGPU(gpu)
{
#if HAVE_ANDROID_OS
    if (heapID()>0) {
        /* this is where the GPU is powered on and the registers are mapped
         * in the client */
        //LOGD("ioctl(HW3D_GRANT_GPU)");
        int err = ioctl(heapID(), HW3D_GRANT_GPU, base());
        if (err) {
            // it can happen if the master heap has been closed already
            // in which case the GPU already is revoked (app crash for
            // instance).
            //LOGW("HW3D_GRANT_GPU failed (%s), mFD=%d, base=%p",
            //        strerror(errno), heapID(), base());
        }
    }
#endif
}

MemoryHeapRegs::~MemoryHeapRegs() 
{
}

sp<IMemory> MemoryHeapRegs::mapMemory(size_t offset, size_t size)
{
    sp<GPUHandle> memory;
    sp<GPUHardware> gpu = mGPU.promote();
    if (heapID()>0 && gpu!=0) 
        memory = new GPUHandle(gpu, this);
    return memory;
}

void MemoryHeapRegs::revoke() 
{
    MemoryHeapPmem::revoke();
#if HAVE_ANDROID_OS
    if (heapID() > 0) {
        //LOGD("ioctl(HW3D_REVOKE_GPU)");
        int err = ioctl(heapID(), HW3D_REVOKE_GPU, base());
        LOGE_IF(err, "HW3D_REVOKE_GPU failed (%s), mFD=%d, base=%p",
                strerror(errno), heapID(), base());
    }
#endif
}

// ---------------------------------------------------------------------------

class GPURegisterHeap : public PMemHeapInterface
{
public:
    GPURegisterHeap(const sp<GPUHardware>& gpu)
        : PMemHeapInterface("/dev/hw3d", GPUR_SIZE), mGPU(gpu)
    {
    }
    virtual ~GPURegisterHeap() {
    }
    virtual sp<MemoryHeapPmem> createClientHeap() {
        sp<MemoryHeapBase> parentHeap(this);
        return new MemoryHeapRegs(mGPU, parentHeap);
    }
private:
    wp<GPUHardware> mGPU;
};

/*****************************************************************************/

GPUHardware::GPUHardware()
    : mOwner(NO_OWNER)
{
}

GPUHardware::~GPUHardware()
{
}

sp<MemoryDealer> GPUHardware::request(int pid)
{
    sp<MemoryDealer> dealer;

    LOGD("pid %d requesting gpu surface (current owner = %d)", pid, mOwner);

    const int self_pid = getpid();
    if (pid == self_pid) {
        // can't use GPU from surfaceflinger's process
        return dealer;
    }

    Mutex::Autolock _l(mLock);
    
    if (mOwner != pid) {
        // someone already has the gpu.
        takeBackGPULocked();

        // releaseLocked() should be a no-op most of the time
        releaseLocked();

        requestLocked(); 
    }

    dealer = mAllocator;
    mOwner = pid;
    if (dealer == 0) {
        mOwner = SURFACE_FAILED;
    }
    
    LOGD_IF(dealer!=0, "gpu surface granted to pid %d", mOwner);
    return dealer;
}

status_t GPUHardware::request(const sp<IGPUCallback>& callback,
        ISurfaceComposer::gpu_info_t* gpu)
{
    sp<IMemory> gpuHandle;
    IPCThreadState* ipc = IPCThreadState::self();
    const int pid = ipc->getCallingPid();
    const int self_pid = getpid();

    LOGD("pid %d requesting gpu core (owner = %d)", pid, mOwner);

    if (pid == self_pid) {
        // can't use GPU from surfaceflinger's process
        return PERMISSION_DENIED;
    }

    Mutex::Autolock _l(mLock);
    if (mOwner != pid) {
        // someone already has the gpu.
        takeBackGPULocked();

        // releaseLocked() should be a no-op most of the time
        releaseLocked();

        requestLocked(); 
    }

    if (mHeapR.isValid()) {
        gpu->count = 2;
        gpu->regions[0].region = mHeap0.map(true);
        gpu->regions[0].reserved = mHeap0.reserved;
        gpu->regions[1].region = mHeap1.map(true);
        gpu->regions[1].reserved = mHeap1.reserved;
        gpu->regs = mHeapR.map();
        if (gpu->regs != 0) {
            static_cast< GPUHandle* >(gpu->regs.get())->setOwner(pid);
        }
        mCallback = callback;
        mOwner = pid;
        //LOGD("gpu core granted to pid %d, handle base=%p",
        //        mOwner, gpu->regs->pointer());
    } else {
        LOGW("couldn't grant gpu core to pid %d", pid);
    }

    return NO_ERROR;
}

void GPUHardware::revoke(int pid)
{
    Mutex::Autolock _l(mLock);
    if (mOwner > 0) {
        if (pid != mOwner) {
            LOGW("GPU owned by %d, revoke from %d", mOwner, pid);
            return;
        }
        //LOGD("revoke pid=%d, owner=%d", pid, mOwner);
        // mOwner could be <0 if the same process acquired the GPU
        // several times without releasing it first.
        mCondition.signal();
        releaseLocked(true);
    }
}

status_t GPUHardware::friendlyRevoke()
{
    Mutex::Autolock _l(mLock);
    takeBackGPULocked();
    //LOGD("friendlyRevoke owner=%d", mOwner);
    releaseLocked(true);
    return NO_ERROR;
}

void GPUHardware::takeBackGPULocked()
{
    sp<IGPUCallback> callback = mCallback;
    mCallback.clear();
    if (callback != 0) {
        callback->gpuLost(); // one-way
        mCondition.waitRelative(mLock, ms2ns(250));
    }
}

void GPUHardware::requestLocked()
{
    if (mAllocator == 0) {
        GPUPart* part = 0;
        sp<PMemHeap> surfaceHeap;
        if (mHeap1.promote() == false) {
            //LOGD("requestLocked: (1) creating new heap");
            mHeap1.set(new PMemHeap("/dev/pmem_gpu1", 0, GPU_RESERVED_SIZE));
        }
        if (mHeap1.isValid()) {
            //LOGD("requestLocked: (1) heap is valid");
            // NOTE: if GPU1 is available we use it for our surfaces
            // this could be device specific, so we should do something more
            // generic
            surfaceHeap = static_cast< PMemHeap* >( mHeap1.getHeap().get() );
            part = &mHeap1;
            if (mHeap0.promote() == false) {
                //LOGD("requestLocked: (0) creating new heap");
                mHeap0.set(new PMemHeap("/dev/pmem_gpu0"));
            }
        } else {
            //LOGD("requestLocked: (1) heap is not valid");
            // No GPU1, use GPU0 only
            if (mHeap0.promote() == false) {
                //LOGD("requestLocked: (0) creating new heap");
                mHeap0.set(new PMemHeap("/dev/pmem_gpu0", 0, GPU_RESERVED_SIZE));
            }
            if (mHeap0.isValid()) {
                //LOGD("requestLocked: (0) heap is valid");
                surfaceHeap = static_cast< PMemHeap* >( mHeap0.getHeap().get() );
                part = &mHeap0;
            }
        }
        
        if (mHeap0.isValid() || mHeap1.isValid()) {
            if (mHeapR.promote() == false) {
                //LOGD("requestLocked: (R) creating new register heap");
                mHeapR.set(new GPURegisterHeap(this));
            }
        } else {
            // we got nothing...
            mHeap0.clear();
            mHeap1.clear();
        }

        if (mHeapR.isValid() == false) {
            //LOGD("requestLocked: (R) register heap not valid!!!");
            // damn, couldn't get the gpu registers!
            mHeap0.clear();
            mHeap1.clear();
            surfaceHeap.clear();
            part = NULL;
        }

        if (surfaceHeap != 0 && part && part->getClientHeap()!=0) {
            part->reserved = GPU_RESERVED_SIZE;
            part->surface = true;
            mAllocatorDebug = static_cast<SimpleBestFitAllocator*>(
                    surfaceHeap->getAllocator().get());
            mAllocator = new MemoryDealer(
                    part->getClientHeap(),
                    surfaceHeap->getAllocator());
        }
    }
}

void GPUHardware::releaseLocked(bool dispose)
{
    /* 
     * if dispose is set, we will force the destruction of the heap,
     * so it is given back to other systems, such as camera.
     * Otherwise, we'll keep a weak pointer to it, this way we might be able
     * to reuse it later if it's still around. 
     */
    //LOGD("revoking gpu from pid %d", mOwner);
    mOwner = NO_OWNER;
    mAllocator.clear();
    mCallback.clear();

    /* if we're asked for a full revoke, dispose only of the heap
     * we're not using for surface (as we might need it while drawing) */
    mHeap0.release(mHeap0.surface ? false : dispose);
    mHeap1.release(mHeap1.surface ? false : dispose);
    mHeapR.release(false);
}

// ----------------------------------------------------------------------------
// for debugging / testing ...

sp<SimpleBestFitAllocator> GPUHardware::getAllocator() const {
    Mutex::Autolock _l(mLock);
    sp<SimpleBestFitAllocator> allocator = mAllocatorDebug.promote();
    return allocator;
}

void GPUHardware::unconditionalRevoke()
{
    Mutex::Autolock _l(mLock);
    releaseLocked();
}

// ---------------------------------------------------------------------------


GPUHardware::GPUPart::GPUPart()
    : surface(false), reserved(0)
{
}

GPUHardware::GPUPart::~GPUPart() {
}
    
const sp<PMemHeapInterface>& GPUHardware::GPUPart::getHeap() const {
    return mHeap;
}

const sp<MemoryHeapPmem>& GPUHardware::GPUPart::getClientHeap() const {
    return mClientHeap;
}

bool GPUHardware::GPUPart::isValid() const {
    return ((mHeap!=0) && (mHeap->base() != MAP_FAILED));
}

void GPUHardware::GPUPart::clear() 
{
    mHeap.clear();
    mHeapWeak.clear();
    mClientHeap.clear();
    surface = false;
}

void GPUHardware::GPUPart::set(const sp<PMemHeapInterface>& heap) 
{
    mHeapWeak.clear();
    if (heap!=0 && heap->base() == MAP_FAILED) {
        mHeap.clear();
        mClientHeap.clear();
    } else { 
        mHeap = heap;
        mClientHeap = mHeap->createClientHeap();
    }
}

bool GPUHardware::GPUPart::promote() 
{
    //LOGD("mHeapWeak=%p, mHeap=%p", mHeapWeak.unsafe_get(), mHeap.get());
    if (mHeap == 0) {
        mHeap = mHeapWeak.promote();
    }
    if (mHeap != 0) {
        if (mClientHeap != 0) {
            mClientHeap->revoke();
        }
        mClientHeap = mHeap->createClientHeap();
    }  else {
        surface = false;
    }
    return mHeap != 0;
}

sp<IMemory> GPUHardware::GPUPart::map(bool clear) 
{
    sp<IMemory> memory;
    if (mClientHeap != NULL) {
        memory = mClientHeap->mapMemory(0, mHeap->virtualSize());
        if (clear && memory!=0) {
            //StopWatch sw("memset");
            memset(memory->pointer(), 0, memory->size());
        }
    }
    return memory;
}

void GPUHardware::GPUPart::release(bool dispose)
{
    if (mClientHeap != 0) {
        mClientHeap->revoke();
        mClientHeap.clear();
    }
    if (dispose) {
        if (mHeapWeak!=0 && mHeap==0) {
            mHeap = mHeapWeak.promote();
        }
        if (mHeap != 0) {
            mHeap->dispose();
            mHeapWeak.clear();
            mHeap.clear();
        } else {
            surface = false;
        }
    } else {
        if (mHeap != 0) {
            mHeapWeak = mHeap;
            mHeap.clear();
        }
    }
}

// ---------------------------------------------------------------------------
}; // namespace android

