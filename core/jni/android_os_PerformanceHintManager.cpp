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

#define LOG_TAG "PerfHint-jni"

#include "jni.h"

#include <dlfcn.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <utils/Log.h>
#include <vector>

#include "core_jni_helpers.h"

namespace android {

namespace {

struct APerformanceHintManager;
struct APerformanceHintSession;

typedef APerformanceHintManager* (*APH_getManager)();
typedef int64_t (*APH_getPreferredUpdateRateNanos)(APerformanceHintManager* manager);
typedef APerformanceHintSession* (*APH_createSession)(APerformanceHintManager*, const int32_t*,
                                                      size_t, int64_t);
typedef void (*APH_updateTargetWorkDuration)(APerformanceHintSession*, int64_t);
typedef void (*APH_reportActualWorkDuration)(APerformanceHintSession*, int64_t);
typedef void (*APH_closeSession)(APerformanceHintSession* session);
typedef void (*APH_sendHint)(APerformanceHintSession*, int32_t);
typedef int (*APH_setThreads)(APerformanceHintSession*, const pid_t*, size_t);
typedef void (*APH_getThreadIds)(APerformanceHintSession*, int32_t* const, size_t* const);
typedef void (*APH_setPreferPowerEfficiency)(APerformanceHintSession*, bool);

bool gAPerformanceHintBindingInitialized = false;
APH_getManager gAPH_getManagerFn = nullptr;
APH_getPreferredUpdateRateNanos gAPH_getPreferredUpdateRateNanosFn = nullptr;
APH_createSession gAPH_createSessionFn = nullptr;
APH_updateTargetWorkDuration gAPH_updateTargetWorkDurationFn = nullptr;
APH_reportActualWorkDuration gAPH_reportActualWorkDurationFn = nullptr;
APH_closeSession gAPH_closeSessionFn = nullptr;
APH_sendHint gAPH_sendHintFn = nullptr;
APH_setThreads gAPH_setThreadsFn = nullptr;
APH_getThreadIds gAPH_getThreadIdsFn = nullptr;
APH_setPreferPowerEfficiency gAPH_setPreferPowerEfficiencyFn = nullptr;

void ensureAPerformanceHintBindingInitialized() {
    if (gAPerformanceHintBindingInitialized) return;

    void* handle_ = dlopen("libandroid.so", RTLD_NOW | RTLD_NODELETE);
    LOG_ALWAYS_FATAL_IF(handle_ == nullptr, "Failed to dlopen libandroid.so!");

    gAPH_getManagerFn = (APH_getManager)dlsym(handle_, "APerformanceHint_getManager");
    LOG_ALWAYS_FATAL_IF(gAPH_getManagerFn == nullptr,
                        "Failed to find required symbol APerformanceHint_getManager!");

    gAPH_getPreferredUpdateRateNanosFn =
            (APH_getPreferredUpdateRateNanos)dlsym(handle_,
                                                   "APerformanceHint_getPreferredUpdateRateNanos");
    LOG_ALWAYS_FATAL_IF(gAPH_getPreferredUpdateRateNanosFn == nullptr,
                        "Failed to find required symbol "
                        "APerformanceHint_getPreferredUpdateRateNanos!");

    gAPH_createSessionFn = (APH_createSession)dlsym(handle_, "APerformanceHint_createSession");
    LOG_ALWAYS_FATAL_IF(gAPH_createSessionFn == nullptr,
                        "Failed to find required symbol APerformanceHint_createSession!");

    gAPH_updateTargetWorkDurationFn =
            (APH_updateTargetWorkDuration)dlsym(handle_,
                                                "APerformanceHint_updateTargetWorkDuration");
    LOG_ALWAYS_FATAL_IF(gAPH_updateTargetWorkDurationFn == nullptr,
                        "Failed to find required symbol "
                        "APerformanceHint_updateTargetWorkDuration!");

    gAPH_reportActualWorkDurationFn =
            (APH_reportActualWorkDuration)dlsym(handle_,
                                                "APerformanceHint_reportActualWorkDuration");
    LOG_ALWAYS_FATAL_IF(gAPH_reportActualWorkDurationFn == nullptr,
                        "Failed to find required symbol "
                        "APerformanceHint_reportActualWorkDuration!");

    gAPH_closeSessionFn = (APH_closeSession)dlsym(handle_, "APerformanceHint_closeSession");
    LOG_ALWAYS_FATAL_IF(gAPH_closeSessionFn == nullptr,
                        "Failed to find required symbol APerformanceHint_closeSession!");

    gAPH_sendHintFn = (APH_sendHint)dlsym(handle_, "APerformanceHint_sendHint");
    LOG_ALWAYS_FATAL_IF(gAPH_sendHintFn == nullptr,
                        "Failed to find required symbol APerformanceHint_sendHint!");

    gAPH_setThreadsFn = (APH_setThreads)dlsym(handle_, "APerformanceHint_setThreads");
    LOG_ALWAYS_FATAL_IF(gAPH_setThreadsFn == nullptr,
                        "Failed to find required symbol APerformanceHint_setThreads!");

    gAPH_getThreadIdsFn = (APH_getThreadIds)dlsym(handle_, "APerformanceHint_getThreadIds");
    LOG_ALWAYS_FATAL_IF(gAPH_getThreadIdsFn == nullptr,
                        "Failed to find required symbol APerformanceHint_getThreadIds!");

    gAPH_setPreferPowerEfficiencyFn =
            (APH_setPreferPowerEfficiency)dlsym(handle_,
                                                "APerformanceHint_setPreferPowerEfficiency");
    LOG_ALWAYS_FATAL_IF(gAPH_setPreferPowerEfficiencyFn == nullptr,
                        "Failed to find required symbol"
                        "APerformanceHint_setPreferPowerEfficiency!");

    gAPerformanceHintBindingInitialized = true;
}

} // namespace

static void throwExceptionForErrno(JNIEnv* env, int err, const std::string& msg) {
    switch (err) {
        case EINVAL:
            jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException", msg.c_str());
            break;
        case EPERM:
            jniThrowExceptionFmt(env, "java/lang/SecurityException", msg.c_str());
            break;
        default:
            jniThrowException(env, "java/lang/RuntimeException", msg.c_str());
            break;
    }
}

static jlong nativeAcquireManager(JNIEnv* env, jclass clazz) {
    ensureAPerformanceHintBindingInitialized();
    return reinterpret_cast<jlong>(gAPH_getManagerFn());
}

static jlong nativeGetPreferredUpdateRateNanos(JNIEnv* env, jclass clazz, jlong nativeManagerPtr) {
    ensureAPerformanceHintBindingInitialized();
    return gAPH_getPreferredUpdateRateNanosFn(
            reinterpret_cast<APerformanceHintManager*>(nativeManagerPtr));
}

static jlong nativeCreateSession(JNIEnv* env, jclass clazz, jlong nativeManagerPtr, jintArray tids,
                                 jlong initialTargetWorkDurationNanos) {
    ensureAPerformanceHintBindingInitialized();
    if (tids == nullptr) return 0;
    std::vector<int32_t> tidsVector;
    ScopedIntArrayRO tidsArray(env, tids);
    for (size_t i = 0; i < tidsArray.size(); ++i) {
        tidsVector.push_back(static_cast<int32_t>(tidsArray[i]));
    }
    return reinterpret_cast<jlong>(
            gAPH_createSessionFn(reinterpret_cast<APerformanceHintManager*>(nativeManagerPtr),
                                 tidsVector.data(), tidsVector.size(),
                                 initialTargetWorkDurationNanos));
}

static void nativeUpdateTargetWorkDuration(JNIEnv* env, jclass clazz, jlong nativeSessionPtr,
                                           jlong targetDurationNanos) {
    ensureAPerformanceHintBindingInitialized();
    gAPH_updateTargetWorkDurationFn(reinterpret_cast<APerformanceHintSession*>(nativeSessionPtr),
                                    targetDurationNanos);
}

static void nativeReportActualWorkDuration(JNIEnv* env, jclass clazz, jlong nativeSessionPtr,
                                           jlong actualDurationNanos) {
    ensureAPerformanceHintBindingInitialized();
    gAPH_reportActualWorkDurationFn(reinterpret_cast<APerformanceHintSession*>(nativeSessionPtr),
                                    actualDurationNanos);
}

static void nativeCloseSession(JNIEnv* env, jclass clazz, jlong nativeSessionPtr) {
    ensureAPerformanceHintBindingInitialized();
    gAPH_closeSessionFn(reinterpret_cast<APerformanceHintSession*>(nativeSessionPtr));
}

static void nativeSendHint(JNIEnv* env, jclass clazz, jlong nativeSessionPtr, jint hint) {
    ensureAPerformanceHintBindingInitialized();
    gAPH_sendHintFn(reinterpret_cast<APerformanceHintSession*>(nativeSessionPtr), hint);
}

static void nativeSetThreads(JNIEnv* env, jclass clazz, jlong nativeSessionPtr, jintArray tids) {
    ensureAPerformanceHintBindingInitialized();

    if (tids == nullptr) {
        return;
    }
    ScopedIntArrayRO tidsArray(env, tids);
    std::vector<int32_t> tidsVector;
    tidsVector.reserve(tidsArray.size());
    for (size_t i = 0; i < tidsArray.size(); ++i) {
        tidsVector.push_back(static_cast<int32_t>(tidsArray[i]));
    }
    int err = gAPH_setThreadsFn(reinterpret_cast<APerformanceHintSession*>(nativeSessionPtr),
                                tidsVector.data(), tidsVector.size());
    if (err != 0) {
        throwExceptionForErrno(env, err, "Failed to set threads for hint session");
    }
}

// This call should only be used for validation in tests only. This call will initiate two IPC
// calls, the first one is used to determined the size of the thread ids list, the second one
// is used to return the actual list.
static jintArray nativeGetThreadIds(JNIEnv* env, jclass clazz, jlong nativeSessionPtr) {
    ensureAPerformanceHintBindingInitialized();
    size_t size = 0;
    gAPH_getThreadIdsFn(reinterpret_cast<APerformanceHintSession*>(nativeSessionPtr), nullptr,
                        &size);
    if (size == 0) {
        jintArray jintArr = env->NewIntArray(0);
        return jintArr;
    }
    std::vector<int32_t> tidsVector(size);
    gAPH_getThreadIdsFn(reinterpret_cast<APerformanceHintSession*>(nativeSessionPtr),
                        tidsVector.data(), &size);
    jintArray jintArr = env->NewIntArray(size);
    if (jintArr == nullptr) {
        jniThrowException(env, "java/lang/OutOfMemoryError", nullptr);
        return nullptr;
    }
    jint* threadIds = env->GetIntArrayElements(jintArr, 0);
    for (size_t i = 0; i < size; ++i) {
        threadIds[i] = tidsVector[i];
    }
    env->ReleaseIntArrayElements(jintArr, threadIds, 0);
    return jintArr;
}

static void nativeSetPreferPowerEfficiency(JNIEnv* env, jclass clazz, jlong nativeSessionPtr,
                                           jboolean enabled) {
    ensureAPerformanceHintBindingInitialized();
    gAPH_setPreferPowerEfficiencyFn(reinterpret_cast<APerformanceHintSession*>(nativeSessionPtr),
                                    enabled);
}

static const JNINativeMethod gPerformanceHintMethods[] = {
        {"nativeAcquireManager", "()J", (void*)nativeAcquireManager},
        {"nativeGetPreferredUpdateRateNanos", "(J)J", (void*)nativeGetPreferredUpdateRateNanos},
        {"nativeCreateSession", "(J[IJ)J", (void*)nativeCreateSession},
        {"nativeUpdateTargetWorkDuration", "(JJ)V", (void*)nativeUpdateTargetWorkDuration},
        {"nativeReportActualWorkDuration", "(JJ)V", (void*)nativeReportActualWorkDuration},
        {"nativeCloseSession", "(J)V", (void*)nativeCloseSession},
        {"nativeSendHint", "(JI)V", (void*)nativeSendHint},
        {"nativeSetThreads", "(J[I)V", (void*)nativeSetThreads},
        {"nativeGetThreadIds", "(J)[I", (void*)nativeGetThreadIds},
        {"nativeSetPreferPowerEfficiency", "(JZ)V", (void*)nativeSetPreferPowerEfficiency},
};

int register_android_os_PerformanceHintManager(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "android/os/PerformanceHintManager", gPerformanceHintMethods,
                                NELEM(gPerformanceHintMethods));
}

} // namespace android
