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
#include <nativehelper/utils.h>
#include <tracing_perfetto.h>
#include <tracing_sdk.h>

namespace android {
constexpr int kFlushTimeoutMs = 5000;

template <typename T>
inline static T* toPointer(jlong ptr) {
    return reinterpret_cast<T*>(static_cast<uintptr_t>(ptr));
}

template <typename T>
inline static jlong toJLong(T* ptr) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(ptr));
}

static jlong android_os_PerfettoTrace_get_process_track_uuid() {
    return tracing_perfetto::get_process_track_uuid();
}

static jlong android_os_PerfettoTrace_get_thread_track_uuid(jlong tid) {
    return tracing_perfetto::get_thread_track_uuid(tid);
}

static void android_os_PerfettoTrace_activate_trigger(JNIEnv* env, jclass, jstring name,
                                                      jint ttl_ms) {
    ScopedUtfChars name_chars = GET_UTF_OR_RETURN_VOID(env, name);
    tracing_perfetto::activate_trigger(name_chars.c_str(), static_cast<uint32_t>(ttl_ms));
}

void android_os_PerfettoTrace_register(bool is_backend_in_process) {
    tracing_perfetto::registerWithPerfetto(is_backend_in_process);
}

static jlong android_os_PerfettoTraceCategory_init(JNIEnv* env, jclass, jstring name, jstring tag,
                                                   jstring severity) {
    ScopedUtfChars name_chars = GET_UTF_OR_RETURN(env, name);
    ScopedUtfChars tag_chars = GET_UTF_OR_RETURN(env, tag);
    ScopedUtfChars severity_chars = GET_UTF_OR_RETURN(env, severity);

    return toJLong(new tracing_perfetto::Category(name_chars.c_str(), tag_chars.c_str(),
                                                  severity_chars.c_str()));
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

static jlong android_os_PerfettoTrace_start_session(JNIEnv* env, jclass /* obj */,
                                                    jboolean is_backend_in_process,
                                                    jbyteArray config_bytes) {
    jsize length = env->GetArrayLength(config_bytes);
    std::vector<uint8_t> data;
    data.reserve(length);
    env->GetByteArrayRegion(config_bytes, 0, length, reinterpret_cast<jbyte*>(data.data()));

    tracing_perfetto::Session* session =
            new tracing_perfetto::Session(is_backend_in_process, data.data(), length);

    return reinterpret_cast<long>(session);
}

static jbyteArray android_os_PerfettoTrace_stop_session([[maybe_unused]] JNIEnv* env,
                                                        jclass /* obj */, jlong ptr) {
    tracing_perfetto::Session* session = reinterpret_cast<tracing_perfetto::Session*>(ptr);

    session->FlushBlocking(kFlushTimeoutMs);
    session->StopBlocking();

    std::vector<uint8_t> data = session->ReadBlocking();

    delete session;

    jbyteArray bytes = env->NewByteArray(data.size());
    env->SetByteArrayRegion(bytes, 0, data.size(), reinterpret_cast<jbyte*>(data.data()));
    return bytes;
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
        {{"native_get_process_track_uuid", "()J",
          (void*)android_os_PerfettoTrace_get_process_track_uuid},
         {"native_get_thread_track_uuid", "(J)J",
          (void*)android_os_PerfettoTrace_get_thread_track_uuid},
         {"native_activate_trigger", "(Ljava/lang/String;I)V",
          (void*)android_os_PerfettoTrace_activate_trigger},
         {"native_register", "(Z)V", (void*)android_os_PerfettoTrace_register},
         {"native_start_session", "(Z[B)J", (void*)android_os_PerfettoTrace_start_session},
         {"native_stop_session", "(J)[B", (void*)android_os_PerfettoTrace_stop_session}};

int register_android_os_PerfettoTrace(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "android/os/PerfettoTrace", gTraceMethods,
                                       NELEM(gTraceMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register perfetto native methods.");

    res = jniRegisterNativeMethods(env, "android/os/PerfettoTrace$Category", gCategoryMethods,
                                   NELEM(gCategoryMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register category native methods.");

    return 0;
}

} // namespace android
