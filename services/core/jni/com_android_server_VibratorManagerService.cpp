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

#define LOG_TAG "VibratorManagerService"

#include <nativehelper/JNIHelp.h>
#include "android_runtime/AndroidRuntime.h"
#include "core_jni_helpers.h"
#include "jni.h"

#include <utils/Log.h>
#include <utils/misc.h>

#include <vibratorservice/VibratorManagerHalWrapper.h>

#include "com_android_server_VibratorManagerService.h"

namespace android {

static std::mutex gManagerMutex;
static vibrator::ManagerHalWrapper* gManager GUARDED_BY(gManagerMutex) = nullptr;

class NativeVibratorManagerService {
public:
    NativeVibratorManagerService() : mHal(std::make_unique<vibrator::LegacyManagerHalWrapper>()) {}
    ~NativeVibratorManagerService() = default;

    vibrator::ManagerHalWrapper* hal() const { return mHal.get(); }

private:
    const std::unique_ptr<vibrator::ManagerHalWrapper> mHal;
};

vibrator::ManagerHalWrapper* android_server_VibratorManagerService_getManager() {
    std::lock_guard<std::mutex> lock(gManagerMutex);
    return gManager;
}

static void destroyNativeService(void* ptr) {
    NativeVibratorManagerService* service = reinterpret_cast<NativeVibratorManagerService*>(ptr);
    if (service) {
        std::lock_guard<std::mutex> lock(gManagerMutex);
        gManager = nullptr;
        delete service;
    }
}

static jlong nativeInit(JNIEnv* /* env */, jclass /* clazz */) {
    std::unique_ptr<NativeVibratorManagerService> service =
            std::make_unique<NativeVibratorManagerService>();
    {
        std::lock_guard<std::mutex> lock(gManagerMutex);
        gManager = service->hal();
    }
    return reinterpret_cast<jlong>(service.release());
}

static jlong nativeGetFinalizer(JNIEnv* /* env */, jclass /* clazz */) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&destroyNativeService));
}

static jintArray nativeGetVibratorIds(JNIEnv* env, jclass /* clazz */, jlong servicePtr) {
    NativeVibratorManagerService* service =
            reinterpret_cast<NativeVibratorManagerService*>(servicePtr);
    if (service == nullptr) {
        ALOGE("nativeGetVibratorIds failed because native service was not initialized");
        return nullptr;
    }
    auto result = service->hal()->getVibratorIds();
    if (!result.isOk()) {
        return nullptr;
    }
    std::vector<int32_t> vibratorIds = result.value();
    jintArray ids = env->NewIntArray(vibratorIds.size());
    env->SetIntArrayRegion(ids, 0, vibratorIds.size(), reinterpret_cast<jint*>(vibratorIds.data()));
    return ids;
}

static const JNINativeMethod method_table[] = {
        {"nativeInit", "()J", (void*)nativeInit},
        {"nativeGetFinalizer", "()J", (void*)nativeGetFinalizer},
        {"nativeGetVibratorIds", "(J)[I", (void*)nativeGetVibratorIds},
};

int register_android_server_VibratorManagerService(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "com/android/server/VibratorManagerService", method_table,
                                    NELEM(method_table));
}

}; // namespace android
