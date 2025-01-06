/*
 * Copyright (C) 2024 The Android Open Source Project
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
#include <android-base/logging.h>
#include <android-base/properties.h>
#include <cutils/compiler.h>
#include <cutils/trace.h>
#include <jni.h>
#include <log/log.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/scoped_local_ref.h>
#include <nativehelper/scoped_primitive_array.h>
#include <nativehelper/scoped_utf_chars.h>
#include <tracing_sdk.h>

namespace android {
template <typename T>
inline static T* toPointer(jlong ptr) {
    return reinterpret_cast<T*>(static_cast<uintptr_t>(ptr));
}

template <typename T>
inline static jlong toJLong(T* ptr) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(ptr));
}

static const char* fromJavaString(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";
    ScopedUtfChars chars(env, jstr);

    if (!chars.c_str()) {
        ALOGE("Failed extracting string");
        return "";
    }

    return chars.c_str();
}

static void android_os_PerfettoTrace_event(JNIEnv* env, jclass, jint type, jlong cat_ptr,
                                           jstring name, jlong extra_ptr) {
    ScopedUtfChars name_utf(env, name);
    if (!name_utf.c_str()) {
        ALOGE("Failed extracting string");
    }

    tracing_perfetto::Category* category = toPointer<tracing_perfetto::Category>(cat_ptr);
    tracing_perfetto::trace_event(type, category->get(), name_utf.c_str(),
                                  toPointer<tracing_perfetto::Extra>(extra_ptr));
}

static jlong android_os_PerfettoTrace_get_process_track_uuid() {
    return tracing_perfetto::get_process_track_uuid();
}

static jlong android_os_PerfettoTrace_get_thread_track_uuid(jlong tid) {
    return tracing_perfetto::get_thread_track_uuid(tid);
}

static void android_os_PerfettoTrace_activate_trigger(JNIEnv* env, jclass, jstring name,
                                                      jint ttl_ms) {
    ScopedUtfChars name_utf(env, name);
    if (!name_utf.c_str()) {
        ALOGE("Failed extracting string");
        return;
    }

    tracing_perfetto::activate_trigger(name_utf.c_str(), static_cast<uint32_t>(ttl_ms));
}

static jlong android_os_PerfettoTraceCategory_init(JNIEnv* env, jclass, jstring name, jstring tag,
                                                   jstring severity) {
    return toJLong(new tracing_perfetto::Category(fromJavaString(env, name),
                                                  fromJavaString(env, tag),
                                                  fromJavaString(env, severity)));
}

static jlong android_os_PerfettoTraceCategory_delete() {
    return toJLong(&tracing_perfetto::Category::delete_category);
}

static void android_os_PerfettoTraceCategory_register(jlong ptr) {
    tracing_perfetto::Category* category = toPointer<tracing_perfetto::Category>(ptr);
    category->register_category();
}

static void android_os_PerfettoTraceCategory_unregister(jlong ptr) {
    tracing_perfetto::Category* category = toPointer<tracing_perfetto::Category>(ptr);
    category->unregister_category();
}

static jboolean android_os_PerfettoTraceCategory_is_enabled(jlong ptr) {
    tracing_perfetto::Category* category = toPointer<tracing_perfetto::Category>(ptr);
    return category->is_category_enabled();
}

static jlong android_os_PerfettoTraceCategory_get_extra_ptr(jlong ptr) {
    tracing_perfetto::Category* category = toPointer<tracing_perfetto::Category>(ptr);
    return toJLong(category->get());
}

static const JNINativeMethod gCategoryMethods[] = {
        {"native_init", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)J",
         (void*)android_os_PerfettoTraceCategory_init},
        {"native_delete", "()J", (void*)android_os_PerfettoTraceCategory_delete},
        {"native_register", "(J)V", (void*)android_os_PerfettoTraceCategory_register},
        {"native_unregister", "(J)V", (void*)android_os_PerfettoTraceCategory_unregister},
        {"native_is_enabled", "(J)Z", (void*)android_os_PerfettoTraceCategory_is_enabled},
        {"native_get_extra_ptr", "(J)J", (void*)android_os_PerfettoTraceCategory_get_extra_ptr},
};

static const JNINativeMethod gTraceMethods[] =
        {{"native_event", "(IJLjava/lang/String;J)V", (void*)android_os_PerfettoTrace_event},
         {"native_get_process_track_uuid", "()J",
          (void*)android_os_PerfettoTrace_get_process_track_uuid},
         {"native_get_thread_track_uuid", "(J)J",
          (void*)android_os_PerfettoTrace_get_thread_track_uuid},
         {"native_activate_trigger", "(Ljava/lang/String;I)V",
          (void*)android_os_PerfettoTrace_activate_trigger}};

int register_android_os_PerfettoTrace(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "android/os/PerfettoTrace", gTraceMethods,
                                       NELEM(gTraceMethods));

    res = jniRegisterNativeMethods(env, "android/os/PerfettoTrace$Category", gCategoryMethods,
                                   NELEM(gCategoryMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register native methods.");

    return 0;
}

} // namespace android
