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

#include "rsProgramVertex.h"

using namespace android;
using namespace android::renderscript;


ProgramVertex::ProgramVertex(Context *rsc, const char * shaderText,
                             uint32_t shaderLength, const uint32_t * params,
                             uint32_t paramLength) :
    Program(rsc, shaderText, shaderLength, params, paramLength)
{
    mAllocFile = __FILE__;
    mAllocLine = __LINE__;

    init(rsc);
}

ProgramVertex::~ProgramVertex()
{
}

static void logMatrix(const char *txt, const float *f)
{
    LOGV("Matrix %s, %p", txt, f);
    LOGV("%6.4f, %6.4f, %6.4f, %6.4f", f[0], f[4], f[8], f[12]);
    LOGV("%6.4f, %6.4f, %6.4f, %6.4f", f[1], f[5], f[9], f[13]);
    LOGV("%6.4f, %6.4f, %6.4f, %6.4f", f[2], f[6], f[10], f[14]);
    LOGV("%6.4f, %6.4f, %6.4f, %6.4f", f[3], f[7], f[11], f[15]);
}

void ProgramVertex::loadShader(Context *rsc) {
    Program::loadShader(rsc, GL_VERTEX_SHADER);
}

void ProgramVertex::createShader()
{
    if (mUserShader.length() > 1) {

        appendUserConstants();

        for (uint32_t ct=0; ct < mInputCount; ct++) {
            const Element *e = mInputElements[ct].get();
            for (uint32_t field=0; field < e->getFieldCount(); field++) {
                const Element *f = e->getField(field);
                const char *fn = e->getFieldName(field);

                if (fn[0] == '#') {
                    continue;
                }

                // Cannot be complex
                rsAssert(!f->getFieldCount());
                switch(f->getComponent().getVectorSize()) {
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
        mShader.append(mUserShader);
    } else {
        LOGE("ProgramFragment::createShader cannot create program, shader code not defined");
        rsAssert(0);
    }
}

void ProgramVertex::setupGL2(Context *rsc, ProgramVertexState *state, ShaderCache *sc)
{
    //LOGE("sgl2 vtx1 %x", glGetError());
    if ((state->mLast.get() == this) && !mDirty) {
        return;
    }

    rsc->checkError("ProgramVertex::setupGL2 start");

    if(!isUserProgram()) {
        float *f = static_cast<float *>(mConstants[0]->getPtr());
        Matrix mvp;
        mvp.load(&f[RS_PROGRAM_VERTEX_PROJECTION_OFFSET]);
        Matrix t;
        t.load(&f[RS_PROGRAM_VERTEX_MODELVIEW_OFFSET]);
        mvp.multiply(&t);
        for(uint32_t i = 0; i < 16; i ++) {
            f[RS_PROGRAM_VERTEX_MVP_OFFSET + i] = mvp.m[i];
        }
    }

    rsc->checkError("ProgramVertex::setupGL2 begin uniforms");
    setupUserConstants(rsc, sc, false);

    state->mLast.set(this);
    rsc->checkError("ProgramVertex::setupGL2");
}

void ProgramVertex::setProjectionMatrix(Context *rsc, const rsc_Matrix *m) const
{
    if(isUserProgram()) {
        LOGE("Attempting to set fixed function emulation matrix projection on user program");
        rsc->setError(RS_ERROR_BAD_SHADER, "Cannot set emulation matrix on user shader");
        return;
    }
    if(mConstants[0].get() == NULL) {
        LOGE("Unable to set fixed function emulation matrix projection because allocation is missing");
        return;
    }
    float *f = static_cast<float *>(mConstants[0]->getPtr());
    memcpy(&f[RS_PROGRAM_VERTEX_PROJECTION_OFFSET], m, sizeof(rsc_Matrix));
    mDirty = true;
}

void ProgramVertex::setModelviewMatrix(Context *rsc, const rsc_Matrix *m) const
{
    if(isUserProgram()) {
        LOGE("Attempting to set fixed function emulation matrix modelview on user program");
        rsc->setError(RS_ERROR_BAD_SHADER, "Cannot set emulation matrix on user shader");
        return;
    }
    if(mConstants[0].get() == NULL) {
        LOGE("Unable to set fixed function emulation matrix modelview because allocation is missing");
        rsc->setError(RS_ERROR_BAD_SHADER, "Fixed function allocation missing");
        return;
    }
    float *f = static_cast<float *>(mConstants[0]->getPtr());
    memcpy(&f[RS_PROGRAM_VERTEX_MODELVIEW_OFFSET], m, sizeof(rsc_Matrix));
    mDirty = true;
}

void ProgramVertex::setTextureMatrix(Context *rsc, const rsc_Matrix *m) const
{
    if(isUserProgram()) {
        LOGE("Attempting to set fixed function emulation matrix texture on user program");
        rsc->setError(RS_ERROR_BAD_SHADER, "Cannot set emulation matrix on user shader");
        return;
    }
    if(mConstants[0].get() == NULL) {
        LOGE("Unable to set fixed function emulation matrix texture because allocation is missing");
        rsc->setError(RS_ERROR_BAD_SHADER, "Fixed function allocation missing");
        return;
    }
    float *f = static_cast<float *>(mConstants[0]->getPtr());
    memcpy(&f[RS_PROGRAM_VERTEX_TEXTURE_OFFSET], m, sizeof(rsc_Matrix));
    mDirty = true;
}

void ProgramVertex::getProjectionMatrix(Context *rsc, rsc_Matrix *m) const
{
    if(isUserProgram()) {
        LOGE("Attempting to get fixed function emulation matrix projection on user program");
        rsc->setError(RS_ERROR_BAD_SHADER, "Cannot get emulation matrix on user shader");
        return;
    }
    if(mConstants[0].get() == NULL) {
        LOGE("Unable to get fixed function emulation matrix projection because allocation is missing");
        rsc->setError(RS_ERROR_BAD_SHADER, "Fixed function allocation missing");
        return;
    }
    float *f = static_cast<float *>(mConstants[0]->getPtr());
    memcpy(m, &f[RS_PROGRAM_VERTEX_PROJECTION_OFFSET], sizeof(rsc_Matrix));
}

void ProgramVertex::transformToScreen(Context *rsc, float *v4out, const float *v3in) const
{
    if(isUserProgram()) {
        return;
    }
    float *f = static_cast<float *>(mConstants[0]->getPtr());
    Matrix mvp;
    mvp.loadMultiply((Matrix *)&f[RS_PROGRAM_VERTEX_MODELVIEW_OFFSET],
                     (Matrix *)&f[RS_PROGRAM_VERTEX_PROJECTION_OFFSET]);
    mvp.vectorMultiply(v4out, v3in);
}

void ProgramVertex::init(Context *rsc)
{
    mAttribCount = 0;
    if (mUserShader.size() > 0) {
        for (uint32_t ct=0; ct < mInputCount; ct++) {
            initAddUserElement(mInputElements[ct].get(), mAttribNames, &mAttribCount, "ATTRIB_");
        }

        mUniformCount = 0;
        for (uint32_t ct=0; ct < mConstantCount; ct++) {
            initAddUserElement(mConstantTypes[ct]->getElement(), mUniformNames, &mUniformCount, "UNI_");
        }
    }
    createShader();
}

void ProgramVertex::serialize(OStream *stream) const
{

}

ProgramVertex *ProgramVertex::createFromStream(Context *rsc, IStream *stream)
{
    return NULL;
}


///////////////////////////////////////////////////////////////////////

ProgramVertexState::ProgramVertexState()
{
}

ProgramVertexState::~ProgramVertexState()
{
}

void ProgramVertexState::init(Context *rsc)
{
    const Element *matrixElem = Element::create(rsc, RS_TYPE_MATRIX_4X4, RS_KIND_USER, false, 1);
    const Element *f3Elem = Element::create(rsc, RS_TYPE_FLOAT_32, RS_KIND_USER, false, 3);
    const Element *f4Elem = Element::create(rsc, RS_TYPE_FLOAT_32, RS_KIND_USER, false, 4);

    rsc->mStateElement.elementBuilderBegin();
    rsc->mStateElement.elementBuilderAdd(matrixElem, "MV", 1);
    rsc->mStateElement.elementBuilderAdd(matrixElem, "P", 1);
    rsc->mStateElement.elementBuilderAdd(matrixElem, "TexMatrix", 1);
    rsc->mStateElement.elementBuilderAdd(matrixElem, "MVP", 1);
    const Element *constInput = rsc->mStateElement.elementBuilderCreate(rsc);

    rsc->mStateElement.elementBuilderBegin();
    rsc->mStateElement.elementBuilderAdd(f4Elem, "position", 1);
    rsc->mStateElement.elementBuilderAdd(f4Elem, "color", 1);
    rsc->mStateElement.elementBuilderAdd(f3Elem, "normal", 1);
    rsc->mStateElement.elementBuilderAdd(f4Elem, "texture0", 1);
    const Element *attrElem = rsc->mStateElement.elementBuilderCreate(rsc);

    Type *inputType = new Type(rsc);
    inputType->setElement(constInput);
    inputType->setDimX(1);
    inputType->compute();

    String8 shaderString(RS_SHADER_INTERNAL);
    shaderString.append("varying vec4 varColor;\n");
    shaderString.append("varying vec4 varTex0;\n");
    shaderString.append("void main() {\n");
    shaderString.append("  gl_Position = UNI_MVP * ATTRIB_position;\n");
    shaderString.append("  gl_PointSize = 1.0;\n");
    shaderString.append("  varColor = ATTRIB_color;\n");
    shaderString.append("  varTex0 = ATTRIB_texture0;\n");
    shaderString.append("}\n");

    uint32_t tmp[6];
    tmp[0] = RS_PROGRAM_PARAM_CONSTANT;
    tmp[1] = (uint32_t)inputType;
    tmp[2] = RS_PROGRAM_PARAM_INPUT;
    tmp[3] = (uint32_t)attrElem;
    tmp[4] = RS_PROGRAM_PARAM_TEXTURE_COUNT;
    tmp[5] = 0;

    ProgramVertex *pv = new ProgramVertex(rsc, shaderString.string(),
                                          shaderString.length(), tmp, 6);
    Allocation *alloc = new Allocation(rsc, inputType);
    pv->bindAllocation(rsc, alloc, 0);

    mDefaultAlloc.set(alloc);
    mDefault.set(pv);

    updateSize(rsc);

}

void ProgramVertexState::updateSize(Context *rsc)
{
    float *f = static_cast<float *>(mDefaultAlloc->getPtr());

    Matrix m;
    m.loadOrtho(0,rsc->getWidth(), rsc->getHeight(),0, -1,1);
    memcpy(&f[RS_PROGRAM_VERTEX_PROJECTION_OFFSET], m.m, sizeof(m));
    memcpy(&f[RS_PROGRAM_VERTEX_MVP_OFFSET], m.m, sizeof(m));

    m.loadIdentity();
    memcpy(&f[RS_PROGRAM_VERTEX_MODELVIEW_OFFSET], m.m, sizeof(m));
    memcpy(&f[RS_PROGRAM_VERTEX_TEXTURE_OFFSET], m.m, sizeof(m));
}

void ProgramVertexState::deinit(Context *rsc)
{
    mDefaultAlloc.clear();
    mDefault.clear();
    mLast.clear();
}


namespace android {
namespace renderscript {

RsProgramVertex rsi_ProgramVertexCreate(Context *rsc, const char * shaderText,
                             uint32_t shaderLength, const uint32_t * params,
                             uint32_t paramLength)
{
    ProgramVertex *pv = new ProgramVertex(rsc, shaderText, shaderLength, params, paramLength);
    pv->incUserRef();
    return pv;
}


}
}
