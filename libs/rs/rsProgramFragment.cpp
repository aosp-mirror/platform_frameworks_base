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

#include <GLES/gl.h>
#include <GLES/glext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

using namespace android;
using namespace android::renderscript;


ProgramFragment::ProgramFragment(Context *rsc, Element *in, Element *out, bool pointSpriteEnable) :
    Program(rsc, in, out)
{
    mAllocFile = __FILE__;
    mAllocLine = __LINE__;
    for (uint32_t ct=0; ct < MAX_TEXTURE; ct++) {
        mEnvModes[ct] = RS_TEX_ENV_MODE_REPLACE;
        mTextureDimensions[ct] = 2;
    }
    mTextureEnableMask = 0;
    mPointSpriteEnable = pointSpriteEnable;
    mEnvModes[1] = RS_TEX_ENV_MODE_DECAL;
}

ProgramFragment::~ProgramFragment()
{
}

void ProgramFragment::setupGL(const Context *rsc, ProgramFragmentState *state)
{
    if ((state->mLast.get() == this) && !mDirty) {
        return;
    }
    state->mLast.set(this);

    for (uint32_t ct=0; ct < MAX_TEXTURE; ct++) {
        glActiveTexture(GL_TEXTURE0 + ct);
        if (!(mTextureEnableMask & (1 << ct)) || !mTextures[ct].get()) {
            glDisable(GL_TEXTURE_2D);
            continue;
        }

        glEnable(GL_TEXTURE_2D);
        if (rsc->checkVersion1_1()) {
            if (mPointSpriteEnable) {
                glEnable(GL_POINT_SPRITE_OES);
            } else {
                glDisable(GL_POINT_SPRITE_OES);
            }
            glTexEnvi(GL_POINT_SPRITE_OES, GL_COORD_REPLACE_OES, mPointSpriteEnable);
        }
        glBindTexture(GL_TEXTURE_2D, mTextures[ct]->getTextureID());

        switch(mEnvModes[ct]) {
        case RS_TEX_ENV_MODE_REPLACE:
            glTexEnvf(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE);
            break;
        case RS_TEX_ENV_MODE_MODULATE:
            glTexEnvf(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_MODULATE);
            break;
        case RS_TEX_ENV_MODE_DECAL:
            glTexEnvf(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_DECAL);
            break;
        }

        if (mSamplers[ct].get()) {
            mSamplers[ct]->setupGL();
        } else {
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        }

        // Gross hack.
        if (ct == 2) {
            glTexEnvi(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_COMBINE);

            glTexEnvi(GL_TEXTURE_ENV, GL_COMBINE_RGB, GL_ADD);
            glTexEnvi(GL_TEXTURE_ENV, GL_SRC0_RGB, GL_PREVIOUS);
            glTexEnvi(GL_TEXTURE_ENV, GL_SRC1_RGB, GL_TEXTURE);
            glTexEnvi(GL_TEXTURE_ENV, GL_OPERAND0_RGB, GL_SRC_COLOR);
            glTexEnvi(GL_TEXTURE_ENV, GL_OPERAND1_RGB, GL_SRC_COLOR);

            glTexEnvi(GL_TEXTURE_ENV, GL_COMBINE_ALPHA, GL_ADD);
            glTexEnvi(GL_TEXTURE_ENV, GL_SRC0_ALPHA, GL_PREVIOUS);
            glTexEnvi(GL_TEXTURE_ENV, GL_SRC1_ALPHA, GL_TEXTURE);
            glTexEnvi(GL_TEXTURE_ENV, GL_OPERAND0_ALPHA, GL_SRC_ALPHA);
            glTexEnvi(GL_TEXTURE_ENV, GL_OPERAND1_ALPHA, GL_SRC_ALPHA);
        }
    }
    glActiveTexture(GL_TEXTURE0);
    mDirty = false;
}

void ProgramFragment::setupGL2(const Context *rsc, ProgramFragmentState *state, ShaderCache *sc)
{
    //LOGE("sgl2 frag1 %x", glGetError());
    if ((state->mLast.get() == this) && !mDirty) {
        //return;
    }
    state->mLast.set(this);

    for (uint32_t ct=0; ct < MAX_TEXTURE; ct++) {
        glActiveTexture(GL_TEXTURE0 + ct);
        if (!(mTextureEnableMask & (1 << ct)) || !mTextures[ct].get()) {
            glDisable(GL_TEXTURE_2D);
            continue;
        }

        glBindTexture(GL_TEXTURE_2D, mTextures[ct]->getTextureID());
        if (mSamplers[ct].get()) {
            mSamplers[ct]->setupGL();
        } else {
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        }

        glEnable(GL_TEXTURE_2D);
        glUniform1i(sc->fragUniformSlot(ct), ct);
    }

    glActiveTexture(GL_TEXTURE0);
    mDirty = false;

    //LOGE("sgl2 frag2 %x", glGetError());
}

void ProgramFragment::loadShader() {
    Program::loadShader(GL_FRAGMENT_SHADER);
}

