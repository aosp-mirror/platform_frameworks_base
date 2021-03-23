/*
 * Copyright (C) 2020 The Android Open Source Project
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

#define LOG_TAG "VibratorController"

#include <android/hardware/vibrator/1.3/IVibrator.h>
#include <android/hardware/vibrator/IVibrator.h>

#include <nativehelper/JNIHelp.h>
#include "android_runtime/AndroidRuntime.h"
#include "core_jni_helpers.h"
#include "jni.h"

#include <utils/Log.h>
#include <utils/misc.h>

#include <vibratorservice/VibratorHalController.h>

#include "com_android_server_vibrator_VibratorManagerService.h"

namespace V1_0 = android::hardware::vibrator::V1_0;
namespace V1_3 = android::hardware::vibrator::V1_3;
namespace aidl = android::hardware::vibrator;

namespace android {

static JavaVM* sJvm = nullptr;
static jmethodID sMethodIdOnComplete;
static struct {
    jfieldID id;
    jfieldID scale;
    jfieldID delay;
} sPrimitiveClassInfo;

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
static_assert(static_cast<uint8_t>(V1_3::Effect::TICK) == static_cast<uint8_t>(aidl::Effect::TICK));
static_assert(static_cast<uint8_t>(V1_3::Effect::THUD) == static_cast<uint8_t>(aidl::Effect::THUD));
static_assert(static_cast<uint8_t>(V1_3::Effect::POP) == static_cast<uint8_t>(aidl::Effect::POP));
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

static std::shared_ptr<vibrator::HalController> findVibrator(int32_t vibratorId) {
    vibrator::ManagerHalController* manager =
            android_server_vibrator_VibratorManagerService_getManager();
    if (manager == nullptr) {
        return nullptr;
    }
    auto result = manager->getVibrator(vibratorId);
    return result.isOk() ? std::move(result.value()) : nullptr;
}

class VibratorControllerWrapper {
public:
    VibratorControllerWrapper(JNIEnv* env, int32_t vibratorId, jobject callbackListener)
          : mHal(findVibrator(vibratorId)),
            mVibratorId(vibratorId),
            mCallbackListener(env->NewGlobalRef(callbackListener)) {
        LOG_ALWAYS_FATAL_IF(mHal == nullptr,
                            "Failed to connect to vibrator HAL, or vibratorId is invalid");
        LOG_ALWAYS_FATAL_IF(mCallbackListener == nullptr,
                            "Unable to create global reference to vibration callback handler");
    }

    ~VibratorControllerWrapper() {
        auto jniEnv = GetOrAttachJNIEnvironment(sJvm);
        jniEnv->DeleteGlobalRef(mCallbackListener);
    }

    vibrator::HalController* hal() const { return mHal.get(); }

    std::function<void()> createCallback(jlong vibrationId) {
        return [vibrationId, this]() {
            auto jniEnv = GetOrAttachJNIEnvironment(sJvm);
            jniEnv->CallVoidMethod(mCallbackListener, sMethodIdOnComplete, mVibratorId,
                                   vibrationId);
        };
    }

private:
    const std::shared_ptr<vibrator::HalController> mHal;
    const int32_t mVibratorId;
    const jobject mCallbackListener;
};

static aidl::CompositeEffect effectFromJavaPrimitive(JNIEnv* env, jobject primitive) {
    aidl::CompositeEffect effect;
    effect.primitive = static_cast<aidl::CompositePrimitive>(
            env->GetIntField(primitive, sPrimitiveClassInfo.id));
    effect.scale = static_cast<float>(env->GetFloatField(primitive, sPrimitiveClassInfo.scale));
    effect.delayMs = static_cast<int32_t>(env->GetIntField(primitive, sPrimitiveClassInfo.delay));
    return effect;
}

static void destroyNativeWrapper(void* ptr) {
    VibratorControllerWrapper* wrapper = reinterpret_cast<VibratorControllerWrapper*>(ptr);
    if (wrapper) {
        delete wrapper;
    }
}

static jlong vibratorNativeInit(JNIEnv* env, jclass /* clazz */, jint vibratorId,
                                jobject callbackListener) {
    std::unique_ptr<VibratorControllerWrapper> wrapper =
            std::make_unique<VibratorControllerWrapper>(env, vibratorId, callbackListener);
    wrapper->hal()->init();
    return reinterpret_cast<jlong>(wrapper.release());
}

