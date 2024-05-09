/*
 * Copyright (C) 2021 The Android Open Source Project
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

#define TAG "HintManagerService-JNI"

//#define LOG_NDEBUG 0

#include <aidl/android/hardware/power/IPower.h>
#include <aidl/android/os/IHintManager.h>
#include <android-base/stringprintf.h>
#include <android/binder_manager.h>
#include <android/binder_parcel.h>
#include <android/binder_parcel_utils.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <powermanager/PowerHalController.h>
#include <powermanager/PowerHintSessionWrapper.h>
#include <utils/Log.h>

#include <unordered_map>

#include "jni.h"

namespace hal = aidl::android::hardware::power;

using android::power::PowerHintSessionWrapper;

namespace android {

static struct {
    jclass clazz{};
    jfieldID workPeriodStartTimestampNanos{};
    jfieldID durationNanos{};
    jfieldID cpuDurationNanos{};
    jfieldID gpuDurationNanos{};
    jfieldID timeStampNanos{};
} gWorkDurationInfo;

static power::PowerHalController gPowerHalController;
static std::mutex gSessionMapLock;
static std::unordered_map<jlong, std::shared_ptr<PowerHintSessionWrapper>> gSessionMap
        GUARDED_BY(gSessionMapLock);

static int64_t getHintSessionPreferredRate() {
    int64_t rate = -1;
    auto result = gPowerHalController.getHintSessionPreferredRate();
    if (result.isOk()) {
        rate = result.value();
    }
    return rate;
}

void throwUnsupported(JNIEnv* env, const char* msg) {
    env->ThrowNew(env->FindClass("java/lang/UnsupportedOperationException"), msg);
}

void throwFailed(JNIEnv* env, const char* msg) {
    // We throw IllegalStateException for all errors other than the "unsupported" ones
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), msg);
}

static jlong createHintSession(JNIEnv* env, int32_t tgid, int32_t uid,
                               std::vector<int32_t>& threadIds, int64_t durationNanos) {
    auto result = gPowerHalController.createHintSession(tgid, uid, threadIds, durationNanos);
    if (result.isOk()) {
        jlong session_ptr = reinterpret_cast<jlong>(result.value().get());
        std::scoped_lock sessionLock(gSessionMapLock);
        auto res = gSessionMap.insert({session_ptr, result.value()});
        return res.second ? session_ptr : 0;
    } else if (result.isFailed()) {
        ALOGW("createHintSession failed with message: %s", result.errorMessage());
        throwFailed(env, result.errorMessage());
    } else if (result.isUnsupported()) {
        throwUnsupported(env, result.errorMessage());
        return -1;
    }
    return 0;
}

static jlong createHintSessionWithConfig(JNIEnv* env, int32_t tgid, int32_t uid,
                                         std::vector<int32_t> threadIds, int64_t durationNanos,
                                         int32_t sessionTag, hal::SessionConfig& config) {
    auto result =
            gPowerHalController.createHintSessionWithConfig(tgid, uid, threadIds, durationNanos,
                                                            static_cast<hal::SessionTag>(
                                                                    sessionTag),
                                                            &config);
    if (result.isOk()) {
        jlong session_ptr = reinterpret_cast<jlong>(result.value().get());
        std::scoped_lock sessionLock(gSessionMapLock);
        auto res = gSessionMap.insert({session_ptr, result.value()});
        if (!res.second) {
            throwFailed(env, "PowerHAL provided an invalid session");
            return 0;
        }
        return session_ptr;
    } else if (result.isUnsupported()) {
        throwUnsupported(env, result.errorMessage());
        return -1;
    }
    throwFailed(env, result.errorMessage());
    return 0;
}

static void pauseHintSession(JNIEnv* env, int64_t session_ptr) {
    auto appSession = reinterpret_cast<PowerHintSessionWrapper*>(session_ptr);
    appSession->pause();
}

static void resumeHintSession(JNIEnv* env, int64_t session_ptr) {
    auto appSession = reinterpret_cast<PowerHintSessionWrapper*>(session_ptr);
    appSession->resume();
}

static void closeHintSession(JNIEnv* env, int64_t session_ptr) {
    auto appSession = reinterpret_cast<PowerHintSessionWrapper*>(session_ptr);
    appSession->close();
    std::scoped_lock sessionLock(gSessionMapLock);
    gSessionMap.erase(session_ptr);
}

static void updateTargetWorkDuration(int64_t session_ptr, int64_t targetDurationNanos) {
    auto appSession = reinterpret_cast<PowerHintSessionWrapper*>(session_ptr);
    appSession->updateTargetWorkDuration(targetDurationNanos);
}

static void reportActualWorkDuration(int64_t session_ptr,
                                     const std::vector<hal::WorkDuration>& actualDurations) {
    auto appSession = reinterpret_cast<PowerHintSessionWrapper*>(session_ptr);
    appSession->reportActualWorkDuration(actualDurations);
}

static void sendHint(int64_t session_ptr, hal::SessionHint hint) {
    auto appSession = reinterpret_cast<PowerHintSessionWrapper*>(session_ptr);
    appSession->sendHint(hint);
}

static void setThreads(int64_t session_ptr, const std::vector<int32_t>& threadIds) {
    auto appSession = reinterpret_cast<PowerHintSessionWrapper*>(session_ptr);
    appSession->setThreads(threadIds);
}

static void setMode(int64_t session_ptr, hal::SessionMode mode, bool enabled) {
    auto appSession = reinterpret_cast<PowerHintSessionWrapper*>(session_ptr);
    appSession->setMode(mode, enabled);
}

// ----------------------------------------------------------------------------
static void nativeInit(JNIEnv* env, jobject obj) {
    gPowerHalController.init();
}

static jlong nativeGetHintSessionPreferredRate(JNIEnv* /* env */, jclass /* clazz */) {
    return static_cast<jlong>(getHintSessionPreferredRate());
}

