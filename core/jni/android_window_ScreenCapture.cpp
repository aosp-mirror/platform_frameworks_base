/*
 * Copyright (C) 2022 The Android Open Source Project
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

#define LOG_TAG "ScreenCapture"
// #define LOG_NDEBUG 0

#include <android/gui/BnScreenCaptureListener.h>
#include <android_runtime/android_hardware_HardwareBuffer.h>
#include <gui/SurfaceComposerClient.h>
#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <ui/GraphicTypes.h>

#include "android_os_Parcel.h"
#include "android_util_Binder.h"
#include "core_jni_helpers.h"
#include "jni_common.h"

// ----------------------------------------------------------------------------

namespace android {

using gui::CaptureArgs;

static struct {
    jfieldID pixelFormat;
    jfieldID sourceCrop;
    jfieldID frameScaleX;
    jfieldID frameScaleY;
    jfieldID captureSecureLayers;
    jfieldID allowProtected;
    jfieldID uid;
    jfieldID grayscale;
} gCaptureArgsClassInfo;

static struct {
    jfieldID displayToken;
    jfieldID width;
    jfieldID height;
    jfieldID useIdentityTransform;
} gDisplayCaptureArgsClassInfo;

static struct {
    jfieldID layer;
    jfieldID excludeLayers;
    jfieldID childrenOnly;
} gLayerCaptureArgsClassInfo;

static struct {
    jclass clazz;
    jmethodID onScreenCaptureComplete;
} gScreenCaptureListenerClassInfo;

static struct {
    jclass clazz;
    jmethodID builder;
} gScreenshotHardwareBufferClassInfo;

enum JNamedColorSpace : jint {
    // ColorSpace.Named.SRGB.ordinal() = 0;
    SRGB = 0,

    // ColorSpace.Named.DISPLAY_P3.ordinal() = 7;
    DISPLAY_P3 = 7,
};

constexpr jint fromDataspaceToNamedColorSpaceValue(const ui::Dataspace dataspace) {
    switch (dataspace) {
        case ui::Dataspace::DISPLAY_P3:
            return JNamedColorSpace::DISPLAY_P3;
        default:
            return JNamedColorSpace::SRGB;
    }
}

static void checkAndClearException(JNIEnv* env, const char* methodName) {
    if (env->ExceptionCheck()) {
        ALOGE("An exception was thrown by callback '%s'.", methodName);
        env->ExceptionClear();
    }
}

class ScreenCaptureListenerWrapper : public gui::BnScreenCaptureListener {
public:
    explicit ScreenCaptureListenerWrapper(JNIEnv* env, jobject jobject) {
        env->GetJavaVM(&mVm);
        mScreenCaptureListenerObject = env->NewGlobalRef(jobject);
        LOG_ALWAYS_FATAL_IF(!mScreenCaptureListenerObject, "Failed to make global ref");
    }

    ~ScreenCaptureListenerWrapper() {
        if (mScreenCaptureListenerObject) {
            getenv()->DeleteGlobalRef(mScreenCaptureListenerObject);
            mScreenCaptureListenerObject = nullptr;
        }
    }

    binder::Status onScreenCaptureCompleted(
            const gui::ScreenCaptureResults& captureResults) override {
        JNIEnv* env = getenv();
        if (!captureResults.fenceResult.ok() || captureResults.buffer == nullptr) {
            env->CallVoidMethod(mScreenCaptureListenerObject,
                                gScreenCaptureListenerClassInfo.onScreenCaptureComplete, nullptr);
            checkAndClearException(env, "onScreenCaptureComplete");
            return binder::Status::ok();
        }
        captureResults.fenceResult.value()->waitForever(LOG_TAG);
        jobject jhardwareBuffer = android_hardware_HardwareBuffer_createFromAHardwareBuffer(
                env, captureResults.buffer->toAHardwareBuffer());
        const jint namedColorSpace =
                fromDataspaceToNamedColorSpaceValue(captureResults.capturedDataspace);
        jobject screenshotHardwareBuffer =
                env->CallStaticObjectMethod(gScreenshotHardwareBufferClassInfo.clazz,
                                            gScreenshotHardwareBufferClassInfo.builder,
                                            jhardwareBuffer, namedColorSpace,
                                            captureResults.capturedSecureLayers,
                                            captureResults.capturedHdrLayers);
        checkAndClearException(env, "builder");
        env->CallVoidMethod(mScreenCaptureListenerObject,
                            gScreenCaptureListenerClassInfo.onScreenCaptureComplete,
                            screenshotHardwareBuffer);
        checkAndClearException(env, "onScreenCaptureComplete");
        env->DeleteLocalRef(jhardwareBuffer);
        env->DeleteLocalRef(screenshotHardwareBuffer);
        return binder::Status::ok();
    }

private:
    jobject mScreenCaptureListenerObject;
    JavaVM* mVm;

    JNIEnv* getenv() {
        JNIEnv* env;
        if (mVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            if (mVm->AttachCurrentThreadAsDaemon(&env, nullptr) != JNI_OK) {
                LOG_ALWAYS_FATAL("Failed to AttachCurrentThread!");
            }
        }
        return env;
    }
};

static void getCaptureArgs(JNIEnv* env, jobject captureArgsObject, CaptureArgs& captureArgs) {
    captureArgs.pixelFormat = static_cast<ui::PixelFormat>(
            env->GetIntField(captureArgsObject, gCaptureArgsClassInfo.pixelFormat));
    captureArgs.sourceCrop =
            JNICommon::rectFromObj(env,
                                   env->GetObjectField(captureArgsObject,
                                                       gCaptureArgsClassInfo.sourceCrop));
    captureArgs.frameScaleX =
            env->GetFloatField(captureArgsObject, gCaptureArgsClassInfo.frameScaleX);
    captureArgs.frameScaleY =
            env->GetFloatField(captureArgsObject, gCaptureArgsClassInfo.frameScaleY);
    captureArgs.captureSecureLayers =
            env->GetBooleanField(captureArgsObject, gCaptureArgsClassInfo.captureSecureLayers);
    captureArgs.allowProtected =
            env->GetBooleanField(captureArgsObject, gCaptureArgsClassInfo.allowProtected);
    captureArgs.uid = env->GetLongField(captureArgsObject, gCaptureArgsClassInfo.uid);
    captureArgs.grayscale =
            env->GetBooleanField(captureArgsObject, gCaptureArgsClassInfo.grayscale);
}

static DisplayCaptureArgs displayCaptureArgsFromObject(JNIEnv* env,
                                                       jobject displayCaptureArgsObject) {
    DisplayCaptureArgs captureArgs;
    getCaptureArgs(env, displayCaptureArgsObject, captureArgs);

    captureArgs.displayToken =
            ibinderForJavaObject(env,
                                 env->GetObjectField(displayCaptureArgsObject,
                                                     gDisplayCaptureArgsClassInfo.displayToken));
    captureArgs.width =
            env->GetIntField(displayCaptureArgsObject, gDisplayCaptureArgsClassInfo.width);
    captureArgs.height =
            env->GetIntField(displayCaptureArgsObject, gDisplayCaptureArgsClassInfo.height);
    captureArgs.useIdentityTransform =
            env->GetBooleanField(displayCaptureArgsObject,
                                 gDisplayCaptureArgsClassInfo.useIdentityTransform);
    return captureArgs;
}

static jint nativeCaptureDisplay(JNIEnv* env, jclass clazz, jobject displayCaptureArgsObject,
                                 jobject screenCaptureListenerObject) {
    const DisplayCaptureArgs captureArgs =
            displayCaptureArgsFromObject(env, displayCaptureArgsObject);

    if (captureArgs.displayToken == nullptr) {
        return BAD_VALUE;
    }

    sp<IScreenCaptureListener> captureListener =
            sp<ScreenCaptureListenerWrapper>::make(env, screenCaptureListenerObject);
    return ScreenshotClient::captureDisplay(captureArgs, captureListener);
}

static jint nativeCaptureLayers(JNIEnv* env, jclass clazz, jobject layerCaptureArgsObject,
                                jobject screenCaptureListenerObject) {
    LayerCaptureArgs captureArgs;
    getCaptureArgs(env, layerCaptureArgsObject, captureArgs);
    SurfaceControl* layer = reinterpret_cast<SurfaceControl*>(
            env->GetLongField(layerCaptureArgsObject, gLayerCaptureArgsClassInfo.layer));
    if (layer == nullptr) {
        return BAD_VALUE;
    }

    captureArgs.layerHandle = layer->getHandle();
    captureArgs.childrenOnly =
            env->GetBooleanField(layerCaptureArgsObject, gLayerCaptureArgsClassInfo.childrenOnly);

    jlongArray excludeObjectArray = reinterpret_cast<jlongArray>(
            env->GetObjectField(layerCaptureArgsObject, gLayerCaptureArgsClassInfo.excludeLayers));
    if (excludeObjectArray != nullptr) {
        ScopedLongArrayRO excludeArray(env, excludeObjectArray);
        const jsize len = excludeArray.size();
        captureArgs.excludeHandles.reserve(len);

        for (jsize i = 0; i < len; i++) {
            auto excludeObject = reinterpret_cast<SurfaceControl*>(excludeArray[i]);
            if (excludeObject == nullptr) {
                jniThrowNullPointerException(env, "Exclude layer is null");
                return BAD_VALUE;
            }
            captureArgs.excludeHandles.emplace(excludeObject->getHandle());
        }
    }

    sp<IScreenCaptureListener> captureListener =
            sp<ScreenCaptureListenerWrapper>::make(env, screenCaptureListenerObject);
    return ScreenshotClient::captureLayers(captureArgs, captureListener);
}

// ----------------------------------------------------------------------------

static const JNINativeMethod sScreenCaptureMethods[] = {
        // clang-format off
   {"nativeCaptureDisplay",
            "(Landroid/window/ScreenCapture$DisplayCaptureArgs;Landroid/window/ScreenCapture$ScreenCaptureListener;)I",
            (void*)nativeCaptureDisplay },
    {"nativeCaptureLayers",
            "(Landroid/window/ScreenCapture$LayerCaptureArgs;Landroid/window/ScreenCapture$ScreenCaptureListener;)I",
            (void*)nativeCaptureLayers },
        // clang-format on
};

int register_android_window_ScreenCapture(JNIEnv* env) {
    int err = RegisterMethodsOrDie(env, "android/window/ScreenCapture", sScreenCaptureMethods,
                                   NELEM(sScreenCaptureMethods));

    jclass captureArgsClazz = FindClassOrDie(env, "android/window/ScreenCapture$CaptureArgs");
    gCaptureArgsClassInfo.pixelFormat = GetFieldIDOrDie(env, captureArgsClazz, "mPixelFormat", "I");
    gCaptureArgsClassInfo.sourceCrop =
            GetFieldIDOrDie(env, captureArgsClazz, "mSourceCrop", "Landroid/graphics/Rect;");
    gCaptureArgsClassInfo.frameScaleX = GetFieldIDOrDie(env, captureArgsClazz, "mFrameScaleX", "F");
    gCaptureArgsClassInfo.frameScaleY = GetFieldIDOrDie(env, captureArgsClazz, "mFrameScaleY", "F");
    gCaptureArgsClassInfo.captureSecureLayers =
            GetFieldIDOrDie(env, captureArgsClazz, "mCaptureSecureLayers", "Z");
    gCaptureArgsClassInfo.allowProtected =
            GetFieldIDOrDie(env, captureArgsClazz, "mAllowProtected", "Z");
    gCaptureArgsClassInfo.uid = GetFieldIDOrDie(env, captureArgsClazz, "mUid", "J");
    gCaptureArgsClassInfo.grayscale = GetFieldIDOrDie(env, captureArgsClazz, "mGrayscale", "Z");

    jclass displayCaptureArgsClazz =
            FindClassOrDie(env, "android/window/ScreenCapture$DisplayCaptureArgs");
    gDisplayCaptureArgsClassInfo.displayToken =
            GetFieldIDOrDie(env, displayCaptureArgsClazz, "mDisplayToken", "Landroid/os/IBinder;");
    gDisplayCaptureArgsClassInfo.width =
            GetFieldIDOrDie(env, displayCaptureArgsClazz, "mWidth", "I");
    gDisplayCaptureArgsClassInfo.height =
            GetFieldIDOrDie(env, displayCaptureArgsClazz, "mHeight", "I");
    gDisplayCaptureArgsClassInfo.useIdentityTransform =
            GetFieldIDOrDie(env, displayCaptureArgsClazz, "mUseIdentityTransform", "Z");

    jclass layerCaptureArgsClazz =
            FindClassOrDie(env, "android/window/ScreenCapture$LayerCaptureArgs");
    gLayerCaptureArgsClassInfo.layer =
            GetFieldIDOrDie(env, layerCaptureArgsClazz, "mNativeLayer", "J");
    gLayerCaptureArgsClassInfo.excludeLayers =
            GetFieldIDOrDie(env, layerCaptureArgsClazz, "mNativeExcludeLayers", "[J");
    gLayerCaptureArgsClassInfo.childrenOnly =
            GetFieldIDOrDie(env, layerCaptureArgsClazz, "mChildrenOnly", "Z");

    jclass screenCaptureListenerClazz =
            FindClassOrDie(env, "android/window/ScreenCapture$ScreenCaptureListener");
    gScreenCaptureListenerClassInfo.clazz = MakeGlobalRefOrDie(env, screenCaptureListenerClazz);
    gScreenCaptureListenerClassInfo.onScreenCaptureComplete =
            GetMethodIDOrDie(env, screenCaptureListenerClazz, "onScreenCaptureComplete",
                             "(Landroid/window/ScreenCapture$ScreenshotHardwareBuffer;)V");

    jclass screenshotGraphicsBufferClazz =
            FindClassOrDie(env, "android/window/ScreenCapture$ScreenshotHardwareBuffer");
    gScreenshotHardwareBufferClassInfo.clazz =
            MakeGlobalRefOrDie(env, screenshotGraphicsBufferClazz);
    gScreenshotHardwareBufferClassInfo.builder =
            GetStaticMethodIDOrDie(env, screenshotGraphicsBufferClazz, "createFromNative",
                                   "(Landroid/hardware/HardwareBuffer;IZZ)Landroid/window/"
                                   "ScreenCapture$ScreenshotHardwareBuffer;");

    return err;
}

} // namespace android
