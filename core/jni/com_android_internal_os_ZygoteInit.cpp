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

#define LOG_TAG "Zygote"

#include <android/graphics/jni_runtime.h>
#include <ui/GraphicBufferMapper.h>

#include "core_jni_helpers.h"

namespace {

// Shadow call stack (SCS) is a security mitigation that uses a separate stack
// (the SCS) for return addresses. In versions of Android newer than P, the
// compiler cooperates with the system to ensure that the SCS address is always
// stored in register x18, as long as the app was compiled with a new enough
// compiler and does not use features that rely on SP-HALs (this restriction is
// because the SP-HALs might not preserve x18 due to potentially having been
// compiled with an old compiler as a consequence of Treble; it generally means
// that the app must be a system app without a UI). This struct is used to
// temporarily store the address on the stack while preloading the SP-HALs, so
// that such apps can use the same zygote as everything else.
struct ScopedSCSExit {
#ifdef __aarch64__
    void* scs;

    ScopedSCSExit() {
        __asm__ __volatile__("str x18, [%0]" ::"r"(&scs));
    }

    ~ScopedSCSExit() {
        __asm__ __volatile__("ldr x18, [%0]; str xzr, [%0]" ::"r"(&scs));
    }
#else
    // Silence unused variable warnings in non-SCS builds.
    ScopedSCSExit() {}
    ~ScopedSCSExit() {}
#endif
};

void android_internal_os_ZygoteInit_nativePreloadAppProcessHALs(JNIEnv* env, jclass) {
    ScopedSCSExit x;
    android::GraphicBufferMapper::preloadHal();
    // Add preloading here for other HALs that are (a) always passthrough, and
    // (b) loaded by most app processes.
}

void android_internal_os_ZygoteInit_nativePreloadGraphicsDriver(JNIEnv* env, jclass) {
    ScopedSCSExit x;
    zygote_preload_graphics();
}

const JNINativeMethod gMethods[] = {
    { "nativePreloadAppProcessHALs", "()V",
      (void*)android_internal_os_ZygoteInit_nativePreloadAppProcessHALs },
    { "nativePreloadGraphicsDriver", "()V",
      (void*)android_internal_os_ZygoteInit_nativePreloadGraphicsDriver },
};

}  // anonymous namespace

namespace android {

int register_com_android_internal_os_ZygoteInit(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "com/android/internal/os/ZygoteInit",
            gMethods, NELEM(gMethods));
}

}  // namespace android
