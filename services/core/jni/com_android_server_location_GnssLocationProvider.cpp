/*
 * Copyright (C) 2008 The Android Open Source Project
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

// Define LOG_TAG and LOG_NDEBUG before <log/log.h> to overwrite the default values.
#define LOG_TAG "GnssLocationProviderJni"
#define LOG_NDEBUG 0

#include <android/hardware/gnss/1.0/IGnss.h>
#include <android/hardware/gnss/1.0/IGnssMeasurement.h>
#include <android/hardware/gnss/1.1/IGnss.h>
#include <android/hardware/gnss/1.1/IGnssMeasurement.h>
#include <android/hardware/gnss/2.0/IGnss.h>
#include <android/hardware/gnss/2.0/IGnssMeasurement.h>
#include <android/hardware/gnss/2.1/IGnss.h>
#include <android/hardware/gnss/2.1/IGnssAntennaInfo.h>
#include <android/hardware/gnss/2.1/IGnssMeasurement.h>
#include <android/hardware/gnss/BnGnss.h>
#include <android/hardware/gnss/BnGnssAntennaInfo.h>
#include <android/hardware/gnss/BnGnssCallback.h>
#include <android/hardware/gnss/BnGnssDebug.h>
#include <android/hardware/gnss/BnGnssGeofence.h>
#include <android/hardware/gnss/BnGnssGeofenceCallback.h>
#include <android/hardware/gnss/BnGnssMeasurementCallback.h>
#include <android/hardware/gnss/BnGnssPowerIndicationCallback.h>
#include <android/hardware/gnss/BnGnssPsdsCallback.h>
#include <binder/IServiceManager.h>
#include <nativehelper/JNIHelp.h>
#include <pthread.h>
#include <string.h>
#include <utils/SystemClock.h>

#include <cinttypes>
#include <iomanip>
#include <limits>

#include "android_runtime/AndroidRuntime.h"
#include "android_runtime/Log.h"
#include "gnss/AGnss.h"
#include "gnss/AGnssRil.h"
#include "gnss/GnssAntennaInfo.h"
#include "gnss/GnssAntennaInfoCallback.h"
#include "gnss/GnssBatching.h"
#include "gnss/GnssConfiguration.h"
#include "gnss/GnssDebug.h"
#include "gnss/GnssGeofence.h"
#include "gnss/GnssMeasurement.h"
#include "gnss/GnssNavigationMessage.h"
#include "gnss/GnssVisibilityControl.h"
#include "gnss/MeasurementCorrections.h"
#include "gnss/Utils.h"
#include "hardware_legacy/power.h"
#include "jni.h"
#include "utils/Log.h"
#include "utils/misc.h"

static jclass class_gnssPowerStats;

static jmethodID method_reportLocation;
static jmethodID method_reportStatus;
static jmethodID method_reportSvStatus;
static jmethodID method_reportNmea;
static jmethodID method_setTopHalCapabilities;
static jmethodID method_setGnssYearOfHardware;
static jmethodID method_setGnssHardwareModelName;
static jmethodID method_psdsDownloadRequest;
static jmethodID method_reportNiNotification;
static jmethodID method_requestLocation;
static jmethodID method_requestUtcTime;
static jmethodID method_reportGnssServiceDied;
static jmethodID method_reportGnssPowerStats;
static jmethodID method_reportNfwNotification;
static jmethodID method_isInEmergencySession;
static jmethodID method_gnssPowerStatsCtor;
static jmethodID method_setSubHalPowerIndicationCapabilities;

using android::OK;
using android::sp;
using android::status_t;
using android::String16;
using android::wp;
using android::binder::Status;
using android::gnss::GnssConfigurationInterface;

using android::hardware::Return;
using android::hardware::Void;
using android::hardware::hidl_vec;
using android::hardware::hidl_string;
using android::hardware::hidl_death_recipient;

using android::hardware::gnss::V1_0::GnssLocationFlags;
using android::hardware::gnss::V1_0::IGnssNavigationMessage;
using android::hardware::gnss::V1_0::IGnssNavigationMessageCallback;
using android::hardware::gnss::V1_0::IGnssNi;
using android::hardware::gnss::V1_0::IGnssNiCallback;
using android::hardware::gnss::V1_0::IGnssXtra;
using android::hardware::gnss::V1_0::IGnssXtraCallback;
using android::hardware::gnss::V2_0::ElapsedRealtimeFlags;

using android::hidl::base::V1_0::IBase;

using GnssConstellationType_V1_0 = android::hardware::gnss::V1_0::GnssConstellationType;
using GnssConstellationType_V2_0 = android::hardware::gnss::V2_0::GnssConstellationType;
using GnssLocation_V1_0 = android::hardware::gnss::V1_0::GnssLocation;
using GnssLocation_V2_0 = android::hardware::gnss::V2_0::GnssLocation;
using IGnss_V1_0 = android::hardware::gnss::V1_0::IGnss;
using IGnss_V1_1 = android::hardware::gnss::V1_1::IGnss;
using IGnss_V2_0 = android::hardware::gnss::V2_0::IGnss;
using IGnss_V2_1 = android::hardware::gnss::V2_1::IGnss;
using IGnssCallback_V1_0 = android::hardware::gnss::V1_0::IGnssCallback;
using IGnssCallback_V2_0 = android::hardware::gnss::V2_0::IGnssCallback;
using IGnssCallback_V2_1 = android::hardware::gnss::V2_1::IGnssCallback;

using android::hardware::gnss::BlocklistedSource;
using android::hardware::gnss::GnssConstellationType;
using android::hardware::gnss::GnssPowerStats;
using android::hardware::gnss::IGnssPowerIndication;
using android::hardware::gnss::IGnssPowerIndicationCallback;
using android::hardware::gnss::PsdsType;

using IAGnssAidl = android::hardware::gnss::IAGnss;
using IAGnssRilAidl = android::hardware::gnss::IAGnssRil;
using IGnssAidl = android::hardware::gnss::IGnss;
using IGnssCallbackAidl = android::hardware::gnss::IGnssCallback;
using IGnssBatchingAidl = android::hardware::gnss::IGnssBatching;
using IGnssDebugAidl = android::hardware::gnss::IGnssDebug;
using IGnssPsdsAidl = android::hardware::gnss::IGnssPsds;
using IGnssPsdsCallbackAidl = android::hardware::gnss::IGnssPsdsCallback;
using IGnssConfigurationAidl = android::hardware::gnss::IGnssConfiguration;
using GnssLocationAidl = android::hardware::gnss::GnssLocation;
using IGnssAntennaInfoAidl = android::hardware::gnss::IGnssAntennaInfo;

struct GnssDeathRecipient : virtual public hidl_death_recipient
{
    // hidl_death_recipient interface
    virtual void serviceDied(uint64_t cookie, const wp<IBase>& who) override {
        ALOGE("IGNSS hidl service failed, trying to recover...");

        JNIEnv* env = android::AndroidRuntime::getJNIEnv();
        env->CallVoidMethod(android::mCallbacksObj, method_reportGnssServiceDied);
    }
};

// Must match the value from GnssMeasurement.java
static const uint32_t SVID_FLAGS_HAS_BASEBAND_CN0 = (1<<4);

sp<GnssDeathRecipient> gnssHalDeathRecipient = nullptr;
sp<IGnss_V1_0> gnssHal = nullptr;
sp<IGnss_V1_1> gnssHal_V1_1 = nullptr;
sp<IGnss_V2_0> gnssHal_V2_0 = nullptr;
sp<IGnss_V2_1> gnssHal_V2_1 = nullptr;
sp<IGnssAidl> gnssHalAidl = nullptr;
sp<IGnssBatchingAidl> gnssBatchingAidlIface = nullptr;
sp<IGnssPsdsAidl> gnssPsdsAidlIface = nullptr;
sp<IGnssXtra> gnssXtraIface = nullptr;
sp<IGnssNi> gnssNiIface = nullptr;
sp<IGnssPowerIndication> gnssPowerIndicationIface = nullptr;

std::unique_ptr<GnssConfigurationInterface> gnssConfigurationIface = nullptr;
std::unique_ptr<android::gnss::GnssMeasurementInterface> gnssMeasurementIface = nullptr;
std::unique_ptr<android::gnss::GnssNavigationMessageInterface> gnssNavigationMessageIface = nullptr;
std::unique_ptr<android::gnss::GnssBatchingInterface> gnssBatchingIface = nullptr;
std::unique_ptr<android::gnss::GnssGeofenceInterface> gnssGeofencingIface = nullptr;
std::unique_ptr<android::gnss::AGnssInterface> agnssIface = nullptr;
std::unique_ptr<android::gnss::GnssDebugInterface> gnssDebugIface = nullptr;
std::unique_ptr<android::gnss::AGnssRilInterface> agnssRilIface = nullptr;
std::unique_ptr<android::gnss::GnssVisibilityControlInterface> gnssVisibilityControlIface = nullptr;
std::unique_ptr<android::gnss::GnssAntennaInfoInterface> gnssAntennaInfoIface = nullptr;
std::unique_ptr<android::gnss::MeasurementCorrectionsInterface> gnssMeasurementCorrectionsIface =
        nullptr;

#define WAKE_LOCK_NAME  "GPS"

namespace android {

namespace {

// Returns true if location has lat/long information.
bool hasLatLong(const GnssLocationAidl& location) {
    return (location.gnssLocationFlags & GnssLocationAidl::HAS_LAT_LONG) != 0;
}

// Returns true if location has lat/long information.
bool hasLatLong(const GnssLocation_V1_0& location) {
    return (static_cast<uint32_t>(location.gnssLocationFlags) &
            GnssLocationFlags::HAS_LAT_LONG) != 0;
}

// Returns true if location has lat/long information.
bool hasLatLong(const GnssLocation_V2_0& location) {
    return hasLatLong(location.v1_0);
}

bool isSvStatusRegistered = false;
bool isNmeaRegistered = false;

}  // namespace

static inline jboolean boolToJbool(bool value) {
    return value ? JNI_TRUE : JNI_FALSE;
}

static GnssLocationAidl createGnssLocation(jint gnssLocationFlags, jdouble latitudeDegrees,
                                           jdouble longitudeDegrees, jdouble altitudeMeters,
                                           jfloat speedMetersPerSec, jfloat bearingDegrees,
                                           jfloat horizontalAccuracyMeters,
                                           jfloat verticalAccuracyMeters,
                                           jfloat speedAccuracyMetersPerSecond,
                                           jfloat bearingAccuracyDegrees, jlong timestamp,
                                           jint elapsedRealtimeFlags, jlong elapsedRealtimeNanos,
                                           jdouble elapsedRealtimeUncertaintyNanos) {
    GnssLocationAidl location;
    location.gnssLocationFlags = static_cast<int>(gnssLocationFlags);
    location.latitudeDegrees = static_cast<double>(latitudeDegrees);
    location.longitudeDegrees = static_cast<double>(longitudeDegrees);
    location.altitudeMeters = static_cast<double>(altitudeMeters);
    location.speedMetersPerSec = static_cast<double>(speedMetersPerSec);
    location.bearingDegrees = static_cast<double>(bearingDegrees);
    location.horizontalAccuracyMeters = static_cast<double>(horizontalAccuracyMeters);
    location.verticalAccuracyMeters = static_cast<double>(verticalAccuracyMeters);
    location.speedAccuracyMetersPerSecond = static_cast<double>(speedAccuracyMetersPerSecond);
    location.bearingAccuracyDegrees = static_cast<double>(bearingAccuracyDegrees);
    location.timestampMillis = static_cast<uint64_t>(timestamp);

    location.elapsedRealtime.flags = static_cast<int>(elapsedRealtimeFlags);
    location.elapsedRealtime.timestampNs = static_cast<uint64_t>(elapsedRealtimeNanos);
    location.elapsedRealtime.timeUncertaintyNs =
            static_cast<double>(elapsedRealtimeUncertaintyNanos);

    return location;
}

static GnssLocation_V1_0 createGnssLocation_V1_0(
        jint gnssLocationFlags, jdouble latitudeDegrees, jdouble longitudeDegrees,
        jdouble altitudeMeters, jfloat speedMetersPerSec, jfloat bearingDegrees,
        jfloat horizontalAccuracyMeters, jfloat verticalAccuracyMeters,
        jfloat speedAccuracyMetersPerSecond, jfloat bearingAccuracyDegrees,
        jlong timestamp) {
    GnssLocation_V1_0 location;
    location.gnssLocationFlags = static_cast<uint16_t>(gnssLocationFlags);
    location.latitudeDegrees = static_cast<double>(latitudeDegrees);
    location.longitudeDegrees = static_cast<double>(longitudeDegrees);
    location.altitudeMeters = static_cast<double>(altitudeMeters);
    location.speedMetersPerSec = static_cast<float>(speedMetersPerSec);
    location.bearingDegrees = static_cast<float>(bearingDegrees);
    location.horizontalAccuracyMeters = static_cast<float>(horizontalAccuracyMeters);
    location.verticalAccuracyMeters = static_cast<float>(verticalAccuracyMeters);
    location.speedAccuracyMetersPerSecond = static_cast<float>(speedAccuracyMetersPerSecond);
    location.bearingAccuracyDegrees = static_cast<float>(bearingAccuracyDegrees);
    location.timestamp = static_cast<uint64_t>(timestamp);

    return location;
}

static GnssLocation_V2_0 createGnssLocation_V2_0(
        jint gnssLocationFlags, jdouble latitudeDegrees, jdouble longitudeDegrees,
        jdouble altitudeMeters, jfloat speedMetersPerSec, jfloat bearingDegrees,
        jfloat horizontalAccuracyMeters, jfloat verticalAccuracyMeters,
        jfloat speedAccuracyMetersPerSecond, jfloat bearingAccuracyDegrees,
        jlong timestamp, jint elapsedRealtimeFlags, jlong elapsedRealtimeNanos,
        jdouble elapsedRealtimeUncertaintyNanos) {
    GnssLocation_V2_0 location;
    location.v1_0 = createGnssLocation_V1_0(
            gnssLocationFlags, latitudeDegrees, longitudeDegrees, altitudeMeters,
            speedMetersPerSec, bearingDegrees, horizontalAccuracyMeters,
            verticalAccuracyMeters, speedAccuracyMetersPerSecond,
            bearingAccuracyDegrees, timestamp);

    location.elapsedRealtime.flags = static_cast<uint16_t>(elapsedRealtimeFlags);
    location.elapsedRealtime.timestampNs = static_cast<uint64_t>(elapsedRealtimeNanos);
    location.elapsedRealtime.timeUncertaintyNs = static_cast<uint64_t>(elapsedRealtimeUncertaintyNanos);

    return location;
}

/*
 * GnssCallback class implements the callback methods for IGnss interface.
 */
