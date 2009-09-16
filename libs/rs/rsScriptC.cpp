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


ScriptC::ScriptC()
{
    mAccScript = NULL;
    memset(&mProgram, 0, sizeof(mProgram));
}

ScriptC::~ScriptC()
{
    if (mAccScript) {
        accDeleteScript(mAccScript);
    }
}


bool ScriptC::run(Context *rsc, uint32_t launchIndex)
{
    Context::ScriptTLSStruct * tls =
    (Context::ScriptTLSStruct *)pthread_getspecific(Context::gThreadTLSKey);

    if (mEnviroment.mFragmentStore.get()) {
        rsc->setFragmentStore(mEnviroment.mFragmentStore.get());
    }
    if (mEnviroment.mFragment.get()) {
        rsc->setFragment(mEnviroment.mFragment.get());
    }
    if (mEnviroment.mVertex.get()) {
        rsc->setVertex(mEnviroment.mVertex.get());
    }

    if (launchIndex == 0) {
        mEnviroment.mStartTimeMillis
                = nanoseconds_to_milliseconds(systemTime(SYSTEM_TIME_MONOTONIC));
    }

    for (int ct=0; ct < MAX_SCRIPT_BANKS; ct++) {
        if (mProgram.mSlotPointers[ct]) {
            *mProgram.mSlotPointers[ct] = mSlots[ct]->getPtr();
        }
    }

    bool ret = false;
    tls->mScript = this;
    ret = mProgram.mScript(launchIndex) != 0;
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
    mScript = new ScriptC();

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
    rsc->appendVarDefines(&tmp);
    appendVarDefines(&tmp);
    appendTypes(&tmp);
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
        LOGE(buf);
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

            if (!strcmp(str[ct], "stateFragmentStore")) {
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
                LOGE("Unreconized value %s passed to stateFragmentStore", str[ct+1]);
            }

        }


    } else {
        // Deal with an error.
    }
}

static void appendElementBody(String8 *s, const Element *e)
{
    s->append(" {\n");
    for (size_t ct2=0; ct2 < e->getComponentCount(); ct2++) {
        const Component *c = e->getComponent(ct2);
        s->append("    ");
        s->append(c->getCType());
        s->append(" ");
        s->append(c->getComponentName());
        s->append(";\n");
    }
    s->append("}");
}

void ScriptCState::appendVarDefines(String8 *str)
{
    char buf[256];
    LOGD("appendVarDefines mInt32Defines.size()=%d mFloatDefines.size()=%d\n",
            mInt32Defines.size(), mFloatDefines.size());
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



void ScriptCState::appendTypes(String8 *str)
{
    char buf[256];
    String8 tmp;

    str->append("struct vec2_s {float x; float y;};");
    str->append("struct vec3_s {float x; float y; float z;};");
    str->append("struct vec4_s {float x; float y; float z; float w;};");

    for (size_t ct=0; ct < MAX_SCRIPT_BANKS; ct++) {
        const Type *t = mConstantBufferTypes[ct].get();
        if (!t) {
            continue;
        }
        const Element *e = t->getElement();
        if (e->getName() && (e->getComponentCount() > 1)) {
            String8 s("struct struct_");
            s.append(e->getName());
            appendElementBody(&s, e);
            s.append(";\n");
            s.append("#define ");
            s.append(e->getName());
            s.append("_t struct struct_");
            s.append(e->getName());
            s.append("\n\n");
            LOGD(s);
            str->append(s);
        }

        if (t->getName()) {
            for (size_t ct2=0; ct2 < e->getComponentCount(); ct2++) {
                const Component *c = e->getComponent(ct2);
                tmp.setTo("#define OFFSETOF_");
                tmp.append(t->getName());
                tmp.append("_");
                tmp.append(c->getComponentName());
                sprintf(buf, " %i\n", ct2);
                tmp.append(buf);
                LOGD(tmp);
                str->append(tmp);
            }
        }

        if (mSlotNames[ct].length() > 0) {
            String8 s;
            if (e->getComponentCount() > 1) {
                if (e->getName()) {
                    // Use the named struct
                    s.setTo(e->getName());
                    s.append("_t *");
                } else {
                    // create an struct named from the slot.
                    s.setTo("struct ");
                    s.append(mSlotNames[ct]);
                    s.append("_s");
                    appendElementBody(&s, e);
                    s.append(";\n");
                    s.append("struct ");
                    s.append(mSlotNames[ct]);
                    s.append("_s * ");
                }
            } else {
                // Just make an array
                s.setTo(e->getComponent(0)->getCType());
                s.append("_t *");
            }
            s.append(mSlotNames[ct]);
            s.append(";\n");
            LOGD(s);
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
    ss->mScript->mEnviroment.mScriptText = text;
    ss->mScript->mEnviroment.mScriptTextLength = len;
}


RsScript rsi_ScriptCCreate(Context * rsc)
{
    ScriptCState *ss = &rsc->mScriptC;

    ScriptC *s = ss->mScript;
    ss->mScript = NULL;

    ss->runCompiler(rsc, s);
    s->incUserRef();
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


