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

#include <aidl/android/hardware/graphics/common/PixelFormat.h>
#include <android-base/chrono_utils.h>
#include <android/graphics/properties.h>
#include <android/graphics/region.h>
#include <android/gui/BnWindowInfosReportedListener.h>
#include <android/hardware/display/IDeviceProductInfoConstants.h>
#include <android/os/IInputConstants.h>
#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/android_graphics_GraphicBuffer.h>
#include <android_runtime/android_hardware_HardwareBuffer.h>
#include <android_runtime/android_hardware_OverlayProperties.h>
#include <android_runtime/android_view_Surface.h>
#include <android_runtime/android_view_SurfaceControl.h>
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
#include <ui/BlurRegion.h>
#include <ui/ConfigStoreTypes.h>
#include <ui/DeviceProductInfo.h>
#include <ui/DisplayMode.h>
#include <ui/DisplayedFrameStats.h>
#include <ui/DynamicDisplayInfo.h>
#include <ui/FrameStats.h>
#include <ui/GraphicTypes.h>
#include <ui/HdrCapabilities.h>
#include <ui/Rect.h>
#include <ui/Region.h>
#include <ui/StaticDisplayInfo.h>
#include <utils/LightRefBase.h>
#include <utils/Log.h>

#include <memory>

#include "android_hardware_input_InputWindowHandle.h"
#include "android_os_Parcel.h"
#include "android_util_Binder.h"
#include "core_jni_helpers.h"
#include "jni_common.h"

// ----------------------------------------------------------------------------

namespace android {

using gui::FocusRequest;

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
    jmethodID run;
} gRunnableClassInfo;

static struct {
    jclass clazz;
    jmethodID ctor;
    jfieldID isInternal;
    jfieldID density;
    jfieldID secure;
    jfieldID deviceProductInfo;
    jfieldID installOrientation;
} gStaticDisplayInfoClassInfo;

static struct {
    jclass clazz;
    jmethodID ctor;
    jfieldID supportedDisplayModes;
    jfieldID activeDisplayModeId;
    jfieldID renderFrameRate;
    jfieldID supportedColorModes;
    jfieldID activeColorMode;
    jfieldID hdrCapabilities;
    jfieldID autoLowLatencyModeSupported;
    jfieldID gameContentTypeSupported;
    jfieldID preferredBootDisplayMode;
} gDynamicDisplayInfoClassInfo;

static struct {
    jclass clazz;
    jmethodID ctor;
    jfieldID id;
    jfieldID width;
    jfieldID height;
    jfieldID xDpi;
    jfieldID yDpi;
    jfieldID peakRefreshRate;
    jfieldID vsyncRate;
    jfieldID appVsyncOffsetNanos;
    jfieldID presentationDeadlineNanos;
    jfieldID group;
    jfieldID supportedHdrTypes;
} gDisplayModeClassInfo;

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
    jmethodID ctor;
    jfieldID min;
    jfieldID max;
} gRefreshRateRangeClassInfo;

static struct {
    jclass clazz;
    jmethodID ctor;
    jfieldID physical;
    jfieldID render;
} gRefreshRateRangesClassInfo;

static struct {
    jclass clazz;
    jmethodID ctor;
    jfieldID defaultMode;
    jfieldID allowGroupSwitching;
    jfieldID primaryRanges;
    jfieldID appRequestRanges;
} gDesiredDisplayModeSpecsClassInfo;

static struct {
    jclass clazz;
    jmethodID onJankDataAvailable;
} gJankDataListenerClassInfo;

static struct {
    jclass clazz;
    jmethodID ctor;
} gJankDataClassInfo;

static struct {
    jclass clazz;
    jmethodID onTransactionCommitted;
} gTransactionCommittedListenerClassInfo;

static struct {
    jclass clazz;
    jmethodID accept;
} gConsumerClassInfo;

static struct {
    jclass clazz;
    jmethodID ctor;
} gTransactionStatsClassInfo;

static struct {
    jclass clazz;
    jmethodID ctor;
    jfieldID format;
    jfieldID alphaInterpretation;
} gDisplayDecorationSupportInfo;

static struct {
    jclass clazz;
    jfieldID mNativeObject;
} gTransactionClassInfo;

static struct {
    jclass clazz;
    jfieldID mNativeObject;
    jmethodID invokeReleaseCallback;
} gSurfaceControlClassInfo;

static struct {
    jclass clazz;
    jfieldID mMinAlpha;
    jfieldID mMinFractionRendered;
    jfieldID mStabilityRequirementMs;
} gTrustedPresentationThresholdsClassInfo;

static struct {
    jmethodID onTrustedPresentationChanged;
} gTrustedPresentationCallbackClassInfo;

static struct {
    jclass clazz;
    jmethodID ctor;
    jfieldID layerName;
    jfieldID bufferId;
    jfieldID frameNumber;
} gStalledTransactionInfoClassInfo;

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

class TransactionCommittedListenerWrapper {
public:
    explicit TransactionCommittedListenerWrapper(JNIEnv* env, jobject object) {
        env->GetJavaVM(&mVm);
        mTransactionCommittedListenerObject = env->NewGlobalRef(object);
        LOG_ALWAYS_FATAL_IF(!mTransactionCommittedListenerObject, "Failed to make global ref");
    }

    ~TransactionCommittedListenerWrapper() {
        getenv()->DeleteGlobalRef(mTransactionCommittedListenerObject);
    }

    void callback() {
        JNIEnv* env = getenv();
        env->CallVoidMethod(mTransactionCommittedListenerObject,
                            gTransactionCommittedListenerClassInfo.onTransactionCommitted);
        DieIfException(env, "Uncaught exception in TransactionCommittedListener.");
    }

    static void transactionCallbackThunk(void* context, nsecs_t /*latchTime*/,
                                         const sp<Fence>& /*presentFence*/,
                                         const std::vector<SurfaceControlStats>& /*stats*/) {
        TransactionCommittedListenerWrapper* listener =
                reinterpret_cast<TransactionCommittedListenerWrapper*>(context);
        listener->callback();
        delete listener;
    }

private:
    jobject mTransactionCommittedListenerObject;
    JavaVM* mVm;

    JNIEnv* getenv() {
        JNIEnv* env;
        mVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        return env;
    }
};

class TransactionCompletedListenerWrapper {
public:
    explicit TransactionCompletedListenerWrapper(JNIEnv* env, jobject object) {
        env->GetJavaVM(&mVm);
        mTransactionCompletedListenerObject = env->NewGlobalRef(object);
        LOG_ALWAYS_FATAL_IF(!mTransactionCompletedListenerObject, "Failed to make global ref");
    }

    ~TransactionCompletedListenerWrapper() {
        getenv()->DeleteGlobalRef(mTransactionCompletedListenerObject);
    }

    void callback(nsecs_t latchTime, const sp<Fence>& presentFence,
                  const std::vector<SurfaceControlStats>& /*stats*/) {
        JNIEnv* env = getenv();
        // Adding a strong reference for java SyncFence
        presentFence->incStrong(0);

        jobject stats =
                env->NewObject(gTransactionStatsClassInfo.clazz, gTransactionStatsClassInfo.ctor,
                               latchTime, presentFence.get());
        env->CallVoidMethod(mTransactionCompletedListenerObject, gConsumerClassInfo.accept, stats);
        env->DeleteLocalRef(stats);
        DieIfException(env, "Uncaught exception in TransactionCompletedListener.");
    }

    static void transactionCallbackThunk(void* context, nsecs_t latchTime,
                                         const sp<Fence>& presentFence,
                                         const std::vector<SurfaceControlStats>& stats) {
        TransactionCompletedListenerWrapper* listener =
                reinterpret_cast<TransactionCompletedListenerWrapper*>(context);
        listener->callback(latchTime, presentFence, stats);
        delete listener;
    }

private:
    jobject mTransactionCompletedListenerObject;
    JavaVM* mVm;

    JNIEnv* getenv() {
        JNIEnv* env;
        mVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        return env;
    }
};

class WindowInfosReportedListenerWrapper : public gui::BnWindowInfosReportedListener {
public:
    explicit WindowInfosReportedListenerWrapper(JNIEnv* env, jobject listener) {
        env->GetJavaVM(&mVm);
        mListener = env->NewGlobalRef(listener);
        LOG_ALWAYS_FATAL_IF(!mListener, "Failed to make global ref");
    }

    ~WindowInfosReportedListenerWrapper() {
        if (mListener) {
            getenv()->DeleteGlobalRef(mListener);
            mListener = nullptr;
        }
    }

    binder::Status onWindowInfosReported() override {
        JNIEnv* env = getenv();
        env->CallVoidMethod(mListener, gRunnableClassInfo.run);
        DieIfException(env, "Uncaught exception in WindowInfosReportedListener.");
        return binder::Status::ok();
    }

private:
    jobject mListener;
    JavaVM* mVm;

    JNIEnv* getenv() {
        JNIEnv* env;
        mVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        return env;
    }
};

class TrustedPresentationCallbackWrapper {
public:
    explicit TrustedPresentationCallbackWrapper(JNIEnv* env, jobject trustedPresentationListener) {
        env->GetJavaVM(&mVm);
        mTrustedPresentationCallback = env->NewGlobalRef(trustedPresentationListener);
        LOG_ALWAYS_FATAL_IF(!mTrustedPresentationCallback, "Failed to make global ref");
    }

    ~TrustedPresentationCallbackWrapper() {
        getenv()->DeleteGlobalRef(mTrustedPresentationCallback);
    }

    void onTrustedPresentationChanged(bool inTrustedPresentationState) {
        JNIEnv* env = getenv();
        env->CallVoidMethod(mTrustedPresentationCallback,
                            gTrustedPresentationCallbackClassInfo.onTrustedPresentationChanged,
                            inTrustedPresentationState);
        DieIfException(env, "Uncaught exception in TrustedPresentationCallback.");
    }

    void addCallbackRef(const sp<SurfaceComposerClient::PresentationCallbackRAII>& callbackRef) {
        mCallbackRef = callbackRef;
    }

