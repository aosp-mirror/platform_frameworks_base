/*
 * Copyright (C) 2010 The Android Open Source Project
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

#include <poll.h>
#include <android/dlext.h>

#include "core_jni_helpers.h"

namespace android
{

static jstring getDlWarning_native(JNIEnv* env, jobject) {
    const char* text = android_dlwarning();
    return text == nullptr ? nullptr : env->NewStringUTF(text);
}

static const JNINativeMethod g_methods[] = {
    { "getDlWarning",
        "()Ljava/lang/String;",
        reinterpret_cast<void*>(getDlWarning_native) },
};

static const char* const kActivityPathName = "android/app/Activity";

int register_android_app_Activity(JNIEnv* env) {
    return RegisterMethodsOrDie(env, kActivityPathName, g_methods, NELEM(g_methods));
}

} // namespace android
