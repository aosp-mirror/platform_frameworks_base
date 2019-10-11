/*
 * Copyright (C) 2019 The Android Open Source Project
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
#include <android/util/StatsEvent.h>

#include <binder/Parcel.h>
#include <binder/Parcelable.h>
#include <binder/Status.h>
#include <vector>

using android::Parcel;
using android::Parcelable;
using android::status_t;
using std::vector;

namespace android {
namespace util {

StatsEvent::StatsEvent(){};

status_t StatsEvent::writeToParcel(Parcel* out) const {
    // Implement me if desired. We don't currently use this.
    ALOGE("Cannot do c++ StatsEvent.writeToParcel(); it is not implemented.");
    (void)out;  // To prevent compile error of unused parameter 'out'
    return UNKNOWN_ERROR;
};

status_t StatsEvent::readFromParcel(const Parcel* in) {
    status_t res = OK;
    if (in == NULL) {
        ALOGE("statsd received parcel argument was NULL.");
        return BAD_VALUE;
    }
    if ((res = in->readInt32(&mAtomTag)) != OK) {
        ALOGE("statsd could not read atom tag from parcel");
        return res;
    }
    if ((res = in->readByteVector(&mBuffer)) != OK) {
        ALOGE("statsd could not read buffer from parcel");
        return res;
    }
    return NO_ERROR;
};

} // Namespace util
} // Namespace android
