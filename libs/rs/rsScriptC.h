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

struct ACCscript;

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {



class ScriptC : public Script
{
public:

    ScriptC();
    virtual ~ScriptC();

    struct Program_t {
        const char * mScriptText;
        uint32_t mScriptTextLength;


        int mVersionMajor;
        int mVersionMinor;

        rsc_RunScript mScript;
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

    Vector<const Type *> mConstantBufferTypes;

    void clear();
    void runCompiler(Context *rsc);

    struct SymbolTable_t {
        const char * mName;
        void * mPtr;
        const char * mDecl;
    };
    static SymbolTable_t gSyms[];
    static const SymbolTable_t * lookupSymbol(const char *);
};


}
}
#endif



