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
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#else
#include "rsContextHostStub.h"
#include <OpenGL/gl.h>
#include <OpenGL/glext.h>
#endif //ANDROID_RS_BUILD_FOR_HOST

#include "rsProgram.h"

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
    mTextureCount = 0;

    mTextures = NULL;
    mSamplers = NULL;
    mInputElements = NULL;
    mOutputElements = NULL;
    mConstantTypes = NULL;
    mInputCount = 0;
    mOutputCount = 0;
    mConstantCount = 0;
    mIsValid = false;
    mIsInternal = false;
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

    mTextures = new ObjectBaseRef<Allocation>[mTextureCount];
    mSamplers = new ObjectBaseRef<Sampler>[mTextureCount];
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
    mIsInternal = false;
    uint32_t internalTokenLen = strlen(RS_SHADER_INTERNAL);
    if(shaderLength > internalTokenLen &&
       strncmp(RS_SHADER_INTERNAL, shaderText, internalTokenLen) == 0) {
        mIsInternal = true;
        shaderText += internalTokenLen;
        shaderLength -= internalTokenLen;
    }
    mUserShader.setTo(shaderText, shaderLength);
}

Program::~Program()
{
    for (uint32_t ct=0; ct < MAX_UNIFORMS; ct++) {
        bindAllocation(NULL, NULL, ct);
    }

    for (uint32_t ct=0; ct < mTextureCount; ct++) {
        bindTexture(NULL, ct, NULL);
        bindSampler(NULL, ct, NULL);
    }
    delete[] mTextures;
    delete[] mSamplers;
    delete[] mInputElements;
    delete[] mOutputElements;
    delete[] mConstantTypes;
    mInputCount = 0;
    mOutputCount = 0;
    mConstantCount = 0;
}


