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

using namespace android;
using namespace android::renderscript;


Light::Light(Context *rsc, bool isLocal, bool isMono) : ObjectBase(rsc)
{
    mAllocFile = __FILE__;
    mAllocLine = __LINE__;
    mIsLocal = isLocal;
    mIsMono = isMono;

    mPosition[0] = 0;
    mPosition[1] = 0;
    mPosition[2] = 1;
    mPosition[3] = 0;

    mColor[0] = 1.f;
    mColor[1] = 1.f;
    mColor[2] = 1.f;
    mColor[3] = 1.f;
}

Light::~Light()
{
}

void Light::setPosition(float x, float y, float z)
{
    mPosition[0] = x;
    mPosition[1] = y;
    mPosition[2] = z;
}

void Light::setColor(float r, float g, float b)
{
    mColor[0] = r;
    mColor[1] = g;
    mColor[2] = b;
}

void Light::setupGL(uint32_t num) const
{
    glLightfv(GL_LIGHT0 + num, GL_DIFFUSE, mColor);
    glLightfv(GL_LIGHT0 + num, GL_SPECULAR, mColor);
    glLightfv(GL_LIGHT0 + num, GL_POSITION, mPosition);
}

////////////////////////////////////////////

LightState::LightState()
{
    clear();
}

LightState::~LightState()
{
}

void LightState::clear()
{
    mIsLocal = false;
    mIsMono = false;
}


////////////////////////////////////////////////////
//

namespace android {
namespace renderscript {

void rsi_LightBegin(Context *rsc)
{
    rsc->mStateLight.clear();
}

void rsi_LightSetLocal(Context *rsc, bool isLocal)
{
    rsc->mStateLight.mIsLocal = isLocal;
}

void rsi_LightSetMonochromatic(Context *rsc, bool isMono)
{
    rsc->mStateLight.mIsMono = isMono;
}

RsLight rsi_LightCreate(Context *rsc)
{
    Light *l = new Light(rsc, rsc->mStateLight.mIsLocal,
                         rsc->mStateLight.mIsMono);
    l->incUserRef();
    return l;
}

void rsi_LightSetColor(Context *rsc, RsLight vl, float r, float g, float b)
{
    Light *l = static_cast<Light *>(vl);
    l->setColor(r, g, b);
}

void rsi_LightSetPosition(Context *rsc, RsLight vl, float x, float y, float z)
{
    Light *l = static_cast<Light *>(vl);
    l->setPosition(x, y, z);
}



}
}
