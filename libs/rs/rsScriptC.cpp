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

void ScriptC::setupScript()
{
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


uint32_t ScriptC::run(Context *rsc, uint32_t launchIndex)
{
    if (mProgram.mRoot == NULL) {
        rsc->setError(RS_ERROR_BAD_SCRIPT, "Attempted to run bad script");
        return 0;
    }

    Context::ScriptTLSStruct * tls =
    (Context::ScriptTLSStruct *)pthread_getspecific(Context::gThreadTLSKey);
    rsAssert(tls);

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

    if (launchIndex == 0) {
        mEnviroment.mStartTimeMillis
                = nanoseconds_to_milliseconds(systemTime(SYSTEM_TIME_MONOTONIC));
    }
    setupScript();

    uint32_t ret = 0;
    tls->mScript = this;
    //LOGE("ScriptC::run %p", mProgram.mRoot);
    ret = mProgram.mRoot();
    tls->mScript = NULL;
    //LOGE("ScriptC::run ret %i", ret);
    return ret;
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
    const ScriptCState::SymbolTable_t *sym = ScriptCState::lookupSymbol(name);
    if (sym) {
        return sym->mPtr;
    }
    LOGE("ScriptC sym lookup failed for %s", name);
    return NULL;
}

void ScriptCState::runCompiler(Context *rsc, ScriptC *s)
{
    LOGE("ScriptCState::runCompiler ");

    s->mBccScript = bccCreateScript();
    bccScriptBitcode(s->mBccScript, s->mEnviroment.mScriptText, s->mEnviroment.mScriptTextLength);
    bccRegisterSymbolCallback(s->mBccScript, symbolLookup, NULL);
    LOGE("ScriptCState::runCompiler 3");
    bccCompileScript(s->mBccScript);
    LOGE("ScriptCState::runCompiler 4");
    bccGetScriptLabel(s->mBccScript, "root", (BCCvoid**) &s->mProgram.mRoot);
    bccGetScriptLabel(s->mBccScript, "init", (BCCvoid**) &s->mProgram.mInit);
    LOGE("root %p,  init %p", s->mProgram.mRoot, s->mProgram.mInit);

    if (s->mProgram.mInit) {
        s->mProgram.mInit();
    }

    s->mEnviroment.mInvokeFunctions = (Script::InvokeFunc_t *)calloc(100, sizeof(void *));
    BCCchar **labels = new char*[100];
    bccGetFunctions(s->mBccScript, (BCCsizei *)&s->mEnviroment.mInvokeFunctionCount,
                    100, (BCCchar **)labels);
    //LOGE("func count %i", s->mEnviroment.mInvokeFunctionCount);
    for (uint32_t i=0; i < s->mEnviroment.mInvokeFunctionCount; i++) {
        BCCsizei length;
        bccGetFunctionBinary(s->mBccScript, labels[i], (BCCvoid **)&(s->mEnviroment.mInvokeFunctions[i]), &length);
        //LOGE("func %i %p", i, s->mEnviroment.mInvokeFunctions[i]);
    }

    s->mEnviroment.mFieldAddress = (void **)calloc(100, sizeof(void *));
    bccGetExportVars(s->mBccScript, (BCCsizei *)&s->mEnviroment.mFieldCount,
                     100, s->mEnviroment.mFieldAddress);
    //LOGE("var count %i", s->mEnviroment.mFieldCount);
    for (uint32_t i=0; i < s->mEnviroment.mFieldCount; i++) {
        //LOGE("var %i %p", i, s->mEnviroment.mFieldAddress[i]);
    }

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


