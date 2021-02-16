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

#include "core_jni_helpers.h"

#include <cputimeinstate.h>

namespace android {

static jboolean KernelCpuTotalBpfMapReader_read(JNIEnv *env, jobject, jobject callback) {
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID callbackMethod = env->GetMethodID(callbackClass, "accept", "(IIJ)V");
    if (callbackMethod == 0) {
        return JNI_FALSE;
    }

    auto freqs = android::bpf::getCpuFreqs();
    if (!freqs) return JNI_FALSE;
    auto freqTimes = android::bpf::getTotalCpuFreqTimes();
    if (!freqTimes) return JNI_FALSE;

    auto freqsClusterSize = (*freqs).size();
    for (uint32_t clusterIndex = 0; clusterIndex < freqsClusterSize; ++clusterIndex) {
        auto freqsSize = (*freqs)[clusterIndex].size();
        for (uint32_t freqIndex = 0; freqIndex < freqsSize; ++freqIndex) {
            env->CallVoidMethod(callback, callbackMethod, clusterIndex,
                                (*freqs)[clusterIndex][freqIndex],
                                (*freqTimes)[clusterIndex][freqIndex] / 1000000);
        }
    }
    return JNI_TRUE;
}

static const JNINativeMethod methods[] = {
        {"read", "(Lcom/android/internal/os/KernelCpuTotalBpfMapReader$Callback;)Z",
         (void *)KernelCpuTotalBpfMapReader_read},
};

int register_com_android_internal_os_KernelCpuTotalBpfMapReader(JNIEnv *env) {
    return RegisterMethodsOrDie(env, "com/android/internal/os/KernelCpuTotalBpfMapReader", methods,
                                NELEM(methods));
}

} // namespace android
