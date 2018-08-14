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

#define LOG_TAG "BroadcastRadioService.TunerCallback.jni"
#define LOG_NDEBUG 0

#include "TunerCallback.h"

#include "Tuner.h"
#include "convert.h"

#include <broadcastradio-utils-1x/Utils.h>
#include <core_jni_helpers.h>
#include <nativehelper/JNIHelp.h>
#include <utils/Log.h>

namespace android {
namespace server {
namespace BroadcastRadio {
namespace TunerCallback {

using std::lock_guard;
using std::mutex;

using hardware::Return;
using hardware::hidl_vec;

namespace V1_0 = hardware::broadcastradio::V1_0;
namespace V1_1 = hardware::broadcastradio::V1_1;
namespace utils = hardware::broadcastradio::utils;

using V1_0::Band;
using V1_0::BandConfig;
using V1_0::MetaData;
using V1_0::Result;
using V1_1::ITunerCallback;
using V1_1::ProgramInfo;
using V1_1::ProgramListResult;
using V1_1::ProgramSelector;
using V1_1::VendorKeyValue;
using utils::HalRevision;

static JavaVM *gvm = nullptr;

static struct {
    struct {
        jclass clazz;
        jfieldID nativeContext;
        jmethodID handleHwFailure;
        jmethodID onError;
        jmethodID onConfigurationChanged;
        jmethodID onCurrentProgramInfoChanged;
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

static mutex gContextMutex;

class NativeCallback : public ITunerCallback {
    mutex mMut;

    jobject mJTuner;
    jobject mJCallback;
    NativeCallbackThread mCallbackThread;
    HalRevision mHalRev;

    Band mBand;

    // Carries current program info data for 1.0 newMetadata callback.
    V1_0::ProgramInfo mCurrentProgramInfo;

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
    virtual Return<void> backgroundScanAvailable(bool isAvailable);
    virtual Return<void> backgroundScanComplete(ProgramListResult result);
    virtual Return<void> programListChanged();
    virtual Return<void> currentProgramInfoChanged(const ProgramInfo& info);
};

struct TunerCallbackContext {
    TunerCallbackContext() {}

