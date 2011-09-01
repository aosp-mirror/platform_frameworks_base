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

Script::Script(Context *rsc) : ObjectBase(rsc) {
    memset(&mEnviroment, 0, sizeof(mEnviroment));
    memset(&mHal, 0, sizeof(mHal));

    mSlots = NULL;
    mTypes = NULL;
}

Script::~Script() {
    if (mSlots) {
        delete [] mSlots;
        mSlots = NULL;
    }
    if (mTypes) {
        delete [] mTypes;
        mTypes = NULL;
    }
}

void Script::setSlot(uint32_t slot, Allocation *a) {
    //LOGE("setSlot %i %p", slot, a);
    if (slot >= mHal.info.exportedVariableCount) {
        LOGE("Script::setSlot unable to set allocation, invalid slot index");
        return;
    }

    mSlots[slot].set(a);
    if (a != NULL) {
        mRSC->mHal.funcs.script.setGlobalBind(mRSC, this, slot, a->getPtr());
    } else {
        mRSC->mHal.funcs.script.setGlobalBind(mRSC, this, slot, NULL);
    }
}

void Script::setVar(uint32_t slot, const void *val, size_t len) {
    //LOGE("setVar %i %p %i", slot, val, len);
    if (slot >= mHal.info.exportedVariableCount) {
        LOGE("Script::setVar unable to set allocation, invalid slot index");
        return;
    }
    mRSC->mHal.funcs.script.setGlobalVar(mRSC, this, slot, (void *)val, len);
}

void Script::setVarObj(uint32_t slot, ObjectBase *val) {
    //LOGE("setVarObj %i %p", slot, val);
    if (slot >= mHal.info.exportedVariableCount) {
        LOGE("Script::setVarObj unable to set allocation, invalid slot index");
        return;
    }
    //LOGE("setvarobj  %i %p", slot, val);
    mRSC->mHal.funcs.script.setGlobalObj(mRSC, this, slot, val);
}

bool Script::freeChildren() {
    incSysRef();
    mRSC->mHal.funcs.script.invokeFreeChildren(mRSC, this);
    return decSysRef();
}

namespace android {
namespace renderscript {

void rsi_ScriptBindAllocation(Context * rsc, RsScript vs, RsAllocation va, uint32_t slot) {
    Script *s = static_cast<Script *>(vs);
    Allocation *a = static_cast<Allocation *>(va);
    s->setSlot(slot, a);
    //LOGE("rsi_ScriptBindAllocation %i  %p  %p", slot, a, a->getPtr());
}

void rsi_ScriptSetTimeZone(Context * rsc, RsScript vs, const char * timeZone, size_t length) {
    Script *s = static_cast<Script *>(vs);
    s->mEnviroment.mTimeZone = timeZone;
}

void rsi_ScriptForEach(Context *rsc, RsScript vs, uint32_t slot,
                       RsAllocation vain, RsAllocation vaout,
                       const void *params, size_t paramLen) {
    Script *s = static_cast<Script *>(vs);
    s->runForEach(rsc,
                  static_cast<const Allocation *>(vain), static_cast<Allocation *>(vaout),
                  params, paramLen);

}

void rsi_ScriptInvoke(Context *rsc, RsScript vs, uint32_t slot) {
    Script *s = static_cast<Script *>(vs);
    s->Invoke(rsc, slot, NULL, 0);
}


void rsi_ScriptInvokeData(Context *rsc, RsScript vs, uint32_t slot, void *data) {
    Script *s = static_cast<Script *>(vs);
    s->Invoke(rsc, slot, NULL, 0);
}

void rsi_ScriptInvokeV(Context *rsc, RsScript vs, uint32_t slot, const void *data, size_t len) {
    Script *s = static_cast<Script *>(vs);
    s->Invoke(rsc, slot, data, len);
}

void rsi_ScriptSetVarI(Context *rsc, RsScript vs, uint32_t slot, int value) {
    Script *s = static_cast<Script *>(vs);
    s->setVar(slot, &value, sizeof(value));
}

void rsi_ScriptSetVarObj(Context *rsc, RsScript vs, uint32_t slot, RsObjectBase value) {
    Script *s = static_cast<Script *>(vs);
    ObjectBase *o = static_cast<ObjectBase *>(value);
    s->setVarObj(slot, o);
}

void rsi_ScriptSetVarJ(Context *rsc, RsScript vs, uint32_t slot, long long value) {
    Script *s = static_cast<Script *>(vs);
    s->setVar(slot, &value, sizeof(value));
}

void rsi_ScriptSetVarF(Context *rsc, RsScript vs, uint32_t slot, float value) {
    Script *s = static_cast<Script *>(vs);
    s->setVar(slot, &value, sizeof(value));
}

void rsi_ScriptSetVarD(Context *rsc, RsScript vs, uint32_t slot, double value) {
    Script *s = static_cast<Script *>(vs);
    s->setVar(slot, &value, sizeof(value));
}

void rsi_ScriptSetVarV(Context *rsc, RsScript vs, uint32_t slot, const void *data, size_t len) {
    Script *s = static_cast<Script *>(vs);
    s->setVar(slot, data, len);
}

}
}

