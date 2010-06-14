/*
 * Copyright (C) 2007 The Android Open Source Project
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

#define LOG_TAG "Input"

#include "jni.h"
#include "JNIHelp.h"
#include <utils/misc.h>
#include <utils/Log.h>

#include <ui/EventHub.h>
#include <utils/threads.h>

#include <stdio.h>

namespace android {

// ----------------------------------------------------------------------------

static struct input_offsets_t
{
    jfieldID mMinValue;
    jfieldID mMaxValue;
    jfieldID mFlat;
    jfieldID mFuzz;
    
    jfieldID mDeviceId;
    jfieldID mType;
    jfieldID mScancode;
    jfieldID mKeycode;
    jfieldID mFlags;
    jfieldID mValue;
    jfieldID mWhen;
} gInputOffsets;

// ----------------------------------------------------------------------------

static Mutex gLock;
static sp<EventHub> gHub;

static jboolean
android_server_KeyInputQueue_readEvent(JNIEnv* env, jobject clazz,
                                          jobject event)
{
    gLock.lock();
    sp<EventHub> hub = gHub;
    if (hub == NULL) {
        hub = new EventHub;
        gHub = hub;
    }
    gLock.unlock();

    int32_t deviceId;
    int32_t type;
    int32_t scancode, keycode;
    uint32_t flags;
    int32_t value;
    nsecs_t when;
    bool res = hub->getEvent(&deviceId, &type, &scancode, &keycode,
            &flags, &value, &when);

    env->SetIntField(event, gInputOffsets.mDeviceId, (jint)deviceId);
    env->SetIntField(event, gInputOffsets.mType, (jint)type);
    env->SetIntField(event, gInputOffsets.mScancode, (jint)scancode);
    env->SetIntField(event, gInputOffsets.mKeycode, (jint)keycode);
    env->SetIntField(event, gInputOffsets.mFlags, (jint)flags);
    env->SetIntField(event, gInputOffsets.mValue, value);
    env->SetLongField(event, gInputOffsets.mWhen,
                        (jlong)(nanoseconds_to_milliseconds(when)));

    return res;
}

static jint
android_server_KeyInputQueue_getDeviceClasses(JNIEnv* env, jobject clazz,
                                              jint deviceId)
{
    jint classes = 0;
    gLock.lock();
    if (gHub != NULL) classes = gHub->getDeviceClasses(deviceId);
    gLock.unlock();
    return classes;
}

static jstring
android_server_KeyInputQueue_getDeviceName(JNIEnv* env, jobject clazz,
                                              jint deviceId)
{
    String8 name;
    gLock.lock();
    if (gHub != NULL) name = gHub->getDeviceName(deviceId);
    gLock.unlock();
    
    if (name.size() > 0) {
        return env->NewStringUTF(name.string());
    }
    return NULL;
}

static void
android_server_KeyInputQueue_addExcludedDevice(JNIEnv* env, jobject clazz,
                                              jstring deviceName)
{
    gLock.lock();
    sp<EventHub> hub = gHub;
    if (hub == NULL) {
        hub = new EventHub;
        gHub = hub;
    }
    gLock.unlock();

    const char* nameStr = env->GetStringUTFChars(deviceName, NULL);
    gHub->addExcludedDevice(nameStr);
    env->ReleaseStringUTFChars(deviceName, nameStr);
}

static jboolean
android_server_KeyInputQueue_getAbsoluteInfo(JNIEnv* env, jobject clazz,
                                             jint deviceId, jint axis,
                                             jobject info)
{
    int32_t minValue, maxValue, flat, fuzz;
    int res = -1;
    gLock.lock();
    if (gHub != NULL) {
        res = gHub->getAbsoluteInfo(deviceId, axis,
                &minValue, &maxValue, &flat, &fuzz);
    }
    gLock.unlock();
    
    if (res < 0) return JNI_FALSE;
    
    env->SetIntField(info, gInputOffsets.mMinValue, (jint)minValue);
    env->SetIntField(info, gInputOffsets.mMaxValue, (jint)maxValue);
    env->SetIntField(info, gInputOffsets.mFlat, (jint)flat);
    env->SetIntField(info, gInputOffsets.mFuzz, (jint)fuzz);
    return JNI_TRUE;
}

static jint
android_server_KeyInputQueue_getSwitchState(JNIEnv* env, jobject clazz,
                                           jint sw)
{
    jint st = -1;
    gLock.lock();
    if (gHub != NULL) st = gHub->getSwitchState(-1, -1, sw);
    gLock.unlock();
    
    return st;
}

static jint
android_server_KeyInputQueue_getSwitchStateDevice(JNIEnv* env, jobject clazz,
                                            jint deviceId, jint sw)
{
    jint st = -1;
    gLock.lock();
    if (gHub != NULL) st = gHub->getSwitchState(deviceId, -1, sw);
    gLock.unlock();
    
    return st;
}

static jint
android_server_KeyInputQueue_getScancodeState(JNIEnv* env, jobject clazz,
                                           jint sw)
{
    jint st = -1;
    gLock.lock();
    if (gHub != NULL) st = gHub->getScanCodeState(0, -1, sw);
    gLock.unlock();
    
    return st;
}

static jint
android_server_KeyInputQueue_getScancodeStateDevice(JNIEnv* env, jobject clazz,
                                            jint deviceId, jint sw)
{
    jint st = -1;
    gLock.lock();
    if (gHub != NULL) st = gHub->getScanCodeState(deviceId, -1, sw);
    gLock.unlock();
    
    return st;
}

static jint
android_server_KeyInputQueue_getKeycodeState(JNIEnv* env, jobject clazz,
                                           jint sw)
{
    jint st = -1;
    gLock.lock();
    if (gHub != NULL) st = gHub->getKeyCodeState(0, -1, sw);
    gLock.unlock();
    
    return st;
}

static jint
android_server_KeyInputQueue_getKeycodeStateDevice(JNIEnv* env, jobject clazz,
                                            jint deviceId, jint sw)
{
    jint st = -1;
    gLock.lock();
    if (gHub != NULL) st = gHub->getKeyCodeState(deviceId,-1, sw);
    gLock.unlock();
    
    return st;
}

static jint
android_server_KeyInputQueue_scancodeToKeycode(JNIEnv* env, jobject clazz,
                                            jint deviceId, jint scancode)
{
    jint res = 0;
    gLock.lock();
    if (gHub != NULL) {
        int32_t keycode;
        uint32_t flags;
        gHub->scancodeToKeycode(deviceId, scancode, &keycode, &flags);
        res = keycode;
    }
    gLock.unlock();
    
    return res;
}

static jboolean
android_server_KeyInputQueue_hasKeys(JNIEnv* env, jobject clazz,
                                     jintArray keyCodes, jbooleanArray outFlags)
{
    jboolean ret = JNI_FALSE;

    int32_t* codes = env->GetIntArrayElements(keyCodes, NULL);
    uint8_t* flags = env->GetBooleanArrayElements(outFlags, NULL);
    jsize numCodes = env->GetArrayLength(keyCodes);
    if (numCodes == env->GetArrayLength(outFlags)) {
        gLock.lock();
        if (gHub != NULL) ret = gHub->hasKeys(numCodes, codes, flags);
        gLock.unlock();
    }

    env->ReleaseBooleanArrayElements(outFlags, flags, 0);
    env->ReleaseIntArrayElements(keyCodes, codes, 0);
    return ret;
}

// ----------------------------------------------------------------------------

/*
 * JNI registration.
 */