    sp<NativeCallback> mNativeCallback;

private:
    DISALLOW_COPY_AND_ASSIGN(TunerCallbackContext);
};

NativeCallback::NativeCallback(JNIEnv *env, jobject jTuner, jobject jCallback, HalRevision halRev)
        : mCallbackThread(gvm), mHalRev(halRev) {
    ALOGV("%s", __func__);
    mJTuner = env->NewGlobalRef(jTuner);
    mJCallback = env->NewGlobalRef(jCallback);
}

NativeCallback::~NativeCallback() {
    ALOGV("%s", __func__);

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
    ALOGV("%s(%d)", __func__, result);

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
    ALOGV("%s(%d)", __func__, result);

    if (mHalRev > HalRevision::V1_0) {
        ALOGW("1.0 callback was ignored");
        return {};
    }

    if (result == Result::OK) {
        {
            lock_guard<mutex> lk(mMut);
            mCurrentProgramInfo = info;
        }

        // tuneComplete_1_1 implementation does not handle success case, see the implementation
        mCallbackThread.enqueue([this, info](JNIEnv *env) {
            auto jInfo = convert::ProgramInfoFromHal(env, info, mBand);
            env->CallVoidMethod(mJCallback, gjni.TunerCallback.onCurrentProgramInfoChanged,
                    jInfo.get());
        });
        return {};
    }

    auto selector = utils::make_selector(mBand, info.channel, info.subChannel);
    return tuneComplete_1_1(result, selector);
}

Return<void> NativeCallback::tuneComplete_1_1(Result result, const ProgramSelector& selector) {
    ALOGV("%s(%d)", __func__, result);

    mCallbackThread.enqueue([result, this](JNIEnv *env) {
        /* for HAL 1.1, onCurrentProgramInfoChanged will be called from currentProgramInfoChanged,
         * so we don't need to handle success case here.
         */
        if (result == Result::OK) return;

        TunerError cause = TunerError::CANCELLED;
        if (result == Result::TIMEOUT) cause = TunerError::SCAN_TIMEOUT;
        env->CallVoidMethod(mJCallback, gjni.TunerCallback.onError, cause);
    });

    return Return<void>();
}

Return<void> NativeCallback::afSwitch(const V1_0::ProgramInfo& info) {
    ALOGV("%s", __func__);
    return tuneComplete(Result::OK, info);
}

Return<void> NativeCallback::antennaStateChange(bool connected) {
    ALOGV("%s(%d)", __func__, connected);

    mCallbackThread.enqueue([this, connected](JNIEnv *env) {
        env->CallVoidMethod(mJCallback, gjni.TunerCallback.onAntennaState, connected);
    });

    return Return<void>();
}

Return<void> NativeCallback::trafficAnnouncement(bool active) {
    ALOGV("%s(%d)", __func__, active);

    mCallbackThread.enqueue([this, active](JNIEnv *env) {
        env->CallVoidMethod(mJCallback, gjni.TunerCallback.onTrafficAnnouncement, active);
    });

    return Return<void>();
}

Return<void> NativeCallback::emergencyAnnouncement(bool active) {
    ALOGV("%s(%d)", __func__, active);

    mCallbackThread.enqueue([this, active](JNIEnv *env) {
        env->CallVoidMethod(mJCallback, gjni.TunerCallback.onEmergencyAnnouncement, active);
    });

    return Return<void>();
}

Return<void> NativeCallback::newMetadata(uint32_t channel, uint32_t subChannel,
        const hidl_vec<MetaData>& metadata) {
    ALOGV("%s(%d, %d)", __func__, channel, subChannel);

    if (mHalRev > HalRevision::V1_0) {
        ALOGW("1.0 callback was ignored");
        return {};
    }

    V1_0::ProgramInfo info;
    {
        lock_guard<mutex> lk(mMut);
        info = mCurrentProgramInfo;
    }
    if (channel != info.channel || subChannel != info.subChannel) {
        ALOGE("Channel mismatch on newMetadata callback (%d.%d != %d.%d)",
                channel, subChannel, info.channel, info.subChannel);
        return {};
    }
    info.metadata = metadata;

    mCallbackThread.enqueue([this, info](JNIEnv *env) {
        auto jInfo = convert::ProgramInfoFromHal(env, info, mBand);
        env->CallVoidMethod(mJCallback, gjni.TunerCallback.onCurrentProgramInfoChanged,
                jInfo.get());
    });

    return {};
}

Return<void> NativeCallback::backgroundScanAvailable(bool isAvailable) {
    ALOGV("%s(%d)", __func__, isAvailable);

    mCallbackThread.enqueue([this, isAvailable](JNIEnv *env) {
        env->CallVoidMethod(mJCallback,
                gjni.TunerCallback.onBackgroundScanAvailabilityChange, isAvailable);
    });

    return Return<void>();
}

Return<void> NativeCallback::backgroundScanComplete(ProgramListResult result) {
    ALOGV("%s(%d)", __func__, result);

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
    ALOGV("%s", __func__);

    mCallbackThread.enqueue([this](JNIEnv *env) {
        env->CallVoidMethod(mJCallback, gjni.TunerCallback.onProgramListChanged);
    });

    return Return<void>();
}

Return<void> NativeCallback::currentProgramInfoChanged(const ProgramInfo& info) {
    ALOGV("%s(%s)", __func__, toString(info).substr(0, 100).c_str());

    mCallbackThread.enqueue([this, info](JNIEnv *env) {
        auto jInfo = convert::ProgramInfoFromHal(env, info);
        env->CallVoidMethod(mJCallback, gjni.TunerCallback.onCurrentProgramInfoChanged,
                jInfo.get());
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
    ALOGV("%s", __func__);
    lock_guard<mutex> lk(gContextMutex);

    auto halRev = static_cast<HalRevision>(jHalRev);

    auto ctx = new TunerCallbackContext();
    ctx->mNativeCallback = new NativeCallback(env, jTuner, obj, halRev);

    static_assert(sizeof(jlong) >= sizeof(ctx), "jlong is smaller than a pointer");
    return reinterpret_cast<jlong>(ctx);
}

static void nativeFinalize(JNIEnv *env, jobject obj, jlong nativeContext) {
    ALOGV("%s", __func__);
    lock_guard<mutex> lk(gContextMutex);

    auto ctx = reinterpret_cast<TunerCallbackContext*>(nativeContext);
    delete ctx;
}

static void nativeDetach(JNIEnv *env, jobject obj, jlong nativeContext) {
    ALOGV("%s", __func__);
    lock_guard<mutex> lk(gContextMutex);
    auto& ctx = getNativeContext(nativeContext);

    if (ctx.mNativeCallback == nullptr) return;
    ctx.mNativeCallback->detach();
    ctx.mNativeCallback = nullptr;
}

sp<ITunerCallback> getNativeCallback(JNIEnv *env, jobject jTunerCallback) {
    lock_guard<mutex> lk(gContextMutex);
    auto& ctx = getNativeContext(env, jTunerCallback);
    return ctx.mNativeCallback;
}

static const JNINativeMethod gTunerCallbackMethods[] = {
    { "nativeInit", "(Lcom/android/server/broadcastradio/hal1/Tuner;I)J", (void*)nativeInit },
    { "nativeFinalize", "(J)V", (void*)nativeFinalize },
    { "nativeDetach", "(J)V", (void*)nativeDetach },
};

} // namespace TunerCallback
} // namespace BroadcastRadio
} // namespace server

void register_android_server_broadcastradio_TunerCallback(JavaVM *vm, JNIEnv *env) {
    using namespace server::BroadcastRadio::TunerCallback;

    gvm = vm;

    auto tunerCbClass = FindClassOrDie(env, "com/android/server/broadcastradio/hal1/TunerCallback");
    gjni.TunerCallback.clazz = MakeGlobalRefOrDie(env, tunerCbClass);
    gjni.TunerCallback.nativeContext = GetFieldIDOrDie(env, tunerCbClass, "mNativeContext", "J");
    gjni.TunerCallback.handleHwFailure = GetMethodIDOrDie(env, tunerCbClass, "handleHwFailure", "()V");
    gjni.TunerCallback.onError = GetMethodIDOrDie(env, tunerCbClass, "onError", "(I)V");
    gjni.TunerCallback.onConfigurationChanged = GetMethodIDOrDie(env, tunerCbClass,
            "onConfigurationChanged", "(Landroid/hardware/radio/RadioManager$BandConfig;)V");
    gjni.TunerCallback.onCurrentProgramInfoChanged = GetMethodIDOrDie(env, tunerCbClass,
            "onCurrentProgramInfoChanged", "(Landroid/hardware/radio/RadioManager$ProgramInfo;)V");
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

    auto res = jniRegisterNativeMethods(env, "com/android/server/broadcastradio/hal1/TunerCallback",
            gTunerCallbackMethods, NELEM(gTunerCallbackMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register native methods.");
}

} // namespace android
