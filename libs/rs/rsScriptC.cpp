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
#include "../../../external/llvm/libbcc/include/bcc/bcc.h"
#include "utils/Timers.h"

#include <GLES/gl.h>
#include <GLES/glext.h>

using namespace android;
using namespace android::renderscript;

#define GET_TLS()  Context::ScriptTLSStruct * tls = \
    (Context::ScriptTLSStruct *)pthread_getspecific(Context::gThreadTLSKey); \
    Context * rsc = tls->mContext; \
    ScriptC * sc = (ScriptC *) tls->mScript


ScriptC::ScriptC(Context *rsc) : Script(rsc)
{
    mAllocFile = __FILE__;
    mAllocLine = __LINE__;
    mBccScript = NULL;
    memset(&mProgram, 0, sizeof(mProgram));
}

ScriptC::~ScriptC()
{
    if (mBccScript) {
        bccDeleteScript(mBccScript);
    }
    free(mEnviroment.mScriptText);
    mEnviroment.mScriptText = NULL;
}

void ScriptC::setupScript(Context *rsc)
{
    setupGLState(rsc);
    mEnviroment.mStartTimeMillis
                = nanoseconds_to_milliseconds(systemTime(SYSTEM_TIME_MONOTONIC));

    for (uint32_t ct=0; ct < mEnviroment.mFieldCount; ct++) {
        if (!mSlots[ct].get())
            continue;
        void *ptr = mSlots[ct]->getPtr();
        void **dest = ((void ***)mEnviroment.mFieldAddress)[ct];
        //LOGE("setupScript %i %p = %p    %p %i", ct, dest, ptr, mSlots[ct]->getType(), mSlots[ct]->getType()->getDimX());

        //const uint32_t *p32 = (const uint32_t *)ptr;
        //for (uint32_t ct2=0; ct2 < mSlots[ct]->getType()->getDimX(); ct2++) {
            //LOGE("  %i = 0x%08x ", ct2, p32[ct2]);
        //}

        if (dest) {
            *dest = ptr;
        } else {
            LOGE("ScriptC::setupScript, NULL var binding address.");
        }
    }
}

const Allocation *ScriptC::ptrToAllocation(const void *ptr) const
{
    if (!ptr) {
        return NULL;
    }
    for (uint32_t ct=0; ct < mEnviroment.mFieldCount; ct++) {
        if (!mSlots[ct].get())
            continue;
        if (mSlots[ct]->getPtr() == ptr) {
            return mSlots[ct].get();
        }
    }
    LOGE("ScriptC::ptrToAllocation, failed to find %p", ptr);
    return NULL;
}

Script * ScriptC::setTLS(Script *sc)
{
    Context::ScriptTLSStruct * tls = (Context::ScriptTLSStruct *)
                                  pthread_getspecific(Context::gThreadTLSKey);
    rsAssert(tls);
    Script *old = tls->mScript;
    tls->mScript = sc;
    return old;
}


void ScriptC::setupGLState(Context *rsc)
{
    if (mEnviroment.mFragmentStore.get()) {
        rsc->setFragmentStore(mEnviroment.mFragmentStore.get());
    }
    if (mEnviroment.mFragment.get()) {
        rsc->setFragment(mEnviroment.mFragment.get());
    }
    if (mEnviroment.mVertex.get()) {
        rsc->setVertex(mEnviroment.mVertex.get());
    }
    if (mEnviroment.mRaster.get()) {
        rsc->setRaster(mEnviroment.mRaster.get());
    }
}

uint32_t ScriptC::run(Context *rsc)
{
    if (mProgram.mRoot == NULL) {
        rsc->setError(RS_ERROR_BAD_SCRIPT, "Attempted to run bad script");
        return 0;
    }

    setupScript(rsc);

    uint32_t ret = 0;
    Script * oldTLS = setTLS(this);
    //LOGE("ScriptC::run %p", mProgram.mRoot);
    ret = mProgram.mRoot();
    setTLS(oldTLS);
    //LOGE("ScriptC::run ret %i", ret);
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

static void wc_xy(void *usr, uint32_t idx)
{
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

        for (uint32_t y = yStart; y < yEnd; y++) {
            uint32_t offset = mtls->dimX * y;
            uint8_t *xPtrOut = mtls->ptrOut + (mtls->eStrideOut * offset);
            const uint8_t *xPtrIn = mtls->ptrIn + (mtls->eStrideIn * offset);

            for (uint32_t x = mtls->xStart; x < mtls->xEnd; x++) {
                ((rs_t)mtls->script->mProgram.mRoot) (xPtrIn, xPtrOut, mtls->usr, x, y, 0, 0);
                xPtrIn += mtls->eStrideIn;
                xPtrOut += mtls->eStrideOut;
            }
        }
    }

}

