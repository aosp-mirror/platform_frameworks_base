/*
 * Copyright (C) 2021 The Android Open Source Project
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

#define LOG_TAG "GnssVisibilityControlCbJni"

#include "GnssVisibilityControlCallback.h"

namespace android::gnss {

// using android::hardware::hidl_vec;
using binder::Status;
using hardware::Return;

namespace {
jmethodID method_reportNfwNotification;
jmethodID method_isInEmergencySession;
} // anonymous namespace

void GnssVisibilityControl_class_init_once(JNIEnv* env, jclass clazz) {
    method_reportNfwNotification =
            env->GetMethodID(clazz, "reportNfwNotification",
                             "(Ljava/lang/String;BLjava/lang/String;BLjava/lang/String;BZZ)V");
    method_isInEmergencySession = env->GetMethodID(clazz, "isInEmergencySession", "()Z");
}

// Implementation of GnssVisibilityControlCallbackAidl class.

Status GnssVisibilityControlCallbackAidl::nfwNotifyCb(
        const android::hardware::gnss::visibility_control::IGnssVisibilityControlCallback::
                NfwNotification& notification) {
    GnssVisibilityControlCallbackUtil::nfwNotifyCb(notification);
    return Status::ok();
}

Status GnssVisibilityControlCallbackAidl::isInEmergencySession(bool* _aidl_return) {
    *_aidl_return = GnssVisibilityControlCallbackUtil::isInEmergencySession();
    return Status::ok();
}

// Implementation of GnssVisibilityControlCallbackHidl class.

Return<void> GnssVisibilityControlCallbackHidl::nfwNotifyCb(
        const IGnssVisibilityControlCallback::NfwNotification& notification) {
    GnssVisibilityControlCallbackUtil::nfwNotifyCb(notification);
    return hardware::Void();
}

Return<bool> GnssVisibilityControlCallbackHidl::isInEmergencySession() {
    return GnssVisibilityControlCallbackUtil::isInEmergencySession();
}

// Implementation of GnssVisibilityControlCallbackUtil class.

bool GnssVisibilityControlCallbackUtil::isInEmergencySession() {
    JNIEnv* env = getJniEnv();
    auto result = env->CallBooleanMethod(mCallbacksObj, method_isInEmergencySession);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return result;
}

template <>
jstring ToJstring(JNIEnv* env, const String16& value) {
    const char16_t* str = value.c_str();
    size_t len = value.size();
    return env->NewString(reinterpret_cast<const jchar*>(str), len);
}

template <>
jstring ToJstring(JNIEnv* env, const hardware::hidl_string& value) {
    return env->NewStringUTF(value.c_str());
}

} // namespace android::gnss
