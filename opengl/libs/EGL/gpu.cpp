/* 
 ** Copyright 2007, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License"); 
 ** you may not use this file except in compliance with the License. 
 ** You may obtain a copy of the License at 
 **
 **     http://www.apache.org/licenses/LICENSE-2.0 
 **
 ** Unless required by applicable law or agreed to in writing, software 
 ** distributed under the License is distributed on an "AS IS" BASIS, 
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 ** See the License for the specific language governing permissions and 
 ** limitations under the License.
 */

#define LOG_TAG "EGL"

#include <ctype.h>
#include <string.h>
#include <errno.h>

#include <sys/ioctl.h>

#if HAVE_ANDROID_OS
#include <linux/android_pmem.h>
#endif

#include <cutils/log.h>
#include <cutils/properties.h>

#include <utils/IMemory.h>
#include <utils/threads.h>
#include <utils/IServiceManager.h>
#include <utils/IPCThreadState.h>
#include <utils/Parcel.h>

#include <ui/EGLDisplaySurface.h>
#include <ui/ISurfaceComposer.h>

#include "hooks.h"
#include "egl_impl.h"

// ----------------------------------------------------------------------------
namespace android {
// ----------------------------------------------------------------------------

/*
 * we provide our own allocators for the GPU regions, these
 * allocators go through surfaceflinger 
 */

static Mutex                            gRegionsLock;
static request_gpu_t                    gRegions;
static sp<ISurfaceComposer>             gSurfaceManager;
ISurfaceComposer*                       GLES_localSurfaceManager = 0;

extern egl_connection_t gEGLImpl[2];

const sp<ISurfaceComposer>& getSurfaceFlinger()
{
    Mutex::Autolock _l(gRegionsLock);

    /*
     * There is a little bit of voodoo magic here. We want to access
     * surfaceflinger for allocating GPU regions, however, when we are
     * running as part of surfaceflinger, we want to bypass the
     * service manager because surfaceflinger might not be registered yet.
     * SurfaceFlinger will populate "GLES_localSurfaceManager" with its
     * own address, so we can just use that.
     */
    if (gSurfaceManager == 0) {
        if (GLES_localSurfaceManager) {
            // we're running in SurfaceFlinger's context
            gSurfaceManager =  GLES_localSurfaceManager;
        } else {
            // we're a remote process or not part of surfaceflinger,
            // go through the service manager
            sp<IServiceManager> sm = defaultServiceManager();
            if (sm != NULL) {
                sp<IBinder> binder = sm->getService(String16("SurfaceFlinger"));
                gSurfaceManager = interface_cast<ISurfaceComposer>(binder);
            }
        }
    }
    return gSurfaceManager;
}

class GPURevokeRequester : public BnGPUCallback
{
public:
    virtual void gpuLost() {
        LOGD("CONTEXT_LOST: Releasing GPU upon request from SurfaceFlinger.");
        gEGLImpl[IMPL_HARDWARE].hooks = &gHooks[IMPL_CONTEXT_LOST];
    }
};

static sp<GPURevokeRequester> gRevokerCallback;


request_gpu_t* gpu_acquire(void* user)
{
    sp<ISurfaceComposer> server( getSurfaceFlinger() );

    Mutex::Autolock _l(gRegionsLock);
    if (server == NULL) {
        return 0;
    }
    
    ISurfaceComposer::gpu_info_t info;
    
    if (gRevokerCallback == 0)
        gRevokerCallback = new GPURevokeRequester();

    status_t err = server->requestGPU(gRevokerCallback, &info);
    if (err != NO_ERROR) {
        LOGD("requestGPU returned %d", err);
        return 0;
    }

    bool failed = false;
    request_gpu_t* gpu = &gRegions;
    memset(gpu, 0, sizeof(*gpu));
    
    if (info.regs != 0) {
        sp<IMemoryHeap> heap(info.regs->getMemory());
        if (heap != 0) {
            int fd = heap->heapID();
            gpu->regs.fd = fd;
            gpu->regs.base = info.regs->pointer(); 
            gpu->regs.size = info.regs->size(); 
            gpu->regs.user = info.regs.get();
#if HAVE_ANDROID_OS
            struct pmem_region region;
            if (ioctl(fd, PMEM_GET_PHYS, &region) >= 0)
                gpu->regs.phys = (void*)region.offset;
#endif
            info.regs->incStrong(gpu);
        } else {
            LOGE("GPU register handle %p is invalid!", info.regs.get());
            failed = true;
        }
    }

    for (size_t i=0 ; i<info.count && !failed ; i++) {
        sp<IMemory>& region(info.regions[i].region);
        if (region != 0) {
            sp<IMemoryHeap> heap(region->getMemory());
            if (heap != 0) {
                const int fd = heap->heapID();
                gpu->gpu[i].fd = fd;
                gpu->gpu[i].base = region->pointer(); 
                gpu->gpu[i].size = region->size(); 
                gpu->gpu[i].user = region.get();
                gpu->gpu[i].offset = info.regions[i].reserved;
#if HAVE_ANDROID_OS
                struct pmem_region reg;
                if (ioctl(fd, PMEM_GET_PHYS, &reg) >= 0)
                    gpu->gpu[i].phys = (void*)reg.offset;
#endif
                region->incStrong(gpu);
            } else {
                LOGE("GPU region handle [%d, %p] is invalid!", i, region.get());
                failed = true;
            }
        }
    }
    
    if (failed) {
        // something went wrong, clean up everything!
        if (gpu->regs.user) {
            static_cast<IMemory*>(gpu->regs.user)->decStrong(gpu);
            for (size_t i=0 ; i<info.count ; i++) {
                if (gpu->gpu[i].user) {
                    static_cast<IMemory*>(gpu->gpu[i].user)->decStrong(gpu);
                }
            }
        }
    }
    
    gpu->count = info.count;
    return gpu;
}

int gpu_release(void*, request_gpu_t* gpu)
{
    sp<IMemory> regs;

    { // scope for lock
        Mutex::Autolock _l(gRegionsLock);
        regs = static_cast<IMemory*>(gpu->regs.user);   
        gpu->regs.user = 0;
        if (regs != 0) regs->decStrong(gpu);
        
        for (int i=0 ; i<gpu->count ; i++) {
            sp<IMemory> r(static_cast<IMemory*>(gpu->gpu[i].user));
            gpu->gpu[i].user = 0;
            if (r != 0) r->decStrong(gpu);
        }
    }
    
    // there is a special transaction to relinquish the GPU
    // (it will happen automatically anyway if we don't do this)
    Parcel data, reply;
    // NOTE: this transaction does not require an interface token
    regs->asBinder()->transact(1000, data, &reply);
    return 1;
}

// ----------------------------------------------------------------------------
}; // namespace android
// ----------------------------------------------------------------------------
