/*
 * Copyright (C) 2022 The Android Open Source Project
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

#ifndef _ANDROID_SERVER_GNSS_MEASUREMENTCORRECTIONSCALLBACK_H
#define _ANDROID_SERVER_GNSS_MEASUREMENTCORRECTIONSCALLBACK_H

#pragma once

#ifndef LOG_TAG
#error LOG_TAG must be defined before including this file.
#endif

#include <android/hardware/gnss/measurement_corrections/1.0/IMeasurementCorrections.h>
#include <android/hardware/gnss/measurement_corrections/1.1/IMeasurementCorrections.h>
#include <android/hardware/gnss/measurement_corrections/BnMeasurementCorrectionsCallback.h>
#include <log/log.h>

#include "Utils.h"
#include "jni.h"

namespace android::gnss {

namespace {
extern jmethodID method_setSubHalMeasurementCorrectionsCapabilities;
}

void MeasurementCorrectionsCallback_class_init_once(JNIEnv* env, jclass clazz);

/*
 * MeasurementCorrectionsCallbackAidl class implements the callback methods required by the
 * android::hardware::gnss::measurement_corrections::IMeasurementCorrectionsCallback interface.
 */
class MeasurementCorrectionsCallbackAidl
      : public hardware::gnss::measurement_corrections::BnMeasurementCorrectionsCallback {
public:
    MeasurementCorrectionsCallbackAidl() {}
    binder::Status setCapabilitiesCb(const int capabilities) override;
};

/*
 * MeasurementCorrectionsCallbackHidl implements callback methods of
 * IMeasurementCorrectionsCallback.hal interface.
 */
class MeasurementCorrectionsCallbackHidl : public android::hardware::gnss::measurement_corrections::
                                                   V1_0::IMeasurementCorrectionsCallback {
public:
    MeasurementCorrectionsCallbackHidl() {}
    hardware::Return<void> setCapabilitiesCb(uint32_t capabilities) override;
};

class MeasurementCorrectionsCallback {
public:
    MeasurementCorrectionsCallback() {}
    sp<MeasurementCorrectionsCallbackAidl> getAidl() {
        if (callbackAidl == nullptr) {
            callbackAidl = sp<MeasurementCorrectionsCallbackAidl>::make();
        }
        return callbackAidl;
    }

    sp<MeasurementCorrectionsCallbackHidl> getHidl() {
        if (callbackHidl == nullptr) {
            callbackHidl = sp<MeasurementCorrectionsCallbackHidl>::make();
        }
        return callbackHidl;
    }

private:
    sp<MeasurementCorrectionsCallbackAidl> callbackAidl;
    sp<MeasurementCorrectionsCallbackHidl> callbackHidl;
};

struct MeasurementCorrectionsCallbackUtil {
    static void setCapabilitiesCb(uint32_t capabilities);

private:
    MeasurementCorrectionsCallbackUtil() = delete;
};

} // namespace android::gnss

#endif // _ANDROID_SERVER_GNSS_MEASUREMENTCORRECTIONSCALLBACK_H
