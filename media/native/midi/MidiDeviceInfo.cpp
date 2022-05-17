/*
 * Copyright (C) 2016 The Android Open Source Project
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

#define LOG_TAG "MidiDeviceInfo"

#include <MidiDeviceInfo.h>

#include <binder/Parcel.h>
#include <log/log.h>
#include <utils/Errors.h>
#include <utils/String16.h>

namespace android {
namespace media {
namespace midi {

// The constant values need to be kept in sync with MidiDeviceInfo.java.
// static
const char* const MidiDeviceInfo::PROPERTY_NAME = "name";
const char* const MidiDeviceInfo::PROPERTY_MANUFACTURER = "manufacturer";
const char* const MidiDeviceInfo::PROPERTY_PRODUCT = "product";
const char* const MidiDeviceInfo::PROPERTY_VERSION = "version";
const char* const MidiDeviceInfo::PROPERTY_SERIAL_NUMBER = "serial_number";
const char* const MidiDeviceInfo::PROPERTY_ALSA_CARD = "alsa_card";
const char* const MidiDeviceInfo::PROPERTY_ALSA_DEVICE = "alsa_device";

String16 MidiDeviceInfo::getProperty(const char* propertyName) {
    String16 value;
    if (mProperties.getString(String16(propertyName), &value)) {
        return value;
    } else {
        return String16();
    }
}

#define RETURN_IF_FAILED(calledOnce)                                     \
    {                                                                    \
        status_t returnStatus = calledOnce;                              \
        if (returnStatus) {                                              \
            ALOGE("Failed at %s:%d (%s)", __FILE__, __LINE__, __func__); \
            return returnStatus;                                         \
         }                                                               \
    }

status_t MidiDeviceInfo::writeToParcel(Parcel* parcel) const {
    // Needs to be kept in sync with code in MidiDeviceInfo.java
    RETURN_IF_FAILED(parcel->writeInt32(mType));
    RETURN_IF_FAILED(parcel->writeInt32(mId));
    RETURN_IF_FAILED(parcel->writeInt32((int32_t)mInputPortNames.size()));
    RETURN_IF_FAILED(parcel->writeInt32((int32_t)mOutputPortNames.size()));
    RETURN_IF_FAILED(writeStringVector(parcel, mInputPortNames));
    RETURN_IF_FAILED(writeStringVector(parcel, mOutputPortNames));
    RETURN_IF_FAILED(parcel->writeInt32(mIsPrivate ? 1 : 0));
    RETURN_IF_FAILED(parcel->writeInt32(mDefaultProtocol));
    RETURN_IF_FAILED(mProperties.writeToParcel(parcel));
    // This corresponds to "extra" properties written by Java code
    RETURN_IF_FAILED(mProperties.writeToParcel(parcel));
    return OK;
}

status_t MidiDeviceInfo::readFromParcel(const Parcel* parcel) {
    // Needs to be kept in sync with code in MidiDeviceInfo.java
    RETURN_IF_FAILED(parcel->readInt32(&mType));
    RETURN_IF_FAILED(parcel->readInt32(&mId));
    int32_t inputPortCount;
    RETURN_IF_FAILED(parcel->readInt32(&inputPortCount));
    int32_t outputPortCount;
    RETURN_IF_FAILED(parcel->readInt32(&outputPortCount));
    RETURN_IF_FAILED(readStringVector(parcel, &mInputPortNames, inputPortCount));
    RETURN_IF_FAILED(readStringVector(parcel, &mOutputPortNames, outputPortCount));
    int32_t isPrivate;
    RETURN_IF_FAILED(parcel->readInt32(&isPrivate));
    mIsPrivate = isPrivate == 1;
    RETURN_IF_FAILED(parcel->readInt32(&mDefaultProtocol));
    RETURN_IF_FAILED(mProperties.readFromParcel(parcel));
    // Ignore "extra" properties as they may contain Java Parcelables
    return OK;
}

status_t MidiDeviceInfo::readStringVector(
        const Parcel* parcel, Vector<String16> *vectorPtr, size_t defaultLength) {
    std::optional<std::vector<std::optional<String16>>> v;
    status_t result = parcel->readString16Vector(&v);
    if (result != OK) return result;
    vectorPtr->clear();
    if (v) {
        for (const auto& iter : *v) {
            if (iter) {
                vectorPtr->push_back(*iter);
            } else {
                vectorPtr->push_back(String16());
            }
        }
    } else {
        vectorPtr->resize(defaultLength);
    }
    return OK;
}

status_t MidiDeviceInfo::writeStringVector(Parcel* parcel, const Vector<String16>& vector) const {
    std::vector<String16> v;
    for (size_t i = 0; i < vector.size(); ++i) {
        v.push_back(vector[i]);
    }
    return parcel->writeString16Vector(v);
}

// Vector does not define operator==
static inline bool areVectorsEqual(const Vector<String16>& lhs, const Vector<String16>& rhs) {
    if (lhs.size() != rhs.size()) return false;
    for (size_t i = 0; i < lhs.size(); ++i) {
        if (lhs[i] != rhs[i]) return false;
    }
    return true;
}

bool operator==(const MidiDeviceInfo& lhs, const MidiDeviceInfo& rhs) {
    return (lhs.mType == rhs.mType && lhs.mId == rhs.mId &&
            areVectorsEqual(lhs.mInputPortNames, rhs.mInputPortNames) &&
            areVectorsEqual(lhs.mOutputPortNames, rhs.mOutputPortNames) &&
            lhs.mProperties == rhs.mProperties &&
            lhs.mIsPrivate == rhs.mIsPrivate &&
            lhs.mDefaultProtocol == rhs.mDefaultProtocol);
}

}  // namespace midi
}  // namespace media
}  // namespace android
