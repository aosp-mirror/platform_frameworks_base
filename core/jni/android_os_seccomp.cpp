/*
 * Copyright (C) 2017 The Android Open Source Project
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
#include <nativehelper/JniConstants.h>
#include "utils/Log.h"
#include <selinux/selinux.h>

#include "seccomp_policy.h"

static void Seccomp_setPolicy(JNIEnv* /*env*/) {
    if (security_getenforce() == 0) {
        ALOGI("seccomp disabled by setenforce 0");
        return;
    }

    if (!set_seccomp_filter()) {
        ALOGE("Failed to set seccomp policy - killing");
        exit(1);
    }
}

static const JNINativeMethod method_table[] = {
    NATIVE_METHOD(Seccomp, setPolicy, "()V"),
};

namespace android {

int register_android_os_seccomp(JNIEnv* env) {
    return android::RegisterMethodsOrDie(env, "android/os/Seccomp",
                                         method_table, NELEM(method_table));
}

}
