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

//#define LOG_NDDEBUG 0

#include "JNIHelp.h"
#include "jni.h"
#include "hardware_legacy/gps.h"
#include "hardware_legacy/gps_ni.h"
#include "utils/Log.h"
#include "utils/misc.h"

#include <string.h>
#include <pthread.h>

static pthread_mutex_t sEventMutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t sEventCond = PTHREAD_COND_INITIALIZER;
static jmethodID method_reportLocation;
static jmethodID method_reportStatus;
static jmethodID method_reportSvStatus;
static jmethodID method_reportAGpsStatus;
static jmethodID method_reportNmea;
static jmethodID method_xtraDownloadRequest;
static jmethodID method_reportNiNotification;

static const GpsInterface* sGpsInterface = NULL;
static const GpsXtraInterface* sGpsXtraInterface = NULL;
static const AGpsInterface* sAGpsInterface = NULL;
static const GpsPrivacyInterface* sGpsPrivacyInterface = NULL;
static const GpsNiInterface* sGpsNiInterface = NULL;
static const GpsDebugInterface* sGpsDebugInterface = NULL;

// data written to by GPS callbacks
static GpsLocation  sGpsLocation;
static GpsStatus    sGpsStatus;
static GpsSvStatus  sGpsSvStatus;
static AGpsStatus   sAGpsStatus;
static GpsNiNotification  sGpsNiNotification;

// buffer for NMEA data
#define NMEA_SENTENCE_LENGTH    100
#define NMEA_SENTENCE_COUNT     40
struct NmeaSentence {
    GpsUtcTime  timestamp;
    char        nmea[NMEA_SENTENCE_LENGTH];
};
static NmeaSentence sNmeaBuffer[NMEA_SENTENCE_COUNT];
static int mNmeaSentenceCount = 0;

// a copy of the data shared by android_location_GpsLocationProvider_wait_for_event
// and android_location_GpsLocationProvider_read_status
static GpsLocation  sGpsLocationCopy;
static GpsStatus    sGpsStatusCopy;
static GpsSvStatus  sGpsSvStatusCopy;
static AGpsStatus   sAGpsStatusCopy;
static NmeaSentence sNmeaBufferCopy[NMEA_SENTENCE_COUNT];
static GpsNiNotification  sGpsNiNotificationCopy;

enum CallbackType {
    kLocation = 1,
    kStatus = 2,
    kSvStatus = 4,
    kAGpsStatus = 8,
    kXtraDownloadRequest = 16,
    kDisableRequest = 32,
    kNmeaAvailable = 64,
    kNiNotification = 128,
}; 
static int sPendingCallbacks;

