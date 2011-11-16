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
    mBitsUnpadded = 0;
    mFields = NULL;
    mFieldCount = 0;
    mHasReference = false;
}

Element::~Element() {
    clear();
}

void Element::preDestroy() const {
    for (uint32_t ct = 0; ct < mRSC->mStateElement.mElements.size(); ct++) {
        if (mRSC->mStateElement.mElements[ct] == this) {
            mRSC->mStateElement.mElements.removeAt(ct);
            break;
        }
    }
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

size_t Element::getSizeBitsUnpadded() const {
    if (!mFieldCount) {
        return mBitsUnpadded;
    }

    size_t total = 0;
    for (size_t ct=0; ct < mFieldCount; ct++) {
        total += mFields[ct].e->mBitsUnpadded * mFields[ct].arraySize;
    }
    return total;
}

void Element::dumpLOGV(const char *prefix) const {
    ObjectBase::dumpLOGV(prefix);
    ALOGV("%s Element: fieldCount: %zu,  size bytes: %zu", prefix, mFieldCount, getSizeBytes());
    mComponent.dumpLOGV(prefix);
    for (uint32_t ct = 0; ct < mFieldCount; ct++) {
        ALOGV("%s Element field index: %u ------------------", prefix, ct);
        ALOGV("%s name: %s, offsetBits: %u, arraySize: %u",
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

    Component component;
    component.loadFromStream(stream);

    uint32_t fieldCount = stream->loadU32();
    if (!fieldCount) {
        return (Element *)Element::create(rsc,
                                          component.getType(),
                                          component.getKind(),
                                          component.getIsNormalized(),
                                          component.getVectorSize());;
    }

    const Element **subElems = new const Element *[fieldCount];
    const char **subElemNames = new const char *[fieldCount];
    size_t *subElemNamesLengths = new size_t[fieldCount];
    uint32_t *arraySizes = new uint32_t[fieldCount];

    String8 elemName;
    for (uint32_t ct = 0; ct < fieldCount; ct ++) {
        stream->loadString(&elemName);
        subElemNamesLengths[ct] = elemName.length();
        char *tmpName = new char[subElemNamesLengths[ct]];
        memcpy(tmpName, elemName.string(), subElemNamesLengths[ct]);
        subElemNames[ct] = tmpName;
        arraySizes[ct] = stream->loadU32();
        subElems[ct] = Element::createFromStream(rsc, stream);
    }

    const Element *elem = Element::create(rsc, fieldCount, subElems, subElemNames,
                                          subElemNamesLengths, arraySizes);
    for (uint32_t ct = 0; ct < fieldCount; ct ++) {
        delete [] subElemNames[ct];
        subElems[ct]->decUserRef();
    }
    delete[] subElems;
    delete[] subElemNames;
    delete[] subElemNamesLengths;
    delete[] arraySizes;

    return (Element *)elem;
}

void Element::compute() {
    if (mFieldCount == 0) {
        mBits = mComponent.getBits();
        mBitsUnpadded = mComponent.getBitsUnpadded();
        mHasReference = mComponent.isReference();
        return;
    }

    size_t bits = 0;
    size_t bitsUnpadded = 0;
    for (size_t ct=0; ct < mFieldCount; ct++) {
        mFields[ct].offsetBits = bits;
        mFields[ct].offsetBitsUnpadded = bitsUnpadded;
        bits += mFields[ct].e->getSizeBits() * mFields[ct].arraySize;
        bitsUnpadded += mFields[ct].e->getSizeBitsUnpadded() * mFields[ct].arraySize;

        if (mFields[ct].e->mHasReference) {
            mHasReference = true;
        }
    }

}

ObjectBaseRef<const Element> Element::createRef(Context *rsc, RsDataType dt, RsDataKind dk,
                                bool isNorm, uint32_t vecSize) {
    ObjectBaseRef<const Element> returnRef;
    // Look for an existing match.
    ObjectBase::asyncLock();
    for (uint32_t ct=0; ct < rsc->mStateElement.mElements.size(); ct++) {
        const Element *ee = rsc->mStateElement.mElements[ct];
        if (!ee->getFieldCount() &&
            (ee->getComponent().getType() == dt) &&
            (ee->getComponent().getKind() == dk) &&
            (ee->getComponent().getIsNormalized() == isNorm) &&
            (ee->getComponent().getVectorSize() == vecSize)) {
            // Match
            returnRef.set(ee);
            ObjectBase::asyncUnlock();
            return ee;
        }
    }
    ObjectBase::asyncUnlock();

    Element *e = new Element(rsc);
    returnRef.set(e);
    e->mComponent.set(dt, dk, isNorm, vecSize);
    e->compute();

    ObjectBase::asyncLock();
    rsc->mStateElement.mElements.push(e);
    ObjectBase::asyncUnlock();

    return returnRef;
}

ObjectBaseRef<const Element> Element::createRef(Context *rsc, size_t count, const Element **ein,
                            const char **nin, const size_t * lengths, const uint32_t *asin) {

    ObjectBaseRef<const Element> returnRef;
    // Look for an existing match.
    ObjectBase::asyncLock();
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
                returnRef.set(ee);
                ObjectBase::asyncUnlock();
                return returnRef;
            }
        }
    }
    ObjectBase::asyncUnlock();

    Element *e = new Element(rsc);
    returnRef.set(e);
    e->mFields = new ElementField_t [count];
    e->mFieldCount = count;
    for (size_t ct=0; ct < count; ct++) {
        e->mFields[ct].e.set(ein[ct]);
        e->mFields[ct].name.setTo(nin[ct], lengths[ct]);
        e->mFields[ct].arraySize = asin[ct];
    }
    e->compute();

    ObjectBase::asyncLock();
    rsc->mStateElement.mElements.push(e);
    ObjectBase::asyncUnlock();

    return returnRef;
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
            const uint8_t *p2 = &p[mFields[i].offsetBits >> 3];
            for (uint32_t ct=0; ct < mFields[i].arraySize; ct++) {
                mFields[i].e->incRefs(p2);
                p2 += mFields[i].e->getSizeBytes();
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
            const uint8_t *p2 = &p[mFields[i].offsetBits >> 3];
            for (uint32_t ct=0; ct < mFields[i].arraySize; ct++) {
                mFields[i].e->decRefs(p2);
                p2 += mFields[i].e->getSizeBytes();
            }
        }
    }
}

