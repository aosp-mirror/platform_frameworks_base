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
    mType = RS_TYPE_FLOAT;
    mIsNormalized = false;
    mKind = RS_KIND_USER;
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

uint32_t Element::getGLType() const
{
    int bits[4];

    if (!mFieldCount) {
        switch (mType) {
        case RS_TYPE_FLOAT:
            if (mBits == 32) {
                return GL_FLOAT;
            }
            return 0;
        case RS_TYPE_SIGNED:
            switch (mBits) {
            case 8:
                return GL_BYTE;
            case 16:
                return GL_SHORT;
            //case 32:
                //return GL_INT;
            }
            return 0;
        case RS_TYPE_UNSIGNED:
            switch (mBits) {
            case 8:
                return GL_UNSIGNED_BYTE;
            case 16:
                return GL_UNSIGNED_SHORT;
            //case 32:
                //return GL_UNSIGNED_INT;
            }
            return 0;
        }
    }

    if (mFieldCount > 4) {
        return 0;
    }

    for (uint32_t ct=0; ct < mFieldCount; ct++) {
        bits[ct] = mFields[ct].e->mBits;
        if (mFields[ct].e->mFieldCount) {
            return 0;
        }
        if (mFields[ct].e->mType != RS_TYPE_UNSIGNED) {
            return 0;
        }
        if (!mFields[ct].e->mIsNormalized) {
            return 0;
        }
    }

    switch(mFieldCount) {
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
    if (!mFieldCount) {
        if (mKind == RS_KIND_ALPHA) {
            return GL_ALPHA;
        }
        if (mKind == RS_KIND_LUMINANCE) {
            return GL_LUMINANCE;
        }
    }

    switch(mFieldCount) {
    case 2:
        if ((mFields[0].e->mKind == RS_KIND_LUMINANCE) &&
            (mFields[1].e->mKind == RS_KIND_ALPHA)) {
            return GL_LUMINANCE_ALPHA;
        }
        break;
    case 3:
        if ((mFields[0].e->mKind == RS_KIND_RED) &&
            (mFields[1].e->mKind == RS_KIND_GREEN) &&
            (mFields[2].e->mKind == RS_KIND_BLUE)) {
            return GL_RGB;
        }
        break;
    case 4:
        if ((mFields[0].e->mKind == RS_KIND_RED) &&
            (mFields[1].e->mKind == RS_KIND_GREEN) &&
            (mFields[2].e->mKind == RS_KIND_BLUE) &&
            (mFields[3].e->mKind == RS_KIND_ALPHA)) {
            return GL_RGBA;
        }
        break;
    }
    return 0;
}

const char * Element::getCType() const
{
    switch(mType) {
    case RS_TYPE_FLOAT:
        return "float";
    case RS_TYPE_SIGNED:
    case RS_TYPE_UNSIGNED:
        switch(mBits) {
        case 32:
            return "int";
        case 16:
            return "short";
        case 8:
            return "char";
        }
        break;
    }
    return NULL;
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

Element * Element::create(Context *rsc, RsDataKind dk, RsDataType dt,
                          bool isNorm, size_t bits)
{
    Element *e = new Element(rsc);
    e->mKind = dk;
    e->mType = dt;
    e->mIsNormalized = isNorm;
    e->mBits = bits;
    return e;
}

Element * Element::create(Context *rsc, Element **ein, const char **nin,
                          const size_t * lengths, size_t count)
{
    Element *e = new Element(rsc);
    e->mFields = new ElementField_t [count];
    e->mFieldCount = count;

    for (size_t ct=0; ct < count; ct++) {
        e->mFields[ct].e.set(ein[ct]);
        e->mFields[ct].name.setTo(nin[ct], lengths[ct]);
    }

    return e;
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
    ElementState * sec = &rsc->mStateElement;

    sec->mBuildList.clear();
    sec->mNames.clear();
}

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


}
}
