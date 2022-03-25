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

#define LOG_TAG "GnssNavMsgCbJni"

#include "GnssNavigationMessageCallback.h"

namespace android::gnss {

namespace {

jclass class_gnssNavigationMessage;
jmethodID method_reportNavigationMessages;
jmethodID method_gnssNavigationMessageCtor;

} // anonymous namespace

using binder::Status;
using hardware::Return;
using hardware::Void;

using GnssNavigationMessageAidl =
        android::hardware::gnss::IGnssNavigationMessageCallback::GnssNavigationMessage;
using GnssNavigationMessageHidl =
        android::hardware::gnss::V1_0::IGnssNavigationMessageCallback::GnssNavigationMessage;

void GnssNavigationMessage_class_init_once(JNIEnv* env, jclass clazz) {
    method_reportNavigationMessages =
            env->GetMethodID(clazz, "reportNavigationMessage",
                             "(Landroid/location/GnssNavigationMessage;)V");

    jclass gnssNavigationMessageClass = env->FindClass("android/location/GnssNavigationMessage");
    class_gnssNavigationMessage = (jclass)env->NewGlobalRef(gnssNavigationMessageClass);
    method_gnssNavigationMessageCtor =
            env->GetMethodID(class_gnssNavigationMessage, "<init>", "()V");
}

Status GnssNavigationMessageCallbackAidl::gnssNavigationMessageCb(
        const GnssNavigationMessageAidl& message) {
    GnssNavigationMessageCallbackUtil::gnssNavigationMessageCbImpl(message);
    return Status::ok();
}

Return<void> GnssNavigationMessageCallbackHidl::gnssNavigationMessageCb(
        const GnssNavigationMessageHidl& message) {
    GnssNavigationMessageCallbackUtil::gnssNavigationMessageCbImpl(message);
    return Void();
}

} // namespace android::gnss
