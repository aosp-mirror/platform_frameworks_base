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

static jlongArray KernelCpuTotalBpfMapReader_readInternal(JNIEnv *env, jobject) {
    auto freqTimes = android::bpf::getTotalCpuFreqTimes();
    if (!freqTimes) return nullptr;

    std::vector<uint64_t> allTimes;
    for (const auto &vec : *freqTimes) {
        for (const auto &timeNs : vec) {
            allTimes.push_back(timeNs / 1000000);
        }
    }

    auto ar = env->NewLongArray(allTimes.size());
    if (ar != nullptr) {
        env->SetLongArrayRegion(ar, 0, allTimes.size(),
                                reinterpret_cast<const jlong *>(allTimes.data()));
    }
    return ar;
}

static const JNINativeMethod methods[] = {
        {"readInternal", "()[J", (void *)KernelCpuTotalBpfMapReader_readInternal},
};

int register_com_android_internal_os_KernelCpuTotalBpfMapReader(JNIEnv *env) {
    return RegisterMethodsOrDie(env, "com/android/internal/os/KernelCpuTotalBpfMapReader", methods,
                                NELEM(methods));
}

} // namespace android
