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


//////////////////////////////////////////////////////////////////////////////
// IO routines
//////////////////////////////////////////////////////////////////////////////

static float SC_loadF(uint32_t bank, uint32_t offset)
{
    GET_TLS();
    const void *vp = sc->mSlots[bank]->getPtr();
    const float *f = static_cast<const float *>(vp);
    //LOGE("loadF %i %i = %f %x", bank, offset, f, ((int *)&f)[0]);
    return f[offset];
}

static int32_t SC_loadI32(uint32_t bank, uint32_t offset)
{
    GET_TLS();
    const void *vp = sc->mSlots[bank]->getPtr();
    const int32_t *i = static_cast<const int32_t *>(vp);
    //LOGE("loadI32 %i %i = %i", bank, offset, t);
    return i[offset];
}

static uint32_t SC_loadU32(uint32_t bank, uint32_t offset)
{
    GET_TLS();
    const void *vp = sc->mSlots[bank]->getPtr();
    const uint32_t *i = static_cast<const uint32_t *>(vp);
    return i[offset];
}

static void SC_loadVec4(uint32_t bank, uint32_t offset, rsc_Vector4 *v)
{
    GET_TLS();
    const void *vp = sc->mSlots[bank]->getPtr();
    const float *f = static_cast<const float *>(vp);
    memcpy(v, &f[offset], sizeof(rsc_Vector4));
}

static void SC_loadMatrix(uint32_t bank, uint32_t offset, rsc_Matrix *m)
{
    GET_TLS();
    const void *vp = sc->mSlots[bank]->getPtr();
    const float *f = static_cast<const float *>(vp);
    memcpy(m, &f[offset], sizeof(rsc_Matrix));
}


static void SC_storeF(uint32_t bank, uint32_t offset, float v)
{
    //LOGE("storeF %i %i %f", bank, offset, v);
    GET_TLS();
    void *vp = sc->mSlots[bank]->getPtr();
    float *f = static_cast<float *>(vp);
    f[offset] = v;
}

static void SC_storeI32(uint32_t bank, uint32_t offset, int32_t v)
{
    GET_TLS();
    void *vp = sc->mSlots[bank]->getPtr();
    int32_t *f = static_cast<int32_t *>(vp);
    static_cast<int32_t *>(sc->mSlots[bank]->getPtr())[offset] = v;
}

static void SC_storeU32(uint32_t bank, uint32_t offset, uint32_t v)
{
    GET_TLS();
    void *vp = sc->mSlots[bank]->getPtr();
    uint32_t *f = static_cast<uint32_t *>(vp);
    static_cast<uint32_t *>(sc->mSlots[bank]->getPtr())[offset] = v;
}

static void SC_storeVec4(uint32_t bank, uint32_t offset, const rsc_Vector4 *v)
{
    GET_TLS();
    void *vp = sc->mSlots[bank]->getPtr();
    float *f = static_cast<float *>(vp);
    memcpy(&f[offset], v, sizeof(rsc_Vector4));
}

static void SC_storeMatrix(uint32_t bank, uint32_t offset, const rsc_Matrix *m)
{
    GET_TLS();
    void *vp = sc->mSlots[bank]->getPtr();
    float *f = static_cast<float *>(vp);
    memcpy(&f[offset], m, sizeof(rsc_Matrix));
}


//////////////////////////////////////////////////////////////////////////////
// Math routines
//////////////////////////////////////////////////////////////////////////////

static float SC_randf(float max)
{
    float r = (float)rand();
    return r / RAND_MAX * max;
}





//////////////////////////////////////////////////////////////////////////////
// Matrix routines
//////////////////////////////////////////////////////////////////////////////


static void SC_matrixLoadIdentity(rsc_Matrix *mat)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->loadIdentity();
}

static void SC_matrixLoadFloat(rsc_Matrix *mat, const float *f)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->load(f);
}

static void SC_matrixLoadMat(rsc_Matrix *mat, const rsc_Matrix *newmat)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->load(reinterpret_cast<const Matrix *>(newmat));
}

static void SC_matrixLoadRotate(rsc_Matrix *mat, float rot, float x, float y, float z)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->loadRotate(rot, x, y, z);
}

