/*
 * Copyright (C) 2004-2010 NXP Software
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


/************************************************************************************/
/*                                                                                  */
/*    Includes                                                                      */
/*                                                                                  */
/************************************************************************************/

#include "LVDBE.h"
#include "LVDBE_Coeffs.h"               /* Filter coefficients */
#include "BIQUAD.h"


/************************************************************************************/
/*                                                                                  */
/*    Coefficients constant table                                                   */
/*                                                                                  */
/************************************************************************************/

/*
 * High Pass Filter Coefficient table
 */
const BQ_C32_Coefs_t LVDBE_HPF_Table[] = {
    /* Coefficients for 55Hz centre frequency */
    {HPF_Fs8000_Fc55_A2,                /* 8kS/s coefficients */
     HPF_Fs8000_Fc55_A1,
     HPF_Fs8000_Fc55_A0,
     -HPF_Fs8000_Fc55_B2,
     -HPF_Fs8000_Fc55_B1},
    {HPF_Fs11025_Fc55_A2,                /* 11kS/s coefficients */
     HPF_Fs11025_Fc55_A1,
     HPF_Fs11025_Fc55_A0,
     -HPF_Fs11025_Fc55_B2,
     -HPF_Fs11025_Fc55_B1},
    {HPF_Fs12000_Fc55_A2,                /* 12kS/s coefficients */
     HPF_Fs12000_Fc55_A1,
     HPF_Fs12000_Fc55_A0,
     -HPF_Fs12000_Fc55_B2,
     -HPF_Fs12000_Fc55_B1},
    {HPF_Fs16000_Fc55_A2,                /* 16kS/s coefficients */
     HPF_Fs16000_Fc55_A1,
     HPF_Fs16000_Fc55_A0,
     -HPF_Fs16000_Fc55_B2,
     -HPF_Fs16000_Fc55_B1},
    {HPF_Fs22050_Fc55_A2,                /* 22kS/s coefficients */
     HPF_Fs22050_Fc55_A1,
     HPF_Fs22050_Fc55_A0,
     -HPF_Fs22050_Fc55_B2,
     -HPF_Fs22050_Fc55_B1},
    {HPF_Fs24000_Fc55_A2,                /* 24kS/s coefficients */
     HPF_Fs24000_Fc55_A1,
     HPF_Fs24000_Fc55_A0,
     -HPF_Fs24000_Fc55_B2,
     -HPF_Fs24000_Fc55_B1},
    {HPF_Fs32000_Fc55_A2,                /* 32kS/s coefficients */
     HPF_Fs32000_Fc55_A1,
     HPF_Fs32000_Fc55_A0,
     -HPF_Fs32000_Fc55_B2,
     -HPF_Fs32000_Fc55_B1},
    {HPF_Fs44100_Fc55_A2,                /* 44kS/s coefficients */
     HPF_Fs44100_Fc55_A1,
     HPF_Fs44100_Fc55_A0,
     -HPF_Fs44100_Fc55_B2,
     -HPF_Fs44100_Fc55_B1},
    {HPF_Fs48000_Fc55_A2,                /* 48kS/s coefficients */
     HPF_Fs48000_Fc55_A1,
     HPF_Fs48000_Fc55_A0,
     -HPF_Fs48000_Fc55_B2,
     -HPF_Fs48000_Fc55_B1},

    /* Coefficients for 66Hz centre frequency */
    {HPF_Fs8000_Fc66_A2,                /* 8kS/s coefficients */
     HPF_Fs8000_Fc66_A1,
     HPF_Fs8000_Fc66_A0,
     -HPF_Fs8000_Fc66_B2,
     -HPF_Fs8000_Fc66_B1},
    {HPF_Fs11025_Fc66_A2,                /* 11kS/s coefficients */
     HPF_Fs11025_Fc66_A1,
     HPF_Fs11025_Fc66_A0,
     -HPF_Fs11025_Fc66_B2,
     -HPF_Fs11025_Fc66_B1},
    {HPF_Fs12000_Fc66_A2,                /* 12kS/s coefficients */
     HPF_Fs12000_Fc66_A1,
     HPF_Fs12000_Fc66_A0,
     -HPF_Fs12000_Fc66_B2,
     -HPF_Fs12000_Fc66_B1},
    {HPF_Fs16000_Fc66_A2,                /* 16kS/s coefficients */
     HPF_Fs16000_Fc66_A1,
     HPF_Fs16000_Fc66_A0,
     -HPF_Fs16000_Fc66_B2,
     -HPF_Fs16000_Fc66_B1},
    {HPF_Fs22050_Fc66_A2,                /* 22kS/s coefficients */
     HPF_Fs22050_Fc66_A1,
     HPF_Fs22050_Fc66_A0,
     -HPF_Fs22050_Fc66_B2,
     -HPF_Fs22050_Fc66_B1},
    {HPF_Fs24000_Fc66_A2,                /* 24kS/s coefficients */
     HPF_Fs24000_Fc66_A1,
     HPF_Fs24000_Fc66_A0,
     -HPF_Fs24000_Fc66_B2,
     -HPF_Fs24000_Fc66_B1},
    {HPF_Fs32000_Fc66_A2,                /* 32kS/s coefficients */
     HPF_Fs32000_Fc66_A1,
     HPF_Fs32000_Fc66_A0,
     -HPF_Fs32000_Fc66_B2,
     -HPF_Fs32000_Fc66_B1},
    {HPF_Fs44100_Fc66_A2,                /* 44kS/s coefficients */
     HPF_Fs44100_Fc66_A1,
     HPF_Fs44100_Fc66_A0,
     -HPF_Fs44100_Fc66_B2,
     -HPF_Fs44100_Fc66_B1},
    {HPF_Fs48000_Fc66_A2,                /* 48kS/s coefficients */
     HPF_Fs48000_Fc66_A1,
     HPF_Fs48000_Fc66_A0,
     -HPF_Fs48000_Fc66_B2,
     -HPF_Fs48000_Fc66_B1},

    /* Coefficients for 78Hz centre frequency */
    {HPF_Fs8000_Fc78_A2,                /* 8kS/s coefficients */
     HPF_Fs8000_Fc78_A1,
     HPF_Fs8000_Fc78_A0,
     -HPF_Fs8000_Fc78_B2,
     -HPF_Fs8000_Fc78_B1},
    {HPF_Fs11025_Fc78_A2,                /* 11kS/s coefficients */
     HPF_Fs11025_Fc78_A1,
     HPF_Fs11025_Fc78_A0,
     -HPF_Fs11025_Fc78_B2,
     -HPF_Fs11025_Fc78_B1},
    {HPF_Fs12000_Fc78_A2,                /* 12kS/s coefficients */
     HPF_Fs12000_Fc78_A1,
     HPF_Fs12000_Fc78_A0,
     -HPF_Fs12000_Fc78_B2,
     -HPF_Fs12000_Fc78_B1},
    {HPF_Fs16000_Fc78_A2,                /* 16kS/s coefficients */
     HPF_Fs16000_Fc78_A1,
     HPF_Fs16000_Fc78_A0,
     -HPF_Fs16000_Fc78_B2,
     -HPF_Fs16000_Fc78_B1},
    {HPF_Fs22050_Fc78_A2,                /* 22kS/s coefficients */
     HPF_Fs22050_Fc78_A1,
     HPF_Fs22050_Fc78_A0,
     -HPF_Fs22050_Fc78_B2,
     -HPF_Fs22050_Fc78_B1},
    {HPF_Fs24000_Fc78_A2,                /* 24kS/s coefficients */
     HPF_Fs24000_Fc78_A1,
     HPF_Fs24000_Fc78_A0,
     -HPF_Fs24000_Fc78_B2,
     -HPF_Fs24000_Fc78_B1},
    {HPF_Fs32000_Fc78_A2,                /* 32kS/s coefficients */
     HPF_Fs32000_Fc78_A1,
     HPF_Fs32000_Fc78_A0,
     -HPF_Fs32000_Fc78_B2,
     -HPF_Fs32000_Fc78_B1},
    {HPF_Fs44100_Fc78_A2,                /* 44kS/s coefficients */
     HPF_Fs44100_Fc78_A1,
     HPF_Fs44100_Fc78_A0,
     -HPF_Fs44100_Fc78_B2,
     -HPF_Fs44100_Fc78_B1},
    {HPF_Fs48000_Fc78_A2,                /* 48kS/s coefficients */
     HPF_Fs48000_Fc78_A1,
     HPF_Fs48000_Fc78_A0,
     -HPF_Fs48000_Fc78_B2,
     -HPF_Fs48000_Fc78_B1},

    /* Coefficients for 90Hz centre frequency */
    {HPF_Fs8000_Fc90_A2,                /* 8kS/s coefficients */
     HPF_Fs8000_Fc90_A1,
     HPF_Fs8000_Fc90_A0,
     -HPF_Fs8000_Fc90_B2,
     -HPF_Fs8000_Fc90_B1},
    {HPF_Fs11025_Fc90_A2,                /* 11kS/s coefficients */
     HPF_Fs11025_Fc90_A1,
     HPF_Fs11025_Fc90_A0,
     -HPF_Fs11025_Fc90_B2,
     -HPF_Fs11025_Fc90_B1},
    {HPF_Fs12000_Fc90_A2,                /* 12kS/s coefficients */
     HPF_Fs12000_Fc90_A1,
     HPF_Fs12000_Fc90_A0,
     -HPF_Fs12000_Fc90_B2,
     -HPF_Fs12000_Fc90_B1},
    {HPF_Fs16000_Fc90_A2,                /* 16kS/s coefficients */
     HPF_Fs16000_Fc90_A1,
     HPF_Fs16000_Fc90_A0,
     -HPF_Fs16000_Fc90_B2,
     -HPF_Fs16000_Fc90_B1},
    {HPF_Fs22050_Fc90_A2,                /* 22kS/s coefficients */
     HPF_Fs22050_Fc90_A1,
     HPF_Fs22050_Fc90_A0,
     -HPF_Fs22050_Fc90_B2,
     -HPF_Fs22050_Fc90_B1},
    {HPF_Fs24000_Fc90_A2,                /* 24kS/s coefficients */
     HPF_Fs24000_Fc90_A1,
     HPF_Fs24000_Fc90_A0,
     -HPF_Fs24000_Fc90_B2,
     -HPF_Fs24000_Fc90_B1},
    {HPF_Fs32000_Fc90_A2,                /* 32kS/s coefficients */
     HPF_Fs32000_Fc90_A1,
     HPF_Fs32000_Fc90_A0,
     -HPF_Fs32000_Fc90_B2,
     -HPF_Fs32000_Fc90_B1},
    {HPF_Fs44100_Fc90_A2,                /* 44kS/s coefficients */
     HPF_Fs44100_Fc90_A1,
     HPF_Fs44100_Fc90_A0,
     -HPF_Fs44100_Fc90_B2,
     -HPF_Fs44100_Fc90_B1},
    {HPF_Fs48000_Fc90_A2,                /* 48kS/s coefficients */
     HPF_Fs48000_Fc90_A1,
     HPF_Fs48000_Fc90_A0,
     -HPF_Fs48000_Fc90_B2,
     -HPF_Fs48000_Fc90_B1}};