namespace android {

static void location_callback(GpsLocation* location)
{
    pthread_mutex_lock(&sEventMutex);

    sPendingCallbacks |= kLocation;
    memcpy(&sGpsLocation, location, sizeof(sGpsLocation));

    pthread_cond_signal(&sEventCond);
    pthread_mutex_unlock(&sEventMutex);
}

static void status_callback(GpsStatus* status)
{
    pthread_mutex_lock(&sEventMutex);

    sPendingCallbacks |= kStatus;
    memcpy(&sGpsStatus, status, sizeof(sGpsStatus));

    pthread_cond_signal(&sEventCond);
    pthread_mutex_unlock(&sEventMutex);
}

static void sv_status_callback(GpsSvStatus* sv_status)
{
    pthread_mutex_lock(&sEventMutex);

    sPendingCallbacks |= kSvStatus;
    memcpy(&sGpsSvStatus, sv_status, sizeof(GpsSvStatus));

    pthread_cond_signal(&sEventCond);
    pthread_mutex_unlock(&sEventMutex);
}

static void nmea_callback(GpsUtcTime timestamp, const char* nmea, int length)
{
    pthread_mutex_lock(&sEventMutex);

    if (length >= NMEA_SENTENCE_LENGTH) {
        LOGE("NMEA data too long in nmea_callback (length = %d)\n", length);
        length = NMEA_SENTENCE_LENGTH - 1;
    }
    if (mNmeaSentenceCount >= NMEA_SENTENCE_COUNT) {
        LOGE("NMEA data overflowed buffer\n");
        pthread_mutex_unlock(&sEventMutex);
        return;
    }

    sPendingCallbacks |= kNmeaAvailable;
    sNmeaBuffer[mNmeaSentenceCount].timestamp = timestamp;
    memcpy(sNmeaBuffer[mNmeaSentenceCount].nmea, nmea, length);
    sNmeaBuffer[mNmeaSentenceCount].nmea[length] = 0;
    mNmeaSentenceCount++;

    pthread_cond_signal(&sEventCond);
    pthread_mutex_unlock(&sEventMutex);
}

static void agps_status_callback(AGpsStatus* agps_status)
{
    pthread_mutex_lock(&sEventMutex);

    sPendingCallbacks |= kAGpsStatus;
    memcpy(&sAGpsStatus, agps_status, sizeof(AGpsStatus));

    pthread_cond_signal(&sEventCond);
    pthread_mutex_unlock(&sEventMutex);
}

GpsCallbacks sGpsCallbacks = {
    location_callback,
    status_callback,
    sv_status_callback,
    nmea_callback
};

static void
download_request_callback()
{
    pthread_mutex_lock(&sEventMutex);
    sPendingCallbacks |= kXtraDownloadRequest;
    pthread_cond_signal(&sEventCond);
    pthread_mutex_unlock(&sEventMutex);
}

static void
gps_ni_notify_callback(GpsNiNotification *notification)
{
   LOGD("gps_ni_notify_callback: notif=%d", notification->notification_id);

   pthread_mutex_lock(&sEventMutex);

   sPendingCallbacks |= kNiNotification;
   memcpy(&sGpsNiNotification, notification, sizeof(GpsNiNotification));

   pthread_cond_signal(&sEventCond);
   pthread_mutex_unlock(&sEventMutex);
}

GpsXtraCallbacks sGpsXtraCallbacks = {
    download_request_callback,
};

AGpsCallbacks sAGpsCallbacks = {
    agps_status_callback,
};

GpsNiCallbacks sGpsNiCallbacks = {
    gps_ni_notify_callback,
};

static void android_location_GpsLocationProvider_class_init_native(JNIEnv* env, jclass clazz) {
    method_reportLocation = env->GetMethodID(clazz, "reportLocation", "(IDDDFFFJ)V");
    method_reportStatus = env->GetMethodID(clazz, "reportStatus", "(I)V");
    method_reportSvStatus = env->GetMethodID(clazz, "reportSvStatus", "()V");
    method_reportAGpsStatus = env->GetMethodID(clazz, "reportAGpsStatus", "(II)V");
    method_reportNmea = env->GetMethodID(clazz, "reportNmea", "(IJ)V");
    method_xtraDownloadRequest = env->GetMethodID(clazz, "xtraDownloadRequest", "()V");
    method_reportNiNotification = env->GetMethodID(clazz, "reportNiNotification", "(IIIIILjava/lang/String;Ljava/lang/String;IILjava/lang/String;)V");
}

static jboolean android_location_GpsLocationProvider_is_supported(JNIEnv* env, jclass clazz) {
    if (!sGpsInterface)
        sGpsInterface = gps_get_interface();
    return (sGpsInterface != NULL);
}

static jboolean android_location_GpsLocationProvider_init(JNIEnv* env, jobject obj)
{
    if (!sGpsInterface)
        sGpsInterface = gps_get_interface();
    if (!sGpsInterface || sGpsInterface->init(&sGpsCallbacks) != 0)
        return false;

    if (!sAGpsInterface)
        sAGpsInterface = (const AGpsInterface*)sGpsInterface->get_extension(AGPS_INTERFACE);
    if (sAGpsInterface)
        sAGpsInterface->init(&sAGpsCallbacks);

    if (!sGpsNiInterface)
        sGpsNiInterface = (const GpsNiInterface*)sGpsInterface->get_extension(GPS_NI_INTERFACE);
    if (sGpsNiInterface)
        sGpsNiInterface->init(&sGpsNiCallbacks);

    // Clear privacy lock while enabled
    if (!sGpsPrivacyInterface)
        sGpsPrivacyInterface = (const GpsPrivacyInterface*)sGpsInterface->get_extension(GPS_PRIVACY_INTERFACE);
    if (sGpsPrivacyInterface)
        sGpsPrivacyInterface->set_privacy_lock(0);

    if (!sGpsDebugInterface)
       sGpsDebugInterface = (const GpsDebugInterface*)sGpsInterface->get_extension(GPS_DEBUG_INTERFACE);

    return true;
}

static void android_location_GpsLocationProvider_disable(JNIEnv* env, jobject obj)
{
    // Enable privacy lock while disabled
    if (!sGpsPrivacyInterface)
        sGpsPrivacyInterface = (const GpsPrivacyInterface*)sGpsInterface->get_extension(GPS_PRIVACY_INTERFACE);
    if (sGpsPrivacyInterface)
        sGpsPrivacyInterface->set_privacy_lock(1);

    pthread_mutex_lock(&sEventMutex);
    sPendingCallbacks |= kDisableRequest;
    pthread_cond_signal(&sEventCond);
    pthread_mutex_unlock(&sEventMutex);
}

static void android_location_GpsLocationProvider_cleanup(JNIEnv* env, jobject obj)
{
    sGpsInterface->cleanup();
}

static jboolean android_location_GpsLocationProvider_start(JNIEnv* env, jobject obj, jint positionMode,
        jboolean singleFix, jint fixFrequency)
{
    int result = sGpsInterface->set_position_mode(positionMode, (singleFix ? 0 : fixFrequency));
    if (result) {
        return false;
    }

    return (sGpsInterface->start() == 0);
}

static jboolean android_location_GpsLocationProvider_stop(JNIEnv* env, jobject obj)
{
    return (sGpsInterface->stop() == 0);
}

static void android_location_GpsLocationProvider_delete_aiding_data(JNIEnv* env, jobject obj, jint flags)
{
    sGpsInterface->delete_aiding_data(flags);
}

static void android_location_GpsLocationProvider_wait_for_event(JNIEnv* env, jobject obj)
{
    pthread_mutex_lock(&sEventMutex);
    while (sPendingCallbacks == 0) {
        pthread_cond_wait(&sEventCond, &sEventMutex);
    }

    // copy and clear the callback flags
    int pendingCallbacks = sPendingCallbacks;
    sPendingCallbacks = 0;
    int nmeaSentenceCount = mNmeaSentenceCount;
    mNmeaSentenceCount = 0;
    
    // copy everything and unlock the mutex before calling into Java code to avoid the possibility
    // of timeouts in the GPS engine.
    if (pendingCallbacks & kLocation)
        memcpy(&sGpsLocationCopy, &sGpsLocation, sizeof(sGpsLocationCopy));
    if (pendingCallbacks & kStatus)
        memcpy(&sGpsStatusCopy, &sGpsStatus, sizeof(sGpsStatusCopy));
    if (pendingCallbacks & kSvStatus)
        memcpy(&sGpsSvStatusCopy, &sGpsSvStatus, sizeof(sGpsSvStatusCopy));
    if (pendingCallbacks & kAGpsStatus)
        memcpy(&sAGpsStatusCopy, &sAGpsStatus, sizeof(sAGpsStatusCopy));
    if (pendingCallbacks & kNmeaAvailable)
        memcpy(&sNmeaBufferCopy, &sNmeaBuffer, nmeaSentenceCount * sizeof(sNmeaBuffer[0]));
    if (pendingCallbacks & kNiNotification)
        memcpy(&sGpsNiNotificationCopy, &sGpsNiNotification, sizeof(sGpsNiNotificationCopy));
    pthread_mutex_unlock(&sEventMutex);   

    if (pendingCallbacks & kLocation) {
        env->CallVoidMethod(obj, method_reportLocation, sGpsLocationCopy.flags,
                (jdouble)sGpsLocationCopy.latitude, (jdouble)sGpsLocationCopy.longitude,
                (jdouble)sGpsLocationCopy.altitude,
                (jfloat)sGpsLocationCopy.speed, (jfloat)sGpsLocationCopy.bearing,
                (jfloat)sGpsLocationCopy.accuracy, (jlong)sGpsLocationCopy.timestamp);
    }
    if (pendingCallbacks & kStatus) {
        env->CallVoidMethod(obj, method_reportStatus, sGpsStatusCopy.status);
    }
    if (pendingCallbacks & kSvStatus) {
        env->CallVoidMethod(obj, method_reportSvStatus);
    }
    if (pendingCallbacks & kAGpsStatus) {
        env->CallVoidMethod(obj, method_reportAGpsStatus, sAGpsStatusCopy.type, sAGpsStatusCopy.status);
    }  
    if (pendingCallbacks & kNmeaAvailable) {
        for (int i = 0; i < nmeaSentenceCount; i++) {
            env->CallVoidMethod(obj, method_reportNmea, i, sNmeaBuffer[i].timestamp);
        }
    }
    if (pendingCallbacks & kXtraDownloadRequest) {
        env->CallVoidMethod(obj, method_xtraDownloadRequest);
    }
    if (pendingCallbacks & kDisableRequest) {
        // don't need to do anything - we are just poking so wait_for_event will return.
    }
    if (pendingCallbacks & kNiNotification) {
       LOGD("android_location_GpsLocationProvider_wait_for_event: sent notification callback.");
       jstring reqId = env->NewStringUTF(sGpsNiNotificationCopy.requestor_id);
       jstring text = env->NewStringUTF(sGpsNiNotificationCopy.text);
       jstring extras = env->NewStringUTF(sGpsNiNotificationCopy.extras);
       env->CallVoidMethod(obj, method_reportNiNotification,
             sGpsNiNotificationCopy.notification_id,
             sGpsNiNotificationCopy.ni_type,
             sGpsNiNotificationCopy.notify_flags,
             sGpsNiNotificationCopy.timeout,
             sGpsNiNotificationCopy.default_response,
             reqId,
             text,
             sGpsNiNotificationCopy.requestor_id_encoding,
             sGpsNiNotificationCopy.text_encoding,
             extras
       );
    }
}

static jint android_location_GpsLocationProvider_read_sv_status(JNIEnv* env, jobject obj,
        jintArray prnArray, jfloatArray snrArray, jfloatArray elevArray, jfloatArray azumArray,
        jintArray maskArray)
{
    // this should only be called from within a call to reportStatus, so we don't need to lock here

    jint* prns = env->GetIntArrayElements(prnArray, 0);
    jfloat* snrs = env->GetFloatArrayElements(snrArray, 0);
    jfloat* elev = env->GetFloatArrayElements(elevArray, 0);
    jfloat* azim = env->GetFloatArrayElements(azumArray, 0);
    jint* mask = env->GetIntArrayElements(maskArray, 0);

    int num_svs = sGpsSvStatusCopy.num_svs;
    for (int i = 0; i < num_svs; i++) {
        prns[i] = sGpsSvStatusCopy.sv_list[i].prn;
        snrs[i] = sGpsSvStatusCopy.sv_list[i].snr;
        elev[i] = sGpsSvStatusCopy.sv_list[i].elevation;
        azim[i] = sGpsSvStatusCopy.sv_list[i].azimuth;
    }
    mask[0] = sGpsSvStatusCopy.ephemeris_mask;
    mask[1] = sGpsSvStatusCopy.almanac_mask;
    mask[2] = sGpsSvStatusCopy.used_in_fix_mask;

    env->ReleaseIntArrayElements(prnArray, prns, 0);
    env->ReleaseFloatArrayElements(snrArray, snrs, 0);
    env->ReleaseFloatArrayElements(elevArray, elev, 0);
    env->ReleaseFloatArrayElements(azumArray, azim, 0);
    env->ReleaseIntArrayElements(maskArray, mask, 0);
    return num_svs;
}

static jint android_location_GpsLocationProvider_read_nmea(JNIEnv* env, jobject obj, jint index, jbyteArray nmeaArray, jint buffer_size)
{
    // this should only be called from within a call to reportNmea, so we don't need to lock here

    jbyte* nmea = env->GetByteArrayElements(nmeaArray, 0);

    int length = strlen(sNmeaBufferCopy[index].nmea);
    if (length > buffer_size)
        length = buffer_size;
    memcpy(nmea, sNmeaBufferCopy[index].nmea, length);

    env->ReleaseByteArrayElements(nmeaArray, nmea, 0);
    return length;
}

static void android_location_GpsLocationProvider_inject_time(JNIEnv* env, jobject obj, jlong time, 
        jlong timeReference, jint uncertainty)
{
    sGpsInterface->inject_time(time, timeReference, uncertainty);
}

static void android_location_GpsLocationProvider_inject_location(JNIEnv* env, jobject obj,
        jdouble latitude, jdouble longitude, jfloat accuracy)
{
    sGpsInterface->inject_location(latitude, longitude, accuracy);
}

static jboolean android_location_GpsLocationProvider_supports_xtra(JNIEnv* env, jobject obj)
{
    if (!sGpsXtraInterface) {
        sGpsXtraInterface = (const GpsXtraInterface*)sGpsInterface->get_extension(GPS_XTRA_INTERFACE);
        if (sGpsXtraInterface) {
            int result = sGpsXtraInterface->init(&sGpsXtraCallbacks);
            if (result) {
                sGpsXtraInterface = NULL;
            }
        }
    }

    return (sGpsXtraInterface != NULL);
}

static void android_location_GpsLocationProvider_inject_xtra_data(JNIEnv* env, jobject obj,
        jbyteArray data, jint length)
{
    jbyte* bytes = env->GetByteArrayElements(data, 0);
    sGpsXtraInterface->inject_xtra_data((char *)bytes, length);
    env->ReleaseByteArrayElements(data, bytes, 0);
}

static void android_location_GpsLocationProvider_agps_data_conn_open(JNIEnv* env, jobject obj, jstring apn)
{
    if (!sAGpsInterface) {
        sAGpsInterface = (const AGpsInterface*)sGpsInterface->get_extension(AGPS_INTERFACE);
    }
    if (sAGpsInterface) {
        if (apn == NULL) {
            jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
            return;
        }
        const char *apnStr = env->GetStringUTFChars(apn, NULL);
        sAGpsInterface->data_conn_open(apnStr);
        env->ReleaseStringUTFChars(apn, apnStr);
    }
}

static void android_location_GpsLocationProvider_agps_data_conn_closed(JNIEnv* env, jobject obj)
{
    if (!sAGpsInterface) {
        sAGpsInterface = (const AGpsInterface*)sGpsInterface->get_extension(AGPS_INTERFACE);
    }
    if (sAGpsInterface) {
        sAGpsInterface->data_conn_closed();
    }
}

static void android_location_GpsLocationProvider_agps_data_conn_failed(JNIEnv* env, jobject obj)
{
    if (!sAGpsInterface) {
        sAGpsInterface = (const AGpsInterface*)sGpsInterface->get_extension(AGPS_INTERFACE);
    }
    if (sAGpsInterface) {
        sAGpsInterface->data_conn_failed();
    }
}

static void android_location_GpsLocationProvider_set_agps_server(JNIEnv* env, jobject obj,
        jint type, jstring hostname, jint port)
{
    if (!sAGpsInterface) {
        sAGpsInterface = (const AGpsInterface*)sGpsInterface->get_extension(AGPS_INTERFACE);
    }
    if (sAGpsInterface) {
        const char *c_hostname = env->GetStringUTFChars(hostname, NULL);
        sAGpsInterface->set_server(type, c_hostname, port);
        env->ReleaseStringUTFChars(hostname, c_hostname);
    }
}

static void android_location_GpsLocationProvider_send_ni_response(JNIEnv* env, jobject obj,
      jint notifId, jint response)
{
    if (!sGpsNiInterface)
        sGpsNiInterface = (const GpsNiInterface*)sGpsInterface->get_extension(GPS_NI_INTERFACE);
    if (sGpsNiInterface)
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

static JNINativeMethod sMethods[] = {
     /* name, signature, funcPtr */
    {"class_init_native", "()V", (void *)android_location_GpsLocationProvider_class_init_native},
    {"native_is_supported", "()Z", (void*)android_location_GpsLocationProvider_is_supported},
    {"native_init", "()Z", (void*)android_location_GpsLocationProvider_init},
    {"native_disable", "()V", (void*)android_location_GpsLocationProvider_disable},
    {"native_cleanup", "()V", (void*)android_location_GpsLocationProvider_cleanup},
    {"native_start", "(IZI)Z", (void*)android_location_GpsLocationProvider_start},
    {"native_stop", "()Z", (void*)android_location_GpsLocationProvider_stop},
    {"native_delete_aiding_data", "(I)V", (void*)android_location_GpsLocationProvider_delete_aiding_data},
    {"native_wait_for_event", "()V", (void*)android_location_GpsLocationProvider_wait_for_event},
    {"native_read_sv_status", "([I[F[F[F[I)I", (void*)android_location_GpsLocationProvider_read_sv_status},
    {"native_read_nmea", "(I[BI)I", (void*)android_location_GpsLocationProvider_read_nmea},
    {"native_inject_time", "(JJI)V", (void*)android_location_GpsLocationProvider_inject_time},
    {"native_inject_location", "(DDF)V", (void*)android_location_GpsLocationProvider_inject_location},
    {"native_supports_xtra", "()Z", (void*)android_location_GpsLocationProvider_supports_xtra},
    {"native_inject_xtra_data", "([BI)V", (void*)android_location_GpsLocationProvider_inject_xtra_data},
    {"native_agps_data_conn_open", "(Ljava/lang/String;)V", (void*)android_location_GpsLocationProvider_agps_data_conn_open},
    {"native_agps_data_conn_closed", "()V", (void*)android_location_GpsLocationProvider_agps_data_conn_closed},
    {"native_agps_data_conn_failed", "()V", (void*)android_location_GpsLocationProvider_agps_data_conn_failed},
    {"native_set_agps_server", "(ILjava/lang/String;I)V", (void*)android_location_GpsLocationProvider_set_agps_server},
    {"native_send_ni_response", "(II)V", (void*)android_location_GpsLocationProvider_send_ni_response},
    {"native_get_internal_state", "()Ljava/lang/String;", (void*)android_location_GpsLocationProvider_get_internal_state},
};

int register_android_location_GpsLocationProvider(JNIEnv* env)
{
    return jniRegisterNativeMethods(env, "com/android/internal/location/GpsLocationProvider", sMethods, NELEM(sMethods));
}

} /* namespace android */
