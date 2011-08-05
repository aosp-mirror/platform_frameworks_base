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

#ifndef ANDROID_RS_SERIALIZE
#include "bcinfo/BitcodeTranslator.h"
#endif

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {


class ScriptC : public Script {
public:
    typedef int (*RunScript_t)();
    typedef void (*VoidFunc_t)();

    ScriptC(Context *);
    virtual ~ScriptC();


    const Allocation *ptrToAllocation(const void *) const;


    virtual void Invoke(Context *rsc, uint32_t slot, const void *data, size_t len);

    virtual uint32_t run(Context *);

    virtual void runForEach(Context *rsc,
                            const Allocation * ain,
                            Allocation * aout,
                            const void * usr,
                            size_t usrBytes,
                            const RsScriptCall *sc = NULL);

    virtual void serialize(OStream *stream) const {    }
    virtual RsA3DClassID getClassId() const { return RS_A3D_CLASS_ID_SCRIPT_C; }
    static Type *createFromStream(Context *rsc, IStream *stream) { return NULL; }

    bool runCompiler(Context *rsc, const char *resName, const char *cacheDir,
                     const uint8_t *bitcode, size_t bitcodeLen);

//protected:
    void setupScript(Context *);
    void setupGLState(Context *);
    Script * setTLS(Script *);
  private:
#ifndef ANDROID_RS_SERIALIZE
    bcinfo::BitcodeTranslator *BT;
#endif
};

class ScriptCState {
public:
    ScriptCState();
    ~ScriptCState();

    char * mScriptText;
    size_t mScriptLen;

    struct SymbolTable_t {
        const char * mName;
        void * mPtr;
        bool threadable;
    };
    static const SymbolTable_t * lookupSymbol(const char *);
    static const SymbolTable_t * lookupSymbolCL(const char *);
    static const SymbolTable_t * lookupSymbolGL(const char *);
};


}
}
#endif
