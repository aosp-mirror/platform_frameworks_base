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

#ifndef ANDROID_RS_BUILD_FOR_HOST
#include "rsContext.h"
#include <GLES/gl.h>
#include <GLES/glext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#else
#include "rsContextHostStub.h"
#include <OpenGL/gl.h>
#include <OpenGL/glext.h>
#endif //ANDROID_RS_BUILD_FOR_HOST

#include "rsProgramFragment.h"

using namespace android;
using namespace android::renderscript;


ProgramFragment::ProgramFragment(Context *rsc, const uint32_t * params,
                                 uint32_t paramLength) :
    Program(rsc)
{
    mAllocFile = __FILE__;
    mAllocLine = __LINE__;
    rsAssert(paramLength = 5);

    mEnvModes[0] = (RsTexEnvMode)params[0];
    mTextureFormats[0] = params[1];
    mEnvModes[1] = (RsTexEnvMode)params[2];
    mTextureFormats[1] = params[3];
    mPointSpriteEnable = params[4] != 0;

    mTextureEnableMask = 0;
    if (mEnvModes[0]) {
        mTextureEnableMask |= 1;
    }
    if (mEnvModes[1]) {
        mTextureEnableMask |= 2;
    }
    init(rsc);
}

ProgramFragment::ProgramFragment(Context *rsc, const char * shaderText,
                                 uint32_t shaderLength, const uint32_t * params,
                                 uint32_t paramLength) :
    Program(rsc, shaderText, shaderLength, params, paramLength)
{
    mAllocFile = __FILE__;
    mAllocLine = __LINE__;

    init(rsc);
    mTextureEnableMask = (1 << mTextureCount) -1;
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
#ifndef ANDROID_RS_BUILD_FOR_HOST // These are GLES only
            if (mPointSpriteEnable) {
                glEnable(GL_POINT_SPRITE_OES);
            } else {
                glDisable(GL_POINT_SPRITE_OES);
            }
            glTexEnvi(GL_POINT_SPRITE_OES, GL_COORD_REPLACE_OES, mPointSpriteEnable);
#endif //ANDROID_RS_BUILD_FOR_HOST

        }
        mTextures[ct]->uploadCheck(rsc);
        glBindTexture(GL_TEXTURE_2D, mTextures[ct]->getTextureID());

        switch(mEnvModes[ct]) {
        case RS_TEX_ENV_MODE_NONE:
            rsAssert(0);
            break;
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
            mSamplers[ct]->setupGL(rsc, mTextures[ct]->getType()->getIsNp2());
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
    rsc->checkError("ProgramFragment::setupGL");
}

void ProgramFragment::setupGL2(const Context *rsc, ProgramFragmentState *state, ShaderCache *sc)
{

    //LOGE("sgl2 frag1 %x", glGetError());
    if ((state->mLast.get() == this) && !mDirty) {
        return;
    }
    state->mLast.set(this);

    rsc->checkError("ProgramFragment::setupGL2 start");
    for (uint32_t ct=0; ct < MAX_TEXTURE; ct++) {
        glActiveTexture(GL_TEXTURE0 + ct);
        if (!(mTextureEnableMask & (1 << ct)) || !mTextures[ct].get()) {
            continue;
        }

        mTextures[ct]->uploadCheck(rsc);
        glBindTexture(GL_TEXTURE_2D, mTextures[ct]->getTextureID());
        rsc->checkError("ProgramFragment::setupGL2 tex bind");
        if (mSamplers[ct].get()) {
            mSamplers[ct]->setupGL(rsc, mTextures[ct]->getType()->getIsNp2());
        } else {
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
            rsc->checkError("ProgramFragment::setupGL2 tex env");
        }

        glUniform1i(sc->fragUniformSlot(ct), ct);
        rsc->checkError("ProgramFragment::setupGL2 uniforms");
    }

    glActiveTexture(GL_TEXTURE0);
    mDirty = false;
    rsc->checkError("ProgramFragment::setupGL2");
}

void ProgramFragment::loadShader(Context *rsc) {
    Program::loadShader(rsc, GL_FRAGMENT_SHADER);
}

