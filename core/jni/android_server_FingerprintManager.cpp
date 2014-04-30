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
#include <utils/Log.h>

namespace android {

static struct {
    jclass clazz;
    jmethodID notify;
} gFingerprintManagerClassInfo;

static jint nativeEnroll(JNIEnv* env, jobject clazz, jint timeout) {
    return -1; // TODO
}

static jint nativeRemove(JNIEnv* env, jobject clazz, jint fingerprintId) {
    return -1; // TODO
}

// ----------------------------------------------------------------------------

static const JNINativeMethod g_methods[] = {
    { "nativeEnroll", "(I)I", (void*)nativeEnroll },
    { "nativeRemove", "(I)I", (void*)nativeRemove },
};

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

int register_android_server_FingerprintManager(JNIEnv* env) {
    FIND_CLASS(gFingerprintManagerClassInfo.clazz,
            "android/service/fingerprint/FingerprintManager");
    GET_METHOD_ID(gFingerprintManagerClassInfo.notify, gFingerprintManagerClassInfo.clazz,
            "notify", "(III)V");
    return AndroidRuntime::registerNativeMethods(
        env, "com/android/service/fingerprint/FingerprintManager", g_methods, NELEM(g_methods));
}

} // namespace android
