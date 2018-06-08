/**
 * Copyright (C) 2017 The Android Open Source Project
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

#define LOG_TAG "BroadcastRadioService.Tuner.jni"
#define LOG_NDEBUG 0

#include "Tuner.h"

#include "convert.h"
#include "TunerCallback.h"

#include <android/hardware/broadcastradio/1.1/IBroadcastRadioFactory.h>
#include <binder/IPCThreadState.h>
#include <broadcastradio-utils-1x/Utils.h>
#include <core_jni_helpers.h>
#include <nativehelper/JNIHelp.h>
#include <utils/Log.h>

namespace android {
namespace server {
namespace BroadcastRadio {
namespace Tuner {

using std::lock_guard;
using std::mutex;

using hardware::Return;
using hardware::hidl_death_recipient;
using hardware::hidl_vec;

namespace V1_0 = hardware::broadcastradio::V1_0;
namespace V1_1 = hardware::broadcastradio::V1_1;
namespace utils = hardware::broadcastradio::utils;

using V1_0::Band;
using V1_0::BandConfig;
using V1_0::MetaData;
using V1_0::Result;
using V1_1::ITunerCallback;
using V1_1::ProgramListResult;
using V1_1::VendorKeyValue;
using utils::HalRevision;

static mutex gContextMutex;

static struct {
    struct {
        jclass clazz;
        jmethodID cstor;
        jmethodID add;
    } ArrayList;
    struct {
        jfieldID nativeContext;
        jfieldID region;
        jfieldID tunerCallback;
    } Tuner;
} gjni;

class HalDeathRecipient : public hidl_death_recipient {
    wp<V1_1::ITunerCallback> mTunerCallback;

public:
    HalDeathRecipient(wp<V1_1::ITunerCallback> tunerCallback):mTunerCallback(tunerCallback) {}

    virtual void serviceDied(uint64_t cookie, const wp<hidl::base::V1_0::IBase>& who);
};

struct TunerContext {
    TunerContext() {}

    bool mIsClosed = false;
    HalRevision mHalRev;
    bool mWithAudio;
    bool mIsAudioConnected = false;
    Band mBand;
    wp<V1_0::IBroadcastRadio> mHalModule;
    sp<V1_0::ITuner> mHalTuner;
    sp<V1_1::ITuner> mHalTuner11;
    sp<HalDeathRecipient> mHalDeathRecipient;

    sp<V1_1::IBroadcastRadio> getHalModule11() const;

private:
    DISALLOW_COPY_AND_ASSIGN(TunerContext);
};

static TunerContext& getNativeContext(jlong nativeContextHandle) {
    auto nativeContext = reinterpret_cast<TunerContext*>(nativeContextHandle);
    LOG_ALWAYS_FATAL_IF(nativeContext == nullptr, "Native context not initialized");
    return *nativeContext;
}

/**
 * Always lock gContextMutex when using native context.
 */
static TunerContext& getNativeContext(JNIEnv *env, JavaRef<jobject> const &jTuner) {
    return getNativeContext(env->GetLongField(jTuner.get(), gjni.Tuner.nativeContext));
}

static jlong nativeInit(JNIEnv *env, jobject obj, jint halRev, bool withAudio, jint band) {
    ALOGV("%s", __func__);
    lock_guard<mutex> lk(gContextMutex);

    auto ctx = new TunerContext();
    ctx->mHalRev = static_cast<HalRevision>(halRev);
    ctx->mWithAudio = withAudio;
    ctx->mBand = static_cast<Band>(band);

    static_assert(sizeof(jlong) >= sizeof(ctx), "jlong is smaller than a pointer");
    return reinterpret_cast<jlong>(ctx);
}

static void nativeFinalize(JNIEnv *env, jobject obj, jlong nativeContext) {
    ALOGV("%s", __func__);
    lock_guard<mutex> lk(gContextMutex);

    auto ctx = reinterpret_cast<TunerContext*>(nativeContext);
    delete ctx;
}

void HalDeathRecipient::serviceDied(uint64_t cookie __unused,
        const wp<hidl::base::V1_0::IBase>& who __unused) {
    ALOGW("HAL Tuner died unexpectedly");

    auto tunerCallback = mTunerCallback.promote();
    if (tunerCallback == nullptr) return;

    tunerCallback->hardwareFailure();
}

sp<V1_1::IBroadcastRadio> TunerContext::getHalModule11() const {
    auto halModule = mHalModule.promote();
    if (halModule == nullptr) {
        ALOGE("HAL module is gone");
        return nullptr;
    }

    return V1_1::IBroadcastRadio::castFrom(halModule).withDefault(nullptr);
}

