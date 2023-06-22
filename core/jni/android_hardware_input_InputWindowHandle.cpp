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

#include "android_hardware_input_InputWindowHandle.h"

#include <android/graphics/matrix.h>
#include <android/graphics/region.h>
#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>
#include <binder/IPCThreadState.h>
#include <ftl/flags.h>
#include <gui/SurfaceControl.h>
#include <gui/WindowInfo.h>
#include <nativehelper/JNIHelp.h>
#include <ui/Region.h>
#include <utils/threads.h>

#include "SkRegion.h"
#include "android_hardware_input_InputApplicationHandle.h"
#include "android_util_Binder.h"
#include "core_jni_helpers.h"
#include "jni.h"

namespace android {

using gui::TouchOcclusionMode;
using gui::WindowInfo;

struct WeakRefHandleField {
    jfieldID ctrl;
    jmethodID get;
    jfieldID mNativeObject;
};

static struct {
    jclass clazz;
    jmethodID ctor;
    jfieldID ptr;
    jfieldID inputApplicationHandle;
    jfieldID token;
    jfieldID name;
    jfieldID layoutParamsFlags;
    jfieldID layoutParamsType;
    jfieldID dispatchingTimeoutMillis;
    jfieldID frameLeft;
    jfieldID frameTop;
    jfieldID frameRight;
    jfieldID frameBottom;
    jfieldID surfaceInset;
    jfieldID scaleFactor;
    jfieldID touchableRegion;
    jfieldID touchOcclusionMode;
    jfieldID ownerPid;
    jfieldID ownerUid;
    jfieldID packageName;
    jfieldID inputConfig;
    jfieldID displayId;
    jfieldID replaceTouchableRegionWithCrop;
    WeakRefHandleField touchableRegionSurfaceControl;
    jfieldID transform;
    jfieldID windowToken;
    jfieldID focusTransferTarget;
} gInputWindowHandleClassInfo;

static struct {
    jclass clazz;
    jmethodID ctor;
} gRegionClassInfo;

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

