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

#define LOG_TAG "GnssLocationProvider"

#define LOG_NDEBUG 0

#include <android/hardware/gnss/1.0/IGnss.h>
#include <android/hardware/gnss/1.1/IGnss.h>
#include <android/hardware/gnss/2.0/IGnss.h>

#include <android/hardware/gnss/1.0/IGnssMeasurement.h>
#include <android/hardware/gnss/1.1/IGnssMeasurement.h>
#include <android/hardware/gnss/2.0/IGnssMeasurement.h>
#include <android/hardware/gnss/measurement_corrections/1.0/IMeasurementCorrections.h>
#include <android/hardware/gnss/visibility_control/1.0/IGnssVisibilityControl.h>
#include <nativehelper/JNIHelp.h>
#include "jni.h"
#include "hardware_legacy/power.h"
#include "utils/Log.h"
#include "utils/misc.h"
#include "android_runtime/AndroidRuntime.h"
#include "android_runtime/Log.h"

#include <arpa/inet.h>
#include <cinttypes>
#include <iomanip>
#include <limits>
#include <linux/in.h>
#include <linux/in6.h>
#include <pthread.h>
#include <string.h>
#include <utils/SystemClock.h>

static jclass class_gnssMeasurementsEvent;
static jclass class_gnssMeasurement;
static jclass class_location;
static jclass class_gnssNavigationMessage;
static jclass class_gnssClock;
static jclass class_gnssConfiguration_halInterfaceVersion;

static jobject mCallbacksObj = nullptr;

static jmethodID method_reportLocation;
static jmethodID method_reportStatus;
static jmethodID method_reportSvStatus;
static jmethodID method_reportAGpsStatus;
static jmethodID method_reportNmea;
static jmethodID method_setTopHalCapabilities;
static jmethodID method_setGnssYearOfHardware;
static jmethodID method_setGnssHardwareModelName;
static jmethodID method_psdsDownloadRequest;
static jmethodID method_reportNiNotification;
static jmethodID method_requestLocation;
static jmethodID method_requestRefLocation;
static jmethodID method_requestSetID;
static jmethodID method_requestUtcTime;
static jmethodID method_reportGeofenceTransition;
static jmethodID method_reportGeofenceStatus;
static jmethodID method_reportGeofenceAddStatus;
static jmethodID method_reportGeofenceRemoveStatus;
static jmethodID method_reportGeofencePauseStatus;
static jmethodID method_reportGeofenceResumeStatus;
static jmethodID method_reportMeasurementData;
static jmethodID method_reportNavigationMessages;
static jmethodID method_reportLocationBatch;
static jmethodID method_reportGnssServiceDied;
static jmethodID method_setSubHalMeasurementCorrectionsCapabilities;
static jmethodID method_correctionsGetLatitudeDegrees;
static jmethodID method_correctionsGetLongitudeDegrees;
static jmethodID method_correctionsGetAltitudeMeters;
static jmethodID method_correctionsGetHorPosUncMeters;
static jmethodID method_correctionsGetVerPosUncMeters;
static jmethodID method_correctionsGetToaGpsNanosecondsOfWeek;
static jmethodID method_correctionsGetSingleSatCorrectionList;
static jmethodID method_listSize;
static jmethodID method_correctionListGet;
static jmethodID method_correctionSatFlags;
static jmethodID method_correctionSatConstType;
static jmethodID method_correctionSatId;
static jmethodID method_correctionSatCarrierFreq;
static jmethodID method_correctionSatIsLosProb;
static jmethodID method_correctionSatEpl;
static jmethodID method_correctionSatEplUnc;
static jmethodID method_correctionSatRefPlane;
static jmethodID method_correctionPlaneLatDeg;
static jmethodID method_correctionPlaneLngDeg;
static jmethodID method_correctionPlaneAltDeg;
static jmethodID method_correctionPlaneAzimDeg;
static jmethodID method_reportNfwNotification;
static jmethodID method_isInEmergencySession;
static jmethodID method_gnssMeasurementsEventCtor;
static jmethodID method_locationCtor;
static jmethodID method_gnssNavigationMessageCtor;
static jmethodID method_gnssClockCtor;
static jmethodID method_gnssMeasurementCtor;
static jmethodID method_halInterfaceVersionCtor;

/*
 * Save a pointer to JavaVm to attach/detach threads executing
 * callback methods that need to make JNI calls.
 */
static JavaVM* sJvm;

using android::OK;
using android::sp;
using android::wp;
using android::status_t;
using android::String16;

using android::hardware::Return;
using android::hardware::Void;
using android::hardware::hidl_vec;
using android::hardware::hidl_string;
using android::hardware::hidl_death_recipient;

using android::hardware::gnss::V1_0::GnssConstellationType;
using android::hardware::gnss::V1_0::GnssLocationFlags;
using android::hardware::gnss::V1_0::IAGnssRilCallback;
using android::hardware::gnss::V1_0::IGnssGeofenceCallback;
using android::hardware::gnss::V1_0::IGnssGeofencing;
using android::hardware::gnss::V1_0::IGnssNavigationMessage;
using android::hardware::gnss::V1_0::IGnssNavigationMessageCallback;
using android::hardware::gnss::V1_0::IGnssNi;
using android::hardware::gnss::V1_0::IGnssNiCallback;
using android::hardware::gnss::V1_0::IGnssXtra;
using android::hardware::gnss::V1_0::IGnssXtraCallback;

using android::hardware::gnss::V2_0::ElapsedRealtimeFlags;
using android::hardware::gnss::V2_0::IGnssCallback;

using android::hardware::gnss::measurement_corrections::V1_0::MeasurementCorrections;
using android::hardware::gnss::measurement_corrections::V1_0::SingleSatCorrection;
using android::hardware::gnss::measurement_corrections::V1_0::ReflectingPlane;

using android::hidl::base::V1_0::IBase;

using GnssLocation_V1_0 = android::hardware::gnss::V1_0::GnssLocation;
using GnssLocation_V2_0 = android::hardware::gnss::V2_0::GnssLocation;
using IGnss_V1_0 = android::hardware::gnss::V1_0::IGnss;
using IGnss_V1_1 = android::hardware::gnss::V1_1::IGnss;
using IGnss_V2_0 = android::hardware::gnss::V2_0::IGnss;
using IGnssCallback_V1_0 = android::hardware::gnss::V1_0::IGnssCallback;
using IGnssConfiguration_V1_0 = android::hardware::gnss::V1_0::IGnssConfiguration;
using IGnssConfiguration_V1_1 = android::hardware::gnss::V1_1::IGnssConfiguration;
using IGnssConfiguration_V2_0 = android::hardware::gnss::V2_0::IGnssConfiguration;
using IGnssDebug_V1_0 = android::hardware::gnss::V1_0::IGnssDebug;
using IGnssDebug_V2_0 = android::hardware::gnss::V2_0::IGnssDebug;
using IGnssMeasurement_V1_0 = android::hardware::gnss::V1_0::IGnssMeasurement;
using IGnssMeasurement_V1_1 = android::hardware::gnss::V1_1::IGnssMeasurement;
using IGnssMeasurement_V2_0 = android::hardware::gnss::V2_0::IGnssMeasurement;
using IGnssMeasurementCallback_V1_0 = android::hardware::gnss::V1_0::IGnssMeasurementCallback;
using IGnssMeasurementCallback_V1_1 = android::hardware::gnss::V1_1::IGnssMeasurementCallback;
using IGnssMeasurementCallback_V2_0 = android::hardware::gnss::V2_0::IGnssMeasurementCallback;
using IAGnssRil_V1_0 = android::hardware::gnss::V1_0::IAGnssRil;
using IAGnssRil_V2_0 = android::hardware::gnss::V2_0::IAGnssRil;
using IAGnss_V1_0 = android::hardware::gnss::V1_0::IAGnss;
using IAGnss_V2_0 = android::hardware::gnss::V2_0::IAGnss;
using IAGnssCallback_V1_0 = android::hardware::gnss::V1_0::IAGnssCallback;
using IAGnssCallback_V2_0 = android::hardware::gnss::V2_0::IAGnssCallback;
using IGnssBatching_V1_0 = android::hardware::gnss::V1_0::IGnssBatching;
using IGnssBatching_V2_0 = android::hardware::gnss::V2_0::IGnssBatching;
using IGnssBatchingCallback_V1_0 = android::hardware::gnss::V1_0::IGnssBatchingCallback;
using IGnssBatchingCallback_V2_0 = android::hardware::gnss::V2_0::IGnssBatchingCallback;

using android::hardware::gnss::measurement_corrections::V1_0::IMeasurementCorrections;
using android::hardware::gnss::measurement_corrections::V1_0::IMeasurementCorrectionsCallback;
using android::hardware::gnss::measurement_corrections::V1_0::GnssSingleSatCorrectionFlags;

using android::hardware::gnss::visibility_control::V1_0::IGnssVisibilityControl;
using android::hardware::gnss::visibility_control::V1_0::IGnssVisibilityControlCallback;

struct GnssDeathRecipient : virtual public hidl_death_recipient
{
    // hidl_death_recipient interface
    virtual void serviceDied(uint64_t cookie, const wp<IBase>& who) override {
        ALOGE("IGNSS hidl service failed, trying to recover...");

        JNIEnv* env = android::AndroidRuntime::getJNIEnv();
        env->CallVoidMethod(mCallbacksObj, method_reportGnssServiceDied);
    }
};

// Must match the value from GnssMeasurement.java
static const uint32_t ADR_STATE_HALF_CYCLE_REPORTED = (1<<4);

sp<GnssDeathRecipient> gnssHalDeathRecipient = nullptr;
sp<IGnss_V1_0> gnssHal = nullptr;
sp<IGnss_V1_1> gnssHal_V1_1 = nullptr;
sp<IGnss_V2_0> gnssHal_V2_0 = nullptr;
sp<IGnssXtra> gnssXtraIface = nullptr;
sp<IAGnssRil_V1_0> agnssRilIface = nullptr;
sp<IAGnssRil_V2_0> agnssRilIface_V2_0 = nullptr;
sp<IGnssGeofencing> gnssGeofencingIface = nullptr;
sp<IAGnss_V1_0> agnssIface = nullptr;
sp<IAGnss_V2_0> agnssIface_V2_0 = nullptr;
sp<IGnssBatching_V1_0> gnssBatchingIface = nullptr;
sp<IGnssBatching_V2_0> gnssBatchingIface_V2_0 = nullptr;
sp<IGnssDebug_V1_0> gnssDebugIface = nullptr;
sp<IGnssDebug_V2_0> gnssDebugIface_V2_0 = nullptr;
sp<IGnssConfiguration_V1_0> gnssConfigurationIface = nullptr;
sp<IGnssConfiguration_V1_1> gnssConfigurationIface_V1_1 = nullptr;
sp<IGnssConfiguration_V2_0> gnssConfigurationIface_V2_0 = nullptr;
sp<IGnssNi> gnssNiIface = nullptr;
sp<IGnssMeasurement_V1_0> gnssMeasurementIface = nullptr;
sp<IGnssMeasurement_V1_1> gnssMeasurementIface_V1_1 = nullptr;
sp<IGnssMeasurement_V2_0> gnssMeasurementIface_V2_0 = nullptr;
sp<IGnssNavigationMessage> gnssNavigationMessageIface = nullptr;
sp<IMeasurementCorrections> gnssCorrectionsIface = nullptr;
sp<IGnssVisibilityControl> gnssVisibilityControlIface = nullptr;

#define WAKE_LOCK_NAME  "GPS"

namespace android {

namespace {

// Returns true if location has lat/long information.
bool hasLatLong(const GnssLocation_V1_0& location) {
    return (static_cast<uint32_t>(location.gnssLocationFlags) &
            GnssLocationFlags::HAS_LAT_LONG) != 0;
}

// Returns true if location has lat/long information.
bool hasLatLong(const GnssLocation_V2_0& location) {
    return hasLatLong(location.v1_0);
}

}  // namespace
template<class T>
class JavaMethodHelper {
 public:
    // Helper function to call setter on a Java object.
    static void callJavaMethod(
           JNIEnv* env,
           jclass clazz,
           jobject object,
           const char* method_name,
           T value);

 private:
    static const char* const signature_;
};

template<class T>
void JavaMethodHelper<T>::callJavaMethod(
        JNIEnv* env,
        jclass clazz,
        jobject object,
        const char* method_name,
        T value) {
    jmethodID method = env->GetMethodID(clazz, method_name, signature_);
    env->CallVoidMethod(object, method, value);
}

class JavaObject {
 public:
    JavaObject(JNIEnv* env, jclass clazz, jmethodID defaultCtor);
    JavaObject(JNIEnv* env, jclass clazz, jmethodID stringCtor, const char * sz_arg_1);
    JavaObject(JNIEnv* env, jclass clazz, jobject object);

    virtual ~JavaObject() = default;

    template<class T>
    void callSetter(const char* method_name, T value);
    template<class T>
    void callSetter(const char* method_name, T* value, size_t size);
    jobject get();

 private:
    JNIEnv* env_;
    jclass clazz_;
    jobject object_;
};

JavaObject::JavaObject(JNIEnv* env, jclass clazz, jmethodID defaultCtor) : env_(env),
        clazz_(clazz) {
    object_ = env_->NewObject(clazz_, defaultCtor);
}


JavaObject::JavaObject(JNIEnv* env, jclass clazz, jmethodID stringCtor, const char * sz_arg_1)
        : env_(env), clazz_(clazz) {
    object_ = env_->NewObject(clazz_, stringCtor, env->NewStringUTF(sz_arg_1));
}


JavaObject::JavaObject(JNIEnv* env, jclass clazz, jobject object)
    : env_(env), clazz_(clazz), object_(object) {
}

template<class T>
void JavaObject::callSetter(const char* method_name, T value) {
    JavaMethodHelper<T>::callJavaMethod(
            env_, clazz_, object_, method_name, value);
}

template<>
void JavaObject::callSetter(
        const char* method_name, uint8_t* value, size_t size) {
    jbyteArray array = env_->NewByteArray(size);
    env_->SetByteArrayRegion(array, 0, size, reinterpret_cast<jbyte*>(value));
    jmethodID method = env_->GetMethodID(
            clazz_,
            method_name,
            "([B)V");
    env_->CallVoidMethod(object_, method, array);
    env_->DeleteLocalRef(array);
}

jobject JavaObject::get() {
    return object_;
}

// Define Java method signatures for all known types.
template<>
const char *const JavaMethodHelper<uint8_t>::signature_ = "(B)V";
template<>
const char *const JavaMethodHelper<int8_t>::signature_ = "(B)V";
template<>
const char *const JavaMethodHelper<int16_t>::signature_ = "(S)V";
template<>
const char *const JavaMethodHelper<uint16_t>::signature_ = "(S)V";
template<>
const char *const JavaMethodHelper<int32_t>::signature_ = "(I)V";
template<>
const char *const JavaMethodHelper<uint32_t>::signature_ = "(I)V";
template<>
const char *const JavaMethodHelper<int64_t>::signature_ = "(J)V";
template<>
const char *const JavaMethodHelper<uint64_t>::signature_ = "(J)V";
template<>
const char *const JavaMethodHelper<float>::signature_ = "(F)V";
template<>
const char *const JavaMethodHelper<double>::signature_ = "(D)V";
template<>
const char *const JavaMethodHelper<bool>::signature_ = "(Z)V";
template<>
const char *const JavaMethodHelper<jstring>::signature_ = "(Ljava/lang/String;)V";

#define SET(setter, value) object.callSetter("set" # setter, (value))

static inline jboolean boolToJbool(bool value) {
    return value ? JNI_TRUE : JNI_FALSE;
}

static void checkAndClearExceptionFromCallback(JNIEnv* env, const char* methodName) {
    if (env->ExceptionCheck()) {
        ALOGE("An exception was thrown by callback '%s'.", methodName);
        LOGE_EX(env);
        env->ExceptionClear();
    }
}

static jobject createHalInterfaceVersionJavaObject(JNIEnv* env, jint major, jint minor) {
    jobject version = env->NewObject(class_gnssConfiguration_halInterfaceVersion,
            method_halInterfaceVersionCtor, major, minor);
    return version;
}

struct ScopedJniString {
    ScopedJniString(JNIEnv* env, jstring javaString) : mEnv(env), mJavaString(javaString) {
        mNativeString = mEnv->GetStringUTFChars(mJavaString, nullptr);
    }

