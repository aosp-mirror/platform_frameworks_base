/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.media;

/**
 * Loudness information for a {@link MediaCodec} object which specifies the
 * input attributes used for measuring the parameters required to perform
 * loudness alignment as specified by the CTA2075 standard.
 *
 * {@hide}
 */
@JavaDerive(equals = true, toString = true)
parcelable LoudnessCodecInfo {
    /** Supported codec metadata types for loudness updates. */
    @Backing(type="int")
    enum CodecMetadataType {
        CODEC_METADATA_TYPE_INVALID = 0,
        CODEC_METADATA_TYPE_MPEG_4 = 1,
        CODEC_METADATA_TYPE_MPEG_D = 2,
        CODEC_METADATA_TYPE_AC_3 = 3,
        CODEC_METADATA_TYPE_AC_4 = 4,
        CODEC_METADATA_TYPE_DTS_HD = 5,
        CODEC_METADATA_TYPE_DTS_UHD = 6
    }

    CodecMetadataType metadataType;
    boolean isDownmixing;
}