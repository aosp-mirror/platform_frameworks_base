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
#include "rsMatrix.h"
#include "utils/Timers.h"
#include "utils/StopWatch.h"

#include <GLES/gl.h>
#include <GLES/glext.h>

#include <bcc/bcc.h>

using namespace android;
using namespace android::renderscript;

#define GET_TLS()  Context::ScriptTLSStruct * tls = \
    (Context::ScriptTLSStruct *)pthread_getspecific(Context::gThreadTLSKey); \
    Context * rsc = tls->mContext; \
    ScriptC * sc = (ScriptC *) tls->mScript

ScriptC::ScriptC(Context *rsc) : Script(rsc) {
}

ScriptC::~ScriptC() {
    mRSC->mHal.funcs.script.destroy(mRSC, this);

    //free(mEnviroment.mScriptText);
    //mEnviroment.mScriptText = NULL;
}

void ScriptC::setupScript(Context *rsc) {
    mEnviroment.mStartTimeMillis
                = nanoseconds_to_milliseconds(systemTime(SYSTEM_TIME_MONOTONIC));

    for (uint32_t ct=0; ct < mHal.info.exportedVariableCount; ct++) {
        if (mSlots[ct].get() && !mTypes[ct].get()) {
            mTypes[ct].set(mSlots[ct]->getType());
        }

        if (!mTypes[ct].get())
            continue;
        void *ptr = NULL;
        if (mSlots[ct].get()) {
            ptr = mSlots[ct]->getPtr();
        }

        rsc->mHal.funcs.script.setGlobalBind(rsc, this, ct, ptr);
    }
}

const Allocation *ScriptC::ptrToAllocation(const void *ptr) const {
    //LOGE("ptr to alloc %p", ptr);
    if (!ptr) {
        return NULL;
    }
    for (uint32_t ct=0; ct < mHal.info.exportedVariableCount; ct++) {
        if (!mSlots[ct].get())
            continue;
        if (mSlots[ct]->getPtr() == ptr) {
            return mSlots[ct].get();
        }
    }
    LOGE("ScriptC::ptrToAllocation, failed to find %p", ptr);
    return NULL;
}

Script * ScriptC::setTLS(Script *sc) {
    Context::ScriptTLSStruct * tls = (Context::ScriptTLSStruct *)
                                  pthread_getspecific(Context::gThreadTLSKey);
    rsAssert(tls);
    Script *old = tls->mScript;
    tls->mScript = sc;
    return old;
}

void ScriptC::setupGLState(Context *rsc) {
    if (mEnviroment.mFragmentStore.get()) {
        rsc->setProgramStore(mEnviroment.mFragmentStore.get());
    }
    if (mEnviroment.mFragment.get()) {
        rsc->setProgramFragment(mEnviroment.mFragment.get());
    }
    if (mEnviroment.mVertex.get()) {
        rsc->setProgramVertex(mEnviroment.mVertex.get());
    }
    if (mEnviroment.mRaster.get()) {
        rsc->setProgramRaster(mEnviroment.mRaster.get());
    }
}

uint32_t ScriptC::run(Context *rsc) {
    if (mHal.info.root == NULL) {
        rsc->setError(RS_ERROR_BAD_SCRIPT, "Attempted to run bad script");
        return 0;
    }

    setupGLState(rsc);
    setupScript(rsc);

    uint32_t ret = 0;
    Script * oldTLS = setTLS(this);

    if (rsc->props.mLogScripts) {
        LOGV("%p ScriptC::run invoking root,  ptr %p", rsc, mHal.info.root);
    }

    ret = mHal.info.root();

    if (rsc->props.mLogScripts) {
        LOGV("%p ScriptC::run invoking complete, ret=%i", rsc, ret);
    }

    setTLS(oldTLS);
    return ret;
}

typedef struct {
    Context *rsc;
    ScriptC *script;
    const Allocation * ain;
    Allocation * aout;
    const void * usr;

    uint32_t mSliceSize;
    volatile int mSliceNum;

    const uint8_t *ptrIn;
    uint32_t eStrideIn;
    uint8_t *ptrOut;
    uint32_t eStrideOut;

    uint32_t xStart;
    uint32_t xEnd;
    uint32_t yStart;
    uint32_t yEnd;
    uint32_t zStart;
    uint32_t zEnd;
    uint32_t arrayStart;
    uint32_t arrayEnd;

    uint32_t dimX;
    uint32_t dimY;
    uint32_t dimZ;
    uint32_t dimArray;
} MTLaunchStruct;
typedef int (*rs_t)(const void *, void *, const void *, uint32_t, uint32_t, uint32_t, uint32_t);

