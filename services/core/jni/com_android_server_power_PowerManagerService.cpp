/*
 * Copyright (C) 2010 The Android Open Source Project
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

#define LOG_TAG "PowerManagerService-JNI"

//#define LOG_NDEBUG 0

#include "com_android_server_power_PowerManagerService.h"

#include <aidl/android/hardware/power/Boost.h>
#include <aidl/android/hardware/power/Mode.h>
#include <aidl/android/system/suspend/ISystemSuspend.h>
#include <aidl/android/system/suspend/IWakeLock.h>
#include <android-base/chrono_utils.h>
#include <android/binder_manager.h>
#include <android/system/suspend/ISuspendControlService.h>
#include <android/system/suspend/internal/ISuspendControlServiceInternal.h>
#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>
#include <binder/IServiceManager.h>
#include <com_android_input_flags.h>
#include <gui/SurfaceComposerClient.h>
#include <hardware_legacy/power.h>
#include <hidl/ServiceManagement.h>
#include <limits.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedUtfChars.h>
#include <powermanager/PowerHalController.h>
#include <utils/Log.h>
#include <utils/String8.h>
#include <utils/Timers.h>
#include <utils/misc.h>

#include "jni.h"

using aidl::android::hardware::power::Boost;
using aidl::android::hardware::power::Mode;
using aidl::android::system::suspend::ISystemSuspend;
using aidl::android::system::suspend::IWakeLock;
using aidl::android::system::suspend::WakeLockType;
using android::String8;
using android::system::suspend::ISuspendControlService;

namespace android {

// ----------------------------------------------------------------------------

static struct {
    jmethodID userActivityFromNative;
} gPowerManagerServiceClassInfo;

// ----------------------------------------------------------------------------

static jobject gPowerManagerServiceObj;
static power::PowerHalController gPowerHalController;
static nsecs_t gLastEventTime[USER_ACTIVITY_EVENT_LAST + 1];

// Throttling interval for user activity calls.
static const nsecs_t MIN_TIME_BETWEEN_USERACTIVITIES = 100 * 1000000L; // 100ms

// ----------------------------------------------------------------------------

static bool checkAndClearExceptionFromCallback(JNIEnv* env, const char* methodName) {
    if (env->ExceptionCheck()) {
        ALOGE("An exception was thrown by callback '%s'.", methodName);
        LOGE_EX(env);
        env->ExceptionClear();
        return true;
    }
    return false;
}

static void setPowerBoost(Boost boost, int32_t durationMs) {
    gPowerHalController.setBoost(boost, durationMs);
    SurfaceComposerClient::notifyPowerBoost(static_cast<int32_t>(boost));
}

static bool setPowerMode(Mode mode, bool enabled) {
    android::base::Timer t;
    auto result = gPowerHalController.setMode(mode, enabled);
    if (mode == Mode::INTERACTIVE && t.duration() > 20ms) {
        ALOGD("Excessive delay in setting interactive mode to %s while turning screen %s",
              enabled ? "true" : "false", enabled ? "on" : "off");
    }
    return result.isOk();
}

void android_server_PowerManagerService_userActivity(nsecs_t eventTime, int32_t eventType,
                                                     int32_t displayId) {
    if (gPowerManagerServiceObj) {
        // Throttle calls into user activity by event type.
        // We're a little conservative about argument checking here in case the caller
        // passes in bad data which could corrupt system state.
        if (eventType >= 0 && eventType <= USER_ACTIVITY_EVENT_LAST) {
            nsecs_t now = systemTime(SYSTEM_TIME_MONOTONIC);
            if (eventTime > now) {
                eventTime = now;
            }

            if (!com::android::input::flags::rate_limit_user_activity_poke_in_dispatcher()) {
                if (gLastEventTime[eventType] + MIN_TIME_BETWEEN_USERACTIVITIES > eventTime) {
                    return;
                }
                gLastEventTime[eventType] = eventTime;
            }

            // Tell the power HAL when user activity occurs.
            setPowerBoost(Boost::INTERACTION, 0);
        }

        JNIEnv* env = AndroidRuntime::getJNIEnv();

        env->CallVoidMethod(gPowerManagerServiceObj,
                gPowerManagerServiceClassInfo.userActivityFromNative,
                nanoseconds_to_milliseconds(eventTime), eventType, displayId, 0);
        checkAndClearExceptionFromCallback(env, "userActivityFromNative");
    }
}

static std::shared_ptr<ISystemSuspend> gSuspendHal = nullptr;
static sp<ISuspendControlService> gSuspendControl = nullptr;
static sp<system::suspend::internal::ISuspendControlServiceInternal> gSuspendControlInternal =
        nullptr;
static std::shared_ptr<IWakeLock> gSuspendBlocker = nullptr;
static std::mutex gSuspendMutex;

// Assume SystemSuspend HAL is always alive.
// TODO: Force device to restart if SystemSuspend HAL dies.
std::shared_ptr<ISystemSuspend> getSuspendHal() {
    static std::once_flag suspendHalFlag;
    std::call_once(suspendHalFlag, []() {
        const std::string suspendInstance = std::string() + ISystemSuspend::descriptor + "/default";
        gSuspendHal = ISystemSuspend::fromBinder(
                ndk::SpAIBinder(AServiceManager_waitForService(suspendInstance.c_str())));
        assert(gSuspendHal != nullptr);
    });
    return gSuspendHal;
}

sp<ISuspendControlService> getSuspendControl() {
    static std::once_flag suspendControlFlag;
    std::call_once(suspendControlFlag, [](){
        gSuspendControl = waitForService<ISuspendControlService>(String16("suspend_control"));
        LOG_ALWAYS_FATAL_IF(gSuspendControl == nullptr);
    });
    return gSuspendControl;
}

sp<system::suspend::internal::ISuspendControlServiceInternal> getSuspendControlInternal() {
    static std::once_flag suspendControlFlag;
    std::call_once(suspendControlFlag, []() {
        gSuspendControlInternal =
                waitForService<system::suspend::internal::ISuspendControlServiceInternal>(
                        String16("suspend_control_internal"));
        LOG_ALWAYS_FATAL_IF(gSuspendControlInternal == nullptr);
    });
    return gSuspendControlInternal;
}

void enableAutoSuspend() {
    static bool enabled = false;
    if (!enabled) {
        static sp<IBinder> autosuspendClientToken = new BBinder();
        sp<system::suspend::internal::ISuspendControlServiceInternal> suspendControl =
                getSuspendControlInternal();
        suspendControl->enableAutosuspend(autosuspendClientToken, &enabled);
    }

    {
        std::lock_guard<std::mutex> lock(gSuspendMutex);
        if (gSuspendBlocker) {
            gSuspendBlocker->release();
            gSuspendBlocker = nullptr;
        }
    }
}

void disableAutoSuspend() {
    std::lock_guard<std::mutex> lock(gSuspendMutex);
    if (!gSuspendBlocker) {
        std::shared_ptr<ISystemSuspend> suspendHal = getSuspendHal();
        suspendHal->acquireWakeLock(WakeLockType::PARTIAL, "PowerManager.SuspendLockout",
                                    &gSuspendBlocker);
        assert(gSuspendBlocker != nullptr);
    }
}

// ----------------------------------------------------------------------------

static void nativeInit(JNIEnv* env, jobject obj) {
    gPowerManagerServiceObj = env->NewGlobalRef(obj);
    gPowerHalController.init();
}

static void nativeAcquireSuspendBlocker(JNIEnv *env, jclass /* clazz */, jstring nameStr) {
    ScopedUtfChars name(env, nameStr);
    acquire_wake_lock(PARTIAL_WAKE_LOCK, name.c_str());
}

