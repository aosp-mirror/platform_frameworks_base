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
#define ENCODING_DOLBY_MAT      19
#define ENCODING_OPUS           20
#define ENCODING_PCM_24BIT_PACKED 21
#define ENCODING_PCM_32BIT 22
#define ENCODING_MPEGH_BL_L3 23
#define ENCODING_MPEGH_BL_L4 24
#define ENCODING_MPEGH_LC_L3 25
#define ENCODING_MPEGH_LC_L4 26
#define ENCODING_DTS_UHD_P1 27
#define ENCODING_DRA 28
#define ENCODING_DTS_HD_MA 29
#define ENCODING_DTS_UHD_P2 30
#define ENCODING_DSD 31
#define ENCODING_AC4_L4 32

#define ENCODING_INVALID    0
#define ENCODING_DEFAULT    1



#define CHANNEL_INVALID 0
#define CHANNEL_OUT_DEFAULT 1
#define CHANNEL_IN_DEFAULT 1

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
    case ENCODING_IEC61937:
        return AUDIO_FORMAT_IEC61937;
    case ENCODING_DOLBY_TRUEHD:
        return AUDIO_FORMAT_DOLBY_TRUEHD;
    case ENCODING_AAC_ELD:
        return AUDIO_FORMAT_AAC_ELD;
    case ENCODING_AAC_XHE:
        return AUDIO_FORMAT_AAC_XHE;
    case ENCODING_AC4:
        return AUDIO_FORMAT_AC4;
    case ENCODING_AC4_L4:
        return AUDIO_FORMAT_AC4_L4;
    case ENCODING_E_AC3_JOC:
        return AUDIO_FORMAT_E_AC3_JOC;
    case ENCODING_DEFAULT:
        return AUDIO_FORMAT_DEFAULT;
    case ENCODING_DOLBY_MAT:
        return AUDIO_FORMAT_MAT;
    case ENCODING_OPUS:
        return AUDIO_FORMAT_OPUS;
    case ENCODING_PCM_24BIT_PACKED:
        return AUDIO_FORMAT_PCM_24_BIT_PACKED;
    case ENCODING_PCM_32BIT:
        return AUDIO_FORMAT_PCM_32_BIT;
    case ENCODING_MPEGH_BL_L3:
        return AUDIO_FORMAT_MPEGH_BL_L3;
    case ENCODING_MPEGH_BL_L4:
        return AUDIO_FORMAT_MPEGH_BL_L4;
    case ENCODING_MPEGH_LC_L3:
        return AUDIO_FORMAT_MPEGH_LC_L3;
    case ENCODING_MPEGH_LC_L4:
        return AUDIO_FORMAT_MPEGH_LC_L4;
    case ENCODING_DTS_UHD_P1:
        return AUDIO_FORMAT_DTS_UHD;
    case ENCODING_DRA:
        return AUDIO_FORMAT_DRA;
    case ENCODING_DTS_HD_MA:
        return AUDIO_FORMAT_DTS_HD_MA;
    case ENCODING_DTS_UHD_P2:
        return AUDIO_FORMAT_DTS_UHD_P2;
    case ENCODING_DSD:
        return AUDIO_FORMAT_DSD;
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

    // As of S, these extend integer precision formats now return more specific values
    // than ENCODING_PCM_FLOAT.
    case AUDIO_FORMAT_PCM_24_BIT_PACKED:
        return ENCODING_PCM_24BIT_PACKED;
    case AUDIO_FORMAT_PCM_32_BIT:
        return ENCODING_PCM_32BIT;

    // map this to ENCODING_PCM_FLOAT
    case AUDIO_FORMAT_PCM_8_24_BIT:
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
    case AUDIO_FORMAT_AAC_XHE:
        return ENCODING_AAC_XHE;
    case AUDIO_FORMAT_AC4:
        return ENCODING_AC4;
    case AUDIO_FORMAT_AC4_L4:
        return ENCODING_AC4_L4;
    case AUDIO_FORMAT_E_AC3_JOC:
        return ENCODING_E_AC3_JOC;
    case AUDIO_FORMAT_MAT:
    case AUDIO_FORMAT_MAT_1_0:
    case AUDIO_FORMAT_MAT_2_0:
    case AUDIO_FORMAT_MAT_2_1:
        return ENCODING_DOLBY_MAT;
    case AUDIO_FORMAT_OPUS:
        return ENCODING_OPUS;
    case AUDIO_FORMAT_MPEGH_BL_L3:
        return ENCODING_MPEGH_BL_L3;
    case AUDIO_FORMAT_MPEGH_BL_L4:
        return ENCODING_MPEGH_BL_L4;
    case AUDIO_FORMAT_MPEGH_LC_L3:
        return ENCODING_MPEGH_LC_L3;
    case AUDIO_FORMAT_MPEGH_LC_L4:
        return ENCODING_MPEGH_LC_L4;
    case AUDIO_FORMAT_DTS_UHD:
        return ENCODING_DTS_UHD_P1;
    case AUDIO_FORMAT_DRA:
        return ENCODING_DRA;
    case AUDIO_FORMAT_DTS_HD_MA:
        return ENCODING_DTS_HD_MA;
    case AUDIO_FORMAT_DTS_UHD_P2:
        return ENCODING_DTS_UHD_P2;
    case AUDIO_FORMAT_DEFAULT:
        return ENCODING_DEFAULT;
    case AUDIO_FORMAT_DSD:
        return ENCODING_DSD;
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
    switch (channelMask) {
        case CHANNEL_IN_DEFAULT:
            return AUDIO_CHANNEL_NONE;
        default:
            return (audio_channel_mask_t)channelMask;
    }
}

static inline int inChannelMaskFromNative(audio_channel_mask_t nativeMask)
{
    switch (nativeMask) {
        case AUDIO_CHANNEL_NONE:
            return CHANNEL_IN_DEFAULT;
        default:
            return (int)nativeMask;
    }
}

#endif // ANDROID_MEDIA_AUDIOFORMAT_H
