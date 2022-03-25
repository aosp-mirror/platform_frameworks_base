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

#ifndef _ANDROID_SERVER_GNSS_GNSSPSDSCALLBACK_H
#define _ANDROID_SERVER_GNSS_GNSSPSDSCALLBACK_H

#pragma once

#ifndef LOG_TAG
#error LOG_TAG must be defined before including this file.
#endif

#include <android/hardware/gnss/1.0/IGnssXtraCallback.h>
#include <android/hardware/gnss/BnGnssPsdsCallback.h>
#include <log/log.h>

#include "jni.h"

namespace android::gnss {

namespace {
extern jmethodID method_psdsDownloadRequest;
} // anonymous namespace

void GnssPsds_class_init_once(JNIEnv* env, jclass clazz);

class GnssPsdsCallbackAidl : public hardware::gnss::BnGnssPsdsCallback {
public:
    GnssPsdsCallbackAidl() {}
    binder::Status downloadRequestCb(hardware::gnss::PsdsType psdsType) override;
};

class GnssPsdsCallbackHidl : public hardware::gnss::V1_0::IGnssXtraCallback {
public:
    GnssPsdsCallbackHidl() {}
    hardware::Return<void> downloadRequestCb() override;
};

class GnssPsdsCallback {
public:
    GnssPsdsCallback() {}
    sp<GnssPsdsCallbackAidl> getAidl() {
        if (callbackAidl == nullptr) {
            callbackAidl = sp<GnssPsdsCallbackAidl>::make();
        }
        return callbackAidl;
    }

    sp<GnssPsdsCallbackHidl> getHidl() {
        if (callbackHidl == nullptr) {
            callbackHidl = sp<GnssPsdsCallbackHidl>::make();
        }
        return callbackHidl;
    }

private:
    sp<GnssPsdsCallbackAidl> callbackAidl;
    sp<GnssPsdsCallbackHidl> callbackHidl;
};

} // namespace android::gnss

#endif // _ANDROID_SERVER_GNSS_GNSSPSDSCALLBACK_H