static void nativeReleaseSuspendBlocker(JNIEnv *env, jclass /* clazz */, jstring nameStr) {
    ScopedUtfChars name(env, nameStr);
    release_wake_lock(name.c_str());
}

static void nativeSetAutoSuspend(JNIEnv* /* env */, jclass /* clazz */, jboolean enable) {
    if (enable) {
        android::base::Timer t;
        enableAutoSuspend();
        if (t.duration() > 100ms) {
            ALOGD("Excessive delay in autosuspend_enable() while turning screen off");
        }
    } else {
        android::base::Timer t;
        disableAutoSuspend();
        if (t.duration() > 100ms) {
            ALOGD("Excessive delay in autosuspend_disable() while turning screen on");
        }
    }
}

static void nativeSetPowerBoost(JNIEnv* /* env */, jclass /* clazz */, jint boost,
                                jint durationMs) {
    setPowerBoost(static_cast<Boost>(boost), durationMs);
}

static jboolean nativeSetPowerMode(JNIEnv* /* env */, jclass /* clazz */, jint mode,
                                   jboolean enabled) {
    return setPowerMode(static_cast<Mode>(mode), enabled);
}

static bool nativeForceSuspend(JNIEnv* /* env */, jclass /* clazz */) {
    bool retval = false;
    getSuspendControlInternal()->forceSuspend(&retval);
    return retval;
}

// ----------------------------------------------------------------------------

static const JNINativeMethod gPowerManagerServiceMethods[] = {
        /* name, signature, funcPtr */
        {"nativeInit", "()V", (void*)nativeInit},
        {"nativeAcquireSuspendBlocker", "(Ljava/lang/String;)V",
         (void*)nativeAcquireSuspendBlocker},
        {"nativeForceSuspend", "()Z", (void*)nativeForceSuspend},
        {"nativeReleaseSuspendBlocker", "(Ljava/lang/String;)V",
         (void*)nativeReleaseSuspendBlocker},
        {"nativeSetAutoSuspend", "(Z)V", (void*)nativeSetAutoSuspend},
        {"nativeSetPowerBoost", "(II)V", (void*)nativeSetPowerBoost},
        {"nativeSetPowerMode", "(IZ)Z", (void*)nativeSetPowerMode},
};

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! (var), "Unable to find class " className);

#define GET_METHOD_ID(var, clazz, methodName, methodDescriptor) \
        var = env->GetMethodID(clazz, methodName, methodDescriptor); \
        LOG_FATAL_IF(! (var), "Unable to find method " methodName);

#define GET_FIELD_ID(var, clazz, fieldName, fieldDescriptor) \
        var = env->GetFieldID(clazz, fieldName, fieldDescriptor); \
        LOG_FATAL_IF(! (var), "Unable to find field " fieldName);

int register_android_server_PowerManagerService(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "com/android/server/power/PowerManagerService",
            gPowerManagerServiceMethods, NELEM(gPowerManagerServiceMethods));
    (void) res;  // Faked use when LOG_NDEBUG.
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    // Callbacks

    jclass clazz;
    FIND_CLASS(clazz, "com/android/server/power/PowerManagerService");

    GET_METHOD_ID(gPowerManagerServiceClassInfo.userActivityFromNative, clazz,
            "userActivityFromNative", "(JIII)V");

    if (!com::android::input::flags::rate_limit_user_activity_poke_in_dispatcher()) {
        // Initialize
        for (int i = 0; i <= USER_ACTIVITY_EVENT_LAST; i++) {
            gLastEventTime[i] = LLONG_MIN;
        }
    }
    gPowerManagerServiceObj = NULL;
    return 0;
}

} /* namespace android */
