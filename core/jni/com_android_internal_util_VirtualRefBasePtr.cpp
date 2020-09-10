/*
 * Copyright (C) 2014 The Android Open Source Project
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

#include <nativehelper/JNIHelp.h>
#include <utils/LightRefBase.h>
#include "core_jni_helpers.h"

namespace android {

static void incStrong(JNIEnv* env, jobject clazz, jlong objPtr) {
    VirtualLightRefBase* obj = reinterpret_cast<VirtualLightRefBase*>(objPtr);
    obj->incStrong(0);
}

static void decStrong(JNIEnv* env, jobject clazz, jlong objPtr) {
    VirtualLightRefBase* obj = reinterpret_cast<VirtualLightRefBase*>(objPtr);
    obj->decStrong(0);
}

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "com/android/internal/util/VirtualRefBasePtr";

static const JNINativeMethod gMethods[] = {
    { "nIncStrong", "(J)V", (void*) incStrong },
    { "nDecStrong", "(J)V", (void*) decStrong },
};

int register_com_android_internal_util_VirtualRefBasePtr(JNIEnv* env) {
    return RegisterMethodsOrDie(env, kClassPathName, gMethods, NELEM(gMethods));
}


} // namespace android
