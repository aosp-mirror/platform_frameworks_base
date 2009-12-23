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
    mBits = 0;
    mAllocFile = __FILE__;
    mAllocLine = __LINE__;
    mFields = NULL;
    mFieldCount = 0;
}


Element::~Element()
{
    clear();
}

void Element::clear()
{
    delete [] mFields;
    mFields = NULL;
    mFieldCount = 0;
}
/*
void Element::setComponent(uint32_t idx, Component *c)
{
    rsAssert(!mComponents[idx].get());
    rsAssert(idx < mComponentCount);
    mComponents[idx].set(c);

// Fixme: This should probably not be here
    c->incUserRef();
}
*/

size_t Element::getSizeBits() const
{
    if (!mFieldCount) {
        return mBits;
    }

    size_t total = 0;
    for (size_t ct=0; ct < mFieldCount; ct++) {
        total += mFields[ct].e->mBits;
    }
    return total;
}

size_t Element::getFieldOffsetBits(uint32_t componentNumber) const
{
    size_t offset = 0;
    for (uint32_t ct = 0; ct < componentNumber; ct++) {
        offset += mFields[ct].e->mBits;
    }
    return offset;
}

void Element::dumpLOGV(const char *prefix) const
{
    ObjectBase::dumpLOGV(prefix);
    LOGV("%s   Element: components %i,  size %i", prefix, mFieldCount, mBits);
    for (uint32_t ct = 0; ct < mFieldCount; ct++) {
        char buf[1024];
        sprintf(buf, "%s component %i: ", prefix, ct);
        //mComponents[ct]->dumpLOGV(buf);
    }
}


Element * Element::create(Context *rsc, RsDataType dt, RsDataKind dk,
                            bool isNorm, uint32_t vecSize)
{
    Element *e = new Element(rsc);
    e->mComponent.set(dt, dk, isNorm, vecSize);
    e->mBits = e->mComponent.getBits();
    return e;
}

Element * Element::create(Context *rsc, size_t count, const Element **ein,
                            const char **nin, const size_t * lengths)
{
    Element *e = new Element(rsc);
    e->mFields = new ElementField_t [count];
    e->mFieldCount = count;

    for (size_t ct=0; ct < count; ct++) {
        e->mFields[ct].e.set(ein[ct]);
        e->mFields[ct].name.setTo(nin[ct], lengths[ct]);
        LOGE("element %p %s", ein[ct], e->mFields[ct].name.string());
    }

    return e;
}

String8 Element::getCStructBody(uint32_t indent) const
{
    String8 si;
    for (uint32_t ct=0; ct < indent; ct++) {
        si.append(" ");
    }

    String8 s(si);
    s.append("{\n");
    for (uint32_t ct = 0; ct < mFieldCount; ct++) {
        s.append(si);
        s.append(mFields[ct].e->getCType(indent+4));
        s.append(" ");
        s.append(mFields[ct].name);
        s.append(";\n");
    }
    s.append(si);
    s.append("}");
    return s;
}

String8 Element::getCType(uint32_t indent) const
{
    String8 s;
    for (uint32_t ct=0; ct < indent; ct++) {
        s.append(" ");
    }

    if (!mFieldCount) {
        // Basic component.
        s.append(mComponent.getCType());
    } else {
        s.append("struct ");
        s.append(getCStructBody(indent));
    }

    return s;
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

RsElement rsi_ElementCreate(Context *rsc,
                            RsDataType dt,
                            RsDataKind dk,
                            bool norm,
                            uint32_t vecSize)
{
    //LOGE("rsi_ElementCreate %i %i %i %i", dt, dk, norm, vecSize);
    Element *e = Element::create(rsc, dt, dk, norm, vecSize);
    e->incUserRef();
    return e;
}

RsElement rsi_ElementCreate2(Context *rsc,
                             size_t count,
                             const RsElement * ein,
                             const char ** names,
                             const size_t * nameLengths)
{
    //LOGE("rsi_ElementCreate2 %i", count);
    Element *e = Element::create(rsc, count, (const Element **)ein, names, nameLengths);
    e->incUserRef();
    return e;
}

/*
void rsi_ElementAdd(Context *rsc, RsDataKind dk, RsDataType dt, bool isNormalized, size_t bits, const char *name)
{
    ElementState * sec = &rsc->mStateElement;

    rsAssert(bits > 0);

    Element *c = Element::create(rsc, dk, dt, isNormalized, bits);
    sec->mBuildList.add(c);
    if (name)
        sec->mNames.add(String8(name));
    else
        sec->mNames.add(String8(""));
}

RsElement rsi_ElementCreate(Context *rsc)
{
    ElementState * sec = &rsc->mStateElement;

    size_t count = sec->mBuildList.size();
    rsAssert(count > 0);

    if (count == 1) {
        Element *se = sec->mBuildList[0];
        se->incUserRef();
        sec->mBuildList.clear();
        sec->mNames.clear();
        return se;
    }

    Element ** tmpElements = (Element **)calloc(count, sizeof(Element *));
    const char ** tmpNames = (const char **)calloc(count, sizeof(char *));
    size_t * tmpLengths = (size_t *)calloc(count, sizeof(size_t));


    for (size_t ct = 0; ct < count; ct++) {
        tmpElements[ct] = sec->mBuildList[ct];
        tmpNames[ct] = sec->mNames[ct].string();
        tmpLengths[ct] = sec->mNames[ct].length();
    }
    Element *se = Element::create(rsc, tmpElements, tmpNames, tmpLengths, count);

    sec->mBuildList.clear();
    sec->mNames.clear();
    se->incUserRef();
    free(tmpElements);
    free(tmpNames);
    free(tmpLengths);
    return se;
}
*/


}
}
