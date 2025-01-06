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

#include "com_android_server_vibrator_VibratorManagerService.h"

#include <nativehelper/JNIHelp.h>
#include <utils/Log.h>
#include <utils/misc.h>
#include <vibratorservice/VibratorManagerHalController.h>

#include <unordered_map>

#include "android_runtime/AndroidRuntime.h"
#include "core_jni_helpers.h"
#include "jni.h"

namespace android {

static JavaVM* sJvm = nullptr;
static jmethodID sMethodIdOnSyncedVibrationComplete;
static jmethodID sMethodIdOnVibrationSessionComplete;
static std::mutex gManagerMutex;
static vibrator::ManagerHalController* gManager GUARDED_BY(gManagerMutex) = nullptr;

class NativeVibratorManagerService {
public:
    using IVibrationSession = aidl::android::hardware::vibrator::IVibrationSession;
    using VibrationSessionConfig = aidl::android::hardware::vibrator::VibrationSessionConfig;

    NativeVibratorManagerService(JNIEnv* env, jobject callbackListener)
          : mHal(std::make_unique<vibrator::ManagerHalController>()),
            mCallbackListener(env->NewGlobalRef(callbackListener)) {
        LOG_ALWAYS_FATAL_IF(mHal == nullptr, "Unable to find reference to vibrator manager hal");
        LOG_ALWAYS_FATAL_IF(mCallbackListener == nullptr,
                            "Unable to create global reference to vibration callback handler");
    }

    ~NativeVibratorManagerService() {
        auto jniEnv = GetOrAttachJNIEnvironment(sJvm);
        jniEnv->DeleteGlobalRef(mCallbackListener);
    }

    vibrator::ManagerHalController* hal() const { return mHal.get(); }

    std::function<void()> createSyncedVibrationCallback(jlong vibrationId) {
        return [vibrationId, this]() {
            auto jniEnv = GetOrAttachJNIEnvironment(sJvm);
            jniEnv->CallVoidMethod(mCallbackListener, sMethodIdOnSyncedVibrationComplete,
                                   vibrationId);
        };
    }

    std::function<void()> createVibrationSessionCallback(jlong sessionId) {
        return [sessionId, this]() {
            auto jniEnv = GetOrAttachJNIEnvironment(sJvm);
            jniEnv->CallVoidMethod(mCallbackListener, sMethodIdOnVibrationSessionComplete,
                                   sessionId);
            std::lock_guard<std::mutex> lock(mSessionMutex);
            auto it = mSessions.find(sessionId);
            if (it != mSessions.end()) {
                mSessions.erase(it);
            }
        };
    }

    bool startSession(jlong sessionId, const std::vector<int32_t>& vibratorIds) {
        VibrationSessionConfig config;
        auto callback = createVibrationSessionCallback(sessionId);
        auto result = hal()->startSession(vibratorIds, config, callback);
        if (!result.isOk()) {
            return false;
        }

        std::lock_guard<std::mutex> lock(mSessionMutex);
        mSessions[sessionId] = std::move(result.value());
        return true;
    }

    void closeSession(jlong sessionId) {
        std::lock_guard<std::mutex> lock(mSessionMutex);
        auto it = mSessions.find(sessionId);
        if (it != mSessions.end()) {
            it->second->close();
            // Keep session, it can still be aborted.
        }
    }

    void abortSession(jlong sessionId) {
        std::lock_guard<std::mutex> lock(mSessionMutex);
        auto it = mSessions.find(sessionId);
        if (it != mSessions.end()) {
            it->second->abort();
            mSessions.erase(it);
        }
    }

    void clearSessions() {
        hal()->clearSessions();
        std::lock_guard<std::mutex> lock(mSessionMutex);
        mSessions.clear();
    }

private:
    std::mutex mSessionMutex;
    const std::unique_ptr<vibrator::ManagerHalController> mHal;
    std::unordered_map<jlong, std::shared_ptr<IVibrationSession>> mSessions
            GUARDED_BY(mSessionMutex);
    const jobject mCallbackListener;
};

vibrator::ManagerHalController* android_server_vibrator_VibratorManagerService_getManager() {
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

static jlong nativeInit(JNIEnv* env, jclass /* clazz */, jobject callbackListener) {
    std::unique_ptr<NativeVibratorManagerService> service =
            std::make_unique<NativeVibratorManagerService>(env, callbackListener);
    {
        std::lock_guard<std::mutex> lock(gManagerMutex);
        gManager = service->hal();
    }
    return reinterpret_cast<jlong>(service.release());
}

static jlong nativeGetFinalizer(JNIEnv* /* env */, jclass /* clazz */) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&destroyNativeService));
}

