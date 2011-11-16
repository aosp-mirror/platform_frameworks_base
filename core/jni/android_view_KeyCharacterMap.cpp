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

#include <ui/KeyCharacterMap.h>
#include <ui/Input.h>

#include <android_runtime/AndroidRuntime.h>
#include <nativehelper/jni.h>
#include <nativehelper/JNIHelp.h>

#include "android_view_KeyEvent.h"

namespace android {

static struct {
    jclass clazz;
} gKeyEventClassInfo;

static struct {
    jfieldID keyCode;
    jfieldID metaState;
} gFallbackActionClassInfo;


static jint nativeLoad(JNIEnv *env, jobject clazz, jstring fileStr) {
    const char* file = env->GetStringUTFChars(fileStr, NULL);

    KeyCharacterMap* map;
    status_t status = KeyCharacterMap::load(String8(file), &map);
    jint result;
    if (status) {
        String8 msg;
        msg.appendFormat("Could not load key character map '%s' due to error %d.  "
                "Refer to the log for details.", file, status);
        jniThrowException(env, "android/view/KeyCharacterMap$KeyCharacterMapUnavailableException",
                msg.string());
        result = 0;
    } else {
        result = reinterpret_cast<jint>(map);
    }

    env->ReleaseStringUTFChars(fileStr, file);
    return result;
}

static void nativeDispose(JNIEnv *env, jobject clazz, jint ptr) {
    KeyCharacterMap* map = reinterpret_cast<KeyCharacterMap*>(ptr);
    delete map;
}

static jchar nativeGetCharacter(JNIEnv *env, jobject clazz, jint ptr,
        jint keyCode, jint metaState) {
    KeyCharacterMap* map = reinterpret_cast<KeyCharacterMap*>(ptr);
    return map->getCharacter(keyCode, metaState);
}

static jboolean nativeGetFallbackAction(JNIEnv *env, jobject clazz, jint ptr, jint keyCode,
        jint metaState, jobject fallbackActionObj) {
    KeyCharacterMap* map = reinterpret_cast<KeyCharacterMap*>(ptr);
    KeyCharacterMap::FallbackAction fallbackAction;

    bool result = map->getFallbackAction(keyCode, metaState, &fallbackAction);
    if (result) {
        env->SetIntField(fallbackActionObj, gFallbackActionClassInfo.keyCode,
                fallbackAction.keyCode);
        env->SetIntField(fallbackActionObj, gFallbackActionClassInfo.metaState,
                fallbackAction.metaState);
    }
    return result;
}

static jchar nativeGetNumber(JNIEnv *env, jobject clazz, jint ptr, jint keyCode) {
    KeyCharacterMap* map = reinterpret_cast<KeyCharacterMap*>(ptr);
    return map->getNumber(keyCode);
}

static jchar nativeGetMatch(JNIEnv *env, jobject clazz, jint ptr, jint keyCode,
        jcharArray charsArray, jint metaState) {
    KeyCharacterMap* map = reinterpret_cast<KeyCharacterMap*>(ptr);

    jsize numChars = env->GetArrayLength(charsArray);
    jchar* chars = static_cast<jchar*>(env->GetPrimitiveArrayCritical(charsArray, NULL));
    if (!chars) {
        return 0;
    }

    char16_t result = map->getMatch(keyCode, chars, size_t(numChars), metaState);

    env->ReleasePrimitiveArrayCritical(charsArray, chars, JNI_ABORT);
    return result;
}

static jchar nativeGetDisplayLabel(JNIEnv *env, jobject clazz, jint ptr, jint keyCode) {
    KeyCharacterMap* map = reinterpret_cast<KeyCharacterMap*>(ptr);
    return map->getDisplayLabel(keyCode);
}

static jint nativeGetKeyboardType(JNIEnv *env, jobject clazz, jint ptr) {
    KeyCharacterMap* map = reinterpret_cast<KeyCharacterMap*>(ptr);
    return map->getKeyboardType();
}

static jobjectArray nativeGetEvents(JNIEnv *env, jobject clazz, jint ptr, jint deviceId,
        jcharArray charsArray) {
    KeyCharacterMap* map = reinterpret_cast<KeyCharacterMap*>(ptr);

    jchar* chars = env->GetCharArrayElements(charsArray, NULL);
    if (!chars) {
        return NULL;
    }
    jsize numChars = env->GetArrayLength(charsArray);

    Vector<KeyEvent> events;
    jobjectArray result = NULL;
    if (map->getEvents(deviceId, chars, size_t(numChars), events)) {
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

static JNINativeMethod g_methods[] = {
    /* name, signature, funcPtr */
    { "nativeLoad", "(Ljava/lang/String;)I",
            (void*)nativeLoad },
    { "nativeDispose", "(I)V",
            (void*)nativeDispose },
    { "nativeGetCharacter", "(III)C",
            (void*)nativeGetCharacter },
    { "nativeGetFallbackAction", "(IIILandroid/view/KeyCharacterMap$FallbackAction;)Z",
            (void*)nativeGetFallbackAction },
    { "nativeGetNumber", "(II)C",
            (void*)nativeGetNumber },
    { "nativeGetMatch", "(II[CI)C",
            (void*)nativeGetMatch },
    { "nativeGetDisplayLabel", "(II)C",
            (void*)nativeGetDisplayLabel },
    { "nativeGetKeyboardType", "(I)I",
            (void*)nativeGetKeyboardType },
    { "nativeGetEvents", "(II[C)[Landroid/view/KeyEvent;",
            (void*)nativeGetEvents },
};

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class " className);

#define GET_FIELD_ID(var, clazz, fieldName, fieldDescriptor) \
        var = env->GetFieldID(clazz, fieldName, fieldDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find field " fieldName);

int register_android_text_KeyCharacterMap(JNIEnv* env)
{
    FIND_CLASS(gKeyEventClassInfo.clazz, "android/view/KeyEvent");
    gKeyEventClassInfo.clazz = jclass(env->NewGlobalRef(gKeyEventClassInfo.clazz));

    jclass clazz;
    FIND_CLASS(clazz, "android/view/KeyCharacterMap$FallbackAction");

    GET_FIELD_ID(gFallbackActionClassInfo.keyCode, clazz,
            "keyCode", "I");

    GET_FIELD_ID(gFallbackActionClassInfo.metaState, clazz,
            "metaState", "I");

    return AndroidRuntime::registerNativeMethods(env,
            "android/view/KeyCharacterMap", g_methods, NELEM(g_methods));
}

}; // namespace android
