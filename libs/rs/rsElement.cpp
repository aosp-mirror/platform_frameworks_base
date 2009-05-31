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

void ElementState::initPredefined()
{
    Component * u_8  = new Component(Component::USER,   Component::UNSIGNED,  true,  8);
    Component * i_8  = new Component(Component::USER,   Component::SIGNED,    true,  8);
    Component * u_16 = new Component(Component::USER,   Component::UNSIGNED,  true,  16);
    Component * i_16 = new Component(Component::USER,   Component::SIGNED,    true,  16);
    Component * u_32 = new Component(Component::USER,   Component::UNSIGNED,  true,  32);
    Component * i_32 = new Component(Component::USER,   Component::SIGNED,    true,  32);
    Component * f_32 = new Component(Component::USER,   Component::FLOAT,     true,  32);


    Component * r_4  = new Component(Component::RED,    Component::UNSIGNED,  true,  4);
    Component * r_5  = new Component(Component::RED,    Component::UNSIGNED,  true,  5);
    Component * r_8  = new Component(Component::RED,    Component::UNSIGNED,  true,  8);

    Component * g_4  = new Component(Component::GREEN,  Component::UNSIGNED,  true,  4);
    Component * g_5  = new Component(Component::GREEN,  Component::UNSIGNED,  true,  5);
    Component * g_6  = new Component(Component::GREEN,  Component::UNSIGNED,  true,  6);
    Component * g_8  = new Component(Component::GREEN,  Component::UNSIGNED,  true,  8);

    Component * b_4  = new Component(Component::BLUE,   Component::UNSIGNED,  true,  4);
    Component * b_5  = new Component(Component::BLUE,   Component::UNSIGNED,  true,  5);
    Component * b_8  = new Component(Component::BLUE,   Component::UNSIGNED,  true,  8);

    Component * a_1  = new Component(Component::ALPHA,  Component::UNSIGNED,  true,  1);
    Component * a_4  = new Component(Component::ALPHA,  Component::UNSIGNED,  true,  4);
    Component * a_8  = new Component(Component::ALPHA,  Component::UNSIGNED,  true,  8);

    Component * idx_16 = new Component(Component::INDEX,  Component::UNSIGNED,  false, 16);
    Component * idx_32 = new Component(Component::INDEX,  Component::UNSIGNED,  false, 32);

    Component * x    = new Component(Component::X,      Component::FLOAT,     false, 32);
    Component * y    = new Component(Component::Y,      Component::FLOAT,     false, 32);
    Component * z    = new Component(Component::Z,      Component::FLOAT,     false, 32);

    Component * nx   = new Component(Component::NX,     Component::FLOAT,     false, 32);
    Component * ny   = new Component(Component::NY,     Component::FLOAT,     false, 32);
    Component * nz   = new Component(Component::NZ,     Component::FLOAT,     false, 32);

    Component * s    = new Component(Component::S,      Component::FLOAT,     false, 32);
    Component * t    = new Component(Component::T,      Component::FLOAT,     false, 32);

    Element * e;

    e = new Element(1);
    e->setComponent(0, u_8);
    mPredefinedList.add(Predefined(RS_ELEMENT_USER_U8, e));

    e = new Element(1);
    e->setComponent(0, i_8);
    mPredefinedList.add(Predefined(RS_ELEMENT_USER_I8, e));

    e = new Element(1);
    e->setComponent(0, u_16);
    mPredefinedList.add(Predefined(RS_ELEMENT_USER_U16, e));

    e = new Element(1);
    e->setComponent(0, i_16);
    mPredefinedList.add(Predefined(RS_ELEMENT_USER_I16, e));

    e = new Element(1);
    e->setComponent(0, u_32);
    mPredefinedList.add(Predefined(RS_ELEMENT_USER_U32, e));

    e = new Element(1);
    e->setComponent(0, i_32);
    mPredefinedList.add(Predefined(RS_ELEMENT_USER_I32, e));

    e = new Element(1);
    e->setComponent(0, f_32);
    mPredefinedList.add(Predefined(RS_ELEMENT_USER_FLOAT, e));

    e = new Element(1);
    e->setComponent(0, a_8);
    mPredefinedList.add(Predefined(RS_ELEMENT_A_8, e));

    e = new Element(3);
    e->setComponent(0, r_5);
    e->setComponent(1, g_6);
    e->setComponent(2, b_5);
    mPredefinedList.add(Predefined(RS_ELEMENT_RGB_565, e));

    e = new Element(4);
    e->setComponent(0, r_5);
    e->setComponent(1, g_5);
    e->setComponent(2, b_5);
    e->setComponent(3, a_1);
    mPredefinedList.add(Predefined(RS_ELEMENT_RGBA_5551, e));

    e = new Element(4);
    e->setComponent(0, r_4);
    e->setComponent(1, g_4);
    e->setComponent(2, b_4);
    e->setComponent(3, a_4);
    mPredefinedList.add(Predefined(RS_ELEMENT_RGBA_4444, e));

    e = new Element(3);
    e->setComponent(0, r_8);
    e->setComponent(1, g_8);
    e->setComponent(2, b_8);
    mPredefinedList.add(Predefined(RS_ELEMENT_RGB_888, e));

    e = new Element(4);
    e->setComponent(0, r_8);
    e->setComponent(1, g_8);
    e->setComponent(2, b_8);
    e->setComponent(3, a_8);
    mPredefinedList.add(Predefined(RS_ELEMENT_RGBA_8888, e));

    e = new Element(1);
    e->setComponent(0, idx_16);
    mPredefinedList.add(Predefined(RS_ELEMENT_INDEX_16, e));

    e = new Element(1);
    e->setComponent(0, idx_32);
    mPredefinedList.add(Predefined(RS_ELEMENT_INDEX_32, e));

    e = new Element(2);
    e->setComponent(0, x);
    e->setComponent(1, y);
    mPredefinedList.add(Predefined(RS_ELEMENT_XY_F32, e));

    e = new Element(3);
    e->setComponent(0, x);
    e->setComponent(1, y);
    e->setComponent(2, z);
    mPredefinedList.add(Predefined(RS_ELEMENT_XYZ_F32, e));

    e = new Element(4);
    e->setComponent(0, s);
    e->setComponent(1, t);
    e->setComponent(2, x);
    e->setComponent(3, y);
    mPredefinedList.add(Predefined(RS_ELEMENT_ST_XY_F32, e));

    e = new Element(5);
    e->setComponent(0, s);
    e->setComponent(1, t);
    e->setComponent(2, x);
    e->setComponent(3, y);
    e->setComponent(4, z);
    mPredefinedList.add(Predefined(RS_ELEMENT_ST_XYZ_F32, e));

    e = new Element(6);
    e->setComponent(0, nx);
    e->setComponent(1, ny);
    e->setComponent(2, nz);
    e->setComponent(3, x);
    e->setComponent(4, y);
    e->setComponent(5, z);
    mPredefinedList.add(Predefined(RS_ELEMENT_NORM_XYZ_F32, e));

    e = new Element(8);
    e->setComponent(0, nx);
    e->setComponent(1, ny);
    e->setComponent(2, nz);
    e->setComponent(3, s);
    e->setComponent(4, t);
    e->setComponent(5, x);
    e->setComponent(6, y);
    e->setComponent(7, z);
    mPredefinedList.add(Predefined(RS_ELEMENT_NORM_ST_XYZ_F32, e));
}


