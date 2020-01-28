/*
 * Copyright (C) 2019, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <binder/ProcessState.h>
#include <jni.h>
#include <log/log.h>
#include <stats_event.h>
#include <stats_pull_atom_callback.h>

#include <chrono>
#include <thread>

using std::this_thread::sleep_for;
using namespace android;

namespace {
static int32_t sAtomTag;
static int32_t sPullReturnVal;
static int64_t sLatencyMillis;
static int32_t sAtomsPerPull;
static int32_t sNumPulls = 0;

static bool initialized = false;

static void init() {
    if (!initialized) {
        initialized = true;
        // Set up the binder
        sp<ProcessState> ps(ProcessState::self());
        ps->setThreadPoolMaxThreadCount(9);
        ps->startThreadPool();
        ps->giveThreadPoolName();
    }
}

static status_pull_atom_return_t pullAtomCallback(int32_t atomTag, pulled_stats_event_list* data,
                                                  void* /*cookie*/) {
    sNumPulls++;
    sleep_for(std::chrono::milliseconds(sLatencyMillis));
    for (int i = 0; i < sAtomsPerPull; i++) {
        stats_event* event = add_stats_event_to_pull_data(data);
        stats_event_set_atom_id(event, atomTag);
        stats_event_write_int64(event, (int64_t) sNumPulls);
        stats_event_build(event);
    }
    return sPullReturnVal;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_android_internal_os_statsd_libstats_LibStatsPullTests_registerStatsPuller(
        JNIEnv* /*env*/, jobject /* this */, jint atomTag, jlong timeoutNs, jlong coolDownNs,
        jint pullRetVal, jlong latencyMillis, int atomsPerPull)
{
    init();
    sAtomTag = atomTag;
    sPullReturnVal = pullRetVal;
    sLatencyMillis = latencyMillis;
    sAtomsPerPull = atomsPerPull;
    sNumPulls = 0;
    pull_atom_metadata metadata = {.cool_down_ns = coolDownNs,
                                   .timeout_ns = timeoutNs,
                                   .additive_fields = nullptr,
                                   .additive_fields_size = 0};
    register_stats_pull_atom_callback(sAtomTag, &pullAtomCallback, &metadata, nullptr);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_android_internal_os_statsd_libstats_LibStatsPullTests_unregisterStatsPuller(
        JNIEnv* /*env*/, jobject /* this */, jint /*atomTag*/)
{
    unregister_stats_pull_atom_callback(sAtomTag);
}
} // namespace