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

#define LOG_TAG "InputWindowHandle"

#include "JNIHelp.h"
#include "jni.h"
#include <android_runtime/AndroidRuntime.h>
#include <utils/threads.h>

#include <android_view_InputChannel.h>
#include <android/graphics/Region.h>

#include "com_android_server_input_InputWindowHandle.h"
#include "com_android_server_input_InputApplicationHandle.h"

namespace android {

static struct {
    jfieldID ptr;
    jfieldID inputApplicationHandle;
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
    jfieldID inputFeatures;
    jfieldID displayId;
} gInputWindowHandleClassInfo;

static Mutex gHandleMutex;


// --- NativeInputWindowHandle ---

NativeInputWindowHandle::NativeInputWindowHandle(
        const sp<InputApplicationHandle>& inputApplicationHandle, jweak objWeak) :
        InputWindowHandle(inputApplicationHandle),
        mObjWeak(objWeak) {
}

NativeInputWindowHandle::~NativeInputWindowHandle() {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->DeleteWeakGlobalRef(mObjWeak);
}

jobject NativeInputWindowHandle::getInputWindowHandleObjLocalRef(JNIEnv* env) {
    return env->NewLocalRef(mObjWeak);
}

bool NativeInputWindowHandle::updateInfo() {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jobject obj = env->NewLocalRef(mObjWeak);
    if (!obj) {
        releaseInfo();
        return false;
    }

    if (!mInfo) {
        mInfo = new InputWindowInfo();
    }

    jobject inputChannelObj = env->GetObjectField(obj,
            gInputWindowHandleClassInfo.inputChannel);
    if (inputChannelObj) {
        mInfo->inputChannel = android_view_InputChannel_getInputChannel(env, inputChannelObj);
        env->DeleteLocalRef(inputChannelObj);
    } else {
        mInfo->inputChannel.clear();
    }

    jstring nameObj = jstring(env->GetObjectField(obj,
            gInputWindowHandleClassInfo.name));
    if (nameObj) {
        const char* nameStr = env->GetStringUTFChars(nameObj, NULL);
        mInfo->name.setTo(nameStr);
        env->ReleaseStringUTFChars(nameObj, nameStr);
        env->DeleteLocalRef(nameObj);
    } else {
        mInfo->name.setTo("<null>");
    }

    mInfo->layoutParamsFlags = env->GetIntField(obj,
            gInputWindowHandleClassInfo.layoutParamsFlags);
    mInfo->layoutParamsType = env->GetIntField(obj,
            gInputWindowHandleClassInfo.layoutParamsType);
    mInfo->dispatchingTimeout = env->GetLongField(obj,
            gInputWindowHandleClassInfo.dispatchingTimeoutNanos);
    mInfo->frameLeft = env->GetIntField(obj,
            gInputWindowHandleClassInfo.frameLeft);
    mInfo->frameTop = env->GetIntField(obj,
            gInputWindowHandleClassInfo.frameTop);
    mInfo->frameRight = env->GetIntField(obj,
            gInputWindowHandleClassInfo.frameRight);
    mInfo->frameBottom = env->GetIntField(obj,
            gInputWindowHandleClassInfo.frameBottom);
    mInfo->scaleFactor = env->GetFloatField(obj,
            gInputWindowHandleClassInfo.scaleFactor);

    jobject regionObj = env->GetObjectField(obj,
            gInputWindowHandleClassInfo.touchableRegion);
    if (regionObj) {
        SkRegion* region = android_graphics_Region_getSkRegion(env, regionObj);
        mInfo->touchableRegion.set(*region);
        env->DeleteLocalRef(regionObj);
    } else {
        mInfo->touchableRegion.setEmpty();
    }

    mInfo->visible = env->GetBooleanField(obj,
            gInputWindowHandleClassInfo.visible);
    mInfo->canReceiveKeys = env->GetBooleanField(obj,
            gInputWindowHandleClassInfo.canReceiveKeys);
    mInfo->hasFocus = env->GetBooleanField(obj,
            gInputWindowHandleClassInfo.hasFocus);
    mInfo->hasWallpaper = env->GetBooleanField(obj,
            gInputWindowHandleClassInfo.hasWallpaper);
    mInfo->paused = env->GetBooleanField(obj,
            gInputWindowHandleClassInfo.paused);
    mInfo->layer = env->GetIntField(obj,
            gInputWindowHandleClassInfo.layer);
    mInfo->ownerPid = env->GetIntField(obj,
            gInputWindowHandleClassInfo.ownerPid);
    mInfo->ownerUid = env->GetIntField(obj,
            gInputWindowHandleClassInfo.ownerUid);
    mInfo->inputFeatures = env->GetIntField(obj,
            gInputWindowHandleClassInfo.inputFeatures);
    mInfo->displayId = env->GetIntField(obj,
            gInputWindowHandleClassInfo.displayId);

    env->DeleteLocalRef(obj);
    return true;
}


// --- Global functions ---

sp<NativeInputWindowHandle> android_server_InputWindowHandle_getHandle(
        JNIEnv* env, jobject inputWindowHandleObj) {
    if (!inputWindowHandleObj) {
        return NULL;
    }

    AutoMutex _l(gHandleMutex);

    int ptr = env->GetIntField(inputWindowHandleObj, gInputWindowHandleClassInfo.ptr);
    NativeInputWindowHandle* handle;
    if (ptr) {
        handle = reinterpret_cast<NativeInputWindowHandle*>(ptr);
    } else {
        jobject inputApplicationHandleObj = env->GetObjectField(inputWindowHandleObj,
                gInputWindowHandleClassInfo.inputApplicationHandle);
        sp<InputApplicationHandle> inputApplicationHandle =
                android_server_InputApplicationHandle_getHandle(env, inputApplicationHandleObj);
        env->DeleteLocalRef(inputApplicationHandleObj);

        jweak objWeak = env->NewWeakGlobalRef(inputWindowHandleObj);
        handle = new NativeInputWindowHandle(inputApplicationHandle, objWeak);
        handle->incStrong((void*)android_server_InputWindowHandle_getHandle);
        env->SetIntField(inputWindowHandleObj, gInputWindowHandleClassInfo.ptr,
                reinterpret_cast<int>(handle));
    }
    return handle;
}


// --- JNI ---

static void android_server_InputWindowHandle_nativeDispose(JNIEnv* env, jobject obj) {
    AutoMutex _l(gHandleMutex);

    int ptr = env->GetIntField(obj, gInputWindowHandleClassInfo.ptr);
    if (ptr) {
        env->SetIntField(obj, gInputWindowHandleClassInfo.ptr, 0);

        NativeInputWindowHandle* handle = reinterpret_cast<NativeInputWindowHandle*>(ptr);
        handle->decStrong((void*)android_server_InputWindowHandle_getHandle);
    }
}


static JNINativeMethod gInputWindowHandleMethods[] = {
    /* name, signature, funcPtr */
    { "nativeDispose", "()V",
            (void*) android_server_InputWindowHandle_nativeDispose },
};

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class " className);

#define GET_FIELD_ID(var, clazz, fieldName, fieldDescriptor) \
        var = env->GetFieldID(clazz, fieldName, fieldDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find field " fieldName);

int register_android_server_InputWindowHandle(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "com/android/server/input/InputWindowHandle",
            gInputWindowHandleMethods, NELEM(gInputWindowHandleMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    jclass clazz;
    FIND_CLASS(clazz, "com/android/server/input/InputWindowHandle");

    GET_FIELD_ID(gInputWindowHandleClassInfo.ptr, clazz,
            "ptr", "I");

    GET_FIELD_ID(gInputWindowHandleClassInfo.inputApplicationHandle,
            clazz,
            "inputApplicationHandle", "Lcom/android/server/input/InputApplicationHandle;");

    GET_FIELD_ID(gInputWindowHandleClassInfo.inputChannel, clazz,
            "inputChannel", "Landroid/view/InputChannel;");

    GET_FIELD_ID(gInputWindowHandleClassInfo.name, clazz,
            "name", "Ljava/lang/String;");

    GET_FIELD_ID(gInputWindowHandleClassInfo.layoutParamsFlags, clazz,
            "layoutParamsFlags", "I");

    GET_FIELD_ID(gInputWindowHandleClassInfo.layoutParamsType, clazz,
            "layoutParamsType", "I");

    GET_FIELD_ID(gInputWindowHandleClassInfo.dispatchingTimeoutNanos, clazz,
            "dispatchingTimeoutNanos", "J");

    GET_FIELD_ID(gInputWindowHandleClassInfo.frameLeft, clazz,
            "frameLeft", "I");

    GET_FIELD_ID(gInputWindowHandleClassInfo.frameTop, clazz,
            "frameTop", "I");

    GET_FIELD_ID(gInputWindowHandleClassInfo.frameRight, clazz,
            "frameRight", "I");

    GET_FIELD_ID(gInputWindowHandleClassInfo.frameBottom, clazz,
            "frameBottom", "I");

    GET_FIELD_ID(gInputWindowHandleClassInfo.scaleFactor, clazz,
            "scaleFactor", "F");

    GET_FIELD_ID(gInputWindowHandleClassInfo.touchableRegion, clazz,
            "touchableRegion", "Landroid/graphics/Region;");

    GET_FIELD_ID(gInputWindowHandleClassInfo.visible, clazz,
            "visible", "Z");

    GET_FIELD_ID(gInputWindowHandleClassInfo.canReceiveKeys, clazz,
            "canReceiveKeys", "Z");

    GET_FIELD_ID(gInputWindowHandleClassInfo.hasFocus, clazz,
            "hasFocus", "Z");

    GET_FIELD_ID(gInputWindowHandleClassInfo.hasWallpaper, clazz,
            "hasWallpaper", "Z");

    GET_FIELD_ID(gInputWindowHandleClassInfo.paused, clazz,
            "paused", "Z");

    GET_FIELD_ID(gInputWindowHandleClassInfo.layer, clazz,
            "layer", "I");

    GET_FIELD_ID(gInputWindowHandleClassInfo.ownerPid, clazz,
            "ownerPid", "I");

    GET_FIELD_ID(gInputWindowHandleClassInfo.ownerUid, clazz,
            "ownerUid", "I");

    GET_FIELD_ID(gInputWindowHandleClassInfo.inputFeatures, clazz,
            "inputFeatures", "I");

    GET_FIELD_ID(gInputWindowHandleClassInfo.displayId, clazz,
            "displayId", "I");
    return 0;
}

} /* namespace android */
