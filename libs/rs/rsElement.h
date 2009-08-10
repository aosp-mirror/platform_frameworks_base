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

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {


// An element is a group of Components that occupies one cell in a structure.
class Element : public ObjectBase
{
public:
    Element(uint32_t count);
    ~Element();


    void setComponent(uint32_t idx, Component *c);

    uint32_t getGLType() const;
    uint32_t getGLFormat() const;


    size_t getSizeBits() const;
    size_t getSizeBytes() const {
        return (getSizeBits() + 7) >> 3;
    }

    size_t getComponentOffsetBits(uint32_t componentNumber) const;
    size_t getComponentOffsetBytes(uint32_t componentNumber) const {
        return (getComponentOffsetBits(componentNumber) + 7) >> 3;
    }

    uint32_t getComponentCount() const {return mComponentCount;}
    Component * getComponent(uint32_t idx) const {return mComponents[idx].get();}

protected:
    // deallocate any components that are part of this element.
    void clear();

    size_t mComponentCount;
    ObjectBaseRef<Component> * mComponents;
    //uint32_t *mOffsetTable;

    Element();
};


class ElementState {
public:
    ElementState();
    ~ElementState();

    Vector<Component *> mComponentBuildList;



    struct Predefined {
        Predefined() {
            mElement = NULL;
        }
        Predefined(RsElementPredefined en, Element *e) {
            mEnum = en;
            mElement = e;
        }
        RsElementPredefined mEnum;
        Element * mElement;
    };
    Vector<Predefined> mPredefinedList;

    void initPredefined();

};


}
}
#endif //ANDROID_STRUCTURED_ELEMENT_H
