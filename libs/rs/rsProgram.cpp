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
    bindAllocation(NULL);

    delete[] mInputElements;
    delete[] mOutputElements;
    delete[] mConstantTypes;
    mInputCount = 0;
    mOutputCount = 0;
    mConstantCount = 0;
}


void Program::bindAllocation(Allocation *alloc)
{
    if (mConstants.get() == alloc) {
        return;
    }
    if (mConstants.get()) {
        mConstants.get()->removeProgramToDirty(this);
    }
    mConstants.set(alloc);
    if (alloc) {
        alloc->addProgramToDirty(this);
    }
    mDirty = true;
}

void Program::createShader()
{
}

bool Program::loadShader(uint32_t type)
{
    mShaderID = glCreateShader(type);
    rsAssert(mShaderID);

    LOGV("Loading shader type %x, ID %i", type, mShaderID);
    LOGE(mShader.string());

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
                return false;
            }
        }
    }
    LOGV("--Shader load result %x ", glGetError());
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
    p->bindAllocation(static_cast<Allocation *>(constants));
}


}
}

