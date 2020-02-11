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

#include <nativehelper/JNIHelp.h>
#include "core_jni_helpers.h"
#include "jni.h"
#include <android_runtime/AndroidRuntime.h>
#include <utils/threads.h>

#include <android/graphics/Region.h>
#include <ui/Region.h>

#include "android_hardware_input_InputWindowHandle.h"
#include "android_hardware_input_InputApplicationHandle.h"
#include "android_util_Binder.h"
#include <binder/IPCThreadState.h>

namespace android {

struct WeakRefHandleField {
    jfieldID handle;
    jmethodID get;
};

static struct {
    jfieldID ptr;
    jfieldID inputApplicationHandle;
    jfieldID token;
    jfieldID name;
    jfieldID layoutParamsFlags;
    jfieldID layoutParamsType;
    jfieldID dispatchingTimeoutNanos;
    jfieldID frameLeft;
    jfieldID frameTop;
    jfieldID frameRight;
    jfieldID frameBottom;
    jfieldID surfaceInset;
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
    jfieldID portalToDisplayId;
    jfieldID replaceTouchableRegionWithCrop;
    WeakRefHandleField touchableRegionCropHandle;
} gInputWindowHandleClassInfo;

static Mutex gHandleMutex;


// --- NativeInputWindowHandle ---

NativeInputWindowHandle::NativeInputWindowHandle(jweak objWeak) :
        mObjWeak(objWeak) {
}

NativeInputWindowHandle::~NativeInputWindowHandle() {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->DeleteWeakGlobalRef(mObjWeak);

    // Clear the weak reference to the layer handle and flush any binder ref count operations so we
    // do not hold on to any binder references.
    // TODO(b/139697085) remove this after it can be flushed automatically
    mInfo.touchableRegionCropHandle.clear();
    IPCThreadState::self()->flushCommands();
}

jobject NativeInputWindowHandle::getInputWindowHandleObjLocalRef(JNIEnv* env) {
    return env->NewLocalRef(mObjWeak);
}

bool NativeInputWindowHandle::updateInfo() {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jobject obj = env->NewLocalRef(mObjWeak);
    if (!obj) {
        releaseChannel();
        return false;
    }

    mInfo.touchableRegion.clear();

    jobject tokenObj = env->GetObjectField(obj, gInputWindowHandleClassInfo.token);
    if (tokenObj) {
        mInfo.token = ibinderForJavaObject(env, tokenObj);
        env->DeleteLocalRef(tokenObj);
    } else {
        mInfo.token.clear();
    }

    mInfo.name = getStringField(env, obj, gInputWindowHandleClassInfo.name, "<null>");

    mInfo.layoutParamsFlags = env->GetIntField(obj,
            gInputWindowHandleClassInfo.layoutParamsFlags);
    mInfo.layoutParamsType = env->GetIntField(obj,
            gInputWindowHandleClassInfo.layoutParamsType);
    mInfo.dispatchingTimeout = env->GetLongField(obj,
            gInputWindowHandleClassInfo.dispatchingTimeoutNanos);
    mInfo.frameLeft = env->GetIntField(obj,
            gInputWindowHandleClassInfo.frameLeft);
    mInfo.frameTop = env->GetIntField(obj,
            gInputWindowHandleClassInfo.frameTop);
    mInfo.frameRight = env->GetIntField(obj,
            gInputWindowHandleClassInfo.frameRight);
    mInfo.frameBottom = env->GetIntField(obj,
            gInputWindowHandleClassInfo.frameBottom);
    mInfo.surfaceInset = env->GetIntField(obj,
            gInputWindowHandleClassInfo.surfaceInset);
    mInfo.globalScaleFactor = env->GetFloatField(obj,
            gInputWindowHandleClassInfo.scaleFactor);

    jobject regionObj = env->GetObjectField(obj,
            gInputWindowHandleClassInfo.touchableRegion);
    if (regionObj) {
        SkRegion* region = android_graphics_Region_getSkRegion(env, regionObj);
        for (SkRegion::Iterator it(*region); !it.done(); it.next()) {
            const SkIRect& rect = it.rect();
            mInfo.addTouchableRegion(Rect(rect.fLeft, rect.fTop, rect.fRight, rect.fBottom));
        }
        env->DeleteLocalRef(regionObj);
    }

    mInfo.visible = env->GetBooleanField(obj,
            gInputWindowHandleClassInfo.visible);
    mInfo.canReceiveKeys = env->GetBooleanField(obj,
            gInputWindowHandleClassInfo.canReceiveKeys);
    mInfo.hasFocus = env->GetBooleanField(obj,
            gInputWindowHandleClassInfo.hasFocus);
    mInfo.hasWallpaper = env->GetBooleanField(obj,
            gInputWindowHandleClassInfo.hasWallpaper);
    mInfo.paused = env->GetBooleanField(obj,
            gInputWindowHandleClassInfo.paused);
    mInfo.layer = env->GetIntField(obj,
            gInputWindowHandleClassInfo.layer);
    mInfo.ownerPid = env->GetIntField(obj,
            gInputWindowHandleClassInfo.ownerPid);
    mInfo.ownerUid = env->GetIntField(obj,
            gInputWindowHandleClassInfo.ownerUid);
    mInfo.inputFeatures = env->GetIntField(obj,
            gInputWindowHandleClassInfo.inputFeatures);
    mInfo.displayId = env->GetIntField(obj,
            gInputWindowHandleClassInfo.displayId);
    mInfo.portalToDisplayId = env->GetIntField(obj,
            gInputWindowHandleClassInfo.portalToDisplayId);

    jobject inputApplicationHandleObj = env->GetObjectField(obj,
            gInputWindowHandleClassInfo.inputApplicationHandle);
    if (inputApplicationHandleObj) {
        sp<InputApplicationHandle> inputApplicationHandle =
            android_view_InputApplicationHandle_getHandle(env, inputApplicationHandleObj);
        if (inputApplicationHandle != nullptr) {
            inputApplicationHandle->updateInfo();
            mInfo.applicationInfo = *(inputApplicationHandle->getInfo());
        }
        env->DeleteLocalRef(inputApplicationHandleObj);
    }

    mInfo.replaceTouchableRegionWithCrop = env->GetBooleanField(obj,
            gInputWindowHandleClassInfo.replaceTouchableRegionWithCrop);

    jobject handleObj = env->GetObjectField(obj,
            gInputWindowHandleClassInfo.touchableRegionCropHandle.handle);
    if (handleObj) {
        // Promote java weak reference.
        jobject strongHandleObj = env->CallObjectMethod(handleObj,
                gInputWindowHandleClassInfo.touchableRegionCropHandle.get);
        if (strongHandleObj) {
            mInfo.touchableRegionCropHandle = ibinderForJavaObject(env, strongHandleObj);
            env->DeleteLocalRef(strongHandleObj);
        } else {
            mInfo.touchableRegionCropHandle.clear();
        }
        env->DeleteLocalRef(handleObj);
    }

    env->DeleteLocalRef(obj);
    return true;
}


// --- Global functions ---

sp<NativeInputWindowHandle> android_view_InputWindowHandle_getHandle(
        JNIEnv* env, jobject inputWindowHandleObj) {
    if (!inputWindowHandleObj) {
        return NULL;
    }

    AutoMutex _l(gHandleMutex);

    jlong ptr = env->GetLongField(inputWindowHandleObj, gInputWindowHandleClassInfo.ptr);
    NativeInputWindowHandle* handle;
    if (ptr) {
        handle = reinterpret_cast<NativeInputWindowHandle*>(ptr);
    } else {
        jweak objWeak = env->NewWeakGlobalRef(inputWindowHandleObj);
        handle = new NativeInputWindowHandle(objWeak);
        handle->incStrong((void*)android_view_InputWindowHandle_getHandle);
        env->SetLongField(inputWindowHandleObj, gInputWindowHandleClassInfo.ptr,
                reinterpret_cast<jlong>(handle));
    }
    return handle;
}


// --- JNI ---

static void android_view_InputWindowHandle_nativeDispose(JNIEnv* env, jobject obj) {
    AutoMutex _l(gHandleMutex);

    jlong ptr = env->GetLongField(obj, gInputWindowHandleClassInfo.ptr);
    if (ptr) {
        env->SetLongField(obj, gInputWindowHandleClassInfo.ptr, 0);

        NativeInputWindowHandle* handle = reinterpret_cast<NativeInputWindowHandle*>(ptr);
        handle->decStrong((void*)android_view_InputWindowHandle_getHandle);
    }
}


static const JNINativeMethod gInputWindowHandleMethods[] = {
    /* name, signature, funcPtr */
    { "nativeDispose", "()V",
            (void*) android_view_InputWindowHandle_nativeDispose },
};

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! (var), "Unable to find class " className);

