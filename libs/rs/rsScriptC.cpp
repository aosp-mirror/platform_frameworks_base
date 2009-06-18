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

using namespace android;
using namespace android::renderscript;


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

extern "C" void matrixLoadIdentity(void *con, rsc_Matrix *mat)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->loadIdentity();
}

extern "C" void matrixLoadFloat(void *con, rsc_Matrix *mat, const float *f)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->load(f);
}

extern "C" void matrixLoadMat(void *con, rsc_Matrix *mat, const rsc_Matrix *newmat)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->load(reinterpret_cast<const Matrix *>(newmat));
}

extern "C" void matrixLoadRotate(void *con, rsc_Matrix *mat, float rot, float x, float y, float z)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->loadRotate(rot, x, y, z);
}

extern "C" void matrixLoadScale(void *con, rsc_Matrix *mat, float x, float y, float z)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->loadScale(x, y, z);
}

extern "C" void matrixLoadTranslate(void *con, rsc_Matrix *mat, float x, float y, float z)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->loadTranslate(x, y, z);
}

extern "C" void matrixLoadMultiply(void *con, rsc_Matrix *mat, const rsc_Matrix *lhs, const rsc_Matrix *rhs)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->loadMultiply(reinterpret_cast<const Matrix *>(lhs),
                    reinterpret_cast<const Matrix *>(rhs));
}

extern "C" void matrixMultiply(void *con, rsc_Matrix *mat, const rsc_Matrix *rhs)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->multiply(reinterpret_cast<const Matrix *>(rhs));
}

extern "C" void matrixRotate(void *con, rsc_Matrix *mat, float rot, float x, float y, float z)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->rotate(rot, x, y, z);
}

extern "C" void matrixScale(void *con, rsc_Matrix *mat, float x, float y, float z)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->scale(x, y, z);
}

extern "C" void matrixTranslate(void *con, rsc_Matrix *mat, float x, float y, float z)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->translate(x, y, z);
}


extern "C" const void * loadVp(void *vp, uint32_t bank, uint32_t offset)
{
    ScriptC::Env * env = static_cast<ScriptC::Env *>(vp);
    return &static_cast<const uint8_t *>(env->mScript->mSlots[bank]->getPtr())[offset];
}

extern "C" float loadF(void *vp, uint32_t bank, uint32_t offset)
{
    ScriptC::Env * env = static_cast<ScriptC::Env *>(vp);
    //LOGE("bank %i, offset %i", bank, offset);
    //LOGE("%p", env->mScript->mSlots[bank]->getPtr());
    return static_cast<const float *>(env->mScript->mSlots[bank]->getPtr())[offset];
}

extern "C" int32_t loadI32(void *vp, uint32_t bank, uint32_t offset)
{
    ScriptC::Env * env = static_cast<ScriptC::Env *>(vp);
    return static_cast<const int32_t *>(env->mScript->mSlots[bank]->getPtr())[offset];
}

extern "C" uint32_t loadU32(void *vp, uint32_t bank, uint32_t offset)
{
    ScriptC::Env * env = static_cast<ScriptC::Env *>(vp);
    return static_cast<const uint32_t *>(env->mScript->mSlots[bank]->getPtr())[offset];
}

extern "C" void loadEnvVec4(void *vp, uint32_t bank, uint32_t offset, rsc_Vector4 *v)
{
    ScriptC::Env * env = static_cast<ScriptC::Env *>(vp);
    memcpy(v, &static_cast<const float *>(env->mScript->mSlots[bank]->getPtr())[offset], sizeof(rsc_Vector4));
}

extern "C" void loadEnvMatrix(void *vp, uint32_t bank, uint32_t offset, rsc_Matrix *m)
{
    ScriptC::Env * env = static_cast<ScriptC::Env *>(vp);
    memcpy(m, &static_cast<const float *>(env->mScript->mSlots[bank]->getPtr())[offset], sizeof(rsc_Matrix));
}


