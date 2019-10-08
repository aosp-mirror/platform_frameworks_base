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

#include <android/content/ComponentName.h>

namespace android {
namespace content {

ComponentName::ComponentName()
        :mPackage(),
         mClass() {
}

ComponentName::ComponentName(const ComponentName& that)
        :mPackage(that.mPackage),
         mClass(that.mClass) {
}

ComponentName::ComponentName(const string& pkg, const string& cls)
        :mPackage(pkg),
         mClass(cls) {
}

ComponentName::~ComponentName() {
}

bool ComponentName::operator<(const ComponentName& that) const {
    if (mPackage < that.mPackage) {
       return true;
    } else if (mPackage > that.mPackage) {
       return false;
    }
    return mClass < that.mClass;
}

status_t ComponentName::readFromParcel(const Parcel* in) {
    status_t err;

    // Note: This is a subtle variation from the java version, which
    // requires non-null strings, but does not require non-empty strings.
    // This code implicitly requires non-null strings, because it's impossible,
    // but reading null strings that were somehow written by the java
    // code would turn them into empty strings.

    err = in->readUtf8FromUtf16(&mPackage);
    if (err != NO_ERROR) {
        return err;
    }

    err = in->readUtf8FromUtf16(&mClass);
    if (err != NO_ERROR) {
        return err;
    }

    return NO_ERROR;
}

status_t ComponentName::writeToParcel(android::Parcel* out) const {
    status_t err;

    err = out->writeUtf8AsUtf16(mPackage);
    if (err != NO_ERROR) {
        return err;
    }

    err = out->writeUtf8AsUtf16(mClass);
    if (err != NO_ERROR) {
        return err;
    }

    return NO_ERROR;
}

}} // namespace android::content