    mInfo.dispatchingTimeout = std::chrono::milliseconds(
            env->GetLongField(obj, gInputWindowHandleClassInfo.dispatchingTimeoutMillis));
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
        for (graphics::RegionIterator it(env, regionObj); !it.isDone(); it.next()) {
            ARect rect = it.getRect();
            mInfo.addTouchableRegion(Rect(rect.left, rect.top, rect.right, rect.bottom));
        }
        env->DeleteLocalRef(regionObj);
    }

    const auto flags = ftl::Flags<WindowInfo::Flag>(
            env->GetIntField(obj, gInputWindowHandleClassInfo.layoutParamsFlags));
    const auto type = static_cast<WindowInfo::Type>(
            env->GetIntField(obj, gInputWindowHandleClassInfo.layoutParamsType));
    mInfo.layoutParamsFlags = flags;
    mInfo.layoutParamsType = type;

    mInfo.inputConfig = static_cast<gui::WindowInfo::InputConfig>(
            env->GetIntField(obj, gInputWindowHandleClassInfo.inputConfig));

    mInfo.touchOcclusionMode = static_cast<TouchOcclusionMode>(
            env->GetIntField(obj, gInputWindowHandleClassInfo.touchOcclusionMode));
    mInfo.ownerPid = env->GetIntField(obj, gInputWindowHandleClassInfo.ownerPid);
    mInfo.ownerUid = gui::Uid{
            static_cast<uid_t>(env->GetIntField(obj, gInputWindowHandleClassInfo.ownerUid))};
    mInfo.packageName = getStringField(env, obj, gInputWindowHandleClassInfo.packageName, "<null>");
    mInfo.displayId = env->GetIntField(obj,
            gInputWindowHandleClassInfo.displayId);

    jobject inputApplicationHandleObj = env->GetObjectField(obj,
            gInputWindowHandleClassInfo.inputApplicationHandle);
    if (inputApplicationHandleObj) {
        std::shared_ptr<InputApplicationHandle> inputApplicationHandle =
                android_view_InputApplicationHandle_getHandle(env, inputApplicationHandleObj);
        if (inputApplicationHandle != nullptr) {
            inputApplicationHandle->updateInfo();
            mInfo.applicationInfo = *(inputApplicationHandle->getInfo());
        }
        env->DeleteLocalRef(inputApplicationHandleObj);
    }

    mInfo.replaceTouchableRegionWithCrop = env->GetBooleanField(obj,
            gInputWindowHandleClassInfo.replaceTouchableRegionWithCrop);

    jobject weakSurfaceCtrl = env->GetObjectField(obj,
            gInputWindowHandleClassInfo.touchableRegionSurfaceControl.ctrl);
    bool touchableRegionCropHandleSet = false;
    if (weakSurfaceCtrl) {
        // Promote java weak reference.
        jobject strongSurfaceCtrl = env->CallObjectMethod(weakSurfaceCtrl,
                gInputWindowHandleClassInfo.touchableRegionSurfaceControl.get);
        if (strongSurfaceCtrl) {
            jlong mNativeObject = env->GetLongField(strongSurfaceCtrl,
                    gInputWindowHandleClassInfo.touchableRegionSurfaceControl.mNativeObject);
            if (mNativeObject) {
                auto ctrl = reinterpret_cast<SurfaceControl *>(mNativeObject);
                mInfo.touchableRegionCropHandle = ctrl->getHandle();
                touchableRegionCropHandleSet = true;
            }
            env->DeleteLocalRef(strongSurfaceCtrl);
        }
        env->DeleteLocalRef(weakSurfaceCtrl);
    }
    if (!touchableRegionCropHandleSet) {
        mInfo.touchableRegionCropHandle.clear();
    }

    jobject windowTokenObj = env->GetObjectField(obj, gInputWindowHandleClassInfo.windowToken);
    if (windowTokenObj) {
        mInfo.windowToken = ibinderForJavaObject(env, windowTokenObj);
        env->DeleteLocalRef(windowTokenObj);
    } else {
        mInfo.windowToken.clear();
    }

    ScopedLocalRef<jobject>
            focusTransferTargetObj(env,
                                   env->GetObjectField(obj,
                                                       gInputWindowHandleClassInfo
                                                               .focusTransferTarget));
    if (focusTransferTargetObj.get()) {
        mInfo.focusTransferTarget = ibinderForJavaObject(env, focusTransferTargetObj.get());
    } else {
        mInfo.focusTransferTarget.clear();
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

jobject android_view_InputWindowHandle_fromWindowInfo(JNIEnv* env, gui::WindowInfo windowInfo) {
    ScopedLocalRef<jobject>
            applicationHandle(env,
                              android_view_InputApplicationHandle_fromInputApplicationInfo(
                                      env, windowInfo.applicationInfo));

    jobject inputWindowHandle =
            env->NewObject(gInputWindowHandleClassInfo.clazz, gInputWindowHandleClassInfo.ctor,
                           applicationHandle.get(), windowInfo.displayId);
    if (env->ExceptionCheck()) {
        LOGE_EX(env);
        env->ExceptionClear();
    }
    LOG_ALWAYS_FATAL_IF(inputWindowHandle == nullptr,
                        "Failed to create new InputWindowHandle object.");
    env->SetObjectField(inputWindowHandle, gInputWindowHandleClassInfo.token,
                        javaObjectForIBinder(env, windowInfo.token));
    ScopedLocalRef<jstring> name(env, env->NewStringUTF(windowInfo.name.data()));
    env->SetObjectField(inputWindowHandle, gInputWindowHandleClassInfo.name, name.get());
    env->SetIntField(inputWindowHandle, gInputWindowHandleClassInfo.layoutParamsFlags,
                     static_cast<uint32_t>(windowInfo.layoutParamsFlags.get()));
    env->SetIntField(inputWindowHandle, gInputWindowHandleClassInfo.layoutParamsType,
                     static_cast<int32_t>(windowInfo.layoutParamsType));
    env->SetLongField(inputWindowHandle, gInputWindowHandleClassInfo.dispatchingTimeoutMillis,
                      std::chrono::duration_cast<std::chrono::milliseconds>(
                              windowInfo.dispatchingTimeout)
                              .count());
    env->SetIntField(inputWindowHandle, gInputWindowHandleClassInfo.frameLeft,
                     windowInfo.frameLeft);
    env->SetIntField(inputWindowHandle, gInputWindowHandleClassInfo.frameTop, windowInfo.frameTop);
    env->SetIntField(inputWindowHandle, gInputWindowHandleClassInfo.frameRight,
                     windowInfo.frameRight);
    env->SetIntField(inputWindowHandle, gInputWindowHandleClassInfo.frameBottom,
                     windowInfo.frameBottom);
    env->SetIntField(inputWindowHandle, gInputWindowHandleClassInfo.surfaceInset,
                     windowInfo.surfaceInset);
    env->SetFloatField(inputWindowHandle, gInputWindowHandleClassInfo.scaleFactor,
                       windowInfo.globalScaleFactor);

    SkRegion* region = new SkRegion();
    for (const auto& r : windowInfo.touchableRegion) {
        region->op({r.left, r.top, r.right, r.bottom}, SkRegion::kUnion_Op);
    }
    ScopedLocalRef<jobject> regionObj(env,
                                      env->NewObject(gRegionClassInfo.clazz, gRegionClassInfo.ctor,
                                                     reinterpret_cast<jlong>(region)));
    env->SetObjectField(inputWindowHandle, gInputWindowHandleClassInfo.touchableRegion,
                        regionObj.get());

    env->SetIntField(inputWindowHandle, gInputWindowHandleClassInfo.touchOcclusionMode,
                     static_cast<int32_t>(windowInfo.touchOcclusionMode));
    env->SetIntField(inputWindowHandle, gInputWindowHandleClassInfo.ownerPid, windowInfo.ownerPid);
    env->SetIntField(inputWindowHandle, gInputWindowHandleClassInfo.ownerUid,
                     windowInfo.ownerUid.val());
    ScopedLocalRef<jstring> packageName(env, env->NewStringUTF(windowInfo.packageName.data()));
    env->SetObjectField(inputWindowHandle, gInputWindowHandleClassInfo.packageName,
                        packageName.get());

    const auto inputConfig = windowInfo.inputConfig.get();
    static_assert(sizeof(inputConfig) == sizeof(int32_t));
    env->SetIntField(inputWindowHandle, gInputWindowHandleClassInfo.inputConfig,
                     static_cast<int32_t>(inputConfig));

    float transformVals[9];
    for (int i = 0; i < 9; i++) {
        transformVals[i] = windowInfo.transform[i % 3][i / 3];
    }
    ScopedLocalRef<jobject> matrixObj(env, AMatrix_newInstance(env, transformVals));
    env->SetObjectField(inputWindowHandle, gInputWindowHandleClassInfo.transform, matrixObj.get());

    env->SetObjectField(inputWindowHandle, gInputWindowHandleClassInfo.windowToken,
                        javaObjectForIBinder(env, windowInfo.windowToken));

    return inputWindowHandle;
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
    gInputWindowHandleClassInfo.clazz = MakeGlobalRefOrDie(env, clazz);

    GET_METHOD_ID(gInputWindowHandleClassInfo.ctor, clazz, "<init>",
                  "(Landroid/view/InputApplicationHandle;I)V");

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

    GET_FIELD_ID(gInputWindowHandleClassInfo.dispatchingTimeoutMillis, clazz,
                 "dispatchingTimeoutMillis", "J");

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

    GET_FIELD_ID(gInputWindowHandleClassInfo.touchOcclusionMode, clazz, "touchOcclusionMode", "I");

    GET_FIELD_ID(gInputWindowHandleClassInfo.ownerPid, clazz,
            "ownerPid", "I");

    GET_FIELD_ID(gInputWindowHandleClassInfo.ownerUid, clazz,
            "ownerUid", "I");

    GET_FIELD_ID(gInputWindowHandleClassInfo.packageName, clazz, "packageName",
                 "Ljava/lang/String;");

    GET_FIELD_ID(gInputWindowHandleClassInfo.inputConfig, clazz, "inputConfig", "I");

    GET_FIELD_ID(gInputWindowHandleClassInfo.displayId, clazz,
            "displayId", "I");

    GET_FIELD_ID(gInputWindowHandleClassInfo.replaceTouchableRegionWithCrop, clazz,
            "replaceTouchableRegionWithCrop", "Z");

    GET_FIELD_ID(gInputWindowHandleClassInfo.transform, clazz, "transform",
                 "Landroid/graphics/Matrix;");

    GET_FIELD_ID(gInputWindowHandleClassInfo.windowToken, clazz, "windowToken",
                 "Landroid/os/IBinder;");

    GET_FIELD_ID(gInputWindowHandleClassInfo.focusTransferTarget, clazz, "focusTransferTarget",
                 "Landroid/os/IBinder;");

    jclass weakRefClazz;
    FIND_CLASS(weakRefClazz, "java/lang/ref/Reference");

    GET_METHOD_ID(gInputWindowHandleClassInfo.touchableRegionSurfaceControl.get, weakRefClazz,
             "get", "()Ljava/lang/Object;")

    GET_FIELD_ID(gInputWindowHandleClassInfo.touchableRegionSurfaceControl.ctrl, clazz,
            "touchableRegionSurfaceControl", "Ljava/lang/ref/WeakReference;");

    jclass surfaceControlClazz;
    FIND_CLASS(surfaceControlClazz, "android/view/SurfaceControl");
    GET_FIELD_ID(gInputWindowHandleClassInfo.touchableRegionSurfaceControl.mNativeObject,
        surfaceControlClazz, "mNativeObject", "J");

    jclass regionClazz;
    FIND_CLASS(regionClazz, "android/graphics/Region");
    gRegionClassInfo.clazz = MakeGlobalRefOrDie(env, regionClazz);
    GET_METHOD_ID(gRegionClassInfo.ctor, gRegionClassInfo.clazz, "<init>", "(J)V");
    return 0;
}

} /* namespace android */
