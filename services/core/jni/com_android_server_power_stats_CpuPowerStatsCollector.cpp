/*
 * Copyright (C) 2023 The Android Open Source Project
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

#define LOG_TAG "CpuPowerStatsCollector"

#include <cputimeinstate.h>
#include <log/log.h>
#include <nativehelper/ScopedPrimitiveArray.h>

#include "core_jni_helpers.h"

#define EXCEPTION (-1)

namespace android {

#define JAVA_CLASS_CPU_POWER_STATS_COLLECTOR "com/android/server/power/stats/CpuPowerStatsCollector"
#define JAVA_CLASS_KERNEL_CPU_STATS_READER \
    JAVA_CLASS_CPU_POWER_STATS_COLLECTOR "$KernelCpuStatsReader"
#define JAVA_CLASS_KERNEL_CPU_STATS_CALLBACK \
    JAVA_CLASS_CPU_POWER_STATS_COLLECTOR "$KernelCpuStatsCallback"

static constexpr uint64_t NSEC_PER_MSEC = 1000000;

static int extractUidStats(JNIEnv *env, std::vector<std::vector<uint64_t>> &times,
                           ScopedIntArrayRO &scopedScalingStepToPowerBracketMap,
                           jlongArray tempForUidStats);

static bool initialized = false;
static jclass class_KernelCpuStatsCallback;
static jmethodID method_KernelCpuStatsCallback_processUidStats;

static int init(JNIEnv *env) {
    jclass temp = env->FindClass(JAVA_CLASS_KERNEL_CPU_STATS_CALLBACK);
    class_KernelCpuStatsCallback = (jclass)env->NewGlobalRef(temp);
    if (!class_KernelCpuStatsCallback) {
        jniThrowExceptionFmt(env, "java/lang/ClassNotFoundException",
                             "Class not found: " JAVA_CLASS_KERNEL_CPU_STATS_CALLBACK);
        return EXCEPTION;
    }
    method_KernelCpuStatsCallback_processUidStats =
            env->GetMethodID(class_KernelCpuStatsCallback, "processUidStats", "(I[J)V");
    if (!method_KernelCpuStatsCallback_processUidStats) {
        jniThrowExceptionFmt(env, "java/lang/NoSuchMethodException",
                             "Method not found: " JAVA_CLASS_KERNEL_CPU_STATS_CALLBACK
                             ".processUidStats");
        return EXCEPTION;
    }
    initialized = true;
    return OK;
}

static jlong nativeReadCpuStats(JNIEnv *env, [[maybe_unused]] jobject zis, jobject callback,
                                jintArray scalingStepToPowerBracketMap,
                                jlong lastUpdateTimestampNanos, jlongArray tempForUidStats) {
    if (!initialized) {
        if (init(env) == EXCEPTION) {
            return 0L;
        }
    }

    uint64_t newLastUpdate = lastUpdateTimestampNanos;
    auto data = android::bpf::getUidsUpdatedCpuFreqTimes(&newLastUpdate);
    if (!data.has_value()) return lastUpdateTimestampNanos;

    ScopedIntArrayRO scopedScalingStepToPowerBracketMap(env, scalingStepToPowerBracketMap);

    for (auto &[uid, times] : *data) {
        int status =
                extractUidStats(env, times, scopedScalingStepToPowerBracketMap, tempForUidStats);
        if (status == EXCEPTION) {
            return 0L;
        }
        env->CallVoidMethod(callback, method_KernelCpuStatsCallback_processUidStats, (jint)uid,
                            tempForUidStats);
    }
    return newLastUpdate;
}

static int extractUidStats(JNIEnv *env, std::vector<std::vector<uint64_t>> &times,
                           ScopedIntArrayRO &scopedScalingStepToPowerBracketMap,
                           jlongArray tempForUidStats) {
    ScopedLongArrayRW scopedTempForStats(env, tempForUidStats);
    uint64_t *arrayForStats = reinterpret_cast<uint64_t *>(scopedTempForStats.get());
    const uint8_t statsSize = scopedTempForStats.size();
    memset(arrayForStats, 0, statsSize * sizeof(uint64_t));
    const uint8_t scalingStepCount = scopedScalingStepToPowerBracketMap.size();

    uint32_t scalingStep = 0;
    for (const auto &subVec : times) {
        for (uint32_t i = 0; i < subVec.size(); ++i) {
            if (scalingStep >= scalingStepCount) {
                jniThrowExceptionFmt(env, "java/lang/IndexOutOfBoundsException",
                                     "scalingStepToPowerBracketMap is too short, "
                                     "size=%u, scalingStep=%u",
                                     scalingStepCount, scalingStep);
                return EXCEPTION;
            }
            uint32_t bucket = scopedScalingStepToPowerBracketMap[scalingStep];
            if (bucket >= statsSize) {
                jniThrowExceptionFmt(env, "java/lang/IndexOutOfBoundsException",
                                     "UidStats array is too short, length=%u, bucket[%u]=%u",
                                     statsSize, scalingStep, bucket);
                return EXCEPTION;
            }
            arrayForStats[bucket] += subVec[i] / NSEC_PER_MSEC;
            scalingStep++;
        }
    }
    return OK;
}

static const JNINativeMethod method_table[] = {
        {"nativeReadCpuStats", "(L" JAVA_CLASS_KERNEL_CPU_STATS_CALLBACK ";[IJ[J)J",
         (void *)nativeReadCpuStats},
};

int register_android_server_power_stats_CpuPowerStatsCollector(JNIEnv *env) {
    return jniRegisterNativeMethods(env, JAVA_CLASS_KERNEL_CPU_STATS_READER, method_table,
                                    NELEM(method_table));
}

} // namespace android
