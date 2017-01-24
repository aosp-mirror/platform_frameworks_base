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

#define LOG_TAG "LightsService"

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"

#include <android/hardware/light/2.0/ILight.h>
#include <android/hardware/light/2.0/types.h>
#include <utils/misc.h>
#include <utils/Log.h>
#include <map>
#include <stdio.h>

namespace android {

using ILight     = ::android::hardware::light::V2_0::ILight;
using Brightness = ::android::hardware::light::V2_0::Brightness;
using Flash      = ::android::hardware::light::V2_0::Flash;
using Type       = ::android::hardware::light::V2_0::Type;
using LightState = ::android::hardware::light::V2_0::LightState;
using Status     = ::android::hardware::light::V2_0::Status;
template<typename T>
using Return     = ::android::hardware::Return<T>;

static sp<ILight> gLight;

static bool validate(jint light, jint flash, jint brightness) {
    bool valid = true;

    if (light < 0 || light >= static_cast<int>(Type::COUNT)) {
        ALOGE("Invalid light parameter %d.", light);
        valid = false;
    }

    if (flash != static_cast<int>(Flash::NONE) &&
        flash != static_cast<int>(Flash::TIMED) &&
        flash != static_cast<int>(Flash::HARDWARE)) {
        ALOGE("Invalid flash parameter %d.", flash);
        valid = false;
    }

    if (brightness != static_cast<int>(Brightness::USER) &&
        brightness != static_cast<int>(Brightness::SENSOR) &&
        brightness != static_cast<int>(Brightness::LOW_PERSISTENCE)) {
        ALOGE("Invalid brightness parameter %d.", brightness);
        valid = false;
    }

    return valid;
}

static void setLight_native(
        JNIEnv* /* env */,
        jobject /* clazz */,
        jint light,
        jint colorARGB,
        jint flashMode,
        jint onMS,
        jint offMS,
        jint brightnessMode) {

    if (!validate(light, flashMode, brightnessMode)) {
        return;
    }

    // TODO(b/31632518)
    if (gLight == nullptr) {
        gLight = ILight::getService();
    }

    if (gLight == nullptr) {
        ALOGE("LightService unable to get ILight interface.");
        return;
    }

    Type type = static_cast<Type>(light);
    Flash flash = static_cast<Flash>(flashMode);
    Brightness brightness = static_cast<Brightness>(brightnessMode);

    LightState state{};

    if (brightnessMode == static_cast<int>(Brightness::LOW_PERSISTENCE)) {
        if (light != static_cast<int>(Type::BACKLIGHT)) {
            ALOGE("Cannot set low-persistence mode for non-backlight device.");
            return;
        }
        state.flashMode = Flash::NONE;
    } else {
        // Only set non-brightness settings when not in low-persistence mode
        state.flashMode = flash;
        state.flashOnMs = onMS;
        state.flashOffMs = offMS;
    }

    state.color = colorARGB;
    state.brightnessMode = brightness;

    Status status;
    {
        ALOGD_IF_SLOW(50, "Excessive delay setting light");
        Return<Status> ret = gLight->setLight(type, state);

        if (!ret.isOk()) {
            ALOGE("Failed to issue set light command.");
            return;
        }

        status = static_cast<Status>(ret); // hal status
    }

    switch (status) {
        case Status::SUCCESS:
            break;
        case Status::LIGHT_NOT_SUPPORTED:
            ALOGE("Light requested not availale on this device.");
            break;
        case Status::BRIGHTNESS_NOT_SUPPORTED:
            ALOGE("Brightness parameter not supported on this device.");
            break;
        case Status::UNKNOWN:
        default:
            ALOGE("Unknown error setting light.");
    }
}

static const JNINativeMethod method_table[] = {
    { "setLight_native", "(IIIIII)V", (void*)setLight_native },
};

int register_android_server_LightsService(JNIEnv *env) {
    return jniRegisterNativeMethods(env, "com/android/server/lights/LightsService",
            method_table, NELEM(method_table));
}

};
