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

#include <android/hardware/power/1.1/IPower.h>
#include <android/system/suspend/1.0/ISystemSuspend.h>
#include <android/system/suspend/ISuspendControlService.h>
#include <nativehelper/JNIHelp.h>
#include <vendor/lineage/power/1.0/ILineagePower.h>
#include "jni.h"

#include <nativehelper/ScopedUtfChars.h>

#include <limits.h>

#include <android-base/chrono_utils.h>
#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>
#include <binder/IServiceManager.h>
#include <gui/SurfaceComposerClient.h>
#include <hardware/power.h>
#include <hardware_legacy/power.h>
#include <hidl/ServiceManagement.h>
#include <utils/Timers.h>
#include <utils/misc.h>
#include <utils/String8.h>
#include <utils/Log.h>

#include "com_android_server_power_PowerManagerService.h"

using android::hardware::Return;
using android::hardware::Void;
using android::hardware::power::V1_0::PowerHint;
using android::hardware::power::V1_0::Feature;
using android::String8;
using android::system::suspend::V1_0::ISystemSuspend;
using android::system::suspend::V1_0::IWakeLock;
using android::system::suspend::V1_0::WakeLockType;
using android::system::suspend::ISuspendControlService;
using IPowerV1_1 = android::hardware::power::V1_1::IPower;
using IPowerV1_0 = android::hardware::power::V1_0::IPower;
using ILineagePowerV1_0 = vendor::lineage::power::V1_0::ILineagePower;
using vendor::lineage::power::V1_0::LineageFeature;

