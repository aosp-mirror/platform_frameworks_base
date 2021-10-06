/*
 * Copyright (C) 2019 The Android Open Source Project
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

 // This file has been semi-automatically generated using hidl2aidl from its counterpart in
 // hardware/interfaces/audio/common/5.0/types.hal

package android.media.audio.common;

/**
 * Audio format  is a 32-bit word that consists of:
 *   main format field (upper 8 bits)
 *   sub format field (lower 24 bits).
 *
 * The main format indicates the main codec type. The sub format field indicates
 * options and parameters for each format. The sub format is mainly used for
 * record to indicate for instance the requested bitrate or profile.  It can
 * also be used for certain formats to give informations not present in the
 * encoded audio stream (e.g. octet alignement for AMR).
 *
 * {@hide}
 */
@Backing(type="int")
enum AudioFormat {
   INVALID = 0xFFFFFFFF,
   DEFAULT = 0,
   PCM = 0x00000000,
   MP3 = 0x01000000,
   AMR_NB = 0x02000000,
   AMR_WB = 0x03000000,
   AAC = 0x04000000,
   /**
    * Deprecated, Use AAC_HE_V1
    */
   HE_AAC_V1 = 0x05000000,
   /**
    * Deprecated, Use AAC_HE_V2
    */
   HE_AAC_V2 = 0x06000000,
   VORBIS = 0x07000000,
   OPUS = 0x08000000,
   AC3 = 0x09000000,
   E_AC3 = 0x0A000000,
   DTS = 0x0B000000,
   DTS_HD = 0x0C000000,
   /**
    * IEC61937 is encoded audio wrapped in 16-bit PCM.
    */
   IEC61937 = 0x0D000000,
   DOLBY_TRUEHD = 0x0E000000,
   EVRC = 0x10000000,
   EVRCB = 0x11000000,
   EVRCWB = 0x12000000,
   EVRCNW = 0x13000000,
   AAC_ADIF = 0x14000000,
   WMA = 0x15000000,
   WMA_PRO = 0x16000000,
   AMR_WB_PLUS = 0x17000000,
   MP2 = 0x18000000,
   QCELP = 0x19000000,
   DSD = 0x1A000000,
   FLAC = 0x1B000000,
   ALAC = 0x1C000000,
   APE = 0x1D000000,
   AAC_ADTS = 0x1E000000,
   SBC = 0x1F000000,
   APTX = 0x20000000,
   APTX_HD = 0x21000000,
   AC4 = 0x22000000,
   LDAC = 0x23000000,
   /**
    * Dolby Metadata-enhanced Audio Transmission
    */
   MAT = 0x24000000,
   AAC_LATM = 0x25000000,
   CELT = 0x26000000,
   APTX_ADAPTIVE = 0x27000000,
   LHDC = 0x28000000,
   LHDC_LL = 0x29000000,
   APTX_TWSP = 0x2A000000,
   /**
    * Deprecated
    */
   MAIN_MASK = 0xFF000000,
   SUB_MASK = 0x00FFFFFF,
   /**
    * Subformats
    */
   PCM_SUB_16_BIT = 0x1,
   PCM_SUB_8_BIT = 0x2,
   PCM_SUB_32_BIT = 0x3,
   PCM_SUB_8_24_BIT = 0x4,
   PCM_SUB_FLOAT = 0x5,
   PCM_SUB_24_BIT_PACKED = 0x6,
   MP3_SUB_NONE = 0x0,
   AMR_SUB_NONE = 0x0,
   AAC_SUB_MAIN = 0x1,
   AAC_SUB_LC = 0x2,
   AAC_SUB_SSR = 0x4,
   AAC_SUB_LTP = 0x8,
   AAC_SUB_HE_V1 = 0x10,
   AAC_SUB_SCALABLE = 0x20,
   AAC_SUB_ERLC = 0x40,
   AAC_SUB_LD = 0x80,
   AAC_SUB_HE_V2 = 0x100,
   AAC_SUB_ELD = 0x200,
   AAC_SUB_XHE = 0x300,
   VORBIS_SUB_NONE = 0x0,
   E_AC3_SUB_JOC = 0x1,
   MAT_SUB_1_0 = 0x1,
   MAT_SUB_2_0 = 0x2,
   MAT_SUB_2_1 = 0x3,
// TODO(ytai): Aliases not currently supported in AIDL - can inline the values.
//   /**
//    * Aliases
//    *
//    *
//    * note != AudioFormat.ENCODING_PCM_16BIT
//    */
//   PCM_16_BIT = (PCM | PCM_SUB_16_BIT),
//   /**
//    * note != AudioFormat.ENCODING_PCM_8BIT
//    */
//   PCM_8_BIT = (PCM | PCM_SUB_8_BIT),
//   PCM_32_BIT = (PCM | PCM_SUB_32_BIT),
//   PCM_8_24_BIT = (PCM | PCM_SUB_8_24_BIT),
//   PCM_FLOAT = (PCM | PCM_SUB_FLOAT),
//   PCM_24_BIT_PACKED = (PCM | PCM_SUB_24_BIT_PACKED),
//   AAC_MAIN = (AAC | AAC_SUB_MAIN),
//   AAC_LC = (AAC | AAC_SUB_LC),
//   AAC_SSR = (AAC | AAC_SUB_SSR),
//   AAC_LTP = (AAC | AAC_SUB_LTP),
//   AAC_HE_V1 = (AAC | AAC_SUB_HE_V1),
//   AAC_SCALABLE = (AAC | AAC_SUB_SCALABLE),
//   AAC_ERLC = (AAC | AAC_SUB_ERLC),
//   AAC_LD = (AAC | AAC_SUB_LD),
//   AAC_HE_V2 = (AAC | AAC_SUB_HE_V2),
//   AAC_ELD = (AAC | AAC_SUB_ELD),
//   AAC_XHE = (AAC | AAC_SUB_XHE),
//   AAC_ADTS_MAIN = (AAC_ADTS | AAC_SUB_MAIN),
//   AAC_ADTS_LC = (AAC_ADTS | AAC_SUB_LC),
//   AAC_ADTS_SSR = (AAC_ADTS | AAC_SUB_SSR),
//   AAC_ADTS_LTP = (AAC_ADTS | AAC_SUB_LTP),
//   AAC_ADTS_HE_V1 = (AAC_ADTS | AAC_SUB_HE_V1),
//   AAC_ADTS_SCALABLE = (AAC_ADTS | AAC_SUB_SCALABLE),
//   AAC_ADTS_ERLC = (AAC_ADTS | AAC_SUB_ERLC),
//   AAC_ADTS_LD = (AAC_ADTS | AAC_SUB_LD),
//   AAC_ADTS_HE_V2 = (AAC_ADTS | AAC_SUB_HE_V2),
//   AAC_ADTS_ELD = (AAC_ADTS | AAC_SUB_ELD),
//   AAC_ADTS_XHE = (AAC_ADTS | AAC_SUB_XHE),
//   E_AC3_JOC = (E_AC3 | E_AC3_SUB_JOC),
//   MAT_1_0 = (MAT | MAT_SUB_1_0),
//   MAT_2_0 = (MAT | MAT_SUB_2_0),
//   MAT_2_1 = (MAT | MAT_SUB_2_1),
//   AAC_LATM_LC = (AAC_LATM | AAC_SUB_LC),
//   AAC_LATM_HE_V1 = (AAC_LATM | AAC_SUB_HE_V1),
//   AAC_LATM_HE_V2 = (AAC_LATM | AAC_SUB_HE_V2),
}
