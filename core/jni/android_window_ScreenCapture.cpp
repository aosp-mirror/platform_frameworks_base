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
    jmethodID getNativeExcludeLayers;
    jfieldID hintForSeamlessTransition;
} gCaptureArgsClassInfo;

static struct {
    jfieldID displayToken;
    jfieldID width;
    jfieldID height;
    jfieldID useIdentityTransform;
} gDisplayCaptureArgsClassInfo;

static struct {
    jfieldID layer;
    jfieldID childrenOnly;
} gLayerCaptureArgsClassInfo;

static struct {
    jmethodID accept;
} gConsumerClassInfo;

static struct {
    jclass clazz;
    jmethodID builder;
} gScreenshotHardwareBufferClassInfo;

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
        mConsumerWeak = env->NewWeakGlobalRef(jobject);
    }

    ~ScreenCaptureListenerWrapper() {
        if (mConsumerWeak) {
            getenv()->DeleteWeakGlobalRef(mConsumerWeak);
            mConsumerWeak = nullptr;
        }
    }

    binder::Status onScreenCaptureCompleted(
            const gui::ScreenCaptureResults& captureResults) override {
        JNIEnv* env = getenv();

        ScopedLocalRef<jobject> consumer{env, env->NewLocalRef(mConsumerWeak)};
        if (consumer == nullptr) {
            ALOGE("ScreenCaptureListenerWrapper consumer not alive.");
            return binder::Status::ok();
        }

        if (!captureResults.fenceResult.ok() || captureResults.buffer == nullptr) {
            env->CallVoidMethod(consumer.get(), gConsumerClassInfo.accept, nullptr);
            checkAndClearException(env, "accept");
            return binder::Status::ok();
        }
        captureResults.fenceResult.value()->waitForever(LOG_TAG);
        jobject jhardwareBuffer = android_hardware_HardwareBuffer_createFromAHardwareBuffer(
                env, captureResults.buffer->toAHardwareBuffer());
        jobject screenshotHardwareBuffer =
                env->CallStaticObjectMethod(gScreenshotHardwareBufferClassInfo.clazz,
                                            gScreenshotHardwareBufferClassInfo.builder,
                                            jhardwareBuffer,
                                            static_cast<jint>(captureResults.capturedDataspace),
                                            captureResults.capturedSecureLayers,
                                            captureResults.capturedHdrLayers);
        checkAndClearException(env, "builder");
        env->CallVoidMethod(consumer.get(), gConsumerClassInfo.accept, screenshotHardwareBuffer);
        checkAndClearException(env, "accept");
        env->DeleteLocalRef(jhardwareBuffer);
        env->DeleteLocalRef(screenshotHardwareBuffer);
        return binder::Status::ok();
    }

private:
    jweak mConsumerWeak;
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

    jlongArray excludeObjectArray = reinterpret_cast<jlongArray>(
            env->CallObjectMethod(captureArgsObject, gCaptureArgsClassInfo.getNativeExcludeLayers));
    if (excludeObjectArray != nullptr) {
        ScopedLongArrayRO excludeArray(env, excludeObjectArray);
        const jsize len = excludeArray.size();
        captureArgs.excludeHandles.reserve(len);

        for (jsize i = 0; i < len; i++) {
            auto excludeObject = reinterpret_cast<SurfaceControl*>(excludeArray[i]);
            if (excludeObject == nullptr) {
                jniThrowNullPointerException(env, "Exclude layer is null");
                return;
            }
            captureArgs.excludeHandles.emplace(excludeObject->getHandle());
        }
    }
    captureArgs.hintForSeamlessTransition =
            env->GetBooleanField(captureArgsObject,
                                 gCaptureArgsClassInfo.hintForSeamlessTransition);
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
                                 jlong screenCaptureListenerObject) {
    const DisplayCaptureArgs captureArgs =
            displayCaptureArgsFromObject(env, displayCaptureArgsObject);

    if (captureArgs.displayToken == nullptr) {
        return BAD_VALUE;
    }

    sp<gui::IScreenCaptureListener> captureListener =
            reinterpret_cast<gui::IScreenCaptureListener*>(screenCaptureListenerObject);
    return ScreenshotClient::captureDisplay(captureArgs, captureListener);
}

