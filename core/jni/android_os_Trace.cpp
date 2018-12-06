/*
 * Copyright (C) 2012 The Android Open Source Project
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

#include <jni.h>

#include <cutils/trace.h>
#include <log/log.h>
#include <nativehelper/JNIHelp.h>

#include <array>

namespace android {

inline static void sanitizeString(char* str, size_t size) {
    for (size_t i = 0; i < size; i++) {
        char c = str[i];
        if (c == '\0' || c == '\n' || c == '|') {
            str[i] = ' ';
        }
    }
}

inline static void getString(JNIEnv* env, jstring jstring, char* outBuffer, jsize maxSize) {
    jsize size = std::min(env->GetStringLength(jstring), maxSize);
    env->GetStringUTFRegion(jstring, 0, size, outBuffer);
    sanitizeString(outBuffer, size);
    outBuffer[size] = '\0';
}

template<typename F>
inline static void withString(JNIEnv* env, jstring jstr, F callback) {
    std::array<char, 1024> buffer;
    getString(env, jstr, buffer.data(), buffer.size());
    callback(buffer.data());
}

static jlong android_os_Trace_nativeGetEnabledTags(JNIEnv*, jclass) {
    return atrace_get_enabled_tags();
}

static void android_os_Trace_nativeTraceCounter(JNIEnv* env, jclass,
        jlong tag, jstring nameStr, jlong value) {
    withString(env, nameStr, [tag, value](char* str) {
        atrace_int64(tag, str, value);
    });
}

static void android_os_Trace_nativeTraceBegin(JNIEnv* env, jclass,
        jlong tag, jstring nameStr) {
    withString(env, nameStr, [tag](char* str) {
        atrace_begin(tag, str);
    });
}

static void android_os_Trace_nativeTraceEnd(JNIEnv*, jclass, jlong tag) {
    atrace_end(tag);
}

static void android_os_Trace_nativeAsyncTraceBegin(JNIEnv* env, jclass,
        jlong tag, jstring nameStr, jint cookie) {
    withString(env, nameStr, [tag, cookie](char* str) {
        atrace_async_begin(tag, str, cookie);
    });
}

static void android_os_Trace_nativeAsyncTraceEnd(JNIEnv* env, jclass,
        jlong tag, jstring nameStr, jint cookie) {
    withString(env, nameStr, [tag, cookie](char* str) {
        atrace_async_end(tag, str, cookie);
    });
}

static void android_os_Trace_nativeSetAppTracingAllowed(JNIEnv*, jclass, jboolean allowed) {
    atrace_set_debuggable(allowed);
}

static void android_os_Trace_nativeSetTracingEnabled(JNIEnv*, jclass, jboolean enabled) {
    atrace_set_tracing_enabled(enabled);
}

static const JNINativeMethod gTraceMethods[] = {
    /* name, signature, funcPtr */
    { "nativeGetEnabledTags",
            "()J",
            (void*)android_os_Trace_nativeGetEnabledTags },
    { "nativeSetAppTracingAllowed",
            "(Z)V",
            (void*)android_os_Trace_nativeSetAppTracingAllowed },
    { "nativeSetTracingEnabled",
            "(Z)V",
            (void*)android_os_Trace_nativeSetTracingEnabled },

    // ----------- @FastNative  ----------------

    { "nativeTraceCounter",
            "(JLjava/lang/String;J)V",
            (void*)android_os_Trace_nativeTraceCounter },
    { "nativeTraceBegin",
            "(JLjava/lang/String;)V",
            (void*)android_os_Trace_nativeTraceBegin },
    { "nativeTraceEnd",
            "(J)V",
            (void*)android_os_Trace_nativeTraceEnd },
    { "nativeAsyncTraceBegin",
            "(JLjava/lang/String;I)V",
            (void*)android_os_Trace_nativeAsyncTraceBegin },
    { "nativeAsyncTraceEnd",
            "(JLjava/lang/String;I)V",
            (void*)android_os_Trace_nativeAsyncTraceEnd },
};

int register_android_os_Trace(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "android/os/Trace",
            gTraceMethods, NELEM(gTraceMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register native methods.");

    return 0;
}

} // namespace android