struct GnssCallback : public IGnssCallback_V2_1 {
    Return<void> gnssLocationCb(const GnssLocation_V1_0& location) override;
    Return<void> gnssStatusCb(const IGnssCallback_V1_0::GnssStatusValue status) override;
    Return<void> gnssSvStatusCb(const IGnssCallback_V1_0::GnssSvStatus& svStatus) override {
        return gnssSvStatusCbImpl<IGnssCallback_V1_0::GnssSvStatus, IGnssCallback_V1_0::GnssSvInfo>(
                svStatus);
    }
    Return<void> gnssNmeaCb(int64_t timestamp, const android::hardware::hidl_string& nmea) override;
    Return<void> gnssSetCapabilitesCb(uint32_t capabilities) override;
    Return<void> gnssAcquireWakelockCb() override;
    Return<void> gnssReleaseWakelockCb() override;
    Return<void> gnssRequestTimeCb() override;
    Return<void> gnssRequestLocationCb(const bool independentFromGnss) override;

    Return<void> gnssSetSystemInfoCb(const IGnssCallback_V1_0::GnssSystemInfo& info) override;

    // New in 1.1
    Return<void> gnssNameCb(const android::hardware::hidl_string& name) override;

    // New in 2.0
    Return<void> gnssRequestLocationCb_2_0(const bool independentFromGnss, const bool isUserEmergency)
            override;
    Return<void> gnssSetCapabilitiesCb_2_0(uint32_t capabilities) override;
    Return<void> gnssLocationCb_2_0(const GnssLocation_V2_0& location) override;
    Return<void> gnssSvStatusCb_2_0(const hidl_vec<IGnssCallback_V2_0::GnssSvInfo>& svInfoList) override {
        return gnssSvStatusCbImpl<hidl_vec<IGnssCallback_V2_0::GnssSvInfo>,
                                  IGnssCallback_V1_0::GnssSvInfo>(svInfoList);
    }

    // New in 2.1
    Return<void> gnssSvStatusCb_2_1(const hidl_vec<IGnssCallback_V2_1::GnssSvInfo>& svInfoList) override {
        return gnssSvStatusCbImpl<hidl_vec<IGnssCallback_V2_1::GnssSvInfo>,
                                  IGnssCallback_V1_0::GnssSvInfo>(svInfoList);
    }
    Return<void> gnssSetCapabilitiesCb_2_1(uint32_t capabilities) override;

    // TODO: Reconsider allocation cost vs threadsafety on these statics
    static const char* sNmeaString;
    static size_t sNmeaStringLength;

    template <class T>
    static Return<void> gnssLocationCbImpl(const T& location);

    template <class T_list, class T_sv_info>
    static Return<void> gnssSvStatusCbImpl(const T_list& svStatus);

private:
    template <class T>
    static uint32_t getHasBasebandCn0DbHzFlag(const T& svStatus) {
        return 0;
    }

    template <class T>
    static double getBasebandCn0DbHz(const T& svStatus, size_t i) {
        return 0.0;
    }

    template <class T>
    static uint32_t getGnssSvInfoListSize(const T& svInfoList) {
        return svInfoList.size();
    }

    static const IGnssCallbackAidl::GnssSvInfo& getGnssSvInfoOfIndex(
            const std::vector<IGnssCallbackAidl::GnssSvInfo>& svInfoList, size_t i) {
        return svInfoList[i];
    }

    static const IGnssCallback_V1_0::GnssSvInfo& getGnssSvInfoOfIndex(
            const IGnssCallback_V1_0::GnssSvStatus& svStatus, size_t i) {
        return svStatus.gnssSvList.data()[i];
    }

    static const IGnssCallback_V1_0::GnssSvInfo& getGnssSvInfoOfIndex(
            const hidl_vec<IGnssCallback_V2_0::GnssSvInfo>& svInfoList, size_t i) {
        return svInfoList[i].v1_0;
    }

    static const IGnssCallback_V1_0::GnssSvInfo& getGnssSvInfoOfIndex(
            const hidl_vec<IGnssCallback_V2_1::GnssSvInfo>& svInfoList, size_t i) {
        return svInfoList[i].v2_0.v1_0;
    }

    template <class T>
    static uint32_t getConstellationType(const T& svInfoList, size_t i) {
        return static_cast<uint32_t>(svInfoList[i].constellation);
    }
};

Return<void> GnssCallback::gnssNameCb(const android::hardware::hidl_string& name) {
    ALOGD("%s: name=%s\n", __func__, name.c_str());

    JNIEnv* env = getJniEnv();
    jstring jstringName = env->NewStringUTF(name.c_str());
    env->CallVoidMethod(mCallbacksObj, method_setGnssHardwareModelName, jstringName);
    if (jstringName) {
        env->DeleteLocalRef(jstringName);
    }
    checkAndClearExceptionFromCallback(env, __FUNCTION__);

    return Void();
}

const char* GnssCallback::sNmeaString = nullptr;
size_t GnssCallback::sNmeaStringLength = 0;

template<class T>
Return<void> GnssCallback::gnssLocationCbImpl(const T& location) {
    JNIEnv* env = getJniEnv();

    jobject jLocation = translateGnssLocation(env, location);

    env->CallVoidMethod(mCallbacksObj,
                        method_reportLocation,
                        boolToJbool(hasLatLong(location)),
                        jLocation);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    env->DeleteLocalRef(jLocation);
    return Void();
}

Return<void> GnssCallback::gnssLocationCb(const GnssLocation_V1_0& location) {
    return gnssLocationCbImpl<GnssLocation_V1_0>(location);
}

Return<void>
GnssCallback::gnssLocationCb_2_0(const GnssLocation_V2_0& location) {
    return gnssLocationCbImpl<GnssLocation_V2_0>(location);
}

Return<void> GnssCallback::gnssStatusCb(const IGnssCallback_V2_0::GnssStatusValue status) {
    JNIEnv* env = getJniEnv();
    env->CallVoidMethod(mCallbacksObj, method_reportStatus, status);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return Void();
}

template<>
uint32_t GnssCallback::getHasBasebandCn0DbHzFlag(const hidl_vec<IGnssCallback_V2_1::GnssSvInfo>&
        svStatus) {
    return SVID_FLAGS_HAS_BASEBAND_CN0;
}

template <>
uint32_t GnssCallback::getHasBasebandCn0DbHzFlag(
        const std::vector<IGnssCallbackAidl::GnssSvInfo>& svStatus) {
    return SVID_FLAGS_HAS_BASEBAND_CN0;
}

template <>
double GnssCallback::getBasebandCn0DbHz(
        const std::vector<IGnssCallbackAidl::GnssSvInfo>& svInfoList, size_t i) {
    return svInfoList[i].basebandCN0DbHz;
}

template<>
double GnssCallback::getBasebandCn0DbHz(const hidl_vec<IGnssCallback_V2_1::GnssSvInfo>& svInfoList,
        size_t i) {
    return svInfoList[i].basebandCN0DbHz;
}

template <>
uint32_t GnssCallback::getGnssSvInfoListSize(const IGnssCallback_V1_0::GnssSvStatus& svStatus) {
    return svStatus.numSvs;
}

template <>
uint32_t GnssCallback::getConstellationType(const IGnssCallback_V1_0::GnssSvStatus& svStatus,
                                            size_t i) {
    return static_cast<uint32_t>(svStatus.gnssSvList.data()[i].constellation);
}

template <>
uint32_t GnssCallback::getConstellationType(
        const hidl_vec<IGnssCallback_V2_1::GnssSvInfo>& svInfoList, size_t i) {
    return static_cast<uint32_t>(svInfoList[i].v2_0.constellation);
}

template <class T_list, class T_sv_info>
Return<void> GnssCallback::gnssSvStatusCbImpl(const T_list& svStatus) {
    // In HIDL or AIDL v1, if no listener is registered, do not report svInfoList to the framework.
    if (gnssHalAidl == nullptr || gnssHalAidl->getInterfaceVersion() <= 1) {
        if (!isSvStatusRegistered) {
            return Void();
        }
    }

    JNIEnv* env = getJniEnv();

    uint32_t listSize = getGnssSvInfoListSize(svStatus);

    jintArray svidWithFlagArray = env->NewIntArray(listSize);
    jfloatArray cn0Array = env->NewFloatArray(listSize);
    jfloatArray elevArray = env->NewFloatArray(listSize);
    jfloatArray azimArray = env->NewFloatArray(listSize);
    jfloatArray carrierFreqArray = env->NewFloatArray(listSize);
    jfloatArray basebandCn0Array = env->NewFloatArray(listSize);

    jint* svidWithFlags = env->GetIntArrayElements(svidWithFlagArray, 0);
    jfloat* cn0s = env->GetFloatArrayElements(cn0Array, 0);
    jfloat* elev = env->GetFloatArrayElements(elevArray, 0);
    jfloat* azim = env->GetFloatArrayElements(azimArray, 0);
    jfloat* carrierFreq = env->GetFloatArrayElements(carrierFreqArray, 0);
    jfloat* basebandCn0s = env->GetFloatArrayElements(basebandCn0Array, 0);

    /*
     * Read GNSS SV info.
     */
    for (size_t i = 0; i < listSize; ++i) {
        enum ShiftWidth: uint8_t {
            SVID_SHIFT_WIDTH = 12,
            CONSTELLATION_TYPE_SHIFT_WIDTH = 8
        };

        const T_sv_info& info = getGnssSvInfoOfIndex(svStatus, i);
        svidWithFlags[i] = (info.svid << SVID_SHIFT_WIDTH) |
            (getConstellationType(svStatus, i) << CONSTELLATION_TYPE_SHIFT_WIDTH) |
            static_cast<uint32_t>(info.svFlag);
        cn0s[i] = info.cN0Dbhz;
        elev[i] = info.elevationDegrees;
        azim[i] = info.azimuthDegrees;
        carrierFreq[i] = info.carrierFrequencyHz;
        svidWithFlags[i] |= getHasBasebandCn0DbHzFlag(svStatus);
        basebandCn0s[i] = getBasebandCn0DbHz(svStatus, i);
    }

    env->ReleaseIntArrayElements(svidWithFlagArray, svidWithFlags, 0);
    env->ReleaseFloatArrayElements(cn0Array, cn0s, 0);
    env->ReleaseFloatArrayElements(elevArray, elev, 0);
    env->ReleaseFloatArrayElements(azimArray, azim, 0);
    env->ReleaseFloatArrayElements(carrierFreqArray, carrierFreq, 0);
    env->ReleaseFloatArrayElements(basebandCn0Array, basebandCn0s, 0);

    env->CallVoidMethod(mCallbacksObj, method_reportSvStatus,
            static_cast<jint>(listSize), svidWithFlagArray, cn0Array, elevArray, azimArray,
            carrierFreqArray, basebandCn0Array);

    env->DeleteLocalRef(svidWithFlagArray);
    env->DeleteLocalRef(cn0Array);
    env->DeleteLocalRef(elevArray);
    env->DeleteLocalRef(azimArray);
    env->DeleteLocalRef(carrierFreqArray);
    env->DeleteLocalRef(basebandCn0Array);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return Void();
}

Return<void> GnssCallback::gnssNmeaCb(int64_t timestamp,
                                      const ::android::hardware::hidl_string& nmea) {
    // In HIDL, if no listener is registered, do not report nmea to the framework.
    if (!isNmeaRegistered) {
        return Void();
    }
    JNIEnv* env = getJniEnv();
    /*
     * The Java code will call back to read these values.
     * We do this to avoid creating unnecessary String objects.
     */
    sNmeaString = nmea.c_str();
    sNmeaStringLength = nmea.size();

    env->CallVoidMethod(mCallbacksObj, method_reportNmea, timestamp);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return Void();
}