static jlong nativeCreateHintSession(JNIEnv* env, jclass /* clazz */, jint tgid, jint uid,
                                     jintArray tids, jlong durationNanos) {
    ScopedIntArrayRO tidArray(env, tids);
    if (nullptr == tidArray.get() || tidArray.size() == 0) {
        ALOGW("nativeCreateHintSession: GetIntArrayElements returns nullptr.");
        return 0;
    }
    std::vector<int32_t> threadIds(tidArray.get(), tidArray.get() + tidArray.size());
    return createHintSession(env, tgid, uid, threadIds, durationNanos);
}

static jlong nativeCreateHintSessionWithConfig(JNIEnv* env, jclass /* clazz */, jint tgid, jint uid,
                                               jintArray tids, jlong durationNanos, jint sessionTag,
                                               jobject sessionConfig) {
    ScopedIntArrayRO tidArray(env, tids);
    if (nullptr == tidArray.get() || tidArray.size() == 0) {
        ALOGW("nativeCreateHintSessionWithConfig: GetIntArrayElements returns nullptr.");
        return 0;
    }
    std::vector<int32_t> threadIds(tidArray.get(), tidArray.get() + tidArray.size());
    hal::SessionConfig config;
    jlong out = createHintSessionWithConfig(env, tgid, uid, std::move(threadIds), durationNanos,
                                            sessionTag, config);
    if (out <= 0) {
        return out;
    }
    static jclass configClass = env->FindClass("android/hardware/power/SessionConfig");
    static jfieldID fid = env->GetFieldID(configClass, "id", "J");
    env->SetLongField(sessionConfig, fid, config.id);
    return out;
}

static void nativePauseHintSession(JNIEnv* env, jclass /* clazz */, jlong session_ptr) {
    pauseHintSession(env, session_ptr);
}

static void nativeResumeHintSession(JNIEnv* env, jclass /* clazz */, jlong session_ptr) {
    resumeHintSession(env, session_ptr);
}

static void nativeCloseHintSession(JNIEnv* env, jclass /* clazz */, jlong session_ptr) {
    closeHintSession(env, session_ptr);
}

static void nativeUpdateTargetWorkDuration(JNIEnv* /* env */, jclass /* clazz */, jlong session_ptr,
                                           jlong targetDurationNanos) {
    updateTargetWorkDuration(session_ptr, targetDurationNanos);
}

static void nativeReportActualWorkDuration(JNIEnv* env, jclass /* clazz */, jlong session_ptr,
                                           jlongArray actualDurations, jlongArray timeStamps) {
    ScopedLongArrayRO arrayActualDurations(env, actualDurations);
    ScopedLongArrayRO arrayTimeStamps(env, timeStamps);

    std::vector<hal::WorkDuration> actualList(arrayActualDurations.size());
    for (size_t i = 0; i < arrayActualDurations.size(); i++) {
        actualList[i].timeStampNanos = arrayTimeStamps[i];
        actualList[i].durationNanos = arrayActualDurations[i];
    }
    reportActualWorkDuration(session_ptr, actualList);
}

static void nativeSendHint(JNIEnv* env, jclass /* clazz */, jlong session_ptr, jint hint) {
    sendHint(session_ptr, static_cast<hal::SessionHint>(hint));
}

