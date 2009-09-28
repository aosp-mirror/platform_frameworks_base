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


Element::Element(Context *rsc) : ObjectBase(rsc)
{
    mAllocFile = __FILE__;
    mAllocLine = __LINE__;
    mComponents = NULL;
    mComponentCount = 0;
}

Element::Element(Context *rsc, uint32_t count) : ObjectBase(rsc)
{
    mAllocFile = __FILE__;
    mAllocLine = __LINE__;
    mComponents = new ObjectBaseRef<Component> [count];
    mComponentCount = count;
}

Element::~Element()
{
    clear();
}

void Element::clear()
{
    delete [] mComponents;
    mComponents = NULL;
    mComponentCount = 0;
}

void Element::setComponent(uint32_t idx, Component *c)
{
    rsAssert(!mComponents[idx].get());
    rsAssert(idx < mComponentCount);
    mComponents[idx].set(c);

// Fixme: This should probably not be here
    c->incUserRef();
}


size_t Element::getSizeBits() const
{
    size_t total = 0;
    for (size_t ct=0; ct < mComponentCount; ct++) {
        total += mComponents[ct]->getBits();
    }
    return total;
}

size_t Element::getComponentOffsetBits(uint32_t componentNumber) const
{
    size_t offset = 0;
    for (uint32_t ct = 0; ct < componentNumber; ct++) {
        offset += mComponents[ct]->getBits();
    }
    return offset;
}

uint32_t Element::getGLType() const
{
    int bits[4];

    if (mComponentCount > 4) {
        return 0;
    }

    for (uint32_t ct=0; ct < mComponentCount; ct++) {
        bits[ct] = mComponents[ct]->getBits();
        if (mComponents[ct]->getType() != Component::UNSIGNED) {
            return 0;
        }
        if (!mComponents[ct]->getIsNormalized()) {
            return 0;
        }
    }

    switch(mComponentCount) {
    case 1:
        if (bits[0] == 8) {
            return GL_UNSIGNED_BYTE;
        }
        return 0;
    case 2:
        if ((bits[0] == 8) &&
            (bits[1] == 8)) {
            return GL_UNSIGNED_BYTE;
        }
        return 0;
    case 3:
        if ((bits[0] == 8) &&
            (bits[1] == 8) &&
            (bits[2] == 8)) {
            return GL_UNSIGNED_BYTE;
        }
        if ((bits[0] == 5) &&
            (bits[1] == 6) &&
            (bits[2] == 5)) {
            return GL_UNSIGNED_SHORT_5_6_5;
        }
        return 0;
    case 4:
        if ((bits[0] == 8) &&
            (bits[1] == 8) &&
            (bits[2] == 8) &&
            (bits[3] == 8)) {
            return GL_UNSIGNED_BYTE;
        }
        if ((bits[0] == 4) &&
            (bits[1] == 4) &&
            (bits[2] == 4) &&
            (bits[3] == 4)) {
            return GL_UNSIGNED_SHORT_4_4_4_4;
        }
        if ((bits[0] == 5) &&
            (bits[1] == 5) &&
            (bits[2] == 5) &&
            (bits[3] == 1)) {
            return GL_UNSIGNED_SHORT_5_5_5_1;
        }
    }
    return 0;
}

uint32_t Element::getGLFormat() const
{
    switch(mComponentCount) {
    case 1:
        if (mComponents[0]->getKind() == Component::ALPHA) {
            return GL_ALPHA;
        }
        if (mComponents[0]->getKind() == Component::LUMINANCE) {
            return GL_LUMINANCE;
        }
        break;
    case 2:
        if ((mComponents[0]->getKind() == Component::LUMINANCE) &&
            (mComponents[1]->getKind() == Component::ALPHA)) {
            return GL_LUMINANCE_ALPHA;
        }
        break;
    case 3:
        if ((mComponents[0]->getKind() == Component::RED) &&
            (mComponents[1]->getKind() == Component::GREEN) &&
            (mComponents[2]->getKind() == Component::BLUE)) {
            return GL_RGB;
        }
        break;
    case 4:
        if ((mComponents[0]->getKind() == Component::RED) &&
            (mComponents[1]->getKind() == Component::GREEN) &&
            (mComponents[2]->getKind() == Component::BLUE) &&
            (mComponents[3]->getKind() == Component::ALPHA)) {
            return GL_RGBA;
        }
        break;
    }
    return 0;
}


void Element::dumpLOGV(const char *prefix) const
{
    ObjectBase::dumpLOGV(prefix);
    LOGV("%s   Element: components %i,  size %i", prefix, mComponentCount, getSizeBytes());
    for (uint32_t ct = 0; ct < mComponentCount; ct++) {
        char buf[1024];
        sprintf(buf, "%s component %i: ", prefix, ct);
        mComponents[ct]->dumpLOGV(buf);
    }
}

ElementState::ElementState()
{
}

ElementState::~ElementState()
{
}

/////////////////////////////////////////
//

namespace android {
namespace renderscript {

void rsi_ElementBegin(Context *rsc)
{
    rsc->mStateElement.mComponentBuildList.clear();
}

void rsi_ElementAdd(Context *rsc, RsDataKind dk, RsDataType dt, bool isNormalized, size_t bits, const char *name)
{
    ElementState * sec = &rsc->mStateElement;

    rsAssert(bits > 0);

    Component *c = new Component(rsc,
                                 static_cast<Component::DataKind>(dk),
                                 static_cast<Component::DataType>(dt),
                                 isNormalized,
                                 bits,
                                 name);
    sec->mComponentBuildList.add(c);
}

RsElement rsi_ElementCreate(Context *rsc)
{
    ElementState * sec = &rsc->mStateElement;
    Element *se = new Element(rsc, sec->mComponentBuildList.size());

    rsAssert(se->getComponentCount() > 0);

    for (size_t ct = 0; ct < se->getComponentCount(); ct++) {
        se->setComponent(ct, sec->mComponentBuildList[ct]);
    }

    rsc->mStateElement.mComponentBuildList.clear();
    se->incUserRef();
    return se;
}


}
}