static jlong vibratorGetNativeFinalizer(JNIEnv* /* env */, jclass /* clazz */) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&destroyNativeWrapper));
}

static jboolean vibratorIsAvailable(JNIEnv* env, jclass /* clazz */, jlong ptr) {
    VibratorControllerWrapper* wrapper = reinterpret_cast<VibratorControllerWrapper*>(ptr);
    if (wrapper == nullptr) {
        ALOGE("vibratorIsAvailable failed because native wrapper was not initialized");
        return JNI_FALSE;
    }
    return wrapper->hal()->ping().isOk() ? JNI_TRUE : JNI_FALSE;
}

static void vibratorOn(JNIEnv* env, jclass /* clazz */, jlong ptr, jlong timeoutMs,
                       jlong vibrationId) {
    VibratorControllerWrapper* wrapper = reinterpret_cast<VibratorControllerWrapper*>(ptr);
    if (wrapper == nullptr) {
        ALOGE("vibratorOn failed because native wrapper was not initialized");
        return;
    }
    auto callback = wrapper->createCallback(vibrationId);
    wrapper->hal()->on(std::chrono::milliseconds(timeoutMs), callback);
}

static void vibratorOff(JNIEnv* env, jclass /* clazz */, jlong ptr) {
    VibratorControllerWrapper* wrapper = reinterpret_cast<VibratorControllerWrapper*>(ptr);
    if (wrapper == nullptr) {
        ALOGE("vibratorOff failed because native wrapper was not initialized");
        return;
    }
    wrapper->hal()->off();
}

static void vibratorSetAmplitude(JNIEnv* env, jclass /* clazz */, jlong ptr, jfloat amplitude) {
    VibratorControllerWrapper* wrapper = reinterpret_cast<VibratorControllerWrapper*>(ptr);
    if (wrapper == nullptr) {
        ALOGE("vibratorSetAmplitude failed because native wrapper was not initialized");
        return;
    }
    wrapper->hal()->setAmplitude(static_cast<float>(amplitude));
}

static void vibratorSetExternalControl(JNIEnv* env, jclass /* clazz */, jlong ptr,
                                       jboolean enabled) {
    VibratorControllerWrapper* wrapper = reinterpret_cast<VibratorControllerWrapper*>(ptr);
    if (wrapper == nullptr) {
        ALOGE("vibratorSetExternalControl failed because native wrapper was not initialized");
        return;
    }
    wrapper->hal()->setExternalControl(enabled);
}

static jintArray vibratorGetSupportedEffects(JNIEnv* env, jclass /* clazz */, jlong ptr) {
    VibratorControllerWrapper* wrapper = reinterpret_cast<VibratorControllerWrapper*>(ptr);
    if (wrapper == nullptr) {
        ALOGE("vibratorGetSupportedEffects failed because native wrapper was not initialized");
        return nullptr;
    }
    auto result = wrapper->hal()->getSupportedEffects();
    if (!result.isOk()) {
        return nullptr;
    }
    std::vector<aidl::Effect> supportedEffects = result.value();
    jintArray effects = env->NewIntArray(supportedEffects.size());
    env->SetIntArrayRegion(effects, 0, supportedEffects.size(),
                           reinterpret_cast<jint*>(supportedEffects.data()));
    return effects;
}

