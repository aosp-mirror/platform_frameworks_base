/*
 * Copyright (C) 2013 The Android Open Source Project
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

#define LOG_TAG "SurfaceControl"
#define LOG_NDEBUG 0

#include "android_os_Parcel.h"
#include "android_util_Binder.h"
#include "android_hardware_input_InputWindowHandle.h"
#include "core_jni_helpers.h"

#include <memory>

#include <android-base/chrono_utils.h>
#include <android/graphics/region.h>
#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/android_view_Surface.h>
#include <android_runtime/android_view_SurfaceSession.h>
#include <gui/ISurfaceComposer.h>
#include <gui/Surface.h>
#include <gui/SurfaceComposerClient.h>
#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedUtfChars.h>
#include <private/gui/ComposerService.h>
#include <stdio.h>
#include <system/graphics.h>
#include <ui/ConfigStoreTypes.h>
#include <ui/DeviceProductInfo.h>
#include <ui/DisplayConfig.h>
#include <ui/DisplayInfo.h>
#include <ui/DisplayedFrameStats.h>
#include <ui/FrameStats.h>
#include <ui/GraphicTypes.h>
#include <ui/HdrCapabilities.h>
#include <ui/Rect.h>
#include <ui/Region.h>
#include <utils/Log.h>

// ----------------------------------------------------------------------------

namespace android {

static void doThrowNPE(JNIEnv* env) {
    jniThrowNullPointerException(env, NULL);
}

static void doThrowIAE(JNIEnv* env, const char* msg = nullptr) {
    jniThrowException(env, "java/lang/IllegalArgumentException", msg);
}

static const char* const OutOfResourcesException =
    "android/view/Surface$OutOfResourcesException";

static struct {
    jclass clazz;
    jmethodID ctor;
} gIntegerClassInfo;

static jobject toInteger(JNIEnv* env, int32_t i) {
    return env->NewObject(gIntegerClassInfo.clazz, gIntegerClassInfo.ctor, i);
}

static struct {
    jclass clazz;
    jmethodID ctor;
    jfieldID isInternal;
    jfieldID density;
    jfieldID secure;
    jfieldID deviceProductInfo;
} gDisplayInfoClassInfo;

static struct {
    jclass clazz;
    jmethodID ctor;
    jfieldID width;
    jfieldID height;
    jfieldID xDpi;
    jfieldID yDpi;
    jfieldID refreshRate;
    jfieldID appVsyncOffsetNanos;
    jfieldID presentationDeadlineNanos;
    jfieldID configGroup;
} gDisplayConfigClassInfo;

static struct {
    jfieldID bottom;
    jfieldID left;
    jfieldID right;
    jfieldID top;
} gRectClassInfo;

// Implements SkMallocPixelRef::ReleaseProc, to delete the screenshot on unref.
void DeleteScreenshot(void* addr, void* context) {
    delete ((ScreenshotClient*) context);
}

static struct {
    nsecs_t UNDEFINED_TIME_NANO;
    jmethodID init;
} gWindowContentFrameStatsClassInfo;

static struct {
    nsecs_t UNDEFINED_TIME_NANO;
    jmethodID init;
} gWindowAnimationFrameStatsClassInfo;

static struct {
    jclass clazz;
    jmethodID ctor;
} gHdrCapabilitiesClassInfo;

static struct {
    jclass clazz;
    jmethodID ctor;
} gDeviceProductInfoClassInfo;

static struct {
    jclass clazz;
    jmethodID ctor;
} gDeviceProductInfoManufactureDateClassInfo;

static struct {
    jclass clazz;
    jmethodID builder;
} gGraphicBufferClassInfo;

static struct {
    jclass clazz;
    jmethodID ctor;
} gDisplayedContentSampleClassInfo;

static struct {
    jclass clazz;
    jmethodID ctor;
} gDisplayedContentSamplingAttributesClassInfo;

static struct {
    jclass clazz;
    jmethodID ctor;
    jfieldID X;
    jfieldID Y;
    jfieldID Z;
} gCieXyzClassInfo;

static struct {
    jclass clazz;
    jmethodID ctor;
    jfieldID red;
    jfieldID green;
    jfieldID blue;
    jfieldID white;
} gDisplayPrimariesClassInfo;

static struct {
    jclass clazz;
    jmethodID builder;
} gScreenshotGraphicBufferClassInfo;

static struct {
    jclass clazz;
    jmethodID ctor;
    jfieldID defaultConfig;
    jfieldID primaryRefreshRateMin;
    jfieldID primaryRefreshRateMax;
    jfieldID appRequestRefreshRateMin;
    jfieldID appRequestRefreshRateMax;
} gDesiredDisplayConfigSpecsClassInfo;

class JNamedColorSpace {
public:
    // ColorSpace.Named.SRGB.ordinal() = 0;
    static constexpr jint SRGB = 0;

    // ColorSpace.Named.DISPLAY_P3.ordinal() = 7;
    static constexpr jint DISPLAY_P3 = 7;
};

constexpr jint fromDataspaceToNamedColorSpaceValue(const ui::Dataspace dataspace) {
    switch (dataspace) {
        case ui::Dataspace::DISPLAY_P3:
            return JNamedColorSpace::DISPLAY_P3;
        default:
            return JNamedColorSpace::SRGB;
    }
}

constexpr ui::Dataspace pickDataspaceFromColorMode(const ui::ColorMode colorMode) {
    switch (colorMode) {
        case ui::ColorMode::DISPLAY_P3:
        case ui::ColorMode::BT2100_PQ:
        case ui::ColorMode::BT2100_HLG:
        case ui::ColorMode::DISPLAY_BT2020:
            return ui::Dataspace::DISPLAY_P3;
        default:
            return ui::Dataspace::V0_SRGB;
    }
}

// ----------------------------------------------------------------------------

static jlong nativeCreateTransaction(JNIEnv* env, jclass clazz) {
    return reinterpret_cast<jlong>(new SurfaceComposerClient::Transaction);
}

static void releaseTransaction(SurfaceComposerClient::Transaction* t) {
    delete t;
}

static jlong nativeGetNativeTransactionFinalizer(JNIEnv* env, jclass clazz) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&releaseTransaction));
}

static jlong nativeCreate(JNIEnv* env, jclass clazz, jobject sessionObj,
        jstring nameStr, jint w, jint h, jint format, jint flags, jlong parentObject,
        jobject metadataParcel) {
    ScopedUtfChars name(env, nameStr);
    sp<SurfaceComposerClient> client;
    if (sessionObj != NULL) {
        client = android_view_SurfaceSession_getClient(env, sessionObj);
    } else {
        client = SurfaceComposerClient::getDefault();
    }
    SurfaceControl *parent = reinterpret_cast<SurfaceControl*>(parentObject);
    sp<SurfaceControl> surface;
    LayerMetadata metadata;
    Parcel* parcel = parcelForJavaObject(env, metadataParcel);
    if (parcel && !parcel->objectsCount()) {
        status_t err = metadata.readFromParcel(parcel);
        if (err != NO_ERROR) {
          jniThrowException(env, "java/lang/IllegalArgumentException",
                            "Metadata parcel has wrong format");
        }
    }

    status_t err = client->createSurfaceChecked(
            String8(name.c_str()), w, h, format, &surface, flags, parent, std::move(metadata));
    if (err == NAME_NOT_FOUND) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return 0;
    } else if (err != NO_ERROR) {
        jniThrowException(env, OutOfResourcesException, NULL);
        return 0;
    }

    surface->incStrong((void *)nativeCreate);
    return reinterpret_cast<jlong>(surface.get());
}

static void nativeRelease(JNIEnv* env, jclass clazz, jlong nativeObject) {
    sp<SurfaceControl> ctrl(reinterpret_cast<SurfaceControl *>(nativeObject));
    ctrl->decStrong((void *)nativeCreate);
}

static void nativeDisconnect(JNIEnv* env, jclass clazz, jlong nativeObject) {
    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    if (ctrl != NULL) {
        ctrl->disconnect();
    }
}

static Rect rectFromObj(JNIEnv* env, jobject rectObj) {
    int left = env->GetIntField(rectObj, gRectClassInfo.left);
    int top = env->GetIntField(rectObj, gRectClassInfo.top);
    int right = env->GetIntField(rectObj, gRectClassInfo.right);
    int bottom = env->GetIntField(rectObj, gRectClassInfo.bottom);
    return Rect(left, top, right, bottom);
}

static jobject nativeScreenshot(JNIEnv* env, jclass clazz,
        jobject displayTokenObj, jobject sourceCropObj, jint width, jint height,
        bool useIdentityTransform, int rotation, bool captureSecureLayers) {
    sp<IBinder> displayToken = ibinderForJavaObject(env, displayTokenObj);
    if (displayToken == NULL) {
        return NULL;
    }
    const ui::ColorMode colorMode = SurfaceComposerClient::getActiveColorMode(displayToken);
    const ui::Dataspace dataspace = pickDataspaceFromColorMode(colorMode);

    Rect sourceCrop = rectFromObj(env, sourceCropObj);
    sp<GraphicBuffer> buffer;
    bool capturedSecureLayers = false;
    status_t res = ScreenshotClient::capture(displayToken, dataspace,
            ui::PixelFormat::RGBA_8888,
            sourceCrop, width, height,
            useIdentityTransform, ui::toRotation(rotation),
            captureSecureLayers, &buffer, capturedSecureLayers);
    if (res != NO_ERROR) {
        return NULL;
    }

    const jint namedColorSpace = fromDataspaceToNamedColorSpaceValue(dataspace);
    return env->CallStaticObjectMethod(gScreenshotGraphicBufferClassInfo.clazz,
            gScreenshotGraphicBufferClassInfo.builder,
            buffer->getWidth(),
            buffer->getHeight(),
            buffer->getPixelFormat(),
            (jint)buffer->getUsage(),
            (jlong)buffer.get(),
            namedColorSpace,
            capturedSecureLayers);
}

static jobject nativeCaptureLayers(JNIEnv* env, jclass clazz, jobject displayTokenObj,
        jlong layerObject, jobject sourceCropObj, jfloat frameScale,
        jlongArray excludeObjectArray, jint format) {

    auto layer = reinterpret_cast<SurfaceControl *>(layerObject);
    if (layer == NULL) {
        return NULL;
    }

    Rect sourceCrop;
    if (sourceCropObj != NULL) {
        sourceCrop = rectFromObj(env, sourceCropObj);
    }

    std::unordered_set<sp<IBinder>,ISurfaceComposer::SpHash<IBinder>> excludeHandles;
    if (excludeObjectArray != NULL) {
        const jsize len = env->GetArrayLength(excludeObjectArray);
        excludeHandles.reserve(len);

        const jlong* objects = env->GetLongArrayElements(excludeObjectArray, nullptr);
        for (jsize i = 0; i < len; i++) {
            auto excludeObject = reinterpret_cast<SurfaceControl *>(objects[i]);
            if (excludeObject == nullptr) {
                jniThrowNullPointerException(env, "Exclude layer is null");
                return NULL;
            }
            excludeHandles.emplace(excludeObject->getHandle());
        }
        env->ReleaseLongArrayElements(excludeObjectArray, const_cast<jlong*>(objects), JNI_ABORT);
    }

    sp<GraphicBuffer> buffer;
    ui::Dataspace dataspace = ui::Dataspace::V0_SRGB;
    sp<IBinder> displayToken = ibinderForJavaObject(env, displayTokenObj);
    if (displayToken != nullptr) {
        const ui::ColorMode colorMode = SurfaceComposerClient::getActiveColorMode(displayToken);
        dataspace = pickDataspaceFromColorMode(colorMode);
    }
    status_t res = ScreenshotClient::captureChildLayers(layer->getHandle(), dataspace,
                                                        static_cast<ui::PixelFormat>(format),
                                                        sourceCrop, excludeHandles, frameScale,
                                                        &buffer);
    if (res != NO_ERROR) {
        return NULL;
    }

    const jint namedColorSpace = fromDataspaceToNamedColorSpaceValue(dataspace);
    return env->CallStaticObjectMethod(gScreenshotGraphicBufferClassInfo.clazz,
                                       gScreenshotGraphicBufferClassInfo.builder,
                                       buffer->getWidth(),
                                       buffer->getHeight(),
                                       buffer->getPixelFormat(),
                                       (jint)buffer->getUsage(),
                                       (jlong)buffer.get(),
                                       namedColorSpace,
                                       false /* capturedSecureLayers */);
}