void assignHalInterfaces(JNIEnv *env, JavaRef<jobject> const &jTuner,
        sp<V1_0::IBroadcastRadio> halModule, sp<V1_0::ITuner> halTuner) {
    ALOGV("%s(%p)", __func__, halTuner.get());
    ALOGE_IF(halTuner == nullptr, "HAL tuner is a nullptr");
    lock_guard<mutex> lk(gContextMutex);
    auto& ctx = getNativeContext(env, jTuner);

    if (ctx.mIsClosed) {
        ALOGD("Tuner was closed during initialization");
        // dropping the last reference will close HAL tuner
        return;
    }
    if (ctx.mHalTuner != nullptr) {
        ALOGE("HAL tuner is already set.");
        return;
    }

    ctx.mHalModule = halModule;
    ctx.mHalTuner = halTuner;
    ctx.mHalTuner11 = V1_1::ITuner::castFrom(halTuner).withDefault(nullptr);
    ALOGW_IF(ctx.mHalRev >= HalRevision::V1_1 && ctx.mHalTuner11 == nullptr,
            "Provided tuner does not implement 1.1 HAL");

    ctx.mHalDeathRecipient = new HalDeathRecipient(getNativeCallback(env, jTuner));
    halTuner->linkToDeath(ctx.mHalDeathRecipient, 0);
}

static sp<V1_0::ITuner> getHalTuner(const TunerContext& ctx) {
    auto tuner = ctx.mHalTuner;
    LOG_ALWAYS_FATAL_IF(tuner == nullptr, "HAL tuner is not open");
    return tuner;
}

static sp<V1_0::ITuner> getHalTuner(jlong nativeContext) {
    lock_guard<mutex> lk(gContextMutex);
    return getHalTuner(getNativeContext(nativeContext));
}

static sp<V1_1::ITuner> getHalTuner11(jlong nativeContext) {
    lock_guard<mutex> lk(gContextMutex);
    return getNativeContext(nativeContext).mHalTuner11;
}

sp<ITunerCallback> getNativeCallback(JNIEnv *env, JavaRef<jobject> const &tuner) {
    return TunerCallback::getNativeCallback(env,
            env->GetObjectField(tuner.get(), gjni.Tuner.tunerCallback));
}

Region getRegion(JNIEnv *env, jobject obj) {
    return static_cast<Region>(env->GetIntField(obj, gjni.Tuner.region));
}

static void nativeClose(JNIEnv *env, jobject obj, jlong nativeContext) {
    lock_guard<mutex> lk(gContextMutex);
    auto& ctx = getNativeContext(nativeContext);

    if (ctx.mIsClosed) return;
    ctx.mIsClosed = true;

    if (ctx.mHalTuner == nullptr) {
        ALOGI("Tuner closed during initialization");
        return;
    }

    ALOGI("Closing tuner %p", ctx.mHalTuner.get());

    ctx.mHalTuner->unlinkToDeath(ctx.mHalDeathRecipient);
    ctx.mHalDeathRecipient = nullptr;

    ctx.mHalTuner11 = nullptr;
    ctx.mHalTuner = nullptr;
}

static void nativeSetConfiguration(JNIEnv *env, jobject obj, jlong nativeContext, jobject config) {
    ALOGV("%s", __func__);
    lock_guard<mutex> lk(gContextMutex);
    auto& ctx = getNativeContext(nativeContext);

    auto halTuner = getHalTuner(ctx);
    if (halTuner == nullptr) return;

    Region region_unused;
    BandConfig bandConfigHal = convert::BandConfigToHal(env, config, region_unused);

    if (convert::ThrowIfFailed(env, halTuner->setConfiguration(bandConfigHal))) return;

    ctx.mBand = bandConfigHal.type;
}

static jobject nativeGetConfiguration(JNIEnv *env, jobject obj, jlong nativeContext,
        Region region) {
    ALOGV("%s", __func__);
    auto halTuner = getHalTuner(nativeContext);
    if (halTuner == nullptr) return nullptr;

    BandConfig halConfig;
    Result halResult;
    auto hidlResult = halTuner->getConfiguration([&](Result result, const BandConfig& config) {
        halResult = result;
        halConfig = config;
    });
    if (convert::ThrowIfFailed(env, hidlResult, halResult)) {
        return nullptr;
    }

    return convert::BandConfigFromHal(env, halConfig, region).release();
}

static void nativeStep(JNIEnv *env, jobject obj, jlong nativeContext,
        bool directionDown, bool skipSubChannel) {
    ALOGV("%s", __func__);
    auto halTuner = getHalTuner(nativeContext);
    if (halTuner == nullptr) return;

    auto dir = convert::DirectionToHal(directionDown);
    convert::ThrowIfFailed(env, halTuner->step(dir, skipSubChannel));
}

