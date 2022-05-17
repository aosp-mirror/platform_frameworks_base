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

#define LOG_TAG "GnssPsdsCbJni"

#include "GnssPsdsCallback.h"

#include <vector>

#include "Utils.h"

namespace android::gnss {

namespace {
jmethodID method_psdsDownloadRequest;
} // anonymous namespace

using binder::Status;
using hardware::Return;
using hardware::Void;
using hardware::gnss::PsdsType;

void GnssPsds_class_init_once(JNIEnv* env, jclass clazz) {
    method_psdsDownloadRequest = env->GetMethodID(clazz, "psdsDownloadRequest", "(I)V");
}

// Implementation of android::hardware::gnss::IGnssPsdsCallback

Status GnssPsdsCallbackAidl::downloadRequestCb(PsdsType psdsType) {
    ALOGD("%s. psdsType: %d", __func__, static_cast<int32_t>(psdsType));
    JNIEnv* env = getJniEnv();
    env->CallVoidMethod(mCallbacksObj, method_psdsDownloadRequest, psdsType);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return Status::ok();
}

// Implementation of android::hardware::gnss::V1_0::IGnssPsdsCallback

Return<void> GnssPsdsCallbackHidl::downloadRequestCb() {
    JNIEnv* env = getJniEnv();
    env->CallVoidMethod(mCallbacksObj, method_psdsDownloadRequest, /* psdsType= */ 1);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return Void();
}

} // namespace android::gnss
