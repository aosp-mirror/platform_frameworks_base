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
    memset(mAttribs, 0, sizeof(mAttribs));
    mActiveBuffer = 0;
}

VertexArray::~VertexArray()
{
}


void VertexArray::clearAll()
{
    memset(mAttribs, 0, sizeof(mAttribs));
    mActiveBuffer = 0;
}

void VertexArray::clear(AttribName n)
{
    mAttribs[n].size = 0;
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

void VertexArray::setTexture(uint32_t size, uint32_t type, uint32_t stride, uint32_t offset, uint32_t num)
{
    mAttribs[TEXTURE_0 + num].buffer = mActiveBuffer;
    mAttribs[TEXTURE_0 + num].type = type;
    mAttribs[TEXTURE_0 + num].size = size;
    mAttribs[TEXTURE_0 + num].offset = offset;
    mAttribs[TEXTURE_0 + num].stride = stride;
    mAttribs[TEXTURE_0 + num].normalized = false;
}

void VertexArray::logAttrib(uint32_t idx) const {
    LOGE("va %i: buf=%i  size=%i  type=0x%x  stride=0x%x  offset=0x%x", idx,
         mAttribs[idx].buffer,
         mAttribs[idx].size,
         mAttribs[idx].type,
         mAttribs[idx].stride,
         mAttribs[idx].offset);
}

void VertexArray::setupGL(class VertexArrayState *state) const
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

    for (uint32_t ct=0; ct < RS_MAX_TEXTURE; ct++) {
        glClientActiveTexture(GL_TEXTURE0 + ct);
        if (mAttribs[TEXTURE_0 + ct].size) {
            //logAttrib(TEXTURE_0 + ct);
            glEnableClientState(GL_TEXTURE_COORD_ARRAY);
            glBindBuffer(GL_ARRAY_BUFFER, mAttribs[TEXTURE_0 + ct].buffer);
            glTexCoordPointer(mAttribs[TEXTURE_0 + ct].size,
                              mAttribs[TEXTURE_0 + ct].type,
                              mAttribs[TEXTURE_0 + ct].stride,
                              (void *)mAttribs[TEXTURE_0 + ct].offset);
        } else {
            glDisableClientState(GL_TEXTURE_COORD_ARRAY);
        }
    }
    glClientActiveTexture(GL_TEXTURE0);

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
}

void VertexArray::setupGL2(class VertexArrayState *state, ShaderCache *sc) const
{
    for (int ct=1; ct < _LAST; ct++) {
        glDisableVertexAttribArray(ct);
    }

    for (int ct=0; ct < _LAST; ct++) {
        if (mAttribs[ct].size) {
            //logAttrib(ct);
            glEnableVertexAttribArray(sc->vtxAttribSlot(ct));
            glBindBuffer(GL_ARRAY_BUFFER, mAttribs[ct].buffer);
            //LOGV("attp %i %i", ct, sc->vtxAttribSlot(ct));

            glVertexAttribPointer(sc->vtxAttribSlot(ct),
                                  mAttribs[ct].size,
                                  mAttribs[ct].type,
                                  mAttribs[ct].normalized,
                                  mAttribs[ct].stride,
                                  (void *)mAttribs[ct].offset);
        } else {
            //glDisableVertexAttribArray(ct);
            rsAssert(ct);
        }
    }
}
////////////////////////////////////////////

void VertexArrayState::init(Context *) {
    memset(this, 0, sizeof(this));
}

