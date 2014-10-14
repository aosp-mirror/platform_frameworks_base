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

#define LOG_TAG "GpsLocationProvider"

#define LOG_NDEBUG 0

#include "JNIHelp.h"
#include "jni.h"
#include "hardware/hardware.h"
#include "hardware/gps.h"
#include "hardware_legacy/power.h"
#include "utils/Log.h"
#include "utils/misc.h"
#include "android_runtime/AndroidRuntime.h"
#include "android_runtime/Log.h"

#include <arpa/inet.h>
#include <string.h>
#include <pthread.h>
#include <linux/in.h>
#include <linux/in6.h>

static jobject mCallbacksObj = NULL;

static jmethodID method_reportLocation;
static jmethodID method_reportStatus;
static jmethodID method_reportSvStatus;
static jmethodID method_reportAGpsStatus;
static jmethodID method_reportNmea;
static jmethodID method_setEngineCapabilities;
static jmethodID method_xtraDownloadRequest;
static jmethodID method_reportNiNotification;
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

static const GpsInterface* sGpsInterface = NULL;
static const GpsXtraInterface* sGpsXtraInterface = NULL;
static const AGpsInterface* sAGpsInterface = NULL;
static const GpsNiInterface* sGpsNiInterface = NULL;
static const GpsDebugInterface* sGpsDebugInterface = NULL;
static const AGpsRilInterface* sAGpsRilInterface = NULL;
static const GpsGeofencingInterface* sGpsGeofencingInterface = NULL;
static const GpsMeasurementInterface* sGpsMeasurementInterface = NULL;
static const GpsNavigationMessageInterface* sGpsNavigationMessageInterface = NULL;
static const GnssConfigurationInterface* sGnssConfigurationInterface = NULL;

// temporary storage for GPS callbacks
static GpsSvStatus  sGpsSvStatus;
static const char* sNmeaString;
static int sNmeaStringLength;

#define WAKE_LOCK_NAME  "GPS"