extern "C" void storeF(void *vp, uint32_t bank, uint32_t offset, float v)
{
    ScriptC::Env * env = static_cast<ScriptC::Env *>(vp);
    static_cast<float *>(env->mScript->mSlots[bank]->getPtr())[offset] = v;
}

extern "C" void storeI32(void *vp, uint32_t bank, uint32_t offset, int32_t v)
{
    ScriptC::Env * env = static_cast<ScriptC::Env *>(vp);
    static_cast<int32_t *>(env->mScript->mSlots[bank]->getPtr())[offset] = v;
}

extern "C" void storeU32(void *vp, uint32_t bank, uint32_t offset, uint32_t v)
{
    ScriptC::Env * env = static_cast<ScriptC::Env *>(vp);
    static_cast<uint32_t *>(env->mScript->mSlots[bank]->getPtr())[offset] = v;
}

extern "C" void storeEnvVec4(void *vp, uint32_t bank, uint32_t offset, const rsc_Vector4 *v)
{
    ScriptC::Env * env = static_cast<ScriptC::Env *>(vp);
    memcpy(&static_cast<float *>(env->mScript->mSlots[bank]->getPtr())[offset], v, sizeof(rsc_Vector4));
}

extern "C" void storeEnvMatrix(void *vp, uint32_t bank, uint32_t offset, const rsc_Matrix *m)
{
    ScriptC::Env * env = static_cast<ScriptC::Env *>(vp);
    memcpy(&static_cast<float *>(env->mScript->mSlots[bank]->getPtr())[offset], m, sizeof(rsc_Matrix));
}


extern "C" void color(void *vp, float r, float g, float b, float a)
{
    ScriptC::Env * env = static_cast<ScriptC::Env *>(vp);
    glColor4f(r, g, b, a);
}

extern "C" void renderTriangleMesh(void *vp, RsTriangleMesh mesh)
{
    ScriptC::Env * env = static_cast<ScriptC::Env *>(vp);
    rsi_TriangleMeshRender(env->mContext, mesh);
}

extern "C" void renderTriangleMeshRange(void *vp, RsTriangleMesh mesh, uint32_t start, uint32_t count)
{
    ScriptC::Env * env = static_cast<ScriptC::Env *>(vp);
    rsi_TriangleMeshRenderRange(env->mContext, mesh, start, count);
}

extern "C" void materialDiffuse(void *vp, float r, float g, float b, float a)
{
    ScriptC::Env * env = static_cast<ScriptC::Env *>(vp);
    float v[] = {r, g, b, a};
    glMaterialfv(GL_FRONT_AND_BACK, GL_DIFFUSE, v);
}

extern "C" void materialSpecular(void *vp, float r, float g, float b, float a)
{
    ScriptC::Env * env = static_cast<ScriptC::Env *>(vp);
    float v[] = {r, g, b, a};
    glMaterialfv(GL_FRONT_AND_BACK, GL_SPECULAR, v);
}

extern "C" void lightPosition(void *vp, float x, float y, float z, float w)
{
    ScriptC::Env * env = static_cast<ScriptC::Env *>(vp);
    float v[] = {x, y, z, w};
    glLightfv(GL_LIGHT0, GL_POSITION, v);
}

extern "C" void materialShininess(void *vp, float s)
{
    ScriptC::Env * env = static_cast<ScriptC::Env *>(vp);
    glMaterialfv(GL_FRONT_AND_BACK, GL_SHININESS, &s);
}

extern "C" void uploadToTexture(void *vp, RsAllocation va, uint32_t baseMipLevel)
{
    ScriptC::Env * env = static_cast<ScriptC::Env *>(vp);
    rsi_AllocationUploadToTexture(env->mContext, va, baseMipLevel);
}

extern "C" void enable(void *vp, uint32_t p)
{
    ScriptC::Env * env = static_cast<ScriptC::Env *>(vp);
    glEnable(p);
}

extern "C" void disable(void *vp, uint32_t p)
{
    ScriptC::Env * env = static_cast<ScriptC::Env *>(vp);
    glDisable(p);
}