static JNINativeMethod gInputMethods[] = {
    /* name, signature, funcPtr */
    { "readEvent",       "(Landroid/view/RawInputEvent;)Z",
            (void*) android_server_KeyInputQueue_readEvent },
    { "getDeviceClasses", "(I)I",
        (void*) android_server_KeyInputQueue_getDeviceClasses },
    { "getDeviceName", "(I)Ljava/lang/String;",
        (void*) android_server_KeyInputQueue_getDeviceName },
    { "addExcludedDevice", "(Ljava/lang/String;)V",
        (void*) android_server_KeyInputQueue_addExcludedDevice },
    { "getAbsoluteInfo", "(IILcom/android/server/InputDevice$AbsoluteInfo;)Z",
        (void*) android_server_KeyInputQueue_getAbsoluteInfo },
    { "getSwitchState", "(I)I",
        (void*) android_server_KeyInputQueue_getSwitchState },
    { "getSwitchState", "(II)I",
        (void*) android_server_KeyInputQueue_getSwitchStateDevice },
    { "nativeGetScancodeState", "(I)I",
        (void*) android_server_KeyInputQueue_getScancodeState },
    { "nativeGetScancodeState", "(II)I",
        (void*) android_server_KeyInputQueue_getScancodeStateDevice },
    { "nativeGetKeycodeState", "(I)I",
        (void*) android_server_KeyInputQueue_getKeycodeState },
    { "nativeGetKeycodeState", "(II)I",
        (void*) android_server_KeyInputQueue_getKeycodeStateDevice },
    { "hasKeys", "([I[Z)Z",
        (void*) android_server_KeyInputQueue_hasKeys },
    { "scancodeToKeycode", "(II)I",
        (void*) android_server_KeyInputQueue_scancodeToKeycode },
};

