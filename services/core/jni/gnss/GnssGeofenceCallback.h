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

#ifndef _ANDROID_SERVER_GNSS_GNSSGEOFENCECALLBACK_H
#define _ANDROID_SERVER_GNSS_GNSSGEOFENCECALLBACK_H

#pragma once

#ifndef LOG_TAG
#error LOG_TAG must be defined before including this file.
#endif

#include <android/hardware/gnss/1.0/IGnssGeofencing.h>
#include <android/hardware/gnss/BnGnssGeofenceCallback.h>
#include <log/log.h>

#include <vector>

#include "Utils.h"
#include "jni.h"

namespace android::gnss {

namespace {
extern jmethodID method_reportGeofenceTransition;
extern jmethodID method_reportGeofenceStatus;
extern jmethodID method_reportGeofenceAddStatus;
extern jmethodID method_reportGeofenceRemoveStatus;
extern jmethodID method_reportGeofencePauseStatus;
extern jmethodID method_reportGeofenceResumeStatus;
} // anonymous namespace

void GnssGeofence_class_init_once(JNIEnv* env, jclass clazz);

class GnssGeofenceCallbackAidl : public hardware::gnss::BnGnssGeofenceCallback {
public:
    GnssGeofenceCallbackAidl() {}
    binder::Status gnssGeofenceTransitionCb(int geofenceId,
                                            const hardware::gnss::GnssLocation& location,
                                            int transition, int64_t timestampMillis) override;
    binder::Status gnssGeofenceStatusCb(int availability,
                                        const hardware::gnss::GnssLocation& lastLocation) override;
    binder::Status gnssGeofenceAddCb(int geofenceId, int status) override;
    binder::Status gnssGeofenceRemoveCb(int geofenceId, int status) override;
    binder::Status gnssGeofencePauseCb(int geofenceId, int status) override;
    binder::Status gnssGeofenceResumeCb(int geofenceId, int status) override;
};

class GnssGeofenceCallbackHidl : public hardware::gnss::V1_0::IGnssGeofenceCallback {
public:
    GnssGeofenceCallbackHidl() {}
    hardware::Return<void> gnssGeofenceTransitionCb(
            int32_t geofenceId, const hardware::gnss::V1_0::GnssLocation& location,
            hardware::gnss::V1_0::IGnssGeofenceCallback::GeofenceTransition transition,
            hardware::gnss::V1_0::GnssUtcTime timestamp) override;
    hardware::Return<void> gnssGeofenceStatusCb(
            hardware::gnss::V1_0::IGnssGeofenceCallback::GeofenceAvailability status,
            const hardware::gnss::V1_0::GnssLocation& location) override;
    hardware::Return<void> gnssGeofenceAddCb(
            int32_t geofenceId,
            hardware::gnss::V1_0::IGnssGeofenceCallback::GeofenceStatus status) override;
    hardware::Return<void> gnssGeofenceRemoveCb(
            int32_t geofenceId,
            hardware::gnss::V1_0::IGnssGeofenceCallback::GeofenceStatus status) override;
    hardware::Return<void> gnssGeofencePauseCb(
            int32_t geofenceId,
            hardware::gnss::V1_0::IGnssGeofenceCallback::GeofenceStatus status) override;
    hardware::Return<void> gnssGeofenceResumeCb(
            int32_t geofenceId,
            hardware::gnss::V1_0::IGnssGeofenceCallback::GeofenceStatus status) override;
};

class GnssGeofenceCallback {
public:
    GnssGeofenceCallback() {}
    sp<GnssGeofenceCallbackAidl> getAidl() {
        if (callbackAidl == nullptr) {
            callbackAidl = sp<GnssGeofenceCallbackAidl>::make();
        }
        return callbackAidl;
    }

    sp<GnssGeofenceCallbackHidl> getHidl() {
        if (callbackHidl == nullptr) {
            callbackHidl = sp<GnssGeofenceCallbackHidl>::make();
        }
        return callbackHidl;
    }

private:
    sp<GnssGeofenceCallbackAidl> callbackAidl;
    sp<GnssGeofenceCallbackHidl> callbackHidl;
};

/** Util class for GnssGeofenceCallback methods. */
struct GnssGeofenceCallbackUtil {
    template <class T>
    static void gnssGeofenceTransitionCb(int geofenceId, const T& location, int transition,
                                         int64_t timestampMillis);
    template <class T>
    static void gnssGeofenceStatusCb(int availability, const T& lastLocation);
    static void gnssGeofenceAddCb(int geofenceId, int status);
    static void gnssGeofenceRemoveCb(int geofenceId, int status);
    static void gnssGeofencePauseCb(int geofenceId, int status);
    static void gnssGeofenceResumeCb(int geofenceId, int status);

private:
    GnssGeofenceCallbackUtil() = delete;
};

template <class T>
void GnssGeofenceCallbackUtil::gnssGeofenceTransitionCb(int geofenceId, const T& location,
                                                        int transition, int64_t timestamp) {
    JNIEnv* env = getJniEnv();

    jobject jLocation = translateGnssLocation(env, location);

    env->CallVoidMethod(mCallbacksObj, method_reportGeofenceTransition, geofenceId, jLocation,
                        transition, timestamp);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    env->DeleteLocalRef(jLocation);
}

template <class T>
void GnssGeofenceCallbackUtil::gnssGeofenceStatusCb(int availability, const T& lastLocation) {
    JNIEnv* env = getJniEnv();

    jobject jLocation = translateGnssLocation(env, lastLocation);

    env->CallVoidMethod(mCallbacksObj, method_reportGeofenceStatus, availability, jLocation);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    env->DeleteLocalRef(jLocation);
}

} // namespace android::gnss

#endif // _ANDROID_SERVER_GNSS_GNSSGEOFENCECALLBACK_H