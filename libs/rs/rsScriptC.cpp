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

#include "acc/acc.h"
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
    mAccScript = NULL;
    memset(&mProgram, 0, sizeof(mProgram));
}

ScriptC::~ScriptC()
{
    if (mAccScript) {
        accDeleteScript(mAccScript);
    }
    free(mEnviroment.mScriptText);
    mEnviroment.mScriptText = NULL;
}

void ScriptC::setupScript()
{
    for (int ct=0; ct < MAX_SCRIPT_BANKS; ct++) {
        if (mProgram.mSlotPointers[ct]) {
            *mProgram.mSlotPointers[ct] = mSlots[ct]->getPtr();
        }
    }
}


uint32_t ScriptC::run(Context *rsc, uint32_t launchIndex)
{
    if (mProgram.mScript == NULL) {
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
    ret = mProgram.mScript(launchIndex);
    tls->mScript = NULL;
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
        mSlotNames[ct].setTo("");
        mInvokableNames[ct].setTo("");
        mSlotWritable[ct] = false;
    }

    delete mScript;
    mScript = new ScriptC(NULL);

    mInt32Defines.clear();
    mFloatDefines.clear();
}

static ACCvoid* symbolLookup(ACCvoid* pContext, const ACCchar* name)
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
    s->mAccScript = accCreateScript();
    String8 tmp;

    rsc->appendNameDefines(&tmp);
    appendDecls(&tmp);
    appendVarDefines(rsc, &tmp);
    appendTypes(rsc, &tmp);
    tmp.append("#line 1\n");

    const char* scriptSource[] = {tmp.string(), s->mEnviroment.mScriptText};
    int scriptLength[] = {tmp.length(), s->mEnviroment.mScriptTextLength} ;
    accScriptSource(s->mAccScript, sizeof(scriptLength) / sizeof(int), scriptSource, scriptLength);
    accRegisterSymbolCallback(s->mAccScript, symbolLookup, NULL);
    accCompileScript(s->mAccScript);
    accGetScriptLabel(s->mAccScript, "main", (ACCvoid**) &s->mProgram.mScript);
    accGetScriptLabel(s->mAccScript, "init", (ACCvoid**) &s->mProgram.mInit);
    rsAssert(s->mProgram.mScript);

    if (!s->mProgram.mScript) {
        ACCchar buf[4096];
        ACCsizei len;
        accGetScriptInfoLog(s->mAccScript, sizeof(buf), &len, buf);
        LOGE("%s", buf);
        rsc->setError(RS_ERROR_BAD_SCRIPT, "Error compiling user script.");
        return;
    }

    if (s->mProgram.mInit) {
        s->mProgram.mInit();
    }

    for (int ct=0; ct < MAX_SCRIPT_BANKS; ct++) {
        if (mSlotNames[ct].length() > 0) {
            accGetScriptLabel(s->mAccScript,
                              mSlotNames[ct].string(),
                              (ACCvoid**) &s->mProgram.mSlotPointers[ct]);
        }
    }

    for (int ct=0; ct < MAX_SCRIPT_BANKS; ct++) {
        if (mInvokableNames[ct].length() > 0) {
            accGetScriptLabel(s->mAccScript,
                              mInvokableNames[ct].string(),
                              (ACCvoid**) &s->mEnviroment.mInvokables[ct]);
        }
    }

    s->mEnviroment.mFragment.set(rsc->getDefaultProgramFragment());
    s->mEnviroment.mVertex.set(rsc->getDefaultProgramVertex());
    s->mEnviroment.mFragmentStore.set(rsc->getDefaultProgramFragmentStore());
    s->mEnviroment.mRaster.set(rsc->getDefaultProgramRaster());

    if (s->mProgram.mScript) {
        const static int pragmaMax = 16;
        ACCsizei pragmaCount;
        ACCchar * str[pragmaMax];
        accGetPragmas(s->mAccScript, &pragmaCount, pragmaMax, &str[0]);

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
                ProgramVertex * pv = (ProgramVertex *)rsc->lookupName(str[ct+1]);
                if (pv != NULL) {
                    s->mEnviroment.mVertex.set(pv);
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
                ProgramRaster * pr = (ProgramRaster *)rsc->lookupName(str[ct+1]);
                if (pr != NULL) {
                    s->mEnviroment.mRaster.set(pr);
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
                ProgramFragment * pf = (ProgramFragment *)rsc->lookupName(str[ct+1]);
                if (pf != NULL) {
                    s->mEnviroment.mFragment.set(pf);
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
                ProgramFragmentStore * pfs =
                    (ProgramFragmentStore *)rsc->lookupName(str[ct+1]);
                if (pfs != NULL) {
                    s->mEnviroment.mFragmentStore.set(pfs);
                    continue;
                }
                LOGE("Unreconized value %s passed to stateStore", str[ct+1]);
            }

        }


    } else {
        // Deal with an error.
    }
}

