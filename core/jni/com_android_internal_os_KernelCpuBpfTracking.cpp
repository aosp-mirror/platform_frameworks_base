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

static const JNINativeMethod methods[] = {
        {"isSupported", "()Z", (void *)KernelCpuBpfTracking_isSupported},
};

int register_com_android_internal_os_KernelCpuBpfTracking(JNIEnv *env) {
    return RegisterMethodsOrDie(env, "com/android/internal/os/KernelCpuBpfTracking", methods,
                                NELEM(methods));
}

} // namespace android
