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

#define LOG_TAG "radio.TunerCallback.jni"
#define LOG_NDEBUG 0

#include "com_android_server_radio_TunerCallback.h"

#include "com_android_server_radio_convert.h"
#include "com_android_server_radio_Tuner.h"

#include <JNIHelp.h>
#include <Utils.h>
#include <core_jni_helpers.h>
#include <utils/Log.h>

namespace android {
namespace server {
namespace radio {
namespace TunerCallback {

using hardware::Return;
using hardware::hidl_vec;

namespace V1_0 = hardware::broadcastradio::V1_0;
namespace V1_1 = hardware::broadcastradio::V1_1;

using V1_0::Band;
using V1_0::BandConfig;
using V1_0::MetaData;
using V1_0::Result;
using V1_1::ITunerCallback;
using V1_1::ProgramListResult;
using V1_1::ProgramSelector;

static JavaVM *gvm = nullptr;

static struct {
    struct {
        jclass clazz;
        jfieldID nativeContext;
        jmethodID handleHwFailure;
        jmethodID onError;
        jmethodID onConfigurationChanged;
        jmethodID onProgramInfoChanged;
        jmethodID onTrafficAnnouncement;
        jmethodID onEmergencyAnnouncement;
        jmethodID onAntennaState;
        jmethodID onBackgroundScanAvailabilityChange;
        jmethodID onBackgroundScanComplete;
        jmethodID onProgramListChanged;
    } TunerCallback;
} gjni;

// from frameworks/base/core/java/android/hardware/radio/RadioTuner.java
enum class TunerError : jint {
    HARDWARE_FAILURE = 0,
    SERVER_DIED = 1,
    CANCELLED = 2,
    SCAN_TIMEOUT = 3,
    CONFIG = 4,
    BACKGROUND_SCAN_UNAVAILABLE = 5,
    BACKGROUND_SCAN_FAILED = 6,
};

static Mutex gContextMutex;

class NativeCallback : public ITunerCallback {
    jobject mJTuner;
    jobject mJCallback;
    NativeCallbackThread mCallbackThread;
    HalRevision mHalRev;

    Band mBand;

    DISALLOW_COPY_AND_ASSIGN(NativeCallback);

public:
    NativeCallback(JNIEnv *env, jobject jTuner, jobject jCallback, HalRevision halRev);
    virtual ~NativeCallback();

    void detach();

    virtual Return<void> hardwareFailure();
    virtual Return<void> configChange(Result result, const BandConfig& config);
    virtual Return<void> tuneComplete(Result result, const V1_0::ProgramInfo& info);
    virtual Return<void> afSwitch(const V1_0::ProgramInfo& info);
    virtual Return<void> antennaStateChange(bool connected);
    virtual Return<void> trafficAnnouncement(bool active);
    virtual Return<void> emergencyAnnouncement(bool active);
    virtual Return<void> newMetadata(uint32_t channel, uint32_t subChannel,
            const hidl_vec<MetaData>& metadata);
    virtual Return<void> tuneComplete_1_1(Result result, const ProgramSelector& selector);
    virtual Return<void> afSwitch_1_1(const ProgramSelector& selector);
    virtual Return<void> backgroundScanAvailable(bool isAvailable);
    virtual Return<void> backgroundScanComplete(ProgramListResult result);
    virtual Return<void> programListChanged();
    virtual Return<void> programInfoChanged();
};

struct TunerCallbackContext {
    TunerCallbackContext() {}

