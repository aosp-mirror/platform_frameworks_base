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

using namespace android;
using namespace android::renderscript;

Script::Script(Context *rsc) : ObjectBase(rsc)
{
    mAllocFile = __FILE__;
    mAllocLine = __LINE__;
    memset(&mEnviroment, 0, sizeof(mEnviroment));
    mEnviroment.mClearColor[0] = 0;
    mEnviroment.mClearColor[1] = 0;
    mEnviroment.mClearColor[2] = 0;
    mEnviroment.mClearColor[3] = 1;
    mEnviroment.mClearDepth = 1;
    mEnviroment.mClearStencil = 0;
    mEnviroment.mIsRoot = false;
}

Script::~Script()
{
}

namespace android {
namespace renderscript {


void rsi_ScriptBindAllocation(Context * rsc, RsScript vs, RsAllocation va, uint32_t slot)
{
    Script *s = static_cast<Script *>(vs);
    s->mSlots[slot].set(static_cast<Allocation *>(va));
}

void rsi_ScriptSetClearColor(Context * rsc, RsScript vs, float r, float g, float b, float a)
{
    Script *s = static_cast<Script *>(vs);
    s->mEnviroment.mClearColor[0] = r;
    s->mEnviroment.mClearColor[1] = g;
    s->mEnviroment.mClearColor[2] = b;
    s->mEnviroment.mClearColor[3] = a;
}

void rsi_ScriptSetTimeZone(Context * rsc, RsScript vs, const char * timeZone, uint32_t length)
{
    Script *s = static_cast<Script *>(vs);
    s->mEnviroment.mTimeZone = timeZone;
}

void rsi_ScriptSetClearDepth(Context * rsc, RsScript vs, float v)
{
    Script *s = static_cast<Script *>(vs);
    s->mEnviroment.mClearDepth = v;
}

void rsi_ScriptSetClearStencil(Context * rsc, RsScript vs, uint32_t v)
{
    Script *s = static_cast<Script *>(vs);
    s->mEnviroment.mClearStencil = v;
}

void rsi_ScriptSetType(Context * rsc, RsType vt, uint32_t slot, bool writable, const char *name)
{
    ScriptCState *ss = &rsc->mScriptC;
    const Type *t = static_cast<const Type *>(vt);
    ss->mConstantBufferTypes[slot].set(t);
    ss->mSlotWritable[slot] = writable;
    if (name) {
        ss->mSlotNames[slot].setTo(name);
    } else {
        ss->mSlotNames[slot].setTo("");
    }
}

void rsi_ScriptSetInvoke(Context *rsc, const char *name, uint32_t slot)
{
    ScriptCState *ss = &rsc->mScriptC;
    ss->mInvokableNames[slot] = name;
}

void rsi_ScriptInvoke(Context *rsc, RsScript vs, uint32_t slot)
{
    Script *s = static_cast<Script *>(vs);
    if (s->mEnviroment.mInvokables[slot] == NULL) {
        rsc->setError(RS_ERROR_BAD_SCRIPT, "Calling invoke on bad script");
        return;
    }
    s->setupScript();
    s->mEnviroment.mInvokables[slot]();
}


void rsi_ScriptSetRoot(Context * rsc, bool isRoot)
{
    ScriptCState *ss = &rsc->mScriptC;
    ss->mScript->mEnviroment.mIsRoot = isRoot;
}


}
}

