/*
 * Copyright (C) 2012 The Android Open Source Project
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

#define LOG_TAG "webviewchromium_plat_support"

#include <jni.h>
#include <utils/Log.h>

namespace android {

void RegisterDrawGLFunctor(JNIEnv* env);
void RegisterGraphicsUtils(JNIEnv* env);

}  // namespace android

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
  JNIEnv* env = NULL;
  jint ret = vm->AttachCurrentThread(&env, NULL);
  LOG_ALWAYS_FATAL_IF(ret != JNI_OK, "AttachCurrentThread failed");
  android::RegisterDrawGLFunctor(env);
  android::RegisterGraphicsUtils(env);

  return JNI_VERSION_1_4;
}
