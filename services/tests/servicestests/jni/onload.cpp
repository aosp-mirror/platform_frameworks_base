/*
 * Copyright (C) 2023 The Android Open Source Project
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

/*
 * this is a mini native libaray for cached app optimizer tests to run properly. It
 * loads all the native methods necessary.
 */
#include <nativehelper/JNIHelp.h>
#include "jni.h"
#include "utils/Log.h"
#include "utils/misc.h"

namespace android {
int register_android_server_am_CachedAppOptimizer(JNIEnv* env);
int register_android_server_app_GameManagerService(JNIEnv* env);
int register_android_server_am_OomConnection(JNIEnv* env);
int register_android_server_utils_AnrTimer(JNIEnv *env);
};

using namespace android;

extern "C" jint JNI_OnLoad(JavaVM* vm, void* /* reserved */)
{
    JNIEnv* env = NULL;
    jint result = -1;

    if (vm->GetEnv((void**) &env, JNI_VERSION_1_4) != JNI_OK) {
        ALOGE("GetEnv failed!");
        return result;
    }
    ALOG_ASSERT(env, "Could not retrieve the env!");
    register_android_server_am_CachedAppOptimizer(env);
    register_android_server_app_GameManagerService(env);
    register_android_server_am_OomConnection(env);
    register_android_server_utils_AnrTimer(env);
    return JNI_VERSION_1_4;
}
