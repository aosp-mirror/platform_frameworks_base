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

#ifndef ANDROID_COMPONENT_H
#define ANDROID_COMPONENT_H

#include "rsUtils.h"

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {


// An element is a group of Components that occupies one cell in a structure.
class Component {
public:
    Component();
    ~Component();

    void set(RsDataType dt, RsDataKind dk, bool norm, uint32_t vecSize=1);

    void dumpLOGV(const char *prefix) const;

    RsDataType getType() const {return mType;}
    RsDataKind getKind() const {return mKind;}
    bool getIsNormalized() const {return mNormalized;}
    uint32_t getVectorSize() const {return mVectorSize;}
    bool getIsFloat() const {return mIsFloat;}
    bool getIsSigned() const {return mIsSigned;}
    uint32_t getBits() const {return mBits;}

    // Helpers for reading / writing this class out
    void serialize(OStream *stream) const;
    void loadFromStream(IStream *stream);

    bool isReference() const;

protected:
    RsDataType mType;
    RsDataKind mKind;
    bool mNormalized;
    uint32_t mVectorSize;

    // derived
    uint32_t mBits;
    uint32_t mTypeBits;
    bool mIsFloat;
    bool mIsSigned;
    bool mIsPixel;
};

}
}

#endif

