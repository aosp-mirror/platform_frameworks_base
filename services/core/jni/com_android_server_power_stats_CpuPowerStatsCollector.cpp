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

static int flatten(JNIEnv *env, const std::vector<std::vector<uint64_t>> &times,
                   jlongArray outArray);

static int combineByBracket(JNIEnv *env, const std::vector<std::vector<uint64_t>> &times,
                            ScopedIntArrayRO &scopedScalingStepToPowerBracketMap,
                            jlongArray outBrackets);

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

static jboolean nativeIsSupportedFeature(JNIEnv *env) {
    if (!android::bpf::startTrackingUidTimes()) {
        return false;
    }
    auto totalByScalingStep = android::bpf::getTotalCpuFreqTimes();
    return totalByScalingStep.has_value();
}

static jlong nativeReadCpuStats(JNIEnv *env, [[maybe_unused]] jobject zis, jobject callback,
                                jintArray scalingStepToPowerBracketMap,
                                jlong lastUpdateTimestampNanos, jlongArray cpuTimeByScalingStep,
                                jlongArray tempForUidStats) {
    ScopedIntArrayRO scopedScalingStepToPowerBracketMap(env, scalingStepToPowerBracketMap);

    if (!initialized) {
        if (init(env) == EXCEPTION) {
            return 0L;
        }
    }

    auto totalByScalingStep = android::bpf::getTotalCpuFreqTimes();
    if (!totalByScalingStep) {
        jniThrowExceptionFmt(env, "java/lang/RuntimeException", "Unsupported kernel feature");
        return EXCEPTION;
    }

    if (flatten(env, *totalByScalingStep, cpuTimeByScalingStep) == EXCEPTION) {
        return 0L;
    }

    uint64_t newLastUpdate = lastUpdateTimestampNanos;
    auto data = android::bpf::getUidsUpdatedCpuFreqTimes(&newLastUpdate);
    if (!data.has_value()) return lastUpdateTimestampNanos;

    for (auto &[uid, times] : *data) {
        if (combineByBracket(env, times, scopedScalingStepToPowerBracketMap, tempForUidStats) ==
            EXCEPTION) {
            return 0L;
        }
        env->CallVoidMethod(callback, method_KernelCpuStatsCallback_processUidStats, (jint)uid,
                            tempForUidStats);
    }
    return newLastUpdate;
}

static int flatten(JNIEnv *env, const std::vector<std::vector<uint64_t>> &times,
                   jlongArray outArray) {
    ScopedLongArrayRW scopedOutArray(env, outArray);
    const uint8_t scalingStepCount = scopedOutArray.size();
    uint64_t *out = reinterpret_cast<uint64_t *>(scopedOutArray.get());
    uint32_t scalingStep = 0;
    for (const auto &subVec : times) {
        for (uint32_t i = 0; i < subVec.size(); ++i) {
            if (scalingStep >= scalingStepCount) {
                jniThrowExceptionFmt(env, "java/lang/IndexOutOfBoundsException",
                                     "Array is too short, size=%u, scalingStep=%u",
                                     scalingStepCount, scalingStep);
                return EXCEPTION;
            }
            out[scalingStep] = subVec[i] / NSEC_PER_MSEC;
            scalingStep++;
        }
    }
    return OK;
}

static int combineByBracket(JNIEnv *env, const std::vector<std::vector<uint64_t>> &times,
                            ScopedIntArrayRO &scopedScalingStepToPowerBracketMap,
                            jlongArray outBrackets) {
    ScopedLongArrayRW scopedOutBrackets(env, outBrackets);
    uint64_t *brackets = reinterpret_cast<uint64_t *>(scopedOutBrackets.get());
    const uint8_t statsSize = scopedOutBrackets.size();
    memset(brackets, 0, statsSize * sizeof(uint64_t));
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
            uint32_t bracket = scopedScalingStepToPowerBracketMap[scalingStep];
            if (bracket >= statsSize) {
                jniThrowExceptionFmt(env, "java/lang/IndexOutOfBoundsException",
                                     "Bracket array is too short, length=%u, bracket[%u]=%u",
                                     statsSize, scalingStep, bracket);
                return EXCEPTION;
            }
            brackets[bracket] += subVec[i] / NSEC_PER_MSEC;
            scalingStep++;
        }
    }
    return OK;
}

static const JNINativeMethod method_table[] = {
        {"nativeIsSupportedFeature", "()Z", (void *)nativeIsSupportedFeature},
        {"nativeReadCpuStats", "(L" JAVA_CLASS_KERNEL_CPU_STATS_CALLBACK ";[IJ[J[J)J",
         (void *)nativeReadCpuStats},
};

int register_android_server_power_stats_CpuPowerStatsCollector(JNIEnv *env) {
    return jniRegisterNativeMethods(env, JAVA_CLASS_KERNEL_CPU_STATS_READER, method_table,
                                    NELEM(method_table));
}

} // namespace android
