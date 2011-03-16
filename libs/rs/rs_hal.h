/*
 * Copyright (C) 2011 The Android Open Source Project
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

#ifndef RS_HAL_H
#define RS_HAL_H

#include <RenderScriptDefines.h>

namespace android {
namespace renderscript {

class Context;
class ObjectBase;
class Element;
class Type;
class Allocation;
class Script;
class ScriptC;


typedef struct RsHalRec RsHal;

typedef void *(*RsHalSymbolLookupFunc)(void *usrptr, char const *symbolName);



/**
 * Script management functions
 */
typedef struct {
    void (*shutdownDriver)(RsHal dc);
    void (*getVersion)(unsigned int *major, unsigned int *minor);



    struct {
        bool (*scriptInit)(const Context *rsc, ScriptC *s,
                           char const *resName,
                           char const *cacheDir,
                           uint8_t const *bitcode,
                           size_t bitcodeSize,
                           uint32_t flags,
                           RsHalSymbolLookupFunc lookupFunc);

        void (*invokeFunction)(const Context *rsc, const Script *s,
                               uint32_t slot,
                               const void *params,
                               size_t paramLength);
        int (*invokeRoot)(const Context *rsc, const Script *s);
        void (*invokeInit)(const Context *rsc, const Script *s);

        void (*setGlobalVar)(const Context *rsc, const Script *s,
                             uint32_t slot,
                             void *data,
                             size_t dataLength);
        void (*setGlobalBind)(const Context *rsc, const Script *s,
                              uint32_t slot,
                              void *data);
        void (*setGlobalObj)(const Context *rsc, const Script *s,
                             uint32_t slot,
                             ObjectBase *data);

        void (*destroy)(const Context *rsc, Script *s);
    } script;



} RsdHalFunctions;

void rsiSetObject(ObjectBase **vdst, ObjectBase * vsrc);
void rsiClearObject(ObjectBase **vdst);
bool rsiIsObject(const ObjectBase *vdst);

}
}


bool rsdHalInit(android::renderscript::Context *, uint32_t version_major, uint32_t version_minor);

#endif

