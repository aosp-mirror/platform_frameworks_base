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

#define LOG_TAG "GpuService-JNI"

#include <binder/IServiceManager.h>
#include <graphicsenv/IGpuService.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/scoped_utf_chars.h>

namespace {

static android::sp<android::IGpuService> getGpuService() {
    static const android::sp<android::IBinder> binder =
            android::defaultServiceManager()->checkService(android::String16("gpu"));
    if (!binder) {
        ALOGE("Failed to get gpu service");
        return nullptr;
    }

    return interface_cast<android::IGpuService>(binder);
}

void setUpdatableDriverPath_native(JNIEnv* env, jobject clazz, jstring jDriverPath) {
    if (jDriverPath == nullptr) {
        return;
    }
    const android::sp<android::IGpuService> gpuService = getGpuService();
    if (!gpuService) {
        return;
    }
    ScopedUtfChars driverPath(env, jDriverPath);
    gpuService->setUpdatableDriverPath(driverPath.c_str());
}

static const JNINativeMethod gGpuServiceMethods[] = {
        /* name, signature, funcPtr */
        {"nSetUpdatableDriverPath", "(Ljava/lang/String;)V",
         reinterpret_cast<void*>(setUpdatableDriverPath_native)},
};

const char* const kGpuServiceName = "com/android/server/gpu/GpuService";

} // anonymous namespace

namespace android {

int register_android_server_GpuService(JNIEnv* env) {
    return jniRegisterNativeMethods(env, kGpuServiceName, gGpuServiceMethods,
                                    NELEM(gGpuServiceMethods));
}

} /* namespace android */