    static void onTrustedPresentationChangedThunk(void* context, bool inTrustedPresentationState) {
        TrustedPresentationCallbackWrapper* listener =
                reinterpret_cast<TrustedPresentationCallbackWrapper*>(context);
        listener->onTrustedPresentationChanged(inTrustedPresentationState);
    }

private:
    jobject mTrustedPresentationCallback;
    JavaVM* mVm;
    sp<SurfaceComposerClient::PresentationCallbackRAII> mCallbackRef;

    JNIEnv* getenv() {
        JNIEnv* env;
        mVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        return env;
    }
};

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

    sp<IBinder> parentHandle;
    if (parent != nullptr) {
        parentHandle = parent->getHandle();
    }

    status_t err = client->createSurfaceChecked(String8(name.c_str()), w, h, format, &surface,
                                                flags, parentHandle, std::move(metadata));
    if (err == NAME_NOT_FOUND) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return 0;
    } else if (err != NO_ERROR) {
        jniThrowException(env, OutOfResourcesException, statusToString(err).c_str());
        return 0;
    }

    surface->incStrong((void *)nativeCreate);
    return reinterpret_cast<jlong>(surface.get());
}

static void release(SurfaceControl* ctrl) {
    ctrl->decStrong((void *)nativeCreate);
}

static jlong nativeGetNativeSurfaceControlFinalizer(JNIEnv* env, jclass clazz) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&release));
}

static void nativeDisconnect(JNIEnv* env, jclass clazz, jlong nativeObject) {
    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    if (ctrl != NULL) {
        ctrl->disconnect();
    }
}

static void nativeSetDefaultBufferSize(JNIEnv* env, jclass clazz, jlong nativeObject,
                                       jint width, jint height) {
    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    if (ctrl != NULL) {
        ctrl->updateDefaultBufferSize(width, height);
    }
}

static void nativeApplyTransaction(JNIEnv* env, jclass clazz, jlong transactionObj, jboolean sync,
                                   jboolean oneWay) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    transaction->apply(sync, oneWay);
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

static void nativeSetEarlyWakeupStart(JNIEnv* env, jclass clazz, jlong transactionObj) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    transaction->setEarlyWakeupStart();
}

static void nativeSetEarlyWakeupEnd(JNIEnv* env, jclass clazz, jlong transactionObj) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    transaction->setEarlyWakeupEnd();
}

static jlong nativeGetTransactionId(JNIEnv* env, jclass clazz, jlong transactionObj) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    return transaction->getId();
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
    transaction->setRelativeLayer(ctrl, relative, zorder);
}

static void nativeSetPosition(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject, jfloat x, jfloat y) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    transaction->setPosition(ctrl, x, y);
}

static void nativeSetScale(JNIEnv* env, jclass clazz, jlong transactionObj, jlong nativeObject,
                           jfloat xScale, jfloat yScale) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl*>(nativeObject);
    transaction->setMatrix(ctrl, xScale, 0, 0, yScale);
}

static void nativeSetGeometry(JNIEnv* env, jclass clazz, jlong transactionObj, jlong nativeObject,
        jobject sourceObj, jobject dstObj, jlong orientation) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);

    Rect source, dst;
    if (sourceObj != NULL) {
        source = JNICommon::rectFromObj(env, sourceObj);
    } else {
        source.makeInvalid();
    }
    if (dstObj != NULL) {
        dst = JNICommon::rectFromObj(env, dstObj);
    } else {
        dst.makeInvalid();
    }
    transaction->setGeometry(ctrl, source, dst, orientation);
}

class JGlobalRefHolder {
public:
    JGlobalRefHolder(JavaVM* vm, jobject object) : mVm(vm), mObject(object) {}

    virtual ~JGlobalRefHolder() {
        env()->DeleteGlobalRef(mObject);
        mObject = nullptr;
    }

    jobject object() { return mObject; }
    JavaVM* vm() { return mVm; }

    JNIEnv* env() {
        JNIEnv* env = nullptr;
        if (mVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            if (mVm->AttachCurrentThreadAsDaemon(&env, nullptr) != JNI_OK) {
                LOG_ALWAYS_FATAL("Failed to AttachCurrentThread!");
            }
        }
        return env;
    }

private:
    JGlobalRefHolder(const JGlobalRefHolder&) = delete;
    void operator=(const JGlobalRefHolder&) = delete;

    JavaVM* mVm;
    jobject mObject;
};

static ReleaseBufferCallback genReleaseCallback(JNIEnv* env, jobject releaseCallback) {
    if (releaseCallback == nullptr) return nullptr;

    JavaVM* vm = nullptr;
    LOG_ALWAYS_FATAL_IF(env->GetJavaVM(&vm) != JNI_OK, "Unable to get Java VM");
    auto globalCallbackRef =
            std::make_shared<JGlobalRefHolder>(vm, env->NewGlobalRef(releaseCallback));
    return [globalCallbackRef](const ReleaseCallbackId&, const sp<Fence>& releaseFence,
                               std::optional<uint32_t> currentMaxAcquiredBufferCount) {
        Fence* fenceCopy = releaseFence.get();
        // We need to grab an extra ref as Java's SyncFence takes ownership
        if (fenceCopy) {
            fenceCopy->incStrong(0);
        }
        globalCallbackRef->env()
                ->CallStaticVoidMethod(gSurfaceControlClassInfo.clazz,
                                       gSurfaceControlClassInfo.invokeReleaseCallback,
                                       globalCallbackRef->object(),
                                       reinterpret_cast<jlong>(fenceCopy));
    };
}

static void nativeSetBuffer(JNIEnv* env, jclass clazz, jlong transactionObj, jlong nativeObject,
                            jobject bufferObject, jlong fencePtr, jobject releaseCallback) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl*>(nativeObject);
    sp<GraphicBuffer> graphicBuffer;
    if (bufferObject != nullptr) {
        graphicBuffer = GraphicBuffer::fromAHardwareBuffer(
                android_hardware_HardwareBuffer_getNativeHardwareBuffer(env, bufferObject));
    }
    std::optional<sp<Fence>> optFence = std::nullopt;
    if (fencePtr != 0) {
        optFence = sp<Fence>{reinterpret_cast<Fence*>(fencePtr)};
    }
    transaction->setBuffer(ctrl, graphicBuffer, optFence, std::nullopt, 0 /* producerId */,
                           genReleaseCallback(env, releaseCallback));
}

static void nativeUnsetBuffer(JNIEnv* env, jclass clazz, jlong transactionObj, jlong nativeObject) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl*>(nativeObject);
    transaction->unsetBuffer(ctrl);
}

static void nativeSetBufferTransform(JNIEnv* env, jclass clazz, jlong transactionObj,
                                     jlong nativeObject, jint transform) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl*>(nativeObject);
    transaction->setTransform(ctrl, transform);
    bool transformToInverseDisplay = (NATIVE_WINDOW_TRANSFORM_INVERSE_DISPLAY & transform) ==
            NATIVE_WINDOW_TRANSFORM_INVERSE_DISPLAY;
    transaction->setTransformToDisplayInverse(ctrl, transformToInverseDisplay);
}

static void nativeSetDataSpace(JNIEnv* env, jclass clazz, jlong transactionObj, jlong nativeObject,
                               jint dataSpace) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl*>(nativeObject);
    ui::Dataspace dataspace = static_cast<ui::Dataspace>(dataSpace);
    transaction->setDataspace(ctrl, dataspace);
}

static void nativeSetExtendedRangeBrightness(JNIEnv* env, jclass clazz, jlong transactionObj,
                                             jlong nativeObject, float currentBufferRatio,
                                             float desiredRatio) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl*>(nativeObject);
    transaction->setExtendedRangeBrightness(ctrl, currentBufferRatio, desiredRatio);
}

static void nativeSetCachingHint(JNIEnv* env, jclass clazz, jlong transactionObj,
                                 jlong nativeObject, jint cachingHint) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl*>(nativeObject);
    transaction->setCachingHint(ctrl, static_cast<gui::CachingHint>(cachingHint));
}

static void nativeSetBlurRegions(JNIEnv* env, jclass clazz, jlong transactionObj,
                                 jlong nativeObject, jobjectArray regions, jint regionsLength) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl*>(nativeObject);

    std::vector<BlurRegion> blurRegionVector;
    const int size = regionsLength;
    float region[10];
    for (int i = 0; i < size; i++) {
        jfloatArray regionArray = (jfloatArray)env->GetObjectArrayElement(regions, i);
        env->GetFloatArrayRegion(regionArray, 0, 10, region);
        float blurRadius = region[0];
        float alpha = region[1];
        float left = region[2];
        float top = region[3];
        float right = region[4];
        float bottom = region[5];
        float cornerRadiusTL = region[6];
        float cornerRadiusTR = region[7];
        float cornerRadiusBL = region[8];
        float cornerRadiusBR = region[9];

        blurRegionVector.push_back(BlurRegion{.blurRadius = static_cast<uint32_t>(blurRadius),
                                              .cornerRadiusTL = cornerRadiusTL,
                                              .cornerRadiusTR = cornerRadiusTR,
                                              .cornerRadiusBL = cornerRadiusBL,
                                              .cornerRadiusBR = cornerRadiusBR,
                                              .alpha = alpha,
                                              .left = static_cast<int>(left),
                                              .top = static_cast<int>(top),
                                              .right = static_cast<int>(right),
                                              .bottom = static_cast<int>(bottom)});
    }

    transaction->setBlurRegions(ctrl, blurRegionVector);
}

static void nativeSetStretchEffect(JNIEnv* env, jclass clazz, jlong transactionObj,
                                   jlong nativeObject, jfloat width, jfloat height,
                                   jfloat vecX, jfloat vecY,
                                   jfloat maxStretchAmountX, jfloat maxStretchAmountY,
                                   jfloat childRelativeLeft, jfloat childRelativeTop,
                                   jfloat childRelativeRight, jfloat childRelativeBottom) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    auto* const ctrl = reinterpret_cast<SurfaceControl*>(nativeObject);
    auto stretch = StretchEffect{
      .width = width,
      .height = height,
      .vectorX = vecX,
      .vectorY = vecY,
      .maxAmountX = maxStretchAmountX,
      .maxAmountY = maxStretchAmountY,
      .mappedChildBounds = FloatRect(
          childRelativeLeft, childRelativeTop, childRelativeRight, childRelativeBottom)
    };
    transaction->setStretchEffect(ctrl, stretch);
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

