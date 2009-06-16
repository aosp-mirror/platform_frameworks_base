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
#include "rsProgramFragment.h"

using namespace android;
using namespace android::renderscript;


ProgramFragment::ProgramFragment(Element *in, Element *out) :
    Program(in, out)
{
    for (uint32_t ct=0; ct < MAX_TEXTURE; ct++) {
        mEnvModes[ct] = RS_TEX_ENV_MODE_REPLACE;
        mTextureDimensions[ct] = 2;
    }
    mTextureEnableMask = 0;
}

ProgramFragment::~ProgramFragment()
{
}

void ProgramFragment::setupGL()
{
    for (uint32_t ct=0; ct < MAX_TEXTURE; ct++) {
        glActiveTexture(GL_TEXTURE0 + ct);
        if (!(mTextureEnableMask & (1 << ct)) ||
            //!mSamplers[ct].get() ||
            !mTextures[ct].get()) {

            glDisable(GL_TEXTURE_2D);
            continue;
        }

        glEnable(GL_TEXTURE_2D);
        glBindTexture(GL_TEXTURE_2D, mTextures[ct]->getTextureID());

        switch(mEnvModes[ct]) {
        case RS_TEX_ENV_MODE_REPLACE:
            glTexEnvf(GL_TEXTURE_2D, GL_TEXTURE_ENV_MODE, GL_REPLACE);
            break;
        case RS_TEX_ENV_MODE_MODULATE:
            glTexEnvf(GL_TEXTURE_2D, GL_TEXTURE_ENV_MODE, GL_MODULATE);
            break;
        case RS_TEX_ENV_MODE_DECAL:
            glTexEnvf(GL_TEXTURE_2D, GL_TEXTURE_ENV_MODE, GL_DECAL);
            break;
        }

        if (mSamplers[ct].get()) {
            mSamplers[ct]->setupGL();
        } else {
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        }
    }
    glActiveTexture(GL_TEXTURE0);
}


void ProgramFragment::bindTexture(uint32_t slot, Allocation *a)
{
    if (slot >= MAX_TEXTURE) {
        LOGE("Attempt to bind a texture to a slot > MAX_TEXTURE");
        return;
    }

    mTextures[slot].set(a);
}

void ProgramFragment::bindSampler(uint32_t slot, Sampler *s)
{
    if (slot >= MAX_TEXTURE) {
        LOGE("Attempt to bind a Sampler to a slot > MAX_TEXTURE");
        return;
    }

    mSamplers[slot].set(s);
}

void ProgramFragment::setType(uint32_t slot, const Element *e, uint32_t dim)
{
    if (slot >= MAX_TEXTURE) {
        LOGE("Attempt to setType to a slot > MAX_TEXTURE");
        return;
    }

    if (dim >= 4) {
        LOGE("Attempt to setType to a dimension > 3");
        return;
    }

    mTextureFormats[slot].set(e);
    mTextureDimensions[slot] = dim;
}

void ProgramFragment::setEnvMode(uint32_t slot, RsTexEnvMode env)
{
    if (slot >= MAX_TEXTURE) {
        LOGE("Attempt to setEnvMode to a slot > MAX_TEXTURE");
        return;
    }

    mEnvModes[slot] = env;
}

void ProgramFragment::setTexEnable(uint32_t slot, bool enable)
{
    if (slot >= MAX_TEXTURE) {
        LOGE("Attempt to setEnvMode to a slot > MAX_TEXTURE");
        return;
    }

    uint32_t bit = 1 << slot;
    mTextureEnableMask &= ~bit;
    if (enable) {
        mTextureEnableMask |= bit;
    }
}



ProgramFragmentState::ProgramFragmentState()
{
    mPF = NULL;
}

ProgramFragmentState::~ProgramFragmentState()
{
    delete mPF;

}



namespace android {
namespace renderscript {

void rsi_ProgramFragmentBegin(Context * rsc, RsElement in, RsElement out)
{
    delete rsc->mStateFragment.mPF;
    rsc->mStateFragment.mPF = new ProgramFragment((Element *)in, (Element *)out);
}

void rsi_ProgramFragmentBindTexture(Context *rsc, RsProgramFragment vpf, uint32_t slot, RsAllocation a)
{
    ProgramFragment *pf = static_cast<ProgramFragment *>(vpf);
    pf->bindTexture(slot, static_cast<Allocation *>(a));

    //LOGE("%p %p", pf, rsc->getFragment());
    if (pf == rsc->getFragment()) {
        pf->setupGL();
    }
}

void rsi_ProgramFragmentBindSampler(Context *rsc, RsProgramFragment vpf, uint32_t slot, RsSampler s)
{
    ProgramFragment *pf = static_cast<ProgramFragment *>(vpf);
    pf->bindSampler(slot, static_cast<Sampler *>(s));

    if (pf == rsc->getFragment()) {
        pf->setupGL();
    }
}

void rsi_ProgramFragmentSetType(Context *rsc, uint32_t slot, RsType vt)
{
    const Type *t = static_cast<const Type *>(vt);
    uint32_t dim = 1;
    if (t->getDimY()) {
        dim ++;
        if (t->getDimZ()) {
            dim ++;
        }
    }

    rsc->mStateFragment.mPF->setType(slot, t->getElement(), dim);
}

void rsi_ProgramFragmentSetEnvMode(Context *rsc, uint32_t slot, RsTexEnvMode env)
{
    rsc->mStateFragment.mPF->setEnvMode(slot, env);
}

void rsi_ProgramFragmentSetTexEnable(Context *rsc, uint32_t slot, bool enable)
{
    rsc->mStateFragment.mPF->setTexEnable(slot, enable);
}

RsProgramFragment rsi_ProgramFragmentCreate(Context *rsc)
{
    ProgramFragment *pf = rsc->mStateFragment.mPF;
    pf->incRef();
    rsc->mStateFragment.mPF = 0;
    return pf;
}

void rsi_ProgramFragmentDestroy(Context *rsc, RsProgramFragment vpf)
{
    ProgramFragment *pf = (ProgramFragment *)vpf;
    if (pf->getName()) {
        rsc->removeName(pf);
    }
    pf->decRef();
}


}
}

