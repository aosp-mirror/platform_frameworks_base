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
 */// This file has been semi-automatically generated using hidl2aidl from its counterpart in
// hardware/interfaces/audio/common/5.0/types.hal
///////////////////////////////////////////////////////////////////////////////
// THIS FILE IS IMMUTABLE. DO NOT EDIT IN ANY CASE.                          //
///////////////////////////////////////////////////////////////////////////////

// This file is a snapshot of an AIDL file. Do not edit it manually. There are
// two cases:
// 1). this is a frozen version file - do not edit this in any case.
// 2). this is a 'current' file. If you make a backwards compatible change to
//     the interface (from the latest frozen version), the build system will
//     prompt you to update this file with `m <name>-update-api`.
//
// You must not make a backward incompatible change to any AIDL file built
// with the aidl_interface module type with versions property set. The module
// type is used to build AIDL files in a way that they can be used across
// independently updatable components of the system. If a device is shipped
// with such a backward incompatible change, it has a high risk of breaking
// later when a module using the interface is updated, e.g., Mainline modules.

package android.media.audio.common;
/* @hide */
@Backing(type="int") @VintfStability
enum AudioFormat {
  INVALID = -1,
  DEFAULT = 0,
  PCM = 0,
  MP3 = 16777216,
  AMR_NB = 33554432,
  AMR_WB = 50331648,
  AAC = 67108864,
  HE_AAC_V1 = 83886080,
  HE_AAC_V2 = 100663296,
  VORBIS = 117440512,
  OPUS = 134217728,
  AC3 = 150994944,
  E_AC3 = 167772160,
  DTS = 184549376,
  DTS_HD = 201326592,
  IEC61937 = 218103808,
  DOLBY_TRUEHD = 234881024,
  EVRC = 268435456,
  EVRCB = 285212672,
  EVRCWB = 301989888,
  EVRCNW = 318767104,
  AAC_ADIF = 335544320,
  WMA = 352321536,
  WMA_PRO = 369098752,
  AMR_WB_PLUS = 385875968,
  MP2 = 402653184,
  QCELP = 419430400,
  DSD = 436207616,
  FLAC = 452984832,
  ALAC = 469762048,
  APE = 486539264,
  AAC_ADTS = 503316480,
  SBC = 520093696,
  APTX = 536870912,
  APTX_HD = 553648128,
  AC4 = 570425344,
  LDAC = 587202560,
  MAT = 603979776,
  AAC_LATM = 620756992,
  CELT = 637534208,
  APTX_ADAPTIVE = 654311424,
  LHDC = 671088640,
  LHDC_LL = 687865856,
  APTX_TWSP = 704643072,
  MAIN_MASK = -16777216,
  SUB_MASK = 16777215,
  PCM_SUB_16_BIT = 1,
  PCM_SUB_8_BIT = 2,
  PCM_SUB_32_BIT = 3,
  PCM_SUB_8_24_BIT = 4,
  PCM_SUB_FLOAT = 5,
  PCM_SUB_24_BIT_PACKED = 6,
  MP3_SUB_NONE = 0,
  AMR_SUB_NONE = 0,
  AAC_SUB_MAIN = 1,
  AAC_SUB_LC = 2,
  AAC_SUB_SSR = 4,
  AAC_SUB_LTP = 8,
  AAC_SUB_HE_V1 = 16,
  AAC_SUB_SCALABLE = 32,
  AAC_SUB_ERLC = 64,
  AAC_SUB_LD = 128,
  AAC_SUB_HE_V2 = 256,
  AAC_SUB_ELD = 512,
  AAC_SUB_XHE = 768,
  VORBIS_SUB_NONE = 0,
  E_AC3_SUB_JOC = 1,
  MAT_SUB_1_0 = 1,
  MAT_SUB_2_0 = 2,
  MAT_SUB_2_1 = 3,
}
