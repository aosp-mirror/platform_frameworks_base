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

#include "rsContext.h"
#include "rsProgramVertex.h"

using namespace android;
using namespace android::renderscript;


ProgramVertex::ProgramVertex(Context *rsc, const char * shaderText,
                             uint32_t shaderLength, const uint32_t * params,
                             uint32_t paramLength)
    : Program(rsc, shaderText, shaderLength, params, paramLength) {
    mRSC->mHal.funcs.vertex.init(mRSC, this, mUserShader.string(), mUserShader.length());
}

ProgramVertex::~ProgramVertex() {
    mRSC->mHal.funcs.vertex.destroy(mRSC, this);
}

void ProgramVertex::setupGL2(Context *rsc, ProgramVertexState *state) {
    if ((state->mLast.get() == this) && !mDirty) {
        return;
    }

    rsc->checkError("ProgramVertex::setupGL2 start");

    if (!isUserProgram()) {
        if (mHal.state.constants[0].get() == NULL) {
            rsc->setError(RS_ERROR_FATAL_UNKNOWN,
                          "Unable to set fixed function emulation matrices because allocation is missing");
            return;
        }
        float *f = static_cast<float *>(mHal.state.constants[0]->getPtr());
        Matrix4x4 mvp;
        mvp.load(&f[RS_PROGRAM_VERTEX_PROJECTION_OFFSET]);
        Matrix4x4 t;
        t.load(&f[RS_PROGRAM_VERTEX_MODELVIEW_OFFSET]);
        mvp.multiply(&t);
        for (uint32_t i = 0; i < 16; i ++) {
            f[RS_PROGRAM_VERTEX_MVP_OFFSET + i] = mvp.m[i];
        }
    }

    state->mLast.set(this);

    rsc->mHal.funcs.vertex.setActive(rsc, this);

    rsc->checkError("ProgramVertex::setupGL2");
}

void ProgramVertex::setProjectionMatrix(Context *rsc, const rsc_Matrix *m) const {
    if (isUserProgram()) {
        rsc->setError(RS_ERROR_FATAL_UNKNOWN,
                      "Attempting to set fixed function emulation matrix projection on user program");
        return;
    }
    if (mHal.state.constants[0].get() == NULL) {
        rsc->setError(RS_ERROR_FATAL_UNKNOWN,
                      "Unable to set fixed function emulation matrix projection because allocation is missing");
        return;
    }
    float *f = static_cast<float *>(mHal.state.constants[0]->getPtr());
    memcpy(&f[RS_PROGRAM_VERTEX_PROJECTION_OFFSET], m, sizeof(rsc_Matrix));
    mDirty = true;
}

void ProgramVertex::setModelviewMatrix(Context *rsc, const rsc_Matrix *m) const {
    if (isUserProgram()) {
        rsc->setError(RS_ERROR_FATAL_UNKNOWN,
                      "Attempting to set fixed function emulation matrix modelview on user program");
        return;
    }
    if (mHal.state.constants[0].get() == NULL) {
        rsc->setError(RS_ERROR_FATAL_UNKNOWN,
                      "Unable to set fixed function emulation matrix modelview because allocation is missing");
        return;
    }
    float *f = static_cast<float *>(mHal.state.constants[0]->getPtr());
    memcpy(&f[RS_PROGRAM_VERTEX_MODELVIEW_OFFSET], m, sizeof(rsc_Matrix));
    mDirty = true;
}

void ProgramVertex::setTextureMatrix(Context *rsc, const rsc_Matrix *m) const {
    if (isUserProgram()) {
        rsc->setError(RS_ERROR_FATAL_UNKNOWN,
                      "Attempting to set fixed function emulation matrix texture on user program");
        return;
    }
    if (mHal.state.constants[0].get() == NULL) {
        rsc->setError(RS_ERROR_FATAL_UNKNOWN,
                      "Unable to set fixed function emulation matrix texture because allocation is missing");
        return;
    }
    float *f = static_cast<float *>(mHal.state.constants[0]->getPtr());
    memcpy(&f[RS_PROGRAM_VERTEX_TEXTURE_OFFSET], m, sizeof(rsc_Matrix));
    mDirty = true;
}

void ProgramVertex::getProjectionMatrix(Context *rsc, rsc_Matrix *m) const {
    if (isUserProgram()) {
        rsc->setError(RS_ERROR_FATAL_UNKNOWN,
                      "Attempting to get fixed function emulation matrix projection on user program");
        return;
    }
    if (mHal.state.constants[0].get() == NULL) {
        rsc->setError(RS_ERROR_FATAL_UNKNOWN,
                      "Unable to get fixed function emulation matrix projection because allocation is missing");
        return;
    }
    float *f = static_cast<float *>(mHal.state.constants[0]->getPtr());
    memcpy(m, &f[RS_PROGRAM_VERTEX_PROJECTION_OFFSET], sizeof(rsc_Matrix));
}

