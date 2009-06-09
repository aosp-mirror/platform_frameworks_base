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

Script::Script()
{
    memset(&mEnviroment, 0, sizeof(mEnviroment));
    mEnviroment.mClearColor[0] = 0;
    mEnviroment.mClearColor[1] = 0;
    mEnviroment.mClearColor[2] = 0;
    mEnviroment.mClearColor[3] = 1;
    mEnviroment.mClearDepth = 1;
}

Script::~Script()
{
}

namespace android {
namespace renderscript {


void rsi_ScriptDestroy(Context * rsc, RsScript vs)
{
    Script *s = static_cast<Script *>(vs);
    s->decRef();
}

void rsi_ScriptBindAllocation(Context * rsc, RsScript vs, RsAllocation va, uint32_t slot)
{
    Script *s = static_cast<Script *>(vs);
    s->mSlots[slot].set(static_cast<Allocation *>(va));
}


}
}