void ProgramFragment::createShader()
{
    mShader.setTo("precision mediump float;\n");
    mShader.append("varying vec4 varColor;\n");
    mShader.append("varying vec4 varTex0;\n");

    uint32_t mask = mTextureEnableMask;
    uint32_t texNum = 0;
    while (mask) {
        if (mask & 1) {
            char buf[64];
            mShader.append("uniform sampler2D uni_Tex");
            sprintf(buf, "%i", texNum);
            mShader.append(buf);
            mShader.append(";\n");
        }
        mask >>= 1;
        texNum++;
    }


    mShader.append("void main() {\n");
    //mShader.append("  gl_FragColor = vec4(0.0, 1.0, 0.0, 1.0);\n");
    mShader.append("  vec4 col = varColor;\n");

    if (mTextureEnableMask) {
        if (mPointSpriteEnable) {
            mShader.append("  vec2 tex0 = gl_PointCoord;\n");
        } else {
            mShader.append("  vec2 tex0 = varTex0.xy;\n");
        }
    }

    mask = mTextureEnableMask;
    texNum = 0;
    while (mask) {
        if (mask & 1) {
            switch(mEnvModes[texNum]) {
            case RS_TEX_ENV_MODE_REPLACE:
                mShader.append("  col = texture2D(uni_Tex0, tex0);\n");
                break;
            case RS_TEX_ENV_MODE_MODULATE:
                mShader.append("  col *= texture2D(uni_Tex0, tex0);\n");
                break;
            case RS_TEX_ENV_MODE_DECAL:
                mShader.append("  col = texture2D(uni_Tex0, tex0);\n");
                break;
            }

        }
        mask >>= 1;
        texNum++;
    }

    //mShader.append("  col.a = 1.0;\n");
    //mShader.append("  col.r = 0.5;\n");

    mShader.append("  gl_FragColor = col;\n");
    mShader.append("}\n");
}

void ProgramFragment::bindTexture(uint32_t slot, Allocation *a)
{
    if (slot >= MAX_TEXTURE) {
        LOGE("Attempt to bind a texture to a slot > MAX_TEXTURE");
        return;
    }

    //LOGE("bindtex %i %p", slot, a);
    mTextures[slot].set(a);
    mDirty = true;
}

void ProgramFragment::bindSampler(uint32_t slot, Sampler *s)
{
    if (slot >= MAX_TEXTURE) {
        LOGE("Attempt to bind a Sampler to a slot > MAX_TEXTURE");
        return;
    }

    mSamplers[slot].set(s);
    mDirty = true;
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

void ProgramFragment::init(Context *rsc)
{
    mUniformCount = 2;
    mUniformNames[0].setTo("uni_Tex0");
    mUniformNames[1].setTo("uni_Tex1");

    createShader();
}

ProgramFragmentState::ProgramFragmentState()
{
    mPF = NULL;
}

ProgramFragmentState::~ProgramFragmentState()
{
    delete mPF;

}

void ProgramFragmentState::init(Context *rsc, int32_t w, int32_t h)
{
    ProgramFragment *pf = new ProgramFragment(rsc, NULL, NULL, false);
    mDefault.set(pf);
    pf->init(rsc);
}

void ProgramFragmentState::deinit(Context *rsc)
{
    mDefault.clear();
    mLast.clear();
}


namespace android {
namespace renderscript {

void rsi_ProgramFragmentBegin(Context * rsc, RsElement in, RsElement out, bool pointSpriteEnable)
{
    delete rsc->mStateFragment.mPF;
    rsc->mStateFragment.mPF = new ProgramFragment(rsc, (Element *)in, (Element *)out, pointSpriteEnable);
}

void rsi_ProgramFragmentBindTexture(Context *rsc, RsProgramFragment vpf, uint32_t slot, RsAllocation a)
{
    ProgramFragment *pf = static_cast<ProgramFragment *>(vpf);
    pf->bindTexture(slot, static_cast<Allocation *>(a));
}

void rsi_ProgramFragmentBindSampler(Context *rsc, RsProgramFragment vpf, uint32_t slot, RsSampler s)
{
    ProgramFragment *pf = static_cast<ProgramFragment *>(vpf);
    pf->bindSampler(slot, static_cast<Sampler *>(s));
}

void rsi_ProgramFragmentSetSlot(Context *rsc, uint32_t slot, bool enable, RsTexEnvMode env, RsType vt)
{
    const Type *t = static_cast<const Type *>(vt);
    if (t) {
        uint32_t dim = 1;
        if (t->getDimY()) {
            dim ++;
            if (t->getDimZ()) {
                dim ++;
            }
        }
        rsc->mStateFragment.mPF->setType(slot, t->getElement(), dim);
    }
    rsc->mStateFragment.mPF->setEnvMode(slot, env);
    rsc->mStateFragment.mPF->setTexEnable(slot, enable);
}

void rsi_ProgramFragmentSetShader(Context *rsc, const char *txt, uint32_t len)
{
    rsc->mStateFragment.mPF->setShader(txt, len);
}

RsProgramFragment rsi_ProgramFragmentCreate(Context *rsc)
{
    ProgramFragment *pf = rsc->mStateFragment.mPF;
    pf->incUserRef();
    pf->init(rsc);
    rsc->mStateFragment.mPF = 0;
    return pf;
}


}
}

