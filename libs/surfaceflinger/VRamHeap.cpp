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

#include <GLES/eglnatives.h>

#include "GPUHardware/GPUHardware.h"
#include "SurfaceFlinger.h"
#include "VRamHeap.h"

#if HAVE_ANDROID_OS
#include <linux/android_pmem.h>
#endif


namespace android {

// ---------------------------------------------------------------------------

/*
 * Amount of memory we reserve for surface, per client in PMEM
 * (PMEM is used for 2D acceleration)
 * 8 MB of address space per client should be enough.
 */
static const int PMEM_SIZE = int(8 * 1024 * 1024);

int SurfaceHeapManager::global_pmem_heap = 0;

// ---------------------------------------------------------------------------

SurfaceHeapManager::SurfaceHeapManager(const sp<SurfaceFlinger>& flinger, 
        size_t clientHeapSize)
    : mFlinger(flinger), mClientHeapSize(clientHeapSize)
{
    SurfaceHeapManager::global_pmem_heap = 1;
}

SurfaceHeapManager::~SurfaceHeapManager()
{
}

void SurfaceHeapManager::onFirstRef()
{
    if (global_pmem_heap) {
        const char* device = "/dev/pmem";
        mPMemHeap = new PMemHeap(device, PMEM_SIZE);
        if (mPMemHeap->base() == MAP_FAILED) {
            mPMemHeap.clear();
            global_pmem_heap = 0;
        }
    }
}

sp<MemoryDealer> SurfaceHeapManager::createHeap(
        uint32_t flags,
        pid_t client_pid,
        const sp<MemoryDealer>& defaultAllocator)
{
    sp<MemoryDealer> dealer; 

    if (flags & ISurfaceComposer::eGPU) {
        // don't grant GPU memory if GPU is disabled
        char value[PROPERTY_VALUE_MAX];
        property_get("debug.egl.hw", value, "1");
        if (atoi(value) == 0) {
            flags &= ~ISurfaceComposer::eGPU;
        }
    }

    if (flags & ISurfaceComposer::eGPU) {
        // FIXME: this is msm7201A specific, where gpu surfaces may not be secure
        if (!(flags & ISurfaceComposer::eSecure)) {
            // if GPU doesn't work, we try eHardware
            flags |= ISurfaceComposer::eHardware;
            // asked for GPU memory, try that first
            dealer = mFlinger->getGPU()->request(client_pid);
        }
    }

    if (dealer == NULL) {
        if (defaultAllocator != NULL)
            // if a default allocator is given, use that
            dealer = defaultAllocator;
    }
    
    if (dealer == NULL) {
        // always try h/w accelerated memory first
        if (global_pmem_heap) {
            const sp<PMemHeap>& heap(mPMemHeap);
            if (dealer == NULL && heap != NULL) {
                dealer = new MemoryDealer( 
                        heap->createClientHeap(),
                        heap->getAllocator());
            }
        }
    }

    if (dealer == NULL) {
        // return the ashmem allocator (software rendering)
        dealer = new MemoryDealer(mClientHeapSize, 0, "SFNativeHeap");
    }
    return dealer;
}

sp<SimpleBestFitAllocator> SurfaceHeapManager::getAllocator(int type) const 
{
    Mutex::Autolock _l(mLock);
    sp<SimpleBestFitAllocator> allocator;

    // this is only used for debugging
    switch (type) {
        case NATIVE_MEMORY_TYPE_PMEM:
            if (mPMemHeap != 0) {
                allocator = mPMemHeap->getAllocator();
            }
            break;
    }
    return allocator;
}

// ---------------------------------------------------------------------------

PMemHeap::PMemHeap(const char* const device, size_t size, size_t reserved)
    : MemoryHeapBase(device, size)
{
    //LOGD("%s, %p, mFD=%d", __PRETTY_FUNCTION__, this, heapID());
    if (base() != MAP_FAILED) {
        //LOGD("%s, %u bytes", device, virtualSize());
        if (reserved == 0)
            reserved = virtualSize();
        mAllocator = new SimpleBestFitAllocator(reserved);
    }
}

PMemHeap::~PMemHeap() {
    //LOGD("%s, %p, mFD=%d", __PRETTY_FUNCTION__, this, heapID());
}

sp<MemoryHeapPmem> PMemHeap::createClientHeap() {
    sp<MemoryHeapBase> parentHeap(this);
    return new MemoryHeapPmem(parentHeap);
}

// ---------------------------------------------------------------------------
}; // namespace android