/*
 * Band Pass Filter coefficient table
 */
const BP_C32_Coefs_t LVDBE_BPF_Table[] = {
    /* Coefficients for 55Hz centre frequency */
    {BPF_Fs8000_Fc55_A0,                /* 8kS/s coefficients */
     -BPF_Fs8000_Fc55_B2,
     -BPF_Fs8000_Fc55_B1},
    {BPF_Fs11025_Fc55_A0,                /* 11kS/s coefficients */
     -BPF_Fs11025_Fc55_B2,
     -BPF_Fs11025_Fc55_B1},
    {BPF_Fs12000_Fc55_A0,                /* 12kS/s coefficients */
     -BPF_Fs12000_Fc55_B2,
     -BPF_Fs12000_Fc55_B1},
    {BPF_Fs16000_Fc55_A0,                /* 16kS/s coefficients */
     -BPF_Fs16000_Fc55_B2,
     -BPF_Fs16000_Fc55_B1},
    {BPF_Fs22050_Fc55_A0,                /* 22kS/s coefficients */
     -BPF_Fs22050_Fc55_B2,
     -BPF_Fs22050_Fc55_B1},
    {BPF_Fs24000_Fc55_A0,                /* 24kS/s coefficients */
     -BPF_Fs24000_Fc55_B2,
     -BPF_Fs24000_Fc55_B1},
    {BPF_Fs32000_Fc55_A0,                /* 32kS/s coefficients */
     -BPF_Fs32000_Fc55_B2,
     -BPF_Fs32000_Fc55_B1},
    {BPF_Fs44100_Fc55_A0,                /* 44kS/s coefficients */
     -BPF_Fs44100_Fc55_B2,
     -BPF_Fs44100_Fc55_B1},
    {BPF_Fs48000_Fc55_A0,                /* 48kS/s coefficients */
     -BPF_Fs48000_Fc55_B2,
     -BPF_Fs48000_Fc55_B1},

    /* Coefficients for 66Hz centre frequency */
    {BPF_Fs8000_Fc66_A0,                /* 8kS/s coefficients */
     -BPF_Fs8000_Fc66_B2,
     -BPF_Fs8000_Fc66_B1},
    {BPF_Fs11025_Fc66_A0,                /* 11kS/s coefficients */
     -BPF_Fs11025_Fc66_B2,
     -BPF_Fs11025_Fc66_B1},
    {BPF_Fs12000_Fc66_A0,                /* 12kS/s coefficients */
     -BPF_Fs12000_Fc66_B2,
     -BPF_Fs12000_Fc66_B1},
    {BPF_Fs16000_Fc66_A0,                /* 16kS/s coefficients */
     -BPF_Fs16000_Fc66_B2,
     -BPF_Fs16000_Fc66_B1},
    {BPF_Fs22050_Fc66_A0,                /* 22kS/s coefficients */
     -BPF_Fs22050_Fc66_B2,
     -BPF_Fs22050_Fc66_B1},
    {BPF_Fs24000_Fc66_A0,                /* 24kS/s coefficients */
     -BPF_Fs24000_Fc66_B2,
     -BPF_Fs24000_Fc66_B1},
    {BPF_Fs32000_Fc66_A0,                /* 32kS/s coefficients */
     -BPF_Fs32000_Fc66_B2,
     -BPF_Fs32000_Fc66_B1},
    {BPF_Fs44100_Fc66_A0,                /* 44kS/s coefficients */
     -BPF_Fs44100_Fc66_B2,
     -BPF_Fs44100_Fc66_B1},
    {BPF_Fs48000_Fc66_A0,                /* 48kS/s coefficients */
     -BPF_Fs48000_Fc66_B2,
     -BPF_Fs48000_Fc66_B1},

    /* Coefficients for 78Hz centre frequency */
    {BPF_Fs8000_Fc78_A0,                /* 8kS/s coefficients */
     -BPF_Fs8000_Fc78_B2,
     -BPF_Fs8000_Fc78_B1},
    {BPF_Fs11025_Fc78_A0,                /* 11kS/s coefficients */
     -BPF_Fs11025_Fc78_B2,
     -BPF_Fs11025_Fc78_B1},
    {BPF_Fs12000_Fc78_A0,                /* 12kS/s coefficients */
     -BPF_Fs12000_Fc78_B2,
     -BPF_Fs12000_Fc78_B1},
    {BPF_Fs16000_Fc78_A0,                /* 16kS/s coefficients */
     -BPF_Fs16000_Fc78_B2,
     -BPF_Fs16000_Fc78_B1},
    {BPF_Fs22050_Fc78_A0,                /* 22kS/s coefficients */
     -BPF_Fs22050_Fc78_B2,
     -BPF_Fs22050_Fc78_B1},
    {BPF_Fs24000_Fc78_A0,                /* 24kS/s coefficients */
     -BPF_Fs24000_Fc78_B2,
     -BPF_Fs24000_Fc78_B1},
    {BPF_Fs32000_Fc78_A0,                /* 32kS/s coefficients */
     -BPF_Fs32000_Fc78_B2,
     -BPF_Fs32000_Fc78_B1},
    {BPF_Fs44100_Fc78_A0,                /* 44kS/s coefficients */
     -BPF_Fs44100_Fc78_B2,
     -BPF_Fs44100_Fc78_B1},
    {BPF_Fs48000_Fc78_A0,                /* 48kS/s coefficients */
     -BPF_Fs48000_Fc78_B2,
     -BPF_Fs48000_Fc78_B1},

    /* Coefficients for 90Hz centre frequency */
    {BPF_Fs8000_Fc90_A0,                /* 8kS/s coefficients */
     -BPF_Fs8000_Fc90_B2,
     -BPF_Fs8000_Fc90_B1},
    {BPF_Fs11025_Fc90_A0,                /* 11kS/s coefficients */
     -BPF_Fs11025_Fc90_B2,
     -BPF_Fs11025_Fc90_B1},
    {BPF_Fs12000_Fc90_A0,                /* 12kS/s coefficients */
     -BPF_Fs12000_Fc90_B2,
     -BPF_Fs12000_Fc90_B1},
    {BPF_Fs16000_Fc90_A0,                /* 16kS/s coefficients */
     -BPF_Fs16000_Fc90_B2,
     -BPF_Fs16000_Fc90_B1},
    {BPF_Fs22050_Fc90_A0,                /* 22kS/s coefficients */
     -BPF_Fs22050_Fc90_B2,
     -BPF_Fs22050_Fc90_B1},
    {BPF_Fs24000_Fc90_A0,                /* 24kS/s coefficients */
     -BPF_Fs24000_Fc90_B2,
     -BPF_Fs24000_Fc90_B1},
    {BPF_Fs32000_Fc90_A0,                /* 32kS/s coefficients */
     -BPF_Fs32000_Fc90_B2,
     -BPF_Fs32000_Fc90_B1},
    {BPF_Fs44100_Fc90_A0,                /* 44kS/s coefficients */
     -BPF_Fs44100_Fc90_B2,
     -BPF_Fs44100_Fc90_B1},
    {BPF_Fs48000_Fc90_A0,                /* 48kS/s coefficients */
     -BPF_Fs48000_Fc90_B2,
     -BPF_Fs48000_Fc90_B1}};


