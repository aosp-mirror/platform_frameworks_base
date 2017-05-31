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

#include <android/hardware/vibrator/1.0/IVibrator.h>
#include <android/hardware/vibrator/1.0/types.h>
#include <android/hardware/vibrator/1.1/IVibrator.h>

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"

#include <utils/misc.h>
#include <utils/Log.h>
#include <hardware/vibrator.h>

#include <inttypes.h>
#include <stdio.h>

using android::hardware::Return;
using android::hardware::vibrator::V1_0::Effect;
using android::hardware::vibrator::V1_0::EffectStrength;
using android::hardware::vibrator::V1_0::IVibrator;
using android::hardware::vibrator::V1_0::Status;
using android::hardware::vibrator::V1_1::Effect_1_1;
using IVibrator_1_1 = android::hardware::vibrator::V1_1::IVibrator;

namespace android
{

static sp<IVibrator> mHal;

static void vibratorInit(JNIEnv /* env */, jobject /* clazz */)
{
    /* TODO(b/31632518) */
    if (mHal != nullptr) {
        return;
    }
    mHal = IVibrator::getService();
}

static jboolean vibratorExists(JNIEnv* /* env */, jobject /* clazz */)
{
    if (mHal != nullptr) {
        return JNI_TRUE;
    } else {
        return JNI_FALSE;
    }
}

static void vibratorOn(JNIEnv* /* env */, jobject /* clazz */, jlong timeout_ms)
{
    if (mHal != nullptr) {
        Status retStatus = mHal->on(timeout_ms);
        if (retStatus != Status::OK) {
            ALOGE("vibratorOn command failed (%" PRIu32 ").", static_cast<uint32_t>(retStatus));
        }
    } else {
        ALOGW("Tried to vibrate but there is no vibrator device.");
    }
}

static void vibratorOff(JNIEnv* /* env */, jobject /* clazz */)
{
    if (mHal != nullptr) {
        Status retStatus = mHal->off();
        if (retStatus != Status::OK) {
            ALOGE("vibratorOff command failed (%" PRIu32 ").", static_cast<uint32_t>(retStatus));
        }
    } else {
        ALOGW("Tried to stop vibrating but there is no vibrator device.");
    }
}

static jlong vibratorSupportsAmplitudeControl(JNIEnv*, jobject) {
    if (mHal != nullptr) {
        return mHal->supportsAmplitudeControl();
    } else {
        ALOGW("Unable to get max vibration amplitude, there is no vibrator device.");
    }
    return false;
}

static void vibratorSetAmplitude(JNIEnv*, jobject, jint amplitude) {
    if (mHal != nullptr) {
        Status status = mHal->setAmplitude(static_cast<uint32_t>(amplitude));
        if (status != Status::OK) {
            ALOGE("Failed to set vibrator amplitude (%" PRIu32 ").",
                    static_cast<uint32_t>(status));
        }
    } else {
        ALOGW("Unable to set vibration amplitude, there is no vibrator device.");
    }
}

static jlong vibratorPerformEffect(JNIEnv*, jobject, jlong effect, jint strength) {
    if (mHal != nullptr) {
        Status status;
        uint32_t lengthMs;
        auto callback = [&status, &lengthMs](Status retStatus, uint32_t retLengthMs) {
            status = retStatus;
            lengthMs = retLengthMs;
        };
        EffectStrength effectStrength(static_cast<EffectStrength>(strength));

        if (effect < 0  || effect > static_cast<uint32_t>(Effect_1_1::TICK)) {
            ALOGW("Unable to perform haptic effect, invalid effect ID (%" PRId32 ")",
                    static_cast<int32_t>(effect));
        } else if (effect == static_cast<uint32_t>(Effect_1_1::TICK)) {
            sp<IVibrator_1_1> hal_1_1 = IVibrator_1_1::castFrom(mHal);
            if (hal_1_1 != nullptr) {
                hal_1_1->perform_1_1(static_cast<Effect_1_1>(effect), effectStrength, callback);
            } else {
                ALOGW("Failed to perform effect (%" PRId32 "), insufficient HAL version",
                        static_cast<int32_t>(effect));
            }
        } else {
            mHal->perform(static_cast<Effect>(effect), effectStrength, callback);
        }
        if (status == Status::OK) {
            return lengthMs;
        } else if (status != Status::UNSUPPORTED_OPERATION) {
            // Don't warn on UNSUPPORTED_OPERATION, that's a normal even and just means the motor
            // doesn't have a pre-defined waveform to perform for it, so we should just fall back
            // to the framework waveforms.
            ALOGE("Failed to perform haptic effect: effect=%" PRId64 ", strength=%" PRId32
                    ", error=%" PRIu32 ").", static_cast<int64_t>(effect),
                    static_cast<int32_t>(strength), static_cast<uint32_t>(status));
        }
    } else {
        ALOGW("Unable to perform haptic effect, there is no vibrator device.");
    }
    return -1;
}

static const JNINativeMethod method_table[] = {
    { "vibratorExists", "()Z", (void*)vibratorExists },
    { "vibratorInit", "()V", (void*)vibratorInit },
    { "vibratorOn", "(J)V", (void*)vibratorOn },
    { "vibratorOff", "()V", (void*)vibratorOff },
    { "vibratorSupportsAmplitudeControl", "()Z", (void*)vibratorSupportsAmplitudeControl},
    { "vibratorSetAmplitude", "(I)V", (void*)vibratorSetAmplitude},
    { "vibratorPerformEffect", "(JJ)J", (void*)vibratorPerformEffect}
};

int register_android_server_VibratorService(JNIEnv *env)
{
    return jniRegisterNativeMethods(env, "com/android/server/VibratorService",
            method_table, NELEM(method_table));
}

};
