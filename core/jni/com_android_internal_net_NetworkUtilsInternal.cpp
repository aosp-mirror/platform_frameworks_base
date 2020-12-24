/*
 * Copyright 2020, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "NetdClient.h"
#include "core_jni_helpers.h"
#include "jni.h"

namespace android {
static void android_net_utils_setAllowNetworkingForProcess(JNIEnv *env, jobject thiz,
                                                           jboolean hasConnectivity) {
    setAllowNetworkingForProcess(hasConnectivity == JNI_TRUE);
}

static const JNINativeMethod gNetworkUtilMethods[] = {
        {"setAllowNetworkingForProcess", "(Z)V",
         (void *)android_net_utils_setAllowNetworkingForProcess},
};

int register_com_android_internal_net_NetworkUtilsInternal(JNIEnv *env) {
    return RegisterMethodsOrDie(env, "com/android/internal/net/NetworkUtilsInternal",
                                gNetworkUtilMethods, NELEM(gNetworkUtilMethods));
}

} // namespace android