#define GET_FIELD_ID(var, clazz, fieldName, fieldDescriptor) \
        var = env->GetFieldID(clazz, fieldName, fieldDescriptor); \
        LOG_FATAL_IF(! (var), "Unable to find field " fieldName);

#define GET_METHOD_ID(var, clazz, methodName, methodSignature) \
        var = env->GetMethodID(clazz, methodName, methodSignature); \
        LOG_FATAL_IF(! (var), "Unable to find method " methodName);

int register_android_view_InputWindowHandle(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "android/view/InputWindowHandle",
            gInputWindowHandleMethods, NELEM(gInputWindowHandleMethods));
    (void) res;  // Faked use when LOG_NDEBUG.
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    jclass clazz;
    FIND_CLASS(clazz, "android/view/InputWindowHandle");

    GET_FIELD_ID(gInputWindowHandleClassInfo.ptr, clazz,
            "ptr", "J");

    GET_FIELD_ID(gInputWindowHandleClassInfo.inputApplicationHandle, clazz,
            "inputApplicationHandle", "Landroid/view/InputApplicationHandle;");

    GET_FIELD_ID(gInputWindowHandleClassInfo.token, clazz,
            "token", "Landroid/os/IBinder;");

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

    GET_FIELD_ID(gInputWindowHandleClassInfo.surfaceInset, clazz,
            "surfaceInset", "I");

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

    GET_FIELD_ID(gInputWindowHandleClassInfo.portalToDisplayId, clazz,
            "portalToDisplayId", "I");

    GET_FIELD_ID(gInputWindowHandleClassInfo.replaceTouchableRegionWithCrop, clazz,
            "replaceTouchableRegionWithCrop", "Z");

    jclass weakRefClazz;
    FIND_CLASS(weakRefClazz, "java/lang/ref/Reference");

    GET_METHOD_ID(gInputWindowHandleClassInfo.touchableRegionCropHandle.get, weakRefClazz,
             "get", "()Ljava/lang/Object;")

    GET_FIELD_ID(gInputWindowHandleClassInfo.touchableRegionCropHandle.handle, clazz,
            "touchableRegionCropHandle", "Ljava/lang/ref/WeakReference;");
    return 0;
}

} /* namespace android */
