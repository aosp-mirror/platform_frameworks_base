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

#define LOG_TAG "Trace"
// #define LOG_NDEBUG 0

#include <JNIHelp.h>
#include <ScopedUtfChars.h>
#include <ScopedStringChars.h>

#include <utils/String8.h>

#include <cutils/trace.h>
#include <cutils/log.h>

namespace android {

static void sanitizeString(String8& utf8Chars) {
    size_t size = utf8Chars.size();
    char* str = utf8Chars.lockBuffer(size);
    for (size_t i = 0; i < size; i++) {
        char c = str[i];
        if (c == '\0' || c == '\n' || c == '|') {
            str[i] = ' ';
        }
    }
    utf8Chars.unlockBuffer();
}

static jlong android_os_Trace_nativeGetEnabledTags(JNIEnv* env, jclass clazz) {
    return atrace_get_enabled_tags();
}

static void android_os_Trace_nativeTraceCounter(JNIEnv* env, jclass clazz,
        jlong tag, jstring nameStr, jint value) {
    ScopedUtfChars name(env, nameStr);

    ALOGV("%s: %lld %s %d", __FUNCTION__, tag, name.c_str(), value);
    atrace_int(tag, name.c_str(), value);
}

static void android_os_Trace_nativeTraceBegin(JNIEnv* env, jclass clazz,
        jlong tag, jstring nameStr) {
    const size_t MAX_SECTION_NAME_LEN = 127;
    ScopedStringChars jchars(env, nameStr);
    String8 utf8Chars(reinterpret_cast<const char16_t*>(jchars.get()), jchars.size());
    sanitizeString(utf8Chars);

    ALOGV("%s: %lld %s", __FUNCTION__, tag, utf8Chars.string());
    atrace_begin(tag, utf8Chars.string());
}

static void android_os_Trace_nativeTraceEnd(JNIEnv* env, jclass clazz,
        jlong tag) {

    ALOGV("%s: %lld", __FUNCTION__, tag);
    atrace_end(tag);
}

static void android_os_Trace_nativeAsyncTraceBegin(JNIEnv* env, jclass clazz,
        jlong tag, jstring nameStr, jint cookie) {
    const size_t MAX_SECTION_NAME_LEN = 127;
    ScopedStringChars jchars(env, nameStr);
    String8 utf8Chars(reinterpret_cast<const char16_t*>(jchars.get()), jchars.size());
    sanitizeString(utf8Chars);

    ALOGV("%s: %lld %s %d", __FUNCTION__, tag, utf8Chars.string(), cookie);
    atrace_async_begin(tag, utf8Chars.string(), cookie);
}

static void android_os_Trace_nativeAsyncTraceEnd(JNIEnv* env, jclass clazz,
        jlong tag, jstring nameStr, jint cookie) {
    const size_t MAX_SECTION_NAME_LEN = 127;
    ScopedStringChars jchars(env, nameStr);
    String8 utf8Chars(reinterpret_cast<const char16_t*>(jchars.get()), jchars.size());
    sanitizeString(utf8Chars);

    ALOGV("%s: %lld %s %d", __FUNCTION__, tag, utf8Chars.string(), cookie);
    atrace_async_end(tag, utf8Chars.string(), cookie);
}

static void android_os_Trace_nativeSetAppTracingAllowed(JNIEnv* env,
        jclass clazz, jboolean allowed) {
    ALOGV("%s: %d", __FUNCTION__, allowed);

    atrace_set_debuggable(allowed);
}

static void android_os_Trace_nativeSetTracingEnabled(JNIEnv* env,
        jclass clazz, jboolean enabled) {
    ALOGV("%s: %d", __FUNCTION__, enabled);

    atrace_set_tracing_enabled(enabled);
}

static JNINativeMethod gTraceMethods[] = {
    /* name, signature, funcPtr */
    { "nativeGetEnabledTags",
            "()J",
            (void*)android_os_Trace_nativeGetEnabledTags },
    { "nativeTraceCounter",
            "(JLjava/lang/String;I)V",
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
    { "nativeSetAppTracingAllowed",
            "(Z)V",
            (void*)android_os_Trace_nativeSetAppTracingAllowed },
    { "nativeSetTracingEnabled",
            "(Z)V",
            (void*)android_os_Trace_nativeSetTracingEnabled },
};

int register_android_os_Trace(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "android/os/Trace",
            gTraceMethods, NELEM(gTraceMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    return 0;
}

} // namespace android
