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

#ifndef ANDROID_OBBINFO_H
#define ANDROID_OBBINFO_H

#include <binder/Parcelable.h>
#include <utils/RefBase.h>
#include <utils/String16.h>
#include <sys/types.h>

namespace android {

class ObbInfo : public Parcelable, public virtual RefBase {

public:
    ObbInfo(const String16 fileName, const String16 packageName, int32_t version,
            int32_t flags, size_t saltSize, const uint8_t* salt);
    ~ObbInfo();

    status_t writeToParcel(Parcel* parcel) const override;
    status_t readFromParcel(const Parcel* parcel) override;

private:
    const String16 mFileName;
    const String16 mPackageName;
    int32_t mVersion;
    int32_t mFlags;
    size_t mSaltSize;
    const uint8_t* mSalt;
};

}; // namespace android

#endif // ANDROID_OBBINFO_H