Return<void> GnssCallback::gnssSetCapabilitesCb(uint32_t capabilities) {
    ALOGD("%s: %du\n", __func__, capabilities);

    JNIEnv* env = getJniEnv();
    env->CallVoidMethod(mCallbacksObj, method_setTopHalCapabilities, capabilities);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return Void();
}

Return<void> GnssCallback::gnssSetCapabilitiesCb_2_0(uint32_t capabilities) {
    return GnssCallback::gnssSetCapabilitesCb(capabilities);
}

Return<void> GnssCallback::gnssSetCapabilitiesCb_2_1(uint32_t capabilities) {
    return GnssCallback::gnssSetCapabilitesCb(capabilities);
}

Return<void> GnssCallback::gnssAcquireWakelockCb() {
    acquire_wake_lock(PARTIAL_WAKE_LOCK, WAKE_LOCK_NAME);
    return Void();
}

Return<void> GnssCallback::gnssReleaseWakelockCb() {
    release_wake_lock(WAKE_LOCK_NAME);
    return Void();
}

Return<void> GnssCallback::gnssRequestTimeCb() {
    JNIEnv* env = getJniEnv();
    env->CallVoidMethod(mCallbacksObj, method_requestUtcTime);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return Void();
}

Return<void> GnssCallback::gnssRequestLocationCb(const bool independentFromGnss) {
    return GnssCallback::gnssRequestLocationCb_2_0(independentFromGnss, /* isUserEmergency= */
            false);
}

Return<void> GnssCallback::gnssRequestLocationCb_2_0(const bool independentFromGnss, const bool
        isUserEmergency) {
    JNIEnv* env = getJniEnv();
    env->CallVoidMethod(mCallbacksObj, method_requestLocation, boolToJbool(independentFromGnss),
            boolToJbool(isUserEmergency));
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return Void();
}

Return<void> GnssCallback::gnssSetSystemInfoCb(const IGnssCallback_V2_0::GnssSystemInfo& info) {
    ALOGD("%s: yearOfHw=%d\n", __func__, info.yearOfHw);

    JNIEnv* env = getJniEnv();
    env->CallVoidMethod(mCallbacksObj, method_setGnssYearOfHardware,
                        info.yearOfHw);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return Void();
}

class GnssCallbackAidl : public android::hardware::gnss::BnGnssCallback {
public:
    Status gnssSetCapabilitiesCb(const int capabilities) override;
    Status gnssStatusCb(const GnssStatusValue status) override;
    Status gnssSvStatusCb(const std::vector<GnssSvInfo>& svInfoList) override;
    Status gnssLocationCb(const GnssLocationAidl& location) override;
    Status gnssNmeaCb(const int64_t timestamp, const std::string& nmea) override;
    Status gnssAcquireWakelockCb() override;
    Status gnssReleaseWakelockCb() override;
    Status gnssSetSystemInfoCb(const GnssSystemInfo& info) override;
    Status gnssRequestTimeCb() override;
    Status gnssRequestLocationCb(const bool independentFromGnss,
                                 const bool isUserEmergency) override;
};

Status GnssCallbackAidl::gnssSetCapabilitiesCb(const int capabilities) {
    ALOGD("GnssCallbackAidl::%s: %du\n", __func__, capabilities);
    JNIEnv* env = getJniEnv();
    env->CallVoidMethod(mCallbacksObj, method_setTopHalCapabilities, capabilities);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return Status::ok();
}

Status GnssCallbackAidl::gnssStatusCb(const GnssStatusValue status) {
    JNIEnv* env = getJniEnv();
    env->CallVoidMethod(mCallbacksObj, method_reportStatus, status);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return Status::ok();
}

Status GnssCallbackAidl::gnssSvStatusCb(const std::vector<GnssSvInfo>& svInfoList) {
    GnssCallback::gnssSvStatusCbImpl<std::vector<GnssSvInfo>, GnssSvInfo>(svInfoList);
    return Status::ok();
}

Status GnssCallbackAidl::gnssLocationCb(const GnssLocationAidl& location) {
    GnssCallback::gnssLocationCbImpl<GnssLocationAidl>(location);
    return Status::ok();
}

Status GnssCallbackAidl::gnssNmeaCb(const int64_t timestamp, const std::string& nmea) {
    // In AIDL v1, if no listener is registered, do not report nmea to the framework.
    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() <= 1) {
        if (!isNmeaRegistered) {
            return Status::ok();
        }
    }
    JNIEnv* env = getJniEnv();
    /*
     * The Java code will call back to read these values.
     * We do this to avoid creating unnecessary String objects.
     */
    GnssCallback::sNmeaString = nmea.c_str();
    GnssCallback::sNmeaStringLength = nmea.size();

    env->CallVoidMethod(mCallbacksObj, method_reportNmea, timestamp);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return Status::ok();
}

Status GnssCallbackAidl::gnssAcquireWakelockCb() {
    acquire_wake_lock(PARTIAL_WAKE_LOCK, WAKE_LOCK_NAME);
    return Status::ok();
}

Status GnssCallbackAidl::gnssReleaseWakelockCb() {
    release_wake_lock(WAKE_LOCK_NAME);
    return Status::ok();
}

Status GnssCallbackAidl::gnssSetSystemInfoCb(const GnssSystemInfo& info) {
    ALOGD("%s: yearOfHw=%d, name=%s\n", __func__, info.yearOfHw, info.name.c_str());
    JNIEnv* env = getJniEnv();
    env->CallVoidMethod(mCallbacksObj, method_setGnssYearOfHardware, info.yearOfHw);
    jstring jstringName = env->NewStringUTF(info.name.c_str());
    env->CallVoidMethod(mCallbacksObj, method_setGnssHardwareModelName, jstringName);
    if (jstringName) {
        env->DeleteLocalRef(jstringName);
    }
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return Status::ok();
}

Status GnssCallbackAidl::gnssRequestTimeCb() {
    JNIEnv* env = getJniEnv();
    env->CallVoidMethod(mCallbacksObj, method_requestUtcTime);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return Status::ok();
}

Status GnssCallbackAidl::gnssRequestLocationCb(const bool independentFromGnss,
                                               const bool isUserEmergency) {
    JNIEnv* env = getJniEnv();
    env->CallVoidMethod(mCallbacksObj, method_requestLocation, boolToJbool(independentFromGnss),
                        boolToJbool(isUserEmergency));
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return Status::ok();
}

/*
 * GnssPowerIndicationCallback class implements the callback methods for the IGnssPowerIndication
 * interface.
 */
struct GnssPowerIndicationCallback : public android::hardware::gnss::BnGnssPowerIndicationCallback {
public:
    Status setCapabilitiesCb(const int capabilities) override;
    Status gnssPowerStatsCb(const GnssPowerStats& data) override;
};

Status GnssPowerIndicationCallback::setCapabilitiesCb(const int capabilities) {
    ALOGD("GnssPowerIndicationCallback::%s: %du\n", __func__, capabilities);
    JNIEnv* env = getJniEnv();
    env->CallVoidMethod(mCallbacksObj, method_setSubHalPowerIndicationCapabilities, capabilities);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return Status::ok();
}

Status GnssPowerIndicationCallback::gnssPowerStatsCb(const GnssPowerStats& data) {
    JNIEnv* env = getJniEnv();

    int size = data.otherModesEnergyMilliJoule.size();
    jdoubleArray otherModesEnergy = env->NewDoubleArray(size);
    if (size > 0) {
        env->SetDoubleArrayRegion(otherModesEnergy, (jsize)0, size,
                                  &(data.otherModesEnergyMilliJoule[0]));
    }
    jobject gnssPowerStats =
            env->NewObject(class_gnssPowerStats, method_gnssPowerStatsCtor,
                           data.elapsedRealtime.flags, data.elapsedRealtime.timestampNs,
                           data.elapsedRealtime.timeUncertaintyNs, data.totalEnergyMilliJoule,
                           data.singlebandTrackingModeEnergyMilliJoule,
                           data.multibandTrackingModeEnergyMilliJoule,
                           data.singlebandAcquisitionModeEnergyMilliJoule,
                           data.multibandAcquisitionModeEnergyMilliJoule, otherModesEnergy);

    env->CallVoidMethod(mCallbacksObj, method_reportGnssPowerStats, gnssPowerStats);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    env->DeleteLocalRef(gnssPowerStats);
    env->DeleteLocalRef(otherModesEnergy);
    return Status::ok();
}

/*
 * GnssPsdsCallback class implements the callback methods for the IGnssPsds
 * interface.
 */
struct GnssPsdsCallbackAidl : public android::hardware::gnss::BnGnssPsdsCallback {
    Status downloadRequestCb(PsdsType psdsType) override {
        ALOGD("%s. psdsType: %d", __func__, static_cast<int32_t>(psdsType));
        JNIEnv* env = getJniEnv();
        env->CallVoidMethod(mCallbacksObj, method_psdsDownloadRequest, psdsType);
        checkAndClearExceptionFromCallback(env, __FUNCTION__);
        return Status::ok();
    }
};

/**
 * GnssXtraCallback class implements the callback methods for the IGnssXtra
 * interface.
 */
class GnssXtraCallback : public IGnssXtraCallback {
    Return<void> downloadRequestCb() override;
};

Return<void> GnssXtraCallback::downloadRequestCb() {
    JNIEnv* env = getJniEnv();
    env->CallVoidMethod(mCallbacksObj, method_psdsDownloadRequest, /* psdsType= */ 1);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return Void();
}

/*
 * GnssNiCallback implements callback methods required by the IGnssNi interface.
 */
struct GnssNiCallback : public IGnssNiCallback {
    Return<void> niNotifyCb(const IGnssNiCallback::GnssNiNotification& notification)
            override;
};

Return<void> GnssNiCallback::niNotifyCb(
        const IGnssNiCallback::GnssNiNotification& notification) {
    JNIEnv* env = getJniEnv();
    jstring requestorId = env->NewStringUTF(notification.requestorId.c_str());
    jstring text = env->NewStringUTF(notification.notificationMessage.c_str());

    if (requestorId && text) {
        env->CallVoidMethod(mCallbacksObj, method_reportNiNotification,
                            notification.notificationId, notification.niType,
                            notification.notifyFlags, notification.timeoutSec,
                            notification.defaultResponse, requestorId, text,
                            notification.requestorIdEncoding,
                            notification.notificationIdEncoding);
    } else {
        ALOGE("%s: OOM Error\n", __func__);
    }

    if (requestorId) {
        env->DeleteLocalRef(requestorId);
    }

    if (text) {
        env->DeleteLocalRef(text);
    }
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return Void();
}

/* Initializes the GNSS service handle. */
static void android_location_gnss_hal_GnssNative_set_gps_service_handle() {
    gnssHalAidl = waitForVintfService<IGnssAidl>();
    if (gnssHalAidl != nullptr) {
        ALOGD("Successfully got GNSS AIDL handle. Version=%d.", gnssHalAidl->getInterfaceVersion());
        if (gnssHalAidl->getInterfaceVersion() >= 2) {
            return;
        }
    }

    ALOGD("Trying IGnss_V2_1::getService()");
    gnssHal_V2_1 = IGnss_V2_1::getService();
    if (gnssHal_V2_1 != nullptr) {
        gnssHal = gnssHal_V2_1;
        gnssHal_V2_0 = gnssHal_V2_1;
        gnssHal_V1_1 = gnssHal_V2_1;
        gnssHal = gnssHal_V2_1;
        return;
    }

    ALOGD("gnssHal 2.1 was null, trying 2.0");
    gnssHal_V2_0 = IGnss_V2_0::getService();
    if (gnssHal_V2_0 != nullptr) {
        gnssHal = gnssHal_V2_0;
        gnssHal_V1_1 = gnssHal_V2_0;
        return;
    }

    ALOGD("gnssHal 2.0 was null, trying 1.1");
    gnssHal_V1_1 = IGnss_V1_1::getService();
    if (gnssHal_V1_1 != nullptr) {
        gnssHal = gnssHal_V1_1;
        return;
    }

    ALOGD("gnssHal 1.1 was null, trying 1.0");
    gnssHal = IGnss_V1_0::getService();
}

