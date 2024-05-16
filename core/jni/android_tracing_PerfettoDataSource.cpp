/*
 * Copyright 2023 The Android Open Source Project
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

#define LOG_TAG "NativeJavaPerfettoDs"

#include "android_tracing_PerfettoDataSource.h"

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>
#include <nativehelper/JNIHelp.h>
#include <perfetto/public/data_source.h>
#include <perfetto/public/producer.h>
#include <perfetto/public/protos/trace/test_event.pzc.h>
#include <perfetto/public/protos/trace/trace_packet.pzc.h>
#include <utils/Log.h>
#include <utils/RefBase.h>

#include <sstream>
#include <thread>

#include "core_jni_helpers.h"

namespace android {

static struct {
    jclass clazz;
    jmethodID createInstance;
    jmethodID createTlsState;
    jmethodID createIncrementalState;
} gPerfettoDataSourceClassInfo;

static struct {
    jclass clazz;
    jmethodID init;
} gCreateTlsStateArgsClassInfo;

static struct {
    jclass clazz;
    jmethodID init;
} gCreateIncrementalStateArgsClassInfo;

static JavaVM* gVm;

struct TlsState {
    jobject jobj;
};

struct IncrementalState {
    jobject jobj;
};

// In a single thread there can be only one trace point active across all data source, so we can use
// a single global thread_local variable to keep track of the active tracer iterator.
thread_local static bool gInIteration;
thread_local static struct PerfettoDsTracerIterator gIterator;

PerfettoDataSource::PerfettoDataSource(JNIEnv* env, jobject javaDataSource,
                                       std::string dataSourceName)
      : dataSourceName(std::move(dataSourceName)),
        mJavaDataSource(env->NewGlobalRef(javaDataSource)) {}

jobject PerfettoDataSource::newInstance(JNIEnv* env, void* ds_config, size_t ds_config_size,
                                        PerfettoDsInstanceIndex inst_id) {
    jbyteArray configArray = env->NewByteArray(ds_config_size);

    void* temp = env->GetPrimitiveArrayCritical((jarray)configArray, 0);
    memcpy(temp, ds_config, ds_config_size);
    env->ReleasePrimitiveArrayCritical(configArray, temp, 0);

    jobject instance =
            env->CallObjectMethod(mJavaDataSource, gPerfettoDataSourceClassInfo.createInstance,
                                  configArray, inst_id);

    if (env->ExceptionCheck()) {
        LOGE_EX(env);
        env->ExceptionClear();
        LOG_ALWAYS_FATAL("Failed to create new Java Perfetto datasource instance");
    }

    return instance;
}

bool PerfettoDataSource::TraceIterateBegin() {
    if (gInIteration) {
        return false;
    }

    gIterator = PerfettoDsTraceIterateBegin(&dataSource);

    if (gIterator.impl.tracer == nullptr) {
        return false;
    }

    gInIteration = true;
    return true;
}

bool PerfettoDataSource::TraceIterateNext() {
    if (!gInIteration) {
        LOG_ALWAYS_FATAL("Tried calling TraceIterateNext outside of a tracer iteration.");
        return false;
    }

    PerfettoDsTraceIterateNext(&dataSource, &gIterator);

    if (gIterator.impl.tracer == nullptr) {
        // Reached end of iterator. No more datasource instances.
        gInIteration = false;
        return false;
    }

    return true;
}

void PerfettoDataSource::TraceIterateBreak() {
    if (!gInIteration) {
        return;
    }

    PerfettoDsTraceIterateBreak(&dataSource, &gIterator);
    gInIteration = false;
}

PerfettoDsInstanceIndex PerfettoDataSource::GetInstanceIndex() {
    if (!gInIteration) {
        LOG_ALWAYS_FATAL("Tried calling GetInstanceIndex outside of a tracer iteration.");
        return -1;
    }

    return gIterator.impl.inst_id;
}

jobject PerfettoDataSource::GetCustomTls() {
    if (!gInIteration) {
        LOG_ALWAYS_FATAL("Tried getting CustomTls outside of a tracer iteration.");
        return nullptr;
    }

    TlsState* tls_state =
            reinterpret_cast<TlsState*>(PerfettoDsGetCustomTls(&dataSource, &gIterator));

    return tls_state->jobj;
}

void PerfettoDataSource::SetCustomTls(jobject tlsState) {
    if (!gInIteration) {
        LOG_ALWAYS_FATAL("Tried getting CustomTls outside of a tracer iteration.");
        return;
    }

    TlsState* tls_state =
            reinterpret_cast<TlsState*>(PerfettoDsGetCustomTls(&dataSource, &gIterator));

    tls_state->jobj = tlsState;
}

jobject PerfettoDataSource::GetIncrementalState() {
    if (!gInIteration) {
        LOG_ALWAYS_FATAL("Tried getting IncrementalState outside of a tracer iteration.");
        return nullptr;
    }

    IncrementalState* incr_state = reinterpret_cast<IncrementalState*>(
            PerfettoDsGetIncrementalState(&dataSource, &gIterator));

    return incr_state->jobj;
}

void PerfettoDataSource::SetIncrementalState(jobject incrementalState) {
    if (!gInIteration) {
        LOG_ALWAYS_FATAL("Tried getting IncrementalState outside of a tracer iteration.");
        return;
    }

    IncrementalState* incr_state = reinterpret_cast<IncrementalState*>(
            PerfettoDsGetIncrementalState(&dataSource, &gIterator));

    incr_state->jobj = incrementalState;
}

void PerfettoDataSource::WritePackets(JNIEnv* env, jobjectArray packets) {
    if (!gInIteration) {
        LOG_ALWAYS_FATAL("Tried writing packets outside of a tracer iteration.");
        return;
    }

    int packets_count = env->GetArrayLength(packets);
    for (int i = 0; i < packets_count; i++) {
        jbyteArray packet_proto_buffer = (jbyteArray)env->GetObjectArrayElement(packets, i);

        jbyte* raw_proto_buffer = env->GetByteArrayElements(packet_proto_buffer, nullptr);
        int buffer_size = env->GetArrayLength(packet_proto_buffer);

        struct PerfettoDsRootTracePacket trace_packet;
        PerfettoDsTracerPacketBegin(&gIterator, &trace_packet);
        PerfettoPbMsgAppendBytes(&trace_packet.msg.msg, (const uint8_t*)raw_proto_buffer,
                                 buffer_size);
        PerfettoDsTracerPacketEnd(&gIterator, &trace_packet);

        env->ReleaseByteArrayElements(packet_proto_buffer, raw_proto_buffer, 0 /* default mode */);
    }
}