static void nativeApplyTransaction(JNIEnv* env, jclass clazz, jlong transactionObj, jboolean sync) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    transaction->apply(sync);
}

static void nativeMergeTransaction(JNIEnv* env, jclass clazz,
        jlong transactionObj, jlong otherTransactionObj) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    auto otherTransaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(
            otherTransactionObj);
    transaction->merge(std::move(*otherTransaction));
}

static void nativeSetAnimationTransaction(JNIEnv* env, jclass clazz, jlong transactionObj) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    transaction->setAnimationTransaction();
}

static void nativeSetEarlyWakeup(JNIEnv* env, jclass clazz, jlong transactionObj) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    transaction->setEarlyWakeup();
}

static void nativeSetLayer(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject, jint zorder) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    transaction->setLayer(ctrl, zorder);
}

static void nativeSetRelativeLayer(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject,
        jlong relativeToObject, jint zorder) {

    auto ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    auto relative = reinterpret_cast<SurfaceControl *>(relativeToObject);
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    transaction->setRelativeLayer(ctrl, relative->getHandle(), zorder);
}

static void nativeSetPosition(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject, jfloat x, jfloat y) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    transaction->setPosition(ctrl, x, y);
}

static void nativeSetGeometry(JNIEnv* env, jclass clazz, jlong transactionObj, jlong nativeObject,
        jobject sourceObj, jobject dstObj, jlong orientation) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);

    Rect source, dst;
    if (sourceObj != NULL) {
        source = rectFromObj(env, sourceObj);
    } else {
        source.makeInvalid();
    }
    if (dstObj != NULL) {
        dst = rectFromObj(env, dstObj);
    } else {
        dst.makeInvalid();
    }
    transaction->setGeometry(ctrl, source, dst, orientation);
}

static void nativeSetSize(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject, jint w, jint h) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    transaction->setSize(ctrl, w, h);
}

static void nativeSetFlags(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject, jint flags, jint mask) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    transaction->setFlags(ctrl, flags, mask);
}

static void nativeSetFrameRateSelectionPriority(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject, jint priority) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    transaction->setFrameRateSelectionPriority(ctrl, priority);
}

static void nativeSetTransparentRegionHint(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject, jobject regionObj) {
    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    graphics::RegionIterator iterator(env, regionObj);
    if (!iterator.isValid()) {
        doThrowIAE(env);
        return;
    }

    ARect bounds = iterator.getTotalBounds();
    Region reg({bounds.left, bounds.top, bounds.right, bounds.bottom});
    if (iterator.isComplex()) {
        while (!iterator.isDone()) {
            ARect rect = iterator.getRect();
            reg.addRectUnchecked(rect.left, rect.top, rect.right, rect.bottom);
            iterator.next();
        }
    }

    {
        auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
        transaction->setTransparentRegionHint(ctrl, reg);
    }
}

static void nativeSetAlpha(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject, jfloat alpha) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    transaction->setAlpha(ctrl, alpha);
}

static void nativeSetInputWindowInfo(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject, jobject inputWindow) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    sp<NativeInputWindowHandle> handle = android_view_InputWindowHandle_getHandle(
            env, inputWindow);
    handle->updateInfo();

    auto ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    transaction->setInputWindowInfo(ctrl, *handle->getInfo());
}

static void nativeSyncInputWindows(JNIEnv* env, jclass clazz, jlong transactionObj) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    transaction->syncInputWindows();
}

static void nativeSetMetadata(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject, jint id, jobject parcelObj) {
    Parcel* parcel = parcelForJavaObject(env, parcelObj);
    if (!parcel) {
        jniThrowNullPointerException(env, "attribute data");
        return;
    }
    if (parcel->objectsCount()) {
        jniThrowException(env, "java/lang/RuntimeException",
                "Tried to marshall a Parcel that contained Binder objects.");
        return;
    }

    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl*>(nativeObject);
    transaction->setMetadata(ctrl, id, *parcel);
}

static void nativeSetColor(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject, jfloatArray fColor) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);

    float* floatColors = env->GetFloatArrayElements(fColor, 0);
    half3 color(floatColors[0], floatColors[1], floatColors[2]);
    transaction->setColor(ctrl, color);
    env->ReleaseFloatArrayElements(fColor, floatColors, 0);
}

static void nativeSetMatrix(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject,
        jfloat dsdx, jfloat dtdx, jfloat dtdy, jfloat dsdy) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    transaction->setMatrix(ctrl, dsdx, dtdx, dtdy, dsdy);
}

static void nativeSetColorTransform(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject, jfloatArray fMatrix, jfloatArray fTranslation) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    SurfaceControl* const surfaceControl = reinterpret_cast<SurfaceControl*>(nativeObject);
    float* floatMatrix = env->GetFloatArrayElements(fMatrix, 0);
    mat3 matrix(static_cast<float const*>(floatMatrix));
    env->ReleaseFloatArrayElements(fMatrix, floatMatrix, 0);

    float* floatTranslation = env->GetFloatArrayElements(fTranslation, 0);
    vec3 translation(floatTranslation[0], floatTranslation[1], floatTranslation[2]);
    env->ReleaseFloatArrayElements(fTranslation, floatTranslation, 0);

    transaction->setColorTransform(surfaceControl, matrix, translation);
}

static void nativeSetColorSpaceAgnostic(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject, jboolean agnostic) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    SurfaceControl* const surfaceControl = reinterpret_cast<SurfaceControl*>(nativeObject);
    transaction->setColorSpaceAgnostic(surfaceControl, agnostic);
}

static void nativeSetWindowCrop(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject,
        jint l, jint t, jint r, jint b) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    Rect crop(l, t, r, b);
    transaction->setCrop_legacy(ctrl, crop);
}

static void nativeSetCornerRadius(JNIEnv* env, jclass clazz, jlong transactionObj,
         jlong nativeObject, jfloat cornerRadius) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    transaction->setCornerRadius(ctrl, cornerRadius);
}

static void nativeSetBackgroundBlurRadius(JNIEnv* env, jclass clazz, jlong transactionObj,
         jlong nativeObject, jint blurRadius) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    transaction->setBackgroundBlurRadius(ctrl, blurRadius);
}

static void nativeSetLayerStack(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject, jint layerStack) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    transaction->setLayerStack(ctrl, layerStack);
}

static void nativeSetShadowRadius(JNIEnv* env, jclass clazz, jlong transactionObj,
         jlong nativeObject, jfloat shadowRadius) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    const auto ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    transaction->setShadowRadius(ctrl, shadowRadius);
}

static void nativeSetFrameRate(JNIEnv* env, jclass clazz, jlong transactionObj, jlong nativeObject,
                               jfloat frameRate, jint compatibility) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    const auto ctrl = reinterpret_cast<SurfaceControl*>(nativeObject);
    // Our compatibility is a Surface.FRAME_RATE_COMPATIBILITY_* value, and
    // Transaction::setFrameRate() takes an ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_* value. The
    // values are identical though, so no need to convert anything.
    transaction->setFrameRate(ctrl, frameRate, static_cast<int8_t>(compatibility));
}

