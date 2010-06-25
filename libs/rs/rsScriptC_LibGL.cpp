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
#include "utils/Timers.h"

#define GL_GLEXT_PROTOTYPES

#include <GLES/gl.h>
#include <GLES/glext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <time.h>

using namespace android;
using namespace android::renderscript;

#define GET_TLS()  Context::ScriptTLSStruct * tls = \
    (Context::ScriptTLSStruct *)pthread_getspecific(Context::gThreadTLSKey); \
    Context * rsc = tls->mContext; \
    ScriptC * sc = (ScriptC *) tls->mScript


//////////////////////////////////////////////////////////////////////////////
// IO routines
//////////////////////////////////////////////////////////////////////////////

static void SC_updateSimpleMesh(RsSimpleMesh mesh)
{
    GET_TLS();
    SimpleMesh *sm = static_cast<SimpleMesh *>(mesh);
    sm->uploadAll(rsc);
}


//////////////////////////////////////////////////////////////////////////////
// Context
//////////////////////////////////////////////////////////////////////////////

static void SC_bindTexture(RsProgramFragment vpf, uint32_t slot, RsAllocation va)
{
    GET_TLS();
    rsi_ProgramBindTexture(rsc,
                           static_cast<ProgramFragment *>(vpf),
                           slot,
                           static_cast<Allocation *>(va));

}

static void SC_bindSampler(RsProgramFragment vpf, uint32_t slot, RsSampler vs)
{
    GET_TLS();
    rsi_ProgramBindSampler(rsc,
                           static_cast<ProgramFragment *>(vpf),
                           slot,
                           static_cast<Sampler *>(vs));

}

static void SC_bindProgramStore(RsProgramStore pfs)
{
    GET_TLS();
    rsi_ContextBindProgramStore(rsc, pfs);
}

static void SC_bindProgramFragment(RsProgramFragment pf)
{
    GET_TLS();
    rsi_ContextBindProgramFragment(rsc, pf);
}

static void SC_bindProgramVertex(RsProgramVertex pv)
{
    GET_TLS();
    rsi_ContextBindProgramVertex(rsc, pv);
}

static void SC_bindProgramRaster(RsProgramRaster pv)
{
    GET_TLS();
    rsi_ContextBindProgramRaster(rsc, pv);
}

//////////////////////////////////////////////////////////////////////////////
// VP
//////////////////////////////////////////////////////////////////////////////

static void SC_vpLoadModelMatrix(const rsc_Matrix *m)
{
    GET_TLS();
    rsc->getVertex()->setModelviewMatrix(m);
}

static void SC_vpLoadTextureMatrix(const rsc_Matrix *m)
{
    GET_TLS();
    rsc->getVertex()->setTextureMatrix(m);
}



//////////////////////////////////////////////////////////////////////////////
// Drawing
//////////////////////////////////////////////////////////////////////////////

static void SC_drawQuadTexCoords(float x1, float y1, float z1,
                                 float u1, float v1,
                                 float x2, float y2, float z2,
                                 float u2, float v2,
                                 float x3, float y3, float z3,
                                 float u3, float v3,
                                 float x4, float y4, float z4,
                                 float u4, float v4)
{
    GET_TLS();
    if (!rsc->setupCheck()) {
        return;
    }

    //LOGE("Quad");
    //LOGE("%4.2f, %4.2f, %4.2f", x1, y1, z1);
    //LOGE("%4.2f, %4.2f, %4.2f", x2, y2, z2);
    //LOGE("%4.2f, %4.2f, %4.2f", x3, y3, z3);
    //LOGE("%4.2f, %4.2f, %4.2f", x4, y4, z4);

    float vtx[] = {x1,y1,z1, x2,y2,z2, x3,y3,z3, x4,y4,z4};
    const float tex[] = {u1,v1, u2,v2, u3,v3, u4,v4};

    VertexArray va;
    va.add(GL_FLOAT, 3, 12, false, (uint32_t)vtx, "position");
    va.add(GL_FLOAT, 2, 8, false, (uint32_t)tex, "texture0");
    va.setupGL2(rsc, &rsc->mStateVertexArray, &rsc->mShaderCache);

    glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
}