    sp<NativeCallback> mNativeCallback;

private:
    DISALLOW_COPY_AND_ASSIGN(TunerCallbackContext);
};

NativeCallback::NativeCallback(JNIEnv *env, jobject jTuner, jobject jCallback, HalRevision halRev)
        : mCallbackThread(gvm), mHalRev(halRev) {
    ALOGV("NativeCallback()");
    mJTuner = env->NewGlobalRef(jTuner);
    mJCallback = env->NewGlobalRef(jCallback);
}

NativeCallback::~NativeCallback() {
    ALOGV("~NativeCallback()");

    // stop callback thread before dereferencing client callback
    mCallbackThread.stop();

    JNIEnv *env = nullptr;
    gvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_4);
    if (env != nullptr) {
        env->DeleteGlobalRef(mJTuner);
        env->DeleteGlobalRef(mJCallback);
    }
}

void NativeCallback::detach() {
    // stop callback thread to ignore further calls
    mCallbackThread.stop();
}

Return<void> NativeCallback::hardwareFailure() {
    mCallbackThread.enqueue([this](JNIEnv *env) {
        env->CallVoidMethod(mJCallback, gjni.TunerCallback.handleHwFailure);
    });

    return Return<void>();
}

Return<void> NativeCallback::configChange(Result result, const BandConfig& config) {
    ALOGV("configChange(%d)", result);

    mCallbackThread.enqueue([result, config, this](JNIEnv *env) {
        if (result == Result::OK) {
            auto region = Tuner::getRegion(env, mJTuner);
            auto jConfig = convert::BandConfigFromHal(env, config, region);
            if (jConfig == nullptr) return;
            env->CallVoidMethod(mJCallback, gjni.TunerCallback.onConfigurationChanged,
                    jConfig.get());
        } else {
            env->CallVoidMethod(mJCallback, gjni.TunerCallback.onError, TunerError::CONFIG);
        }
    });

    return Return<void>();
}

Return<void> NativeCallback::tuneComplete(Result result, const V1_0::ProgramInfo& info) {
    ALOGV("tuneComplete(%d)", result);

    if (mHalRev > HalRevision::V1_0) {
        ALOGW("1.0 callback was ignored");
        return Return<void>();
    }

    auto selector = V1_1::utils::make_selector(mBand, info.channel, info.subChannel);
    return tuneComplete_1_1(result, selector);
}

Return<void> NativeCallback::tuneComplete_1_1(Result result, const ProgramSelector& selector) {
    ALOGV("tuneComplete_1_1(%d)", result);

    mCallbackThread.enqueue([result, this](JNIEnv *env) {
        if (result == Result::OK) {
            env->CallVoidMethod(mJCallback, gjni.TunerCallback.onProgramInfoChanged);
        } else {
            TunerError cause = TunerError::CANCELLED;
            if (result == Result::TIMEOUT) cause = TunerError::SCAN_TIMEOUT;
            env->CallVoidMethod(mJCallback, gjni.TunerCallback.onError, cause);
        }
    });

    return Return<void>();
}

Return<void> NativeCallback::afSwitch(const V1_0::ProgramInfo& info) {
    ALOGV("afSwitch()");
    return tuneComplete(Result::OK, info);
}

Return<void> NativeCallback::afSwitch_1_1(const ProgramSelector& selector) {
    ALOGV("afSwitch_1_1()");
    return tuneComplete_1_1(Result::OK, selector);
}

Return<void> NativeCallback::antennaStateChange(bool connected) {
    ALOGV("antennaStateChange(%d)", connected);

    mCallbackThread.enqueue([this, connected](JNIEnv *env) {
        env->CallVoidMethod(mJCallback, gjni.TunerCallback.onAntennaState, connected);
    });

    return Return<void>();
}

Return<void> NativeCallback::trafficAnnouncement(bool active) {
    ALOGV("trafficAnnouncement(%d)", active);

    mCallbackThread.enqueue([this, active](JNIEnv *env) {
        env->CallVoidMethod(mJCallback, gjni.TunerCallback.onTrafficAnnouncement, active);
    });

    return Return<void>();
}

Return<void> NativeCallback::emergencyAnnouncement(bool active) {
    ALOGV("emergencyAnnouncement(%d)", active);

    mCallbackThread.enqueue([this, active](JNIEnv *env) {
        env->CallVoidMethod(mJCallback, gjni.TunerCallback.onEmergencyAnnouncement, active);
    });

    return Return<void>();
}

Return<void> NativeCallback::newMetadata(uint32_t channel, uint32_t subChannel,
        const hidl_vec<MetaData>& metadata) {
    // channel and subChannel are not used
    ALOGV("newMetadata(%d, %d)", channel, subChannel);

    if (mHalRev > HalRevision::V1_0) {
        ALOGW("1.0 callback was ignored");
        return Return<void>();
    }

    mCallbackThread.enqueue([this, metadata](JNIEnv *env) {
        env->CallVoidMethod(mJCallback, gjni.TunerCallback.onProgramInfoChanged);
    });

    return Return<void>();
}

Return<void> NativeCallback::backgroundScanAvailable(bool isAvailable) {
    ALOGV("backgroundScanAvailable(%d)", isAvailable);

    mCallbackThread.enqueue([this, isAvailable](JNIEnv *env) {
        env->CallVoidMethod(mJCallback,
                gjni.TunerCallback.onBackgroundScanAvailabilityChange, isAvailable);
    });

    return Return<void>();
}

Return<void> NativeCallback::backgroundScanComplete(ProgramListResult result) {
    ALOGV("backgroundScanComplete(%d)", result);

    mCallbackThread.enqueue([this, result](JNIEnv *env) {
        if (result == ProgramListResult::OK) {
            env->CallVoidMethod(mJCallback, gjni.TunerCallback.onBackgroundScanComplete);
        } else {
            auto cause = (result == ProgramListResult::UNAVAILABLE) ?
                    TunerError::BACKGROUND_SCAN_UNAVAILABLE : TunerError::BACKGROUND_SCAN_FAILED;
            env->CallVoidMethod(mJCallback, gjni.TunerCallback.onError, cause);
        }
    });

    return Return<void>();
}

Return<void> NativeCallback::programListChanged() {
    ALOGV("programListChanged()");

    mCallbackThread.enqueue([this](JNIEnv *env) {
        env->CallVoidMethod(mJCallback, gjni.TunerCallback.onProgramListChanged);
    });

    return Return<void>();
}

Return<void> NativeCallback::programInfoChanged() {
    ALOGV("programInfoChanged()");

    mCallbackThread.enqueue([this](JNIEnv *env) {
        env->CallVoidMethod(mJCallback, gjni.TunerCallback.onProgramInfoChanged);
    });

    return Return<void>();
}

static TunerCallbackContext& getNativeContext(jlong nativeContextHandle) {
    auto nativeContext = reinterpret_cast<TunerCallbackContext*>(nativeContextHandle);
    LOG_ALWAYS_FATAL_IF(nativeContext == nullptr, "Native context not initialized");
    return *nativeContext;
}

/**
 * Always lock gContextMutex when using native context.
 */
static TunerCallbackContext& getNativeContext(JNIEnv *env, jobject jTunerCb) {
    return getNativeContext(env->GetLongField(jTunerCb, gjni.TunerCallback.nativeContext));
}

static jlong nativeInit(JNIEnv *env, jobject obj, jobject jTuner, jint jHalRev) {
    ALOGV("nativeInit()");
    AutoMutex _l(gContextMutex);

    auto halRev = static_cast<HalRevision>(jHalRev);

    auto ctx = new TunerCallbackContext();
    ctx->mNativeCallback = new NativeCallback(env, jTuner, obj, halRev);

    static_assert(sizeof(jlong) >= sizeof(ctx), "jlong is smaller than a pointer");
    return reinterpret_cast<jlong>(ctx);
}

static void nativeFinalize(JNIEnv *env, jobject obj, jlong nativeContext) {
    ALOGV("nativeFinalize()");
    AutoMutex _l(gContextMutex);

    auto ctx = reinterpret_cast<TunerCallbackContext*>(nativeContext);
    delete ctx;
}

static void nativeDetach(JNIEnv *env, jobject obj, jlong nativeContext) {
    ALOGV("nativeDetach()");
    AutoMutex _l(gContextMutex);
    auto& ctx = getNativeContext(nativeContext);

    if (ctx.mNativeCallback == nullptr) return;
    ctx.mNativeCallback->detach();
    ctx.mNativeCallback = nullptr;
}

sp<ITunerCallback> getNativeCallback(JNIEnv *env, jobject jTunerCallback) {
    AutoMutex _l(gContextMutex);
    auto& ctx = getNativeContext(env, jTunerCallback);
    return ctx.mNativeCallback;
}

static const JNINativeMethod gTunerCallbackMethods[] = {
    { "nativeInit", "(Lcom/android/server/radio/Tuner;I)J", (void*)nativeInit },
    { "nativeFinalize", "(J)V", (void*)nativeFinalize },
    { "nativeDetach", "(J)V", (void*)nativeDetach },
};

} // namespace TunerCallback
} // namespace radio
} // namespace server

