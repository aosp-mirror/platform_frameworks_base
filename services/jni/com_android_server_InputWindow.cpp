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

#define LOG_TAG "InputWindow"

#include "JNIHelp.h"
#include "jni.h"
#include <android_runtime/AndroidRuntime.h>

#include <android_view_InputChannel.h>
#include <android/graphics/Region.h>
#include "com_android_server_InputWindow.h"
#include "com_android_server_InputWindowHandle.h"

namespace android {

static struct {
    jclass clazz;

    jfieldID inputWindowHandle;
    jfieldID inputChannel;
    jfieldID name;
    jfieldID layoutParamsFlags;
    jfieldID layoutParamsType;
    jfieldID dispatchingTimeoutNanos;
    jfieldID frameLeft;
    jfieldID frameTop;
    jfieldID frameRight;
    jfieldID frameBottom;
    jfieldID scaleFactor;
    jfieldID touchableRegion;
    jfieldID visible;
    jfieldID canReceiveKeys;
    jfieldID hasFocus;
    jfieldID hasWallpaper;
    jfieldID paused;
    jfieldID layer;
    jfieldID ownerPid;
    jfieldID ownerUid;
} gInputWindowClassInfo;


// --- Global functions ---

void android_server_InputWindow_toNative(
        JNIEnv* env, jobject inputWindowObj, InputWindow* outInputWindow) {
    jobject inputWindowHandleObj = env->GetObjectField(inputWindowObj,
            gInputWindowClassInfo.inputWindowHandle);
    if (inputWindowHandleObj) {
        outInputWindow->inputWindowHandle =
                android_server_InputWindowHandle_getHandle(env, inputWindowHandleObj);
        env->DeleteLocalRef(inputWindowHandleObj);
    } else {
        outInputWindow->inputWindowHandle = NULL;
    }

    jobject inputChannelObj = env->GetObjectField(inputWindowObj,
            gInputWindowClassInfo.inputChannel);
    if (inputChannelObj) {
        outInputWindow->inputChannel =
                android_view_InputChannel_getInputChannel(env, inputChannelObj);
        env->DeleteLocalRef(inputChannelObj);
    } else {
        outInputWindow->inputChannel = NULL;
    }

    jstring nameObj = jstring(env->GetObjectField(inputWindowObj,
            gInputWindowClassInfo.name));
    if (nameObj) {
        const char* nameStr = env->GetStringUTFChars(nameObj, NULL);
        outInputWindow->name.setTo(nameStr);
        env->ReleaseStringUTFChars(nameObj, nameStr);
        env->DeleteLocalRef(nameObj);
    } else {
        LOGE("InputWindow.name should not be null.");
        outInputWindow->name.setTo("unknown");
    }

    outInputWindow->layoutParamsFlags = env->GetIntField(inputWindowObj,
            gInputWindowClassInfo.layoutParamsFlags);
    outInputWindow->layoutParamsType = env->GetIntField(inputWindowObj,
            gInputWindowClassInfo.layoutParamsType);
    outInputWindow->dispatchingTimeout = env->GetLongField(inputWindowObj,
            gInputWindowClassInfo.dispatchingTimeoutNanos);
    outInputWindow->frameLeft = env->GetIntField(inputWindowObj,
            gInputWindowClassInfo.frameLeft);
    outInputWindow->frameTop = env->GetIntField(inputWindowObj,
            gInputWindowClassInfo.frameTop);
    outInputWindow->frameRight = env->GetIntField(inputWindowObj,
            gInputWindowClassInfo.frameRight);
    outInputWindow->frameBottom = env->GetIntField(inputWindowObj,
            gInputWindowClassInfo.frameBottom);
    outInputWindow->scaleFactor = env->GetFloatField(inputWindowObj,
            gInputWindowClassInfo.scaleFactor);

    jobject regionObj = env->GetObjectField(inputWindowObj,
            gInputWindowClassInfo.touchableRegion);
    if (regionObj) {
        SkRegion* region = android_graphics_Region_getSkRegion(env, regionObj);
        outInputWindow->touchableRegion.set(*region);
        env->DeleteLocalRef(regionObj);
    } else {
        outInputWindow->touchableRegion.setEmpty();
    }

    outInputWindow->visible = env->GetBooleanField(inputWindowObj,
            gInputWindowClassInfo.visible);
    outInputWindow->canReceiveKeys = env->GetBooleanField(inputWindowObj,
            gInputWindowClassInfo.canReceiveKeys);
    outInputWindow->hasFocus = env->GetBooleanField(inputWindowObj,
            gInputWindowClassInfo.hasFocus);
    outInputWindow->hasWallpaper = env->GetBooleanField(inputWindowObj,
            gInputWindowClassInfo.hasWallpaper);
    outInputWindow->paused = env->GetBooleanField(inputWindowObj,
            gInputWindowClassInfo.paused);
    outInputWindow->layer = env->GetIntField(inputWindowObj,
            gInputWindowClassInfo.layer);
    outInputWindow->ownerPid = env->GetIntField(inputWindowObj,
            gInputWindowClassInfo.ownerPid);
    outInputWindow->ownerUid = env->GetIntField(inputWindowObj,
            gInputWindowClassInfo.ownerUid);
}


// --- JNI ---

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class " className); \
        var = jclass(env->NewGlobalRef(var));

#define GET_FIELD_ID(var, clazz, fieldName, fieldDescriptor) \
        var = env->GetFieldID(clazz, fieldName, fieldDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find field " fieldName);

int register_android_server_InputWindow(JNIEnv* env) {
    FIND_CLASS(gInputWindowClassInfo.clazz, "com/android/server/wm/InputWindow");

    GET_FIELD_ID(gInputWindowClassInfo.inputWindowHandle, gInputWindowClassInfo.clazz,
            "inputWindowHandle", "Lcom/android/server/wm/InputWindowHandle;");

    GET_FIELD_ID(gInputWindowClassInfo.inputChannel, gInputWindowClassInfo.clazz,
            "inputChannel", "Landroid/view/InputChannel;");

    GET_FIELD_ID(gInputWindowClassInfo.name, gInputWindowClassInfo.clazz,
            "name", "Ljava/lang/String;");

    GET_FIELD_ID(gInputWindowClassInfo.layoutParamsFlags, gInputWindowClassInfo.clazz,
            "layoutParamsFlags", "I");

    GET_FIELD_ID(gInputWindowClassInfo.layoutParamsType, gInputWindowClassInfo.clazz,
            "layoutParamsType", "I");

    GET_FIELD_ID(gInputWindowClassInfo.dispatchingTimeoutNanos, gInputWindowClassInfo.clazz,
            "dispatchingTimeoutNanos", "J");

    GET_FIELD_ID(gInputWindowClassInfo.frameLeft, gInputWindowClassInfo.clazz,
            "frameLeft", "I");

    GET_FIELD_ID(gInputWindowClassInfo.frameTop, gInputWindowClassInfo.clazz,
            "frameTop", "I");

    GET_FIELD_ID(gInputWindowClassInfo.frameRight, gInputWindowClassInfo.clazz,
            "frameRight", "I");

    GET_FIELD_ID(gInputWindowClassInfo.frameBottom, gInputWindowClassInfo.clazz,
            "frameBottom", "I");

    GET_FIELD_ID(gInputWindowClassInfo.scaleFactor, gInputWindowClassInfo.clazz,
            "scaleFactor", "F");

    GET_FIELD_ID(gInputWindowClassInfo.touchableRegion, gInputWindowClassInfo.clazz,
            "touchableRegion", "Landroid/graphics/Region;");

    GET_FIELD_ID(gInputWindowClassInfo.visible, gInputWindowClassInfo.clazz,
            "visible", "Z");

    GET_FIELD_ID(gInputWindowClassInfo.canReceiveKeys, gInputWindowClassInfo.clazz,
            "canReceiveKeys", "Z");

    GET_FIELD_ID(gInputWindowClassInfo.hasFocus, gInputWindowClassInfo.clazz,
            "hasFocus", "Z");

    GET_FIELD_ID(gInputWindowClassInfo.hasWallpaper, gInputWindowClassInfo.clazz,
            "hasWallpaper", "Z");

    GET_FIELD_ID(gInputWindowClassInfo.paused, gInputWindowClassInfo.clazz,
            "paused", "Z");

    GET_FIELD_ID(gInputWindowClassInfo.layer, gInputWindowClassInfo.clazz,
            "layer", "I");

    GET_FIELD_ID(gInputWindowClassInfo.ownerPid, gInputWindowClassInfo.clazz,
            "ownerPid", "I");

    GET_FIELD_ID(gInputWindowClassInfo.ownerUid, gInputWindowClassInfo.clazz,
            "ownerUid", "I");
    return 0;
}

} /* namespace android */