static void nativeSetDamageRegion(JNIEnv* env, jclass clazz, jlong transactionObj,
                                  jlong nativeObject, jobject regionObj) {
    SurfaceControl* const surfaceControl = reinterpret_cast<SurfaceControl*>(nativeObject);
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    if (regionObj == nullptr) {
        transaction->setSurfaceDamageRegion(surfaceControl, Region::INVALID_REGION);
        return;
    }

    graphics::RegionIterator iterator(env, regionObj);
    if (!iterator.isValid()) {
        transaction->setSurfaceDamageRegion(surfaceControl, Region::INVALID_REGION);
        return;
    }

    Region region;
    while (!iterator.isDone()) {
        ARect rect = iterator.getRect();
        region.orSelf(static_cast<const Rect&>(rect));
        iterator.next();
    }

    if (region.getBounds().isEmpty()) {
        transaction->setSurfaceDamageRegion(surfaceControl, Region::INVALID_REGION);
        return;
    }

    transaction->setSurfaceDamageRegion(surfaceControl, region);
}

static void nativeSetDimmingEnabled(JNIEnv* env, jclass clazz, jlong transactionObj,
                                    jlong nativeObject, jboolean dimmingEnabled) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl*>(nativeObject);
    transaction->setDimmingEnabled(ctrl, dimmingEnabled);
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

static void nativeAddWindowInfosReportedListener(JNIEnv* env, jclass clazz, jlong transactionObj,
                                                 jobject runnable) {
    auto listener = sp<WindowInfosReportedListenerWrapper>::make(env, runnable);
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    transaction->addWindowInfosReportedListener(listener);
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
    transaction->setCrop(ctrl, crop);
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
    transaction->setLayerStack(ctrl, ui::LayerStack::fromValue(layerStack));
}

static void nativeSetShadowRadius(JNIEnv* env, jclass clazz, jlong transactionObj,
         jlong nativeObject, jfloat shadowRadius) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    const auto ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    transaction->setShadowRadius(ctrl, shadowRadius);
}

static void nativeSetTrustedOverlay(JNIEnv* env, jclass clazz, jlong transactionObj,
                                    jlong nativeObject, jboolean isTrustedOverlay) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    transaction->setTrustedOverlay(ctrl, isTrustedOverlay);
}

static void nativeSetFrameRate(JNIEnv* env, jclass clazz, jlong transactionObj, jlong nativeObject,
                               jfloat frameRate, jint compatibility, jint changeFrameRateStrategy) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    const auto ctrl = reinterpret_cast<SurfaceControl*>(nativeObject);
    // Our compatibility is a Surface.FRAME_RATE_COMPATIBILITY_* value, and
    // Transaction::setFrameRate() takes an ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_* value. The
    // values are identical though, so no need to convert anything.
    transaction->setFrameRate(ctrl, frameRate, static_cast<int8_t>(compatibility),
                              static_cast<int8_t>(changeFrameRateStrategy));
}

static void nativeSetDefaultFrameRateCompatibility(JNIEnv* env, jclass clazz, jlong transactionObj,
                                                   jlong nativeObject, jint compatibility) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    const auto ctrl = reinterpret_cast<SurfaceControl*>(nativeObject);

    transaction->setDefaultFrameRateCompatibility(ctrl, static_cast<int8_t>(compatibility));
}

static void nativeSetFrameRateCategory(JNIEnv* env, jclass clazz, jlong transactionObj,
                                       jlong nativeObject, jint category,
                                       jboolean smoothSwitchOnly) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    const auto ctrl = reinterpret_cast<SurfaceControl*>(nativeObject);
    transaction->setFrameRateCategory(ctrl, static_cast<int8_t>(category), smoothSwitchOnly);
}

static void nativeSetFrameRateSelectionStrategy(JNIEnv* env, jclass clazz, jlong transactionObj,
                                                jlong nativeObject, jint strategy) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    const auto ctrl = reinterpret_cast<SurfaceControl*>(nativeObject);
    transaction->setFrameRateSelectionStrategy(ctrl, static_cast<int8_t>(strategy));
}

static void nativeSetFixedTransformHint(JNIEnv* env, jclass clazz, jlong transactionObj,
                                        jlong nativeObject, jint transformHint) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl*>(nativeObject);
    transaction->setFixedTransformHint(ctrl, transformHint);
}

static void nativeSetDropInputMode(JNIEnv* env, jclass clazz, jlong transactionObj,
                                   jlong nativeObject, jint mode) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl*>(nativeObject);
    transaction->setDropInputMode(ctrl, static_cast<gui::DropInputMode>(mode));
}

static void nativeSurfaceFlushJankData(JNIEnv* env, jclass clazz, jlong nativeObject) {
    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl*>(nativeObject);
    SurfaceComposerClient::Transaction::sendSurfaceFlushJankDataTransaction(ctrl);
}

static void nativeSanitize(JNIEnv* env, jclass clazz, jlong transactionObj, jint pid, jint uid) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    transaction->sanitize(pid, uid);
}

static void nativeSetDestinationFrame(JNIEnv* env, jclass clazz, jlong transactionObj,
                                      jlong nativeObject, jint l, jint t, jint r, jint b) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl*>(nativeObject);
    Rect crop(l, t, r, b);
    transaction->setDestinationFrame(ctrl, crop);
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
        transaction->setDisplayLayerStack(token, ui::LayerStack::fromValue(layerStack));
    }
}

static void nativeSetDisplayFlags(JNIEnv* env, jclass clazz, jlong transactionObj, jobject tokenObj,
                                  jint flags) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == NULL) return;

    {
        auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
        transaction->setDisplayFlags(token, flags);
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
    jint connectionToSinkType;
    // Relative address maps to HDMI physical address. All addresses are 4 digits long allowing
    // for a 5–device-deep hierarchy. For more information, refer:
    // Section 8.7 - Physical Address of HDMI Specification Version 1.3a
    using android::hardware::display::IDeviceProductInfoConstants;
    if (info->relativeAddress.size() != 4) {
        connectionToSinkType = IDeviceProductInfoConstants::CONNECTION_TO_SINK_UNKNOWN;
    } else if (info->relativeAddress[0] == 0) {
        connectionToSinkType = IDeviceProductInfoConstants::CONNECTION_TO_SINK_BUILT_IN;
    } else if (info->relativeAddress[1] == 0) {
        connectionToSinkType = IDeviceProductInfoConstants::CONNECTION_TO_SINK_DIRECT;
    } else {
        connectionToSinkType = IDeviceProductInfoConstants::CONNECTION_TO_SINK_TRANSITIVE;
    }

    return env->NewObject(gDeviceProductInfoClassInfo.clazz, gDeviceProductInfoClassInfo.ctor, name,
                          manufacturerPnpId, productId, modelYear, manufactureDate,
                          connectionToSinkType);
}

static jobject nativeGetStaticDisplayInfo(JNIEnv* env, jclass clazz, jlong id) {
    ui::StaticDisplayInfo info;
    if (SurfaceComposerClient::getStaticDisplayInfo(id, &info) != NO_ERROR) {
        return nullptr;
    }

    jobject object =
            env->NewObject(gStaticDisplayInfoClassInfo.clazz, gStaticDisplayInfoClassInfo.ctor);
    env->SetBooleanField(object, gStaticDisplayInfoClassInfo.isInternal,
                         info.connectionType == ui::DisplayConnectionType::Internal);
    env->SetFloatField(object, gStaticDisplayInfoClassInfo.density, info.density);
    env->SetBooleanField(object, gStaticDisplayInfoClassInfo.secure, info.secure);
    env->SetObjectField(object, gStaticDisplayInfoClassInfo.deviceProductInfo,
                        convertDeviceProductInfoToJavaObject(env, info.deviceProductInfo));
    env->SetIntField(object, gStaticDisplayInfoClassInfo.installOrientation,
                     static_cast<uint32_t>(info.installOrientation));
    return object;
}

static jobject convertDisplayModeToJavaObject(JNIEnv* env, const ui::DisplayMode& config) {
    jobject object = env->NewObject(gDisplayModeClassInfo.clazz, gDisplayModeClassInfo.ctor);
    env->SetIntField(object, gDisplayModeClassInfo.id, config.id);
    env->SetIntField(object, gDisplayModeClassInfo.width, config.resolution.getWidth());
    env->SetIntField(object, gDisplayModeClassInfo.height, config.resolution.getHeight());
    env->SetFloatField(object, gDisplayModeClassInfo.xDpi, config.xDpi);
    env->SetFloatField(object, gDisplayModeClassInfo.yDpi, config.yDpi);

    env->SetFloatField(object, gDisplayModeClassInfo.peakRefreshRate, config.peakRefreshRate);
    env->SetFloatField(object, gDisplayModeClassInfo.vsyncRate, config.vsyncRate);
    env->SetLongField(object, gDisplayModeClassInfo.appVsyncOffsetNanos, config.appVsyncOffset);
    env->SetLongField(object, gDisplayModeClassInfo.presentationDeadlineNanos,
                      config.presentationDeadline);
    env->SetIntField(object, gDisplayModeClassInfo.group, config.group);

    const auto& types = config.supportedHdrTypes;
    std::vector<jint> intTypes;
    for (auto type : types) {
        intTypes.push_back(static_cast<jint>(type));
    }
    auto typesArray = env->NewIntArray(types.size());
    env->SetIntArrayRegion(typesArray, 0, intTypes.size(), intTypes.data());
    env->SetObjectField(object, gDisplayModeClassInfo.supportedHdrTypes, typesArray);

    return object;
}

