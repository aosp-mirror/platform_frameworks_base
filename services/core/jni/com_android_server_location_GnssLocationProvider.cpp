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

#include <android/hardware/gnss/1.0/IGnssMeasurement.h>
#include <android/hardware/gnss/1.1/IGnssMeasurement.h>
#include <nativehelper/JNIHelp.h>
#include "jni.h"
#include "hardware_legacy/power.h"
#include "utils/Log.h"
#include "utils/misc.h"
#include "android_runtime/AndroidRuntime.h"
#include "android_runtime/Log.h"

#include <arpa/inet.h>
#include <limits>
#include <linux/in.h>
#include <linux/in6.h>
#include <pthread.h>
#include <string.h>
#include <cinttypes>

static jobject mCallbacksObj = NULL;

static jmethodID method_reportLocation;
static jmethodID method_reportStatus;
static jmethodID method_reportSvStatus;
static jmethodID method_reportAGpsStatus;
static jmethodID method_reportNmea;
static jmethodID method_setEngineCapabilities;
static jmethodID method_setGnssYearOfHardware;
static jmethodID method_setGnssHardwareModelName;
static jmethodID method_xtraDownloadRequest;
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
using android::hardware::hidl_death_recipient;
using android::hardware::gnss::V1_0::GnssConstellationType;
using android::hardware::gnss::V1_0::GnssLocation;
using android::hardware::gnss::V1_0::GnssLocationFlags;

using android::hardware::gnss::V1_0::IAGnss;
using android::hardware::gnss::V1_0::IAGnssCallback;
using android::hardware::gnss::V1_0::IAGnssCallback;
using android::hardware::gnss::V1_0::IAGnssRil;
using android::hardware::gnss::V1_0::IAGnssRilCallback;
using android::hardware::gnss::V1_0::IGnssBatching;
using android::hardware::gnss::V1_0::IGnssBatchingCallback;
using android::hardware::gnss::V1_0::IGnssDebug;
using android::hardware::gnss::V1_0::IGnssGeofenceCallback;
using android::hardware::gnss::V1_0::IGnssGeofencing;
using android::hardware::gnss::V1_0::IGnssNavigationMessage;
using android::hardware::gnss::V1_0::IGnssNavigationMessageCallback;
using android::hardware::gnss::V1_0::IGnssNi;
using android::hardware::gnss::V1_0::IGnssNiCallback;
using android::hardware::gnss::V1_0::IGnssXtra;
using android::hardware::gnss::V1_0::IGnssXtraCallback;

using android::hardware::gnss::V1_1::IGnssCallback;

using android::hidl::base::V1_0::IBase;

using IGnss_V1_0 = android::hardware::gnss::V1_0::IGnss;
using IGnss_V1_1 = android::hardware::gnss::V1_1::IGnss;
using IGnssConfiguration_V1_0 = android::hardware::gnss::V1_0::IGnssConfiguration;
using IGnssConfiguration_V1_1 = android::hardware::gnss::V1_1::IGnssConfiguration;
using IGnssMeasurement_V1_0 = android::hardware::gnss::V1_0::IGnssMeasurement;
using IGnssMeasurement_V1_1 = android::hardware::gnss::V1_1::IGnssMeasurement;
using IGnssMeasurementCallback_V1_0 = android::hardware::gnss::V1_0::IGnssMeasurementCallback;
using IGnssMeasurementCallback_V1_1 = android::hardware::gnss::V1_1::IGnssMeasurementCallback;


struct GnssDeathRecipient : virtual public hidl_death_recipient
{
    // hidl_death_recipient interface
    virtual void serviceDied(uint64_t cookie, const wp<IBase>& who) override {
      // TODO(b/37460011): implement a better death recovery mechanism without
      // crashing system server process as described in go//treble-gnss-death
      LOG_ALWAYS_FATAL("Abort due to IGNSS hidl service failure,"
            " restarting system server");
    }
};

// Must match the value from GnssMeasurement.java
static const uint32_t ADR_STATE_HALF_CYCLE_REPORTED = (1<<4);

sp<GnssDeathRecipient> gnssHalDeathRecipient = nullptr;
sp<IGnss_V1_0> gnssHal = nullptr;
sp<IGnss_V1_1> gnssHal_V1_1 = nullptr;
sp<IGnssXtra> gnssXtraIface = nullptr;
sp<IAGnssRil> agnssRilIface = nullptr;
sp<IGnssGeofencing> gnssGeofencingIface = nullptr;
sp<IAGnss> agnssIface = nullptr;
sp<IGnssBatching> gnssBatchingIface = nullptr;
sp<IGnssDebug> gnssDebugIface = nullptr;
sp<IGnssConfiguration_V1_0> gnssConfigurationIface = nullptr;
sp<IGnssConfiguration_V1_1> gnssConfigurationIface_V1_1 = nullptr;
sp<IGnssNi> gnssNiIface = nullptr;
sp<IGnssMeasurement_V1_0> gnssMeasurementIface = nullptr;
sp<IGnssMeasurement_V1_1> gnssMeasurementIface_V1_1 = nullptr;
sp<IGnssNavigationMessage> gnssNavigationMessageIface = nullptr;

#define WAKE_LOCK_NAME  "GPS"

