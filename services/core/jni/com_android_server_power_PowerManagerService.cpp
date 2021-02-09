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
#include <android/hardware/power/Boost.h>
#include <android/hardware/power/IPower.h>
#include <android/hardware/power/Mode.h>
#include <android/system/suspend/1.0/ISystemSuspend.h>
#include <android/system/suspend/ISuspendControlService.h>
#include <nativehelper/JNIHelp.h>
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
using android::hardware::power::Boost;
using android::hardware::power::Mode;
using android::hardware::power::V1_0::PowerHint;
using android::hardware::power::V1_0::Feature;
using android::String8;
using android::system::suspend::V1_0::ISystemSuspend;
using android::system::suspend::V1_0::IWakeLock;
using android::system::suspend::V1_0::WakeLockType;
using android::system::suspend::ISuspendControlService;
using IPowerV1_1 = android::hardware::power::V1_1::IPower;
using IPowerV1_0 = android::hardware::power::V1_0::IPower;
using IPowerAidl = android::hardware::power::IPower;

namespace android {

// ----------------------------------------------------------------------------

static struct {
    jmethodID userActivityFromNative;
} gPowerManagerServiceClassInfo;

// ----------------------------------------------------------------------------

static jobject gPowerManagerServiceObj;
static sp<IPowerV1_0> gPowerHalHidlV1_0_ = nullptr;
static sp<IPowerV1_1> gPowerHalHidlV1_1_ = nullptr;
static sp<IPowerAidl> gPowerHalAidl_ = nullptr;
static std::mutex gPowerHalMutex;

enum class HalVersion {
    NONE,
    HIDL_1_0,
    HIDL_1_1,
    AIDL,
};

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

// Check validity of current handle to the power HAL service, and connect to it if necessary.
// The caller must be holding gPowerHalMutex.
static HalVersion connectPowerHalLocked() {
    static bool gPowerHalHidlExists = true;
    static bool gPowerHalAidlExists = true;
    if (!gPowerHalHidlExists && !gPowerHalAidlExists) {
        return HalVersion::NONE;
    }
    if (gPowerHalAidlExists) {
        if (!gPowerHalAidl_) {
            gPowerHalAidl_ = waitForVintfService<IPowerAidl>();
        }
        if (gPowerHalAidl_) {
            ALOGV("Successfully connected to Power HAL AIDL service.");
            return HalVersion::AIDL;
        } else {
            gPowerHalAidlExists = false;
        }
    }
    if (gPowerHalHidlExists && gPowerHalHidlV1_0_ == nullptr) {
        gPowerHalHidlV1_0_ = IPowerV1_0::getService();
        if (gPowerHalHidlV1_0_) {
            ALOGV("Successfully connected to Power HAL HIDL 1.0 service.");
            // Try cast to powerHAL HIDL V1_1
            gPowerHalHidlV1_1_ = IPowerV1_1::castFrom(gPowerHalHidlV1_0_);
            if (gPowerHalHidlV1_1_) {
                ALOGV("Successfully connected to Power HAL HIDL 1.1 service.");
            }
        } else {
            ALOGV("Couldn't load power HAL HIDL service");
            gPowerHalHidlExists = false;
            return HalVersion::NONE;
        }
    }
    if (gPowerHalHidlV1_1_) {
        return HalVersion::HIDL_1_1;
    } else if (gPowerHalHidlV1_0_) {
        return HalVersion::HIDL_1_0;
    }
    return HalVersion::NONE;
}

// Retrieve a copy of PowerHAL HIDL V1_0
sp<IPowerV1_0> getPowerHalHidlV1_0() {
    std::lock_guard<std::mutex> lock(gPowerHalMutex);
    HalVersion halVersion = connectPowerHalLocked();
    if (halVersion == HalVersion::HIDL_1_0 || halVersion == HalVersion::HIDL_1_1) {
        return gPowerHalHidlV1_0_;
    }

    return nullptr;
}

// Retrieve a copy of PowerHAL HIDL V1_1
sp<IPowerV1_1> getPowerHalHidlV1_1() {
    std::lock_guard<std::mutex> lock(gPowerHalMutex);
    if (connectPowerHalLocked() == HalVersion::HIDL_1_1) {
        return gPowerHalHidlV1_1_;
    }

    return nullptr;
}

// Check if a call to a power HAL function failed; if so, log the failure and invalidate the
// current handle to the power HAL service.
bool processPowerHalReturn(bool isOk, const char* functionName) {
    if (!isOk) {
        ALOGE("%s() failed: power HAL service not available.", functionName);
        gPowerHalMutex.lock();
        gPowerHalHidlV1_0_ = nullptr;
        gPowerHalHidlV1_1_ = nullptr;
        gPowerHalAidl_ = nullptr;
        gPowerHalMutex.unlock();
    }
    return isOk;
}

enum class HalSupport {
    UNKNOWN = 0,
    ON,
    OFF,
};

static void setPowerBoostWithHandle(sp<IPowerAidl> handle, Boost boost, int32_t durationMs) {
    // Android framework only sends boost upto DISPLAY_UPDATE_IMMINENT.
    // Need to increase the array size if more boost supported.
    static std::array<std::atomic<HalSupport>,
                      static_cast<int32_t>(Boost::DISPLAY_UPDATE_IMMINENT) + 1>
            boostSupportedArray = {HalSupport::UNKNOWN};
    size_t idx = static_cast<size_t>(boost);

    // Quick return if boost is not supported by HAL
    if (idx >= boostSupportedArray.size() || boostSupportedArray[idx] == HalSupport::OFF) {
        ALOGV("Skipped setPowerBoost %s because HAL doesn't support it", toString(boost).c_str());
        return;
    }

    if (boostSupportedArray[idx] == HalSupport::UNKNOWN) {
        bool isSupported = false;
        handle->isBoostSupported(boost, &isSupported);
        boostSupportedArray[idx] = isSupported ? HalSupport::ON : HalSupport::OFF;
        if (!isSupported) {
            ALOGV("Skipped setPowerBoost %s because HAL doesn't support it",
                  toString(boost).c_str());
            return;
        }
    }

    auto ret = handle->setBoost(boost, durationMs);
    processPowerHalReturn(ret.isOk(), "setPowerBoost");
}

static void setPowerBoost(Boost boost, int32_t durationMs) {
    std::unique_lock<std::mutex> lock(gPowerHalMutex);
    if (connectPowerHalLocked() != HalVersion::AIDL) {
        ALOGV("Power HAL AIDL not available");
        return;
    }
    sp<IPowerAidl> handle = gPowerHalAidl_;
    lock.unlock();
    setPowerBoostWithHandle(handle, boost, durationMs);
}

static bool setPowerModeWithHandle(sp<IPowerAidl> handle, Mode mode, bool enabled) {
    // Android framework only sends mode upto DISPLAY_INACTIVE.
    // Need to increase the array if more mode supported.
    static std::array<std::atomic<HalSupport>, static_cast<int32_t>(Mode::DISPLAY_INACTIVE) + 1>
            modeSupportedArray = {HalSupport::UNKNOWN};
    size_t idx = static_cast<size_t>(mode);

    // Quick return if mode is not supported by HAL
    if (idx >= modeSupportedArray.size() || modeSupportedArray[idx] == HalSupport::OFF) {
        ALOGV("Skipped setPowerMode %s because HAL doesn't support it", toString(mode).c_str());
        return false;
    }

    if (modeSupportedArray[idx] == HalSupport::UNKNOWN) {
        bool isSupported = false;
        handle->isModeSupported(mode, &isSupported);
        modeSupportedArray[idx] = isSupported ? HalSupport::ON : HalSupport::OFF;
        if (!isSupported) {
            ALOGV("Skipped setPowerMode %s because HAL doesn't support it", toString(mode).c_str());
            return false;
        }
    }

    auto ret = handle->setMode(mode, enabled);
    processPowerHalReturn(ret.isOk(), "setPowerMode");
    return ret.isOk();
}

static bool setPowerMode(Mode mode, bool enabled) {
    std::unique_lock<std::mutex> lock(gPowerHalMutex);
    if (connectPowerHalLocked() != HalVersion::AIDL) {
        ALOGV("Power HAL AIDL not available");
        return false;
    }
    sp<IPowerAidl> handle = gPowerHalAidl_;
    lock.unlock();
    return setPowerModeWithHandle(handle, mode, enabled);
}

static void sendPowerHint(PowerHint hintId, uint32_t data) {
    std::unique_lock<std::mutex> lock(gPowerHalMutex);
    switch (connectPowerHalLocked()) {
        case HalVersion::NONE:
            return;
        case HalVersion::HIDL_1_0: {
            sp<IPowerV1_0> handle = gPowerHalHidlV1_0_;
            lock.unlock();
            auto ret = handle->powerHint(hintId, data);
            processPowerHalReturn(ret.isOk(), "powerHint");
            break;
        }
        case HalVersion::HIDL_1_1: {
            sp<IPowerV1_1> handle = gPowerHalHidlV1_1_;
            lock.unlock();
            auto ret = handle->powerHintAsync(hintId, data);
            processPowerHalReturn(ret.isOk(), "powerHintAsync");
            break;
        }
        case HalVersion::AIDL: {
            if (hintId == PowerHint::INTERACTION) {
                sp<IPowerAidl> handle = gPowerHalAidl_;
                lock.unlock();
                setPowerBoostWithHandle(handle, Boost::INTERACTION, data);
                break;
            } else if (hintId == PowerHint::LAUNCH) {
                sp<IPowerAidl> handle = gPowerHalAidl_;
                lock.unlock();
                setPowerModeWithHandle(handle, Mode::LAUNCH, static_cast<bool>(data));
                break;
            } else if (hintId == PowerHint::LOW_POWER) {
                sp<IPowerAidl> handle = gPowerHalAidl_;
                lock.unlock();
                setPowerModeWithHandle(handle, Mode::LOW_POWER, static_cast<bool>(data));
                break;
            } else if (hintId == PowerHint::SUSTAINED_PERFORMANCE) {
                sp<IPowerAidl> handle = gPowerHalAidl_;
                lock.unlock();
                setPowerModeWithHandle(handle, Mode::SUSTAINED_PERFORMANCE,
                                       static_cast<bool>(data));
                break;
            } else if (hintId == PowerHint::VR_MODE) {
                sp<IPowerAidl> handle = gPowerHalAidl_;
                lock.unlock();
                setPowerModeWithHandle(handle, Mode::VR, static_cast<bool>(data));
                break;
            } else {
                ALOGE("Unsupported power hint: %s.", toString(hintId).c_str());
                return;
            }
        }
        default: {
            ALOGE("Unknown power HAL state");
            return;
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
        gSuspendControl = waitForService<ISuspendControlService>(String16("suspend_control"));
        LOG_ALWAYS_FATAL_IF(gSuspendControl == nullptr);
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
    std::unique_lock<std::mutex> lock(gPowerHalMutex);
    switch (connectPowerHalLocked()) {
        case HalVersion::NONE:
            return;
        case HalVersion::HIDL_1_0:
            FALLTHROUGH_INTENDED;
        case HalVersion::HIDL_1_1: {
            android::base::Timer t;
            sp<IPowerV1_0> handle = gPowerHalHidlV1_0_;
            lock.unlock();
            auto ret = handle->setInteractive(enable);
            processPowerHalReturn(ret.isOk(), "setInteractive");
            if (t.duration() > 20ms) {
                ALOGD("Excessive delay in setInteractive(%s) while turning screen %s",
                      enable ? "true" : "false", enable ? "on" : "off");
            }
            return;
        }
        case HalVersion::AIDL: {
            sp<IPowerAidl> handle = gPowerHalAidl_;
            lock.unlock();
            setPowerModeWithHandle(handle, Mode::INTERACTIVE, enable);
            return;
        }
        default: {
            ALOGE("Unknown power HAL state");
            return;
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

static void nativeSetPowerBoost(JNIEnv* /* env */, jclass /* clazz */, jint boost,
                                jint durationMs) {
    setPowerBoost(static_cast<Boost>(boost), durationMs);
}

static jboolean nativeSetPowerMode(JNIEnv* /* env */, jclass /* clazz */, jint mode,
                                   jboolean enabled) {
    return setPowerMode(static_cast<Mode>(mode), enabled);
}

static void nativeSetFeature(JNIEnv* /* env */, jclass /* clazz */, jint featureId, jint data) {
    std::unique_lock<std::mutex> lock(gPowerHalMutex);
    switch (connectPowerHalLocked()) {
        case HalVersion::NONE:
            return;
        case HalVersion::HIDL_1_0:
            FALLTHROUGH_INTENDED;
        case HalVersion::HIDL_1_1: {
            sp<IPowerV1_0> handle = gPowerHalHidlV1_0_;
            lock.unlock();
            auto ret = handle->setFeature(static_cast<Feature>(featureId), static_cast<bool>(data));
            processPowerHalReturn(ret.isOk(), "setFeature");
            return;
        }
        case HalVersion::AIDL: {
            sp<IPowerAidl> handle = gPowerHalAidl_;
            lock.unlock();
            setPowerModeWithHandle(handle, Mode::DOUBLE_TAP_TO_WAKE, static_cast<bool>(data));
            return;
        }
        default: {
            ALOGE("Unknown power HAL state");
            return;
        }
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
        {"nativeInit", "()V", (void*)nativeInit},
        {"nativeAcquireSuspendBlocker", "(Ljava/lang/String;)V",
         (void*)nativeAcquireSuspendBlocker},
        {"nativeForceSuspend", "()Z", (void*)nativeForceSuspend},
        {"nativeReleaseSuspendBlocker", "(Ljava/lang/String;)V",
         (void*)nativeReleaseSuspendBlocker},
        {"nativeSetInteractive", "(Z)V", (void*)nativeSetInteractive},
        {"nativeSetAutoSuspend", "(Z)V", (void*)nativeSetAutoSuspend},
        {"nativeSendPowerHint", "(II)V", (void*)nativeSendPowerHint},
        {"nativeSetPowerBoost", "(II)V", (void*)nativeSetPowerBoost},
        {"nativeSetPowerMode", "(IZ)Z", (void*)nativeSetPowerMode},
        {"nativeSetFeature", "(II)V", (void*)nativeSetFeature},
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