extern "C" uint32_t scriptRand(void *vp, uint32_t max)
{
    return (uint32_t)(((float)rand()) * max / RAND_MAX);
}

// Assumes (GL_FIXED) x,y,z (GL_UNSIGNED_BYTE)r,g,b,a
extern "C" void drawTriangleArray(void *vp, RsAllocation alloc, uint32_t count)
{
    const Allocation *a = (const Allocation *)alloc;
    const uint32_t *ptr = (const uint32_t *)a->getPtr();

    ScriptC::Env * env = static_cast<ScriptC::Env *>(vp);
    env->mContext->setupCheck();

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

extern "C" void drawRect(void *vp, int32_t x1, int32_t x2, int32_t y1, int32_t y2)
{
    x1 = (x1 << 16);
    x2 = (x2 << 16);
    y1 = (y1 << 16);
    y2 = (y2 << 16);

    int32_t vtx[] = {x1,y1, x1,y2, x2,y1, x2,y2};
    static const int32_t tex[] = {0,0, 0,0x10000, 0x10000,0, 0x10000,0x10000};


    ScriptC::Env * env = static_cast<ScriptC::Env *>(vp);
    env->mContext->setupCheck();

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

extern "C" void pfBindTexture(void *vp, RsProgramFragment vpf, uint32_t slot, RsAllocation va)
{
    //LOGE("pfBindTexture %p", vpf);
    ScriptC::Env * env = static_cast<ScriptC::Env *>(vp);
    rsi_ProgramFragmentBindTexture(env->mContext,
                                   static_cast<ProgramFragment *>(vpf),
                                   slot,
                                   static_cast<Allocation *>(va));

}

extern "C" void pfBindSampler(void *vp, RsProgramFragment vpf, uint32_t slot, RsSampler vs)
{
    ScriptC::Env * env = static_cast<ScriptC::Env *>(vp);
    rsi_ProgramFragmentBindSampler(env->mContext,
                                   static_cast<ProgramFragment *>(vpf),
                                   slot,
                                   static_cast<Sampler *>(vs));

}

extern "C" void contextBindProgramFragmentStore(void *vp, RsProgramFragmentStore pfs)
{
    //LOGE("contextBindProgramFragmentStore %p", pfs);
    ScriptC::Env * env = static_cast<ScriptC::Env *>(vp);
    rsi_ContextBindProgramFragmentStore(env->mContext, pfs);

}

extern "C" void contextBindProgramFragment(void *vp, RsProgramFragment pf)
{
    //LOGE("contextBindProgramFragment %p", pf);
    ScriptC::Env * env = static_cast<ScriptC::Env *>(vp);
    rsi_ContextBindProgramFragment(env->mContext, pf);

}


static rsc_FunctionTable scriptCPtrTable = {
    loadVp,
    loadF,
    loadI32,
    loadU32,
    loadEnvVec4,
    loadEnvMatrix,

    storeF,
    storeI32,
    storeU32,
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


bool ScriptC::run(Context *rsc, uint32_t launchID)
{
    Env e = {rsc, this};

    if (mEnviroment.mFragmentStore.get()) {
        rsc->setFragmentStore(mEnviroment.mFragmentStore.get());
    }
    if (mEnviroment.mFragment.get()) {
        rsc->setFragment(mEnviroment.mFragment.get());
    }
    if (mEnviroment.mVertex.get()) {
        rsc->setVertex(mEnviroment.mVertex.get());
    }

    return mProgram.mScript(&e, &scriptCPtrTable, launchID) != 0;
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


void ScriptCState::runCompiler(Context *rsc)
{
    mAccScript = accCreateScript();
    String8 tmp;

    rsc->appendNameDefines(&tmp);

    const char* scriptSource[] = {tmp.string(), mProgram.mScriptText};
    int scriptLength[] = {tmp.length(), mProgram.mScriptTextLength} ;
    accScriptSource(mAccScript, sizeof(scriptLength) / sizeof(int), scriptSource, scriptLength);
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
            LOGE("pragma %i %s %s", ct, str[ct], str[ct+1]);

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


