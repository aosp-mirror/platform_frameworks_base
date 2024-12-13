/*
 * Copyright (C) 2024 The Android Open Source Project
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

#define LOG_TAG "UsbAlsaDeviceJNI"

#include <nativehelper/JNIPlatformHelp.h>
#include <tinyalsa/asoundlib.h>

#include <string>
#include <vector>

#include "jni.h"
#include "utils/Log.h"

static const std::vector<std::string> POSSIBLE_HARDWARE_VOLUME_MIXER_NAMES =
        {"Headphone Playback Volume", "Headset Playback Volume", "PCM Playback Volume"};

namespace android {

static void android_server_UsbAlsaDevice_setVolume(JNIEnv* /*env*/, jobject /*thiz*/, jint card,
                                                   float volume) {
    ALOGD("%s(%d, %f)", __func__, card, volume);
    struct mixer* alsaMixer = mixer_open(card);
    if (alsaMixer == nullptr) {
        ALOGW("%s(%d, %f) returned as no mixer is opened", __func__, card, volume);
        return;
    }
    struct mixer_ctl* ctl = nullptr;
    for (const auto& mixerName : POSSIBLE_HARDWARE_VOLUME_MIXER_NAMES) {
        ctl = mixer_get_ctl_by_name(alsaMixer, mixerName.c_str());
        if (ctl != nullptr) {
            break;
        }
    }
    if (ctl == nullptr) {
        ALOGW("%s(%d, %f) returned as no volume mixer is found", __func__, card, volume);
        return;
    }
    const unsigned int n = mixer_ctl_get_num_values(ctl);
    for (unsigned int id = 0; id < n; id++) {
        if (int error = mixer_ctl_set_percent(ctl, id, 100 * volume); error != 0) {
            ALOGE("%s(%d, %f) failed, error=%d", __func__, card, volume, error);
            return;
        }
    }
    ALOGD("%s(%d, %f) succeed", __func__, card, volume);
}

static JNINativeMethod method_table[] = {
        {"nativeSetVolume", "(IF)V", (void*)android_server_UsbAlsaDevice_setVolume},
};

int register_android_server_UsbAlsaDevice(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "com/android/server/usb/UsbAlsaDevice", method_table,
                                    NELEM(method_table));
}
} // namespace android
