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


void ScriptC::runForEach(Context *rsc,
                         const Allocation * ain,
                         Allocation * aout,
                         const void * usr,
                         const RsScriptCall *sc)
{
    uint32_t dimX = ain->getType()->getDimX();
    uint32_t dimY = ain->getType()->getDimY();
    uint32_t dimZ = ain->getType()->getDimZ();
    uint32_t dimA = 0;//ain->getType()->getDimArray();

    uint32_t xStart = 0;
    uint32_t xEnd = 0;
    uint32_t yStart = 0;
    uint32_t yEnd = 0;
    uint32_t zStart = 0;
    uint32_t zEnd = 0;
    uint32_t arrayStart = 0;
    uint32_t arrayEnd = 0;

    if (!sc || (sc->xEnd == 0)) {
        xStart = 0;
        xEnd = ain->getType()->getDimX();
    } else {
        rsAssert(xStart < dimX);
        rsAssert(xEnd <= dimX);
        rsAssert(sc->xStart < sc->xEnd);
        xStart = rsMin(dimX, sc->xStart);
        xEnd = rsMin(dimX, sc->xEnd);
        if (xStart >= xEnd) return;
    }

    if (!sc || (sc->yEnd == 0)) {
        yStart = 0;
        yEnd = ain->getType()->getDimY();
    } else {
        rsAssert(yStart < dimY);
        rsAssert(yEnd <= dimY);
        rsAssert(sc->yStart < sc->yEnd);
        yStart = rsMin(dimY, sc->yStart);
        yEnd = rsMin(dimY, sc->yEnd);
        if (yStart >= yEnd) return;
    }

    xEnd = rsMax((uint32_t)1, xEnd);
    yEnd = rsMax((uint32_t)1, yEnd);
    zEnd = rsMax((uint32_t)1, zEnd);
    arrayEnd = rsMax((uint32_t)1, arrayEnd);

    rsAssert(ain->getType()->getDimZ() == 0);

    setupScript(rsc);
    Script * oldTLS = setTLS(this);

    typedef int (*rs_t)(const void *, void *, const void *, uint32_t, uint32_t, uint32_t, uint32_t);

    const uint8_t *ptrIn = (const uint8_t *)ain->getPtr();
    uint32_t eStrideIn = ain->getType()->getElementSizeBytes();

    uint8_t *ptrOut = NULL;
    uint32_t eStrideOut = 0;
    if (aout) {
        ptrOut = (uint8_t *)aout->getPtr();
        eStrideOut = aout->getType()->getElementSizeBytes();
    }

    for (uint32_t ar = arrayStart; ar < arrayEnd; ar++) {
        for (uint32_t z = zStart; z < zEnd; z++) {
            for (uint32_t y = yStart; y < yEnd; y++) {
                uint32_t offset = dimX * dimY * dimZ * ar +
                                  dimX * dimY * z +
                                  dimX * y;
                uint8_t *xPtrOut = ptrOut + (eStrideOut * offset);
                const uint8_t *xPtrIn = ptrIn + (eStrideIn * offset);

                for (uint32_t x = xStart; x < xEnd; x++) {
                    ((rs_t)mProgram.mRoot) (xPtrIn, xPtrOut, usr, x, y, z, ar);
                    xPtrIn += eStrideIn;
                    xPtrOut += eStrideOut;
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

    s->mEnviroment.mFieldAddress = (void **)calloc(100, sizeof(void *));
    bccGetExportVars(s->mBccScript, (BCCsizei *)&s->mEnviroment.mFieldCount,
                     100, s->mEnviroment.mFieldAddress);

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


