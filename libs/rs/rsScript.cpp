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
}

Script::~Script()
{
}

void Script::setVar(uint32_t slot, const void *val, uint32_t len)
{
    int32_t *destPtr = ((int32_t **)mEnviroment.mFieldAddress)[slot];
    if (destPtr) {
        //LOGE("setVar f1  %f", ((const float *)destPtr)[0]);
        //LOGE("setVar %p %i", destPtr, len);
        memcpy(destPtr, val, len);
        //LOGE("setVar f2  %f", ((const float *)destPtr)[0]);
    } else {
        LOGE("Calling setVar on slot = %i which is null", slot);
    }
}

namespace android {
namespace renderscript {


void rsi_ScriptBindAllocation(Context * rsc, RsScript vs, RsAllocation va, uint32_t slot)
{
    Script *s = static_cast<Script *>(vs);
    Allocation *a = static_cast<Allocation *>(va);
    s->mSlots[slot].set(a);
    //LOGE("rsi_ScriptBindAllocation %i  %p  %p", slot, a, a->getPtr());
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
    LOGE("rsi_ScriptSetType");
}

void rsi_ScriptSetInvoke(Context *rsc, const char *name, uint32_t slot)
{
    LOGE("rsi_ScriptSetInvoke");
}


void rsi_ScriptInvoke(Context *rsc, RsScript vs, uint32_t slot)
{
    //LOGE("rsi_ScriptInvoke %i", slot);
    Script *s = static_cast<Script *>(vs);
    if ((slot >= s->mEnviroment.mInvokeFunctionCount) ||
        (s->mEnviroment.mInvokeFunctions[slot] == NULL)) {
        rsc->setError(RS_ERROR_BAD_SCRIPT, "Calling invoke on bad script");
        return;
    }
    s->setupScript();
    //LOGE("invoking %i %p", slot, s->mEnviroment.mInvokeFunctions[slot]);
    s->mEnviroment.mInvokeFunctions[slot]();
    //LOGE("invoke finished");
}


void rsi_ScriptInvokeData(Context *rsc, RsScript vs, uint32_t slot, void *data)
{
    //LOGE("rsi_ScriptInvoke %i", slot);
    Script *s = static_cast<Script *>(vs);
    if ((slot >= s->mEnviroment.mInvokeFunctionCount) ||
        (s->mEnviroment.mInvokeFunctions[slot] == NULL)) {
        rsc->setError(RS_ERROR_BAD_SCRIPT, "Calling invoke on bad script");
        return;
    }
    s->setupScript();
    //LOGE("invoking %i %p", slot, s->mEnviroment.mInvokeFunctions[slot]);
    s->mEnviroment.mInvokeFunctions[slot]();
    //LOGE("invoke finished");
}

void rsi_ScriptInvokeV(Context *rsc, RsScript vs, uint32_t slot, const void *data, uint32_t len)
{
    //LOGE("rsi_ScriptInvoke %i", slot);
    Script *s = static_cast<Script *>(vs);
    if ((slot >= s->mEnviroment.mInvokeFunctionCount) ||
        (s->mEnviroment.mInvokeFunctions[slot] == NULL)) {
        rsc->setError(RS_ERROR_BAD_SCRIPT, "Calling invoke on bad script");
        return;
    }
    s->setupScript();

    LOGE("rsi_ScriptInvokeV, len=%i", len);
    const uint32_t * dPtr = (const uint32_t *)data;
    switch(len) {
    case 0:
        s->mEnviroment.mInvokeFunctions[slot]();
        break;
    case 4:
        ((void (*)(uint32_t))
         s->mEnviroment.mInvokeFunctions[slot])(dPtr[0]);
        break;
    case 8:
        ((void (*)(uint32_t, uint32_t))
         s->mEnviroment.mInvokeFunctions[slot])(dPtr[0], dPtr[1]);
        break;
    case 12:
        ((void (*)(uint32_t, uint32_t, uint32_t))
         s->mEnviroment.mInvokeFunctions[slot])(dPtr[0], dPtr[1], dPtr[2]);
        break;
    case 16:
        ((void (*)(uint32_t, uint32_t, uint32_t, uint32_t))
         s->mEnviroment.mInvokeFunctions[slot])(dPtr[0], dPtr[1], dPtr[2], dPtr[3]);
        break;
    case 20:
        ((void (*)(uint32_t, uint32_t, uint32_t, uint32_t, uint32_t))
         s->mEnviroment.mInvokeFunctions[slot])(dPtr[0], dPtr[1], dPtr[2], dPtr[3], dPtr[4]);
        break;


    }

}

void rsi_ScriptSetRoot(Context * rsc, bool isRoot)
{
    LOGE("rsi_ScriptSetRoot");
}

void rsi_ScriptSetVarI(Context *rsc, RsScript vs, uint32_t slot, int value)
{
    Script *s = static_cast<Script *>(vs);
    s->setVar(slot, &value, sizeof(value));
}

void rsi_ScriptSetVarF(Context *rsc, RsScript vs, uint32_t slot, float value)
{
    Script *s = static_cast<Script *>(vs);
    s->setVar(slot, &value, sizeof(value));
}

void rsi_ScriptSetVarV(Context *rsc, RsScript vs, uint32_t slot, const void *data, uint32_t len)
{
    const float *fp = (const float *)data;
    Script *s = static_cast<Script *>(vs);
    s->setVar(slot, data, len);
}


}
}