/************************************************************************************/
/*                                                                                  */
/*    AGC constant tables                                                           */
/*                                                                                  */
/************************************************************************************/

/* Attack time (signal too large) */
const LVM_INT16 LVDBE_AGC_ATTACK_Table[] = {
    AGC_ATTACK_Fs8000,
    AGC_ATTACK_Fs11025,
    AGC_ATTACK_Fs12000,
    AGC_ATTACK_Fs16000,
    AGC_ATTACK_Fs22050,
    AGC_ATTACK_Fs24000,
    AGC_ATTACK_Fs32000,
    AGC_ATTACK_Fs44100,
    AGC_ATTACK_Fs48000};

/* Decay time (signal too small) */
const LVM_INT16 LVDBE_AGC_DECAY_Table[] = {
    AGC_DECAY_Fs8000,
    AGC_DECAY_Fs11025,
    AGC_DECAY_Fs12000,
    AGC_DECAY_Fs16000,
    AGC_DECAY_Fs22050,
    AGC_DECAY_Fs24000,
    AGC_DECAY_Fs32000,
    AGC_DECAY_Fs44100,
    AGC_DECAY_Fs48000};

/* Gain for use without the high pass filter */
const LVM_INT32 LVDBE_AGC_GAIN_Table[] = {
    AGC_GAIN_0dB,
    AGC_GAIN_1dB,
    AGC_GAIN_2dB,
    AGC_GAIN_3dB,
    AGC_GAIN_4dB,
    AGC_GAIN_5dB,
    AGC_GAIN_6dB,
    AGC_GAIN_7dB,
    AGC_GAIN_8dB,
    AGC_GAIN_9dB,
    AGC_GAIN_10dB,
    AGC_GAIN_11dB,
    AGC_GAIN_12dB,
    AGC_GAIN_13dB,
    AGC_GAIN_14dB,
    AGC_GAIN_15dB};

