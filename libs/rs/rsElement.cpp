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


Element::Element(Context *rsc) : ObjectBase(rsc) {
    mBits = 0;
    mFields = NULL;
    mFieldCount = 0;
    mHasReference = false;
}

Element::~Element() {
    for (uint32_t ct = 0; ct < mRSC->mStateElement.mElements.size(); ct++) {
        if (mRSC->mStateElement.mElements[ct] == this) {
            mRSC->mStateElement.mElements.removeAt(ct);
            break;
        }
    }
    clear();
}

void Element::clear() {
    delete [] mFields;
    mFields = NULL;
    mFieldCount = 0;
    mHasReference = false;
}

size_t Element::getSizeBits() const {
    if (!mFieldCount) {
        return mBits;
    }

    size_t total = 0;
    for (size_t ct=0; ct < mFieldCount; ct++) {
        total += mFields[ct].e->mBits * mFields[ct].arraySize;
    }
    return total;
}

void Element::dumpLOGV(const char *prefix) const {
    ObjectBase::dumpLOGV(prefix);
    LOGV("%s Element: fieldCount: %zu,  size bytes: %zu", prefix, mFieldCount, getSizeBytes());
    for (uint32_t ct = 0; ct < mFieldCount; ct++) {
        LOGV("%s Element field index: %u ------------------", prefix, ct);
        LOGV("%s name: %s, offsetBits: %u, arraySize: %u",
             prefix, mFields[ct].name.string(), mFields[ct].offsetBits, mFields[ct].arraySize);
        mFields[ct].e->dumpLOGV(prefix);
    }
}

void Element::serialize(OStream *stream) const {
    // Need to identify ourselves
    stream->addU32((uint32_t)getClassId());

    String8 name(getName());
    stream->addString(&name);

    mComponent.serialize(stream);

    // Now serialize all the fields
    stream->addU32(mFieldCount);
    for (uint32_t ct = 0; ct < mFieldCount; ct++) {
        stream->addString(&mFields[ct].name);
        stream->addU32(mFields[ct].arraySize);
        mFields[ct].e->serialize(stream);
    }
}

Element *Element::createFromStream(Context *rsc, IStream *stream) {
    // First make sure we are reading the correct object
    RsA3DClassID classID = (RsA3DClassID)stream->loadU32();
    if (classID != RS_A3D_CLASS_ID_ELEMENT) {
        LOGE("element loading skipped due to invalid class id\n");
        return NULL;
    }

    String8 name;
    stream->loadString(&name);

    Element *elem = new Element(rsc);
    elem->mComponent.loadFromStream(stream);

    elem->mFieldCount = stream->loadU32();
    if (elem->mFieldCount) {
        elem->mFields = new ElementField_t [elem->mFieldCount];
        for (uint32_t ct = 0; ct < elem->mFieldCount; ct ++) {
            stream->loadString(&elem->mFields[ct].name);
            elem->mFields[ct].arraySize = stream->loadU32();
            Element *fieldElem = Element::createFromStream(rsc, stream);
            elem->mFields[ct].e.set(fieldElem);
        }
    }

    // We need to check if this already exists
    for (uint32_t ct=0; ct < rsc->mStateElement.mElements.size(); ct++) {
        Element *ee = rsc->mStateElement.mElements[ct];
        if (ee->isEqual(elem)) {
            ObjectBase::checkDelete(elem);
            ee->incUserRef();
            return ee;
        }
    }

    elem->compute();
    rsc->mStateElement.mElements.push(elem);
    return elem;
}

bool Element::isEqual(const Element *other) const {
    if (other == NULL) {
        return false;
    }
    if (!other->getFieldCount() && !mFieldCount) {
        if ((other->getType() == getType()) &&
           (other->getKind() == getKind()) &&
           (other->getComponent().getIsNormalized() == getComponent().getIsNormalized()) &&
           (other->getComponent().getVectorSize() == getComponent().getVectorSize())) {
            return true;
        }
        return false;
    }
    if (other->getFieldCount() == mFieldCount) {
        for (uint32_t i=0; i < mFieldCount; i++) {
            if ((!other->mFields[i].e->isEqual(mFields[i].e.get())) ||
                (other->mFields[i].name.length() != mFields[i].name.length()) ||
                (other->mFields[i].name != mFields[i].name) ||
                (other->mFields[i].arraySize != mFields[i].arraySize)) {
                return false;
            }
        }
        return true;
    }
    return false;
}

