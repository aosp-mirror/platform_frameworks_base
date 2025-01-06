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

// #define LOG_NDEBUG 0
#define LOG_TAG "PerfettoTraceTest"

#include <nativehelper/JNIHelp.h>
#include <utils/Log.h>

#include "jni.h"
#include "perfetto/public/abi/data_source_abi.h"
#include "perfetto/public/abi/heap_buffer.h"
#include "perfetto/public/abi/pb_decoder_abi.h"
#include "perfetto/public/abi/tracing_session_abi.h"
#include "perfetto/public/abi/track_event_abi.h"
#include "perfetto/public/compiler.h"
#include "perfetto/public/data_source.h"
#include "perfetto/public/pb_decoder.h"
#include "perfetto/public/producer.h"
#include "perfetto/public/protos/config/trace_config.pzc.h"
#include "perfetto/public/protos/trace/interned_data/interned_data.pzc.h"
#include "perfetto/public/protos/trace/test_event.pzc.h"
#include "perfetto/public/protos/trace/trace.pzc.h"
#include "perfetto/public/protos/trace/trace_packet.pzc.h"
#include "perfetto/public/protos/trace/track_event/debug_annotation.pzc.h"
#include "perfetto/public/protos/trace/track_event/track_descriptor.pzc.h"
#include "perfetto/public/protos/trace/track_event/track_event.pzc.h"
#include "perfetto/public/protos/trace/trigger.pzc.h"
#include "perfetto/public/te_category_macros.h"
#include "perfetto/public/te_macros.h"
#include "perfetto/public/track_event.h"
#include "protos/perfetto/trace/interned_data/interned_data.pb.h"
#include "protos/perfetto/trace/trace.pb.h"
#include "protos/perfetto/trace/trace_packet.pb.h"
#include "tracing_perfetto.h"
#include "utils.h"

namespace android {
using ::perfetto::protos::EventCategory;
using ::perfetto::protos::EventName;
using ::perfetto::protos::FtraceEvent;
using ::perfetto::protos::FtraceEventBundle;
using ::perfetto::protos::InternedData;
using ::perfetto::protos::Trace;
using ::perfetto::protos::TracePacket;

using ::perfetto::shlib::test_utils::TracingSession;

struct TracingSessionHolder {
    TracingSession tracing_session;
};

static void nativeRegisterPerfetto([[maybe_unused]] JNIEnv* env, jclass /* obj */) {
    tracing_perfetto::registerWithPerfetto(false /* test */);
}

static jlong nativeStartTracing(JNIEnv* env, jclass /* obj */, jbyteArray configBytes) {
    jsize length = env->GetArrayLength(configBytes);
    std::vector<uint8_t> data;
    data.reserve(length);
    env->GetByteArrayRegion(configBytes, 0, length, reinterpret_cast<jbyte*>(data.data()));

    TracingSession session = TracingSession::FromBytes(data.data(), length);
    TracingSessionHolder* holder = new TracingSessionHolder(std::move(session));

    return reinterpret_cast<long>(holder);
}

static jbyteArray nativeStopTracing([[maybe_unused]] JNIEnv* env, jclass /* obj */, jlong ptr) {
    TracingSessionHolder* holder = reinterpret_cast<TracingSessionHolder*>(ptr);

    // Stop
    holder->tracing_session.FlushBlocking(5000);
    holder->tracing_session.StopBlocking();

    std::vector<uint8_t> data = holder->tracing_session.ReadBlocking();

    delete holder;

    jbyteArray bytes = env->NewByteArray(data.size());
    env->SetByteArrayRegion(bytes, 0, data.size(), reinterpret_cast<jbyte*>(data.data()));
    return bytes;
}

extern "C" jint JNI_OnLoad(JavaVM* vm, void* /* reserved */) {
    JNIEnv* env;
    const JNINativeMethod methodTable[] = {/* name, signature, funcPtr */
                                           {"nativeStartTracing", "([B)J",
                                            (void*)nativeStartTracing},
                                           {"nativeStopTracing", "(J)[B", (void*)nativeStopTracing},
                                           {"nativeRegisterPerfetto", "()V",
                                            (void*)nativeRegisterPerfetto}};

    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jniRegisterNativeMethods(env, "android/os/PerfettoTraceTest", methodTable,
                             sizeof(methodTable) / sizeof(JNINativeMethod));

    return JNI_VERSION_1_6;
}

} /* namespace android */
