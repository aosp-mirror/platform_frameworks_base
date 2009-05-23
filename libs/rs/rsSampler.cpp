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


#include <GLES/gl.h>
#include <GLES/glext.h>
#include <utils/Log.h>

#include "rsContext.h"
#include "rsSampler.h"

using namespace android;
using namespace android::renderscript;


Sampler::Sampler()
{
    // Should not get called.
    rsAssert(0);
}

Sampler::Sampler(RsSamplerValue magFilter,
                 RsSamplerValue minFilter,
                 RsSamplerValue wrapS,
                 RsSamplerValue wrapT,
                 RsSamplerValue wrapR)
{
    mMagFilter = magFilter;
    mMinFilter = minFilter;
    mWrapS = wrapS;
    mWrapT = wrapT;
    mWrapR = wrapR;
}

Sampler::~Sampler()
{
}

void Sampler::setupGL()
{
    //LOGE("setup gl");
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);

}

void Sampler::bindToContext(SamplerState *ss, uint32_t slot)
{
    ss->mSamplers[slot].set(this);
    mBoundSlot = slot;
}

void Sampler::unbindFromContext(SamplerState *ss)
{
    int32_t slot = mBoundSlot;
    mBoundSlot = -1;
    ss->mSamplers[slot].clear();
}

void SamplerState::setupGL()
{
    for (uint32_t ct=0; ct < 1/*RS_MAX_SAMPLER_SLOT*/; ct++) {
        Sampler *s = mSamplers[ct].get();
        if (s) {
            s->setupGL();
        } else {
            glBindTexture(GL_TEXTURE_2D, 0);
        }
    }
}

////////////////////////////////

namespace android {
namespace renderscript {


void rsi_SamplerBegin(Context *rsc)
{
    SamplerState * ss = &rsc->mStateSampler;

    ss->mMagFilter = RS_SAMPLER_LINEAR;
    ss->mMinFilter = RS_SAMPLER_LINEAR;
    ss->mWrapS = RS_SAMPLER_WRAP;
    ss->mWrapT = RS_SAMPLER_WRAP;
    ss->mWrapR = RS_SAMPLER_WRAP;
}

void rsi_SamplerSet(Context *rsc, RsSamplerParam param, RsSamplerValue value)
{
    SamplerState * ss = &rsc->mStateSampler;

    switch(param) {
    case RS_SAMPLER_MAG_FILTER:
        ss->mMagFilter = value;
        break;
    case RS_SAMPLER_MIN_FILTER:
        ss->mMinFilter = value;
        break;
    case RS_SAMPLER_WRAP_S:
        ss->mWrapS = value;
        break;
    case RS_SAMPLER_WRAP_T:
        ss->mWrapT = value;
        break;
    case RS_SAMPLER_WRAP_R:
        ss->mWrapR = value;
        break;
    }

}

RsSampler rsi_SamplerCreate(Context *rsc)
{
    SamplerState * ss = &rsc->mStateSampler;


    Sampler * s = new Sampler(ss->mMagFilter, 
                              ss->mMinFilter, 
                              ss->mWrapS, 
                              ss->mWrapT,
                              ss->mWrapR);
    return s;
}

}}
