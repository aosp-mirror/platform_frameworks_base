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

using namespace android;
using namespace android::renderscript;

ProgramFragment::ProgramFragment(Context *rsc, const char * shaderText,
                                 uint32_t shaderLength, const uint32_t * params,
                                 uint32_t paramLength)
    : Program(rsc, shaderText, shaderLength, params, paramLength) {
    mConstantColor[0] = 1.f;
    mConstantColor[1] = 1.f;
    mConstantColor[2] = 1.f;
    mConstantColor[3] = 1.f;

    mRSC->mHal.funcs.fragment.init(mRSC, this, mUserShader.string(), mUserShader.length());
}

ProgramFragment::~ProgramFragment() {
    mRSC->mHal.funcs.fragment.destroy(mRSC, this);
}

void ProgramFragment::setConstantColor(Context *rsc, float r, float g, float b, float a) {
    if (isUserProgram()) {
        LOGE("Attempting to set fixed function emulation color on user program");
        rsc->setError(RS_ERROR_BAD_SHADER, "Cannot  set fixed function emulation color on user program");
        return;
    }
    if (mHal.state.constants[0].get() == NULL) {
        LOGE("Unable to set fixed function emulation color because allocation is missing");
        rsc->setError(RS_ERROR_BAD_SHADER, "Unable to set fixed function emulation color because allocation is missing");
        return;
    }
    mConstantColor[0] = r;
    mConstantColor[1] = g;
    mConstantColor[2] = b;
    mConstantColor[3] = a;
    memcpy(mHal.state.constants[0]->getPtr(), mConstantColor, 4*sizeof(float));
    mDirty = true;
}

void ProgramFragment::setup(Context *rsc, ProgramFragmentState *state) {
    if ((state->mLast.get() == this) && !mDirty) {
        return;
    }
    state->mLast.set(this);

    for (uint32_t ct=0; ct < mHal.state.texturesCount; ct++) {
        if (!mHal.state.textures[ct].get()) {
            LOGE("No texture bound for shader id %u, texture unit %u", (uint)this, ct);
            rsc->setError(RS_ERROR_BAD_SHADER, "No texture bound");
            continue;
        }
    }

    rsc->mHal.funcs.fragment.setActive(rsc, this);
}

void ProgramFragment::serialize(OStream *stream) const {
}

ProgramFragment *ProgramFragment::createFromStream(Context *rsc, IStream *stream) {
    return NULL;
}

ProgramFragmentState::ProgramFragmentState() {
    mPF = NULL;
}

ProgramFragmentState::~ProgramFragmentState() {
    ObjectBase::checkDelete(mPF);
    mPF = NULL;
}

void ProgramFragmentState::init(Context *rsc) {
    String8 shaderString(RS_SHADER_INTERNAL);
    shaderString.append("varying lowp vec4 varColor;\n");
    shaderString.append("varying vec2 varTex0;\n");
    shaderString.append("void main() {\n");
    shaderString.append("  lowp vec4 col = UNI_Color;\n");
    shaderString.append("  gl_FragColor = col;\n");
    shaderString.append("}\n");

    ObjectBaseRef<const Element> colorElem = Element::createRef(rsc, RS_TYPE_FLOAT_32, RS_KIND_USER, false, 4);
    Element::Builder builder;
    builder.add(colorElem.get(), "Color", 1);
    ObjectBaseRef<const Element> constInput = builder.create(rsc);

    ObjectBaseRef<Type> inputType = Type::getTypeRef(rsc, constInput.get(), 1, 0, 0, false, false);

    uint32_t tmp[2];
    tmp[0] = RS_PROGRAM_PARAM_CONSTANT;
    tmp[1] = (uint32_t)inputType.get();

    Allocation *constAlloc = Allocation::createAllocation(rsc, inputType.get(),
                              RS_ALLOCATION_USAGE_SCRIPT | RS_ALLOCATION_USAGE_GRAPHICS_CONSTANTS);
    ProgramFragment *pf = new ProgramFragment(rsc, shaderString.string(),
                                              shaderString.length(), tmp, 2);
    pf->bindAllocation(rsc, constAlloc, 0);
    pf->setConstantColor(rsc, 1.0f, 1.0f, 1.0f, 1.0f);

    mDefault.set(pf);
}

void ProgramFragmentState::deinit(Context *rsc) {
    mDefault.clear();
    mLast.clear();
}

namespace android {
namespace renderscript {

RsProgramFragment rsi_ProgramFragmentCreate(Context *rsc, const char * shaderText,
                             size_t shaderLength, const uint32_t * params,
                             size_t paramLength) {
    ProgramFragment *pf = new ProgramFragment(rsc, shaderText, shaderLength, params, paramLength);
    pf->incUserRef();
    //LOGE("rsi_ProgramFragmentCreate %p", pf);
    return pf;
}

}
}