jobject convertHdrCapabilitiesToJavaObject(JNIEnv* env, const HdrCapabilities& capabilities) {
    const auto& types = capabilities.getSupportedHdrTypes();
    std::vector<int32_t> intTypes;
    for (auto type : types) {
        intTypes.push_back(static_cast<int32_t>(type));
    }
    auto typesArray = env->NewIntArray(types.size());
    env->SetIntArrayRegion(typesArray, 0, intTypes.size(), intTypes.data());

    return env->NewObject(gHdrCapabilitiesClassInfo.clazz, gHdrCapabilitiesClassInfo.ctor,
                          typesArray, capabilities.getDesiredMaxLuminance(),
                          capabilities.getDesiredMaxAverageLuminance(),
                          capabilities.getDesiredMinLuminance());
}

static jobject nativeGetDynamicDisplayInfo(JNIEnv* env, jclass clazz, jlong displayId) {
    ui::DynamicDisplayInfo info;
    if (SurfaceComposerClient::getDynamicDisplayInfoFromId(displayId, &info) != NO_ERROR) {
        return nullptr;
    }

    jobject object =
            env->NewObject(gDynamicDisplayInfoClassInfo.clazz, gDynamicDisplayInfoClassInfo.ctor);
    if (object == NULL) {
        jniThrowException(env, "java/lang/OutOfMemoryError", NULL);
        return NULL;
    }

    const auto numModes = info.supportedDisplayModes.size();
    jobjectArray modesArray = env->NewObjectArray(numModes, gDisplayModeClassInfo.clazz, nullptr);
    for (size_t i = 0; i < numModes; i++) {
        const ui::DisplayMode& mode = info.supportedDisplayModes[i];
        jobject displayModeObj = convertDisplayModeToJavaObject(env, mode);
        env->SetObjectArrayElement(modesArray, static_cast<jsize>(i), displayModeObj);
        env->DeleteLocalRef(displayModeObj);
    }
    env->SetObjectField(object, gDynamicDisplayInfoClassInfo.supportedDisplayModes, modesArray);
    env->SetIntField(object, gDynamicDisplayInfoClassInfo.activeDisplayModeId,
                     info.activeDisplayModeId);
    env->SetFloatField(object, gDynamicDisplayInfoClassInfo.renderFrameRate, info.renderFrameRate);

    jintArray colorModesArray = env->NewIntArray(info.supportedColorModes.size());
    if (colorModesArray == NULL) {
        jniThrowException(env, "java/lang/OutOfMemoryError", NULL);
        return NULL;
    }
    jint* colorModesArrayValues = env->GetIntArrayElements(colorModesArray, 0);
    for (size_t i = 0; i < info.supportedColorModes.size(); i++) {
        colorModesArrayValues[i] = static_cast<jint>(info.supportedColorModes[i]);
    }
    env->ReleaseIntArrayElements(colorModesArray, colorModesArrayValues, 0);
    env->SetObjectField(object, gDynamicDisplayInfoClassInfo.supportedColorModes, colorModesArray);

    env->SetIntField(object, gDynamicDisplayInfoClassInfo.activeColorMode,
                     static_cast<jint>(info.activeColorMode));

    env->SetObjectField(object, gDynamicDisplayInfoClassInfo.hdrCapabilities,
                        convertHdrCapabilitiesToJavaObject(env, info.hdrCapabilities));

    env->SetBooleanField(object, gDynamicDisplayInfoClassInfo.autoLowLatencyModeSupported,
                         info.autoLowLatencyModeSupported);

    env->SetBooleanField(object, gDynamicDisplayInfoClassInfo.gameContentTypeSupported,
                         info.gameContentTypeSupported);

    env->SetIntField(object, gDynamicDisplayInfoClassInfo.preferredBootDisplayMode,
                     info.preferredBootDisplayMode);
    return object;
}

static jboolean nativeSetDesiredDisplayModeSpecs(JNIEnv* env, jclass clazz, jobject tokenObj,
                                                 jobject DesiredDisplayModeSpecs) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == nullptr) return JNI_FALSE;

    const auto makeRanges = [env](jobject obj) {
        const auto makeRange = [env](jobject obj) {
            gui::DisplayModeSpecs::RefreshRateRanges::RefreshRateRange range;
            range.min = env->GetFloatField(obj, gRefreshRateRangeClassInfo.min);
            range.max = env->GetFloatField(obj, gRefreshRateRangeClassInfo.max);
            return range;
        };

        gui::DisplayModeSpecs::RefreshRateRanges ranges;
        ranges.physical = makeRange(env->GetObjectField(obj, gRefreshRateRangesClassInfo.physical));
        ranges.render = makeRange(env->GetObjectField(obj, gRefreshRateRangesClassInfo.render));
        return ranges;
    };

    gui::DisplayModeSpecs specs;
    specs.defaultMode = env->GetIntField(DesiredDisplayModeSpecs,
                                         gDesiredDisplayModeSpecsClassInfo.defaultMode);
    specs.allowGroupSwitching =
            env->GetBooleanField(DesiredDisplayModeSpecs,
                                 gDesiredDisplayModeSpecsClassInfo.allowGroupSwitching);

    specs.primaryRanges =
            makeRanges(env->GetObjectField(DesiredDisplayModeSpecs,
                                           gDesiredDisplayModeSpecsClassInfo.primaryRanges));
    specs.appRequestRanges =
            makeRanges(env->GetObjectField(DesiredDisplayModeSpecs,
                                           gDesiredDisplayModeSpecsClassInfo.appRequestRanges));

    size_t result = SurfaceComposerClient::setDesiredDisplayModeSpecs(token, specs);
    return result == NO_ERROR ? JNI_TRUE : JNI_FALSE;
}

static jobject nativeGetDesiredDisplayModeSpecs(JNIEnv* env, jclass clazz, jobject tokenObj) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == nullptr) return nullptr;

    const auto rangesToJava = [env](const gui::DisplayModeSpecs::RefreshRateRanges& ranges) {
        const auto rangeToJava =
                [env](const gui::DisplayModeSpecs::RefreshRateRanges::RefreshRateRange& range) {
                    return env->NewObject(gRefreshRateRangeClassInfo.clazz,
                                          gRefreshRateRangeClassInfo.ctor, range.min, range.max);
                };

        return env->NewObject(gRefreshRateRangesClassInfo.clazz, gRefreshRateRangesClassInfo.ctor,
                              rangeToJava(ranges.physical), rangeToJava(ranges.render));
    };

    gui::DisplayModeSpecs specs;
    if (SurfaceComposerClient::getDesiredDisplayModeSpecs(token, &specs) != NO_ERROR) {
        return nullptr;
    }

    return env->NewObject(gDesiredDisplayModeSpecsClassInfo.clazz,
                          gDesiredDisplayModeSpecsClassInfo.ctor, specs.defaultMode,
                          specs.allowGroupSwitching, rangesToJava(specs.primaryRanges),
                          rangesToJava(specs.appRequestRanges));
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

static jobject nativeGetOverlaySupport(JNIEnv* env, jclass) {
    gui::OverlayProperties* overlayProperties = new gui::OverlayProperties;
    if (SurfaceComposerClient::getOverlaySupport(overlayProperties) != NO_ERROR) {
        delete overlayProperties;
        return nullptr;
    }
    return android_hardware_OverlayProperties_convertToJavaObject(env, overlayProperties);
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

static void nativeReparent(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject,
        jlong newParentObject) {
    auto ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    auto newParent = reinterpret_cast<SurfaceControl *>(newParentObject);
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    transaction->reparent(ctrl, newParent);
}

static jboolean nativeGetBootDisplayModeSupport(JNIEnv* env, jclass clazz) {
    bool isBootDisplayModeSupported = false;
    SurfaceComposerClient::getBootDisplayModeSupport(&isBootDisplayModeSupported);
    return static_cast<jboolean>(isBootDisplayModeSupported);
}

static void nativeSetBootDisplayMode(JNIEnv* env, jclass clazz, jobject tokenObject,
                                     jint displayModId) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObject));
    if (token == NULL) return;

    SurfaceComposerClient::setBootDisplayMode(token, displayModId);
}

static void nativeClearBootDisplayMode(JNIEnv* env, jclass clazz, jobject tokenObject) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObject));
    if (token == NULL) return;

    SurfaceComposerClient::clearBootDisplayMode(token);
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
    sp<SurfaceControl> surface;
    SurfaceControl::readFromParcel(*parcel, &surface);
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
        self->writeToParcel(*parcel);
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
                                           jfloat sdrBrightness, jfloat sdrBrightnessNits,
                                           jfloat displayBrightness, jfloat displayBrightnessNits) {
    sp<IBinder> displayToken(ibinderForJavaObject(env, displayTokenObject));
    if (displayToken == nullptr) {
        return JNI_FALSE;
    }
    gui::DisplayBrightness brightness;
    brightness.sdrWhitePoint = sdrBrightness;
    brightness.sdrWhitePointNits = sdrBrightnessNits;
    brightness.displayBrightness = displayBrightness;
    brightness.displayBrightnessNits = displayBrightnessNits;
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
    }
}