static jlong nativeAcquireFrameRateFlexibilityToken(JNIEnv* env, jclass clazz) {
    sp<ISurfaceComposer> composer = ComposerService::getComposerService();
    sp<IBinder> token;
    status_t result = composer->acquireFrameRateFlexibilityToken(&token);
    if (result < 0) {
        ALOGE("Failed acquiring frame rate flexibility token: %s (%d)", strerror(-result), result);
        return 0;
    }
    token->incStrong((void*)nativeAcquireFrameRateFlexibilityToken);
    return reinterpret_cast<jlong>(token.get());
}

static void nativeReleaseFrameRateFlexibilityToken(JNIEnv* env, jclass clazz, jlong tokenLong) {
    sp<IBinder> token(reinterpret_cast<IBinder*>(tokenLong));
    token->decStrong((void*)nativeAcquireFrameRateFlexibilityToken);
}

static jlongArray nativeGetPhysicalDisplayIds(JNIEnv* env, jclass clazz) {
    const auto displayIds = SurfaceComposerClient::getPhysicalDisplayIds();
    jlongArray array = env->NewLongArray(displayIds.size());
    if (array == nullptr) {
        jniThrowException(env, "java/lang/OutOfMemoryError", nullptr);
        return nullptr;
    }

    if (displayIds.empty()) {
        return array;
    }

    jlong* values = env->GetLongArrayElements(array, 0);
    for (size_t i = 0; i < displayIds.size(); ++i) {
        values[i] = static_cast<jlong>(displayIds[i]);
    }

    env->ReleaseLongArrayElements(array, values, 0);
    return array;
}

static jobject nativeGetPhysicalDisplayToken(JNIEnv* env, jclass clazz, jlong physicalDisplayId) {
    sp<IBinder> token = SurfaceComposerClient::getPhysicalDisplayToken(physicalDisplayId);
    return javaObjectForIBinder(env, token);
}

static jobject nativeGetDisplayedContentSamplingAttributes(JNIEnv* env, jclass clazz,
        jobject tokenObj) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));

    ui::PixelFormat format;
    ui::Dataspace dataspace;
    uint8_t componentMask;
    status_t err = SurfaceComposerClient::getDisplayedContentSamplingAttributes(
            token, &format, &dataspace, &componentMask);
    if (err != OK) {
        return nullptr;
    }
    return env->NewObject(gDisplayedContentSamplingAttributesClassInfo.clazz,
                          gDisplayedContentSamplingAttributesClassInfo.ctor,
                          format, dataspace, componentMask);
}

static jboolean nativeSetDisplayedContentSamplingEnabled(JNIEnv* env, jclass clazz,
        jobject tokenObj, jboolean enable, jint componentMask, jint maxFrames) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    status_t rc = SurfaceComposerClient::setDisplayContentSamplingEnabled(
            token, enable, componentMask, maxFrames);
    return rc == OK;
}

static jobject nativeGetDisplayedContentSample(JNIEnv* env, jclass clazz, jobject tokenObj,
    jlong maxFrames, jlong timestamp) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));

    DisplayedFrameStats stats;
    status_t err = SurfaceComposerClient::getDisplayedContentSample(
            token, maxFrames, timestamp, &stats);
    if (err != OK) {
        return nullptr;
    }

    jlongArray histogramComponent0 = env->NewLongArray(stats.component_0_sample.size());
    jlongArray histogramComponent1 = env->NewLongArray(stats.component_1_sample.size());
    jlongArray histogramComponent2 = env->NewLongArray(stats.component_2_sample.size());
    jlongArray histogramComponent3 = env->NewLongArray(stats.component_3_sample.size());
    if ((histogramComponent0 == nullptr) ||
        (histogramComponent1 == nullptr) ||
        (histogramComponent2 == nullptr) ||
        (histogramComponent3 == nullptr)) {
        return JNI_FALSE;
    }

    env->SetLongArrayRegion(histogramComponent0, 0,
            stats.component_0_sample.size(),
            reinterpret_cast<jlong*>(stats.component_0_sample.data()));
    env->SetLongArrayRegion(histogramComponent1, 0,
            stats.component_1_sample.size(),
            reinterpret_cast<jlong*>(stats.component_1_sample.data()));
    env->SetLongArrayRegion(histogramComponent2, 0,
            stats.component_2_sample.size(),
            reinterpret_cast<jlong*>(stats.component_2_sample.data()));
    env->SetLongArrayRegion(histogramComponent3, 0,
            stats.component_3_sample.size(),
            reinterpret_cast<jlong*>(stats.component_3_sample.data()));
    return env->NewObject(gDisplayedContentSampleClassInfo.clazz,
                          gDisplayedContentSampleClassInfo.ctor,
                          stats.numFrames,
                          histogramComponent0,
                          histogramComponent1,
                          histogramComponent2,
                          histogramComponent3);
}

static jobject nativeCreateDisplay(JNIEnv* env, jclass clazz, jstring nameObj,
        jboolean secure) {
    ScopedUtfChars name(env, nameObj);
    sp<IBinder> token(SurfaceComposerClient::createDisplay(
            String8(name.c_str()), bool(secure)));
    return javaObjectForIBinder(env, token);
}

static void nativeDestroyDisplay(JNIEnv* env, jclass clazz, jobject tokenObj) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == NULL) return;
    SurfaceComposerClient::destroyDisplay(token);
}

static void nativeSetDisplaySurface(JNIEnv* env, jclass clazz,
        jlong transactionObj,
        jobject tokenObj, jlong nativeSurfaceObject) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == NULL) return;
    sp<IGraphicBufferProducer> bufferProducer;
    sp<Surface> sur(reinterpret_cast<Surface *>(nativeSurfaceObject));
    if (sur != NULL) {
        bufferProducer = sur->getIGraphicBufferProducer();
    }


    status_t err = NO_ERROR;
    {
        auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
        err = transaction->setDisplaySurface(token,
                bufferProducer);
    }
    if (err != NO_ERROR) {
        doThrowIAE(env, "Illegal Surface, could not enable async mode. Was this"
                " Surface created with singleBufferMode?");
    }
}

static void nativeSetDisplayLayerStack(JNIEnv* env, jclass clazz,
        jlong transactionObj,
        jobject tokenObj, jint layerStack) {

    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == NULL) return;

    {
        auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
        transaction->setDisplayLayerStack(token, layerStack);
    }
}

static void nativeSetDisplayProjection(JNIEnv* env, jclass clazz,
        jlong transactionObj,
        jobject tokenObj, jint orientation,
        jint layerStackRect_left, jint layerStackRect_top, jint layerStackRect_right, jint layerStackRect_bottom,
        jint displayRect_left, jint displayRect_top, jint displayRect_right, jint displayRect_bottom) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == NULL) return;
    Rect layerStackRect(layerStackRect_left, layerStackRect_top, layerStackRect_right, layerStackRect_bottom);
    Rect displayRect(displayRect_left, displayRect_top, displayRect_right, displayRect_bottom);

    {
        auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
        transaction->setDisplayProjection(token, static_cast<ui::Rotation>(orientation),
                                          layerStackRect, displayRect);
    }
}

static void nativeSetDisplaySize(JNIEnv* env, jclass clazz,
        jlong transactionObj,
        jobject tokenObj, jint width, jint height) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == NULL) return;

    {
        auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
        transaction->setDisplaySize(token, width, height);
    }
}

static jobject convertDeviceProductInfoToJavaObject(
        JNIEnv* env, const std::optional<DeviceProductInfo>& info) {
    using ModelYear = android::DeviceProductInfo::ModelYear;
    using ManufactureYear = android::DeviceProductInfo::ManufactureYear;
    using ManufactureWeekAndYear = android::DeviceProductInfo::ManufactureWeekAndYear;

    if (!info) return nullptr;
    jstring name = env->NewStringUTF(info->name.data());
    jstring manufacturerPnpId = env->NewStringUTF(info->manufacturerPnpId.data());
    jobject productId = env->NewStringUTF(info->productId.data());
    const auto& date = info->manufactureOrModelDate;
    jobject modelYear, manufactureDate;
    if (const auto* model = std::get_if<ModelYear>(&date)) {
        modelYear = toInteger(env, model->year);
        manufactureDate = nullptr;
    } else if (const auto* manufactureWeekAndYear = std::get_if<ManufactureWeekAndYear>(&date)) {
        modelYear = nullptr;
        manufactureDate = env->NewObject(gDeviceProductInfoManufactureDateClassInfo.clazz,
                                               gDeviceProductInfoManufactureDateClassInfo.ctor,
                                               toInteger(env, manufactureWeekAndYear->week),
                                               toInteger(env, manufactureWeekAndYear->year));
    } else if (const auto* manufactureYear = std::get_if<ManufactureYear>(&date)) {
        modelYear = nullptr;
        manufactureDate = env->NewObject(gDeviceProductInfoManufactureDateClassInfo.clazz,
                                       gDeviceProductInfoManufactureDateClassInfo.ctor,
                                       nullptr,
                                       toInteger(env, manufactureYear->year));
    } else {
        LOG_FATAL("Unknown alternative for variant DeviceProductInfo::ManufactureOrModelDate");
    }

    jintArray relativeAddress = nullptr;
    if (info->relativeAddress != DeviceProductInfo::NO_RELATIVE_ADDRESS) {
        relativeAddress = env->NewIntArray(info->relativeAddress.size());
        jint* relativeAddressData = env->GetIntArrayElements(relativeAddress, nullptr);
        for (size_t i = 0; i < info->relativeAddress.size(); i++) {
            relativeAddressData[i] = static_cast<jint>(info->relativeAddress[i]);
        }
        env->ReleaseIntArrayElements(relativeAddress, relativeAddressData, 0);
    }
    return env->NewObject(gDeviceProductInfoClassInfo.clazz, gDeviceProductInfoClassInfo.ctor, name,
                          manufacturerPnpId, productId, modelYear, manufactureDate,
                          relativeAddress);
}