static void appendElementBody(String8 *s, const Element *e)
{
    s->append(" {\n");
    for (size_t ct2=0; ct2 < e->getFieldCount(); ct2++) {
        const Element *c = e->getField(ct2);
        s->append("    ");
        s->append(c->getCType());
        s->append(" ");
        s->append(e->getFieldName(ct2));
        s->append(";\n");
    }
    s->append("}");
}

void ScriptCState::appendVarDefines(const Context *rsc, String8 *str)
{
    char buf[256];
    if (rsc->props.mLogScripts) {
        LOGD("appendVarDefines mInt32Defines.size()=%d mFloatDefines.size()=%d\n",
                mInt32Defines.size(), mFloatDefines.size());
    }
    for (size_t ct=0; ct < mInt32Defines.size(); ct++) {
        str->append("#define ");
        str->append(mInt32Defines.keyAt(ct));
        str->append(" ");
        sprintf(buf, "%i\n", (int)mInt32Defines.valueAt(ct));
        str->append(buf);
    }
    for (size_t ct=0; ct < mFloatDefines.size(); ct++) {
        str->append("#define ");
        str->append(mFloatDefines.keyAt(ct));
        str->append(" ");
        sprintf(buf, "%ff\n", mFloatDefines.valueAt(ct));
        str->append(buf);
    }
}



void ScriptCState::appendTypes(const Context *rsc, String8 *str)
{
    char buf[256];
    String8 tmp;

    str->append("struct vecF32_2_s {float x; float y;};\n");
    str->append("struct vecF32_3_s {float x; float y; float z;};\n");
    str->append("struct vecF32_4_s {float x; float y; float z; float w;};\n");
    str->append("struct vecU8_4_s {char r; char g; char b; char a;};\n");
    str->append("#define vecF32_2_t struct vecF32_2_s\n");
    str->append("#define vecF32_3_t struct vecF32_3_s\n");
    str->append("#define vecF32_4_t struct vecF32_4_s\n");
    str->append("#define vecU8_4_t struct vecU8_4_s\n");
    str->append("#define vecI8_4_t struct vecU8_4_s\n");

    for (size_t ct=0; ct < MAX_SCRIPT_BANKS; ct++) {
        const Type *t = mConstantBufferTypes[ct].get();
        if (!t) {
            continue;
        }
        const Element *e = t->getElement();
        if (e->getName() && (e->getFieldCount() > 1)) {
            String8 s("struct struct_");
            s.append(e->getName());
            s.append(e->getCStructBody());
            s.append(";\n");

            s.append("#define ");
            s.append(e->getName());
            s.append("_t struct struct_");
            s.append(e->getName());
            s.append("\n\n");
            if (rsc->props.mLogScripts) {
                LOGV("%s", static_cast<const char*>(s));
            }
            str->append(s);
        }

        if (mSlotNames[ct].length() > 0) {
            String8 s;
            if (e->getName()) {
                // Use the named struct
                s.setTo(e->getName());
            } else {
                // create an struct named from the slot.
                s.setTo("struct ");
                s.append(mSlotNames[ct]);
                s.append("_s");
                s.append(e->getCStructBody());
                //appendElementBody(&s, e);
                s.append(";\n");
                s.append("struct ");
                s.append(mSlotNames[ct]);
                s.append("_s");
            }

            s.append(" * ");
            s.append(mSlotNames[ct]);
            s.append(";\n");
            if (rsc->props.mLogScripts) {
                LOGV("%s", static_cast<const char*>(s));
            }
            str->append(s);
        }
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
        s->mSlotNames[ct] = ss->mSlotNames[ct];
        s->mSlotWritable[ct] = ss->mSlotWritable[ct];
    }

    ss->clear();
    return s;
}

void rsi_ScriptCSetDefineF(Context *rsc, const char* name, float value)
{
    ScriptCState *ss = &rsc->mScriptC;
    ss->mFloatDefines.add(String8(name), value);
}

void rsi_ScriptCSetDefineI32(Context *rsc, const char* name, int32_t value)
{
    ScriptCState *ss = &rsc->mScriptC;
    ss->mInt32Defines.add(String8(name), value);
}

}
}


