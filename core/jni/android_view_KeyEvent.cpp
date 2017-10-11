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

#define LOG_TAG "KeyEvent-JNI"

#include <nativehelper/JNIHelp.h>

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>
#include <utils/Log.h>
#include <input/Input.h>
#include <nativehelper/ScopedUtfChars.h>
#include "android_view_KeyEvent.h"

#include "core_jni_helpers.h"

namespace android {

// ----------------------------------------------------------------------------

static struct {
    jclass clazz;

    jmethodID obtain;
    jmethodID recycle;

    jfieldID mDeviceId;
    jfieldID mSource;
    jfieldID mMetaState;
    jfieldID mAction;
    jfieldID mKeyCode;
    jfieldID mScanCode;
    jfieldID mRepeatCount;
    jfieldID mFlags;
    jfieldID mDownTime;
    jfieldID mEventTime;
    jfieldID mCharacters;
} gKeyEventClassInfo;

// ----------------------------------------------------------------------------

jobject android_view_KeyEvent_fromNative(JNIEnv* env, const KeyEvent* event) {
    jobject eventObj = env->CallStaticObjectMethod(gKeyEventClassInfo.clazz,
            gKeyEventClassInfo.obtain,
            nanoseconds_to_milliseconds(event->getDownTime()),
            nanoseconds_to_milliseconds(event->getEventTime()),
            event->getAction(),
            event->getKeyCode(),
            event->getRepeatCount(),
            event->getMetaState(),
            event->getDeviceId(),
            event->getScanCode(),
            event->getFlags(),
            event->getSource(),
            NULL);
    if (env->ExceptionCheck()) {
        ALOGE("An exception occurred while obtaining a key event.");
        LOGE_EX(env);
        env->ExceptionClear();
        return NULL;
    }
    return eventObj;
}

status_t android_view_KeyEvent_toNative(JNIEnv* env, jobject eventObj,
        KeyEvent* event) {
    jint deviceId = env->GetIntField(eventObj, gKeyEventClassInfo.mDeviceId);
    jint source = env->GetIntField(eventObj, gKeyEventClassInfo.mSource);
    jint metaState = env->GetIntField(eventObj, gKeyEventClassInfo.mMetaState);
    jint action = env->GetIntField(eventObj, gKeyEventClassInfo.mAction);
    jint keyCode = env->GetIntField(eventObj, gKeyEventClassInfo.mKeyCode);
    jint scanCode = env->GetIntField(eventObj, gKeyEventClassInfo.mScanCode);
    jint repeatCount = env->GetIntField(eventObj, gKeyEventClassInfo.mRepeatCount);
    jint flags = env->GetIntField(eventObj, gKeyEventClassInfo.mFlags);
    jlong downTime = env->GetLongField(eventObj, gKeyEventClassInfo.mDownTime);
    jlong eventTime = env->GetLongField(eventObj, gKeyEventClassInfo.mEventTime);

    event->initialize(deviceId, source, action, flags, keyCode, scanCode, metaState, repeatCount,
            milliseconds_to_nanoseconds(downTime),
            milliseconds_to_nanoseconds(eventTime));
    return OK;
}

status_t android_view_KeyEvent_recycle(JNIEnv* env, jobject eventObj) {
    env->CallVoidMethod(eventObj, gKeyEventClassInfo.recycle);
    if (env->ExceptionCheck()) {
        ALOGW("An exception occurred while recycling a key event.");
        LOGW_EX(env);
        env->ExceptionClear();
        return UNKNOWN_ERROR;
    }
    return OK;
}

static jstring android_view_KeyEvent_nativeKeyCodeToString(JNIEnv* env, jobject clazz,
        jint keyCode) {
    return env->NewStringUTF(KeyEvent::getLabel(keyCode));
}

static jint android_view_KeyEvent_nativeKeyCodeFromString(JNIEnv* env, jobject clazz,
        jstring label) {
    ScopedUtfChars keyLabel(env, label);
    return KeyEvent::getKeyCodeFromLabel(keyLabel.c_str());
}


// ----------------------------------------------------------------------------

static const JNINativeMethod g_methods[] = {
    { "nativeKeyCodeToString", "(I)Ljava/lang/String;",
        (void*)android_view_KeyEvent_nativeKeyCodeToString},
    { "nativeKeyCodeFromString", "(Ljava/lang/String;)I",
        (void*)android_view_KeyEvent_nativeKeyCodeFromString},
};

int register_android_view_KeyEvent(JNIEnv* env) {
    jclass clazz = FindClassOrDie(env, "android/view/KeyEvent");
    gKeyEventClassInfo.clazz = MakeGlobalRefOrDie(env, clazz);

    gKeyEventClassInfo.obtain = GetStaticMethodIDOrDie(env, gKeyEventClassInfo.clazz,
            "obtain", "(JJIIIIIIIILjava/lang/String;)Landroid/view/KeyEvent;");
    gKeyEventClassInfo.recycle = GetMethodIDOrDie(env, gKeyEventClassInfo.clazz,
            "recycle", "()V");

    gKeyEventClassInfo.mDeviceId = GetFieldIDOrDie(env, gKeyEventClassInfo.clazz, "mDeviceId", "I");
    gKeyEventClassInfo.mSource = GetFieldIDOrDie(env, gKeyEventClassInfo.clazz, "mSource", "I");
    gKeyEventClassInfo.mMetaState = GetFieldIDOrDie(env, gKeyEventClassInfo.clazz, "mMetaState",
                                                    "I");
    gKeyEventClassInfo.mAction = GetFieldIDOrDie(env, gKeyEventClassInfo.clazz, "mAction", "I");
    gKeyEventClassInfo.mKeyCode = GetFieldIDOrDie(env, gKeyEventClassInfo.clazz, "mKeyCode", "I");
    gKeyEventClassInfo.mScanCode = GetFieldIDOrDie(env, gKeyEventClassInfo.clazz, "mScanCode", "I");
    gKeyEventClassInfo.mRepeatCount = GetFieldIDOrDie(env, gKeyEventClassInfo.clazz, "mRepeatCount",
                                                      "I");
    gKeyEventClassInfo.mFlags = GetFieldIDOrDie(env, gKeyEventClassInfo.clazz, "mFlags", "I");
    gKeyEventClassInfo.mDownTime = GetFieldIDOrDie(env, gKeyEventClassInfo.clazz, "mDownTime", "J");
    gKeyEventClassInfo.mEventTime = GetFieldIDOrDie(env, gKeyEventClassInfo.clazz, "mEventTime",
                                                    "J");
    gKeyEventClassInfo.mCharacters = GetFieldIDOrDie(env, gKeyEventClassInfo.clazz, "mCharacters",
                                                     "Ljava/lang/String;");

    return RegisterMethodsOrDie(env, "android/view/KeyEvent", g_methods, NELEM(g_methods));
}

} // namespace android