Element::Builder::Builder() {
    const uint32_t initialCapacity = 32;
    mBuilderElementRefs.setCapacity(initialCapacity);
    mBuilderElements.setCapacity(initialCapacity);
    mBuilderNameStrings.setCapacity(initialCapacity);
    mBuilderNameLengths.setCapacity(initialCapacity);
    mBuilderArrays.setCapacity(initialCapacity);
}

void Element::Builder::add(const Element *e, const char *nameStr, uint32_t arraySize) {
    mBuilderElementRefs.push(ObjectBaseRef<const Element>(e));
    mBuilderElements.push(e);
    mBuilderNameStrings.push(nameStr);
    mBuilderNameLengths.push(strlen(nameStr));
    mBuilderArrays.push(arraySize);

}

ObjectBaseRef<const Element> Element::Builder::create(Context *rsc) {
    return Element::createRef(rsc, mBuilderElements.size(),
                              &(mBuilderElements.editArray()[0]),
                              &(mBuilderNameStrings.editArray()[0]),
                              mBuilderNameLengths.editArray(),
                              mBuilderArrays.editArray());
}


ElementState::ElementState() {
}

ElementState::~ElementState() {
    rsAssert(!mElements.size());
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
    return (RsElement)Element::create(rsc, dt, dk, norm, vecSize);
}


RsElement rsi_ElementCreate2(Context *rsc,
                             const RsElement * ein,
                             size_t ein_length,

                             const char ** names,
                             size_t nameLengths_length,
                             const size_t * nameLengths,

                             const uint32_t * arraySizes,
                             size_t arraySizes_length) {
    return (RsElement)Element::create(rsc, ein_length, (const Element **)ein,
                                      names, nameLengths, arraySizes);
}

}
}

void rsaElementGetNativeData(RsContext con, RsElement elem,
                             uint32_t *elemData, uint32_t elemDataSize) {
    rsAssert(elemDataSize == 5);
    // we will pack mType; mKind; mNormalized; mVectorSize; NumSubElements
    Element *e = static_cast<Element *>(elem);

    (*elemData++) = (uint32_t)e->getType();
    (*elemData++) = (uint32_t)e->getKind();
    (*elemData++) = e->getComponent().getIsNormalized() ? 1 : 0;
    (*elemData++) = e->getComponent().getVectorSize();
    (*elemData++) = e->getFieldCount();
}

void rsaElementGetSubElements(RsContext con, RsElement elem, uint32_t *ids,
                              const char **names, uint32_t *arraySizes, uint32_t dataSize) {
    Element *e = static_cast<Element *>(elem);
    rsAssert(e->getFieldCount() == dataSize);

    for (uint32_t i = 0; i < dataSize; i ++) {
        e->getField(i)->incUserRef();
        ids[i] = (uint32_t)e->getField(i);
        names[i] = e->getFieldName(i);
        arraySizes[i] = e->getFieldArraySize(i);
    }
}