void PerfettoDataSource::flushAll() {
    PERFETTO_DS_TRACE(dataSource, ctx) {
        PerfettoDsTracerFlush(&ctx, nullptr, nullptr);
    }
}

PerfettoDataSource::~PerfettoDataSource() {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->DeleteGlobalRef(mJavaDataSource);
}

jlong nativeCreate(JNIEnv* env, jclass clazz, jobject javaDataSource, jstring name) {
    const char* nativeString = env->GetStringUTFChars(name, 0);
    PerfettoDataSource* dataSource = new PerfettoDataSource(env, javaDataSource, nativeString);
    env->ReleaseStringUTFChars(name, nativeString);

    dataSource->incStrong((void*)nativeCreate);

    return reinterpret_cast<jlong>(dataSource);
}

void nativeDestroy(void* ptr) {
    PerfettoDataSource* dataSource = reinterpret_cast<PerfettoDataSource*>(ptr);
    dataSource->decStrong((void*)nativeCreate);
}

static jlong nativeGetFinalizer(JNIEnv* /* env */, jclass /* clazz */) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&nativeDestroy));
}

void nativeWritePackets(JNIEnv* env, jclass clazz, jlong ds_ptr, jobjectArray packets) {
    ALOG(LOG_DEBUG, LOG_TAG, "nativeWritePackets(%p)", (void*)ds_ptr);
    sp<PerfettoDataSource> datasource = reinterpret_cast<PerfettoDataSource*>(ds_ptr);
    datasource->WritePackets(env, packets);
}

void nativeFlushAll(JNIEnv* env, jclass clazz, jlong ptr) {
    sp<PerfettoDataSource> datasource = reinterpret_cast<PerfettoDataSource*>(ptr);
    datasource->flushAll();
}

