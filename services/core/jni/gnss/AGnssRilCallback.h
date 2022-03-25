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

#ifndef _ANDROID_SERVER_GNSS_AGNSSRILCALLBACK_H
#define _ANDROID_SERVER_GNSS_AGNSSRILCALLBACK_H

#pragma once

#ifndef LOG_TAG
#error LOG_TAG must be defined before including this file.
#endif

#include <android/hardware/gnss/1.0/IAGnssRil.h>
#include <android/hardware/gnss/BnAGnssRilCallback.h>
#include <log/log.h>

#include "Utils.h"
#include "jni.h"

namespace android::gnss {

void AGnssRil_class_init_once(JNIEnv* env, jclass clazz);

/*
 * AGnssRilCallbackAidl class implements the callback methods required by the
 * android::hardware::gnss::IAGnssRil interface.
 */
class AGnssRilCallbackAidl : public android::hardware::gnss::BnAGnssRilCallback {
public:
    binder::Status requestSetIdCb(int setIdflag) override;
    binder::Status requestRefLocCb() override;
};

/*
 * AGnssRilCallback_V1_0 implements callback methods required by the IAGnssRilCallback 1.0
 * interface.
 */
class AGnssRilCallback_V1_0 : public android::hardware::gnss::V1_0::IAGnssRilCallback {
public:
    // Methods from ::android::hardware::gps::V1_0::IAGnssRilCallback follow.
    hardware::Return<void> requestSetIdCb(uint32_t setIdflag) override;
    hardware::Return<void> requestRefLocCb() override;
};

class AGnssRilCallback {
public:
    AGnssRilCallback() {}
    sp<AGnssRilCallbackAidl> getAidl() {
        if (callbackAidl == nullptr) {
            callbackAidl = sp<AGnssRilCallbackAidl>::make();
        }
        return callbackAidl;
    }

    sp<AGnssRilCallback_V1_0> getV1_0() {
        if (callbackV1_0 == nullptr) {
            callbackV1_0 = sp<AGnssRilCallback_V1_0>::make();
        }
        return callbackV1_0;
    }

private:
    sp<AGnssRilCallbackAidl> callbackAidl;
    sp<AGnssRilCallback_V1_0> callbackV1_0;
};

struct AGnssRilCallbackUtil {
    static void requestSetIdCb(int setIdflag);
    static void requestRefLocCb();

private:
    AGnssRilCallbackUtil() = delete;
};

} // namespace android::gnss

#endif // _ANDROID_SERVER_GNSS_AGNSSRILCALLBACK_H