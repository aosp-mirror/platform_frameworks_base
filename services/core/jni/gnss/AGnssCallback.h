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

#ifndef _ANDROID_SERVER_GNSS_AGNSSCALLBACK_H
#define _ANDROID_SERVER_GNSS_AGNSSCALLBACK_H

#pragma once

#ifndef LOG_TAG
#error LOG_TAG must be defined before including this file.
#endif

#include <android/hardware/gnss/1.0/IAGnss.h>
#include <android/hardware/gnss/2.0/IAGnss.h>
#include <android/hardware/gnss/BnAGnssCallback.h>
#include <arpa/inet.h>
#include <log/log.h>

#include <vector>

#include "Utils.h"
#include "jni.h"

namespace android::gnss {

namespace {

extern jmethodID method_reportAGpsStatus;

}

void AGnss_class_init_once(JNIEnv* env, jclass clazz);

/*
 * AGnssCallbackAidl class implements the callback methods required by the
 * android::hardware::gnss::IAGnss interface.
 */
class AGnssCallbackAidl : public android::hardware::gnss::BnAGnssCallback {
public:
    binder::Status agnssStatusCb(AGnssType type, AGnssStatusValue status) override;
};

/*
 * AGnssCallback_V1_0 implements callback methods required by the IAGnssCallback 1.0 interface.
 */
class AGnssCallback_V1_0 : public android::hardware::gnss::V1_0::IAGnssCallback {
public:
    // Methods from ::android::hardware::gps::V1_0::IAGnssCallback follow.
    hardware::Return<void> agnssStatusIpV6Cb(
            const android::hardware::gnss::V1_0::IAGnssCallback::AGnssStatusIpV6& agps_status)
            override;

    hardware::Return<void> agnssStatusIpV4Cb(
            const android::hardware::gnss::V1_0::IAGnssCallback::AGnssStatusIpV4& agps_status)
            override;

private:
    jbyteArray convertToIpV4(uint32_t ip);
};

/*
 * AGnssCallback_V2_0 implements callback methods required by the IAGnssCallback 2.0 interface.
 */
class AGnssCallback_V2_0 : public android::hardware::gnss::V2_0::IAGnssCallback {
public:
    // Methods from ::android::hardware::gps::V2_0::IAGnssCallback follow.
    hardware::Return<void> agnssStatusCb(
            android::hardware::gnss::V2_0::IAGnssCallback::AGnssType type,
            android::hardware::gnss::V2_0::IAGnssCallback::AGnssStatusValue status) override;
};

class AGnssCallback {
public:
    AGnssCallback() {}
    sp<AGnssCallbackAidl> getAidl() {
        if (callbackAidl == nullptr) {
            callbackAidl = sp<AGnssCallbackAidl>::make();
        }
        return callbackAidl;
    }

    sp<AGnssCallback_V1_0> getV1_0() {
        if (callbackV1_0 == nullptr) {
            callbackV1_0 = sp<AGnssCallback_V1_0>::make();
        }
        return callbackV1_0;
    }

    sp<AGnssCallback_V2_0> getV2_0() {
        if (callbackV2_0 == nullptr) {
            callbackV2_0 = sp<AGnssCallback_V2_0>::make();
        }
        return callbackV2_0;
    }

private:
    sp<AGnssCallbackAidl> callbackAidl;
    sp<AGnssCallback_V1_0> callbackV1_0;
    sp<AGnssCallback_V2_0> callbackV2_0;
};

struct AGnssCallbackUtil {
    template <class T, class U>
    static void agnssStatusCbImpl(const T& type, const U& status);

private:
    AGnssCallbackUtil() = delete;
};

template <class T, class U>
void AGnssCallbackUtil::agnssStatusCbImpl(const T& type, const U& status) {
    ALOGD("%s. type: %d, status:%d", __func__, static_cast<int32_t>(type),
          static_cast<int32_t>(status));
    JNIEnv* env = getJniEnv();
    env->CallVoidMethod(mCallbacksObj, method_reportAGpsStatus, type, status, nullptr);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

} // namespace android::gnss

#endif // _ANDROID_SERVER_GNSS_AGNSSCALLBACK_H