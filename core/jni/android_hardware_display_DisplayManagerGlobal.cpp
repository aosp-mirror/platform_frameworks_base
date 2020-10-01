/*
 * Copyright 2020 The Android Open Source Project
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

#define LOG_TAG "DisplayManagerGlobal-JNI"

#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedUtfChars.h>
#include <private/android/choreographer.h>

#include <vector>

#include "core_jni_helpers.h"

using namespace android;

namespace android {

// Dispatches the current refresh rate for the default display to all
// choreographer instances
void android_hardware_display_DisplayManagerGlobal_signalNativeCallbacks(JNIEnv* env, jobject,
                                                                         jfloat refreshRate) {
    const constexpr int64_t kNanosPerSecond = 1000 * 1000 * 1000;
    const nsecs_t vsyncPeriod = kNanosPerSecond / refreshRate;

    AChoreographer_signalRefreshRateCallbacks(vsyncPeriod);
}

} // namespace android

// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/hardware/display/DisplayManagerGlobal";

static const JNINativeMethod gMethods[] = {
        {"nSignalNativeCallbacks", "(F)V",
         (void*)android_hardware_display_DisplayManagerGlobal_signalNativeCallbacks},
};

int register_android_hardware_display_DisplayManagerGlobal(JNIEnv* env) {
    AChoreographer_initJVM(env);
    return RegisterMethodsOrDie(env, kClassPathName, gMethods, NELEM(gMethods));
}