static jobject nativeGetDisplayInfo(JNIEnv* env, jclass clazz, jobject tokenObj) {
    DisplayInfo info;
    if (const auto token = ibinderForJavaObject(env, tokenObj);
        !token || SurfaceComposerClient::getDisplayInfo(token, &info) != NO_ERROR) {
        return nullptr;
    }

    jobject object = env->NewObject(gDisplayInfoClassInfo.clazz, gDisplayInfoClassInfo.ctor);
    env->SetBooleanField(object, gDisplayInfoClassInfo.isInternal,
                         info.connectionType == DisplayConnectionType::Internal);
    env->SetFloatField(object, gDisplayInfoClassInfo.density, info.density);
    env->SetBooleanField(object, gDisplayInfoClassInfo.secure, info.secure);
    env->SetObjectField(object, gDisplayInfoClassInfo.deviceProductInfo,
                        convertDeviceProductInfoToJavaObject(env, info.deviceProductInfo));
    return object;
}

static jobjectArray nativeGetDisplayConfigs(JNIEnv* env, jclass clazz, jobject tokenObj) {
    Vector<DisplayConfig> configs;
    if (const auto token = ibinderForJavaObject(env, tokenObj); !token ||
        SurfaceComposerClient::getDisplayConfigs(token, &configs) != NO_ERROR ||
        configs.isEmpty()) {
        return nullptr;
    }

    jobjectArray configArray =
            env->NewObjectArray(configs.size(), gDisplayConfigClassInfo.clazz, nullptr);

    for (size_t c = 0; c < configs.size(); ++c) {
        const DisplayConfig& config = configs[c];
        jobject object =
                env->NewObject(gDisplayConfigClassInfo.clazz, gDisplayConfigClassInfo.ctor);
        env->SetIntField(object, gDisplayConfigClassInfo.width, config.resolution.getWidth());
        env->SetIntField(object, gDisplayConfigClassInfo.height, config.resolution.getHeight());
        env->SetFloatField(object, gDisplayConfigClassInfo.xDpi, config.xDpi);
        env->SetFloatField(object, gDisplayConfigClassInfo.yDpi, config.yDpi);

        env->SetFloatField(object, gDisplayConfigClassInfo.refreshRate, config.refreshRate);
        env->SetLongField(object, gDisplayConfigClassInfo.appVsyncOffsetNanos,
                          config.appVsyncOffset);
        env->SetLongField(object, gDisplayConfigClassInfo.presentationDeadlineNanos,
                          config.presentationDeadline);
        env->SetIntField(object, gDisplayConfigClassInfo.configGroup, config.configGroup);
        env->SetObjectArrayElement(configArray, static_cast<jsize>(c), object);
        env->DeleteLocalRef(object);
    }

    return configArray;
}

static jboolean nativeSetDesiredDisplayConfigSpecs(JNIEnv* env, jclass clazz, jobject tokenObj,
                                                   jobject desiredDisplayConfigSpecs) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == nullptr) return JNI_FALSE;

    jint defaultConfig = env->GetIntField(desiredDisplayConfigSpecs,
                                          gDesiredDisplayConfigSpecsClassInfo.defaultConfig);
    jfloat primaryRefreshRateMin =
            env->GetFloatField(desiredDisplayConfigSpecs,
                               gDesiredDisplayConfigSpecsClassInfo.primaryRefreshRateMin);
    jfloat primaryRefreshRateMax =
            env->GetFloatField(desiredDisplayConfigSpecs,
                               gDesiredDisplayConfigSpecsClassInfo.primaryRefreshRateMax);
    jfloat appRequestRefreshRateMin =
            env->GetFloatField(desiredDisplayConfigSpecs,
                               gDesiredDisplayConfigSpecsClassInfo.appRequestRefreshRateMin);
    jfloat appRequestRefreshRateMax =
            env->GetFloatField(desiredDisplayConfigSpecs,
                               gDesiredDisplayConfigSpecsClassInfo.appRequestRefreshRateMax);

    size_t result = SurfaceComposerClient::setDesiredDisplayConfigSpecs(token, defaultConfig,
                                                                        primaryRefreshRateMin,
                                                                        primaryRefreshRateMax,
                                                                        appRequestRefreshRateMin,
                                                                        appRequestRefreshRateMax);
    return result == NO_ERROR ? JNI_TRUE : JNI_FALSE;
}

static jobject nativeGetDesiredDisplayConfigSpecs(JNIEnv* env, jclass clazz, jobject tokenObj) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == nullptr) return nullptr;

    int32_t defaultConfig;
    float primaryRefreshRateMin;
    float primaryRefreshRateMax;
    float appRequestRefreshRateMin;
    float appRequestRefreshRateMax;
    if (SurfaceComposerClient::getDesiredDisplayConfigSpecs(token, &defaultConfig,
                                                            &primaryRefreshRateMin,
                                                            &primaryRefreshRateMax,
                                                            &appRequestRefreshRateMin,
                                                            &appRequestRefreshRateMax) !=
        NO_ERROR) {
        return nullptr;
    }

    return env->NewObject(gDesiredDisplayConfigSpecsClassInfo.clazz,
                          gDesiredDisplayConfigSpecsClassInfo.ctor, defaultConfig,
                          primaryRefreshRateMin, primaryRefreshRateMax, appRequestRefreshRateMin,
                          appRequestRefreshRateMax);
}

static jint nativeGetActiveConfig(JNIEnv* env, jclass clazz, jobject tokenObj) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == NULL) return -1;
    return static_cast<jint>(SurfaceComposerClient::getActiveConfig(token));
}

static jintArray nativeGetDisplayColorModes(JNIEnv* env, jclass, jobject tokenObj) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == NULL) return NULL;
    Vector<ui::ColorMode> colorModes;
    if (SurfaceComposerClient::getDisplayColorModes(token, &colorModes) != NO_ERROR ||
            colorModes.isEmpty()) {
        return NULL;
    }

    jintArray colorModesArray = env->NewIntArray(colorModes.size());
    if (colorModesArray == NULL) {
        jniThrowException(env, "java/lang/OutOfMemoryError", NULL);
        return NULL;
    }
    jint* colorModesArrayValues = env->GetIntArrayElements(colorModesArray, 0);
    for (size_t i = 0; i < colorModes.size(); i++) {
        colorModesArrayValues[i] = static_cast<jint>(colorModes[i]);
    }
    env->ReleaseIntArrayElements(colorModesArray, colorModesArrayValues, 0);
    return colorModesArray;
}

static jobject nativeGetDisplayNativePrimaries(JNIEnv* env, jclass, jobject tokenObj) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == NULL) return NULL;

    ui::DisplayPrimaries primaries;
    if (SurfaceComposerClient::getDisplayNativePrimaries(token, primaries) != NO_ERROR) {
        return NULL;
    }

    jobject jred = env->NewObject(gCieXyzClassInfo.clazz, gCieXyzClassInfo.ctor);
    if (jred == NULL) {
        jniThrowException(env, "java/lang/OutOfMemoryError", NULL);
        return NULL;
    }

    jobject jgreen = env->NewObject(gCieXyzClassInfo.clazz, gCieXyzClassInfo.ctor);
    if (jgreen == NULL) {
        jniThrowException(env, "java/lang/OutOfMemoryError", NULL);
        return NULL;
    }

    jobject jblue = env->NewObject(gCieXyzClassInfo.clazz, gCieXyzClassInfo.ctor);
    if (jblue == NULL) {
        jniThrowException(env, "java/lang/OutOfMemoryError", NULL);
        return NULL;
    }

    jobject jwhite = env->NewObject(gCieXyzClassInfo.clazz, gCieXyzClassInfo.ctor);
    if (jwhite == NULL) {
        jniThrowException(env, "java/lang/OutOfMemoryError", NULL);
        return NULL;
    }

    jobject jprimaries = env->NewObject(gDisplayPrimariesClassInfo.clazz,
            gDisplayPrimariesClassInfo.ctor);
    if (jprimaries == NULL) {
        jniThrowException(env, "java/lang/OutOfMemoryError", NULL);
        return NULL;
    }

    env->SetFloatField(jred, gCieXyzClassInfo.X, primaries.red.X);
    env->SetFloatField(jred, gCieXyzClassInfo.Y, primaries.red.Y);
    env->SetFloatField(jred, gCieXyzClassInfo.Z, primaries.red.Z);
    env->SetFloatField(jgreen, gCieXyzClassInfo.X, primaries.green.X);
    env->SetFloatField(jgreen, gCieXyzClassInfo.Y, primaries.green.Y);
    env->SetFloatField(jgreen, gCieXyzClassInfo.Z, primaries.green.Z);
    env->SetFloatField(jblue, gCieXyzClassInfo.X, primaries.blue.X);
    env->SetFloatField(jblue, gCieXyzClassInfo.Y, primaries.blue.Y);
    env->SetFloatField(jblue, gCieXyzClassInfo.Z, primaries.blue.Z);
    env->SetFloatField(jwhite, gCieXyzClassInfo.X, primaries.white.X);
    env->SetFloatField(jwhite, gCieXyzClassInfo.Y, primaries.white.Y);
    env->SetFloatField(jwhite, gCieXyzClassInfo.Z, primaries.white.Z);
    env->SetObjectField(jprimaries, gDisplayPrimariesClassInfo.red, jred);
    env->SetObjectField(jprimaries, gDisplayPrimariesClassInfo.green, jgreen);
    env->SetObjectField(jprimaries, gDisplayPrimariesClassInfo.blue, jblue);
    env->SetObjectField(jprimaries, gDisplayPrimariesClassInfo.white, jwhite);

    return jprimaries;
}