static void SC_drawQuad(float x1, float y1, float z1,
                        float x2, float y2, float z2,
                        float x3, float y3, float z3,
                        float x4, float y4, float z4)
{
    SC_drawQuadTexCoords(x1, y1, z1, 0, 1,
                         x2, y2, z2, 1, 1,
                         x3, y3, z3, 1, 0,
                         x4, y4, z4, 0, 0);
}

static void SC_drawSpriteScreenspace(float x, float y, float z, float w, float h)
{
    GET_TLS();
    ObjectBaseRef<const ProgramVertex> tmp(rsc->getVertex());
    rsc->setVertex(rsc->getDefaultProgramVertex());
    //rsc->setupCheck();

    //GLint crop[4] = {0, h, w, -h};

    float sh = rsc->getHeight();

    SC_drawQuad(x,   sh - y,     z,
                x+w, sh - y,     z,
                x+w, sh - (y+h), z,
                x,   sh - (y+h), z);
    rsc->setVertex((ProgramVertex *)tmp.get());
}
/*
static void SC_drawSprite(float x, float y, float z, float w, float h)
{
    GET_TLS();
    float vin[3] = {x, y, z};
    float vout[4];

    //LOGE("ds  in %f %f %f", x, y, z);
    rsc->getVertex()->transformToScreen(rsc, vout, vin);
    //LOGE("ds  out %f %f %f %f", vout[0], vout[1], vout[2], vout[3]);
    vout[0] /= vout[3];
    vout[1] /= vout[3];
    vout[2] /= vout[3];

    vout[0] *= rsc->getWidth() / 2;
    vout[1] *= rsc->getHeight() / 2;
    vout[0] += rsc->getWidth() / 2;
    vout[1] += rsc->getHeight() / 2;

    vout[0] -= w/2;
    vout[1] -= h/2;

    //LOGE("ds  out2 %f %f %f", vout[0], vout[1], vout[2]);

    // U, V, W, H
    SC_drawSpriteScreenspace(vout[0], vout[1], z, h, w);
    //rsc->setupCheck();
}
*/

static void SC_drawRect(float x1, float y1,
                        float x2, float y2, float z)
{
    //LOGE("SC_drawRect %f,%f  %f,%f  %f", x1, y1, x2, y2, z);
    SC_drawQuad(x1, y2, z,
                x2, y2, z,
                x2, y1, z,
                x1, y1, z);
}

static void SC_drawSimpleMesh(RsSimpleMesh vsm)
{
    GET_TLS();
    SimpleMesh *sm = static_cast<SimpleMesh *>(vsm);
    if (!rsc->setupCheck()) {
        return;
    }
    sm->render(rsc);
}

static void SC_drawSimpleMeshRange(RsSimpleMesh vsm, uint32_t start, uint32_t len)
{
    GET_TLS();
    SimpleMesh *sm = static_cast<SimpleMesh *>(vsm);
    if (!rsc->setupCheck()) {
        return;
    }
    sm->renderRange(rsc, start, len);
}


//////////////////////////////////////////////////////////////////////////////
//
//////////////////////////////////////////////////////////////////////////////


static void SC_color(float r, float g, float b, float a)
{
    GET_TLS();
    rsc->mStateVertex.color[0] = r;
    rsc->mStateVertex.color[1] = g;
    rsc->mStateVertex.color[2] = b;
    rsc->mStateVertex.color[3] = a;
    if (!rsc->checkVersion2_0()) {
        glColor4f(r, g, b, a);
    }
}

static void SC_uploadToTexture2(RsAllocation va, uint32_t baseMipLevel)
{
    GET_TLS();
    rsi_AllocationUploadToTexture(rsc, va, false, baseMipLevel);
}
static void SC_uploadToTexture(RsAllocation va)
{
    GET_TLS();
    rsi_AllocationUploadToTexture(rsc, va, false, 0);
}

static void SC_uploadToBufferObject(RsAllocation va)
{
    GET_TLS();
    rsi_AllocationUploadToBufferObject(rsc, va);
}

static void SC_ClearColor(float r, float g, float b, float a)
{
    GET_TLS();
    if (!rsc->setupCheck()) {
        return;
    }

    glClearColor(r, g, b, a);
    glClear(GL_COLOR_BUFFER_BIT);
}

