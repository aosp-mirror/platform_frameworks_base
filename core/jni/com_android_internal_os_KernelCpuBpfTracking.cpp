/*
 * Copyright (C) 2021 The Android Open Source Project
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

#include "core_jni_helpers.h"

#include <cputimeinstate.h>

namespace android {

static jboolean KernelCpuBpfTracking_isSupported(JNIEnv *, jobject) {
    return android::bpf::isTrackingUidTimesSupported() ? JNI_TRUE : JNI_FALSE;
}

static jboolean KernelCpuBpfTracking_startTrackingInternal(JNIEnv *, jobject) {
    return android::bpf::startTrackingUidTimes();
}

static jlongArray KernelCpuBpfTracking_getFreqsInternal(JNIEnv *env, jobject) {
    auto freqs = android::bpf::getCpuFreqs();
    if (!freqs) return nullptr;

    std::vector<uint64_t> allFreqs;
    for (const auto &vec : *freqs) std::copy(vec.begin(), vec.end(), std::back_inserter(allFreqs));

    auto ar = env->NewLongArray(allFreqs.size());
    if (ar != nullptr) {
        env->SetLongArrayRegion(ar, 0, allFreqs.size(),
                                reinterpret_cast<const jlong *>(allFreqs.data()));
    }
    return ar;
}

static jintArray KernelCpuBpfTracking_getFreqsClustersInternal(JNIEnv *env, jobject) {
    auto freqs = android::bpf::getCpuFreqs();
    if (!freqs) return nullptr;

    std::vector<uint32_t> freqsClusters;
    uint32_t clusters = freqs->size();
    for (uint32_t c = 0; c < clusters; ++c) {
        freqsClusters.insert(freqsClusters.end(), (*freqs)[c].size(), c);
    }

    auto ar = env->NewIntArray(freqsClusters.size());
    if (ar != nullptr) {
        env->SetIntArrayRegion(ar, 0, freqsClusters.size(),
                               reinterpret_cast<const jint *>(freqsClusters.data()));
    }
    return ar;
}

static const JNINativeMethod methods[] = {
        {"isSupported", "()Z", (void *)KernelCpuBpfTracking_isSupported},
        {"startTrackingInternal", "()Z", (void *)KernelCpuBpfTracking_startTrackingInternal},
        {"getFreqsInternal", "()[J", (void *)KernelCpuBpfTracking_getFreqsInternal},
        {"getFreqsClustersInternal", "()[I", (void *)KernelCpuBpfTracking_getFreqsClustersInternal},
};

int register_com_android_internal_os_KernelCpuBpfTracking(JNIEnv *env) {
    return RegisterMethodsOrDie(env, "com/android/internal/os/KernelCpuBpfTracking", methods,
                                NELEM(methods));
}

} // namespace android
