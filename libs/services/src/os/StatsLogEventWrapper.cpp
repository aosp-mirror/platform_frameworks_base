/*
 * Copyright (C) 2017 The Android Open Source Project
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
#include <android/os/StatsLogEventWrapper.h>

#include <binder/Parcel.h>
#include <binder/Parcelable.h>
#include <binder/Status.h>
#include <utils/RefBase.h>
#include <vector>

using android::Parcel;
using android::Parcelable;
using android::status_t;
using std::vector;

namespace android {
namespace os {

StatsLogEventWrapper::StatsLogEventWrapper(){};

status_t StatsLogEventWrapper::writeToParcel(Parcel* out) const {
    out->writeByteVector(bytes);
    return ::android::NO_ERROR;
};

status_t StatsLogEventWrapper::readFromParcel(const Parcel* in) {
    in->readByteVector(&bytes);
    return ::android::NO_ERROR;
};

} // Namespace os
} // Namespace android
