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

#ifndef _ANDROID_SERVER_GNSS_VISIBILITYCONTROLCALLBACK_H
#define _ANDROID_SERVER_GNSS_VISIBILITYCONTROLCALLBACK_H

#pragma once

#ifndef LOG_TAG
#error LOG_TAG must be defined before including this file.
#endif

#include <android/hardware/gnss/visibility_control/1.0/IGnssVisibilityControl.h>
#include <android/hardware/gnss/visibility_control/BnGnssVisibilityControlCallback.h>
#include <log/log.h>

#include "Utils.h"
#include "jni.h"

namespace android::gnss {

namespace {
extern jmethodID method_reportNfwNotification;
extern jmethodID method_isInEmergencySession;
} // anonymous namespace

void GnssVisibilityControl_class_init_once(JNIEnv* env, jclass clazz);

/*
 * GnssVisibilityControlCallbackAidl class implements the callback methods required by the
 * android::hardware::gnss::visibility_control::IGnssVisibilityControlCallback interface.
 */
class GnssVisibilityControlCallbackAidl
      : public hardware::gnss::visibility_control::BnGnssVisibilityControlCallback {
public:
    GnssVisibilityControlCallbackAidl() {}
    binder::Status nfwNotifyCb(
            const android::hardware::gnss::visibility_control::IGnssVisibilityControlCallback::
                    NfwNotification& notification) override;
    binder::Status isInEmergencySession(bool* _aidl_return) override;
};

/*
 * GnssVisibilityControlCallbackHidl implements callback methods of
 * IGnssVisibilityControlCallback 1.0 interface.
 */
class GnssVisibilityControlCallbackHidl
      : public android::hardware::gnss::visibility_control::V1_0::IGnssVisibilityControlCallback {
public:
    GnssVisibilityControlCallbackHidl() {}
    hardware::Return<void> nfwNotifyCb(
            const IGnssVisibilityControlCallback::NfwNotification& notification) override;
    hardware::Return<bool> isInEmergencySession() override;
};

class GnssVisibilityControlCallback {
public:
    GnssVisibilityControlCallback() {}
    sp<GnssVisibilityControlCallbackAidl> getAidl() {
        if (callbackAidl == nullptr) {
            callbackAidl = sp<GnssVisibilityControlCallbackAidl>::make();
        }
        return callbackAidl;
    }

    sp<GnssVisibilityControlCallbackHidl> getHidl() {
        if (callbackHidl == nullptr) {
            callbackHidl = sp<GnssVisibilityControlCallbackHidl>::make();
        }
        return callbackHidl;
    }

private:
    sp<GnssVisibilityControlCallbackAidl> callbackAidl;
    sp<GnssVisibilityControlCallbackHidl> callbackHidl;
};

struct GnssVisibilityControlCallbackUtil {
    template <class T>
    static void nfwNotifyCb(const T& notification);
    static bool isInEmergencySession();

private:
    GnssVisibilityControlCallbackUtil() = delete;
};

template <class T>
static jstring ToJstring(JNIEnv* env, const T& value);

template <class T>
void GnssVisibilityControlCallbackUtil::nfwNotifyCb(const T& notification) {
    JNIEnv* env = getJniEnv();
    jstring proxyAppPackageName = ToJstring(env, notification.proxyAppPackageName);
    jstring otherProtocolStackName = ToJstring(env, notification.otherProtocolStackName);
    jstring requestorId = ToJstring(env, notification.requestorId);

    if (proxyAppPackageName && otherProtocolStackName && requestorId) {
        env->CallVoidMethod(mCallbacksObj, method_reportNfwNotification, proxyAppPackageName,
                            notification.protocolStack, otherProtocolStackName,
                            notification.requestor, requestorId, notification.responseType,
                            notification.inEmergencyMode, notification.isCachedLocation);
    } else {
        ALOGE("%s: OOM Error\n", __func__);
    }

    if (requestorId) {
        env->DeleteLocalRef(requestorId);
    }

    if (otherProtocolStackName) {
        env->DeleteLocalRef(otherProtocolStackName);
    }

    if (proxyAppPackageName) {
        env->DeleteLocalRef(proxyAppPackageName);
    }

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

} // namespace android::gnss

#endif // _ANDROID_SERVER_GNSS_VISIBILITYCONTROLCALLBACK_H