static jint nativeGetActiveColorMode(JNIEnv* env, jclass, jobject tokenObj) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == NULL) return -1;
    return static_cast<jint>(SurfaceComposerClient::getActiveColorMode(token));
}

static jintArray nativeGetCompositionDataspaces(JNIEnv* env, jclass) {
    ui::Dataspace defaultDataspace, wcgDataspace;
    ui::PixelFormat defaultPixelFormat, wcgPixelFormat;
    if (SurfaceComposerClient::getCompositionPreference(&defaultDataspace,
                                                        &defaultPixelFormat,
                                                        &wcgDataspace,
                                                        &wcgPixelFormat) != NO_ERROR) {
        return nullptr;
    }
    jintArray array = env->NewIntArray(2);
    if (array == nullptr) {
        jniThrowException(env, "java/lang/OutOfMemoryError", nullptr);
        return nullptr;
    }
    jint* arrayValues = env->GetIntArrayElements(array, 0);
    arrayValues[0] = static_cast<jint>(defaultDataspace);
    arrayValues[1] = static_cast<jint>(wcgDataspace);
    env->ReleaseIntArrayElements(array, arrayValues, 0);
    return array;
}

static jboolean nativeSetActiveColorMode(JNIEnv* env, jclass,
        jobject tokenObj, jint colorMode) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == NULL) return JNI_FALSE;
    status_t err = SurfaceComposerClient::setActiveColorMode(token,
            static_cast<ui::ColorMode>(colorMode));
    return err == NO_ERROR ? JNI_TRUE : JNI_FALSE;
}

static void nativeSetDisplayPowerMode(JNIEnv* env, jclass clazz, jobject tokenObj, jint mode) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == NULL) return;

    android::base::Timer t;
    SurfaceComposerClient::setDisplayPowerMode(token, mode);
    if (t.duration() > 100ms) ALOGD("Excessive delay in setPowerMode()");
}

static jboolean nativeGetProtectedContentSupport(JNIEnv* env, jclass) {
    return static_cast<jboolean>(SurfaceComposerClient::getProtectedContentSupport());
}

static jboolean nativeClearContentFrameStats(JNIEnv* env, jclass clazz, jlong nativeObject) {
    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    status_t err = ctrl->clearLayerFrameStats();

    if (err < 0 && err != NO_INIT) {
        doThrowIAE(env);
    }

    // The other end is not ready, just report we failed.
    if (err == NO_INIT) {
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

static jboolean nativeGetContentFrameStats(JNIEnv* env, jclass clazz, jlong nativeObject,
    jobject outStats) {
    FrameStats stats;

    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    status_t err = ctrl->getLayerFrameStats(&stats);
    if (err < 0 && err != NO_INIT) {
        doThrowIAE(env);
    }

    // The other end is not ready, fine just return empty stats.
    if (err == NO_INIT) {
        return JNI_FALSE;
    }

    jlong refreshPeriodNano = static_cast<jlong>(stats.refreshPeriodNano);
    size_t frameCount = stats.desiredPresentTimesNano.size();

    jlongArray postedTimesNanoDst = env->NewLongArray(frameCount);
    if (postedTimesNanoDst == NULL) {
        return JNI_FALSE;
    }

    jlongArray presentedTimesNanoDst = env->NewLongArray(frameCount);
    if (presentedTimesNanoDst == NULL) {
        return JNI_FALSE;
    }

    jlongArray readyTimesNanoDst = env->NewLongArray(frameCount);
    if (readyTimesNanoDst == NULL) {
        return JNI_FALSE;
    }

    nsecs_t postedTimesNanoSrc[frameCount];
    nsecs_t presentedTimesNanoSrc[frameCount];
    nsecs_t readyTimesNanoSrc[frameCount];

    for (size_t i = 0; i < frameCount; i++) {
        nsecs_t postedTimeNano = stats.desiredPresentTimesNano[i];
        if (postedTimeNano == INT64_MAX) {
            postedTimeNano = gWindowContentFrameStatsClassInfo.UNDEFINED_TIME_NANO;
        }
        postedTimesNanoSrc[i] = postedTimeNano;

        nsecs_t presentedTimeNano = stats.actualPresentTimesNano[i];
        if (presentedTimeNano == INT64_MAX) {
            presentedTimeNano = gWindowContentFrameStatsClassInfo.UNDEFINED_TIME_NANO;
        }
        presentedTimesNanoSrc[i] = presentedTimeNano;

        nsecs_t readyTimeNano = stats.frameReadyTimesNano[i];
        if (readyTimeNano == INT64_MAX) {
            readyTimeNano = gWindowContentFrameStatsClassInfo.UNDEFINED_TIME_NANO;
        }
        readyTimesNanoSrc[i] = readyTimeNano;
    }

    env->SetLongArrayRegion(postedTimesNanoDst, 0, frameCount, postedTimesNanoSrc);
    env->SetLongArrayRegion(presentedTimesNanoDst, 0, frameCount, presentedTimesNanoSrc);
    env->SetLongArrayRegion(readyTimesNanoDst, 0, frameCount, readyTimesNanoSrc);

    env->CallVoidMethod(outStats, gWindowContentFrameStatsClassInfo.init, refreshPeriodNano,
            postedTimesNanoDst, presentedTimesNanoDst, readyTimesNanoDst);

    if (env->ExceptionCheck()) {
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

static jboolean nativeClearAnimationFrameStats(JNIEnv* env, jclass clazz) {
    status_t err = SurfaceComposerClient::clearAnimationFrameStats();

    if (err < 0 && err != NO_INIT) {
        doThrowIAE(env);
    }

    // The other end is not ready, just report we failed.
    if (err == NO_INIT) {
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

static jboolean nativeGetAnimationFrameStats(JNIEnv* env, jclass clazz, jobject outStats) {
    FrameStats stats;

    status_t err = SurfaceComposerClient::getAnimationFrameStats(&stats);
    if (err < 0 && err != NO_INIT) {
        doThrowIAE(env);
    }

    // The other end is not ready, fine just return empty stats.
    if (err == NO_INIT) {
        return JNI_FALSE;
    }

    jlong refreshPeriodNano = static_cast<jlong>(stats.refreshPeriodNano);
    size_t frameCount = stats.desiredPresentTimesNano.size();

    jlongArray presentedTimesNanoDst = env->NewLongArray(frameCount);
    if (presentedTimesNanoDst == NULL) {
        return JNI_FALSE;
    }

    nsecs_t presentedTimesNanoSrc[frameCount];

    for (size_t i = 0; i < frameCount; i++) {
        nsecs_t presentedTimeNano = stats.actualPresentTimesNano[i];
        if (presentedTimeNano == INT64_MAX) {
            presentedTimeNano = gWindowContentFrameStatsClassInfo.UNDEFINED_TIME_NANO;
        }
        presentedTimesNanoSrc[i] = presentedTimeNano;
    }

    env->SetLongArrayRegion(presentedTimesNanoDst, 0, frameCount, presentedTimesNanoSrc);

    env->CallVoidMethod(outStats, gWindowAnimationFrameStatsClassInfo.init, refreshPeriodNano,
            presentedTimesNanoDst);

    if (env->ExceptionCheck()) {
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

static void nativeDeferTransactionUntil(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject, jlong barrierObject, jlong frameNumber) {
    auto ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    auto barrier = reinterpret_cast<SurfaceControl *>(barrierObject);
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    transaction->deferTransactionUntil_legacy(ctrl, barrier->getHandle(), frameNumber);
}

static void nativeDeferTransactionUntilSurface(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject,
        jlong surfaceObject, jlong frameNumber) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    auto ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    sp<Surface> barrier = reinterpret_cast<Surface *>(surfaceObject);

    transaction->deferTransactionUntil_legacy(ctrl, barrier, frameNumber);
}

static void nativeReparentChildren(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject,
        jlong newParentObject) {

    auto ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    auto newParent = reinterpret_cast<SurfaceControl *>(newParentObject);
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    transaction->reparentChildren(ctrl, newParent->getHandle());
}

static void nativeReparent(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject,
        jlong newParentObject) {
    auto ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    auto newParent = reinterpret_cast<SurfaceControl *>(newParentObject);
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    transaction->reparent(ctrl, newParent != NULL ? newParent->getHandle() : NULL);
}

static void nativeSeverChildren(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    auto ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    transaction->detachChildren(ctrl);
}

static void nativeSetOverrideScalingMode(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject,
        jint scalingMode) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    auto ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    transaction->setOverrideScalingMode(ctrl, scalingMode);
}

static jobject nativeGetHdrCapabilities(JNIEnv* env, jclass clazz, jobject tokenObject) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObject));
    if (token == NULL) return NULL;

    HdrCapabilities capabilities;
    SurfaceComposerClient::getHdrCapabilities(token, &capabilities);

    const auto& types = capabilities.getSupportedHdrTypes();
    std::vector<int32_t> intTypes;
    for (auto type : types) {
        intTypes.push_back(static_cast<int32_t>(type));
    }
    auto typesArray = env->NewIntArray(types.size());
    env->SetIntArrayRegion(typesArray, 0, intTypes.size(), intTypes.data());

    return env->NewObject(gHdrCapabilitiesClassInfo.clazz, gHdrCapabilitiesClassInfo.ctor,
            typesArray, capabilities.getDesiredMaxLuminance(),
            capabilities.getDesiredMaxAverageLuminance(), capabilities.getDesiredMinLuminance());
}

static jboolean nativeGetAutoLowLatencyModeSupport(JNIEnv* env, jclass clazz, jobject tokenObject) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObject));
    if (token == NULL) return NULL;

    return SurfaceComposerClient::getAutoLowLatencyModeSupport(token);
}

