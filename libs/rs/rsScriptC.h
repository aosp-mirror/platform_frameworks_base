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

#include <utils/KeyedVector.h>

struct ACCscript;

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {



class ScriptC : public Script
{
public:
    typedef int (*RunScript_t)(uint32_t launchIndex);

    ScriptC();
    virtual ~ScriptC();

    struct Program_t {
        const char * mScriptText;
        uint32_t mScriptTextLength;


        int mVersionMajor;
        int mVersionMinor;

        RunScript_t mScript;
    };

    Program_t mProgram;

    ACCscript*    mAccScript;

    virtual bool run(Context *, uint32_t launchID);
};

class ScriptCState
{
public:
    ScriptCState();
    ~ScriptCState();

    ACCscript* mAccScript;

    ScriptC::Program_t mProgram;
    Script::Enviroment_t mEnviroment;

    ObjectBaseRef<const Type> mConstantBufferTypes[MAX_SCRIPT_BANKS];
    uint32_t mConstantTypeCount;

    void clear();
    void runCompiler(Context *rsc);
    void appendVarDefines(String8 *str);
    void appendTypes(String8 *str);

    struct SymbolTable_t {
        const char * mName;
        void * mPtr;
        const char * mRet;
        const char * mParam;
    };
    static SymbolTable_t gSyms[];
    static const SymbolTable_t * lookupSymbol(const char *);
    static void appendDecls(String8 *str);

    KeyedVector<String8,int> mInt32Defines;
    KeyedVector<String8,float> mFloatDefines;
};


}
}
#endif