static jintArray vibratorGetSupportedPrimitives(JNIEnv* env, jclass /* clazz */, jlong ptr) {
    VibratorControllerWrapper* wrapper = reinterpret_cast<VibratorControllerWrapper*>(ptr);
    if (wrapper == nullptr) {
        ALOGE("vibratorGetSupportedPrimitives failed because native wrapper was not initialized");
        return nullptr;
    }
    auto result = wrapper->hal()->getSupportedPrimitives();
    if (!result.isOk()) {
        return nullptr;
    }
    std::vector<aidl::CompositePrimitive> supportedPrimitives = result.value();
    jintArray primitives = env->NewIntArray(supportedPrimitives.size());
    env->SetIntArrayRegion(primitives, 0, supportedPrimitives.size(),
                           reinterpret_cast<jint*>(supportedPrimitives.data()));
    return primitives;
}

static jlong vibratorPerformEffect(JNIEnv* env, jclass /* clazz */, jlong ptr, jlong effect,
                                   jlong strength, jlong vibrationId) {
    VibratorControllerWrapper* wrapper = reinterpret_cast<VibratorControllerWrapper*>(ptr);
    if (wrapper == nullptr) {
        ALOGE("vibratorPerformEffect failed because native wrapper was not initialized");
        return -1;
    }
    aidl::Effect effectType = static_cast<aidl::Effect>(effect);
    aidl::EffectStrength effectStrength = static_cast<aidl::EffectStrength>(strength);
    auto callback = wrapper->createCallback(vibrationId);
    auto result = wrapper->hal()->performEffect(effectType, effectStrength, callback);
    return result.isOk() ? result.value().count() : -1;
}

static jlong vibratorPerformComposedEffect(JNIEnv* env, jclass /* clazz */, jlong ptr,
                                           jobjectArray composition, jlong vibrationId) {
    VibratorControllerWrapper* wrapper = reinterpret_cast<VibratorControllerWrapper*>(ptr);
    if (wrapper == nullptr) {
        ALOGE("vibratorPerformComposedEffect failed because native wrapper was not initialized");
        return -1;
    }
    size_t size = env->GetArrayLength(composition);
    std::vector<aidl::CompositeEffect> effects;
    for (size_t i = 0; i < size; i++) {
        jobject element = env->GetObjectArrayElement(composition, i);
        effects.push_back(effectFromJavaPrimitive(env, element));
    }
    auto callback = wrapper->createCallback(vibrationId);
    auto result = wrapper->hal()->performComposedEffect(effects, callback);
    return result.isOk() ? result.value().count() : -1;
}

static jlong vibratorGetCapabilities(JNIEnv* env, jclass /* clazz */, jlong ptr) {
    VibratorControllerWrapper* wrapper = reinterpret_cast<VibratorControllerWrapper*>(ptr);
    if (wrapper == nullptr) {
        ALOGE("vibratorGetCapabilities failed because native wrapper was not initialized");
        return 0;
    }
    auto result = wrapper->hal()->getCapabilities();
    return result.isOk() ? static_cast<jlong>(result.value()) : 0;
}

static void vibratorAlwaysOnEnable(JNIEnv* env, jclass /* clazz */, jlong ptr, jlong id,
                                   jlong effect, jlong strength) {
    VibratorControllerWrapper* wrapper = reinterpret_cast<VibratorControllerWrapper*>(ptr);
    if (wrapper == nullptr) {
        ALOGE("vibratorAlwaysOnEnable failed because native wrapper was not initialized");
        return;
    }
    wrapper->hal()->alwaysOnEnable(static_cast<int32_t>(id), static_cast<aidl::Effect>(effect),
                                   static_cast<aidl::EffectStrength>(strength));
}

static void vibratorAlwaysOnDisable(JNIEnv* env, jclass /* clazz */, jlong ptr, jlong id) {
    VibratorControllerWrapper* wrapper = reinterpret_cast<VibratorControllerWrapper*>(ptr);
    if (wrapper == nullptr) {
        ALOGE("vibratorAlwaysOnDisable failed because native wrapper was not initialized");
        return;
    }
    wrapper->hal()->alwaysOnDisable(static_cast<int32_t>(id));
}

