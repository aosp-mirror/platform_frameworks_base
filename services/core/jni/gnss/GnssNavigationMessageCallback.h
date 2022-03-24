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

#ifndef _ANDROID_SERVER_GNSS_GNSSNAVIGATIONMESSAGECALLBACK_H
#define _ANDROID_SERVER_GNSS_GNSSNAVIGATIONMESSAGECALLBACK_H

#pragma once

#ifndef LOG_TAG
#error LOG_TAG must be defined before including this file.
#endif

#include <android/hardware/gnss/1.0/IGnssNavigationMessage.h>
#include <android/hardware/gnss/BnGnssNavigationMessageCallback.h>
#include <log/log.h>

#include <vector>

#include "Utils.h"
#include "jni.h"

namespace android::gnss {

namespace {

extern jclass class_gnssNavigationMessage;
extern jmethodID method_reportNavigationMessages;
extern jmethodID method_gnssNavigationMessageCtor;

} // anonymous namespace

void GnssNavigationMessage_class_init_once(JNIEnv* env, jclass clazz);

class GnssNavigationMessageCallbackAidl : public hardware::gnss::BnGnssNavigationMessageCallback {
public:
    GnssNavigationMessageCallbackAidl() {}
    android::binder::Status gnssNavigationMessageCb(
            const hardware::gnss::IGnssNavigationMessageCallback::GnssNavigationMessage& message)
            override;
};

class GnssNavigationMessageCallbackHidl
      : public hardware::gnss::V1_0::IGnssNavigationMessageCallback {
public:
    GnssNavigationMessageCallbackHidl() {}

    hardware::Return<void> gnssNavigationMessageCb(
            const hardware::gnss::V1_0::IGnssNavigationMessageCallback::GnssNavigationMessage&
                    message) override;
};

class GnssNavigationMessageCallback {
public:
    GnssNavigationMessageCallback() {}
    sp<GnssNavigationMessageCallbackAidl> getAidl() {
        if (callbackAidl == nullptr) {
            callbackAidl = sp<GnssNavigationMessageCallbackAidl>::make();
        }
        return callbackAidl;
    }

    sp<GnssNavigationMessageCallbackHidl> getHidl() {
        if (callbackHidl == nullptr) {
            callbackHidl = sp<GnssNavigationMessageCallbackHidl>::make();
        }
        return callbackHidl;
    }

private:
    sp<GnssNavigationMessageCallbackAidl> callbackAidl;
    sp<GnssNavigationMessageCallbackHidl> callbackHidl;
};

struct GnssNavigationMessageCallbackUtil {
    template <class T>
    static void gnssNavigationMessageCbImpl(const T& message);

private:
    GnssNavigationMessageCallbackUtil() = delete;
};

template <class T>
void GnssNavigationMessageCallbackUtil::gnssNavigationMessageCbImpl(const T& message) {
    JNIEnv* env = getJniEnv();

    size_t dataLength = message.data.size();

    std::vector<uint8_t> navigationData = message.data;
    uint8_t* data = &(navigationData[0]);
    if (dataLength == 0 || data == nullptr) {
        ALOGE("Invalid Navigation Message found: data=%p, length=%zd", data, dataLength);
        return;
    }

    JavaObject object(env, class_gnssNavigationMessage, method_gnssNavigationMessageCtor);
    SET(Type, static_cast<int32_t>(message.type));
    SET(Svid, static_cast<int32_t>(message.svid));
    SET(MessageId, static_cast<int32_t>(message.messageId));
    SET(SubmessageId, static_cast<int32_t>(message.submessageId));
    object.callSetter("setData", data, dataLength);
    SET(Status, static_cast<int32_t>(message.status));

    jobject navigationMessage = object.get();
    env->CallVoidMethod(mCallbacksObj, method_reportNavigationMessages, navigationMessage);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    env->DeleteLocalRef(navigationMessage);
    return;
}

} // namespace android::gnss

#endif // _ANDROID_SERVER_GNSS_GNSSNAVIGATIONMESSAGECALLBACK_H