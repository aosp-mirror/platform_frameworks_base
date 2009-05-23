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

#ifndef ANDROID_RS_SCRIPT_C_H
#define ANDROID_RS_SCRIPT_C_H

#include "rsScript.h"

#include "RenderScriptEnv.h"

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {

    

class ScriptC : public Script
{
public:

    ScriptC();
    virtual ~ScriptC();


    virtual void run(Context *, uint32_t launchID);


    rsc_RunScript mScript;


    struct Env {
        Context *mContext;
        ScriptC *mScript;
    };

};

class ScriptCState 
{
public:
    ScriptCState();
    ~ScriptCState();


    rsc_RunScript mScript;
    float mClearColor[4];
    float mClearDepth;
    uint32_t mClearStencil;
    bool mIsRoot;
    bool mIsOrtho;

    Vector<const Type *> mConstantBufferTypes;

    void clear();
};


}
}
#endif



