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

#define LOG_TAG "MeasurementCorrectionsCbJni"

#include "MeasurementCorrectionsCallback.h"

namespace android::gnss {

namespace {
jmethodID method_setSubHalMeasurementCorrectionsCapabilities;
}

void MeasurementCorrectionsCallback_class_init_once(JNIEnv* env, jclass clazz) {
    method_setSubHalMeasurementCorrectionsCapabilities =
            env->GetMethodID(clazz, "setSubHalMeasurementCorrectionsCapabilities", "(I)V");
}

using binder::Status;
using hardware::Return;

// Implementation of MeasurementCorrectionsCallbackAidl class.

Status MeasurementCorrectionsCallbackAidl::setCapabilitiesCb(const int capabilities) {
    MeasurementCorrectionsCallbackUtil::setCapabilitiesCb(capabilities);
    return Status::ok();
}

// Implementation of MeasurementCorrectionsCallbackHidl class.

Return<void> MeasurementCorrectionsCallbackHidl::setCapabilitiesCb(uint32_t capabilities) {
    MeasurementCorrectionsCallbackUtil::setCapabilitiesCb(capabilities);
    return hardware::Void();
}

// Implementation of MeasurementCorrectionsCallbackUtil class.

void MeasurementCorrectionsCallbackUtil::setCapabilitiesCb(uint32_t capabilities) {
    ALOGD("%s: %du\n", __func__, capabilities);
    JNIEnv* env = getJniEnv();
    env->CallVoidMethod(mCallbacksObj, method_setSubHalMeasurementCorrectionsCapabilities,
                        capabilities);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

} // namespace android::gnss
