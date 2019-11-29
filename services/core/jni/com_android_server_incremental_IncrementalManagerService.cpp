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

#define LOG_TAG "incremental_manager_service-jni"

#include "incremental_service.h"
#include "jni.h"

#include <memory>
#include <nativehelper/JNIHelp.h>


namespace android {

static jlong nativeStartService(JNIEnv* env, jclass klass, jobject self) {
    return Incremental_IncrementalService_Start();
}

static void nativeSystemReady(JNIEnv* env, jclass klass, jlong self) {
    Incremental_IncrementalService_OnSystemReady(self);
}

static const JNINativeMethod method_table[] = {
        {"nativeStartService", "()J", (void*)nativeStartService},
        {"nativeSystemReady", "(J)V", (void*)nativeSystemReady},
};

int register_android_server_incremental_IncrementalManagerService(JNIEnv* env) {
    return jniRegisterNativeMethods(env,
        "com/android/server/incremental/IncrementalManagerService",
        method_table, std::size(method_table));
}

} // namespace android
