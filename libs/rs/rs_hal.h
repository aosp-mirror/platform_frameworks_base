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
#include <ui/egl/android_natives.h>

namespace android {
namespace renderscript {

class Context;
class ObjectBase;
class Element;
class Type;
class Allocation;
class Script;
class ScriptC;
class Program;
class ProgramStore;
class ProgramRaster;
class ProgramVertex;
class ProgramFragment;
class Mesh;
class Sampler;

typedef void *(*RsHalSymbolLookupFunc)(void *usrptr, char const *symbolName);

/**
 * Script management functions
 */
typedef struct {
    bool (*initGraphics)(const Context *);
    void (*shutdownGraphics)(const Context *);
    bool (*setSurface)(const Context *, uint32_t w, uint32_t h, ANativeWindow *);
    void (*swap)(const Context *);

    void (*shutdownDriver)(Context *);
    void (*getVersion)(unsigned int *major, unsigned int *minor);
    void (*setPriority)(const Context *, int32_t priority);



    struct {
        bool (*init)(const Context *rsc, ScriptC *s,
                     char const *resName,
                     char const *cacheDir,
                     uint8_t const *bitcode,
                     size_t bitcodeSize,
                     uint32_t flags);

        void (*invokeFunction)(const Context *rsc, Script *s,
                               uint32_t slot,
                               const void *params,
                               size_t paramLength);
        int (*invokeRoot)(const Context *rsc, Script *s);
        void (*invokeForEach)(const Context *rsc,
                              Script *s,
                              const Allocation * ain,
                              Allocation * aout,
                              const void * usr,
                              uint32_t usrLen,
                              const RsScriptCall *sc);
        void (*invokeInit)(const Context *rsc, Script *s);

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

    struct {
        bool (*init)(const Context *rsc, const ProgramStore *ps);
        void (*setActive)(const Context *rsc, const ProgramStore *ps);
        void (*destroy)(const Context *rsc, const ProgramStore *ps);
    } store;

    struct {
        bool (*init)(const Context *rsc, const ProgramRaster *ps);
        void (*setActive)(const Context *rsc, const ProgramRaster *ps);
        void (*destroy)(const Context *rsc, const ProgramRaster *ps);
    } raster;

    struct {
        bool (*init)(const Context *rsc, const ProgramVertex *pv,
                     const char* shader, uint32_t shaderLen);
        void (*setActive)(const Context *rsc, const ProgramVertex *pv);
        void (*destroy)(const Context *rsc, const ProgramVertex *pv);
    } vertex;

    struct {
        bool (*init)(const Context *rsc, const ProgramFragment *pf,
                     const char* shader, uint32_t shaderLen);
        void (*setActive)(const Context *rsc, const ProgramFragment *pf);
        void (*destroy)(const Context *rsc, const ProgramFragment *pf);
    } fragment;

    struct {
        bool (*init)(const Context *rsc, const Mesh *m);
        void (*draw)(const Context *rsc, const Mesh *m, uint32_t primIndex, uint32_t start, uint32_t len);
        void (*destroy)(const Context *rsc, const Mesh *m);
    } mesh;

    struct {
        bool (*init)(const Context *rsc, const Sampler *m);
        void (*destroy)(const Context *rsc, const Sampler *m);
    } sampler;

} RsdHalFunctions;


}
}


bool rsdHalInit(android::renderscript::Context *, uint32_t version_major, uint32_t version_minor);

#endif

