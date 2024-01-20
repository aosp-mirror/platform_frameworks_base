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

#include "android_view_PointerIcon.h"

#include <android-base/logging.h>
#include <android/graphics/bitmap.h>
#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedLocalRef.h>

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
} gPointerIconClassInfo;


// --- Global Functions ---

PointerIcon android_view_PointerIcon_toNative(JNIEnv* env, jobject pointerIconObj) {
    if (!pointerIconObj) {
        LOG(FATAL) << __func__ << ": pointerIconObj is null";
    }
    PointerIcon icon;
    icon.style = static_cast<PointerIconStyle>(
            env->GetIntField(pointerIconObj, gPointerIconClassInfo.mType));
    icon.hotSpotX = env->GetFloatField(pointerIconObj, gPointerIconClassInfo.mHotSpotX);
    icon.hotSpotY = env->GetFloatField(pointerIconObj, gPointerIconClassInfo.mHotSpotY);

    ScopedLocalRef<jobject> bitmapObj(
            env, env->GetObjectField(pointerIconObj, gPointerIconClassInfo.mBitmap));
    if (bitmapObj.get()) {
        icon.bitmap = graphics::Bitmap(env, bitmapObj.get());
    }

    ScopedLocalRef<jobjectArray> bitmapFramesObj(env, reinterpret_cast<jobjectArray>(
            env->GetObjectField(pointerIconObj, gPointerIconClassInfo.mBitmapFrames)));
    if (bitmapFramesObj.get()) {
        icon.durationPerFrame =
                env->GetIntField(pointerIconObj, gPointerIconClassInfo.mDurationPerFrame);
        jsize size = env->GetArrayLength(bitmapFramesObj.get());
        icon.bitmapFrames.resize(size);
        for (jsize i = 0; i < size; ++i) {
            ScopedLocalRef<jobject> bitmapObj(env, env->GetObjectArrayElement(bitmapFramesObj.get(), i));
            icon.bitmapFrames[i] = graphics::Bitmap(env, bitmapObj.get());
        }
    }

    return icon;
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

    return 0;
}

} // namespace android
