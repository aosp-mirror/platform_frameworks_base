/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include <cputimeinstate.h>
#include <nativehelper/ScopedPrimitiveArray.h>

#include "LongArrayMultiStateCounter.h"
#include "core_jni_helpers.h"

namespace android {

static constexpr uint64_t NSEC_PER_MSEC = 1000000;

static jlongArray copyVecsToArray(JNIEnv *env, std::vector<std::vector<uint64_t>> &vec) {
    jsize s = 0;
    for (const auto &subVec : vec) s += subVec.size();
    jlongArray ar = env->NewLongArray(s);
    jsize start = 0;
    for (auto &subVec : vec) {
        for (uint32_t i = 0; i < subVec.size(); ++i) subVec[i] /= NSEC_PER_MSEC;
        env->SetLongArrayRegion(ar, start, subVec.size(),
                                reinterpret_cast<const jlong*>(subVec.data()));
        start += subVec.size();
    }
    return ar;
}

static jlongArray getUidCpuFreqTimeMs(JNIEnv *env, jclass, jint uid) {
    auto out = android::bpf::getUidCpuFreqTimes(uid);
    if (!out) return env->NewLongArray(0);
    return copyVecsToArray(env, out.value());
}

/**
 * Computes delta of CPU time-in-freq from the previously supplied counts and adds the delta
 * to the supplied multi-state counter in accordance with the counter's state.
 */
static jboolean addCpuTimeInFreqDelta(
        JNIEnv *env, jint uid, jlong counterNativePtr, jlong timestampMs,
        std::optional<std::vector<std::vector<uint64_t>>> timeInFreqDataNanos,
        jlongArray deltaOut) {
    if (!timeInFreqDataNanos) {
        return false;
    }

    auto counter = reinterpret_cast<battery::LongArrayMultiStateCounter *>(counterNativePtr);
    size_t s = 0;
    for (const auto &cluster : *timeInFreqDataNanos) s += cluster.size();

    battery::Uint64ArrayRW flattened(s);
    uint64_t *out = flattened.dataRW();
    auto offset = out;
    for (const auto &cluster : *timeInFreqDataNanos) {
        memcpy(offset, cluster.data(), cluster.size() * sizeof(uint64_t));
        offset += cluster.size();
    }
    for (size_t i = 0; i < s; ++i) {
        out[i] /= NSEC_PER_MSEC;
    }
    if (s != counter->getCount(0).size()) { // Counter has at least one state
        ALOGE("Mismatch between eBPF data size (%d) and the counter size (%d)", (int)s,
              (int)counter->getCount(0).size());
        return false;
    }

    const battery::Uint64Array &delta = counter->updateValue(flattened, timestampMs);
    if (deltaOut) {
        ScopedLongArrayRW scopedArray(env, deltaOut);
        uint64_t *array = reinterpret_cast<uint64_t *>(scopedArray.get());
        if (delta.data() != nullptr) {
            memcpy(array, delta.data(), s * sizeof(uint64_t));
        } else {
            memset(array, 0, s * sizeof(uint64_t));
        }
    }

    return true;
}

static jboolean addDeltaFromBpf(JNIEnv *env, jlong self, jint uid, jlong counterNativePtr,
                                jlong timestampMs, jlongArray deltaOut) {
    return addCpuTimeInFreqDelta(env, uid, counterNativePtr, timestampMs,
                                 android::bpf::getUidCpuFreqTimes(uid), deltaOut);
}

static jboolean addDeltaForTest(JNIEnv *env, jclass, jint uid, jlong counterNativePtr,
                                jlong timestampMs, jobjectArray timeInFreqDataNanos,
                                jlongArray deltaOut) {
    if (!timeInFreqDataNanos) {
        return addCpuTimeInFreqDelta(env, uid, counterNativePtr, timestampMs,
                                     std::optional<std::vector<std::vector<uint64_t>>>(), deltaOut);
    }

    std::vector<std::vector<uint64_t>> timeInFreqData;
    jsize len = env->GetArrayLength(timeInFreqDataNanos);
    for (jsize i = 0; i < len; i++) {
        std::vector<uint64_t> cluster;
        ScopedLongArrayRO row(env, (jlongArray)env->GetObjectArrayElement(timeInFreqDataNanos, i));
        cluster.reserve(row.size());
        for (size_t j = 0; j < row.size(); j++) {
            cluster.push_back(row[j]);
        }
        timeInFreqData.push_back(cluster);
    }
    return addCpuTimeInFreqDelta(env, uid, counterNativePtr, timestampMs,
                                 std::optional(timeInFreqData), deltaOut);
}

static const JNINativeMethod g_single_methods[] = {
        {"readBpfData", "(I)[J", (void *)getUidCpuFreqTimeMs},
        {"addDeltaFromBpf", "(IJJ[J)Z", (void *)addDeltaFromBpf},

        // Used for testing
        {"addDeltaForTest", "(IJJ[[J[J)Z", (void *)addDeltaForTest},
};

int register_com_android_internal_os_KernelSingleUidTimeReader(JNIEnv *env) {
    return RegisterMethodsOrDie(env, "com/android/internal/os/KernelSingleUidTimeReader$Injector",
                                g_single_methods, NELEM(g_single_methods));
}

}
