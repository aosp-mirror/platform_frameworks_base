/*
 * Copyright (C) 2009 The Android Open Source Project
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

#define LOG_TAG "VibratorService"

#include <android/hardware/vibrator/1.3/IVibrator.h>
#include <android/hardware/vibrator/BnVibratorCallback.h>
#include <android/hardware/vibrator/IVibrator.h>
#include <binder/IServiceManager.h>

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include "android_runtime/AndroidRuntime.h"
#include "core_jni_helpers.h"

#include <utils/misc.h>
#include <utils/Log.h>
#include <hardware/vibrator.h>

#include <inttypes.h>
#include <stdio.h>

using android::hardware::Return;
using android::hardware::Void;
using android::hardware::vibrator::V1_0::EffectStrength;
using android::hardware::vibrator::V1_0::Status;
using android::hardware::vibrator::V1_1::Effect_1_1;

namespace V1_0 = android::hardware::vibrator::V1_0;
namespace V1_1 = android::hardware::vibrator::V1_1;
namespace V1_2 = android::hardware::vibrator::V1_2;
namespace V1_3 = android::hardware::vibrator::V1_3;
namespace aidl = android::hardware::vibrator;

namespace android {

static jmethodID sMethodIdOnComplete;

static struct {
    jfieldID id;
    jfieldID scale;
    jfieldID delay;
} gPrimitiveClassInfo;

static_assert(static_cast<uint8_t>(V1_0::EffectStrength::LIGHT) ==
                static_cast<uint8_t>(aidl::EffectStrength::LIGHT));
static_assert(static_cast<uint8_t>(V1_0::EffectStrength::MEDIUM) ==
                static_cast<uint8_t>(aidl::EffectStrength::MEDIUM));
static_assert(static_cast<uint8_t>(V1_0::EffectStrength::STRONG) ==
                static_cast<uint8_t>(aidl::EffectStrength::STRONG));

static_assert(static_cast<uint8_t>(V1_3::Effect::CLICK) ==
                static_cast<uint8_t>(aidl::Effect::CLICK));
static_assert(static_cast<uint8_t>(V1_3::Effect::DOUBLE_CLICK) ==
                static_cast<uint8_t>(aidl::Effect::DOUBLE_CLICK));
static_assert(static_cast<uint8_t>(V1_3::Effect::TICK) ==
                static_cast<uint8_t>(aidl::Effect::TICK));
static_assert(static_cast<uint8_t>(V1_3::Effect::THUD) ==
                static_cast<uint8_t>(aidl::Effect::THUD));
static_assert(static_cast<uint8_t>(V1_3::Effect::POP) ==
                static_cast<uint8_t>(aidl::Effect::POP));
static_assert(static_cast<uint8_t>(V1_3::Effect::HEAVY_CLICK) ==
                static_cast<uint8_t>(aidl::Effect::HEAVY_CLICK));
static_assert(static_cast<uint8_t>(V1_3::Effect::RINGTONE_1) ==
                static_cast<uint8_t>(aidl::Effect::RINGTONE_1));
static_assert(static_cast<uint8_t>(V1_3::Effect::RINGTONE_2) ==
                static_cast<uint8_t>(aidl::Effect::RINGTONE_2));
static_assert(static_cast<uint8_t>(V1_3::Effect::RINGTONE_15) ==
                static_cast<uint8_t>(aidl::Effect::RINGTONE_15));
static_assert(static_cast<uint8_t>(V1_3::Effect::TEXTURE_TICK) ==
                static_cast<uint8_t>(aidl::Effect::TEXTURE_TICK));

class VibratorCallback {
    public:
        VibratorCallback(JNIEnv *env, jobject vibration) :
        mVibration(MakeGlobalRefOrDie(env, vibration)) {}

        ~VibratorCallback() {
            JNIEnv *env = AndroidRuntime::getJNIEnv();
            env->DeleteGlobalRef(mVibration);
        }

        void onComplete() {
            auto env = AndroidRuntime::getJNIEnv();
            env->CallVoidMethod(mVibration, sMethodIdOnComplete);
        }

    private:
        jobject mVibration;
};

class AidlVibratorCallback : public aidl::BnVibratorCallback {
  public:
    AidlVibratorCallback(JNIEnv *env, jobject vibration) :
    mCb(env, vibration) {}

    binder::Status onComplete() override {
        mCb.onComplete();
        return binder::Status::ok(); // oneway, local call
    }

  private:
    VibratorCallback mCb;
};

static constexpr int NUM_TRIES = 2;

template<class R>
inline R NoneStatus() {
    using ::android::hardware::Status;
    return Status::fromExceptionCode(Status::EX_NONE);
}

template<>
inline binder::Status NoneStatus() {
    using binder::Status;
    return Status::fromExceptionCode(Status::EX_NONE);
}

// Creates a Return<R> with STATUS::EX_NULL_POINTER.
template<class R>
inline R NullptrStatus() {
    using ::android::hardware::Status;
    return Status::fromExceptionCode(Status::EX_NULL_POINTER);
}

template<>
inline binder::Status NullptrStatus() {
    using binder::Status;
    return Status::fromExceptionCode(Status::EX_NULL_POINTER);
}

template <typename I>
sp<I> getService() {
    return I::getService();
}

template <>
sp<aidl::IVibrator> getService() {
    return waitForVintfService<aidl::IVibrator>();
}

template <typename I>
sp<I> tryGetService() {
    return I::tryGetService();
}

template <>
sp<aidl::IVibrator> tryGetService() {
    return checkVintfService<aidl::IVibrator>();
}

template <typename I>
class HalWrapper {
  public:
    static std::unique_ptr<HalWrapper> Create() {
        // Assume that if getService returns a nullptr, HAL is not available on the
        // device.
        auto hal = getService<I>();
        return hal ? std::unique_ptr<HalWrapper>(new HalWrapper(std::move(hal))) : nullptr;
    }

    // Helper used to transparently deal with the vibrator HAL becoming unavailable.
    template<class R, class... Args0, class... Args1>
    R call(R (I::* fn)(Args0...), Args1&&... args1) {
        // Return<R> doesn't have a default constructor, so make a Return<R> with
        // STATUS::EX_NONE.
        R ret{NoneStatus<R>()};

        // Note that ret is guaranteed to be changed after this loop.
        for (int i = 0; i < NUM_TRIES; ++i) {
            ret = (mHal == nullptr) ? NullptrStatus<R>()
                    : (*mHal.*fn)(std::forward<Args1>(args1)...);

            if (ret.isOk()) {
                break;
            }

            ALOGE("Failed to issue command to vibrator HAL. Retrying.");

            // Restoring connection to the HAL.
            mHal = tryGetService<I>();
        }
        return ret;
    }

  private:
    HalWrapper(sp<I> &&hal) : mHal(std::move(hal)) {}

  private:
    sp<I> mHal;
};

template <typename I>
static auto getHal() {
    static auto sHalWrapper = HalWrapper<I>::Create();
    return sHalWrapper.get();
}

template<class R, class I, class... Args0, class... Args1>
R halCall(R (I::* fn)(Args0...), Args1&&... args1) {
    auto hal = getHal<I>();
    return hal ? hal->call(fn, std::forward<Args1>(args1)...) : NullptrStatus<R>();
}

template<class R>
bool isValidEffect(jlong effect) {
    if (effect < 0) {
        return false;
    }
    R val = static_cast<R>(effect);
    auto iter = hardware::hidl_enum_range<R>();
    return val >= *iter.begin() && val <= *std::prev(iter.end());
}

static void vibratorInit(JNIEnv *env, jclass clazz)
{
    if (auto hal = getHal<aidl::IVibrator>()) {
        // IBinder::pingBinder isn't accessible as a pointer function
        // but getCapabilities can serve the same purpose
        int32_t cap;
        hal->call(&aidl::IVibrator::getCapabilities, &cap).isOk();
    } else {
        halCall(&V1_0::IVibrator::ping).isOk();
    }
}

static jboolean vibratorExists(JNIEnv* /* env */, jclass /* clazz */)
{
    bool ok;

    if (auto hal = getHal<aidl::IVibrator>()) {
        // IBinder::pingBinder isn't accessible as a pointer function
        // but getCapabilities can serve the same purpose
        int32_t cap;
        ok = hal->call(&aidl::IVibrator::getCapabilities, &cap).isOk();
    } else {
        ok = halCall(&V1_0::IVibrator::ping).isOk();
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

static void vibratorOn(JNIEnv* /* env */, jclass /* clazz */, jlong timeout_ms)
{
    if (auto hal = getHal<aidl::IVibrator>()) {
        auto status = hal->call(&aidl::IVibrator::on, timeout_ms, nullptr);
        if (!status.isOk()) {
            ALOGE("vibratorOn command failed: %s", status.toString8().string());
        }
    } else {
        Status retStatus = halCall(&V1_0::IVibrator::on, timeout_ms).withDefault(Status::UNKNOWN_ERROR);
        if (retStatus != Status::OK) {
            ALOGE("vibratorOn command failed (%" PRIu32 ").", static_cast<uint32_t>(retStatus));
        }
    }
}

static void vibratorOff(JNIEnv* /* env */, jclass /* clazz */)
{
    if (auto hal = getHal<aidl::IVibrator>()) {
        auto status = hal->call(&aidl::IVibrator::off);
        if (!status.isOk()) {
            ALOGE("vibratorOff command failed: %s", status.toString8().string());
        }
    } else {
        Status retStatus = halCall(&V1_0::IVibrator::off).withDefault(Status::UNKNOWN_ERROR);
        if (retStatus != Status::OK) {
            ALOGE("vibratorOff command failed (%" PRIu32 ").", static_cast<uint32_t>(retStatus));
        }
    }
}

static jlong vibratorSupportsAmplitudeControl(JNIEnv*, jclass) {
    if (auto hal = getHal<aidl::IVibrator>()) {
        int32_t cap = 0;
        if (!hal->call(&aidl::IVibrator::getCapabilities, &cap).isOk()) {
            return false;
        }
        return (cap & aidl::IVibrator::CAP_AMPLITUDE_CONTROL) > 0;
    } else {
        return halCall(&V1_0::IVibrator::supportsAmplitudeControl).withDefault(false);
    }
}

static void vibratorSetAmplitude(JNIEnv*, jclass, jint amplitude) {
    if (auto hal = getHal<aidl::IVibrator>()) {
        auto status = hal->call(&aidl::IVibrator::IVibrator::setAmplitude, static_cast<float>(amplitude) / UINT8_MAX);
        if (!status.isOk()) {
            ALOGE("Failed to set vibrator amplitude: %s", status.toString8().string());
        }
    } else {
        Status status = halCall(&V1_0::IVibrator::setAmplitude, static_cast<uint32_t>(amplitude))
            .withDefault(Status::UNKNOWN_ERROR);
        if (status != Status::OK) {
            ALOGE("Failed to set vibrator amplitude (%" PRIu32 ").",
                  static_cast<uint32_t>(status));
        }
    }
}

static jboolean vibratorSupportsExternalControl(JNIEnv*, jclass) {
    if (auto hal = getHal<aidl::IVibrator>()) {
        int32_t cap = 0;
        if (!hal->call(&aidl::IVibrator::getCapabilities, &cap).isOk()) {
            return false;
        }
        return (cap & aidl::IVibrator::CAP_EXTERNAL_CONTROL) > 0;
    } else {
        return halCall(&V1_3::IVibrator::supportsExternalControl).withDefault(false);
    }
}

static void vibratorSetExternalControl(JNIEnv*, jclass, jboolean enabled) {
    if (auto hal = getHal<aidl::IVibrator>()) {
        auto status = hal->call(&aidl::IVibrator::IVibrator::setExternalControl, enabled);
        if (!status.isOk()) {
            ALOGE("Failed to set vibrator external control: %s", status.toString8().string());
        }
    } else {
        Status status = halCall(&V1_3::IVibrator::setExternalControl, static_cast<uint32_t>(enabled))
            .withDefault(Status::UNKNOWN_ERROR);
        if (status != Status::OK) {
            ALOGE("Failed to set vibrator external control (%" PRIu32 ").",
                static_cast<uint32_t>(status));
        }
    }
}

static jintArray vibratorGetSupportedEffects(JNIEnv *env, jclass) {
    if (auto hal = getHal<aidl::IVibrator>()) {
        std::vector<aidl::Effect> supportedEffects;
        if (!hal->call(&aidl::IVibrator::getSupportedEffects, &supportedEffects).isOk()) {
            return nullptr;
        }
        jintArray arr = env->NewIntArray(supportedEffects.size());
        env->SetIntArrayRegion(arr, 0, supportedEffects.size(),
                reinterpret_cast<jint*>(supportedEffects.data()));
        return arr;
    } else {
        return nullptr;
    }
}

static jlong vibratorPerformEffect(JNIEnv* env, jclass, jlong effect, jlong strength,
                                   jobject vibration, jboolean withCallback) {
    if (auto hal = getHal<aidl::IVibrator>()) {
        int32_t lengthMs;
        sp<AidlVibratorCallback> effectCallback =
                (withCallback != JNI_FALSE ? new AidlVibratorCallback(env, vibration) : nullptr);
        aidl::Effect effectType(static_cast<aidl::Effect>(effect));
        aidl::EffectStrength effectStrength(static_cast<aidl::EffectStrength>(strength));

        auto status = hal->call(&aidl::IVibrator::perform, effectType, effectStrength, effectCallback, &lengthMs);
        if (!status.isOk()) {
            if (status.exceptionCode() != binder::Status::EX_UNSUPPORTED_OPERATION) {
                ALOGE("Failed to perform haptic effect: effect=%" PRId64 ", strength=%" PRId32
                        ": %s", static_cast<int64_t>(effect), static_cast<int32_t>(strength), status.toString8().string());
            }
            return -1;
        }
        return lengthMs;
    } else {
        Status status;
        uint32_t lengthMs;
        auto callback = [&status, &lengthMs](Status retStatus, uint32_t retLengthMs) {
            status = retStatus;
            lengthMs = retLengthMs;
        };
        EffectStrength effectStrength(static_cast<EffectStrength>(strength));

        Return<void> ret;
        if (isValidEffect<V1_0::Effect>(effect)) {
            ret = halCall(&V1_0::IVibrator::perform, static_cast<V1_0::Effect>(effect),
                    effectStrength, callback);
        } else if (isValidEffect<Effect_1_1>(effect)) {
            ret = halCall(&V1_1::IVibrator::perform_1_1, static_cast<Effect_1_1>(effect),
                            effectStrength, callback);
        } else if (isValidEffect<V1_2::Effect>(effect)) {
            ret = halCall(&V1_2::IVibrator::perform_1_2, static_cast<V1_2::Effect>(effect),
                            effectStrength, callback);
        } else if (isValidEffect<V1_3::Effect>(effect)) {
            ret = halCall(&V1_3::IVibrator::perform_1_3, static_cast<V1_3::Effect>(effect),
                            effectStrength, callback);
        } else {
            ALOGW("Unable to perform haptic effect, invalid effect ID (%" PRId32 ")",
                    static_cast<int32_t>(effect));
            return -1;
        }

        if (!ret.isOk()) {
            ALOGW("Failed to perform effect (%" PRId32 ")", static_cast<int32_t>(effect));
            return -1;
        }

        if (status == Status::OK) {
            return lengthMs;
        } else if (status != Status::UNSUPPORTED_OPERATION) {
            // Don't warn on UNSUPPORTED_OPERATION, that's a normal event and just means the motor
            // doesn't have a pre-defined waveform to perform for it, so we should just give the
            // opportunity to fall back to the framework waveforms.
            ALOGE("Failed to perform haptic effect: effect=%" PRId64 ", strength=%" PRId32
                    ", error=%" PRIu32 ").", static_cast<int64_t>(effect),
                    static_cast<int32_t>(strength), static_cast<uint32_t>(status));
        }
    }

    return -1;
}

static aidl::CompositeEffect effectFromJavaPrimitive(JNIEnv* env, jobject primitive) {
    aidl::CompositeEffect effect;
    effect.primitive = static_cast<aidl::CompositePrimitive>(
            env->GetIntField(primitive, gPrimitiveClassInfo.id));
    effect.scale = static_cast<float>(env->GetFloatField(primitive, gPrimitiveClassInfo.scale));
    effect.delayMs = static_cast<int>(env->GetIntField(primitive, gPrimitiveClassInfo.delay));
    return effect;
}

static void vibratorPerformComposedEffect(JNIEnv* env, jclass, jobjectArray composition,
                                   jobject vibration) {
    auto hal = getHal<aidl::IVibrator>();
    if (!hal) {
        return;
    }
    size_t size = env->GetArrayLength(composition);
    std::vector<aidl::CompositeEffect> effects;
    for (size_t i = 0; i < size; i++) {
        jobject element = env->GetObjectArrayElement(composition, i);
        effects.push_back(effectFromJavaPrimitive(env, element));
    }
    sp<AidlVibratorCallback> effectCallback = new AidlVibratorCallback(env, vibration);

    auto status = hal->call(&aidl::IVibrator::compose, effects, effectCallback);
    if (!status.isOk()) {
        if (status.exceptionCode() != binder::Status::EX_UNSUPPORTED_OPERATION) {
            ALOGE("Failed to play haptic effect composition");
        }
    }
}

static jlong vibratorGetCapabilities(JNIEnv*, jclass) {
    if (auto hal = getHal<aidl::IVibrator>()) {
        int32_t cap = 0;
        if (!hal->call(&aidl::IVibrator::getCapabilities, &cap).isOk()) {
            return 0;
        }
        return cap;
    }

    return 0;
}

static void vibratorAlwaysOnEnable(JNIEnv* env, jclass, jlong id, jlong effect, jlong strength) {
    auto status = halCall(&aidl::IVibrator::alwaysOnEnable, id,
            static_cast<aidl::Effect>(effect), static_cast<aidl::EffectStrength>(strength));
    if (!status.isOk()) {
        ALOGE("vibratortAlwaysOnEnable command failed (%s).", status.toString8().string());
    }
}

static void vibratorAlwaysOnDisable(JNIEnv* env, jclass, jlong id) {
    auto status = halCall(&aidl::IVibrator::alwaysOnDisable, id);
    if (!status.isOk()) {
        ALOGE("vibratorAlwaysOnDisable command failed (%s).", status.toString8().string());
    }
}

static const JNINativeMethod method_table[] = {
        {"vibratorExists", "()Z", (void*)vibratorExists},
        {"vibratorInit", "()V", (void*)vibratorInit},
        {"vibratorOn", "(J)V", (void*)vibratorOn},
        {"vibratorOff", "()V", (void*)vibratorOff},
        {"vibratorSupportsAmplitudeControl", "()Z", (void*)vibratorSupportsAmplitudeControl},
        {"vibratorSetAmplitude", "(I)V", (void*)vibratorSetAmplitude},
        {"vibratorPerformEffect", "(JJLcom/android/server/VibratorService$Vibration;Z)J",
         (void*)vibratorPerformEffect},
        {"vibratorPerformComposedEffect",
         "([Landroid/os/VibrationEffect$Composition$PrimitiveEffect;Lcom/android/server/"
         "VibratorService$Vibration;)V",
         (void*)vibratorPerformComposedEffect},
        {"vibratorGetSupportedEffects", "()[I", (void*)vibratorGetSupportedEffects},
        {"vibratorSupportsExternalControl", "()Z", (void*)vibratorSupportsExternalControl},
        {"vibratorSetExternalControl", "(Z)V", (void*)vibratorSetExternalControl},
        {"vibratorGetCapabilities", "()J", (void*)vibratorGetCapabilities},
        {"vibratorAlwaysOnEnable", "(JJJ)V", (void*)vibratorAlwaysOnEnable},
        {"vibratorAlwaysOnDisable", "(J)V", (void*)vibratorAlwaysOnDisable},
};

int register_android_server_VibratorService(JNIEnv *env) {
    sMethodIdOnComplete = GetMethodIDOrDie(env,
            FindClassOrDie(env, "com/android/server/VibratorService$Vibration"),
            "onComplete", "()V");
    jclass primitiveClass = FindClassOrDie(env,
            "android/os/VibrationEffect$Composition$PrimitiveEffect");
    gPrimitiveClassInfo.id = GetFieldIDOrDie(env, primitiveClass, "id", "I");
    gPrimitiveClassInfo.scale = GetFieldIDOrDie(env, primitiveClass, "scale", "F");
    gPrimitiveClassInfo.delay = GetFieldIDOrDie(env, primitiveClass, "delay", "I");
    return jniRegisterNativeMethods(env, "com/android/server/VibratorService",
            method_table, NELEM(method_table));
}

};