void nativeRegisterDataSource(JNIEnv* env, jclass clazz, jlong datasource_ptr,
                              jint buffer_exhausted_policy, jboolean will_notify_on_stop,
                              jboolean no_flush) {
    sp<PerfettoDataSource> datasource = reinterpret_cast<PerfettoDataSource*>(datasource_ptr);

    struct PerfettoDsParams params = PerfettoDsParamsDefault();
    params.will_notify_on_stop = will_notify_on_stop;
    params.buffer_exhausted_policy = (PerfettoDsBufferExhaustedPolicy)buffer_exhausted_policy;

    params.user_arg = reinterpret_cast<void*>(datasource.get());

    params.on_setup_cb = [](struct PerfettoDsImpl*, PerfettoDsInstanceIndex inst_id,
                            void* ds_config, size_t ds_config_size, void* user_arg,
                            struct PerfettoDsOnSetupArgs*) -> void* {
        JNIEnv* env = GetOrAttachJNIEnvironment(gVm, JNI_VERSION_1_6);

        auto* datasource = reinterpret_cast<PerfettoDataSource*>(user_arg);

        ScopedLocalRef<jobject> java_data_source_instance(env,
                                                          datasource->newInstance(env, ds_config,
                                                                                  ds_config_size,
                                                                                  inst_id));

        auto* datasource_instance =
                new PerfettoDataSourceInstance(env, java_data_source_instance.get(), inst_id);
        return static_cast<void*>(datasource_instance);
    };

    params.on_create_tls_cb = [](struct PerfettoDsImpl* ds_impl, PerfettoDsInstanceIndex inst_id,
                                 struct PerfettoDsTracerImpl* tracer, void* user_arg) -> void* {
        // Populated later and only if required by the java side
        auto* tls_state = new TlsState(NULL);
        return static_cast<void*>(tls_state);
    };

    params.on_delete_tls_cb = [](void* ptr) {
        JNIEnv* env = GetOrAttachJNIEnvironment(gVm, JNI_VERSION_1_6);

        TlsState* tls_state = reinterpret_cast<TlsState*>(ptr);

        if (tls_state->jobj != NULL) {
            env->DeleteGlobalRef(tls_state->jobj);
        }
        delete tls_state;
    };

    params.on_create_incr_cb = [](struct PerfettoDsImpl* ds_impl, PerfettoDsInstanceIndex inst_id,
                                  struct PerfettoDsTracerImpl* tracer, void* user_arg) -> void* {
        // Populated later and only if required by the java side
        auto* incr_state = new IncrementalState(NULL);
        return static_cast<void*>(incr_state);
    };

    params.on_delete_incr_cb = [](void* ptr) {
        JNIEnv* env = GetOrAttachJNIEnvironment(gVm, JNI_VERSION_1_6);

        IncrementalState* incr_state = reinterpret_cast<IncrementalState*>(ptr);

        if (incr_state->jobj != NULL) {
            env->DeleteGlobalRef(incr_state->jobj);
        }
        delete incr_state;
    };

    params.on_start_cb = [](struct PerfettoDsImpl*, PerfettoDsInstanceIndex, void*, void* inst_ctx,
                            struct PerfettoDsOnStartArgs*) {
        JNIEnv* env = GetOrAttachJNIEnvironment(gVm, JNI_VERSION_1_6);

        auto* datasource_instance = static_cast<PerfettoDataSourceInstance*>(inst_ctx);
        datasource_instance->onStart(env);
    };

    if (!no_flush) {
        params.on_flush_cb = [](struct PerfettoDsImpl*, PerfettoDsInstanceIndex, void*,
                                void* inst_ctx, struct PerfettoDsOnFlushArgs*) {
            JNIEnv* env = GetOrAttachJNIEnvironment(gVm, JNI_VERSION_1_6);

            auto* datasource_instance = static_cast<PerfettoDataSourceInstance*>(inst_ctx);
            datasource_instance->onFlush(env);
        };
    }

    params.on_stop_cb = [](struct PerfettoDsImpl*, PerfettoDsInstanceIndex inst_id, void* user_arg,
                           void* inst_ctx, struct PerfettoDsOnStopArgs*) {
        JNIEnv* env = GetOrAttachJNIEnvironment(gVm, JNI_VERSION_1_6);

        auto* datasource_instance = static_cast<PerfettoDataSourceInstance*>(inst_ctx);
        datasource_instance->onStop(env);
    };

    params.on_destroy_cb = [](struct PerfettoDsImpl* ds_impl, void* user_arg,
                              void* inst_ctx) -> void {
        auto* datasource_instance = static_cast<PerfettoDataSourceInstance*>(inst_ctx);

        delete datasource_instance;
    };

    PerfettoDsRegister(&datasource->dataSource, datasource->dataSourceName.c_str(), params);
}

