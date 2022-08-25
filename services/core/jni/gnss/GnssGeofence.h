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

#ifndef _ANDROID_SERVER_GNSS_GNSSGEOFENCE_H
#define _ANDROID_SERVER_GNSS_GNSSGEOFENCE_H

#pragma once

#ifndef LOG_TAG
#error LOG_TAG must be defined before including this file.
#endif

#include <android/hardware/gnss/1.0/IGnssGeofencing.h>
#include <android/hardware/gnss/BnGnssGeofence.h>
#include <log/log.h>

#include "GnssGeofenceCallback.h"
#include "jni.h"

namespace android::gnss {

class GnssGeofenceInterface {
public:
    virtual ~GnssGeofenceInterface() {}
    virtual jboolean setCallback(const std::unique_ptr<GnssGeofenceCallback>& callback);
    virtual jboolean addGeofence(int geofenceId, double latitudeDegrees, double longitudeDegrees,
                                 double radiusMeters, int lastTransition, int monitorTransitions,
                                 int notificationResponsivenessMs, int unknownTimerMs);
    virtual jboolean pauseGeofence(int geofenceId);
    virtual jboolean resumeGeofence(int geofenceId, int monitorTransitions);
    virtual jboolean removeGeofence(int geofenceId);
};

class GnssGeofenceAidl : public GnssGeofenceInterface {
public:
    GnssGeofenceAidl(const sp<android::hardware::gnss::IGnssGeofence>& iGnssGeofence);
    jboolean setCallback(const std::unique_ptr<GnssGeofenceCallback>& callback) override;
    jboolean addGeofence(int geofenceId, double latitudeDegrees, double longitudeDegrees,
                         double radiusMeters, int lastTransition, int monitorTransitions,
                         int notificationResponsivenessMs, int unknownTimerMs) override;
    jboolean pauseGeofence(int geofenceId) override;
    jboolean resumeGeofence(int geofenceId, int monitorTransitions) override;
    jboolean removeGeofence(int geofenceId) override;

private:
    const sp<android::hardware::gnss::IGnssGeofence> mIGnssGeofenceAidl;
};

class GnssGeofenceHidl : public GnssGeofenceInterface {
public:
    GnssGeofenceHidl(const sp<android::hardware::gnss::V1_0::IGnssGeofencing>& iGnssGeofence);
    jboolean setCallback(const std::unique_ptr<GnssGeofenceCallback>& callback) override;
    jboolean addGeofence(int geofenceId, double latitudeDegrees, double longitudeDegrees,
                         double radiusMeters, int lastTransition, int monitorTransitions,
                         int notificationResponsivenessMs, int unknownTimerMs) override;
    jboolean pauseGeofence(int geofenceId) override;
    jboolean resumeGeofence(int geofenceId, int monitorTransitions) override;
    jboolean removeGeofence(int geofenceId) override;

private:
    const sp<android::hardware::gnss::V1_0::IGnssGeofencing> mIGnssGeofenceHidl;
};

} // namespace android::gnss

#endif // _ANDROID_SERVER_GNSS_GNSSGEOFENCE_H
