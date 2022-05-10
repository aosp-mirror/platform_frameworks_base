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

#define LOG_TAG "AGnssRilCbJni"

#include "AGnssRilCallback.h"

namespace android::gnss {

jmethodID method_requestSetID;
jmethodID method_requestRefLocation;

using binder::Status;
using hardware::Return;
using hardware::Void;

void AGnssRil_class_init_once(JNIEnv* env, jclass clazz) {
    method_requestSetID = env->GetMethodID(clazz, "requestSetID", "(I)V");
    method_requestRefLocation = env->GetMethodID(clazz, "requestRefLocation", "()V");
}

Status AGnssRilCallbackAidl::requestSetIdCb(int setIdflag) {
    AGnssRilCallbackUtil::requestSetIdCb(setIdflag);
    return Status::ok();
}

Status AGnssRilCallbackAidl::requestRefLocCb() {
    AGnssRilCallbackUtil::requestRefLocCb();
    return Status::ok();
}

Return<void> AGnssRilCallback_V1_0::requestSetIdCb(uint32_t setIdflag) {
    AGnssRilCallbackUtil::requestSetIdCb(setIdflag);
    return Void();
}

Return<void> AGnssRilCallback_V1_0::requestRefLocCb() {
    AGnssRilCallbackUtil::requestRefLocCb();
    return Void();
}

void AGnssRilCallbackUtil::requestSetIdCb(int setIdflag) {
    ALOGD("%s. setIdflag: %d, ", __func__, setIdflag);
    JNIEnv* env = getJniEnv();
    env->CallVoidMethod(mCallbacksObj, method_requestSetID, setIdflag);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

void AGnssRilCallbackUtil::requestRefLocCb() {
    ALOGD("%s.", __func__);
    JNIEnv* env = getJniEnv();
    env->CallVoidMethod(mCallbacksObj, method_requestRefLocation);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

} // namespace android::gnss
