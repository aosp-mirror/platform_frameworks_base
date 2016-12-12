/*
 * Copyright 2016 The Android Open Source Project
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

#define LOG_TAG "GraphicsEnvironment"

#include <gui/GraphicsEnv.h>
#include <nativehelper/ScopedUtfChars.h>
#include "core_jni_helpers.h"

namespace {

void setDriverPath(JNIEnv* env, jobject clazz, jstring path) {
    ScopedUtfChars pathChars(env, path);
    android::GraphicsEnv::getInstance().setDriverPath(pathChars.c_str());
}

const JNINativeMethod g_methods[] = {
    { "setDriverPath", "(Ljava/lang/String;)V", reinterpret_cast<void*>(setDriverPath) },
};

const char* const kGraphicsEnvironmentName = "android/os/GraphicsEnvironment";

} // anonymous namespace

namespace android {

int register_android_os_GraphicsEnvironment(JNIEnv* env) {
    return RegisterMethodsOrDie(env, kGraphicsEnvironmentName, g_methods, NELEM(g_methods));
}

} // namespace android
