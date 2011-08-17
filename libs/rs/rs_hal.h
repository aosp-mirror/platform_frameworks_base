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
class Program;
class ProgramStore;
class ProgramRaster;
class ProgramVertex;
class ProgramFragment;
class Mesh;
class Sampler;
class FBOCache;

typedef void *(*RsHalSymbolLookupFunc)(void *usrptr, char const *symbolName);

typedef struct {
    const void *in;
    void *out;
    const void *usr;
    size_t usr_len;
    uint32_t x;
    uint32_t y;
    uint32_t z;
    uint32_t lod;
    RsAllocationCubemapFace face;
    uint32_t ar[16];
} RsForEachStubParamStruct;

/**
 * Script management functions
 */
typedef struct {
    bool (*initGraphics)(const Context *);
    void (*shutdownGraphics)(const Context *);
    bool (*setSurface)(const Context *, uint32_t w, uint32_t h, RsNativeWindow);
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
                              uint32_t slot,
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
        bool (*init)(const Context *rsc, Allocation *alloc, bool forceZero);
        void (*destroy)(const Context *rsc, Allocation *alloc);

        void (*resize)(const Context *rsc, const Allocation *alloc, const Type *newType,
                       bool zeroNew);
        void (*syncAll)(const Context *rsc, const Allocation *alloc, RsAllocationUsageType src);
        void (*markDirty)(const Context *rsc, const Allocation *alloc);

        void (*data1D)(const Context *rsc, const Allocation *alloc,
                       uint32_t xoff, uint32_t lod, uint32_t count,
                       const void *data, uint32_t sizeBytes);
        void (*data2D)(const Context *rsc, const Allocation *alloc,
                       uint32_t xoff, uint32_t yoff, uint32_t lod,
                       RsAllocationCubemapFace face, uint32_t w, uint32_t h,
                       const void *data, uint32_t sizeBytes);
        void (*data3D)(const Context *rsc, const Allocation *alloc,
                       uint32_t xoff, uint32_t yoff, uint32_t zoff,
                       uint32_t lod, RsAllocationCubemapFace face,
                       uint32_t w, uint32_t h, uint32_t d, const void *data, uint32_t sizeBytes);

        // Allocation to allocation copies
        void (*allocData1D)(const Context *rsc,
                            const Allocation *dstAlloc,
                            uint32_t dstXoff, uint32_t dstLod, uint32_t count,
                            const Allocation *srcAlloc, uint32_t srcXoff, uint32_t srcLod);
        void (*allocData2D)(const Context *rsc,
                            const Allocation *dstAlloc,
                            uint32_t dstXoff, uint32_t dstYoff, uint32_t dstLod,
                            RsAllocationCubemapFace dstFace, uint32_t w, uint32_t h,
                            const Allocation *srcAlloc,
                            uint32_t srcXoff, uint32_t srcYoff, uint32_t srcLod,
                            RsAllocationCubemapFace srcFace);
        void (*allocData3D)(const Context *rsc,
                            const Allocation *dstAlloc,
                            uint32_t dstXoff, uint32_t dstYoff, uint32_t dstZoff,
                            uint32_t dstLod, RsAllocationCubemapFace dstFace,
                            uint32_t w, uint32_t h, uint32_t d,
                            const Allocation *srcAlloc,
                            uint32_t srcXoff, uint32_t srcYoff, uint32_t srcZoff,
                            uint32_t srcLod, RsAllocationCubemapFace srcFace);

        void (*elementData1D)(const Context *rsc, const Allocation *alloc, uint32_t x,
                              const void *data, uint32_t elementOff, uint32_t sizeBytes);
        void (*elementData2D)(const Context *rsc, const Allocation *alloc, uint32_t x, uint32_t y,
                              const void *data, uint32_t elementOff, uint32_t sizeBytes);


    } allocation;

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

    struct {
        bool (*init)(const Context *rsc, const FBOCache *fb);
        void (*setActive)(const Context *rsc, const FBOCache *fb);
        void (*destroy)(const Context *rsc, const FBOCache *fb);
    } framebuffer;

} RsdHalFunctions;


}
}


bool rsdHalInit(android::renderscript::Context *, uint32_t version_major, uint32_t version_minor);

#endif

