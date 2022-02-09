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

// Define LOG_TAG before <log/log.h> to overwrite the default value.
#define LOG_TAG "GnssVisibilityControlJni"

#include "GnssVisibilityControl.h"

#include "Utils.h"

using IGnssVisibilityControlAidl =
        android::hardware::gnss::visibility_control::IGnssVisibilityControl;
using IGnssVisibilityControlHidl =
        android::hardware::gnss::visibility_control::V1_0::IGnssVisibilityControl;

namespace android::gnss {

// Implementation of GnssVisibilityControl (AIDL HAL)

GnssVisibilityControlAidl::GnssVisibilityControlAidl(
        const sp<IGnssVisibilityControlAidl>& iGnssVisibilityControl)
      : mIGnssVisibilityControlAidl(iGnssVisibilityControl) {
    assert(mIGnssVisibilityControlAidl != nullptr);
}

jboolean GnssVisibilityControlAidl::setCallback(
        const std::unique_ptr<GnssVisibilityControlCallback>& callback) {
    auto status = mIGnssVisibilityControlAidl->setCallback(callback->getAidl());
    return checkAidlStatus(status, "IGnssVisibilityControlAidl setCallback() failed.");
}

jboolean GnssVisibilityControlAidl::enableNfwLocationAccess(JNIEnv* env, jobjectArray proxyApps) {
    int length = env->GetArrayLength(proxyApps);
    std::vector<std::string> aidlProxyApps(length);
    for (int i = 0; i < length; ++i) {
        jstring proxyApp = (jstring)(env->GetObjectArrayElement(proxyApps, i));
        ScopedJniString jniProxyApp(env, proxyApp);
        aidlProxyApps[i] = std::string(jniProxyApp.c_str());
    }
    auto status = mIGnssVisibilityControlAidl->enableNfwLocationAccess(aidlProxyApps);
    return checkAidlStatus(status, "IGnssVisibilityControlAidl enableNfwLocationAccess() failed");
}

// Implementation of GnssVisibilityControlHidl

GnssVisibilityControlHidl::GnssVisibilityControlHidl(
        const sp<IGnssVisibilityControlHidl>& iGnssVisibilityControl)
      : mIGnssVisibilityControlHidl(iGnssVisibilityControl) {
    assert(mIGnssVisibilityControlHidl != nullptr);
}

jboolean GnssVisibilityControlHidl::setCallback(
        const std::unique_ptr<GnssVisibilityControlCallback>& callback) {
    auto result = mIGnssVisibilityControlHidl->setCallback(callback->getHidl());
    return checkHidlReturn(result, "IGnssVisibilityControlHidl setCallback() failed.");
}

jboolean GnssVisibilityControlHidl::enableNfwLocationAccess(JNIEnv* env, jobjectArray proxyApps) {
    const jsize length = env->GetArrayLength(proxyApps);
    hardware::hidl_vec<hardware::hidl_string> hidlProxyApps(length);
    for (int i = 0; i < length; ++i) {
        jstring proxyApp = (jstring)(env->GetObjectArrayElement(proxyApps, i));
        ScopedJniString jniProxyApp(env, proxyApp);
        hidlProxyApps[i] = jniProxyApp;
    }

    auto result = mIGnssVisibilityControlHidl->enableNfwLocationAccess(hidlProxyApps);
    return checkHidlReturn(result, "IGnssVisibilityControlHidl enableNfwLocationAccess() failed.");
}

} // namespace android::gnss
