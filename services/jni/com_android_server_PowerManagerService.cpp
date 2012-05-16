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

#include "JNIHelp.h"
#include "jni.h"

#include <limits.h>

#include <android_runtime/AndroidRuntime.h>
#include <gui/ISurfaceComposer.h>
#include <utils/Timers.h>
#include <utils/misc.h>
#include <utils/String8.h>
#include <hardware/power.h>
#include <hardware_legacy/power.h>
#include <cutils/android_reboot.h>
#include <suspend/autosuspend.h>

#include <private/gui/ComposerService.h>

#include "com_android_server_PowerManagerService.h"

namespace android {

// ----------------------------------------------------------------------------

static struct {
    jmethodID goToSleep;
    jmethodID userActivity;
} gPowerManagerServiceClassInfo;

// ----------------------------------------------------------------------------

static jobject gPowerManagerServiceObj;
static struct power_module* gPowerModule;

static Mutex gPowerManagerLock;
static bool gScreenOn;
static bool gScreenBright;

static nsecs_t gLastEventTime[POWER_MANAGER_LAST_EVENT + 1];

// Throttling interval for user activity calls.
static const nsecs_t MIN_TIME_BETWEEN_USERACTIVITIES = 500 * 1000000L; // 500ms

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

bool android_server_PowerManagerService_isScreenOn() {
    AutoMutex _l(gPowerManagerLock);
    return gScreenOn;
}

bool android_server_PowerManagerService_isScreenBright() {
    AutoMutex _l(gPowerManagerLock);
    return gScreenBright;
}

void android_server_PowerManagerService_userActivity(nsecs_t eventTime, int32_t eventType) {
    // Tell the power HAL when user activity occurs.
    if (gPowerModule && gPowerModule->powerHint) {
        gPowerModule->powerHint(gPowerModule, POWER_HINT_INTERACTION, NULL);
    }

    if (gPowerManagerServiceObj) {
        // Throttle calls into user activity by event type.
        // We're a little conservative about argument checking here in case the caller
        // passes in bad data which could corrupt system state.
        if (eventType >= 0 && eventType <= POWER_MANAGER_LAST_EVENT) {
            nsecs_t now = systemTime(SYSTEM_TIME_MONOTONIC);
            if (eventTime > now) {
                eventTime = now;
            }

            if (gLastEventTime[eventType] + MIN_TIME_BETWEEN_USERACTIVITIES > eventTime) {
                return;
            }
            gLastEventTime[eventType] = eventTime;
        }

        JNIEnv* env = AndroidRuntime::getJNIEnv();

        env->CallVoidMethod(gPowerManagerServiceObj, gPowerManagerServiceClassInfo.userActivity,
                nanoseconds_to_milliseconds(eventTime), false, eventType, false);
        checkAndClearExceptionFromCallback(env, "userActivity");
    }
}

void android_server_PowerManagerService_goToSleep(nsecs_t eventTime) {
    if (gPowerManagerServiceObj) {
        JNIEnv* env = AndroidRuntime::getJNIEnv();

        env->CallVoidMethod(gPowerManagerServiceObj, gPowerManagerServiceClassInfo.goToSleep,
                nanoseconds_to_milliseconds(eventTime));
        checkAndClearExceptionFromCallback(env, "goToSleep");
    }
}

// ----------------------------------------------------------------------------

static void nativeInit(JNIEnv* env, jobject obj) {
    gPowerManagerServiceObj = env->NewGlobalRef(obj);

    status_t err = hw_get_module(POWER_HARDWARE_MODULE_ID,
            (hw_module_t const**)&gPowerModule);
    if (!err) {
        gPowerModule->init(gPowerModule);
    } else {
        ALOGE("Couldn't load %s module (%s)", POWER_HARDWARE_MODULE_ID, strerror(-err));
    }
}

static void nativeSetPowerState(JNIEnv* env,
        jobject serviceObj, jboolean screenOn, jboolean screenBright) {
    AutoMutex _l(gPowerManagerLock);
    gScreenOn = screenOn;
    gScreenBright = screenBright;
}

static void nativeStartSurfaceFlingerAnimation(JNIEnv* env,
        jobject obj, jint mode) {
    sp<ISurfaceComposer> s(ComposerService::getComposerService());
    s->turnElectronBeamOff(mode);
}

static void nativeAcquireWakeLock(JNIEnv *env, jobject clazz, jint lock, jstring idObj) {
    if (idObj == NULL) {
        jniThrowNullPointerException(env, "id is null");
        return;
    }

    const char *id = env->GetStringUTFChars(idObj, NULL);

    acquire_wake_lock(lock, id);

    env->ReleaseStringUTFChars(idObj, id);
}

static void nativeReleaseWakeLock(JNIEnv *env, jobject clazz, jstring idObj) {
    if (idObj == NULL) {
        jniThrowNullPointerException(env, "id is null");
        return ;
    }

    const char *id = env->GetStringUTFChars(idObj, NULL);

    release_wake_lock(id);

    env->ReleaseStringUTFChars(idObj, id);

}

static int nativeSetScreenState(JNIEnv *env, jobject clazz, jboolean on) {
    if (on) {
        autosuspend_disable();
        if (gPowerModule) {
            gPowerModule->setInteractive(gPowerModule, true);
        }
    } else {
        if (gPowerModule) {
            gPowerModule->setInteractive(gPowerModule, false);
        }
        autosuspend_enable();
    }

    return 0;
}

static void nativeShutdown(JNIEnv *env, jobject clazz) {
    android_reboot(ANDROID_RB_POWEROFF, 0, 0);
}

static void nativeReboot(JNIEnv *env, jobject clazz, jstring reason) {
    if (reason == NULL) {
        android_reboot(ANDROID_RB_RESTART, 0, 0);
    } else {
        const char *chars = env->GetStringUTFChars(reason, NULL);
        android_reboot(ANDROID_RB_RESTART2, 0, (char *) chars);
        env->ReleaseStringUTFChars(reason, chars);  // In case it fails.
    }
    jniThrowIOException(env, errno);
}


// ----------------------------------------------------------------------------

static JNINativeMethod gPowerManagerServiceMethods[] = {
    /* name, signature, funcPtr */
    { "nativeInit", "()V",
            (void*) nativeInit },
    { "nativeSetPowerState", "(ZZ)V",
            (void*) nativeSetPowerState },
    { "nativeStartSurfaceFlingerAnimation", "(I)V",
            (void*) nativeStartSurfaceFlingerAnimation },
    { "nativeAcquireWakeLock", "(ILjava/lang/String;)V",
            (void*) nativeAcquireWakeLock },
    { "nativeReleaseWakeLock", "(Ljava/lang/String;)V",
            (void*) nativeReleaseWakeLock },
    { "nativeSetScreenState", "(Z)I",
            (void*) nativeSetScreenState },
    { "nativeShutdown", "()V",
            (void*) nativeShutdown },
    { "nativeReboot", "(Ljava/lang/String;)V",
            (void*) nativeReboot },
};

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class " className);

#define GET_METHOD_ID(var, clazz, methodName, methodDescriptor) \
        var = env->GetMethodID(clazz, methodName, methodDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find method " methodName);

#define GET_FIELD_ID(var, clazz, fieldName, fieldDescriptor) \
        var = env->GetFieldID(clazz, fieldName, fieldDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find field " fieldName);

int register_android_server_PowerManagerService(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "com/android/server/PowerManagerService",
            gPowerManagerServiceMethods, NELEM(gPowerManagerServiceMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    // Callbacks

    jclass clazz;
    FIND_CLASS(clazz, "com/android/server/PowerManagerService");

    GET_METHOD_ID(gPowerManagerServiceClassInfo.goToSleep, clazz,
            "goToSleep", "(J)V");

    GET_METHOD_ID(gPowerManagerServiceClassInfo.userActivity, clazz,
            "userActivity", "(JZIZ)V");

    // Initialize
    for (int i = 0; i < POWER_MANAGER_LAST_EVENT; i++) {
        gLastEventTime[i] = LLONG_MIN;
    }
    gScreenOn = true;
    gScreenBright = true;
    gPowerManagerServiceObj = NULL;
    gPowerModule = NULL;
    return 0;
}

} /* namespace android */