static void nativeScan(JNIEnv *env, jobject obj, jlong nativeContext,
        bool directionDown, bool skipSubChannel) {
    ALOGV("%s", __func__);
    auto halTuner = getHalTuner(nativeContext);
    if (halTuner == nullptr) return;

    auto dir = convert::DirectionToHal(directionDown);
    convert::ThrowIfFailed(env, halTuner->scan(dir, skipSubChannel));
}

static void nativeTune(JNIEnv *env, jobject obj, jlong nativeContext, jobject jSelector) {
    ALOGV("%s", __func__);
    lock_guard<mutex> lk(gContextMutex);
    auto& ctx = getNativeContext(nativeContext);

    auto halTuner10 = getHalTuner(ctx);
    auto halTuner11 = ctx.mHalTuner11;
    if (halTuner10 == nullptr) return;

    auto selector = convert::ProgramSelectorToHal(env, jSelector);
    if (halTuner11 != nullptr) {
        convert::ThrowIfFailed(env, halTuner11->tuneByProgramSelector(selector));
    } else {
        uint32_t channel, subChannel;
        if (!utils::getLegacyChannel(selector, &channel, &subChannel)) {
            jniThrowException(env, "java/lang/IllegalArgumentException",
                    "Can't tune to non-AM/FM channel with HAL<1.1");
            return;
        }
        convert::ThrowIfFailed(env, halTuner10->tune(channel, subChannel));
    }
}

static void nativeCancel(JNIEnv *env, jobject obj, jlong nativeContext) {
    ALOGV("%s", __func__);
    auto halTuner = getHalTuner(nativeContext);
    if (halTuner == nullptr) return;

    convert::ThrowIfFailed(env, halTuner->cancel());
}

static void nativeCancelAnnouncement(JNIEnv *env, jobject obj, jlong nativeContext) {
    ALOGV("%s", __func__);
    auto halTuner = getHalTuner11(nativeContext);
    if (halTuner == nullptr) {
        ALOGI("cancelling announcements is not supported with HAL < 1.1");
        return;
    }

    convert::ThrowIfFailed(env, halTuner->cancelAnnouncement());
}

static bool nativeStartBackgroundScan(JNIEnv *env, jobject obj, jlong nativeContext) {
    ALOGV("%s", __func__);
    auto halTuner = getHalTuner11(nativeContext);
    if (halTuner == nullptr) {
        ALOGI("Background scan is not supported with HAL < 1.1");
        return false;
    }

    auto halResult = halTuner->startBackgroundScan();

    if (halResult.isOk() && halResult == ProgramListResult::UNAVAILABLE) return false;
    return !convert::ThrowIfFailed(env, halResult);
}

static jobject nativeGetProgramList(JNIEnv *env, jobject obj, jlong nativeContext, jobject jVendorFilter) {
    ALOGV("%s", __func__);
    auto halTuner = getHalTuner11(nativeContext);
    if (halTuner == nullptr) {
        ALOGI("Program list is not supported with HAL < 1.1");
        return nullptr;
    }

    JavaRef<jobject> jList;
    ProgramListResult halResult = ProgramListResult::NOT_INITIALIZED;
    auto filter = convert::VendorInfoToHal(env, jVendorFilter);
    auto hidlResult = halTuner->getProgramList(filter,
            [&](ProgramListResult result, const hidl_vec<V1_1::ProgramInfo>& programList) {
        halResult = result;
        if (halResult != ProgramListResult::OK) return;

        jList = make_javaref(env, env->NewObject(gjni.ArrayList.clazz, gjni.ArrayList.cstor));
        for (auto& program : programList) {
            auto jProgram = convert::ProgramInfoFromHal(env, program);
            env->CallBooleanMethod(jList.get(), gjni.ArrayList.add, jProgram.get());
        }
    });

    if (convert::ThrowIfFailed(env, hidlResult, halResult)) return nullptr;

    return jList.release();
}

