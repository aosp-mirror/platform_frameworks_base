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
#else
#include "rsContextHostStub.h"
#include <OpenGL/gl.h>
#include <OpenGl/glext.h>
#endif //ANDROID_RS_BUILD_FOR_HOST

#include "rsProgramRaster.h"

using namespace android;
using namespace android::renderscript;


ProgramRaster::ProgramRaster(Context *rsc,
                             bool pointSmooth,
                             bool lineSmooth,
                             bool pointSprite) :
    Program(rsc)
{
    mAllocFile = __FILE__;
    mAllocLine = __LINE__;
    mPointSmooth = pointSmooth;
    mLineSmooth = lineSmooth;
    mPointSprite = pointSprite;
    mLineWidth = 1.0f;
    mCull = RS_CULL_BACK;
}

ProgramRaster::~ProgramRaster()
{
}

void ProgramRaster::setLineWidth(float s)
{
    mLineWidth = s;
    mDirty = true;
}

void ProgramRaster::setCullMode(RsCullMode mode)
{
    mCull = mode;
    mDirty = true;
}

void ProgramRaster::setupGL(const Context *rsc, ProgramRasterState *state)
{
    if (state->mLast.get() == this && !mDirty) {
        return;
    }
    state->mLast.set(this);
    mDirty = false;

    if (mPointSmooth) {
        glEnable(GL_POINT_SMOOTH);
    } else {
        glDisable(GL_POINT_SMOOTH);
    }

    glLineWidth(mLineWidth);
    if (mLineSmooth) {
        glEnable(GL_LINE_SMOOTH);
    } else {
        glDisable(GL_LINE_SMOOTH);
    }

    if (rsc->checkVersion1_1()) {
#ifndef ANDROID_RS_BUILD_FOR_HOST
        if (mPointSprite) {
            glEnable(GL_POINT_SPRITE_OES);
        } else {
            glDisable(GL_POINT_SPRITE_OES);
        }
#endif //ANDROID_RS_BUILD_FOR_HOST
    }

    switch(mCull) {
        case RS_CULL_BACK:
            glEnable(GL_CULL_FACE);
            glCullFace(GL_BACK);
            break;
        case RS_CULL_FRONT:
            glEnable(GL_CULL_FACE);
            glCullFace(GL_FRONT);
            break;
        case RS_CULL_NONE:
            glDisable(GL_CULL_FACE);
            break;
    }
}

void ProgramRaster::setupGL2(const Context *rsc, ProgramRasterState *state)
{
    if (state->mLast.get() == this && !mDirty) {
        return;
    }
    state->mLast.set(this);
    mDirty = false;

    switch(mCull) {
        case RS_CULL_BACK:
            glEnable(GL_CULL_FACE);
            glCullFace(GL_BACK);
            break;
        case RS_CULL_FRONT:
            glEnable(GL_CULL_FACE);
            glCullFace(GL_FRONT);
            break;
        case RS_CULL_NONE:
            glDisable(GL_CULL_FACE);
            break;
    }
}

void ProgramRaster::serialize(OStream *stream) const
{

}

ProgramRaster *ProgramRaster::createFromStream(Context *rsc, IStream *stream)
{
    return NULL;
}

ProgramRasterState::ProgramRasterState()
{
}

ProgramRasterState::~ProgramRasterState()
{
}

void ProgramRasterState::init(Context *rsc)
{
    ProgramRaster *pr = new ProgramRaster(rsc, false, false, false);
    mDefault.set(pr);
}

void ProgramRasterState::deinit(Context *rsc)
{
    mDefault.clear();
    mLast.clear();
}


namespace android {
namespace renderscript {

RsProgramRaster rsi_ProgramRasterCreate(Context * rsc,
                                      bool pointSmooth,
                                      bool lineSmooth,
                                      bool pointSprite)
{
    ProgramRaster *pr = new ProgramRaster(rsc,
                                          pointSmooth,
                                          lineSmooth,
                                          pointSprite);
    pr->incUserRef();
    return pr;
}

void rsi_ProgramRasterSetLineWidth(Context * rsc, RsProgramRaster vpr, float s)
{
    ProgramRaster *pr = static_cast<ProgramRaster *>(vpr);
    pr->setLineWidth(s);
}

void rsi_ProgramRasterSetCullMode(Context * rsc, RsProgramRaster vpr, RsCullMode mode)
{
    ProgramRaster *pr = static_cast<ProgramRaster *>(vpr);
    pr->setCullMode(mode);
}


}
}

