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
#include "rsProgramVertex.h"

#include <GLES/gl.h>
#include <GLES/glext.h>

using namespace android;
using namespace android::renderscript;


ProgramVertex::ProgramVertex(Element *in, Element *out) :
    Program(in, out)
{
    mTextureMatrixEnable = false;
}

ProgramVertex::~ProgramVertex()
{
}

static void logMatrix(const char *txt, const float *f)
{
    LOGE("Matrix %s, %p", txt, f);
    LOGE("%6.2f, %6.2f, %6.2f, %6.2f", f[0], f[4], f[8], f[12]);
    LOGE("%6.2f, %6.2f, %6.2f, %6.2f", f[1], f[5], f[9], f[13]);
    LOGE("%6.2f, %6.2f, %6.2f, %6.2f", f[2], f[6], f[10], f[14]);
    LOGE("%6.2f, %6.2f, %6.2f, %6.2f", f[3], f[7], f[11], f[15]);
}

void ProgramVertex::setupGL()
{
    const float *f = static_cast<const float *>(mConstants[0]->getPtr());

    glMatrixMode(GL_TEXTURE);
    if (mTextureMatrixEnable) {
        glLoadMatrixf(&f[RS_PROGRAM_VERTEX_TEXTURE_OFFSET]);
    } else {
        glLoadIdentity();
    }

    //logMatrix("prog", &f[RS_PROGRAM_VERTEX_PROJECTION_OFFSET]);
    //logMatrix("model", &f[RS_PROGRAM_VERTEX_MODELVIEW_OFFSET]);

    glMatrixMode(GL_PROJECTION);
    glLoadMatrixf(&f[RS_PROGRAM_VERTEX_PROJECTION_OFFSET]);
    glMatrixMode(GL_MODELVIEW);
    glLoadMatrixf(&f[RS_PROGRAM_VERTEX_MODELVIEW_OFFSET]);
}

void ProgramVertex::setConstantType(uint32_t slot, const Type *t)
{
    mConstantTypes[slot].set(t);
}

void ProgramVertex::bindAllocation(uint32_t slot, Allocation *a)
{
    mConstants[slot].set(a);
}


ProgramVertexState::ProgramVertexState()
{
    mPV = NULL;
}

ProgramVertexState::~ProgramVertexState()
{
    delete mPV;
}

void ProgramVertexState::init(Context *rsc, int32_t w, int32_t h)
{
    ProgramVertex *pv = new ProgramVertex(NULL, NULL);
    Allocation *alloc = (Allocation *)
        rsi_AllocationCreatePredefSized(rsc, RS_ELEMENT_USER_FLOAT, 48);
    mDefaultAlloc.set(alloc);
    mDefault.set(pv);

    pv->bindAllocation(0, alloc);
    
    Matrix m;
    m.loadOrtho(0,w, h,0, -1,1);
    alloc->subData(RS_PROGRAM_VERTEX_PROJECTION_OFFSET, 16, &m.m[0]);

    m.loadIdentity();
    alloc->subData(RS_PROGRAM_VERTEX_MODELVIEW_OFFSET, 16, &m.m[0]);
}


namespace android {
namespace renderscript {

void rsi_ProgramVertexBegin(Context *rsc, RsElement in, RsElement out)
{
    delete rsc->mStateVertex.mPV;
    rsc->mStateVertex.mPV = new ProgramVertex((Element *)in, (Element *)out);
}

RsProgramVertex rsi_ProgramVertexCreate(Context *rsc)
{
    ProgramVertex *pv = rsc->mStateVertex.mPV;
    pv->incRef();
    rsc->mStateVertex.mPV = 0;
    return pv;
}

void rsi_ProgramVertexBindAllocation(Context *rsc, RsProgramVertex vpgm, uint32_t slot, RsAllocation constants)
{
    ProgramVertex *pv = static_cast<ProgramVertex *>(vpgm);
    pv->bindAllocation(slot, static_cast<Allocation *>(constants));
}

void rsi_ProgramVertexSetType(Context *rsc, uint32_t slot, RsType constants)
{
    rsc->mStateVertex.mPV->setConstantType(slot, static_cast<const Type *>(constants));
}

void rsi_ProgramVertexSetTextureMatrixEnable(Context *rsc, bool enable)
{
    rsc->mStateVertex.mPV->setTextureMatrixEnable(enable);
}



}
}