static void wc_xy(void *usr, uint32_t idx) {
    MTLaunchStruct *mtls = (MTLaunchStruct *)usr;

    while (1) {
        uint32_t slice = (uint32_t)android_atomic_inc(&mtls->mSliceNum);
        uint32_t yStart = mtls->yStart + slice * mtls->mSliceSize;
        uint32_t yEnd = yStart + mtls->mSliceSize;
        yEnd = rsMin(yEnd, mtls->yEnd);
        if (yEnd <= yStart) {
            return;
        }

        //LOGE("usr idx %i, x %i,%i  y %i,%i", idx, mtls->xStart, mtls->xEnd, yStart, yEnd);
        //LOGE("usr ptr in %p,  out %p", mtls->ptrIn, mtls->ptrOut);
        for (uint32_t y = yStart; y < yEnd; y++) {
            uint32_t offset = mtls->dimX * y;
            uint8_t *xPtrOut = mtls->ptrOut + (mtls->eStrideOut * offset);
            const uint8_t *xPtrIn = mtls->ptrIn + (mtls->eStrideIn * offset);

            for (uint32_t x = mtls->xStart; x < mtls->xEnd; x++) {
                ((rs_t)mtls->script->mHal.info.root) (xPtrIn, xPtrOut, mtls->usr, x, y, 0, 0);
                xPtrIn += mtls->eStrideIn;
                xPtrOut += mtls->eStrideOut;
            }
        }
    }
}

static void wc_x(void *usr, uint32_t idx) {
    MTLaunchStruct *mtls = (MTLaunchStruct *)usr;

    while (1) {
        uint32_t slice = (uint32_t)android_atomic_inc(&mtls->mSliceNum);
        uint32_t xStart = mtls->xStart + slice * mtls->mSliceSize;
        uint32_t xEnd = xStart + mtls->mSliceSize;
        xEnd = rsMin(xEnd, mtls->xEnd);
        if (xEnd <= xStart) {
            return;
        }

        //LOGE("usr idx %i, x %i,%i  y %i,%i", idx, mtls->xStart, mtls->xEnd, yStart, yEnd);
        //LOGE("usr ptr in %p,  out %p", mtls->ptrIn, mtls->ptrOut);
        uint8_t *xPtrOut = mtls->ptrOut + (mtls->eStrideOut * xStart);
        const uint8_t *xPtrIn = mtls->ptrIn + (mtls->eStrideIn * xStart);
        for (uint32_t x = xStart; x < xEnd; x++) {
            ((rs_t)mtls->script->mHal.info.root) (xPtrIn, xPtrOut, mtls->usr, x, 0, 0, 0);
            xPtrIn += mtls->eStrideIn;
            xPtrOut += mtls->eStrideOut;
        }
    }
}

