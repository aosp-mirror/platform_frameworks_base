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

using namespace android;
using namespace android::renderscript;


Light::Light(bool isLocal, bool isMono)
{
    mIsLocal = isLocal;
    mIsMono = isMono;

    mX = 0;
    mY = 0;
    mZ = 0;

    mR = 1.f;
    mG = 1.f;
    mB = 1.f;
}

Light::~Light()
{
}

void Light::setPosition(float x, float y, float z)
{
    mX = x;
    mY = y;
    mZ = z;
}

void Light::setColor(float r, float g, float b)
{
    mR = r;
    mG = g;
    mB = b;
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
    Light *l = new Light(rsc->mStateLight.mIsLocal,
                         rsc->mStateLight.mIsMono);
    l->incRef();
    return l;
}

void rsi_LightDestroy(Context *rsc, RsLight vl)
{
    Light *l = static_cast<Light *>(vl);
    l->decRef();
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