void ProgramVertex::transformToScreen(Context *rsc, float *v4out, const float *v3in) const {
    if (isUserProgram()) {
        return;
    }
    float *f = static_cast<float *>(mHal.state.constants[0]->getPtr());
    Matrix4x4 mvp;
    mvp.loadMultiply((Matrix4x4 *)&f[RS_PROGRAM_VERTEX_MODELVIEW_OFFSET],
                     (Matrix4x4 *)&f[RS_PROGRAM_VERTEX_PROJECTION_OFFSET]);
    mvp.vectorMultiply(v4out, v3in);
}

void ProgramVertex::serialize(OStream *stream) const {
}

ProgramVertex *ProgramVertex::createFromStream(Context *rsc, IStream *stream) {
    return NULL;
}


///////////////////////////////////////////////////////////////////////

ProgramVertexState::ProgramVertexState() {
}

ProgramVertexState::~ProgramVertexState() {
}

void ProgramVertexState::init(Context *rsc) {
    const Element *matrixElem = Element::create(rsc, RS_TYPE_MATRIX_4X4, RS_KIND_USER, false, 1);
    const Element *f2Elem = Element::create(rsc, RS_TYPE_FLOAT_32, RS_KIND_USER, false, 2);
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
    rsc->mStateElement.elementBuilderAdd(f2Elem, "texture0", 1);
    const Element *attrElem = rsc->mStateElement.elementBuilderCreate(rsc);

    Type *inputType = Type::getType(rsc, constInput, 1, 0, 0, false, false);

    String8 shaderString(RS_SHADER_INTERNAL);
    shaderString.append("varying vec4 varColor;\n");
    shaderString.append("varying vec2 varTex0;\n");
    shaderString.append("void main() {\n");
    shaderString.append("  gl_Position = UNI_MVP * ATTRIB_position;\n");
    shaderString.append("  gl_PointSize = 1.0;\n");
    shaderString.append("  varColor = ATTRIB_color;\n");
    shaderString.append("  varTex0 = ATTRIB_texture0;\n");
    shaderString.append("}\n");

    uint32_t tmp[4];
    tmp[0] = RS_PROGRAM_PARAM_CONSTANT;
    tmp[1] = (uint32_t)inputType;
    tmp[2] = RS_PROGRAM_PARAM_INPUT;
    tmp[3] = (uint32_t)attrElem;

    ProgramVertex *pv = new ProgramVertex(rsc, shaderString.string(),
                                          shaderString.length(), tmp, 4);
    Allocation *alloc = new Allocation(rsc, inputType, RS_ALLOCATION_USAGE_SCRIPT | RS_ALLOCATION_USAGE_GRAPHICS_CONSTANTS);
    pv->bindAllocation(rsc, alloc, 0);

    mDefaultAlloc.set(alloc);
    mDefault.set(pv);

    updateSize(rsc);
}

void ProgramVertexState::updateSize(Context *rsc) {
    float *f = static_cast<float *>(mDefaultAlloc->getPtr());

    Matrix4x4 m;
    m.loadOrtho(0,rsc->getWidth(), rsc->getHeight(),0, -1,1);
    memcpy(&f[RS_PROGRAM_VERTEX_PROJECTION_OFFSET], m.m, sizeof(m));
    memcpy(&f[RS_PROGRAM_VERTEX_MVP_OFFSET], m.m, sizeof(m));

    m.loadIdentity();
    memcpy(&f[RS_PROGRAM_VERTEX_MODELVIEW_OFFSET], m.m, sizeof(m));
    memcpy(&f[RS_PROGRAM_VERTEX_TEXTURE_OFFSET], m.m, sizeof(m));
}

void ProgramVertexState::deinit(Context *rsc) {
    mDefaultAlloc.clear();
    mDefault.clear();
    mLast.clear();
}


namespace android {
namespace renderscript {

RsProgramVertex rsi_ProgramVertexCreate(Context *rsc, const char * shaderText,
                             uint32_t shaderLength, const uint32_t * params,
                             uint32_t paramLength) {
    ProgramVertex *pv = new ProgramVertex(rsc, shaderText, shaderLength, params, paramLength);
    pv->incUserRef();
    return pv;
}

}
}
