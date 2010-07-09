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
#include <GLES2/gl2.h>
#else
#include "rsContextHostStub.h"
#include <OpenGL/gl.h>
#endif


using namespace android;
using namespace android::renderscript;


VertexArray::VertexArray()
{
    clearAll();
}

VertexArray::~VertexArray()
{
}


void VertexArray::clearAll()
{
    for (uint32_t ct=0; ct < RS_MAX_ATTRIBS; ct++) {
        mAttribs[ct].clear();
    }
    mActiveBuffer = 0;
    mActivePointer = NULL;
    mCount = 0;
}

VertexArray::Attrib::Attrib()
{
    clear();
}

void VertexArray::Attrib::set(const Attrib &a)
{
    buffer = a.buffer;
    ptr = a.ptr;
    offset = a.offset;
    type = a.type;
    size = a.size;
    stride = a.stride;
    normalized = a.normalized;
    name.setTo(a.name);
}

void VertexArray::Attrib::clear()
{
    buffer = 0;
    offset = 0;
    type = 0;
    size = 0;
    stride = 0;
    ptr = NULL;
    normalized = false;
    name.setTo("");
}

void VertexArray::clear(uint32_t n)
{
    mAttribs[n].clear();
}

void VertexArray::add(const Attrib &a, uint32_t stride)
{
    rsAssert(mCount < RS_MAX_ATTRIBS);
    mAttribs[mCount].set(a);
    mAttribs[mCount].buffer = mActiveBuffer;
    mAttribs[mCount].ptr = mActivePointer;
    mAttribs[mCount].stride = stride;
    mCount ++;
}

void VertexArray::add(uint32_t type, uint32_t size, uint32_t stride, bool normalized, uint32_t offset, const char *name)
{
    rsAssert(mCount < RS_MAX_ATTRIBS);
    mAttribs[mCount].clear();
    mAttribs[mCount].type = type;
    mAttribs[mCount].size = size;
    mAttribs[mCount].offset = offset;
    mAttribs[mCount].normalized = normalized;
    mAttribs[mCount].stride = stride;
    mAttribs[mCount].name.setTo(name);

    mAttribs[mCount].buffer = mActiveBuffer;
    mAttribs[mCount].ptr = mActivePointer;
    mCount ++;
}

void VertexArray::logAttrib(uint32_t idx, uint32_t slot) const {
    LOGE("va %i: slot=%i name=%s buf=%i ptr=%p size=%i  type=0x%x  stride=0x%x  norm=%i  offset=0x%x", idx, slot,
         mAttribs[idx].name.string(),
         mAttribs[idx].buffer,
         mAttribs[idx].ptr,
         mAttribs[idx].size,
         mAttribs[idx].type,
         mAttribs[idx].stride,
         mAttribs[idx].normalized,
         mAttribs[idx].offset);
}

void VertexArray::setupGL2(const Context *rsc, class VertexArrayState *state, ShaderCache *sc) const
{
    rsc->checkError("VertexArray::setupGL2 start");
    for (uint32_t ct=1; ct <= 0xf/*state->mLastEnableCount*/; ct++) {
        glDisableVertexAttribArray(ct);
    }

    rsc->checkError("VertexArray::setupGL2 disabled");
    for (uint32_t ct=0; ct < mCount; ct++) {
        uint32_t slot = 0;

        if (mAttribs[ct].name[0] == '#') {
            continue;
        }

        if (sc->isUserVertexProgram()) {
            slot = sc->vtxAttribSlot(ct);
        } else {
            if (mAttribs[ct].name == "position") {
                slot = 0;
            } else if (mAttribs[ct].name == "color") {
                slot = 1;
            } else if (mAttribs[ct].name == "normal") {
                slot = 2;
            } else if (mAttribs[ct].name == "texture0") {
                slot = 3;
            } else {
                continue;
            }
        }

        //logAttrib(ct, slot);
        glEnableVertexAttribArray(slot);
        glBindBuffer(GL_ARRAY_BUFFER, mAttribs[ct].buffer);
        glVertexAttribPointer(slot,
                              mAttribs[ct].size,
                              mAttribs[ct].type,
                              mAttribs[ct].normalized,
                              mAttribs[ct].stride,
                              mAttribs[ct].ptr + mAttribs[ct].offset);
    }
    state->mLastEnableCount = mCount;
    rsc->checkError("VertexArray::setupGL2 done");
}
////////////////////////////////////////////

void VertexArrayState::init(Context *) {
    mLastEnableCount = 0;
}

