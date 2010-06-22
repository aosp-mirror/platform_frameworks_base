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

#ifndef ANDROID_STRUCTURED_ELEMENT_H
#define ANDROID_STRUCTURED_ELEMENT_H

#include "rsComponent.h"
#include "rsUtils.h"
#include "rsObjectBase.h"

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {


// An element is a group of Components that occupies one cell in a structure.
class Element : public ObjectBase
{
public:
    ~Element();

    uint32_t getGLType() const;
    uint32_t getGLFormat() const;

    size_t getSizeBits() const;
    size_t getSizeBytes() const {
        return (getSizeBits() + 7) >> 3;
    }

    size_t getFieldOffsetBits(uint32_t componentNumber) const;
    size_t getFieldOffsetBytes(uint32_t componentNumber) const {
        return (getFieldOffsetBits(componentNumber) + 7) >> 3;
    }

    uint32_t getFieldCount() const {return mFieldCount;}
    const Element * getField(uint32_t idx) const {return mFields[idx].e.get();}
    const char * getFieldName(uint32_t idx) const {return mFields[idx].name.string();}

    const Component & getComponent() const {return mComponent;}
    RsDataType getType() const {return mComponent.getType();}
    RsDataKind getKind() const {return mComponent.getKind();}
    uint32_t getBits() const {return mBits;}

    String8 getGLSLType(uint32_t indent=0) const;

    void dumpLOGV(const char *prefix) const;
    virtual void serialize(OStream *stream) const;
    virtual RsA3DClassID getClassId() const { return RS_A3D_CLASS_ID_ELEMENT; }
    static Element *createFromStream(Context *rsc, IStream *stream);

    static const Element * create(Context *rsc, RsDataType dt, RsDataKind dk,
                            bool isNorm, uint32_t vecSize);
    static const Element * create(Context *rsc, size_t count, const Element **,
                            const char **, const size_t * lengths);

protected:
    // deallocate any components that are part of this element.
    void clear();

    typedef struct {
        String8 name;
        ObjectBaseRef<const Element> e;
    } ElementField_t;
    ElementField_t *mFields;
    size_t mFieldCount;


    Element(Context *);

    Component mComponent;
    uint32_t mBits;
};


class ElementState {
public:
    ElementState();
    ~ElementState();

    // Cache of all existing elements.
    Vector<Element *> mElements;
};


}
}
#endif //ANDROID_STRUCTURED_ELEMENT_H
