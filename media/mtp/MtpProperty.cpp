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
#include "utils/Log.h"

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

void MtpProperty::read(MtpDataPacket& packet) {
    MtpStringBuffer string;

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

void MtpProperty::print() {
    LOGD("MtpProperty %04X\n", mCode);
    LOGD("    type %04X\n", mType);
    LOGD("    writeable %s\n", (mWriteable ? "true" : "false"));
}

void MtpProperty::readValue(MtpDataPacket& packet, MtpPropertyValue& value) {
    switch (mType) {
        case MTP_TYPE_INT8:
            value.i8 = packet.getInt8();
            break;
        case MTP_TYPE_UINT8:
            value.u8 = packet.getUInt8();
            break;
        case MTP_TYPE_INT16:
            value.i16 = packet.getInt16();
            break;
        case MTP_TYPE_UINT16:
            value.u16 = packet.getUInt16();
            break;
        case MTP_TYPE_INT32:
            value.i32 = packet.getInt32();
            break;
        case MTP_TYPE_UINT32:
            value.u32 = packet.getUInt32();
            break;
        case MTP_TYPE_INT64:
            value.i64 = packet.getInt64();
            break;
        case MTP_TYPE_UINT64:
            value.u64 = packet.getUInt64();
            break;
        case MTP_TYPE_INT128:
            packet.getInt128(value.i128);
            break;
        case MTP_TYPE_UINT128:
            packet.getUInt128(value.u128);
            break;
        default:
            fprintf(stderr, "unknown type %d in MtpProperty::readValue\n", mType);
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

}  // namespace android
