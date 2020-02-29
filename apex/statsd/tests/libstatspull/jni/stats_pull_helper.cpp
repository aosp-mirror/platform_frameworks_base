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

#include <android/binder_process.h>
#include <jni.h>
#include <log/log.h>
#include <stats_event.h>
#include <stats_pull_atom_callback.h>

#include <chrono>
#include <thread>

using std::this_thread::sleep_for;

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
        ABinderProcess_setThreadPoolMaxThreadCount(9);
        ABinderProcess_startThreadPool();
    }
}

static AStatsManager_PullAtomCallbackReturn pullAtomCallback(int32_t atomTag, AStatsEventList* data,
                                                             void* /*cookie*/) {
    sNumPulls++;
    sleep_for(std::chrono::milliseconds(sLatencyMillis));
    for (int i = 0; i < sAtomsPerPull; i++) {
        AStatsEvent* event = AStatsEventList_addStatsEvent(data);
        AStatsEvent_setAtomId(event, atomTag);
        AStatsEvent_writeInt64(event, (int64_t) sNumPulls);
        AStatsEvent_build(event);
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
    AStatsManager_PullAtomMetadata* metadata = AStatsManager_PullAtomMetadata_obtain();
    AStatsManager_PullAtomMetadata_setCoolDownNs(metadata, coolDownNs);
    AStatsManager_PullAtomMetadata_setTimeoutNs(metadata, timeoutNs);

    AStatsManager_registerPullAtomCallback(sAtomTag, &pullAtomCallback, metadata, nullptr);
    AStatsManager_PullAtomMetadata_release(metadata);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_android_internal_os_statsd_libstats_LibStatsPullTests_unregisterStatsPuller(
        JNIEnv* /*env*/, jobject /* this */, jint /*atomTag*/)
{
    AStatsManager_unregisterPullAtomCallback(sAtomTag);
}
} // namespace
