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

#include "rsdCore.h"
#include "rsdBcc.h"

#include <malloc.h>
#include "rsContext.h"

using namespace android;
using namespace android::renderscript;

static RsdHalFunctions FunctionTable = {
    NULL,
    NULL,
    {
        rsdScriptInit,
        rsdScriptInvokeFunction,
        rsdScriptInvokeRoot,
        rsdScriptInvokeInit,
        rsdScriptSetGlobalVar,
        rsdScriptSetGlobalBind,
        rsdScriptSetGlobalObj,
        rsdScriptDestroy
    }
};


bool rsdHalInit(Context *rsc, uint32_t version_major, uint32_t version_minor) {
    rsc->mHal.funcs = FunctionTable;

    /*
    rsc->mHal.drv = (RsHal *)calloc(1, sizeof(RsHal));
    if (!rsc->mHal.drv) {
        return false;
    }
    */

    return true;
}

