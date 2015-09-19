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
#include <JNIHelp.h>

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>

#include "activity_recognition.h"


// keep base connection data from the HAL
static activity_recognition_module_t* sModule = NULL;
static activity_recognition_device_t* sDevice = NULL;

static jobject sCallbacksObject = NULL;
static jmethodID sOnActivityChanged = NULL;


static void check_and_clear_exceptions(JNIEnv* env, const char* method_name) {
    if (!env->ExceptionCheck()) {
        return;
    }

    ALOGE("An exception was thrown by '%s'.", method_name);
    LOGE_EX(env);
    env->ExceptionClear();
}

static jint attach_thread(JNIEnv** env) {
    JavaVM* java_vm = android::AndroidRuntime::getJavaVM();
    assert(java_vm != NULL);

    JavaVMAttachArgs args = {
        JNI_VERSION_1_6,
        "ActivityRecognition HAL callback.",
        NULL /* group */
    };

    jint result = java_vm->AttachCurrentThread(env, &args);
    if (result != JNI_OK) {
        ALOGE("Attach to callback thread failed: %d", result);
    }

    return result;
}

static jint detach_thread() {
    JavaVM* java_vm = android::AndroidRuntime::getJavaVM();
    assert(java_vm != NULL);

    jint result = java_vm->DetachCurrentThread();
    if (result != JNI_OK) {
        ALOGE("Detach of callback thread failed: %d", result);
    }

    return result;
}


/**
 * Handle activity recognition events from HAL.
 */
static void activity_callback(
        const activity_recognition_callback_procs_t* procs,
        const activity_event_t* events,
        int count) {
    if (sOnActivityChanged == NULL) {
        ALOGE("Dropping activity_callback because onActivityChanged handler is null.");
        return;
    }

    if (events == NULL || count <= 0) {
        ALOGE("Invalid activity_callback. Count: %d, Events: %p", count, events);
        return;
    }

    JNIEnv* env = NULL;
    int result = attach_thread(&env);
    if (result != JNI_OK) {
        ALOGE("Unable to attach thread with JNI.");
        return;
    }

    jclass event_class =
            env->FindClass("android/hardware/location/ActivityRecognitionHardware$Event");
    jmethodID event_ctor = env->GetMethodID(event_class, "<init>", "()V");
    jfieldID activity_field = env->GetFieldID(event_class, "activity", "I");
    jfieldID type_field = env->GetFieldID(event_class, "type", "I");
    jfieldID timestamp_field = env->GetFieldID(event_class, "timestamp", "J");

    jobjectArray events_array = env->NewObjectArray(count, event_class, NULL);
    for (int i = 0; i < count; ++i) {
        const activity_event_t* event = &events[i];
        jobject event_object = env->NewObject(event_class, event_ctor);
        env->SetIntField(event_object, activity_field, event->activity);
        env->SetIntField(event_object, type_field, event->event_type);
        env->SetLongField(event_object, timestamp_field, event->timestamp);
        env->SetObjectArrayElement(events_array, i, event_object);
        env->DeleteLocalRef(event_object);
    }

    env->CallVoidMethod(sCallbacksObject, sOnActivityChanged, events_array);
    check_and_clear_exceptions(env, __FUNCTION__);

    // TODO: ideally we'd let the HAL register the callback thread only once
    detach_thread();
}

activity_recognition_callback_procs_t sCallbacks = {
    activity_callback,
};

/**
 * Initializes the ActivityRecognitionHardware class from the native side.
 */
static void class_init(JNIEnv* env, jclass clazz) {
    // open the hardware module
    int error = hw_get_module(
            ACTIVITY_RECOGNITION_HARDWARE_MODULE_ID,
            (const hw_module_t**) &sModule);
    if (error != 0) {
        ALOGE("Error hw_get_module: %d", error);
        return;
    }

    error = activity_recognition_open(&sModule->common, &sDevice);
    if (error != 0) {
        ALOGE("Error opening device: %d", error);
        return;
    }

    // get references to the Java provided methods
    sOnActivityChanged = env->GetMethodID(
            clazz,
            "onActivityChanged",
            "([Landroid/hardware/location/ActivityRecognitionHardware$Event;)V");
    if (sOnActivityChanged == NULL) {
        ALOGE("Error obtaining ActivityChanged callback.");
        return;
    }

    // register callbacks
    sDevice->register_activity_callback(sDevice, &sCallbacks);
}

/**
 * Initializes and connect the callbacks handlers in the HAL.
 */
static void initialize(JNIEnv* env, jobject obj) {
    if (sCallbacksObject == NULL) {
        sCallbacksObject = env->NewGlobalRef(obj);
    } else {
        ALOGD("Callbacks Object was already initialized.");
    }

    if (sDevice != NULL) {
        sDevice->register_activity_callback(sDevice, &sCallbacks);
    } else {
        ALOGD("ActivityRecognition device not found during initialization.");
    }
}

/**
 * De-initializes the ActivityRecognitionHardware from the native side.
 */
static void release(JNIEnv* env, jobject obj) {
    if (sDevice == NULL) {
        return;
    }

    int error = activity_recognition_close(sDevice);
    if (error != 0) {
        ALOGE("Error closing device: %d", error);
        return;
    }
}

/**
 * Returns true if ActivityRecognition HAL is supported, false otherwise.
 */
static jboolean is_supported(JNIEnv* env, jclass clazz) {
    if (sModule != NULL && sDevice != NULL ) {
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

/**
 * Gets an array representing the supported activities.
 */
static jobjectArray get_supported_activities(JNIEnv* env, jobject obj) {
    if (sModule == NULL) {
        return NULL;
    }

    char const* const* list = NULL;
    int list_size = sModule->get_supported_activities_list(sModule, &list);
    if (list_size <= 0 || list == NULL) {
        return NULL;
    }

    jclass string_class = env->FindClass("java/lang/String");
    if (string_class == NULL) {
        ALOGE("Unable to find String class for supported activities.");
        return NULL;
    }

    jobjectArray string_array = env->NewObjectArray(list_size, string_class, NULL);
    if (string_array == NULL) {
        ALOGE("Unable to create string array for supported activities.");
        return NULL;
    }

    for (int i = 0; i < list_size; ++i) {
        const char* string_ptr = const_cast<const char*>(list[i]);
        jstring string = env->NewStringUTF(string_ptr);
        env->SetObjectArrayElement(string_array, i, string);
    }

    return string_array;
}

/**
 * Enables a given activity event to be actively monitored.
 */
static int enable_activity_event(
        JNIEnv* env,
        jobject obj,
        jint activity_handle,
        jint event_type,
        jlong report_latency_ns) {
    return sDevice->enable_activity_event(
            sDevice,
            (uint32_t) activity_handle,
            (uint32_t) event_type,
            report_latency_ns);
}

/**
 * Disables a given activity event from being actively monitored.
 */
static int disable_activity_event(
        JNIEnv* env,
        jobject obj,
        jint activity_handle,
        jint event_type) {
    return sDevice->disable_activity_event(
            sDevice,
            (uint32_t) activity_handle,
            (uint32_t) event_type);
}

/**
 * Request flush for al batch buffers.
 */
static int flush(JNIEnv* env, jobject obj) {
    return sDevice->flush(sDevice);
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
