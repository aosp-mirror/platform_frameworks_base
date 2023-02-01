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

#define LOG_TAG "GnssGeofenceCbJni"

#include "GnssGeofenceCallback.h"

namespace android::gnss {

namespace {

jmethodID method_reportGeofenceTransition;
jmethodID method_reportGeofenceStatus;
jmethodID method_reportGeofenceAddStatus;
jmethodID method_reportGeofenceRemoveStatus;
jmethodID method_reportGeofencePauseStatus;
jmethodID method_reportGeofenceResumeStatus;

} // anonymous namespace

using binder::Status;
using hardware::Return;
using hardware::Void;
using GeofenceAvailability =
        android::hardware::gnss::V1_0::IGnssGeofenceCallback::GeofenceAvailability;
using GeofenceStatus = android::hardware::gnss::V1_0::IGnssGeofenceCallback::GeofenceStatus;
using GeofenceTransition = android::hardware::gnss::V1_0::IGnssGeofenceCallback::GeofenceTransition;

using GnssLocationAidl = android::hardware::gnss::GnssLocation;
using GnssLocation_V1_0 = android::hardware::gnss::V1_0::GnssLocation;

void GnssGeofence_class_init_once(JNIEnv* env, jclass clazz) {
    method_reportGeofenceTransition = env->GetMethodID(clazz, "reportGeofenceTransition",
                                                       "(ILandroid/location/Location;IJ)V");
    method_reportGeofenceStatus =
            env->GetMethodID(clazz, "reportGeofenceStatus", "(ILandroid/location/Location;)V");
    method_reportGeofenceAddStatus = env->GetMethodID(clazz, "reportGeofenceAddStatus", "(II)V");
    method_reportGeofenceRemoveStatus =
            env->GetMethodID(clazz, "reportGeofenceRemoveStatus", "(II)V");
    method_reportGeofenceResumeStatus =
            env->GetMethodID(clazz, "reportGeofenceResumeStatus", "(II)V");
    method_reportGeofencePauseStatus =
            env->GetMethodID(clazz, "reportGeofencePauseStatus", "(II)V");
}

Status GnssGeofenceCallbackAidl::gnssGeofenceTransitionCb(int geofenceId,
                                                          const GnssLocationAidl& location,
                                                          int transition, int64_t timestampMillis) {
    GnssGeofenceCallbackUtil::gnssGeofenceTransitionCb(geofenceId, location, transition,
                                                       timestampMillis);
    return Status::ok();
}

Status GnssGeofenceCallbackAidl::gnssGeofenceStatusCb(int availability,
                                                      const GnssLocationAidl& lastLocation) {
    GnssGeofenceCallbackUtil::gnssGeofenceStatusCb(availability, lastLocation);
    return Status::ok();
}

Status GnssGeofenceCallbackAidl::gnssGeofenceAddCb(int geofenceId, int status) {
    GnssGeofenceCallbackUtil::gnssGeofenceAddCb(geofenceId, status);
    return Status::ok();
}

Status GnssGeofenceCallbackAidl::gnssGeofenceRemoveCb(int geofenceId, int status) {
    GnssGeofenceCallbackUtil::gnssGeofenceRemoveCb(geofenceId, status);
    return Status::ok();
}

Status GnssGeofenceCallbackAidl::gnssGeofencePauseCb(int geofenceId, int status) {
    GnssGeofenceCallbackUtil::gnssGeofencePauseCb(geofenceId, status);
    return Status::ok();
}

Status GnssGeofenceCallbackAidl::gnssGeofenceResumeCb(int geofenceId, int status) {
    GnssGeofenceCallbackUtil::gnssGeofenceResumeCb(geofenceId, status);
    return Status::ok();
}

Return<void> GnssGeofenceCallbackHidl::gnssGeofenceTransitionCb(
        int32_t geofenceId, const GnssLocation_V1_0& location, GeofenceTransition transition,
        hardware::gnss::V1_0::GnssUtcTime timestamp) {
    GnssGeofenceCallbackUtil::gnssGeofenceTransitionCb(geofenceId, location, (int)transition,
                                                       (int64_t)timestamp);
    return Void();
}

Return<void> GnssGeofenceCallbackHidl::gnssGeofenceStatusCb(GeofenceAvailability availability,
                                                            const GnssLocation_V1_0& location) {
    GnssGeofenceCallbackUtil::gnssGeofenceStatusCb((int)availability, location);
    return Void();
}

Return<void> GnssGeofenceCallbackHidl::gnssGeofenceAddCb(int32_t geofenceId,
                                                         GeofenceStatus status) {
    GnssGeofenceCallbackUtil::gnssGeofenceAddCb(geofenceId, (int)status);
    return Void();
}

Return<void> GnssGeofenceCallbackHidl::gnssGeofenceRemoveCb(int32_t geofenceId,
                                                            GeofenceStatus status) {
    GnssGeofenceCallbackUtil::gnssGeofenceRemoveCb(geofenceId, (int)status);
    return Void();
}

Return<void> GnssGeofenceCallbackHidl::gnssGeofencePauseCb(int32_t geofenceId,
                                                           GeofenceStatus status) {
    GnssGeofenceCallbackUtil::gnssGeofencePauseCb(geofenceId, (int)status);
    return Void();
}

Return<void> GnssGeofenceCallbackHidl::gnssGeofenceResumeCb(int32_t geofenceId,
                                                            GeofenceStatus status) {
    GnssGeofenceCallbackUtil::gnssGeofenceResumeCb(geofenceId, (int)status);
    return Void();
}

void GnssGeofenceCallbackUtil::gnssGeofenceAddCb(int geofenceId, int status) {
    JNIEnv* env = getJniEnv();
    if (status != hardware::gnss::IGnssGeofenceCallback::OPERATION_SUCCESS) {
        ALOGE("%s: Error in adding a Geofence: %d\n", __func__, status);
    }

    env->CallVoidMethod(mCallbacksObj, method_reportGeofenceAddStatus, geofenceId, status);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

void GnssGeofenceCallbackUtil::gnssGeofenceRemoveCb(int geofenceId, int status) {
    JNIEnv* env = getJniEnv();
    if (status != hardware::gnss::IGnssGeofenceCallback::OPERATION_SUCCESS) {
        ALOGE("%s: Error in removing a Geofence: %d\n", __func__, status);
    }

    env->CallVoidMethod(mCallbacksObj, method_reportGeofenceRemoveStatus, geofenceId, status);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

void GnssGeofenceCallbackUtil::gnssGeofencePauseCb(int geofenceId, int status) {
    JNIEnv* env = getJniEnv();
    if (status != hardware::gnss::IGnssGeofenceCallback::OPERATION_SUCCESS) {
        ALOGE("%s: Error in pausing Geofence: %d\n", __func__, status);
    }

    env->CallVoidMethod(mCallbacksObj, method_reportGeofencePauseStatus, geofenceId, status);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

void GnssGeofenceCallbackUtil::gnssGeofenceResumeCb(int geofenceId, int status) {
    JNIEnv* env = getJniEnv();
    if (status != hardware::gnss::IGnssGeofenceCallback::OPERATION_SUCCESS) {
        ALOGE("%s: Error in resuming Geofence: %d\n", __func__, status);
    }

    env->CallVoidMethod(mCallbacksObj, method_reportGeofenceResumeStatus, geofenceId, status);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

} // namespace android::gnss
