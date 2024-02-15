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

#include <nativehelper/JNIPlatformHelp.h>
#include <nativehelper/scoped_local_ref.h>
#include <nativehelper/scoped_utf_chars.h>
#include <android_runtime/AndroidRuntime.h>

#include "jni_wrappers.h"

// Host targets (layoutlib) do not differentiate between regular and critical native methods,
// and they need all the JNI methods to have JNIEnv* and jclass/jobject as their first two arguments.
// The following macro allows to have those arguments when compiling for host while omitting them when
// compiling for Android.
#ifdef __ANDROID__
#define CRITICAL_JNI_PARAMS
#define CRITICAL_JNI_PARAMS_COMMA
#else
#define CRITICAL_JNI_PARAMS JNIEnv*, jclass
#define CRITICAL_JNI_PARAMS_COMMA JNIEnv*, jclass,
#endif

namespace android {

/**
 * Returns the result of invoking java.lang.ref.Reference.get() on a Reference object.
 */
jobject GetReferent(JNIEnv* env, jobject ref);

/**
 * Read the specified field from jobject, and convert to std::string.
 * If the field cannot be obtained, return defaultValue.
 */
static inline std::string getStringField(JNIEnv* env, jobject obj, jfieldID fieldId,
        const char* defaultValue) {
    ScopedLocalRef<jstring> strObj(env, jstring(env->GetObjectField(obj, fieldId)));
    if (strObj != nullptr) {
        ScopedUtfChars chars(env, strObj.get());
        return std::string(chars.c_str());
    }
    return std::string(defaultValue);
}

static inline JNIEnv* GetJNIEnvironment(JavaVM* vm, jint version = JNI_VERSION_1_4) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), version) != JNI_OK) {
        return nullptr;
    }
    return env;
}

static inline JNIEnv* GetOrAttachJNIEnvironment(JavaVM* jvm, jint version = JNI_VERSION_1_4) {
    JNIEnv* env = GetJNIEnvironment(jvm, version);
    if (!env) {
        int result = jvm->AttachCurrentThread(&env, nullptr);
        LOG_ALWAYS_FATAL_IF(result != JNI_OK, "JVM thread attach failed.");
        struct VmDetacher {
            VmDetacher(JavaVM* vm) : mVm(vm) {}
            ~VmDetacher() { mVm->DetachCurrentThread(); }

        private:
            JavaVM* const mVm;
        };
        static thread_local VmDetacher detacher(jvm);
    }
    return env;
}

static inline void DieIfException(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) {
        jnihelp::ExpandableString summary;
        jnihelp::ExpandableStringInitialize(&summary);
        jnihelp::GetStackTraceOrSummary(env, nullptr, &summary);
        LOG_ALWAYS_FATAL("%s\n%s", message, summary.data);
    }
}

}  // namespace android

#endif  // CORE_JNI_HELPERS
