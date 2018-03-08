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
#define ENCODING_PCM_16BIT      2
#define ENCODING_PCM_8BIT       3
#define ENCODING_PCM_FLOAT      4
#define ENCODING_AC3            5
#define ENCODING_E_AC3          6
#define ENCODING_DTS            7
#define ENCODING_DTS_HD         8
#define ENCODING_MP3            9
#define ENCODING_AAC_LC         10
#define ENCODING_AAC_HE_V1      11
#define ENCODING_AAC_HE_V2      12
#define ENCODING_IEC61937       13
#define ENCODING_DOLBY_TRUEHD   14
#define ENCODING_AAC_ELD        15
#define ENCODING_AAC_XHE        16
#define ENCODING_AC4            17
#define ENCODING_E_AC3_JOC      18

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
    case ENCODING_DTS:
        return AUDIO_FORMAT_DTS;
    case ENCODING_DTS_HD:
        return AUDIO_FORMAT_DTS_HD;
    case ENCODING_MP3:
        return AUDIO_FORMAT_MP3;
    case ENCODING_AAC_LC:
        return AUDIO_FORMAT_AAC_LC;
    case ENCODING_AAC_HE_V1:
        return AUDIO_FORMAT_AAC_HE_V1;
    case ENCODING_AAC_HE_V2:
        return AUDIO_FORMAT_AAC_HE_V2;
    case ENCODING_DOLBY_TRUEHD:
        return AUDIO_FORMAT_DOLBY_TRUEHD;
    case ENCODING_IEC61937:
        return AUDIO_FORMAT_IEC61937;
    case ENCODING_AAC_ELD:
        return AUDIO_FORMAT_AAC_ELD;
    case ENCODING_AAC_XHE:
        return AUDIO_FORMAT_AAC_XHE;
    case ENCODING_AC4:
        return AUDIO_FORMAT_AC4;
    case ENCODING_E_AC3_JOC:
        return AUDIO_FORMAT_E_AC3_JOC;
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

    // map these to ENCODING_PCM_FLOAT
    case AUDIO_FORMAT_PCM_8_24_BIT:
    case AUDIO_FORMAT_PCM_24_BIT_PACKED:
    case AUDIO_FORMAT_PCM_32_BIT:
        return ENCODING_PCM_FLOAT;

    case AUDIO_FORMAT_AC3:
        return ENCODING_AC3;
    case AUDIO_FORMAT_E_AC3:
        return ENCODING_E_AC3;
    case AUDIO_FORMAT_DTS:
        return ENCODING_DTS;
    case AUDIO_FORMAT_DTS_HD:
        return ENCODING_DTS_HD;
    case AUDIO_FORMAT_MP3:
        return ENCODING_MP3;
    case AUDIO_FORMAT_AAC_LC:
        return ENCODING_AAC_LC;
    case AUDIO_FORMAT_AAC_HE_V1:
        return ENCODING_AAC_HE_V1;
    case AUDIO_FORMAT_AAC_HE_V2:
        return ENCODING_AAC_HE_V2;
    case AUDIO_FORMAT_IEC61937:
        return ENCODING_IEC61937;
    case AUDIO_FORMAT_DOLBY_TRUEHD:
        return ENCODING_DOLBY_TRUEHD;
    case AUDIO_FORMAT_AAC_ELD:
            return ENCODING_AAC_ELD;
    // FIXME needs addition of AUDIO_FORMAT_AAC_XHE
    //case AUDIO_FORMAT_AAC_XHE:
    //    return ENCODING_AAC_XHE;
    case AUDIO_FORMAT_AC4:
        return ENCODING_AC4;
    case AUDIO_FORMAT_E_AC3_JOC:
        return ENCODING_E_AC3_JOC;
    case AUDIO_FORMAT_DEFAULT:
        return ENCODING_DEFAULT;
    default:
        return ENCODING_INVALID;
    }
}

// This function converts Java channel masks to a native channel mask.
// validity should be checked with audio_is_output_channel().
static inline audio_channel_mask_t nativeChannelMaskFromJavaChannelMasks(
        jint channelPositionMask, jint channelIndexMask)
{
    // 0 is the java android.media.AudioFormat.CHANNEL_INVALID value
    if (channelIndexMask != 0) {  // channel index mask takes priority
        // To convert to a native channel mask, the Java channel index mask
        // requires adding the index representation.
        return audio_channel_mask_from_representation_and_bits(
                        AUDIO_CHANNEL_REPRESENTATION_INDEX,
                        channelIndexMask);
    }
    // To convert to a native channel mask, the Java channel position mask
    // requires a shift by 2 to skip the two deprecated channel
    // configurations "default" and "mono".
    return (audio_channel_mask_t)((uint32_t)channelPositionMask >> 2);
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