void Element::compute() {
    if (mFieldCount == 0) {
        mBits = mComponent.getBits();
        mHasReference = mComponent.isReference();
        return;
    }

    size_t bits = 0;
    for (size_t ct=0; ct < mFieldCount; ct++) {
        mFields[ct].offsetBits = bits;
        bits += mFields[ct].e->getSizeBits() * mFields[ct].arraySize;

        if (mFields[ct].e->mHasReference) {
            mHasReference = true;
        }
    }

}

const Element * Element::create(Context *rsc, RsDataType dt, RsDataKind dk,
                                bool isNorm, uint32_t vecSize) {
    // Look for an existing match.
    for (uint32_t ct=0; ct < rsc->mStateElement.mElements.size(); ct++) {
        const Element *ee = rsc->mStateElement.mElements[ct];
        if (!ee->getFieldCount() &&
            (ee->getComponent().getType() == dt) &&
            (ee->getComponent().getKind() == dk) &&
            (ee->getComponent().getIsNormalized() == isNorm) &&
            (ee->getComponent().getVectorSize() == vecSize)) {
            // Match
            ee->incUserRef();
            return ee;
        }
    }

    Element *e = new Element(rsc);
    e->mComponent.set(dt, dk, isNorm, vecSize);
    e->compute();
    rsc->mStateElement.mElements.push(e);
    return e;
}

const Element * Element::create(Context *rsc, size_t count, const Element **ein,
                            const char **nin, const size_t * lengths, const uint32_t *asin) {
    // Look for an existing match.
    for (uint32_t ct=0; ct < rsc->mStateElement.mElements.size(); ct++) {
        const Element *ee = rsc->mStateElement.mElements[ct];
        if (ee->getFieldCount() == count) {
            bool match = true;
            for (uint32_t i=0; i < count; i++) {
                if ((ee->mFields[i].e.get() != ein[i]) ||
                    (ee->mFields[i].name.length() != lengths[i]) ||
                    (ee->mFields[i].name != nin[i]) ||
                    (ee->mFields[i].arraySize != asin[i])) {
                    match = false;
                    break;
                }
            }
            if (match) {
                ee->incUserRef();
                return ee;
            }
        }
    }

    Element *e = new Element(rsc);
    e->mFields = new ElementField_t [count];
    e->mFieldCount = count;
    for (size_t ct=0; ct < count; ct++) {
        e->mFields[ct].e.set(ein[ct]);
        e->mFields[ct].name.setTo(nin[ct], lengths[ct]);
        e->mFields[ct].arraySize = asin[ct];
    }
    e->compute();

    rsc->mStateElement.mElements.push(e);
    return e;
}

String8 Element::getGLSLType(uint32_t indent) const {
    String8 s;
    for (uint32_t ct=0; ct < indent; ct++) {
        s.append(" ");
    }

    if (!mFieldCount) {
        // Basic component.
        s.append(mComponent.getGLSLType());
    } else {
        rsAssert(0);
        //s.append("struct ");
        //s.append(getCStructBody(indent));
    }

    return s;
}

void Element::incRefs(const void *ptr) const {
    if (!mFieldCount) {
        if (mComponent.isReference()) {
            ObjectBase *const*obp = static_cast<ObjectBase *const*>(ptr);
            ObjectBase *ob = obp[0];
            if (ob) ob->incSysRef();
        }
        return;
    }

    const uint8_t *p = static_cast<const uint8_t *>(ptr);
    for (uint32_t i=0; i < mFieldCount; i++) {
        if (mFields[i].e->mHasReference) {
            p = &p[mFields[i].offsetBits >> 3];
            for (uint32_t ct=0; ct < mFields[i].arraySize; ct++) {
                mFields[i].e->incRefs(p);
                p += mFields[i].e->getSizeBytes();
            }
        }
    }
}