static void SC_matrixLoadScale(rsc_Matrix *mat, float x, float y, float z)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->loadScale(x, y, z);
}

static void SC_matrixLoadTranslate(rsc_Matrix *mat, float x, float y, float z)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->loadTranslate(x, y, z);
}

static void SC_matrixLoadMultiply(rsc_Matrix *mat, const rsc_Matrix *lhs, const rsc_Matrix *rhs)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->loadMultiply(reinterpret_cast<const Matrix *>(lhs),
                    reinterpret_cast<const Matrix *>(rhs));
}

static void SC_matrixMultiply(rsc_Matrix *mat, const rsc_Matrix *rhs)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->multiply(reinterpret_cast<const Matrix *>(rhs));
}

static void SC_matrixRotate(rsc_Matrix *mat, float rot, float x, float y, float z)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->rotate(rot, x, y, z);
}

static void SC_matrixScale(rsc_Matrix *mat, float x, float y, float z)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->scale(x, y, z);
}

static void SC_matrixTranslate(rsc_Matrix *mat, float x, float y, float z)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->translate(x, y, z);
}




//////////////////////////////////////////////////////////////////////////////
// Context
//////////////////////////////////////////////////////////////////////////////

static void SC_bindTexture(RsProgramFragment vpf, uint32_t slot, RsAllocation va)
{
    GET_TLS();
    rsi_ProgramFragmentBindTexture(rsc,
                                   static_cast<ProgramFragment *>(vpf),
                                   slot,
                                   static_cast<Allocation *>(va));

}

static void SC_bindSampler(RsProgramFragment vpf, uint32_t slot, RsSampler vs)
{
    GET_TLS();
    rsi_ProgramFragmentBindSampler(rsc,
                                   static_cast<ProgramFragment *>(vpf),
                                   slot,
                                   static_cast<Sampler *>(vs));

}

static void SC_bindProgramFragmentStore(RsProgramFragmentStore pfs)
{
    GET_TLS();
    rsi_ContextBindProgramFragmentStore(rsc, pfs);

}

static void SC_bindProgramFragment(RsProgramFragment pf)
{
    GET_TLS();
    rsi_ContextBindProgramFragment(rsc, pf);

}


//////////////////////////////////////////////////////////////////////////////
// Drawing
//////////////////////////////////////////////////////////////////////////////

static void SC_drawTriangleMesh(RsTriangleMesh mesh)
{
    GET_TLS();
    rsi_TriangleMeshRender(rsc, mesh);
}

static void SC_drawTriangleMeshRange(RsTriangleMesh mesh, uint32_t start, uint32_t count)
{
    GET_TLS();
    rsi_TriangleMeshRenderRange(rsc, mesh, start, count);
}

