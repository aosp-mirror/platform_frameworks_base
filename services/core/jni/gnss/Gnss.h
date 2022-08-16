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

#ifndef _ANDROID_SERVER_GNSS_GNSS_H
#define _ANDROID_SERVER_GNSS_GNSS_H

#pragma once

#ifndef LOG_TAG
#error LOG_TAG must be defined before including this file.
#endif

#include <android/hardware/gnss/1.0/IGnss.h>
#include <android/hardware/gnss/1.1/IGnss.h>
#include <android/hardware/gnss/2.0/IGnss.h>
#include <android/hardware/gnss/2.1/IGnss.h>
#include <android/hardware/gnss/BnGnss.h>
#include <binder/IBinder.h>
#include <log/log.h>

#include "AGnss.h"
#include "AGnssRil.h"
#include "GnssAntennaInfo.h"
#include "GnssBatching.h"
#include "GnssCallback.h"
#include "GnssConfiguration.h"
#include "GnssDebug.h"
#include "GnssGeofence.h"
#include "GnssMeasurement.h"
#include "GnssNavigationMessage.h"
#include "GnssPsds.h"
#include "GnssVisibilityControl.h"
#include "MeasurementCorrections.h"
#include "jni.h"

namespace android::gnss {

struct GnssDeathRecipient : virtual public hardware::hidl_death_recipient {
    // hidl_death_recipient interface
    virtual void serviceDied(uint64_t cookie, const wp<hidl::base::V1_0::IBase>& who) override {
        ALOGE("GNSS HIDL service failed, trying to recover...");
        onServiceDied();
    }

    static void onServiceDied() {
        JNIEnv* env = android::AndroidRuntime::getJNIEnv();
        env->CallVoidMethod(android::mCallbacksObj, method_reportGnssServiceDied);
    }
};

struct GnssDeathRecipientAidl : virtual public IBinder::DeathRecipient {
    // IBinder::DeathRecipient implementation
    virtual void binderDied(const wp<IBinder>& who) override {
        ALOGE("GNSS AIDL service failed, trying to recover...");
        GnssDeathRecipient::onServiceDied();
    }
};

class GnssHal {
public:
    GnssHal();
    ~GnssHal() {}

    jboolean isSupported();
    jboolean setCallback();
    jboolean start();
    jboolean stop();
    jboolean setPositionMode(jint mode, jint recurrence, jint min_interval, jint preferred_accuracy,
                             jint preferred_time, jboolean low_power_mode);
    jboolean startSvStatus();
    jboolean stopSvStatus();
    jboolean startNmea();
    jboolean stopNmea();
    jint readNmea(jbyteArray& nmeaArray, jint& buffer_size);
    void linkToDeath();
    void close();
    void deleteAidingData(jint flags);
    void injectTime(jlong time, jlong timeReference, jint uncertainty);
    void injectLocation(jint gnssLocationFlags, jdouble latitudeDegrees, jdouble longitudeDegrees,
                        jdouble altitudeMeters, jfloat speedMetersPerSec, jfloat bearingDegrees,
                        jfloat horizontalAccuracyMeters, jfloat verticalAccuracyMeters,
                        jfloat speedAccuracyMetersPerSecond, jfloat bearingAccuracyDegrees,
                        jlong timestamp, jint elapsedRealtimeFlags, jlong elapsedRealtimeNanos,
                        jdouble elapsedRealtimeUncertaintyNanos);
    void injectBestLocation(jint gnssLocationFlags, jdouble latitudeDegrees,
                            jdouble longitudeDegrees, jdouble altitudeMeters,
                            jfloat speedMetersPerSec, jfloat bearingDegrees,
                            jfloat horizontalAccuracyMeters, jfloat verticalAccuracyMeters,
                            jfloat speedAccuracyMetersPerSecond, jfloat bearingAccuracyDegrees,
                            jlong timestamp, jint elapsedRealtimeFlags, jlong elapsedRealtimeNanos,
                            jdouble elapsedRealtimeUncertaintyNanos);

    std::unique_ptr<AGnssInterface> getAGnssInterface();
    std::unique_ptr<AGnssRilInterface> getAGnssRilInterface();
    std::unique_ptr<GnssNavigationMessageInterface> getGnssNavigationMessageInterface();
    std::unique_ptr<GnssMeasurementInterface> getGnssMeasurementInterface();
    std::unique_ptr<GnssDebugInterface> getGnssDebugInterface();
    std::unique_ptr<GnssConfigurationInterface> getGnssConfigurationInterface();
    std::unique_ptr<GnssGeofenceInterface> getGnssGeofenceInterface();
    std::unique_ptr<GnssBatchingInterface> getGnssBatchingInterface();
    std::unique_ptr<MeasurementCorrectionsInterface> getMeasurementCorrectionsInterface();
    std::unique_ptr<GnssVisibilityControlInterface> getGnssVisibilityControlInterface();
    std::unique_ptr<GnssAntennaInfoInterface> getGnssAntennaInfoInterface();
    std::unique_ptr<GnssPsdsInterface> getGnssPsdsInterface();

    sp<hardware::gnss::IGnssPowerIndication> getGnssPowerIndicationInterface();
    sp<hardware::gnss::V1_0::IGnssNi> getGnssNiInterface();

private:
    sp<GnssDeathRecipient> gnssHalDeathRecipient = nullptr;
    sp<GnssDeathRecipientAidl> gnssHalDeathRecipientAidl = nullptr;
    sp<hardware::gnss::V1_0::IGnss> gnssHal = nullptr;
    sp<hardware::gnss::V1_1::IGnss> gnssHal_V1_1 = nullptr;
    sp<hardware::gnss::V2_0::IGnss> gnssHal_V2_0 = nullptr;
    sp<hardware::gnss::V2_1::IGnss> gnssHal_V2_1 = nullptr;
    sp<hardware::gnss::IGnss> gnssHalAidl = nullptr;
};

} // namespace android::gnss

#endif // _ANDROID_SERVER_GNSS_Gnss_H