void Element::decRefs(const void *ptr) const {
    if (!mFieldCount) {
        if (mComponent.isReference()) {
            ObjectBase *const*obp = static_cast<ObjectBase *const*>(ptr);
            ObjectBase *ob = obp[0];
            if (ob) ob->decSysRef();
        }
        return;
    }

    const uint8_t *p = static_cast<const uint8_t *>(ptr);
    for (uint32_t i=0; i < mFieldCount; i++) {
        if (mFields[i].e->mHasReference) {
            p = &p[mFields[i].offsetBits >> 3];
            for (uint32_t ct=0; ct < mFields[i].arraySize; ct++) {
                mFields[i].e->decRefs(p);
                p += mFields[i].e->getSizeBytes();
            }
        }
    }
}


ElementState::ElementState() {
    const uint32_t initialCapacity = 32;
    mBuilderElements.setCapacity(initialCapacity);
    mBuilderNameStrings.setCapacity(initialCapacity);
    mBuilderNameLengths.setCapacity(initialCapacity);
    mBuilderArrays.setCapacity(initialCapacity);
}

ElementState::~ElementState() {
    rsAssert(!mElements.size());
}

void ElementState::elementBuilderBegin() {
    mBuilderElements.clear();
    mBuilderNameStrings.clear();
    mBuilderNameLengths.clear();
    mBuilderArrays.clear();
}

void ElementState::elementBuilderAdd(const Element *e, const char *nameStr, uint32_t arraySize) {
    mBuilderElements.push(e);
    mBuilderNameStrings.push(nameStr);
    mBuilderNameLengths.push(strlen(nameStr));
    mBuilderArrays.push(arraySize);

}

const Element *ElementState::elementBuilderCreate(Context *rsc) {
    return Element::create(rsc, mBuilderElements.size(),
                                &(mBuilderElements.editArray()[0]),
                                &(mBuilderNameStrings.editArray()[0]),
                                mBuilderNameLengths.editArray(),
                                mBuilderArrays.editArray());
}


/////////////////////////////////////////
//

namespace android {
namespace renderscript {

RsElement rsi_ElementCreate(Context *rsc,
                            RsDataType dt,
                            RsDataKind dk,
                            bool norm,
                            uint32_t vecSize) {
    const Element *e = Element::create(rsc, dt, dk, norm, vecSize);
    e->incUserRef();
    return (RsElement)e;
}

RsElement rsi_ElementCreate2(Context *rsc,
                             const RsElement * ein,
                             size_t ein_length,
                             const char ** names,
                             size_t names_length,
                             const size_t * nameLengths,
                             size_t nameLengths_length,
                             const uint32_t * arraySizes,
                             size_t arraySizes_length) {
    const Element *e = Element::create(rsc, ein_length, (const Element **)ein, names, nameLengths, arraySizes);
    e->incUserRef();
    return (RsElement)e;
}

}
}

void rsaElementGetNativeData(RsContext con, RsElement elem, uint32_t *elemData, uint32_t elemDataSize) {
    rsAssert(elemDataSize == 5);
    // we will pack mType; mKind; mNormalized; mVectorSize; NumSubElements
    Element *e = static_cast<Element *>(elem);

    (*elemData++) = (uint32_t)e->getType();
    (*elemData++) = (uint32_t)e->getKind();
    (*elemData++) = e->getComponent().getIsNormalized() ? 1 : 0;
    (*elemData++) = e->getComponent().getVectorSize();
    (*elemData++) = e->getFieldCount();
}

void rsaElementGetSubElements(RsContext con, RsElement elem, uint32_t *ids, const char **names, uint32_t dataSize) {
    Element *e = static_cast<Element *>(elem);
    rsAssert(e->getFieldCount() == dataSize);

    for (uint32_t i = 0; i < dataSize; i ++) {
        e->getField(i)->incUserRef();
        ids[i] = (uint32_t)e->getField(i);
        names[i] = e->getFieldName(i);
    }
}
