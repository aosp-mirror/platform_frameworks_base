/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the license at
 *
 *      http://www.apache.org/license/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the license.
 */

#define LOG_TAG "FlpHardwareProvider"
#define LOG_NDEBUG  0

#define WAKE_LOCK_NAME  "FLP"
#define LOCATION_CLASS_NAME "android/location/Location"

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"
#include "android_runtime/Log.h"
#include "hardware/fused_location.h"
#include "hardware_legacy/power.h"

static jobject sCallbacksObj = NULL;
static JNIEnv *sCallbackEnv = NULL;
static hw_device_t* sHardwareDevice = NULL;

static jmethodID sSetVersion = NULL;
static jmethodID sOnLocationReport = NULL;
static jmethodID sOnDataReport = NULL;
static jmethodID sOnBatchingCapabilities = NULL;
static jmethodID sOnBatchingStatus = NULL;
static jmethodID sOnGeofenceTransition = NULL;
static jmethodID sOnGeofenceMonitorStatus = NULL;
static jmethodID sOnGeofenceAdd = NULL;
static jmethodID sOnGeofenceRemove = NULL;
static jmethodID sOnGeofencePause = NULL;
static jmethodID sOnGeofenceResume = NULL;
static jmethodID sOnGeofencingCapabilities = NULL;

static const FlpLocationInterface* sFlpInterface = NULL;
static const FlpDiagnosticInterface* sFlpDiagnosticInterface = NULL;
static const FlpGeofencingInterface* sFlpGeofencingInterface = NULL;
static const FlpDeviceContextInterface* sFlpDeviceContextInterface = NULL;

