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
#ifndef ANDROID_RS_SERIALIZE
#include <GLES/gl.h>
#include <GLES/glext.h>
#endif //ANDROID_RS_SERIALIZE

#include "rsSampler.h"


using namespace android;
using namespace android::renderscript;


Sampler::Sampler(Context *rsc) : ObjectBase(rsc) {
    // Should not get called.
    rsAssert(0);
}

Sampler::Sampler(Context *rsc,
                 RsSamplerValue magFilter,
                 RsSamplerValue minFilter,
                 RsSamplerValue wrapS,
                 RsSamplerValue wrapT,
                 RsSamplerValue wrapR,
                 float aniso) : ObjectBase(rsc) {
    mHal.state.magFilter = magFilter;
    mHal.state.minFilter = minFilter;
    mHal.state.wrapS = wrapS;
    mHal.state.wrapT = wrapT;
    mHal.state.wrapR = wrapR;
    mHal.state.aniso = aniso;
}

Sampler::~Sampler() {
}

void Sampler::setupGL(const Context *rsc, const Allocation *tex) {
    GLenum trans[] = {
        GL_NEAREST, //RS_SAMPLER_NEAREST,
        GL_LINEAR, //RS_SAMPLER_LINEAR,
        GL_LINEAR_MIPMAP_LINEAR, //RS_SAMPLER_LINEAR_MIP_LINEAR,
        GL_REPEAT, //RS_SAMPLER_WRAP,
        GL_CLAMP_TO_EDGE, //RS_SAMPLER_CLAMP
        GL_LINEAR_MIPMAP_NEAREST, //RS_SAMPLER_LINEAR_MIP_NEAREST
    };

    GLenum transNP[] = {
        GL_NEAREST, //RS_SAMPLER_NEAREST,
        GL_LINEAR, //RS_SAMPLER_LINEAR,
        GL_LINEAR, //RS_SAMPLER_LINEAR_MIP_LINEAR,
        GL_CLAMP_TO_EDGE, //RS_SAMPLER_WRAP,
        GL_CLAMP_TO_EDGE, //RS_SAMPLER_CLAMP
        GL_LINEAR, //RS_SAMPLER_LINEAR_MIP_NEAREST,
    };

    // This tells us the correct texture type
    GLenum target = (GLenum)tex->getGLTarget();

    if (!rsc->ext_OES_texture_npot() && tex->getType()->getIsNp2()) {
        if (tex->getHasGraphicsMipmaps() &&
            (rsc->ext_GL_NV_texture_npot_2D_mipmap() || rsc->ext_GL_IMG_texture_npot())) {
            if (rsc->ext_GL_NV_texture_npot_2D_mipmap()) {
                glTexParameteri(target, GL_TEXTURE_MIN_FILTER, trans[mHal.state.minFilter]);
            } else {
                switch (trans[mHal.state.minFilter]) {
                case GL_LINEAR_MIPMAP_LINEAR:
                    glTexParameteri(target, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_NEAREST);
                    break;
                default:
                    glTexParameteri(target, GL_TEXTURE_MIN_FILTER, trans[mHal.state.minFilter]);
                    break;
                }
            }
        } else {
            glTexParameteri(target, GL_TEXTURE_MIN_FILTER, transNP[mHal.state.minFilter]);
        }
        glTexParameteri(target, GL_TEXTURE_MAG_FILTER, transNP[mHal.state.magFilter]);
        glTexParameteri(target, GL_TEXTURE_WRAP_S, transNP[mHal.state.wrapS]);
        glTexParameteri(target, GL_TEXTURE_WRAP_T, transNP[mHal.state.wrapT]);
    } else {
        if (tex->getHasGraphicsMipmaps()) {
            glTexParameteri(target, GL_TEXTURE_MIN_FILTER, trans[mHal.state.minFilter]);
        } else {
            glTexParameteri(target, GL_TEXTURE_MIN_FILTER, transNP[mHal.state.minFilter]);
        }
        glTexParameteri(target, GL_TEXTURE_MAG_FILTER, trans[mHal.state.magFilter]);
        glTexParameteri(target, GL_TEXTURE_WRAP_S, trans[mHal.state.wrapS]);
        glTexParameteri(target, GL_TEXTURE_WRAP_T, trans[mHal.state.wrapT]);
    }

    float anisoValue = rsMin(rsc->ext_texture_max_aniso(), mHal.state.aniso);
    if (rsc->ext_texture_max_aniso() > 1.0f) {
        glTexParameterf(target, GL_TEXTURE_MAX_ANISOTROPY_EXT, anisoValue);
    }

    rsc->checkError("Sampler::setupGL2 tex env");
}

void Sampler::bindToContext(SamplerState *ss, uint32_t slot) {
    ss->mSamplers[slot].set(this);
    mBoundSlot = slot;
}

void Sampler::unbindFromContext(SamplerState *ss) {
    int32_t slot = mBoundSlot;
    mBoundSlot = -1;
    ss->mSamplers[slot].clear();
}

void Sampler::serialize(OStream *stream) const {
}

Sampler *Sampler::createFromStream(Context *rsc, IStream *stream) {
    return NULL;
}

////////////////////////////////

namespace android {
namespace renderscript {

RsSampler rsi_SamplerCreate(Context * rsc,
                            RsSamplerValue magFilter,
                            RsSamplerValue minFilter,
                            RsSamplerValue wrapS,
                            RsSamplerValue wrapT,
                            RsSamplerValue wrapR,
                            float aniso) {
    Sampler * s = new Sampler(rsc, magFilter, minFilter, wrapS, wrapT, wrapR, aniso);
    s->incUserRef();
    return s;
}

}}
