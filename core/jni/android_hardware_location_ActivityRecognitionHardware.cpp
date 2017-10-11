/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "ActivityRecognitionHardware"

#include <jni.h>
#include <nativehelper/JNIHelp.h>

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>

// #include <hardware/activity_recognition.h>
// The activity recognition HAL is being deprecated. This means -
//    i) Android framework code shall not depend on activity recognition
//       being provided through the activity_recognition.h interface.
//   ii) activity recognition HAL will not be binderized as the other HALs.
//

/**
 * Initializes the ActivityRecognitionHardware class from the native side.
 */
static void class_init(JNIEnv* /*env*/, jclass /*clazz*/) {
    ALOGE("activity_recognition HAL is deprecated. %s is effectively a no-op",
          __FUNCTION__);
}

/**
 * Initializes and connect the callbacks handlers in the HAL.
 */
static void initialize(JNIEnv* /*env*/, jobject /*obj*/) {
    ALOGE("activity_recognition HAL is deprecated. %s is effectively a no-op",
          __FUNCTION__);
}

/**
 * De-initializes the ActivityRecognitionHardware from the native side.
 */
static void release(JNIEnv* /*env*/, jobject /*obj*/) {
    ALOGE("activity_recognition HAL is deprecated. %s is effectively a no-op",
          __FUNCTION__);
}

/**
 * Returns true if ActivityRecognition HAL is supported, false otherwise.
 */
static jboolean is_supported(JNIEnv* /*env*/, jclass /*clazz*/) {
    ALOGE("activity_recognition HAL is deprecated. %s is effectively a no-op",
          __FUNCTION__);
    return JNI_FALSE;
}

/**
 * Gets an array representing the supported activities.
 */
static jobjectArray get_supported_activities(JNIEnv* /*env*/, jobject /*obj*/) {
    ALOGE("activity_recognition HAL is deprecated. %s is effectively a no-op",
          __FUNCTION__);
    return NULL;
}

/**
 * Enables a given activity event to be actively monitored.
 */
static int enable_activity_event(
        JNIEnv* /*env*/,
        jobject /*obj*/,
        jint /*activity_handle*/,
        jint /*event_type*/,
        jlong /*report_latency_ns*/) {
    ALOGE("activity_recognition HAL is deprecated. %s is effectively a no-op",
          __FUNCTION__);
    return android::NO_INIT;
}

/**
 * Disables a given activity event from being actively monitored.
 */
static int disable_activity_event(
        JNIEnv* /*env*/,
        jobject /*obj*/,
        jint /*activity_handle*/,
        jint /*event_type*/) {
    ALOGE("activity_recognition HAL is deprecated. %s is effectively a no-op",
          __FUNCTION__);
    return android::NO_INIT;
}

/**
 * Request flush for al batch buffers.
 */
static int flush(JNIEnv* /*env*/, jobject /*obj*/) {
    ALOGE("activity_recognition HAL is deprecated. %s is effectively a no-op",
          __FUNCTION__);
    return android::NO_INIT;
}


static const JNINativeMethod sMethods[] = {
    // {"name", "signature", (void*) functionPointer },
    { "nativeClassInit", "()V", (void*) class_init },
    { "nativeInitialize", "()V", (void*) initialize },
    { "nativeRelease", "()V", (void*) release },
    { "nativeIsSupported", "()Z", (void*) is_supported },
    { "nativeGetSupportedActivities", "()[Ljava/lang/String;", (void*) get_supported_activities },
    { "nativeEnableActivityEvent", "(IIJ)I", (void*) enable_activity_event },
    { "nativeDisableActivityEvent", "(II)I", (void*) disable_activity_event },
    { "nativeFlush", "()I", (void*) flush },
};

/**
 * Registration method invoked in JNI load.
 */
int register_android_hardware_location_ActivityRecognitionHardware(JNIEnv* env) {
    return jniRegisterNativeMethods(
            env,
            "android/hardware/location/ActivityRecognitionHardware",
            sMethods,
            NELEM(sMethods));
}
