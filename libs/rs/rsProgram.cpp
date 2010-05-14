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
#include "rsProgram.h"

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

using namespace android;
using namespace android::renderscript;


Program::Program(Context *rsc) : ObjectBase(rsc)
{
    mAllocFile = __FILE__;
    mAllocLine = __LINE__;
    mDirty = true;
    mShaderID = 0;
    mAttribCount = 0;
    mUniformCount = 0;

    mInputElements = NULL;
    mOutputElements = NULL;
    mConstantTypes = NULL;
    mInputCount = 0;
    mOutputCount = 0;
    mConstantCount = 0;
    mIsValid = false;
}

Program::Program(Context *rsc, const char * shaderText, uint32_t shaderLength,
                 const uint32_t * params, uint32_t paramLength) :
    ObjectBase(rsc)
{
    mAllocFile = __FILE__;
    mAllocLine = __LINE__;
    mDirty = true;
    mShaderID = 0;
    mAttribCount = 0;
    mUniformCount = 0;
    mTextureCount = 0;

    mInputCount = 0;
    mOutputCount = 0;
    mConstantCount = 0;

    for (uint32_t ct=0; ct < paramLength; ct+=2) {
        if (params[ct] == RS_PROGRAM_PARAM_INPUT) {
            mInputCount++;
        }
        if (params[ct] == RS_PROGRAM_PARAM_OUTPUT) {
            mOutputCount++;
        }
        if (params[ct] == RS_PROGRAM_PARAM_CONSTANT) {
            mConstantCount++;
        }
        if (params[ct] == RS_PROGRAM_PARAM_TEXTURE_COUNT) {
            mTextureCount = params[ct+1];
        }
    }

    mInputElements = new ObjectBaseRef<Element>[mInputCount];
    mOutputElements = new ObjectBaseRef<Element>[mOutputCount];
    mConstantTypes = new ObjectBaseRef<Type>[mConstantCount];

    uint32_t input = 0;
    uint32_t output = 0;
    uint32_t constant = 0;
    for (uint32_t ct=0; ct < paramLength; ct+=2) {
        if (params[ct] == RS_PROGRAM_PARAM_INPUT) {
            mInputElements[input++].set(reinterpret_cast<Element *>(params[ct+1]));
        }
        if (params[ct] == RS_PROGRAM_PARAM_OUTPUT) {
            mOutputElements[output++].set(reinterpret_cast<Element *>(params[ct+1]));
        }
        if (params[ct] == RS_PROGRAM_PARAM_CONSTANT) {
            mConstantTypes[constant++].set(reinterpret_cast<Type *>(params[ct+1]));
        }
    }
    mUserShader.setTo(shaderText, shaderLength);
}

Program::~Program()
{
    for (uint32_t ct=0; ct < MAX_UNIFORMS; ct++) {
        bindAllocation(NULL, ct);
    }

    delete[] mInputElements;
    delete[] mOutputElements;
    delete[] mConstantTypes;
    mInputCount = 0;
    mOutputCount = 0;
    mConstantCount = 0;
}


void Program::bindAllocation(Allocation *alloc, uint32_t slot)
{
    if (mConstants[slot].get() == alloc) {
        return;
    }
    if (mConstants[slot].get()) {
        mConstants[slot].get()->removeProgramToDirty(this);
    }
    mConstants[slot].set(alloc);
    if (alloc) {
        alloc->addProgramToDirty(this);
    }
    mDirty = true;
}

void Program::bindTexture(uint32_t slot, Allocation *a)
{
    if (slot >= MAX_TEXTURE) {
        LOGE("Attempt to bind a texture to a slot > MAX_TEXTURE");
        return;
    }

    //LOGE("bindtex %i %p", slot, a);
    mTextures[slot].set(a);
    mDirty = true;
}

void Program::bindSampler(uint32_t slot, Sampler *s)
{
    if (slot >= MAX_TEXTURE) {
        LOGE("Attempt to bind a Sampler to a slot > MAX_TEXTURE");
        return;
    }

    mSamplers[slot].set(s);
    mDirty = true;
}

String8 Program::getGLSLInputString() const
{
    String8 s;
    for (uint32_t ct=0; ct < mInputCount; ct++) {
        const Element *e = mInputElements[ct].get();
        for (uint32_t field=0; field < e->getFieldCount(); field++) {
            const Element *f = e->getField(field);

            // Cannot be complex
            rsAssert(!f->getFieldCount());
            switch(f->getComponent().getVectorSize()) {
            case 1: s.append("attribute float ATTRIB_"); break;
            case 2: s.append("attribute vec2 ATTRIB_"); break;
            case 3: s.append("attribute vec3 ATTRIB_"); break;
            case 4: s.append("attribute vec4 ATTRIB_"); break;
            default:
                rsAssert(0);
            }

            s.append(e->getFieldName(field));
            s.append(";\n");
        }
    }
    return s;
}

String8 Program::getGLSLOutputString() const
{
    return String8();
}

String8 Program::getGLSLConstantString() const
{
    return String8();
}


void Program::createShader()
{
}

bool Program::loadShader(Context *rsc, uint32_t type)
{
    mShaderID = glCreateShader(type);
    rsAssert(mShaderID);

    if (rsc->props.mLogShaders) {
        LOGV("Loading shader type %x, ID %i", type, mShaderID);
        LOGV("%s", mShader.string());
    }

    if (mShaderID) {
        const char * ss = mShader.string();
        glShaderSource(mShaderID, 1, &ss, NULL);
        glCompileShader(mShaderID);

        GLint compiled = 0;
        glGetShaderiv(mShaderID, GL_COMPILE_STATUS, &compiled);
        if (!compiled) {
            GLint infoLen = 0;
            glGetShaderiv(mShaderID, GL_INFO_LOG_LENGTH, &infoLen);
            if (infoLen) {
                char* buf = (char*) malloc(infoLen);
                if (buf) {
                    glGetShaderInfoLog(mShaderID, infoLen, NULL, buf);
                    LOGE("Could not compile shader \n%s\n", buf);
                    free(buf);
                }
                glDeleteShader(mShaderID);
                mShaderID = 0;
                rsc->setError(RS_ERROR_BAD_SHADER, "Error returned from GL driver loading shader text,");
                return false;
            }
        }
    }

    if (rsc->props.mLogShaders) {
        LOGV("--Shader load result %x ", glGetError());
    }
    mIsValid = true;
    return true;
}

void Program::setShader(const char *txt, uint32_t len)
{
    mUserShader.setTo(txt, len);
}



namespace android {
namespace renderscript {


void rsi_ProgramBindConstants(Context *rsc, RsProgram vp, uint32_t slot, RsAllocation constants)
{
    Program *p = static_cast<Program *>(vp);
    p->bindAllocation(static_cast<Allocation *>(constants), slot);
}

void rsi_ProgramBindTexture(Context *rsc, RsProgram vpf, uint32_t slot, RsAllocation a)
{
    Program *p = static_cast<Program *>(vpf);
    p->bindTexture(slot, static_cast<Allocation *>(a));
}

void rsi_ProgramBindSampler(Context *rsc, RsProgram vpf, uint32_t slot, RsSampler s)
{
    Program *p = static_cast<Program *>(vpf);
    p->bindSampler(slot, static_cast<Sampler *>(s));
}

}
}

