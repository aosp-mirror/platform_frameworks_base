/*
 * Copyright (C) 2023 The Android Open Source Project
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

#pragma once

// JNI wrappers for better logging

#include <jni.h>
#include <log/log.h>
#include <nativehelper/JNIHelp.h>

namespace android {

static inline jclass FindClassOrDie(JNIEnv* env, const char* class_name) {
    jclass clazz = env->FindClass(class_name);
    LOG_ALWAYS_FATAL_IF(clazz == NULL, "Unable to find class %s", class_name);
    return clazz;
}

static inline jfieldID GetFieldIDOrDie(JNIEnv* env, jclass clazz, const char* field_name,
                                       const char* field_signature) {
    jfieldID res = env->GetFieldID(clazz, field_name, field_signature);
    LOG_ALWAYS_FATAL_IF(res == NULL, "Unable to find field %s with signature %s", field_name,
                        field_signature);
    return res;
}

static inline jmethodID GetMethodIDOrDie(JNIEnv* env, jclass clazz, const char* method_name,
                                         const char* method_signature) {
    jmethodID res = env->GetMethodID(clazz, method_name, method_signature);
    LOG_ALWAYS_FATAL_IF(res == NULL, "Unable to find method %s with signature %s", method_name,
                        method_signature);
    return res;
}

static inline jfieldID GetStaticFieldIDOrDie(JNIEnv* env, jclass clazz, const char* field_name,
                                             const char* field_signature) {
    jfieldID res = env->GetStaticFieldID(clazz, field_name, field_signature);
    LOG_ALWAYS_FATAL_IF(res == NULL, "Unable to find static field %s with signature %s", field_name,
                        field_signature);
    return res;
}

static inline jmethodID GetStaticMethodIDOrDie(JNIEnv* env, jclass clazz, const char* method_name,
                                               const char* method_signature) {
    jmethodID res = env->GetStaticMethodID(clazz, method_name, method_signature);
    LOG_ALWAYS_FATAL_IF(res == NULL, "Unable to find static method %s with signature %s",
                        method_name, method_signature);
    return res;
}

template <typename T>
static inline T MakeGlobalRefOrDie(JNIEnv* env, T in) {
    jobject res = env->NewGlobalRef(in);
    LOG_ALWAYS_FATAL_IF(res == NULL, "Unable to create global reference.");
    return static_cast<T>(res);
}

//  Inline variable that specifies the method binding format.
//  The expected format is 'XX${method}XX', where ${method} represents the original method name.
//  This variable is shared across all translation units. This is treated as a global variable as
//  per C++ 17.
inline std::string jniMethodFormat;

inline static void setJniMethodFormat(std::string value) {
    jniMethodFormat = value;
}

// Potentially translates the given JNINativeMethods if setJniMethodFormat has been set.
// Has no effect otherwise
inline const JNINativeMethod* maybeRenameJniMethods(const JNINativeMethod* gMethods,
                                                    int numMethods) {
    if (jniMethodFormat.empty()) {
        return gMethods;
    }
    // Make a copy of gMethods with reformatted method names.
    JNINativeMethod* modifiedMethods = new JNINativeMethod[numMethods];
    LOG_ALWAYS_FATAL_IF(!modifiedMethods, "Failed to allocate a copy of the JNI methods");

    size_t methodNamePos = jniMethodFormat.find("${method}");
    LOG_ALWAYS_FATAL_IF(methodNamePos == std::string::npos,
                        "Invalid jniMethodFormat: could not find '${method}' in pattern");

    for (int i = 0; i < numMethods; i++) {
        modifiedMethods[i] = gMethods[i];
        std::string modifiedName = jniMethodFormat;
        modifiedName.replace(methodNamePos, 9, gMethods[i].name);
        char* modifiedNameChars = new char[modifiedName.length() + 1];
        LOG_ALWAYS_FATAL_IF(!modifiedNameChars, "Failed to allocate the new method name");
        std::strcpy(modifiedNameChars, modifiedName.c_str());
        modifiedMethods[i].name = modifiedNameChars;
    }
    return modifiedMethods;
}

static inline int RegisterMethodsOrDie(JNIEnv* env, const char* className,
                                       const JNINativeMethod* gMethods, int numMethods) {
    const JNINativeMethod* modifiedMethods = maybeRenameJniMethods(gMethods, numMethods);
    int res = jniRegisterNativeMethods(env, className, modifiedMethods, numMethods);
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register native methods.");
    return res;
}

} // namespace android
