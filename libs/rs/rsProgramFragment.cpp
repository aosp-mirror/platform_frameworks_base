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

ProgramFragment::ProgramFragment(Context *rsc, const char * shaderText,
                                 uint32_t shaderLength, const uint32_t * params,
                                 uint32_t paramLength) :
    Program(rsc, shaderText, shaderLength, params, paramLength)
{
    mAllocFile = __FILE__;
    mAllocLine = __LINE__;

    mConstantColor[0] = 1.f;
    mConstantColor[1] = 1.f;
    mConstantColor[2] = 1.f;
    mConstantColor[3] = 1.f;

    init(rsc);
}

ProgramFragment::~ProgramFragment()
{
    if(mShaderID) {
        mRSC->mShaderCache.cleanupFragment(mShaderID);
    }
}

void ProgramFragment::setConstantColor(Context *rsc, float r, float g, float b, float a)
{
    if(isUserProgram()) {
        LOGE("Attempting to set fixed function emulation color on user program");
        rsc->setError(RS_ERROR_BAD_SHADER, "Cannot  set fixed function emulation color on user program");
        return;
    }
    if(mConstants[0].get() == NULL) {
        LOGE("Unable to set fixed function emulation color because allocation is missing");
        rsc->setError(RS_ERROR_BAD_SHADER, "Unable to set fixed function emulation color because allocation is missing");
        return;
    }
    mConstantColor[0] = r;
    mConstantColor[1] = g;
    mConstantColor[2] = b;
    mConstantColor[3] = a;
    memcpy(mConstants[0]->getPtr(), mConstantColor, 4*sizeof(float));
    mDirty = true;
}

void ProgramFragment::setupGL2(Context *rsc, ProgramFragmentState *state, ShaderCache *sc)
{
    //LOGE("sgl2 frag1 %x", glGetError());
    if ((state->mLast.get() == this) && !mDirty) {
        return;
    }
    state->mLast.set(this);

    rsc->checkError("ProgramFragment::setupGL2 start");

    rsc->checkError("ProgramFragment::setupGL2 begin uniforms");
    setupUserConstants(rsc, sc, true);

    uint32_t numTexturesToBind = mTextureCount;
    uint32_t numTexturesAvailable = rsc->getMaxFragmentTextures();
    if(numTexturesToBind >= numTexturesAvailable) {
        LOGE("Attempting to bind %u textures on shader id %u, but only %u are available",
             mTextureCount, (uint32_t)this, numTexturesAvailable);
        rsc->setError(RS_ERROR_BAD_SHADER, "Cannot bind more textuers than available");
        numTexturesToBind = numTexturesAvailable;
    }

    for (uint32_t ct=0; ct < numTexturesToBind; ct++) {
        glActiveTexture(GL_TEXTURE0 + ct);
        if (!mTextures[ct].get()) {
            LOGE("No texture bound for shader id %u, texture unit %u", (uint)this, ct);
            rsc->setError(RS_ERROR_BAD_SHADER, "No texture bound");
            continue;
        }

        mTextures[ct]->uploadCheck(rsc);
        glBindTexture(GL_TEXTURE_2D, mTextures[ct]->getTextureID());
        rsc->checkError("ProgramFragment::setupGL2 tex bind");
        if (mSamplers[ct].get()) {
            mSamplers[ct]->setupGL(rsc, mTextures[ct].get());
        } else {
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            rsc->checkError("ProgramFragment::setupGL2 tex env");
        }

        glUniform1i(sc->fragUniformSlot(mTextureUniformIndexStart + ct), ct);
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
    if (mUserShader.length() > 1) {
        mShader.append("precision mediump float;\n");
        appendUserConstants();
        char buf[256];
        for (uint32_t ct=0; ct < mTextureCount; ct++) {
            sprintf(buf, "uniform sampler2D UNI_Tex%i;\n", ct);
            mShader.append(buf);
        }
        mShader.append(mUserShader);
    } else {
        LOGE("ProgramFragment::createShader cannot create program, shader code not defined");
        rsAssert(0);
    }
}

void ProgramFragment::init(Context *rsc)
{
    mUniformCount = 0;
    if (mUserShader.size() > 0) {
        for (uint32_t ct=0; ct < mConstantCount; ct++) {
            initAddUserElement(mConstantTypes[ct]->getElement(), mUniformNames, &mUniformCount, RS_SHADER_UNI);
        }
    }
    mTextureUniformIndexStart = mUniformCount;
    char buf[256];
    for (uint32_t ct=0; ct < mTextureCount; ct++) {
        sprintf(buf, "UNI_Tex%i", ct);
        mUniformNames[mUniformCount++].setTo(buf);
    }

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
    String8 shaderString(RS_SHADER_INTERNAL);
    shaderString.append("varying lowp vec4 varColor;\n");
    shaderString.append("varying vec2 varTex0;\n");
    shaderString.append("void main() {\n");
    shaderString.append("  lowp vec4 col = UNI_Color;\n");
    shaderString.append("  gl_FragColor = col;\n");
    shaderString.append("}\n");

    const Element *colorElem = Element::create(rsc, RS_TYPE_FLOAT_32, RS_KIND_USER, false, 4);
    rsc->mStateElement.elementBuilderBegin();
    rsc->mStateElement.elementBuilderAdd(colorElem, "Color", 1);
    const Element *constInput = rsc->mStateElement.elementBuilderCreate(rsc);

    Type *inputType = new Type(rsc);
    inputType->setElement(constInput);
    inputType->setDimX(1);
    inputType->compute();

    uint32_t tmp[4];
    tmp[0] = RS_PROGRAM_PARAM_CONSTANT;
    tmp[1] = (uint32_t)inputType;
    tmp[2] = RS_PROGRAM_PARAM_TEXTURE_COUNT;
    tmp[3] = 0;

    Allocation *constAlloc = new Allocation(rsc, inputType);
    ProgramFragment *pf = new ProgramFragment(rsc, shaderString.string(),
                                              shaderString.length(), tmp, 4);
    pf->bindAllocation(rsc, constAlloc, 0);
    pf->setConstantColor(rsc, 1.0f, 1.0f, 1.0f, 1.0f);

    mDefault.set(pf);
}

void ProgramFragmentState::deinit(Context *rsc)
{
    mDefault.clear();
    mLast.clear();
}


namespace android {
namespace renderscript {

RsProgramFragment rsi_ProgramFragmentCreate(Context *rsc, const char * shaderText,
                             uint32_t shaderLength, const uint32_t * params,
                             uint32_t paramLength)
{
    ProgramFragment *pf = new ProgramFragment(rsc, shaderText, shaderLength, params, paramLength);
    pf->incUserRef();
    //LOGE("rsi_ProgramFragmentCreate %p", pf);
    return pf;
}

}
}