static jboolean nativeGetGameContentTypeSupport(JNIEnv* env, jclass clazz, jobject tokenObject) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObject));
    if (token == NULL) return NULL;

    return SurfaceComposerClient::getGameContentTypeSupport(token);
}

static void nativeSetAutoLowLatencyMode(JNIEnv* env, jclass clazz, jobject tokenObject, jboolean on) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObject));
    if (token == NULL) return;

    SurfaceComposerClient::setAutoLowLatencyMode(token, on);
}

static void nativeSetGameContentType(JNIEnv* env, jclass clazz, jobject tokenObject, jboolean on) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObject));
    if (token == NULL) return;

    SurfaceComposerClient::setGameContentType(token, on);
}

static jlong nativeReadFromParcel(JNIEnv* env, jclass clazz, jobject parcelObj) {
    Parcel* parcel = parcelForJavaObject(env, parcelObj);
    if (parcel == NULL) {
        doThrowNPE(env);
        return 0;
    }
    sp<SurfaceControl> surface = SurfaceControl::readFromParcel(parcel);
    if (surface == nullptr) {
        return 0;
    }
    surface->incStrong((void *)nativeCreate);
    return reinterpret_cast<jlong>(surface.get());
}

static jlong nativeCopyFromSurfaceControl(JNIEnv* env, jclass clazz, jlong surfaceControlNativeObj) {
    sp<SurfaceControl> surface(reinterpret_cast<SurfaceControl *>(surfaceControlNativeObj));
    if (surface == nullptr) {
        return 0;
    }

    sp<SurfaceControl> newSurface = new SurfaceControl(surface);
    newSurface->incStrong((void *)nativeCreate);
    return reinterpret_cast<jlong>(newSurface.get());
}

static void nativeWriteToParcel(JNIEnv* env, jclass clazz,
        jlong nativeObject, jobject parcelObj) {
    Parcel* parcel = parcelForJavaObject(env, parcelObj);
    if (parcel == NULL) {
        doThrowNPE(env);
        return;
    }
    SurfaceControl* const self = reinterpret_cast<SurfaceControl *>(nativeObject);
    if (self != nullptr) {
        self->writeToParcel(parcel);
    }
}

static jboolean nativeGetDisplayBrightnessSupport(JNIEnv* env, jclass clazz,
        jobject displayTokenObject) {
    sp<IBinder> displayToken(ibinderForJavaObject(env, displayTokenObject));
    if (displayToken == nullptr) {
        return JNI_FALSE;
    }
    return static_cast<jboolean>(SurfaceComposerClient::getDisplayBrightnessSupport(displayToken));
}

static jboolean nativeSetDisplayBrightness(JNIEnv* env, jclass clazz, jobject displayTokenObject,
        jfloat brightness) {
    sp<IBinder> displayToken(ibinderForJavaObject(env, displayTokenObject));
    if (displayToken == nullptr) {
        return JNI_FALSE;
    }
    status_t error = SurfaceComposerClient::setDisplayBrightness(displayToken, brightness);
    return error == OK ? JNI_TRUE : JNI_FALSE;
}

static void nativeWriteTransactionToParcel(JNIEnv* env, jclass clazz, jlong nativeObject,
        jobject parcelObj) {
    Parcel* parcel = parcelForJavaObject(env, parcelObj);
    if (parcel == NULL) {
        doThrowNPE(env);
        return;
    }
    SurfaceComposerClient::Transaction* const self =
            reinterpret_cast<SurfaceComposerClient::Transaction *>(nativeObject);
    if (self != nullptr) {
        self->writeToParcel(parcel);
        self->clear();
    }
}

static jlong nativeReadTransactionFromParcel(JNIEnv* env, jclass clazz, jobject parcelObj) {
    Parcel* parcel = parcelForJavaObject(env, parcelObj);
    if (parcel == NULL) {
        doThrowNPE(env);
        return 0;
    }
    std::unique_ptr<SurfaceComposerClient::Transaction> transaction =
            SurfaceComposerClient::Transaction::createFromParcel(parcel);

    return reinterpret_cast<jlong>(transaction.release());
}

static jlong nativeMirrorSurface(JNIEnv* env, jclass clazz, jlong mirrorOfObj) {
    sp<SurfaceComposerClient> client = SurfaceComposerClient::getDefault();
    SurfaceControl *mirrorOf = reinterpret_cast<SurfaceControl*>(mirrorOfObj);
    sp<SurfaceControl> surface = client->mirrorSurface(mirrorOf);

    surface->incStrong((void *)nativeCreate);
    return reinterpret_cast<jlong>(surface.get());
}

static void nativeSetGlobalShadowSettings(JNIEnv* env, jclass clazz, jfloatArray jAmbientColor,
        jfloatArray jSpotColor, jfloat lightPosY, jfloat lightPosZ, jfloat lightRadius) {
    sp<SurfaceComposerClient> client = SurfaceComposerClient::getDefault();

    float* floatAmbientColor = env->GetFloatArrayElements(jAmbientColor, 0);
    half4 ambientColor = half4(floatAmbientColor[0], floatAmbientColor[1], floatAmbientColor[2],
            floatAmbientColor[3]);
    env->ReleaseFloatArrayElements(jAmbientColor, floatAmbientColor, 0);

    float* floatSpotColor = env->GetFloatArrayElements(jSpotColor, 0);
    half4 spotColor = half4(floatSpotColor[0], floatSpotColor[1], floatSpotColor[2],
            floatSpotColor[3]);
    env->ReleaseFloatArrayElements(jSpotColor, floatSpotColor, 0);

    client->setGlobalShadowSettings(ambientColor, spotColor, lightPosY, lightPosZ, lightRadius);
}

static jlong nativeGetHandle(JNIEnv* env, jclass clazz, jlong nativeObject) {
    SurfaceControl *surfaceControl = reinterpret_cast<SurfaceControl*>(nativeObject);
    return reinterpret_cast<jlong>(surfaceControl->getHandle().get());
}

// ----------------------------------------------------------------------------

