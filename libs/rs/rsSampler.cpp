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

#include <GLES/gl.h>
#include <GLES/glext.h>

#include "rsContext.h"
#include "rsSampler.h"


using namespace android;
using namespace android::renderscript;


Sampler::Sampler(Context *rsc) : ObjectBase(rsc)
{
    mAllocFile = __FILE__;
    mAllocLine = __LINE__;
    // Should not get called.
    rsAssert(0);
}

Sampler::Sampler(Context *rsc,
                 RsSamplerValue magFilter,
                 RsSamplerValue minFilter,
                 RsSamplerValue wrapS,
                 RsSamplerValue wrapT,
                 RsSamplerValue wrapR) : ObjectBase(rsc)
{
    mAllocFile = __FILE__;
    mAllocLine = __LINE__;
    mMagFilter = magFilter;
    mMinFilter = minFilter;
    mWrapS = wrapS;
    mWrapT = wrapT;
    mWrapR = wrapR;
}

Sampler::~Sampler()
{
}

void Sampler::setupGL(const Context *rsc, bool npot)
{
    GLenum trans[] = {
        GL_NEAREST, //RS_SAMPLER_NEAREST,
        GL_LINEAR, //RS_SAMPLER_LINEAR,
        GL_LINEAR_MIPMAP_LINEAR, //RS_SAMPLER_LINEAR_MIP_LINEAR,
        GL_REPEAT, //RS_SAMPLER_WRAP,
        GL_CLAMP_TO_EDGE, //RS_SAMPLER_CLAMP

    };

    bool forceNonMip = false;
    if (!rsc->ext_OES_texture_npot() && npot) {
        forceNonMip = true;
    }

    if ((mMinFilter == RS_SAMPLER_LINEAR_MIP_LINEAR) && forceNonMip) {
        if (rsc->ext_GL_IMG_texture_npot()) {
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_NEAREST);
        } else {
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        }
    } else {
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, trans[mMinFilter]);
    }
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, trans[mMagFilter]);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, trans[mWrapS]);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, trans[mWrapT]);


    rsc->checkError("ProgramFragment::setupGL2 tex env");
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
/*
void SamplerState::setupGL()
{
    for (uint32_t ct=0; ct < RS_MAX_SAMPLER_SLOT; ct++) {
        Sampler *s = mSamplers[ct].get();
        if (s) {
            s->setupGL(rsc);
        } else {
            glBindTexture(GL_TEXTURE_2D, 0);
        }
    }
}*/

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


    Sampler * s = new Sampler(rsc,
                              ss->mMagFilter,
                              ss->mMinFilter,
                              ss->mWrapS,
                              ss->mWrapT,
                              ss->mWrapR);
    s->incUserRef();
    return s;
}


}}