void ScriptC::runForEach(Context *rsc,
                         const Allocation * ain,
                         Allocation * aout,
                         const void * usr,
                         const RsScriptCall *sc) {
    MTLaunchStruct mtls;
    memset(&mtls, 0, sizeof(mtls));
    Context::PushState ps(rsc);


    if (ain) {
        mtls.dimX = ain->getType()->getDimX();
        mtls.dimY = ain->getType()->getDimY();
        mtls.dimZ = ain->getType()->getDimZ();
        //mtls.dimArray = ain->getType()->getDimArray();
    } else if (aout) {
        mtls.dimX = aout->getType()->getDimX();
        mtls.dimY = aout->getType()->getDimY();
        mtls.dimZ = aout->getType()->getDimZ();
        //mtls.dimArray = aout->getType()->getDimArray();
    } else {
        rsc->setError(RS_ERROR_BAD_SCRIPT, "rsForEach called with null allocations");
        return;
    }

    if (!sc || (sc->xEnd == 0)) {
        mtls.xEnd = mtls.dimX;
    } else {
        rsAssert(sc->xStart < mtls.dimX);
        rsAssert(sc->xEnd <= mtls.dimX);
        rsAssert(sc->xStart < sc->xEnd);
        mtls.xStart = rsMin(mtls.dimX, sc->xStart);
        mtls.xEnd = rsMin(mtls.dimX, sc->xEnd);
        if (mtls.xStart >= mtls.xEnd) return;
    }

    if (!sc || (sc->yEnd == 0)) {
        mtls.yEnd = mtls.dimY;
    } else {
        rsAssert(sc->yStart < mtls.dimY);
        rsAssert(sc->yEnd <= mtls.dimY);
        rsAssert(sc->yStart < sc->yEnd);
        mtls.yStart = rsMin(mtls.dimY, sc->yStart);
        mtls.yEnd = rsMin(mtls.dimY, sc->yEnd);
        if (mtls.yStart >= mtls.yEnd) return;
    }

    mtls.xEnd = rsMax((uint32_t)1, mtls.xEnd);
    mtls.yEnd = rsMax((uint32_t)1, mtls.yEnd);
    mtls.zEnd = rsMax((uint32_t)1, mtls.zEnd);
    mtls.arrayEnd = rsMax((uint32_t)1, mtls.arrayEnd);

    rsAssert(ain->getType()->getDimZ() == 0);

    setupGLState(rsc);
    setupScript(rsc);
    Script * oldTLS = setTLS(this);

    mtls.rsc = rsc;
    mtls.ain = ain;
    mtls.aout = aout;
    mtls.script = this;
    mtls.usr = usr;
    mtls.mSliceSize = 10;
    mtls.mSliceNum = 0;

    mtls.ptrIn = NULL;
    mtls.eStrideIn = 0;
    if (ain) {
        mtls.ptrIn = (const uint8_t *)ain->getPtr();
        mtls.eStrideIn = ain->getType()->getElementSizeBytes();
    }

    mtls.ptrOut = NULL;
    mtls.eStrideOut = 0;
    if (aout) {
        mtls.ptrOut = (uint8_t *)aout->getPtr();
        mtls.eStrideOut = aout->getType()->getElementSizeBytes();
    }

    if ((rsc->getWorkerPoolSize() > 1) && mHal.info.isThreadable) {
        if (mtls.dimY > 1) {
            rsc->launchThreads(wc_xy, &mtls);
        } else {
            rsc->launchThreads(wc_x, &mtls);
        }

        //LOGE("launch 1");
    } else {
        //LOGE("launch 3");
        for (uint32_t ar = mtls.arrayStart; ar < mtls.arrayEnd; ar++) {
            for (uint32_t z = mtls.zStart; z < mtls.zEnd; z++) {
                for (uint32_t y = mtls.yStart; y < mtls.yEnd; y++) {
                    uint32_t offset = mtls.dimX * mtls.dimY * mtls.dimZ * ar +
                                      mtls.dimX * mtls.dimY * z +
                                      mtls.dimX * y;
                    uint8_t *xPtrOut = mtls.ptrOut + (mtls.eStrideOut * offset);
                    const uint8_t *xPtrIn = mtls.ptrIn + (mtls.eStrideIn * offset);

                    for (uint32_t x = mtls.xStart; x < mtls.xEnd; x++) {
                        ((rs_t)mHal.info.root) (xPtrIn, xPtrOut, usr, x, y, z, ar);
                        xPtrIn += mtls.eStrideIn;
                        xPtrOut += mtls.eStrideOut;
                    }
                }
            }
        }
    }

    setTLS(oldTLS);
}

void ScriptC::Invoke(Context *rsc, uint32_t slot, const void *data, uint32_t len) {
    if (slot >= mHal.info.exportedFunctionCount) {
        rsc->setError(RS_ERROR_BAD_SCRIPT, "Calling invoke on bad script");
        return;
    }
    setupScript(rsc);
    Script * oldTLS = setTLS(this);

    if (rsc->props.mLogScripts) {
        LOGV("%p ScriptC::Invoke invoking slot %i,  ptr %p", rsc, slot, this);
    }
    rsc->mHal.funcs.script.invokeFunction(rsc, this, slot, data, len);

    setTLS(oldTLS);
}

ScriptCState::ScriptCState() {
}

ScriptCState::~ScriptCState() {
}

