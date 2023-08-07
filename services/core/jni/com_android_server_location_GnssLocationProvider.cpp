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
#include "gnss/Gnss.h"
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

using android::hardware::Return;
using android::hardware::Void;
using android::hardware::hidl_vec;
using android::hardware::hidl_string;
using android::hardware::hidl_death_recipient;

using android::hardware::gnss::V1_0::GnssLocationFlags;
using android::hardware::gnss::V1_0::IGnssNavigationMessage;
using android::hardware::gnss::V1_0::IGnssNavigationMessageCallback;
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

using IGnssAidl = android::hardware::gnss::IGnss;
using IGnssBatchingAidl = android::hardware::gnss::IGnssBatching;
using IGnssDebugAidl = android::hardware::gnss::IGnssDebug;
using IGnssPsdsAidl = android::hardware::gnss::IGnssPsds;
using IGnssPsdsCallbackAidl = android::hardware::gnss::IGnssPsdsCallback;
using IGnssConfigurationAidl = android::hardware::gnss::IGnssConfiguration;
using GnssLocationAidl = android::hardware::gnss::GnssLocation;
using IGnssAntennaInfoAidl = android::hardware::gnss::IGnssAntennaInfo;

sp<IGnssPowerIndication> gnssPowerIndicationIface = nullptr;

std::unique_ptr<android::gnss::GnssHal> gnssHal = nullptr;
std::unique_ptr<android::gnss::AGnssInterface> agnssIface = nullptr;
std::unique_ptr<android::gnss::AGnssRilInterface> agnssRilIface = nullptr;
std::unique_ptr<android::gnss::GnssAntennaInfoInterface> gnssAntennaInfoIface = nullptr;
std::unique_ptr<android::gnss::GnssConfigurationInterface> gnssConfigurationIface = nullptr;
std::unique_ptr<android::gnss::GnssMeasurementInterface> gnssMeasurementIface = nullptr;
std::unique_ptr<android::gnss::GnssNavigationMessageInterface> gnssNavigationMessageIface = nullptr;
std::unique_ptr<android::gnss::GnssBatchingInterface> gnssBatchingIface = nullptr;
std::unique_ptr<android::gnss::GnssDebugInterface> gnssDebugIface = nullptr;
std::unique_ptr<android::gnss::GnssGeofenceInterface> gnssGeofencingIface = nullptr;
std::unique_ptr<android::gnss::GnssPsdsInterface> gnssPsdsIface = nullptr;
std::unique_ptr<android::gnss::GnssVisibilityControlInterface> gnssVisibilityControlIface = nullptr;
std::unique_ptr<android::gnss::MeasurementCorrectionsInterface> gnssMeasurementCorrectionsIface =
        nullptr;

