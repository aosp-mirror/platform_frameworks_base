/*
 * Copyright (C) 2014 The Android Open Source Project
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

#define LOG_TAG "Fingerprint-JNI"

#include "JNIHelp.h"

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>
#include <hardware/hardware.h>
#include <hardware/fingerprint.h>
#include <utils/Log.h>

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class " className); \
        var = jclass(env->NewGlobalRef(var));

#define GET_STATIC_METHOD_ID(var, clazz, methodName, fieldDescriptor) \
        var = env->GetStaticMethodID(clazz, methodName, fieldDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find static method" methodName);

#define GET_METHOD_ID(var, clazz, methodName, fieldDescriptor) \
        var = env->GetMethodID(clazz, methodName, fieldDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find method" methodName);

#define GET_FIELD_ID(var, clazz, fieldName, fieldDescriptor) \
        var = env->GetFieldID(clazz, fieldName, fieldDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find field " fieldName);

namespace android {

static const uint16_t kVersion = HARDWARE_MODULE_API_VERSION(1, 0);

static const char* FINGERPRINT_SERVICE = "com/android/server/fingerprint/FingerprintService";
static struct {
    jclass clazz;
    jmethodID notify;
    jobject callbackObject;
} gFingerprintServiceClassInfo;

static struct {
    fingerprint_module_t const* module;
    fingerprint_device_t *device;
} gContext;

// Called by the HAL to notify us of fingerprint events
static void hal_notify_callback(fingerprint_msg_t msg) {
    uint32_t arg1 = 0;
    uint32_t arg2 = 0;
    uint32_t arg3 = 0; // TODO
    switch (msg.type) {
        case FINGERPRINT_ERROR:
            arg1 = msg.data.error;
            break;
        case FINGERPRINT_ACQUIRED:
            arg1 = msg.data.acquired.acquired_info;
            break;
        case FINGERPRINT_PROCESSED:
            arg1 = msg.data.processed.id;
            break;
        case FINGERPRINT_TEMPLATE_ENROLLING:
            arg1 = msg.data.enroll.id;
            arg2 = msg.data.enroll.samples_remaining;
            arg3 = msg.data.enroll.data_collected_bmp;
            break;
        case FINGERPRINT_TEMPLATE_REMOVED:
            arg1 = msg.data.removed.id;
            break;
        default:
            ALOGE("fingerprint: invalid msg: %d", msg.type);
            return;
    }
    //ALOG(LOG_VERBOSE, LOG_TAG, "hal_notify(msg=%d, arg1=%d, arg2=%d)\n", msg.type, arg1, arg2);

	// TODO: fix gross hack to attach JNI to calling thread
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    if (env == NULL) {
        JavaVMAttachArgs args = {JNI_VERSION_1_4, NULL, NULL};
        JavaVM* vm = AndroidRuntime::getJavaVM();
        int result = vm->AttachCurrentThread(&env, (void*) &args);
        if (result != JNI_OK) {
            ALOGE("Can't call JNI method: attach failed: %#x", result);
            return;
        }
    }
    env->CallVoidMethod(gFingerprintServiceClassInfo.callbackObject,
            gFingerprintServiceClassInfo.notify, msg.type, arg1, arg2);
}

static void nativeInit(JNIEnv *env, jobject clazz, jobject callbackObj) {
    ALOG(LOG_VERBOSE, LOG_TAG, "nativeInit()\n");
    FIND_CLASS(gFingerprintServiceClassInfo.clazz, FINGERPRINT_SERVICE);
    GET_METHOD_ID(gFingerprintServiceClassInfo.notify, gFingerprintServiceClassInfo.clazz,
           "notify", "(III)V");
    gFingerprintServiceClassInfo.callbackObject = env->NewGlobalRef(callbackObj);
}

static jint nativeEnroll(JNIEnv* env, jobject clazz, jint timeout) {
    ALOG(LOG_VERBOSE, LOG_TAG, "nativeEnroll()\n");
    int ret = gContext.device->enroll(gContext.device, timeout);
    return reinterpret_cast<jint>(ret);
}

static jint nativeEnrollCancel(JNIEnv* env, jobject clazz) {
    ALOG(LOG_VERBOSE, LOG_TAG, "nativeEnrollCancel()\n");
    int ret = gContext.device->enroll_cancel(gContext.device);
    return reinterpret_cast<jint>(ret);
}

static jint nativeRemove(JNIEnv* env, jobject clazz, jint fingerprintId) {
    ALOG(LOG_VERBOSE, LOG_TAG, "nativeRemove(%d)\n", fingerprintId);
    int ret = gContext.device->remove(gContext.device, fingerprintId);
    return reinterpret_cast<jint>(ret);
}

static jint nativeOpenHal(JNIEnv* env, jobject clazz) {
    ALOG(LOG_VERBOSE, LOG_TAG, "nativeOpenHal()\n");
    int err;
    const hw_module_t *hw_module = NULL;
    if (0 != (err = hw_get_module(FINGERPRINT_HARDWARE_MODULE_ID, &hw_module))) {
        ALOGE("Can't open fingerprint HW Module, error: %d", err);
        return 0;
    }
    if (NULL == hw_module) {
        ALOGE("No valid fingerprint module");
        return 0;
    }

    gContext.module = reinterpret_cast<const fingerprint_module_t*>(hw_module);

    if (gContext.module->common.methods->open == NULL) {
        ALOGE("No valid open method");
        return 0;
    }

    hw_device_t *device = NULL;

    if (0 != (err = gContext.module->common.methods->open(hw_module, NULL, &device))) {
        ALOGE("Can't open fingerprint methods, error: %d", err);
        return 0;
    }

    if (kVersion != device->version) {
        ALOGE("Wrong fp version. Expected %d, got %d", kVersion, device->version);
        // return 0; // FIXME
    }

    gContext.device = reinterpret_cast<fingerprint_device_t*>(device);
    err = gContext.device->set_notify(gContext.device, hal_notify_callback);
    if (err < 0) {
        ALOGE("Failed in call to set_notify(), err=%d", err);
        return 0;
    }

    // Sanity check - remove
    if (gContext.device->notify != hal_notify_callback) {
        ALOGE("NOTIFY not set properly: %p != %p", gContext.device->notify, hal_notify_callback);
    }

    ALOG(LOG_VERBOSE, LOG_TAG, "fingerprint HAL successfully initialized");
    return reinterpret_cast<jlong>(gContext.device);
}

static jint nativeCloseHal(JNIEnv* env, jobject clazz) {
    return -ENOSYS; // TODO
}

// ----------------------------------------------------------------------------

// TODO: clean up void methods
static const JNINativeMethod g_methods[] = {
    { "nativeEnroll", "(I)I", (void*)nativeEnroll },
    { "nativeEnrollCancel", "()I", (void*)nativeEnroll },
    { "nativeRemove", "(I)I", (void*)nativeRemove },
    { "nativeOpenHal", "()I", (void*)nativeOpenHal },
    { "nativeCloseHal", "()I", (void*)nativeCloseHal },
    { "nativeInit", "(Lcom/android/server/fingerprint/FingerprintService;)V", (void*)nativeInit }
};

int register_android_server_fingerprint_FingerprintService(JNIEnv* env) {
    FIND_CLASS(gFingerprintServiceClassInfo.clazz, FINGERPRINT_SERVICE);
    GET_METHOD_ID(gFingerprintServiceClassInfo.notify, gFingerprintServiceClassInfo.clazz, "notify",
            "(III)V");
    int result = AndroidRuntime::registerNativeMethods(
        env, FINGERPRINT_SERVICE, g_methods, NELEM(g_methods));
    ALOG(LOG_VERBOSE, LOG_TAG, "FingerprintManager JNI ready.\n");
    return result;
}

} // namespace android
