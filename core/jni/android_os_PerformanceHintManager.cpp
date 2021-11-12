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
typedef APerformanceHintSession* (*APH_createSession)(APerformanceHintManager*, const int32_t*,
                                                      size_t, int64_t);
typedef int64_t (*APH_getPreferredUpdateRateNanos)(APerformanceHintManager* manager);
typedef void (*APH_updateTargetWorkDuration)(APerformanceHintSession*, int64_t);
typedef void (*APH_reportActualWorkDuration)(APerformanceHintSession*, int64_t);
typedef void (*APH_closeSession)(APerformanceHintSession* session);

bool gAPerformanceHintBindingInitialized = false;
APH_getManager gAPH_getManagerFn = nullptr;
APH_createSession gAPH_createSessionFn = nullptr;
APH_getPreferredUpdateRateNanos gAPH_getPreferredUpdateRateNanosFn = nullptr;
APH_updateTargetWorkDuration gAPH_updateTargetWorkDurationFn = nullptr;
APH_reportActualWorkDuration gAPH_reportActualWorkDurationFn = nullptr;
APH_closeSession gAPH_closeSessionFn = nullptr;

void ensureAPerformanceHintBindingInitialized() {
    if (gAPerformanceHintBindingInitialized) return;

    void* handle_ = dlopen("libandroid.so", RTLD_NOW | RTLD_NODELETE);
    LOG_ALWAYS_FATAL_IF(handle_ == nullptr, "Failed to dlopen libandroid.so!");

    gAPH_getManagerFn = (APH_getManager)dlsym(handle_, "APerformanceHint_getManager");
    LOG_ALWAYS_FATAL_IF(gAPH_getManagerFn == nullptr,
                        "Failed to find required symbol APerformanceHint_getManager!");

    gAPH_createSessionFn = (APH_createSession)dlsym(handle_, "APerformanceHint_createSession");
    LOG_ALWAYS_FATAL_IF(gAPH_createSessionFn == nullptr,
                        "Failed to find required symbol APerformanceHint_createSession!");

    gAPH_getPreferredUpdateRateNanosFn =
            (APH_getPreferredUpdateRateNanos)dlsym(handle_,
                                                   "APerformanceHint_getPreferredUpdateRateNanos");
    LOG_ALWAYS_FATAL_IF(gAPH_getPreferredUpdateRateNanosFn == nullptr,
                        "Failed to find required symbol "
                        "APerformanceHint_getPreferredUpdateRateNanos!");

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

    gAPerformanceHintBindingInitialized = true;
}

} // namespace

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

static const JNINativeMethod gPerformanceHintMethods[] = {
        {"nativeAcquireManager", "()J", (void*)nativeAcquireManager},
        {"nativeGetPreferredUpdateRateNanos", "(J)J", (void*)nativeGetPreferredUpdateRateNanos},
        {"nativeCreateSession", "(J[IJ)J", (void*)nativeCreateSession},
        {"nativeUpdateTargetWorkDuration", "(JJ)V", (void*)nativeUpdateTargetWorkDuration},
        {"nativeReportActualWorkDuration", "(JJ)V", (void*)nativeReportActualWorkDuration},
        {"nativeCloseSession", "(J)V", (void*)nativeCloseSession},
};

int register_android_os_PerformanceHintManager(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "android/os/PerformanceHintManager", gPerformanceHintMethods,
                                NELEM(gPerformanceHintMethods));
}

} // namespace android