void ScriptC::runForEach(Context *rsc,
                         const Allocation * ain,
                         Allocation * aout,
                         const void * usr,
                         const RsScriptCall *sc)
{
    MTLaunchStruct mtls;
    memset(&mtls, 0, sizeof(mtls));

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


    if ((rsc->getWorkerPoolSize() > 1) &&
        ((mtls.dimY * mtls.dimZ * mtls.dimArray) > 1)) {

        //LOGE("launch 1");
        rsc->launchThreads(wc_xy, &mtls);
        //LOGE("launch 2");
    } else {
        for (uint32_t ar = mtls.arrayStart; ar < mtls.arrayEnd; ar++) {
            for (uint32_t z = mtls.zStart; z < mtls.zEnd; z++) {
                for (uint32_t y = mtls.yStart; y < mtls.yEnd; y++) {
                    uint32_t offset = mtls.dimX * mtls.dimY * mtls.dimZ * ar +
                                      mtls.dimX * mtls.dimY * z +
                                      mtls.dimX * y;
                    uint8_t *xPtrOut = mtls.ptrOut + (mtls.eStrideOut * offset);
                    const uint8_t *xPtrIn = mtls.ptrIn + (mtls.eStrideIn * offset);

                    for (uint32_t x = mtls.xStart; x < mtls.xEnd; x++) {
                        ((rs_t)mProgram.mRoot) (xPtrIn, xPtrOut, usr, x, y, z, ar);
                        xPtrIn += mtls.eStrideIn;
                        xPtrOut += mtls.eStrideOut;
                    }
                }
            }
        }
    }

    setTLS(oldTLS);
}

void ScriptC::Invoke(Context *rsc, uint32_t slot, const void *data, uint32_t len)
{
    //LOGE("rsi_ScriptInvoke %i", slot);
    if ((slot >= mEnviroment.mInvokeFunctionCount) ||
        (mEnviroment.mInvokeFunctions[slot] == NULL)) {
        rsc->setError(RS_ERROR_BAD_SCRIPT, "Calling invoke on bad script");
        return;
    }
    setupScript(rsc);
    Script * oldTLS = setTLS(this);

    ((void (*)(const void *, uint32_t))
        mEnviroment.mInvokeFunctions[slot])(data, len);

    setTLS(oldTLS);
}

ScriptCState::ScriptCState()
{
    mScript = NULL;
    clear();
}

ScriptCState::~ScriptCState()
{
    delete mScript;
    mScript = NULL;
}

void ScriptCState::clear()
{
    for (uint32_t ct=0; ct < MAX_SCRIPT_BANKS; ct++) {
        mConstantBufferTypes[ct].clear();
        mSlotWritable[ct] = false;
    }

    delete mScript;
    mScript = new ScriptC(NULL);
}

static BCCvoid* symbolLookup(BCCvoid* pContext, const BCCchar* name)
{
    const ScriptCState::SymbolTable_t *sym;
    sym = ScriptCState::lookupSymbol(name);
    if (sym) {
        return sym->mPtr;
    }
    sym = ScriptCState::lookupSymbolCL(name);
    if (sym) {
        return sym->mPtr;
    }
    sym = ScriptCState::lookupSymbolGL(name);
    if (sym) {
        return sym->mPtr;
    }
    LOGE("ScriptC sym lookup failed for %s", name);
    return NULL;
}

