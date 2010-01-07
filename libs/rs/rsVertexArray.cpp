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
    mActiveBuffer = 0;
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

void VertexArray::clear(AttribName n)
{
    mAttribs[n].clear();
}

void VertexArray::setPosition(uint32_t size, uint32_t type, uint32_t stride, uint32_t offset)
{
    mAttribs[POSITION].buffer = mActiveBuffer;
    mAttribs[POSITION].type = type;
    mAttribs[POSITION].size = size;
    mAttribs[POSITION].offset = offset;
    mAttribs[POSITION].stride = stride;
    mAttribs[POSITION].normalized = false;
}

void VertexArray::setColor(uint32_t size, uint32_t type, uint32_t stride, uint32_t offset)
{
    mAttribs[COLOR].buffer = mActiveBuffer;
    mAttribs[COLOR].type = type;
    mAttribs[COLOR].size = size;
    mAttribs[COLOR].offset = offset;
    mAttribs[COLOR].stride = stride;
    mAttribs[COLOR].normalized = type != GL_FLOAT;
}

void VertexArray::setNormal(uint32_t type, uint32_t stride, uint32_t offset)
{
    mAttribs[NORMAL].buffer = mActiveBuffer;
    mAttribs[NORMAL].type = type;
    mAttribs[NORMAL].size = 3;
    mAttribs[NORMAL].offset = offset;
    mAttribs[NORMAL].stride = stride;
    mAttribs[NORMAL].normalized = type != GL_FLOAT;
}

void VertexArray::setPointSize(uint32_t type, uint32_t stride, uint32_t offset)
{
    mAttribs[POINT_SIZE].buffer = mActiveBuffer;
    mAttribs[POINT_SIZE].type = type;
    mAttribs[POINT_SIZE].size = 1;
    mAttribs[POINT_SIZE].offset = offset;
    mAttribs[POINT_SIZE].stride = stride;
    mAttribs[POINT_SIZE].normalized = false;
}

void VertexArray::setTexture(uint32_t size, uint32_t type, uint32_t stride, uint32_t offset)
{
    mAttribs[TEXTURE].buffer = mActiveBuffer;
    mAttribs[TEXTURE].type = type;
    mAttribs[TEXTURE].size = size;
    mAttribs[TEXTURE].offset = offset;
    mAttribs[TEXTURE].stride = stride;
    mAttribs[TEXTURE].normalized = false;
}

void VertexArray::setUser(const Attrib &a, uint32_t stride)
{
    // Find empty slot, some may be taken by legacy 1.1 slots.
    uint32_t slot = 0;
    while (mAttribs[slot].size) slot++;
    rsAssert(slot < RS_MAX_ATTRIBS);
    mAttribs[slot].set(a);
    mAttribs[slot].buffer = mActiveBuffer;
    mAttribs[slot].stride = stride;
}

void VertexArray::logAttrib(uint32_t idx, uint32_t slot) const {
    LOGE("va %i: slot=%i name=%s buf=%i  size=%i  type=0x%x  stride=0x%x  norm=%i  offset=0x%x", idx, slot,
         mAttribs[idx].name.string(),
         mAttribs[idx].buffer,
         mAttribs[idx].size,
         mAttribs[idx].type,
         mAttribs[idx].stride,
         mAttribs[idx].normalized,
         mAttribs[idx].offset);
}

void VertexArray::setupGL(const Context *rsc, class VertexArrayState *state) const
{
    if (mAttribs[POSITION].size) {
        //logAttrib(POSITION);
        glEnableClientState(GL_VERTEX_ARRAY);
        glBindBuffer(GL_ARRAY_BUFFER, mAttribs[POSITION].buffer);
        glVertexPointer(mAttribs[POSITION].size,
                        mAttribs[POSITION].type,
                        mAttribs[POSITION].stride,
                        (void *)mAttribs[POSITION].offset);
    } else {
        rsAssert(0);
    }

    if (mAttribs[NORMAL].size) {
        //logAttrib(NORMAL);
        glEnableClientState(GL_NORMAL_ARRAY);
        rsAssert(mAttribs[NORMAL].size == 3);
        glBindBuffer(GL_ARRAY_BUFFER, mAttribs[NORMAL].buffer);
        glNormalPointer(mAttribs[NORMAL].type,
                        mAttribs[NORMAL].stride,
                        (void *)mAttribs[NORMAL].offset);
    } else {
        glDisableClientState(GL_NORMAL_ARRAY);
    }

    if (mAttribs[COLOR].size) {
        //logAttrib(COLOR);
        glEnableClientState(GL_COLOR_ARRAY);
        glBindBuffer(GL_ARRAY_BUFFER, mAttribs[COLOR].buffer);
        glColorPointer(mAttribs[COLOR].size,
                       mAttribs[COLOR].type,
                       mAttribs[COLOR].stride,
                       (void *)mAttribs[COLOR].offset);
    } else {
        glDisableClientState(GL_COLOR_ARRAY);
    }

    glClientActiveTexture(GL_TEXTURE0);
    if (mAttribs[TEXTURE].size) {
        //logAttrib(TEXTURE);
        glEnableClientState(GL_TEXTURE_COORD_ARRAY);
        glBindBuffer(GL_ARRAY_BUFFER, mAttribs[TEXTURE].buffer);
        glTexCoordPointer(mAttribs[TEXTURE].size,
                          mAttribs[TEXTURE].type,
                          mAttribs[TEXTURE].stride,
                          (void *)mAttribs[TEXTURE].offset);
    } else {
        glDisableClientState(GL_TEXTURE_COORD_ARRAY);
    }

    if (mAttribs[POINT_SIZE].size) {
        //logAttrib(POINT_SIZE);
        glEnableClientState(GL_POINT_SIZE_ARRAY_OES);
        glBindBuffer(GL_ARRAY_BUFFER, mAttribs[POINT_SIZE].buffer);
        glPointSizePointerOES(mAttribs[POINT_SIZE].type,
                              mAttribs[POINT_SIZE].stride,
                              (void *)mAttribs[POINT_SIZE].offset);
    } else {
        glDisableClientState(GL_POINT_SIZE_ARRAY_OES);
    }
    rsc->checkError("VertexArray::setupGL");
}

void VertexArray::setupGL2(const Context *rsc, class VertexArrayState *state, ShaderCache *sc) const
{
    for (int ct=1; ct < _LAST; ct++) {
        glDisableVertexAttribArray(ct);
    }

    for (uint32_t ct=0; ct < RS_MAX_ATTRIBS; ct++) {
        if (mAttribs[ct].size && (sc->vtxAttribSlot(ct) >= 0)) {
            //logAttrib(ct, sc->vtxAttribSlot(ct));
            glEnableVertexAttribArray(sc->vtxAttribSlot(ct));
            glBindBuffer(GL_ARRAY_BUFFER, mAttribs[ct].buffer);

            glVertexAttribPointer(sc->vtxAttribSlot(ct),
                                  mAttribs[ct].size,
                                  mAttribs[ct].type,
                                  mAttribs[ct].normalized,
                                  mAttribs[ct].stride,
                                  (void *)mAttribs[ct].offset);
        }
    }
    rsc->checkError("VertexArray::setupGL2");
}
////////////////////////////////////////////

void VertexArrayState::init(Context *) {
}

