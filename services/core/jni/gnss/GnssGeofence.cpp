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

// Define LOG_TAG before <log/log.h> to overwrite the default value.
#define LOG_TAG "GnssGeofenceJni"

#include "GnssGeofence.h"

#include "Utils.h"

using android::hardware::hidl_bitfield;
using GeofenceTransition = android::hardware::gnss::V1_0::IGnssGeofenceCallback::GeofenceTransition;
using IGnssGeofenceAidl = android::hardware::gnss::IGnssGeofence;
using IGnssGeofenceHidl = android::hardware::gnss::V1_0::IGnssGeofencing;

namespace android::gnss {

// Implementation of GnssGeofence (AIDL HAL)

GnssGeofenceAidl::GnssGeofenceAidl(const sp<IGnssGeofenceAidl>& iGnssGeofence)
      : mIGnssGeofenceAidl(iGnssGeofence) {
    assert(mIGnssGeofenceAidl != nullptr);
}

jboolean GnssGeofenceAidl::setCallback(const std::unique_ptr<GnssGeofenceCallback>& callback) {
    auto status = mIGnssGeofenceAidl->setCallback(callback->getAidl());
    return checkAidlStatus(status, "IGnssGeofenceAidl init() failed.");
}

jboolean GnssGeofenceAidl::addGeofence(int geofenceId, double latitudeDegrees,
                                       double longitudeDegrees, double radiusMeters,
                                       int lastTransition, int monitorTransitions,
                                       int notificationResponsivenessMs, int unknownTimerMs) {
    auto status = mIGnssGeofenceAidl->addGeofence(geofenceId, latitudeDegrees, longitudeDegrees,
                                                  radiusMeters, lastTransition, monitorTransitions,
                                                  notificationResponsivenessMs, unknownTimerMs);
    return checkAidlStatus(status, "IGnssGeofenceAidl addGeofence() failed");
}

jboolean GnssGeofenceAidl::removeGeofence(int geofenceId) {
    auto status = mIGnssGeofenceAidl->removeGeofence(geofenceId);
    return checkAidlStatus(status, "IGnssGeofenceAidl removeGeofence() failed.");
}

jboolean GnssGeofenceAidl::pauseGeofence(int geofenceId) {
    auto status = mIGnssGeofenceAidl->pauseGeofence(geofenceId);
    return checkAidlStatus(status, "IGnssGeofenceAidl pauseGeofence() failed.");
}

jboolean GnssGeofenceAidl::resumeGeofence(int geofenceId, int monitorTransitions) {
    auto status = mIGnssGeofenceAidl->resumeGeofence(geofenceId, monitorTransitions);
    return checkAidlStatus(status, "IGnssGeofenceAidl resumeGeofence() failed.");
}

// Implementation of GnssGeofenceHidl

GnssGeofenceHidl::GnssGeofenceHidl(const sp<IGnssGeofenceHidl>& iGnssGeofence)
      : mIGnssGeofenceHidl(iGnssGeofence) {
    assert(mIGnssGeofenceHidl != nullptr);
}

jboolean GnssGeofenceHidl::setCallback(const std::unique_ptr<GnssGeofenceCallback>& callback) {
    auto result = mIGnssGeofenceHidl->setCallback(callback->getHidl());
    return checkHidlReturn(result, "IGnssGeofenceHidl setCallback() failed.");
}

jboolean GnssGeofenceHidl::addGeofence(int geofenceId, double latitudeDegrees,
                                       double longitudeDegrees, double radiusMeters,
                                       int lastTransition, int monitorTransitions,
                                       int notificationResponsivenessMs, int unknownTimerMs) {
    auto result = mIGnssGeofenceHidl->addGeofence(geofenceId, latitudeDegrees, longitudeDegrees,
                                                  radiusMeters,
                                                  static_cast<GeofenceTransition>(lastTransition),
                                                  static_cast<hidl_bitfield<GeofenceTransition>>(
                                                          monitorTransitions),
                                                  notificationResponsivenessMs, unknownTimerMs);
    return checkHidlReturn(result, "IGnssGeofence addGeofence() failed.");
}

jboolean GnssGeofenceHidl::removeGeofence(int geofenceId) {
    auto result = mIGnssGeofenceHidl->removeGeofence(geofenceId);
    return checkHidlReturn(result, "IGnssGeofence removeGeofence() failed.");
}

jboolean GnssGeofenceHidl::pauseGeofence(int geofenceId) {
    auto result = mIGnssGeofenceHidl->pauseGeofence(geofenceId);
    return checkHidlReturn(result, "IGnssGeofence pauseGeofence() failed.");
}

jboolean GnssGeofenceHidl::resumeGeofence(int geofenceId, int monitorTransitions) {
    auto result = mIGnssGeofenceHidl->resumeGeofence(geofenceId, monitorTransitions);
    return checkHidlReturn(result, "IGnssGeofence resumeGeofence() failed.");
}

} // namespace android::gnss
