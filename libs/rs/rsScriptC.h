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
    typedef void (*VoidFunc_t)();

    ScriptC(Context *);
    virtual ~ScriptC();

    struct Program_t {
        int mVersionMajor;
        int mVersionMinor;

        RunScript_t mScript;
        VoidFunc_t mInit;

        void ** mSlotPointers[MAX_SCRIPT_BANKS];
    };

    Program_t mProgram;

    ACCscript*    mAccScript;

    virtual void setupScript();
    virtual uint32_t run(Context *, uint32_t launchID);
};

class ScriptCState
{
public:
    ScriptCState();
    ~ScriptCState();

    ScriptC *mScript;

    ObjectBaseRef<const Type> mConstantBufferTypes[MAX_SCRIPT_BANKS];
    String8 mSlotNames[MAX_SCRIPT_BANKS];
    bool mSlotWritable[MAX_SCRIPT_BANKS];
    String8 mInvokableNames[MAX_SCRIPT_BANKS];

    void clear();
    void runCompiler(Context *rsc, ScriptC *s);
    void appendVarDefines(const Context *rsc, String8 *str);
    void appendTypes(const Context *rsc, String8 *str);

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