static jlong nativeGetCapabilities(JNIEnv* env, jclass /* clazz */, jlong servicePtr) {
    NativeVibratorManagerService* service =
            reinterpret_cast<NativeVibratorManagerService*>(servicePtr);
    if (service == nullptr) {
        ALOGE("nativeGetCapabilities failed because native service was not initialized");
        return 0;
    }
    auto result = service->hal()->getCapabilities();
    return result.isOk() ? static_cast<jlong>(result.value()) : 0;
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

static jboolean nativePrepareSynced(JNIEnv* env, jclass /* clazz */, jlong servicePtr,
                                    jintArray vibratorIds) {
    NativeVibratorManagerService* service =
            reinterpret_cast<NativeVibratorManagerService*>(servicePtr);
    if (service == nullptr) {
        ALOGE("nativePrepareSynced failed because native service was not initialized");
        return JNI_FALSE;
    }
    jsize size = env->GetArrayLength(vibratorIds);
    std::vector<int32_t> ids(size);
    env->GetIntArrayRegion(vibratorIds, 0, size, reinterpret_cast<jint*>(ids.data()));
    return service->hal()->prepareSynced(ids).isOk() ? JNI_TRUE : JNI_FALSE;
}

static jboolean nativeTriggerSynced(JNIEnv* env, jclass /* clazz */, jlong servicePtr,
                                    jlong vibrationId) {
    NativeVibratorManagerService* service =
            reinterpret_cast<NativeVibratorManagerService*>(servicePtr);
    if (service == nullptr) {
        ALOGE("nativeTriggerSynced failed because native service was not initialized");
        return JNI_FALSE;
    }
    auto callback = service->createSyncedVibrationCallback(vibrationId);
    return service->hal()->triggerSynced(callback).isOk() ? JNI_TRUE : JNI_FALSE;
}

static void nativeCancelSynced(JNIEnv* env, jclass /* clazz */, jlong servicePtr) {
    NativeVibratorManagerService* service =
            reinterpret_cast<NativeVibratorManagerService*>(servicePtr);
    if (service == nullptr) {
        ALOGE("nativeCancelSynced failed because native service was not initialized");
        return;
    }
    service->hal()->cancelSynced();
}

static jboolean nativeStartSession(JNIEnv* env, jclass /* clazz */, jlong servicePtr,
                                   jlong sessionId, jintArray vibratorIds) {
    NativeVibratorManagerService* service =
            reinterpret_cast<NativeVibratorManagerService*>(servicePtr);
    if (service == nullptr) {
        ALOGE("nativeStartSession failed because native service was not initialized");
        return JNI_FALSE;
    }
    jsize size = env->GetArrayLength(vibratorIds);
    std::vector<int32_t> ids(size);
    env->GetIntArrayRegion(vibratorIds, 0, size, reinterpret_cast<jint*>(ids.data()));
    return service->startSession(sessionId, ids) ? JNI_TRUE : JNI_FALSE;
}

static void nativeEndSession(JNIEnv* env, jclass /* clazz */, jlong servicePtr, jlong sessionId,
                             jboolean shouldAbort) {
    NativeVibratorManagerService* service =
            reinterpret_cast<NativeVibratorManagerService*>(servicePtr);
    if (service == nullptr) {
        ALOGE("nativeEndSession failed because native service was not initialized");
        return;
    }
    if (shouldAbort) {
        service->abortSession(sessionId);
    } else {
        service->closeSession(sessionId);
    }
}

static void nativeClearSessions(JNIEnv* env, jclass /* clazz */, jlong servicePtr) {
    NativeVibratorManagerService* service =
            reinterpret_cast<NativeVibratorManagerService*>(servicePtr);
    if (service == nullptr) {
        ALOGE("nativeClearSessions failed because native service was not initialized");
        return;
    }
    service->clearSessions();
}

inline static constexpr auto sNativeInitMethodSignature =
        "(Lcom/android/server/vibrator/VibratorManagerService$VibratorManagerNativeCallbacks;)J";

static const JNINativeMethod method_table[] = {
        {"nativeInit", sNativeInitMethodSignature, (void*)nativeInit},
        {"nativeGetFinalizer", "()J", (void*)nativeGetFinalizer},
        {"nativeGetCapabilities", "(J)J", (void*)nativeGetCapabilities},
        {"nativeGetVibratorIds", "(J)[I", (void*)nativeGetVibratorIds},
        {"nativePrepareSynced", "(J[I)Z", (void*)nativePrepareSynced},
        {"nativeTriggerSynced", "(JJ)Z", (void*)nativeTriggerSynced},
        {"nativeCancelSynced", "(J)V", (void*)nativeCancelSynced},
        {"nativeStartSession", "(JJ[I)Z", (void*)nativeStartSession},
        {"nativeEndSession", "(JJZ)V", (void*)nativeEndSession},
        {"nativeClearSessions", "(J)V", (void*)nativeClearSessions},
};

int register_android_server_vibrator_VibratorManagerService(JavaVM* jvm, JNIEnv* env) {
    sJvm = jvm;
    auto listenerClassName =
            "com/android/server/vibrator/VibratorManagerService$VibratorManagerNativeCallbacks";
    jclass listenerClass = FindClassOrDie(env, listenerClassName);
    sMethodIdOnSyncedVibrationComplete =
            GetMethodIDOrDie(env, listenerClass, "onSyncedVibrationComplete", "(J)V");
    sMethodIdOnVibrationSessionComplete =
            GetMethodIDOrDie(env, listenerClass, "onVibrationSessionComplete", "(J)V");
    return jniRegisterNativeMethods(env, "com/android/server/vibrator/VibratorManagerService",
                                    method_table, NELEM(method_table));
}

}; // namespace android
