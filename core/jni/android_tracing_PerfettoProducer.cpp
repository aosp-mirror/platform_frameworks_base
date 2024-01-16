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

#define LOG_TAG "Perfetto"

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>
#include <nativehelper/JNIHelp.h>
#include <perfetto/public/data_source.h>
#include <perfetto/public/producer.h>
#include <perfetto/public/protos/trace/test_event.pzc.h>
#include <perfetto/public/protos/trace/trace_packet.pzc.h>
#include <perfetto/tracing.h>
#include <utils/Log.h>
#include <utils/RefBase.h>

#include <sstream>
#include <thread>

#include "android_tracing_PerfettoDataSource.h"
#include "core_jni_helpers.h"

namespace android {

void perfettoProducerInit(JNIEnv* env, jclass clazz, int backends) {
    struct PerfettoProducerInitArgs args = PERFETTO_PRODUCER_INIT_ARGS_INIT();
    args.backends = (PerfettoBackendTypes)backends;
    PerfettoProducerInit(args);
}

const JNINativeMethod gMethods[] = {
        /* name, signature, funcPtr */
        {"nativePerfettoProducerInit", "(I)V", (void*)perfettoProducerInit},
};

int register_android_tracing_PerfettoProducer(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "android/tracing/perfetto/Producer", gMethods,
                                       NELEM(gMethods));

    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register native methods.");

    return 0;
}

} // namespace android