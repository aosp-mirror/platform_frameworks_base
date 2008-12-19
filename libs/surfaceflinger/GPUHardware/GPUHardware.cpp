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

#include <utils/IBinder.h>
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
 * Manage the GPU. This implementation is very specific to the G1.
 * There are no abstraction here. 
 * 
 * All this code will soon go-away and be replaced by a new architecture
 * for managing graphics accelerators.
 * 
 * In the meantime, it is conceptually possible to instantiate a
 * GPUHardwareInterface for another GPU (see GPUFactory at the bottom
 * of this file); practically... doubtful.
 * 
 */

namespace android {

// ---------------------------------------------------------------------------

class GPUClientHeap;
class GPUAreaHeap;

class GPUHardware : public GPUHardwareInterface, public IBinder::DeathRecipient
{
public:
    static const int GPU_RESERVED_SIZE;
    static const int GPUR_SIZE;

            GPUHardware();
    virtual ~GPUHardware();
    
    virtual void revoke(int pid);
    virtual sp<MemoryDealer> request(int pid);
    virtual status_t request(int pid, 
            const sp<IGPUCallback>& callback,
            ISurfaceComposer::gpu_info_t* gpu);

    virtual status_t friendlyRevoke();
    virtual void unconditionalRevoke();
    
    virtual pid_t getOwner() const { return mOwner; }

    // used for debugging only...
    virtual sp<SimpleBestFitAllocator> getAllocator() const;

private:
    
    
    enum {
        NO_OWNER = -1,
    };
        
    struct GPUArea {
        sp<GPUAreaHeap>     heap;
        sp<MemoryHeapPmem>  clientHeap;
        sp<IMemory> map();
    };
    
    struct Client {
        pid_t       pid;
        GPUArea     smi;
        GPUArea     ebi;
        GPUArea     reg;
        void createClientHeaps();
        void revokeAllHeaps();
    };
    
    Client& getClientLocked(pid_t pid);
    status_t requestLocked(int pid);
    void releaseLocked();
    void takeBackGPULocked();
    void registerCallbackLocked(const sp<IGPUCallback>& callback,
            Client& client);

    virtual void binderDied(const wp<IBinder>& who);

    mutable Mutex           mLock;
    sp<GPUAreaHeap>         mSMIHeap;
    sp<GPUAreaHeap>         mEBIHeap;
    sp<GPUAreaHeap>         mREGHeap;

    KeyedVector<pid_t, Client> mClients;
    DefaultKeyedVector< wp<IBinder>, pid_t > mRegisteredClients;
    
    pid_t                   mOwner;

    sp<MemoryDealer>        mCurrentAllocator;
    sp<IGPUCallback>        mCallback;
    
    sp<SimpleBestFitAllocator>  mAllocator;