static jbyteArray nativeGetImage(JNIEnv *env, jobject obj, jlong nativeContext, jint id) {
    ALOGV("%s(%x)", __func__, id);
    lock_guard<mutex> lk(gContextMutex);
    auto& ctx = getNativeContext(nativeContext);

    auto halModule = ctx.getHalModule11();
    if (halModule == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "Out-of-band images are not supported with HAL < 1.1");
        return nullptr;
    }

    JavaRef<jbyteArray> jRawImage = nullptr;

    auto hidlResult = halModule->getImage(id, [&](hidl_vec<uint8_t> rawImage) {
        auto len = rawImage.size();
        if (len == 0) return;

        jRawImage = make_javaref(env, env->NewByteArray(len));
        if (jRawImage == nullptr) {
            ALOGE("Failed to allocate byte array of len %zu", len);
            return;
        }

        env->SetByteArrayRegion(jRawImage.get(), 0, len,
                reinterpret_cast<const jbyte*>(rawImage.data()));
    });

    if (convert::ThrowIfFailed(env, hidlResult)) return nullptr;

    return jRawImage.release();
}

static bool nativeIsAnalogForced(JNIEnv *env, jobject obj, jlong nativeContext) {
    ALOGV("%s", __func__);
    auto halTuner = getHalTuner11(nativeContext);
    if (halTuner == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "Forced analog switch is not supported with HAL < 1.1");
        return false;
    }

    bool isForced;
    Result halResult;
    auto hidlResult = halTuner->isAnalogForced([&](Result result, bool isForcedRet) {
        halResult = result;
        isForced = isForcedRet;
    });

    if (convert::ThrowIfFailed(env, hidlResult, halResult)) return false;

    return isForced;
}

static void nativeSetAnalogForced(JNIEnv *env, jobject obj, jlong nativeContext, bool isForced) {
    ALOGV("%s(%d)", __func__, isForced);
    auto halTuner = getHalTuner11(nativeContext);
    if (halTuner == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "Forced analog switch is not supported with HAL < 1.1");
        return;
    }

    auto halResult = halTuner->setAnalogForced(isForced);
    convert::ThrowIfFailed(env, halResult);
}

static const JNINativeMethod gTunerMethods[] = {
    { "nativeInit", "(IZI)J", (void*)nativeInit },
    { "nativeFinalize", "(J)V", (void*)nativeFinalize },
    { "nativeClose", "(J)V", (void*)nativeClose },
    { "nativeSetConfiguration", "(JLandroid/hardware/radio/RadioManager$BandConfig;)V",
            (void*)nativeSetConfiguration },
    { "nativeGetConfiguration", "(JI)Landroid/hardware/radio/RadioManager$BandConfig;",
            (void*)nativeGetConfiguration },
    { "nativeStep", "(JZZ)V", (void*)nativeStep },
    { "nativeScan", "(JZZ)V", (void*)nativeScan },
    { "nativeTune", "(JLandroid/hardware/radio/ProgramSelector;)V", (void*)nativeTune },
    { "nativeCancel", "(J)V", (void*)nativeCancel },
    { "nativeCancelAnnouncement", "(J)V", (void*)nativeCancelAnnouncement },
    { "nativeStartBackgroundScan", "(J)Z", (void*)nativeStartBackgroundScan },
    { "nativeGetProgramList", "(JLjava/util/Map;)Ljava/util/List;",
            (void*)nativeGetProgramList },
    { "nativeGetImage", "(JI)[B", (void*)nativeGetImage},
    { "nativeIsAnalogForced", "(J)Z", (void*)nativeIsAnalogForced },
    { "nativeSetAnalogForced", "(JZ)V", (void*)nativeSetAnalogForced },
};

} // namespace Tuner
} // namespace BroadcastRadio
} // namespace server

void register_android_server_broadcastradio_Tuner(JavaVM *vm, JNIEnv *env) {
    using namespace server::BroadcastRadio::Tuner;

    register_android_server_broadcastradio_TunerCallback(vm, env);

    auto tunerClass = FindClassOrDie(env, "com/android/server/broadcastradio/hal1/Tuner");
    gjni.Tuner.nativeContext = GetFieldIDOrDie(env, tunerClass, "mNativeContext", "J");
    gjni.Tuner.region = GetFieldIDOrDie(env, tunerClass, "mRegion", "I");
    gjni.Tuner.tunerCallback = GetFieldIDOrDie(env, tunerClass, "mTunerCallback",
            "Lcom/android/server/broadcastradio/hal1/TunerCallback;");

    auto arrayListClass = FindClassOrDie(env, "java/util/ArrayList");
    gjni.ArrayList.clazz = MakeGlobalRefOrDie(env, arrayListClass);
    gjni.ArrayList.cstor = GetMethodIDOrDie(env, arrayListClass, "<init>", "()V");
    gjni.ArrayList.add = GetMethodIDOrDie(env, arrayListClass, "add", "(Ljava/lang/Object;)Z");

    auto res = jniRegisterNativeMethods(env, "com/android/server/broadcastradio/hal1/Tuner",
            gTunerMethods, NELEM(gTunerMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register native methods.");
}

} // namespace android
