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

#define LOG_TAG "MtpProperty"

#include "MtpDataPacket.h"
#include "MtpProperty.h"
#include "MtpStringBuffer.h"
#include "MtpUtils.h"

namespace android {

MtpProperty::MtpProperty()
    :   mCode(0),
        mType(0),
        mWriteable(false),
        mDefaultArrayLength(0),
        mDefaultArrayValues(NULL),
        mCurrentArrayLength(0),
        mCurrentArrayValues(NULL),
        mFormFlag(kFormNone),
        mEnumLength(0),
        mEnumValues(NULL)
{
    mDefaultValue.str = NULL;
    mCurrentValue.str = NULL;
    mMinimumValue.str = NULL;
    mMaximumValue.str = NULL;
}

MtpProperty::MtpProperty(MtpPropertyCode propCode,
                         MtpDataType type,
                         bool writeable,
                         int defaultValue)
    :   mCode(propCode),
        mType(type),
        mWriteable(writeable),
        mDefaultArrayLength(0),
        mDefaultArrayValues(NULL),
        mCurrentArrayLength(0),
        mCurrentArrayValues(NULL),
        mFormFlag(kFormNone),
        mEnumLength(0),
        mEnumValues(NULL)
{
    memset(&mDefaultValue, 0, sizeof(mDefaultValue));
    memset(&mCurrentValue, 0, sizeof(mCurrentValue));
    memset(&mMinimumValue, 0, sizeof(mMinimumValue));
    memset(&mMaximumValue, 0, sizeof(mMaximumValue));

    if (defaultValue) {
        switch (type) {
            case MTP_TYPE_INT8:
                mDefaultValue.i8 = defaultValue;
                break;
            case MTP_TYPE_UINT8:
                mDefaultValue.u8 = defaultValue;
                break;
            case MTP_TYPE_INT16:
                mDefaultValue.i16 = defaultValue;
                break;
            case MTP_TYPE_UINT16:
                mDefaultValue.u16 = defaultValue;
                break;
            case MTP_TYPE_INT32:
                mDefaultValue.i32 = defaultValue;
                break;
            case MTP_TYPE_UINT32:
                mDefaultValue.u32 = defaultValue;
                break;
            case MTP_TYPE_INT64:
                mDefaultValue.i64 = defaultValue;
                break;
            case MTP_TYPE_UINT64:
                mDefaultValue.u64 = defaultValue;
                break;
            default:
                LOGE("unknown type %04X in MtpProperty::MtpProperty", type);
        }
    }
}

MtpProperty::~MtpProperty() {
    if (mType == MTP_TYPE_STR) {
        // free all strings
        free(mDefaultValue.str);
        free(mCurrentValue.str);
        free(mMinimumValue.str);
        free(mMaximumValue.str);
        if (mDefaultArrayValues) {
            for (int i = 0; i < mDefaultArrayLength; i++)
                free(mDefaultArrayValues[i].str);
        }
        if (mCurrentArrayValues) {
            for (int i = 0; i < mCurrentArrayLength; i++)
                free(mCurrentArrayValues[i].str);
        }
        if (mEnumValues) {
            for (int i = 0; i < mEnumLength; i++)
                free(mEnumValues[i].str);
        }
    }
    delete[] mDefaultArrayValues;
    delete[] mCurrentArrayValues;
    delete[] mEnumValues;
}

void MtpProperty::read(MtpDataPacket& packet, bool deviceProp) {

    mCode = packet.getUInt16();
    mType = packet.getUInt16();
    mWriteable = (packet.getUInt8() == 1);
    switch (mType) {
        case MTP_TYPE_AINT8:
        case MTP_TYPE_AUINT8:
        case MTP_TYPE_AINT16:
        case MTP_TYPE_AUINT16:
        case MTP_TYPE_AINT32:
        case MTP_TYPE_AUINT32:
        case MTP_TYPE_AINT64:
        case MTP_TYPE_AUINT64:
        case MTP_TYPE_AINT128:
        case MTP_TYPE_AUINT128:
            mDefaultArrayValues = readArrayValues(packet, mDefaultArrayLength);
            mCurrentArrayValues = readArrayValues(packet, mCurrentArrayLength);
            break;
        default:
            readValue(packet, mDefaultValue);
            if (deviceProp)
                readValue(packet, mCurrentValue);
    }
    mFormFlag = packet.getUInt8();

    if (mFormFlag == kFormRange) {
            readValue(packet, mMinimumValue);
            readValue(packet, mMaximumValue);
            readValue(packet, mStepSize);
    } else if (mFormFlag == kFormEnum) {
        mEnumLength = packet.getUInt16();
        mEnumValues = new MtpPropertyValue[mEnumLength];
        for (int i = 0; i < mEnumLength; i++)
            readValue(packet, mEnumValues[i]);
    }
}

// FIXME - only works for object properties
void MtpProperty::write(MtpDataPacket& packet) {
    packet.putUInt16(mCode);
    packet.putUInt16(mType);
    packet.putUInt8(mWriteable ? 1 : 0);

    switch (mType) {
        case MTP_TYPE_AINT8:
        case MTP_TYPE_AUINT8:
        case MTP_TYPE_AINT16:
        case MTP_TYPE_AUINT16:
        case MTP_TYPE_AINT32:
        case MTP_TYPE_AUINT32:
        case MTP_TYPE_AINT64:
        case MTP_TYPE_AUINT64:
        case MTP_TYPE_AINT128:
        case MTP_TYPE_AUINT128:
            writeArrayValues(packet, mDefaultArrayValues, mDefaultArrayLength);
            break;
        default:
            writeValue(packet, mDefaultValue);
    }
    packet.putUInt8(mFormFlag);
    if (mFormFlag == kFormRange) {
            writeValue(packet, mMinimumValue);
            writeValue(packet, mMaximumValue);
            writeValue(packet, mStepSize);
    } else if (mFormFlag == kFormEnum) {
        packet.putUInt16(mEnumLength);
        for (int i = 0; i < mEnumLength; i++)
            writeValue(packet, mEnumValues[i]);
    }
}

void MtpProperty::print() {
    LOGD("MtpProperty %04X\n", mCode);
    LOGD("    type %04X\n", mType);
    LOGD("    writeable %s\n", (mWriteable ? "true" : "false"));
}

void MtpProperty::readValue(MtpDataPacket& packet, MtpPropertyValue& value) {
    MtpStringBuffer stringBuffer;

    switch (mType) {
        case MTP_TYPE_INT8:
        case MTP_TYPE_AINT8:
            value.i8 = packet.getInt8();
            break;
        case MTP_TYPE_UINT8:
        case MTP_TYPE_AUINT8:
            value.u8 = packet.getUInt8();
            break;
        case MTP_TYPE_INT16:
        case MTP_TYPE_AINT16:
            value.i16 = packet.getInt16();
            break;
        case MTP_TYPE_UINT16:
        case MTP_TYPE_AUINT16:
            value.u16 = packet.getUInt16();
            break;
        case MTP_TYPE_INT32:
        case MTP_TYPE_AINT32:
            value.i32 = packet.getInt32();
            break;
        case MTP_TYPE_UINT32:
        case MTP_TYPE_AUINT32:
            value.u32 = packet.getUInt32();
            break;
        case MTP_TYPE_INT64:
        case MTP_TYPE_AINT64:
            value.i64 = packet.getInt64();
            break;
        case MTP_TYPE_UINT64:
        case MTP_TYPE_AUINT64:
            value.u64 = packet.getUInt64();
            break;
        case MTP_TYPE_INT128:
        case MTP_TYPE_AINT128:
            packet.getInt128(value.i128);
            break;
        case MTP_TYPE_UINT128:
        case MTP_TYPE_AUINT128:
            packet.getUInt128(value.u128);
            break;
        case MTP_TYPE_STR:
            packet.getString(stringBuffer);
            value.str = strdup(stringBuffer);
            break;
        default:
            LOGE("unknown type %04X in MtpProperty::readValue", mType);
    }
}

void MtpProperty::writeValue(MtpDataPacket& packet, MtpPropertyValue& value) {
    MtpStringBuffer stringBuffer;

    switch (mType) {
        case MTP_TYPE_INT8:
        case MTP_TYPE_AINT8:
            packet.putInt8(value.i8);
            break;
        case MTP_TYPE_UINT8:
        case MTP_TYPE_AUINT8:
            packet.putUInt8(value.u8);
            break;
        case MTP_TYPE_INT16:
        case MTP_TYPE_AINT16:
            packet.putInt16(value.i16);
            break;
        case MTP_TYPE_UINT16:
        case MTP_TYPE_AUINT16:
            packet.putUInt16(value.u16);
            break;
        case MTP_TYPE_INT32:
        case MTP_TYPE_AINT32:
            packet.putInt32(value.i32);
            break;
        case MTP_TYPE_UINT32:
        case MTP_TYPE_AUINT32:
            packet.putUInt32(value.u32);
            break;
        case MTP_TYPE_INT64:
        case MTP_TYPE_AINT64:
            packet.putInt64(value.i64);
            break;
        case MTP_TYPE_UINT64:
        case MTP_TYPE_AUINT64:
            packet.putUInt64(value.u64);
            break;
        case MTP_TYPE_INT128:
        case MTP_TYPE_AINT128:
            packet.putInt128(value.i128);
            break;
        case MTP_TYPE_UINT128:
        case MTP_TYPE_AUINT128:
            packet.putUInt128(value.u128);
            break;
        case MTP_TYPE_STR:
            if (value.str)
                packet.putString(value.str);
            else
                packet.putEmptyString();
            break;
        default:
            LOGE("unknown type %04X in MtpProperty::writeValue", mType);
    }
}

MtpPropertyValue* MtpProperty::readArrayValues(MtpDataPacket& packet, int& length) {
    length = packet.getUInt32();
    if (length == 0)
        return NULL;
    MtpPropertyValue* result = new MtpPropertyValue[length];
    for (int i = 0; i < length; i++)
        readValue(packet, result[i]);
    return result;
}

void MtpProperty::writeArrayValues(MtpDataPacket& packet, MtpPropertyValue* values, int length) {
    packet.putUInt32(length);
    for (int i = 0; i < length; i++)
        writeValue(packet, values[i]);
}

}  // namespace android