static void SC_ClearDepth(float v)
{
    GET_TLS();
    if (!rsc->setupCheck()) {
        return;
    }

    glClearDepthf(v);
    glClear(GL_DEPTH_BUFFER_BIT);
}

static uint32_t SC_getWidth()
{
    GET_TLS();
    return rsc->getWidth();
}

static uint32_t SC_getHeight()
{
    GET_TLS();
    return rsc->getHeight();
}

static void SC_DrawTextAlloc(RsAllocation va, int x, int y)
{
    GET_TLS();
    Allocation *alloc = static_cast<Allocation *>(va);
    rsc->mStateFont.renderText(alloc, x, y);
}

static void SC_DrawText(const char *text, int x, int y)
{
    GET_TLS();
    rsc->mStateFont.renderText(text, x, y);
}

static void SC_BindFont(RsFont font)
{
    GET_TLS();
    rsi_ContextBindFont(rsc, font);
}

//////////////////////////////////////////////////////////////////////////////
// Class implementation
//////////////////////////////////////////////////////////////////////////////

// llvm name mangling ref
//  <builtin-type> ::= v  # void
//                 ::= b  # bool
//                 ::= c  # char
//                 ::= a  # signed char
//                 ::= h  # unsigned char
//                 ::= s  # short
//                 ::= t  # unsigned short
//                 ::= i  # int
//                 ::= j  # unsigned int
//                 ::= l  # long
//                 ::= m  # unsigned long
//                 ::= x  # long long, __int64
//                 ::= y  # unsigned long long, __int64
//                 ::= f  # float
//                 ::= d  # double

static ScriptCState::SymbolTable_t gSyms[] = {
    { "rsgBindProgramFragment", (void *)&SC_bindProgramFragment },
    { "rsgBindProgramStore", (void *)&SC_bindProgramStore },
    { "rsgBindProgramVertex", (void *)&SC_bindProgramVertex },
    { "rsgBindProgramRaster", (void *)&SC_bindProgramRaster },
    { "rsgBindSampler", (void *)&SC_bindSampler },
    { "rsgBindTexture", (void *)&SC_bindTexture },

    { "rsgProgramVertexLoadModelMatrix", (void *)&SC_vpLoadModelMatrix },
    { "rsgProgramVertexLoadTextureMatrix", (void *)&SC_vpLoadTextureMatrix },

    { "rsgGetWidth", (void *)&SC_getWidth },
    { "rsgGetHeight", (void *)&SC_getHeight },

    { "_Z18rsgUploadToTexture13rs_allocationi", (void *)&SC_uploadToTexture2 },
    { "_Z18rsgUploadToTexture13rs_allocation", (void *)&SC_uploadToTexture },
    { "rsgUploadToBufferObject", (void *)&SC_uploadToBufferObject },

    { "rsgDrawRect", (void *)&SC_drawRect },
    { "rsgDrawQuad", (void *)&SC_drawQuad },
    { "rsgDrawQuadTexCoords", (void *)&SC_drawQuadTexCoords },
    //{ "drawSprite", (void *)&SC_drawSprite },
    { "rsgDrawSpriteScreenspace", (void *)&SC_drawSpriteScreenspace },
    { "_Z17rsgDrawSimpleMesh7rs_mesh", (void *)&SC_drawSimpleMesh },
    { "_Z17rsgDrawSimpleMesh7rs_meshii", (void *)&SC_drawSimpleMeshRange },

    { "rsgClearColor", (void *)&SC_ClearColor },
    { "rsgClearDepth", (void *)&SC_ClearDepth },

    { "_Z11rsgDrawTextPKcii", (void *)&SC_DrawText },
    { "_Z11rsgDrawText13rs_allocationii", (void *)&SC_DrawTextAlloc },

    { "rsgBindFont", (void *)&SC_BindFont },


    //////////////////////////////////////
    // IO
    { "updateSimpleMesh", (void *)&SC_updateSimpleMesh },

    // misc
    { "color", (void *)&SC_color },

    { NULL, NULL }
};

const ScriptCState::SymbolTable_t * ScriptCState::lookupSymbolGL(const char *sym)
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