    Condition               mCondition;
};

// size reserved for GPU surfaces
// 1200 KB fits exactly:
//  - two 320*480 16-bits double-buffered surfaces
//  - one 320*480 32-bits double-buffered surface
//  - one 320*240 16-bits double-buffered, 4x anti-aliased surface
const int GPUHardware::GPU_RESERVED_SIZE  = 1200 * 1024;
const int GPUHardware::GPUR_SIZE          = 1 * 1024 * 1024;

// ---------------------------------------------------------------------------

/* 
 * GPUHandle is a special IMemory given to the client. It represents their
 * handle to the GPU. Once they give it up, they loose GPU access, or if
 * they explicitly revoke their access through the binder code 1000.
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

class GPUClientHeap : public MemoryHeapPmem
{
public:
    GPUClientHeap(const wp<GPUHardware>& gpu, 
            const sp<MemoryHeapBase>& heap)
        :  MemoryHeapPmem(heap), mGPU(gpu) { }
protected:
    wp<GPUHardware> mGPU;
};

class GPUAreaHeap : public MemoryHeapBase
{
public:
    GPUAreaHeap(const wp<GPUHardware>& gpu,
            const char* const vram, size_t size=0, size_t reserved=0)
    : MemoryHeapBase(vram, size), mGPU(gpu) { 
        if (base() != MAP_FAILED) {
            if (reserved == 0)
                reserved = virtualSize();
            mAllocator = new SimpleBestFitAllocator(reserved);
        }
    }
    virtual sp<MemoryHeapPmem> createClientHeap() {
        sp<MemoryHeapBase> parentHeap(this);
        return new GPUClientHeap(mGPU, parentHeap);
    }
    virtual const sp<SimpleBestFitAllocator>& getAllocator() const {
        return mAllocator; 
    }
private:
    sp<SimpleBestFitAllocator>  mAllocator;
protected:
    wp<GPUHardware> mGPU;
};

class GPURegisterHeap : public GPUAreaHeap
{
public:
    GPURegisterHeap(const sp<GPUHardware>& gpu)
        : GPUAreaHeap(gpu, "/dev/hw3d", GPUHardware::GPUR_SIZE) { }
    virtual sp<MemoryHeapPmem> createClientHeap() {
        sp<MemoryHeapBase> parentHeap(this);
        return new MemoryHeapRegs(mGPU, parentHeap);
    }
private:
    class MemoryHeapRegs : public GPUClientHeap  {
    public:
        MemoryHeapRegs(const wp<GPUHardware>& gpu, 
             const sp<MemoryHeapBase>& heap)
            : GPUClientHeap(gpu, heap) { }
        sp<MemoryHeapPmem::MemoryPmem> createMemory(size_t offset, size_t size);
        virtual void revoke();
    private:
        class GPUHandle : public MemoryHeapPmem::MemoryPmem {
        public:
            GPUHandle(const sp<GPUHardware>& gpu,
                    const sp<MemoryHeapPmem>& heap)
                : MemoryHeapPmem::MemoryPmem(heap), 
                  mGPU(gpu), mOwner(gpu->getOwner()) { }
            virtual ~GPUHandle();
            virtual sp<IMemoryHeap> getMemory(
                    ssize_t* offset, size_t* size) const;
            virtual void revoke() { };
            virtual status_t onTransact(
                    uint32_t code, const Parcel& data, 
                    Parcel* reply, uint32_t flags);
        private:
            void revokeNotification();
            wp<GPUHardware> mGPU;
            pid_t mOwner;
        };
    };
};

GPURegisterHeap::MemoryHeapRegs::GPUHandle::~GPUHandle() { 
    //LOGD("GPUHandle %p released, revoking GPU", this);
    revokeNotification(); 
}
void GPURegisterHeap::MemoryHeapRegs::GPUHandle::revokeNotification()  {
    sp<GPUHardware> hw(mGPU.promote());
    if (hw != 0) {
        hw->revoke(mOwner);
    }
}
sp<IMemoryHeap> GPURegisterHeap::MemoryHeapRegs::GPUHandle::getMemory(
        ssize_t* offset, size_t* size) const
{
    sp<MemoryHeapPmem> heap = getHeap();
    if (offset) *offset = 0;
    if (size)   *size = heap !=0 ? heap->virtualSize() : 0;
    return heap;
}
status_t GPURegisterHeap::MemoryHeapRegs::GPUHandle::onTransact(
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


sp<MemoryHeapPmem::MemoryPmem> GPURegisterHeap::MemoryHeapRegs::createMemory(
        size_t offset, size_t size)
{
    sp<GPUHandle> memory;
    sp<GPUHardware> gpu = mGPU.promote();
    if (heapID()>0 && gpu!=0) {
#if HAVE_ANDROID_OS
        /* this is where the GPU is powered on and the registers are mapped
         * in the client */
        //LOGD("ioctl(HW3D_GRANT_GPU)");
        int err = ioctl(heapID(), HW3D_GRANT_GPU, base());
        if (err) {
            // it can happen if the master heap has been closed already
            // in which case the GPU already is revoked (app crash for
            // instance).
            LOGW("HW3D_GRANT_GPU failed (%s), mFD=%d, base=%p",
                    strerror(errno), heapID(), base());
        }
        memory = new GPUHandle(gpu, this);
#endif
    }
    return memory;
}

void GPURegisterHeap::MemoryHeapRegs::revoke() 
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

/*****************************************************************************/

GPUHardware::GPUHardware()
    : mOwner(NO_OWNER)
{
}

GPUHardware::~GPUHardware()
{
}

status_t GPUHardware::requestLocked(int pid)
{
    const int self_pid = getpid();
    if (pid == self_pid) {
        // can't use GPU from surfaceflinger's process
        return PERMISSION_DENIED;
    }

    if (mOwner != pid) {
        if (mREGHeap != 0) {
            if (mOwner != NO_OWNER) {
                // someone already has the gpu.
                takeBackGPULocked();
                releaseLocked();
            }
        } else {
            // first time, initialize the stuff.
            if (mSMIHeap == 0)
                mSMIHeap = new GPUAreaHeap(this, "/dev/pmem_gpu0");
            if (mEBIHeap == 0)
                mEBIHeap = new GPUAreaHeap(this, 
                        "/dev/pmem_gpu1", 0, GPU_RESERVED_SIZE);
            mREGHeap = new GPURegisterHeap(this);
            mAllocator = mEBIHeap->getAllocator();
            if (mAllocator == NULL) {
                // something went terribly wrong.
                mSMIHeap.clear();
                mEBIHeap.clear();
                mREGHeap.clear();
                return INVALID_OPERATION;
            }
        }
        Client& client = getClientLocked(pid);
        mCurrentAllocator = new MemoryDealer(client.ebi.clientHeap, mAllocator);
        mOwner = pid;
    }
    return NO_ERROR;
}

sp<MemoryDealer> GPUHardware::request(int pid)
{
    sp<MemoryDealer> dealer;
    Mutex::Autolock _l(mLock);
    Client* client;
    LOGD("pid %d requesting gpu surface (current owner = %d)", pid, mOwner);
    if (requestLocked(pid) == NO_ERROR) {
        dealer = mCurrentAllocator;
        LOGD_IF(dealer!=0, "gpu surface granted to pid %d", mOwner);
    }
    return dealer;
}