void ProgramFragment::createShader()
{
    mShader.setTo("precision mediump float;\n");
    mShader.append("varying vec4 varColor;\n");
    mShader.append("varying vec4 varTex0;\n");

    if (mUserShader.length() > 1) {
        for (uint32_t ct=0; ct < mTextureCount; ct++) {
            char buf[256];
            sprintf(buf, "uniform sampler2D uni_Tex%i;\n", ct);
            mShader.append(buf);
        }

        mShader.append(mUserShader);
    } else {
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
        mShader.append("  vec4 col = varColor;\n");

        if (mTextureEnableMask) {
            if (mPointSpriteEnable) {
                mShader.append("  vec2 t0 = gl_PointCoord;\n");
            } else {
                mShader.append("  vec2 t0 = varTex0.xy;\n");
            }
        }

        mask = mTextureEnableMask;
        texNum = 0;
        while (mask) {
            if (mask & 1) {
                switch(mEnvModes[texNum]) {
                case RS_TEX_ENV_MODE_NONE:
                    rsAssert(0);
                    break;
                case RS_TEX_ENV_MODE_REPLACE:
                    switch(mTextureFormats[texNum]) {
                    case 1:
                        mShader.append("  col.a = texture2D(uni_Tex0, t0).a;\n");
                        break;
                    case 2:
                        mShader.append("  col.rgba = texture2D(uni_Tex0, t0).rgba;\n");
                        break;
                    case 3:
                        mShader.append("  col.rgb = texture2D(uni_Tex0, t0).rgb;\n");
                        break;
                    case 4:
                        mShader.append("  col.rgba = texture2D(uni_Tex0, t0).rgba;\n");
                        break;
                    }
                    break;
                case RS_TEX_ENV_MODE_MODULATE:
                    switch(mTextureFormats[texNum]) {
                    case 1:
                        mShader.append("  col.a *= texture2D(uni_Tex0, t0).a;\n");
                        break;
                    case 2:
                        mShader.append("  col.rgba *= texture2D(uni_Tex0, t0).rgba;\n");
                        break;
                    case 3:
                        mShader.append("  col.rgb *= texture2D(uni_Tex0, t0).rgb;\n");
                        break;
                    case 4:
                        mShader.append("  col.rgba *= texture2D(uni_Tex0, t0).rgba;\n");
                        break;
                    }
                    break;
                case RS_TEX_ENV_MODE_DECAL:
                    mShader.append("  col = texture2D(uni_Tex0, t0);\n");
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
}

void ProgramFragment::init(Context *rsc)
{
    mUniformCount = 2;
    mUniformNames[0].setTo("uni_Tex0");
    mUniformNames[1].setTo("uni_Tex1");

    createShader();
}

void ProgramFragment::serialize(OStream *stream) const
{

}

ProgramFragment *ProgramFragment::createFromStream(Context *rsc, IStream *stream)
{
    return NULL;
}

ProgramFragmentState::ProgramFragmentState()
{
    mPF = NULL;
}

ProgramFragmentState::~ProgramFragmentState()
{
    delete mPF;

}

void ProgramFragmentState::init(Context *rsc)
{
    uint32_t tmp[5] = {
        RS_TEX_ENV_MODE_NONE, 0,
        RS_TEX_ENV_MODE_NONE, 0,
        0
    };
    ProgramFragment *pf = new ProgramFragment(rsc, tmp, 5);
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

RsProgramFragment rsi_ProgramFragmentCreate(Context *rsc,
                                            const uint32_t * params,
                                            uint32_t paramLength)
{
    ProgramFragment *pf = new ProgramFragment(rsc, params, paramLength);
    pf->incUserRef();
    //LOGE("rsi_ProgramFragmentCreate %p", pf);
    return pf;
}

RsProgramFragment rsi_ProgramFragmentCreate2(Context *rsc, const char * shaderText,
                             uint32_t shaderLength, const uint32_t * params,
                             uint32_t paramLength)
{
    ProgramFragment *pf = new ProgramFragment(rsc, shaderText, shaderLength, params, paramLength);
    pf->incUserRef();
    //LOGE("rsi_ProgramFragmentCreate2 %p", pf);
    return pf;
}

}
}