/* One time initialization at system boot */
static void android_location_gnss_hal_GnssNative_class_init_once(JNIEnv* env, jclass clazz) {
    // Initialize the top level gnss HAL handle.
    android_location_gnss_hal_GnssNative_set_gps_service_handle();

    // Cache methodIDs and class IDs.
    method_reportLocation = env->GetMethodID(clazz, "reportLocation",
            "(ZLandroid/location/Location;)V");
    method_reportStatus = env->GetMethodID(clazz, "reportStatus", "(I)V");
    method_reportSvStatus = env->GetMethodID(clazz, "reportSvStatus", "(I[I[F[F[F[F[F)V");
    method_reportNmea = env->GetMethodID(clazz, "reportNmea", "(J)V");
    method_setTopHalCapabilities = env->GetMethodID(clazz, "setTopHalCapabilities", "(I)V");
    method_setGnssYearOfHardware = env->GetMethodID(clazz, "setGnssYearOfHardware", "(I)V");
    method_setGnssHardwareModelName = env->GetMethodID(clazz, "setGnssHardwareModelName",
            "(Ljava/lang/String;)V");
    method_psdsDownloadRequest = env->GetMethodID(clazz, "psdsDownloadRequest", "(I)V");
    method_reportNiNotification = env->GetMethodID(clazz, "reportNiNotification",
            "(IIIIILjava/lang/String;Ljava/lang/String;II)V");
    method_requestLocation = env->GetMethodID(clazz, "requestLocation", "(ZZ)V");
    method_requestUtcTime = env->GetMethodID(clazz, "requestUtcTime", "()V");
    method_reportGnssServiceDied = env->GetMethodID(clazz, "reportGnssServiceDied", "()V");
    method_reportNfwNotification = env->GetMethodID(clazz, "reportNfwNotification",
            "(Ljava/lang/String;BLjava/lang/String;BLjava/lang/String;BZZ)V");
    method_reportGnssPowerStats =
            env->GetMethodID(clazz, "reportGnssPowerStats",
                             "(Lcom/android/server/location/gnss/GnssPowerStats;)V");
    method_isInEmergencySession = env->GetMethodID(clazz, "isInEmergencySession", "()Z");

    method_setSubHalPowerIndicationCapabilities =
            env->GetMethodID(clazz, "setSubHalPowerIndicationCapabilities", "(I)V");

    jclass gnssPowerStatsClass = env->FindClass("com/android/server/location/gnss/GnssPowerStats");
    class_gnssPowerStats = (jclass)env->NewGlobalRef(gnssPowerStatsClass);
    method_gnssPowerStatsCtor = env->GetMethodID(class_gnssPowerStats, "<init>", "(IJDDDDDD[D)V");

    gnss::GnssAntennaInfo_class_init_once(env, clazz);
    gnss::GnssBatching_class_init_once(env, clazz);
    gnss::GnssConfiguration_class_init_once(env);
    gnss::GnssGeofence_class_init_once(env, clazz);
    gnss::GnssMeasurement_class_init_once(env, clazz);
    gnss::GnssNavigationMessage_class_init_once(env, clazz);
    gnss::GnssVisibilityControl_class_init_once(env, clazz);
    gnss::MeasurementCorrections_class_init_once(env, clazz);
    gnss::MeasurementCorrectionsCallback_class_init_once(env, clazz);
    gnss::AGnss_class_init_once(env, clazz);
    gnss::AGnssRil_class_init_once(env, clazz);
    gnss::Utils_class_init_once(env);
}

/* Initialization needed at system boot and whenever GNSS service dies. */
static void android_location_gnss_hal_GnssNative_init_once(JNIEnv* env, jobject obj,
                                                           jboolean reinitializeGnssServiceHandle) {
    /*
     * Save a pointer to JVM.
     */
    jint jvmStatus = env->GetJavaVM(&android::ScopedJniThreadAttach::sJvm);
    if (jvmStatus != JNI_OK) {
        LOG_ALWAYS_FATAL("Unable to get Java VM. Error: %d", jvmStatus);
    }

    if (reinitializeGnssServiceHandle) {
        android_location_gnss_hal_GnssNative_set_gps_service_handle();
    }

    if (gnssHal == nullptr && gnssHalAidl == nullptr) {
        ALOGE("Unable to get GPS service\n");
        return;
    }

    // TODO: linkToDeath for AIDL HAL

    if (gnssHal != nullptr) {
        gnssHalDeathRecipient = new GnssDeathRecipient();
        hardware::Return<bool> linked = gnssHal->linkToDeath(gnssHalDeathRecipient, /*cookie*/ 0);
        if (!linked.isOk()) {
            ALOGE("Transaction error in linking to GnssHAL death: %s",
                  linked.description().c_str());
        } else if (!linked) {
            ALOGW("Unable to link to GnssHal death notifications");
        } else {
            ALOGD("Link to death notification successful");
        }
    }

    if (gnssHalAidl != nullptr) {
        sp<IGnssPsdsAidl> gnssPsdsAidl;
        auto status = gnssHalAidl->getExtensionPsds(&gnssPsdsAidl);
        if (status.isOk()) {
            gnssPsdsAidlIface = gnssPsdsAidl;
        } else {
            ALOGD("Unable to get a handle to PSDS AIDL interface.");
        }
    } else if (gnssHal != nullptr) {
        auto gnssXtra = gnssHal->getExtensionXtra();
        if (!gnssXtra.isOk()) {
            ALOGD("Unable to get a handle to Xtra");
        } else {
            gnssXtraIface = gnssXtra;
        }
    }

    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        sp<IAGnssRilAidl> agnssRilAidl;
        auto status = gnssHalAidl->getExtensionAGnssRil(&agnssRilAidl);
        if (checkAidlStatus(status, "Unable to get a handle to AGnssRil interface.")) {
            agnssRilIface = std::make_unique<gnss::AGnssRil>(agnssRilAidl);
        }
    } else if (gnssHal_V2_0 != nullptr) {
        auto agnssRil_V2_0 = gnssHal_V2_0->getExtensionAGnssRil_2_0();
        if (checkHidlReturn(agnssRil_V2_0, "Unable to get a handle to AGnssRil_V2_0")) {
            agnssRilIface = std::make_unique<gnss::AGnssRil_V2_0>(agnssRil_V2_0);
        }
    } else if (gnssHal != nullptr) {
        auto agnssRil_V1_0 = gnssHal->getExtensionAGnssRil();
        if (checkHidlReturn(agnssRil_V1_0, "Unable to get a handle to AGnssRil_V1_0")) {
            agnssRilIface = std::make_unique<gnss::AGnssRil_V1_0>(agnssRil_V1_0);
        }
    }

    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        sp<IAGnssAidl> agnssAidl;
        auto status = gnssHalAidl->getExtensionAGnss(&agnssAidl);
        if (checkAidlStatus(status, "Unable to get a handle to AGnss interface.")) {
            agnssIface = std::make_unique<gnss::AGnss>(agnssAidl);
        }
    } else if (gnssHal_V2_0 != nullptr) {
        auto agnss_V2_0 = gnssHal_V2_0->getExtensionAGnss_2_0();
        if (checkHidlReturn(agnss_V2_0, "Unable to get a handle to AGnss_V2_0")) {
            agnssIface = std::make_unique<gnss::AGnss_V2_0>(agnss_V2_0);
        }
    } else if (gnssHal != nullptr) {
        auto agnss_V1_0 = gnssHal->getExtensionAGnss();
        if (checkHidlReturn(agnss_V1_0, "Unable to get a handle to AGnss_V1_0")) {
            agnssIface = std::make_unique<gnss::AGnss_V1_0>(agnss_V1_0);
        }
    }

    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        sp<hardware::gnss::IGnssNavigationMessageInterface> gnssNavigationMessage;
        auto status = gnssHalAidl->getExtensionGnssNavigationMessage(&gnssNavigationMessage);
        if (checkAidlStatus(status,
                            "Unable to get a handle to GnssNavigationMessage AIDL interface.")) {
            gnssNavigationMessageIface =
                    std::make_unique<gnss::GnssNavigationMessageAidl>(gnssNavigationMessage);
        }
    } else if (gnssHal != nullptr) {
        auto gnssNavigationMessage = gnssHal->getExtensionGnssNavigationMessage();
        if (checkHidlReturn(gnssNavigationMessage,
                            "Unable to get a handle to GnssNavigationMessage interface.")) {
            gnssNavigationMessageIface =
                    std::make_unique<gnss::GnssNavigationMessageHidl>(gnssNavigationMessage);
        }
    }

    // Allow all causal combinations between IGnss.hal and IGnssMeasurement.hal. That means,
    // 2.1@IGnss can be paired with {1.0, 1,1, 2.0, 2.1}@IGnssMeasurement
    // 2.0@IGnss can be paired with {1.0, 1,1, 2.0}@IGnssMeasurement
    // 1.1@IGnss can be paired {1.0, 1.1}@IGnssMeasurement
    // 1.0@IGnss is paired with 1.0@IGnssMeasurement
    gnssMeasurementIface = nullptr;
    if (gnssHalAidl != nullptr) {
        sp<hardware::gnss::IGnssMeasurementInterface> gnssMeasurement;
        auto status = gnssHalAidl->getExtensionGnssMeasurement(&gnssMeasurement);
        if (checkAidlStatus(status, "Unable to get a handle to GnssMeasurement AIDL interface.")) {
            gnssMeasurementIface =
                    std::make_unique<android::gnss::GnssMeasurement>(gnssMeasurement);
        }
    }
    if (gnssHal_V2_1 != nullptr && gnssMeasurementIface == nullptr) {
        auto gnssMeasurement = gnssHal_V2_1->getExtensionGnssMeasurement_2_1();
        if (checkHidlReturn(gnssMeasurement, "Unable to get a handle to GnssMeasurement_V2_1")) {
            gnssMeasurementIface =
                    std::make_unique<android::gnss::GnssMeasurement_V2_1>(gnssMeasurement);
        }
    }
    if (gnssHal_V2_0 != nullptr && gnssMeasurementIface == nullptr) {
        auto gnssMeasurement = gnssHal_V2_0->getExtensionGnssMeasurement_2_0();
        if (checkHidlReturn(gnssMeasurement, "Unable to get a handle to GnssMeasurement_V2_0")) {
            gnssMeasurementIface =
                    std::make_unique<android::gnss::GnssMeasurement_V2_0>(gnssMeasurement);
        }
    }
    if (gnssHal_V1_1 != nullptr && gnssMeasurementIface == nullptr) {
        auto gnssMeasurement = gnssHal_V1_1->getExtensionGnssMeasurement_1_1();
        if (checkHidlReturn(gnssMeasurement, "Unable to get a handle to GnssMeasurement_V1_1")) {
            gnssMeasurementIface =
                    std::make_unique<android::gnss::GnssMeasurement_V1_1>(gnssMeasurement);
        }
    }
    if (gnssHal != nullptr && gnssMeasurementIface == nullptr) {
        auto gnssMeasurement = gnssHal->getExtensionGnssMeasurement();
        if (checkHidlReturn(gnssMeasurement, "Unable to get a handle to GnssMeasurement_V1_0")) {
            gnssMeasurementIface =
                    std::make_unique<android::gnss::GnssMeasurement_V1_0>(gnssMeasurement);
        }
    }

    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        sp<IGnssAntennaInfoAidl> gnssAntennaInfoAidl;
        auto status = gnssHalAidl->getExtensionGnssAntennaInfo(&gnssAntennaInfoAidl);
        if (checkAidlStatus(status, "Unable to get a handle to GnssAntennaInfo interface.")) {
            gnssAntennaInfoIface = std::make_unique<gnss::GnssAntennaInfoAidl>(gnssAntennaInfoAidl);
        }
    } else if (gnssHal_V2_1 != nullptr) {
        auto gnssAntennaInfo_V2_1 = gnssHal_V2_1->getExtensionGnssAntennaInfo();
        if (checkHidlReturn(gnssAntennaInfo_V2_1,
                            "Unable to get a handle to GnssAntennaInfo_V2_1")) {
            gnssAntennaInfoIface =
                    std::make_unique<gnss::GnssAntennaInfo_V2_1>(gnssAntennaInfo_V2_1);
        }
    }

    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        sp<android::hardware::gnss::measurement_corrections::IMeasurementCorrectionsInterface>
                gnssMeasurementCorrectionsAidl;
        auto status =
                gnssHalAidl->getExtensionMeasurementCorrections(&gnssMeasurementCorrectionsAidl);
        if (checkAidlStatus(status,
                            "Unable to get a handle to GnssVisibilityControl AIDL interface.")) {
            gnssMeasurementCorrectionsIface =
                    std::make_unique<gnss::MeasurementCorrectionsIface_Aidl>(
                            gnssMeasurementCorrectionsAidl);
        }
    }
    if (gnssHal_V2_1 != nullptr && gnssMeasurementCorrectionsIface == nullptr) {
        auto gnssCorrections = gnssHal_V2_1->getExtensionMeasurementCorrections_1_1();
        if (checkHidlReturn(gnssCorrections,
                            "Unable to get a handle to GnssMeasurementCorrections HIDL "
                            "interface")) {
            gnssMeasurementCorrectionsIface =
                    std::make_unique<gnss::MeasurementCorrectionsIface_V1_1>(gnssCorrections);
        }
    }
    if (gnssHal_V2_0 != nullptr && gnssMeasurementCorrectionsIface == nullptr) {
        auto gnssCorrections = gnssHal_V2_0->getExtensionMeasurementCorrections();
        if (checkHidlReturn(gnssCorrections,
                            "Unable to get a handle to GnssMeasurementCorrections HIDL "
                            "interface")) {
            gnssMeasurementCorrectionsIface =
                    std::make_unique<gnss::MeasurementCorrectionsIface_V1_0>(gnssCorrections);
        }
    }

    // Allow all causal combinations between IGnss.hal and IGnssDebug.hal. That means,
    // 2.0@IGnss can be paired with {1.0, 2.0}@IGnssDebug
    // 1.0@IGnss is paired with 1.0@IGnssDebug

    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        sp<IGnssDebugAidl> gnssDebugAidl;
        auto status = gnssHalAidl->getExtensionGnssDebug(&gnssDebugAidl);
        if (checkAidlStatus(status, "Unable to get a handle to GnssDebug interface.")) {
            gnssDebugIface = std::make_unique<gnss::GnssDebug>(gnssDebugAidl);
        }
    }
    if (gnssHal_V2_0 != nullptr && gnssDebugIface == nullptr) {
        auto gnssDebug_V2_0 = gnssHal_V2_0->getExtensionGnssDebug_2_0();
        if (checkHidlReturn(gnssDebug_V2_0, "Unable to get a handle to GnssDebug_V2_0.")) {
            gnssDebugIface = std::make_unique<gnss::GnssDebug_V2_0>(gnssDebug_V2_0);
        }
    }
    if (gnssHal != nullptr && gnssDebugIface == nullptr) {
        auto gnssDebug_V1_0 = gnssHal->getExtensionGnssDebug();
        if (checkHidlReturn(gnssDebug_V1_0, "Unable to get a handle to GnssDebug_V1_0.")) {
            gnssDebugIface = std::make_unique<gnss::GnssDebug_V1_0>(gnssDebug_V1_0);
        }
    }

    if (gnssHal != nullptr) {
        auto gnssNi = gnssHal->getExtensionGnssNi();
        if (!gnssNi.isOk()) {
            ALOGD("Unable to get a handle to GnssNi");
        } else {
            gnssNiIface = gnssNi;
        }
    }

    if (gnssHalAidl != nullptr) {
        sp<IGnssConfigurationAidl> gnssConfigurationAidl;
        auto status = gnssHalAidl->getExtensionGnssConfiguration(&gnssConfigurationAidl);
        if (checkAidlStatus(status,
                            "Unable to get a handle to GnssConfiguration AIDL interface.")) {
            gnssConfigurationIface =
                    std::make_unique<android::gnss::GnssConfiguration>(gnssConfigurationAidl);
        }
    } else if (gnssHal_V2_1 != nullptr) {
        auto gnssConfiguration = gnssHal_V2_1->getExtensionGnssConfiguration_2_1();
        if (checkHidlReturn(gnssConfiguration,
                            "Unable to get a handle to GnssConfiguration_V2_1")) {
            gnssConfigurationIface =
                    std::make_unique<android::gnss::GnssConfiguration_V2_1>(gnssConfiguration);
        }
    } else if (gnssHal_V2_0 != nullptr) {
        auto gnssConfiguration = gnssHal_V2_0->getExtensionGnssConfiguration_2_0();
        if (checkHidlReturn(gnssConfiguration,
                            "Unable to get a handle to GnssConfiguration_V2_0")) {
            gnssConfigurationIface =
                    std::make_unique<android::gnss::GnssConfiguration_V2_0>(gnssConfiguration);
        }
    } else if (gnssHal_V1_1 != nullptr) {
        auto gnssConfiguration = gnssHal_V1_1->getExtensionGnssConfiguration_1_1();
        if (checkHidlReturn(gnssConfiguration,
                            "Unable to get a handle to GnssConfiguration_V1_1")) {
            gnssConfigurationIface =
                    std::make_unique<gnss::GnssConfiguration_V1_1>(gnssConfiguration);
        }
    } else if (gnssHal != nullptr) {
        auto gnssConfiguration = gnssHal->getExtensionGnssConfiguration();
        if (checkHidlReturn(gnssConfiguration,
                            "Unable to get a handle to GnssConfiguration_V1_0")) {
            gnssConfigurationIface =
                    std::make_unique<gnss::GnssConfiguration_V1_0>(gnssConfiguration);
        }
    }

    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        sp<hardware::gnss::IGnssGeofence> gnssGeofence;
        auto status = gnssHalAidl->getExtensionGnssGeofence(&gnssGeofence);
        if (checkAidlStatus(status, "Unable to get a handle to GnssGeofence AIDL interface.")) {
            gnssGeofencingIface = std::make_unique<gnss::GnssGeofenceAidl>(gnssGeofence);
        }
    } else if (gnssHal != nullptr) {
        auto gnssGeofencing = gnssHal->getExtensionGnssGeofencing();
        if (checkHidlReturn(gnssGeofencing, "Unable to get a handle to GnssGeofencing")) {
            gnssGeofencingIface = std::make_unique<gnss::GnssGeofenceHidl>(gnssGeofencing);
        }
    }

    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        sp<android::hardware::gnss::IGnssBatching> gnssBatchingAidl;
        auto status = gnssHalAidl->getExtensionGnssBatching(&gnssBatchingAidl);
        if (checkAidlStatus(status, "Unable to get a handle to GnssBatching interface.")) {
            gnssBatchingIface = std::make_unique<gnss::GnssBatching>(gnssBatchingAidl);
        }
    } else if (gnssHal_V2_0 != nullptr) {
        auto gnssBatching_V2_0 = gnssHal_V2_0->getExtensionGnssBatching_2_0();
        if (checkHidlReturn(gnssBatching_V2_0, "Unable to get a handle to GnssBatching_V2_0")) {
            gnssBatchingIface = std::make_unique<gnss::GnssBatching_V2_0>(gnssBatching_V2_0);
        }
    }
    if (gnssHal != nullptr && gnssBatchingIface == nullptr) {
        auto gnssBatching_V1_0 = gnssHal->getExtensionGnssBatching();
        if (checkHidlReturn(gnssBatching_V1_0, "Unable to get a handle to GnssBatching")) {
            gnssBatchingIface = std::make_unique<gnss::GnssBatching_V1_0>(gnssBatching_V1_0);
        }
    }

    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        sp<android::hardware::gnss::visibility_control::IGnssVisibilityControl>
                gnssVisibilityControlAidl;
        auto status = gnssHalAidl->getExtensionGnssVisibilityControl(&gnssVisibilityControlAidl);
        if (checkAidlStatus(status,
                            "Unable to get a handle to GnssVisibilityControl AIDL interface.")) {
            gnssVisibilityControlIface =
                    std::make_unique<gnss::GnssVisibilityControlAidl>(gnssVisibilityControlAidl);
        }
    } else if (gnssHal_V2_0 != nullptr) {
        auto gnssVisibilityControlHidl = gnssHal_V2_0->getExtensionVisibilityControl();
        if (checkHidlReturn(gnssVisibilityControlHidl,
                            "Unable to get a handle to GnssVisibilityControl HIDL interface")) {
            gnssVisibilityControlIface =
                    std::make_unique<gnss::GnssVisibilityControlHidl>(gnssVisibilityControlHidl);
        }
    }

    if (gnssHalAidl != nullptr) {
        sp<IGnssPowerIndication> gnssPowerIndication;
        auto status = gnssHalAidl->getExtensionGnssPowerIndication(&gnssPowerIndication);
        if (checkAidlStatus(status, "Unable to get a handle to GnssPowerIndication interface.")) {
            gnssPowerIndicationIface = gnssPowerIndication;
        }
    }

    if (mCallbacksObj) {
        ALOGE("Callbacks already initialized");
    } else {
        mCallbacksObj = env->NewGlobalRef(obj);
    }
}