status_t GPUHardware::request(int pid, const sp<IGPUCallback>& callback,
        ISurfaceComposer::gpu_info_t* gpu)
{
    if (callback == 0)
        return BAD_VALUE;

    sp<IMemory> gpuHandle;
    LOGD("pid %d requesting gpu core (owner = %d)", pid, mOwner);
    Mutex::Autolock _l(mLock);
    status_t err = requestLocked(pid);
    if (err == NO_ERROR) {
        // it's guaranteed to be there, be construction
        Client& client = mClients.editValueFor(pid);
        registerCallbackLocked(callback, client);
        gpu->count = 2;
        gpu->regions[0].region = client.smi.map();
        gpu->regions[1].region = client.ebi.map();
        gpu->regs              = client.reg.map();
        gpu->regions[0].reserved = 0;
        gpu->regions[1].reserved = GPU_RESERVED_SIZE;
        if (gpu->regs != 0) {
            //LOGD("gpu core granted to pid %d, handle base=%p",
            //        mOwner, gpu->regs->pointer());
        }
        mCallback = callback;
    } else {
        LOGW("couldn't grant gpu core to pid %d", pid);
    }
    return err;
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
        releaseLocked();
    }
}

status_t GPUHardware::friendlyRevoke()
{
    Mutex::Autolock _l(mLock);
    //LOGD("friendlyRevoke owner=%d", mOwner);
    takeBackGPULocked();
    releaseLocked();
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

void GPUHardware::releaseLocked()
{
    //LOGD("revoking gpu from pid %d", mOwner);
    if (mOwner != NO_OWNER) {
        // this may fail because the client might have died, and have
        // been removed from the list.
        ssize_t index = mClients.indexOfKey(mOwner);
        if (index >= 0) {
            Client& client(mClients.editValueAt(index));
            client.revokeAllHeaps();
        }
        mOwner = NO_OWNER;
        mCurrentAllocator.clear();
        mCallback.clear();
    }
}

GPUHardware::Client& GPUHardware::getClientLocked(pid_t pid)
{
    ssize_t index = mClients.indexOfKey(pid);
    if (index < 0) {
        Client client;
        client.pid = pid;
        client.smi.heap = mSMIHeap;
        client.ebi.heap = mEBIHeap;
        client.reg.heap = mREGHeap;
        index = mClients.add(pid, client);
    }
    Client& client(mClients.editValueAt(index));
    client.createClientHeaps();
    return client;
}

// ----------------------------------------------------------------------------
// for debugging / testing ...

sp<SimpleBestFitAllocator> GPUHardware::getAllocator() const {
    Mutex::Autolock _l(mLock);
    return mAllocator;
}

void GPUHardware::unconditionalRevoke()
{
    Mutex::Autolock _l(mLock);
    releaseLocked();
}

// ---------------------------------------------------------------------------

sp<IMemory> GPUHardware::GPUArea::map() {
    sp<IMemory> memory;
    if (clientHeap != 0 && heap != 0) {
        memory = clientHeap->mapMemory(0, heap->virtualSize());
    }
    return memory;
}

void GPUHardware::Client::createClientHeaps() 
{
    if (smi.clientHeap == 0)
        smi.clientHeap = smi.heap->createClientHeap();
    if (ebi.clientHeap == 0)
        ebi.clientHeap = ebi.heap->createClientHeap();
    if (reg.clientHeap == 0)
        reg.clientHeap = reg.heap->createClientHeap();
}

void GPUHardware::Client::revokeAllHeaps() 
{
    if (smi.clientHeap != 0)
        smi.clientHeap->revoke();
    if (ebi.clientHeap != 0)
        ebi.clientHeap->revoke();
    if (reg.clientHeap != 0)
        reg.clientHeap->revoke();
}

void GPUHardware::registerCallbackLocked(const sp<IGPUCallback>& callback,
        Client& client)
{
    sp<IBinder> binder = callback->asBinder();
    if (mRegisteredClients.add(binder, client.pid) >= 0) {
        binder->linkToDeath(this);
    }
}

void GPUHardware::binderDied(const wp<IBinder>& who)
{
    Mutex::Autolock _l(mLock);
    pid_t pid = mRegisteredClients.valueFor(who);
    if (pid != 0) {
        ssize_t index = mClients.indexOfKey(pid);
        if (index >= 0) {
            //LOGD("*** removing client at %d", index);
            Client& client(mClients.editValueAt(index));
            client.revokeAllHeaps(); // not really needed in theory
            mClients.removeItemsAt(index);
            if (mClients.size() == 0) {
                //LOGD("*** was last client closing everything");
                mCallback.clear();
                mAllocator.clear();
                mCurrentAllocator.clear();
                mSMIHeap.clear();
                mREGHeap.clear();
                
                // NOTE: we cannot clear the EBI heap because surfaceflinger
                // itself may be using it, since this is where surfaces
                // are allocated. if we're in the middle of compositing 
                // a surface (even if its process just died), we cannot
                // rip the heap under our feet.
                
                mOwner = NO_OWNER;
            }
        }
    }
}

// ---------------------------------------------------------------------------

sp<GPUHardwareInterface> GPUFactory::getGPU()
{
    return new GPUHardware();
}

// ---------------------------------------------------------------------------
}; // namespace android

