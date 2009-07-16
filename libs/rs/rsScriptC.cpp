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
#include "rsScriptC.h"
#include "rsMatrix.h"

#include "acc/acc.h"
#include "utils/String8.h"

#include <GLES/gl.h>
#include <GLES/glext.h>

using namespace android;
using namespace android::renderscript;

#define GET_TLS()  Context::ScriptTLSStruct * tls = \
    (Context::ScriptTLSStruct *)pthread_getspecific(Context::gThreadTLSKey); \
    Context * rsc = tls->mContext; \
    ScriptC * sc = (ScriptC *) tls->mScript


ScriptC::ScriptC()
{
    mAccScript = NULL;
    memset(&mProgram, 0, sizeof(mProgram));
}

ScriptC::~ScriptC()
{
    if (mAccScript) {
        accDeleteScript(mAccScript);
    }
}

extern "C" float fixedToFloat(int32_t f)
{
    return ((float)f) / 0x10000;
}

extern "C" float intToFloat(int32_t f)
{
    return (float)f;
}

extern "C" void matrixLoadIdentity(rsc_Matrix *mat)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->loadIdentity();
}

extern "C" void matrixLoadFloat(rsc_Matrix *mat, const float *f)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->load(f);
}

extern "C" void matrixLoadMat(rsc_Matrix *mat, const rsc_Matrix *newmat)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->load(reinterpret_cast<const Matrix *>(newmat));
}

extern "C" void matrixLoadRotate(rsc_Matrix *mat, float rot, float x, float y, float z)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->loadRotate(rot, x, y, z);
}

extern "C" void matrixLoadScale(rsc_Matrix *mat, float x, float y, float z)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->loadScale(x, y, z);
}

extern "C" void matrixLoadTranslate(rsc_Matrix *mat, float x, float y, float z)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->loadTranslate(x, y, z);
}

extern "C" void matrixLoadMultiply(rsc_Matrix *mat, const rsc_Matrix *lhs, const rsc_Matrix *rhs)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->loadMultiply(reinterpret_cast<const Matrix *>(lhs),
                    reinterpret_cast<const Matrix *>(rhs));
}

extern "C" void matrixMultiply(rsc_Matrix *mat, const rsc_Matrix *rhs)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->multiply(reinterpret_cast<const Matrix *>(rhs));
}

extern "C" void matrixRotate(rsc_Matrix *mat, float rot, float x, float y, float z)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->rotate(rot, x, y, z);
}

extern "C" void matrixScale(rsc_Matrix *mat, float x, float y, float z)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->scale(x, y, z);
}

extern "C" void matrixTranslate(rsc_Matrix *mat, float x, float y, float z)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->translate(x, y, z);
}


extern "C" const void * loadVp(uint32_t bank, uint32_t offset)
{
    GET_TLS();
    return &static_cast<const uint8_t *>(sc->mSlots[bank]->getPtr())[offset];
}

static float SC_loadF(uint32_t bank, uint32_t offset)
{
    GET_TLS();
    float f = static_cast<const float *>(sc->mSlots[bank]->getPtr())[offset];
    //LOGE("loadF %i %i = %f %x", bank, offset, f, ((int *)&f)[0]);
    return f;
}

static int32_t SC_loadI32(uint32_t bank, uint32_t offset)
{
    GET_TLS();
    int32_t t = static_cast<const int32_t *>(sc->mSlots[bank]->getPtr())[offset];
    //LOGE("loadI32 %i %i = %i", bank, offset, t);
    return t;
}

static uint32_t SC_loadU32(uint32_t bank, uint32_t offset)
{
    GET_TLS();
    return static_cast<const uint32_t *>(sc->mSlots[bank]->getPtr())[offset];
}

extern "C" void loadEnvVec4(uint32_t bank, uint32_t offset, rsc_Vector4 *v)
{
    GET_TLS();
    memcpy(v, &static_cast<const float *>(sc->mSlots[bank]->getPtr())[offset], sizeof(rsc_Vector4));
}

extern "C" void loadEnvMatrix(uint32_t bank, uint32_t offset, rsc_Matrix *m)
{
    GET_TLS();
    memcpy(m, &static_cast<const float *>(sc->mSlots[bank]->getPtr())[offset], sizeof(rsc_Matrix));
}


