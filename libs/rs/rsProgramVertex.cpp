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

using namespace android;
using namespace android::renderscript;


ProgramVertex::ProgramVertex(Element *in, Element *out) :
    Program(in, out)
{
    mTextureMatrixEnable = false;
    mProjectionEnable = false;
    mTransformEnable = false;
}

ProgramVertex::~ProgramVertex()
{
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


    glMatrixMode(GL_PROJECTION);
    if (mProjectionEnable) {
        glLoadMatrixf(&f[RS_PROGRAM_VERTEX_PROJECTION_OFFSET]);
    } else {
    }

    glMatrixMode(GL_MODELVIEW);
    if (mTransformEnable) {
        glLoadMatrixf(&f[RS_PROGRAM_VERTEX_MODELVIEW_OFFSET]);
    } else {
        glLoadIdentity();
    }
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

void rsi_ProgramVertexSetCameraMode(Context *rsc, bool ortho)
{
    rsc->mStateVertex.mPV->setProjectionEnabled(!ortho);
}

void rsi_ProgramVertexSetTextureMatrixEnable(Context *rsc, bool enable)
{
    rsc->mStateVertex.mPV->setTextureMatrixEnable(enable);
}

void rsi_ProgramVertexSetModelMatrixEnable(Context *rsc, bool enable)
{
    rsc->mStateVertex.mPV->setTransformEnable(enable);
}

void rsi_ProgramVertexSetProjectionMatrixEnable(Context *rsc, bool enable)
{
    rsc->mStateVertex.mPV->setProjectionEnable(enable);
}



}
}