void ScriptCState::runCompiler(Context *rsc, ScriptC *s)
{
    LOGV("ScriptCState::runCompiler ");

    s->mBccScript = bccCreateScript();
    bccScriptBitcode(s->mBccScript, s->mEnviroment.mScriptText, s->mEnviroment.mScriptTextLength);
    bccRegisterSymbolCallback(s->mBccScript, symbolLookup, NULL);
    bccCompileScript(s->mBccScript);
    bccGetScriptLabel(s->mBccScript, "root", (BCCvoid**) &s->mProgram.mRoot);
    bccGetScriptLabel(s->mBccScript, "init", (BCCvoid**) &s->mProgram.mInit);
    LOGV("root %p,  init %p", s->mProgram.mRoot, s->mProgram.mInit);

    if (s->mProgram.mInit) {
        s->mProgram.mInit();
    }

    bccGetExportFuncs(s->mBccScript, (BCCsizei*) &s->mEnviroment.mInvokeFunctionCount, 0, NULL);
    if(s->mEnviroment.mInvokeFunctionCount <= 0)
        s->mEnviroment.mInvokeFunctions = NULL;
    else {
        s->mEnviroment.mInvokeFunctions = (Script::InvokeFunc_t*) calloc(s->mEnviroment.mInvokeFunctionCount, sizeof(Script::InvokeFunc_t));
        bccGetExportFuncs(s->mBccScript, NULL, s->mEnviroment.mInvokeFunctionCount, (BCCvoid **) s->mEnviroment.mInvokeFunctions);
    }

    bccGetExportVars(s->mBccScript, (BCCsizei*) &s->mEnviroment.mFieldCount, 0, NULL);
    if(s->mEnviroment.mFieldCount <= 0)
        s->mEnviroment.mFieldAddress = NULL;
    else {
        s->mEnviroment.mFieldAddress = (void **) calloc(s->mEnviroment.mFieldCount, sizeof(void *));
        bccGetExportVars(s->mBccScript, NULL, s->mEnviroment.mFieldCount, (BCCvoid **) s->mEnviroment.mFieldAddress);
    }
    //for (int ct2=0; ct2 < s->mEnviroment.mFieldCount; ct2++ ) {
        //LOGE("Script field %i = %p", ct2, s->mEnviroment.mFieldAddress[ct2]);
    //}

    s->mEnviroment.mFragment.set(rsc->getDefaultProgramFragment());
    s->mEnviroment.mVertex.set(rsc->getDefaultProgramVertex());
    s->mEnviroment.mFragmentStore.set(rsc->getDefaultProgramStore());
    s->mEnviroment.mRaster.set(rsc->getDefaultProgramRaster());

    if (s->mProgram.mRoot) {
        const static int pragmaMax = 16;
        BCCsizei pragmaCount;
        BCCchar * str[pragmaMax];
        bccGetPragmas(s->mBccScript, &pragmaCount, pragmaMax, &str[0]);

        for (int ct=0; ct < pragmaCount; ct+=2) {
            //LOGE("pragme %s %s", str[ct], str[ct+1]);
            if (!strcmp(str[ct], "version")) {
                continue;
            }

            if (!strcmp(str[ct], "stateVertex")) {
                if (!strcmp(str[ct+1], "default")) {
                    continue;
                }
                if (!strcmp(str[ct+1], "parent")) {
                    s->mEnviroment.mVertex.clear();
                    continue;
                }
                LOGE("Unreconized value %s passed to stateVertex", str[ct+1]);
            }

            if (!strcmp(str[ct], "stateRaster")) {
                if (!strcmp(str[ct+1], "default")) {
                    continue;
                }
                if (!strcmp(str[ct+1], "parent")) {
                    s->mEnviroment.mRaster.clear();
                    continue;
                }
                LOGE("Unreconized value %s passed to stateRaster", str[ct+1]);
            }

            if (!strcmp(str[ct], "stateFragment")) {
                if (!strcmp(str[ct+1], "default")) {
                    continue;
                }
                if (!strcmp(str[ct+1], "parent")) {
                    s->mEnviroment.mFragment.clear();
                    continue;
                }
                LOGE("Unreconized value %s passed to stateFragment", str[ct+1]);
            }

            if (!strcmp(str[ct], "stateStore")) {
                if (!strcmp(str[ct+1], "default")) {
                    continue;
                }
                if (!strcmp(str[ct+1], "parent")) {
                    s->mEnviroment.mFragmentStore.clear();
                    continue;
                }
                LOGE("Unreconized value %s passed to stateStore", str[ct+1]);
            }

        }


    } else {
        // Deal with an error.
    }
}



namespace android {
namespace renderscript {

void rsi_ScriptCBegin(Context * rsc)
{
    ScriptCState *ss = &rsc->mScriptC;
    ss->clear();
}

void rsi_ScriptCSetScript(Context * rsc, void *vp)
{
    rsAssert(0);
    //ScriptCState *ss = &rsc->mScriptC;
    //ss->mProgram.mScript = reinterpret_cast<ScriptC::RunScript_t>(vp);
}

void rsi_ScriptCSetText(Context *rsc, const char *text, uint32_t len)
{
    ScriptCState *ss = &rsc->mScriptC;

    char *t = (char *)malloc(len + 1);
    memcpy(t, text, len);
    t[len] = 0;
    ss->mScript->mEnviroment.mScriptText = t;
    ss->mScript->mEnviroment.mScriptTextLength = len;
}


RsScript rsi_ScriptCCreate(Context * rsc)
{
    ScriptCState *ss = &rsc->mScriptC;

    ScriptC *s = ss->mScript;
    ss->mScript = NULL;

    ss->runCompiler(rsc, s);
    s->incUserRef();
    s->setContext(rsc);
    for (int ct=0; ct < MAX_SCRIPT_BANKS; ct++) {
        s->mTypes[ct].set(ss->mConstantBufferTypes[ct].get());
        s->mSlotWritable[ct] = ss->mSlotWritable[ct];
    }

    ss->clear();
    return s;
}

}
}