static jboolean android_location_gnss_hal_GnssNative_is_supported(JNIEnv* /* env */, jclass) {
    return (gnssHalAidl != nullptr || gnssHal != nullptr) ? JNI_TRUE : JNI_FALSE;
}

static jboolean android_location_GnssNetworkConnectivityHandler_is_agps_ril_supported(
        JNIEnv* /* env */, jclass /* clazz */) {
    return (agnssRilIface != nullptr) ? JNI_TRUE : JNI_FALSE;
}

static jobject android_location_GnssConfiguration_get_gnss_configuration_version(
        JNIEnv* env, jclass /* jclazz */) {
    if (gnssConfigurationIface == nullptr) {
        return nullptr;
    }
    return gnssConfigurationIface->getVersion(env);
}

/* Initialization needed each time the GPS service is shutdown. */
static jboolean android_location_gnss_hal_GnssNative_init(JNIEnv* /* env */, jclass) {
    /*
     * This must be set before calling into the HAL library.
     */
    if (!mCallbacksObj) {
        ALOGE("No callbacks set during GNSS HAL initialization.");
        return JNI_FALSE;
    }

    /*
     * Fail if the main interface fails to initialize
     */
    if (gnssHal == nullptr && gnssHalAidl == nullptr) {
        ALOGE("Unable to initialize GNSS HAL.");
        return JNI_FALSE;
    }

    // Set top level IGnss.hal callback.
    if (gnssHal != nullptr) {
        Return<bool> result = false;
        sp<IGnssCallback_V2_1> gnssCbIface = new GnssCallback();
        if (gnssHal_V2_1 != nullptr) {
            result = gnssHal_V2_1->setCallback_2_1(gnssCbIface);
        } else if (gnssHal_V2_0 != nullptr) {
            result = gnssHal_V2_0->setCallback_2_0(gnssCbIface);
        } else if (gnssHal_V1_1 != nullptr) {
            result = gnssHal_V1_1->setCallback_1_1(gnssCbIface);
        } else {
            result = gnssHal->setCallback(gnssCbIface);
        }
        if (!checkHidlReturn(result, "IGnss setCallback() failed.")) {
            return JNI_FALSE;
        }
    }

    if (gnssHalAidl != nullptr) {
        sp<IGnssCallbackAidl> gnssCbIfaceAidl = new GnssCallbackAidl();
        auto status = gnssHalAidl->setCallback(gnssCbIfaceAidl);
        if (!checkAidlStatus(status, "IGnssAidl setCallback() failed.")) {
            return JNI_FALSE;
        }
    }

    // Set IGnssPsds or IGnssXtra callback.
    if (gnssPsdsAidlIface != nullptr) {
        sp<IGnssPsdsCallbackAidl> gnssPsdsCallbackAidl = new GnssPsdsCallbackAidl();
        auto status = gnssPsdsAidlIface->setCallback(gnssPsdsCallbackAidl);
        if (!checkAidlStatus(status, "IGnssPsdsAidl setCallback() failed.")) {
            gnssPsdsAidlIface = nullptr;
        }
    } else if (gnssXtraIface != nullptr) {
        sp<IGnssXtraCallback> gnssXtraCbIface = new GnssXtraCallback();
        auto result = gnssXtraIface->setCallback(gnssXtraCbIface);
        if (!checkHidlReturn(result, "IGnssXtra setCallback() failed.")) {
            gnssXtraIface = nullptr;
        } else {
            ALOGI("Unable to initialize IGnssXtra interface.");
        }
    }

    // Set IAGnss callback.
    if (agnssIface == nullptr ||
        !agnssIface->setCallback(std::make_unique<gnss::AGnssCallback>())) {
        ALOGI("Unable to initialize IAGnss interface.");
    }

    // Set GnssGeofence callback.
    if (gnssGeofencingIface != nullptr) {
        gnssGeofencingIface->setCallback(std::make_unique<gnss::GnssGeofenceCallback>());
    } else {
        ALOGI("Unable to initialize IGnssGeofencing interface.");
    }

    // Set IGnssNi.hal callback.
    sp<IGnssNiCallback> gnssNiCbIface = new GnssNiCallback();
    if (gnssNiIface != nullptr) {
        auto status = gnssNiIface->setCallback(gnssNiCbIface);
        checkHidlReturn(status, "IGnssNi setCallback() failed.");
    } else {
        ALOGI("Unable to initialize IGnssNi interface.");
    }

    // Set IAGnssRil callback.
    if (agnssRilIface == nullptr ||
        !agnssRilIface->setCallback(std::make_unique<gnss::AGnssRilCallback>())) {
        ALOGI("Unable to initialize IAGnssRil interface.");
    }

    // Set IGnssVisibilityControl callback.
    if (gnssVisibilityControlIface != nullptr) {
        gnssVisibilityControlIface->setCallback(
                std::make_unique<gnss::GnssVisibilityControlCallback>());
    } else {
        ALOGI("Unable to initialize IGnssVisibilityControl interface.");
    }

    // Set IMeasurementCorrection callback.
    if (gnssMeasurementCorrectionsIface == nullptr ||
        !gnssMeasurementCorrectionsIface->setCallback(
                std::make_unique<gnss::MeasurementCorrectionsCallback>())) {
        ALOGI("Unable to initialize IGnssMeasurementCorrections interface.");
    }

    // Set IGnssPowerIndication.hal callback.
    if (gnssPowerIndicationIface != nullptr) {
        sp<IGnssPowerIndicationCallback> gnssPowerIndicationCallback =
                new GnssPowerIndicationCallback();
        auto status = gnssPowerIndicationIface->setCallback(gnssPowerIndicationCallback);
        if (!checkAidlStatus(status, "IGnssPowerIndication setCallback() failed.")) {
            gnssPowerIndicationIface = nullptr;
        }
    }

    return JNI_TRUE;
}