// Assumes (GL_FIXED) x,y,z (GL_UNSIGNED_BYTE)r,g,b,a
static void SC_drawTriangleArray(int ialloc, uint32_t count)
{
    GET_TLS();
    RsAllocation alloc = (RsAllocation)ialloc;

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
    static const float tex[] = {0,1, 1,1, 1,0, 0,0};


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

//////////////////////////////////////////////////////////////////////////////
//
//////////////////////////////////////////////////////////////////////////////

extern "C" const void * loadVp(uint32_t bank, uint32_t offset)
{
    GET_TLS();
    return &static_cast<const uint8_t *>(sc->mSlots[bank]->getPtr())[offset];
}



static void SC_color(float r, float g, float b, float a)
{
    glColor4f(r, g, b, a);
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



static void SC_ClearColor(float r, float g, float b, float a)
{
    //LOGE("c %f %f %f %f", r, g, b, a);
    GET_TLS();
    sc->mEnviroment.mClearColor[0] = r;
    sc->mEnviroment.mClearColor[1] = g;
    sc->mEnviroment.mClearColor[2] = b;
    sc->mEnviroment.mClearColor[3] = a;
}



//////////////////////////////////////////////////////////////////////////////
// Class implementation
//////////////////////////////////////////////////////////////////////////////

ScriptCState::SymbolTable_t ScriptCState::gSyms[] = {
    // IO
    { "loadI32", (void *)&SC_loadI32,
        "int", "(int, int)" },
    //{ "loadU32", (void *)&SC_loadU32, "unsigned int", "(int, int)" },
    { "loadF", (void *)&SC_loadF,
        "float", "(int, int)" },
    { "loadVec4", (void *)&SC_loadVec4,
        "void", "(int, int, float *)" },
    { "loadMatrix", (void *)&SC_loadMatrix,
        "void", "(int, int, float *)" },
    { "storeI32", (void *)&SC_storeI32,
        "void", "(int, int, int)" },
    //{ "storeU32", (void *)&SC_storeU32, "void", "(int, int, unsigned int)" },
    { "storeF", (void *)&SC_storeF,
        "void", "(int, int, float)" },
    { "storeVec4", (void *)&SC_storeVec4,
        "void", "(int, int, float *)" },
    { "storeMatrix", (void *)&SC_storeMatrix,
        "void", "(int, int, float *)" },

    // math
    { "sinf", (void *)&sinf,
        "float", "(float)" },
    { "cosf", (void *)&cosf,
        "float", "(float)" },
    { "fabs", (void *)&fabs,
        "float", "(float)" },
    { "randf", (void *)&SC_randf,
        "float", "(float)" },

    // matrix
    { "matrixLoadIdentity", (void *)&SC_matrixLoadIdentity,
        "void", "(float *mat)" },
    { "matrixLoadFloat", (void *)&SC_matrixLoadFloat,
        "void", "(float *mat, float *f)" },
    { "matrixLoadMat", (void *)&SC_matrixLoadMat,
        "void", "(float *mat, float *newmat)" },
    { "matrixLoadRotate", (void *)&SC_matrixLoadRotate,
        "void", "(float *mat, float rot, float x, float y, float z)" },
    { "matrixLoadScale", (void *)&SC_matrixLoadScale,
        "void", "(float *mat, float x, float y, float z)" },
    { "matrixLoadTranslate", (void *)&SC_matrixLoadTranslate,
        "void", "(float *mat, float x, float y, float z)" },
    { "matrixLoadMultiply", (void *)&SC_matrixLoadMultiply,
        "void", "(float *mat, float *lhs, float *rhs)" },
    { "matrixMultiply", (void *)&SC_matrixMultiply,
        "void", "(float *mat, float *rhs)" },
    { "matrixRotate", (void *)&SC_matrixRotate,
        "void", "(float *mat, float rot, float x, float y, float z)" },
    { "matrixScale", (void *)&SC_matrixScale,
        "void", "(float *mat, float x, float y, float z)" },
    { "matrixTranslate", (void *)&SC_matrixTranslate,
        "void", "(float *mat, float x, float y, float z)" },

    // context
    { "bindProgramFragment", (void *)&SC_bindProgramFragment,
        "void", "(int)" },
    { "bindProgramFragmentStore", (void *)&SC_bindProgramFragmentStore,
        "void", "(int)" },
    { "bindSampler", (void *)&SC_bindSampler,
        "void", "(int, int, int)" },
    { "bindTexture", (void *)&SC_bindTexture,
        "void", "(int, int, int)" },

    // drawing
    { "drawQuad", (void *)&SC_drawQuad,
        "void", "(float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3, float x4, float y4, float z4)" },
    { "drawTriangleArray", (void *)&SC_drawTriangleArray,
        "void", "(int ialloc, int count)" },
    { "drawTriangleMesh", (void *)&SC_drawTriangleMesh,
        "void", "(int mesh)" },
    { "drawTriangleMeshRange", (void *)&SC_drawTriangleMeshRange,
        "void", "(int mesh, int start, int count)" },


    // misc
    { "pfClearColor", (void *)&SC_ClearColor,
        "void", "(float, float, float, float)" },

    { "color", (void *)&SC_color,
        "void", "(float, float, float, float)" },

    { NULL, NULL, NULL, NULL }
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

void ScriptCState::appendDecls(String8 *str)
{
    ScriptCState::SymbolTable_t *syms = gSyms;
    while (syms->mPtr) {
        str->append(syms->mRet);
        str->append(" ");
        str->append(syms->mName);
        str->append(syms->mParam);
        str->append(";\n");
        syms++;
    }
}


