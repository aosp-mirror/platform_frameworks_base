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

#include <JNIHelp.h>
#include <ScopedUtfChars.h>

#include <utils/Trace.h>
#include <cutils/log.h>

namespace android {

static jlong android_os_Trace_nativeGetEnabledTags(JNIEnv* env, jclass clazz) {
    return Tracer::getEnabledTags();
}

static void android_os_Trace_nativeTraceCounter(JNIEnv* env, jclass clazz,
        jlong tag, jstring nameStr, jint value) {
    ScopedUtfChars name(env, nameStr);
    Tracer::traceCounter(tag, name.c_str(), value);
}

static void android_os_Trace_nativeTraceBegin(JNIEnv* env, jclass clazz,
        jlong tag, jstring nameStr) {
    ScopedUtfChars name(env, nameStr);
    Tracer::traceBegin(tag, name.c_str());
}

static void android_os_Trace_nativeTraceEnd(JNIEnv* env, jclass clazz,
        jlong tag) {
    Tracer::traceEnd(tag);
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
};

int register_android_os_Trace(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "android/os/Trace",
            gTraceMethods, NELEM(gTraceMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    return 0;
}

} // namespace android
