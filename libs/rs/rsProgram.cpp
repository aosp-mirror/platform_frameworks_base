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

Program::Program(Context *rsc) : ObjectBase(rsc) {
   initMemberVars();
}

Program::Program(Context *rsc, const char * shaderText, uint32_t shaderLength,
                 const uint32_t * params, uint32_t paramLength)
    : ObjectBase(rsc) {

    initMemberVars();
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
        if (params[ct] == RS_PROGRAM_PARAM_TEXTURE_TYPE) {
            mTextureCount++;
        }
    }

    mTextures = new ObjectBaseRef<Allocation>[mTextureCount];
    mSamplers = new ObjectBaseRef<Sampler>[mTextureCount];
    mTextureTargets = new RsTextureTarget[mTextureCount];
    mInputElements = new ObjectBaseRef<Element>[mInputCount];
    mOutputElements = new ObjectBaseRef<Element>[mOutputCount];
    mConstantTypes = new ObjectBaseRef<Type>[mConstantCount];
    mConstants = new ObjectBaseRef<Allocation>[mConstantCount];

    uint32_t input = 0;
    uint32_t output = 0;
    uint32_t constant = 0;
    uint32_t texture = 0;
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
        if (params[ct] == RS_PROGRAM_PARAM_TEXTURE_TYPE) {
            mTextureTargets[texture++] = (RsTextureTarget)params[ct+1];
        }
    }
    mIsInternal = false;
    uint32_t internalTokenLen = strlen(RS_SHADER_INTERNAL);
    if (shaderLength > internalTokenLen &&
       strncmp(RS_SHADER_INTERNAL, shaderText, internalTokenLen) == 0) {
        mIsInternal = true;
        shaderText += internalTokenLen;
        shaderLength -= internalTokenLen;
    }
    mUserShader.setTo(shaderText, shaderLength);

    initAttribAndUniformArray();
}

Program::~Program() {
    if (mRSC->props.mLogShaders) {
        LOGV("Program::~Program with shader id %u", mShaderID);
    }

    if (mShaderID) {
        glDeleteShader(mShaderID);
    }

    for (uint32_t ct=0; ct < mConstantCount; ct++) {
        bindAllocation(NULL, NULL, ct);
    }

    for (uint32_t ct=0; ct < mTextureCount; ct++) {
        bindTexture(NULL, ct, NULL);
        bindSampler(NULL, ct, NULL);
    }
    delete[] mTextures;
    delete[] mSamplers;
    delete[] mTextureTargets;
    delete[] mInputElements;
    delete[] mOutputElements;
    delete[] mConstantTypes;
    delete[] mConstants;
    delete[] mAttribNames;
    delete[] mUniformNames;
    delete[] mUniformArraySizes;
    mInputCount = 0;
    mOutputCount = 0;
    mConstantCount = 0;
}

void Program::initMemberVars() {
    mDirty = true;
    mShaderID = 0;
    mAttribCount = 0;
    mUniformCount = 0;
    mTextureCount = 0;

    mTextures = NULL;
    mSamplers = NULL;
    mTextureTargets = NULL;
    mInputElements = NULL;
    mOutputElements = NULL;
    mConstantTypes = NULL;
    mConstants = NULL;
    mAttribNames = NULL;
    mUniformNames = NULL;
    mUniformArraySizes = NULL;
    mInputCount = 0;
    mOutputCount = 0;
    mConstantCount = 0;
    mIsValid = false;
    mIsInternal = false;
}