static void android_location_gnss_hal_GnssNative_cleanup(JNIEnv* /* env */, jclass) {
    if (gnssHalAidl != nullptr) {
        auto status = gnssHalAidl->close();
        checkAidlStatus(status, "IGnssAidl close() failed.");
    }

    if (gnssHal != nullptr) {
        auto result = gnssHal->cleanup();
        checkHidlReturn(result, "IGnss cleanup() failed.");
    }
}

static jboolean android_location_gnss_hal_GnssNative_set_position_mode(
        JNIEnv* /* env */, jclass, jint mode, jint recurrence, jint min_interval,
        jint preferred_accuracy, jint preferred_time, jboolean low_power_mode) {
    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        IGnssAidl::PositionModeOptions options;
        options.mode = static_cast<IGnssAidl::GnssPositionMode>(mode);
        options.recurrence = static_cast<IGnssAidl::GnssPositionRecurrence>(recurrence);
        options.minIntervalMs = min_interval;
        options.preferredAccuracyMeters = preferred_accuracy;
        options.preferredTimeMs = preferred_time;
        options.lowPowerMode = low_power_mode;
        auto status = gnssHalAidl->setPositionMode(options);
        return checkAidlStatus(status, "IGnssAidl setPositionMode() failed.");
    }

    Return<bool> result = false;
    if (gnssHal_V1_1 != nullptr) {
         result = gnssHal_V1_1->setPositionMode_1_1(static_cast<IGnss_V1_0::GnssPositionMode>(mode),
                 static_cast<IGnss_V1_0::GnssPositionRecurrence>(recurrence),
                 min_interval,
                 preferred_accuracy,
                 preferred_time,
                 low_power_mode);
     } else if (gnssHal != nullptr) {
         result = gnssHal->setPositionMode(static_cast<IGnss_V1_0::GnssPositionMode>(mode),
                 static_cast<IGnss_V1_0::GnssPositionRecurrence>(recurrence),
                 min_interval,
                 preferred_accuracy,
                 preferred_time);
    }

    return checkHidlReturn(result, "IGnss setPositionMode() failed.");
}

static jboolean android_location_gnss_hal_GnssNative_start(JNIEnv* /* env */, jclass) {
    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        auto status = gnssHalAidl->start();
        return checkAidlStatus(status, "IGnssAidl start() failed.");
    }

    if (gnssHal == nullptr) {
        return JNI_FALSE;
    }

    auto result = gnssHal->start();
    return checkHidlReturn(result, "IGnss start() failed.");
}

static jboolean android_location_gnss_hal_GnssNative_stop(JNIEnv* /* env */, jclass) {
    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        auto status = gnssHalAidl->stop();
        return checkAidlStatus(status, "IGnssAidl stop() failed.");
    }

    if (gnssHal == nullptr) {
        return JNI_FALSE;
    }

    auto result = gnssHal->stop();
    return checkHidlReturn(result, "IGnss stop() failed.");
}

static jboolean android_location_gnss_hal_GnssNative_start_sv_status_collection(JNIEnv* /* env */,
                                                                                jclass) {
    isSvStatusRegistered = true;
    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        auto status = gnssHalAidl->startSvStatus();
        return checkAidlStatus(status, "IGnssAidl startSvStatus() failed.");
    }
    if (gnssHal == nullptr) {
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

static jboolean android_location_gnss_hal_GnssNative_stop_sv_status_collection(JNIEnv* /* env */,
                                                                               jclass) {
    isSvStatusRegistered = false;
    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        auto status = gnssHalAidl->stopSvStatus();
        return checkAidlStatus(status, "IGnssAidl stopSvStatus() failed.");
    }
    if (gnssHal == nullptr) {
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

static jboolean android_location_gnss_hal_GnssNative_start_nmea_message_collection(
        JNIEnv* /* env */, jclass) {
    isNmeaRegistered = true;
    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        auto status = gnssHalAidl->startNmea();
        return checkAidlStatus(status, "IGnssAidl startNmea() failed.");
    }
    if (gnssHal == nullptr) {
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

static jboolean android_location_gnss_hal_GnssNative_stop_nmea_message_collection(JNIEnv* /* env */,
                                                                                  jclass) {
    isNmeaRegistered = false;
    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        auto status = gnssHalAidl->stopNmea();
        return checkAidlStatus(status, "IGnssAidl stopNmea() failed.");
    }
    if (gnssHal == nullptr) {
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

static void android_location_gnss_hal_GnssNative_delete_aiding_data(JNIEnv* /* env */, jclass,
                                                                    jint flags) {
    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        auto status = gnssHalAidl->deleteAidingData(static_cast<IGnssAidl::GnssAidingData>(flags));
        checkAidlStatus(status, "IGnssAidl deleteAidingData() failed.");
        return;
    }

    if (gnssHal == nullptr) {
        return;
    }

    auto result = gnssHal->deleteAidingData(static_cast<IGnss_V1_0::GnssAidingData>(flags));
    checkHidlReturn(result, "IGnss deleteAidingData() failed.");
}

static void android_location_gnss_hal_GnssNative_agps_set_reference_location_cellid(
        JNIEnv* /* env */, jclass, jint type, jint mcc, jint mnc, jint lac, jlong cid, jint tac,
        jint pcid, jint arfcn) {
    if (agnssRilIface == nullptr) {
        ALOGE("%s: IAGnssRil interface not available.", __func__);
        return;
    }
    agnssRilIface->setRefLocation(type, mcc, mnc, lac, cid, tac, pcid, arfcn);
}

static void android_location_gnss_hal_GnssNative_agps_set_id(JNIEnv* env, jclass, jint type,
                                                             jstring setid_string) {
    if (agnssRilIface == nullptr) {
        ALOGE("%s: IAGnssRil interface not available.", __func__);
        return;
    }
    agnssRilIface->setSetId(type, setid_string);
}

static jint android_location_gnss_hal_GnssNative_read_nmea(JNIEnv* env, jclass,
                                                           jbyteArray nmeaArray, jint buffer_size) {
    // this should only be called from within a call to reportNmea
    jbyte* nmea = reinterpret_cast<jbyte *>(env->GetPrimitiveArrayCritical(nmeaArray, 0));
    int length = GnssCallback::sNmeaStringLength;
    if (length > buffer_size)
        length = buffer_size;
    memcpy(nmea, GnssCallback::sNmeaString, length);
    env->ReleasePrimitiveArrayCritical(nmeaArray, nmea, JNI_ABORT);
    return (jint) length;
}

static void android_location_gnss_hal_GnssNative_inject_time(JNIEnv* /* env */, jclass, jlong time,
                                                             jlong timeReference,
                                                             jint uncertainty) {
    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        auto status = gnssHalAidl->injectTime(time, timeReference, uncertainty);
        checkAidlStatus(status, "IGnssAidl injectTime() failed.");
        return;
    }

    if (gnssHal == nullptr) {
        return;
    }
    auto result = gnssHal->injectTime(time, timeReference, uncertainty);
    checkHidlReturn(result, "IGnss injectTime() failed.");
}

static void android_location_gnss_hal_GnssNative_inject_best_location(
        JNIEnv* /* env */, jclass, jint gnssLocationFlags, jdouble latitudeDegrees,
        jdouble longitudeDegrees, jdouble altitudeMeters, jfloat speedMetersPerSec,
        jfloat bearingDegrees, jfloat horizontalAccuracyMeters, jfloat verticalAccuracyMeters,
        jfloat speedAccuracyMetersPerSecond, jfloat bearingAccuracyDegrees, jlong timestamp,
        jint elapsedRealtimeFlags, jlong elapsedRealtimeNanos,
        jdouble elapsedRealtimeUncertaintyNanos) {
    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        GnssLocationAidl location =
                createGnssLocation(gnssLocationFlags, latitudeDegrees, longitudeDegrees,
                                   altitudeMeters, speedMetersPerSec, bearingDegrees,
                                   horizontalAccuracyMeters, verticalAccuracyMeters,
                                   speedAccuracyMetersPerSecond, bearingAccuracyDegrees, timestamp,
                                   elapsedRealtimeFlags, elapsedRealtimeNanos,
                                   elapsedRealtimeUncertaintyNanos);
        auto status = gnssHalAidl->injectBestLocation(location);
        checkAidlStatus(status, "IGnssAidl injectBestLocation() failed.");
        return;
    }

    if (gnssHal_V2_0 != nullptr) {
        GnssLocation_V2_0 location = createGnssLocation_V2_0(
                gnssLocationFlags,
                latitudeDegrees,
                longitudeDegrees,
                altitudeMeters,
                speedMetersPerSec,
                bearingDegrees,
                horizontalAccuracyMeters,
                verticalAccuracyMeters,
                speedAccuracyMetersPerSecond,
                bearingAccuracyDegrees,
                timestamp,
                elapsedRealtimeFlags,
                elapsedRealtimeNanos,
                elapsedRealtimeUncertaintyNanos);
        auto result = gnssHal_V2_0->injectBestLocation_2_0(location);
        checkHidlReturn(result, "IGnss injectBestLocation_2_0() failed.");
        return;
    }

    if (gnssHal_V1_1 != nullptr) {
        GnssLocation_V1_0 location = createGnssLocation_V1_0(
                gnssLocationFlags,
                latitudeDegrees,
                longitudeDegrees,
                altitudeMeters,
                speedMetersPerSec,
                bearingDegrees,
                horizontalAccuracyMeters,
                verticalAccuracyMeters,
                speedAccuracyMetersPerSecond,
                bearingAccuracyDegrees,
                timestamp);
        auto result = gnssHal_V1_1->injectBestLocation(location);
        checkHidlReturn(result, "IGnss injectBestLocation() failed.");
    }

    ALOGE("IGnss injectBestLocation() is called but gnssHal_V1_1 is not available.");
}

static void android_location_gnss_hal_GnssNative_inject_location(
        JNIEnv* /* env */, jclass, jint gnssLocationFlags, jdouble latitudeDegrees,
        jdouble longitudeDegrees, jdouble altitudeMeters, jfloat speedMetersPerSec,
        jfloat bearingDegrees, jfloat horizontalAccuracyMeters, jfloat verticalAccuracyMeters,
        jfloat speedAccuracyMetersPerSecond, jfloat bearingAccuracyDegrees, jlong timestamp,
        jint elapsedRealtimeFlags, jlong elapsedRealtimeNanos,
        jdouble elapsedRealtimeUncertaintyNanos) {
    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        GnssLocationAidl location =
                createGnssLocation(gnssLocationFlags, latitudeDegrees, longitudeDegrees,
                                   altitudeMeters, speedMetersPerSec, bearingDegrees,
                                   horizontalAccuracyMeters, verticalAccuracyMeters,
                                   speedAccuracyMetersPerSecond, bearingAccuracyDegrees, timestamp,
                                   elapsedRealtimeFlags, elapsedRealtimeNanos,
                                   elapsedRealtimeUncertaintyNanos);
        auto status = gnssHalAidl->injectLocation(location);
        checkAidlStatus(status, "IGnssAidl injectLocation() failed.");
        return;
    }

    if (gnssHal == nullptr) {
        return;
    }
    auto result =
            gnssHal->injectLocation(latitudeDegrees, longitudeDegrees, horizontalAccuracyMeters);
    checkHidlReturn(result, "IGnss injectLocation() failed.");
}

static jboolean android_location_gnss_hal_GnssNative_supports_psds(JNIEnv* /* env */, jclass) {
    return (gnssPsdsAidlIface != nullptr || gnssXtraIface != nullptr) ? JNI_TRUE : JNI_FALSE;
}

static void android_location_gnss_hal_GnssNative_inject_psds_data(JNIEnv* env, jclass,
                                                                  jbyteArray data, jint length,
                                                                  jint psdsType) {
    if (gnssPsdsAidlIface == nullptr && gnssXtraIface == nullptr) {
        ALOGE("%s: IGnssPsdsAidl or IGnssXtra interface not available.", __func__);
        return;
    }

    jbyte* bytes = reinterpret_cast<jbyte *>(env->GetPrimitiveArrayCritical(data, 0));
    if (gnssPsdsAidlIface != nullptr) {
        auto status = gnssPsdsAidlIface->injectPsdsData(static_cast<PsdsType>(psdsType),
                                                        std::vector<uint8_t>((const uint8_t*)bytes,
                                                                             (const uint8_t*)bytes +
                                                                                     length));
        checkAidlStatus(status, "IGnssPsdsAidl injectPsdsData() failed.");
    } else if (gnssXtraIface != nullptr) {
        auto result = gnssXtraIface->injectXtraData(std::string((const char*)bytes, length));
        checkHidlReturn(result, "IGnssXtra injectXtraData() failed.");
    }
    env->ReleasePrimitiveArrayCritical(data, bytes, JNI_ABORT);
}

static void android_location_GnssNetworkConnectivityHandler_agps_data_conn_open(
        JNIEnv* env, jobject /* obj */, jlong networkHandle, jstring apn, jint apnIpType) {
    if (apn == nullptr) {
        jniThrowException(env, "java/lang/IllegalArgumentException", nullptr);
        return;
    }

    if (agnssIface != nullptr) {
        agnssIface->dataConnOpen(env, networkHandle, apn, apnIpType);
    } else {
        ALOGE("%s: IAGnss interface not available.", __func__);
        return;
    }
}

