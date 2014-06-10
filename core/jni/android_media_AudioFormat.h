/*
 * Copyright (C) 2014 The Android Open Source Project
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

#ifndef ANDROID_MEDIA_AUDIOFORMAT_H
#define ANDROID_MEDIA_AUDIOFORMAT_H

#include <system/audio.h>

// keep these values in sync with AudioFormat.java
#define ENCODING_PCM_16BIT  2
#define ENCODING_PCM_8BIT   3
#define ENCODING_PCM_FLOAT  4
#define ENCODING_AC3        5
#define ENCODING_E_AC3      6
#define ENCODING_INVALID    0
#define ENCODING_DEFAULT    1



#define CHANNEL_INVALID 0
#define CHANNEL_OUT_DEFAULT 1

static inline audio_format_t audioFormatToNative(int audioFormat)
{
    switch (audioFormat) {
    case ENCODING_PCM_16BIT:
        return AUDIO_FORMAT_PCM_16_BIT;
    case ENCODING_PCM_8BIT:
        return AUDIO_FORMAT_PCM_8_BIT;
    case ENCODING_PCM_FLOAT:
        return AUDIO_FORMAT_PCM_FLOAT;
    case ENCODING_AC3:
        return AUDIO_FORMAT_AC3;
    case ENCODING_E_AC3:
        return AUDIO_FORMAT_E_AC3;
    case ENCODING_DEFAULT:
        return AUDIO_FORMAT_DEFAULT;
    default:
        return AUDIO_FORMAT_INVALID;
    }
}

static inline int audioFormatFromNative(audio_format_t nativeFormat)
{
    switch (nativeFormat) {
    case AUDIO_FORMAT_PCM_16_BIT:
        return ENCODING_PCM_16BIT;
    case AUDIO_FORMAT_PCM_8_BIT:
        return ENCODING_PCM_8BIT;
    case AUDIO_FORMAT_PCM_FLOAT:
        return ENCODING_PCM_FLOAT;
    case AUDIO_FORMAT_AC3:
        return ENCODING_AC3;
    case AUDIO_FORMAT_E_AC3:
        return ENCODING_E_AC3;
    case AUDIO_FORMAT_DEFAULT:
        return ENCODING_DEFAULT;
    default:
        return ENCODING_INVALID;
    }
}

static inline audio_channel_mask_t outChannelMaskToNative(int channelMask)
{
    switch (channelMask) {
    case CHANNEL_OUT_DEFAULT:
    case CHANNEL_INVALID:
        return AUDIO_CHANNEL_NONE;
    default:
        return (audio_channel_mask_t)(channelMask>>2);
    }
}

static inline int outChannelMaskFromNative(audio_channel_mask_t nativeMask)
{
    switch (nativeMask) {
    case AUDIO_CHANNEL_NONE:
        return CHANNEL_OUT_DEFAULT;
    default:
        return (int)nativeMask<<2;
    }
}

static inline audio_channel_mask_t inChannelMaskToNative(int channelMask)
{
    return (audio_channel_mask_t)channelMask;
}

static inline int inChannelMaskFromNative(audio_channel_mask_t nativeMask)
{
    return (int)nativeMask;
}

#endif // ANDROID_MEDIA_AUDIOFORMAT_H
