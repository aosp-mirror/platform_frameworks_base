/*
 * Copyright 2006, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at 
 *
 *     http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License.
*/

#include <android_runtime/AndroidRuntime.h>

#include <input/KeyCharacterMap.h>
#include <input/Input.h>
#include <binder/Parcel.h>

#include <jni.h>
#include <nativehelper/JNIHelp.h>

#include "android_os_Parcel.h"
#include "android_view_KeyEvent.h"

#include "core_jni_helpers.h"

namespace android {

static struct {
    jclass clazz;
    jmethodID ctor;
} gKeyCharacterMapClassInfo;

static struct {
    jclass clazz;
} gKeyEventClassInfo;

static struct {
    jfieldID keyCode;
    jfieldID metaState;
} gFallbackActionClassInfo;


class NativeKeyCharacterMap {
public:
    NativeKeyCharacterMap(int32_t deviceId, const sp<KeyCharacterMap>& map) :
        mDeviceId(deviceId), mMap(map) {
    }

    ~NativeKeyCharacterMap() {
    }

    inline int32_t getDeviceId() const {
        return mDeviceId;
    }

    inline const sp<KeyCharacterMap>& getMap() const {
        return mMap;
    }

private:
    int32_t mDeviceId;
    sp<KeyCharacterMap> mMap;
};


jobject android_view_KeyCharacterMap_create(JNIEnv* env, int32_t deviceId,
        const sp<KeyCharacterMap>& kcm) {
    NativeKeyCharacterMap* map = new NativeKeyCharacterMap(deviceId,
            kcm.get() ? kcm : KeyCharacterMap::empty());
    if (!map) {
        return NULL;
    }

    return env->NewObject(gKeyCharacterMapClassInfo.clazz, gKeyCharacterMapClassInfo.ctor,
            reinterpret_cast<jlong>(map));
}

static jlong nativeReadFromParcel(JNIEnv *env, jobject clazz, jobject parcelObj) {
    Parcel* parcel = parcelForJavaObject(env, parcelObj);
    if (!parcel) {
        return 0;
    }

    int32_t deviceId = parcel->readInt32();
    if (parcel->errorCheck()) {
        return 0;
    }

    sp<KeyCharacterMap> kcm = KeyCharacterMap::readFromParcel(parcel);
    if (!kcm.get()) {
        return 0;
    }

    NativeKeyCharacterMap* map = new NativeKeyCharacterMap(deviceId, kcm);
    return reinterpret_cast<jlong>(map);
}

static void nativeWriteToParcel(JNIEnv* env, jobject clazz, jlong ptr, jobject parcelObj) {
    NativeKeyCharacterMap* map = reinterpret_cast<NativeKeyCharacterMap*>(ptr);
    Parcel* parcel = parcelForJavaObject(env, parcelObj);
    if (parcel) {
        parcel->writeInt32(map->getDeviceId());
        map->getMap()->writeToParcel(parcel);
    }
}

static void nativeDispose(JNIEnv *env, jobject clazz, jlong ptr) {
    NativeKeyCharacterMap* map = reinterpret_cast<NativeKeyCharacterMap*>(ptr);
    delete map;
}

static jchar nativeGetCharacter(JNIEnv *env, jobject clazz, jlong ptr,
        jint keyCode, jint metaState) {
    NativeKeyCharacterMap* map = reinterpret_cast<NativeKeyCharacterMap*>(ptr);
    return map->getMap()->getCharacter(keyCode, metaState);
}

static jboolean nativeGetFallbackAction(JNIEnv *env, jobject clazz, jlong ptr, jint keyCode,
        jint metaState, jobject fallbackActionObj) {
    NativeKeyCharacterMap* map = reinterpret_cast<NativeKeyCharacterMap*>(ptr);
    KeyCharacterMap::FallbackAction fallbackAction;

    bool result = map->getMap()->getFallbackAction(keyCode, metaState, &fallbackAction);
    if (result) {
        env->SetIntField(fallbackActionObj, gFallbackActionClassInfo.keyCode,
                fallbackAction.keyCode);
        env->SetIntField(fallbackActionObj, gFallbackActionClassInfo.metaState,
                fallbackAction.metaState);
    }
    return result;
}

static jchar nativeGetNumber(JNIEnv *env, jobject clazz, jlong ptr, jint keyCode) {
    NativeKeyCharacterMap* map = reinterpret_cast<NativeKeyCharacterMap*>(ptr);
    return map->getMap()->getNumber(keyCode);
}

static jchar nativeGetMatch(JNIEnv *env, jobject clazz, jlong ptr, jint keyCode,
        jcharArray charsArray, jint metaState) {
    NativeKeyCharacterMap* map = reinterpret_cast<NativeKeyCharacterMap*>(ptr);

    jsize numChars = env->GetArrayLength(charsArray);
    jchar* chars = static_cast<jchar*>(env->GetPrimitiveArrayCritical(charsArray, NULL));
    if (!chars) {
        return 0;
    }

    char16_t result = map->getMap()->getMatch(
        keyCode, reinterpret_cast<char16_t*>(chars), size_t(numChars),
        metaState);

    env->ReleasePrimitiveArrayCritical(charsArray, chars, JNI_ABORT);
    return result;
}

static jchar nativeGetDisplayLabel(JNIEnv *env, jobject clazz, jlong ptr, jint keyCode) {
    NativeKeyCharacterMap* map = reinterpret_cast<NativeKeyCharacterMap*>(ptr);
    return map->getMap()->getDisplayLabel(keyCode);
}

static jint nativeGetKeyboardType(JNIEnv *env, jobject clazz, jlong ptr) {
    NativeKeyCharacterMap* map = reinterpret_cast<NativeKeyCharacterMap*>(ptr);
    return map->getMap()->getKeyboardType();
}

static jobjectArray nativeGetEvents(JNIEnv *env, jobject clazz, jlong ptr,
        jcharArray charsArray) {
    NativeKeyCharacterMap* map = reinterpret_cast<NativeKeyCharacterMap*>(ptr);

    jchar* chars = env->GetCharArrayElements(charsArray, NULL);
    if (!chars) {
        return NULL;
    }
    jsize numChars = env->GetArrayLength(charsArray);

    Vector<KeyEvent> events;
    jobjectArray result = NULL;
    if (map->getMap()->getEvents(map->getDeviceId(),
                                 reinterpret_cast<char16_t*>(chars),
                                 size_t(numChars), events)) {
        result = env->NewObjectArray(jsize(events.size()), gKeyEventClassInfo.clazz, NULL);
        if (result) {
            for (size_t i = 0; i < events.size(); i++) {
                jobject keyEventObj = android_view_KeyEvent_fromNative(env, &events.itemAt(i));
                if (!keyEventObj) break; // threw OOM exception
                env->SetObjectArrayElement(result, jsize(i), keyEventObj);
                env->DeleteLocalRef(keyEventObj);
            }
        }
    }

    env->ReleaseCharArrayElements(charsArray, chars, JNI_ABORT);
    return result;
}


/*
 * JNI registration.
 */

static const JNINativeMethod g_methods[] = {
    /* name, signature, funcPtr */
    { "nativeReadFromParcel", "(Landroid/os/Parcel;)J",
            (void*)nativeReadFromParcel },
    { "nativeWriteToParcel", "(JLandroid/os/Parcel;)V",
            (void*)nativeWriteToParcel },
    { "nativeDispose", "(J)V",
            (void*)nativeDispose },
    { "nativeGetCharacter", "(JII)C",
            (void*)nativeGetCharacter },
    { "nativeGetFallbackAction", "(JIILandroid/view/KeyCharacterMap$FallbackAction;)Z",
            (void*)nativeGetFallbackAction },
    { "nativeGetNumber", "(JI)C",
            (void*)nativeGetNumber },
    { "nativeGetMatch", "(JI[CI)C",
            (void*)nativeGetMatch },
    { "nativeGetDisplayLabel", "(JI)C",
            (void*)nativeGetDisplayLabel },
    { "nativeGetKeyboardType", "(J)I",
            (void*)nativeGetKeyboardType },
    { "nativeGetEvents", "(J[C)[Landroid/view/KeyEvent;",
            (void*)nativeGetEvents },
};

int register_android_view_KeyCharacterMap(JNIEnv* env)
{
    gKeyCharacterMapClassInfo.clazz = FindClassOrDie(env, "android/view/KeyCharacterMap");
    gKeyCharacterMapClassInfo.clazz = MakeGlobalRefOrDie(env, gKeyCharacterMapClassInfo.clazz);

    gKeyCharacterMapClassInfo.ctor = GetMethodIDOrDie(env, gKeyCharacterMapClassInfo.clazz,
            "<init>", "(J)V");

    gKeyEventClassInfo.clazz = FindClassOrDie(env, "android/view/KeyEvent");
    gKeyEventClassInfo.clazz = MakeGlobalRefOrDie(env, gKeyEventClassInfo.clazz);

    jclass clazz = FindClassOrDie(env, "android/view/KeyCharacterMap$FallbackAction");

    gFallbackActionClassInfo.keyCode = GetFieldIDOrDie(env, clazz, "keyCode", "I");
    gFallbackActionClassInfo.metaState = GetFieldIDOrDie(env, clazz, "metaState", "I");

    return RegisterMethodsOrDie(env, "android/view/KeyCharacterMap", g_methods, NELEM(g_methods));
}

}; // namespace android
