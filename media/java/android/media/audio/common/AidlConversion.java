/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.media.audio.common;

import android.annotation.NonNull;
import android.media.AudioFormat;

/**
 * This class provides utility functions for converting between
 * the AIDL types defined in 'android.media.audio.common' and:
 *  - SDK (Java API) types from 'android.media';
 *  - legacy native (system/audio.h) types.
 *
 * Methods that convert between AIDL and SDK types are called
 * using the following pattern:
 *
 *  aidl2api_AIDL-type-name_SDK-type-name
 *  api2aidl_SDK-type-name_AIDL-type-name
 *
 * Methods that convert between AIDL and legacy types are called
 * using the following pattern:
 *
 *  aidl2legacy_AIDL-type-name_native-type-name
 *  legacy2aidl_native-type-name_AIDL-type-name
 *
 * @hide
 */
public class AidlConversion {
    /** Convert from legacy audio_channel_mask_t to AIDL AudioChannelMask. */
    public static int legacy2aidl_audio_channel_mask_t_AudioChannelMask(
            int /*audio_channel_mask_t*/ legacy) {
        // Relies on the fact that AudioChannelMask was converted from
        // the HIDL definition which uses the same constant values as system/audio.h
        return legacy;
    }

    /** Convert from legacy audio_format_t to AIDL AudioFormat. */
    public static int legacy2aidl_audio_format_t_AudioFormat(int /*audio_format_t*/ legacy) {
        // Relies on the fact that AudioFormat was converted from
        // the HIDL definition which uses the same constant values as system/audio.h
        return legacy;
    }

    /** Convert from legacy audio_stream_type_t to AIDL AudioStreamType. */
    public static int legacy2aidl_audio_stream_type_t_AudioStreamType(
            int /*audio_stream_type_t*/ legacy) {
        // Relies on the fact that AudioStreamType was converted from
        // the HIDL definition which uses the same constant values as system/audio.h
        return legacy;
    }

    /** Convert from legacy audio_usage_t to AIDL AudioUsage. */
    public static int legacy2aidl_audio_usage_t_AudioUsage(int /*audio_usage_t*/ legacy) {
        // Relies on the fact that AudioUsage was converted from
        // the HIDL definition which uses the same constant values as system/audio.h
        return legacy;
    }

    /** Convert from AIDL AudioChannelMask to SDK AudioFormat.CHANNEL_IN_*. */
    public static int aidl2api_AudioChannelMask_AudioFormatChannelInMask(int aidlMask) {
        // We're assuming AudioFormat.CHANNEL_IN_* constants are kept in sync with
        // android.media.audio.common.AudioChannelMask.
        return aidlMask;
    }

    /** Convert from AIDL AudioConfig to SDK AudioFormat. */
    public static @NonNull AudioFormat aidl2api_AudioConfig_AudioFormat(
            @NonNull AudioConfig audioConfig) {
        AudioFormat.Builder apiBuilder = new AudioFormat.Builder();
        apiBuilder.setSampleRate(audioConfig.sampleRateHz);
        apiBuilder.setChannelMask(aidl2api_AudioChannelMask_AudioFormatChannelInMask(
                        audioConfig.channelMask));
        apiBuilder.setEncoding(aidl2api_AudioFormat_AudioFormatEncoding(audioConfig.format));
        return apiBuilder.build();
    }

    /** Convert from AIDL AudioFormat to SDK AudioFormat.ENCODING_*. */
    public static int aidl2api_AudioFormat_AudioFormatEncoding(int aidlFormat) {
        switch (aidlFormat) {
            case android.media.audio.common.AudioFormat.PCM
                    | android.media.audio.common.AudioFormat.PCM_SUB_16_BIT:
                return AudioFormat.ENCODING_PCM_16BIT;

            case android.media.audio.common.AudioFormat.PCM
                    | android.media.audio.common.AudioFormat.PCM_SUB_8_BIT:
                return AudioFormat.ENCODING_PCM_8BIT;

            case android.media.audio.common.AudioFormat.PCM
                    | android.media.audio.common.AudioFormat.PCM_SUB_FLOAT:
            case android.media.audio.common.AudioFormat.PCM
                    | android.media.audio.common.AudioFormat.PCM_SUB_8_24_BIT:
            case android.media.audio.common.AudioFormat.PCM
                    | android.media.audio.common.AudioFormat.PCM_SUB_24_BIT_PACKED:
            case android.media.audio.common.AudioFormat.PCM
                    | android.media.audio.common.AudioFormat.PCM_SUB_32_BIT:
                return AudioFormat.ENCODING_PCM_FLOAT;

            case android.media.audio.common.AudioFormat.AC3:
                return AudioFormat.ENCODING_AC3;

            case android.media.audio.common.AudioFormat.E_AC3:
                return AudioFormat.ENCODING_E_AC3;

            case android.media.audio.common.AudioFormat.DTS:
                return AudioFormat.ENCODING_DTS;

            case android.media.audio.common.AudioFormat.DTS_HD:
                return AudioFormat.ENCODING_DTS_HD;

            case android.media.audio.common.AudioFormat.MP3:
                return AudioFormat.ENCODING_MP3;

            case android.media.audio.common.AudioFormat.AAC
                    | android.media.audio.common.AudioFormat.AAC_SUB_LC:
                return AudioFormat.ENCODING_AAC_LC;

            case android.media.audio.common.AudioFormat.AAC
                    | android.media.audio.common.AudioFormat.AAC_SUB_HE_V1:
                return AudioFormat.ENCODING_AAC_HE_V1;

            case android.media.audio.common.AudioFormat.AAC
                    | android.media.audio.common.AudioFormat.AAC_SUB_HE_V2:
                return AudioFormat.ENCODING_AAC_HE_V2;

            case android.media.audio.common.AudioFormat.IEC61937:
                return AudioFormat.ENCODING_IEC61937;

            case android.media.audio.common.AudioFormat.DOLBY_TRUEHD:
                return AudioFormat.ENCODING_DOLBY_TRUEHD;

            case android.media.audio.common.AudioFormat.AAC
                    | android.media.audio.common.AudioFormat.AAC_SUB_ELD:
                return AudioFormat.ENCODING_AAC_ELD;

            case android.media.audio.common.AudioFormat.AAC
                    | android.media.audio.common.AudioFormat.AAC_SUB_XHE:
                return AudioFormat.ENCODING_AAC_XHE;

            case android.media.audio.common.AudioFormat.AC4:
                return AudioFormat.ENCODING_AC4;

            case android.media.audio.common.AudioFormat.E_AC3
                    | android.media.audio.common.AudioFormat.E_AC3_SUB_JOC:
                return AudioFormat.ENCODING_E_AC3_JOC;

            case android.media.audio.common.AudioFormat.MAT:
            case android.media.audio.common.AudioFormat.MAT
                    | android.media.audio.common.AudioFormat.MAT_SUB_1_0:
            case android.media.audio.common.AudioFormat.MAT
                    | android.media.audio.common.AudioFormat.MAT_SUB_2_0:
            case android.media.audio.common.AudioFormat.MAT
                    | android.media.audio.common.AudioFormat.MAT_SUB_2_1:
                return AudioFormat.ENCODING_DOLBY_MAT;

            case android.media.audio.common.AudioFormat.DEFAULT:
                return AudioFormat.ENCODING_DEFAULT;

            default:
                return AudioFormat.ENCODING_INVALID;
        }
    }
}
