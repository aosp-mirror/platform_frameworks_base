/*
 * Copyright (C) 2008 The Android Open Source Project
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

#ifndef __ANDROID_TYPE_H__
#define __ANDROID_TYPE_H__

#include <rs.h>
#include "RenderScript.h"
#include "BaseObj.h"

class Type : public BaseObj {
protected:
    friend class Allocation;

    uint32_t mDimX;
    uint32_t mDimY;
    uint32_t mDimZ;
    bool mDimMipmaps;
    bool mDimFaces;
    size_t mElementCount;
    const Element *mElement;

    void calcElementCount();
    virtual void updateFromNative();

public:

    const Element* getElement() const {
        return mElement;
    }

    uint32_t getX() const {
        return mDimX;
    }

    uint32_t getY() const {
        return mDimY;
    }

    uint32_t getZ() const {
        return mDimZ;
    }

    bool hasMipmaps() const {
        return mDimMipmaps;
    }

    bool hasFaces() const {
        return mDimFaces;
    }

    size_t getCount() const {
        return mElementCount;
    }

    size_t getSizeBytes() const {
        return mElementCount * mElement->getSizeBytes();
    }


    Type(void *id, RenderScript *rs);


    class Builder {
    protected:
        RenderScript *mRS;
        uint32_t mDimX;
        uint32_t mDimY;
        uint32_t mDimZ;
        bool mDimMipmaps;
        bool mDimFaces;
        const Element *mElement;

    public:
        Builder(RenderScript *rs, const Element *e);

        void setX(uint32_t value);
        void setY(int value);
        void setMipmaps(bool value);
        void setFaces(bool value);
        const Type * create();
    };

};

#endif
