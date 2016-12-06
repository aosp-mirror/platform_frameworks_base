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

#include "JNIHelp.h"
#include "jni.h"
#include "hardware/hardware.h"
#include "hardware/gps_internal.h"
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

static jobject mCallbacksObj = NULL;

static jmethodID method_reportLocation;
static jmethodID method_reportStatus;
static jmethodID method_reportSvStatus;
static jmethodID method_reportAGpsStatus;
static jmethodID method_reportNmea;
static jmethodID method_setEngineCapabilities;
static jmethodID method_setGnssYearOfHardware;
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

#define GPS_MAX_SATELLITE_COUNT 32
#define GNSS_MAX_SATELLITE_COUNT 64

// Let these through, with ID remapped down to 1, 2... by offset
#define GLONASS_SVID_OFFSET 64
#define GLONASS_SVID_COUNT 24
#define BEIDOU_SVID_OFFSET 200
#define BEIDOU_SVID_COUNT 35

// Let these through, with ID remapped up (33->120 ... 64->151, etc.)
#define SBAS_SVID_MIN 33
#define SBAS_SVID_MAX 64
#define SBAS_SVID_ADD 87

// Let these through, with no ID remapping
#define QZSS_SVID_MIN 193
#define QZSS_SVID_MAX 200

#define SVID_SHIFT_WIDTH 7
#define CONSTELLATION_TYPE_SHIFT_WIDTH 3

// temporary storage for GPS callbacks
static GnssSvInfo sGnssSvList[GNSS_MAX_SATELLITE_COUNT];
static size_t sGnssSvListSize;
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
    size_t status_size = sv_status->size;
    // Some drives doesn't set the size field correctly. Assume GpsSvStatus_v1
    // if it doesn't provide a valid size.
    if (status_size == 0) {
        ALOGW("Invalid size of GpsSvStatus found: %zd.", status_size);
    }
    sGnssSvListSize = sv_status->num_svs;
    // Clamp the list size. Legacy GpsSvStatus has only 32 elements in sv_list.
    if (sGnssSvListSize > GPS_MAX_SATELLITE_COUNT) {
        ALOGW("Too many satellites %zd. Clamps to %d.",
              sGnssSvListSize,
              GPS_MAX_SATELLITE_COUNT);
        sGnssSvListSize = GPS_MAX_SATELLITE_COUNT;
    }
    uint32_t ephemeris_mask = sv_status->ephemeris_mask;
    uint32_t almanac_mask = sv_status->almanac_mask;
    uint32_t used_in_fix_mask = sv_status->used_in_fix_mask;
    for (size_t i = 0; i < sGnssSvListSize; i++) {
        GnssSvInfo& info = sGnssSvList[i];
        info.svid = sv_status->sv_list[i].prn;
        // Defacto mapping from the overused API that was designed for GPS-only
        if (info.svid >=1 && info.svid <= 32) {
            info.constellation = GNSS_CONSTELLATION_GPS;
        } else if (info.svid > GLONASS_SVID_OFFSET &&
                   info.svid <= GLONASS_SVID_OFFSET + GLONASS_SVID_COUNT) {
            info.constellation = GNSS_CONSTELLATION_GLONASS;
            info.svid -= GLONASS_SVID_OFFSET;
        } else if (info.svid > BEIDOU_SVID_OFFSET &&
                   info.svid <= BEIDOU_SVID_OFFSET + BEIDOU_SVID_COUNT) {
            info.constellation = GNSS_CONSTELLATION_BEIDOU;
            info.svid -= BEIDOU_SVID_OFFSET;
        } else if (info.svid >= SBAS_SVID_MIN && info.svid <= SBAS_SVID_MAX) {
            info.constellation = GNSS_CONSTELLATION_SBAS;
            info.svid += SBAS_SVID_ADD;
        } else if (info.svid >= QZSS_SVID_MIN && info.svid <= QZSS_SVID_MAX) {
            info.constellation = GNSS_CONSTELLATION_QZSS;
        } else {
            ALOGD("Unknown constellation type with Svid = %d.", info.svid);
            info.constellation = GNSS_CONSTELLATION_UNKNOWN;
        }
        info.c_n0_dbhz = sv_status->sv_list[i].snr;
        info.elevation = sv_status->sv_list[i].elevation;
        info.azimuth = sv_status->sv_list[i].azimuth;
        info.flags = GNSS_SV_FLAGS_NONE;
        // Only GPS info is valid for these fields, as these masks are just 32 bits, by GPS prn
        if (info.constellation == GNSS_CONSTELLATION_GPS) {
            int32_t this_svid_mask = (1 << (info.svid - 1));
            if ((ephemeris_mask & this_svid_mask) != 0) {
                info.flags |= GNSS_SV_FLAGS_HAS_EPHEMERIS_DATA;
            }
            if ((almanac_mask & this_svid_mask) != 0) {
                info.flags |= GNSS_SV_FLAGS_HAS_ALMANAC_DATA;
            }
            if ((used_in_fix_mask & this_svid_mask) != 0) {
                info.flags |= GNSS_SV_FLAGS_USED_IN_FIX;
            }
        }
    }
    env->CallVoidMethod(mCallbacksObj, method_reportSvStatus);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