namespace android {

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
    static const char *const signature_;
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
    JavaObject(JNIEnv* env, const char* class_name);
    JavaObject(JNIEnv* env, const char* class_name, const char * sz_arg_1);
    virtual ~JavaObject();

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

JavaObject::JavaObject(JNIEnv* env, const char* class_name) : env_(env) {
    clazz_ = env_->FindClass(class_name);
    jmethodID ctor = env->GetMethodID(clazz_, "<init>", "()V");
    object_ = env_->NewObject(clazz_, ctor);
}

JavaObject::JavaObject(JNIEnv* env, const char* class_name, const char * sz_arg_1) : env_(env) {
    clazz_ = env_->FindClass(class_name);
    jmethodID ctor = env->GetMethodID(clazz_, "<init>", "(Ljava/lang/String;)V");
    object_ = env_->NewObject(clazz_, ctor, env->NewStringUTF(sz_arg_1));
}

JavaObject::~JavaObject() {
    env_->DeleteLocalRef(clazz_);
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
const char *const JavaMethodHelper<float>::signature_ = "(F)V";
template<>
const char *const JavaMethodHelper<double>::signature_ = "(D)V";
template<>
const char *const JavaMethodHelper<bool>::signature_ = "(Z)V";

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

static jobject translateLocation(JNIEnv* env, const GnssLocation& location) {
    JavaObject object(env, "android/location/Location", "gps");

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

    return object.get();
}

static GnssLocation createGnssLocation(
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
        jlong timestamp) {
    GnssLocation location;
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

/*
 * GnssCallback class implements the callback methods for IGnss interface.
 */
struct GnssCallback : public IGnssCallback {
    Return<void> gnssLocationCb(const GnssLocation& location) override;
    Return<void> gnssStatusCb(const IGnssCallback::GnssStatusValue status) override;
    Return<void> gnssSvStatusCb(const IGnssCallback::GnssSvStatus& svStatus) override;
    Return<void> gnssNmeaCb(int64_t timestamp, const android::hardware::hidl_string& nmea) override;
    Return<void> gnssSetCapabilitesCb(uint32_t capabilities) override;
    Return<void> gnssAcquireWakelockCb() override;
    Return<void> gnssReleaseWakelockCb() override;
    Return<void> gnssRequestTimeCb() override;
    Return<void> gnssRequestLocationCb(const bool independentFromGnss) override;
    Return<void> gnssSetSystemInfoCb(const IGnssCallback::GnssSystemInfo& info) override;

    // New in 1.1
    Return<void> gnssNameCb(const android::hardware::hidl_string& name) override;

    // TODO(b/73306084): Reconsider allocation cost vs threadsafety on these statics
    static const char* sNmeaString;
    static size_t sNmeaStringLength;
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

Return<void> GnssCallback::gnssLocationCb(const GnssLocation& location) {
    JNIEnv* env = getJniEnv();

    jobject jLocation = translateLocation(env, location);
    bool hasLatLong = (static_cast<uint32_t>(location.gnssLocationFlags) &
            GnssLocationFlags::HAS_LAT_LONG) != 0;

    env->CallVoidMethod(mCallbacksObj,
                        method_reportLocation,
                        boolToJbool(hasLatLong),
                        jLocation);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    env->DeleteLocalRef(jLocation);
    return Void();
}

Return<void> GnssCallback::gnssStatusCb(const IGnssCallback::GnssStatusValue status) {
    JNIEnv* env = getJniEnv();
    env->CallVoidMethod(mCallbacksObj, method_reportStatus, status);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return Void();
}

Return<void> GnssCallback::gnssSvStatusCb(const IGnssCallback::GnssSvStatus& svStatus) {
    JNIEnv* env = getJniEnv();

    uint32_t listSize = svStatus.numSvs;
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

        const IGnssCallback::GnssSvInfo& info = svStatus.gnssSvList.data()[i];
        svidWithFlags[i] = (info.svid << SVID_SHIFT_WIDTH) |
            (static_cast<uint32_t>(info.constellation) << CONSTELLATION_TYPE_SHIFT_WIDTH) |
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
    env->CallVoidMethod(mCallbacksObj, method_setEngineCapabilities, capabilities);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return Void();
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
    JNIEnv* env = getJniEnv();
    env->CallVoidMethod(mCallbacksObj, method_requestLocation, boolToJbool(independentFromGnss));
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
    env->CallVoidMethod(mCallbacksObj, method_xtraDownloadRequest);
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
            const GnssLocation& location,
            GeofenceTransition transition,
            hardware::gnss::V1_0::GnssUtcTime timestamp) override;
    Return<void> gnssGeofenceStatusCb(
            GeofenceAvailability status,
            const GnssLocation& location) override;
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
        int32_t geofenceId,
        const GnssLocation& location,
        GeofenceTransition transition,
        hardware::gnss::V1_0::GnssUtcTime timestamp) {
    JNIEnv* env = getJniEnv();

    jobject jLocation = translateLocation(env, location);

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

Return<void> GnssGeofenceCallback::gnssGeofenceStatusCb(
        GeofenceAvailability status,
        const GnssLocation& location) {
    JNIEnv* env = getJniEnv();

    jobject jLocation = translateLocation(env, location);

    env->CallVoidMethod(mCallbacksObj,
                        method_reportGeofenceStatus,
                        status,
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
    if (dataLength == 0 || data == NULL) {
      ALOGE("Invalid Navigation Message found: data=%p, length=%zd", data,
            dataLength);
      return Void();
    }

    JavaObject object(env, "android/location/GnssNavigationMessage");
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
struct GnssMeasurementCallback : public IGnssMeasurementCallback_V1_1 {
    Return<void> gnssMeasurementCb(const IGnssMeasurementCallback_V1_1::GnssData& data) override;
    Return<void> GnssMeasurementCb(const IGnssMeasurementCallback_V1_0::GnssData& data) override;
 private:
    void translateGnssMeasurement_V1_0(
            JNIEnv* env, const IGnssMeasurementCallback_V1_0::GnssMeasurement* measurement,
            JavaObject& object);
    jobjectArray translateGnssMeasurements(
            JNIEnv* env,
            const IGnssMeasurementCallback_V1_1::GnssMeasurement* measurements_v1_1,
            const IGnssMeasurementCallback_V1_0::GnssMeasurement* measurements_v1_0,
            size_t count);
    jobject translateGnssClock(
            JNIEnv* env, const IGnssMeasurementCallback_V1_0::GnssClock* clock);
    void setMeasurementData(JNIEnv* env, jobject clock, jobjectArray measurementArray);
};


Return<void> GnssMeasurementCallback::gnssMeasurementCb(
        const IGnssMeasurementCallback_V1_1::GnssData& data) {
    JNIEnv* env = getJniEnv();

    jobject clock;
    jobjectArray measurementArray;

    clock = translateGnssClock(env, &data.clock);

    measurementArray = translateGnssMeasurements(
        env, data.measurements.data(), NULL, data.measurements.size());
    setMeasurementData(env, clock, measurementArray);

    env->DeleteLocalRef(clock);
    env->DeleteLocalRef(measurementArray);
    return Void();
}

Return<void> GnssMeasurementCallback::GnssMeasurementCb(
        const IGnssMeasurementCallback_V1_0::GnssData& data) {
    JNIEnv* env = getJniEnv();

    jobject clock;
    jobjectArray measurementArray;

    clock = translateGnssClock(env, &data.clock);
    measurementArray = translateGnssMeasurements(
        env, NULL, data.measurements.data(), data.measurementCount);
    setMeasurementData(env, clock, measurementArray);

    env->DeleteLocalRef(clock);
    env->DeleteLocalRef(measurementArray);
    return Void();
}

// preallocate object as: JavaObject object(env, "android/location/GnssMeasurement");
void GnssMeasurementCallback::translateGnssMeasurement_V1_0(
        JNIEnv* env, const IGnssMeasurementCallback_V1_0::GnssMeasurement* measurement,
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
        !ADR_STATE_HALF_CYCLE_REPORTED)); // Half Cycle state not reported from Hardware in V1_0
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

jobject GnssMeasurementCallback::translateGnssClock(
       JNIEnv* env, const IGnssMeasurementCallback_V1_0::GnssClock* clock) {
    JavaObject object(env, "android/location/GnssClock");

    uint32_t flags = static_cast<uint32_t>(clock->gnssClockFlags);
    if (flags & static_cast<uint32_t>(GnssClockFlags::HAS_LEAP_SECOND)) {
        SET(LeapSecond, static_cast<int32_t>(clock->leapSecond));
    }

    if (flags & static_cast<uint32_t>(GnssClockFlags::HAS_TIME_UNCERTAINTY)) {
        SET(TimeUncertaintyNanos, clock->timeUncertaintyNs);
    }

    if (flags & static_cast<uint32_t>(GnssClockFlags::HAS_FULL_BIAS)) {
        SET(FullBiasNanos, clock->fullBiasNs);
    }

    if (flags & static_cast<uint32_t>(GnssClockFlags::HAS_BIAS)) {
        SET(BiasNanos, clock->biasNs);
    }

    if (flags & static_cast<uint32_t>(GnssClockFlags::HAS_BIAS_UNCERTAINTY)) {
        SET(BiasUncertaintyNanos, clock->biasUncertaintyNs);
    }

    if (flags & static_cast<uint32_t>(GnssClockFlags::HAS_DRIFT)) {
        SET(DriftNanosPerSecond, clock->driftNsps);
    }

    if (flags & static_cast<uint32_t>(GnssClockFlags::HAS_DRIFT_UNCERTAINTY)) {
        SET(DriftUncertaintyNanosPerSecond, clock->driftUncertaintyNsps);
    }

    SET(TimeNanos, clock->timeNs);
    SET(HardwareClockDiscontinuityCount, clock->hwClockDiscontinuityCount);

    return object.get();
}

jobjectArray GnssMeasurementCallback::translateGnssMeasurements(JNIEnv* env,
         const IGnssMeasurementCallback_V1_1::GnssMeasurement* measurements_v1_1,
         const IGnssMeasurementCallback_V1_0::GnssMeasurement* measurements_v1_0,
         size_t count) {
    if (count == 0) {
        return NULL;
    }

    jclass gnssMeasurementClass = env->FindClass("android/location/GnssMeasurement");
    jobjectArray gnssMeasurementArray = env->NewObjectArray(
            count,
            gnssMeasurementClass,
            NULL /* initialElement */);

    for (uint16_t i = 0; i < count; ++i) {
        JavaObject object(env, "android/location/GnssMeasurement");
        if (measurements_v1_1 != NULL) {
            translateGnssMeasurement_V1_0(env, &(measurements_v1_1[i].v1_0), object);

            // Set the V1_1 flag, and mark that new field has valid information for Java Layer
            SET(AccumulatedDeltaRangeState,
                    (static_cast<int32_t>(measurements_v1_1[i].accumulatedDeltaRangeState) |
                    ADR_STATE_HALF_CYCLE_REPORTED));
        } else {
            translateGnssMeasurement_V1_0(env, &(measurements_v1_0[i]), object);
        }

        env->SetObjectArrayElement(gnssMeasurementArray, i, object.get());
    }

    env->DeleteLocalRef(gnssMeasurementClass);
    return gnssMeasurementArray;
}

void GnssMeasurementCallback::setMeasurementData(JNIEnv* env, jobject clock,
                             jobjectArray measurementArray) {
    jclass gnssMeasurementsEventClass =
            env->FindClass("android/location/GnssMeasurementsEvent");
    jmethodID gnssMeasurementsEventCtor =
            env->GetMethodID(
                    gnssMeasurementsEventClass,
                    "<init>",
                    "(Landroid/location/GnssClock;[Landroid/location/GnssMeasurement;)V");

    jobject gnssMeasurementsEvent = env->NewObject(gnssMeasurementsEventClass,
                                                   gnssMeasurementsEventCtor,
                                                   clock,
                                                   measurementArray);

    env->CallVoidMethod(mCallbacksObj, method_reportMeasurementData,
                      gnssMeasurementsEvent);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    env->DeleteLocalRef(gnssMeasurementsEventClass);
    env->DeleteLocalRef(gnssMeasurementsEvent);
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
 * AGnssCallback implements callback methods required by the IAGnss interface.
 */
struct AGnssCallback : public IAGnssCallback {
    // Methods from ::android::hardware::gps::V1_0::IAGnssCallback follow.
    Return<void> agnssStatusIpV6Cb(
      const IAGnssCallback::AGnssStatusIpV6& agps_status) override;

    Return<void> agnssStatusIpV4Cb(
      const IAGnssCallback::AGnssStatusIpV4& agps_status) override;
 private:
    jbyteArray convertToIpV4(uint32_t ip);
};

Return<void> AGnssCallback::agnssStatusIpV6Cb(
        const IAGnssCallback::AGnssStatusIpV6& agps_status) {
    JNIEnv* env = getJniEnv();
    jbyteArray byteArray = NULL;
    bool isSupported = false;

    byteArray = env->NewByteArray(16);
    if (byteArray != NULL) {
        env->SetByteArrayRegion(byteArray, 0, 16,
                                (const jbyte*)(agps_status.ipV6Addr.data()));
        isSupported = true;
    } else {
        ALOGE("Unable to allocate byte array for IPv6 address.");
    }

    IF_ALOGD() {
        // log the IP for reference in case there is a bogus value pushed by HAL
        char str[INET6_ADDRSTRLEN];
        inet_ntop(AF_INET6, agps_status.ipV6Addr.data(), str, INET6_ADDRSTRLEN);
        ALOGD("AGPS IP is v6: %s", str);
    }

    jsize byteArrayLength = byteArray != NULL ? env->GetArrayLength(byteArray) : 0;
    ALOGV("Passing AGPS IP addr: size %d", byteArrayLength);
    env->CallVoidMethod(mCallbacksObj, method_reportAGpsStatus,
                        agps_status.type, agps_status.status, byteArray);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);

    if (byteArray) {
        env->DeleteLocalRef(byteArray);
    }

    return Void();
}

Return<void> AGnssCallback::agnssStatusIpV4Cb(
        const IAGnssCallback::AGnssStatusIpV4& agps_status) {
    JNIEnv* env = getJniEnv();
    jbyteArray byteArray = NULL;

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
      byteArray != NULL ? env->GetArrayLength(byteArray) : 0;
    ALOGV("Passing AGPS IP addr: size %d", byteArrayLength);
    env->CallVoidMethod(mCallbacksObj, method_reportAGpsStatus,
                      agps_status.type, agps_status.status, byteArray);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);

    if (byteArray) {
        env->DeleteLocalRef(byteArray);
    }
    return Void();
}

jbyteArray AGnssCallback::convertToIpV4(uint32_t ip) {
    if (INADDR_NONE == ip) {
        return NULL;
    }

    JNIEnv* env = getJniEnv();
    jbyteArray byteArray = env->NewByteArray(4);
    if (byteArray == NULL) {
        ALOGE("Unable to allocate byte array for IPv4 address");
        return NULL;
    }

    jbyte ipv4[4];
    ALOGV("Converting IPv4 address byte array (net_order) %x", ip);
    memcpy(ipv4, &ip, sizeof(ipv4));
    env->SetByteArrayRegion(byteArray, 0, 4, (const jbyte*)ipv4);
    return byteArray;
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

/*
 * GnssBatchingCallback interface implements the callback methods
 * required by the IGnssBatching interface.
 */
struct GnssBatchingCallback : public IGnssBatchingCallback {
    /*
    * Methods from ::android::hardware::gps::V1_0::IGnssBatchingCallback
    * follow.
    */
    Return<void> gnssLocationBatchCb(const hidl_vec<GnssLocation> & locations)
        override;
};

Return<void> GnssBatchingCallback::gnssLocationBatchCb(const hidl_vec<GnssLocation> & locations) {
    JNIEnv* env = getJniEnv();

    jobjectArray jLocations = env->NewObjectArray(locations.size(),
            env->FindClass("android/location/Location"), nullptr);

    for (uint16_t i = 0; i < locations.size(); ++i) {
        jobject jLocation = translateLocation(env, locations[i]);
        env->SetObjectArrayElement(jLocations, i, jLocation);
        env->DeleteLocalRef(jLocation);
    }

    env->CallVoidMethod(mCallbacksObj, method_reportLocationBatch, jLocations);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);

    env->DeleteLocalRef(jLocations);

    return Void();
}

static void android_location_GnssLocationProvider_class_init_native(JNIEnv* env, jclass clazz) {
    gnssHal_V1_1 = IGnss_V1_1::getService();
    if (gnssHal_V1_1 == nullptr) {
        ALOGD("gnssHal 1.1 was null, trying 1.0");
        gnssHal = IGnss_V1_0::getService();
    } else {
        gnssHal = gnssHal_V1_1;
    }
}

static void android_location_GnssLocationProvider_init_once(JNIEnv* env, jclass clazz) {
    method_reportLocation = env->GetMethodID(clazz, "reportLocation",
            "(ZLandroid/location/Location;)V");
    method_reportStatus = env->GetMethodID(clazz, "reportStatus", "(I)V");
    method_reportSvStatus = env->GetMethodID(clazz, "reportSvStatus", "(I[I[F[F[F[F)V");
    method_reportAGpsStatus = env->GetMethodID(clazz, "reportAGpsStatus", "(II[B)V");
    method_reportNmea = env->GetMethodID(clazz, "reportNmea", "(J)V");
    method_setEngineCapabilities = env->GetMethodID(clazz, "setEngineCapabilities", "(I)V");
    method_setGnssYearOfHardware = env->GetMethodID(clazz, "setGnssYearOfHardware", "(I)V");
    method_setGnssHardwareModelName = env->GetMethodID(clazz, "setGnssHardwareModelName",
            "(Ljava/lang/String;)V");
    method_xtraDownloadRequest = env->GetMethodID(clazz, "xtraDownloadRequest", "()V");
    method_reportNiNotification = env->GetMethodID(clazz, "reportNiNotification",
            "(IIIIILjava/lang/String;Ljava/lang/String;II)V");
    method_requestLocation = env->GetMethodID(clazz, "requestLocation", "(Z)V");
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

    /*
     * Save a pointer to JVM.
     */
    jint jvmStatus = env->GetJavaVM(&sJvm);
    if (jvmStatus != JNI_OK) {
        LOG_ALWAYS_FATAL("Unable to get Java VM. Error: %d", jvmStatus);
    }

    if (gnssHal != nullptr) {
      gnssHalDeathRecipient = new GnssDeathRecipient();
      hardware::Return<bool> linked = gnssHal->linkToDeath(
          gnssHalDeathRecipient, /*cookie*/ 0);
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

        auto gnssRil = gnssHal->getExtensionAGnssRil();
        if (!gnssRil.isOk()) {
            ALOGD("Unable to get a handle to AGnssRil");
        } else {
            agnssRilIface = gnssRil;
        }

        auto gnssAgnss = gnssHal->getExtensionAGnss();
        if (!gnssAgnss.isOk()) {
            ALOGD("Unable to get a handle to AGnss");
        } else {
            agnssIface = gnssAgnss;
        }

        auto gnssNavigationMessage = gnssHal->getExtensionGnssNavigationMessage();
        if (!gnssNavigationMessage.isOk()) {
            ALOGD("Unable to get a handle to GnssNavigationMessage");
        } else {
            gnssNavigationMessageIface = gnssNavigationMessage;
        }

        if (gnssHal_V1_1 != nullptr) {
             auto gnssMeasurement = gnssHal_V1_1->getExtensionGnssMeasurement_1_1();
             if (!gnssMeasurement.isOk()) {
                 ALOGD("Unable to get a handle to GnssMeasurement");
             } else {
                 gnssMeasurementIface_V1_1 = gnssMeasurement;
                 gnssMeasurementIface = gnssMeasurementIface_V1_1;
             }
        } else {
             auto gnssMeasurement_V1_0 = gnssHal->getExtensionGnssMeasurement();
             if (!gnssMeasurement_V1_0.isOk()) {
                 ALOGD("Unable to get a handle to GnssMeasurement");
             } else {
                 gnssMeasurementIface = gnssMeasurement_V1_0;
             }
        }

        auto gnssDebug = gnssHal->getExtensionGnssDebug();
        if (!gnssDebug.isOk()) {
            ALOGD("Unable to get a handle to GnssDebug");
        } else {
            gnssDebugIface = gnssDebug;
        }

        auto gnssNi = gnssHal->getExtensionGnssNi();
        if (!gnssNi.isOk()) {
            ALOGD("Unable to get a handle to GnssNi");
        } else {
            gnssNiIface = gnssNi;
        }

        if (gnssHal_V1_1 != nullptr) {
            auto gnssConfiguration = gnssHal_V1_1->getExtensionGnssConfiguration_1_1();
            if (!gnssConfiguration.isOk()) {
                ALOGD("Unable to get a handle to GnssConfiguration");
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

        auto gnssBatching = gnssHal->getExtensionGnssBatching();
        if (!gnssBatching.isOk()) {
            ALOGD("Unable to get a handle to gnssBatching");
        } else {
            gnssBatchingIface = gnssBatching;
        }
    } else {
      ALOGE("Unable to get GPS service\n");
    }
}

static jboolean android_location_GnssLocationProvider_is_supported(
        JNIEnv* /* env */, jclass /* clazz */) {
    return (gnssHal != nullptr) ?  JNI_TRUE : JNI_FALSE;
}

static jboolean android_location_GnssLocationProvider_is_agps_ril_supported(
        JNIEnv* /* env */, jclass /* clazz */) {
    return (agnssRilIface != nullptr) ? JNI_TRUE : JNI_FALSE;
}

static jboolean android_location_gpsLocationProvider_is_gnss_configuration_supported(
        JNIEnv* /* env */, jclass /* jclazz */) {
    return (gnssConfigurationIface != nullptr) ? JNI_TRUE : JNI_FALSE;
}

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
        ALOGE("Unable to Initialize GNSS HAL\n");
        return JNI_FALSE;
    }

    sp<IGnssCallback> gnssCbIface = new GnssCallback();

    Return<bool> result = false;
    if (gnssHal_V1_1 != nullptr) {
        result = gnssHal_V1_1->setCallback_1_1(gnssCbIface);
    } else {
        result = gnssHal->setCallback(gnssCbIface);
    }
    if (!result.isOk() || !result) {
        ALOGE("SetCallback for Gnss Interface fails\n");
        return JNI_FALSE;
    }

    sp<IGnssXtraCallback> gnssXtraCbIface = new GnssXtraCallback();
    if (gnssXtraIface == nullptr) {
        ALOGE("Unable to initialize GNSS Xtra interface\n");
    } else {
        result = gnssXtraIface->setCallback(gnssXtraCbIface);
        if (!result.isOk() || !result) {
            gnssXtraIface = nullptr;
            ALOGI("SetCallback for Gnss Xtra Interface fails\n");
        }
    }

    sp<IAGnssCallback> aGnssCbIface = new AGnssCallback();
    if (agnssIface != nullptr) {
        agnssIface->setCallback(aGnssCbIface);
    } else {
        ALOGI("Unable to Initialize AGnss interface\n");
    }

    sp<IGnssGeofenceCallback> gnssGeofencingCbIface = new GnssGeofenceCallback();
    if (gnssGeofencingIface != nullptr) {
      gnssGeofencingIface->setCallback(gnssGeofencingCbIface);
    } else {
        ALOGI("Unable to initialize GNSS Geofencing interface\n");
    }

    sp<IGnssNiCallback> gnssNiCbIface = new GnssNiCallback();
    if (gnssNiIface != nullptr) {
        gnssNiIface->setCallback(gnssNiCbIface);
    } else {
        ALOGI("Unable to initialize GNSS NI interface\n");
    }

    sp<IAGnssRilCallback> aGnssRilCbIface = new AGnssRilCallback();
    if (agnssRilIface != nullptr) {
        agnssRilIface->setCallback(aGnssRilCbIface);
    } else {
        ALOGI("Unable to Initialize AGnss Ril interface\n");
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
    IAGnssRil::AGnssRefLocation location;

    if (agnssRilIface == nullptr) {
        ALOGE("No AGPS RIL interface in agps_set_reference_location_cellid");
        return;
    }

    switch (static_cast<IAGnssRil::AGnssRefLocationType>(type)) {
        case IAGnssRil::AGnssRefLocationType::GSM_CELLID:
        case IAGnssRil::AGnssRefLocationType::UMTS_CELLID:
          location.type = static_cast<IAGnssRil::AGnssRefLocationType>(type);
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

static void android_location_GnssLocationProvider_agps_set_id(JNIEnv *env, jobject /* obj */,
                                                             jint type, jstring  setid_string) {
    if (agnssRilIface == nullptr) {
        ALOGE("no AGPS RIL interface in agps_set_id");
        return;
    }

    const char *setid = env->GetStringUTFChars(setid_string, NULL);
    agnssRilIface->setSetId((IAGnssRil::SetIDType)type, setid);
    env->ReleaseStringUTFChars(setid_string, setid);
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
        jlong timestamp) {
    if (gnssHal_V1_1 != nullptr) {
        GnssLocation location = createGnssLocation(
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
    } else {
        ALOGE("%s: injectBestLocation() is called but gnssHal_V1_1 is not available.", __func__);
    }
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

static jboolean android_location_GnssLocationProvider_supports_xtra(
        JNIEnv* /* env */, jobject /* obj */) {
    return (gnssXtraIface != nullptr) ? JNI_TRUE : JNI_FALSE;
}

static void android_location_GnssLocationProvider_inject_xtra_data(JNIEnv* env, jobject /* obj */,
        jbyteArray data, jint length) {
    if (gnssXtraIface == nullptr) {
        ALOGE("XTRA Interface not supported");
        return;
    }

    jbyte* bytes = reinterpret_cast<jbyte *>(env->GetPrimitiveArrayCritical(data, 0));
    gnssXtraIface->injectXtraData(std::string((const char*)bytes, length));
    env->ReleasePrimitiveArrayCritical(data, bytes, JNI_ABORT);
}

static void android_location_GnssLocationProvider_agps_data_conn_open(
        JNIEnv* env, jobject /* obj */, jstring apn, jint apnIpType) {
    if (agnssIface == nullptr) {
        ALOGE("no AGPS interface in agps_data_conn_open");
        return;
    }
    if (apn == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return;
    }

    const char *apnStr = env->GetStringUTFChars(apn, NULL);

    auto result = agnssIface->dataConnOpen(apnStr, static_cast<IAGnss::ApnIpType>(apnIpType));
    if (!result.isOk() || !result){
        ALOGE("%s: Failed to set APN and its IP type", __func__);
    }
    env->ReleaseStringUTFChars(apn, apnStr);
}

static void android_location_GnssLocationProvider_agps_data_conn_closed(JNIEnv* /* env */,
                                                                       jobject /* obj */) {
    if (agnssIface == nullptr) {
        ALOGE("%s: AGPS interface not supported", __func__);
        return;
    }

    auto result = agnssIface->dataConnClosed();
    if (!result.isOk() || !result) {
        ALOGE("%s: Failed to close AGnss data connection", __func__);
    }
}

static void android_location_GnssLocationProvider_agps_data_conn_failed(JNIEnv* /* env */,
                                                                       jobject /* obj */) {
    if (agnssIface == nullptr) {
        ALOGE("%s: AGPS interface not supported", __func__);
        return;
    }

    auto result = agnssIface->dataConnFailed();
    if (!result.isOk() || !result) {
        ALOGE("%s: Failed to notify unavailability of AGnss data connection", __func__);
    }
}

static void android_location_GnssLocationProvider_set_agps_server(JNIEnv* env, jobject /* obj */,
        jint type, jstring hostname, jint port) {
    if (agnssIface == nullptr) {
        ALOGE("no AGPS interface in set_agps_server");
        return;
    }

    const char *c_hostname = env->GetStringUTFChars(hostname, NULL);
    auto result = agnssIface->setServer(static_cast<IAGnssCallback::AGnssType>(type),
                                       c_hostname,
                                       port);
    if (!result.isOk() || !result) {
        ALOGE("%s: Failed to set AGnss host name and port", __func__);
    }

    env->ReleaseStringUTFChars(hostname, c_hostname);
}

static void android_location_GnssLocationProvider_send_ni_response(JNIEnv* /* env */,
      jobject /* obj */, jint notifId, jint response) {
    if (gnssNiIface == nullptr) {
        ALOGE("no NI interface in send_ni_response");
        return;
    }

    gnssNiIface->respond(notifId, static_cast<IGnssNiCallback::GnssUserResponseType>(response));
}

static jstring android_location_GnssLocationProvider_get_internal_state(JNIEnv* env,
                                                                       jobject /* obj */) {
    jstring result = NULL;
    /*
     * TODO(b/33089503) : Create a jobject to represent GnssDebug.
     */

    std::stringstream internalState;

    if (gnssDebugIface == nullptr) {
        internalState << "Gnss Debug Interface not available"  << std::endl;
    } else {
        IGnssDebug::DebugData data;
        gnssDebugIface->getDebugData([&data](const IGnssDebug::DebugData& debugData) {
            data = debugData;
        });

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

        internalState << "constellation: 1=GPS, 2=SBAS, 3=GLO, 4=QZSS, 5=BDS, 6=GAL; "
                      << "ephemerisType: 0=Eph, 1=Alm, 2=?; "
                      << "ephemerisSource: 0=Demod, 1=Supl, 2=Server, 3=?; "
                      << "ephemerisHealth: 0=Good, 1=Bad, 2=?" << std::endl;
        for (size_t i = 0; i < data.satelliteDataArray.size(); i++) {
            internalState << "svid: " << data.satelliteDataArray[i].svid
                          << ", constellation: "
                          << static_cast<uint32_t>(data.satelliteDataArray[i].constellation)
                          << ", ephemerisType: "
                          << static_cast<uint32_t>(data.satelliteDataArray[i].ephemerisType)
                          << ", ephemerisSource: "
                          << static_cast<uint32_t>(data.satelliteDataArray[i].ephemerisSource)
                          << ", ephemerisHealth: "
                          << static_cast<uint32_t>(data.satelliteDataArray[i].ephemerisHealth)
                          << ", serverPredictionIsAvailable: "
                          << data.satelliteDataArray[i].serverPredictionIsAvailable
                          << ", serverPredictionAgeSeconds: "
                          << data.satelliteDataArray[i].serverPredictionAgeSeconds
                          << ", ephemerisAgeSeconds: "
                          << data.satelliteDataArray[i].ephemerisAgeSeconds << std::endl;
        }
    }

    result = env->NewStringUTF(internalState.str().c_str());
    return result;
}

static void android_location_GnssLocationProvider_update_network_state(JNIEnv* env,
                                                                       jobject /* obj */,
                                                                       jboolean connected,
                                                                       jint type,
                                                                       jboolean roaming,
                                                                       jboolean available,
                                                                       jstring extraInfo,
                                                                       jstring apn) {
    if (agnssRilIface != nullptr) {
        auto result = agnssRilIface->updateNetworkState(connected,
                                                       static_cast<IAGnssRil::NetworkType>(type),
                                                       roaming);
        if (!result.isOk() || !result) {
            ALOGE("updateNetworkState failed");
        }

        const char *c_apn = env->GetStringUTFChars(apn, NULL);
        result = agnssRilIface->updateNetworkAvailability(available, c_apn);
        if (!result.isOk() || !result) {
            ALOGE("updateNetworkAvailability failed");
        }

        env->ReleaseStringUTFChars(apn, c_apn);
    } else {
        ALOGE("AGnssRilInterface does not exist");
    }
}

static jboolean android_location_GnssLocationProvider_is_geofence_supported(
        JNIEnv* /* env */, jobject /* obj */) {
    return (gnssGeofencingIface != nullptr) ? JNI_TRUE : JNI_FALSE;
}

static jboolean android_location_GnssLocationProvider_add_geofence(JNIEnv* /* env */,
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

static jboolean android_location_GnssLocationProvider_remove_geofence(JNIEnv* /* env */,
        jobject /* obj */, jint geofenceId) {
    if (gnssGeofencingIface != nullptr) {
        auto result = gnssGeofencingIface->removeGeofence(geofenceId);
        return boolToJbool(result.isOk());
    } else {
        ALOGE("Geofence interface not available");
    }
    return JNI_FALSE;
}

static jboolean android_location_GnssLocationProvider_pause_geofence(JNIEnv* /* env */,
        jobject /* obj */, jint geofenceId) {
    if (gnssGeofencingIface != nullptr) {
        auto result = gnssGeofencingIface->pauseGeofence(geofenceId);
        return boolToJbool(result.isOk());
    } else {
        ALOGE("Geofence interface not available");
    }
    return JNI_FALSE;
}

static jboolean android_location_GnssLocationProvider_resume_geofence(JNIEnv* /* env */,
        jobject /* obj */, jint geofenceId, jint monitor_transition) {
    if (gnssGeofencingIface != nullptr) {
        auto result = gnssGeofencingIface->resumeGeofence(geofenceId, monitor_transition);
        return boolToJbool(result.isOk());
    } else {
        ALOGE("Geofence interface not available");
    }
    return JNI_FALSE;
}

static jboolean android_location_GnssLocationProvider_is_measurement_supported(
    JNIEnv* env, jclass clazz) {
    if (gnssMeasurementIface != nullptr) {
        return JNI_TRUE;
    }

    return JNI_FALSE;
}

static jboolean android_location_GnssLocationProvider_start_measurement_collection(
        JNIEnv* /* env */,
        jobject /* obj */,
        jboolean enableFullTracking) {
    if (gnssMeasurementIface == nullptr) {
        ALOGE("GNSS Measurement interface is not available.");
        return JNI_FALSE;
    }

    sp<GnssMeasurementCallback> cbIface = new GnssMeasurementCallback();
    IGnssMeasurement_V1_0::GnssMeasurementStatus result =
                    IGnssMeasurement_V1_0::GnssMeasurementStatus::ERROR_GENERIC;;
    if (gnssMeasurementIface_V1_1 != nullptr) {
         result = gnssMeasurementIface_V1_1->setCallback_1_1(cbIface,
                        enableFullTracking);
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

static jboolean android_location_GnssLocationProvider_stop_measurement_collection(
        JNIEnv* env,
        jobject obj) {
    if (gnssMeasurementIface == nullptr) {
        ALOGE("Measurement interface not available");
        return JNI_FALSE;
    }

    auto result = gnssMeasurementIface->close();
    return boolToJbool(result.isOk());
}

static jboolean android_location_GnssLocationProvider_is_navigation_message_supported(
        JNIEnv* env,
        jclass clazz) {
    if (gnssNavigationMessageIface != nullptr) {
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

static jboolean android_location_GnssLocationProvider_start_navigation_message_collection(
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

static jboolean android_location_GnssLocationProvider_stop_navigation_message_collection(
        JNIEnv* env,
        jobject obj) {
    if (gnssNavigationMessageIface == nullptr) {
        ALOGE("Navigation Message interface is not available.");
        return JNI_FALSE;
    }

    auto result = gnssNavigationMessageIface->close();
    return boolToJbool(result.isOk());
}

static jboolean android_location_GnssLocationProvider_set_emergency_supl_pdn(JNIEnv*,
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

static jboolean android_location_GnssLocationProvider_set_supl_version(JNIEnv*,
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

static jboolean android_location_GnssLocationProvider_set_supl_es(JNIEnv*,
                                                                    jobject,
                                                                    jint suplEs) {
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

static jboolean android_location_GnssLocationProvider_set_supl_mode(JNIEnv*,
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

static jboolean android_location_GnssLocationProvider_set_gps_lock(JNIEnv*,
                                                                   jobject,
                                                                   jint gpsLock) {
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

static jboolean android_location_GnssLocationProvider_set_lpp_profile(JNIEnv*,
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

static jboolean android_location_GnssLocationProvider_set_gnss_pos_protocol_select(JNIEnv*,
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

static jboolean android_location_GnssLocationProvider_set_satellite_blacklist(
        JNIEnv* env, jobject, jintArray constellations, jintArray sv_ids) {
    if (gnssConfigurationIface_V1_1 == nullptr) {
        ALOGI("No GNSS Satellite Blacklist interface available");
        return JNI_FALSE;
    }

    jint *constellation_array = env->GetIntArrayElements(constellations, 0);
    if (NULL == constellation_array) {
        ALOGI("GetIntArrayElements returns NULL.");
        return JNI_FALSE;
    }
    jsize length = env->GetArrayLength(constellations);

    jint *sv_id_array = env->GetIntArrayElements(sv_ids, 0);
    if (NULL == sv_id_array) {
        ALOGI("GetIntArrayElements returns NULL.");
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
    if (gnssBatchingIface == nullptr) {
        return JNI_FALSE; // batching not supported
    }
    sp<IGnssBatchingCallback> gnssBatchingCbIface = new GnssBatchingCallback();

    return static_cast<jboolean>(gnssBatchingIface->init(gnssBatchingCbIface));
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

    IGnssBatching::Options options;
    options.periodNanos = periodNanos;
    if (wakeOnFifoFull) {
        options.flags = static_cast<uint8_t>(IGnssBatching::Flag::WAKEUP_ON_FIFO_FULL);
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

static const JNINativeMethod sMethods[] = {
     /* name, signature, funcPtr */
    {"class_init_native", "()V", reinterpret_cast<void *>(
            android_location_GnssLocationProvider_class_init_native)},
    {"native_is_supported", "()Z", reinterpret_cast<void *>(
            android_location_GnssLocationProvider_is_supported)},
    {"native_is_agps_ril_supported", "()Z",
            reinterpret_cast<void *>(android_location_GnssLocationProvider_is_agps_ril_supported)},
    {"native_is_gnss_configuration_supported", "()Z",
            reinterpret_cast<void *>(
                    android_location_gpsLocationProvider_is_gnss_configuration_supported)},
    {"native_init_once", "()V", reinterpret_cast<void *>(
            android_location_GnssLocationProvider_init_once)},
    {"native_init", "()Z", reinterpret_cast<void *>(android_location_GnssLocationProvider_init)},
    {"native_cleanup", "()V", reinterpret_cast<void *>(
            android_location_GnssLocationProvider_cleanup)},
    {"native_set_position_mode",
                "(IIIIIZ)Z",
                reinterpret_cast<void*>(android_location_GnssLocationProvider_set_position_mode)},
    {"native_start", "()Z", reinterpret_cast<void*>(android_location_GnssLocationProvider_start)},
    {"native_stop", "()Z", reinterpret_cast<void*>(android_location_GnssLocationProvider_stop)},
    {"native_delete_aiding_data",
            "(I)V",
            reinterpret_cast<void*>(android_location_GnssLocationProvider_delete_aiding_data)},
    {"native_read_nmea", "([BI)I", reinterpret_cast<void *>(
            android_location_GnssLocationProvider_read_nmea)},
    {"native_inject_time", "(JJI)V", reinterpret_cast<void *>(
            android_location_GnssLocationProvider_inject_time)},
    {"native_inject_best_location",
            "(IDDDFFFFFFJ)V",
            reinterpret_cast<void *>(android_location_GnssLocationProvider_inject_best_location)},
    {"native_inject_location",
            "(DDF)V",
            reinterpret_cast<void *>(android_location_GnssLocationProvider_inject_location)},
    {"native_supports_xtra", "()Z", reinterpret_cast<void *>(
            android_location_GnssLocationProvider_supports_xtra)},
    {"native_inject_xtra_data",
            "([BI)V",
            reinterpret_cast<void *>(android_location_GnssLocationProvider_inject_xtra_data)},
    {"native_agps_data_conn_open",
            "(Ljava/lang/String;I)V",
            reinterpret_cast<void *>(android_location_GnssLocationProvider_agps_data_conn_open)},
    {"native_agps_data_conn_closed",
            "()V",
            reinterpret_cast<void *>(android_location_GnssLocationProvider_agps_data_conn_closed)},
    {"native_agps_data_conn_failed",
            "()V",
            reinterpret_cast<void *>(android_location_GnssLocationProvider_agps_data_conn_failed)},
    {"native_agps_set_id",
            "(ILjava/lang/String;)V",
            reinterpret_cast<void *>(android_location_GnssLocationProvider_agps_set_id)},
    {"native_agps_set_ref_location_cellid",
            "(IIIII)V",
            reinterpret_cast<void *>(
                    android_location_GnssLocationProvider_agps_set_reference_location_cellid)},
    {"native_set_agps_server",
            "(ILjava/lang/String;I)V",
            reinterpret_cast<void *>(android_location_GnssLocationProvider_set_agps_server)},
    {"native_send_ni_response",
            "(II)V",
            reinterpret_cast<void *>(android_location_GnssLocationProvider_send_ni_response)},
    {"native_get_internal_state",
            "()Ljava/lang/String;",
            reinterpret_cast<void *>(android_location_GnssLocationProvider_get_internal_state)},
    {"native_update_network_state",
            "(ZIZZLjava/lang/String;Ljava/lang/String;)V",
            reinterpret_cast<void *>(android_location_GnssLocationProvider_update_network_state)},
    {"native_is_geofence_supported",
            "()Z",
            reinterpret_cast<void *>(android_location_GnssLocationProvider_is_geofence_supported)},
    {"native_add_geofence",
            "(IDDDIIII)Z",
            reinterpret_cast<void *>(android_location_GnssLocationProvider_add_geofence)},
    {"native_remove_geofence",
            "(I)Z",
            reinterpret_cast<void *>(android_location_GnssLocationProvider_remove_geofence)},
    {"native_pause_geofence", "(I)Z", reinterpret_cast<void *>(
            android_location_GnssLocationProvider_pause_geofence)},
    {"native_resume_geofence",
            "(II)Z",
            reinterpret_cast<void *>(android_location_GnssLocationProvider_resume_geofence)},
    {"native_is_measurement_supported",
            "()Z",
            reinterpret_cast<void *>(
                    android_location_GnssLocationProvider_is_measurement_supported)},
    {"native_start_measurement_collection",
             "(Z)Z",
            reinterpret_cast<void *>(
                    android_location_GnssLocationProvider_start_measurement_collection)},
    {"native_stop_measurement_collection",
            "()Z",
            reinterpret_cast<void *>(
                    android_location_GnssLocationProvider_stop_measurement_collection)},
    {"native_is_navigation_message_supported",
            "()Z",
            reinterpret_cast<void *>(
                    android_location_GnssLocationProvider_is_navigation_message_supported)},
    {"native_start_navigation_message_collection",
            "()Z",
            reinterpret_cast<void *>(
                    android_location_GnssLocationProvider_start_navigation_message_collection)},
    {"native_stop_navigation_message_collection",
            "()Z",
            reinterpret_cast<void *>(
                    android_location_GnssLocationProvider_stop_navigation_message_collection)},
    {"native_set_supl_es",
            "(I)Z",
            reinterpret_cast<void *>(android_location_GnssLocationProvider_set_supl_es)},
    {"native_set_supl_version",
            "(I)Z",
            reinterpret_cast<void *>(android_location_GnssLocationProvider_set_supl_version)},
    {"native_set_supl_mode",
            "(I)Z",
            reinterpret_cast<void *>(android_location_GnssLocationProvider_set_supl_mode)},
    {"native_set_lpp_profile",
            "(I)Z",
            reinterpret_cast<void *>(android_location_GnssLocationProvider_set_lpp_profile)},
    {"native_set_gnss_pos_protocol_select",
            "(I)Z",
            reinterpret_cast<void *>(
                    android_location_GnssLocationProvider_set_gnss_pos_protocol_select)},
    {"native_set_gps_lock",
            "(I)Z",
            reinterpret_cast<void *>(android_location_GnssLocationProvider_set_gps_lock)},
    {"native_set_emergency_supl_pdn",
            "(I)Z",
            reinterpret_cast<void *>(android_location_GnssLocationProvider_set_emergency_supl_pdn)},
    {"native_set_satellite_blacklist",
            "([I[I)Z",
            reinterpret_cast<void *>(android_location_GnssLocationProvider_set_satellite_blacklist)},
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

int register_android_server_location_GnssLocationProvider(JNIEnv* env) {
    jniRegisterNativeMethods(
            env,
            "com/android/server/location/GnssBatchingProvider",
            sMethodsBatching,
            NELEM(sMethodsBatching));
    return jniRegisterNativeMethods(
            env,
            "com/android/server/location/GnssLocationProvider",
            sMethods,
            NELEM(sMethods));
}

} /* namespace android */