static void* symbolLookup(void* pContext, char const* name) {
    const ScriptCState::SymbolTable_t *sym;
    ScriptC *s = (ScriptC *)pContext;
    if (!strcmp(name, "__isThreadable")) {
      return (void*) s->mHal.info.isThreadable;
    } else if (!strcmp(name, "__clearThreadable")) {
      s->mHal.info.isThreadable = false;
      return NULL;
    }
    sym = ScriptCState::lookupSymbol(name);
    if (!sym) {
        sym = ScriptCState::lookupSymbolCL(name);
    }
    if (!sym) {
        sym = ScriptCState::lookupSymbolGL(name);
    }
    if (sym) {
        s->mHal.info.isThreadable &= sym->threadable;
        return sym->mPtr;
    }
    LOGE("ScriptC sym lookup failed for %s", name);
    return NULL;
}

#if 0
extern const char rs_runtime_lib_bc[];
extern unsigned rs_runtime_lib_bc_size;
#endif

bool ScriptC::runCompiler(Context *rsc,
                          const char *resName,
                          const char *cacheDir,
                          const uint8_t *bitcode,
                          size_t bitcodeLen) {

    //LOGE("runCompiler %p %p %p %p %p %i", rsc, this, resName, cacheDir, bitcode, bitcodeLen);

    rsc->mHal.funcs.script.scriptInit(rsc, this, resName, cacheDir, bitcode, bitcodeLen, 0, symbolLookup);

    mEnviroment.mFragment.set(rsc->getDefaultProgramFragment());
    mEnviroment.mVertex.set(rsc->getDefaultProgramVertex());
    mEnviroment.mFragmentStore.set(rsc->getDefaultProgramStore());
    mEnviroment.mRaster.set(rsc->getDefaultProgramRaster());

    rsc->mHal.funcs.script.invokeInit(rsc, this);

    for (size_t i=0; i < mHal.info.exportedPragmaCount; ++i) {
        const char * key = mHal.info.exportedPragmaKeyList[i];
        const char * value = mHal.info.exportedPragmaValueList[i];
        //LOGE("pragma %s %s", keys[i], values[i]);
        if (!strcmp(key, "version")) {
            if (!strcmp(value, "1")) {
                continue;
            }
            LOGE("Invalid version pragma value: %s\n", value);
            return false;
        }

        if (!strcmp(key, "stateVertex")) {
            if (!strcmp(value, "default")) {
                continue;
            }
            if (!strcmp(value, "parent")) {
                mEnviroment.mVertex.clear();
                continue;
            }
            LOGE("Unrecognized value %s passed to stateVertex", value);
            return false;
        }

        if (!strcmp(key, "stateRaster")) {
            if (!strcmp(value, "default")) {
                continue;
            }
            if (!strcmp(value, "parent")) {
                mEnviroment.mRaster.clear();
                continue;
            }
            LOGE("Unrecognized value %s passed to stateRaster", value);
            return false;
        }

        if (!strcmp(key, "stateFragment")) {
            if (!strcmp(value, "default")) {
                continue;
            }
            if (!strcmp(value, "parent")) {
                mEnviroment.mFragment.clear();
                continue;
            }
            LOGE("Unrecognized value %s passed to stateFragment", value);
            return false;
        }

        if (!strcmp(key, "stateStore")) {
            if (!strcmp(value, "default")) {
                continue;
            }
            if (!strcmp(value, "parent")) {
                mEnviroment.mFragmentStore.clear();
                continue;
            }
            LOGE("Unrecognized value %s passed to stateStore", value);
            return false;
        }
    }

    mSlots = new ObjectBaseRef<Allocation>[mHal.info.exportedVariableCount];
    mTypes = new ObjectBaseRef<const Type>[mHal.info.exportedVariableCount];

    return true;
}

namespace android {
namespace renderscript {

RsScript rsi_ScriptCCreate(Context *rsc,
                           const char *resName, const char *cacheDir,
                           const char *text, uint32_t len)
{
    ScriptC *s = new ScriptC(rsc);

    if (!s->runCompiler(rsc, resName, cacheDir, (uint8_t *)text, len)) {
        // Error during compile, destroy s and return null.
        delete s;
        return NULL;
    }

    s->incUserRef();
    return s;
}

}
}