jobject nativeGetPerfettoInstanceLocked(JNIEnv* /* env */, jclass /* clazz */, jlong dataSourcePtr,
                                        PerfettoDsInstanceIndex instance_idx) {
    sp<PerfettoDataSource> datasource = reinterpret_cast<PerfettoDataSource*>(dataSourcePtr);
    auto* datasource_instance = static_cast<PerfettoDataSourceInstance*>(
            PerfettoDsImplGetInstanceLocked(datasource->dataSource.impl, instance_idx));

    if (datasource_instance == nullptr) {
        // datasource instance doesn't exist
        ALOG(LOG_WARN, LOG_TAG,
             "DS instance invalid!! nativeGetPerfettoInstanceLocked returning NULL");
        return nullptr;
    }

    return datasource_instance->GetJavaDataSourceInstance();
}

void nativeReleasePerfettoInstanceLocked(JNIEnv* /* env */, jclass /* clazz */, jlong dataSourcePtr,
                                         PerfettoDsInstanceIndex instance_idx) {
    sp<PerfettoDataSource> datasource = reinterpret_cast<PerfettoDataSource*>(dataSourcePtr);
    PerfettoDsImplReleaseInstanceLocked(datasource->dataSource.impl, instance_idx);
}

bool nativePerfettoDsTraceIterateBegin(JNIEnv* /* env */, jclass /* clazz */, jlong dataSourcePtr) {
    sp<PerfettoDataSource> datasource = reinterpret_cast<PerfettoDataSource*>(dataSourcePtr);
    return datasource->TraceIterateBegin();
}

bool nativePerfettoDsTraceIterateNext(JNIEnv* /* env */, jclass /* clazz */, jlong dataSourcePtr) {
    sp<PerfettoDataSource> datasource = reinterpret_cast<PerfettoDataSource*>(dataSourcePtr);
    return datasource->TraceIterateNext();
}

void nativePerfettoDsTraceIterateBreak(JNIEnv* /* env */, jclass /* clazz */, jlong dataSourcePtr) {
    sp<PerfettoDataSource> datasource = reinterpret_cast<PerfettoDataSource*>(dataSourcePtr);
    return datasource->TraceIterateBreak();
}

jint nativeGetPerfettoDsInstanceIndex(JNIEnv* /* env */, jclass /* clazz */, jlong dataSourcePtr) {
    sp<PerfettoDataSource> datasource = reinterpret_cast<PerfettoDataSource*>(dataSourcePtr);
    return (jint)datasource->GetInstanceIndex();
}

jobject nativeGetCustomTls(JNIEnv* /* env */, jclass /* clazz */, jlong dataSourcePtr) {
    sp<PerfettoDataSource> datasource = reinterpret_cast<PerfettoDataSource*>(dataSourcePtr);
    return datasource->GetCustomTls();
}

void nativeSetCustomTls(JNIEnv* env, jclass /* clazz */, jlong dataSourcePtr, jobject tlsState) {
    sp<PerfettoDataSource> datasource = reinterpret_cast<PerfettoDataSource*>(dataSourcePtr);
    tlsState = env->NewGlobalRef(tlsState);
    return datasource->SetCustomTls(tlsState);
}

jobject nativeGetIncrementalState(JNIEnv* /* env */, jclass /* clazz */, jlong dataSourcePtr) {
    sp<PerfettoDataSource> datasource = reinterpret_cast<PerfettoDataSource*>(dataSourcePtr);
    return datasource->GetIncrementalState();
}

void nativeSetIncrementalState(JNIEnv* env, jclass /* clazz */, jlong dataSourcePtr,
                               jobject incrementalState) {
    sp<PerfettoDataSource> datasource = reinterpret_cast<PerfettoDataSource*>(dataSourcePtr);
    incrementalState = env->NewGlobalRef(incrementalState);
    return datasource->SetIncrementalState(incrementalState);
}

