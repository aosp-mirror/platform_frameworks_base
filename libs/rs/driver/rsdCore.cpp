/*
 * Copyright (C) 2011 The Android Open Source Project
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

#include "rsdCore.h"
#include "rsdAllocation.h"
#include "rsdBcc.h"
#include "rsdGL.h"
#include "rsdPath.h"
#include "rsdProgramStore.h"
#include "rsdProgramRaster.h"
#include "rsdProgramVertex.h"
#include "rsdProgramFragment.h"
#include "rsdMesh.h"
#include "rsdSampler.h"
#include "rsdFrameBuffer.h"

#include <malloc.h>
#include "rsContext.h"

#include <sys/types.h>
#include <sys/resource.h>
#include <sched.h>
#include <cutils/properties.h>
#include <cutils/sched_policy.h>
#include <sys/syscall.h>
#include <string.h>
#include <bcc/bcc.h>

using namespace android;
using namespace android::renderscript;

static void Shutdown(Context *rsc);
static void SetPriority(const Context *rsc, int32_t priority);
static void initForEach(outer_foreach_t* forEachLaunch);

static RsdHalFunctions FunctionTable = {
    rsdGLInit,
    rsdGLShutdown,
    rsdGLSetSurface,
    rsdGLSwap,

    Shutdown,
    NULL,
    SetPriority,
    {
        rsdScriptInit,
        rsdScriptInvokeFunction,
        rsdScriptInvokeRoot,
        rsdScriptInvokeForEach,
        rsdScriptInvokeInit,
        rsdScriptInvokeFreeChildren,
        rsdScriptSetGlobalVar,
        rsdScriptSetGlobalBind,
        rsdScriptSetGlobalObj,
        rsdScriptDestroy
    },

    {
        rsdAllocationInit,
        rsdAllocationDestroy,
        rsdAllocationResize,
        rsdAllocationSyncAll,
        rsdAllocationMarkDirty,
        rsdAllocationData1D,
        rsdAllocationData2D,
        rsdAllocationData3D,
        rsdAllocationData1D_alloc,
        rsdAllocationData2D_alloc,
        rsdAllocationData3D_alloc,
        rsdAllocationElementData1D,
        rsdAllocationElementData2D
    },


    {
        rsdProgramStoreInit,
        rsdProgramStoreSetActive,
        rsdProgramStoreDestroy
    },

    {
        rsdProgramRasterInit,
        rsdProgramRasterSetActive,
        rsdProgramRasterDestroy
    },

    {
        rsdProgramVertexInit,
        rsdProgramVertexSetActive,
        rsdProgramVertexDestroy
    },

    {
        rsdProgramFragmentInit,
        rsdProgramFragmentSetActive,
        rsdProgramFragmentDestroy
    },

    {
        rsdMeshInit,
        rsdMeshDraw,
        rsdMeshDestroy
    },

    {
        rsdPathInitStatic,
        rsdPathInitDynamic,
        rsdPathDraw,
        rsdPathDestroy
    },

    {
        rsdSamplerInit,
        rsdSamplerDestroy
    },

    {
        rsdFrameBufferInit,
        rsdFrameBufferSetActive,
        rsdFrameBufferDestroy
    },

};

pthread_key_t rsdgThreadTLSKey = 0;
uint32_t rsdgThreadTLSKeyCount = 0;
pthread_mutex_t rsdgInitMutex = PTHREAD_MUTEX_INITIALIZER;


static void * HelperThreadProc(void *vrsc) {
    Context *rsc = static_cast<Context *>(vrsc);
    RsdHal *dc = (RsdHal *)rsc->mHal.drv;


    uint32_t idx = (uint32_t)android_atomic_inc(&dc->mWorkers.mLaunchCount);

    //ALOGV("RS helperThread starting %p idx=%i", rsc, idx);

    dc->mWorkers.mLaunchSignals[idx].init();
    dc->mWorkers.mNativeThreadId[idx] = gettid();

    int status = pthread_setspecific(rsdgThreadTLSKey, &dc->mTlsStruct);
    if (status) {
        ALOGE("pthread_setspecific %i", status);
    }

#if 0
    typedef struct {uint64_t bits[1024 / 64]; } cpu_set_t;
    cpu_set_t cpuset;
    memset(&cpuset, 0, sizeof(cpuset));
    cpuset.bits[idx / 64] |= 1ULL << (idx % 64);
    int ret = syscall(241, rsc->mWorkers.mNativeThreadId[idx],
              sizeof(cpuset), &cpuset);
    ALOGE("SETAFFINITY ret = %i %s", ret, EGLUtils::strerror(ret));
#endif

    while (!dc->mExit) {
        dc->mWorkers.mLaunchSignals[idx].wait();
        if (dc->mWorkers.mLaunchCallback) {
           dc->mWorkers.mLaunchCallback(dc->mWorkers.mLaunchData, idx);
        }
        android_atomic_dec(&dc->mWorkers.mRunningCount);
        dc->mWorkers.mCompleteSignal.set();
    }

    //ALOGV("RS helperThread exited %p idx=%i", rsc, idx);
    return NULL;
}

void rsdLaunchThreads(Context *rsc, WorkerCallback_t cbk, void *data) {
    RsdHal *dc = (RsdHal *)rsc->mHal.drv;

    dc->mWorkers.mLaunchData = data;
    dc->mWorkers.mLaunchCallback = cbk;
    android_atomic_release_store(dc->mWorkers.mCount, &dc->mWorkers.mRunningCount);
    for (uint32_t ct = 0; ct < dc->mWorkers.mCount; ct++) {
        dc->mWorkers.mLaunchSignals[ct].set();
    }
    while (android_atomic_acquire_load(&dc->mWorkers.mRunningCount) != 0) {
        dc->mWorkers.mCompleteSignal.wait();
    }
}

bool rsdHalInit(Context *rsc, uint32_t version_major, uint32_t version_minor) {
    rsc->mHal.funcs = FunctionTable;

    RsdHal *dc = (RsdHal *)calloc(1, sizeof(RsdHal));
    if (!dc) {
        ALOGE("Calloc for driver hal failed.");
        return false;
    }
    rsc->mHal.drv = dc;

    pthread_mutex_lock(&rsdgInitMutex);
    if (!rsdgThreadTLSKeyCount) {
        int status = pthread_key_create(&rsdgThreadTLSKey, NULL);
        if (status) {
            ALOGE("Failed to init thread tls key.");
            pthread_mutex_unlock(&rsdgInitMutex);
            return false;
        }
    }
    rsdgThreadTLSKeyCount++;
    pthread_mutex_unlock(&rsdgInitMutex);

    initForEach(dc->mForEachLaunch);

    dc->mTlsStruct.mContext = rsc;
    dc->mTlsStruct.mScript = NULL;
    int status = pthread_setspecific(rsdgThreadTLSKey, &dc->mTlsStruct);
    if (status) {
        ALOGE("pthread_setspecific %i", status);
    }


    int cpu = sysconf(_SC_NPROCESSORS_ONLN);
    ALOGV("%p Launching thread(s), CPUs %i", rsc, cpu);
    if (cpu < 2) cpu = 0;

    dc->mWorkers.mCount = (uint32_t)cpu;
    dc->mWorkers.mThreadId = (pthread_t *) calloc(dc->mWorkers.mCount, sizeof(pthread_t));
    dc->mWorkers.mNativeThreadId = (pid_t *) calloc(dc->mWorkers.mCount, sizeof(pid_t));
    dc->mWorkers.mLaunchSignals = new Signal[dc->mWorkers.mCount];
    dc->mWorkers.mLaunchCallback = NULL;

    dc->mWorkers.mCompleteSignal.init();

    android_atomic_release_store(dc->mWorkers.mCount, &dc->mWorkers.mRunningCount);
    android_atomic_release_store(0, &dc->mWorkers.mLaunchCount);

    pthread_attr_t threadAttr;
    status = pthread_attr_init(&threadAttr);
    if (status) {
        ALOGE("Failed to init thread attribute.");
        return false;
    }

    for (uint32_t ct=0; ct < dc->mWorkers.mCount; ct++) {
        status = pthread_create(&dc->mWorkers.mThreadId[ct], &threadAttr, HelperThreadProc, rsc);
        if (status) {
            dc->mWorkers.mCount = ct;
            ALOGE("Created fewer than expected number of RS threads.");
            break;
        }
    }
    while (android_atomic_acquire_load(&dc->mWorkers.mRunningCount) != 0) {
        usleep(100);
    }

    pthread_attr_destroy(&threadAttr);
    return true;
}


void SetPriority(const Context *rsc, int32_t priority) {
    RsdHal *dc = (RsdHal *)rsc->mHal.drv;
    for (uint32_t ct=0; ct < dc->mWorkers.mCount; ct++) {
        setpriority(PRIO_PROCESS, dc->mWorkers.mNativeThreadId[ct], priority);
    }
}

void Shutdown(Context *rsc) {
    RsdHal *dc = (RsdHal *)rsc->mHal.drv;

    dc->mExit = true;
    dc->mWorkers.mLaunchData = NULL;
    dc->mWorkers.mLaunchCallback = NULL;
    android_atomic_release_store(dc->mWorkers.mCount, &dc->mWorkers.mRunningCount);
    for (uint32_t ct = 0; ct < dc->mWorkers.mCount; ct++) {
        dc->mWorkers.mLaunchSignals[ct].set();
    }
    int status;
    void *res;
    for (uint32_t ct = 0; ct < dc->mWorkers.mCount; ct++) {
        status = pthread_join(dc->mWorkers.mThreadId[ct], &res);
    }
    rsAssert(android_atomic_acquire_load(&dc->mWorkers.mRunningCount) == 0);

    // Global structure cleanup.
    pthread_mutex_lock(&rsdgInitMutex);
    --rsdgThreadTLSKeyCount;
    if (!rsdgThreadTLSKeyCount) {
        pthread_key_delete(rsdgThreadTLSKey);
    }
    pthread_mutex_unlock(&rsdgInitMutex);

}

static void rsdForEach17(const void *vRoot,
        const android::renderscript::RsForEachStubParamStruct *p,
                                uint32_t x1, uint32_t x2,
                                uint32_t instep, uint32_t outstep) {
    typedef void (*fe)(const void *, uint32_t);
    (*(fe*)vRoot)(p->in, p->y);
}

static void rsdForEach18(const void *vRoot,
        const android::renderscript::RsForEachStubParamStruct *p,
                                uint32_t x1, uint32_t x2,
                                uint32_t instep, uint32_t outstep) {
    typedef void (*fe)(void *, uint32_t);
    (*(fe*)vRoot)(p->out, p->y);
}

static void rsdForEach19(const void *vRoot,
        const android::renderscript::RsForEachStubParamStruct *p,
                                uint32_t x1, uint32_t x2,
                                uint32_t instep, uint32_t outstep) {
    typedef void (*fe)(const void *, void *, uint32_t);
    (*(fe*)vRoot)(p->in, p->out, p->y);
}

static void rsdForEach21(const void *vRoot,
        const android::renderscript::RsForEachStubParamStruct *p,
                                uint32_t x1, uint32_t x2,
                                uint32_t instep, uint32_t outstep) {
    typedef void (*fe)(const void *, const void *, uint32_t);
    (*(fe*)vRoot)(p->in, p->usr, p->y);
}

static void rsdForEach22(const void *vRoot,
        const android::renderscript::RsForEachStubParamStruct *p,
                                uint32_t x1, uint32_t x2,
                                uint32_t instep, uint32_t outstep) {
    typedef void (*fe)(void *, const void *, uint32_t);
    (*(fe*)vRoot)(p->out, p->usr, p->y);
}

static void rsdForEach23(const void *vRoot,
        const android::renderscript::RsForEachStubParamStruct *p,
                                uint32_t x1, uint32_t x2,
                                uint32_t instep, uint32_t outstep) {
    typedef void (*fe)(const void *, void *, const void *, uint32_t);
    (*(fe*)vRoot)(p->in, p->out, p->usr, p->y);
}

static void rsdForEach25(const void *vRoot,
        const android::renderscript::RsForEachStubParamStruct *p,
                                uint32_t x1, uint32_t x2,
                                uint32_t instep, uint32_t outstep) {
    typedef void (*fe)(const void *, uint32_t, uint32_t);
    const uint8_t *pin = (const uint8_t *)p->in;
    uint32_t y = p->y;
    for (uint32_t x = x1; x < x2; x++) {
        (*(fe*)vRoot)(pin, x, y);
        pin += instep;
    }
}

static void rsdForEach26(const void *vRoot,
        const android::renderscript::RsForEachStubParamStruct *p,
                                uint32_t x1, uint32_t x2,
                                uint32_t instep, uint32_t outstep) {
    typedef void (*fe)(void *, uint32_t, uint32_t);
    uint8_t *pout = (uint8_t *)p->out;
    uint32_t y = p->y;
    for (uint32_t x = x1; x < x2; x++) {
        (*(fe*)vRoot)(pout, x, y);
        pout += outstep;
    }
}

static void rsdForEach27(const void *vRoot,
        const android::renderscript::RsForEachStubParamStruct *p,
                                uint32_t x1, uint32_t x2,
                                uint32_t instep, uint32_t outstep) {
    typedef void (*fe)(const void *, void *, uint32_t, uint32_t);
    uint8_t *pout = (uint8_t *)p->out;
    const uint8_t *pin = (const uint8_t *)p->in;
    uint32_t y = p->y;
    for (uint32_t x = x1; x < x2; x++) {
        (*(fe*)vRoot)(pin, pout, x, y);
        pin += instep;
        pout += outstep;
    }
}

static void rsdForEach29(const void *vRoot,
        const android::renderscript::RsForEachStubParamStruct *p,
                                uint32_t x1, uint32_t x2,
                                uint32_t instep, uint32_t outstep) {
    typedef void (*fe)(const void *, const void *, uint32_t, uint32_t);
    const uint8_t *pin = (const uint8_t *)p->in;
    const void *usr = p->usr;
    const uint32_t y = p->y;
    for (uint32_t x = x1; x < x2; x++) {
        (*(fe*)vRoot)(pin, usr, x, y);
        pin += instep;
    }
}

static void rsdForEach30(const void *vRoot,
        const android::renderscript::RsForEachStubParamStruct *p,
                                uint32_t x1, uint32_t x2,
                                uint32_t instep, uint32_t outstep) {
    typedef void (*fe)(void *, const void *, uint32_t, uint32_t);
    uint8_t *pout = (uint8_t *)p->out;
    const void *usr = p->usr;
    const uint32_t y = p->y;
    for (uint32_t x = x1; x < x2; x++) {
        (*(fe*)vRoot)(pout, usr, x, y);
        pout += outstep;
    }
}

static void rsdForEach31(const void *vRoot,
        const android::renderscript::RsForEachStubParamStruct *p,
                                uint32_t x1, uint32_t x2,
                                uint32_t instep, uint32_t outstep) {
    typedef void (*fe)(const void *, void *, const void *, uint32_t, uint32_t);
    uint8_t *pout = (uint8_t *)p->out;
    const uint8_t *pin = (const uint8_t *)p->in;
    const void *usr = p->usr;
    const uint32_t y = p->y;
    for (uint32_t x = x1; x < x2; x++) {
        (*(fe*)vRoot)(pin, pout, usr, x, y);
        pin += instep;
        pout += outstep;
    }
}


static void initForEach(outer_foreach_t* forEachLaunch) {
    rsAssert(forEachLaunch);
    forEachLaunch[0x00] = NULL;
    forEachLaunch[0x01] = rsdForEach31; // in
    forEachLaunch[0x02] = rsdForEach30; //     out
    forEachLaunch[0x03] = rsdForEach31; // in, out
    forEachLaunch[0x04] = NULL;
    forEachLaunch[0x05] = rsdForEach29;  // in,      usr
    forEachLaunch[0x06] = rsdForEach30; //     out, usr
    forEachLaunch[0x07] = rsdForEach31; // in, out, usr
    forEachLaunch[0x08] = NULL;
    forEachLaunch[0x09] = rsdForEach25; // in,           x
    forEachLaunch[0x0a] = rsdForEach26; //     out,      x
    forEachLaunch[0x0b] = rsdForEach27; // in, out,      x
    forEachLaunch[0x0c] = NULL;
    forEachLaunch[0x0d] = rsdForEach29; // in,      usr, x
    forEachLaunch[0x0e] = rsdForEach30; //     out, usr, x
    forEachLaunch[0x0f] = rsdForEach31; // in, out, usr, x
    forEachLaunch[0x10] = NULL;
    forEachLaunch[0x11] = rsdForEach17; // in               y
    forEachLaunch[0x12] = rsdForEach18; //     out,         y
    forEachLaunch[0x13] = rsdForEach19; // in, out,         y
    forEachLaunch[0x14] = NULL;
    forEachLaunch[0x15] = rsdForEach21; // in,      usr,    y
    forEachLaunch[0x16] = rsdForEach22; //     out, usr,    y
    forEachLaunch[0x17] = rsdForEach23; // in, out, usr,    y
    forEachLaunch[0x18] = NULL;
    forEachLaunch[0x19] = rsdForEach25; // in,           x, y
    forEachLaunch[0x1a] = rsdForEach26; //     out,      x, y
    forEachLaunch[0x1b] = rsdForEach27; // in, out,      x, y
    forEachLaunch[0x1c] = NULL;
    forEachLaunch[0x1d] = rsdForEach29; // in,      usr, x, y
    forEachLaunch[0x1e] = rsdForEach30; //     out, usr, x, y
    forEachLaunch[0x1f] = rsdForEach31; // in, out, usr, x, y
}

