/*
 * Copyright (C) 2011 The Android Open Source Project
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

#define LOG_TAG "PointerIcon-JNI"

#include "JNIHelp.h"

#include "android_view_PointerIcon.h"

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>
#include <utils/Log.h>
#include <android/graphics/GraphicsJNI.h>
#include "ScopedLocalRef.h"

#include "core_jni_helpers.h"

namespace android {

static struct {
    jclass clazz;
    jfieldID mType;
    jfieldID mBitmap;
    jfieldID mHotSpotX;
    jfieldID mHotSpotY;
    jfieldID mBitmapFrames;
    jfieldID mDurationPerFrame;
    jmethodID getSystemIcon;
    jmethodID load;
} gPointerIconClassInfo;


// --- Global Functions ---

jobject android_view_PointerIcon_getSystemIcon(JNIEnv* env, jobject contextObj, int32_t style) {
    jobject pointerIconObj = env->CallStaticObjectMethod(gPointerIconClassInfo.clazz,
            gPointerIconClassInfo.getSystemIcon, contextObj, style);
    if (env->ExceptionCheck()) {
        ALOGW("An exception occurred while getting a pointer icon with style %d.", style);
        LOGW_EX(env);
        env->ExceptionClear();
        return NULL;
    }
    return pointerIconObj;
}

status_t android_view_PointerIcon_load(JNIEnv* env, jobject pointerIconObj, jobject contextObj,
        PointerIcon* outPointerIcon) {
    outPointerIcon->reset();

    if (!pointerIconObj) {
        return OK;
    }

    ScopedLocalRef<jobject> loadedPointerIconObj(env, env->CallObjectMethod(pointerIconObj,
            gPointerIconClassInfo.load, contextObj));
    if (env->ExceptionCheck() || !loadedPointerIconObj.get()) {
        ALOGW("An exception occurred while loading a pointer icon.");
        LOGW_EX(env);
        env->ExceptionClear();
        return UNKNOWN_ERROR;
    }
    return android_view_PointerIcon_getLoadedIcon(env, loadedPointerIconObj.get(), outPointerIcon);
}

status_t android_view_PointerIcon_getLoadedIcon(JNIEnv* env, jobject pointerIconObj,
        PointerIcon* outPointerIcon) {
    outPointerIcon->style = env->GetIntField(pointerIconObj, gPointerIconClassInfo.mType);
    outPointerIcon->hotSpotX = env->GetFloatField(pointerIconObj, gPointerIconClassInfo.mHotSpotX);
    outPointerIcon->hotSpotY = env->GetFloatField(pointerIconObj, gPointerIconClassInfo.mHotSpotY);

    ScopedLocalRef<jobject> bitmapObj(
            env, env->GetObjectField(pointerIconObj, gPointerIconClassInfo.mBitmap));
    if (bitmapObj.get()) {
        GraphicsJNI::getSkBitmap(env, bitmapObj.get(), &(outPointerIcon->bitmap));
    }

    ScopedLocalRef<jobjectArray> bitmapFramesObj(env, reinterpret_cast<jobjectArray>(
            env->GetObjectField(pointerIconObj, gPointerIconClassInfo.mBitmapFrames)));
    if (bitmapFramesObj.get()) {
        outPointerIcon->durationPerFrame = env->GetIntField(
                pointerIconObj, gPointerIconClassInfo.mDurationPerFrame);
        jsize size = env->GetArrayLength(bitmapFramesObj.get());
        outPointerIcon->bitmapFrames.resize(size);
        for (jsize i = 0; i < size; ++i) {
            ScopedLocalRef<jobject> bitmapObj(env, env->GetObjectArrayElement(bitmapFramesObj.get(), i));
            GraphicsJNI::getSkBitmap(env, bitmapObj.get(), &(outPointerIcon->bitmapFrames[i]));
        }
    }

    return OK;
}

status_t android_view_PointerIcon_loadSystemIcon(JNIEnv* env, jobject contextObj,
        int32_t style, PointerIcon* outPointerIcon) {
    jobject pointerIconObj = android_view_PointerIcon_getSystemIcon(env, contextObj, style);
    if (!pointerIconObj) {
        outPointerIcon->reset();
        return UNKNOWN_ERROR;
    }

    status_t status = android_view_PointerIcon_load(env, pointerIconObj,
            contextObj, outPointerIcon);
    env->DeleteLocalRef(pointerIconObj);
    return status;
}


// --- JNI Registration ---

int register_android_view_PointerIcon(JNIEnv* env) {
    jclass clazz = FindClassOrDie(env, "android/view/PointerIcon");
    gPointerIconClassInfo.clazz = MakeGlobalRefOrDie(env, clazz);

    gPointerIconClassInfo.mBitmap = GetFieldIDOrDie(env, gPointerIconClassInfo.clazz,
            "mBitmap", "Landroid/graphics/Bitmap;");

    gPointerIconClassInfo.mType = GetFieldIDOrDie(env, gPointerIconClassInfo.clazz,
            "mType", "I");

    gPointerIconClassInfo.mHotSpotX = GetFieldIDOrDie(env, gPointerIconClassInfo.clazz,
            "mHotSpotX", "F");

    gPointerIconClassInfo.mHotSpotY = GetFieldIDOrDie(env, gPointerIconClassInfo.clazz,
            "mHotSpotY", "F");

    gPointerIconClassInfo.mBitmapFrames = GetFieldIDOrDie(env, gPointerIconClassInfo.clazz,
            "mBitmapFrames", "[Landroid/graphics/Bitmap;");

    gPointerIconClassInfo.mDurationPerFrame = GetFieldIDOrDie(env, gPointerIconClassInfo.clazz,
            "mDurationPerFrame", "I");

    gPointerIconClassInfo.getSystemIcon = GetStaticMethodIDOrDie(env, gPointerIconClassInfo.clazz,
            "getSystemIcon", "(Landroid/content/Context;I)Landroid/view/PointerIcon;");

    gPointerIconClassInfo.load = GetMethodIDOrDie(env, gPointerIconClassInfo.clazz,
            "load", "(Landroid/content/Context;)Landroid/view/PointerIcon;");

    return 0;
}

} // namespace android
