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

#include <android-base/stringprintf.h>
#include <android/hardware/power/IPower.h>
#include <android_runtime/AndroidRuntime.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <powermanager/PowerHalController.h>
#include <utils/Log.h>

#include <unistd.h>
#include <cinttypes>

#include <sys/types.h>

#include "jni.h"

using android::hardware::power::IPowerHintSession;
using android::hardware::power::WorkDuration;

using android::base::StringPrintf;

namespace android {

static power::PowerHalController gPowerHalController;

static jlong createHintSession(JNIEnv* env, int32_t tgid, int32_t uid,
                               std::vector<int32_t> threadIds, int64_t durationNanos) {
    auto result =
            gPowerHalController.createHintSession(tgid, uid, std::move(threadIds), durationNanos);
    if (result.isOk()) {
        sp<IPowerHintSession> appSession = result.value();
        if (appSession) appSession->incStrong(env);
        return reinterpret_cast<jlong>(appSession.get());
    }
    return 0;
}

static void pauseHintSession(JNIEnv* env, int64_t session_ptr) {
    sp<IPowerHintSession> appSession = reinterpret_cast<IPowerHintSession*>(session_ptr);
    appSession->pause();
}

static void resumeHintSession(JNIEnv* env, int64_t session_ptr) {
    sp<IPowerHintSession> appSession = reinterpret_cast<IPowerHintSession*>(session_ptr);
    appSession->resume();
}

static void closeHintSession(JNIEnv* env, int64_t session_ptr) {
    sp<IPowerHintSession> appSession = reinterpret_cast<IPowerHintSession*>(session_ptr);
    appSession->close();
    appSession->decStrong(env);
}

static void updateTargetWorkDuration(int64_t session_ptr, int64_t targetDurationNanos) {
    sp<IPowerHintSession> appSession = reinterpret_cast<IPowerHintSession*>(session_ptr);
    appSession->updateTargetWorkDuration(targetDurationNanos);
}

static void reportActualWorkDuration(int64_t session_ptr,
                                     const std::vector<WorkDuration>& actualDurations) {
    sp<IPowerHintSession> appSession = reinterpret_cast<IPowerHintSession*>(session_ptr);
    appSession->reportActualWorkDuration(actualDurations);
}

static int64_t getHintSessionPreferredRate() {
    int64_t rate = -1;
    auto result = gPowerHalController.getHintSessionPreferredRate();
    if (result.isOk()) {
        rate = result.value();
    }
    return rate;
}

// ----------------------------------------------------------------------------
static void nativeInit(JNIEnv* env, jobject obj) {
    gPowerHalController.init();
}

static jlong nativeCreateHintSession(JNIEnv* env, jclass /* clazz */, jint tgid, jint uid,
                                     jintArray tids, jlong durationNanos) {
    ScopedIntArrayRO tidArray(env, tids);
    if (nullptr == tidArray.get() || tidArray.size() == 0) {
        ALOGW("GetIntArrayElements returns nullptr.");
        return 0;
    }
    std::vector<int32_t> threadIds(tidArray.size());
    for (size_t i = 0; i < tidArray.size(); i++) {
        threadIds[i] = tidArray[i];
    }
    return createHintSession(env, tgid, uid, std::move(threadIds), durationNanos);
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

    std::vector<WorkDuration> actualList(arrayActualDurations.size());
    for (size_t i = 0; i < arrayActualDurations.size(); i++) {
        actualList[i].timeStampNanos = arrayTimeStamps[i];
        actualList[i].durationNanos = arrayActualDurations[i];
    }
    reportActualWorkDuration(session_ptr, actualList);
}

static jlong nativeGetHintSessionPreferredRate(JNIEnv* /* env */, jclass /* clazz */) {
    return static_cast<jlong>(getHintSessionPreferredRate());
}

// ----------------------------------------------------------------------------
static const JNINativeMethod sHintManagerServiceMethods[] = {
        /* name, signature, funcPtr */
        {"nativeInit", "()V", (void*)nativeInit},
        {"nativeCreateHintSession", "(II[IJ)J", (void*)nativeCreateHintSession},
        {"nativePauseHintSession", "(J)V", (void*)nativePauseHintSession},
        {"nativeResumeHintSession", "(J)V", (void*)nativeResumeHintSession},
        {"nativeCloseHintSession", "(J)V", (void*)nativeCloseHintSession},
        {"nativeUpdateTargetWorkDuration", "(JJ)V", (void*)nativeUpdateTargetWorkDuration},
        {"nativeReportActualWorkDuration", "(J[J[J)V", (void*)nativeReportActualWorkDuration},
        {"nativeGetHintSessionPreferredRate", "()J", (void*)nativeGetHintSessionPreferredRate},
};

int register_android_server_HintManagerService(JNIEnv* env) {
    return jniRegisterNativeMethods(env,
                                    "com/android/server/power/hint/"
                                    "HintManagerService$NativeWrapper",
                                    sHintManagerServiceMethods, NELEM(sHintManagerServiceMethods));
}

} /* namespace android */
