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

static const JNINativeMethod g_single_methods[] = {
    {"readBpfData", "(I)[J", (void *)getUidCpuFreqTimeMs},
};

int register_com_android_internal_os_KernelSingleUidTimeReader(JNIEnv *env) {
    return RegisterMethodsOrDie(env, "com/android/internal/os/KernelSingleUidTimeReader$Injector",
                                g_single_methods, NELEM(g_single_methods));
}

}