void Program::bindAllocation(Context *rsc, Allocation *alloc, uint32_t slot)
{
    if (alloc != NULL) {
        if (slot >= mConstantCount) {
            LOGE("Attempt to bind alloc at slot %u, on shader id %u, but const count is %u",
                 slot, (uint32_t)this, mConstantCount);
            rsc->setError(RS_ERROR_BAD_SHADER, "Cannot bind allocation");
            return;
        }
        if (!alloc->getType()->isEqual(mConstantTypes[slot].get())) {
            LOGE("Attempt to bind alloc at slot %u, on shader id %u, but types mismatch",
                 slot, (uint32_t)this);
            rsc->setError(RS_ERROR_BAD_SHADER, "Cannot bind allocation");
            return;
        }
    }
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

void Program::bindTexture(Context *rsc, uint32_t slot, Allocation *a)
{
    if (slot >= mTextureCount) {
        LOGE("Attempt to bind texture to slot %u but tex count is %u", slot, mTextureCount);
        rsc->setError(RS_ERROR_BAD_SHADER, "Cannot bind texture");
        return;
    }

    //LOGE("bindtex %i %p", slot, a);
    mTextures[slot].set(a);
    mDirty = true;
}

void Program::bindSampler(Context *rsc, uint32_t slot, Sampler *s)
{
    if (slot >= mTextureCount) {
        LOGE("Attempt to bind sampler to slot %u but tex count is %u", slot, mTextureCount);
        rsc->setError(RS_ERROR_BAD_SHADER, "Cannot bind sampler");
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

void Program::appendUserConstants() {
    for (uint32_t ct=0; ct < mConstantCount; ct++) {
        const Element *e = mConstantTypes[ct]->getElement();
        for (uint32_t field=0; field < e->getFieldCount(); field++) {
            const Element *f = e->getField(field);
            const char *fn = e->getFieldName(field);

            if (fn[0] == '#') {
                continue;
            }

            // Cannot be complex
            rsAssert(!f->getFieldCount());
            if(f->getType() == RS_TYPE_MATRIX_4X4) {
                mShader.append("uniform mat4 UNI_");
            }
            else if(f->getType() == RS_TYPE_MATRIX_3X3) {
                mShader.append("uniform mat3 UNI_");
            }
            else if(f->getType() == RS_TYPE_MATRIX_2X2) {
                mShader.append("uniform mat2 UNI_");
            }
            else {
                switch(f->getComponent().getVectorSize()) {
                case 1: mShader.append("uniform float UNI_"); break;
                case 2: mShader.append("uniform vec2 UNI_"); break;
                case 3: mShader.append("uniform vec3 UNI_"); break;
                case 4: mShader.append("uniform vec4 UNI_"); break;
                default:
                    rsAssert(0);
                }
            }

            mShader.append(fn);
            mShader.append(";\n");
        }
    }
}

void Program::setupUserConstants(Context *rsc, ShaderCache *sc, bool isFragment) {
    uint32_t uidx = 0;
    for (uint32_t ct=0; ct < mConstantCount; ct++) {
        Allocation *alloc = mConstants[ct].get();
        if (!alloc) {
            LOGE("Attempting to set constants on shader id %u, but alloc at slot %u is not set", (uint32_t)this, ct);
            rsc->setError(RS_ERROR_BAD_SHADER, "No constant allocation bound");
            continue;
        }

        const uint8_t *data = static_cast<const uint8_t *>(alloc->getPtr());
        const Element *e = mConstantTypes[ct]->getElement();
        for (uint32_t field=0; field < e->getFieldCount(); field++) {
            const Element *f = e->getField(field);
            const char *fieldName = e->getFieldName(field);
            // If this field is padding, skip it
            if(fieldName[0] == '#') {
                continue;
            }

            uint32_t offset = e->getFieldOffsetBytes(field);
            const float *fd = reinterpret_cast<const float *>(&data[offset]);

            int32_t slot = -1;
            if(!isFragment) {
                slot = sc->vtxUniformSlot(uidx);
            }
            else {
                slot = sc->fragUniformSlot(uidx);
            }

            //LOGE("Uniform  slot=%i, offset=%i, constant=%i, field=%i, uidx=%i, name=%s", slot, offset, ct, field, uidx, fieldName);
            if (slot >= 0) {
                if(f->getType() == RS_TYPE_MATRIX_4X4) {
                    glUniformMatrix4fv(slot, 1, GL_FALSE, fd);
                    /*for(int i = 0; i < 4; i++) {
                        LOGE("Mat = %f %f %f %f", fd[i*4 + 0], fd[i*4 + 1], fd[i*4 + 2], fd[i*4 + 3]);
                    }*/
                }
                else if(f->getType() == RS_TYPE_MATRIX_3X3) {
                    glUniformMatrix3fv(slot, 1, GL_FALSE, fd);
                }
                else if(f->getType() == RS_TYPE_MATRIX_2X2) {
                    glUniformMatrix2fv(slot, 1, GL_FALSE, fd);
                }
                else {
                    switch(f->getComponent().getVectorSize()) {
                    case 1:
                        //LOGE("Uniform 1 = %f", fd[0]);
                        glUniform1fv(slot, 1, fd);
                        break;
                    case 2:
                        //LOGE("Uniform 2 = %f %f", fd[0], fd[1]);
                        glUniform2fv(slot, 1, fd);
                        break;
                    case 3:
                        //LOGE("Uniform 3 = %f %f %f", fd[0], fd[1], fd[2]);
                        glUniform3fv(slot, 1, fd);
                        break;
                    case 4:
                        //LOGE("Uniform 4 = %f %f %f %f", fd[0], fd[1], fd[2], fd[3]);
                        glUniform4fv(slot, 1, fd);
                        break;
                    default:
                        rsAssert(0);
                    }
                }
            }
            uidx ++;
        }
    }
}

void Program::initAddUserElement(const Element *e, String8 *names, uint32_t *count, const char *prefix)
{
    rsAssert(e->getFieldCount());
    for (uint32_t ct=0; ct < e->getFieldCount(); ct++) {
        const Element *ce = e->getField(ct);
        if (ce->getFieldCount()) {
            initAddUserElement(ce, names, count, prefix);
        }
        else if(e->getFieldName(ct)[0] != '#') {
            String8 tmp(prefix);
            tmp.append(e->getFieldName(ct));
            names[*count].setTo(tmp.string());
            (*count)++;
        }
    }
}

namespace android {
namespace renderscript {


void rsi_ProgramBindConstants(Context *rsc, RsProgram vp, uint32_t slot, RsAllocation constants)
{
    Program *p = static_cast<Program *>(vp);
    p->bindAllocation(rsc, static_cast<Allocation *>(constants), slot);
}

void rsi_ProgramBindTexture(Context *rsc, RsProgram vpf, uint32_t slot, RsAllocation a)
{
    Program *p = static_cast<Program *>(vpf);
    p->bindTexture(rsc, slot, static_cast<Allocation *>(a));
}

void rsi_ProgramBindSampler(Context *rsc, RsProgram vpf, uint32_t slot, RsSampler s)
{
    Program *p = static_cast<Program *>(vpf);
    p->bindSampler(rsc, slot, static_cast<Sampler *>(s));
}

}
}