namespace android {

static inline void CheckExceptions(JNIEnv* env, const char* methodName) {
  if(!env->ExceptionCheck()) {
    return;
  }

  ALOGE("An exception was thrown by '%s'.", methodName);
  LOGE_EX(env);
  env->ExceptionClear();
}

static inline void ThrowOnError(
    JNIEnv* env,
    int resultCode,
    const char* methodName) {
  if(resultCode == FLP_RESULT_SUCCESS) {
    return;
  }

  ALOGE("Error %d in '%s'", resultCode, methodName);
  // TODO: this layer needs to be refactored to return error codes to Java
  // raising a FatalError is harsh, and because FLP Hardware Provider is loaded inside the system
  // service, it can cause the device to reboot, or remain in a reboot loop
  // a simple exception is still dumped to logcat, but it is handled more gracefully
  jclass exceptionClass = env->FindClass("java/lang/RuntimeException");
  env->ThrowNew(exceptionClass, methodName);
}

static bool IsValidCallbackThreadEnvOnly() {
  JNIEnv* env = AndroidRuntime::getJNIEnv();

  if(sCallbackEnv == NULL || sCallbackEnv != env) {
    ALOGE("CallbackThread check fail: env=%p, expected=%p", env, sCallbackEnv);
    return false;
  }

  return true;
}

static bool IsValidCallbackThread() {
  // sCallbacksObject is created when FlpHardwareProvider on Java side is
  // initialized. Sometimes the hardware may call a function before the Java
  // side is ready. In order to prevent a system crash, check whether
  // sCallbacksObj has been created. If not, simply ignore this event from
  // hardware.
  if (sCallbacksObj == NULL) {
    ALOGE("Attempt to use FlpHardwareProvider blocked, because it hasn't been initialized.");
    return false;
  }

  return IsValidCallbackThreadEnvOnly();
}

static void BatchingCapabilitiesCallback(int32_t capabilities) {
  if(!IsValidCallbackThread()) {
    return;
  }

  sCallbackEnv->CallVoidMethod(
      sCallbacksObj,
      sOnBatchingCapabilities,
      capabilities
      );
  CheckExceptions(sCallbackEnv, __FUNCTION__);
}

static void BatchingStatusCallback(int32_t status) {
  if(!IsValidCallbackThread()) {
    return;
  }

  sCallbackEnv->CallVoidMethod(
      sCallbacksObj,
      sOnBatchingStatus,
      status
      );
  CheckExceptions(sCallbackEnv, __FUNCTION__);
}

static int SetThreadEvent(ThreadEvent event) {
  JavaVM* javaVm = AndroidRuntime::getJavaVM();

  switch(event) {
    case ASSOCIATE_JVM:
    {
      if(sCallbackEnv != NULL) {
        ALOGE(
            "Attempted to associate callback in '%s'. Callback already associated.",
            __FUNCTION__
            );
        return FLP_RESULT_ERROR;
      }

      JavaVMAttachArgs args = {
          JNI_VERSION_1_6,
          "FLP Service Callback Thread",
          /* group */ NULL
      };

      jint attachResult = javaVm->AttachCurrentThread(&sCallbackEnv, &args);
      if (attachResult != 0) {
        ALOGE("Callback thread attachment error: %d", attachResult);
        return FLP_RESULT_ERROR;
      }

      ALOGV("Callback thread attached: %p", sCallbackEnv);

      // Send the version to the upper layer.
      sCallbackEnv->CallVoidMethod(
            sCallbacksObj,
            sSetVersion,
            sFlpInterface->size == sizeof(FlpLocationInterface) ? 2 : 1
            );
      CheckExceptions(sCallbackEnv, __FUNCTION__);
      break;
    }
    case DISASSOCIATE_JVM:
    {
      if (!IsValidCallbackThreadEnvOnly()) {
        ALOGE(
            "Attempted to dissasociate an unnownk callback thread : '%s'.",
            __FUNCTION__
            );
        return FLP_RESULT_ERROR;
      }

      if (javaVm->DetachCurrentThread() != 0) {
        return FLP_RESULT_ERROR;
      }

      sCallbackEnv = NULL;
      break;
    }
    default:
      ALOGE("Invalid ThreadEvent request %d", event);
      return FLP_RESULT_ERROR;
  }

  return FLP_RESULT_SUCCESS;
}

/*
 * Initializes the FlpHardwareProvider class from the native side by opening
 * the HW module and obtaining the proper interfaces.
 */
static void ClassInit(JNIEnv* env, jclass clazz) {
  sFlpInterface = NULL;

  // get references to the Java provider methods
  sSetVersion = env->GetMethodID(
        clazz,
        "setVersion",
        "(I)V");
  sOnLocationReport = env->GetMethodID(
      clazz,
      "onLocationReport",
      "([Landroid/location/Location;)V");
  sOnDataReport = env->GetMethodID(
      clazz,
      "onDataReport",
      "(Ljava/lang/String;)V"
      );
    sOnBatchingCapabilities = env->GetMethodID(
        clazz,
        "onBatchingCapabilities",
        "(I)V");
    sOnBatchingStatus = env->GetMethodID(
            clazz,
            "onBatchingStatus",
            "(I)V");
    sOnGeofencingCapabilities = env->GetMethodID(
            clazz,
            "onGeofencingCapabilities",
            "(I)V");
  sOnGeofenceTransition = env->GetMethodID(
      clazz,
      "onGeofenceTransition",
      "(ILandroid/location/Location;IJI)V"
      );
  sOnGeofenceMonitorStatus = env->GetMethodID(
      clazz,
      "onGeofenceMonitorStatus",
      "(IILandroid/location/Location;)V"
      );
  sOnGeofenceAdd = env->GetMethodID(clazz, "onGeofenceAdd", "(II)V");
  sOnGeofenceRemove = env->GetMethodID(clazz, "onGeofenceRemove", "(II)V");
  sOnGeofencePause = env->GetMethodID(clazz, "onGeofencePause", "(II)V");
  sOnGeofenceResume = env->GetMethodID(clazz, "onGeofenceResume", "(II)V");

  // open the hardware module
  const hw_module_t* module = NULL;
  int err = hw_get_module(FUSED_LOCATION_HARDWARE_MODULE_ID, &module);
  if (err != 0) {
    ALOGE("Error hw_get_module '%s': %d", FUSED_LOCATION_HARDWARE_MODULE_ID, err);
    return;
  }

  err = module->methods->open(
      module,
      FUSED_LOCATION_HARDWARE_MODULE_ID,
      &sHardwareDevice);
  if (err != 0) {
    ALOGE("Error opening device '%s': %d", FUSED_LOCATION_HARDWARE_MODULE_ID, err);
    return;
  }

  // acquire the interfaces pointers
  flp_device_t* flp_device = reinterpret_cast<flp_device_t*>(sHardwareDevice);
  sFlpInterface = flp_device->get_flp_interface(flp_device);

  if (sFlpInterface != NULL) {
    sFlpDiagnosticInterface = reinterpret_cast<const FlpDiagnosticInterface*>(
        sFlpInterface->get_extension(FLP_DIAGNOSTIC_INTERFACE));

    sFlpGeofencingInterface = reinterpret_cast<const FlpGeofencingInterface*>(
        sFlpInterface->get_extension(FLP_GEOFENCING_INTERFACE));

    sFlpDeviceContextInterface = reinterpret_cast<const FlpDeviceContextInterface*>(
        sFlpInterface->get_extension(FLP_DEVICE_CONTEXT_INTERFACE));
  }
}

/*
 * Helper function to unwrap a java object back into a FlpLocation structure.
 */
static void TranslateFromObject(
    JNIEnv* env,
    jobject locationObject,
    FlpLocation& location) {
  location.size = sizeof(FlpLocation);
  location.flags = 0;

  jclass locationClass = env->GetObjectClass(locationObject);

  jmethodID getLatitude = env->GetMethodID(locationClass, "getLatitude", "()D");
  location.latitude = env->CallDoubleMethod(locationObject, getLatitude);
  jmethodID getLongitude = env->GetMethodID(locationClass, "getLongitude", "()D");
  location.longitude = env->CallDoubleMethod(locationObject, getLongitude);
  jmethodID getTime = env->GetMethodID(locationClass, "getTime", "()J");
  location.timestamp = env->CallLongMethod(locationObject, getTime);
  location.flags |= FLP_LOCATION_HAS_LAT_LONG;

  jmethodID hasAltitude = env->GetMethodID(locationClass, "hasAltitude", "()Z");
  if (env->CallBooleanMethod(locationObject, hasAltitude)) {
    jmethodID getAltitude = env->GetMethodID(locationClass, "getAltitude", "()D");
    location.altitude = env->CallDoubleMethod(locationObject, getAltitude);
    location.flags |= FLP_LOCATION_HAS_ALTITUDE;
  }

  jmethodID hasSpeed = env->GetMethodID(locationClass, "hasSpeed", "()Z");
  if (env->CallBooleanMethod(locationObject, hasSpeed)) {
    jmethodID getSpeed = env->GetMethodID(locationClass, "getSpeed", "()F");
    location.speed = env->CallFloatMethod(locationObject, getSpeed);
    location.flags |= FLP_LOCATION_HAS_SPEED;
  }

  jmethodID hasBearing = env->GetMethodID(locationClass, "hasBearing", "()Z");
  if (env->CallBooleanMethod(locationObject, hasBearing)) {
    jmethodID getBearing = env->GetMethodID(locationClass, "getBearing", "()F");
    location.bearing = env->CallFloatMethod(locationObject, getBearing);
    location.flags |= FLP_LOCATION_HAS_BEARING;
  }

  jmethodID hasAccuracy = env->GetMethodID(locationClass, "hasAccuracy", "()Z");
  if (env->CallBooleanMethod(locationObject, hasAccuracy)) {
    jmethodID getAccuracy = env->GetMethodID(
        locationClass,
        "getAccuracy",
        "()F"
        );
    location.accuracy = env->CallFloatMethod(locationObject, getAccuracy);
    location.flags |= FLP_LOCATION_HAS_ACCURACY;
  }

  // TODO: wire sources_used if Location class exposes them

  env->DeleteLocalRef(locationClass);
}

/*
 * Helper function to unwrap FlpBatchOptions from the Java Runtime calls.
 */
static void TranslateFromObject(
    JNIEnv* env,
    jobject batchOptionsObject,
    FlpBatchOptions& batchOptions) {
  jclass batchOptionsClass = env->GetObjectClass(batchOptionsObject);

  jmethodID getMaxPower = env->GetMethodID(
      batchOptionsClass,
      "getMaxPowerAllocationInMW",
      "()D"
      );
  batchOptions.max_power_allocation_mW = env->CallDoubleMethod(
      batchOptionsObject,
      getMaxPower
      );

  jmethodID getPeriod = env->GetMethodID(
      batchOptionsClass,
      "getPeriodInNS",
      "()J"
      );
  batchOptions.period_ns = env->CallLongMethod(batchOptionsObject, getPeriod);

  jmethodID getSourcesToUse = env->GetMethodID(
      batchOptionsClass,
      "getSourcesToUse",
      "()I"
      );
  batchOptions.sources_to_use = env->CallIntMethod(
      batchOptionsObject,
      getSourcesToUse
      );

  jmethodID getSmallestDisplacementMeters = env->GetMethodID(
      batchOptionsClass,
      "getSmallestDisplacementMeters",
      "()F"
      );
  batchOptions.smallest_displacement_meters
      = env->CallFloatMethod(batchOptionsObject, getSmallestDisplacementMeters);

  jmethodID getFlags = env->GetMethodID(batchOptionsClass, "getFlags", "()I");
  batchOptions.flags = env->CallIntMethod(batchOptionsObject, getFlags);

  env->DeleteLocalRef(batchOptionsClass);
}

/*
 * Helper function to unwrap Geofence structures from the Java Runtime calls.
 */
static void TranslateGeofenceFromGeofenceHardwareRequestParcelable(
    JNIEnv* env,
    jobject geofenceRequestObject,
    Geofence& geofence) {
  jclass geofenceRequestClass = env->GetObjectClass(geofenceRequestObject);

  jmethodID getId = env->GetMethodID(geofenceRequestClass, "getId", "()I");
  geofence.geofence_id = env->CallIntMethod(geofenceRequestObject, getId);

  jmethodID getType = env->GetMethodID(geofenceRequestClass, "getType", "()I");
  // this works because GeofenceHardwareRequest.java and fused_location.h have
  // the same notion of geofence types
  GeofenceType type = (GeofenceType)env->CallIntMethod(geofenceRequestObject, getType);
  if(type != TYPE_CIRCLE) {
    ThrowOnError(env, FLP_RESULT_ERROR, __FUNCTION__);
  }
  geofence.data->type = type;
  GeofenceCircle& circle = geofence.data->geofence.circle;

  jmethodID getLatitude = env->GetMethodID(
      geofenceRequestClass,
      "getLatitude",
      "()D");
  circle.latitude = env->CallDoubleMethod(geofenceRequestObject, getLatitude);

  jmethodID getLongitude = env->GetMethodID(
      geofenceRequestClass,
      "getLongitude",
      "()D");
  circle.longitude = env->CallDoubleMethod(geofenceRequestObject, getLongitude);

  jmethodID getRadius = env->GetMethodID(geofenceRequestClass, "getRadius", "()D");
  circle.radius_m = env->CallDoubleMethod(geofenceRequestObject, getRadius);

  GeofenceOptions* options = geofence.options;
  jmethodID getMonitorTransitions = env->GetMethodID(
      geofenceRequestClass,
      "getMonitorTransitions",
      "()I");
  options->monitor_transitions = env->CallIntMethod(
      geofenceRequestObject,
      getMonitorTransitions);

  jmethodID getUnknownTimer = env->GetMethodID(
      geofenceRequestClass,
      "getUnknownTimer",
      "()I");
  options->unknown_timer_ms = env->CallIntMethod(geofenceRequestObject, getUnknownTimer);

  jmethodID getNotificationResponsiveness = env->GetMethodID(
      geofenceRequestClass,
      "getNotificationResponsiveness",
      "()I");
  options->notification_responsivenes_ms = env->CallIntMethod(
      geofenceRequestObject,
      getNotificationResponsiveness);

  jmethodID getLastTransition = env->GetMethodID(
      geofenceRequestClass,
      "getLastTransition",
      "()I");
  options->last_transition = env->CallIntMethod(geofenceRequestObject, getLastTransition);

  jmethodID getSourceTechnologies =
      env->GetMethodID(geofenceRequestClass, "getSourceTechnologies", "()I");
  options->sources_to_use = env->CallIntMethod(geofenceRequestObject, getSourceTechnologies);

  env->DeleteLocalRef(geofenceRequestClass);
}

/*
 * Helper function to transform FlpLocation into a java object.
 */
static void TranslateToObject(const FlpLocation* location, jobject& locationObject) {
  jclass locationClass = sCallbackEnv->FindClass(LOCATION_CLASS_NAME);
  jmethodID locationCtor = sCallbackEnv->GetMethodID(
      locationClass,
      "<init>",
      "(Ljava/lang/String;)V"
      );

  // the provider is set in the upper JVM layer
  locationObject = sCallbackEnv->NewObject(locationClass, locationCtor, NULL);
  jint flags = location->flags;

  // set the valid information in the object
  if (flags & FLP_LOCATION_HAS_LAT_LONG) {
    jmethodID setLatitude = sCallbackEnv->GetMethodID(
        locationClass,
        "setLatitude",
        "(D)V"
        );
    sCallbackEnv->CallVoidMethod(locationObject, setLatitude, location->latitude);

    jmethodID setLongitude = sCallbackEnv->GetMethodID(
        locationClass,
        "setLongitude",
        "(D)V"
        );
    sCallbackEnv->CallVoidMethod(
        locationObject,
        setLongitude,
        location->longitude
        );

    jmethodID setTime = sCallbackEnv->GetMethodID(
        locationClass,
        "setTime",
        "(J)V"
        );
    sCallbackEnv->CallVoidMethod(locationObject, setTime, location->timestamp);
  }

  if (flags & FLP_LOCATION_HAS_ALTITUDE) {
    jmethodID setAltitude = sCallbackEnv->GetMethodID(
        locationClass,
        "setAltitude",
        "(D)V"
        );
    sCallbackEnv->CallVoidMethod(locationObject, setAltitude, location->altitude);
  }

  if (flags & FLP_LOCATION_HAS_SPEED) {
    jmethodID setSpeed = sCallbackEnv->GetMethodID(
        locationClass,
        "setSpeed",
        "(F)V"
        );
    sCallbackEnv->CallVoidMethod(locationObject, setSpeed, location->speed);
  }

  if (flags & FLP_LOCATION_HAS_BEARING) {
    jmethodID setBearing = sCallbackEnv->GetMethodID(
        locationClass,
        "setBearing",
        "(F)V"
        );
    sCallbackEnv->CallVoidMethod(locationObject, setBearing, location->bearing);
  }

  if (flags & FLP_LOCATION_HAS_ACCURACY) {
    jmethodID setAccuracy = sCallbackEnv->GetMethodID(
        locationClass,
        "setAccuracy",
        "(F)V"
        );
    sCallbackEnv->CallVoidMethod(locationObject, setAccuracy, location->accuracy);
  }

  // TODO: wire FlpLocation::sources_used when needed

  sCallbackEnv->DeleteLocalRef(locationClass);
}

/*
 * Helper function to serialize FlpLocation structures.
 */
static void TranslateToObjectArray(
    int32_t locationsCount,
    FlpLocation** locations,
    jobjectArray& locationsArray) {
  jclass locationClass = sCallbackEnv->FindClass(LOCATION_CLASS_NAME);
  locationsArray = sCallbackEnv->NewObjectArray(
      locationsCount,
      locationClass,
      /* initialElement */ NULL
      );

  for (int i = 0; i < locationsCount; ++i) {
    jobject locationObject = NULL;
    TranslateToObject(locations[i], locationObject);
    sCallbackEnv->SetObjectArrayElement(locationsArray, i, locationObject);
    sCallbackEnv->DeleteLocalRef(locationObject);
  }

  sCallbackEnv->DeleteLocalRef(locationClass);
}

static void LocationCallback(int32_t locationsCount, FlpLocation** locations) {
  if(!IsValidCallbackThread()) {
    return;
  }

  if(locationsCount == 0 || locations == NULL) {
    ALOGE(
        "Invalid LocationCallback. Count: %d, Locations: %p",
        locationsCount,
        locations
        );
    return;
  }

  jobjectArray locationsArray = NULL;
  TranslateToObjectArray(locationsCount, locations, locationsArray);

  sCallbackEnv->CallVoidMethod(
      sCallbacksObj,
      sOnLocationReport,
      locationsArray
      );
  CheckExceptions(sCallbackEnv, __FUNCTION__);

  if(locationsArray != NULL) {
    sCallbackEnv->DeleteLocalRef(locationsArray);
  }
}

static void AcquireWakelock() {
  acquire_wake_lock(PARTIAL_WAKE_LOCK, WAKE_LOCK_NAME);
}

static void ReleaseWakelock() {
  release_wake_lock(WAKE_LOCK_NAME);
}

FlpCallbacks sFlpCallbacks = {
  sizeof(FlpCallbacks),
  LocationCallback,
  AcquireWakelock,
  ReleaseWakelock,
  SetThreadEvent,
  BatchingCapabilitiesCallback,
  BatchingStatusCallback
};

static void ReportData(char* data, int length) {
  jstring stringData = NULL;

  if(length != 0 && data != NULL) {
    stringData = sCallbackEnv->NewString(reinterpret_cast<jchar*>(data), length);
  } else {
    ALOGE("Invalid ReportData callback. Length: %d, Data: %p", length, data);
    return;
  }

  sCallbackEnv->CallVoidMethod(sCallbacksObj, sOnDataReport, stringData);
  CheckExceptions(sCallbackEnv, __FUNCTION__);
}

FlpDiagnosticCallbacks sFlpDiagnosticCallbacks = {
  sizeof(FlpDiagnosticCallbacks),
  SetThreadEvent,
  ReportData
};

static void GeofenceTransitionCallback(
    int32_t geofenceId,
    FlpLocation* location,
    int32_t transition,
    FlpUtcTime timestamp,
    uint32_t sourcesUsed
    ) {
  if(!IsValidCallbackThread()) {
    return;
  }

  if(location == NULL) {
    ALOGE("GeofenceTransition received with invalid location: %p", location);
    return;
  }

  jobject locationObject = NULL;
  TranslateToObject(location, locationObject);

  sCallbackEnv->CallVoidMethod(
      sCallbacksObj,
      sOnGeofenceTransition,
      geofenceId,
      locationObject,
      transition,
      timestamp,
      sourcesUsed
      );
  CheckExceptions(sCallbackEnv, __FUNCTION__);

  if(locationObject != NULL) {
    sCallbackEnv->DeleteLocalRef(locationObject);
  }
}

static void GeofenceMonitorStatusCallback(
    int32_t status,
    uint32_t source,
    FlpLocation* lastLocation) {
  if(!IsValidCallbackThread()) {
    return;
  }

  jobject locationObject = NULL;
  if(lastLocation != NULL) {
    TranslateToObject(lastLocation, locationObject);
  }

  sCallbackEnv->CallVoidMethod(
      sCallbacksObj,
      sOnGeofenceMonitorStatus,
      status,
      source,
      locationObject
      );
  CheckExceptions(sCallbackEnv, __FUNCTION__);

  if(locationObject != NULL) {
    sCallbackEnv->DeleteLocalRef(locationObject);
  }
}

static void GeofenceAddCallback(int32_t geofenceId, int32_t result) {
  if(!IsValidCallbackThread()) {
    return;
  }

  sCallbackEnv->CallVoidMethod(sCallbacksObj, sOnGeofenceAdd, geofenceId, result);
  CheckExceptions(sCallbackEnv, __FUNCTION__);
}

static void GeofenceRemoveCallback(int32_t geofenceId, int32_t result) {
  if(!IsValidCallbackThread()) {
    return;
  }

  sCallbackEnv->CallVoidMethod(
      sCallbacksObj,
      sOnGeofenceRemove,
      geofenceId,
      result
      );
  CheckExceptions(sCallbackEnv, __FUNCTION__);
}

static void GeofencePauseCallback(int32_t geofenceId, int32_t result) {
  if(!IsValidCallbackThread()) {
    return;
  }

  sCallbackEnv->CallVoidMethod(
      sCallbacksObj,
      sOnGeofencePause,
      geofenceId,
      result
      );
  CheckExceptions(sCallbackEnv, __FUNCTION__);
}

static void GeofenceResumeCallback(int32_t geofenceId, int32_t result) {
  if(!IsValidCallbackThread()) {
    return;
  }

  sCallbackEnv->CallVoidMethod(
      sCallbacksObj,
      sOnGeofenceResume,
      geofenceId,
      result
      );
  CheckExceptions(sCallbackEnv, __FUNCTION__);
}

static void GeofencingCapabilitiesCallback(int32_t capabilities) {
  if(!IsValidCallbackThread()) {
    return;
  }

  sCallbackEnv->CallVoidMethod(
      sCallbacksObj,
      sOnGeofencingCapabilities,
      capabilities
      );
  CheckExceptions(sCallbackEnv, __FUNCTION__);
}

FlpGeofenceCallbacks sFlpGeofenceCallbacks = {
  sizeof(FlpGeofenceCallbacks),
  GeofenceTransitionCallback,
  GeofenceMonitorStatusCallback,
  GeofenceAddCallback,
  GeofenceRemoveCallback,
  GeofencePauseCallback,
  GeofenceResumeCallback,
  SetThreadEvent,
  GeofencingCapabilitiesCallback
};

/*
 * Initializes the Fused Location Provider in the native side. It ensures that
 * the Flp interfaces are initialized properly.
 */
static void Init(JNIEnv* env, jobject obj) {
  if(sCallbacksObj == NULL) {
    sCallbacksObj = env->NewGlobalRef(obj);
  }

  // initialize the Flp interfaces
  if(sFlpInterface == NULL || sFlpInterface->init(&sFlpCallbacks) != 0) {
    ThrowOnError(env, FLP_RESULT_ERROR, __FUNCTION__);
  }

  if(sFlpDiagnosticInterface != NULL) {
    sFlpDiagnosticInterface->init(&sFlpDiagnosticCallbacks);
  }

  if(sFlpGeofencingInterface != NULL) {
    sFlpGeofencingInterface->init(&sFlpGeofenceCallbacks);
  }

  // TODO: inject any device context if when needed
}

static jboolean IsSupported(JNIEnv* /* env */, jclass /* clazz */) {
  if (sFlpInterface == NULL) {
    return JNI_FALSE;
  }
  return JNI_TRUE;
}

static jint GetBatchSize(JNIEnv* env, jobject /* object */) {
  if(sFlpInterface == NULL) {
    ThrowOnError(env, FLP_RESULT_ERROR, __FUNCTION__);
  }

  return sFlpInterface->get_batch_size();
}

static void StartBatching(
    JNIEnv* env,
    jobject /* object */,
    jint id,
    jobject optionsObject) {
  if(sFlpInterface == NULL || optionsObject == NULL) {
    ThrowOnError(env, FLP_RESULT_ERROR, __FUNCTION__);
  }

  FlpBatchOptions options;
  TranslateFromObject(env, optionsObject, options);
  int result = sFlpInterface->start_batching(id, &options);
  ThrowOnError(env, result, __FUNCTION__);
}

static void UpdateBatchingOptions(
    JNIEnv* env,
    jobject /* object */,
    jint id,
    jobject optionsObject) {
  if(sFlpInterface == NULL || optionsObject == NULL) {
    ThrowOnError(env, FLP_RESULT_ERROR, __FUNCTION__);
  }

  FlpBatchOptions options;
  TranslateFromObject(env, optionsObject, options);
  int result = sFlpInterface->update_batching_options(id, &options);
  ThrowOnError(env, result, __FUNCTION__);
}

static void StopBatching(JNIEnv* env, jobject /* object */, jint id) {
  if(sFlpInterface == NULL) {
    ThrowOnError(env, FLP_RESULT_ERROR, __FUNCTION__);
  }

  sFlpInterface->stop_batching(id);
}

static void Cleanup(JNIEnv* env, jobject /* object */) {
  if(sFlpInterface == NULL) {
    ThrowOnError(env, FLP_RESULT_ERROR, __FUNCTION__);
  }

  sFlpInterface->cleanup();

  if(sCallbacksObj != NULL) {
    env->DeleteGlobalRef(sCallbacksObj);
    sCallbacksObj = NULL;
  }
}

static void GetBatchedLocation(JNIEnv* env, jobject /* object */, jint lastNLocations) {
  if(sFlpInterface == NULL) {
    ThrowOnError(env, FLP_RESULT_ERROR, __FUNCTION__);
  }

  sFlpInterface->get_batched_location(lastNLocations);
}

static void FlushBatchedLocations(JNIEnv* env, jobject /* object */) {
  if(sFlpInterface == NULL) {
    ThrowOnError(env, FLP_RESULT_ERROR, __FUNCTION__);
  }

  sFlpInterface->flush_batched_locations();
}

static void InjectLocation(JNIEnv* env, jobject /* object */, jobject locationObject) {
  if(locationObject == NULL) {
    ALOGE("Invalid location for injection: %p", locationObject);
    ThrowOnError(env, FLP_RESULT_ERROR, __FUNCTION__);
  }

  if(sFlpInterface == NULL) {
    // there is no listener, bail
    return;
  }

  FlpLocation location;
  TranslateFromObject(env, locationObject, location);
  int result = sFlpInterface->inject_location(&location);
  if (result != FLP_RESULT_SUCCESS) {
    // do not throw but log, this operation should be fire and forget
    ALOGE("Error %d in '%s'", result, __FUNCTION__);
  }
}

static jboolean IsDiagnosticSupported() {
  return sFlpDiagnosticInterface != NULL;
}

static void InjectDiagnosticData(JNIEnv* env, jobject /* object */, jstring stringData) {
  if(stringData == NULL) {
    ALOGE("Invalid diagnostic data for injection: %p", stringData);
    ThrowOnError(env, FLP_RESULT_ERROR, __FUNCTION__);
  }

  if(sFlpDiagnosticInterface == NULL) {
    ThrowOnError(env, FLP_RESULT_ERROR, __FUNCTION__);
  }

  int length = env->GetStringLength(stringData);
  const jchar* data = env->GetStringChars(stringData, /* isCopy */ NULL);
  if(data == NULL) {
    ThrowOnError(env, FLP_RESULT_ERROR, __FUNCTION__);
  }

  int result = sFlpDiagnosticInterface->inject_data((char*) data, length);
  ThrowOnError(env, result, __FUNCTION__);
}

static jboolean IsDeviceContextSupported() {
  return sFlpDeviceContextInterface != NULL;
}

static void InjectDeviceContext(JNIEnv* env, jobject /* object */, jint enabledMask) {
  if(sFlpDeviceContextInterface == NULL) {
    ThrowOnError(env, FLP_RESULT_ERROR, __FUNCTION__);
  }

  int result = sFlpDeviceContextInterface->inject_device_context(enabledMask);
  ThrowOnError(env, result, __FUNCTION__);
}

static jboolean IsGeofencingSupported() {
  return sFlpGeofencingInterface != NULL;
}

static void AddGeofences(
    JNIEnv* env,
    jobject /* object */,
    jobjectArray geofenceRequestsArray) {
  if(geofenceRequestsArray == NULL) {
    ALOGE("Invalid Geofences to add: %p", geofenceRequestsArray);
    ThrowOnError(env, FLP_RESULT_ERROR, __FUNCTION__);
  }

  if (sFlpGeofencingInterface == NULL) {
    ThrowOnError(env, FLP_RESULT_ERROR, __FUNCTION__);
  }

  jint geofenceRequestsCount = env->GetArrayLength(geofenceRequestsArray);
  if(geofenceRequestsCount == 0) {
    return;
  }

  Geofence* geofences = new Geofence[geofenceRequestsCount];
  if (geofences == NULL) {
    ThrowOnError(env, FLP_RESULT_INSUFFICIENT_MEMORY, __FUNCTION__);
  }

  for (int i = 0; i < geofenceRequestsCount; ++i) {
    geofences[i].data = new GeofenceData();
    geofences[i].options = new GeofenceOptions();
    jobject geofenceObject = env->GetObjectArrayElement(geofenceRequestsArray, i);

    TranslateGeofenceFromGeofenceHardwareRequestParcelable(env, geofenceObject, geofences[i]);
    env->DeleteLocalRef(geofenceObject);
  }

  sFlpGeofencingInterface->add_geofences(geofenceRequestsCount, &geofences);
  if (geofences != NULL) {
    for(int i = 0; i < geofenceRequestsCount; ++i) {
      delete geofences[i].data;
      delete geofences[i].options;
    }
    delete[] geofences;
  }
}

static void PauseGeofence(JNIEnv* env, jobject /* object */, jint geofenceId) {
  if(sFlpGeofencingInterface == NULL) {
    ThrowOnError(env, FLP_RESULT_ERROR, __FUNCTION__);
  }

  sFlpGeofencingInterface->pause_geofence(geofenceId);
}

static void ResumeGeofence(
    JNIEnv* env,
    jobject /* object */,
    jint geofenceId,
    jint monitorTransitions) {
  if(sFlpGeofencingInterface == NULL) {
    ThrowOnError(env, FLP_RESULT_ERROR, __FUNCTION__);
  }

  sFlpGeofencingInterface->resume_geofence(geofenceId, monitorTransitions);
}

static void ModifyGeofenceOption(
    JNIEnv* env,
    jobject /* object */,
    jint geofenceId,
    jint lastTransition,
    jint monitorTransitions,
    jint notificationResponsiveness,
    jint unknownTimer,
    jint sourcesToUse) {
  if(sFlpGeofencingInterface == NULL) {
    ThrowOnError(env, FLP_RESULT_ERROR, __FUNCTION__);
  }

  GeofenceOptions options = {
      lastTransition,
      monitorTransitions,
      notificationResponsiveness,
      unknownTimer,
      (uint32_t)sourcesToUse
  };

  sFlpGeofencingInterface->modify_geofence_option(geofenceId, &options);
}

static void RemoveGeofences(
    JNIEnv* env,
    jobject /* object */,
    jintArray geofenceIdsArray) {
  if(sFlpGeofencingInterface == NULL) {
    ThrowOnError(env, FLP_RESULT_ERROR, __FUNCTION__);
  }

  jsize geofenceIdsCount = env->GetArrayLength(geofenceIdsArray);
  jint* geofenceIds = env->GetIntArrayElements(geofenceIdsArray, /* isCopy */ NULL);
  if(geofenceIds == NULL) {
    ThrowOnError(env, FLP_RESULT_ERROR, __FUNCTION__);
  }

  sFlpGeofencingInterface->remove_geofences(geofenceIdsCount, geofenceIds);
  env->ReleaseIntArrayElements(geofenceIdsArray, geofenceIds, 0 /*mode*/);
}

static const JNINativeMethod sMethods[] = {
  //{"name", "signature", functionPointer }
  {"nativeClassInit", "()V", reinterpret_cast<void*>(ClassInit)},
  {"nativeInit", "()V", reinterpret_cast<void*>(Init)},
  {"nativeCleanup", "()V", reinterpret_cast<void*>(Cleanup)},
  {"nativeIsSupported", "()Z", reinterpret_cast<void*>(IsSupported)},
  {"nativeGetBatchSize", "()I", reinterpret_cast<void*>(GetBatchSize)},
  {"nativeStartBatching",
        "(ILandroid/location/FusedBatchOptions;)V",
        reinterpret_cast<void*>(StartBatching)},
  {"nativeUpdateBatchingOptions",
        "(ILandroid/location/FusedBatchOptions;)V",
        reinterpret_cast<void*>(UpdateBatchingOptions)},
  {"nativeStopBatching", "(I)V", reinterpret_cast<void*>(StopBatching)},
  {"nativeRequestBatchedLocation",
        "(I)V",
        reinterpret_cast<void*>(GetBatchedLocation)},
  {"nativeFlushBatchedLocations",
          "()V",
          reinterpret_cast<void*>(FlushBatchedLocations)},
  {"nativeInjectLocation",
        "(Landroid/location/Location;)V",
        reinterpret_cast<void*>(InjectLocation)},
  {"nativeIsDiagnosticSupported",
        "()Z",
        reinterpret_cast<void*>(IsDiagnosticSupported)},
  {"nativeInjectDiagnosticData",
        "(Ljava/lang/String;)V",
        reinterpret_cast<void*>(InjectDiagnosticData)},
  {"nativeIsDeviceContextSupported",
        "()Z",
        reinterpret_cast<void*>(IsDeviceContextSupported)},
  {"nativeInjectDeviceContext",
        "(I)V",
        reinterpret_cast<void*>(InjectDeviceContext)},
  {"nativeIsGeofencingSupported",
        "()Z",
        reinterpret_cast<void*>(IsGeofencingSupported)},
  {"nativeAddGeofences",
        "([Landroid/hardware/location/GeofenceHardwareRequestParcelable;)V",
        reinterpret_cast<void*>(AddGeofences)},
  {"nativePauseGeofence", "(I)V", reinterpret_cast<void*>(PauseGeofence)},
  {"nativeResumeGeofence", "(II)V", reinterpret_cast<void*>(ResumeGeofence)},
  {"nativeModifyGeofenceOption",
        "(IIIIII)V",
        reinterpret_cast<void*>(ModifyGeofenceOption)},
  {"nativeRemoveGeofences", "([I)V", reinterpret_cast<void*>(RemoveGeofences)}
};

/*
 * Registration method invoked on JNI Load.
 */
int register_android_server_location_FlpHardwareProvider(JNIEnv* env) {
  return jniRegisterNativeMethods(
      env,
      "com/android/server/location/FlpHardwareProvider",
      sMethods,
      NELEM(sMethods)
      );
}

} /* name-space Android */