void register_android_server_radio_TunerCallback(JavaVM *vm, JNIEnv *env) {
    using namespace server::radio::TunerCallback;

    gvm = vm;

    auto tunerCbClass = FindClassOrDie(env, "com/android/server/radio/TunerCallback");
    gjni.TunerCallback.clazz = MakeGlobalRefOrDie(env, tunerCbClass);
    gjni.TunerCallback.nativeContext = GetFieldIDOrDie(env, tunerCbClass, "mNativeContext", "J");
    gjni.TunerCallback.handleHwFailure = GetMethodIDOrDie(env, tunerCbClass, "handleHwFailure", "()V");
    gjni.TunerCallback.onError = GetMethodIDOrDie(env, tunerCbClass, "onError", "(I)V");
    gjni.TunerCallback.onConfigurationChanged = GetMethodIDOrDie(env, tunerCbClass,
            "onConfigurationChanged", "(Landroid/hardware/radio/RadioManager$BandConfig;)V");
    gjni.TunerCallback.onProgramInfoChanged = GetMethodIDOrDie(env, tunerCbClass,
            "onProgramInfoChanged", "()V");
    gjni.TunerCallback.onTrafficAnnouncement = GetMethodIDOrDie(env, tunerCbClass,
            "onTrafficAnnouncement", "(Z)V");
    gjni.TunerCallback.onEmergencyAnnouncement = GetMethodIDOrDie(env, tunerCbClass,
            "onEmergencyAnnouncement", "(Z)V");
    gjni.TunerCallback.onAntennaState = GetMethodIDOrDie(env, tunerCbClass,
            "onAntennaState", "(Z)V");
    gjni.TunerCallback.onBackgroundScanAvailabilityChange = GetMethodIDOrDie(env, tunerCbClass,
            "onBackgroundScanAvailabilityChange", "(Z)V");
    gjni.TunerCallback.onBackgroundScanComplete = GetMethodIDOrDie(env, tunerCbClass,
            "onBackgroundScanComplete", "()V");
    gjni.TunerCallback.onProgramListChanged = GetMethodIDOrDie(env, tunerCbClass,
            "onProgramListChanged", "()V");

    auto res = jniRegisterNativeMethods(env, "com/android/server/radio/TunerCallback",
            gTunerCallbackMethods, NELEM(gTunerCallbackMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register native methods.");
}

} // namespace android