static const JNINativeMethod sSurfaceControlMethods[] = {
    {"nativeCreate", "(Landroid/view/SurfaceSession;Ljava/lang/String;IIIIJLandroid/os/Parcel;)J",
            (void*)nativeCreate },
    {"nativeReadFromParcel", "(Landroid/os/Parcel;)J",
            (void*)nativeReadFromParcel },
    {"nativeCopyFromSurfaceControl", "(J)J" ,
            (void*)nativeCopyFromSurfaceControl },
    {"nativeWriteToParcel", "(JLandroid/os/Parcel;)V",
            (void*)nativeWriteToParcel },
    {"nativeRelease", "(J)V",
            (void*)nativeRelease },
    {"nativeDisconnect", "(J)V",
            (void*)nativeDisconnect },
    {"nativeCreateTransaction", "()J",
            (void*)nativeCreateTransaction },
    {"nativeApplyTransaction", "(JZ)V",
            (void*)nativeApplyTransaction },
    {"nativeGetNativeTransactionFinalizer", "()J",
            (void*)nativeGetNativeTransactionFinalizer },
    {"nativeMergeTransaction", "(JJ)V",
            (void*)nativeMergeTransaction },
    {"nativeSetAnimationTransaction", "(J)V",
            (void*)nativeSetAnimationTransaction },
    {"nativeSetEarlyWakeup", "(J)V",
            (void*)nativeSetEarlyWakeup },
    {"nativeSetLayer", "(JJI)V",
            (void*)nativeSetLayer },
    {"nativeSetRelativeLayer", "(JJJI)V",
            (void*)nativeSetRelativeLayer },
    {"nativeSetPosition", "(JJFF)V",
            (void*)nativeSetPosition },
    {"nativeSetSize", "(JJII)V",
            (void*)nativeSetSize },
    {"nativeSetTransparentRegionHint", "(JJLandroid/graphics/Region;)V",
            (void*)nativeSetTransparentRegionHint },
    {"nativeSetAlpha", "(JJF)V",
            (void*)nativeSetAlpha },
    {"nativeSetColor", "(JJ[F)V",
            (void*)nativeSetColor },
    {"nativeSetMatrix", "(JJFFFF)V",
            (void*)nativeSetMatrix },
    {"nativeSetColorTransform", "(JJ[F[F)V",
            (void*)nativeSetColorTransform },
    {"nativeSetColorSpaceAgnostic", "(JJZ)V",
            (void*)nativeSetColorSpaceAgnostic },
    {"nativeSetFlags", "(JJII)V",
            (void*)nativeSetFlags },
    {"nativeSetFrameRateSelectionPriority", "(JJI)V",
            (void*)nativeSetFrameRateSelectionPriority },
    {"nativeSetWindowCrop", "(JJIIII)V",
            (void*)nativeSetWindowCrop },
    {"nativeSetCornerRadius", "(JJF)V",
            (void*)nativeSetCornerRadius },
    {"nativeSetBackgroundBlurRadius", "(JJI)V",
            (void*)nativeSetBackgroundBlurRadius },
    {"nativeSetLayerStack", "(JJI)V",
            (void*)nativeSetLayerStack },
    {"nativeSetShadowRadius", "(JJF)V",
            (void*)nativeSetShadowRadius },
    {"nativeSetFrameRate", "(JJFI)V",
            (void*)nativeSetFrameRate },
    {"nativeAcquireFrameRateFlexibilityToken", "()J",
            (void*)nativeAcquireFrameRateFlexibilityToken },
    {"nativeReleaseFrameRateFlexibilityToken", "(J)V",
            (void*)nativeReleaseFrameRateFlexibilityToken },
    {"nativeGetPhysicalDisplayIds", "()[J",
            (void*)nativeGetPhysicalDisplayIds },
    {"nativeGetPhysicalDisplayToken", "(J)Landroid/os/IBinder;",
            (void*)nativeGetPhysicalDisplayToken },
    {"nativeCreateDisplay", "(Ljava/lang/String;Z)Landroid/os/IBinder;",
            (void*)nativeCreateDisplay },
    {"nativeDestroyDisplay", "(Landroid/os/IBinder;)V",
            (void*)nativeDestroyDisplay },
    {"nativeSetDisplaySurface", "(JLandroid/os/IBinder;J)V",
            (void*)nativeSetDisplaySurface },
    {"nativeSetDisplayLayerStack", "(JLandroid/os/IBinder;I)V",
            (void*)nativeSetDisplayLayerStack },
    {"nativeSetDisplayProjection", "(JLandroid/os/IBinder;IIIIIIIII)V",
            (void*)nativeSetDisplayProjection },
    {"nativeSetDisplaySize", "(JLandroid/os/IBinder;II)V",
            (void*)nativeSetDisplaySize },
    {"nativeGetDisplayInfo", "(Landroid/os/IBinder;)Landroid/view/SurfaceControl$DisplayInfo;",
            (void*)nativeGetDisplayInfo },
    {"nativeGetDisplayConfigs", "(Landroid/os/IBinder;)[Landroid/view/SurfaceControl$DisplayConfig;",
            (void*)nativeGetDisplayConfigs },
    {"nativeGetActiveConfig", "(Landroid/os/IBinder;)I",
            (void*)nativeGetActiveConfig },
    {"nativeSetDesiredDisplayConfigSpecs",
            "(Landroid/os/IBinder;Landroid/view/SurfaceControl$DesiredDisplayConfigSpecs;)Z",
            (void*)nativeSetDesiredDisplayConfigSpecs },
    {"nativeGetDesiredDisplayConfigSpecs",
            "(Landroid/os/IBinder;)Landroid/view/SurfaceControl$DesiredDisplayConfigSpecs;",
            (void*)nativeGetDesiredDisplayConfigSpecs },
    {"nativeGetDisplayColorModes", "(Landroid/os/IBinder;)[I",
            (void*)nativeGetDisplayColorModes},
    {"nativeGetDisplayNativePrimaries", "(Landroid/os/IBinder;)Landroid/view/SurfaceControl$DisplayPrimaries;",
            (void*)nativeGetDisplayNativePrimaries },
    {"nativeGetActiveColorMode", "(Landroid/os/IBinder;)I",
            (void*)nativeGetActiveColorMode},
    {"nativeSetActiveColorMode", "(Landroid/os/IBinder;I)Z",
            (void*)nativeSetActiveColorMode},
    {"nativeGetAutoLowLatencyModeSupport", "(Landroid/os/IBinder;)Z",
            (void*)nativeGetAutoLowLatencyModeSupport },
    {"nativeSetAutoLowLatencyMode", "(Landroid/os/IBinder;Z)V",
            (void*)nativeSetAutoLowLatencyMode },
    {"nativeGetGameContentTypeSupport", "(Landroid/os/IBinder;)Z",
            (void*)nativeGetGameContentTypeSupport },
    {"nativeSetGameContentType", "(Landroid/os/IBinder;Z)V",
            (void*)nativeSetGameContentType },
    {"nativeGetCompositionDataspaces", "()[I",
            (void*)nativeGetCompositionDataspaces},
    {"nativeGetHdrCapabilities", "(Landroid/os/IBinder;)Landroid/view/Display$HdrCapabilities;",
            (void*)nativeGetHdrCapabilities },
    {"nativeClearContentFrameStats", "(J)Z",
            (void*)nativeClearContentFrameStats },
    {"nativeGetContentFrameStats", "(JLandroid/view/WindowContentFrameStats;)Z",
            (void*)nativeGetContentFrameStats },
    {"nativeClearAnimationFrameStats", "()Z",
            (void*)nativeClearAnimationFrameStats },
    {"nativeGetAnimationFrameStats", "(Landroid/view/WindowAnimationFrameStats;)Z",
            (void*)nativeGetAnimationFrameStats },
    {"nativeSetDisplayPowerMode", "(Landroid/os/IBinder;I)V",
            (void*)nativeSetDisplayPowerMode },
    {"nativeGetProtectedContentSupport", "()Z",
            (void*)nativeGetProtectedContentSupport },
    {"nativeDeferTransactionUntil", "(JJJJ)V",
            (void*)nativeDeferTransactionUntil },
    {"nativeDeferTransactionUntilSurface", "(JJJJ)V",
            (void*)nativeDeferTransactionUntilSurface },
    {"nativeReparentChildren", "(JJJ)V",
            (void*)nativeReparentChildren } ,
    {"nativeReparent", "(JJJ)V",
            (void*)nativeReparent },
    {"nativeSeverChildren", "(JJ)V",
            (void*)nativeSeverChildren } ,
    {"nativeSetOverrideScalingMode", "(JJI)V",
            (void*)nativeSetOverrideScalingMode },
    {"nativeScreenshot",
            "(Landroid/os/IBinder;Landroid/graphics/Rect;IIZIZ)"
            "Landroid/view/SurfaceControl$ScreenshotGraphicBuffer;",
            (void*)nativeScreenshot },
    {"nativeCaptureLayers",
            "(Landroid/os/IBinder;JLandroid/graphics/Rect;"
            "F[JI)"
            "Landroid/view/SurfaceControl$ScreenshotGraphicBuffer;",
            (void*)nativeCaptureLayers },
    {"nativeSetInputWindowInfo", "(JJLandroid/view/InputWindowHandle;)V",
            (void*)nativeSetInputWindowInfo },
    {"nativeSetMetadata", "(JJILandroid/os/Parcel;)V",
            (void*)nativeSetMetadata },
    {"nativeGetDisplayedContentSamplingAttributes",
            "(Landroid/os/IBinder;)Landroid/hardware/display/DisplayedContentSamplingAttributes;",
            (void*)nativeGetDisplayedContentSamplingAttributes },
    {"nativeSetDisplayedContentSamplingEnabled", "(Landroid/os/IBinder;ZII)Z",
            (void*)nativeSetDisplayedContentSamplingEnabled },
    {"nativeGetDisplayedContentSample",
            "(Landroid/os/IBinder;JJ)Landroid/hardware/display/DisplayedContentSample;",
            (void*)nativeGetDisplayedContentSample },
    {"nativeSetGeometry", "(JJLandroid/graphics/Rect;Landroid/graphics/Rect;J)V",
            (void*)nativeSetGeometry },
    {"nativeSyncInputWindows", "(J)V",
            (void*)nativeSyncInputWindows },
    {"nativeGetDisplayBrightnessSupport", "(Landroid/os/IBinder;)Z",
            (void*)nativeGetDisplayBrightnessSupport },
    {"nativeSetDisplayBrightness", "(Landroid/os/IBinder;F)Z",
            (void*)nativeSetDisplayBrightness },
    {"nativeReadTransactionFromParcel", "(Landroid/os/Parcel;)J",
            (void*)nativeReadTransactionFromParcel },
    {"nativeWriteTransactionToParcel", "(JLandroid/os/Parcel;)V",
            (void*)nativeWriteTransactionToParcel },
    {"nativeMirrorSurface", "(J)J",
            (void*)nativeMirrorSurface },
    {"nativeSetGlobalShadowSettings", "([F[FFFF)V",
            (void*)nativeSetGlobalShadowSettings },
    {"nativeGetHandle", "(J)J",
            (void*)nativeGetHandle },
};