const JNINativeMethod gMethods[] = {
        /* name, signature, funcPtr */
        {"nativeCreate", "(Landroid/tracing/perfetto/DataSource;Ljava/lang/String;)J",
         (void*)nativeCreate},
        {"nativeFlushAll", "(J)V", (void*)nativeFlushAll},
        {"nativeGetFinalizer", "()J", (void*)nativeGetFinalizer},
        {"nativeRegisterDataSource", "(JIZZ)V", (void*)nativeRegisterDataSource},
        {"nativeGetPerfettoInstanceLocked", "(JI)Landroid/tracing/perfetto/DataSourceInstance;",
         (void*)nativeGetPerfettoInstanceLocked},
        {"nativeReleasePerfettoInstanceLocked", "(JI)V",
         (void*)nativeReleasePerfettoInstanceLocked},

        {"nativePerfettoDsTraceIterateBegin", "(J)Z", (void*)nativePerfettoDsTraceIterateBegin},
        {"nativePerfettoDsTraceIterateNext", "(J)Z", (void*)nativePerfettoDsTraceIterateNext},
        {"nativePerfettoDsTraceIterateBreak", "(J)V", (void*)nativePerfettoDsTraceIterateBreak},
        {"nativeGetPerfettoDsInstanceIndex", "(J)I", (void*)nativeGetPerfettoDsInstanceIndex},

        {"nativeWritePackets", "(J[[B)V", (void*)nativeWritePackets}};

const JNINativeMethod gMethodsTracingContext[] = {
        /* name, signature, funcPtr */
        {"nativeGetCustomTls", "(J)Ljava/lang/Object;", (void*)nativeGetCustomTls},
        {"nativeGetIncrementalState", "(J)Ljava/lang/Object;", (void*)nativeGetIncrementalState},
        {"nativeSetCustomTls", "(JLjava/lang/Object;)V", (void*)nativeSetCustomTls},
        {"nativeSetIncrementalState", "(JLjava/lang/Object;)V", (void*)nativeSetIncrementalState},
};

int register_android_tracing_PerfettoDataSource(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "android/tracing/perfetto/DataSource", gMethods,
                                       NELEM(gMethods));

    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register native methods.");

    res = jniRegisterNativeMethods(env, "android/tracing/perfetto/TracingContext",
                                   gMethodsTracingContext, NELEM(gMethodsTracingContext));

    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register native methods.");

    if (env->GetJavaVM(&gVm) != JNI_OK) {
        LOG_ALWAYS_FATAL("Failed to get JavaVM from JNIEnv: %p", env);
    }

    jclass clazz = env->FindClass("android/tracing/perfetto/DataSource");
    gPerfettoDataSourceClassInfo.clazz = MakeGlobalRefOrDie(env, clazz);
    gPerfettoDataSourceClassInfo.createInstance =
            env->GetMethodID(gPerfettoDataSourceClassInfo.clazz, "createInstance",
                             "([BI)Landroid/tracing/perfetto/DataSourceInstance;");
    gPerfettoDataSourceClassInfo.createTlsState =
            env->GetMethodID(gPerfettoDataSourceClassInfo.clazz, "createTlsState",
                             "(Landroid/tracing/perfetto/CreateTlsStateArgs;)Ljava/lang/Object;");
    gPerfettoDataSourceClassInfo.createIncrementalState =
            env->GetMethodID(gPerfettoDataSourceClassInfo.clazz, "createIncrementalState",
                             "(Landroid/tracing/perfetto/CreateIncrementalStateArgs;)Ljava/lang/"
                             "Object;");

    clazz = env->FindClass("android/tracing/perfetto/CreateTlsStateArgs");
    gCreateTlsStateArgsClassInfo.clazz = MakeGlobalRefOrDie(env, clazz);
    gCreateTlsStateArgsClassInfo.init =
            env->GetMethodID(gCreateTlsStateArgsClassInfo.clazz, "<init>",
                             "(Landroid/tracing/perfetto/DataSource;I)V");

    clazz = env->FindClass("android/tracing/perfetto/CreateIncrementalStateArgs");
    gCreateIncrementalStateArgsClassInfo.clazz = MakeGlobalRefOrDie(env, clazz);
    gCreateIncrementalStateArgsClassInfo.init =
            env->GetMethodID(gCreateIncrementalStateArgsClassInfo.clazz, "<init>",
                             "(Landroid/tracing/perfetto/DataSource;I)V");

    return 0;
}

} // namespace android