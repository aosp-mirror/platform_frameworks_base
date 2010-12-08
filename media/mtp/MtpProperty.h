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

struct MtpPropertyValue {
    union {
        int8_t          i8;
        uint8_t         u8;
        int16_t         i16;
        uint16_t        u16;
        int32_t         i32;
        uint32_t        u32;
        int64_t         i64;
        uint64_t        u64;
        int128_t        i128;
        uint128_t       u128;
    } u;
    // string in UTF8 format
    char*               str;
};

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
        kFormDateTime = 3,
    };

    uint32_t            mGroupCode;
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
                        MtpProperty(MtpPropertyCode propCode,
                                     MtpDataType type,
                                     bool writeable = false,
                                     int defaultValue = 0);
    virtual             ~MtpProperty();

    inline MtpPropertyCode getPropertyCode() const { return mCode; }

    void                read(MtpDataPacket& packet);
    void                write(MtpDataPacket& packet);

    void                setDefaultValue(const uint16_t* string);
    void                setCurrentValue(const uint16_t* string);

    void                setFormRange(int min, int max, int step);
    void                setFormEnum(const int* values, int count);
    void                setFormDateTime();

    void                print();
    void                print(MtpPropertyValue& value, MtpString& buffer);

    inline bool         isDeviceProperty() const {
                            return (   ((mCode & 0xF000) == 0x5000)
                                    || ((mCode & 0xF800) == 0xD000));
                        }

private:
    void                readValue(MtpDataPacket& packet, MtpPropertyValue& value);
    void                writeValue(MtpDataPacket& packet, MtpPropertyValue& value);
    MtpPropertyValue*   readArrayValues(MtpDataPacket& packet, int& length);
    void                writeArrayValues(MtpDataPacket& packet,
                                            MtpPropertyValue* values, int length);
};

}; // namespace android

#endif // _MTP_PROPERTY_H