    ~ScopedJniString() {
        if (mNativeString != nullptr) {
            mEnv->ReleaseStringUTFChars(mJavaString, mNativeString);
        }
    }

    const char* c_str() const {
        return mNativeString;
    }

    operator hidl_string() const {
        return hidl_string(mNativeString);
    }

private:
    ScopedJniString(const ScopedJniString&) = delete;
    ScopedJniString& operator=(const ScopedJniString&) = delete;

    JNIEnv* mEnv;
    jstring mJavaString;
    const char* mNativeString;
};

class ScopedJniThreadAttach {
public:
    ScopedJniThreadAttach() {
        /*
         * attachResult will also be JNI_OK if the thead was already attached to
         * JNI before the call to AttachCurrentThread().
         */
        jint attachResult = sJvm->AttachCurrentThread(&mEnv, nullptr);
        LOG_ALWAYS_FATAL_IF(attachResult != JNI_OK, "Unable to attach thread. Error %d",
                            attachResult);
    }

    ~ScopedJniThreadAttach() {
        jint detachResult = sJvm->DetachCurrentThread();
        /*
         * Return if the thread was already detached. Log error for any other
         * failure.
         */
        if (detachResult == JNI_EDETACHED) {
            return;
        }

        LOG_ALWAYS_FATAL_IF(detachResult != JNI_OK, "Unable to detach thread. Error %d",
                            detachResult);
    }