static void nativeClearTransaction(JNIEnv* env, jclass clazz, jlong nativeObject) {
    SurfaceComposerClient::Transaction* const self =
            reinterpret_cast<SurfaceComposerClient::Transaction*>(nativeObject);
    if (self != nullptr) {
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

static jobject nativeGetDisplayDecorationSupport(JNIEnv* env, jclass clazz,
                                                 jobject displayTokenObject) {
    sp<IBinder> displayToken(ibinderForJavaObject(env, displayTokenObject));
    if (displayToken == nullptr) {
        return nullptr;
    }
    const auto support = SurfaceComposerClient::getDisplayDecorationSupport(displayToken);
    if (!support) {
        return nullptr;
    }

    using aidl::android::hardware::graphics::common::PixelFormat;
    if (support.value().format == PixelFormat::R_8 && !hwui_uses_vulkan()) {
        return nullptr;
    }

    jobject jDisplayDecorationSupport =
            env->NewObject(gDisplayDecorationSupportInfo.clazz, gDisplayDecorationSupportInfo.ctor);
    if (jDisplayDecorationSupport == nullptr) {
        jniThrowException(env, "java/lang/OutOfMemoryError", nullptr);
        return nullptr;
    }

    env->SetIntField(jDisplayDecorationSupport, gDisplayDecorationSupportInfo.format,
                     static_cast<jint>(support.value().format));
    env->SetIntField(jDisplayDecorationSupport, gDisplayDecorationSupportInfo.alphaInterpretation,
                     static_cast<jint>(support.value().alphaInterpretation));
    return jDisplayDecorationSupport;
}

static jlong nativeGetHandle(JNIEnv* env, jclass clazz, jlong nativeObject) {
    SurfaceControl *surfaceControl = reinterpret_cast<SurfaceControl*>(nativeObject);
    return reinterpret_cast<jlong>(surfaceControl->getHandle().get());
}

static void nativeRemoveCurrentInputFocus(JNIEnv* env, jclass clazz, jlong transactionObj,
                                          jint displayId) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    FocusRequest request;
    request.timestamp = systemTime(SYSTEM_TIME_MONOTONIC);
    request.displayId = displayId;
    request.windowName = "<null>";
    transaction->setFocusedWindow(request);
}

static void nativeSetFocusedWindow(JNIEnv* env, jclass clazz, jlong transactionObj,
                                   jobject toTokenObj, jstring windowNameJstr, jint displayId) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    if (toTokenObj == NULL) return;

    sp<IBinder> toToken(ibinderForJavaObject(env, toTokenObj));

    FocusRequest request;
    request.token = toToken;
    if (windowNameJstr != NULL) {
        ScopedUtfChars windowName(env, windowNameJstr);
        request.windowName = windowName.c_str();
    }

    request.timestamp = systemTime(SYSTEM_TIME_MONOTONIC);
    request.displayId = displayId;
    transaction->setFocusedWindow(request);
}

static void nativeSetFrameTimelineVsync(JNIEnv* env, jclass clazz, jlong transactionObj,
                                        jlong frameTimelineVsyncId) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    FrameTimelineInfo ftInfo;
    ftInfo.vsyncId = frameTimelineVsyncId;
    transaction->setFrameTimelineInfo(ftInfo);
}

static void nativeSetDesiredPresentTime(JNIEnv* env, jclass clazz, jlong transactionObj,
                                        jlong desiredPresentTime) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    transaction->setDesiredPresentTime(desiredPresentTime);
}

static void nativeAddTransactionCommittedListener(JNIEnv* env, jclass clazz, jlong transactionObj,
                                                  jobject transactionCommittedListenerObject) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    void* context =
            new TransactionCommittedListenerWrapper(env, transactionCommittedListenerObject);
    transaction->addTransactionCommittedCallback(TransactionCommittedListenerWrapper::
                                                         transactionCallbackThunk,
                                                 context);
}

static void nativeAddTransactionCompletedListener(JNIEnv* env, jclass clazz, jlong transactionObj,
                                                  jobject transactionCompletedListenerObject) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    void* context =
            new TransactionCompletedListenerWrapper(env, transactionCompletedListenerObject);
    transaction->addTransactionCompletedCallback(TransactionCompletedListenerWrapper::
                                                         transactionCallbackThunk,
                                                 context);
}

static void nativeSetTrustedPresentationCallback(JNIEnv* env, jclass clazz, jlong transactionObj,
                                                 jlong nativeObject,
                                                 jlong trustedPresentationCallbackObject,
                                                 jobject trustedPresentationThresholds) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    auto ctrl = reinterpret_cast<SurfaceControl*>(nativeObject);

    TrustedPresentationThresholds thresholds;
    thresholds.minAlpha = env->GetFloatField(trustedPresentationThresholds,
                                             gTrustedPresentationThresholdsClassInfo.mMinAlpha);
    thresholds.minFractionRendered =
            env->GetFloatField(trustedPresentationThresholds,
                               gTrustedPresentationThresholdsClassInfo.mMinFractionRendered);
    thresholds.stabilityRequirementMs =
            env->GetIntField(trustedPresentationThresholds,
                             gTrustedPresentationThresholdsClassInfo.mStabilityRequirementMs);

    sp<SurfaceComposerClient::PresentationCallbackRAII> callbackRef;
    TrustedPresentationCallbackWrapper* wrapper =
            reinterpret_cast<TrustedPresentationCallbackWrapper*>(
                    trustedPresentationCallbackObject);
    transaction->setTrustedPresentationCallback(ctrl,
                                                TrustedPresentationCallbackWrapper::
                                                        onTrustedPresentationChangedThunk,
                                                thresholds, wrapper, callbackRef);
    wrapper->addCallbackRef(callbackRef);
}

static void nativeClearTrustedPresentationCallback(JNIEnv* env, jclass clazz, jlong transactionObj,
                                                   jlong nativeObject) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    auto ctrl = reinterpret_cast<SurfaceControl*>(nativeObject);

    transaction->clearTrustedPresentationCallback(ctrl);
}

class JankDataListenerWrapper : public JankDataListener {
public:
    JankDataListenerWrapper(JNIEnv* env, jobject onJankDataListenerObject) {
        mOnJankDataListenerWeak = env->NewWeakGlobalRef(onJankDataListenerObject);
        env->GetJavaVM(&mVm);
    }

    ~JankDataListenerWrapper() {
        JNIEnv* env = getEnv();
        env->DeleteWeakGlobalRef(mOnJankDataListenerWeak);
    }

    void onJankDataAvailable(const std::vector<JankData>& jankData) {
        JNIEnv* env = getEnv();

        jobject target = env->NewLocalRef(mOnJankDataListenerWeak);
        if (target == nullptr) return;

        jobjectArray jJankDataArray = env->NewObjectArray(jankData.size(),
                gJankDataClassInfo.clazz, nullptr);
        for (size_t i = 0; i < jankData.size(); i++) {
            jobject jJankData = env->NewObject(gJankDataClassInfo.clazz, gJankDataClassInfo.ctor,
                                               jankData[i].frameVsyncId, jankData[i].jankType,
                                               jankData[i].frameIntervalNs);
            env->SetObjectArrayElement(jJankDataArray, i, jJankData);
            env->DeleteLocalRef(jJankData);
        }
        env->CallVoidMethod(target,
                gJankDataListenerClassInfo.onJankDataAvailable,
                jJankDataArray);
        env->DeleteLocalRef(jJankDataArray);
        env->DeleteLocalRef(target);
    }

private:

    JNIEnv* getEnv() {
        JNIEnv* env;
        mVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        return env;
    }

    JavaVM* mVm;
    jobject mOnJankDataListenerWeak;
};

static void nativeAddJankDataListener(JNIEnv* env, jclass clazz,
                                       jlong jankDataCallbackListenerPtr,
                                       jlong nativeSurfaceControl) {
    sp<SurfaceControl> surface(reinterpret_cast<SurfaceControl *>(nativeSurfaceControl));
    if (surface == nullptr) {
        return;
    }
    sp<JankDataListenerWrapper> wrapper =
            reinterpret_cast<JankDataListenerWrapper*>(jankDataCallbackListenerPtr);
    TransactionCompletedListener::getInstance()->addJankListener(wrapper, surface);
}

static void nativeRemoveJankDataListener(JNIEnv* env, jclass clazz,
                                          jlong jankDataCallbackListenerPtr) {
    sp<JankDataListenerWrapper> wrapper =
            reinterpret_cast<JankDataListenerWrapper*>(jankDataCallbackListenerPtr);
    TransactionCompletedListener::getInstance()->removeJankListener(wrapper);
}

static jlong nativeCreateJankDataListenerWrapper(JNIEnv* env, jclass clazz,
                                                 jobject jankDataListenerObject) {
    return reinterpret_cast<jlong>(
            new JankDataListenerWrapper(env, jankDataListenerObject));
}

static jint nativeGetGPUContextPriority(JNIEnv* env, jclass clazz) {
    return static_cast<jint>(SurfaceComposerClient::getGpuContextPriority());
}

static void nativeSetTransformHint(JNIEnv* env, jclass clazz, jlong nativeSurfaceControl,
                                   jint transformHint) {
    sp<SurfaceControl> surface(reinterpret_cast<SurfaceControl*>(nativeSurfaceControl));
    if (surface == nullptr) {
        return;
    }
    surface->setTransformHint(transformHint);
}

static jint nativeGetTransformHint(JNIEnv* env, jclass clazz, jlong nativeSurfaceControl) {
    sp<SurfaceControl> surface(reinterpret_cast<SurfaceControl*>(nativeSurfaceControl));
    return surface->getTransformHint();
}

static jint nativeGetLayerId(JNIEnv* env, jclass clazz, jlong nativeSurfaceControl) {
    sp<SurfaceControl> surface(reinterpret_cast<SurfaceControl*>(nativeSurfaceControl));

    return surface->getLayerId();
}

static void nativeSetDefaultApplyToken(JNIEnv* env, jclass clazz, jobject applyToken) {
    sp<IBinder> token(ibinderForJavaObject(env, applyToken));
    if (token == nullptr) {
        ALOGE("Null apply token provided.");
        return;
    }
    SurfaceComposerClient::Transaction::setDefaultApplyToken(token);
}

static jobject nativeGetDefaultApplyToken(JNIEnv* env, jclass clazz) {
    sp<IBinder> token = SurfaceComposerClient::Transaction::getDefaultApplyToken();
    return javaObjectForIBinder(env, token);
}

static jboolean nativeBootFinished(JNIEnv* env, jclass clazz) {
    status_t error = SurfaceComposerClient::bootFinished();
    return error == OK ? JNI_TRUE : JNI_FALSE;
}

jlong nativeCreateTpc(JNIEnv* env, jclass clazz, jobject trustedPresentationCallback) {
    return reinterpret_cast<jlong>(
            new TrustedPresentationCallbackWrapper(env, trustedPresentationCallback));
}