static float vibratorGetResonantFrequency(JNIEnv* env, jclass /* clazz */, jlong ptr) {
    VibratorControllerWrapper* wrapper = reinterpret_cast<VibratorControllerWrapper*>(ptr);
    if (wrapper == nullptr) {
        ALOGE("vibratorGetResonantFrequency failed because native wrapper was not initialized");
        return NAN;
    }
    auto result = wrapper->hal()->getResonantFrequency();
    return result.isOk() ? static_cast<jfloat>(result.value()) : NAN;
}

static float vibratorGetQFactor(JNIEnv* env, jclass /* clazz */, jlong ptr) {
    VibratorControllerWrapper* wrapper = reinterpret_cast<VibratorControllerWrapper*>(ptr);
    if (wrapper == nullptr) {
        ALOGE("vibratorGetQFactor failed because native wrapper was not initialized");
        return NAN;
    }
    auto result = wrapper->hal()->getQFactor();
    return result.isOk() ? static_cast<jfloat>(result.value()) : NAN;
}

static const JNINativeMethod method_table[] = {
        {"nativeInit",
         "(ILcom/android/server/vibrator/VibratorController$OnVibrationCompleteListener;)J",
         (void*)vibratorNativeInit},
        {"getNativeFinalizer", "()J", (void*)vibratorGetNativeFinalizer},
        {"isAvailable", "(J)Z", (void*)vibratorIsAvailable},
        {"on", "(JJJ)V", (void*)vibratorOn},
        {"off", "(J)V", (void*)vibratorOff},
        {"setAmplitude", "(JF)V", (void*)vibratorSetAmplitude},
        {"performEffect", "(JJJJ)J", (void*)vibratorPerformEffect},
        {"performComposedEffect", "(J[Landroid/os/vibrator/PrimitiveSegment;J)J",
         (void*)vibratorPerformComposedEffect},
        {"getSupportedEffects", "(J)[I", (void*)vibratorGetSupportedEffects},
        {"getSupportedPrimitives", "(J)[I", (void*)vibratorGetSupportedPrimitives},
        {"setExternalControl", "(JZ)V", (void*)vibratorSetExternalControl},
        {"getCapabilities", "(J)J", (void*)vibratorGetCapabilities},
        {"alwaysOnEnable", "(JJJJ)V", (void*)vibratorAlwaysOnEnable},
        {"alwaysOnDisable", "(JJ)V", (void*)vibratorAlwaysOnDisable},
        {"getResonantFrequency", "(J)F", (void*)vibratorGetResonantFrequency},
        {"getQFactor", "(J)F", (void*)vibratorGetQFactor},
};

int register_android_server_vibrator_VibratorController(JavaVM* jvm, JNIEnv* env) {
    sJvm = jvm;
    auto listenerClassName =
            "com/android/server/vibrator/VibratorController$OnVibrationCompleteListener";
    jclass listenerClass = FindClassOrDie(env, listenerClassName);
    sMethodIdOnComplete = GetMethodIDOrDie(env, listenerClass, "onComplete", "(IJ)V");

    jclass primitiveClass = FindClassOrDie(env, "android/os/vibrator/PrimitiveSegment");
    sPrimitiveClassInfo.id = GetFieldIDOrDie(env, primitiveClass, "mPrimitiveId", "I");
    sPrimitiveClassInfo.scale = GetFieldIDOrDie(env, primitiveClass, "mScale", "F");
    sPrimitiveClassInfo.delay = GetFieldIDOrDie(env, primitiveClass, "mDelay", "I");

    return jniRegisterNativeMethods(env,
                                    "com/android/server/vibrator/VibratorController$NativeWrapper",
                                    method_table, NELEM(method_table));
}

}; // namespace android
