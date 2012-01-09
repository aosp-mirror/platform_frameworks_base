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
#include "utils/Timers.h"
#include "utils/StopWatch.h"

#ifndef ANDROID_RS_SERIALIZE
#include <bcinfo/BitcodeTranslator.h>
#include <bcinfo/BitcodeWrapper.h>
#endif

using namespace android;
using namespace android::renderscript;

#define GET_TLS()  Context::ScriptTLSStruct * tls = \
    (Context::ScriptTLSStruct *)pthread_getspecific(Context::gThreadTLSKey); \
    Context * rsc = tls->mContext; \
    ScriptC * sc = (ScriptC *) tls->mScript

ScriptC::ScriptC(Context *rsc) : Script(rsc) {
#ifndef ANDROID_RS_SERIALIZE
    BT = NULL;
#endif
}

ScriptC::~ScriptC() {
#ifndef ANDROID_RS_SERIALIZE
    if (BT) {
        delete BT;
        BT = NULL;
    }
#endif
    if (mInitialized) {
        mRSC->mHal.funcs.script.invokeFreeChildren(mRSC, this);
        mRSC->mHal.funcs.script.destroy(mRSC, this);
    }
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
    //ALOGE("ptr to alloc %p", ptr);
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
    ALOGE("ScriptC::ptrToAllocation, failed to find %p", ptr);
    return NULL;
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

    if (rsc->props.mLogScripts) {
        ALOGV("%p ScriptC::run invoking root,  ptr %p", rsc, mHal.info.root);
    }

    ret = rsc->mHal.funcs.script.invokeRoot(rsc, this);

    if (rsc->props.mLogScripts) {
        ALOGV("%p ScriptC::run invoking complete, ret=%i", rsc, ret);
    }

    return ret;
}


void ScriptC::runForEach(Context *rsc,
                         const Allocation * ain,
                         Allocation * aout,
                         const void * usr,
                         size_t usrBytes,
                         const RsScriptCall *sc) {

    Context::PushState ps(rsc);

    setupGLState(rsc);
    setupScript(rsc);
    rsc->mHal.funcs.script.invokeForEach(rsc, this, 0, ain, aout, usr, usrBytes, sc);
}

void ScriptC::Invoke(Context *rsc, uint32_t slot, const void *data, size_t len) {
    if (slot >= mHal.info.exportedFunctionCount) {
        rsc->setError(RS_ERROR_BAD_SCRIPT, "Calling invoke on bad script");
        return;
    }
    setupScript(rsc);

    if (rsc->props.mLogScripts) {
        ALOGV("%p ScriptC::Invoke invoking slot %i,  ptr %p", rsc, slot, this);
    }
    rsc->mHal.funcs.script.invokeFunction(rsc, this, slot, data, len);
}

ScriptCState::ScriptCState() {
}

ScriptCState::~ScriptCState() {
}

/*
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
    ALOGE("ScriptC sym lookup failed for %s", name);
    return NULL;
}
*/

#if 0
extern const char rs_runtime_lib_bc[];
extern unsigned rs_runtime_lib_bc_size;
#endif

bool ScriptC::runCompiler(Context *rsc,
                          const char *resName,
                          const char *cacheDir,
                          const uint8_t *bitcode,
                          size_t bitcodeLen) {

    //ALOGE("runCompiler %p %p %p %p %p %i", rsc, this, resName, cacheDir, bitcode, bitcodeLen);
#ifndef ANDROID_RS_SERIALIZE
    uint32_t sdkVersion = 0;
    bcinfo::BitcodeWrapper bcWrapper((const char *)bitcode, bitcodeLen);
    if (!bcWrapper.unwrap()) {
        ALOGE("Bitcode is not in proper container format (raw or wrapper)");
        return false;
    }

    rsAssert(bcWrapper.getHeaderVersion() == 0);
    if (bcWrapper.getBCFileType() == bcinfo::BC_WRAPPER) {
        sdkVersion = bcWrapper.getTargetAPI();
    }

    if (sdkVersion == 0) {
        // This signals that we didn't have a wrapper containing information
        // about the bitcode.
        sdkVersion = rsc->getTargetSdkVersion();
    }

    if (BT) {
        delete BT;
    }
    BT = new bcinfo::BitcodeTranslator((const char *)bitcode, bitcodeLen,
                                       sdkVersion);
    if (!BT->translate()) {
        ALOGE("Failed to translate bitcode from version: %u", sdkVersion);
        delete BT;
        BT = NULL;
        return false;
    }
    bitcode = (const uint8_t *) BT->getTranslatedBitcode();
    bitcodeLen = BT->getTranslatedBitcodeSize();
#endif

    if (!rsc->mHal.funcs.script.init(rsc, this, resName, cacheDir, bitcode, bitcodeLen, 0)) {
        return false;
    }

    mInitialized = true;
    mEnviroment.mFragment.set(rsc->getDefaultProgramFragment());
    mEnviroment.mVertex.set(rsc->getDefaultProgramVertex());
    mEnviroment.mFragmentStore.set(rsc->getDefaultProgramStore());
    mEnviroment.mRaster.set(rsc->getDefaultProgramRaster());

    rsc->mHal.funcs.script.invokeInit(rsc, this);

    for (size_t i=0; i < mHal.info.exportedPragmaCount; ++i) {
        const char * key = mHal.info.exportedPragmaKeyList[i];
        const char * value = mHal.info.exportedPragmaValueList[i];
        //ALOGE("pragma %s %s", keys[i], values[i]);
        if (!strcmp(key, "version")) {
            if (!strcmp(value, "1")) {
                continue;
            }
            ALOGE("Invalid version pragma value: %s\n", value);
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
            ALOGE("Unrecognized value %s passed to stateVertex", value);
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
            ALOGE("Unrecognized value %s passed to stateRaster", value);
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
            ALOGE("Unrecognized value %s passed to stateFragment", value);
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
            ALOGE("Unrecognized value %s passed to stateStore", value);
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
                           const char *resName, size_t resName_length,
                           const char *cacheDir, size_t cacheDir_length,
                           const char *text, size_t text_length)
{
    ScriptC *s = new ScriptC(rsc);

    if (!s->runCompiler(rsc, resName, cacheDir, (uint8_t *)text, text_length)) {
        // Error during compile, destroy s and return null.
        delete s;
        return NULL;
    }

    s->incUserRef();
    return s;
}

}
}
