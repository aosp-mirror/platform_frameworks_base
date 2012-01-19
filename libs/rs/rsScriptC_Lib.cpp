/*
 * Copyright (C) 2009 The Android Open Source Project
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

#include "rsContext.h"
#include "rsScriptC.h"
#include "rsMatrix4x4.h"
#include "rsMatrix3x3.h"
#include "rsMatrix2x2.h"

#include "utils/Timers.h"

#include <time.h>

using namespace android;
using namespace android::renderscript;


namespace android {
namespace renderscript {


//////////////////////////////////////////////////////////////////////////////
// Math routines
//////////////////////////////////////////////////////////////////////////////

#if 0
static float SC_sinf_fast(float x) {
    const float A =   1.0f / (2.0f * M_PI);
    const float B = -16.0f;
    const float C =   8.0f;

    // scale angle for easy argument reduction
    x *= A;

    if (fabsf(x) >= 0.5f) {
        // argument reduction
        x = x - ceilf(x + 0.5f) + 1.0f;
    }

    const float y = B * x * fabsf(x) + C * x;
    return 0.2215f * (y * fabsf(y) - y) + y;
}

static float SC_cosf_fast(float x) {
    x += float(M_PI / 2);

    const float A =   1.0f / (2.0f * M_PI);
    const float B = -16.0f;
    const float C =   8.0f;

    // scale angle for easy argument reduction
    x *= A;

    if (fabsf(x) >= 0.5f) {
        // argument reduction
        x = x - ceilf(x + 0.5f) + 1.0f;
    }

    const float y = B * x * fabsf(x) + C * x;
    return 0.2215f * (y * fabsf(y) - y) + y;
}
#endif

//////////////////////////////////////////////////////////////////////////////
// Time routines
//////////////////////////////////////////////////////////////////////////////

time_t rsrTime(Context *rsc, Script *sc, time_t *timer) {
    return time(timer);
}

tm* rsrLocalTime(Context *rsc, Script *sc, tm *local, time_t *timer) {
    if (!local) {
      return NULL;
    }

    // The native localtime function is not thread-safe, so we
    // have to apply locking for proper behavior in RenderScript.
    pthread_mutex_lock(&rsc->gLibMutex);
    tm *tmp = localtime(timer);
    memcpy(local, tmp, sizeof(*tmp));
    pthread_mutex_unlock(&rsc->gLibMutex);
    return local;
}

int64_t rsrUptimeMillis(Context *rsc, Script *sc) {
    return nanoseconds_to_milliseconds(systemTime(SYSTEM_TIME_MONOTONIC));
}

int64_t rsrUptimeNanos(Context *rsc, Script *sc) {
    return systemTime(SYSTEM_TIME_MONOTONIC);
}

float rsrGetDt(Context *rsc, Script *sc) {
    int64_t l = sc->mEnviroment.mLastDtTime;
    sc->mEnviroment.mLastDtTime = systemTime(SYSTEM_TIME_MONOTONIC);
    return ((float)(sc->mEnviroment.mLastDtTime - l)) / 1.0e9;
}

//////////////////////////////////////////////////////////////////////////////
//
//////////////////////////////////////////////////////////////////////////////

void rsrSetObject(const Context *rsc, const Script *sc, ObjectBase **dst, ObjectBase * src) {
    //ALOGE("rsiSetObject  %p,%p  %p", vdst, *vdst, vsrc);
    if (src) {
        CHECK_OBJ(src);
        src->incSysRef();
    }
    if (dst[0]) {
        CHECK_OBJ(dst[0]);
        dst[0]->decSysRef();
    }
    *dst = src;
}

void rsrClearObject(const Context *rsc, const Script *sc, ObjectBase **dst) {
    //ALOGE("rsiClearObject  %p,%p", vdst, *vdst);
    if (dst[0]) {
        CHECK_OBJ(dst[0]);
        dst[0]->decSysRef();
    }
    *dst = NULL;
}

bool rsrIsObject(const Context *rsc, const Script *sc, const ObjectBase *src) {
    return src != NULL;
}


uint32_t rsrToClient(Context *rsc, Script *sc, int cmdID, void *data, int len) {
    //ALOGE("SC_toClient %i %i %i", cmdID, len);
    return rsc->sendMessageToClient(data, RS_MESSAGE_TO_CLIENT_USER, cmdID, len, false);
}

uint32_t rsrToClientBlocking(Context *rsc, Script *sc, int cmdID, void *data, int len) {
    //ALOGE("SC_toClientBlocking %i %i", cmdID, len);
    return rsc->sendMessageToClient(data, RS_MESSAGE_TO_CLIENT_USER, cmdID, len, true);
}


void rsrForEach(Context *rsc, Script *sc,
                Script *target,
                Allocation *in, Allocation *out,
                const void *usr, uint32_t usrBytes,
                const RsScriptCall *call) {
    target->runForEach(rsc, in, out, usr, usrBytes, call);
}

void rsrAllocationSyncAll(Context *rsc, Script *sc, Allocation *a, RsAllocationUsageType usage) {
    a->syncAll(rsc, usage);
}

void rsrAllocationCopy1DRange(Context *rsc, Allocation *dstAlloc,
                              uint32_t dstOff,
                              uint32_t dstMip,
                              uint32_t count,
                              Allocation *srcAlloc,
                              uint32_t srcOff, uint32_t srcMip) {
    rsi_AllocationCopy2DRange(rsc, dstAlloc, dstOff, 0,
                              dstMip, 0, count, 1,
                              srcAlloc, srcOff, 0, srcMip, 0);
}

void rsrAllocationCopy2DRange(Context *rsc, Allocation *dstAlloc,
                              uint32_t dstXoff, uint32_t dstYoff,
                              uint32_t dstMip, uint32_t dstFace,
                              uint32_t width, uint32_t height,
                              Allocation *srcAlloc,
                              uint32_t srcXoff, uint32_t srcYoff,
                              uint32_t srcMip, uint32_t srcFace) {
    rsi_AllocationCopy2DRange(rsc, dstAlloc, dstXoff, dstYoff,
                              dstMip, dstFace, width, height,
                              srcAlloc, srcXoff, srcYoff, srcMip, srcFace);
}

const Allocation * rsrGetAllocation(Context *rsc, Script *s, const void *ptr) {
    ScriptC *sc = (ScriptC *)s;
    return sc->ptrToAllocation(ptr);
}

}
}

