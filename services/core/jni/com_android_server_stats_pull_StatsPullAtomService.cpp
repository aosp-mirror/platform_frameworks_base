/*
 * Copyright 2020 The Android Open Source Project
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

#define LOG_TAG "StatsPullAtomService"

#include <jni.h>
#include <log/log.h>
#include <nativehelper/JNIHelp.h>
#include <stats_event.h>
#include <stats_pull_atom_callback.h>
#include <statslog.h>

#include "stats/SurfaceFlingerPuller.h"

namespace android {

static server::stats::SurfaceFlingerPuller gSurfaceFlingerPuller;

static AStatsManager_PullAtomCallbackReturn onSurfaceFlingerPullCallback(int32_t atom_tag,
                                                                         AStatsEventList* data,
                                                                         void* cookie) {
    return gSurfaceFlingerPuller.pull(atom_tag, data);
}

static void initializeNativePullers(JNIEnv* env, jobject javaObject) {
    // Surface flinger layer & global info.
    gSurfaceFlingerPuller = server::stats::SurfaceFlingerPuller();
    AStatsManager_setPullAtomCallback(android::util::SURFACEFLINGER_STATS_GLOBAL_INFO,
                                      /* metadata= */ nullptr, onSurfaceFlingerPullCallback,
                                      /* cookie= */ nullptr);
    AStatsManager_setPullAtomCallback(android::util::SURFACEFLINGER_STATS_LAYER_INFO,
                                      /* metadata= */ nullptr, onSurfaceFlingerPullCallback,
                                      /* cookie= */ nullptr);
}

static const JNINativeMethod sMethods[] = {
        {"initializeNativePullers", "()V", (void*)initializeNativePullers}};

int register_android_server_stats_pull_StatsPullAtomService(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "com/android/server/stats/pull/StatsPullAtomService",
                                       sMethods, NELEM(sMethods));
    if (res < 0) {
        ALOGE("failed to register native methods");
    }
    return res;
}

} // namespace android