namespace android {

namespace {

}  // namespace

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

/* Initializes the GNSS service handle. */
static void android_location_gnss_hal_GnssNative_set_gps_service_handle() {
    gnssHal = std::make_unique<gnss::GnssHal>();
}

/* One time initialization at system boot */
static void android_location_gnss_hal_GnssNative_class_init_once(JNIEnv* env, jclass clazz) {
    // Initialize the top level gnss HAL handle.
    android_location_gnss_hal_GnssNative_set_gps_service_handle();

    // Cache methodIDs and class IDs.
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

    gnss::AGnss_class_init_once(env, clazz);
    gnss::AGnssRil_class_init_once(env, clazz);
    gnss::Gnss_class_init_once(env, clazz);
    gnss::GnssAntennaInfo_class_init_once(env, clazz);
    gnss::GnssBatching_class_init_once(env, clazz);
    gnss::GnssConfiguration_class_init_once(env);
    gnss::GnssGeofence_class_init_once(env, clazz);
    gnss::GnssMeasurement_class_init_once(env, clazz);
    gnss::GnssNavigationMessage_class_init_once(env, clazz);
    gnss::GnssPsds_class_init_once(env, clazz);
    gnss::GnssVisibilityControl_class_init_once(env, clazz);
    gnss::MeasurementCorrections_class_init_once(env, clazz);
    gnss::MeasurementCorrectionsCallback_class_init_once(env, clazz);
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

    if (gnssHal == nullptr || !gnssHal->isSupported()) {
        ALOGE("Unable to get GPS service\n");
        return;
    }

    gnssHal->linkToDeath();
    gnssPsdsIface = gnssHal->getGnssPsdsInterface();
    agnssRilIface = gnssHal->getAGnssRilInterface();
    agnssIface = gnssHal->getAGnssInterface();
    gnssNavigationMessageIface = gnssHal->getGnssNavigationMessageInterface();
    gnssMeasurementIface = gnssHal->getGnssMeasurementInterface();
    gnssAntennaInfoIface = gnssHal->getGnssAntennaInfoInterface();
    gnssMeasurementCorrectionsIface = gnssHal->getMeasurementCorrectionsInterface();
    gnssDebugIface = gnssHal->getGnssDebugInterface();
    gnssConfigurationIface = gnssHal->getGnssConfigurationInterface();
    gnssGeofencingIface = gnssHal->getGnssGeofenceInterface();
    gnssBatchingIface = gnssHal->getGnssBatchingInterface();
    gnssVisibilityControlIface = gnssHal->getGnssVisibilityControlInterface();
    gnssPowerIndicationIface = gnssHal->getGnssPowerIndicationInterface();

    if (mCallbacksObj) {
        ALOGE("Callbacks already initialized");
    } else {
        mCallbacksObj = env->NewGlobalRef(obj);
    }
}

static jboolean android_location_gnss_hal_GnssNative_is_supported(JNIEnv* /* env */, jclass) {
    return (gnssHal != nullptr && gnssHal->isSupported()) ? JNI_TRUE : JNI_FALSE;
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
    if (!gnssHal->isSupported()) {
        ALOGE("Unable to initialize GNSS HAL.");
        return JNI_FALSE;
    }

    // Set top level IGnss HAL callback.
    gnssHal->setCallback();

    // Set IGnssPsds callback.
    if (gnssPsdsIface == nullptr ||
        !gnssPsdsIface->setCallback(std::make_unique<gnss::GnssPsdsCallback>())) {
        ALOGI("Unable to initialize IGnssPsds interface.");
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
    gnssHal->close();
}

static jboolean android_location_gnss_hal_GnssNative_set_position_mode(
        JNIEnv* /* env */, jclass, jint mode, jint recurrence, jint min_interval,
        jint preferred_accuracy, jint preferred_time, jboolean low_power_mode) {
    return gnssHal->setPositionMode(mode, recurrence, min_interval, preferred_accuracy,
                                    preferred_time, low_power_mode);
}

static jboolean android_location_gnss_hal_GnssNative_start(JNIEnv* /* env */, jclass) {
    return gnssHal->start();
}

static jboolean android_location_gnss_hal_GnssNative_stop(JNIEnv* /* env */, jclass) {
    return gnssHal->stop();
}

static jboolean android_location_gnss_hal_GnssNative_start_sv_status_collection(JNIEnv* /* env */,
                                                                                jclass) {
    return gnssHal->startSvStatus();
}

static jboolean android_location_gnss_hal_GnssNative_stop_sv_status_collection(JNIEnv* /* env */,
                                                                               jclass) {
    return gnssHal->stopSvStatus();
}

static jboolean android_location_gnss_hal_GnssNative_start_nmea_message_collection(
        JNIEnv* /* env */, jclass) {
    return gnssHal->startNmea();
}

static jboolean android_location_gnss_hal_GnssNative_stop_nmea_message_collection(JNIEnv* /* env */,
                                                                                  jclass) {
    return gnssHal->stopNmea();
}

static void android_location_gnss_hal_GnssNative_delete_aiding_data(JNIEnv* /* env */, jclass,
                                                                    jint flags) {
    gnssHal->deleteAidingData(flags);
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

static void android_location_gnss_hal_GnssNative_inject_ni_supl_message_data(JNIEnv* env, jclass,
                                                                             jbyteArray data,
                                                                             jint length,
                                                                             jint slotIndex) {
    if (agnssRilIface == nullptr) {
        ALOGE("%s: IAGnssRil interface not available.", __func__);
        return;
    }
    agnssRilIface->injectNiSuplMessageData(data, length, slotIndex);
}

static jint android_location_gnss_hal_GnssNative_read_nmea(JNIEnv* env, jclass,
                                                           jbyteArray nmeaArray, jint buffer_size) {
    return gnssHal->readNmea(nmeaArray, buffer_size);
}

static void android_location_gnss_hal_GnssNative_inject_time(JNIEnv* /* env */, jclass, jlong time,
                                                             jlong timeReference,
                                                             jint uncertainty) {
    gnssHal->injectTime(time, timeReference, uncertainty);
}

static void android_location_gnss_hal_GnssNative_inject_best_location(
        JNIEnv* /* env */, jclass, jint gnssLocationFlags, jdouble latitudeDegrees,
        jdouble longitudeDegrees, jdouble altitudeMeters, jfloat speedMetersPerSec,
        jfloat bearingDegrees, jfloat horizontalAccuracyMeters, jfloat verticalAccuracyMeters,
        jfloat speedAccuracyMetersPerSecond, jfloat bearingAccuracyDegrees, jlong timestamp,
        jint elapsedRealtimeFlags, jlong elapsedRealtimeNanos,
        jdouble elapsedRealtimeUncertaintyNanos) {
    gnssHal->injectBestLocation(gnssLocationFlags, latitudeDegrees, longitudeDegrees,
                                altitudeMeters, speedMetersPerSec, bearingDegrees,
                                horizontalAccuracyMeters, verticalAccuracyMeters,
                                speedAccuracyMetersPerSecond, bearingAccuracyDegrees, timestamp,
                                elapsedRealtimeFlags, elapsedRealtimeNanos,
                                elapsedRealtimeUncertaintyNanos);
}

static void android_location_gnss_hal_GnssNative_inject_location(
        JNIEnv* /* env */, jclass, jint gnssLocationFlags, jdouble latitudeDegrees,
        jdouble longitudeDegrees, jdouble altitudeMeters, jfloat speedMetersPerSec,
        jfloat bearingDegrees, jfloat horizontalAccuracyMeters, jfloat verticalAccuracyMeters,
        jfloat speedAccuracyMetersPerSecond, jfloat bearingAccuracyDegrees, jlong timestamp,
        jint elapsedRealtimeFlags, jlong elapsedRealtimeNanos,
        jdouble elapsedRealtimeUncertaintyNanos) {
    gnssHal->injectLocation(gnssLocationFlags, latitudeDegrees, longitudeDegrees, altitudeMeters,
                            speedMetersPerSec, bearingDegrees, horizontalAccuracyMeters,
                            verticalAccuracyMeters, speedAccuracyMetersPerSecond,
                            bearingAccuracyDegrees, timestamp, elapsedRealtimeFlags,
                            elapsedRealtimeNanos, elapsedRealtimeUncertaintyNanos);
}

static jboolean android_location_gnss_hal_GnssNative_supports_psds(JNIEnv* /* env */, jclass) {
    return (gnssPsdsIface != nullptr) ? JNI_TRUE : JNI_FALSE;
}

static void android_location_gnss_hal_GnssNative_inject_psds_data(JNIEnv* env, jclass,
                                                                  jbyteArray data, jint length,
                                                                  jint psdsType) {
    if (gnssPsdsIface == nullptr) {
        ALOGE("%s: IGnssPsds or IGnssXtra interface not available.", __func__);
        return;
    }
    gnssPsdsIface->injectPsdsData(data, length, psdsType);
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

    return gnssMeasurementIface->setCallback(std::make_unique<gnss::GnssMeasurementCallback>(),
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
        {"native_inject_ni_supl_message_data", "([BII)V",
         reinterpret_cast<void*>(android_location_gnss_hal_GnssNative_inject_ni_supl_message_data)},
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
