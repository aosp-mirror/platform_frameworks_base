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
#include <android/hardware/vibrator/IVibrator.h>

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include "android_runtime/AndroidRuntime.h"
#include "core_jni_helpers.h"

#include <utils/misc.h>
#include <utils/Log.h>

#include <inttypes.h>

#include <vibratorservice/VibratorHalController.h>

namespace V1_0 = android::hardware::vibrator::V1_0;
namespace V1_1 = android::hardware::vibrator::V1_1;
namespace V1_2 = android::hardware::vibrator::V1_2;
namespace V1_3 = android::hardware::vibrator::V1_3;
namespace aidl = android::hardware::vibrator;

namespace android {

static JavaVM* sJvm = nullptr;

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

static inline void callVibrationOnComplete(jobject vibration) {
    if (vibration == nullptr) {
        return;
    }
    auto jniEnv = GetOrAttachJNIEnvironment(sJvm);
    jniEnv->CallVoidMethod(vibration, sMethodIdOnComplete);
    jniEnv->DeleteGlobalRef(vibration);
}

static aidl::CompositeEffect effectFromJavaPrimitive(JNIEnv* env, jobject primitive) {
    aidl::CompositeEffect effect;
    effect.primitive = static_cast<aidl::CompositePrimitive>(
            env->GetIntField(primitive, gPrimitiveClassInfo.id));
    effect.scale = static_cast<float>(env->GetFloatField(primitive, gPrimitiveClassInfo.scale));
    effect.delayMs = static_cast<int32_t>(env->GetIntField(primitive, gPrimitiveClassInfo.delay));
    return effect;
}

static void destroyVibratorController(void* rawVibratorController) {
    vibrator::HalController* vibratorController =
            reinterpret_cast<vibrator::HalController*>(rawVibratorController);
    if (vibratorController) {
        delete vibratorController;
    }
}

static jlong vibratorInit(JNIEnv* /* env */, jclass /* clazz */) {
    std::unique_ptr<vibrator::HalController> controller =
            std::make_unique<vibrator::HalController>();
    controller->init();
    return reinterpret_cast<jlong>(controller.release());
}

static jlong vibratorGetFinalizer(JNIEnv* /* env */, jclass /* clazz */) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&destroyVibratorController));
}

static jboolean vibratorExists(JNIEnv* env, jclass /* clazz */, jlong controllerPtr) {
    vibrator::HalController* controller = reinterpret_cast<vibrator::HalController*>(controllerPtr);
    if (controller == nullptr) {
        ALOGE("vibratorExists failed because controller was not initialized");
        return JNI_FALSE;
    }
    return controller->ping().isOk() ? JNI_TRUE : JNI_FALSE;
}

static void vibratorOn(JNIEnv* env, jclass /* clazz */, jlong controllerPtr, jlong timeoutMs,
                       jobject vibration) {
    vibrator::HalController* controller = reinterpret_cast<vibrator::HalController*>(controllerPtr);
    if (controller == nullptr) {
        ALOGE("vibratorOn failed because controller was not initialized");
        return;
    }
    jobject vibrationRef = vibration == nullptr ? vibration : MakeGlobalRefOrDie(env, vibration);
    auto callback = [vibrationRef]() { callVibrationOnComplete(vibrationRef); };
    controller->on(std::chrono::milliseconds(timeoutMs), callback);
}

static void vibratorOff(JNIEnv* env, jclass /* clazz */, jlong controllerPtr) {
    vibrator::HalController* controller = reinterpret_cast<vibrator::HalController*>(controllerPtr);
    if (controller == nullptr) {
        ALOGE("vibratorOff failed because controller was not initialized");
        return;
    }
    controller->off();
}

static void vibratorSetAmplitude(JNIEnv* env, jclass /* clazz */, jlong controllerPtr,
                                 jint amplitude) {
    vibrator::HalController* controller = reinterpret_cast<vibrator::HalController*>(controllerPtr);
    if (controller == nullptr) {
        ALOGE("vibratorSetAmplitude failed because controller was not initialized");
        return;
    }
    controller->setAmplitude(static_cast<int32_t>(amplitude));
}