void Program::bindAllocation(Context *rsc, Allocation *alloc, uint32_t slot) {
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

void Program::bindTexture(Context *rsc, uint32_t slot, Allocation *a) {
    if (slot >= mTextureCount) {
        LOGE("Attempt to bind texture to slot %u but tex count is %u", slot, mTextureCount);
        rsc->setError(RS_ERROR_BAD_SHADER, "Cannot bind texture");
        return;
    }

    if (a && a->getType()->getDimFaces() && mTextureTargets[slot] != RS_TEXTURE_CUBE) {
        LOGE("Attempt to bind cubemap to slot %u but 2d texture needed", slot);
        rsc->setError(RS_ERROR_BAD_SHADER, "Cannot bind cubemap to 2d texture slot");
        return;
    }

    //LOGE("bindtex %i %p", slot, a);
    mTextures[slot].set(a);
    mDirty = true;
}

void Program::bindSampler(Context *rsc, uint32_t slot, Sampler *s) {
    if (slot >= mTextureCount) {
        LOGE("Attempt to bind sampler to slot %u but tex count is %u", slot, mTextureCount);
        rsc->setError(RS_ERROR_BAD_SHADER, "Cannot bind sampler");
        return;
    }

    mSamplers[slot].set(s);
    mDirty = true;
}

String8 Program::getGLSLInputString() const {
    String8 s;
    for (uint32_t ct=0; ct < mInputCount; ct++) {
        const Element *e = mInputElements[ct].get();
        for (uint32_t field=0; field < e->getFieldCount(); field++) {
            const Element *f = e->getField(field);

            // Cannot be complex
            rsAssert(!f->getFieldCount());
            switch (f->getComponent().getVectorSize()) {
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

String8 Program::getGLSLOutputString() const {
    return String8();
}

String8 Program::getGLSLConstantString() const {
    return String8();
}

void Program::createShader() {
}

bool Program::loadShader(Context *rsc, uint32_t type) {
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

void Program::setShader(const char *txt, uint32_t len) {
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
            if (f->getType() == RS_TYPE_MATRIX_4X4) {
                mShader.append("uniform mat4 UNI_");
            } else if (f->getType() == RS_TYPE_MATRIX_3X3) {
                mShader.append("uniform mat3 UNI_");
            } else if (f->getType() == RS_TYPE_MATRIX_2X2) {
                mShader.append("uniform mat2 UNI_");
            } else {
                switch (f->getComponent().getVectorSize()) {
                case 1: mShader.append("uniform float UNI_"); break;
                case 2: mShader.append("uniform vec2 UNI_"); break;
                case 3: mShader.append("uniform vec3 UNI_"); break;
                case 4: mShader.append("uniform vec4 UNI_"); break;
                default:
                    rsAssert(0);
                }
            }

            mShader.append(fn);
            if (e->getFieldArraySize(field) > 1) {
                mShader.appendFormat("[%d]", e->getFieldArraySize(field));
            }
            mShader.append(";\n");
        }
    }
}

void Program::logUniform(const Element *field, const float *fd, uint32_t arraySize ) {
    RsDataType dataType = field->getType();
    uint32_t elementSize = field->getSizeBytes() / sizeof(float);
    for (uint32_t i = 0; i < arraySize; i ++) {
        if (arraySize > 1) {
            LOGV("Array Element [%u]", i);
        }
        if (dataType == RS_TYPE_MATRIX_4X4) {
            LOGV("Matrix4x4");
            LOGV("{%f, %f, %f, %f",  fd[0], fd[4], fd[8], fd[12]);
            LOGV(" %f, %f, %f, %f",  fd[1], fd[5], fd[9], fd[13]);
            LOGV(" %f, %f, %f, %f",  fd[2], fd[6], fd[10], fd[14]);
            LOGV(" %f, %f, %f, %f}", fd[3], fd[7], fd[11], fd[15]);
        } else if (dataType == RS_TYPE_MATRIX_3X3) {
            LOGV("Matrix3x3");
            LOGV("{%f, %f, %f",  fd[0], fd[3], fd[6]);
            LOGV(" %f, %f, %f",  fd[1], fd[4], fd[7]);
            LOGV(" %f, %f, %f}", fd[2], fd[5], fd[8]);
        } else if (dataType == RS_TYPE_MATRIX_2X2) {
            LOGV("Matrix2x2");
            LOGV("{%f, %f",  fd[0], fd[2]);
            LOGV(" %f, %f}", fd[1], fd[3]);
        } else {
            switch (field->getComponent().getVectorSize()) {
            case 1:
                LOGV("Uniform 1 = %f", fd[0]);
                break;
            case 2:
                LOGV("Uniform 2 = %f %f", fd[0], fd[1]);
                break;
            case 3:
                LOGV("Uniform 3 = %f %f %f", fd[0], fd[1], fd[2]);
                break;
            case 4:
                LOGV("Uniform 4 = %f %f %f %f", fd[0], fd[1], fd[2], fd[3]);
                break;
            default:
                rsAssert(0);
            }
        }
        LOGE("Element size %u data=%p", elementSize, fd);
        fd += elementSize;
        LOGE("New data=%p", fd);
    }
}

void Program::setUniform(Context *rsc, const Element *field, const float *fd,
                         int32_t slot, uint32_t arraySize ) {
    RsDataType dataType = field->getType();
    if (dataType == RS_TYPE_MATRIX_4X4) {
        glUniformMatrix4fv(slot, arraySize, GL_FALSE, fd);
    } else if (dataType == RS_TYPE_MATRIX_3X3) {
        glUniformMatrix3fv(slot, arraySize, GL_FALSE, fd);
    } else if (dataType == RS_TYPE_MATRIX_2X2) {
        glUniformMatrix2fv(slot, arraySize, GL_FALSE, fd);
    } else {
        switch (field->getComponent().getVectorSize()) {
        case 1:
            glUniform1fv(slot, arraySize, fd);
            break;
        case 2:
            glUniform2fv(slot, arraySize, fd);
            break;
        case 3:
            glUniform3fv(slot, arraySize, fd);
            break;
        case 4:
            glUniform4fv(slot, arraySize, fd);
            break;
        default:
            rsAssert(0);
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
            if (fieldName[0] == '#') {
                continue;
            }

            uint32_t offset = e->getFieldOffsetBytes(field);
            const float *fd = reinterpret_cast<const float *>(&data[offset]);

            int32_t slot = -1;
            uint32_t arraySize = 1;
            if (!isFragment) {
                slot = sc->vtxUniformSlot(uidx);
                arraySize = sc->vtxUniformSize(uidx);
            } else {
                slot = sc->fragUniformSlot(uidx);
                arraySize = sc->fragUniformSize(uidx);
            }
            if (rsc->props.mLogShadersUniforms) {
                LOGV("Uniform  slot=%i, offset=%i, constant=%i, field=%i, uidx=%i, name=%s", slot, offset, ct, field, uidx, fieldName);
            }
            uidx ++;
            if (slot < 0) {
                continue;
            }

            if (rsc->props.mLogShadersUniforms) {
                logUniform(f, fd, arraySize);
            }
            setUniform(rsc, f, fd, slot, arraySize);
        }
    }
}

void Program::initAttribAndUniformArray() {
    mAttribCount = 0;
    for (uint32_t ct=0; ct < mInputCount; ct++) {
        const Element *elem = mInputElements[ct].get();
        for (uint32_t field=0; field < elem->getFieldCount(); field++) {
            if (elem->getFieldName(field)[0] != '#') {
                mAttribCount ++;
            }
        }
    }

    mUniformCount = 0;
    for (uint32_t ct=0; ct < mConstantCount; ct++) {
        const Element *elem = mConstantTypes[ct]->getElement();

        for (uint32_t field=0; field < elem->getFieldCount(); field++) {
            if (elem->getFieldName(field)[0] != '#') {
                mUniformCount ++;
            }
        }
    }
    mUniformCount += mTextureCount;

    if (mAttribCount) {
        mAttribNames = new String8[mAttribCount];
    }
    if (mUniformCount) {
        mUniformNames = new String8[mUniformCount];
        mUniformArraySizes = new uint32_t[mUniformCount];
    }
}

void Program::initAddUserElement(const Element *e, String8 *names, uint32_t *arrayLengths, uint32_t *count, const char *prefix) {
    rsAssert(e->getFieldCount());
    for (uint32_t ct=0; ct < e->getFieldCount(); ct++) {
        const Element *ce = e->getField(ct);
        if (ce->getFieldCount()) {
            initAddUserElement(ce, names, arrayLengths, count, prefix);
        } else if (e->getFieldName(ct)[0] != '#') {
            String8 tmp(prefix);
            tmp.append(e->getFieldName(ct));
            names[*count].setTo(tmp.string());
            if (arrayLengths) {
                arrayLengths[*count] = e->getFieldArraySize(ct);
            }
            (*count)++;
        }
    }
}

namespace android {
namespace renderscript {

void rsi_ProgramBindConstants(Context *rsc, RsProgram vp, uint32_t slot, RsAllocation constants) {
    Program *p = static_cast<Program *>(vp);
    p->bindAllocation(rsc, static_cast<Allocation *>(constants), slot);
}

void rsi_ProgramBindTexture(Context *rsc, RsProgram vpf, uint32_t slot, RsAllocation a) {
    Program *p = static_cast<Program *>(vpf);
    p->bindTexture(rsc, slot, static_cast<Allocation *>(a));
}

void rsi_ProgramBindSampler(Context *rsc, RsProgram vpf, uint32_t slot, RsSampler s) {
    Program *p = static_cast<Program *>(vpf);
    p->bindSampler(rsc, slot, static_cast<Sampler *>(s));
}

}
}