static void SC_storeF(uint32_t bank, uint32_t offset, float v)
{
    //LOGE("storeF %i %i %f", bank, offset, v);
    GET_TLS();
    static_cast<float *>(sc->mSlots[bank]->getPtr())[offset] = v;
}

static void SC_storeI32(uint32_t bank, uint32_t offset, int32_t v)
{
    GET_TLS();
    static_cast<int32_t *>(sc->mSlots[bank]->getPtr())[offset] = v;
}

static void SC_storeU32(uint32_t bank, uint32_t offset, uint32_t v)
{
    GET_TLS();
    static_cast<uint32_t *>(sc->mSlots[bank]->getPtr())[offset] = v;
}

extern "C" void storeEnvVec4(uint32_t bank, uint32_t offset, const rsc_Vector4 *v)
{
    GET_TLS();
    memcpy(&static_cast<float *>(sc->mSlots[bank]->getPtr())[offset], v, sizeof(rsc_Vector4));
}

extern "C" void storeEnvMatrix(uint32_t bank, uint32_t offset, const rsc_Matrix *m)
{
    GET_TLS();
    memcpy(&static_cast<float *>(sc->mSlots[bank]->getPtr())[offset], m, sizeof(rsc_Matrix));
}


extern "C" void color(float r, float g, float b, float a)
{
    glColor4f(r, g, b, a);
}

extern "C" void renderTriangleMesh(RsTriangleMesh mesh)
{
    GET_TLS();
    rsi_TriangleMeshRender(rsc, mesh);
}

extern "C" void renderTriangleMeshRange(RsTriangleMesh mesh, uint32_t start, uint32_t count)
{
    GET_TLS();
    rsi_TriangleMeshRenderRange(rsc, mesh, start, count);
}

extern "C" void materialDiffuse(float r, float g, float b, float a)
{
    float v[] = {r, g, b, a};
    glMaterialfv(GL_FRONT_AND_BACK, GL_DIFFUSE, v);
}

extern "C" void materialSpecular(float r, float g, float b, float a)
{
    float v[] = {r, g, b, a};
    glMaterialfv(GL_FRONT_AND_BACK, GL_SPECULAR, v);
}

extern "C" void lightPosition(float x, float y, float z, float w)
{
    float v[] = {x, y, z, w};
    glLightfv(GL_LIGHT0, GL_POSITION, v);
}

extern "C" void materialShininess(float s)
{
    glMaterialfv(GL_FRONT_AND_BACK, GL_SHININESS, &s);
}

extern "C" void uploadToTexture(RsAllocation va, uint32_t baseMipLevel)
{
    GET_TLS();
    rsi_AllocationUploadToTexture(rsc, va, baseMipLevel);
}

extern "C" void enable(uint32_t p)
{
    glEnable(p);
}

extern "C" void disable(uint32_t p)
{
    glDisable(p);
}

extern "C" uint32_t scriptRand(uint32_t max)
{
    return (uint32_t)(((float)rand()) * max / RAND_MAX);
}

// Assumes (GL_FIXED) x,y,z (GL_UNSIGNED_BYTE)r,g,b,a
extern "C" void drawTriangleArray(RsAllocation alloc, uint32_t count)
{
    GET_TLS();

    const Allocation *a = (const Allocation *)alloc;
    const uint32_t *ptr = (const uint32_t *)a->getPtr();

    rsc->setupCheck();

    glBindBuffer(GL_ARRAY_BUFFER, 0);
    //glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, tm->mBufferObjects[1]);

    glEnableClientState(GL_VERTEX_ARRAY);
    glDisableClientState(GL_NORMAL_ARRAY);
    glDisableClientState(GL_TEXTURE_COORD_ARRAY);
    glEnableClientState(GL_COLOR_ARRAY);

    glVertexPointer(2, GL_FIXED, 12, ptr + 1);
    //glTexCoordPointer(2, GL_FIXED, 24, ptr + 1);
    glColorPointer(4, GL_UNSIGNED_BYTE, 12, ptr);

    glDrawArrays(GL_TRIANGLES, 0, count * 3);
}

