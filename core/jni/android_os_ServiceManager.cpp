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

#define LOG_TAG "ServiceManager"
//#define LOG_NDEBUG 0
#include <android-base/logging.h>

#include <binder/IInterface.h>
#include <binder/IServiceManager.h>
#include <nativehelper/JNIHelp.h>

#include "android_util_Binder.h"
#include "core_jni_helpers.h"

namespace android {

// Native because we have a client-side wait in waitForService() and we do not
// want an unnecessary second copy of it.
static jobject android_os_ServiceManager_waitForServiceNative(JNIEnv* env, jclass /* clazzObj */,
                                                              jstring serviceNameObj) {
    const jchar* serviceName = env->GetStringCritical(serviceNameObj, nullptr);
    if (!serviceName) {
        jniThrowNullPointerException(env, nullptr);
        return nullptr;
    }
    String16 nameCopy = String16(reinterpret_cast<const char16_t *>(serviceName),
            env->GetStringLength(serviceNameObj));
    env->ReleaseStringCritical(serviceNameObj, serviceName);

    sp<IBinder> service = defaultServiceManager()->waitForService(nameCopy);

    if (!service) {
        return nullptr;
    }

    return javaObjectForIBinder(env, service);
}

// ----------------------------------------------------------------------------

static const JNINativeMethod method_table[] = {
        /* name, signature, funcPtr */
        {"waitForServiceNative", "(Ljava/lang/String;)Landroid/os/IBinder;",
         (void*)android_os_ServiceManager_waitForServiceNative},
};

int register_android_os_ServiceManager(JNIEnv* env) {
    return RegisterMethodsOrDie(
            env, "android/os/ServiceManager", method_table, NELEM(method_table));
}

}; // namespace android
