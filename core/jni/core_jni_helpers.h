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

#ifndef CORE_JNI_HELPERS
#define CORE_JNI_HELPERS

#include <nativehelper/JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>

namespace android {

// Defines some helpful functions.

static inline jclass FindClassOrDie(JNIEnv* env, const char* class_name) {
    jclass clazz = env->FindClass(class_name);
    LOG_ALWAYS_FATAL_IF(clazz == NULL, "Unable to find class %s", class_name);
    return clazz;
}

static inline jfieldID GetFieldIDOrDie(JNIEnv* env, jclass clazz, const char* field_name,
                                       const char* field_signature) {
    jfieldID res = env->GetFieldID(clazz, field_name, field_signature);
    LOG_ALWAYS_FATAL_IF(res == NULL, "Unable to find static field %s", field_name);
    return res;
}

static inline jmethodID GetMethodIDOrDie(JNIEnv* env, jclass clazz, const char* method_name,
                                         const char* method_signature) {
    jmethodID res = env->GetMethodID(clazz, method_name, method_signature);
    LOG_ALWAYS_FATAL_IF(res == NULL, "Unable to find method %s", method_name);
    return res;
}

static inline jfieldID GetStaticFieldIDOrDie(JNIEnv* env, jclass clazz, const char* field_name,
                                             const char* field_signature) {
    jfieldID res = env->GetStaticFieldID(clazz, field_name, field_signature);
    LOG_ALWAYS_FATAL_IF(res == NULL, "Unable to find static field %s", field_name);
    return res;
}

static inline jmethodID GetStaticMethodIDOrDie(JNIEnv* env, jclass clazz, const char* method_name,
                                               const char* method_signature) {
    jmethodID res = env->GetStaticMethodID(clazz, method_name, method_signature);
    LOG_ALWAYS_FATAL_IF(res == NULL, "Unable to find static method %s", method_name);
    return res;
}

template <typename T>
static inline T MakeGlobalRefOrDie(JNIEnv* env, T in) {
    jobject res = env->NewGlobalRef(in);
    LOG_ALWAYS_FATAL_IF(res == NULL, "Unable to create global reference.");
    return static_cast<T>(res);
}

static inline int RegisterMethodsOrDie(JNIEnv* env, const char* className,
                                       const JNINativeMethod* gMethods, int numMethods) {
    int res = AndroidRuntime::registerNativeMethods(env, className, gMethods, numMethods);
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register native methods.");
    return res;
}

}  // namespace android

#endif  // CORE_JNI_HELPERS