/* Gain for use with the high pass filter */
const LVM_INT32 LVDBE_AGC_HPFGAIN_Table[] = {
    AGC_HPFGAIN_0dB,
    AGC_HPFGAIN_1dB,
    AGC_HPFGAIN_2dB,
    AGC_HPFGAIN_3dB,
    AGC_HPFGAIN_4dB,
    AGC_HPFGAIN_5dB,
    AGC_HPFGAIN_6dB,
    AGC_HPFGAIN_7dB,
    AGC_HPFGAIN_8dB,
    AGC_HPFGAIN_9dB,
    AGC_HPFGAIN_10dB,
    AGC_HPFGAIN_11dB,
    AGC_HPFGAIN_12dB,
    AGC_HPFGAIN_13dB,
    AGC_HPFGAIN_14dB,
    AGC_HPFGAIN_15dB};


/************************************************************************************/
/*                                                                                  */
/*    Volume control gain and time constant tables                                  */
/*                                                                                  */
/************************************************************************************/

/* dB to linear conversion table */
const LVM_INT16 LVDBE_VolumeTable[] = {
    0x4000,             /* -6dB */
    0x47FB,             /* -5dB */
    0x50C3,             /* -4dB */
    0x5A9E,             /* -3dB */
    0x65AD,             /* -2dB */
    0x7215,             /* -1dB */
    0x7FFF};            /*  0dB */

const LVM_INT16 LVDBE_VolumeTCTable[] = {
    VOL_TC_Fs8000,
    VOL_TC_Fs11025,
    VOL_TC_Fs12000,
    VOL_TC_Fs16000,
    VOL_TC_Fs22050,
    VOL_TC_Fs24000,
    VOL_TC_Fs32000,
    VOL_TC_Fs44100,
    VOL_TC_Fs48000};

const LVM_INT16 LVDBE_MixerTCTable[] = {
    MIX_TC_Fs8000,
    MIX_TC_Fs11025,
    MIX_TC_Fs12000,
    MIX_TC_Fs16000,
    MIX_TC_Fs22050,
    MIX_TC_Fs24000,
    MIX_TC_Fs32000,
    MIX_TC_Fs44100,
    MIX_TC_Fs48000};


