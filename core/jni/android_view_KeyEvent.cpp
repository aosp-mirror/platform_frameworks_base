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
#include <attestation/HmacKeyManager.h>
#include <input/Input.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/ScopedUtfChars.h>
#include <utils/Log.h>
#include <optional>
#include "android_view_KeyEvent.h"

#include "core_jni_helpers.h"

namespace android {

/**
 * Convert an std::array of bytes into a Java object.
 */
template <size_t N>
static ScopedLocalRef<jbyteArray> toJbyteArray(JNIEnv* env, const std::array<uint8_t, N>& data) {
    ScopedLocalRef<jbyteArray> array(env, env->NewByteArray(N));
    if (array.get() == nullptr) {
        jniThrowException(env, "java/lang/OutOfMemoryError", nullptr);
        return array;
    }
    static_assert(sizeof(char) == sizeof(uint8_t));
    env->SetByteArrayRegion(array.get(), 0, N, reinterpret_cast<const signed char*>(data.data()));
    return array;
}

/**
 * Convert a Java object into an std::array of bytes of size N.
 * If the object is null,  or the length is unexpected, return std::nullopt.
 */
template <size_t N>
static std::optional<std::array<uint8_t, N>> fromJobject(JNIEnv* env, jobject object) {
    if (object == nullptr) {
        return std::nullopt;
    }
    jbyteArray javaArray = reinterpret_cast<jbyteArray>(object);
    ScopedByteArrayRO bytes(env, javaArray);
    if (bytes.size() != N) {
        ALOGE("Could not initialize array from java object, expected length %zu but got %zu", N,
              bytes.size());
        return std::nullopt;
    }
    std::array<uint8_t, N> array;
    std::move(bytes.get(), bytes.get() + N, array.begin());
    return array;
}

// ----------------------------------------------------------------------------

static struct {
    jclass clazz;

    jmethodID obtain;
    jmethodID recycle;

    jfieldID mId;
    jfieldID mDeviceId;
    jfieldID mSource;
    jfieldID mDisplayId;
    jfieldID mHmac;
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

ScopedLocalRef<jobject> android_view_KeyEvent_obtainAsCopy(JNIEnv* env, const KeyEvent& event) {
    ScopedLocalRef<jbyteArray> hmac = toJbyteArray(env, event.getHmac());
    ScopedLocalRef<jobject>
            eventObj(env,
                     env->CallStaticObjectMethod(gKeyEventClassInfo.clazz,
                                                 gKeyEventClassInfo.obtain, event.getId(),
                                                 event.getDownTime(), event.getEventTime(),
                                                 event.getAction(), event.getKeyCode(),
                                                 event.getRepeatCount(), event.getMetaState(),
                                                 event.getDeviceId(), event.getScanCode(),
                                                 event.getFlags(), event.getSource(),
                                                 event.getDisplayId(), hmac.get(), nullptr));
    if (env->ExceptionCheck()) {
        ALOGE("An exception occurred while obtaining a key event.");
        LOGE_EX(env);
        env->ExceptionClear();
        return ScopedLocalRef<jobject>(env);
    }
    return eventObj;
}

KeyEvent android_view_KeyEvent_obtainAsCopy(JNIEnv* env, jobject eventObj) {
    jint id = env->GetIntField(eventObj, gKeyEventClassInfo.mId);
    jint deviceId = env->GetIntField(eventObj, gKeyEventClassInfo.mDeviceId);
    jint source = env->GetIntField(eventObj, gKeyEventClassInfo.mSource);
    jint displayId = env->GetIntField(eventObj, gKeyEventClassInfo.mDisplayId);
    jobject hmacObj = env->GetObjectField(eventObj, gKeyEventClassInfo.mHmac);
    std::optional<std::array<uint8_t, 32>> hmac = fromJobject<32>(env, hmacObj);
    if (!hmac) {
        hmac = INVALID_HMAC;
    }
    jint metaState = env->GetIntField(eventObj, gKeyEventClassInfo.mMetaState);
    jint action = env->GetIntField(eventObj, gKeyEventClassInfo.mAction);
    jint keyCode = env->GetIntField(eventObj, gKeyEventClassInfo.mKeyCode);
    jint scanCode = env->GetIntField(eventObj, gKeyEventClassInfo.mScanCode);
    jint repeatCount = env->GetIntField(eventObj, gKeyEventClassInfo.mRepeatCount);
    jint flags = env->GetIntField(eventObj, gKeyEventClassInfo.mFlags);
    jlong downTime = env->GetLongField(eventObj, gKeyEventClassInfo.mDownTime);
    jlong eventTime = env->GetLongField(eventObj, gKeyEventClassInfo.mEventTime);

    KeyEvent event;
    event.initialize(id, deviceId, source, displayId, *hmac, action, flags, keyCode, scanCode,
                     metaState, repeatCount, downTime, eventTime);
    return event;
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
    return KeyEvent::getKeyCodeFromLabel(keyLabel.c_str()).value_or(AKEYCODE_UNKNOWN);
}

static jint android_view_KeyEvent_nativeNextId() {
    return static_cast<jint>(InputEvent::nextId());
}

// ----------------------------------------------------------------------------

static const JNINativeMethod g_methods[] = {
        {"nativeKeyCodeToString", "(I)Ljava/lang/String;",
         (void*)android_view_KeyEvent_nativeKeyCodeToString},
        {"nativeKeyCodeFromString", "(Ljava/lang/String;)I",
         (void*)android_view_KeyEvent_nativeKeyCodeFromString},
        {"nativeNextId", "()I", (void*)android_view_KeyEvent_nativeNextId},
};

int register_android_view_KeyEvent(JNIEnv* env) {
    jclass clazz = FindClassOrDie(env, "android/view/KeyEvent");
    gKeyEventClassInfo.clazz = MakeGlobalRefOrDie(env, clazz);

    gKeyEventClassInfo.obtain =
            GetStaticMethodIDOrDie(env, gKeyEventClassInfo.clazz, "obtain",
                                   "(IJJIIIIIIIII[BLjava/lang/String;)Landroid/view/KeyEvent;");
    gKeyEventClassInfo.recycle = GetMethodIDOrDie(env, gKeyEventClassInfo.clazz,
            "recycle", "()V");

    gKeyEventClassInfo.mId = GetFieldIDOrDie(env, gKeyEventClassInfo.clazz, "mId", "I");
    gKeyEventClassInfo.mDeviceId = GetFieldIDOrDie(env, gKeyEventClassInfo.clazz, "mDeviceId", "I");
    gKeyEventClassInfo.mSource = GetFieldIDOrDie(env, gKeyEventClassInfo.clazz, "mSource", "I");
    gKeyEventClassInfo.mDisplayId = GetFieldIDOrDie(env, gKeyEventClassInfo.clazz, "mDisplayId",
                                                    "I");
    gKeyEventClassInfo.mHmac = GetFieldIDOrDie(env, gKeyEventClassInfo.clazz, "mHmac", "[B");
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