Element::Element()
{
    mComponents = NULL;
    mComponentCount = 0;
}

Element::Element(uint32_t count)
{
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
    c->incRef();
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

void rsi_ElementAddPredefined(Context *rsc, RsElementPredefined predef)
{
    ElementState * sec = &rsc->mStateElement;

    RsElement ve = rsi_ElementGetPredefined(rsc, predef);
    const Element *e = static_cast<const Element *>(ve);

    for(size_t ct = 0; ct < sec->mPredefinedList[predef].mElement->getComponentCount(); ct++) {
        sec->mComponentBuildList.add(sec->mPredefinedList[predef].mElement->getComponent(ct));
    }
}

RsElement rsi_ElementGetPredefined(Context *rsc, RsElementPredefined predef)
{
    ElementState * sec = &rsc->mStateElement;

    if (!sec->mPredefinedList.size()) {
        sec->initPredefined();
    }

    if ((predef < 0) || 
        (static_cast<uint32_t>(predef) >= sec->mPredefinedList.size())) {
        LOGE("rsElementGetPredefined: Request for bad predefined type");
        // error
        return NULL;
    }

    rsAssert(sec->mPredefinedList[predef].mEnum == predef);
    Element * e = sec->mPredefinedList[predef].mElement;
    e->incRef();
    return e;
}

void rsi_ElementAdd(Context *rsc, RsDataKind dk, RsDataType dt, bool isNormalized, size_t bits)
{
    ElementState * sec = &rsc->mStateElement;

}

RsElement rsi_ElementCreate(Context *rsc)
{
    ElementState * sec = &rsc->mStateElement;

    Element *se = new Element(sec->mComponentBuildList.size());
    sec->mAllElements.add(se);

    for (size_t ct = 0; ct < se->getComponentCount(); ct++) {
        se->setComponent(ct, sec->mComponentBuildList[ct]);
    }

    rsc->mStateElement.mComponentBuildList.clear();
    se->incRef();

    LOGE("Create %p", se);
    return se;
}

void rsi_ElementDestroy(Context *rsc, RsElement vse)
{
    ElementState * sec = &rsc->mStateElement;
    Element * se = static_cast<Element *>(vse);

    for (size_t ct = 0; ct < sec->mAllElements.size(); ct++) {
        if (sec->mAllElements[ct] == se) {
            sec->mAllElements.removeAt(ct);
            break;
        }
    }
    se->decRef();
}


}
}
