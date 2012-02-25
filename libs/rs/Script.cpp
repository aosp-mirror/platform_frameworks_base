/*
 * Copyright (C) 2008-2012 The Android Open Source Project
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

#define LOG_TAG "libRS_cpp"

#include <utils/Log.h>
#include <malloc.h>

#include "RenderScript.h"
#include "Element.h"
#include "Type.h"
#include "Allocation.h"
#include "Script.h"

void Script::invoke(uint32_t slot, const void *v, size_t len) {
    rsScriptInvokeV(mRS->mContext, getID(), slot, v, len);
}

void Script::forEach(uint32_t slot, const Allocation *ain, const Allocation *aout,
                       const void *usr, size_t usrLen) {
    if ((ain == NULL) && (aout == NULL)) {
        mRS->throwError("At least one of ain or aout is required to be non-null.");
    }
    void *in_id = BaseObj::getObjID(ain);
    void *out_id = BaseObj::getObjID(aout);
    rsScriptForEach(mRS->mContext, getID(), slot, in_id, out_id, usr, usrLen);
}


Script::Script(void *id, RenderScript *rs) : BaseObj(id, rs) {
}


void Script::bindAllocation(const Allocation *va, uint32_t slot) {
    rsScriptBindAllocation(mRS->mContext, getID(), BaseObj::getObjID(va), slot);
}


void Script::setVar(uint32_t index, const BaseObj *o) {
    rsScriptSetVarObj(mRS->mContext, getID(), index, (o == NULL) ? 0 : o->getID());
}

void Script::setVar(uint32_t index, const void *v, size_t len) {
    rsScriptSetVarV(mRS->mContext, getID(), index, v, len);
}



void Script::FieldBase::init(RenderScript *rs, uint32_t dimx, uint32_t usages) {
    mAllocation = Allocation::createSized(rs, mElement, dimx, RS_ALLOCATION_USAGE_SCRIPT | usages);
}

//Script::FieldBase::FieldBase() {
//}


