/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef ANDROID_AUDIOCOMMON_H_
#define ANDROID_AUDIOCOMMON_H_

#if __cplusplus
extern "C" {
#endif

/////////////////////////////////////////////////
//      Common definitions for PCM audio
/////////////////////////////////////////////////


// PCM Sample format
enum audio_format_e {
    PCM_FORMAT_S15 = 1,     // PCM signed 16 bits, must be 1 for backward compatibility
    PCM_FORMAT_U8 = 2,      // PCM unsigned 8 bits, must be 2 for backward compatibility
    PCM_FORMAT_S7_24        // signed 7.24 fixed point representation
};

// Channel mask definitions
enum audio_channels_e {
    CHANNEL_FRONT_LEFT = 0x4,                   // front left channel
    CHANNEL_FRONT_RIGHT = 0x8,                  // front right channel
    CHANNEL_FRONT_CENTER = 0x10,                // front center channel
    CHANNEL_LOW_FREQUENCY = 0x20,               // low frequency channel
    CHANNEL_BACK_LEFT = 0x40,                   // back left channel
    CHANNEL_BACK_RIGHT = 0x80,                  // back right channel
    CHANNEL_FRONT_LEFT_OF_CENTER = 0x100,       // front left of center channel
    CHANNEL_FRONT_RIGHT_OF_CENTER = 0x200,      // front right of center channel
    CHANNEL_BACK_CENTER = 0x400,                // back center channel
    CHANNEL_MONO = CHANNEL_FRONT_LEFT,
    CHANNEL_STEREO = (CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT),
    CHANNEL_QUAD = (CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT |
            CHANNEL_BACK_LEFT | CHANNEL_BACK_RIGHT),
    CHANNEL_SURROUND = (CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT |
            CHANNEL_FRONT_CENTER | CHANNEL_BACK_CENTER),
    CHANNEL_5POINT1 = (CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT |
            CHANNEL_FRONT_CENTER | CHANNEL_LOW_FREQUENCY | CHANNEL_BACK_LEFT | CHANNEL_BACK_RIGHT),
    CHANNEL_7POINT1 = (CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT |
            CHANNEL_FRONT_CENTER | CHANNEL_LOW_FREQUENCY | CHANNEL_BACK_LEFT | CHANNEL_BACK_RIGHT |
            CHANNEL_FRONT_LEFT_OF_CENTER | CHANNEL_FRONT_RIGHT_OF_CENTER),
};

// Render device definitions
enum audio_device_e {
    DEVICE_EARPIECE = 0x1,                      // earpiece
    DEVICE_SPEAKER = 0x2,                       // speaker
    DEVICE_WIRED_HEADSET = 0x4,                 // wired headset, with microphone
    DEVICE_WIRED_HEADPHONE = 0x8,               // wired headphone, without microphone
    DEVICE_BLUETOOTH_SCO = 0x10,                // generic bluetooth SCO
    DEVICE_BLUETOOTH_SCO_HEADSET = 0x20,        // bluetooth SCO headset
    DEVICE_BLUETOOTH_SCO_CARKIT = 0x40,         // bluetooth SCO car kit
    DEVICE_BLUETOOTH_A2DP = 0x80,               // generic bluetooth A2DP
    DEVICE_BLUETOOTH_A2DP_HEADPHONES = 0x100,   // bluetooth A2DP headphones
    DEVICE_BLUETOOTH_A2DP_SPEAKER = 0x200,      // bluetooth A2DP speakers
    DEVICE_AUX_DIGITAL = 0x400                  // digital output
};

#if __cplusplus
}  // extern "C"
#endif


#endif /*ANDROID_AUDIOCOMMON_H_*/