void destroyNativeTpc(void* ptr) {
    TrustedPresentationCallbackWrapper* callback =
            reinterpret_cast<TrustedPresentationCallbackWrapper*>(ptr);
    delete callback;
}

static jlong getNativeTrustedPresentationCallbackFinalizer(JNIEnv* env, jclass clazz) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&destroyNativeTpc));
}

static jobject nativeGetStalledTransactionInfo(JNIEnv* env, jclass clazz, jint pid) {
    std::optional<gui::StalledTransactionInfo> stalledTransactionInfo =
            SurfaceComposerClient::getStalledTransactionInfo(pid);
    if (!stalledTransactionInfo) {
        return nullptr;
    }

    jobject jStalledTransactionInfo = env->NewObject(gStalledTransactionInfoClassInfo.clazz,
                                                     gStalledTransactionInfoClassInfo.ctor);
    if (!jStalledTransactionInfo) {
        jniThrowException(env, "java/lang/OutOfMemoryError", nullptr);
        return nullptr;
    }

    env->SetObjectField(jStalledTransactionInfo, gStalledTransactionInfoClassInfo.layerName,
                        env->NewStringUTF(String8{stalledTransactionInfo->layerName}));
    env->SetLongField(jStalledTransactionInfo, gStalledTransactionInfoClassInfo.bufferId,
                      static_cast<jlong>(stalledTransactionInfo->bufferId));
    env->SetLongField(jStalledTransactionInfo, gStalledTransactionInfoClassInfo.frameNumber,
                      static_cast<jlong>(stalledTransactionInfo->frameNumber));
    return jStalledTransactionInfo;
}

// ----------------------------------------------------------------------------

SurfaceControl* android_view_SurfaceControl_getNativeSurfaceControl(JNIEnv* env,
                                                                    jobject surfaceControlObj) {
    if (!!surfaceControlObj &&
        env->IsInstanceOf(surfaceControlObj, gSurfaceControlClassInfo.clazz)) {
        return reinterpret_cast<SurfaceControl*>(
                env->GetLongField(surfaceControlObj, gSurfaceControlClassInfo.mNativeObject));
    } else {
        return nullptr;
    }
}

SurfaceComposerClient::Transaction* android_view_SurfaceTransaction_getNativeSurfaceTransaction(
        JNIEnv* env, jobject surfaceTransactionObj) {
    if (!!surfaceTransactionObj &&
        env->IsInstanceOf(surfaceTransactionObj, gTransactionClassInfo.clazz)) {
        return reinterpret_cast<SurfaceComposerClient::Transaction*>(
                env->GetLongField(surfaceTransactionObj, gTransactionClassInfo.mNativeObject));
    } else {
        return nullptr;
    }
}

static const JNINativeMethod sSurfaceControlMethods[] = {
        // clang-format off
    {"nativeCreate", "(Landroid/view/SurfaceSession;Ljava/lang/String;IIIIJLandroid/os/Parcel;)J",
            (void*)nativeCreate },
    {"nativeReadFromParcel", "(Landroid/os/Parcel;)J",
            (void*)nativeReadFromParcel },
    {"nativeCopyFromSurfaceControl", "(J)J" ,
            (void*)nativeCopyFromSurfaceControl },
    {"nativeWriteToParcel", "(JLandroid/os/Parcel;)V",
            (void*)nativeWriteToParcel },
    {"nativeGetNativeSurfaceControlFinalizer", "()J",
            (void*) nativeGetNativeSurfaceControlFinalizer },
    {"nativeDisconnect", "(J)V",
            (void*)nativeDisconnect },
    {"nativeUpdateDefaultBufferSize", "(JII)V",
            (void*)nativeSetDefaultBufferSize},
    {"nativeCreateTransaction", "()J",
            (void*)nativeCreateTransaction },
    {"nativeApplyTransaction", "(JZZ)V",
            (void*)nativeApplyTransaction },
    {"nativeGetNativeTransactionFinalizer", "()J",
            (void*)nativeGetNativeTransactionFinalizer },
    {"nativeMergeTransaction", "(JJ)V",
            (void*)nativeMergeTransaction },
    {"nativeSetAnimationTransaction", "(J)V",
            (void*)nativeSetAnimationTransaction },
    {"nativeSetEarlyWakeupStart", "(J)V",
            (void*)nativeSetEarlyWakeupStart },
    {"nativeSetEarlyWakeupEnd", "(J)V",
            (void*)nativeSetEarlyWakeupEnd },
    {"nativeGetTransactionId", "(J)J",
                (void*)nativeGetTransactionId },
    {"nativeSetLayer", "(JJI)V",
            (void*)nativeSetLayer },
    {"nativeSetRelativeLayer", "(JJJI)V",
            (void*)nativeSetRelativeLayer },
    {"nativeSetPosition", "(JJFF)V",
            (void*)nativeSetPosition },
    {"nativeSetScale", "(JJFF)V",
            (void*)nativeSetScale },
    {"nativeSetTransparentRegionHint", "(JJLandroid/graphics/Region;)V",
            (void*)nativeSetTransparentRegionHint },
    {"nativeSetDamageRegion", "(JJLandroid/graphics/Region;)V",
            (void*)nativeSetDamageRegion },
    {"nativeSetDimmingEnabled", "(JJZ)V", (void*)nativeSetDimmingEnabled },
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
    {"nativeSetBlurRegions", "(JJ[[FI)V",
            (void*)nativeSetBlurRegions },
    {"nativeSetStretchEffect", "(JJFFFFFFFFFF)V",
            (void*) nativeSetStretchEffect },
    {"nativeSetShadowRadius", "(JJF)V",
            (void*)nativeSetShadowRadius },
    {"nativeSetFrameRate", "(JJFII)V",
            (void*)nativeSetFrameRate },
    {"nativeSetDefaultFrameRateCompatibility", "(JJI)V",
            (void*)nativeSetDefaultFrameRateCompatibility},
    {"nativeSetFrameRateCategory", "(JJIZ)V",
            (void*)nativeSetFrameRateCategory},
    {"nativeSetFrameRateSelectionStrategy", "(JJI)V",
            (void*)nativeSetFrameRateSelectionStrategy},
    {"nativeSetDisplaySurface", "(JLandroid/os/IBinder;J)V",
            (void*)nativeSetDisplaySurface },
    {"nativeSetDisplayLayerStack", "(JLandroid/os/IBinder;I)V",
            (void*)nativeSetDisplayLayerStack },
    {"nativeSetDisplayFlags", "(JLandroid/os/IBinder;I)V",
            (void*)nativeSetDisplayFlags },
    {"nativeSetDisplayProjection", "(JLandroid/os/IBinder;IIIIIIIII)V",
            (void*)nativeSetDisplayProjection },
    {"nativeSetDisplaySize", "(JLandroid/os/IBinder;II)V",
            (void*)nativeSetDisplaySize },
    {"nativeGetStaticDisplayInfo",
            "(J)Landroid/view/SurfaceControl$StaticDisplayInfo;",
            (void*)nativeGetStaticDisplayInfo },
    {"nativeGetDynamicDisplayInfo",
            "(J)Landroid/view/SurfaceControl$DynamicDisplayInfo;",
            (void*)nativeGetDynamicDisplayInfo },
    {"nativeSetDesiredDisplayModeSpecs",
            "(Landroid/os/IBinder;Landroid/view/SurfaceControl$DesiredDisplayModeSpecs;)Z",
            (void*)nativeSetDesiredDisplayModeSpecs },
    {"nativeGetDesiredDisplayModeSpecs",
            "(Landroid/os/IBinder;)Landroid/view/SurfaceControl$DesiredDisplayModeSpecs;",
            (void*)nativeGetDesiredDisplayModeSpecs },
    {"nativeGetDisplayNativePrimaries",
            "(Landroid/os/IBinder;)Landroid/view/SurfaceControl$DisplayPrimaries;",
            (void*)nativeGetDisplayNativePrimaries },
    {"nativeSetActiveColorMode", "(Landroid/os/IBinder;I)Z",
            (void*)nativeSetActiveColorMode},
     {"nativeGetBootDisplayModeSupport", "()Z",
                (void*)nativeGetBootDisplayModeSupport },
    {"nativeSetBootDisplayMode", "(Landroid/os/IBinder;I)V",
            (void*)nativeSetBootDisplayMode },
    {"nativeClearBootDisplayMode", "(Landroid/os/IBinder;)V",
            (void*)nativeClearBootDisplayMode },
    {"nativeSetAutoLowLatencyMode", "(Landroid/os/IBinder;Z)V",
            (void*)nativeSetAutoLowLatencyMode },
    {"nativeSetGameContentType", "(Landroid/os/IBinder;Z)V",
            (void*)nativeSetGameContentType },
    {"nativeGetCompositionDataspaces", "()[I",
            (void*)nativeGetCompositionDataspaces},
    {"nativeGetOverlaySupport", "()Landroid/hardware/OverlayProperties;",
            (void*) nativeGetOverlaySupport},
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
    {"nativeReparent", "(JJJ)V",
            (void*)nativeReparent },
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
    {"nativeSetBuffer", "(JJLandroid/hardware/HardwareBuffer;JLjava/util/function/Consumer;)V",
            (void*)nativeSetBuffer },
    {"nativeUnsetBuffer", "(JJ)V", (void*)nativeUnsetBuffer },

    {"nativeSetBufferTransform", "(JJI)V", (void*) nativeSetBufferTransform},
    {"nativeSetDataSpace", "(JJI)V",
            (void*)nativeSetDataSpace },
    {"nativeSetExtendedRangeBrightness", "(JJFF)V",
            (void*)nativeSetExtendedRangeBrightness },
            {"nativeSetCachingHint", "(JJI)V",
            (void*)nativeSetCachingHint },
    {"nativeAddWindowInfosReportedListener", "(JLjava/lang/Runnable;)V",
            (void*)nativeAddWindowInfosReportedListener },
    {"nativeGetDisplayBrightnessSupport", "(Landroid/os/IBinder;)Z",
            (void*)nativeGetDisplayBrightnessSupport },
    {"nativeSetDisplayBrightness", "(Landroid/os/IBinder;FFFF)Z",
            (void*)nativeSetDisplayBrightness },
    {"nativeReadTransactionFromParcel", "(Landroid/os/Parcel;)J",
            (void*)nativeReadTransactionFromParcel },
    {"nativeWriteTransactionToParcel", "(JLandroid/os/Parcel;)V",
            (void*)nativeWriteTransactionToParcel },
    {"nativeClearTransaction", "(J)V",
            (void*)nativeClearTransaction },
    {"nativeMirrorSurface", "(J)J",
            (void*)nativeMirrorSurface },
    {"nativeSetGlobalShadowSettings", "([F[FFFF)V",
            (void*)nativeSetGlobalShadowSettings },
    {"nativeGetDisplayDecorationSupport",
            "(Landroid/os/IBinder;)Landroid/hardware/graphics/common/DisplayDecorationSupport;",
            (void*)nativeGetDisplayDecorationSupport},
    {"nativeGetHandle", "(J)J",
            (void*)nativeGetHandle },
    {"nativeSetFixedTransformHint", "(JJI)V",
            (void*)nativeSetFixedTransformHint},
    {"nativeSetFocusedWindow", "(JLandroid/os/IBinder;Ljava/lang/String;I)V",
            (void*)nativeSetFocusedWindow},
    {"nativeRemoveCurrentInputFocus", "(JI)V",
            (void*)nativeRemoveCurrentInputFocus},
    {"nativeSetFrameTimelineVsync", "(JJ)V",
            (void*)nativeSetFrameTimelineVsync },
    {"nativeAddJankDataListener", "(JJ)V",
            (void*)nativeAddJankDataListener },
    {"nativeRemoveJankDataListener", "(J)V",
            (void*)nativeRemoveJankDataListener },
    {"nativeCreateJankDataListenerWrapper", "(Landroid/view/SurfaceControl$OnJankDataListener;)J",
            (void*)nativeCreateJankDataListenerWrapper },
    {"nativeGetGPUContextPriority", "()I",
            (void*)nativeGetGPUContextPriority },
    {"nativeSetTransformHint", "(JI)V",
            (void*)nativeSetTransformHint },
    {"nativeGetTransformHint", "(J)I",
            (void*)nativeGetTransformHint },
    {"nativeSetTrustedOverlay", "(JJZ)V",
            (void*)nativeSetTrustedOverlay },
    {"nativeGetLayerId", "(J)I",
            (void*)nativeGetLayerId },
    {"nativeSetDropInputMode", "(JJI)V",
             (void*)nativeSetDropInputMode },
    {"nativeSurfaceFlushJankData", "(J)V",
            (void*)nativeSurfaceFlushJankData },
    {"nativeAddTransactionCommittedListener", "(JLandroid/view/SurfaceControl$TransactionCommittedListener;)V",
            (void*) nativeAddTransactionCommittedListener },
    {"nativeAddTransactionCompletedListener", "(JLjava/util/function/Consumer;)V",
            (void*) nativeAddTransactionCompletedListener },
    {"nativeSetTrustedPresentationCallback", "(JJJLandroid/view/SurfaceControl$TrustedPresentationThresholds;)V",
            (void*) nativeSetTrustedPresentationCallback },
    {"nativeClearTrustedPresentationCallback", "(JJ)V",
            (void*) nativeClearTrustedPresentationCallback },
    {"nativeSanitize", "(JII)V",
            (void*) nativeSanitize },
    {"nativeSetDestinationFrame", "(JJIIII)V",
                (void*)nativeSetDestinationFrame },
    {"nativeSetDefaultApplyToken", "(Landroid/os/IBinder;)V",
                (void*)nativeSetDefaultApplyToken },
    {"nativeGetDefaultApplyToken", "()Landroid/os/IBinder;",
                (void*)nativeGetDefaultApplyToken },
    {"nativeBootFinished", "()Z",
            (void*)nativeBootFinished },
    {"nativeCreateTpc", "(Landroid/view/SurfaceControl$TrustedPresentationCallback;)J",
            (void*)nativeCreateTpc},
    {"getNativeTrustedPresentationCallbackFinalizer", "()J", (void*)getNativeTrustedPresentationCallbackFinalizer },
    {"nativeGetStalledTransactionInfo", "(I)Landroid/gui/StalledTransactionInfo;",
            (void*) nativeGetStalledTransactionInfo },
    {"nativeSetDesiredPresentTime", "(JJ)V",
            (void*) nativeSetDesiredPresentTime },
        // clang-format on
};

