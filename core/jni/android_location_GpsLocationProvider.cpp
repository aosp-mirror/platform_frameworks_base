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

#include "JNIHelp.h"
#include "jni.h"
#include "hardware_legacy/gps.h"
#include "utils/Log.h"
#include "utils/misc.h"

#include <string.h>
#include <pthread.h>


static pthread_mutex_t sEventMutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t sEventCond = PTHREAD_COND_INITIALIZER;
static jmethodID method_reportLocation;
static jmethodID method_reportStatus;
static jmethodID method_reportSvStatus;
static jmethodID method_xtraDownloadRequest;

static const GpsInterface* sGpsInterface = NULL;
static const GpsXtraInterface* sGpsXtraInterface = NULL;
static const GpsSuplInterface* sGpsSuplInterface = NULL;

// data written to by GPS callbacks
static GpsLocation  sGpsLocation;
static GpsStatus    sGpsStatus;
static GpsSvStatus  sGpsSvStatus;

// a copy of the data shared by android_location_GpsLocationProvider_wait_for_event
// and android_location_GpsLocationProvider_read_status
static GpsLocation  sGpsLocationCopy;
static GpsStatus    sGpsStatusCopy;
static GpsSvStatus  sGpsSvStatusCopy;

enum CallbackType {
    kLocation = 1,
    kStatus = 2,
    kSvStatus = 4,
    kXtraDownloadRequest = 8,
    kDisableRequest = 16,
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

GpsCallbacks sGpsCallbacks = {
    location_callback,
    status_callback,
    sv_status_callback,
};

static void
download_request_callback()
{
    pthread_mutex_lock(&sEventMutex);
    sPendingCallbacks |= kXtraDownloadRequest;
    pthread_cond_signal(&sEventCond);
    pthread_mutex_unlock(&sEventMutex);
}

GpsXtraCallbacks sGpsXtraCallbacks = {
    download_request_callback,
};


static void android_location_GpsLocationProvider_class_init_native(JNIEnv* env, jclass clazz) {
    method_reportLocation = env->GetMethodID(clazz, "reportLocation", "(IDDDFFFJ)V");
    method_reportStatus = env->GetMethodID(clazz, "reportStatus", "(I)V");
    method_reportSvStatus = env->GetMethodID(clazz, "reportSvStatus", "()V");
    method_xtraDownloadRequest = env->GetMethodID(clazz, "xtraDownloadRequest", "()V");
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
    return (sGpsInterface && sGpsInterface->init(&sGpsCallbacks) == 0);
}

static void android_location_GpsLocationProvider_disable(JNIEnv* env, jobject obj)
{
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
        return result;
    }

    return (sGpsInterface->start() == 0);
}

static jboolean android_location_GpsLocationProvider_stop(JNIEnv* env, jobject obj)
{
    return (sGpsInterface->stop() == 0);
}

static void android_location_GpsLocationProvider_set_fix_frequency(JNIEnv* env, jobject obj, jint fixFrequency)
{
    if (sGpsInterface->set_fix_frequency)
        sGpsInterface->set_fix_frequency(fixFrequency);
}

static void android_location_GpsLocationProvider_delete_aiding_data(JNIEnv* env, jobject obj, jint flags)
{
    sGpsInterface->delete_aiding_data(flags);
}

static void android_location_GpsLocationProvider_wait_for_event(JNIEnv* env, jobject obj)
{
    pthread_mutex_lock(&sEventMutex);
    pthread_cond_wait(&sEventCond, &sEventMutex);
    
    // copy and clear the callback flags
    int pendingCallbacks = sPendingCallbacks;
    sPendingCallbacks = 0;
    
    // copy everything and unlock the mutex before calling into Java code to avoid the possibility
    // of timeouts in the GPS engine.
    memcpy(&sGpsLocationCopy, &sGpsLocation, sizeof(sGpsLocationCopy));
    memcpy(&sGpsStatusCopy, &sGpsStatus, sizeof(sGpsStatusCopy));
    memcpy(&sGpsSvStatusCopy, &sGpsSvStatus, sizeof(sGpsSvStatusCopy));
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
    if (pendingCallbacks & kXtraDownloadRequest) {    
        env->CallVoidMethod(obj, method_xtraDownloadRequest);
    }
    if (pendingCallbacks & kDisableRequest) {
        // don't need to do anything - we are just poking so wait_for_event will return.
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

static void android_location_GpsLocationProvider_inject_time(JNIEnv* env, jobject obj, jlong time, 
        jlong timeReference, jint uncertainty)
{
    sGpsInterface->inject_time(time, timeReference, uncertainty);
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

static void android_location_GpsLocationProvider_set_supl_server(JNIEnv* env, jobject obj,
        jint addr, jint port)
{
    if (!sGpsSuplInterface) {
        sGpsSuplInterface = (const GpsSuplInterface*)sGpsInterface->get_extension(GPS_SUPL_INTERFACE);
    }
    if (sGpsSuplInterface) {
        sGpsSuplInterface->set_server(addr, port);
    }
}

static void android_location_GpsLocationProvider_set_supl_apn(JNIEnv* env, jobject obj, jstring apn)
{
    if (!sGpsSuplInterface) {
        sGpsSuplInterface = (const GpsSuplInterface*)sGpsInterface->get_extension(GPS_SUPL_INTERFACE);
    }
    if (sGpsSuplInterface) {
        if (apn == NULL) {
            jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
            return;
        }
        const char *apnStr = env->GetStringUTFChars(apn, NULL);
        sGpsSuplInterface->set_apn(apnStr);
        env->ReleaseStringUTFChars(apn, apnStr);
    }
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
	{"native_set_fix_frequency", "(I)V", (void*)android_location_GpsLocationProvider_set_fix_frequency},
	{"native_delete_aiding_data", "(I)V", (void*)android_location_GpsLocationProvider_delete_aiding_data},
	{"native_wait_for_event", "()V", (void*)android_location_GpsLocationProvider_wait_for_event},
	{"native_read_sv_status", "([I[F[F[F[I)I", (void*)android_location_GpsLocationProvider_read_sv_status},
	{"native_inject_time", "(JJI)V", (void*)android_location_GpsLocationProvider_inject_time},
	{"native_supports_xtra", "()Z", (void*)android_location_GpsLocationProvider_supports_xtra},
	{"native_inject_xtra_data", "([BI)V", (void*)android_location_GpsLocationProvider_inject_xtra_data},
 	{"native_set_supl_server", "(II)V", (void*)android_location_GpsLocationProvider_set_supl_server},
 	{"native_set_supl_apn", "(Ljava/lang/String;)V", (void*)android_location_GpsLocationProvider_set_supl_apn},
};

int register_android_location_GpsLocationProvider(JNIEnv* env)
{
    return jniRegisterNativeMethods(env, "com/android/internal/location/GpsLocationProvider", sMethods, NELEM(sMethods));
}

} /* namespace android */
