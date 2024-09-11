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

#include <cutils/compiler.h>
#include <cutils/trace.h>
#include <jni.h>
#include <log/log.h>
#include <nativehelper/JNIHelp.h>
#include <tracing_perfetto.h>

#include <array>

static constexpr const char* kNullReplacement = "(null)";

namespace android {

inline static void sanitizeString(char* str) {
    while (*str) {
        char c = *str;
        if (c == '\n' || c == '|') {
            *str = ' ';
        }
        str++;
    }
}

template<typename F>
inline static void withString(JNIEnv* env, jstring jstr, F callback) {
    if (CC_UNLIKELY(jstr == nullptr)) {
        callback(kNullReplacement);
        return;
    }

    // We need to handle the worst case of 1 character -> 4 bytes
    // So make a buffer of size 4097 and let it hold a string with a maximum length
    // of 1024. The extra last byte for the null terminator.
    std::array<char, 4097> buffer;
    // We have no idea of knowing how much data GetStringUTFRegion wrote, so null it out in
    // advance so we can have a reliable null terminator
    memset(buffer.data(), 0, buffer.size());
    jsize size = std::min(env->GetStringLength(jstr), 1024);
    env->GetStringUTFRegion(jstr, 0, size, buffer.data());
    sanitizeString(buffer.data());

    callback(buffer.data());
}

static void android_os_Trace_nativeTraceCounter(JNIEnv* env, jclass,
        jlong tag, jstring nameStr, jlong value) {
    withString(env, nameStr,
               [tag, value](const char* str) { tracing_perfetto::traceCounter(tag, str, value); });
}

static void android_os_Trace_nativeTraceBegin(JNIEnv* env, jclass,
        jlong tag, jstring nameStr) {
    withString(env, nameStr, [tag](const char* str) { tracing_perfetto::traceBegin(tag, str); });
}

static void android_os_Trace_nativeTraceEnd(JNIEnv*, jclass, jlong tag) {
    tracing_perfetto::traceEnd(tag);
}

static void android_os_Trace_nativeAsyncTraceBegin(JNIEnv* env, jclass,
        jlong tag, jstring nameStr, jint cookie) {
    withString(env, nameStr, [tag, cookie](const char* str) {
        tracing_perfetto::traceAsyncBegin(tag, str, cookie);
    });
}

static void android_os_Trace_nativeAsyncTraceEnd(JNIEnv* env, jclass,
        jlong tag, jstring nameStr, jint cookie) {
    withString(env, nameStr, [tag, cookie](const char* str) {
        tracing_perfetto::traceAsyncEnd(tag, str, cookie);
    });
}

static void android_os_Trace_nativeAsyncTraceForTrackBegin(JNIEnv* env, jclass,
        jlong tag, jstring trackStr, jstring nameStr, jint cookie) {
    withString(env, trackStr, [env, tag, nameStr, cookie](const char* track) {
        withString(env, nameStr, [tag, track, cookie](const char* name) {
            tracing_perfetto::traceAsyncBeginForTrack(tag, name, track, cookie);
        });
    });
}

static void android_os_Trace_nativeAsyncTraceForTrackEnd(JNIEnv* env, jclass,
        jlong tag, jstring trackStr, jint cookie) {
    withString(env, trackStr, [tag, cookie](const char* track) {
        tracing_perfetto::traceAsyncEndForTrack(tag, track, cookie);
    });
}

static void android_os_Trace_nativeSetAppTracingAllowed(JNIEnv*, jclass, jboolean allowed) {
    // TODO(b/331916606): this is load-bearing for an app to notice that it is
    // traced after post-zygote-fork specialisation.
    atrace_update_tags();
}

static void android_os_Trace_nativeSetTracingEnabled(JNIEnv*, jclass, jboolean enabled) {
    // no-op
}

static void android_os_Trace_nativeInstant(JNIEnv* env, jclass,
        jlong tag, jstring nameStr) {
    withString(env, nameStr, [tag](const char* str) { tracing_perfetto::traceInstant(tag, str); });
}

static void android_os_Trace_nativeInstantForTrack(JNIEnv* env, jclass,
        jlong tag, jstring trackStr, jstring nameStr) {
    withString(env, trackStr, [env, tag, nameStr](const char* track) {
        withString(env, nameStr, [tag, track](const char* name) {
            tracing_perfetto::traceInstantForTrack(tag, track, name);
        });
    });
}

static jboolean android_os_Trace_nativeIsTagEnabled(jlong tag) {
    return tracing_perfetto::isTagEnabled(tag);
}

static void android_os_Trace_nativeRegisterWithPerfetto(JNIEnv* env) {
    tracing_perfetto::registerWithPerfetto();
}

static const JNINativeMethod gTraceMethods[] = {
        /* name, signature, funcPtr */
        {"nativeSetAppTracingAllowed", "(Z)V", (void*)android_os_Trace_nativeSetAppTracingAllowed},
        {"nativeSetTracingEnabled", "(Z)V", (void*)android_os_Trace_nativeSetTracingEnabled},

        // ----------- @FastNative  ----------------

        {"nativeTraceCounter", "(JLjava/lang/String;J)V",
         (void*)android_os_Trace_nativeTraceCounter},
        {"nativeTraceBegin", "(JLjava/lang/String;)V", (void*)android_os_Trace_nativeTraceBegin},
        {"nativeTraceEnd", "(J)V", (void*)android_os_Trace_nativeTraceEnd},
        {"nativeAsyncTraceBegin", "(JLjava/lang/String;I)V",
         (void*)android_os_Trace_nativeAsyncTraceBegin},
        {"nativeAsyncTraceEnd", "(JLjava/lang/String;I)V",
         (void*)android_os_Trace_nativeAsyncTraceEnd},
        {"nativeAsyncTraceForTrackBegin", "(JLjava/lang/String;Ljava/lang/String;I)V",
         (void*)android_os_Trace_nativeAsyncTraceForTrackBegin},
        {"nativeAsyncTraceForTrackEnd", "(JLjava/lang/String;I)V",
         (void*)android_os_Trace_nativeAsyncTraceForTrackEnd},
        {"nativeInstant", "(JLjava/lang/String;)V", (void*)android_os_Trace_nativeInstant},
        {"nativeInstantForTrack", "(JLjava/lang/String;Ljava/lang/String;)V",
         (void*)android_os_Trace_nativeInstantForTrack},
        {"nativeRegisterWithPerfetto", "()V", (void*)android_os_Trace_nativeRegisterWithPerfetto},

        // ----------- @CriticalNative  ----------------
        {"nativeIsTagEnabled", "(J)Z", (void*)android_os_Trace_nativeIsTagEnabled},
};

int register_android_os_Trace(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "android/os/Trace",
            gTraceMethods, NELEM(gTraceMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register native methods.");

    return 0;
}

} // namespace android