static void android_location_GnssNetworkConnectivityHandler_agps_data_conn_closed(JNIEnv* /* env */,
                                                                       jobject /* obj */) {
    if (agnssIface != nullptr) {
        agnssIface->dataConnClosed();
    } else {
        ALOGE("%s: IAGnss interface not available.", __func__);
        return;
    }
}

static void android_location_GnssNetworkConnectivityHandler_agps_data_conn_failed(JNIEnv* /* env */,
                                                                       jobject /* obj */) {
    if (agnssIface != nullptr) {
        agnssIface->dataConnFailed();
    } else {
        ALOGE("%s: IAGnss interface not available.", __func__);
        return;
    }
}

static void android_location_gnss_hal_GnssNative_set_agps_server(JNIEnv* env, jclass, jint type,
                                                                 jstring hostname, jint port) {
    if (agnssIface != nullptr) {
        agnssIface->setServer(env, type, hostname, port);
    } else {
        ALOGE("%s: IAGnss interface not available.", __func__);
        return;
    }
}

static void android_location_gnss_hal_GnssNative_send_ni_response(JNIEnv* /* env */, jclass,
                                                                  jint notifId, jint response) {
    if (gnssNiIface == nullptr) {
        ALOGE("%s: IGnssNi interface not available.", __func__);
        return;
    }

    auto result = gnssNiIface->respond(notifId,
            static_cast<IGnssNiCallback::GnssUserResponseType>(response));
    checkHidlReturn(result, "IGnssNi respond() failed.");
}

static jstring android_location_gnss_hal_GnssNative_get_internal_state(JNIEnv* env, jclass) {
    /*
     * TODO: Create a jobject to represent GnssDebug.
     */

    if (gnssDebugIface == nullptr) {
        ALOGE("%s: IGnssDebug interface not available.", __func__);
        return nullptr;
    }
    return gnssDebugIface->getDebugData(env);
}

static void android_location_gnss_hal_GnssNative_request_power_stats(JNIEnv* env) {
    if (gnssPowerIndicationIface == nullptr) {
        return;
    }
    auto status = gnssPowerIndicationIface->requestGnssPowerStats();
    checkAidlStatus(status, "IGnssPowerIndication requestGnssPowerStats() failed.");
}

static jboolean android_location_gnss_hal_GnssNative_is_gnss_visibility_control_supported(
        JNIEnv* /* env */, jclass) {
    return (gnssVisibilityControlIface != nullptr) ?  JNI_TRUE : JNI_FALSE;
}

static void android_location_GnssNetworkConnectivityHandler_update_network_state(JNIEnv* env,
                                                                       jobject /* obj */,
                                                                       jboolean connected,
                                                                       jint type,
                                                                       jboolean roaming,
                                                                       jboolean available,
                                                                       jstring apn,
                                                                       jlong networkHandle,
                                                                       jshort capabilities) {
    if (agnssRilIface == nullptr) {
        ALOGE("%s: IAGnssRil interface not available.", __func__);
        return;
    }
    agnssRilIface->updateNetworkState(connected, type, roaming, available, apn, networkHandle,
                                      capabilities);
}

static jboolean android_location_gnss_hal_GnssNative_is_geofence_supported(JNIEnv* /* env */,
                                                                           jclass) {
    if (gnssGeofencingIface == nullptr) {
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

static jboolean android_location_gnss_hal_GnssNative_add_geofence(
        JNIEnv* /* env */, jclass, jint geofenceId, jdouble latitude, jdouble longitude,
        jdouble radius, jint last_transition, jint monitor_transition,
        jint notification_responsiveness, jint unknown_timer) {
    if (gnssGeofencingIface == nullptr) {
        ALOGE("%s: IGnssGeofencing interface not available.", __func__);
        return JNI_FALSE;
    }
    return gnssGeofencingIface->addGeofence(geofenceId, latitude, longitude, radius,
                                            last_transition, monitor_transition,
                                            notification_responsiveness, unknown_timer);
}

static jboolean android_location_gnss_hal_GnssNative_remove_geofence(JNIEnv* /* env */, jclass,
                                                                     jint geofenceId) {
    if (gnssGeofencingIface == nullptr) {
        ALOGE("%s: IGnssGeofencing interface not available.", __func__);
        return JNI_FALSE;
    }
    return gnssGeofencingIface->removeGeofence(geofenceId);
}

static jboolean android_location_gnss_hal_GnssNative_pause_geofence(JNIEnv* /* env */, jclass,
                                                                    jint geofenceId) {
    if (gnssGeofencingIface == nullptr) {
        ALOGE("%s: IGnssGeofencing interface not available.", __func__);
        return JNI_FALSE;
    }
    return gnssGeofencingIface->pauseGeofence(geofenceId);
}

static jboolean android_location_gnss_hal_GnssNative_resume_geofence(JNIEnv* /* env */, jclass,
                                                                     jint geofenceId,
                                                                     jint monitor_transition) {
    if (gnssGeofencingIface == nullptr) {
        ALOGE("%s: IGnssGeofencing interface not available.", __func__);
        return JNI_FALSE;
    }
    return gnssGeofencingIface->resumeGeofence(geofenceId, monitor_transition);
}

static jboolean android_location_gnss_hal_GnssNative_is_antenna_info_supported(JNIEnv* env,
                                                                               jclass) {
    if (gnssAntennaInfoIface != nullptr) {
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

static jboolean android_location_gnss_hal_GnssNative_start_antenna_info_listening(JNIEnv* /* env */,
                                                                                  jclass) {
    if (gnssAntennaInfoIface == nullptr) {
        ALOGE("%s: IGnssAntennaInfo interface not available.", __func__);
        return JNI_FALSE;
    }
    return gnssAntennaInfoIface->setCallback(std::make_unique<gnss::GnssAntennaInfoCallback>());
}

static jboolean android_location_gnss_hal_GnssNative_stop_antenna_info_listening(JNIEnv* /* env */,
                                                                                 jclass) {
    if (gnssAntennaInfoIface == nullptr) {
        ALOGE("%s: IGnssAntennaInfo interface not available.", __func__);
        return JNI_FALSE;
    }
    return gnssAntennaInfoIface->close();
}

static jboolean android_location_gnss_hal_GnssNative_is_measurement_supported(JNIEnv* env, jclass) {
    if (gnssMeasurementIface != nullptr) {
        return JNI_TRUE;
    }

    return JNI_FALSE;
}

static jboolean android_location_gnss_hal_GnssNative_start_measurement_collection(
        JNIEnv* /* env */, jclass, jboolean enableFullTracking, jboolean enableCorrVecOutputs,
        jint intervalMs) {
    if (gnssMeasurementIface == nullptr) {
        ALOGE("%s: IGnssMeasurement interface not available.", __func__);
        return JNI_FALSE;
    }
    hardware::gnss::IGnssMeasurementInterface::Options options;
    options.enableFullTracking = enableFullTracking;
    options.enableCorrVecOutputs = enableCorrVecOutputs;
    options.intervalMs = intervalMs;

    return gnssMeasurementIface->setCallback(std::make_unique<gnss::GnssMeasurementCallback>(
                                                     mCallbacksObj),
                                             options);
}

static jboolean android_location_gnss_hal_GnssNative_stop_measurement_collection(JNIEnv* env,
                                                                                 jclass) {
    if (gnssMeasurementIface == nullptr) {
        ALOGE("%s: IGnssMeasurement interface not available.", __func__);
        return JNI_FALSE;
    }

    return gnssMeasurementIface->close();
}

static jboolean android_location_gnss_hal_GnssNative_is_measurement_corrections_supported(
        JNIEnv* env, jclass) {
    if (gnssMeasurementCorrectionsIface != nullptr) {
        return JNI_TRUE;
    }

    return JNI_FALSE;
}

static jboolean android_location_gnss_hal_GnssNative_inject_measurement_corrections(
        JNIEnv* env, jclass, jobject correctionsObj) {
    if (gnssMeasurementCorrectionsIface == nullptr) {
        ALOGW("Trying to inject GNSS measurement corrections on a chipset that does not"
            " support them.");
        return JNI_FALSE;
    }
    return gnssMeasurementCorrectionsIface->setCorrections(env, correctionsObj);
}

static jboolean android_location_gnss_hal_GnssNative_is_navigation_message_supported(JNIEnv* env,
                                                                                     jclass) {
    if (gnssNavigationMessageIface != nullptr) {
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

static jboolean android_location_gnss_hal_GnssNative_start_navigation_message_collection(
        JNIEnv* env, jclass) {
    if (gnssNavigationMessageIface == nullptr) {
        ALOGE("%s: IGnssNavigationMessage interface not available.", __func__);
        return JNI_FALSE;
    }

    return gnssNavigationMessageIface->setCallback(
            std::make_unique<gnss::GnssNavigationMessageCallback>());
}

static jboolean android_location_gnss_hal_GnssNative_stop_navigation_message_collection(JNIEnv* env,
                                                                                        jclass) {
    if (gnssNavigationMessageIface == nullptr) {
        ALOGE("%s: IGnssNavigationMessage interface not available.", __func__);
        return JNI_FALSE;
    }
    return gnssNavigationMessageIface->close();
}

static jboolean android_location_GnssConfiguration_set_emergency_supl_pdn(JNIEnv*,
                                                                          jobject,
                                                                          jint emergencySuplPdn) {
    if (gnssConfigurationIface == nullptr) {
        ALOGE("%s: IGnssConfiguration interface not available.", __func__);
        return JNI_FALSE;
    }
    return gnssConfigurationIface->setEmergencySuplPdn(emergencySuplPdn);
}

static jboolean android_location_GnssConfiguration_set_supl_version(JNIEnv*,
                                                                    jobject,
                                                                    jint version) {
    if (gnssConfigurationIface == nullptr) {
        ALOGE("%s: IGnssConfiguration interface not available.", __func__);
        return JNI_FALSE;
    }
    return gnssConfigurationIface->setSuplVersion(version);
}

static jboolean android_location_GnssConfiguration_set_supl_es(JNIEnv*,
                                                               jobject,
                                                               jint suplEs) {
    if (gnssConfigurationIface == nullptr) {
        ALOGE("%s: IGnssConfiguration interface not available.", __func__);
        return JNI_FALSE;
    }
    return gnssConfigurationIface->setSuplEs(suplEs);
}

static jboolean android_location_GnssConfiguration_set_supl_mode(JNIEnv*,
                                                                 jobject,
                                                                 jint mode) {
    if (gnssConfigurationIface == nullptr) {
        ALOGE("%s: IGnssConfiguration interface not available.", __func__);
        return JNI_FALSE;
    }
    return gnssConfigurationIface->setSuplMode(mode);
}

static jboolean android_location_GnssConfiguration_set_gps_lock(JNIEnv*,
                                                                jobject,
                                                                jint gpsLock) {
    if (gnssConfigurationIface == nullptr) {
        ALOGE("%s: IGnssConfiguration interface not available.", __func__);
        return JNI_FALSE;
    }
    return gnssConfigurationIface->setGpsLock(gpsLock);
}

static jboolean android_location_GnssConfiguration_set_lpp_profile(JNIEnv*,
                                                                   jobject,
                                                                   jint lppProfile) {
    if (gnssConfigurationIface == nullptr) {
        ALOGE("%s: IGnssConfiguration interface not available.", __func__);
        return JNI_FALSE;
    }
    return gnssConfigurationIface->setLppProfile(lppProfile);
}

static jboolean android_location_GnssConfiguration_set_gnss_pos_protocol_select(JNIEnv*,
                                                                            jobject,
                                                                            jint gnssPosProtocol) {
    if (gnssConfigurationIface == nullptr) {
        ALOGE("%s: IGnssConfiguration interface not available.", __func__);
        return JNI_FALSE;
    }
    return gnssConfigurationIface->setGlonassPositioningProtocol(gnssPosProtocol);
}

static jboolean android_location_GnssConfiguration_set_satellite_blocklist(JNIEnv* env, jobject,
                                                                           jintArray constellations,
                                                                           jintArray sv_ids) {
    if (gnssConfigurationIface == nullptr) {
        ALOGI("IGnssConfiguration interface does not support satellite blocklist.");
        return JNI_FALSE;
    }
    return gnssConfigurationIface->setBlocklist(env, constellations, sv_ids);
}

static jboolean android_location_GnssConfiguration_set_es_extension_sec(
        JNIEnv*, jobject, jint emergencyExtensionSeconds) {
    if (gnssConfigurationIface == nullptr) {
        ALOGE("%s: IGnssConfiguration interface not available.", __func__);
        return JNI_FALSE;
    }
    return gnssConfigurationIface->setEsExtensionSec(emergencyExtensionSeconds);
}

static jint android_location_gnss_hal_GnssNative_get_batch_size(JNIEnv*) {
    if (gnssBatchingIface == nullptr) {
        return 0; // batching not supported, size = 0
    }
    return gnssBatchingIface->getBatchSize();
}

static jboolean android_location_gnss_hal_GnssNative_init_batching(JNIEnv*, jclass) {
    if (gnssBatchingIface == nullptr) {
        return JNI_FALSE; // batching not supported
    }
    return gnssBatchingIface->init(std::make_unique<gnss::GnssBatchingCallback>());
}

static void android_location_gnss_hal_GnssNative_cleanup_batching(JNIEnv*, jclass) {
    if (gnssBatchingIface == nullptr) {
        return; // batching not supported
    }
    gnssBatchingIface->cleanup();
}

static jboolean android_location_gnss_hal_GnssNative_start_batch(JNIEnv*, jclass, jlong periodNanos,
                                                                 jfloat minUpdateDistanceMeters,
                                                                 jboolean wakeOnFifoFull) {
    if (gnssBatchingIface == nullptr) {
        return JNI_FALSE; // batching not supported
    }
    return gnssBatchingIface->start(periodNanos, minUpdateDistanceMeters, wakeOnFifoFull);
}

static void android_location_gnss_hal_GnssNative_flush_batch(JNIEnv*, jclass) {
    if (gnssBatchingIface == nullptr) {
        return; // batching not supported
    }
    gnssBatchingIface->flush();
}

static jboolean android_location_gnss_hal_GnssNative_stop_batch(JNIEnv*, jclass) {
    if (gnssBatchingIface == nullptr) {
        return JNI_FALSE; // batching not supported
    }
    return gnssBatchingIface->stop();
}

static jboolean android_location_GnssVisibilityControl_enable_nfw_location_access(
        JNIEnv* env, jobject, jobjectArray proxyApps) {
    if (gnssVisibilityControlIface == nullptr) {
        ALOGI("IGnssVisibilityControl interface not available.");
        return JNI_FALSE;
    }
    return gnssVisibilityControlIface->enableNfwLocationAccess(env, proxyApps);
}

static const JNINativeMethod sCoreMethods[] = {
        /* name, signature, funcPtr */
        {"native_class_init_once", "()V",
         reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_class_init_once)},
        {"native_is_supported", "()Z",
         reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_is_supported)},
        {"native_init_once", "(Z)V",
         reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_init_once)},
};

static const JNINativeMethod sLocationProviderMethods[] = {
        /* name, signature, funcPtr */
        {"native_init", "()Z", reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_init)},
        {"native_cleanup", "()V",
         reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_cleanup)},
        {"native_set_position_mode", "(IIIIIZ)Z",
         reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_set_position_mode)},
        {"native_start", "()Z",
         reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_start)},
        {"native_stop", "()Z", reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_stop)},
        {"native_delete_aiding_data", "(I)V",
         reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_delete_aiding_data)},
        {"native_read_nmea", "([BI)I",
         reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_read_nmea)},
        {"native_inject_time", "(JJI)V",
         reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_inject_time)},
        {"native_inject_best_location", "(IDDDFFFFFFJIJD)V",
         reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_inject_best_location)},
        {"native_inject_location", "(IDDDFFFFFFJIJD)V",
         reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_inject_location)},
        {"native_supports_psds", "()Z",
         reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_supports_psds)},
        {"native_inject_psds_data", "([BII)V",
         reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_inject_psds_data)},
        {"native_agps_set_id", "(ILjava/lang/String;)V",
         reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_agps_set_id)},
        {"native_agps_set_ref_location_cellid", "(IIIIJIII)V",
         reinterpret_cast<void*>(
                 android_location_gnss_hal_GnssNative_agps_set_reference_location_cellid)},
        {"native_set_agps_server", "(ILjava/lang/String;I)V",
         reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_set_agps_server)},
        {"native_send_ni_response", "(II)V",
         reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_send_ni_response)},
        {"native_get_internal_state", "()Ljava/lang/String;",
         reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_get_internal_state)},
        {"native_is_gnss_visibility_control_supported", "()Z",
         reinterpret_cast<void*>(
                 android_location_gnss_hal_GnssNative_is_gnss_visibility_control_supported)},
        {"native_start_sv_status_collection", "()Z",
         reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_start_sv_status_collection)},
        {"native_stop_sv_status_collection", "()Z",
         reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_stop_sv_status_collection)},
        {"native_start_nmea_message_collection", "()Z",
         reinterpret_cast<void*>(
                 android_location_gnss_hal_GnssNative_start_nmea_message_collection)},
        {"native_stop_nmea_message_collection", "()Z",
         reinterpret_cast<void*>(
                 android_location_gnss_hal_GnssNative_stop_nmea_message_collection)},
};

