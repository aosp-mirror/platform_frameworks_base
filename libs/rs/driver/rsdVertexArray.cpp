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

#include <rs_hal.h>
#include <rsContext.h>

#include <GLES/gl.h>
#include <GLES2/gl2.h>

#include "rsdGL.h"
#include "rsdCore.h"
#include "rsdVertexArray.h"
#include "rsdShaderCache.h"

using namespace android;
using namespace android::renderscript;

RsdVertexArray::RsdVertexArray(const Attrib *attribs, uint32_t numAttribs) {
    mAttribs = attribs;
    mCount = numAttribs;
}

RsdVertexArray::~RsdVertexArray() {
}

RsdVertexArray::Attrib::Attrib() {
    clear();
}

void RsdVertexArray::Attrib::clear() {
    buffer = 0;
    offset = 0;
    type = 0;
    size = 0;
    stride = 0;
    ptr = NULL;
    normalized = false;
    name.setTo("");
}

void RsdVertexArray::Attrib::set(uint32_t type, uint32_t size, uint32_t stride,
                              bool normalized, uint32_t offset,
                              const char *name) {
    clear();
    this->type = type;
    this->size = size;
    this->offset = offset;
    this->normalized = normalized;
    this->stride = stride;
    this->name.setTo(name);
}

void RsdVertexArray::logAttrib(uint32_t idx, uint32_t slot) const {
    if (idx == 0) {
        ALOGV("Starting vertex attribute binding");
    }
    ALOGV("va %i: slot=%i name=%s buf=%i ptr=%p size=%i  type=0x%x  stride=0x%x  norm=%i  offset=0x%x",
         idx, slot,
         mAttribs[idx].name.string(),
         mAttribs[idx].buffer,
         mAttribs[idx].ptr,
         mAttribs[idx].size,
         mAttribs[idx].type,
         mAttribs[idx].stride,
         mAttribs[idx].normalized,
         mAttribs[idx].offset);
}

void RsdVertexArray::setup(const Context *rsc) const {

    RsdHal *dc = (RsdHal *)rsc->mHal.drv;
    RsdVertexArrayState *state = dc->gl.vertexArrayState;
    RsdShaderCache *sc = dc->gl.shaderCache;

    rsdGLCheckError(rsc, "RsdVertexArray::setup start");
    uint32_t maxAttrs = state->mAttrsEnabledSize;

    for (uint32_t ct=1; ct < maxAttrs; ct++) {
        if(state->mAttrsEnabled[ct]) {
            glDisableVertexAttribArray(ct);
            state->mAttrsEnabled[ct] = false;
        }
    }

    rsdGLCheckError(rsc, "RsdVertexArray::setup disabled");
    for (uint32_t ct=0; ct < mCount; ct++) {
        int32_t slot = sc->vtxAttribSlot(mAttribs[ct].name);
        if (rsc->props.mLogShadersAttr) {
            logAttrib(ct, slot);
        }
        if (slot < 0 || slot >= (int32_t)maxAttrs) {
            continue;
        }
        glEnableVertexAttribArray(slot);
        state->mAttrsEnabled[slot] = true;
        glBindBuffer(GL_ARRAY_BUFFER, mAttribs[ct].buffer);
        glVertexAttribPointer(slot,
                              mAttribs[ct].size,
                              mAttribs[ct].type,
                              mAttribs[ct].normalized,
                              mAttribs[ct].stride,
                              mAttribs[ct].ptr + mAttribs[ct].offset);
    }
    rsdGLCheckError(rsc, "RsdVertexArray::setup done");
}
////////////////////////////////////////////
RsdVertexArrayState::RsdVertexArrayState() {
    mAttrsEnabled = NULL;
    mAttrsEnabledSize = 0;
}

RsdVertexArrayState::~RsdVertexArrayState() {
    if (mAttrsEnabled) {
        delete[] mAttrsEnabled;
        mAttrsEnabled = NULL;
    }
}
void RsdVertexArrayState::init(uint32_t maxAttrs) {
    mAttrsEnabledSize = maxAttrs;
    mAttrsEnabled = new bool[mAttrsEnabledSize];
    for (uint32_t ct = 0; ct < mAttrsEnabledSize; ct++) {
        mAttrsEnabled[ct] = false;
    }
}

