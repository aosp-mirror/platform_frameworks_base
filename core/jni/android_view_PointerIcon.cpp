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
#include <utils/Log.h>
#include <android/graphics/GraphicsJNI.h>

namespace android {

static struct {
    jclass clazz;
    jfieldID mStyle;
    jfieldID mBitmap;
    jfieldID mHotSpotX;
    jfieldID mHotSpotY;
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

    jobject loadedPointerIconObj = env->CallObjectMethod(pointerIconObj,
            gPointerIconClassInfo.load, contextObj);
    if (env->ExceptionCheck() || !loadedPointerIconObj) {
        ALOGW("An exception occurred while loading a pointer icon.");
        LOGW_EX(env);
        env->ExceptionClear();
        return UNKNOWN_ERROR;
    }

    outPointerIcon->style = env->GetIntField(loadedPointerIconObj,
            gPointerIconClassInfo.mStyle);
    outPointerIcon->hotSpotX = env->GetFloatField(loadedPointerIconObj,
            gPointerIconClassInfo.mHotSpotX);
    outPointerIcon->hotSpotY = env->GetFloatField(loadedPointerIconObj,
            gPointerIconClassInfo.mHotSpotY);

    jobject bitmapObj = env->GetObjectField(loadedPointerIconObj, gPointerIconClassInfo.mBitmap);
    if (bitmapObj) {
        SkBitmap* bitmap = GraphicsJNI::getNativeBitmap(env, bitmapObj);
        if (bitmap) {
            outPointerIcon->bitmap = *bitmap; // use a shared pixel ref
        }
        env->DeleteLocalRef(bitmapObj);
    }

    env->DeleteLocalRef(loadedPointerIconObj);
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

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class " className); \
        var = jclass(env->NewGlobalRef(var));

#define GET_STATIC_METHOD_ID(var, clazz, methodName, methodDescriptor) \
        var = env->GetStaticMethodID(clazz, methodName, methodDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find method " methodName);

#define GET_METHOD_ID(var, clazz, methodName, methodDescriptor) \
        var = env->GetMethodID(clazz, methodName, methodDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find method " methodName);

#define GET_FIELD_ID(var, clazz, fieldName, fieldDescriptor) \
        var = env->GetFieldID(clazz, fieldName, fieldDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find field " fieldName);

int register_android_view_PointerIcon(JNIEnv* env) {
    FIND_CLASS(gPointerIconClassInfo.clazz, "android/view/PointerIcon");

    GET_FIELD_ID(gPointerIconClassInfo.mBitmap, gPointerIconClassInfo.clazz,
            "mBitmap", "Landroid/graphics/Bitmap;");

    GET_FIELD_ID(gPointerIconClassInfo.mStyle, gPointerIconClassInfo.clazz,
            "mStyle", "I");

    GET_FIELD_ID(gPointerIconClassInfo.mHotSpotX, gPointerIconClassInfo.clazz,
            "mHotSpotX", "F");

    GET_FIELD_ID(gPointerIconClassInfo.mHotSpotY, gPointerIconClassInfo.clazz,
            "mHotSpotY", "F");

    GET_STATIC_METHOD_ID(gPointerIconClassInfo.getSystemIcon, gPointerIconClassInfo.clazz,
            "getSystemIcon", "(Landroid/content/Context;I)Landroid/view/PointerIcon;");

    GET_METHOD_ID(gPointerIconClassInfo.load, gPointerIconClassInfo.clazz,
            "load", "(Landroid/content/Context;)Landroid/view/PointerIcon;");

    return 0;
}

} // namespace android