static void vibratorSetExternalControl(JNIEnv* env, jclass /* clazz */, jlong controllerPtr,
                                       jboolean enabled) {
    vibrator::HalController* controller = reinterpret_cast<vibrator::HalController*>(controllerPtr);
    if (controller == nullptr) {
        ALOGE("vibratorSetExternalControl failed because controller was not initialized");
        return;
    }
    controller->setExternalControl(enabled);
}

static jintArray vibratorGetSupportedEffects(JNIEnv* env, jclass /* clazz */, jlong controllerPtr) {
    vibrator::HalController* controller = reinterpret_cast<vibrator::HalController*>(controllerPtr);
    if (controller == nullptr) {
        ALOGE("vibratorGetSupportedEffects failed because controller was not initialized");
        return nullptr;
    }
    auto result = controller->getSupportedEffects();
    if (!result.isOk()) {
        return nullptr;
    }
    std::vector<aidl::Effect> supportedEffects = result.value();
    jintArray effects = env->NewIntArray(supportedEffects.size());
    env->SetIntArrayRegion(effects, 0, supportedEffects.size(),
                           reinterpret_cast<jint*>(supportedEffects.data()));
    return effects;
}

static jintArray vibratorGetSupportedPrimitives(JNIEnv* env, jclass /* clazz */,
                                                jlong controllerPtr) {
    vibrator::HalController* controller = reinterpret_cast<vibrator::HalController*>(controllerPtr);
    if (controller == nullptr) {
        ALOGE("vibratorGetSupportedPrimitives failed because controller was not initialized");
        return nullptr;
    }
    auto result = controller->getSupportedPrimitives();
    if (!result.isOk()) {
        return nullptr;
    }
    std::vector<aidl::CompositePrimitive> supportedPrimitives = result.value();
    jintArray primitives = env->NewIntArray(supportedPrimitives.size());
    env->SetIntArrayRegion(primitives, 0, supportedPrimitives.size(),
                           reinterpret_cast<jint*>(supportedPrimitives.data()));
    return primitives;
}

static jlong vibratorPerformEffect(JNIEnv* env, jclass /* clazz */, jlong controllerPtr,
                                   jlong effect, jlong strength, jobject vibration) {
    vibrator::HalController* controller = reinterpret_cast<vibrator::HalController*>(controllerPtr);
    if (controller == nullptr) {
        ALOGE("vibratorPerformEffect failed because controller was not initialized");
        return -1;
    }
    aidl::Effect effectType = static_cast<aidl::Effect>(effect);
    aidl::EffectStrength effectStrength = static_cast<aidl::EffectStrength>(strength);
    jobject vibrationRef = vibration == nullptr ? vibration : MakeGlobalRefOrDie(env, vibration);
    auto callback = [vibrationRef]() { callVibrationOnComplete(vibrationRef); };
    auto result = controller->performEffect(effectType, effectStrength, callback);
    return result.isOk() ? result.value().count() : -1;
}

static void vibratorPerformComposedEffect(JNIEnv* env, jclass /* clazz */, jlong controllerPtr,
                                          jobjectArray composition, jobject vibration) {
    vibrator::HalController* controller = reinterpret_cast<vibrator::HalController*>(controllerPtr);
    if (controller == nullptr) {
        ALOGE("vibratorPerformComposedEffect failed because controller was not initialized");
        return;
    }
    size_t size = env->GetArrayLength(composition);
    std::vector<aidl::CompositeEffect> effects;
    for (size_t i = 0; i < size; i++) {
        jobject element = env->GetObjectArrayElement(composition, i);
        effects.push_back(effectFromJavaPrimitive(env, element));
    }
    jobject vibrationRef = vibration == nullptr ? vibration : MakeGlobalRefOrDie(env, vibration);
    auto callback = [vibrationRef]() { callVibrationOnComplete(vibrationRef); };
    controller->performComposedEffect(effects, callback);
}