static void gnss_sv_status_callback(GnssSvStatus* sv_status) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    size_t status_size = sv_status->size;
    // Check the size, and reject the object that has invalid size.
    if (status_size != sizeof(GnssSvStatus)) {
        ALOGE("Invalid size of GnssSvStatus found: %zd.", status_size);
        return;
    }
    sGnssSvListSize = sv_status->num_svs;
    // Clamp the list size
    if (sGnssSvListSize > GNSS_MAX_SATELLITE_COUNT) {
        ALOGD("Too many satellites %zd. Clamps to %d.",
              sGnssSvListSize,
              GNSS_MAX_SATELLITE_COUNT);
        sGnssSvListSize = GNSS_MAX_SATELLITE_COUNT;
    }
    // Copy GNSS SV info into sGnssSvList, if any.
    if (sGnssSvListSize > 0) {
        memcpy(sGnssSvList,
               sv_status->gnss_sv_list,
               sizeof(GnssSvInfo) * sGnssSvListSize);
    }
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

static void set_system_info_callback(const GnssSystemInfo* info) {
    ALOGD("set_system_info_callback: year_of_hw=%d\n", info->year_of_hw);
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->CallVoidMethod(mCallbacksObj, method_setGnssYearOfHardware,
                        info->year_of_hw);
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
    set_system_info_callback,
    gnss_sv_status_callback,
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
    if (status_size == sizeof(AGpsStatus)) {
      ALOGV("AGpsStatus is V3: %zd", status_size);
      switch (agps_status->addr.ss_family)
      {
      case AF_INET:
          {
            struct sockaddr_in *in = (struct sockaddr_in*)&(agps_status->addr);
            uint32_t ipAddr = *(uint32_t*)&(in->sin_addr);
            byteArray = convert_to_ipv4(ipAddr, true /* net_order */);
            if (ipAddr == INADDR_NONE || byteArray != NULL) {
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
      ALOGV("AGpsStatus is V2+: %zd", status_size);
      // for back-compatibility reasons we check in v2 that the data structure size is greater or
      // equal to the declared size in gps.h
      uint32_t ipaddr = agps_status->ipaddr;
      ALOGV("AGPS IP is v4: %x", ipaddr);
      byteArray = convert_to_ipv4(ipaddr, false /* net_order */);
      if (ipaddr == INADDR_NONE || byteArray != NULL) {
          isSupported = true;
      }
    } else if (status_size >= sizeof(AGpsStatus_v1)) {
        ALOGV("AGpsStatus is V1+: %zd", status_size);
        // because we have to check for >= with regards to v2, we also need to relax the check here
        // and only make sure that the size is at least what we expect
        isSupported = true;
    } else {
        ALOGE("Invalid size of AGpsStatus found: %zd.", status_size);
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

static void android_location_GnssLocationProvider_class_init_native(JNIEnv* env, jclass clazz) {
    int err;
    hw_module_t* module;

    method_reportLocation = env->GetMethodID(clazz, "reportLocation", "(IDDDFFFJ)V");
    method_reportStatus = env->GetMethodID(clazz, "reportStatus", "(I)V");
    method_reportSvStatus = env->GetMethodID(clazz, "reportSvStatus", "()V");
    method_reportAGpsStatus = env->GetMethodID(clazz, "reportAGpsStatus", "(II[B)V");
    method_reportNmea = env->GetMethodID(clazz, "reportNmea", "(J)V");
    method_setEngineCapabilities = env->GetMethodID(clazz, "setEngineCapabilities", "(I)V");
    method_setGnssYearOfHardware = env->GetMethodID(clazz, "setGnssYearOfHardware", "(I)V");
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
            "(Landroid/location/GnssMeasurementsEvent;)V");
    method_reportNavigationMessages = env->GetMethodID(
            clazz,
            "reportNavigationMessage",
            "(Landroid/location/GnssNavigationMessage;)V");

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

static jboolean android_location_GnssLocationProvider_is_supported(
        JNIEnv* /* env */, jclass /* clazz */)
{
    return (sGpsInterface != NULL) ?  JNI_TRUE : JNI_FALSE;
}

static jboolean android_location_GnssLocationProvider_is_agps_ril_supported(
        JNIEnv* /* env */, jclass /* clazz */)
{
    return (sAGpsRilInterface != NULL) ? JNI_TRUE : JNI_FALSE;
}

static jboolean android_location_gpsLocationProvider_is_gnss_configuration_supported(
        JNIEnv* /* env */, jclass /* jclazz */)
{
    return (sGnssConfigurationInterface != NULL) ? JNI_TRUE : JNI_FALSE;
}

static jboolean android_location_GnssLocationProvider_init(JNIEnv* env, jobject obj)
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

static void android_location_GnssLocationProvider_cleanup(JNIEnv* /* env */, jobject /* obj */)
{
    if (sGpsInterface)
        sGpsInterface->cleanup();
}

static jboolean android_location_GnssLocationProvider_set_position_mode(JNIEnv* /* env */,
        jobject /* obj */, jint mode, jint recurrence, jint min_interval, jint preferred_accuracy,
        jint preferred_time)
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

static jboolean android_location_GnssLocationProvider_start(JNIEnv* /* env */, jobject /* obj */)
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

static jboolean android_location_GnssLocationProvider_stop(JNIEnv* /* env */, jobject /* obj */)
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

static void android_location_GnssLocationProvider_delete_aiding_data(JNIEnv* /* env */,
                                                                    jobject /* obj */,
                                                                    jint flags)
{
    if (sGpsInterface)
        sGpsInterface->delete_aiding_data(flags);
}

static jint android_location_GnssLocationProvider_read_sv_status(JNIEnv* env, jobject /* obj */,
        jintArray svidWithFlagArray, jfloatArray cn0Array, jfloatArray elevArray,
        jfloatArray azumArray)
{
    // this should only be called from within a call to reportSvStatus
    jint* svidWithFlags = env->GetIntArrayElements(svidWithFlagArray, 0);
    jfloat* cn0s = env->GetFloatArrayElements(cn0Array, 0);
    jfloat* elev = env->GetFloatArrayElements(elevArray, 0);
    jfloat* azim = env->GetFloatArrayElements(azumArray, 0);

    // GNSS SV info.
    for (size_t i = 0; i < sGnssSvListSize; ++i) {
        const GnssSvInfo& info = sGnssSvList[i];
        svidWithFlags[i] = (info.svid << SVID_SHIFT_WIDTH) |
            (info.constellation << CONSTELLATION_TYPE_SHIFT_WIDTH) |
            info.flags;
        cn0s[i] = info.c_n0_dbhz;
        elev[i] = info.elevation;
        azim[i] = info.azimuth;
    }

    env->ReleaseIntArrayElements(svidWithFlagArray, svidWithFlags, 0);
    env->ReleaseFloatArrayElements(cn0Array, cn0s, 0);
    env->ReleaseFloatArrayElements(elevArray, elev, 0);
    env->ReleaseFloatArrayElements(azumArray, azim, 0);
    return (jint) sGnssSvListSize;
}

static void android_location_GnssLocationProvider_agps_set_reference_location_cellid(
        JNIEnv* /* env */, jobject /* obj */, jint type, jint mcc, jint mnc, jint lac, jint cid)
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

static void android_location_GnssLocationProvider_agps_send_ni_message(JNIEnv* env,
        jobject /* obj */, jbyteArray ni_msg, jint size)
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

static void android_location_GnssLocationProvider_agps_set_id(JNIEnv *env, jobject /* obj */,
                                                             jint type, jstring  setid_string)
{
    if (!sAGpsRilInterface) {
        ALOGE("no AGPS RIL interface in agps_set_id");
        return;
    }

    const char *setid = env->GetStringUTFChars(setid_string, NULL);
    sAGpsRilInterface->set_set_id(type, setid);
    env->ReleaseStringUTFChars(setid_string, setid);
}

static jint android_location_GnssLocationProvider_read_nmea(JNIEnv* env, jobject /* obj */,
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

static void android_location_GnssLocationProvider_inject_time(JNIEnv* /* env */, jobject /* obj */,
        jlong time, jlong timeReference, jint uncertainty)
{
    if (sGpsInterface)
        sGpsInterface->inject_time(time, timeReference, uncertainty);
}

static void android_location_GnssLocationProvider_inject_location(JNIEnv* /* env */,
        jobject /* obj */, jdouble latitude, jdouble longitude, jfloat accuracy)
{
    if (sGpsInterface)
        sGpsInterface->inject_location(latitude, longitude, accuracy);
}

static jboolean android_location_GnssLocationProvider_supports_xtra(
        JNIEnv* /* env */, jobject /* obj */)
{
    return (sGpsXtraInterface != NULL) ? JNI_TRUE : JNI_FALSE;
}

static void android_location_GnssLocationProvider_inject_xtra_data(JNIEnv* env, jobject /* obj */,
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

static void android_location_GnssLocationProvider_agps_data_conn_open(
        JNIEnv* env, jobject /* obj */, jstring apn, jint apnIpType)
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
    if (interface_size == sizeof(AGpsInterface)) {
        sAGpsInterface->data_conn_open_with_apn_ip_type(apnStr, apnIpType);
    } else if (interface_size == sizeof(AGpsInterface_v1)) {
        sAGpsInterface->data_conn_open(apnStr);
    } else {
        ALOGE("Invalid size of AGpsInterface found: %zd.", interface_size);
    }

    env->ReleaseStringUTFChars(apn, apnStr);
}

static void android_location_GnssLocationProvider_agps_data_conn_closed(JNIEnv* /* env */,
                                                                       jobject /* obj */)
{
    if (!sAGpsInterface) {
        ALOGE("no AGPS interface in agps_data_conn_closed");
        return;
    }
    sAGpsInterface->data_conn_closed();
}

static void android_location_GnssLocationProvider_agps_data_conn_failed(JNIEnv* /* env */,
                                                                       jobject /* obj */)
{
    if (!sAGpsInterface) {
        ALOGE("no AGPS interface in agps_data_conn_failed");
        return;
    }
    sAGpsInterface->data_conn_failed();
}

static void android_location_GnssLocationProvider_set_agps_server(JNIEnv* env, jobject /* obj */,
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

static void android_location_GnssLocationProvider_send_ni_response(JNIEnv* /* env */,
      jobject /* obj */, jint notifId, jint response)
{
    if (!sGpsNiInterface) {
        ALOGE("no NI interface in send_ni_response");
        return;
    }

    sGpsNiInterface->respond(notifId, response);
}

static jstring android_location_GnssLocationProvider_get_internal_state(JNIEnv* env,
                                                                       jobject /* obj */) {
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

static void android_location_GnssLocationProvider_update_network_state(JNIEnv* env, jobject /* obj */,
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

static jboolean android_location_GnssLocationProvider_is_geofence_supported(
        JNIEnv* /* env */, jobject /* obj */)
{
    return (sGpsGeofencingInterface != NULL) ? JNI_TRUE : JNI_FALSE;
}

static jboolean android_location_GnssLocationProvider_add_geofence(JNIEnv* /* env */,
        jobject /* obj */, jint geofence_id, jdouble latitude, jdouble longitude, jdouble radius,
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

static jboolean android_location_GnssLocationProvider_remove_geofence(JNIEnv* /* env */,
        jobject /* obj */, jint geofence_id) {
    if (sGpsGeofencingInterface != NULL) {
        sGpsGeofencingInterface->remove_geofence_area(geofence_id);
        return JNI_TRUE;
    } else {
        ALOGE("Geofence interface not available");
    }
    return JNI_FALSE;
}

static jboolean android_location_GnssLocationProvider_pause_geofence(JNIEnv* /* env */,
        jobject /* obj */, jint geofence_id) {
    if (sGpsGeofencingInterface != NULL) {
        sGpsGeofencingInterface->pause_geofence(geofence_id);
        return JNI_TRUE;
    } else {
        ALOGE("Geofence interface not available");
    }
    return JNI_FALSE;
}

static jboolean android_location_GnssLocationProvider_resume_geofence(JNIEnv* /* env */,
        jobject /* obj */, jint geofence_id, jint monitor_transition) {
    if (sGpsGeofencingInterface != NULL) {
        sGpsGeofencingInterface->resume_geofence(geofence_id, monitor_transition);
        return JNI_TRUE;
    } else {
        ALOGE("Geofence interface not available");
    }
    return JNI_FALSE;
}

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
    env_->SetByteArrayRegion(array, 0, size, (jbyte*) value);
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

// If you want to check if a flag is not set, use SET_IF_NOT(FLAG, setter,
// value) to do that. SET_IF(!FLAG, setter, value) won't compile.
//
// This macros generates compilation error if the provided 'flag' is not a
// single token. For example, 'GNSS_CLOCK_HAS_BIAS' can be accepted, but
// '!GNSS_CLOCK_HAS_DRIFT' will fail to compile.
#define SET_IF(flag, setter, value) do { \
        if (flags & flag) { \
            JavaObject& name_check_##flag = object; \
            name_check_##flag.callSetter("set" # setter, (value)); \
        } \
    } while (false)
#define SET_IF_NOT(flag, setter, value) do { \
        if (!(flags & flag)) { \
            JavaObject& name_check_##flag = object; \
            name_check_##flag.callSetter("set" # setter, (value)); \
        } \
    } while (false)

static jobject translate_gps_clock(JNIEnv* env, GpsClock* clock) {
    static uint32_t discontinuity_count_to_handle_old_clock_type = 0;
    JavaObject object(env, "android/location/GnssClock");
    GpsClockFlags flags = clock->flags;

    SET_IF(GPS_CLOCK_HAS_LEAP_SECOND,
           LeapSecond,
           static_cast<int32_t>(clock->leap_second));

    // GnssClock only supports the more effective HW_CLOCK type, so type
    // handling and documentation complexity has been removed.  To convert the
    // old GPS_CLOCK types (active only in a limited number of older devices),
    // the GPS time information is handled as an always discontinuous HW clock,
    // with the GPS time information put into the full_bias_ns instead - so that
    // time_ns - full_bias_ns = local estimate of GPS time. Additionally, the
    // sign of full_bias_ns and bias_ns has flipped between GpsClock &
    // GnssClock, so that is also handled below.
    switch (clock->type) {
      case GPS_CLOCK_TYPE_UNKNOWN:
        // Clock type unsupported.
        ALOGE("Unknown clock type provided.");
        break;
      case GPS_CLOCK_TYPE_LOCAL_HW_TIME:
        // Already local hardware time. No need to do anything.
        break;
      case GPS_CLOCK_TYPE_GPS_TIME:
        // GPS time, need to convert.
        flags |= GPS_CLOCK_HAS_FULL_BIAS;
        clock->full_bias_ns = clock->time_ns;
        clock->time_ns = 0;
        SET(HardwareClockDiscontinuityCount,
            discontinuity_count_to_handle_old_clock_type++);
        break;
    }

    SET(TimeNanos, clock->time_ns);
    SET_IF(GPS_CLOCK_HAS_TIME_UNCERTAINTY,
           TimeUncertaintyNanos,
           clock->time_uncertainty_ns);

    // Definition of sign for full_bias_ns & bias_ns has been changed since N,
    // so flip signs here.
    SET_IF(GPS_CLOCK_HAS_FULL_BIAS, FullBiasNanos, -(clock->full_bias_ns));
    SET_IF(GPS_CLOCK_HAS_BIAS, BiasNanos, -(clock->bias_ns));

    SET_IF(GPS_CLOCK_HAS_BIAS_UNCERTAINTY,
           BiasUncertaintyNanos,
           clock->bias_uncertainty_ns);
    SET_IF(GPS_CLOCK_HAS_DRIFT, DriftNanosPerSecond, clock->drift_nsps);
    SET_IF(GPS_CLOCK_HAS_DRIFT_UNCERTAINTY,
           DriftUncertaintyNanosPerSecond,
           clock->drift_uncertainty_nsps);

    return object.get();
}

static jobject translate_gnss_clock(JNIEnv* env, GnssClock* clock) {
    JavaObject object(env, "android/location/GnssClock");
    GnssClockFlags flags = clock->flags;

    SET_IF(GNSS_CLOCK_HAS_LEAP_SECOND,
           LeapSecond,
           static_cast<int32_t>(clock->leap_second));
    SET(TimeNanos, clock->time_ns);
    SET_IF(GNSS_CLOCK_HAS_TIME_UNCERTAINTY,
           TimeUncertaintyNanos,
           clock->time_uncertainty_ns);
    SET_IF(GNSS_CLOCK_HAS_FULL_BIAS, FullBiasNanos, clock->full_bias_ns);
    SET_IF(GNSS_CLOCK_HAS_BIAS, BiasNanos, clock->bias_ns);
    SET_IF(GNSS_CLOCK_HAS_BIAS_UNCERTAINTY,
           BiasUncertaintyNanos,
           clock->bias_uncertainty_ns);
    SET_IF(GNSS_CLOCK_HAS_DRIFT, DriftNanosPerSecond, clock->drift_nsps);
    SET_IF(GNSS_CLOCK_HAS_DRIFT_UNCERTAINTY,
           DriftUncertaintyNanosPerSecond,
           clock->drift_uncertainty_nsps);

    SET(HardwareClockDiscontinuityCount, clock->hw_clock_discontinuity_count);

    return object.get();
}

static jobject translate_gps_measurement(JNIEnv* env,
                                         GpsMeasurement* measurement) {
    JavaObject object(env, "android/location/GnssMeasurement");
    GpsMeasurementFlags flags = measurement->flags;
    SET(Svid, static_cast<int32_t>(measurement->prn));
    if (measurement->prn >= 1 && measurement->prn <= 32) {
        SET(ConstellationType, static_cast<int32_t>(GNSS_CONSTELLATION_GPS));
    } else {
        ALOGD("Unknown constellation type with Svid = %d.", measurement->prn);
        SET(ConstellationType,
            static_cast<int32_t>(GNSS_CONSTELLATION_UNKNOWN));
    }
    SET(TimeOffsetNanos, measurement->time_offset_ns);
    SET(State, static_cast<int32_t>(measurement->state));
    SET(ReceivedSvTimeNanos, measurement->received_gps_tow_ns);
    SET(ReceivedSvTimeUncertaintyNanos,
        measurement->received_gps_tow_uncertainty_ns);
    SET(Cn0DbHz, measurement->c_n0_dbhz);
    SET(PseudorangeRateMetersPerSecond, measurement->pseudorange_rate_mps);
    SET(PseudorangeRateUncertaintyMetersPerSecond,
        measurement->pseudorange_rate_uncertainty_mps);
    SET(AccumulatedDeltaRangeState,
        static_cast<int32_t>(measurement->accumulated_delta_range_state));
    SET(AccumulatedDeltaRangeMeters, measurement->accumulated_delta_range_m);
    SET(AccumulatedDeltaRangeUncertaintyMeters,
        measurement->accumulated_delta_range_uncertainty_m);
    SET_IF(GNSS_MEASUREMENT_HAS_CARRIER_FREQUENCY,
           CarrierFrequencyHz,
           measurement->carrier_frequency_hz);
    SET_IF(GNSS_MEASUREMENT_HAS_CARRIER_CYCLES,
           CarrierCycles,
           measurement->carrier_cycles);
    SET_IF(GNSS_MEASUREMENT_HAS_CARRIER_PHASE,
           CarrierPhase,
           measurement->carrier_phase);
    SET_IF(GNSS_MEASUREMENT_HAS_CARRIER_PHASE_UNCERTAINTY,
           CarrierPhaseUncertainty,
           measurement->carrier_phase_uncertainty);
    SET(MultipathIndicator,
        static_cast<int32_t>(measurement->multipath_indicator));
    SET_IF(GNSS_MEASUREMENT_HAS_SNR, SnrInDb, measurement->snr_db);

    return object.get();
}

static jobject translate_gnss_measurement(JNIEnv* env,
                                          GnssMeasurement* measurement) {
    JavaObject object(env, "android/location/GnssMeasurement");

    GnssMeasurementFlags flags = measurement->flags;

    SET(Svid, static_cast<int32_t>(measurement->svid));
    SET(ConstellationType, static_cast<int32_t>(measurement->constellation));
    SET(TimeOffsetNanos, measurement->time_offset_ns);
    SET(State, static_cast<int32_t>(measurement->state));
    SET(ReceivedSvTimeNanos, measurement->received_sv_time_in_ns);
    SET(ReceivedSvTimeUncertaintyNanos,
        measurement->received_sv_time_uncertainty_in_ns);
    SET(Cn0DbHz, measurement->c_n0_dbhz);
    SET(PseudorangeRateMetersPerSecond, measurement->pseudorange_rate_mps);
    SET(PseudorangeRateUncertaintyMetersPerSecond,
        measurement->pseudorange_rate_uncertainty_mps);
    SET(AccumulatedDeltaRangeState,
        static_cast<int32_t>(measurement->accumulated_delta_range_state));
    SET(AccumulatedDeltaRangeMeters, measurement->accumulated_delta_range_m);
    SET(AccumulatedDeltaRangeUncertaintyMeters,
        measurement->accumulated_delta_range_uncertainty_m);
    SET_IF(GNSS_MEASUREMENT_HAS_CARRIER_FREQUENCY,
           CarrierFrequencyHz,
           measurement->carrier_frequency_hz);
    SET_IF(GNSS_MEASUREMENT_HAS_CARRIER_CYCLES,
           CarrierCycles,
           measurement->carrier_cycles);
    SET_IF(GNSS_MEASUREMENT_HAS_CARRIER_PHASE,
           CarrierPhase,
           measurement->carrier_phase);
    SET_IF(GNSS_MEASUREMENT_HAS_CARRIER_PHASE_UNCERTAINTY,
           CarrierPhaseUncertainty,
           measurement->carrier_phase_uncertainty);
    SET(MultipathIndicator,
        static_cast<int32_t>(measurement->multipath_indicator));
    SET_IF(GNSS_MEASUREMENT_HAS_SNR, SnrInDb, measurement->snr_db);

    return object.get();
}

static jobjectArray translate_gps_measurements(JNIEnv* env,
                                               GpsMeasurement* measurements,
                                               size_t count) {
    if (count == 0) {
        return NULL;
    }

    jclass gnssMeasurementClass = env->FindClass(
            "android/location/GnssMeasurement");
    jobjectArray gnssMeasurementArray = env->NewObjectArray(
            count,
            gnssMeasurementClass,
            NULL /* initialElement */);

    for (uint16_t i = 0; i < count; ++i) {
        jobject gnssMeasurement = translate_gps_measurement(
            env,
            &measurements[i]);
        env->SetObjectArrayElement(gnssMeasurementArray, i, gnssMeasurement);
        env->DeleteLocalRef(gnssMeasurement);
    }

    env->DeleteLocalRef(gnssMeasurementClass);
    return gnssMeasurementArray;
}

static jobjectArray translate_gnss_measurements(JNIEnv* env,
                                                GnssMeasurement* measurements,
                                                size_t count) {
    if (count == 0) {
        return NULL;
    }

    jclass gnssMeasurementClass = env->FindClass(
            "android/location/GnssMeasurement");
    jobjectArray gnssMeasurementArray = env->NewObjectArray(
            count,
            gnssMeasurementClass,
            NULL /* initialElement */);

    for (uint16_t i = 0; i < count; ++i) {
        jobject gnssMeasurement = translate_gnss_measurement(
            env,
            &measurements[i]);
        env->SetObjectArrayElement(gnssMeasurementArray, i, gnssMeasurement);
        env->DeleteLocalRef(gnssMeasurement);
    }

    env->DeleteLocalRef(gnssMeasurementClass);
    return gnssMeasurementArray;
}

static void set_measurement_data(JNIEnv *env,
                                 jobject clock,
                                 jobjectArray measurementArray) {
    jclass gnssMeasurementsEventClass = env->FindClass(
            "android/location/GnssMeasurementsEvent");
    jmethodID gnssMeasurementsEventCtor = env->GetMethodID(
        gnssMeasurementsEventClass,
        "<init>",
        "(Landroid/location/GnssClock;[Landroid/location/GnssMeasurement;)V");

    jobject gnssMeasurementsEvent = env->NewObject(
        gnssMeasurementsEventClass,
        gnssMeasurementsEventCtor,
        clock,
        measurementArray);
    env->CallVoidMethod(mCallbacksObj,
                        method_reportMeasurementData,
                        gnssMeasurementsEvent);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    env->DeleteLocalRef(gnssMeasurementsEventClass);
    env->DeleteLocalRef(gnssMeasurementsEvent);
}

static void measurement_callback(GpsData* data) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    if (data == NULL) {
        ALOGE("Invalid data provided to gps_measurement_callback");
        return;
    }
    if (data->size != sizeof(GpsData)) {
        ALOGE("Invalid GpsData size found in gps_measurement_callback, "
              "size=%zd",
              data->size);
        return;
    }

    jobject clock;
    jobjectArray measurementArray;
    clock = translate_gps_clock(env, &data->clock);
    measurementArray = translate_gps_measurements(
            env, data->measurements, data->measurement_count);
    set_measurement_data(env, clock, measurementArray);

    env->DeleteLocalRef(clock);
    env->DeleteLocalRef(measurementArray);
}

static void gnss_measurement_callback(GnssData* data) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    if (data == NULL) {
        ALOGE("Invalid data provided to gps_measurement_callback");
        return;
    }
    if (data->size != sizeof(GnssData)) {
        ALOGE("Invalid GnssData size found in gnss_measurement_callback, "
              "size=%zd",
              data->size);
        return;
    }

    jobject clock;
    jobjectArray measurementArray;
    clock = translate_gnss_clock(env, &data->clock);
    measurementArray = translate_gnss_measurements(
            env, data->measurements, data->measurement_count);
    set_measurement_data(env, clock, measurementArray);

    env->DeleteLocalRef(clock);
    env->DeleteLocalRef(measurementArray);
}

GpsMeasurementCallbacks sGpsMeasurementCallbacks = {
    sizeof(GpsMeasurementCallbacks),
    measurement_callback,
    gnss_measurement_callback,
};

static jboolean android_location_GnssLocationProvider_is_measurement_supported(
        JNIEnv* env,
        jclass clazz) {
    if (sGpsMeasurementInterface != NULL) {
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

static jboolean android_location_GnssLocationProvider_start_measurement_collection(
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

static jboolean android_location_GnssLocationProvider_stop_measurement_collection(
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
        ALOGE("Invalid Navigation Message found: data=%p, length=%zd", data, dataLength);
        return NULL;
    }
    JavaObject object(env, "android/location/GnssNavigationMessage");
    SET(Svid, static_cast<int32_t>(message->prn));
    if (message->prn >=1 && message->prn <= 32) {
        SET(ConstellationType, static_cast<int32_t>(GNSS_CONSTELLATION_GPS));
        // Legacy driver doesn't set the higher byte to constellation type
        // correctly. Set the higher byte to 'GPS'.
        SET(Type, static_cast<int32_t>(message->type | 0x0100));
    } else {
        ALOGD("Unknown constellation type with Svid = %d.", message->prn);
        SET(ConstellationType,
            static_cast<int32_t>(GNSS_CONSTELLATION_UNKNOWN));
        SET(Type, static_cast<int32_t>(GNSS_NAVIGATION_MESSAGE_TYPE_UNKNOWN));
    }
    SET(MessageId, static_cast<int32_t>(message->message_id));
    SET(SubmessageId, static_cast<int32_t>(message->submessage_id));
    object.callSetter("setData", data, dataLength);
    SET(Status, static_cast<int32_t>(message->status));
    return object.get();
}

static jobject translate_gnss_navigation_message(
        JNIEnv* env, GnssNavigationMessage* message) {
    size_t dataLength = message->data_length;
    uint8_t* data = message->data;
    if (dataLength == 0 || data == NULL) {
        ALOGE("Invalid Navigation Message found: data=%p, length=%zd", data, dataLength);
        return NULL;
    }
    JavaObject object(env, "android/location/GnssNavigationMessage");
    SET(Type, static_cast<int32_t>(message->type));
    SET(Svid, static_cast<int32_t>(message->svid));
    SET(MessageId, static_cast<int32_t>(message->message_id));
    SET(SubmessageId, static_cast<int32_t>(message->submessage_id));
    object.callSetter("setData", data, dataLength);
    SET(Status, static_cast<int32_t>(message->status));
    return object.get();
}

static void navigation_message_callback(GpsNavigationMessage* message) {
    if (message == NULL) {
        ALOGE("Invalid Navigation Message provided to callback");
        return;
    }
    if (message->size != sizeof(GpsNavigationMessage)) {
        ALOGE("Invalid GpsNavigationMessage size found: %zd", message->size);
        return;
    }
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jobject navigationMessage = translate_gps_navigation_message(env, message);
    env->CallVoidMethod(mCallbacksObj,
                        method_reportNavigationMessages,
                        navigationMessage);
    env->DeleteLocalRef(navigationMessage);
}

static void gnss_navigation_message_callback(GnssNavigationMessage* message) {
    if (message == NULL) {
        ALOGE("Invalid Navigation Message provided to callback");
        return;
    }
    if (message->size != sizeof(GnssNavigationMessage)) {
        ALOGE("Invalid GnssNavigationMessage size found: %zd", message->size);
        return;
    }
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jobject navigationMessage = translate_gnss_navigation_message(env, message);
    env->CallVoidMethod(mCallbacksObj,
                        method_reportNavigationMessages,
                        navigationMessage);
    env->DeleteLocalRef(navigationMessage);
}

GpsNavigationMessageCallbacks sGpsNavigationMessageCallbacks = {
    sizeof(GpsNavigationMessageCallbacks),
    navigation_message_callback,
    gnss_navigation_message_callback,
};

static jboolean android_location_GnssLocationProvider_is_navigation_message_supported(
        JNIEnv* env,
        jclass clazz) {
    if(sGpsNavigationMessageInterface != NULL) {
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

static jboolean android_location_GnssLocationProvider_start_navigation_message_collection(
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

static jboolean android_location_GnssLocationProvider_stop_navigation_message_collection(
        JNIEnv* env,
        jobject obj) {
    if (sGpsNavigationMessageInterface == NULL) {
        ALOGE("Navigation Message interface is not available.");
        return JNI_FALSE;
    }

    sGpsNavigationMessageInterface->close();
    return JNI_TRUE;
}

static void android_location_GnssLocationProvider_configuration_update(JNIEnv* env, jobject obj,
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

static const JNINativeMethod sMethods[] = {
     /* name, signature, funcPtr */
    {"class_init_native", "()V", (void *)android_location_GnssLocationProvider_class_init_native},
    {"native_is_supported", "()Z", (void*)android_location_GnssLocationProvider_is_supported},
    {"native_is_agps_ril_supported", "()Z",
            (void*)android_location_GnssLocationProvider_is_agps_ril_supported},
    {"native_is_gnss_configuration_supported", "()Z",
            (void*)android_location_gpsLocationProvider_is_gnss_configuration_supported},
    {"native_init", "()Z", (void*)android_location_GnssLocationProvider_init},
    {"native_cleanup", "()V", (void*)android_location_GnssLocationProvider_cleanup},
    {"native_set_position_mode",
            "(IIIII)Z",
            (void*)android_location_GnssLocationProvider_set_position_mode},
    {"native_start", "()Z", (void*)android_location_GnssLocationProvider_start},
    {"native_stop", "()Z", (void*)android_location_GnssLocationProvider_stop},
    {"native_delete_aiding_data",
            "(I)V",
            (void*)android_location_GnssLocationProvider_delete_aiding_data},
    {"native_read_sv_status",
            "([I[F[F[F)I",
            (void*)android_location_GnssLocationProvider_read_sv_status},
    {"native_read_nmea", "([BI)I", (void*)android_location_GnssLocationProvider_read_nmea},
    {"native_inject_time", "(JJI)V", (void*)android_location_GnssLocationProvider_inject_time},
    {"native_inject_location",
            "(DDF)V",
            (void*)android_location_GnssLocationProvider_inject_location},
    {"native_supports_xtra", "()Z", (void*)android_location_GnssLocationProvider_supports_xtra},
    {"native_inject_xtra_data",
            "([BI)V",
            (void*)android_location_GnssLocationProvider_inject_xtra_data},
    {"native_agps_data_conn_open",
            "(Ljava/lang/String;I)V",
            (void*)android_location_GnssLocationProvider_agps_data_conn_open},
    {"native_agps_data_conn_closed",
            "()V",
            (void*)android_location_GnssLocationProvider_agps_data_conn_closed},
    {"native_agps_data_conn_failed",
            "()V",
            (void*)android_location_GnssLocationProvider_agps_data_conn_failed},
    {"native_agps_set_id",
            "(ILjava/lang/String;)V",
            (void*)android_location_GnssLocationProvider_agps_set_id},
    {"native_agps_set_ref_location_cellid",
            "(IIIII)V",
            (void*)android_location_GnssLocationProvider_agps_set_reference_location_cellid},
    {"native_set_agps_server",
            "(ILjava/lang/String;I)V",
            (void*)android_location_GnssLocationProvider_set_agps_server},
    {"native_send_ni_response",
            "(II)V",
            (void*)android_location_GnssLocationProvider_send_ni_response},
    {"native_agps_ni_message",
            "([BI)V",
            (void *)android_location_GnssLocationProvider_agps_send_ni_message},
    {"native_get_internal_state",
            "()Ljava/lang/String;",
            (void*)android_location_GnssLocationProvider_get_internal_state},
    {"native_update_network_state",
            "(ZIZZLjava/lang/String;Ljava/lang/String;)V",
            (void*)android_location_GnssLocationProvider_update_network_state },
    {"native_is_geofence_supported",
            "()Z",
            (void*) android_location_GnssLocationProvider_is_geofence_supported},
    {"native_add_geofence",
            "(IDDDIIII)Z",
            (void *)android_location_GnssLocationProvider_add_geofence},
    {"native_remove_geofence",
            "(I)Z",
            (void *)android_location_GnssLocationProvider_remove_geofence},
    {"native_pause_geofence", "(I)Z", (void *)android_location_GnssLocationProvider_pause_geofence},
    {"native_resume_geofence",
            "(II)Z",
            (void *)android_location_GnssLocationProvider_resume_geofence},
    {"native_is_measurement_supported",
            "()Z",
            (void*) android_location_GnssLocationProvider_is_measurement_supported},
    {"native_start_measurement_collection",
            "()Z",
            (void*) android_location_GnssLocationProvider_start_measurement_collection},
    {"native_stop_measurement_collection",
            "()Z",
            (void*) android_location_GnssLocationProvider_stop_measurement_collection},
    {"native_is_navigation_message_supported",
            "()Z",
            (void*) android_location_GnssLocationProvider_is_navigation_message_supported},
    {"native_start_navigation_message_collection",
            "()Z",
            (void*) android_location_GnssLocationProvider_start_navigation_message_collection},
    {"native_stop_navigation_message_collection",
            "()Z",
            (void*) android_location_GnssLocationProvider_stop_navigation_message_collection},
    {"native_configuration_update",
            "(Ljava/lang/String;)V",
            (void*)android_location_GnssLocationProvider_configuration_update},
};

int register_android_server_location_GnssLocationProvider(JNIEnv* env)
{
    return jniRegisterNativeMethods(
            env,
            "com/android/server/location/GnssLocationProvider",
            sMethods,
            NELEM(sMethods));
}

} /* namespace android */
