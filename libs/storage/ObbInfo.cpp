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

#include <storage/ObbInfo.h>

#include <binder/Parcel.h>
#include <utils/String16.h>
#include <sys/types.h>

namespace android {

ObbInfo::ObbInfo(const String16 fileName, const String16 packageName, int32_t version,
        int32_t flags, size_t saltSize, const uint8_t* salt) : mFileName(fileName),
        mPackageName(packageName), mVersion(version), mFlags(flags), mSaltSize(saltSize),
        mSalt(salt) {}

ObbInfo::~ObbInfo() {}

status_t ObbInfo::readFromParcel(const Parcel*) {
    return INVALID_OPERATION;
}

status_t ObbInfo::writeToParcel(Parcel* p) const {
    // Parcel write code must be kept in sync with
    // frameworks/base/core/java/android/content/res/ObbInfo.java
    p->writeString16(mFileName);
    p->writeString16(mPackageName);
    p->writeInt32(mVersion);
    p->writeInt32(mFlags);
    p->writeByteArray(mSaltSize, mSalt);
    return OK;
}

}; // namespace android