static void nativeSetThreads(JNIEnv* env, jclass /* clazz */, jlong session_ptr, jintArray tids) {
    ScopedIntArrayRO tidArray(env, tids);

    std::vector<int32_t> threadIds(tidArray.get(), tidArray.get() + tidArray.size());
    setThreads(session_ptr, threadIds);
}

static void nativeSetMode(JNIEnv* env, jclass /* clazz */, jlong session_ptr, jint mode,
                          jboolean enabled) {
    setMode(session_ptr, static_cast<hal::SessionMode>(mode), enabled);
}

static void nativeReportActualWorkDuration2(JNIEnv* env, jclass /* clazz */, jlong session_ptr,
                                            jobjectArray jWorkDurations) {
    int size = env->GetArrayLength(jWorkDurations);
    std::vector<hal::WorkDuration> workDurations(size);
    for (int i = 0; i < size; i++) {
        jobject workDuration = env->GetObjectArrayElement(jWorkDurations, i);
        workDurations[i].workPeriodStartTimestampNanos =
                env->GetLongField(workDuration, gWorkDurationInfo.workPeriodStartTimestampNanos);
        workDurations[i].durationNanos =
                env->GetLongField(workDuration, gWorkDurationInfo.durationNanos);
        workDurations[i].cpuDurationNanos =
                env->GetLongField(workDuration, gWorkDurationInfo.cpuDurationNanos);
        workDurations[i].gpuDurationNanos =
                env->GetLongField(workDuration, gWorkDurationInfo.gpuDurationNanos);
        workDurations[i].timeStampNanos =
                env->GetLongField(workDuration, gWorkDurationInfo.timeStampNanos);
    }
    reportActualWorkDuration(session_ptr, workDurations);
}

// ----------------------------------------------------------------------------
static const JNINativeMethod sHintManagerServiceMethods[] = {
        /* name, signature, funcPtr */
        {"nativeInit", "()V", (void*)nativeInit},
        {"nativeGetHintSessionPreferredRate", "()J", (void*)nativeGetHintSessionPreferredRate},
        {"nativeCreateHintSession", "(II[IJ)J", (void*)nativeCreateHintSession},
        {"nativeCreateHintSessionWithConfig", "(II[IJILandroid/hardware/power/SessionConfig;)J",
         (void*)nativeCreateHintSessionWithConfig},
        {"nativePauseHintSession", "(J)V", (void*)nativePauseHintSession},
        {"nativeResumeHintSession", "(J)V", (void*)nativeResumeHintSession},
        {"nativeCloseHintSession", "(J)V", (void*)nativeCloseHintSession},
        {"nativeUpdateTargetWorkDuration", "(JJ)V", (void*)nativeUpdateTargetWorkDuration},
        {"nativeReportActualWorkDuration", "(J[J[J)V", (void*)nativeReportActualWorkDuration},
        {"nativeSendHint", "(JI)V", (void*)nativeSendHint},
        {"nativeSetThreads", "(J[I)V", (void*)nativeSetThreads},
        {"nativeSetMode", "(JIZ)V", (void*)nativeSetMode},
        {"nativeReportActualWorkDuration", "(J[Landroid/hardware/power/WorkDuration;)V",
         (void*)nativeReportActualWorkDuration2},
};

int register_android_server_HintManagerService(JNIEnv* env) {
    gWorkDurationInfo.clazz = env->FindClass("android/hardware/power/WorkDuration");
    gWorkDurationInfo.workPeriodStartTimestampNanos =
            env->GetFieldID(gWorkDurationInfo.clazz, "workPeriodStartTimestampNanos", "J");
    gWorkDurationInfo.durationNanos =
            env->GetFieldID(gWorkDurationInfo.clazz, "durationNanos", "J");
    gWorkDurationInfo.cpuDurationNanos =
            env->GetFieldID(gWorkDurationInfo.clazz, "cpuDurationNanos", "J");
    gWorkDurationInfo.gpuDurationNanos =
            env->GetFieldID(gWorkDurationInfo.clazz, "gpuDurationNanos", "J");
    gWorkDurationInfo.timeStampNanos =
            env->GetFieldID(gWorkDurationInfo.clazz, "timeStampNanos", "J");

    return jniRegisterNativeMethods(env,
                                    "com/android/server/power/hint/"
                                    "HintManagerService$NativeWrapper",
                                    sHintManagerServiceMethods, NELEM(sHintManagerServiceMethods));
}

} /* namespace android */
