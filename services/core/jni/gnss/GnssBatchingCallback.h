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

#ifndef _ANDROID_SERVER_GNSS_GNSSBATCHCALLBACK_H
#define _ANDROID_SERVER_GNSS_GNSSBATCHCALLBACK_H

#pragma once

#ifndef LOG_TAG
#error LOG_TAG must be defined before including this file.
#endif

#include <android/hardware/gnss/1.0/IGnssBatching.h>
#include <android/hardware/gnss/2.0/IGnssBatching.h>
#include <android/hardware/gnss/BnGnssBatchingCallback.h>
#include <log/log.h>

#include <vector>

#include "Utils.h"
#include "jni.h"

namespace android::gnss {

namespace {

extern jmethodID method_reportLocationBatch;

} // anonymous namespace

void GnssBatching_class_init_once(JNIEnv* env, jclass clazz);

class GnssBatchingCallbackAidl : public hardware::gnss::BnGnssBatchingCallback {
public:
    GnssBatchingCallbackAidl() {}
    android::binder::Status gnssLocationBatchCb(
            const std::vector<android::hardware::gnss::GnssLocation>& locations) override;
};

class GnssBatchingCallback_V1_0 : public hardware::gnss::V1_0::IGnssBatchingCallback {
public:
    GnssBatchingCallback_V1_0() {}
    hardware::Return<void> gnssLocationBatchCb(
            const hardware::hidl_vec<hardware::gnss::V1_0::GnssLocation>& locations) override;
};

class GnssBatchingCallback_V2_0 : public hardware::gnss::V2_0::IGnssBatchingCallback {
public:
    GnssBatchingCallback_V2_0() {}
    hardware::Return<void> gnssLocationBatchCb(
            const hardware::hidl_vec<hardware::gnss::V2_0::GnssLocation>& locations) override;
};

class GnssBatchingCallback {
public:
    GnssBatchingCallback() {}
    sp<GnssBatchingCallbackAidl> getAidl() {
        if (callbackAidl == nullptr) {
            callbackAidl = sp<GnssBatchingCallbackAidl>::make();
        }
        return callbackAidl;
    }

    sp<GnssBatchingCallback_V1_0> getV1_0() {
        if (callbackV1_0 == nullptr) {
            callbackV1_0 = sp<GnssBatchingCallback_V1_0>::make();
        }
        return callbackV1_0;
    }

    sp<GnssBatchingCallback_V2_0> getV2_0() {
        if (callbackV2_0 == nullptr) {
            callbackV2_0 = sp<GnssBatchingCallback_V2_0>::make();
        }
        return callbackV2_0;
    }

private:
    sp<GnssBatchingCallbackAidl> callbackAidl;
    sp<GnssBatchingCallback_V1_0> callbackV1_0;
    sp<GnssBatchingCallback_V2_0> callbackV2_0;
};

struct GnssBatchingCallbackUtil {
    template <class T>
    static hardware::Return<void> gnssLocationBatchCbImpl(const hardware::hidl_vec<T>& locations);

private:
    GnssBatchingCallbackUtil() = delete;
};

template <class T>
hardware::Return<void> GnssBatchingCallbackUtil::gnssLocationBatchCbImpl(
        const hardware::hidl_vec<T>& locations) {
    JNIEnv* env = getJniEnv();

    jobjectArray jLocations = env->NewObjectArray(locations.size(), class_location, nullptr);

    for (uint16_t i = 0; i < locations.size(); ++i) {
        jobject jLocation = translateGnssLocation(env, locations[i]);
        env->SetObjectArrayElement(jLocations, i, jLocation);
        env->DeleteLocalRef(jLocation);
    }

    env->CallVoidMethod(android::getCallbacksObj(), method_reportLocationBatch, jLocations);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);

    env->DeleteLocalRef(jLocations);

    return hardware::Void();
}

} // namespace android::gnss

#endif // _ANDROID_SERVER_GNSS_GNSSBATCHCALLBACK_H