namespace android {

static void checkAndClearExceptionFromCallback(JNIEnv* env, const char* methodName) {
    if (env->ExceptionCheck()) {
        ALOGE("An exception was thrown by callback '%s'.", methodName);
        LOGE_EX(env);
        env->ExceptionClear();
    }
}

static void location_callback(GpsLocation* location)
{
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->CallVoidMethod(mCallbacksObj, method_reportLocation, location->flags,
            (jdouble)location->latitude, (jdouble)location->longitude,
            (jdouble)location->altitude,
            (jfloat)location->speed, (jfloat)location->bearing,
            (jfloat)location->accuracy, (jlong)location->timestamp);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

static void status_callback(GpsStatus* status)
{
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->CallVoidMethod(mCallbacksObj, method_reportStatus, status->status);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

static void sv_status_callback(GpsSvStatus* sv_status)
{
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    memcpy(&sGpsSvStatus, sv_status, sizeof(sGpsSvStatus));
    env->CallVoidMethod(mCallbacksObj, method_reportSvStatus);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

static void nmea_callback(GpsUtcTime timestamp, const char* nmea, int length)
{
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    // The Java code will call back to read these values
    // We do this to avoid creating unnecessary String objects
    sNmeaString = nmea;
    sNmeaStringLength = length;
    env->CallVoidMethod(mCallbacksObj, method_reportNmea, timestamp);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

static void set_capabilities_callback(uint32_t capabilities)
{
    ALOGD("set_capabilities_callback: %du\n", capabilities);
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->CallVoidMethod(mCallbacksObj, method_setEngineCapabilities, capabilities);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

static void acquire_wakelock_callback()
{
    acquire_wake_lock(PARTIAL_WAKE_LOCK, WAKE_LOCK_NAME);
}

static void release_wakelock_callback()
{
    release_wake_lock(WAKE_LOCK_NAME);
}

static void request_utc_time_callback()
{
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->CallVoidMethod(mCallbacksObj, method_requestUtcTime);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

static pthread_t create_thread_callback(const char* name, void (*start)(void *), void* arg)
{
    return (pthread_t)AndroidRuntime::createJavaThread(name, start, arg);
}

GpsCallbacks sGpsCallbacks = {
    sizeof(GpsCallbacks),
    location_callback,
    status_callback,
    sv_status_callback,
    nmea_callback,
    set_capabilities_callback,
    acquire_wakelock_callback,
    release_wakelock_callback,
    create_thread_callback,
    request_utc_time_callback,
};

static void xtra_download_request_callback()
{
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->CallVoidMethod(mCallbacksObj, method_xtraDownloadRequest);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

GpsXtraCallbacks sGpsXtraCallbacks = {
    xtra_download_request_callback,
    create_thread_callback,
};

static jbyteArray convert_to_ipv4(uint32_t ip, bool net_order)
{
    if (INADDR_NONE == ip) {
        return NULL;
    }

    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jbyteArray byteArray = env->NewByteArray(4);
    if (byteArray == NULL) {
        ALOGE("Unable to allocate byte array for IPv4 address");
        return NULL;
    }

    jbyte ipv4[4];
    if (net_order) {
        ALOGV("Converting IPv4 address(net_order) %x", ip);
        memcpy(ipv4, &ip, sizeof(ipv4));
    } else {
        ALOGV("Converting IPv4 address(host_order) %x", ip);
        //endianess transparent conversion from int to char[]
        ipv4[0] = (jbyte) (ip & 0xFF);
        ipv4[1] = (jbyte)((ip>>8) & 0xFF);
        ipv4[2] = (jbyte)((ip>>16) & 0xFF);
        ipv4[3] = (jbyte) (ip>>24);
    }

    env->SetByteArrayRegion(byteArray, 0, 4, (const jbyte*) ipv4);
    return byteArray;
}

static void agps_status_callback(AGpsStatus* agps_status)
{
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jbyteArray byteArray = NULL;
    bool isSupported = false;

    size_t status_size = agps_status->size;
    if (status_size == sizeof(AGpsStatus_v3)) {
      ALOGV("AGpsStatus is V3: %d", status_size);
      switch (agps_status->addr.ss_family)
      {
      case AF_INET:
          {
            struct sockaddr_in *in = (struct sockaddr_in*)&(agps_status->addr);
            uint32_t *pAddr = (uint32_t*)&(in->sin_addr);
            byteArray = convert_to_ipv4(*pAddr, true /* net_order */);
            if (byteArray != NULL) {
                isSupported = true;
            }
            IF_ALOGD() {
                // log the IP for reference in case there is a bogus value pushed by HAL
                char str[INET_ADDRSTRLEN];
                inet_ntop(AF_INET, &(in->sin_addr), str, INET_ADDRSTRLEN);
                ALOGD("AGPS IP is v4: %s", str);
            }
          }
          break;
      case AF_INET6:
          {
            struct sockaddr_in6 *in6 = (struct sockaddr_in6*)&(agps_status->addr);
            byteArray = env->NewByteArray(16);
            if (byteArray != NULL) {
                env->SetByteArrayRegion(byteArray, 0, 16, (const jbyte *)&(in6->sin6_addr));
                isSupported = true;
            } else {
                ALOGE("Unable to allocate byte array for IPv6 address.");
            }
            IF_ALOGD() {
                // log the IP for reference in case there is a bogus value pushed by HAL
                char str[INET6_ADDRSTRLEN];
                inet_ntop(AF_INET6, &(in6->sin6_addr), str, INET6_ADDRSTRLEN);
                ALOGD("AGPS IP is v6: %s", str);
            }
          }
          break;
      default:
          ALOGE("Invalid ss_family found: %d", agps_status->addr.ss_family);
          break;
      }
    } else if (status_size >= sizeof(AGpsStatus_v2)) {
      ALOGV("AGpsStatus is V2+: %d", status_size);
      // for back-compatibility reasons we check in v2 that the data structure size is greater or
      // equal to the declared size in gps.h
      uint32_t ipaddr = agps_status->ipaddr;
      ALOGV("AGPS IP is v4: %x", ipaddr);
      byteArray = convert_to_ipv4(ipaddr, false /* net_order */);
      if (ipaddr == INADDR_NONE || byteArray != NULL) {
          isSupported = true;
      }
    } else if (status_size >= sizeof(AGpsStatus_v1)) {
        ALOGV("AGpsStatus is V1+: %d", status_size);
        // because we have to check for >= with regards to v2, we also need to relax the check here
        // and only make sure that the size is at least what we expect
        isSupported = true;
    } else {
        ALOGE("Invalid size of AGpsStatus found: %d.", status_size);
    }

    if (isSupported) {
        jsize byteArrayLength = byteArray != NULL ? env->GetArrayLength(byteArray) : 0;
        ALOGV("Passing AGPS IP addr: size %d", byteArrayLength);
        env->CallVoidMethod(mCallbacksObj, method_reportAGpsStatus, agps_status->type,
                            agps_status->status, byteArray);

        checkAndClearExceptionFromCallback(env, __FUNCTION__);
    } else {
        ALOGD("Skipping calling method_reportAGpsStatus.");
    }

    if (byteArray) {
        env->DeleteLocalRef(byteArray);
    }
}

AGpsCallbacks sAGpsCallbacks = {
    agps_status_callback,
    create_thread_callback,
};

static void gps_ni_notify_callback(GpsNiNotification *notification)
{
    ALOGD("gps_ni_notify_callback\n");
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jstring requestor_id = env->NewStringUTF(notification->requestor_id);
    jstring text = env->NewStringUTF(notification->text);
    jstring extras = env->NewStringUTF(notification->extras);

    if (requestor_id && text && extras) {
        env->CallVoidMethod(mCallbacksObj, method_reportNiNotification,
            notification->notification_id, notification->ni_type,
            notification->notify_flags, notification->timeout,
            notification->default_response, requestor_id, text,
            notification->requestor_id_encoding,
            notification->text_encoding, extras);
    } else {
        ALOGE("out of memory in gps_ni_notify_callback\n");
    }

    if (requestor_id)
        env->DeleteLocalRef(requestor_id);
    if (text)
        env->DeleteLocalRef(text);
    if (extras)
        env->DeleteLocalRef(extras);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

GpsNiCallbacks sGpsNiCallbacks = {
    gps_ni_notify_callback,
    create_thread_callback,
};

static void agps_request_set_id(uint32_t flags)
{
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->CallVoidMethod(mCallbacksObj, method_requestSetID, flags);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

static void agps_request_ref_location(uint32_t flags)
{
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->CallVoidMethod(mCallbacksObj, method_requestRefLocation, flags);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

AGpsRilCallbacks sAGpsRilCallbacks = {
    agps_request_set_id,
    agps_request_ref_location,
    create_thread_callback,
};

static void gps_geofence_transition_callback(int32_t geofence_id,  GpsLocation* location,
        int32_t transition, GpsUtcTime timestamp)
{
    JNIEnv* env = AndroidRuntime::getJNIEnv();

    env->CallVoidMethod(mCallbacksObj, method_reportGeofenceTransition, geofence_id,
            location->flags, (jdouble)location->latitude, (jdouble)location->longitude,
            (jdouble)location->altitude,
            (jfloat)location->speed, (jfloat)location->bearing,
            (jfloat)location->accuracy, (jlong)location->timestamp,
            transition, timestamp);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
};

static void gps_geofence_status_callback(int32_t status, GpsLocation* location)
{
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jint flags = 0;
    jdouble latitude = 0;
    jdouble longitude = 0;
    jdouble altitude = 0;
    jfloat speed = 0;
    jfloat bearing = 0;
    jfloat accuracy = 0;
    jlong timestamp = 0;
    if (location != NULL) {
        flags = location->flags;
        latitude = location->latitude;
        longitude = location->longitude;
        altitude = location->altitude;
        speed = location->speed;
        bearing = location->bearing;
        accuracy = location->accuracy;
        timestamp = location->timestamp;
    }

    env->CallVoidMethod(mCallbacksObj, method_reportGeofenceStatus, status,
            flags, latitude, longitude, altitude, speed, bearing, accuracy, timestamp);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
};

static void gps_geofence_add_callback(int32_t geofence_id, int32_t status)
{
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    if (status != GPS_GEOFENCE_OPERATION_SUCCESS) {
        ALOGE("Error in geofence_add_callback: %d\n", status);
    }
    env->CallVoidMethod(mCallbacksObj, method_reportGeofenceAddStatus, geofence_id, status);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
};

static void gps_geofence_remove_callback(int32_t geofence_id, int32_t status)
{
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    if (status != GPS_GEOFENCE_OPERATION_SUCCESS) {
        ALOGE("Error in geofence_remove_callback: %d\n", status);
    }
    env->CallVoidMethod(mCallbacksObj, method_reportGeofenceRemoveStatus, geofence_id, status);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
};

static void gps_geofence_resume_callback(int32_t geofence_id, int32_t status)
{
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    if (status != GPS_GEOFENCE_OPERATION_SUCCESS) {
        ALOGE("Error in geofence_resume_callback: %d\n", status);
    }
    env->CallVoidMethod(mCallbacksObj, method_reportGeofenceResumeStatus, geofence_id, status);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
};

static void gps_geofence_pause_callback(int32_t geofence_id, int32_t status)
{
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    if (status != GPS_GEOFENCE_OPERATION_SUCCESS) {
        ALOGE("Error in geofence_pause_callback: %d\n", status);
    }
    env->CallVoidMethod(mCallbacksObj, method_reportGeofencePauseStatus, geofence_id, status);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
};

GpsGeofenceCallbacks sGpsGeofenceCallbacks = {
    gps_geofence_transition_callback,
    gps_geofence_status_callback,
    gps_geofence_add_callback,
    gps_geofence_remove_callback,
    gps_geofence_pause_callback,
    gps_geofence_resume_callback,
    create_thread_callback,
};

static void android_location_GpsLocationProvider_class_init_native(JNIEnv* env, jclass clazz) {
    int err;
    hw_module_t* module;

    method_reportLocation = env->GetMethodID(clazz, "reportLocation", "(IDDDFFFJ)V");
    method_reportStatus = env->GetMethodID(clazz, "reportStatus", "(I)V");
    method_reportSvStatus = env->GetMethodID(clazz, "reportSvStatus", "()V");
    method_reportAGpsStatus = env->GetMethodID(clazz, "reportAGpsStatus", "(II[B)V");
    method_reportNmea = env->GetMethodID(clazz, "reportNmea", "(J)V");
    method_setEngineCapabilities = env->GetMethodID(clazz, "setEngineCapabilities", "(I)V");
    method_xtraDownloadRequest = env->GetMethodID(clazz, "xtraDownloadRequest", "()V");
    method_reportNiNotification = env->GetMethodID(clazz, "reportNiNotification",
            "(IIIIILjava/lang/String;Ljava/lang/String;IILjava/lang/String;)V");
    method_requestRefLocation = env->GetMethodID(clazz,"requestRefLocation","(I)V");
    method_requestSetID = env->GetMethodID(clazz,"requestSetID","(I)V");
    method_requestUtcTime = env->GetMethodID(clazz,"requestUtcTime","()V");
    method_reportGeofenceTransition = env->GetMethodID(clazz,"reportGeofenceTransition",
            "(IIDDDFFFJIJ)V");
    method_reportGeofenceStatus = env->GetMethodID(clazz,"reportGeofenceStatus",
            "(IIDDDFFFJ)V");
    method_reportGeofenceAddStatus = env->GetMethodID(clazz,"reportGeofenceAddStatus",
            "(II)V");
    method_reportGeofenceRemoveStatus = env->GetMethodID(clazz,"reportGeofenceRemoveStatus",
            "(II)V");
    method_reportGeofenceResumeStatus = env->GetMethodID(clazz,"reportGeofenceResumeStatus",
            "(II)V");
    method_reportGeofencePauseStatus = env->GetMethodID(clazz,"reportGeofencePauseStatus",
            "(II)V");
    method_reportMeasurementData = env->GetMethodID(
            clazz,
            "reportMeasurementData",
            "(Landroid/location/GpsMeasurementsEvent;)V");
    method_reportNavigationMessages = env->GetMethodID(
            clazz,
            "reportNavigationMessage",
            "(Landroid/location/GpsNavigationMessageEvent;)V");

    err = hw_get_module(GPS_HARDWARE_MODULE_ID, (hw_module_t const**)&module);
    if (err == 0) {
        hw_device_t* device;
        err = module->methods->open(module, GPS_HARDWARE_MODULE_ID, &device);
        if (err == 0) {
            gps_device_t* gps_device = (gps_device_t *)device;
            sGpsInterface = gps_device->get_gps_interface(gps_device);
        }
    }
    if (sGpsInterface) {
        sGpsXtraInterface =
            (const GpsXtraInterface*)sGpsInterface->get_extension(GPS_XTRA_INTERFACE);
        sAGpsInterface =
            (const AGpsInterface*)sGpsInterface->get_extension(AGPS_INTERFACE);
        sGpsNiInterface =
            (const GpsNiInterface*)sGpsInterface->get_extension(GPS_NI_INTERFACE);
        sGpsDebugInterface =
            (const GpsDebugInterface*)sGpsInterface->get_extension(GPS_DEBUG_INTERFACE);
        sAGpsRilInterface =
            (const AGpsRilInterface*)sGpsInterface->get_extension(AGPS_RIL_INTERFACE);
        sGpsGeofencingInterface =
            (const GpsGeofencingInterface*)sGpsInterface->get_extension(GPS_GEOFENCING_INTERFACE);
        sGpsMeasurementInterface =
            (const GpsMeasurementInterface*)sGpsInterface->get_extension(GPS_MEASUREMENT_INTERFACE);
        sGpsNavigationMessageInterface =
            (const GpsNavigationMessageInterface*)sGpsInterface->get_extension(
                    GPS_NAVIGATION_MESSAGE_INTERFACE);
        sGnssConfigurationInterface =
            (const GnssConfigurationInterface*)sGpsInterface->get_extension(
                    GNSS_CONFIGURATION_INTERFACE);
    }
}

static jboolean android_location_GpsLocationProvider_is_supported(JNIEnv* env, jclass clazz) {
    if (sGpsInterface != NULL) {
        return JNI_TRUE;
    } else {
        return JNI_FALSE;
    }
}

static jboolean android_location_GpsLocationProvider_init(JNIEnv* env, jobject obj)
{
    // this must be set before calling into the HAL library
    if (!mCallbacksObj)
        mCallbacksObj = env->NewGlobalRef(obj);

    // fail if the main interface fails to initialize
    if (!sGpsInterface || sGpsInterface->init(&sGpsCallbacks) != 0)
        return JNI_FALSE;

    // if XTRA initialization fails we will disable it by sGpsXtraInterface to NULL,
    // but continue to allow the rest of the GPS interface to work.
    if (sGpsXtraInterface && sGpsXtraInterface->init(&sGpsXtraCallbacks) != 0)
        sGpsXtraInterface = NULL;
    if (sAGpsInterface)
        sAGpsInterface->init(&sAGpsCallbacks);
    if (sGpsNiInterface)
        sGpsNiInterface->init(&sGpsNiCallbacks);
    if (sAGpsRilInterface)
        sAGpsRilInterface->init(&sAGpsRilCallbacks);
    if (sGpsGeofencingInterface)
        sGpsGeofencingInterface->init(&sGpsGeofenceCallbacks);

    return JNI_TRUE;
}

static void android_location_GpsLocationProvider_cleanup(JNIEnv* env, jobject obj)
{
    if (sGpsInterface)
        sGpsInterface->cleanup();
}

static jboolean android_location_GpsLocationProvider_set_position_mode(JNIEnv* env, jobject obj,
        jint mode, jint recurrence, jint min_interval, jint preferred_accuracy, jint preferred_time)
{
    if (sGpsInterface) {
        if (sGpsInterface->set_position_mode(mode, recurrence, min_interval, preferred_accuracy,
                preferred_time) == 0) {
            return JNI_TRUE;
        } else {
            return JNI_FALSE;
        }
    }
    else
        return JNI_FALSE;
}

static jboolean android_location_GpsLocationProvider_start(JNIEnv* env, jobject obj)
{
    if (sGpsInterface) {
        if (sGpsInterface->start() == 0) {
            return JNI_TRUE;
        } else {
            return JNI_FALSE;
        }
    }
    else
        return JNI_FALSE;
}

static jboolean android_location_GpsLocationProvider_stop(JNIEnv* env, jobject obj)
{
    if (sGpsInterface) {
        if (sGpsInterface->stop() == 0) {
            return JNI_TRUE;
        } else {
            return JNI_FALSE;
        }
    }
    else
        return JNI_FALSE;
}

static void android_location_GpsLocationProvider_delete_aiding_data(JNIEnv* env, jobject obj, jint flags)
{
    if (sGpsInterface)
        sGpsInterface->delete_aiding_data(flags);
}

static jint android_location_GpsLocationProvider_read_sv_status(JNIEnv* env, jobject obj,
        jintArray prnArray, jfloatArray snrArray, jfloatArray elevArray, jfloatArray azumArray,
        jintArray maskArray)
{
    // this should only be called from within a call to reportSvStatus

    jint* prns = env->GetIntArrayElements(prnArray, 0);
    jfloat* snrs = env->GetFloatArrayElements(snrArray, 0);
    jfloat* elev = env->GetFloatArrayElements(elevArray, 0);
    jfloat* azim = env->GetFloatArrayElements(azumArray, 0);
    jint* mask = env->GetIntArrayElements(maskArray, 0);

    int num_svs = sGpsSvStatus.num_svs;
    for (int i = 0; i < num_svs; i++) {
        prns[i] = sGpsSvStatus.sv_list[i].prn;
        snrs[i] = sGpsSvStatus.sv_list[i].snr;
        elev[i] = sGpsSvStatus.sv_list[i].elevation;
        azim[i] = sGpsSvStatus.sv_list[i].azimuth;
    }
    mask[0] = sGpsSvStatus.ephemeris_mask;
    mask[1] = sGpsSvStatus.almanac_mask;
    mask[2] = sGpsSvStatus.used_in_fix_mask;

    env->ReleaseIntArrayElements(prnArray, prns, 0);
    env->ReleaseFloatArrayElements(snrArray, snrs, 0);
    env->ReleaseFloatArrayElements(elevArray, elev, 0);
    env->ReleaseFloatArrayElements(azumArray, azim, 0);
    env->ReleaseIntArrayElements(maskArray, mask, 0);
    return (jint) num_svs;
}

static void android_location_GpsLocationProvider_agps_set_reference_location_cellid(JNIEnv* env,
        jobject obj, jint type, jint mcc, jint mnc, jint lac, jint cid)
{
    AGpsRefLocation location;

    if (!sAGpsRilInterface) {
        ALOGE("no AGPS RIL interface in agps_set_reference_location_cellid");
        return;
    }

    switch(type) {
        case AGPS_REF_LOCATION_TYPE_GSM_CELLID:
        case AGPS_REF_LOCATION_TYPE_UMTS_CELLID:
            location.type = type;
            location.u.cellID.mcc = mcc;
            location.u.cellID.mnc = mnc;
            location.u.cellID.lac = lac;
            location.u.cellID.cid = cid;
            break;
        default:
            ALOGE("Neither a GSM nor a UMTS cellid (%s:%d).",__FUNCTION__,__LINE__);
            return;
            break;
    }
    sAGpsRilInterface->set_ref_location(&location, sizeof(location));
}

static void android_location_GpsLocationProvider_agps_send_ni_message(JNIEnv* env,
        jobject obj, jbyteArray ni_msg, jint size)
{
    size_t sz;

    if (!sAGpsRilInterface) {
        ALOGE("no AGPS RIL interface in send_ni_message");
        return;
    }
    if (size < 0)
        return;
    sz = (size_t)size;
    jbyte* b = env->GetByteArrayElements(ni_msg, 0);
    sAGpsRilInterface->ni_message((uint8_t *)b,sz);
    env->ReleaseByteArrayElements(ni_msg,b,0);
}

static void android_location_GpsLocationProvider_agps_set_id(JNIEnv *env,
        jobject obj, jint type, jstring  setid_string)
{
    if (!sAGpsRilInterface) {
        ALOGE("no AGPS RIL interface in agps_set_id");
        return;
    }

    const char *setid = env->GetStringUTFChars(setid_string, NULL);
    sAGpsRilInterface->set_set_id(type, setid);
    env->ReleaseStringUTFChars(setid_string, setid);
}

static jint android_location_GpsLocationProvider_read_nmea(JNIEnv* env, jobject obj,
                                            jbyteArray nmeaArray, jint buffer_size)
{
    // this should only be called from within a call to reportNmea
    jbyte* nmea = (jbyte *)env->GetPrimitiveArrayCritical(nmeaArray, 0);
    int length = sNmeaStringLength;
    if (length > buffer_size)
        length = buffer_size;
    memcpy(nmea, sNmeaString, length);
    env->ReleasePrimitiveArrayCritical(nmeaArray, nmea, JNI_ABORT);
    return (jint) length;
}

static void android_location_GpsLocationProvider_inject_time(JNIEnv* env, jobject obj,
        jlong time, jlong timeReference, jint uncertainty)
{
    if (sGpsInterface)
        sGpsInterface->inject_time(time, timeReference, uncertainty);
}

static void android_location_GpsLocationProvider_inject_location(JNIEnv* env, jobject obj,
        jdouble latitude, jdouble longitude, jfloat accuracy)
{
    if (sGpsInterface)
        sGpsInterface->inject_location(latitude, longitude, accuracy);
}

static jboolean android_location_GpsLocationProvider_supports_xtra(JNIEnv* env, jobject obj)
{
    if (sGpsXtraInterface != NULL) {
        return JNI_TRUE;
    } else {
        return JNI_FALSE;
    }
}

static void android_location_GpsLocationProvider_inject_xtra_data(JNIEnv* env, jobject obj,
        jbyteArray data, jint length)
{
    if (!sGpsXtraInterface) {
        ALOGE("no XTRA interface in inject_xtra_data");
        return;
    }

    jbyte* bytes = (jbyte *)env->GetPrimitiveArrayCritical(data, 0);
    sGpsXtraInterface->inject_xtra_data((char *)bytes, length);
    env->ReleasePrimitiveArrayCritical(data, bytes, JNI_ABORT);
}

static void android_location_GpsLocationProvider_agps_data_conn_open(
        JNIEnv* env, jobject obj, jstring apn, jint apnIpType)
{
    if (!sAGpsInterface) {
        ALOGE("no AGPS interface in agps_data_conn_open");
        return;
    }
    if (apn == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return;
    }

    const char *apnStr = env->GetStringUTFChars(apn, NULL);

    size_t interface_size = sAGpsInterface->size;
    if (interface_size == sizeof(AGpsInterface_v2)) {
        sAGpsInterface->data_conn_open_with_apn_ip_type(apnStr, apnIpType);
    } else if (interface_size == sizeof(AGpsInterface_v1)) {
        sAGpsInterface->data_conn_open(apnStr);
    } else {
        ALOGE("Invalid size of AGpsInterface found: %d.", interface_size);
    }

    env->ReleaseStringUTFChars(apn, apnStr);
}

static void android_location_GpsLocationProvider_agps_data_conn_closed(JNIEnv* env, jobject obj)
{
    if (!sAGpsInterface) {
        ALOGE("no AGPS interface in agps_data_conn_closed");
        return;
    }
    sAGpsInterface->data_conn_closed();
}

static void android_location_GpsLocationProvider_agps_data_conn_failed(JNIEnv* env, jobject obj)
{
    if (!sAGpsInterface) {
        ALOGE("no AGPS interface in agps_data_conn_failed");
        return;
    }
    sAGpsInterface->data_conn_failed();
}

static void android_location_GpsLocationProvider_set_agps_server(JNIEnv* env, jobject obj,
        jint type, jstring hostname, jint port)
{
    if (!sAGpsInterface) {
        ALOGE("no AGPS interface in set_agps_server");
        return;
    }
    const char *c_hostname = env->GetStringUTFChars(hostname, NULL);
    sAGpsInterface->set_server(type, c_hostname, port);
    env->ReleaseStringUTFChars(hostname, c_hostname);
}

static void android_location_GpsLocationProvider_send_ni_response(JNIEnv* env, jobject obj,
      jint notifId, jint response)
{
    if (!sGpsNiInterface) {
        ALOGE("no NI interface in send_ni_response");
        return;
    }

    sGpsNiInterface->respond(notifId, response);
}

static jstring android_location_GpsLocationProvider_get_internal_state(JNIEnv* env, jobject obj)
{
    jstring result = NULL;
    if (sGpsDebugInterface) {
        const size_t maxLength = 2047;
        char buffer[maxLength+1];
        size_t length = sGpsDebugInterface->get_internal_state(buffer, maxLength);
        if (length > maxLength) length = maxLength;
        buffer[length] = 0;
        result = env->NewStringUTF(buffer);
    }
    return result;
}

static void android_location_GpsLocationProvider_update_network_state(JNIEnv* env, jobject obj,
        jboolean connected, jint type, jboolean roaming, jboolean available, jstring extraInfo, jstring apn)
{

    if (sAGpsRilInterface && sAGpsRilInterface->update_network_state) {
        if (extraInfo) {
            const char *extraInfoStr = env->GetStringUTFChars(extraInfo, NULL);
            sAGpsRilInterface->update_network_state(connected, type, roaming, extraInfoStr);
            env->ReleaseStringUTFChars(extraInfo, extraInfoStr);
        } else {
            sAGpsRilInterface->update_network_state(connected, type, roaming, NULL);
        }

        // update_network_availability callback was not included in original AGpsRilInterface
        if (sAGpsRilInterface->size >= sizeof(AGpsRilInterface)
                && sAGpsRilInterface->update_network_availability) {
            const char *c_apn = env->GetStringUTFChars(apn, NULL);
            sAGpsRilInterface->update_network_availability(available, c_apn);
            env->ReleaseStringUTFChars(apn, c_apn);
        }
    }
}

static jboolean android_location_GpsLocationProvider_is_geofence_supported(JNIEnv* env,
          jobject obj) {
    if (sGpsGeofencingInterface != NULL) {
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

static jboolean android_location_GpsLocationProvider_add_geofence(JNIEnv* env, jobject obj,
        jint geofence_id, jdouble latitude, jdouble longitude, jdouble radius,
        jint last_transition, jint monitor_transition, jint notification_responsiveness,
        jint unknown_timer) {
    if (sGpsGeofencingInterface != NULL) {
        sGpsGeofencingInterface->add_geofence_area(geofence_id, latitude, longitude,
                radius, last_transition, monitor_transition, notification_responsiveness,
                unknown_timer);
        return JNI_TRUE;
    } else {
        ALOGE("Geofence interface not available");
    }
    return JNI_FALSE;
}

static jboolean android_location_GpsLocationProvider_remove_geofence(JNIEnv* env, jobject obj,
        jint geofence_id) {
    if (sGpsGeofencingInterface != NULL) {
        sGpsGeofencingInterface->remove_geofence_area(geofence_id);
        return JNI_TRUE;
    } else {
        ALOGE("Geofence interface not available");
    }
    return JNI_FALSE;
}

static jboolean android_location_GpsLocationProvider_pause_geofence(JNIEnv* env, jobject obj,
        jint geofence_id) {
    if (sGpsGeofencingInterface != NULL) {
        sGpsGeofencingInterface->pause_geofence(geofence_id);
        return JNI_TRUE;
    } else {
        ALOGE("Geofence interface not available");
    }
    return JNI_FALSE;
}

static jboolean android_location_GpsLocationProvider_resume_geofence(JNIEnv* env, jobject obj,
        jint geofence_id, jint monitor_transition) {
    if (sGpsGeofencingInterface != NULL) {
        sGpsGeofencingInterface->resume_geofence(geofence_id, monitor_transition);
        return JNI_TRUE;
    } else {
        ALOGE("Geofence interface not available");
    }
    return JNI_FALSE;
}

static jobject translate_gps_clock(JNIEnv* env, GpsClock* clock) {
    const char* doubleSignature = "(D)V";
    const char* longSignature = "(J)V";

    jclass gpsClockClass = env->FindClass("android/location/GpsClock");
    jmethodID gpsClockCtor = env->GetMethodID(gpsClockClass, "<init>", "()V");

    jobject gpsClockObject = env->NewObject(gpsClockClass, gpsClockCtor);
    GpsClockFlags flags = clock->flags;

    if (flags & GPS_CLOCK_HAS_LEAP_SECOND) {
        jmethodID setterMethod = env->GetMethodID(gpsClockClass, "setLeapSecond", "(S)V");
        env->CallVoidMethod(gpsClockObject, setterMethod, clock->leap_second);
   }

   jmethodID typeSetterMethod = env->GetMethodID(gpsClockClass, "setType", "(B)V");
   env->CallVoidMethod(gpsClockObject, typeSetterMethod, clock->type);

    jmethodID setterMethod = env->GetMethodID(gpsClockClass, "setTimeInNs", longSignature);
    env->CallVoidMethod(gpsClockObject, setterMethod, clock->time_ns);

    if (flags & GPS_CLOCK_HAS_TIME_UNCERTAINTY) {
        jmethodID setterMethod =
                env->GetMethodID(gpsClockClass, "setTimeUncertaintyInNs", doubleSignature);
        env->CallVoidMethod(gpsClockObject, setterMethod, clock->time_uncertainty_ns);
    }

    if (flags & GPS_CLOCK_HAS_FULL_BIAS) {
        jmethodID setterMethod = env->GetMethodID(gpsClockClass, "setFullBiasInNs", longSignature);
        env->CallVoidMethod(gpsClockObject, setterMethod, clock->full_bias_ns);
    }

    if (flags & GPS_CLOCK_HAS_BIAS) {
        jmethodID setterMethod = env->GetMethodID(gpsClockClass, "setBiasInNs", doubleSignature);
        env->CallVoidMethod(gpsClockObject, setterMethod, clock->bias_ns);
    }

    if (flags & GPS_CLOCK_HAS_BIAS_UNCERTAINTY) {
        jmethodID setterMethod =
                env->GetMethodID(gpsClockClass, "setBiasUncertaintyInNs", doubleSignature);
        env->CallVoidMethod(gpsClockObject, setterMethod, clock->bias_uncertainty_ns);
    }

    if (flags & GPS_CLOCK_HAS_DRIFT) {
        jmethodID setterMethod =
                env->GetMethodID(gpsClockClass, "setDriftInNsPerSec", doubleSignature);
        env->CallVoidMethod(gpsClockObject, setterMethod, clock->drift_nsps);
    }

    if (flags & GPS_CLOCK_HAS_DRIFT_UNCERTAINTY) {
        jmethodID setterMethod =
                env->GetMethodID(gpsClockClass, "setDriftUncertaintyInNsPerSec", doubleSignature);
        env->CallVoidMethod(gpsClockObject, setterMethod, clock->drift_uncertainty_nsps);
    }

    env->DeleteLocalRef(gpsClockClass);
    return gpsClockObject;
}

static jobject translate_gps_measurement(JNIEnv* env, GpsMeasurement* measurement) {
    const char* byteSignature = "(B)V";
    const char* shortSignature = "(S)V";
    const char* intSignature = "(I)V";
    const char* longSignature = "(J)V";
    const char* floatSignature = "(F)V";
    const char* doubleSignature = "(D)V";

    jclass gpsMeasurementClass = env->FindClass("android/location/GpsMeasurement");
    jmethodID gpsMeasurementCtor = env->GetMethodID(gpsMeasurementClass, "<init>", "()V");

    jobject gpsMeasurementObject = env->NewObject(gpsMeasurementClass, gpsMeasurementCtor);
    GpsMeasurementFlags flags = measurement->flags;

    jmethodID prnSetterMethod = env->GetMethodID(gpsMeasurementClass, "setPrn", byteSignature);
    env->CallVoidMethod(gpsMeasurementObject, prnSetterMethod, measurement->prn);

    jmethodID timeOffsetSetterMethod =
            env->GetMethodID(gpsMeasurementClass, "setTimeOffsetInNs", doubleSignature);
    env->CallVoidMethod(
            gpsMeasurementObject,
            timeOffsetSetterMethod,
            measurement->time_offset_ns);

    jmethodID stateSetterMethod = env->GetMethodID(gpsMeasurementClass, "setState", shortSignature);
    env->CallVoidMethod(gpsMeasurementObject, stateSetterMethod, measurement->state);

    jmethodID receivedGpsTowSetterMethod =
            env->GetMethodID(gpsMeasurementClass, "setReceivedGpsTowInNs", longSignature);
    env->CallVoidMethod(
            gpsMeasurementObject,
            receivedGpsTowSetterMethod,
            measurement->received_gps_tow_ns);

    jmethodID receivedGpsTowUncertaintySetterMethod = env->GetMethodID(
            gpsMeasurementClass,
            "setReceivedGpsTowUncertaintyInNs",
            longSignature);
    env->CallVoidMethod(
            gpsMeasurementObject,
            receivedGpsTowUncertaintySetterMethod,
            measurement->received_gps_tow_uncertainty_ns);

    jmethodID cn0SetterMethod =
            env->GetMethodID(gpsMeasurementClass, "setCn0InDbHz", doubleSignature);
    env->CallVoidMethod(gpsMeasurementObject, cn0SetterMethod, measurement->c_n0_dbhz);

    jmethodID pseudorangeRateSetterMethod = env->GetMethodID(
            gpsMeasurementClass,
            "setPseudorangeRateInMetersPerSec",
            doubleSignature);
    env->CallVoidMethod(
            gpsMeasurementObject,
            pseudorangeRateSetterMethod,
            measurement->pseudorange_rate_mps);

    jmethodID pseudorangeRateUncertaintySetterMethod = env->GetMethodID(
            gpsMeasurementClass,
            "setPseudorangeRateUncertaintyInMetersPerSec",
            doubleSignature);
    env->CallVoidMethod(
            gpsMeasurementObject,
            pseudorangeRateUncertaintySetterMethod,
            measurement->pseudorange_rate_uncertainty_mps);

    jmethodID accumulatedDeltaRangeStateSetterMethod =
            env->GetMethodID(gpsMeasurementClass, "setAccumulatedDeltaRangeState", shortSignature);
    env->CallVoidMethod(
            gpsMeasurementObject,
            accumulatedDeltaRangeStateSetterMethod,
            measurement->accumulated_delta_range_state);

    jmethodID accumulatedDeltaRangeSetterMethod = env->GetMethodID(
            gpsMeasurementClass,
            "setAccumulatedDeltaRangeInMeters",
            doubleSignature);
    env->CallVoidMethod(
            gpsMeasurementObject,
            accumulatedDeltaRangeSetterMethod,
            measurement->accumulated_delta_range_m);

    jmethodID accumulatedDeltaRangeUncertaintySetterMethod = env->GetMethodID(
            gpsMeasurementClass,
            "setAccumulatedDeltaRangeUncertaintyInMeters",
            doubleSignature);
    env->CallVoidMethod(
            gpsMeasurementObject,
            accumulatedDeltaRangeUncertaintySetterMethod,
            measurement->accumulated_delta_range_uncertainty_m);

    if (flags & GPS_MEASUREMENT_HAS_PSEUDORANGE) {
        jmethodID setterMethod =
                env->GetMethodID(gpsMeasurementClass, "setPseudorangeInMeters", doubleSignature);
        env->CallVoidMethod(gpsMeasurementObject, setterMethod, measurement->pseudorange_m);
    }

    if (flags & GPS_MEASUREMENT_HAS_PSEUDORANGE_UNCERTAINTY) {
        jmethodID setterMethod = env->GetMethodID(
                gpsMeasurementClass,
                "setPseudorangeUncertaintyInMeters",
                doubleSignature);
        env->CallVoidMethod(
                gpsMeasurementObject,
                setterMethod,
                measurement->pseudorange_uncertainty_m);
    }

    if (flags & GPS_MEASUREMENT_HAS_CODE_PHASE) {
        jmethodID setterMethod =
                env->GetMethodID(gpsMeasurementClass, "setCodePhaseInChips", doubleSignature);
        env->CallVoidMethod(gpsMeasurementObject, setterMethod, measurement->code_phase_chips);
    }

    if (flags & GPS_MEASUREMENT_HAS_CODE_PHASE_UNCERTAINTY) {
        jmethodID setterMethod = env->GetMethodID(
                gpsMeasurementClass,
                "setCodePhaseUncertaintyInChips",
                doubleSignature);
        env->CallVoidMethod(
                gpsMeasurementObject,
                setterMethod,
                measurement->code_phase_uncertainty_chips);
    }

    if (flags & GPS_MEASUREMENT_HAS_CARRIER_FREQUENCY) {
        jmethodID setterMethod =
                env->GetMethodID(gpsMeasurementClass, "setCarrierFrequencyInHz", floatSignature);
        env->CallVoidMethod(
                gpsMeasurementObject,
                setterMethod,
                measurement->carrier_frequency_hz);
    }

    if (flags & GPS_MEASUREMENT_HAS_CARRIER_CYCLES) {
        jmethodID setterMethod =
                env->GetMethodID(gpsMeasurementClass, "setCarrierCycles", longSignature);
        env->CallVoidMethod(gpsMeasurementObject, setterMethod, measurement->carrier_cycles);
    }

    if (flags & GPS_MEASUREMENT_HAS_CARRIER_PHASE) {
        jmethodID setterMethod =
                env->GetMethodID(gpsMeasurementClass, "setCarrierPhase", doubleSignature);
        env->CallVoidMethod(gpsMeasurementObject, setterMethod, measurement->carrier_phase);
    }

    if (flags & GPS_MEASUREMENT_HAS_CARRIER_PHASE_UNCERTAINTY) {
        jmethodID setterMethod = env->GetMethodID(
                gpsMeasurementClass,
                "setCarrierPhaseUncertainty",
                doubleSignature);
        env->CallVoidMethod(
                gpsMeasurementObject,
                setterMethod,
                measurement->carrier_phase_uncertainty);
    }

    jmethodID lossOfLockSetterMethod =
            env->GetMethodID(gpsMeasurementClass, "setLossOfLock", byteSignature);
    env->CallVoidMethod(gpsMeasurementObject, lossOfLockSetterMethod, measurement->loss_of_lock);

    if (flags & GPS_MEASUREMENT_HAS_BIT_NUMBER) {
        jmethodID setterMethod =
                env->GetMethodID(gpsMeasurementClass, "setBitNumber", intSignature);
        env->CallVoidMethod(gpsMeasurementObject, setterMethod, measurement->bit_number);
    }

    if (flags & GPS_MEASUREMENT_HAS_TIME_FROM_LAST_BIT) {
        jmethodID setterMethod =
                env->GetMethodID(gpsMeasurementClass, "setTimeFromLastBitInMs", shortSignature);
        env->CallVoidMethod(
                gpsMeasurementObject,
                setterMethod,
                measurement->time_from_last_bit_ms);
    }

    if (flags & GPS_MEASUREMENT_HAS_DOPPLER_SHIFT) {
        jmethodID setterMethod =
                env->GetMethodID(gpsMeasurementClass, "setDopplerShiftInHz", doubleSignature);
        env->CallVoidMethod(gpsMeasurementObject, setterMethod, measurement->doppler_shift_hz);
    }

    if (flags & GPS_MEASUREMENT_HAS_DOPPLER_SHIFT_UNCERTAINTY) {
        jmethodID setterMethod = env->GetMethodID(
                gpsMeasurementClass,
                "setDopplerShiftUncertaintyInHz",
                doubleSignature);
        env->CallVoidMethod(
                gpsMeasurementObject,
                setterMethod,
                measurement->doppler_shift_uncertainty_hz);
    }

    jmethodID multipathIndicatorSetterMethod =
            env->GetMethodID(gpsMeasurementClass, "setMultipathIndicator", byteSignature);
    env->CallVoidMethod(
            gpsMeasurementObject,
            multipathIndicatorSetterMethod,
            measurement->multipath_indicator);

    if (flags & GPS_MEASUREMENT_HAS_SNR) {
        jmethodID setterMethod =
                env->GetMethodID(gpsMeasurementClass, "setSnrInDb", doubleSignature);
        env->CallVoidMethod(gpsMeasurementObject, setterMethod, measurement->snr_db);
    }

    if (flags & GPS_MEASUREMENT_HAS_ELEVATION) {
        jmethodID setterMethod =
                env->GetMethodID(gpsMeasurementClass, "setElevationInDeg", doubleSignature);
        env->CallVoidMethod(gpsMeasurementObject, setterMethod, measurement->elevation_deg);
    }

    if (flags & GPS_MEASUREMENT_HAS_ELEVATION_UNCERTAINTY) {
        jmethodID setterMethod =
                env->GetMethodID(gpsMeasurementClass, "setElevationUncertaintyInDeg", doubleSignature);
        env->CallVoidMethod(
                gpsMeasurementObject,
                setterMethod,
                measurement->elevation_uncertainty_deg);
    }

    if (flags & GPS_MEASUREMENT_HAS_AZIMUTH) {
        jmethodID setterMethod =
                env->GetMethodID(gpsMeasurementClass, "setAzimuthInDeg", doubleSignature);
        env->CallVoidMethod(gpsMeasurementObject, setterMethod, measurement->azimuth_deg);
    }

    if (flags & GPS_MEASUREMENT_HAS_AZIMUTH_UNCERTAINTY) {
        jmethodID setterMethod = env->GetMethodID(
                gpsMeasurementClass,
                "setAzimuthUncertaintyInDeg",
                doubleSignature);
        env->CallVoidMethod(
                gpsMeasurementObject,
                setterMethod,
                measurement->azimuth_uncertainty_deg);
    }

    jmethodID usedInFixSetterMethod = env->GetMethodID(gpsMeasurementClass, "setUsedInFix", "(Z)V");
    env->CallVoidMethod(
            gpsMeasurementObject,
            usedInFixSetterMethod,
            (flags & GPS_MEASUREMENT_HAS_USED_IN_FIX) && measurement->used_in_fix);

    env->DeleteLocalRef(gpsMeasurementClass);
    return gpsMeasurementObject;
}

static jobjectArray translate_gps_measurements(JNIEnv* env, GpsData* data) {
    size_t measurementCount = data->measurement_count;
    if (measurementCount == 0) {
        return NULL;
    }

    jclass gpsMeasurementClass = env->FindClass("android/location/GpsMeasurement");
    jobjectArray gpsMeasurementArray = env->NewObjectArray(
            measurementCount,
            gpsMeasurementClass,
            NULL /* initialElement */);

    GpsMeasurement* gpsMeasurements = data->measurements;
    for (uint16_t i = 0; i < measurementCount; ++i) {
        jobject gpsMeasurement = translate_gps_measurement(env, &gpsMeasurements[i]);
        env->SetObjectArrayElement(gpsMeasurementArray, i, gpsMeasurement);
        env->DeleteLocalRef(gpsMeasurement);
    }

    env->DeleteLocalRef(gpsMeasurementClass);
    return gpsMeasurementArray;
}

static void measurement_callback(GpsData* data) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    if (data == NULL) {
        ALOGE("Invalid data provided to gps_measurement_callback");
        return;
    }

    if (data->size == sizeof(GpsData)) {
        jobject gpsClock = translate_gps_clock(env, &data->clock);
        jobjectArray measurementArray = translate_gps_measurements(env, data);

        jclass gpsMeasurementsEventClass = env->FindClass("android/location/GpsMeasurementsEvent");
        jmethodID gpsMeasurementsEventCtor = env->GetMethodID(
                gpsMeasurementsEventClass,
                "<init>",
                "(Landroid/location/GpsClock;[Landroid/location/GpsMeasurement;)V");

        jobject gpsMeasurementsEvent = env->NewObject(
                gpsMeasurementsEventClass,
                gpsMeasurementsEventCtor,
                gpsClock,
                measurementArray);

        env->CallVoidMethod(mCallbacksObj, method_reportMeasurementData, gpsMeasurementsEvent);
        checkAndClearExceptionFromCallback(env, __FUNCTION__);

        env->DeleteLocalRef(gpsClock);
        env->DeleteLocalRef(measurementArray);
        env->DeleteLocalRef(gpsMeasurementsEventClass);
        env->DeleteLocalRef(gpsMeasurementsEvent);
    } else {
        ALOGE("Invalid GpsData size found in gps_measurement_callback, size=%d", data->size);
    }
}

GpsMeasurementCallbacks sGpsMeasurementCallbacks = {
    sizeof(GpsMeasurementCallbacks),
    measurement_callback,
};

static jboolean android_location_GpsLocationProvider_is_measurement_supported(
        JNIEnv* env,
        jclass clazz) {
    if (sGpsMeasurementInterface != NULL) {
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

static jboolean android_location_GpsLocationProvider_start_measurement_collection(
        JNIEnv* env,
        jobject obj) {
    if (sGpsMeasurementInterface == NULL) {
        ALOGE("Measurement interface is not available.");
        return JNI_FALSE;
    }

    int result = sGpsMeasurementInterface->init(&sGpsMeasurementCallbacks);
    if (result != GPS_GEOFENCE_OPERATION_SUCCESS) {
        ALOGE("An error has been found on GpsMeasurementInterface::init, status=%d", result);
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

static jboolean android_location_GpsLocationProvider_stop_measurement_collection(
        JNIEnv* env,
        jobject obj) {
    if (sGpsMeasurementInterface == NULL) {
        ALOGE("Measurement interface not available");
        return JNI_FALSE;
    }

    sGpsMeasurementInterface->close();
    return JNI_TRUE;
}

static jobject translate_gps_navigation_message(JNIEnv* env, GpsNavigationMessage* message) {
    size_t dataLength = message->data_length;
    uint8_t* data = message->data;
    if (dataLength == 0 || data == NULL) {
        ALOGE("Invalid Navigation Message found: data=%p, length=%d", data, dataLength);
        return NULL;
    }

    jclass navigationMessageClass = env->FindClass("android/location/GpsNavigationMessage");
    jmethodID navigationMessageCtor = env->GetMethodID(navigationMessageClass, "<init>", "()V");
    jobject navigationMessageObject = env->NewObject(navigationMessageClass, navigationMessageCtor);

    jmethodID setTypeMethod = env->GetMethodID(navigationMessageClass, "setType", "(B)V");
    env->CallVoidMethod(navigationMessageObject, setTypeMethod, message->type);

    jmethodID setPrnMethod = env->GetMethodID(navigationMessageClass, "setPrn", "(B)V");
    env->CallVoidMethod(navigationMessageObject, setPrnMethod, message->prn);

    jmethodID setMessageIdMethod = env->GetMethodID(navigationMessageClass, "setMessageId", "(S)V");
    env->CallVoidMethod(navigationMessageObject, setMessageIdMethod, message->message_id);

    jmethodID setSubmessageIdMethod =
            env->GetMethodID(navigationMessageClass, "setSubmessageId", "(S)V");
    env->CallVoidMethod(navigationMessageObject, setSubmessageIdMethod, message->submessage_id);

    jbyteArray dataArray = env->NewByteArray(dataLength);
    env->SetByteArrayRegion(dataArray, 0, dataLength, (jbyte*) data);
    jmethodID setDataMethod = env->GetMethodID(navigationMessageClass, "setData", "([B)V");
    env->CallVoidMethod(navigationMessageObject, setDataMethod, dataArray);

    env->DeleteLocalRef(navigationMessageClass);
    env->DeleteLocalRef(dataArray);
    return navigationMessageObject;
}

static void navigation_message_callback(GpsNavigationMessage* message) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    if (message == NULL) {
        ALOGE("Invalid Navigation Message provided to callback");
        return;
    }

    if (message->size == sizeof(GpsNavigationMessage)) {
        jobject navigationMessage = translate_gps_navigation_message(env, message);

        jclass navigationMessageEventClass =
                env->FindClass("android/location/GpsNavigationMessageEvent");
        jmethodID navigationMessageEventCtor = env->GetMethodID(
                navigationMessageEventClass,
                "<init>",
                "(Landroid/location/GpsNavigationMessage;)V");
        jobject navigationMessageEvent = env->NewObject(
                navigationMessageEventClass,
                navigationMessageEventCtor,
                navigationMessage);

        env->CallVoidMethod(mCallbacksObj, method_reportNavigationMessages, navigationMessageEvent);
        checkAndClearExceptionFromCallback(env, __FUNCTION__);

        env->DeleteLocalRef(navigationMessage);
        env->DeleteLocalRef(navigationMessageEventClass);
        env->DeleteLocalRef(navigationMessageEvent);
    } else {
        ALOGE("Invalid GpsNavigationMessage size found: %d", message->size);
    }
}

GpsNavigationMessageCallbacks sGpsNavigationMessageCallbacks = {
    sizeof(GpsNavigationMessageCallbacks),
    navigation_message_callback,
};

static jboolean android_location_GpsLocationProvider_is_navigation_message_supported(
        JNIEnv* env,
        jclass clazz) {
    if(sGpsNavigationMessageInterface != NULL) {
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

static jboolean android_location_GpsLocationProvider_start_navigation_message_collection(
        JNIEnv* env,
        jobject obj) {
    if (sGpsNavigationMessageInterface == NULL) {
        ALOGE("Navigation Message interface is not available.");
        return JNI_FALSE;
    }

    int result = sGpsNavigationMessageInterface->init(&sGpsNavigationMessageCallbacks);
    if (result != GPS_NAVIGATION_MESSAGE_OPERATION_SUCCESS) {
        ALOGE("An error has been found in %s: %d", __FUNCTION__, result);
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

static jboolean android_location_GpsLocationProvider_stop_navigation_message_collection(
        JNIEnv* env,
        jobject obj) {
    if (sGpsNavigationMessageInterface == NULL) {
        ALOGE("Navigation Message interface is not available.");
        return JNI_FALSE;
    }

    sGpsNavigationMessageInterface->close();
    return JNI_TRUE;
}

static void android_location_GpsLocationProvider_configuration_update(JNIEnv* env, jobject obj,
        jstring config_content)
{
    if (!sGnssConfigurationInterface) {
        ALOGE("no GPS configuration interface in configuraiton_update");
        return;
    }
    const char *data = env->GetStringUTFChars(config_content, NULL);
    ALOGD("GPS configuration:\n %s", data);
    sGnssConfigurationInterface->configuration_update(
            data, env->GetStringUTFLength(config_content));
    env->ReleaseStringUTFChars(config_content, data);
}

static JNINativeMethod sMethods[] = {
     /* name, signature, funcPtr */
    {"class_init_native", "()V", (void *)android_location_GpsLocationProvider_class_init_native},
    {"native_is_supported", "()Z", (void*)android_location_GpsLocationProvider_is_supported},
    {"native_init", "()Z", (void*)android_location_GpsLocationProvider_init},
    {"native_cleanup", "()V", (void*)android_location_GpsLocationProvider_cleanup},
    {"native_set_position_mode",
            "(IIIII)Z",
            (void*)android_location_GpsLocationProvider_set_position_mode},
    {"native_start", "()Z", (void*)android_location_GpsLocationProvider_start},
    {"native_stop", "()Z", (void*)android_location_GpsLocationProvider_stop},
    {"native_delete_aiding_data",
            "(I)V",
            (void*)android_location_GpsLocationProvider_delete_aiding_data},
    {"native_read_sv_status",
            "([I[F[F[F[I)I",
            (void*)android_location_GpsLocationProvider_read_sv_status},
    {"native_read_nmea", "([BI)I", (void*)android_location_GpsLocationProvider_read_nmea},
    {"native_inject_time", "(JJI)V", (void*)android_location_GpsLocationProvider_inject_time},
    {"native_inject_location",
            "(DDF)V",
            (void*)android_location_GpsLocationProvider_inject_location},
    {"native_supports_xtra", "()Z", (void*)android_location_GpsLocationProvider_supports_xtra},
    {"native_inject_xtra_data",
            "([BI)V",
            (void*)android_location_GpsLocationProvider_inject_xtra_data},
    {"native_agps_data_conn_open",
            "(Ljava/lang/String;I)V",
            (void*)android_location_GpsLocationProvider_agps_data_conn_open},
    {"native_agps_data_conn_closed",
            "()V",
            (void*)android_location_GpsLocationProvider_agps_data_conn_closed},
    {"native_agps_data_conn_failed",
            "()V",
            (void*)android_location_GpsLocationProvider_agps_data_conn_failed},
    {"native_agps_set_id",
            "(ILjava/lang/String;)V",
            (void*)android_location_GpsLocationProvider_agps_set_id},
    {"native_agps_set_ref_location_cellid",
            "(IIIII)V",
            (void*)android_location_GpsLocationProvider_agps_set_reference_location_cellid},
    {"native_set_agps_server",
            "(ILjava/lang/String;I)V",
            (void*)android_location_GpsLocationProvider_set_agps_server},
    {"native_send_ni_response",
            "(II)V",
            (void*)android_location_GpsLocationProvider_send_ni_response},
    {"native_agps_ni_message",
            "([BI)V",
            (void *)android_location_GpsLocationProvider_agps_send_ni_message},
    {"native_get_internal_state",
            "()Ljava/lang/String;",
            (void*)android_location_GpsLocationProvider_get_internal_state},
    {"native_update_network_state",
            "(ZIZZLjava/lang/String;Ljava/lang/String;)V",
            (void*)android_location_GpsLocationProvider_update_network_state },
    {"native_is_geofence_supported",
            "()Z",
            (void*) android_location_GpsLocationProvider_is_geofence_supported},
    {"native_add_geofence",
            "(IDDDIIII)Z",
            (void *)android_location_GpsLocationProvider_add_geofence},
    {"native_remove_geofence",
            "(I)Z",
            (void *)android_location_GpsLocationProvider_remove_geofence},
    {"native_pause_geofence", "(I)Z", (void *)android_location_GpsLocationProvider_pause_geofence},
    {"native_resume_geofence",
            "(II)Z",
            (void *)android_location_GpsLocationProvider_resume_geofence},
    {"native_is_measurement_supported",
            "()Z",
            (void*) android_location_GpsLocationProvider_is_measurement_supported},
    {"native_start_measurement_collection",
            "()Z",
            (void*) android_location_GpsLocationProvider_start_measurement_collection},
    {"native_stop_measurement_collection",
            "()Z",
            (void*) android_location_GpsLocationProvider_stop_measurement_collection},
    {"native_is_navigation_message_supported",
            "()Z",
            (void*) android_location_GpsLocationProvider_is_navigation_message_supported},
    {"native_start_navigation_message_collection",
            "()Z",
            (void*) android_location_GpsLocationProvider_start_navigation_message_collection},
    {"native_stop_navigation_message_collection",
            "()Z",
            (void*) android_location_GpsLocationProvider_stop_navigation_message_collection},
    {"native_configuration_update",
            "(Ljava/lang/String;)V",
            (void*)android_location_GpsLocationProvider_configuration_update},
};

int register_android_server_location_GpsLocationProvider(JNIEnv* env)
{
    return jniRegisterNativeMethods(
            env,
            "com/android/server/location/GpsLocationProvider",
            sMethods,
            NELEM(sMethods));
}

} /* namespace android */