static jlong vibratorGetCapabilities(JNIEnv* env, jclass /* clazz */, jlong controllerPtr) {
    vibrator::HalController* controller = reinterpret_cast<vibrator::HalController*>(controllerPtr);
    if (controller == nullptr) {
        ALOGE("vibratorGetCapabilities failed because controller was not initialized");
        return 0;
    }
    auto result = controller->getCapabilities();
    return result.isOk() ? static_cast<jlong>(result.value()) : 0;
}

static void vibratorAlwaysOnEnable(JNIEnv* env, jclass /* clazz */, jlong controllerPtr, jlong id,
                                   jlong effect, jlong strength) {
    vibrator::HalController* controller = reinterpret_cast<vibrator::HalController*>(controllerPtr);
    if (controller == nullptr) {
        ALOGE("vibratorAlwaysOnEnable failed because controller was not initialized");
        return;
    }
    controller->alwaysOnEnable(static_cast<int32_t>(id), static_cast<aidl::Effect>(effect),
                               static_cast<aidl::EffectStrength>(strength));
}

static void vibratorAlwaysOnDisable(JNIEnv* env, jclass /* clazz */, jlong controllerPtr,
                                    jlong id) {
    vibrator::HalController* controller = reinterpret_cast<vibrator::HalController*>(controllerPtr);
    if (controller == nullptr) {
        ALOGE("vibratorAlwaysOnDisable failed because controller was not initialized");
        return;
    }
    controller->alwaysOnDisable(static_cast<int32_t>(id));
}

static const JNINativeMethod method_table[] = {
        {"vibratorInit", "()J", (void*)vibratorInit},
        {"vibratorGetFinalizer", "()J", (void*)vibratorGetFinalizer},
        {"vibratorExists", "(J)Z", (void*)vibratorExists},
        {"vibratorOn", "(JJLcom/android/server/VibratorService$Vibration;)V", (void*)vibratorOn},
        {"vibratorOff", "(J)V", (void*)vibratorOff},
        {"vibratorSetAmplitude", "(JI)V", (void*)vibratorSetAmplitude},
        {"vibratorPerformEffect", "(JJJLcom/android/server/VibratorService$Vibration;)J",
         (void*)vibratorPerformEffect},
        {"vibratorPerformComposedEffect",
         "(J[Landroid/os/VibrationEffect$Composition$PrimitiveEffect;Lcom/android/server/"
         "VibratorService$Vibration;)V",
         (void*)vibratorPerformComposedEffect},
        {"vibratorGetSupportedEffects", "(J)[I", (void*)vibratorGetSupportedEffects},
        {"vibratorGetSupportedPrimitives", "(J)[I", (void*)vibratorGetSupportedPrimitives},
        {"vibratorSetExternalControl", "(JZ)V", (void*)vibratorSetExternalControl},
        {"vibratorGetCapabilities", "(J)J", (void*)vibratorGetCapabilities},
        {"vibratorAlwaysOnEnable", "(JJJJ)V", (void*)vibratorAlwaysOnEnable},
        {"vibratorAlwaysOnDisable", "(JJ)V", (void*)vibratorAlwaysOnDisable},
};

int register_android_server_VibratorService(JavaVM* vm, JNIEnv* env) {
    sJvm = vm;
    sMethodIdOnComplete =
            GetMethodIDOrDie(env,
                             FindClassOrDie(env, "com/android/server/VibratorService$Vibration"),
                             "onComplete", "()V");

    jclass primitiveClass =
            FindClassOrDie(env, "android/os/VibrationEffect$Composition$PrimitiveEffect");
    gPrimitiveClassInfo.id = GetFieldIDOrDie(env, primitiveClass, "id", "I");
    gPrimitiveClassInfo.scale = GetFieldIDOrDie(env, primitiveClass, "scale", "F");
    gPrimitiveClassInfo.delay = GetFieldIDOrDie(env, primitiveClass, "delay", "I");

    return jniRegisterNativeMethods(env, "com/android/server/VibratorService", method_table,
                                    NELEM(method_table));
}

}; // namespace android
