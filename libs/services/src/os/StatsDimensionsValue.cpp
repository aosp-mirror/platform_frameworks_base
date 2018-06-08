/*
 * Copyright (C) 2018 The Android Open Source Project
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

#define LOG_TAG "StatsDimensionsValue"

#include "android/os/StatsDimensionsValue.h"

#include <cutils/log.h>

using android::Parcel;
using android::Parcelable;
using android::status_t;
using std::vector;

namespace android {
namespace os {

StatsDimensionsValue::StatsDimensionsValue() {};

StatsDimensionsValue::StatsDimensionsValue(int32_t field, String16 value) :
    mField(field),
    mValueType(kStrValueType),
    mStrValue(value) {
}
StatsDimensionsValue::StatsDimensionsValue(int32_t field, int32_t value) :
    mField(field),
    mValueType(kIntValueType),
    mIntValue(value) {
}
StatsDimensionsValue::StatsDimensionsValue(int32_t field, int64_t value) :
    mField(field),
    mValueType(kLongValueType),
    mLongValue(value) {
}
StatsDimensionsValue::StatsDimensionsValue(int32_t field, bool value) :
    mField(field),
    mValueType(kBoolValueType),
    mBoolValue(value) {
}
StatsDimensionsValue::StatsDimensionsValue(int32_t field, float value) :
    mField(field),
    mValueType(kFloatValueType),
    mFloatValue(value) {
}
StatsDimensionsValue::StatsDimensionsValue(int32_t field, vector<StatsDimensionsValue> value) :
    mField(field),
    mValueType(kTupleValueType),
    mTupleValue(value) {
}

StatsDimensionsValue::~StatsDimensionsValue() {}

status_t
StatsDimensionsValue::writeToParcel(Parcel* out) const {
    status_t err ;

    err = out->writeInt32(mField);
    if (err != NO_ERROR) {
        return err;
    }
    err = out->writeInt32(mValueType);
    if (err != NO_ERROR) {
        return err;
    }
    switch (mValueType) {
        case kStrValueType:
            err = out->writeString16(mStrValue);
            break;
        case kIntValueType:
            err = out->writeInt32(mIntValue);
            break;
        case kLongValueType:
            err = out->writeInt64(mLongValue);
            break;
        case kBoolValueType:
            err = out->writeBool(mBoolValue);
            break;
        case kFloatValueType:
            err = out->writeFloat(mFloatValue);
            break;
        case kTupleValueType:
            {
                int sz = mTupleValue.size();
                err = out->writeInt32(sz);
                if (err != NO_ERROR) {
                    return err;
                }
                for (int i = 0; i < sz; ++i) {
                    err = mTupleValue[i].writeToParcel(out);
                    if (err != NO_ERROR) {
                        return err;
                    }
                }
            }
            break;
        default:
            err = UNKNOWN_ERROR;
            break;
    }
    return err;
}

status_t
StatsDimensionsValue::readFromParcel(const Parcel* in)
{
    // Implement me if desired. We don't currently use this.
    ALOGE("Cannot do c++ StatsDimensionsValue.readFromParcel(); it is not implemented.");
    (void)in; // To prevent compile error of unused parameter 'in'
    return UNKNOWN_ERROR;
}

}  // namespace os
}  // namespace android