    JNIEnv* getEnv() {
        /*
         * Checking validity of mEnv in case the thread was detached elsewhere.
         */
        LOG_ALWAYS_FATAL_IF(AndroidRuntime::getJNIEnv() != mEnv);
        return mEnv;
    }

private:
    JNIEnv* mEnv = nullptr;
};

thread_local std::unique_ptr<ScopedJniThreadAttach> tJniThreadAttacher;

static JNIEnv* getJniEnv() {
    JNIEnv* env = AndroidRuntime::getJNIEnv();

    /*
     * If env is nullptr, the thread is not already attached to
     * JNI. It is attached below and the destructor for ScopedJniThreadAttach
     * will detach it on thread exit.
     */
    if (env == nullptr) {
        tJniThreadAttacher.reset(new ScopedJniThreadAttach());
        env = tJniThreadAttacher->getEnv();
    }

    return env;
}

static jobject translateGnssLocation(JNIEnv* env,
                                     const GnssLocation_V1_0& location) {
    JavaObject object(env, class_location, method_locationCtor, "gps");

    uint16_t flags = static_cast<uint32_t>(location.gnssLocationFlags);
    if (flags & GnssLocationFlags::HAS_LAT_LONG) {
        SET(Latitude, location.latitudeDegrees);
        SET(Longitude, location.longitudeDegrees);
    }
    if (flags & GnssLocationFlags::HAS_ALTITUDE) {
        SET(Altitude, location.altitudeMeters);
    }
    if (flags & GnssLocationFlags::HAS_SPEED) {
        SET(Speed, location.speedMetersPerSec);
    }
    if (flags & GnssLocationFlags::HAS_BEARING) {
        SET(Bearing, location.bearingDegrees);
    }
    if (flags & GnssLocationFlags::HAS_HORIZONTAL_ACCURACY) {
        SET(Accuracy, location.horizontalAccuracyMeters);
    }
    if (flags & GnssLocationFlags::HAS_VERTICAL_ACCURACY) {
        SET(VerticalAccuracyMeters, location.verticalAccuracyMeters);
    }
    if (flags & GnssLocationFlags::HAS_SPEED_ACCURACY) {
        SET(SpeedAccuracyMetersPerSecond, location.speedAccuracyMetersPerSecond);
    }
    if (flags & GnssLocationFlags::HAS_BEARING_ACCURACY) {
        SET(BearingAccuracyDegrees, location.bearingAccuracyDegrees);
    }
    SET(Time, location.timestamp);
    SET(ElapsedRealtimeNanos, android::elapsedRealtimeNano());

    return object.get();
}

static jobject translateGnssLocation(JNIEnv* env,
                                     const GnssLocation_V2_0& location) {
    JavaObject object(env, class_location, translateGnssLocation(env, location.v1_0));

    const uint16_t flags = static_cast<uint16_t>(location.elapsedRealtime.flags);

    // Overwrite ElapsedRealtimeNanos when available from HAL.
    if (flags & ElapsedRealtimeFlags::HAS_TIMESTAMP_NS) {
        SET(ElapsedRealtimeNanos, location.elapsedRealtime.timestampNs);
    }

    if (flags & ElapsedRealtimeFlags::HAS_TIME_UNCERTAINTY_NS) {
        SET(ElapsedRealtimeUncertaintyNanos, static_cast<double>(location.elapsedRealtime.timeUncertaintyNs));
    }

    return object.get();
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
struct GnssCallback : public IGnssCallback {
    Return<void> gnssLocationCb(const GnssLocation_V1_0& location) override;
    Return<void> gnssStatusCb(const IGnssCallback::GnssStatusValue status) override;
    Return<void> gnssSvStatusCb(const IGnssCallback_V1_0::GnssSvStatus& svStatus) override {
        return gnssSvStatusCbImpl(svStatus);
    }
    Return<void> gnssNmeaCb(int64_t timestamp, const android::hardware::hidl_string& nmea) override;
    Return<void> gnssSetCapabilitesCb(uint32_t capabilities) override;
    Return<void> gnssAcquireWakelockCb() override;
    Return<void> gnssReleaseWakelockCb() override;
    Return<void> gnssRequestTimeCb() override;
    Return<void> gnssRequestLocationCb(const bool independentFromGnss) override;

    Return<void> gnssSetSystemInfoCb(const IGnssCallback::GnssSystemInfo& info) override;

    // New in 1.1
    Return<void> gnssNameCb(const android::hardware::hidl_string& name) override;

    // New in 2.0
    Return<void> gnssRequestLocationCb_2_0(const bool independentFromGnss, const bool isUserEmergency)
            override;
    Return<void> gnssSetCapabilitiesCb_2_0(uint32_t capabilities) override;
    Return<void> gnssLocationCb_2_0(const GnssLocation_V2_0& location) override;
    Return<void> gnssSvStatusCb_2_0(const hidl_vec<IGnssCallback::GnssSvInfo>& svInfoList) override {
        return gnssSvStatusCbImpl(svInfoList);
    }

    Return<void> gnssSetCapabilitesCbImpl(uint32_t capabilities, bool hasSubHalCapabilityFlags);

    // TODO: Reconsider allocation cost vs threadsafety on these statics
    static const char* sNmeaString;
    static size_t sNmeaStringLength;
private:
    template<class T>
    Return<void> gnssLocationCbImpl(const T& location);

    template<class T>
    Return<void> gnssSvStatusCbImpl(const T& svStatus);

    uint32_t getGnssSvInfoListSize(const IGnssCallback_V1_0::GnssSvStatus& svStatus) {
        return svStatus.numSvs;
    }

    uint32_t getGnssSvInfoListSize(const hidl_vec<IGnssCallback::GnssSvInfo>& svInfoList) {
        return svInfoList.size();
    }

    const IGnssCallback_V1_0::GnssSvInfo& getGnssSvInfoOfIndex(
            const IGnssCallback_V1_0::GnssSvStatus& svStatus, size_t i) {
        return svStatus.gnssSvList.data()[i];
    }

    const IGnssCallback_V1_0::GnssSvInfo& getGnssSvInfoOfIndex(
            const hidl_vec<IGnssCallback::GnssSvInfo>& svInfoList, size_t i) {
        return svInfoList[i].v1_0;
    }

    uint32_t getConstellationType(const IGnssCallback_V1_0::GnssSvStatus& svStatus, size_t i) {
        return static_cast<uint32_t>(svStatus.gnssSvList.data()[i].constellation);
    }

    uint32_t getConstellationType(const hidl_vec<IGnssCallback::GnssSvInfo>& svInfoList, size_t i) {
        return static_cast<uint32_t>(svInfoList[i].constellation);
    }
};

Return<void> GnssCallback::gnssNameCb(const android::hardware::hidl_string& name) {
    ALOGD("%s: name=%s\n", __func__, name.c_str());

    JNIEnv* env = getJniEnv();
    jstring jstringName = env->NewStringUTF(name.c_str());
    env->CallVoidMethod(mCallbacksObj, method_setGnssHardwareModelName, jstringName);
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

Return<void> GnssCallback::gnssStatusCb(const IGnssCallback::GnssStatusValue status) {
    JNIEnv* env = getJniEnv();
    env->CallVoidMethod(mCallbacksObj, method_reportStatus, status);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return Void();
}

template<class T>
Return<void> GnssCallback::gnssSvStatusCbImpl(const T& svStatus) {
    JNIEnv* env = getJniEnv();

    uint32_t listSize = getGnssSvInfoListSize(svStatus);
    if (listSize > static_cast<uint32_t>(
            android::hardware::gnss::V1_0::GnssMax::SVS_COUNT)) {
        ALOGD("Too many satellites %u. Clamps to %u.", listSize,
              static_cast<uint32_t>(android::hardware::gnss::V1_0::GnssMax::SVS_COUNT));
        listSize = static_cast<uint32_t>(android::hardware::gnss::V1_0::GnssMax::SVS_COUNT);
    }

    jintArray svidWithFlagArray = env->NewIntArray(listSize);
    jfloatArray cn0Array = env->NewFloatArray(listSize);
    jfloatArray elevArray = env->NewFloatArray(listSize);
    jfloatArray azimArray = env->NewFloatArray(listSize);
    jfloatArray carrierFreqArray = env->NewFloatArray(listSize);

    jint* svidWithFlags = env->GetIntArrayElements(svidWithFlagArray, 0);
    jfloat* cn0s = env->GetFloatArrayElements(cn0Array, 0);
    jfloat* elev = env->GetFloatArrayElements(elevArray, 0);
    jfloat* azim = env->GetFloatArrayElements(azimArray, 0);
    jfloat* carrierFreq = env->GetFloatArrayElements(carrierFreqArray, 0);

    /*
     * Read GNSS SV info.
     */
    for (size_t i = 0; i < listSize; ++i) {
        enum ShiftWidth: uint8_t {
            SVID_SHIFT_WIDTH = 8,
            CONSTELLATION_TYPE_SHIFT_WIDTH = 4
        };

        const IGnssCallback_V1_0::GnssSvInfo& info = getGnssSvInfoOfIndex(svStatus, i);
        svidWithFlags[i] = (info.svid << SVID_SHIFT_WIDTH) |
            (getConstellationType(svStatus, i) << CONSTELLATION_TYPE_SHIFT_WIDTH) |
            static_cast<uint32_t>(info.svFlag);
        cn0s[i] = info.cN0Dbhz;
        elev[i] = info.elevationDegrees;
        azim[i] = info.azimuthDegrees;
        carrierFreq[i] = info.carrierFrequencyHz;
    }

    env->ReleaseIntArrayElements(svidWithFlagArray, svidWithFlags, 0);
    env->ReleaseFloatArrayElements(cn0Array, cn0s, 0);
    env->ReleaseFloatArrayElements(elevArray, elev, 0);
    env->ReleaseFloatArrayElements(azimArray, azim, 0);
    env->ReleaseFloatArrayElements(carrierFreqArray, carrierFreq, 0);

    env->CallVoidMethod(mCallbacksObj, method_reportSvStatus,
            static_cast<jint>(listSize), svidWithFlagArray, cn0Array, elevArray, azimArray,
            carrierFreqArray);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return Void();
}

Return<void> GnssCallback::gnssNmeaCb(
    int64_t timestamp, const ::android::hardware::hidl_string& nmea) {
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

Return<void> GnssCallback::gnssSetSystemInfoCb(const IGnssCallback::GnssSystemInfo& info) {
    ALOGD("%s: yearOfHw=%d\n", __func__, info.yearOfHw);

    JNIEnv* env = getJniEnv();
    env->CallVoidMethod(mCallbacksObj, method_setGnssYearOfHardware,
                        info.yearOfHw);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return Void();
}

class GnssXtraCallback : public IGnssXtraCallback {
    Return<void> downloadRequestCb() override;
};

/*
 * GnssXtraCallback class implements the callback methods for the IGnssXtra
 * interface.
 */
Return<void> GnssXtraCallback::downloadRequestCb() {
    JNIEnv* env = getJniEnv();
    env->CallVoidMethod(mCallbacksObj, method_psdsDownloadRequest);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return Void();
}

/*
 * GnssGeofenceCallback class implements the callback methods for the
 * IGnssGeofence interface.
 */
struct GnssGeofenceCallback : public IGnssGeofenceCallback {
    // Methods from ::android::hardware::gps::V1_0::IGnssGeofenceCallback follow.
    Return<void> gnssGeofenceTransitionCb(
            int32_t geofenceId,
            const GnssLocation_V1_0& location,
            GeofenceTransition transition,
            hardware::gnss::V1_0::GnssUtcTime timestamp) override;
    Return<void>
    gnssGeofenceStatusCb(
            GeofenceAvailability status,
            const GnssLocation_V1_0& location) override;
    Return<void> gnssGeofenceAddCb(int32_t geofenceId,
                                   GeofenceStatus status) override;
    Return<void> gnssGeofenceRemoveCb(int32_t geofenceId,
                                      GeofenceStatus status) override;
    Return<void> gnssGeofencePauseCb(int32_t geofenceId,
                                     GeofenceStatus status) override;
    Return<void> gnssGeofenceResumeCb(int32_t geofenceId,
                                      GeofenceStatus status) override;
};

Return<void> GnssGeofenceCallback::gnssGeofenceTransitionCb(
        int32_t geofenceId, const GnssLocation_V1_0& location,
        GeofenceTransition transition,
        hardware::gnss::V1_0::GnssUtcTime timestamp) {
    JNIEnv* env = getJniEnv();

    jobject jLocation = translateGnssLocation(env, location);

    env->CallVoidMethod(mCallbacksObj,
                        method_reportGeofenceTransition,
                        geofenceId,
                        jLocation,
                        transition,
                        timestamp);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    env->DeleteLocalRef(jLocation);
    return Void();
}

Return<void>
GnssGeofenceCallback::gnssGeofenceStatusCb(GeofenceAvailability status,
                                           const GnssLocation_V1_0& location) {
    JNIEnv* env = getJniEnv();

    jobject jLocation = translateGnssLocation(env, location);

    env->CallVoidMethod(mCallbacksObj, method_reportGeofenceStatus, status,
                        jLocation);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    env->DeleteLocalRef(jLocation);
    return Void();
}

Return<void> GnssGeofenceCallback::gnssGeofenceAddCb(int32_t geofenceId,
                                                    GeofenceStatus status) {
    JNIEnv* env = getJniEnv();
    if (status != IGnssGeofenceCallback::GeofenceStatus::OPERATION_SUCCESS) {
        ALOGE("%s: Error in adding a Geofence: %d\n", __func__, status);
    }

    env->CallVoidMethod(mCallbacksObj,
                        method_reportGeofenceAddStatus,
                        geofenceId,
                        status);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return Void();
}

Return<void> GnssGeofenceCallback::gnssGeofenceRemoveCb(int32_t geofenceId,
                                                       GeofenceStatus status) {
    JNIEnv* env = getJniEnv();
    if (status != IGnssGeofenceCallback::GeofenceStatus::OPERATION_SUCCESS) {
        ALOGE("%s: Error in removing a Geofence: %d\n", __func__, status);
    }

    env->CallVoidMethod(mCallbacksObj,
                        method_reportGeofenceRemoveStatus,
                        geofenceId, status);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return Void();
}

Return<void> GnssGeofenceCallback::gnssGeofencePauseCb(int32_t geofenceId,
                                                      GeofenceStatus status) {
    JNIEnv* env = getJniEnv();
    if (status != IGnssGeofenceCallback::GeofenceStatus::OPERATION_SUCCESS) {
        ALOGE("%s: Error in pausing Geofence: %d\n", __func__, status);
    }

    env->CallVoidMethod(mCallbacksObj,
                        method_reportGeofencePauseStatus,
                        geofenceId, status);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return Void();
}

Return<void> GnssGeofenceCallback::gnssGeofenceResumeCb(int32_t geofenceId,
                                                       GeofenceStatus status) {
    JNIEnv* env = getJniEnv();
    if (status != IGnssGeofenceCallback::GeofenceStatus::OPERATION_SUCCESS) {
        ALOGE("%s: Error in resuming Geofence: %d\n", __func__, status);
    }

    env->CallVoidMethod(mCallbacksObj,
                        method_reportGeofenceResumeStatus,
                        geofenceId, status);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return Void();
}

/*
 * GnssNavigationMessageCallback interface implements the callback methods
 * required by the IGnssNavigationMessage interface.
 */
struct GnssNavigationMessageCallback : public IGnssNavigationMessageCallback {
  /*
   * Methods from ::android::hardware::gps::V1_0::IGnssNavigationMessageCallback
   * follow.
   */
  Return<void> gnssNavigationMessageCb(
          const IGnssNavigationMessageCallback::GnssNavigationMessage& message) override;
};

Return<void> GnssNavigationMessageCallback::gnssNavigationMessageCb(
        const IGnssNavigationMessageCallback::GnssNavigationMessage& message) {
    JNIEnv* env = getJniEnv();

    size_t dataLength = message.data.size();

    std::vector<uint8_t> navigationData = message.data;
    uint8_t* data = &(navigationData[0]);
    if (dataLength == 0 || data == nullptr) {
      ALOGE("Invalid Navigation Message found: data=%p, length=%zd", data,
            dataLength);
      return Void();
    }

    JavaObject object(env, class_gnssNavigationMessage, method_gnssNavigationMessageCtor);
    SET(Type, static_cast<int32_t>(message.type));
    SET(Svid, static_cast<int32_t>(message.svid));
    SET(MessageId, static_cast<int32_t>(message.messageId));
    SET(SubmessageId, static_cast<int32_t>(message.submessageId));
    object.callSetter("setData", data, dataLength);
    SET(Status, static_cast<int32_t>(message.status));

    jobject navigationMessage = object.get();
    env->CallVoidMethod(mCallbacksObj,
                        method_reportNavigationMessages,
                        navigationMessage);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    env->DeleteLocalRef(navigationMessage);
    return Void();
}

/*
 * GnssMeasurementCallback implements the callback methods required for the
 * GnssMeasurement interface.
 */
struct GnssMeasurementCallback : public IGnssMeasurementCallback_V2_0 {
    Return<void> gnssMeasurementCb_2_0(const IGnssMeasurementCallback_V2_0::GnssData& data)
            override;
    Return<void> gnssMeasurementCb(const IGnssMeasurementCallback_V1_1::GnssData& data) override;
    Return<void> GnssMeasurementCb(const IGnssMeasurementCallback_V1_0::GnssData& data) override;
 private:
    template<class T>
    void translateSingleGnssMeasurement(const T* measurement, JavaObject& object);

    template<class T>
    jobjectArray translateAllGnssMeasurements(JNIEnv* env, const T* measurements, size_t count);

    template<class T>
    void translateAndSetGnssData(const T& data);

    template<class T>
    size_t getMeasurementCount(const T& data);

    template<class T>
    void translateGnssClock(JavaObject& object, const T& data);

    void setMeasurementData(JNIEnv* env, jobject clock, jobjectArray measurementArray);
};

Return<void> GnssMeasurementCallback::gnssMeasurementCb_2_0(
        const IGnssMeasurementCallback_V2_0::GnssData& data) {
    translateAndSetGnssData(data);
    return Void();
}

Return<void> GnssMeasurementCallback::gnssMeasurementCb(
        const IGnssMeasurementCallback_V1_1::GnssData& data) {
    translateAndSetGnssData(data);
    return Void();
}

Return<void> GnssMeasurementCallback::GnssMeasurementCb(
        const IGnssMeasurementCallback_V1_0::GnssData& data) {
    translateAndSetGnssData(data);
    return Void();
}

template<class T>
void GnssMeasurementCallback::translateAndSetGnssData(const T& data) {
    JNIEnv* env = getJniEnv();

    JavaObject gnssClockJavaObject(env, class_gnssClock, method_gnssClockCtor);
    translateGnssClock(gnssClockJavaObject, data);
    jobject clock = gnssClockJavaObject.get();

    size_t count = getMeasurementCount(data);
    jobjectArray measurementArray = translateAllGnssMeasurements(env, data.measurements.data(), count);
    setMeasurementData(env, clock, measurementArray);

    env->DeleteLocalRef(clock);
    env->DeleteLocalRef(measurementArray);
}

template<>
size_t GnssMeasurementCallback::getMeasurementCount<IGnssMeasurementCallback_V1_0::GnssData>
        (const IGnssMeasurementCallback_V1_0::GnssData& data) {
    return data.measurementCount;
}

template<class T>
size_t GnssMeasurementCallback::getMeasurementCount(const T& data) {
    return data.measurements.size();
}

// Preallocate object as: JavaObject object(env, "android/location/GnssMeasurement");
template<>
void GnssMeasurementCallback::translateSingleGnssMeasurement
        <IGnssMeasurementCallback_V1_0::GnssMeasurement>(
        const IGnssMeasurementCallback_V1_0::GnssMeasurement* measurement,
        JavaObject& object) {
    uint32_t flags = static_cast<uint32_t>(measurement->flags);

    SET(Svid, static_cast<int32_t>(measurement->svid));
    SET(ConstellationType, static_cast<int32_t>(measurement->constellation));
    SET(TimeOffsetNanos, measurement->timeOffsetNs);
    SET(State, static_cast<int32_t>(measurement->state));
    SET(ReceivedSvTimeNanos, measurement->receivedSvTimeInNs);
    SET(ReceivedSvTimeUncertaintyNanos,
        measurement->receivedSvTimeUncertaintyInNs);
    SET(Cn0DbHz, measurement->cN0DbHz);
    SET(PseudorangeRateMetersPerSecond, measurement->pseudorangeRateMps);
    SET(PseudorangeRateUncertaintyMetersPerSecond,
        measurement->pseudorangeRateUncertaintyMps);
    SET(AccumulatedDeltaRangeState,
        (static_cast<int32_t>(measurement->accumulatedDeltaRangeState) &
        ~ADR_STATE_HALF_CYCLE_REPORTED)); // Half Cycle state not reported from Hardware in V1_0
    SET(AccumulatedDeltaRangeMeters, measurement->accumulatedDeltaRangeM);
    SET(AccumulatedDeltaRangeUncertaintyMeters,
        measurement->accumulatedDeltaRangeUncertaintyM);

    if (flags & static_cast<uint32_t>(GnssMeasurementFlags::HAS_CARRIER_FREQUENCY)) {
        SET(CarrierFrequencyHz, measurement->carrierFrequencyHz);
    }

    // Intentionally not copying deprecated fields of carrierCycles,
    // carrierPhase, carrierPhaseUncertainty

    SET(MultipathIndicator, static_cast<int32_t>(measurement->multipathIndicator));

    if (flags & static_cast<uint32_t>(GnssMeasurementFlags::HAS_SNR)) {
        SET(SnrInDb, measurement->snrDb);
    }

    if (flags & static_cast<uint32_t>(GnssMeasurementFlags::HAS_AUTOMATIC_GAIN_CONTROL)) {
        SET(AutomaticGainControlLevelInDb, measurement->agcLevelDb);
    }
}

// Preallocate object as: JavaObject object(env, "android/location/GnssMeasurement");
template<>
void GnssMeasurementCallback::translateSingleGnssMeasurement
        <IGnssMeasurementCallback_V1_1::GnssMeasurement>(
        const IGnssMeasurementCallback_V1_1::GnssMeasurement* measurement_V1_1,
        JavaObject& object) {
    translateSingleGnssMeasurement(&(measurement_V1_1->v1_0), object);

    // Set the V1_1 flag, and mark that new field has valid information for Java Layer
    SET(AccumulatedDeltaRangeState,
            (static_cast<int32_t>(measurement_V1_1->accumulatedDeltaRangeState) |
            ADR_STATE_HALF_CYCLE_REPORTED));
}

// Preallocate object as: JavaObject object(env, "android/location/GnssMeasurement");
template<>
void GnssMeasurementCallback::translateSingleGnssMeasurement
        <IGnssMeasurementCallback_V2_0::GnssMeasurement>(
        const IGnssMeasurementCallback_V2_0::GnssMeasurement* measurement_V2_0,
        JavaObject& object) {
    JNIEnv* env = getJniEnv();
    translateSingleGnssMeasurement(&(measurement_V2_0->v1_1), object);

    SET(CodeType, env->NewStringUTF(measurement_V2_0->codeType.c_str()));

    // Overwrite with v2_0.state since v2_0->v1_1->v1_0.state is deprecated.
    SET(State, static_cast<int32_t>(measurement_V2_0->state));

    // Overwrite with v2_0.constellation since v2_0->v1_1->v1_0.constellation is deprecated.
    SET(ConstellationType, static_cast<int32_t>(measurement_V2_0->constellation));
}

template<class T>
void GnssMeasurementCallback::translateGnssClock(JavaObject& object, const T& data) {
    translateGnssClock(object, data.clock);
}

template<>
void GnssMeasurementCallback::translateGnssClock(
       JavaObject& object, const IGnssMeasurementCallback_V1_0::GnssClock& clock) {
    uint32_t flags = static_cast<uint32_t>(clock.gnssClockFlags);
    if (flags & static_cast<uint32_t>(GnssClockFlags::HAS_LEAP_SECOND)) {
        SET(LeapSecond, static_cast<int32_t>(clock.leapSecond));
    }

    if (flags & static_cast<uint32_t>(GnssClockFlags::HAS_TIME_UNCERTAINTY)) {
        SET(TimeUncertaintyNanos, clock.timeUncertaintyNs);
    }

    if (flags & static_cast<uint32_t>(GnssClockFlags::HAS_FULL_BIAS)) {
        SET(FullBiasNanos, clock.fullBiasNs);
    }

    if (flags & static_cast<uint32_t>(GnssClockFlags::HAS_BIAS)) {
        SET(BiasNanos, clock.biasNs);
    }

    if (flags & static_cast<uint32_t>(GnssClockFlags::HAS_BIAS_UNCERTAINTY)) {
        SET(BiasUncertaintyNanos, clock.biasUncertaintyNs);
    }

    if (flags & static_cast<uint32_t>(GnssClockFlags::HAS_DRIFT)) {
        SET(DriftNanosPerSecond, clock.driftNsps);
    }

    if (flags & static_cast<uint32_t>(GnssClockFlags::HAS_DRIFT_UNCERTAINTY)) {
        SET(DriftUncertaintyNanosPerSecond, clock.driftUncertaintyNsps);
    }

    SET(TimeNanos, clock.timeNs);
    SET(HardwareClockDiscontinuityCount, clock.hwClockDiscontinuityCount);
}

template<>
void GnssMeasurementCallback::translateGnssClock(
       JavaObject& object, const IGnssMeasurementCallback_V2_0::GnssData& data) {
    auto elapsedRealtime = data.elapsedRealtime;
    uint16_t flags = static_cast<uint16_t>(elapsedRealtime.flags);
    if (flags & ElapsedRealtimeFlags::HAS_TIMESTAMP_NS) {
        SET(ElapsedRealtimeNanos, static_cast<uint64_t>(elapsedRealtime.timestampNs));
    }
    if (flags & ElapsedRealtimeFlags::HAS_TIME_UNCERTAINTY_NS) {
        SET(ElapsedRealtimeUncertaintyNanos, static_cast<double>(elapsedRealtime.timeUncertaintyNs));
    }
    translateGnssClock(object, data.clock);
}

template<class T>
jobjectArray GnssMeasurementCallback::translateAllGnssMeasurements(JNIEnv* env,
        const T* measurements,
        size_t count) {
    if (count == 0) {
        return nullptr;
    }

    jobjectArray gnssMeasurementArray = env->NewObjectArray(
            count,
            class_gnssMeasurement,
            nullptr /* initialElement */);

    for (uint16_t i = 0; i < count; ++i) {
        JavaObject object(env, class_gnssMeasurement, method_gnssMeasurementCtor);
        translateSingleGnssMeasurement(&(measurements[i]), object);
        env->SetObjectArrayElement(gnssMeasurementArray, i, object.get());
    }

    return gnssMeasurementArray;
}

void GnssMeasurementCallback::setMeasurementData(JNIEnv* env, jobject clock,
                             jobjectArray measurementArray) {
    jobject gnssMeasurementsEvent = env->NewObject(class_gnssMeasurementsEvent,
                                                   method_gnssMeasurementsEventCtor,
                                                   clock,
                                                   measurementArray);

    env->CallVoidMethod(mCallbacksObj, method_reportMeasurementData,
                      gnssMeasurementsEvent);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    env->DeleteLocalRef(gnssMeasurementsEvent);
}

/*
 * MeasurementCorrectionsCallback implements callback methods of interface
 * IMeasurementCorrectionsCallback.hal.
 */
struct MeasurementCorrectionsCallback : public IMeasurementCorrectionsCallback {
    Return<void> setCapabilitiesCb(uint32_t capabilities) override;
};

Return<void> MeasurementCorrectionsCallback::setCapabilitiesCb(uint32_t capabilities) {
    ALOGD("%s: %du\n", __func__, capabilities);
    JNIEnv* env = getJniEnv();
    env->CallVoidMethod(mCallbacksObj, method_setSubHalMeasurementCorrectionsCapabilities,
                        capabilities);
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

/*
 * GnssVisibilityControlCallback implements callback methods of IGnssVisibilityControlCallback.hal.
 */
struct GnssVisibilityControlCallback : public IGnssVisibilityControlCallback {
    Return<void> nfwNotifyCb(const IGnssVisibilityControlCallback::NfwNotification& notification)
            override;
    Return<bool> isInEmergencySession() override;
};

Return<void> GnssVisibilityControlCallback::nfwNotifyCb(
        const IGnssVisibilityControlCallback::NfwNotification& notification) {
    JNIEnv* env = getJniEnv();
    jstring proxyAppPackageName = env->NewStringUTF(notification.proxyAppPackageName.c_str());
    jstring otherProtocolStackName = env->NewStringUTF(notification.otherProtocolStackName.c_str());
    jstring requestorId = env->NewStringUTF(notification.requestorId.c_str());

    if (proxyAppPackageName && otherProtocolStackName && requestorId) {
        env->CallVoidMethod(mCallbacksObj, method_reportNfwNotification, proxyAppPackageName,
                            notification.protocolStack, otherProtocolStackName,
                            notification.requestor, requestorId, notification.responseType,
                            notification.inEmergencyMode, notification.isCachedLocation);
    } else {
        ALOGE("%s: OOM Error\n", __func__);
    }

    if (requestorId) {
        env->DeleteLocalRef(requestorId);
    }

    if (otherProtocolStackName) {
        env->DeleteLocalRef(otherProtocolStackName);
    }

    if (proxyAppPackageName) {
        env->DeleteLocalRef(proxyAppPackageName);
    }

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return Void();
}

Return<bool> GnssVisibilityControlCallback::isInEmergencySession() {
    JNIEnv* env = getJniEnv();
    auto result = env->CallBooleanMethod(mCallbacksObj, method_isInEmergencySession);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return result;
}

/*
 * AGnssCallback_V1_0 implements callback methods required by the IAGnssCallback 1.0 interface.
 */
struct AGnssCallback_V1_0 : public IAGnssCallback_V1_0 {
    // Methods from ::android::hardware::gps::V1_0::IAGnssCallback follow.
    Return<void> agnssStatusIpV6Cb(
      const IAGnssCallback_V1_0::AGnssStatusIpV6& agps_status) override;

    Return<void> agnssStatusIpV4Cb(
      const IAGnssCallback_V1_0::AGnssStatusIpV4& agps_status) override;
 private:
    jbyteArray convertToIpV4(uint32_t ip);
};

Return<void> AGnssCallback_V1_0::agnssStatusIpV6Cb(
        const IAGnssCallback_V1_0::AGnssStatusIpV6& agps_status) {
    JNIEnv* env = getJniEnv();
    jbyteArray byteArray = nullptr;

    byteArray = env->NewByteArray(16);
    if (byteArray != nullptr) {
        env->SetByteArrayRegion(byteArray, 0, 16,
                                (const jbyte*)(agps_status.ipV6Addr.data()));
    } else {
        ALOGE("Unable to allocate byte array for IPv6 address.");
    }

    IF_ALOGD() {
        // log the IP for reference in case there is a bogus value pushed by HAL
        char str[INET6_ADDRSTRLEN];
        inet_ntop(AF_INET6, agps_status.ipV6Addr.data(), str, INET6_ADDRSTRLEN);
        ALOGD("AGPS IP is v6: %s", str);
    }

    jsize byteArrayLength = byteArray != nullptr ? env->GetArrayLength(byteArray) : 0;
    ALOGV("Passing AGPS IP addr: size %d", byteArrayLength);
    env->CallVoidMethod(mCallbacksObj, method_reportAGpsStatus,
                        agps_status.type, agps_status.status, byteArray);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);

    if (byteArray) {
        env->DeleteLocalRef(byteArray);
    }

    return Void();
}

Return<void> AGnssCallback_V1_0::agnssStatusIpV4Cb(
        const IAGnssCallback_V1_0::AGnssStatusIpV4& agps_status) {
    JNIEnv* env = getJniEnv();
    jbyteArray byteArray = nullptr;

    uint32_t ipAddr = agps_status.ipV4Addr;
    byteArray = convertToIpV4(ipAddr);

    IF_ALOGD() {
        /*
         * log the IP for reference in case there is a bogus value pushed by
         * HAL.
         */
        char str[INET_ADDRSTRLEN];
        inet_ntop(AF_INET, &ipAddr, str, INET_ADDRSTRLEN);
        ALOGD("AGPS IP is v4: %s", str);
    }

    jsize byteArrayLength =
      byteArray != nullptr ? env->GetArrayLength(byteArray) : 0;
    ALOGV("Passing AGPS IP addr: size %d", byteArrayLength);
    env->CallVoidMethod(mCallbacksObj, method_reportAGpsStatus,
                      agps_status.type, agps_status.status, byteArray);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);

    if (byteArray) {
        env->DeleteLocalRef(byteArray);
    }
    return Void();
}

jbyteArray AGnssCallback_V1_0::convertToIpV4(uint32_t ip) {
    if (INADDR_NONE == ip) {
        return nullptr;
    }

    JNIEnv* env = getJniEnv();
    jbyteArray byteArray = env->NewByteArray(4);
    if (byteArray == nullptr) {
        ALOGE("Unable to allocate byte array for IPv4 address");
        return nullptr;
    }

    jbyte ipv4[4];
    ALOGV("Converting IPv4 address byte array (net_order) %x", ip);
    memcpy(ipv4, &ip, sizeof(ipv4));
    env->SetByteArrayRegion(byteArray, 0, 4, (const jbyte*)ipv4);
    return byteArray;
}

/*
 * AGnssCallback_V2_0 implements callback methods required by the IAGnssCallback 2.0 interface.
 */
struct AGnssCallback_V2_0 : public IAGnssCallback_V2_0 {
    // Methods from ::android::hardware::gps::V2_0::IAGnssCallback follow.
    Return<void> agnssStatusCb(IAGnssCallback_V2_0::AGnssType type,
        IAGnssCallback_V2_0::AGnssStatusValue status) override;
};

Return<void> AGnssCallback_V2_0::agnssStatusCb(IAGnssCallback_V2_0::AGnssType type,
        IAGnssCallback_V2_0::AGnssStatusValue status) {
    JNIEnv* env = getJniEnv();
    env->CallVoidMethod(mCallbacksObj, method_reportAGpsStatus, type, status, nullptr);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return Void();
}

/*
 * AGnssRilCallback implements the callback methods required by the AGnssRil
 * interface.
 */
struct AGnssRilCallback : IAGnssRilCallback {
    Return<void> requestSetIdCb(uint32_t setIdFlag) override;
    Return<void> requestRefLocCb() override;
};

Return<void> AGnssRilCallback::requestSetIdCb(uint32_t setIdFlag) {
    JNIEnv* env = getJniEnv();
    env->CallVoidMethod(mCallbacksObj, method_requestSetID, setIdFlag);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return Void();
}

Return<void> AGnssRilCallback::requestRefLocCb() {
    JNIEnv* env = getJniEnv();
    env->CallVoidMethod(mCallbacksObj, method_requestRefLocation);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return Void();
}

struct GnssBatchingCallbackUtil {
    template<class T>
    static Return<void> gnssLocationBatchCbImpl(const hidl_vec<T>& locations);
private:
    GnssBatchingCallbackUtil() = delete;
};

template<class T>
Return<void> GnssBatchingCallbackUtil::gnssLocationBatchCbImpl(const hidl_vec<T>& locations) {
    JNIEnv* env = getJniEnv();

    jobjectArray jLocations = env->NewObjectArray(locations.size(), class_location, nullptr);

    for (uint16_t i = 0; i < locations.size(); ++i) {
        jobject jLocation = translateGnssLocation(env, locations[i]);
        env->SetObjectArrayElement(jLocations, i, jLocation);
        env->DeleteLocalRef(jLocation);
    }

    env->CallVoidMethod(mCallbacksObj, method_reportLocationBatch, jLocations);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);

    env->DeleteLocalRef(jLocations);

    return Void();
}

/*
 * GnssBatchingCallback_V1_0 class implements the callback methods required by the
 * IGnssBatching 1.0 interface.
 */
struct GnssBatchingCallback_V1_0 : public IGnssBatchingCallback_V1_0 {
    /** Methods from ::android::hardware::gps::V1_0::IGnssBatchingCallback follow. */
    Return<void> gnssLocationBatchCb(const hidl_vec<GnssLocation_V1_0>& locations) override {
        return GnssBatchingCallbackUtil::gnssLocationBatchCbImpl(locations);
    }
};

/*
 * GnssBatchingCallback_V2_0 class implements the callback methods required by the
 * IGnssBatching 2.0 interface.
 */
struct GnssBatchingCallback_V2_0 : public IGnssBatchingCallback_V2_0 {
    /** Methods from ::android::hardware::gps::V2_0::IGnssBatchingCallback follow. */
    Return<void> gnssLocationBatchCb(const hidl_vec<GnssLocation_V2_0>& locations) override {
        return GnssBatchingCallbackUtil::gnssLocationBatchCbImpl(locations);
    }
};

/* Initializes the GNSS service handle. */
static void android_location_GnssLocationProvider_set_gps_service_handle() {
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
static void android_location_GnssLocationProvider_class_init_native(JNIEnv* env, jclass clazz) {
    // Initialize the top level gnss HAL handle.
    android_location_GnssLocationProvider_set_gps_service_handle();

    // Cache methodIDs and class IDs.
    method_reportLocation = env->GetMethodID(clazz, "reportLocation",
            "(ZLandroid/location/Location;)V");
    method_reportStatus = env->GetMethodID(clazz, "reportStatus", "(I)V");
    method_reportSvStatus = env->GetMethodID(clazz, "reportSvStatus", "(I[I[F[F[F[F)V");
    method_reportAGpsStatus = env->GetMethodID(clazz, "reportAGpsStatus", "(II[B)V");
    method_reportNmea = env->GetMethodID(clazz, "reportNmea", "(J)V");
    method_setTopHalCapabilities = env->GetMethodID(clazz, "setTopHalCapabilities", "(I)V");
    method_setGnssYearOfHardware = env->GetMethodID(clazz, "setGnssYearOfHardware", "(I)V");
    method_setGnssHardwareModelName = env->GetMethodID(clazz, "setGnssHardwareModelName",
            "(Ljava/lang/String;)V");
    method_psdsDownloadRequest = env->GetMethodID(clazz, "psdsDownloadRequest", "()V");
    method_reportNiNotification = env->GetMethodID(clazz, "reportNiNotification",
            "(IIIIILjava/lang/String;Ljava/lang/String;II)V");
    method_requestLocation = env->GetMethodID(clazz, "requestLocation", "(ZZ)V");
    method_requestRefLocation = env->GetMethodID(clazz, "requestRefLocation", "()V");
    method_requestSetID = env->GetMethodID(clazz, "requestSetID", "(I)V");
    method_requestUtcTime = env->GetMethodID(clazz, "requestUtcTime", "()V");
    method_reportGeofenceTransition = env->GetMethodID(clazz, "reportGeofenceTransition",
            "(ILandroid/location/Location;IJ)V");
    method_reportGeofenceStatus = env->GetMethodID(clazz, "reportGeofenceStatus",
            "(ILandroid/location/Location;)V");
    method_reportGeofenceAddStatus = env->GetMethodID(clazz, "reportGeofenceAddStatus",
            "(II)V");
    method_reportGeofenceRemoveStatus = env->GetMethodID(clazz, "reportGeofenceRemoveStatus",
            "(II)V");
    method_reportGeofenceResumeStatus = env->GetMethodID(clazz, "reportGeofenceResumeStatus",
            "(II)V");
    method_reportGeofencePauseStatus = env->GetMethodID(clazz, "reportGeofencePauseStatus",
            "(II)V");
    method_reportMeasurementData = env->GetMethodID(
            clazz,
            "reportMeasurementData",
            "(Landroid/location/GnssMeasurementsEvent;)V");
    method_reportNavigationMessages = env->GetMethodID(
            clazz,
            "reportNavigationMessage",
            "(Landroid/location/GnssNavigationMessage;)V");
    method_reportLocationBatch = env->GetMethodID(
            clazz,
            "reportLocationBatch",
            "([Landroid/location/Location;)V");
    method_reportGnssServiceDied = env->GetMethodID(clazz, "reportGnssServiceDied", "()V");
    method_reportNfwNotification = env->GetMethodID(clazz, "reportNfwNotification",
            "(Ljava/lang/String;BLjava/lang/String;BLjava/lang/String;BZZ)V");
    method_isInEmergencySession = env->GetMethodID(clazz, "isInEmergencySession", "()Z");

    method_setSubHalMeasurementCorrectionsCapabilities = env->GetMethodID(clazz,
            "setSubHalMeasurementCorrectionsCapabilities", "(I)V");

    jclass measCorrClass = env->FindClass("android/location/GnssMeasurementCorrections");
    method_correctionsGetLatitudeDegrees = env->GetMethodID(
            measCorrClass,"getLatitudeDegrees", "()D");
    method_correctionsGetLongitudeDegrees = env->GetMethodID(
            measCorrClass, "getLongitudeDegrees", "()D");
    method_correctionsGetAltitudeMeters = env->GetMethodID(
            measCorrClass, "getAltitudeMeters", "()D");
    method_correctionsGetHorPosUncMeters = env->GetMethodID(
            measCorrClass, "getHorizontalPositionUncertaintyMeters", "()D");
    method_correctionsGetVerPosUncMeters = env->GetMethodID(
            measCorrClass, "getVerticalPositionUncertaintyMeters", "()D");
    method_correctionsGetToaGpsNanosecondsOfWeek = env->GetMethodID(
            measCorrClass, "getToaGpsNanosecondsOfWeek", "()J");

    method_correctionsGetSingleSatCorrectionList = env->GetMethodID(
            measCorrClass, "getSingleSatelliteCorrectionList", "()Ljava/util/List;");

    jclass corrListClass = env->FindClass("java/util/List");
    method_listSize = env->GetMethodID(corrListClass, "size", "()I");
    method_correctionListGet = env->GetMethodID(corrListClass, "get", "(I)Ljava/lang/Object;");

    jclass singleSatCorrClass = env->FindClass("android/location/GnssSingleSatCorrection");
    method_correctionSatFlags = env->GetMethodID(
            singleSatCorrClass, "getSingleSatelliteCorrectionFlags", "()I");
    method_correctionSatConstType = env->GetMethodID(
            singleSatCorrClass, "getConstellationType", "()I");
    method_correctionSatId= env->GetMethodID(
            singleSatCorrClass, "getSatelliteId", "()I");
    method_correctionSatCarrierFreq = env->GetMethodID(
            singleSatCorrClass, "getCarrierFrequencyHz", "()F");
    method_correctionSatIsLosProb = env->GetMethodID(
            singleSatCorrClass,"getProbabilityLineOfSight", "()F");
    method_correctionSatEpl = env->GetMethodID(
            singleSatCorrClass, "getExcessPathLengthMeters", "()F");
    method_correctionSatEplUnc = env->GetMethodID(
            singleSatCorrClass, "getExcessPathLengthUncertaintyMeters", "()F");
    method_correctionSatRefPlane = env->GetMethodID(
            singleSatCorrClass, "getReflectingPlane", "()Landroid/location/GnssReflectingPlane;");

    jclass refPlaneClass = env->FindClass("android/location/GnssReflectingPlane");
    method_correctionPlaneLatDeg = env->GetMethodID(refPlaneClass, "getLatitudeDegrees", "()D");
    method_correctionPlaneLngDeg = env->GetMethodID(refPlaneClass, "getLongitudeDegrees", "()D");
    method_correctionPlaneAltDeg = env->GetMethodID(refPlaneClass, "getAltitudeMeters", "()D");
    method_correctionPlaneAzimDeg = env->GetMethodID(refPlaneClass, "getAzimuthDegrees", "()D");

    jclass gnssMeasurementsEventClass = env->FindClass("android/location/GnssMeasurementsEvent");
    class_gnssMeasurementsEvent= (jclass) env->NewGlobalRef(gnssMeasurementsEventClass);
    method_gnssMeasurementsEventCtor = env->GetMethodID(
                    class_gnssMeasurementsEvent,
                    "<init>",
                    "(Landroid/location/GnssClock;[Landroid/location/GnssMeasurement;)V");

    jclass gnssMeasurementClass = env->FindClass("android/location/GnssMeasurement");
    class_gnssMeasurement = (jclass) env->NewGlobalRef(gnssMeasurementClass);
    method_gnssMeasurementCtor = env->GetMethodID(class_gnssMeasurement, "<init>", "()V");

    jclass locationClass = env->FindClass("android/location/Location");
    class_location = (jclass) env->NewGlobalRef(locationClass);
    method_locationCtor = env->GetMethodID(class_location, "<init>", "(Ljava/lang/String;)V");

    jclass gnssNavigationMessageClass = env->FindClass("android/location/GnssNavigationMessage");
    class_gnssNavigationMessage = (jclass) env->NewGlobalRef(gnssNavigationMessageClass);
    method_gnssNavigationMessageCtor = env->GetMethodID(class_gnssNavigationMessage, "<init>", "()V");

    jclass gnssClockClass = env->FindClass("android/location/GnssClock");
    class_gnssClock = (jclass) env->NewGlobalRef(gnssClockClass);
    method_gnssClockCtor = env->GetMethodID(class_gnssClock, "<init>", "()V");

    jclass gnssConfiguration_halInterfaceVersionClass =
            env->FindClass("com/android/server/location/GnssConfiguration$HalInterfaceVersion");
    class_gnssConfiguration_halInterfaceVersion =
            (jclass) env->NewGlobalRef(gnssConfiguration_halInterfaceVersionClass);
    method_halInterfaceVersionCtor =
            env->GetMethodID(class_gnssConfiguration_halInterfaceVersion, "<init>", "(II)V");
}

/* Initialization needed at system boot and whenever GNSS service dies. */
static void android_location_GnssLocationProvider_init_once(JNIEnv* env, jclass clazz,
        jboolean reinitializeGnssServiceHandle) {
    /*
     * Save a pointer to JVM.
     */
    jint jvmStatus = env->GetJavaVM(&sJvm);
    if (jvmStatus != JNI_OK) {
        LOG_ALWAYS_FATAL("Unable to get Java VM. Error: %d", jvmStatus);
    }

    if (reinitializeGnssServiceHandle) {
        android_location_GnssLocationProvider_set_gps_service_handle();
    }

    if (gnssHal == nullptr) {
        ALOGE("Unable to get GPS service\n");
        return;
    }

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

    auto gnssXtra = gnssHal->getExtensionXtra();
    if (!gnssXtra.isOk()) {
        ALOGD("Unable to get a handle to Xtra");
    } else {
        gnssXtraIface = gnssXtra;
    }

    if (gnssHal_V2_0 != nullptr) {
        auto agnssRil_V2_0 = gnssHal_V2_0->getExtensionAGnssRil_2_0();
        if (!agnssRil_V2_0.isOk()) {
            ALOGD("Unable to get a handle to AGnssRil_V2_0");
        } else {
            agnssRilIface_V2_0 = agnssRil_V2_0;
            agnssRilIface = agnssRilIface_V2_0;
        }
    } else {
        auto agnssRil_V1_0 = gnssHal->getExtensionAGnssRil();
        if (!agnssRil_V1_0.isOk()) {
            ALOGD("Unable to get a handle to AGnssRil");
        } else {
            agnssRilIface = agnssRil_V1_0;
        }
    }

    if (gnssHal_V2_0 != nullptr) {
        auto agnss_V2_0 = gnssHal_V2_0->getExtensionAGnss_2_0();
        if (!agnss_V2_0.isOk()) {
            ALOGD("Unable to get a handle to AGnss_V2_0");
        } else {
            agnssIface_V2_0 = agnss_V2_0;
        }
    } else {
        auto agnss_V1_0 = gnssHal->getExtensionAGnss();
        if (!agnss_V1_0.isOk()) {
            ALOGD("Unable to get a handle to AGnss");
        } else {
            agnssIface = agnss_V1_0;
        }
    }

    auto gnssNavigationMessage = gnssHal->getExtensionGnssNavigationMessage();
    if (!gnssNavigationMessage.isOk()) {
        ALOGD("Unable to get a handle to GnssNavigationMessage");
    } else {
        gnssNavigationMessageIface = gnssNavigationMessage;
    }

    // Allow all causal combinations between IGnss.hal and IGnssMeasurement.hal. That means,
    // 2.0@IGnss can be paired with {1.0, 1,1, 2.0}@IGnssMeasurement
    // 1.1@IGnss can be paired {1.0, 1.1}@IGnssMeasurement
    // 1.0@IGnss is paired with 1.0@IGnssMeasurement
    gnssMeasurementIface = nullptr;
    if (gnssHal_V2_0 != nullptr) {
        auto gnssMeasurement = gnssHal_V2_0->getExtensionGnssMeasurement_2_0();
        if (!gnssMeasurement.isOk()) {
            ALOGD("Unable to get a handle to GnssMeasurement_V2_0");
        } else {
            gnssMeasurementIface_V2_0 = gnssMeasurement;
            gnssMeasurementIface_V1_1 = gnssMeasurementIface_V2_0;
            gnssMeasurementIface = gnssMeasurementIface_V2_0;
        }
    }
    if (gnssHal_V1_1 != nullptr && gnssMeasurementIface == nullptr) {
         auto gnssMeasurement = gnssHal_V1_1->getExtensionGnssMeasurement_1_1();
         if (!gnssMeasurement.isOk()) {
             ALOGD("Unable to get a handle to GnssMeasurement_V1_1");
         } else {
             gnssMeasurementIface_V1_1 = gnssMeasurement;
             gnssMeasurementIface = gnssMeasurementIface_V1_1;
         }
    }
    if (gnssMeasurementIface == nullptr) {
         auto gnssMeasurement = gnssHal->getExtensionGnssMeasurement();
         if (!gnssMeasurement.isOk()) {
             ALOGD("Unable to get a handle to GnssMeasurement");
         } else {
             gnssMeasurementIface = gnssMeasurement;
         }
    }

    if (gnssHal_V2_0 != nullptr) {
        auto gnssCorrections = gnssHal_V2_0->getExtensionMeasurementCorrections();
        if (!gnssCorrections.isOk()) {
            ALOGD("Unable to get a handle to GnssMeasurementCorrections interface");
        } else {
            gnssCorrectionsIface = gnssCorrections;
        }
    }

    // Allow all causal combinations between IGnss.hal and IGnssDebug.hal. That means,
    // 2.0@IGnss can be paired with {1.0, 2.0}@IGnssDebug
    // 1.0@IGnss is paired with 1.0@IGnssDebug
    gnssDebugIface = nullptr;
    if (gnssHal_V2_0 != nullptr) {
        auto gnssDebug = gnssHal_V2_0->getExtensionGnssDebug_2_0();
        if (!gnssDebug.isOk()) {
            ALOGD("Unable to get a handle to GnssDebug_V2_0");
        } else {
            gnssDebugIface_V2_0 = gnssDebug;
            gnssDebugIface = gnssDebugIface_V2_0;
        }
    }
    if (gnssDebugIface == nullptr) {
        auto gnssDebug = gnssHal->getExtensionGnssDebug();
        if (!gnssDebug.isOk()) {
            ALOGD("Unable to get a handle to GnssDebug");
        } else {
            gnssDebugIface = gnssDebug;
        }
    }

    auto gnssNi = gnssHal->getExtensionGnssNi();
    if (!gnssNi.isOk()) {
        ALOGD("Unable to get a handle to GnssNi");
    } else {
        gnssNiIface = gnssNi;
    }

    if (gnssHal_V2_0 != nullptr) {
        auto gnssConfiguration = gnssHal_V2_0->getExtensionGnssConfiguration_2_0();
        if (!gnssConfiguration.isOk()) {
            ALOGD("Unable to get a handle to GnssConfiguration_V2_0");
        } else {
            gnssConfigurationIface_V2_0 = gnssConfiguration;
            gnssConfigurationIface_V1_1 = gnssConfigurationIface_V2_0;
            gnssConfigurationIface = gnssConfigurationIface_V2_0;
        }
    } else if (gnssHal_V1_1 != nullptr) {
        auto gnssConfiguration = gnssHal_V1_1->getExtensionGnssConfiguration_1_1();
        if (!gnssConfiguration.isOk()) {
            ALOGD("Unable to get a handle to GnssConfiguration_V1_1");
        } else {
            gnssConfigurationIface_V1_1 = gnssConfiguration;
            gnssConfigurationIface = gnssConfigurationIface_V1_1;
        }
    } else {
        auto gnssConfiguration_V1_0 = gnssHal->getExtensionGnssConfiguration();
        if (!gnssConfiguration_V1_0.isOk()) {
            ALOGD("Unable to get a handle to GnssConfiguration");
        } else {
            gnssConfigurationIface = gnssConfiguration_V1_0;
        }
    }

    auto gnssGeofencing = gnssHal->getExtensionGnssGeofencing();
    if (!gnssGeofencing.isOk()) {
        ALOGD("Unable to get a handle to GnssGeofencing");
    } else {
        gnssGeofencingIface = gnssGeofencing;
    }

    // If IGnssBatching.hal@2.0 is not supported, use IGnssBatching.hal@1.0
    if (gnssHal_V2_0 != nullptr) {
        auto gnssBatching_V2_0 = gnssHal_V2_0->getExtensionGnssBatching_2_0();
        if (!gnssBatching_V2_0.isOk()) {
            ALOGD("Unable to get a handle to GnssBatching_V2_0");
        } else {
            gnssBatchingIface_V2_0 = gnssBatching_V2_0;
        }
    }
    if (gnssBatchingIface_V2_0 == nullptr ) {
        auto gnssBatching_V1_0 = gnssHal->getExtensionGnssBatching();
        if (!gnssBatching_V1_0.isOk()) {
            ALOGD("Unable to get a handle to GnssBatching");
        } else {
            gnssBatchingIface = gnssBatching_V1_0;
        }
    }

    if (gnssHal_V2_0 != nullptr) {
        auto gnssVisibilityControl = gnssHal_V2_0->getExtensionVisibilityControl();
        if (!gnssVisibilityControl.isOk()) {
            ALOGD("Unable to get a handle to GnssVisibilityControl interface");
        } else {
            gnssVisibilityControlIface = gnssVisibilityControl;
        }
    }
}

static jboolean android_location_GnssLocationProvider_is_supported(
        JNIEnv* /* env */, jclass /* clazz */) {
    return (gnssHal != nullptr) ?  JNI_TRUE : JNI_FALSE;
}

static jboolean android_location_GnssNetworkConnectivityHandler_is_agps_ril_supported(
        JNIEnv* /* env */, jclass /* clazz */) {
    return (agnssRilIface != nullptr) ? JNI_TRUE : JNI_FALSE;
}

static jobject android_location_GnssConfiguration_get_gnss_configuration_version(
        JNIEnv* env, jclass /* jclazz */) {
    jint major, minor;
    if (gnssConfigurationIface_V2_0 != nullptr) {
        major = 2;
        minor = 0;
    } else if (gnssConfigurationIface_V1_1 != nullptr) {
        major = 1;
        minor = 1;
    } else if (gnssConfigurationIface != nullptr) {
        major = 1;
        minor = 0;
    } else {
        return nullptr;
    }

    return createHalInterfaceVersionJavaObject(env, major, minor);
}

/* Initialization needed each time the GPS service is shutdown. */
static jboolean android_location_GnssLocationProvider_init(JNIEnv* env, jobject obj) {
    /*
     * This must be set before calling into the HAL library.
     */
    if (!mCallbacksObj)
        mCallbacksObj = env->NewGlobalRef(obj);

    /*
     * Fail if the main interface fails to initialize
     */
    if (gnssHal == nullptr) {
        ALOGE("Unable to initialize GNSS HAL.");
        return JNI_FALSE;
    }

    Return<bool> result = false;

    // Set top level IGnss.hal callback.
    sp<IGnssCallback> gnssCbIface = new GnssCallback();
    if (gnssHal_V2_0 != nullptr) {
        result = gnssHal_V2_0->setCallback_2_0(gnssCbIface);
    } else if (gnssHal_V1_1 != nullptr) {
        result = gnssHal_V1_1->setCallback_1_1(gnssCbIface);
    } else {
        result = gnssHal->setCallback(gnssCbIface);
    }

    if (!result.isOk() || !result) {
        ALOGE("SetCallback for IGnss interface failed.");
        return JNI_FALSE;
    }

    // Set IGnssXtra.hal callback.
    if (gnssXtraIface == nullptr) {
        ALOGI("Unable to initialize IGnssXtra interface.");
    } else {
        sp<IGnssXtraCallback> gnssXtraCbIface = new GnssXtraCallback();
        result = gnssXtraIface->setCallback(gnssXtraCbIface);
        if (!result.isOk() || !result) {
            gnssXtraIface = nullptr;
            ALOGI("SetCallback for IGnssXtra interface failed.");
        }
    }

    // Set IAGnss.hal callback.
    Return<void> agnssStatus;
    if (agnssIface_V2_0 != nullptr) {
        sp<IAGnssCallback_V2_0> aGnssCbIface = new AGnssCallback_V2_0();
        agnssStatus = agnssIface_V2_0->setCallback(aGnssCbIface);
    } else if (agnssIface != nullptr) {
        sp<IAGnssCallback_V1_0> aGnssCbIface = new AGnssCallback_V1_0();
        agnssStatus = agnssIface->setCallback(aGnssCbIface);
    } else {
        ALOGI("Unable to initialize IAGnss interface.");
    }

    if (!agnssStatus.isOk()) {
        ALOGI("SetCallback for IAGnss interface failed.");
    }

    // Set IGnssGeofencing.hal callback.
    sp<IGnssGeofenceCallback> gnssGeofencingCbIface = new GnssGeofenceCallback();
    if (gnssGeofencingIface != nullptr) {
        auto status = gnssGeofencingIface->setCallback(gnssGeofencingCbIface);
        if (!status.isOk()) {
            ALOGI("SetCallback for IGnssGeofencing interface failed.");
        }
    } else {
        ALOGI("Unable to initialize IGnssGeofencing interface.");
    }

    // Set IGnssNi.hal callback.
    sp<IGnssNiCallback> gnssNiCbIface = new GnssNiCallback();
    if (gnssNiIface != nullptr) {
        auto status = gnssNiIface->setCallback(gnssNiCbIface);
        if (!status.isOk()) {
            ALOGI("SetCallback for IGnssNi interface failed.");
        }
    } else {
        ALOGI("Unable to initialize IGnssNi interface.");
    }

    // Set IAGnssRil.hal callback.
    sp<IAGnssRilCallback> aGnssRilCbIface = new AGnssRilCallback();
    if (agnssRilIface != nullptr) {
        auto status = agnssRilIface->setCallback(aGnssRilCbIface);
        if (!status.isOk()) {
            ALOGI("SetCallback for IAGnssRil interface failed.");
        }
    } else {
        ALOGI("Unable to initialize IAGnssRil interface.");
    }

    // Set IGnssVisibilityControl.hal callback.
    if (gnssVisibilityControlIface != nullptr) {
        sp<IGnssVisibilityControlCallback> gnssVisibilityControlCbIface =
                new GnssVisibilityControlCallback();
        result = gnssVisibilityControlIface->setCallback(gnssVisibilityControlCbIface);
        if (!result.isOk() || !result) {
            ALOGI("SetCallback for IGnssVisibilityControl interface failed.");
        }
    }

    // Set IMeasurementCorrections.hal callback.
    if (gnssCorrectionsIface != nullptr) {
        sp<IMeasurementCorrectionsCallback> gnssCorrectionsIfaceCbIface =
                new MeasurementCorrectionsCallback();
        result = gnssCorrectionsIface->setCallback(gnssCorrectionsIfaceCbIface);
        if (!result.isOk() || !result) {
            ALOGI("SetCallback for IMeasurementCorrections interface failed.");
        }
    }

    return JNI_TRUE;
}

static void android_location_GnssLocationProvider_cleanup(JNIEnv* /* env */, jobject /* obj */) {
    if (gnssHal != nullptr) {
        gnssHal->cleanup();
    }
}

static jboolean android_location_GnssLocationProvider_set_position_mode(JNIEnv* /* env */,
        jobject /* obj */, jint mode, jint recurrence, jint min_interval, jint preferred_accuracy,
        jint preferred_time, jboolean low_power_mode) {
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
    if (!result.isOk()) {
       ALOGE("%s: GNSS setPositionMode failed\n", __func__);
       return JNI_FALSE;
    } else {
       return result;
    }
}

static jboolean android_location_GnssLocationProvider_start(JNIEnv* /* env */, jobject /* obj */) {
    if (gnssHal != nullptr) {
        auto result = gnssHal->start();
        if (!result.isOk()) {
            return JNI_FALSE;
        } else {
            return result;
        }
    } else {
        return JNI_FALSE;
    }
}

static jboolean android_location_GnssLocationProvider_stop(JNIEnv* /* env */, jobject /* obj */) {
    if (gnssHal != nullptr) {
        auto result = gnssHal->stop();
        if (!result.isOk()) {
            return JNI_FALSE;
        } else {
            return result;
        }
    } else {
        return JNI_FALSE;
    }
}
static void android_location_GnssLocationProvider_delete_aiding_data(JNIEnv* /* env */,
                                                                    jobject /* obj */,
                                                                    jint flags) {
    if (gnssHal != nullptr) {
        auto result = gnssHal->deleteAidingData(static_cast<IGnss_V1_0::GnssAidingData>(flags));
        if (!result.isOk()) {
            ALOGE("Error in deleting aiding data");
        }
    }
}

static void android_location_GnssLocationProvider_agps_set_reference_location_cellid(
        JNIEnv* /* env */, jobject /* obj */, jint type, jint mcc, jint mnc, jint lac, jint cid) {
    IAGnssRil_V1_0::AGnssRefLocation location;

    if (agnssRilIface == nullptr) {
        ALOGE("No AGPS RIL interface in agps_set_reference_location_cellid");
        return;
    }

    switch (static_cast<IAGnssRil_V1_0::AGnssRefLocationType>(type)) {
        case IAGnssRil_V1_0::AGnssRefLocationType::GSM_CELLID:
        case IAGnssRil_V1_0::AGnssRefLocationType::UMTS_CELLID:
          location.type = static_cast<IAGnssRil_V1_0::AGnssRefLocationType>(type);
          location.cellID.mcc = mcc;
          location.cellID.mnc = mnc;
          location.cellID.lac = lac;
          location.cellID.cid = cid;
          break;
        default:
            ALOGE("Neither a GSM nor a UMTS cellid (%s:%d).", __FUNCTION__, __LINE__);
            return;
            break;
    }

    agnssRilIface->setRefLocation(location);
}

static void android_location_GnssLocationProvider_agps_set_id(JNIEnv* env, jobject /* obj */,
                                                             jint type, jstring  setid_string) {
    if (agnssRilIface == nullptr) {
        ALOGE("no AGPS RIL interface in agps_set_id");
        return;
    }

    ScopedJniString jniSetId{env, setid_string};
    agnssRilIface->setSetId((IAGnssRil_V1_0::SetIDType)type, jniSetId);
}

static jint android_location_GnssLocationProvider_read_nmea(JNIEnv* env, jobject /* obj */,
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

static void android_location_GnssLocationProvider_inject_time(JNIEnv* /* env */, jobject /* obj */,
        jlong time, jlong timeReference, jint uncertainty) {
    if (gnssHal != nullptr) {
        auto result = gnssHal->injectTime(time, timeReference, uncertainty);
        if (!result.isOk() || !result) {
            ALOGE("%s: Gnss injectTime() failed", __func__);
        }
    }
}

static void android_location_GnssLocationProvider_inject_best_location(
        JNIEnv*,
        jobject,
        jint gnssLocationFlags,
        jdouble latitudeDegrees,
        jdouble longitudeDegrees,
        jdouble altitudeMeters,
        jfloat speedMetersPerSec,
        jfloat bearingDegrees,
        jfloat horizontalAccuracyMeters,
        jfloat verticalAccuracyMeters,
        jfloat speedAccuracyMetersPerSecond,
        jfloat bearingAccuracyDegrees,
        jlong timestamp,
        jint elapsedRealtimeFlags,
        jlong elapsedRealtimeNanos,
        jdouble elapsedRealtimeUncertaintyNanos) {
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

        if (!result.isOk() || !result) {
            ALOGE("%s: Gnss injectBestLocation() failed.", __func__);
        }
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

        if (!result.isOk() || !result) {
            ALOGE("%s: Gnss injectBestLocation() failed.", __func__);
        }
        return;
    }

    ALOGE("%s: injectBestLocation() is called but gnssHal_V1_1 is not available.", __func__);
}

static void android_location_GnssLocationProvider_inject_location(JNIEnv* /* env */,
        jobject /* obj */, jdouble latitude, jdouble longitude, jfloat accuracy) {
    if (gnssHal != nullptr) {
        auto result = gnssHal->injectLocation(latitude, longitude, accuracy);
        if (!result.isOk() || !result) {
            ALOGE("%s: Gnss injectLocation() failed", __func__);
        }
    }
}

static jboolean android_location_GnssLocationProvider_supports_psds(
        JNIEnv* /* env */, jobject /* obj */) {
    return (gnssXtraIface != nullptr) ? JNI_TRUE : JNI_FALSE;
}

static void android_location_GnssLocationProvider_inject_psds_data(JNIEnv* env, jobject /* obj */,
        jbyteArray data, jint length) {
    if (gnssXtraIface == nullptr) {
        ALOGE("XTRA Interface not supported");
        return;
    }

    jbyte* bytes = reinterpret_cast<jbyte *>(env->GetPrimitiveArrayCritical(data, 0));
    gnssXtraIface->injectXtraData(std::string((const char*)bytes, length));
    env->ReleasePrimitiveArrayCritical(data, bytes, JNI_ABORT);
}

struct AGnssDispatcher {
    static void dataConnOpen(sp<IAGnss_V1_0> agnssIface, JNIEnv* env, jstring apn, jint apnIpType);
    static void dataConnOpen(sp<IAGnss_V2_0> agnssIface_V2_0, JNIEnv* env, jlong networkHandle,
            jstring apn, jint apnIpType);

    template <class T>
    static void dataConnClosed(sp<T> agnssIface);

    template <class T>
    static void dataConnFailed(sp<T> agnssIface);

    template <class T, class U>
    static void setServer(sp<T> agnssIface, JNIEnv* env, jint type, jstring hostname, jint port);

private:
    AGnssDispatcher() = delete;
};

void AGnssDispatcher::dataConnOpen(sp<IAGnss_V1_0> agnssIface, JNIEnv* env, jstring apn,
        jint apnIpType) {
    ScopedJniString jniApn{env, apn};
    auto result = agnssIface->dataConnOpen(jniApn,
            static_cast<IAGnss_V1_0::ApnIpType>(apnIpType));
    if (!result.isOk() || !result){
        ALOGE("%s: Failed to set APN and its IP type", __func__);
    }
}

void AGnssDispatcher::dataConnOpen(sp<IAGnss_V2_0> agnssIface_V2_0, JNIEnv* env,
        jlong networkHandle, jstring apn, jint apnIpType) {
    ScopedJniString jniApn{env, apn};
    auto result = agnssIface_V2_0->dataConnOpen(static_cast<uint64_t>(networkHandle), jniApn,
            static_cast<IAGnss_V2_0::ApnIpType>(apnIpType));
    if (!result.isOk() || !result){
        ALOGE("%s: Failed to set APN and its IP type", __func__);
    }
}

template<class T>
void AGnssDispatcher::dataConnClosed(sp<T> agnssIface) {
    auto result = agnssIface->dataConnClosed();
    if (!result.isOk() || !result) {
        ALOGE("%s: Failed to close AGnss data connection", __func__);
    }
}

template<class T>
void AGnssDispatcher::dataConnFailed(sp<T> agnssIface) {
    auto result = agnssIface->dataConnFailed();
    if (!result.isOk() || !result) {
        ALOGE("%s: Failed to notify unavailability of AGnss data connection", __func__);
    }
}

template <class T, class U>
void AGnssDispatcher::setServer(sp<T> agnssIface, JNIEnv* env, jint type, jstring hostname,
        jint port) {
    ScopedJniString jniHostName{env, hostname};
    auto result = agnssIface->setServer(static_cast<typename U::AGnssType>(type),
            jniHostName, port);
    if (!result.isOk() || !result) {
        ALOGE("%s: Failed to set AGnss host name and port", __func__);
    }
}

static void android_location_GnssNetworkConnectivityHandler_agps_data_conn_open(
        JNIEnv* env, jobject /* obj */, jlong networkHandle, jstring apn, jint apnIpType) {
    if (apn == nullptr) {
        jniThrowException(env, "java/lang/IllegalArgumentException", nullptr);
        return;
    }

    if (agnssIface_V2_0 != nullptr) {
        AGnssDispatcher::dataConnOpen(agnssIface_V2_0, env, networkHandle, apn, apnIpType);
    } else if (agnssIface != nullptr) {
        AGnssDispatcher::dataConnOpen(agnssIface, env, apn, apnIpType);
    } else {
        ALOGE("%s: AGPS interface not supported", __func__);
        return;
    }
}

static void android_location_GnssNetworkConnectivityHandler_agps_data_conn_closed(JNIEnv* /* env */,
                                                                       jobject /* obj */) {
    if (agnssIface_V2_0 != nullptr) {
        AGnssDispatcher::dataConnClosed(agnssIface_V2_0);
    } else if (agnssIface != nullptr) {
        AGnssDispatcher::dataConnClosed(agnssIface);
    } else {
        ALOGE("%s: AGPS interface not supported", __func__);
        return;
    }
}

static void android_location_GnssNetworkConnectivityHandler_agps_data_conn_failed(JNIEnv* /* env */,
                                                                       jobject /* obj */) {
    if (agnssIface_V2_0 != nullptr) {
        AGnssDispatcher::dataConnFailed(agnssIface_V2_0);
    } else if (agnssIface != nullptr) {
        AGnssDispatcher::dataConnFailed(agnssIface);
    } else {
        ALOGE("%s: AGPS interface not supported", __func__);
        return;
    }
}

static void android_location_GnssLocationProvider_set_agps_server(JNIEnv* env, jobject /* obj */,
        jint type, jstring hostname, jint port) {
    if (agnssIface_V2_0 != nullptr) {
        AGnssDispatcher::setServer<IAGnss_V2_0, IAGnssCallback_V2_0>(agnssIface_V2_0, env, type,
                hostname, port);
    } else if (agnssIface != nullptr) {
        AGnssDispatcher::setServer<IAGnss_V1_0, IAGnssCallback_V1_0>(agnssIface, env, type,
                hostname, port);
    } else {
        ALOGE("%s: AGPS interface not supported", __func__);
        return;
    }
}

static void android_location_GnssLocationProvider_send_ni_response(JNIEnv* /* env */,
      jobject /* obj */, jint notifId, jint response) {
    if (gnssNiIface == nullptr) {
        ALOGE("no NI interface in send_ni_response");
        return;
    }

    gnssNiIface->respond(notifId, static_cast<IGnssNiCallback::GnssUserResponseType>(response));
}

const IGnssDebug_V1_0::SatelliteData& getSatelliteData(const hidl_vec<IGnssDebug_V1_0::SatelliteData>& satelliteDataArray, size_t i) {
    return satelliteDataArray[i];
}

const IGnssDebug_V1_0::SatelliteData& getSatelliteData(const hidl_vec<IGnssDebug_V2_0::SatelliteData>& satelliteDataArray, size_t i) {
    return satelliteDataArray[i].v1_0;
}

template<class T>
uint32_t getConstellationType(const hidl_vec<T>& satelliteDataArray, size_t i) {
    return static_cast<uint32_t>(satelliteDataArray[i].constellation);
}

template<class T>
static jstring parseDebugData(JNIEnv* env, std::stringstream& internalState, const T& data) {
    internalState << "Gnss Location Data:: ";
    if (!data.position.valid) {
        internalState << "not valid";
    } else {
        internalState << "LatitudeDegrees: " << data.position.latitudeDegrees
                      << ", LongitudeDegrees: " << data.position.longitudeDegrees
                      << ", altitudeMeters: " << data.position.altitudeMeters
                      << ", speedMetersPerSecond: " << data.position.speedMetersPerSec
                      << ", bearingDegrees: " << data.position.bearingDegrees
                      << ", horizontalAccuracyMeters: "
                      << data.position.horizontalAccuracyMeters
                      << ", verticalAccuracyMeters: " << data.position.verticalAccuracyMeters
                      << ", speedAccuracyMetersPerSecond: "
                      << data.position.speedAccuracyMetersPerSecond
                      << ", bearingAccuracyDegrees: " << data.position.bearingAccuracyDegrees
                      << ", ageSeconds: " << data.position.ageSeconds;
    }
    internalState << std::endl;

    internalState << "Gnss Time Data:: timeEstimate: " << data.time.timeEstimate
                  << ", timeUncertaintyNs: " << data.time.timeUncertaintyNs
                  << ", frequencyUncertaintyNsPerSec: "
                  << data.time.frequencyUncertaintyNsPerSec << std::endl;

    if (data.satelliteDataArray.size() != 0) {
        internalState << "Satellite Data for " << data.satelliteDataArray.size()
                      << " satellites:: " << std::endl;
    }

    internalState << "constell: 1=GPS, 2=SBAS, 3=GLO, 4=QZSS, 5=BDS, 6=GAL, 7=IRNSS; "
                  << "ephType: 0=Eph, 1=Alm, 2=Unk; "
                  << "ephSource: 0=Demod, 1=Supl, 2=Server, 3=Unk; "
                  << "ephHealth: 0=Good, 1=Bad, 2=Unk" << std::endl;
    for (size_t i = 0; i < data.satelliteDataArray.size(); i++) {
        IGnssDebug_V1_0::SatelliteData satelliteData =
                getSatelliteData(data.satelliteDataArray, i);
        internalState << "constell: "
                      << getConstellationType(data.satelliteDataArray, i)
                      << ", svid: " << std::setw(3) << satelliteData.svid
                      << ", serverPredAvail: "
                      << satelliteData.serverPredictionIsAvailable
                      << ", serverPredAgeSec: " << std::setw(7)
                      << satelliteData.serverPredictionAgeSeconds
                      << ", ephType: "
                      << static_cast<uint32_t>(satelliteData.ephemerisType)
                      << ", ephSource: "
                      << static_cast<uint32_t>(satelliteData.ephemerisSource)
                      << ", ephHealth: "
                      << static_cast<uint32_t>(satelliteData.ephemerisHealth)
                      << ", ephAgeSec: " << std::setw(7)
                      << satelliteData.ephemerisAgeSeconds << std::endl;
    }
    return (jstring) env->NewStringUTF(internalState.str().c_str());
}

static jstring android_location_GnssLocationProvider_get_internal_state(JNIEnv* env,
                                                                       jobject /* obj */) {
    jstring result = nullptr;
    /*
     * TODO: Create a jobject to represent GnssDebug.
     */

    std::stringstream internalState;

    if (gnssDebugIface == nullptr) {
        internalState << "Gnss Debug Interface not available"  << std::endl;
    } else if (gnssDebugIface_V2_0 != nullptr) {
        IGnssDebug_V2_0::DebugData data;
        gnssDebugIface_V2_0->getDebugData_2_0([&data](const IGnssDebug_V2_0::DebugData& debugData) {
            data = debugData;
        });
        result = parseDebugData(env, internalState, data);
    } else {
        IGnssDebug_V1_0::DebugData data;
        gnssDebugIface->getDebugData([&data](const IGnssDebug_V1_0::DebugData& debugData) {
            data = debugData;
        });
        result = parseDebugData(env, internalState, data);
    }
    return result;
}

static jboolean android_location_GnssLocationProvider_is_gnss_visibility_control_supported(
        JNIEnv* /* env */, jclass /* clazz */) {
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
    if (agnssRilIface_V2_0 != nullptr) {
        ScopedJniString jniApn{env, apn};
        IAGnssRil_V2_0::NetworkAttributes networkAttributes = {
            .networkHandle = static_cast<uint64_t>(networkHandle),
            .isConnected = static_cast<bool>(connected),
            .capabilities = static_cast<uint16_t>(capabilities),
            .apn = jniApn
        };

        auto result = agnssRilIface_V2_0->updateNetworkState_2_0(networkAttributes);
        if (!result.isOk() || !result) {
            ALOGE("updateNetworkState_2_0 failed");
        }
    } else if (agnssRilIface != nullptr) {
        ScopedJniString jniApn{env, apn};
        hidl_string hidlApn{jniApn};
        auto result = agnssRilIface->updateNetworkState(connected,
                static_cast<IAGnssRil_V1_0::NetworkType>(type), roaming);
        if (!result.isOk() || !result) {
            ALOGE("updateNetworkState failed");
        }

        if (!hidlApn.empty()) {
            result = agnssRilIface->updateNetworkAvailability(available, hidlApn);
            if (!result.isOk() || !result) {
                ALOGE("updateNetworkAvailability failed");
            }
        }
    } else {
        ALOGE("AGnssRilInterface does not exist");
    }
}

static jboolean android_location_GnssGeofenceProvider_is_geofence_supported(
        JNIEnv* /* env */, jobject /* obj */) {
    return (gnssGeofencingIface != nullptr) ? JNI_TRUE : JNI_FALSE;
}

static jboolean android_location_GnssGeofenceProvider_add_geofence(JNIEnv* /* env */,
        jobject /* obj */, jint geofenceId, jdouble latitude, jdouble longitude, jdouble radius,
        jint last_transition, jint monitor_transition, jint notification_responsiveness,
        jint unknown_timer) {
    if (gnssGeofencingIface != nullptr) {
        auto result = gnssGeofencingIface->addGeofence(
                geofenceId, latitude, longitude, radius,
                static_cast<IGnssGeofenceCallback::GeofenceTransition>(last_transition),
                monitor_transition, notification_responsiveness, unknown_timer);
        return boolToJbool(result.isOk());
    } else {
        ALOGE("Geofence Interface not available");
    }
    return JNI_FALSE;
}

static jboolean android_location_GnssGeofenceProvider_remove_geofence(JNIEnv* /* env */,
        jobject /* obj */, jint geofenceId) {
    if (gnssGeofencingIface != nullptr) {
        auto result = gnssGeofencingIface->removeGeofence(geofenceId);
        return boolToJbool(result.isOk());
    } else {
        ALOGE("Geofence interface not available");
    }
    return JNI_FALSE;
}

static jboolean android_location_GnssGeofenceProvider_pause_geofence(JNIEnv* /* env */,
        jobject /* obj */, jint geofenceId) {
    if (gnssGeofencingIface != nullptr) {
        auto result = gnssGeofencingIface->pauseGeofence(geofenceId);
        return boolToJbool(result.isOk());
    } else {
        ALOGE("Geofence interface not available");
    }
    return JNI_FALSE;
}

static jboolean android_location_GnssGeofenceProvider_resume_geofence(JNIEnv* /* env */,
        jobject /* obj */, jint geofenceId, jint monitor_transition) {
    if (gnssGeofencingIface != nullptr) {
        auto result = gnssGeofencingIface->resumeGeofence(geofenceId, monitor_transition);
        return boolToJbool(result.isOk());
    } else {
        ALOGE("Geofence interface not available");
    }
    return JNI_FALSE;
}

static jboolean android_location_GnssMeasurementsProvider_is_measurement_supported(
    JNIEnv* env, jclass clazz) {
    if (gnssMeasurementIface != nullptr) {
        return JNI_TRUE;
    }

    return JNI_FALSE;
}

static jboolean android_location_GnssMeasurementsProvider_start_measurement_collection(
        JNIEnv* /* env */,
        jobject /* obj */,
        jboolean enableFullTracking) {
    if (gnssMeasurementIface == nullptr) {
        ALOGE("GNSS Measurement interface is not available.");
        return JNI_FALSE;
    }

    sp<GnssMeasurementCallback> cbIface = new GnssMeasurementCallback();
    IGnssMeasurement_V1_0::GnssMeasurementStatus result =
            IGnssMeasurement_V1_0::GnssMeasurementStatus::ERROR_GENERIC;
    if (gnssMeasurementIface_V2_0 != nullptr) {
        result = gnssMeasurementIface_V2_0->setCallback_2_0(cbIface, enableFullTracking);
    } else if (gnssMeasurementIface_V1_1 != nullptr) {
        result = gnssMeasurementIface_V1_1->setCallback_1_1(cbIface, enableFullTracking);
    } else {
        if (enableFullTracking == JNI_TRUE) {
            // full tracking mode not supported in 1.0 HAL
            return JNI_FALSE;
        }
        result = gnssMeasurementIface->setCallback(cbIface);
    }

    if (result != IGnssMeasurement_V1_0::GnssMeasurementStatus::SUCCESS) {
        ALOGE("An error has been found on GnssMeasurementInterface::init, status=%d",
              static_cast<int32_t>(result));
        return JNI_FALSE;
    } else {
        ALOGD("gnss measurement infc has been enabled");
    }

    return JNI_TRUE;
}

static jboolean android_location_GnssMeasurementsProvider_stop_measurement_collection(
        JNIEnv* env,
        jobject obj) {
    if (gnssMeasurementIface == nullptr) {
        ALOGE("Measurement interface not available");
        return JNI_FALSE;
    }

    auto result = gnssMeasurementIface->close();
    return boolToJbool(result.isOk());
}

static jboolean
    android_location_GnssMeasurementCorrectionsProvider_is_measurement_corrections_supported(
    JNIEnv* env, jclass clazz) {
    if (gnssCorrectionsIface != nullptr) {
        return JNI_TRUE;
    }

    return JNI_FALSE;
}

static jboolean
    android_location_GnssMeasurementCorrectionsProvider_inject_gnss_measurement_corrections(
        JNIEnv* env,
        jobject obj /* clazz*/,
        jobject correctionsObj) {

    if (gnssCorrectionsIface == nullptr) {
        ALOGW("Trying to inject GNSS measurement corrections on a chipset that does not"
            " support them.");
        return JNI_FALSE;
    }

    jdouble latitudeDegreesCorr = env->CallDoubleMethod(
        correctionsObj, method_correctionsGetLatitudeDegrees);
    jdouble longitudeDegreesCorr = env->CallDoubleMethod(
        correctionsObj, method_correctionsGetLongitudeDegrees);
    jdouble altitudeDegreesCorr = env->CallDoubleMethod(
        correctionsObj, method_correctionsGetAltitudeMeters);
    jdouble horizontalPositionUncertaintyMeters = env->CallDoubleMethod(
        correctionsObj, method_correctionsGetHorPosUncMeters);
    jdouble verticalPositionUncertaintyMeters = env->CallDoubleMethod(
            correctionsObj, method_correctionsGetVerPosUncMeters);
    jlong toaGpsNanosOfWeek = env->CallLongMethod(
        correctionsObj, method_correctionsGetToaGpsNanosecondsOfWeek);
    jobject singleSatCorrectionList = env->CallObjectMethod(correctionsObj,
        method_correctionsGetSingleSatCorrectionList);

    auto len = (singleSatCorrectionList == nullptr)
        ? 0
        : env->CallIntMethod(singleSatCorrectionList, method_listSize);
    if (len == 0) {
        ALOGI("Empty correction list injected....Returning with no HAL injection");
        return JNI_TRUE;
    }
    hidl_vec<SingleSatCorrection> list(len);

    for (uint16_t i = 0; i < len; ++i) {
        jobject singleSatCorrectionObj = env->CallObjectMethod(
            singleSatCorrectionList, method_correctionListGet, i);

        jint correctionFlags =
            env->CallIntMethod(singleSatCorrectionObj, method_correctionSatFlags);
        jint constType = env->CallIntMethod(singleSatCorrectionObj,
            method_correctionSatConstType);
        jint satId =
            env->CallIntMethod(singleSatCorrectionObj, method_correctionSatId);
        jfloat carrierFreqHz = env->CallFloatMethod(
            singleSatCorrectionObj, method_correctionSatCarrierFreq);
        jfloat probSatIsLos = env->CallFloatMethod(singleSatCorrectionObj,
            method_correctionSatIsLosProb);
        jfloat eplMeters =
            env->CallFloatMethod(singleSatCorrectionObj, method_correctionSatEpl);
        jfloat eplUncMeters = env->CallFloatMethod(singleSatCorrectionObj,
            method_correctionSatEplUnc);
        uint16_t corrFlags = static_cast<uint16_t>(correctionFlags);
        jobject reflectingPlaneObj;
        bool has_ref_plane = (corrFlags & GnssSingleSatCorrectionFlags::HAS_REFLECTING_PLANE) != 0;
        if (has_ref_plane) {
            reflectingPlaneObj = env->CallObjectMethod(
                singleSatCorrectionObj, method_correctionSatRefPlane);
        }

        ReflectingPlane reflectingPlane;
        if (has_ref_plane) {
            jdouble latitudeDegreesRefPlane = env->CallDoubleMethod(
                reflectingPlaneObj, method_correctionPlaneLatDeg);
            jdouble longitudeDegreesRefPlane = env->CallDoubleMethod(
                reflectingPlaneObj, method_correctionPlaneLngDeg);
            jdouble altitudeDegreesRefPlane = env->CallDoubleMethod(
                reflectingPlaneObj, method_correctionPlaneAltDeg);
            jdouble azimuthDegreeRefPlane = env->CallDoubleMethod(
                reflectingPlaneObj, method_correctionPlaneAzimDeg);
            reflectingPlane = {
                .latitudeDegrees = latitudeDegreesRefPlane,
                .longitudeDegrees = longitudeDegreesRefPlane,
                .altitudeMeters = altitudeDegreesRefPlane,
                .azimuthDegrees = azimuthDegreeRefPlane,
            };
        }

        SingleSatCorrection singleSatCorrection = {
            .singleSatCorrectionFlags = corrFlags,
            .constellation = static_cast<GnssConstellationType>(constType),
            .svid = static_cast<uint16_t>(satId),
            .carrierFrequencyHz = carrierFreqHz,
            .probSatIsLos = probSatIsLos,
            .excessPathLengthMeters = eplMeters,
            .excessPathLengthUncertaintyMeters = eplUncMeters,
            .reflectingPlane = reflectingPlane,
        };
        list[i] = singleSatCorrection;
    }
    MeasurementCorrections measurementCorrections = {
        .latitudeDegrees = latitudeDegreesCorr,
        .longitudeDegrees = longitudeDegreesCorr,
        .altitudeMeters = altitudeDegreesCorr,
        .horizontalPositionUncertaintyMeters = horizontalPositionUncertaintyMeters,
        .verticalPositionUncertaintyMeters = verticalPositionUncertaintyMeters,
        .toaGpsNanosecondsOfWeek = static_cast<uint64_t>(toaGpsNanosOfWeek),
        .satCorrections = list,
    };

    gnssCorrectionsIface->setCorrections(measurementCorrections);
    return JNI_TRUE;
}

static jboolean android_location_GnssNavigationMessageProvider_is_navigation_message_supported(
        JNIEnv* env,
        jclass clazz) {
    if (gnssNavigationMessageIface != nullptr) {
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

static jboolean android_location_GnssNavigationMessageProvider_start_navigation_message_collection(
        JNIEnv* env,
        jobject obj) {
    if (gnssNavigationMessageIface == nullptr) {
        ALOGE("Navigation Message interface is not available.");
        return JNI_FALSE;
    }

    sp<IGnssNavigationMessageCallback> gnssNavigationMessageCbIface =
            new GnssNavigationMessageCallback();
    IGnssNavigationMessage::GnssNavigationMessageStatus result =
            gnssNavigationMessageIface->setCallback(gnssNavigationMessageCbIface);

    if (result != IGnssNavigationMessage::GnssNavigationMessageStatus::SUCCESS) {
        ALOGE("An error has been found in %s: %d", __FUNCTION__, static_cast<int32_t>(result));
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

static jboolean android_location_GnssNavigationMessageProvider_stop_navigation_message_collection(
        JNIEnv* env,
        jobject obj) {
    if (gnssNavigationMessageIface == nullptr) {
        ALOGE("Navigation Message interface is not available.");
        return JNI_FALSE;
    }

    auto result = gnssNavigationMessageIface->close();
    return boolToJbool(result.isOk());
}

static jboolean android_location_GnssConfiguration_set_emergency_supl_pdn(JNIEnv*,
                                                                          jobject,
                                                                          jint emergencySuplPdn) {
    if (gnssConfigurationIface == nullptr) {
        ALOGE("no GNSS configuration interface available");
        return JNI_FALSE;
    }

    auto result = gnssConfigurationIface->setEmergencySuplPdn(emergencySuplPdn);
    if (result.isOk()) {
        return result;
    } else {
        return JNI_FALSE;
    }
}

static jboolean android_location_GnssConfiguration_set_supl_version(JNIEnv*,
                                                                    jobject,
                                                                    jint version) {
    if (gnssConfigurationIface == nullptr) {
        ALOGE("no GNSS configuration interface available");
        return JNI_FALSE;
    }
    auto result = gnssConfigurationIface->setSuplVersion(version);
    if (result.isOk()) {
        return result;
    } else {
        return JNI_FALSE;
    }
}

static jboolean android_location_GnssConfiguration_set_supl_es(JNIEnv*,
                                                               jobject,
                                                               jint suplEs) {
    if (gnssConfigurationIface_V2_0 != nullptr) {
        ALOGI("Config parameter SUPL_ES is deprecated in IGnssConfiguration.hal version 2.0.");
        return JNI_FALSE;
    }

    if (gnssConfigurationIface == nullptr) {
        ALOGE("no GNSS configuration interface available");
        return JNI_FALSE;
    }

    auto result = gnssConfigurationIface->setSuplEs(suplEs);
    if (result.isOk()) {
        return result;
    } else {
        return JNI_FALSE;
    }
}

static jboolean android_location_GnssConfiguration_set_supl_mode(JNIEnv*,
                                                                 jobject,
                                                                 jint mode) {
    if (gnssConfigurationIface == nullptr) {
        ALOGE("no GNSS configuration interface available");
        return JNI_FALSE;
    }

    auto result = gnssConfigurationIface->setSuplMode(mode);
    if (result.isOk()) {
        return result;
    } else {
        return JNI_FALSE;
    }
}

static jboolean android_location_GnssConfiguration_set_gps_lock(JNIEnv*,
                                                                jobject,
                                                                jint gpsLock) {
    if (gnssConfigurationIface_V2_0 != nullptr) {
        ALOGI("Config parameter GPS_LOCK is deprecated in IGnssConfiguration.hal version 2.0.");
        return JNI_FALSE;
    }

    if (gnssConfigurationIface == nullptr) {
        ALOGE("no GNSS configuration interface available");
        return JNI_FALSE;
    }

    auto result = gnssConfigurationIface->setGpsLock(gpsLock);
    if (result.isOk()) {
        return result;
    } else {
        return JNI_FALSE;
    }
}

static jboolean android_location_GnssConfiguration_set_lpp_profile(JNIEnv*,
                                                                   jobject,
                                                                   jint lppProfile) {
    if (gnssConfigurationIface == nullptr) {
        ALOGE("no GNSS configuration interface available");
        return JNI_FALSE;
    }

    auto result = gnssConfigurationIface->setLppProfile(lppProfile);

    if (result.isOk()) {
        return result;
    } else {
        return JNI_FALSE;
    }
}

static jboolean android_location_GnssConfiguration_set_gnss_pos_protocol_select(JNIEnv*,
                                                                            jobject,
                                                                            jint gnssPosProtocol) {
    if (gnssConfigurationIface == nullptr) {
        ALOGE("no GNSS configuration interface available");
        return JNI_FALSE;
    }

    auto result = gnssConfigurationIface->setGlonassPositioningProtocol(gnssPosProtocol);
    if (result.isOk()) {
        return result;
    } else {
        return JNI_FALSE;
    }
}

static jboolean android_location_GnssConfiguration_set_satellite_blacklist(
        JNIEnv* env, jobject, jintArray constellations, jintArray sv_ids) {
    if (gnssConfigurationIface_V1_1 == nullptr) {
        ALOGI("No GNSS Satellite Blacklist interface available");
        return JNI_FALSE;
    }

    jint *constellation_array = env->GetIntArrayElements(constellations, 0);
    if (nullptr == constellation_array) {
        ALOGI("GetIntArrayElements returns nullptr.");
        return JNI_FALSE;
    }
    jsize length = env->GetArrayLength(constellations);

    jint *sv_id_array = env->GetIntArrayElements(sv_ids, 0);
    if (nullptr == sv_id_array) {
        ALOGI("GetIntArrayElements returns nullptr.");
        return JNI_FALSE;
    }

    if (length != env->GetArrayLength(sv_ids)) {
        ALOGI("Lengths of constellations and sv_ids are inconsistent.");
        return JNI_FALSE;
    }

    hidl_vec<IGnssConfiguration_V1_1::BlacklistedSource> sources;
    sources.resize(length);

    for (int i = 0; i < length; i++) {
        sources[i].constellation = static_cast<GnssConstellationType>(constellation_array[i]);
        sources[i].svid = sv_id_array[i];
    }

    auto result = gnssConfigurationIface_V1_1->setBlacklist(sources);
    if (result.isOk()) {
        return result;
    } else {
        return JNI_FALSE;
    }
}

static jboolean android_location_GnssConfiguration_set_es_extension_sec(
        JNIEnv*, jobject, jint emergencyExtensionSeconds) {
    if (gnssConfigurationIface == nullptr) {
        ALOGE("no GNSS configuration interface available");
        return JNI_FALSE;
    }

    if (gnssConfigurationIface_V2_0 == nullptr) {
        ALOGI("Config parameter ES_EXTENSION_SEC is not supported in IGnssConfiguration.hal"
                " versions earlier than 2.0.");
        return JNI_FALSE;
    }

    auto result = gnssConfigurationIface_V2_0->setEsExtensionSec(emergencyExtensionSeconds);
    if (result.isOk()) {
        return result;
    } else {
        return JNI_FALSE;
    }
}

static jint android_location_GnssBatchingProvider_get_batch_size(JNIEnv*, jclass) {
    if (gnssBatchingIface == nullptr) {
        return 0; // batching not supported, size = 0
    }
    auto result = gnssBatchingIface->getBatchSize();
    if (result.isOk()) {
        return static_cast<jint>(result);
    } else {
        return 0; // failure in binder, don't support batching
    }
}

static jboolean android_location_GnssBatchingProvider_init_batching(JNIEnv*, jclass) {
    if (gnssBatchingIface_V2_0 != nullptr) {
        sp<IGnssBatchingCallback_V2_0> gnssBatchingCbIface_V2_0 = new GnssBatchingCallback_V2_0();
        return static_cast<jboolean>(gnssBatchingIface_V2_0->init_2_0(gnssBatchingCbIface_V2_0));
    } else if (gnssBatchingIface != nullptr) {
        sp<IGnssBatchingCallback_V1_0> gnssBatchingCbIface_V1_0 = new GnssBatchingCallback_V1_0();
        return static_cast<jboolean>(gnssBatchingIface->init(gnssBatchingCbIface_V1_0));
    } else {
        return JNI_FALSE; // batching not supported
    }
}

static void android_location_GnssBatchingProvider_cleanup_batching(JNIEnv*, jclass) {
    if (gnssBatchingIface == nullptr) {
        return; // batching not supported
    }
    gnssBatchingIface->cleanup();
}

static jboolean android_location_GnssBatchingProvider_start_batch(JNIEnv*, jclass,
        jlong periodNanos, jboolean wakeOnFifoFull) {
    if (gnssBatchingIface == nullptr) {
        return JNI_FALSE; // batching not supported
    }

    IGnssBatching_V1_0::Options options;
    options.periodNanos = periodNanos;
    if (wakeOnFifoFull) {
        options.flags = static_cast<uint8_t>(IGnssBatching_V1_0::Flag::WAKEUP_ON_FIFO_FULL);
    } else {
        options.flags = 0;
    }

    return static_cast<jboolean>(gnssBatchingIface->start(options));
}

static void android_location_GnssBatchingProvider_flush_batch(JNIEnv*, jclass) {
    if (gnssBatchingIface == nullptr) {
        return; // batching not supported
    }

    gnssBatchingIface->flush();
}

static jboolean android_location_GnssBatchingProvider_stop_batch(JNIEnv*, jclass) {
    if (gnssBatchingIface == nullptr) {
        return JNI_FALSE; // batching not supported
    }

    return gnssBatchingIface->stop();
}

static jboolean android_location_GnssVisibilityControl_enable_nfw_location_access(
        JNIEnv* env, jobject, jobjectArray proxyApps) {
    if (gnssVisibilityControlIface == nullptr) {
        ALOGI("No GNSS Visibility Control interface available");
        return JNI_FALSE;
    }

    const jsize length = env->GetArrayLength(proxyApps);
    hidl_vec<hidl_string> hidlProxyApps(length);
    for (int i = 0; i < length; ++i) {
        jstring proxyApp = (jstring) (env->GetObjectArrayElement(proxyApps, i));
        ScopedJniString jniProxyApp(env, proxyApp);
        hidlProxyApps[i] = jniProxyApp;
    }

    auto result = gnssVisibilityControlIface->enableNfwLocationAccess(hidlProxyApps);
    if (result.isOk()) {
        return result;
    } else {
        return JNI_FALSE;
    }
}

static const JNINativeMethod sMethods[] = {
     /* name, signature, funcPtr */
    {"class_init_native", "()V", reinterpret_cast<void *>(
            android_location_GnssLocationProvider_class_init_native)},
    {"native_is_supported", "()Z", reinterpret_cast<void *>(
            android_location_GnssLocationProvider_is_supported)},
    {"native_init_once", "(Z)V", reinterpret_cast<void *>(
            android_location_GnssLocationProvider_init_once)},
    {"native_init", "()Z", reinterpret_cast<void *>(android_location_GnssLocationProvider_init)},
    {"native_cleanup", "()V", reinterpret_cast<void *>(
            android_location_GnssLocationProvider_cleanup)},
    {"native_set_position_mode", "(IIIIIZ)Z", reinterpret_cast<void *>(
            android_location_GnssLocationProvider_set_position_mode)},
    {"native_start", "()Z", reinterpret_cast<void *>(
            android_location_GnssLocationProvider_start)},
    {"native_stop", "()Z", reinterpret_cast<void *>(
            android_location_GnssLocationProvider_stop)},
    {"native_delete_aiding_data", "(I)V", reinterpret_cast<void *>(
            android_location_GnssLocationProvider_delete_aiding_data)},
    {"native_read_nmea", "([BI)I", reinterpret_cast<void *>(
            android_location_GnssLocationProvider_read_nmea)},
    {"native_inject_time", "(JJI)V", reinterpret_cast<void *>(
            android_location_GnssLocationProvider_inject_time)},
    {"native_inject_best_location", "(IDDDFFFFFFJIJD)V", reinterpret_cast<void *>(
            android_location_GnssLocationProvider_inject_best_location)},
    {"native_inject_location", "(DDF)V", reinterpret_cast<void *>(
            android_location_GnssLocationProvider_inject_location)},
    {"native_supports_psds", "()Z", reinterpret_cast<void *>(
            android_location_GnssLocationProvider_supports_psds)},
    {"native_inject_psds_data", "([BI)V", reinterpret_cast<void *>(
            android_location_GnssLocationProvider_inject_psds_data)},
    {"native_agps_set_id", "(ILjava/lang/String;)V", reinterpret_cast<void *>(
            android_location_GnssLocationProvider_agps_set_id)},
    {"native_agps_set_ref_location_cellid", "(IIIII)V", reinterpret_cast<void *>(
            android_location_GnssLocationProvider_agps_set_reference_location_cellid)},
    {"native_set_agps_server", "(ILjava/lang/String;I)V", reinterpret_cast<void *>(
            android_location_GnssLocationProvider_set_agps_server)},
    {"native_send_ni_response", "(II)V", reinterpret_cast<void *>(
            android_location_GnssLocationProvider_send_ni_response)},
    {"native_get_internal_state", "()Ljava/lang/String;", reinterpret_cast<void *>(
            android_location_GnssLocationProvider_get_internal_state)},
    {"native_is_gnss_visibility_control_supported", "()Z", reinterpret_cast<void *>(
            android_location_GnssLocationProvider_is_gnss_visibility_control_supported)},
};

static const JNINativeMethod sMethodsBatching[] = {
     /* name, signature, funcPtr */
    {"native_get_batch_size",
            "()I",
            reinterpret_cast<void *>(android_location_GnssBatchingProvider_get_batch_size)},
    {"native_start_batch",
            "(JZ)Z",
            reinterpret_cast<void *>(android_location_GnssBatchingProvider_start_batch)},
    {"native_flush_batch",
            "()V",
            reinterpret_cast<void *>(android_location_GnssBatchingProvider_flush_batch)},
    {"native_stop_batch",
            "()Z",
            reinterpret_cast<void *>(android_location_GnssBatchingProvider_stop_batch)},
    {"native_init_batching",
            "()Z",
            reinterpret_cast<void *>(android_location_GnssBatchingProvider_init_batching)},
    {"native_cleanup_batching",
            "()V",
            reinterpret_cast<void *>(android_location_GnssBatchingProvider_cleanup_batching)},
};

static const JNINativeMethod sGeofenceMethods[] = {
     /* name, signature, funcPtr */
    {"native_is_geofence_supported",
            "()Z",
            reinterpret_cast<void *>(android_location_GnssGeofenceProvider_is_geofence_supported)},
    {"native_add_geofence",
            "(IDDDIIII)Z",
            reinterpret_cast<void *>(android_location_GnssGeofenceProvider_add_geofence)},
    {"native_remove_geofence",
            "(I)Z",
            reinterpret_cast<void *>(android_location_GnssGeofenceProvider_remove_geofence)},
    {"native_pause_geofence", "(I)Z", reinterpret_cast<void *>(
            android_location_GnssGeofenceProvider_pause_geofence)},
    {"native_resume_geofence",
            "(II)Z",
            reinterpret_cast<void *>(android_location_GnssGeofenceProvider_resume_geofence)},
};

static const JNINativeMethod sMeasurementMethods[] = {
    /* name, signature, funcPtr */
    {"native_is_measurement_supported", "()Z",
            reinterpret_cast<void*>(
            android_location_GnssMeasurementsProvider_is_measurement_supported)},
    {"native_start_measurement_collection", "(Z)Z",
            reinterpret_cast<void*>(
            android_location_GnssMeasurementsProvider_start_measurement_collection)},
    {"native_stop_measurement_collection", "()Z",
            reinterpret_cast<void*>(
            android_location_GnssMeasurementsProvider_stop_measurement_collection)},
};

static const JNINativeMethod sMeasurementCorrectionsMethods[] = {
    /* name, signature, funcPtr */
    {"native_is_measurement_corrections_supported", "()Z",
            reinterpret_cast<void*>(
            android_location_GnssMeasurementCorrectionsProvider_is_measurement_corrections_supported)},
    {"native_inject_gnss_measurement_corrections",
            "(Landroid/location/GnssMeasurementCorrections;)Z",
            reinterpret_cast<void*>(
            android_location_GnssMeasurementCorrectionsProvider_inject_gnss_measurement_corrections)},
};

static const JNINativeMethod sNavigationMessageMethods[] = {
     /* name, signature, funcPtr */
    {"native_is_navigation_message_supported",
            "()Z",
            reinterpret_cast<void *>(
                    android_location_GnssNavigationMessageProvider_is_navigation_message_supported)},
    {"native_start_navigation_message_collection",
            "()Z",
            reinterpret_cast<void *>(
                    android_location_GnssNavigationMessageProvider_start_navigation_message_collection)},
    {"native_stop_navigation_message_collection",
            "()Z",
            reinterpret_cast<void *>(
                    android_location_GnssNavigationMessageProvider_stop_navigation_message_collection)},
};

static const JNINativeMethod sNetworkConnectivityMethods[] = {
     /* name, signature, funcPtr */
    {"native_is_agps_ril_supported", "()Z",
            reinterpret_cast<void *>(android_location_GnssNetworkConnectivityHandler_is_agps_ril_supported)},
    {"native_update_network_state",
            "(ZIZZLjava/lang/String;JS)V",
            reinterpret_cast<void *>(android_location_GnssNetworkConnectivityHandler_update_network_state)},
    {"native_agps_data_conn_open",
            "(JLjava/lang/String;I)V",
            reinterpret_cast<void *>(android_location_GnssNetworkConnectivityHandler_agps_data_conn_open)},
    {"native_agps_data_conn_closed",
            "()V",
            reinterpret_cast<void *>(android_location_GnssNetworkConnectivityHandler_agps_data_conn_closed)},
    {"native_agps_data_conn_failed",
            "()V",
            reinterpret_cast<void *>(android_location_GnssNetworkConnectivityHandler_agps_data_conn_failed)},
};

static const JNINativeMethod sConfigurationMethods[] = {
     /* name, signature, funcPtr */
    {"native_get_gnss_configuration_version",
            "()Lcom/android/server/location/GnssConfiguration$HalInterfaceVersion;",
            reinterpret_cast<void *>(
                    android_location_GnssConfiguration_get_gnss_configuration_version)},
    {"native_set_supl_es",
            "(I)Z",
            reinterpret_cast<void *>(android_location_GnssConfiguration_set_supl_es)},
    {"native_set_supl_version",
            "(I)Z",
            reinterpret_cast<void *>(android_location_GnssConfiguration_set_supl_version)},
    {"native_set_supl_mode",
            "(I)Z",
            reinterpret_cast<void *>(android_location_GnssConfiguration_set_supl_mode)},
    {"native_set_lpp_profile",
            "(I)Z",
            reinterpret_cast<void *>(android_location_GnssConfiguration_set_lpp_profile)},
    {"native_set_gnss_pos_protocol_select",
            "(I)Z",
            reinterpret_cast<void *>(
                    android_location_GnssConfiguration_set_gnss_pos_protocol_select)},
    {"native_set_gps_lock",
            "(I)Z",
            reinterpret_cast<void *>(android_location_GnssConfiguration_set_gps_lock)},
    {"native_set_emergency_supl_pdn",
            "(I)Z",
            reinterpret_cast<void *>(android_location_GnssConfiguration_set_emergency_supl_pdn)},
    {"native_set_satellite_blacklist",
            "([I[I)Z",
            reinterpret_cast<void *>(android_location_GnssConfiguration_set_satellite_blacklist)},
    {"native_set_es_extension_sec",
            "(I)Z",
            reinterpret_cast<void *>(android_location_GnssConfiguration_set_es_extension_sec)},
};

static const JNINativeMethod sVisibilityControlMethods[] = {
     /* name, signature, funcPtr */
    {"native_enable_nfw_location_access",
            "([Ljava/lang/String;)Z",
            reinterpret_cast<void *>(
                    android_location_GnssVisibilityControl_enable_nfw_location_access)},
};

int register_android_server_location_GnssLocationProvider(JNIEnv* env) {
    jniRegisterNativeMethods(
            env,
            "com/android/server/location/GnssBatchingProvider",
            sMethodsBatching,
            NELEM(sMethodsBatching));
    jniRegisterNativeMethods(
            env,
            "com/android/server/location/GnssGeofenceProvider",
            sGeofenceMethods,
            NELEM(sGeofenceMethods));
    jniRegisterNativeMethods(
            env,
            "com/android/server/location/GnssMeasurementsProvider",
            sMeasurementMethods,
            NELEM(sMeasurementMethods));
    jniRegisterNativeMethods(
            env,
            "com/android/server/location/GnssMeasurementCorrectionsProvider",
            sMeasurementCorrectionsMethods,
            NELEM(sMeasurementCorrectionsMethods));
    jniRegisterNativeMethods(
            env,
            "com/android/server/location/GnssNavigationMessageProvider",
            sNavigationMessageMethods,
            NELEM(sNavigationMessageMethods));
    jniRegisterNativeMethods(
            env,
            "com/android/server/location/GnssNetworkConnectivityHandler",
            sNetworkConnectivityMethods,
            NELEM(sNetworkConnectivityMethods));
    jniRegisterNativeMethods(
            env,
            "com/android/server/location/GnssConfiguration",
            sConfigurationMethods,
            NELEM(sConfigurationMethods));
    jniRegisterNativeMethods(
            env,
            "com/android/server/location/GnssVisibilityControl",
            sVisibilityControlMethods,
            NELEM(sVisibilityControlMethods));
    return jniRegisterNativeMethods(
            env,
            "com/android/server/location/GnssLocationProvider",
            sMethods,
            NELEM(sMethods));
}

} /* namespace android */