static const JNINativeMethod sBatchingMethods[] = {
        /* name, signature, funcPtr */
        {"native_get_batch_size", "()I",
         reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_get_batch_size)},
        {"native_start_batch", "(JFZ)Z",
         reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_start_batch)},
        {"native_flush_batch", "()V",
         reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_flush_batch)},
        {"native_stop_batch", "()Z",
         reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_stop_batch)},
        {"native_init_batching", "()Z",
         reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_init_batching)},
        {"native_cleanup_batching", "()V",
         reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_cleanup_batching)},
};

static const JNINativeMethod sAntennaInfoMethods[] = {
        /* name, signature, funcPtr */
        {"native_is_antenna_info_supported", "()Z",
         reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_is_antenna_info_supported)},
        {"native_start_antenna_info_listening", "()Z",
         reinterpret_cast<void*>(
                 android_location_gnss_hal_GnssNative_start_antenna_info_listening)},
        {"native_stop_antenna_info_listening", "()Z",
         reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_stop_antenna_info_listening)},
};

static const JNINativeMethod sGeofenceMethods[] = {
        /* name, signature, funcPtr */
        {"native_is_geofence_supported", "()Z",
         reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_is_geofence_supported)},
        {"native_add_geofence", "(IDDDIIII)Z",
         reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_add_geofence)},
        {"native_remove_geofence", "(I)Z",
         reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_remove_geofence)},
        {"native_pause_geofence", "(I)Z",
         reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_pause_geofence)},
        {"native_resume_geofence", "(II)Z",
         reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_resume_geofence)},
};

static const JNINativeMethod sMeasurementMethods[] = {
        /* name, signature, funcPtr */
        {"native_is_measurement_supported", "()Z",
         reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_is_measurement_supported)},
        {"native_start_measurement_collection", "(ZZI)Z",
         reinterpret_cast<void*>(
                 android_location_gnss_hal_GnssNative_start_measurement_collection)},
        {"native_stop_measurement_collection", "()Z",
         reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_stop_measurement_collection)},
};

static const JNINativeMethod sMeasurementCorrectionsMethods[] = {
        /* name, signature, funcPtr */
        {"native_is_measurement_corrections_supported", "()Z",
         reinterpret_cast<void*>(
                 android_location_gnss_hal_GnssNative_is_measurement_corrections_supported)},
        {"native_inject_measurement_corrections",
         "(Landroid/location/GnssMeasurementCorrections;)Z",
         reinterpret_cast<void*>(
                 android_location_gnss_hal_GnssNative_inject_measurement_corrections)},
};

static const JNINativeMethod sNavigationMessageMethods[] = {
        /* name, signature, funcPtr */
        {"native_is_navigation_message_supported", "()Z",
         reinterpret_cast<void*>(
                 android_location_gnss_hal_GnssNative_is_navigation_message_supported)},
        {"native_start_navigation_message_collection", "()Z",
         reinterpret_cast<void*>(
                 android_location_gnss_hal_GnssNative_start_navigation_message_collection)},
        {"native_stop_navigation_message_collection", "()Z",
         reinterpret_cast<void*>(
                 android_location_gnss_hal_GnssNative_stop_navigation_message_collection)},
};

static const JNINativeMethod sNetworkConnectivityMethods[] = {
        /* name, signature, funcPtr */
        {"native_is_agps_ril_supported", "()Z",
         reinterpret_cast<void*>(
                 android_location_GnssNetworkConnectivityHandler_is_agps_ril_supported)},
        {"native_update_network_state", "(ZIZZLjava/lang/String;JS)V",
         reinterpret_cast<void*>(
                 android_location_GnssNetworkConnectivityHandler_update_network_state)},
        {"native_agps_data_conn_open", "(JLjava/lang/String;I)V",
         reinterpret_cast<void*>(
                 android_location_GnssNetworkConnectivityHandler_agps_data_conn_open)},
        {"native_agps_data_conn_closed", "()V",
         reinterpret_cast<void*>(
                 android_location_GnssNetworkConnectivityHandler_agps_data_conn_closed)},
        {"native_agps_data_conn_failed", "()V",
         reinterpret_cast<void*>(
                 android_location_GnssNetworkConnectivityHandler_agps_data_conn_failed)},
};

static const JNINativeMethod sConfigurationMethods[] = {
        /* name, signature, funcPtr */
        {"native_get_gnss_configuration_version",
         "()Lcom/android/server/location/gnss/GnssConfiguration$HalInterfaceVersion;",
         reinterpret_cast<void*>(
                 android_location_GnssConfiguration_get_gnss_configuration_version)},
        {"native_set_supl_es", "(I)Z",
         reinterpret_cast<void*>(android_location_GnssConfiguration_set_supl_es)},
        {"native_set_supl_version", "(I)Z",
         reinterpret_cast<void*>(android_location_GnssConfiguration_set_supl_version)},
        {"native_set_supl_mode", "(I)Z",
         reinterpret_cast<void*>(android_location_GnssConfiguration_set_supl_mode)},
        {"native_set_lpp_profile", "(I)Z",
         reinterpret_cast<void*>(android_location_GnssConfiguration_set_lpp_profile)},
        {"native_set_gnss_pos_protocol_select", "(I)Z",
         reinterpret_cast<void*>(android_location_GnssConfiguration_set_gnss_pos_protocol_select)},
        {"native_set_gps_lock", "(I)Z",
         reinterpret_cast<void*>(android_location_GnssConfiguration_set_gps_lock)},
        {"native_set_emergency_supl_pdn", "(I)Z",
         reinterpret_cast<void*>(android_location_GnssConfiguration_set_emergency_supl_pdn)},
        {"native_set_satellite_blocklist", "([I[I)Z",
         reinterpret_cast<void*>(android_location_GnssConfiguration_set_satellite_blocklist)},
        {"native_set_es_extension_sec", "(I)Z",
         reinterpret_cast<void*>(android_location_GnssConfiguration_set_es_extension_sec)},
};

static const JNINativeMethod sVisibilityControlMethods[] = {
        /* name, signature, funcPtr */
        {"native_enable_nfw_location_access", "([Ljava/lang/String;)Z",
         reinterpret_cast<void*>(
                 android_location_GnssVisibilityControl_enable_nfw_location_access)},
};

static const JNINativeMethod sPowerIndicationMethods[] = {
        /* name, signature, funcPtr */
        {"native_request_power_stats", "()V",
         reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_request_power_stats)},
};

int register_android_server_location_GnssLocationProvider(JNIEnv* env) {
    int res;

    res = jniRegisterNativeMethods(env, "com/android/server/location/gnss/hal/GnssNative",
                                   sAntennaInfoMethods, NELEM(sAntennaInfoMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    res = jniRegisterNativeMethods(env, "com/android/server/location/gnss/hal/GnssNative",
                                   sBatchingMethods, NELEM(sBatchingMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    res = jniRegisterNativeMethods(env, "com/android/server/location/gnss/hal/GnssNative",
                                   sGeofenceMethods, NELEM(sGeofenceMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    res = jniRegisterNativeMethods(env, "com/android/server/location/gnss/hal/GnssNative",
                                   sMeasurementMethods, NELEM(sMeasurementMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    res = jniRegisterNativeMethods(env, "com/android/server/location/gnss/hal/GnssNative",
                                   sMeasurementCorrectionsMethods,
                                   NELEM(sMeasurementCorrectionsMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    res = jniRegisterNativeMethods(env, "com/android/server/location/gnss/hal/GnssNative",
                                   sNavigationMessageMethods, NELEM(sNavigationMessageMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    res = jniRegisterNativeMethods(env,
                                   "com/android/server/location/gnss/"
                                   "GnssNetworkConnectivityHandler",
                                   sNetworkConnectivityMethods, NELEM(sNetworkConnectivityMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    res = jniRegisterNativeMethods(env, "com/android/server/location/gnss/GnssConfiguration",
                                   sConfigurationMethods, NELEM(sConfigurationMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    res = jniRegisterNativeMethods(env, "com/android/server/location/gnss/GnssVisibilityControl",
                                   sVisibilityControlMethods, NELEM(sVisibilityControlMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    res = jniRegisterNativeMethods(env, "com/android/server/location/gnss/hal/GnssNative",
                                   sPowerIndicationMethods, NELEM(sPowerIndicationMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    res = jniRegisterNativeMethods(env, "com/android/server/location/gnss/hal/GnssNative",
                                   sLocationProviderMethods, NELEM(sLocationProviderMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    res = jniRegisterNativeMethods(env, "com/android/server/location/gnss/hal/GnssNative",
                                   sCoreMethods, NELEM(sCoreMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    return 0;
}

} /* namespace android */