int register_android_view_SurfaceControl(JNIEnv* env)
{
    int err = RegisterMethodsOrDie(env, "android/view/SurfaceControl",
            sSurfaceControlMethods, NELEM(sSurfaceControlMethods));

    jclass integerClass = FindClassOrDie(env, "java/lang/Integer");
    gIntegerClassInfo.clazz = MakeGlobalRefOrDie(env, integerClass);
    gIntegerClassInfo.ctor = GetMethodIDOrDie(env, gIntegerClassInfo.clazz, "<init>", "(I)V");

    jclass infoClazz = FindClassOrDie(env, "android/view/SurfaceControl$DisplayInfo");
    gDisplayInfoClassInfo.clazz = MakeGlobalRefOrDie(env, infoClazz);
    gDisplayInfoClassInfo.ctor = GetMethodIDOrDie(env, infoClazz, "<init>", "()V");
    gDisplayInfoClassInfo.isInternal = GetFieldIDOrDie(env, infoClazz, "isInternal", "Z");
    gDisplayInfoClassInfo.density = GetFieldIDOrDie(env, infoClazz, "density", "F");
    gDisplayInfoClassInfo.secure = GetFieldIDOrDie(env, infoClazz, "secure", "Z");
    gDisplayInfoClassInfo.deviceProductInfo =
            GetFieldIDOrDie(env, infoClazz, "deviceProductInfo",
                            "Landroid/hardware/display/DeviceProductInfo;");

    jclass configClazz = FindClassOrDie(env, "android/view/SurfaceControl$DisplayConfig");
    gDisplayConfigClassInfo.clazz = MakeGlobalRefOrDie(env, configClazz);
    gDisplayConfigClassInfo.ctor = GetMethodIDOrDie(env, configClazz, "<init>", "()V");
    gDisplayConfigClassInfo.width = GetFieldIDOrDie(env, configClazz, "width", "I");
    gDisplayConfigClassInfo.height = GetFieldIDOrDie(env, configClazz, "height", "I");
    gDisplayConfigClassInfo.xDpi = GetFieldIDOrDie(env, configClazz, "xDpi", "F");
    gDisplayConfigClassInfo.yDpi = GetFieldIDOrDie(env, configClazz, "yDpi", "F");
    gDisplayConfigClassInfo.refreshRate = GetFieldIDOrDie(env, configClazz, "refreshRate", "F");
    gDisplayConfigClassInfo.appVsyncOffsetNanos =
            GetFieldIDOrDie(env, configClazz, "appVsyncOffsetNanos", "J");
    gDisplayConfigClassInfo.presentationDeadlineNanos =
            GetFieldIDOrDie(env, configClazz, "presentationDeadlineNanos", "J");
    gDisplayConfigClassInfo.configGroup = GetFieldIDOrDie(env, configClazz, "configGroup", "I");

    jclass rectClazz = FindClassOrDie(env, "android/graphics/Rect");
    gRectClassInfo.bottom = GetFieldIDOrDie(env, rectClazz, "bottom", "I");
    gRectClassInfo.left =   GetFieldIDOrDie(env, rectClazz, "left", "I");
    gRectClassInfo.right =  GetFieldIDOrDie(env, rectClazz, "right", "I");
    gRectClassInfo.top =    GetFieldIDOrDie(env, rectClazz, "top", "I");

    jclass frameStatsClazz = FindClassOrDie(env, "android/view/FrameStats");
    jfieldID undefined_time_nano_field = GetStaticFieldIDOrDie(env,
            frameStatsClazz, "UNDEFINED_TIME_NANO", "J");
    nsecs_t undefined_time_nano = env->GetStaticLongField(frameStatsClazz, undefined_time_nano_field);

    jclass contFrameStatsClazz = FindClassOrDie(env, "android/view/WindowContentFrameStats");
    gWindowContentFrameStatsClassInfo.init = GetMethodIDOrDie(env,
            contFrameStatsClazz, "init", "(J[J[J[J)V");
    gWindowContentFrameStatsClassInfo.UNDEFINED_TIME_NANO = undefined_time_nano;

    jclass animFrameStatsClazz = FindClassOrDie(env, "android/view/WindowAnimationFrameStats");
    gWindowAnimationFrameStatsClassInfo.init =  GetMethodIDOrDie(env,
            animFrameStatsClazz, "init", "(J[J)V");
    gWindowAnimationFrameStatsClassInfo.UNDEFINED_TIME_NANO = undefined_time_nano;

    jclass hdrCapabilitiesClazz = FindClassOrDie(env, "android/view/Display$HdrCapabilities");
    gHdrCapabilitiesClassInfo.clazz = MakeGlobalRefOrDie(env, hdrCapabilitiesClazz);
    gHdrCapabilitiesClassInfo.ctor = GetMethodIDOrDie(env, hdrCapabilitiesClazz, "<init>",
            "([IFFF)V");

    jclass deviceProductInfoClazz =
            FindClassOrDie(env, "android/hardware/display/DeviceProductInfo");
    gDeviceProductInfoClassInfo.clazz = MakeGlobalRefOrDie(env, deviceProductInfoClazz);
    gDeviceProductInfoClassInfo.ctor =
            GetMethodIDOrDie(env, deviceProductInfoClazz, "<init>",
                             "(Ljava/lang/String;"
                             "Ljava/lang/String;"
                             "Ljava/lang/String;"
                             "Ljava/lang/Integer;"
                             "Landroid/hardware/display/DeviceProductInfo$ManufactureDate;"
                             "[I)V");

    jclass deviceProductInfoManufactureDateClazz =
            FindClassOrDie(env, "android/hardware/display/DeviceProductInfo$ManufactureDate");
    gDeviceProductInfoManufactureDateClassInfo.clazz =
            MakeGlobalRefOrDie(env, deviceProductInfoManufactureDateClazz);
    gDeviceProductInfoManufactureDateClassInfo.ctor =
            GetMethodIDOrDie(env, deviceProductInfoManufactureDateClazz, "<init>",
                             "(Ljava/lang/Integer;Ljava/lang/Integer;)V");

    jclass graphicsBufferClazz = FindClassOrDie(env, "android/graphics/GraphicBuffer");
    gGraphicBufferClassInfo.clazz = MakeGlobalRefOrDie(env, graphicsBufferClazz);
    gGraphicBufferClassInfo.builder = GetStaticMethodIDOrDie(env, graphicsBufferClazz,
            "createFromExisting", "(IIIIJ)Landroid/graphics/GraphicBuffer;");

    jclass screenshotGraphicsBufferClazz = FindClassOrDie(env,
            "android/view/SurfaceControl$ScreenshotGraphicBuffer");
    gScreenshotGraphicBufferClassInfo.clazz =
            MakeGlobalRefOrDie(env, screenshotGraphicsBufferClazz);
    gScreenshotGraphicBufferClassInfo.builder = GetStaticMethodIDOrDie(env,
            screenshotGraphicsBufferClazz,
            "createFromNative", "(IIIIJIZ)Landroid/view/SurfaceControl$ScreenshotGraphicBuffer;");

    jclass displayedContentSampleClazz = FindClassOrDie(env,
            "android/hardware/display/DisplayedContentSample");
    gDisplayedContentSampleClassInfo.clazz = MakeGlobalRefOrDie(env, displayedContentSampleClazz);
    gDisplayedContentSampleClassInfo.ctor = GetMethodIDOrDie(env,
            displayedContentSampleClazz, "<init>", "(J[J[J[J[J)V");

    jclass displayedContentSamplingAttributesClazz = FindClassOrDie(env,
            "android/hardware/display/DisplayedContentSamplingAttributes");
    gDisplayedContentSamplingAttributesClassInfo.clazz = MakeGlobalRefOrDie(env,
            displayedContentSamplingAttributesClazz);
    gDisplayedContentSamplingAttributesClassInfo.ctor = GetMethodIDOrDie(env,
            displayedContentSamplingAttributesClazz, "<init>", "(III)V");

    jclass cieXyzClazz = FindClassOrDie(env, "android/view/SurfaceControl$CieXyz");
    gCieXyzClassInfo.clazz = MakeGlobalRefOrDie(env, cieXyzClazz);
    gCieXyzClassInfo.ctor = GetMethodIDOrDie(env, gCieXyzClassInfo.clazz, "<init>", "()V");
    gCieXyzClassInfo.X = GetFieldIDOrDie(env, cieXyzClazz, "X", "F");
    gCieXyzClassInfo.Y = GetFieldIDOrDie(env, cieXyzClazz, "Y", "F");
    gCieXyzClassInfo.Z = GetFieldIDOrDie(env, cieXyzClazz, "Z", "F");

    jclass displayPrimariesClazz = FindClassOrDie(env,
            "android/view/SurfaceControl$DisplayPrimaries");
    gDisplayPrimariesClassInfo.clazz = MakeGlobalRefOrDie(env, displayPrimariesClazz);
    gDisplayPrimariesClassInfo.ctor = GetMethodIDOrDie(env, gDisplayPrimariesClassInfo.clazz,
            "<init>", "()V");
    gDisplayPrimariesClassInfo.red = GetFieldIDOrDie(env, displayPrimariesClazz, "red",
            "Landroid/view/SurfaceControl$CieXyz;");
    gDisplayPrimariesClassInfo.green = GetFieldIDOrDie(env, displayPrimariesClazz, "green",
            "Landroid/view/SurfaceControl$CieXyz;");
    gDisplayPrimariesClassInfo.blue = GetFieldIDOrDie(env, displayPrimariesClazz, "blue",
            "Landroid/view/SurfaceControl$CieXyz;");
    gDisplayPrimariesClassInfo.white = GetFieldIDOrDie(env, displayPrimariesClazz, "white",
            "Landroid/view/SurfaceControl$CieXyz;");

    jclass desiredDisplayConfigSpecsClazz =
            FindClassOrDie(env, "android/view/SurfaceControl$DesiredDisplayConfigSpecs");
    gDesiredDisplayConfigSpecsClassInfo.clazz =
            MakeGlobalRefOrDie(env, desiredDisplayConfigSpecsClazz);
    gDesiredDisplayConfigSpecsClassInfo.ctor =
            GetMethodIDOrDie(env, gDesiredDisplayConfigSpecsClassInfo.clazz, "<init>", "(IFFFF)V");
    gDesiredDisplayConfigSpecsClassInfo.defaultConfig =
            GetFieldIDOrDie(env, desiredDisplayConfigSpecsClazz, "defaultConfig", "I");
    gDesiredDisplayConfigSpecsClassInfo.primaryRefreshRateMin =
            GetFieldIDOrDie(env, desiredDisplayConfigSpecsClazz, "primaryRefreshRateMin", "F");
    gDesiredDisplayConfigSpecsClassInfo.primaryRefreshRateMax =
            GetFieldIDOrDie(env, desiredDisplayConfigSpecsClazz, "primaryRefreshRateMax", "F");
    gDesiredDisplayConfigSpecsClassInfo.appRequestRefreshRateMin =
            GetFieldIDOrDie(env, desiredDisplayConfigSpecsClazz, "appRequestRefreshRateMin", "F");
    gDesiredDisplayConfigSpecsClassInfo.appRequestRefreshRateMax =
            GetFieldIDOrDie(env, desiredDisplayConfigSpecsClazz, "appRequestRefreshRateMax", "F");

    return err;
}

} // namespace android
