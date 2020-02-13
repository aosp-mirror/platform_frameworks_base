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

#define LOG_TAG "MotionEvent-JNI"

#include "android_view_VerifiedKeyEvent.h"
#include <input/Input.h>
#include "core_jni_helpers.h"

namespace android {

// ----------------------------------------------------------------------------

jobject android_view_VerifiedKeyEvent(JNIEnv* env, const VerifiedKeyEvent& event) {
    static jclass clazz = FindClassOrDie(env, "android/view/VerifiedKeyEvent");

    static jmethodID constructor = GetMethodIDOrDie(env, clazz, "<init>", "(IJIIIJIIIII)V");

    jobject object =
            env->NewObject(clazz, constructor, event.deviceId, event.eventTimeNanos, event.source,
                           event.displayId, event.action, event.downTimeNanos, event.flags,
                           event.keyCode, event.scanCode, event.metaState, event.repeatCount);
    return object;
}

} // namespace android
