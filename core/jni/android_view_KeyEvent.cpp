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

#include "JNIHelp.h"

#include <android_runtime/AndroidRuntime.h>
#include <utils/Log.h>
#include <ui/Input.h>
#include "android_view_KeyEvent.h"

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

static jboolean native_isSystemKey(JNIEnv* env, jobject clazz, jint keyCode) {
    return KeyEvent::isSystemKey(keyCode);
}

static jboolean native_hasDefaultAction(JNIEnv* env, jobject clazz, jint keyCode) {
    return KeyEvent::hasDefaultAction(keyCode);
}


// ----------------------------------------------------------------------------

static const JNINativeMethod g_methods[] = {
    { "native_isSystemKey", "(I)Z", (void*)native_isSystemKey },
    { "native_hasDefaultAction", "(I)Z", (void*)native_hasDefaultAction },
};

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class " className); \
        var = jclass(env->NewGlobalRef(var));

#define GET_STATIC_METHOD_ID(var, clazz, methodName, fieldDescriptor) \
        var = env->GetStaticMethodID(clazz, methodName, fieldDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find static method" methodName);

#define GET_METHOD_ID(var, clazz, methodName, fieldDescriptor) \
        var = env->GetMethodID(clazz, methodName, fieldDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find method" methodName);

#define GET_FIELD_ID(var, clazz, fieldName, fieldDescriptor) \
        var = env->GetFieldID(clazz, fieldName, fieldDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find field " fieldName);

int register_android_view_KeyEvent(JNIEnv* env) {
    FIND_CLASS(gKeyEventClassInfo.clazz, "android/view/KeyEvent");

    GET_STATIC_METHOD_ID(gKeyEventClassInfo.obtain, gKeyEventClassInfo.clazz,
            "obtain", "(JJIIIIIIIILjava/lang/String;)Landroid/view/KeyEvent;");
    GET_METHOD_ID(gKeyEventClassInfo.recycle, gKeyEventClassInfo.clazz,
            "recycle", "()V");

    GET_FIELD_ID(gKeyEventClassInfo.mDeviceId, gKeyEventClassInfo.clazz,
            "mDeviceId", "I");
    GET_FIELD_ID(gKeyEventClassInfo.mSource, gKeyEventClassInfo.clazz,
            "mSource", "I");
    GET_FIELD_ID(gKeyEventClassInfo.mMetaState, gKeyEventClassInfo.clazz,
            "mMetaState", "I");
    GET_FIELD_ID(gKeyEventClassInfo.mAction, gKeyEventClassInfo.clazz,
            "mAction", "I");
    GET_FIELD_ID(gKeyEventClassInfo.mKeyCode, gKeyEventClassInfo.clazz,
            "mKeyCode", "I");
    GET_FIELD_ID(gKeyEventClassInfo.mScanCode, gKeyEventClassInfo.clazz,
            "mScanCode", "I");
    GET_FIELD_ID(gKeyEventClassInfo.mRepeatCount, gKeyEventClassInfo.clazz,
            "mRepeatCount", "I");
    GET_FIELD_ID(gKeyEventClassInfo.mFlags, gKeyEventClassInfo.clazz,
            "mFlags", "I");
    GET_FIELD_ID(gKeyEventClassInfo.mDownTime, gKeyEventClassInfo.clazz,
            "mDownTime", "J");
    GET_FIELD_ID(gKeyEventClassInfo.mEventTime, gKeyEventClassInfo.clazz,
            "mEventTime", "J");
    GET_FIELD_ID(gKeyEventClassInfo.mCharacters, gKeyEventClassInfo.clazz,
            "mCharacters", "Ljava/lang/String;");

    return AndroidRuntime::registerNativeMethods(
        env, "android/view/KeyEvent", g_methods, NELEM(g_methods));
}

} // namespace android
