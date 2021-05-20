/*
 * Copyright (C) 2019 The Android Open Source Project
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
 * this is a mini native libaray for NetworkStatsFactoryTest to run properly. It
 * load all the native method related to NetworkStatsFactory when test run
 */
#include <nativehelper/JNIHelp.h>
#include "jni.h"
#include "utils/Log.h"
#include "utils/misc.h"

namespace android {
int register_android_server_net_NetworkStatsFactory(JNIEnv* env);
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
    register_android_server_net_NetworkStatsFactory(env);
    return JNI_VERSION_1_4;
}
