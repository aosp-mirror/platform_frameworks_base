/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef _MTP_PROPERTY_H
#define _MTP_PROPERTY_H

#include "MtpTypes.h"

namespace android {

class MtpDataPacket;

class MtpProperty {
public:
    MtpPropertyCode     mCode;
    MtpDataType         mType;
    bool                mWriteable;
    MtpPropertyValue    mDefaultValue;
    MtpPropertyValue    mCurrentValue;

    // for array types
    int                 mDefaultArrayLength;
    MtpPropertyValue*   mDefaultArrayValues;
    int                 mCurrentArrayLength;
    MtpPropertyValue*   mCurrentArrayValues;

    enum {
        kFormNone = 0,
        kFormRange = 1,
        kFormEnum = 2,
    };
    uint8_t             mFormFlag;

    // for range form
    MtpPropertyValue    mMinimumValue;
    MtpPropertyValue    mMaximumValue;
    MtpPropertyValue    mStepSize;

    // for enum form
    int                 mEnumLength;
    MtpPropertyValue*   mEnumValues;

public:
                        MtpProperty();
    virtual             ~MtpProperty();

    void                read(MtpDataPacket& packet);

    void                print();

private:
    void                readValue(MtpDataPacket& packet, MtpPropertyValue& value);
    MtpPropertyValue*   readArrayValues(MtpDataPacket& packet, int& length);
};

}; // namespace android

#endif // _MTP_PROPERTY_H
