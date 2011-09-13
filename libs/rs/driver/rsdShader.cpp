/*
 * Copyright (C) 2011 The Android Open Source Project
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

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <rs_hal.h>
#include <rsContext.h>
#include <rsProgram.h>

#include "rsdCore.h"
#include "rsdAllocation.h"
#include "rsdShader.h"
#include "rsdShaderCache.h"

using namespace android;
using namespace android::renderscript;

RsdShader::RsdShader(const Program *p, uint32_t type,
                       const char * shaderText, uint32_t shaderLength) {

    mUserShader.setTo(shaderText, shaderLength);
    mRSProgram = p;
    mType = type;
    initMemberVars();
    initAttribAndUniformArray();
    init();
}

RsdShader::~RsdShader() {
    if (mShaderID) {
        glDeleteShader(mShaderID);
    }

    delete[] mAttribNames;
    delete[] mUniformNames;
    delete[] mUniformArraySizes;
    delete[] mTextureTargets;
}

void RsdShader::initMemberVars() {
    mDirty = true;
    mShaderID = 0;
    mAttribCount = 0;
    mUniformCount = 0;

    mAttribNames = NULL;
    mUniformNames = NULL;
    mUniformArraySizes = NULL;
    mTextureTargets = NULL;

    mIsValid = false;
}

void RsdShader::init() {
    uint32_t attribCount = 0;
    uint32_t uniformCount = 0;
    for (uint32_t ct=0; ct < mRSProgram->mHal.state.inputElementsCount; ct++) {
        initAddUserElement(mRSProgram->mHal.state.inputElements[ct].get(), mAttribNames, NULL, &attribCount, RS_SHADER_ATTR);
    }
    for (uint32_t ct=0; ct < mRSProgram->mHal.state.constantsCount; ct++) {
        initAddUserElement(mRSProgram->mHal.state.constantTypes[ct]->getElement(), mUniformNames, mUniformArraySizes, &uniformCount, RS_SHADER_UNI);
    }

    mTextureUniformIndexStart = uniformCount;
    char buf[256];
    for (uint32_t ct=0; ct < mRSProgram->mHal.state.texturesCount; ct++) {
        snprintf(buf, sizeof(buf), "UNI_Tex%i", ct);
        mUniformNames[uniformCount].setTo(buf);
        mUniformArraySizes[uniformCount] = 1;
        uniformCount++;
    }

}

String8 RsdShader::getGLSLInputString() const {
    String8 s;
    for (uint32_t ct=0; ct < mRSProgram->mHal.state.inputElementsCount; ct++) {
        const Element *e = mRSProgram->mHal.state.inputElements[ct].get();
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

void RsdShader::appendAttributes() {
    for (uint32_t ct=0; ct < mRSProgram->mHal.state.inputElementsCount; ct++) {
        const Element *e = mRSProgram->mHal.state.inputElements[ct].get();
        for (uint32_t field=0; field < e->getFieldCount(); field++) {
            const Element *f = e->getField(field);
            const char *fn = e->getFieldName(field);

            if (fn[0] == '#') {
                continue;
            }

            // Cannot be complex
            rsAssert(!f->getFieldCount());
            switch (f->getComponent().getVectorSize()) {
            case 1: mShader.append("attribute float ATTRIB_"); break;
            case 2: mShader.append("attribute vec2 ATTRIB_"); break;
            case 3: mShader.append("attribute vec3 ATTRIB_"); break;
            case 4: mShader.append("attribute vec4 ATTRIB_"); break;
            default:
                rsAssert(0);
            }

            mShader.append(fn);
            mShader.append(";\n");
        }
    }
}

void RsdShader::appendTextures() {
    char buf[256];
    for (uint32_t ct=0; ct < mRSProgram->mHal.state.texturesCount; ct++) {
        if (mRSProgram->mHal.state.textureTargets[ct] == RS_TEXTURE_2D) {
            snprintf(buf, sizeof(buf), "uniform sampler2D UNI_Tex%i;\n", ct);
            mTextureTargets[ct] = GL_TEXTURE_2D;
        } else {
            snprintf(buf, sizeof(buf), "uniform samplerCube UNI_Tex%i;\n", ct);
            mTextureTargets[ct] = GL_TEXTURE_CUBE_MAP;
        }
        mShader.append(buf);
    }
}

bool RsdShader::createShader() {

    if (mType == GL_FRAGMENT_SHADER) {
        mShader.append("precision mediump float;\n");
    }
    appendUserConstants();
    appendAttributes();
    appendTextures();

    mShader.append(mUserShader);

    return true;
}

bool RsdShader::loadShader(const Context *rsc) {
    mShaderID = glCreateShader(mType);
    rsAssert(mShaderID);

    if (rsc->props.mLogShaders) {
        LOGV("Loading shader type %x, ID %i", mType, mShaderID);
        LOGV("%s", mShader.string());
    }

    if (mShaderID) {
        const char * ss = mShader.string();
        RSD_CALL_GL(glShaderSource, mShaderID, 1, &ss, NULL);
        RSD_CALL_GL(glCompileShader, mShaderID);

        GLint compiled = 0;
        RSD_CALL_GL(glGetShaderiv, mShaderID, GL_COMPILE_STATUS, &compiled);
        if (!compiled) {
            GLint infoLen = 0;
            RSD_CALL_GL(glGetShaderiv, mShaderID, GL_INFO_LOG_LENGTH, &infoLen);
            if (infoLen) {
                char* buf = (char*) malloc(infoLen);
                if (buf) {
                    RSD_CALL_GL(glGetShaderInfoLog, mShaderID, infoLen, NULL, buf);
                    LOGE("Could not compile shader \n%s\n", buf);
                    free(buf);
                }
                RSD_CALL_GL(glDeleteShader, mShaderID);
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

void RsdShader::appendUserConstants() {
    for (uint32_t ct=0; ct < mRSProgram->mHal.state.constantsCount; ct++) {
        const Element *e = mRSProgram->mHal.state.constantTypes[ct]->getElement();
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

void RsdShader::logUniform(const Element *field, const float *fd, uint32_t arraySize ) {
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

void RsdShader::setUniform(const Context *rsc, const Element *field, const float *fd,
                         int32_t slot, uint32_t arraySize ) {
    RsDataType dataType = field->getType();
    if (dataType == RS_TYPE_MATRIX_4X4) {
        RSD_CALL_GL(glUniformMatrix4fv, slot, arraySize, GL_FALSE, fd);
    } else if (dataType == RS_TYPE_MATRIX_3X3) {
        RSD_CALL_GL(glUniformMatrix3fv, slot, arraySize, GL_FALSE, fd);
    } else if (dataType == RS_TYPE_MATRIX_2X2) {
        RSD_CALL_GL(glUniformMatrix2fv, slot, arraySize, GL_FALSE, fd);
    } else {
        switch (field->getComponent().getVectorSize()) {
        case 1:
            RSD_CALL_GL(glUniform1fv, slot, arraySize, fd);
            break;
        case 2:
            RSD_CALL_GL(glUniform2fv, slot, arraySize, fd);
            break;
        case 3:
            RSD_CALL_GL(glUniform3fv, slot, arraySize, fd);
            break;
        case 4:
            RSD_CALL_GL(glUniform4fv, slot, arraySize, fd);
            break;
        default:
            rsAssert(0);
        }
    }
}

void RsdShader::setupSampler(const Context *rsc, const Sampler *s, const Allocation *tex) {
    RsdHal *dc = (RsdHal *)rsc->mHal.drv;

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
    DrvAllocation *drvTex = (DrvAllocation *)tex->mHal.drv;
    const GLenum target = drvTex->glTarget;

    if (!dc->gl.gl.OES_texture_npot && tex->getType()->getIsNp2()) {
        if (tex->getHasGraphicsMipmaps() &&
            (dc->gl.gl.GL_NV_texture_npot_2D_mipmap || dc->gl.gl.GL_IMG_texture_npot)) {
            if (dc->gl.gl.GL_NV_texture_npot_2D_mipmap) {
                RSD_CALL_GL(glTexParameteri, target, GL_TEXTURE_MIN_FILTER,
                            trans[s->mHal.state.minFilter]);
            } else {
                switch (trans[s->mHal.state.minFilter]) {
                case GL_LINEAR_MIPMAP_LINEAR:
                    RSD_CALL_GL(glTexParameteri, target, GL_TEXTURE_MIN_FILTER,
                                GL_LINEAR_MIPMAP_NEAREST);
                    break;
                default:
                    RSD_CALL_GL(glTexParameteri, target, GL_TEXTURE_MIN_FILTER,
                                trans[s->mHal.state.minFilter]);
                    break;
                }
            }
        } else {
            RSD_CALL_GL(glTexParameteri, target, GL_TEXTURE_MIN_FILTER,
                        transNP[s->mHal.state.minFilter]);
        }
        RSD_CALL_GL(glTexParameteri, target, GL_TEXTURE_MAG_FILTER,
                    transNP[s->mHal.state.magFilter]);
        RSD_CALL_GL(glTexParameteri, target, GL_TEXTURE_WRAP_S, transNP[s->mHal.state.wrapS]);
        RSD_CALL_GL(glTexParameteri, target, GL_TEXTURE_WRAP_T, transNP[s->mHal.state.wrapT]);
    } else {
        if (tex->getHasGraphicsMipmaps()) {
            RSD_CALL_GL(glTexParameteri, target, GL_TEXTURE_MIN_FILTER,
                        trans[s->mHal.state.minFilter]);
        } else {
            RSD_CALL_GL(glTexParameteri, target, GL_TEXTURE_MIN_FILTER,
                        transNP[s->mHal.state.minFilter]);
        }
        RSD_CALL_GL(glTexParameteri, target, GL_TEXTURE_MAG_FILTER, trans[s->mHal.state.magFilter]);
        RSD_CALL_GL(glTexParameteri, target, GL_TEXTURE_WRAP_S, trans[s->mHal.state.wrapS]);
        RSD_CALL_GL(glTexParameteri, target, GL_TEXTURE_WRAP_T, trans[s->mHal.state.wrapT]);
    }

    float anisoValue = rsMin(dc->gl.gl.EXT_texture_max_aniso, s->mHal.state.aniso);
    if (dc->gl.gl.EXT_texture_max_aniso > 1.0f) {
        RSD_CALL_GL(glTexParameterf, target, GL_TEXTURE_MAX_ANISOTROPY_EXT, anisoValue);
    }

    rsdGLCheckError(rsc, "Sampler::setup tex env");
}

void RsdShader::setupTextures(const Context *rsc, RsdShaderCache *sc) {
    if (mRSProgram->mHal.state.texturesCount == 0) {
        return;
    }

    RsdHal *dc = (RsdHal *)rsc->mHal.drv;

    uint32_t numTexturesToBind = mRSProgram->mHal.state.texturesCount;
    uint32_t numTexturesAvailable = dc->gl.gl.maxFragmentTextureImageUnits;
    if (numTexturesToBind >= numTexturesAvailable) {
        LOGE("Attempting to bind %u textures on shader id %u, but only %u are available",
             mRSProgram->mHal.state.texturesCount, (uint32_t)this, numTexturesAvailable);
        rsc->setError(RS_ERROR_BAD_SHADER, "Cannot bind more textuers than available");
        numTexturesToBind = numTexturesAvailable;
    }

    for (uint32_t ct=0; ct < numTexturesToBind; ct++) {
        RSD_CALL_GL(glActiveTexture, GL_TEXTURE0 + ct);
        RSD_CALL_GL(glUniform1i, sc->fragUniformSlot(mTextureUniformIndexStart + ct), ct);

        if (!mRSProgram->mHal.state.textures[ct].get()) {
            // if nothing is bound, reset to default GL texture
            RSD_CALL_GL(glBindTexture, mTextureTargets[ct], 0);
            continue;
        }

        DrvAllocation *drvTex = (DrvAllocation *)mRSProgram->mHal.state.textures[ct]->mHal.drv;
        if (drvTex->glTarget != GL_TEXTURE_2D && drvTex->glTarget != GL_TEXTURE_CUBE_MAP) {
            LOGE("Attempting to bind unknown texture to shader id %u, texture unit %u", (uint)this, ct);
            rsc->setError(RS_ERROR_BAD_SHADER, "Non-texture allocation bound to a shader");
        }
        RSD_CALL_GL(glBindTexture, drvTex->glTarget, drvTex->textureID);
        rsdGLCheckError(rsc, "ProgramFragment::setup tex bind");
        if (mRSProgram->mHal.state.samplers[ct].get()) {
            setupSampler(rsc, mRSProgram->mHal.state.samplers[ct].get(),
                         mRSProgram->mHal.state.textures[ct].get());
        } else {
            RSD_CALL_GL(glTexParameteri, drvTex->glTarget, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            RSD_CALL_GL(glTexParameteri, drvTex->glTarget, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            RSD_CALL_GL(glTexParameteri, drvTex->glTarget, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            RSD_CALL_GL(glTexParameteri, drvTex->glTarget, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            rsdGLCheckError(rsc, "ProgramFragment::setup tex env");
        }
        rsdGLCheckError(rsc, "ProgramFragment::setup uniforms");
    }

    RSD_CALL_GL(glActiveTexture, GL_TEXTURE0);
    mDirty = false;
    rsdGLCheckError(rsc, "ProgramFragment::setup");
}

void RsdShader::setupUserConstants(const Context *rsc, RsdShaderCache *sc, bool isFragment) {
    uint32_t uidx = 0;
    for (uint32_t ct=0; ct < mRSProgram->mHal.state.constantsCount; ct++) {
        Allocation *alloc = mRSProgram->mHal.state.constants[ct].get();
        if (!alloc) {
            LOGE("Attempting to set constants on shader id %u, but alloc at slot %u is not set",
                 (uint32_t)this, ct);
            rsc->setError(RS_ERROR_BAD_SHADER, "No constant allocation bound");
            continue;
        }

        const uint8_t *data = static_cast<const uint8_t *>(alloc->getPtr());
        const Element *e = mRSProgram->mHal.state.constantTypes[ct]->getElement();
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
                LOGV("Uniform  slot=%i, offset=%i, constant=%i, field=%i, uidx=%i, name=%s",
                     slot, offset, ct, field, uidx, fieldName);
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

void RsdShader::setup(const android::renderscript::Context *rsc, RsdShaderCache *sc) {

    setupUserConstants(rsc, sc, mType == GL_FRAGMENT_SHADER);
    setupTextures(rsc, sc);
}

void RsdShader::initAttribAndUniformArray() {
    mAttribCount = 0;
    for (uint32_t ct=0; ct < mRSProgram->mHal.state.inputElementsCount; ct++) {
        const Element *elem = mRSProgram->mHal.state.inputElements[ct].get();
        for (uint32_t field=0; field < elem->getFieldCount(); field++) {
            if (elem->getFieldName(field)[0] != '#') {
                mAttribCount ++;
            }
        }
    }

    mUniformCount = 0;
    for (uint32_t ct=0; ct < mRSProgram->mHal.state.constantsCount; ct++) {
        const Element *elem = mRSProgram->mHal.state.constantTypes[ct]->getElement();

        for (uint32_t field=0; field < elem->getFieldCount(); field++) {
            if (elem->getFieldName(field)[0] != '#') {
                mUniformCount ++;
            }
        }
    }
    mUniformCount += mRSProgram->mHal.state.texturesCount;

    if (mAttribCount) {
        mAttribNames = new String8[mAttribCount];
    }
    if (mUniformCount) {
        mUniformNames = new String8[mUniformCount];
        mUniformArraySizes = new uint32_t[mUniformCount];
    }

    mTextureCount = mRSProgram->mHal.state.texturesCount;
    if (mTextureCount) {
        mTextureTargets = new uint32_t[mTextureCount];
    }
}

void RsdShader::initAddUserElement(const Element *e, String8 *names, uint32_t *arrayLengths,
                                   uint32_t *count, const char *prefix) {
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