int register_android_server_KeyInputQueue(JNIEnv* env)
{
    jclass input = env->FindClass("com/android/server/KeyInputQueue");
    LOG_FATAL_IF(input == NULL, "Unable to find class com/android/server/KeyInputQueue");
    int res = jniRegisterNativeMethods(env, "com/android/server/KeyInputQueue",
                                        gInputMethods, NELEM(gInputMethods));

    jclass absoluteInfo = env->FindClass("com/android/server/InputDevice$AbsoluteInfo");
    LOG_FATAL_IF(absoluteInfo == NULL, "Unable to find class com/android/server/InputDevice$AbsoluteInfo");
    
    gInputOffsets.mMinValue
        = env->GetFieldID(absoluteInfo, "minValue", "I");
    LOG_FATAL_IF(gInputOffsets.mMinValue == NULL, "Unable to find InputDevice.AbsoluteInfo.minValue");
    
    gInputOffsets.mMaxValue
        = env->GetFieldID(absoluteInfo, "maxValue", "I");
    LOG_FATAL_IF(gInputOffsets.mMaxValue == NULL, "Unable to find InputDevice.AbsoluteInfo.maxValue");
    
    gInputOffsets.mFlat
        = env->GetFieldID(absoluteInfo, "flat", "I");
    LOG_FATAL_IF(gInputOffsets.mFlat == NULL, "Unable to find InputDevice.AbsoluteInfo.flat");
    
    gInputOffsets.mFuzz
        = env->GetFieldID(absoluteInfo, "fuzz", "I");
    LOG_FATAL_IF(gInputOffsets.mFuzz == NULL, "Unable to find InputDevice.AbsoluteInfo.fuzz");
    
    jclass inputEvent = env->FindClass("android/view/RawInputEvent");
    LOG_FATAL_IF(inputEvent == NULL, "Unable to find class android/view/RawInputEvent");

    gInputOffsets.mDeviceId
        = env->GetFieldID(inputEvent, "deviceId", "I");
    LOG_FATAL_IF(gInputOffsets.mDeviceId == NULL, "Unable to find RawInputEvent.deviceId");
    
    gInputOffsets.mType
        = env->GetFieldID(inputEvent, "type", "I");
    LOG_FATAL_IF(gInputOffsets.mType == NULL, "Unable to find RawInputEvent.type");
    
    gInputOffsets.mScancode
        = env->GetFieldID(inputEvent, "scancode", "I");
    LOG_FATAL_IF(gInputOffsets.mScancode == NULL, "Unable to find RawInputEvent.scancode");

    gInputOffsets.mKeycode
        = env->GetFieldID(inputEvent, "keycode", "I");
    LOG_FATAL_IF(gInputOffsets.mKeycode == NULL, "Unable to find RawInputEvent.keycode");

    gInputOffsets.mFlags
        = env->GetFieldID(inputEvent, "flags", "I");
    LOG_FATAL_IF(gInputOffsets.mFlags == NULL, "Unable to find RawInputEvent.flags");

    gInputOffsets.mValue
        = env->GetFieldID(inputEvent, "value", "I");
    LOG_FATAL_IF(gInputOffsets.mValue == NULL, "Unable to find RawInputEvent.value");
    
    gInputOffsets.mWhen
        = env->GetFieldID(inputEvent, "when", "J");
    LOG_FATAL_IF(gInputOffsets.mWhen == NULL, "Unable to find RawInputEvent.when");

    return res;
}

}; // namespace android