extern "C" void drawRect(int32_t x1, int32_t x2, int32_t y1, int32_t y2)
{
    GET_TLS();
    x1 = (x1 << 16);
    x2 = (x2 << 16);
    y1 = (y1 << 16);
    y2 = (y2 << 16);

    int32_t vtx[] = {x1,y1, x1,y2, x2,y1, x2,y2};
    static const int32_t tex[] = {0,0, 0,0x10000, 0x10000,0, 0x10000,0x10000};


    rsc->setupCheck();

    glBindBuffer(GL_ARRAY_BUFFER, 0);
    //glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, tm->mBufferObjects[1]);

    glEnableClientState(GL_VERTEX_ARRAY);
    glEnableClientState(GL_TEXTURE_COORD_ARRAY);
    glDisableClientState(GL_NORMAL_ARRAY);
    glDisableClientState(GL_COLOR_ARRAY);

    glVertexPointer(2, GL_FIXED, 8, vtx);
    glTexCoordPointer(2, GL_FIXED, 8, tex);
    //glColorPointer(4, GL_UNSIGNED_BYTE, 12, ptr);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

static void SC_drawQuad(float x1, float y1, float z1,
                        float x2, float y2, float z2,
                        float x3, float y3, float z3,
                        float x4, float y4, float z4)
{
    GET_TLS();

    //LOGE("Quad");
    //LOGE("%4.2f, %4.2f, %4.2f", x1, y1, z1);
    //LOGE("%4.2f, %4.2f, %4.2f", x2, y2, z2);
    //LOGE("%4.2f, %4.2f, %4.2f", x3, y3, z3);
    //LOGE("%4.2f, %4.2f, %4.2f", x4, y4, z4);

    float vtx[] = {x1,y1,z1, x2,y2,z2, x3,y3,z3, x4,y4,z4};
    static const float tex[] = {0,0, 0,1, 1,1, 1,0};


    rsc->setupCheck();

    glBindBuffer(GL_ARRAY_BUFFER, 0);
    //glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, tm->mBufferObjects[1]);

    glEnableClientState(GL_VERTEX_ARRAY);
    glVertexPointer(3, GL_FLOAT, 0, vtx);

    glClientActiveTexture(GL_TEXTURE0);
    glEnableClientState(GL_TEXTURE_COORD_ARRAY);
    glTexCoordPointer(2, GL_FLOAT, 0, tex);
    glClientActiveTexture(GL_TEXTURE1);
    glEnableClientState(GL_TEXTURE_COORD_ARRAY);
    glTexCoordPointer(2, GL_FLOAT, 0, tex);
    glClientActiveTexture(GL_TEXTURE0);

    glDisableClientState(GL_NORMAL_ARRAY);
    glDisableClientState(GL_COLOR_ARRAY);

    //glColorPointer(4, GL_UNSIGNED_BYTE, 12, ptr);

    glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
}

extern "C" void drawQuad(int32_t x1, int32_t y1, int32_t z1,
                         int32_t x2, int32_t y2, int32_t z2,
                         int32_t x3, int32_t y3, int32_t z3,
                         int32_t x4, int32_t y4, int32_t z4)
{
    GET_TLS();
    //x1 = (x1 << 16);
    //x2 = (x2 << 16);
    //y1 = (y1 << 16);
    //y2 = (y2 << 16);

    //LOGE("Quad");
    //LOGE("0x%08x, 0x%08x, 0x%08x", x1, y1, z1);
    //LOGE("0x%08x, 0x%08x, 0x%08x", x2, y2, z2);
    //LOGE("0x%08x, 0x%08x, 0x%08x", x3, y3, z3);
    //LOGE("0x%08x, 0x%08x, 0x%08x", x4, y4, z4);

    int32_t vtx[] = {x1,y1,z1, x2,y2,z2, x3,y3,z3, x4,y4,z4};
    static const int32_t tex[] = {0,0, 0,0x10000, 0x10000,0x10000, 0x10000,0};


    rsc->setupCheck();

    glBindBuffer(GL_ARRAY_BUFFER, 0);
    //glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, tm->mBufferObjects[1]);

    glEnableClientState(GL_VERTEX_ARRAY);
    glVertexPointer(3, GL_FIXED, 0, vtx);

    glClientActiveTexture(GL_TEXTURE0);
    glEnableClientState(GL_TEXTURE_COORD_ARRAY);
    glTexCoordPointer(2, GL_FIXED, 0, tex);
    glClientActiveTexture(GL_TEXTURE1);
    glEnableClientState(GL_TEXTURE_COORD_ARRAY);
    glTexCoordPointer(2, GL_FIXED, 0, tex);
    glClientActiveTexture(GL_TEXTURE0);

    glDisableClientState(GL_NORMAL_ARRAY);
    glDisableClientState(GL_COLOR_ARRAY);

    //glColorPointer(4, GL_UNSIGNED_BYTE, 12, ptr);

    glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
}

extern "C" int32_t sinx(int32_t angle)
{
    float a = ((float)angle) / 0x10000;
    a *= 3.14f / 180.f;
    float s = (float)sin(a);
    return int32_t(s * 0x10000);
}

extern "C" int32_t cosx(int32_t angle)
{
    float a = ((float)angle) / 0x10000;
    a *= 3.14f / 180.f;
    float s = (float)cos(a);
    return int32_t(s * 0x10000);
}

extern "C" float sinf(float angle)
{
    float s = (float)sin(angle);
    return s;
}

extern "C" float cosf(float angle)
{
    float s = (float)cos(angle);
    return s;
}

extern "C" void pfClearColor(float r, float g, float b, float a)
{
    //LOGE("c %f %f %f %f", r, g, b, a);
    GET_TLS();
    sc->mEnviroment.mClearColor[0] = r;
    sc->mEnviroment.mClearColor[1] = g;
    sc->mEnviroment.mClearColor[2] = b;
    sc->mEnviroment.mClearColor[3] = a;
}

extern "C" void pfBindTexture(RsProgramFragment vpf, uint32_t slot, RsAllocation va)
{
    GET_TLS();
    rsi_ProgramFragmentBindTexture(rsc,
                                   static_cast<ProgramFragment *>(vpf),
                                   slot,
                                   static_cast<Allocation *>(va));

}

extern "C" void pfBindSampler(RsProgramFragment vpf, uint32_t slot, RsSampler vs)
{
    GET_TLS();
    rsi_ProgramFragmentBindSampler(rsc,
                                   static_cast<ProgramFragment *>(vpf),
                                   slot,
                                   static_cast<Sampler *>(vs));

}

extern "C" void contextBindProgramFragmentStore(RsProgramFragmentStore pfs)
{
    GET_TLS();
    rsi_ContextBindProgramFragmentStore(rsc, pfs);

}

extern "C" void contextBindProgramFragment(RsProgramFragment pf)
{
    GET_TLS();
    rsi_ContextBindProgramFragment(rsc, pf);

}


static rsc_FunctionTable scriptCPtrTable = {
    loadVp,
    SC_loadF,
    SC_loadI32,
    SC_loadU32,
    loadEnvVec4,
    loadEnvMatrix,

    SC_storeF,
    SC_storeI32,
    SC_storeU32,
    storeEnvVec4,
    storeEnvMatrix,

    matrixLoadIdentity,
    matrixLoadFloat,
    matrixLoadMat,
    matrixLoadRotate,
    matrixLoadScale,
    matrixLoadTranslate,
    matrixLoadMultiply,
    matrixMultiply,
    matrixRotate,
    matrixScale,
    matrixTranslate,

    color,

    pfBindTexture,
    pfBindSampler,

    materialDiffuse,
    materialSpecular,
    lightPosition,
    materialShininess,
    uploadToTexture,
    enable,
    disable,

    scriptRand,
    contextBindProgramFragment,
    contextBindProgramFragmentStore,


    renderTriangleMesh,
    renderTriangleMeshRange,

    drawTriangleArray,
    drawRect

};


bool ScriptC::run(Context *rsc, uint32_t launchIndex)
{
    Context::ScriptTLSStruct * tls = 
    (Context::ScriptTLSStruct *)pthread_getspecific(Context::gThreadTLSKey);

    if (mEnviroment.mFragmentStore.get()) {
        rsc->setFragmentStore(mEnviroment.mFragmentStore.get());
    }
    if (mEnviroment.mFragment.get()) {
        rsc->setFragment(mEnviroment.mFragment.get());
    }
    if (mEnviroment.mVertex.get()) {
        rsc->setVertex(mEnviroment.mVertex.get());
    }

    tls->mScript = this;
    return mProgram.mScript(launchIndex, &scriptCPtrTable) != 0;
    tls->mScript = NULL;
}

ScriptCState::ScriptCState()
{
    clear();
}

ScriptCState::~ScriptCState()
{
    if (mAccScript) {
        accDeleteScript(mAccScript);
    }
}

void ScriptCState::clear()
{
    memset(&mProgram, 0, sizeof(mProgram));

    mConstantBufferTypes.clear();

    memset(&mEnviroment, 0, sizeof(mEnviroment));
    mEnviroment.mClearColor[0] = 0;
    mEnviroment.mClearColor[1] = 0;
    mEnviroment.mClearColor[2] = 0;
    mEnviroment.mClearColor[3] = 1;
    mEnviroment.mClearDepth = 1;
    mEnviroment.mClearStencil = 0;
    mEnviroment.mIsRoot = false;

    mAccScript = NULL;

}

ScriptCState::SymbolTable_t ScriptCState::gSyms[] = {
    { "loadI32", (void *)&SC_loadI32, "int loadI32(int, int)" },
    { "loadF", (void *)&SC_loadF, "float loadF(int, int)" },
    { "storeI32", (void *)&SC_storeI32, "void storeI32(int, int, int)" },
    { "storeF", (void *)&SC_storeF, "void storeF(int, int, float)" },
    { "drawQuad", (void *)&SC_drawQuad, "drawQuad(float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3, float x4, float y4, float z4)" },
    { "sinf", (void *)&sinf, "float sinf(float)" },
    { "cosf", (void *)&cosf, "float cosf(float)" },
    { "contextBindProgramFragmentStore", (void *)&contextBindProgramFragmentStore, "" },
    { "pfClearColor", (void *)&pfClearColor, "" },
    { "pfBindTexture", (void *)&pfBindTexture, "" },


    { NULL, NULL, NULL }
};

const ScriptCState::SymbolTable_t * ScriptCState::lookupSymbol(const char *sym)
{
    ScriptCState::SymbolTable_t *syms = gSyms;

    while (syms->mPtr) {
        if (!strcmp(syms->mName, sym)) {
            return syms;
        }
        syms++;
    }
    return NULL;
}

static ACCvoid* symbolLookup(ACCvoid* pContext, const ACCchar* name) 
{
    const ScriptCState::SymbolTable_t *sym = ScriptCState::lookupSymbol(name);

    if (sym) {
        return sym->mPtr;
    }

    LOGE("ScriptC sym lookup failed for %s", name);

    // Default to calling dlsym to allow any global symbol:
    return NULL;
}

void ScriptCState::runCompiler(Context *rsc)
{
    mAccScript = accCreateScript();
    String8 tmp;

    rsc->appendNameDefines(&tmp);

    const char* scriptSource[] = {tmp.string(), mProgram.mScriptText};
    int scriptLength[] = {tmp.length(), mProgram.mScriptTextLength} ;
    accScriptSource(mAccScript, sizeof(scriptLength) / sizeof(int), scriptSource, scriptLength);
    accRegisterSymbolCallback(mAccScript, symbolLookup, NULL);
    accCompileScript(mAccScript);
    accGetScriptLabel(mAccScript, "main", (ACCvoid**) &mProgram.mScript);
    rsAssert(mProgram.mScript);

    mEnviroment.mFragment.set(rsc->getDefaultProgramFragment());
    mEnviroment.mVertex.set(rsc->getDefaultProgramVertex());
    mEnviroment.mFragmentStore.set(rsc->getDefaultProgramFragmentStore());

    if (mProgram.mScript) {
        const static int pragmaMax = 16;
        ACCsizei pragmaCount;
        ACCchar * str[pragmaMax];
        accGetPragmas(mAccScript, &pragmaCount, pragmaMax, &str[0]);

        for (int ct=0; ct < pragmaCount; ct+=2) {
            if (!strcmp(str[ct], "version")) {
                continue;
            }

            if (!strcmp(str[ct], "stateVertex")) {
                if (!strcmp(str[ct+1], "default")) {
                    continue;
                }
                if (!strcmp(str[ct+1], "parent")) {
                    mEnviroment.mVertex.clear();
                    continue;
                }
                ProgramVertex * pv = (ProgramVertex *)rsc->lookupName(str[ct+1]);
                if (pv != NULL) {
                    mEnviroment.mVertex.set(pv);
                    continue;
                }
                LOGE("Unreconized value %s passed to stateVertex", str[ct+1]);
            }

            if (!strcmp(str[ct], "stateRaster")) {
                LOGE("Unreconized value %s passed to stateRaster", str[ct+1]);
            }

            if (!strcmp(str[ct], "stateFragment")) {
                if (!strcmp(str[ct+1], "default")) {
                    continue;
                }
                if (!strcmp(str[ct+1], "parent")) {
                    mEnviroment.mFragment.clear();
                    continue;
                }
                ProgramFragment * pf = (ProgramFragment *)rsc->lookupName(str[ct+1]);
                if (pf != NULL) {
                    mEnviroment.mFragment.set(pf);
                    continue;
                }
                LOGE("Unreconized value %s passed to stateFragment", str[ct+1]);
            }

            if (!strcmp(str[ct], "stateFragmentStore")) {
                if (!strcmp(str[ct+1], "default")) {
                    continue;
                }
                if (!strcmp(str[ct+1], "parent")) {
                    mEnviroment.mFragmentStore.clear();
                    continue;
                }
                ProgramFragmentStore * pfs = 
                    (ProgramFragmentStore *)rsc->lookupName(str[ct+1]);
                if (pfs != NULL) {
                    mEnviroment.mFragmentStore.set(pfs);
                    continue;
                }
                LOGE("Unreconized value %s passed to stateFragmentStore", str[ct+1]);
            }

        }

            
    } else {
        // Deal with an error.
    }

}

namespace android {
namespace renderscript {

void rsi_ScriptCBegin(Context * rsc)
{
    ScriptCState *ss = &rsc->mScriptC;
    ss->clear();
}

void rsi_ScriptCSetClearColor(Context * rsc, float r, float g, float b, float a)
{
    ScriptCState *ss = &rsc->mScriptC;
    ss->mEnviroment.mClearColor[0] = r;
    ss->mEnviroment.mClearColor[1] = g;
    ss->mEnviroment.mClearColor[2] = b;
    ss->mEnviroment.mClearColor[3] = a;
}

void rsi_ScriptCSetClearDepth(Context * rsc, float v)
{
    ScriptCState *ss = &rsc->mScriptC;
    ss->mEnviroment.mClearDepth = v;
}

void rsi_ScriptCSetClearStencil(Context * rsc, uint32_t v)
{
    ScriptCState *ss = &rsc->mScriptC;
    ss->mEnviroment.mClearStencil = v;
}

void rsi_ScriptCAddType(Context * rsc, RsType vt)
{
    ScriptCState *ss = &rsc->mScriptC;
    ss->mConstantBufferTypes.add(static_cast<const Type *>(vt));
}

void rsi_ScriptCSetScript(Context * rsc, void *vp)
{
    ScriptCState *ss = &rsc->mScriptC;
    ss->mProgram.mScript = reinterpret_cast<rsc_RunScript>(vp);
}

void rsi_ScriptCSetRoot(Context * rsc, bool isRoot)
{
    ScriptCState *ss = &rsc->mScriptC;
    ss->mEnviroment.mIsRoot = isRoot;
}

void rsi_ScriptCSetText(Context *rsc, const char *text, uint32_t len)
{
    ScriptCState *ss = &rsc->mScriptC;
    ss->mProgram.mScriptText = text;
    ss->mProgram.mScriptTextLength = len;
}


RsScript rsi_ScriptCCreate(Context * rsc)
{
    ScriptCState *ss = &rsc->mScriptC;

    ss->runCompiler(rsc);

    ScriptC *s = new ScriptC();
    s->incRef();
    s->mAccScript = ss->mAccScript;
    ss->mAccScript = NULL;
    s->mEnviroment = ss->mEnviroment;
    s->mProgram = ss->mProgram;
    ss->clear();

    return s;
}

}
}


