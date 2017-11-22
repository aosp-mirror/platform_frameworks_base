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

#include <hidl/HidlTransportSupport.h>
#include <nativehelper/JNIHelp.h>

#include "core_jni_helpers.h"

namespace android {
static jint android_os_HidlSupport_getPidIfSharable(JNIEnv*, jclass) {
    return android::hardware::details::getPidIfSharable();
}

static const JNINativeMethod gHidlSupportMethods[] = {
    {"getPidIfSharable", "()I", (void*)android_os_HidlSupport_getPidIfSharable},
};

const char* const kHidlSupportPathName = "android/os/HidlSupport";

int register_android_os_HidlSupport(JNIEnv* env)
{
    return RegisterMethodsOrDie(env, kHidlSupportPathName, gHidlSupportMethods, NELEM(gHidlSupportMethods));
}

}  // namespace android