static jint nativeCaptureLayers(JNIEnv* env, jclass clazz, jobject layerCaptureArgsObject,
                                jlong screenCaptureListenerObject) {
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

    sp<gui::IScreenCaptureListener> captureListener =
            reinterpret_cast<gui::IScreenCaptureListener*>(screenCaptureListenerObject);
    return ScreenshotClient::captureLayers(captureArgs, captureListener);
}

static jlong nativeCreateScreenCaptureListener(JNIEnv* env, jclass clazz, jobject consumerObj) {
    sp<gui::IScreenCaptureListener> listener =
            sp<ScreenCaptureListenerWrapper>::make(env, consumerObj);
    listener->incStrong((void*)nativeCreateScreenCaptureListener);
    return reinterpret_cast<jlong>(listener.get());
}

static void nativeWriteListenerToParcel(JNIEnv* env, jclass clazz, jlong nativeObject,
                                        jobject parcelObj) {
    Parcel* parcel = parcelForJavaObject(env, parcelObj);
    if (parcel == NULL) {
        jniThrowNullPointerException(env, NULL);
        return;
    }
    ScreenCaptureListenerWrapper* const self =
            reinterpret_cast<ScreenCaptureListenerWrapper*>(nativeObject);
    if (self != nullptr) {
        parcel->writeStrongBinder(IInterface::asBinder(self));
    }
}

static jlong nativeReadListenerFromParcel(JNIEnv* env, jclass clazz, jobject parcelObj) {
    Parcel* parcel = parcelForJavaObject(env, parcelObj);
    if (parcel == NULL) {
        jniThrowNullPointerException(env, NULL);
        return 0;
    }
    sp<gui::IScreenCaptureListener> listener =
            interface_cast<gui::IScreenCaptureListener>(parcel->readStrongBinder());
    if (listener == nullptr) {
        return 0;
    }
    listener->incStrong((void*)nativeCreateScreenCaptureListener);
    return reinterpret_cast<jlong>(listener.get());
}

void destroyNativeListener(void* ptr) {
    ScreenCaptureListenerWrapper* listener = reinterpret_cast<ScreenCaptureListenerWrapper*>(ptr);
    listener->decStrong((void*)nativeCreateScreenCaptureListener);
}

static jlong getNativeListenerFinalizer(JNIEnv* env, jclass clazz) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&destroyNativeListener));
}

// ----------------------------------------------------------------------------

static const JNINativeMethod sScreenCaptureMethods[] = {
        // clang-format off
    {"nativeCaptureDisplay", "(Landroid/window/ScreenCapture$DisplayCaptureArgs;J)I",
            (void*)nativeCaptureDisplay },
    {"nativeCaptureLayers",  "(Landroid/window/ScreenCapture$LayerCaptureArgs;J)I",
            (void*)nativeCaptureLayers },
    {"nativeCreateScreenCaptureListener", "(Ljava/util/function/Consumer;)J",
            (void*)nativeCreateScreenCaptureListener },
    {"nativeWriteListenerToParcel", "(JLandroid/os/Parcel;)V", (void*)nativeWriteListenerToParcel },
    {"nativeReadListenerFromParcel", "(Landroid/os/Parcel;)J",
            (void*)nativeReadListenerFromParcel },
    {"getNativeListenerFinalizer", "()J", (void*)getNativeListenerFinalizer },
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
    gCaptureArgsClassInfo.getNativeExcludeLayers =
            GetMethodIDOrDie(env, captureArgsClazz, "getNativeExcludeLayers", "()[J");
    gCaptureArgsClassInfo.hintForSeamlessTransition =
            GetFieldIDOrDie(env, captureArgsClazz, "mHintForSeamlessTransition", "Z");

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
    gLayerCaptureArgsClassInfo.childrenOnly =
            GetFieldIDOrDie(env, layerCaptureArgsClazz, "mChildrenOnly", "Z");

    jclass consumer = FindClassOrDie(env, "java/util/function/Consumer");
    gConsumerClassInfo.accept = GetMethodIDOrDie(env, consumer, "accept", "(Ljava/lang/Object;)V");

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