int register_android_view_SurfaceControl(JNIEnv* env)
{
    int err = RegisterMethodsOrDie(env, "android/view/SurfaceControl",
            sSurfaceControlMethods, NELEM(sSurfaceControlMethods));

    jclass integerClass = FindClassOrDie(env, "java/lang/Integer");
    gIntegerClassInfo.clazz = MakeGlobalRefOrDie(env, integerClass);
    gIntegerClassInfo.ctor = GetMethodIDOrDie(env, gIntegerClassInfo.clazz, "<init>", "(I)V");

    jclass runnableClazz = FindClassOrDie(env, "java/lang/Runnable");
    gRunnableClassInfo.clazz = MakeGlobalRefOrDie(env, runnableClazz);
    gRunnableClassInfo.run = GetMethodIDOrDie(env, runnableClazz, "run", "()V");

    jclass infoClazz = FindClassOrDie(env, "android/view/SurfaceControl$StaticDisplayInfo");
    gStaticDisplayInfoClassInfo.clazz = MakeGlobalRefOrDie(env, infoClazz);
    gStaticDisplayInfoClassInfo.ctor = GetMethodIDOrDie(env, infoClazz, "<init>", "()V");
    gStaticDisplayInfoClassInfo.isInternal = GetFieldIDOrDie(env, infoClazz, "isInternal", "Z");
    gStaticDisplayInfoClassInfo.density = GetFieldIDOrDie(env, infoClazz, "density", "F");
    gStaticDisplayInfoClassInfo.secure = GetFieldIDOrDie(env, infoClazz, "secure", "Z");
    gStaticDisplayInfoClassInfo.deviceProductInfo =
            GetFieldIDOrDie(env, infoClazz, "deviceProductInfo",
                            "Landroid/hardware/display/DeviceProductInfo;");
    gStaticDisplayInfoClassInfo.installOrientation =
            GetFieldIDOrDie(env, infoClazz, "installOrientation", "I");

    jclass dynamicInfoClazz = FindClassOrDie(env, "android/view/SurfaceControl$DynamicDisplayInfo");
    gDynamicDisplayInfoClassInfo.clazz = MakeGlobalRefOrDie(env, dynamicInfoClazz);
    gDynamicDisplayInfoClassInfo.ctor = GetMethodIDOrDie(env, dynamicInfoClazz, "<init>", "()V");
    gDynamicDisplayInfoClassInfo.supportedDisplayModes =
            GetFieldIDOrDie(env, dynamicInfoClazz, "supportedDisplayModes",
                            "[Landroid/view/SurfaceControl$DisplayMode;");
    gDynamicDisplayInfoClassInfo.activeDisplayModeId =
            GetFieldIDOrDie(env, dynamicInfoClazz, "activeDisplayModeId", "I");
    gDynamicDisplayInfoClassInfo.renderFrameRate =
            GetFieldIDOrDie(env, dynamicInfoClazz, "renderFrameRate", "F");
    gDynamicDisplayInfoClassInfo.supportedColorModes =
            GetFieldIDOrDie(env, dynamicInfoClazz, "supportedColorModes", "[I");
    gDynamicDisplayInfoClassInfo.activeColorMode =
            GetFieldIDOrDie(env, dynamicInfoClazz, "activeColorMode", "I");
    gDynamicDisplayInfoClassInfo.hdrCapabilities =
            GetFieldIDOrDie(env, dynamicInfoClazz, "hdrCapabilities",
                            "Landroid/view/Display$HdrCapabilities;");
    gDynamicDisplayInfoClassInfo.autoLowLatencyModeSupported =
            GetFieldIDOrDie(env, dynamicInfoClazz, "autoLowLatencyModeSupported", "Z");
    gDynamicDisplayInfoClassInfo.gameContentTypeSupported =
            GetFieldIDOrDie(env, dynamicInfoClazz, "gameContentTypeSupported", "Z");
    gDynamicDisplayInfoClassInfo.preferredBootDisplayMode =
            GetFieldIDOrDie(env, dynamicInfoClazz, "preferredBootDisplayMode", "I");

    jclass modeClazz = FindClassOrDie(env, "android/view/SurfaceControl$DisplayMode");
    gDisplayModeClassInfo.clazz = MakeGlobalRefOrDie(env, modeClazz);
    gDisplayModeClassInfo.ctor = GetMethodIDOrDie(env, modeClazz, "<init>", "()V");
    gDisplayModeClassInfo.id = GetFieldIDOrDie(env, modeClazz, "id", "I");
    gDisplayModeClassInfo.width = GetFieldIDOrDie(env, modeClazz, "width", "I");
    gDisplayModeClassInfo.height = GetFieldIDOrDie(env, modeClazz, "height", "I");
    gDisplayModeClassInfo.xDpi = GetFieldIDOrDie(env, modeClazz, "xDpi", "F");
    gDisplayModeClassInfo.yDpi = GetFieldIDOrDie(env, modeClazz, "yDpi", "F");
    gDisplayModeClassInfo.peakRefreshRate = GetFieldIDOrDie(env, modeClazz, "peakRefreshRate", "F");
    gDisplayModeClassInfo.vsyncRate = GetFieldIDOrDie(env, modeClazz, "vsyncRate", "F");
    gDisplayModeClassInfo.appVsyncOffsetNanos =
            GetFieldIDOrDie(env, modeClazz, "appVsyncOffsetNanos", "J");
    gDisplayModeClassInfo.presentationDeadlineNanos =
            GetFieldIDOrDie(env, modeClazz, "presentationDeadlineNanos", "J");
    gDisplayModeClassInfo.group = GetFieldIDOrDie(env, modeClazz, "group", "I");
    gDisplayModeClassInfo.supportedHdrTypes =
            GetFieldIDOrDie(env, modeClazz, "supportedHdrTypes", "[I");

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
                             "I)V");

    jclass deviceProductInfoManufactureDateClazz =
            FindClassOrDie(env, "android/hardware/display/DeviceProductInfo$ManufactureDate");
    gDeviceProductInfoManufactureDateClassInfo.clazz =
            MakeGlobalRefOrDie(env, deviceProductInfoManufactureDateClazz);
    gDeviceProductInfoManufactureDateClassInfo.ctor =
            GetMethodIDOrDie(env, deviceProductInfoManufactureDateClazz, "<init>",
                             "(Ljava/lang/Integer;Ljava/lang/Integer;)V");

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

    jclass RefreshRateRangeClazz =
            FindClassOrDie(env, "android/view/SurfaceControl$RefreshRateRange");
    gRefreshRateRangeClassInfo.clazz = MakeGlobalRefOrDie(env, RefreshRateRangeClazz);
    gRefreshRateRangeClassInfo.ctor =
            GetMethodIDOrDie(env, gRefreshRateRangeClassInfo.clazz, "<init>", "(FF)V");
    gRefreshRateRangeClassInfo.min = GetFieldIDOrDie(env, RefreshRateRangeClazz, "min", "F");
    gRefreshRateRangeClassInfo.max = GetFieldIDOrDie(env, RefreshRateRangeClazz, "max", "F");

    jclass RefreshRateRangesClazz =
            FindClassOrDie(env, "android/view/SurfaceControl$RefreshRateRanges");
    gRefreshRateRangesClassInfo.clazz = MakeGlobalRefOrDie(env, RefreshRateRangesClazz);
    gRefreshRateRangesClassInfo.ctor =
            GetMethodIDOrDie(env, gRefreshRateRangesClassInfo.clazz, "<init>",
                             "(Landroid/view/SurfaceControl$RefreshRateRange;Landroid/view/"
                             "SurfaceControl$RefreshRateRange;)V");
    gRefreshRateRangesClassInfo.physical =
            GetFieldIDOrDie(env, RefreshRateRangesClazz, "physical",
                            "Landroid/view/SurfaceControl$RefreshRateRange;");
    gRefreshRateRangesClassInfo.render =
            GetFieldIDOrDie(env, RefreshRateRangesClazz, "render",
                            "Landroid/view/SurfaceControl$RefreshRateRange;");

    jclass DesiredDisplayModeSpecsClazz =
            FindClassOrDie(env, "android/view/SurfaceControl$DesiredDisplayModeSpecs");
    gDesiredDisplayModeSpecsClassInfo.clazz = MakeGlobalRefOrDie(env, DesiredDisplayModeSpecsClazz);
    gDesiredDisplayModeSpecsClassInfo.ctor =
            GetMethodIDOrDie(env, gDesiredDisplayModeSpecsClassInfo.clazz, "<init>",
                             "(IZLandroid/view/SurfaceControl$RefreshRateRanges;Landroid/view/"
                             "SurfaceControl$RefreshRateRanges;)V");
    gDesiredDisplayModeSpecsClassInfo.defaultMode =
            GetFieldIDOrDie(env, DesiredDisplayModeSpecsClazz, "defaultMode", "I");
    gDesiredDisplayModeSpecsClassInfo.allowGroupSwitching =
            GetFieldIDOrDie(env, DesiredDisplayModeSpecsClazz, "allowGroupSwitching", "Z");
    gDesiredDisplayModeSpecsClassInfo.primaryRanges =
            GetFieldIDOrDie(env, DesiredDisplayModeSpecsClazz, "primaryRanges",
                            "Landroid/view/SurfaceControl$RefreshRateRanges;");
    gDesiredDisplayModeSpecsClassInfo.appRequestRanges =
            GetFieldIDOrDie(env, DesiredDisplayModeSpecsClazz, "appRequestRanges",
                            "Landroid/view/SurfaceControl$RefreshRateRanges;");

    jclass jankDataClazz =
                FindClassOrDie(env, "android/view/SurfaceControl$JankData");
    gJankDataClassInfo.clazz = MakeGlobalRefOrDie(env, jankDataClazz);
    gJankDataClassInfo.ctor = GetMethodIDOrDie(env, gJankDataClassInfo.clazz, "<init>", "(JIJ)V");
    jclass onJankDataListenerClazz =
            FindClassOrDie(env, "android/view/SurfaceControl$OnJankDataListener");
    gJankDataListenerClassInfo.clazz = MakeGlobalRefOrDie(env, onJankDataListenerClazz);
    gJankDataListenerClassInfo.onJankDataAvailable =
            GetMethodIDOrDie(env, onJankDataListenerClazz, "onJankDataAvailable",
                             "([Landroid/view/SurfaceControl$JankData;)V");

    jclass transactionCommittedListenerClazz =
            FindClassOrDie(env, "android/view/SurfaceControl$TransactionCommittedListener");
    gTransactionCommittedListenerClassInfo.clazz =
            MakeGlobalRefOrDie(env, transactionCommittedListenerClazz);
    gTransactionCommittedListenerClassInfo.onTransactionCommitted =
            GetMethodIDOrDie(env, transactionCommittedListenerClazz, "onTransactionCommitted",
                             "()V");
    jclass consumerClazz = FindClassOrDie(env, "java/util/function/Consumer");
    gConsumerClassInfo.clazz = MakeGlobalRefOrDie(env, consumerClazz);
    gConsumerClassInfo.accept =
            GetMethodIDOrDie(env, consumerClazz, "accept", "(Ljava/lang/Object;)V");

    jclass transactionStatsClazz =
            FindClassOrDie(env, "android/view/SurfaceControl$TransactionStats");
    gTransactionStatsClassInfo.clazz = MakeGlobalRefOrDie(env, transactionStatsClazz);
    gTransactionStatsClassInfo.ctor =
            GetMethodIDOrDie(env, gTransactionStatsClassInfo.clazz, "<init>", "(JJ)V");

    jclass displayDecorationSupportClazz =
            FindClassOrDie(env, "android/hardware/graphics/common/DisplayDecorationSupport");
    gDisplayDecorationSupportInfo.clazz = MakeGlobalRefOrDie(env, displayDecorationSupportClazz);
    gDisplayDecorationSupportInfo.ctor =
            GetMethodIDOrDie(env, displayDecorationSupportClazz, "<init>", "()V");
    gDisplayDecorationSupportInfo.format =
            GetFieldIDOrDie(env, displayDecorationSupportClazz, "format", "I");
    gDisplayDecorationSupportInfo.alphaInterpretation =
            GetFieldIDOrDie(env, displayDecorationSupportClazz, "alphaInterpretation", "I");

    jclass surfaceControlClazz = FindClassOrDie(env, "android/view/SurfaceControl");
    gSurfaceControlClassInfo.clazz = MakeGlobalRefOrDie(env, surfaceControlClazz);
    gSurfaceControlClassInfo.mNativeObject =
            GetFieldIDOrDie(env, gSurfaceControlClassInfo.clazz, "mNativeObject", "J");
    gSurfaceControlClassInfo.invokeReleaseCallback =
            GetStaticMethodIDOrDie(env, surfaceControlClazz, "invokeReleaseCallback",
                                   "(Ljava/util/function/Consumer;J)V");

    jclass surfaceTransactionClazz = FindClassOrDie(env, "android/view/SurfaceControl$Transaction");
    gTransactionClassInfo.clazz = MakeGlobalRefOrDie(env, surfaceTransactionClazz);
    gTransactionClassInfo.mNativeObject =
            GetFieldIDOrDie(env, gTransactionClassInfo.clazz, "mNativeObject", "J");

    jclass trustedPresentationThresholdsClazz =
            FindClassOrDie(env, "android/view/SurfaceControl$TrustedPresentationThresholds");
    gTrustedPresentationThresholdsClassInfo.clazz =
            MakeGlobalRefOrDie(env, trustedPresentationThresholdsClazz);
    gTrustedPresentationThresholdsClassInfo.mMinAlpha =
            GetFieldIDOrDie(env, trustedPresentationThresholdsClazz, "mMinAlpha", "F");
    gTrustedPresentationThresholdsClassInfo.mMinFractionRendered =
            GetFieldIDOrDie(env, trustedPresentationThresholdsClazz, "mMinFractionRendered", "F");
    gTrustedPresentationThresholdsClassInfo.mStabilityRequirementMs =
            GetFieldIDOrDie(env, trustedPresentationThresholdsClazz, "mStabilityRequirementMs",
                            "I");

    jclass trustedPresentationCallbackClazz =
            FindClassOrDie(env, "android/view/SurfaceControl$TrustedPresentationCallback");
    gTrustedPresentationCallbackClassInfo.onTrustedPresentationChanged =
            GetMethodIDOrDie(env, trustedPresentationCallbackClazz, "onTrustedPresentationChanged",
                             "(Z)V");

    jclass stalledTransactionInfoClazz = FindClassOrDie(env, "android/gui/StalledTransactionInfo");
    gStalledTransactionInfoClassInfo.clazz = MakeGlobalRefOrDie(env, stalledTransactionInfoClazz);
    gStalledTransactionInfoClassInfo.ctor =
            GetMethodIDOrDie(env, stalledTransactionInfoClazz, "<init>", "()V");
    gStalledTransactionInfoClassInfo.layerName =
            GetFieldIDOrDie(env, stalledTransactionInfoClazz, "layerName", "Ljava/lang/String;");
    gStalledTransactionInfoClassInfo.bufferId =
            GetFieldIDOrDie(env, stalledTransactionInfoClazz, "bufferId", "J");
    gStalledTransactionInfoClassInfo.frameNumber =
            GetFieldIDOrDie(env, stalledTransactionInfoClazz, "frameNumber", "J");

    return err;
}

} // namespace android
