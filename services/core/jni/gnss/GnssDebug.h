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

#ifndef _ANDROID_SERVER_GNSS_GNSSDEBUG_H
#define _ANDROID_SERVER_GNSS_GNSSDEBUG_H

#pragma once

#ifndef LOG_TAG
#error LOG_TAG must be defined before including this file.
#endif

#include <android/hardware/gnss/1.0/IGnssDebug.h>
#include <android/hardware/gnss/2.0/IGnssDebug.h>
#include <android/hardware/gnss/BnGnssDebug.h>
#include <log/log.h>

#include <iomanip>

#include "jni.h"

namespace android::gnss {

class GnssDebugInterface {
public:
    virtual ~GnssDebugInterface() {}
    virtual jstring getDebugData(JNIEnv* env) = 0;
};

class GnssDebug : public GnssDebugInterface {
public:
    GnssDebug(const sp<android::hardware::gnss::IGnssDebug>& iGnssDebug);
    jstring getDebugData(JNIEnv* env) override;

private:
    const sp<android::hardware::gnss::IGnssDebug> mIGnssDebug;
};

class GnssDebug_V1_0 : public GnssDebugInterface {
public:
    GnssDebug_V1_0(const sp<android::hardware::gnss::V1_0::IGnssDebug>& iGnssDebug);
    jstring getDebugData(JNIEnv* env) override;

private:
    const sp<android::hardware::gnss::V1_0::IGnssDebug> mIGnssDebug_V1_0;
};

class GnssDebug_V2_0 : public GnssDebugInterface {
public:
    GnssDebug_V2_0(const sp<android::hardware::gnss::V2_0::IGnssDebug>& iGnssDebug);
    jstring getDebugData(JNIEnv* env) override;

private:
    const sp<android::hardware::gnss::V2_0::IGnssDebug> mIGnssDebug_V2_0;
};

struct GnssDebugUtil {
    template <class T>
    static uint32_t getConstellationType(const hardware::hidl_vec<T>& satelliteDataArray, size_t i);

    template <class T>
    static uint32_t getConstellationType(const std::vector<T>& satelliteDataArray, size_t i);

    template <class T>
    static int64_t getTimeEstimateMs(const T& data);

    template <class T_DebugData, class T_SatelliteData>
    static jstring parseDebugData(JNIEnv* env, std::stringstream& internalState,
                                  const T_DebugData& data);

    const static android::hardware::gnss::V1_0::IGnssDebug::SatelliteData& getSatelliteData(
            const hardware::hidl_vec<android::hardware::gnss::V1_0::IGnssDebug::SatelliteData>&
                    satelliteDataArray,
            size_t i);

    const static android::hardware::gnss::V1_0::IGnssDebug::SatelliteData& getSatelliteData(
            const hardware::hidl_vec<android::hardware::gnss::V2_0::IGnssDebug::SatelliteData>&
                    satelliteDataArray,
            size_t i);

    const static android::hardware::gnss::IGnssDebug::SatelliteData& getSatelliteData(
            const std::vector<android::hardware::gnss::IGnssDebug::SatelliteData>&
                    satelliteDataArray,
            size_t i);
};

template <class T>
uint32_t GnssDebugUtil::getConstellationType(const hardware::hidl_vec<T>& satelliteDataArray,
                                             size_t i) {
    return static_cast<uint32_t>(satelliteDataArray[i].constellation);
}

template <class T>
uint32_t GnssDebugUtil::getConstellationType(const std::vector<T>& satelliteDataArray, size_t i) {
    return static_cast<uint32_t>(satelliteDataArray[i].constellation);
}

template <class T>
int64_t GnssDebugUtil::getTimeEstimateMs(const T& data) {
    return data.time.timeEstimate;
}

template <class T_DebugData, class T_SatelliteData>
jstring GnssDebugUtil::parseDebugData(JNIEnv* env, std::stringstream& internalState,
                                      const T_DebugData& data) {
    internalState << "Gnss Location Data:: ";
    if (!data.position.valid) {
        internalState << "not valid";
    } else {
        internalState << "LatitudeDegrees: " << data.position.latitudeDegrees
                      << ", LongitudeDegrees: " << data.position.longitudeDegrees
                      << ", altitudeMeters: " << data.position.altitudeMeters
                      << ", speedMetersPerSecond: " << data.position.speedMetersPerSec
                      << ", bearingDegrees: " << data.position.bearingDegrees
                      << ", horizontalAccuracyMeters: " << data.position.horizontalAccuracyMeters
                      << ", verticalAccuracyMeters: " << data.position.verticalAccuracyMeters
                      << ", speedAccuracyMetersPerSecond: "
                      << data.position.speedAccuracyMetersPerSecond
                      << ", bearingAccuracyDegrees: " << data.position.bearingAccuracyDegrees
                      << ", ageSeconds: " << data.position.ageSeconds;
    }
    internalState << std::endl;

    internalState << "Gnss Time Data:: timeEstimate: " << GnssDebugUtil::getTimeEstimateMs(data)
                  << ", timeUncertaintyNs: " << data.time.timeUncertaintyNs
                  << ", frequencyUncertaintyNsPerSec: " << data.time.frequencyUncertaintyNsPerSec
                  << std::endl;

    if (data.satelliteDataArray.size() != 0) {
        internalState << "Satellite Data for " << data.satelliteDataArray.size()
                      << " satellites:: " << std::endl;
    }

    internalState << "constell: 1=GPS, 2=SBAS, 3=GLO, 4=QZSS, 5=BDS, 6=GAL, 7=IRNSS; "
                  << "ephType: 0=Eph, 1=Alm, 2=Unk; "
                  << "ephSource: 0=Demod, 1=Supl, 2=Server, 3=Unk; "
                  << "ephHealth: 0=Good, 1=Bad, 2=Unk" << std::endl;
    for (size_t i = 0; i < data.satelliteDataArray.size(); i++) {
        T_SatelliteData satelliteData = getSatelliteData(data.satelliteDataArray, i);
        internalState << "constell: "
                      << GnssDebugUtil::getConstellationType(data.satelliteDataArray, i)
                      << ", svid: " << std::setw(3) << satelliteData.svid
                      << ", serverPredAvail: " << satelliteData.serverPredictionIsAvailable
                      << ", serverPredAgeSec: " << std::setw(7)
                      << satelliteData.serverPredictionAgeSeconds
                      << ", ephType: " << static_cast<uint32_t>(satelliteData.ephemerisType)
                      << ", ephSource: " << static_cast<uint32_t>(satelliteData.ephemerisSource)
                      << ", ephHealth: " << static_cast<uint32_t>(satelliteData.ephemerisHealth)
                      << ", ephAgeSec: " << std::setw(7) << satelliteData.ephemerisAgeSeconds
                      << std::endl;
    }
    return (jstring)env->NewStringUTF(internalState.str().c_str());
}

} // namespace android::gnss

#endif // _ANDROID_SERVER_GNSS_GNSSDEBUG_H