namespace android {

// ----------------------------------------------------------------------------

static struct {
    jmethodID userActivityFromNative;
} gPowerManagerServiceClassInfo;

// ----------------------------------------------------------------------------

static jobject gPowerManagerServiceObj;
// Use getPowerHal* to retrieve a copy
static sp<IPowerV1_0> gPowerHalV1_0_ = nullptr;
static sp<IPowerV1_1> gPowerHalV1_1_ = nullptr;
static sp<ILineagePowerV1_0> gLineagePowerHalV1_0_ = nullptr;
static bool gPowerHalExists = true;
static bool gLineagePowerHalExists = true;
static std::mutex gPowerHalMutex;
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

// Check validity of current handle to the power HAL service, and call getService() if necessary.
// The caller must be holding gPowerHalMutex.
static void connectPowerHalLocked() {
    if (gPowerHalExists && gPowerHalV1_0_ == nullptr) {
        gPowerHalV1_0_ = IPowerV1_0::getService();
        if (gPowerHalV1_0_ != nullptr) {
            ALOGI("Loaded power HAL 1.0 service");
            // Try cast to powerHAL V1_1
            gPowerHalV1_1_ =  IPowerV1_1::castFrom(gPowerHalV1_0_);
            if (gPowerHalV1_1_ == nullptr) {
            } else {
                ALOGI("Loaded power HAL 1.1 service");
            }
        } else {
            ALOGI("Couldn't load power HAL service");
            gPowerHalExists = false;
        }
    }
}

// Check validity of current handle to the Lineage power HAL service, and call getService() if necessary.
// The caller must be holding gPowerHalMutex.
void connectLineagePowerHalLocked() {
    if (gLineagePowerHalExists && gLineagePowerHalV1_0_ == nullptr) {
        gLineagePowerHalV1_0_ = ILineagePowerV1_0::getService();
        if (gLineagePowerHalV1_0_ != nullptr) {
            ALOGI("Loaded power HAL service");
        } else {
            ALOGI("Couldn't load power HAL service");
            gLineagePowerHalExists = false;
        }
    }
}

// Retrieve a copy of PowerHAL V1_0
sp<IPowerV1_0> getPowerHalV1_0() {
    std::lock_guard<std::mutex> lock(gPowerHalMutex);
    connectPowerHalLocked();
    return gPowerHalV1_0_;
}

// Retrieve a copy of PowerHAL V1_1
sp<IPowerV1_1> getPowerHalV1_1() {
    std::lock_guard<std::mutex> lock(gPowerHalMutex);
    connectPowerHalLocked();
    return gPowerHalV1_1_;
}

// Retrieve a copy of LineagePowerHAL V1_0
sp<ILineagePowerV1_0> getLineagePowerHalV1_0() {
    std::lock_guard<std::mutex> lock(gPowerHalMutex);
    connectLineagePowerHalLocked();
    return gLineagePowerHalV1_0_;
}

// Check if a call to a power HAL function failed; if so, log the failure and invalidate the
// current handle to the power HAL service.
bool processPowerHalReturn(const Return<void> &ret, const char* functionName) {
    if (!ret.isOk()) {
        ALOGE("%s() failed: power HAL service not available.", functionName);
        gPowerHalMutex.lock();
        gPowerHalV1_0_ = nullptr;
        gPowerHalV1_1_ = nullptr;
        gPowerHalMutex.unlock();
    }
    return ret.isOk();
}

static void sendPowerHint(PowerHint hintId, uint32_t data) {
    sp<IPowerV1_1> powerHalV1_1 = getPowerHalV1_1();
    Return<void> ret;
    if (powerHalV1_1 != nullptr) {
        ret = powerHalV1_1->powerHintAsync(hintId, data);
        processPowerHalReturn(ret, "powerHintAsync");
    } else {
        sp<IPowerV1_0> powerHalV1_0 = getPowerHalV1_0();
        if (powerHalV1_0 != nullptr) {
            ret = powerHalV1_0->powerHint(hintId, data);
            processPowerHalReturn(ret, "powerHint");
        }
    }

    SurfaceComposerClient::notifyPowerHint(static_cast<int32_t>(hintId));
}

void android_server_PowerManagerService_userActivity(nsecs_t eventTime, int32_t eventType) {
    if (gPowerManagerServiceObj) {
        // Throttle calls into user activity by event type.
        // We're a little conservative about argument checking here in case the caller
        // passes in bad data which could corrupt system state.
        if (eventType >= 0 && eventType <= USER_ACTIVITY_EVENT_LAST) {
            nsecs_t now = systemTime(SYSTEM_TIME_MONOTONIC);
            if (eventTime > now) {
                eventTime = now;
            }

            if (gLastEventTime[eventType] + MIN_TIME_BETWEEN_USERACTIVITIES > eventTime) {
                return;
            }
            gLastEventTime[eventType] = eventTime;

            // Tell the power HAL when user activity occurs.
            sendPowerHint(PowerHint::INTERACTION, 0);
        }

        JNIEnv* env = AndroidRuntime::getJNIEnv();

        env->CallVoidMethod(gPowerManagerServiceObj,
                gPowerManagerServiceClassInfo.userActivityFromNative,
                nanoseconds_to_milliseconds(eventTime), eventType, 0);
        checkAndClearExceptionFromCallback(env, "userActivityFromNative");
    }
}

static sp<ISystemSuspend> gSuspendHal = nullptr;
static sp<ISuspendControlService> gSuspendControl = nullptr;
static sp<IWakeLock> gSuspendBlocker = nullptr;
static std::mutex gSuspendMutex;

// Assume SystemSuspend HAL is always alive.
// TODO: Force device to restart if SystemSuspend HAL dies.
sp<ISystemSuspend> getSuspendHal() {
    static std::once_flag suspendHalFlag;
    std::call_once(suspendHalFlag, [](){
        ::android::hardware::details::waitForHwService(ISystemSuspend::descriptor, "default");
        gSuspendHal = ISystemSuspend::getService();
        assert(gSuspendHal != nullptr);
    });
    return gSuspendHal;
}

sp<ISuspendControlService> getSuspendControl() {
    static std::once_flag suspendControlFlag;
    std::call_once(suspendControlFlag, [](){
        while(gSuspendControl == nullptr) {
            sp<IBinder> control =
                    defaultServiceManager()->getService(String16("suspend_control"));
            if (control != nullptr) {
                gSuspendControl = interface_cast<ISuspendControlService>(control);
            }
        }
    });
    return gSuspendControl;
}

void enableAutoSuspend() {
    static bool enabled = false;
    if (!enabled) {
        sp<ISuspendControlService> suspendControl = getSuspendControl();
        suspendControl->enableAutosuspend(&enabled);
    }

    {
        std::lock_guard<std::mutex> lock(gSuspendMutex);
        if (gSuspendBlocker) {
            gSuspendBlocker->release();
            gSuspendBlocker.clear();
        }
    }
}

void disableAutoSuspend() {
    std::lock_guard<std::mutex> lock(gSuspendMutex);
    if (!gSuspendBlocker) {
        sp<ISystemSuspend> suspendHal = getSuspendHal();
        gSuspendBlocker = suspendHal->acquireWakeLock(WakeLockType::PARTIAL,
                "PowerManager.SuspendLockout");
    }
}

static jint nativeGetFeature(JNIEnv* /* env */, jclass /* clazz */, jint featureId) {
    int value = -1;

    sp<ILineagePowerV1_0> lineagePowerHalV1_0 = getLineagePowerHalV1_0();
    if (lineagePowerHalV1_0 != nullptr) {
        value = lineagePowerHalV1_0->getFeature(static_cast<LineageFeature>(featureId));
    }

    return static_cast<jint>(value);
}

// ----------------------------------------------------------------------------

static void nativeInit(JNIEnv* env, jobject obj) {
    gPowerManagerServiceObj = env->NewGlobalRef(obj);

    gPowerHalMutex.lock();
    connectPowerHalLocked();
    gPowerHalMutex.unlock();
}

static void nativeAcquireSuspendBlocker(JNIEnv *env, jclass /* clazz */, jstring nameStr) {
    ScopedUtfChars name(env, nameStr);
    acquire_wake_lock(PARTIAL_WAKE_LOCK, name.c_str());
}

static void nativeReleaseSuspendBlocker(JNIEnv *env, jclass /* clazz */, jstring nameStr) {
    ScopedUtfChars name(env, nameStr);
    release_wake_lock(name.c_str());
}

static void nativeSetInteractive(JNIEnv* /* env */, jclass /* clazz */, jboolean enable) {
    sp<IPowerV1_0> powerHalV1_0 = getPowerHalV1_0();
    if (powerHalV1_0 != nullptr) {
        android::base::Timer t;
        Return<void> ret = powerHalV1_0->setInteractive(enable);
        processPowerHalReturn(ret, "setInteractive");
        if (t.duration() > 20ms) {
            ALOGD("Excessive delay in setInteractive(%s) while turning screen %s",
                  enable ? "true" : "false", enable ? "on" : "off");
        }
    }
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

static void nativeSendPowerHint(JNIEnv* /* env */, jclass /* clazz */, jint hintId, jint data) {
    sendPowerHint(static_cast<PowerHint>(hintId), data);
}

static void nativeSetFeature(JNIEnv* /* env */, jclass /* clazz */, jint featureId, jint data) {
    sp<IPowerV1_0> powerHalV1_0 = getPowerHalV1_0();
    if (powerHalV1_0 != nullptr) {
        Return<void> ret = powerHalV1_0->setFeature((Feature)featureId, static_cast<bool>(data));
        processPowerHalReturn(ret, "setFeature");
    }
}

static bool nativeForceSuspend(JNIEnv* /* env */, jclass /* clazz */) {
    bool retval = false;
    getSuspendControl()->forceSuspend(&retval);
    return retval;
}

// ----------------------------------------------------------------------------

static const JNINativeMethod gPowerManagerServiceMethods[] = {
    /* name, signature, funcPtr */
    { "nativeInit", "()V",
            (void*) nativeInit },
    { "nativeAcquireSuspendBlocker", "(Ljava/lang/String;)V",
            (void*) nativeAcquireSuspendBlocker },
    { "nativeForceSuspend", "()Z",
            (void*) nativeForceSuspend },
    { "nativeReleaseSuspendBlocker", "(Ljava/lang/String;)V",
            (void*) nativeReleaseSuspendBlocker },
    { "nativeSetInteractive", "(Z)V",
            (void*) nativeSetInteractive },
    { "nativeSetAutoSuspend", "(Z)V",
            (void*) nativeSetAutoSuspend },
    { "nativeSendPowerHint", "(II)V",
            (void*) nativeSendPowerHint },
    { "nativeSetFeature", "(II)V",
            (void*) nativeSetFeature },
    { "nativeGetFeature", "(I)I",
            (void*) nativeGetFeature },
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
            "userActivityFromNative", "(JII)V");

    // Initialize
    for (int i = 0; i <= USER_ACTIVITY_EVENT_LAST; i++) {
        gLastEventTime[i] = LLONG_MIN;
    }
    gPowerManagerServiceObj = NULL;
    return 0;
}

} /* namespace android */
