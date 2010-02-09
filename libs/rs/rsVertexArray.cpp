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

#include <GLES/gl.h>
#include <GLES2/gl2.h>

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
    mCount = 0;
}

VertexArray::Attrib::Attrib()
{
    clear();
}

void VertexArray::Attrib::set(const Attrib &a)
{
    buffer = a.buffer;
    offset = a.offset;
    type = a.type;
    size = a.size;
    stride = a.stride;
    normalized = a.normalized;
    kind = RS_KIND_USER;
    name.setTo(a.name);
}

void VertexArray::Attrib::clear()
{
    buffer = 0;
    offset = 0;
    type = 0;
    size = 0;
    stride = 0;
    normalized = false;
    name.setTo("");
}

void VertexArray::clear(uint32_t n)
{
    mAttribs[n].clear();
}

void VertexArray::addUser(const Attrib &a, uint32_t stride)
{
    assert(mCount < RS_MAX_ATTRIBS);
    mAttribs[mCount].set(a);
    mAttribs[mCount].buffer = mActiveBuffer;
    mAttribs[mCount].stride = stride;
    mAttribs[mCount].kind = RS_KIND_USER;
    mCount ++;
}

void VertexArray::addLegacy(uint32_t type, uint32_t size, uint32_t stride, RsDataKind kind, bool normalized, uint32_t offset)
{
    assert(mCount < RS_MAX_ATTRIBS);
    mAttribs[mCount].clear();
    mAttribs[mCount].type = type;
    mAttribs[mCount].size = size;
    mAttribs[mCount].offset = offset;
    mAttribs[mCount].normalized = normalized;
    mAttribs[mCount].buffer = mActiveBuffer;
    mAttribs[mCount].stride = stride;
    mAttribs[mCount].kind = kind;
    mCount ++;
}

void VertexArray::logAttrib(uint32_t idx, uint32_t slot) const {
    LOGE("va %i: slot=%i name=%s buf=%i  size=%i  type=0x%x  kind=%i  stride=0x%x  norm=%i  offset=0x%x", idx, slot,
         mAttribs[idx].name.string(),
         mAttribs[idx].buffer,
         mAttribs[idx].size,
         mAttribs[idx].type,
         mAttribs[idx].kind,
         mAttribs[idx].stride,
         mAttribs[idx].normalized,
         mAttribs[idx].offset);
}

void VertexArray::setupGL(const Context *rsc, class VertexArrayState *state) const
{
    glClientActiveTexture(GL_TEXTURE0);
    glDisableClientState(GL_NORMAL_ARRAY);
    glDisableClientState(GL_COLOR_ARRAY);
    glDisableClientState(GL_TEXTURE_COORD_ARRAY);
    glDisableClientState(GL_POINT_SIZE_ARRAY_OES);

    for (uint32_t ct=0; ct < mCount; ct++) {
        switch(mAttribs[ct].kind) {
        case RS_KIND_POSITION:
            //logAttrib(POSITION);
            glEnableClientState(GL_VERTEX_ARRAY);
            glBindBuffer(GL_ARRAY_BUFFER, mAttribs[ct].buffer);
            glVertexPointer(mAttribs[ct].size,
                            mAttribs[ct].type,
                            mAttribs[ct].stride,
                            (void *)mAttribs[ct].offset);
            break;

        case RS_KIND_NORMAL:
            //logAttrib(NORMAL);
            glEnableClientState(GL_NORMAL_ARRAY);
            rsAssert(mAttribs[ct].size == 3);
            glBindBuffer(GL_ARRAY_BUFFER, mAttribs[ct].buffer);
            glNormalPointer(mAttribs[ct].type,
                            mAttribs[ct].stride,
                            (void *)mAttribs[ct].offset);
            break;

        case RS_KIND_COLOR:
            //logAttrib(COLOR);
            glEnableClientState(GL_COLOR_ARRAY);
            glBindBuffer(GL_ARRAY_BUFFER, mAttribs[ct].buffer);
            glColorPointer(mAttribs[ct].size,
                           mAttribs[ct].type,
                           mAttribs[ct].stride,
                           (void *)mAttribs[ct].offset);
            break;

        case RS_KIND_TEXTURE:
            //logAttrib(TEXTURE);
            glEnableClientState(GL_TEXTURE_COORD_ARRAY);
            glBindBuffer(GL_ARRAY_BUFFER, mAttribs[ct].buffer);
            glTexCoordPointer(mAttribs[ct].size,
                              mAttribs[ct].type,
                              mAttribs[ct].stride,
                              (void *)mAttribs[ct].offset);
            break;

        case RS_KIND_POINT_SIZE:
            //logAttrib(POINT_SIZE);
            glEnableClientState(GL_POINT_SIZE_ARRAY_OES);
            glBindBuffer(GL_ARRAY_BUFFER, mAttribs[ct].buffer);
            glPointSizePointerOES(mAttribs[ct].type,
                                  mAttribs[ct].stride,
                                  (void *)mAttribs[ct].offset);
            break;

        default:
            rsAssert(0);
        }
    }

    rsc->checkError("VertexArray::setupGL");
}

void VertexArray::setupGL2(const Context *rsc, class VertexArrayState *state, ShaderCache *sc) const
{
    rsc->checkError("VertexArray::setupGL2 start");
    for (uint32_t ct=1; ct <= state->mLastEnableCount; ct++) {
        glDisableVertexAttribArray(ct);
    }

    rsc->checkError("VertexArray::setupGL2 disabled");
    for (uint32_t ct=0; ct < mCount; ct++) {
        uint32_t slot = 0;
        if (sc->isUserVertexProgram()) {
            slot = sc->vtxAttribSlot(ct);
        } else {
            if (mAttribs[ct].kind == RS_KIND_USER) {
                continue;
            }
            slot = sc->vtxAttribSlot(mAttribs[ct].kind);
        }

        //logAttrib(ct, slot);
        glEnableVertexAttribArray(slot);
        glBindBuffer(GL_ARRAY_BUFFER, mAttribs[ct].buffer);

        glVertexAttribPointer(slot,
                              mAttribs[ct].size,
                              mAttribs[ct].type,
                              mAttribs[ct].normalized,
                              mAttribs[ct].stride,
                              (void *)mAttribs[ct].offset);
    }
    state->mLastEnableCount = mCount;
    rsc->checkError("VertexArray::setupGL2 done");
}
////////////////////////////////////////////

void VertexArrayState::init(Context *) {
    mLastEnableCount = 0;
}

