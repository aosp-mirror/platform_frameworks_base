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

#include "LVM_Tables.h"
#include "LVM_Coeffs.h"

/************************************************************************************/
/*                                                                                  */
/*    Treble Boost Filter Coefficients                                              */
/*                                                                                  */
/************************************************************************************/

FO_C16_LShx_Coefs_t    LVM_TrebleBoostCoefs[] = {

                    /* 22kHz sampling rate */
                    {HPF_Fs22050_Gain1_A1,             /* Gain setting 1 */
                     HPF_Fs22050_Gain1_A0,
                     -HPF_Fs22050_Gain1_B1,
                    HPF_Fs22050_Gain1_Shift},
                    {HPF_Fs22050_Gain2_A1,             /* Gain setting 2 */
                     HPF_Fs22050_Gain2_A0,
                     -HPF_Fs22050_Gain2_B1,
                    HPF_Fs22050_Gain2_Shift},
                    {HPF_Fs22050_Gain3_A1,             /* Gain setting 3 */
                     HPF_Fs22050_Gain3_A0,
                     -HPF_Fs22050_Gain3_B1,
                    HPF_Fs22050_Gain3_Shift},
                    {HPF_Fs22050_Gain4_A1,             /* Gain setting 4 */
                     HPF_Fs22050_Gain4_A0,
                     -HPF_Fs22050_Gain4_B1,
                    HPF_Fs22050_Gain4_Shift},
                    {HPF_Fs22050_Gain5_A1,             /* Gain setting 5 */
                     HPF_Fs22050_Gain5_A0,
                     -HPF_Fs22050_Gain5_B1,
                    HPF_Fs22050_Gain5_Shift},
                    {HPF_Fs22050_Gain6_A1,             /* Gain setting 6 */
                     HPF_Fs22050_Gain6_A0,
                     -HPF_Fs22050_Gain6_B1,
                    HPF_Fs22050_Gain6_Shift},
                    {HPF_Fs22050_Gain7_A1,             /* Gain setting 7 */
                     HPF_Fs22050_Gain7_A0,
                     -HPF_Fs22050_Gain7_B1,
                    HPF_Fs22050_Gain7_Shift},
                    {HPF_Fs22050_Gain8_A1,             /* Gain setting 8 */
                     HPF_Fs22050_Gain8_A0,
                     -HPF_Fs22050_Gain8_B1,
                    HPF_Fs22050_Gain8_Shift},
                    {HPF_Fs22050_Gain9_A1,             /* Gain setting 9 */
                     HPF_Fs22050_Gain9_A0,
                     -HPF_Fs22050_Gain9_B1,
                    HPF_Fs22050_Gain9_Shift},
                    {HPF_Fs22050_Gain10_A1,             /* Gain setting 10 */
                     HPF_Fs22050_Gain10_A0,
                     -HPF_Fs22050_Gain10_B1,
                    HPF_Fs22050_Gain10_Shift},
                    {HPF_Fs22050_Gain11_A1,             /* Gain setting 11 */
                     HPF_Fs22050_Gain11_A0,
                     -HPF_Fs22050_Gain11_B1,
                    HPF_Fs22050_Gain11_Shift},
                    {HPF_Fs22050_Gain12_A1,             /* Gain setting 12 */
                     HPF_Fs22050_Gain12_A0,
                     -HPF_Fs22050_Gain12_B1,
                    HPF_Fs22050_Gain12_Shift},
                    {HPF_Fs22050_Gain13_A1,             /* Gain setting 13 */
                     HPF_Fs22050_Gain13_A0,
                     -HPF_Fs22050_Gain13_B1,
                    HPF_Fs22050_Gain13_Shift},
                    {HPF_Fs22050_Gain14_A1,             /* Gain setting 14 */
                     HPF_Fs22050_Gain14_A0,
                     -HPF_Fs22050_Gain14_B1,
                    HPF_Fs22050_Gain14_Shift},
                    {HPF_Fs22050_Gain15_A1,             /* Gain setting 15 */
                     HPF_Fs22050_Gain15_A0,
                     -HPF_Fs22050_Gain15_B1,
                    HPF_Fs22050_Gain15_Shift},

                    /* 24kHz sampling rate */
                    {HPF_Fs24000_Gain1_A1,             /* Gain setting 1 */
                     HPF_Fs24000_Gain1_A0,
                     -HPF_Fs24000_Gain1_B1,
                     HPF_Fs24000_Gain1_Shift},
                    {HPF_Fs24000_Gain2_A1,             /* Gain setting 2 */
                     HPF_Fs24000_Gain2_A0,
                     -HPF_Fs24000_Gain2_B1,
                     HPF_Fs24000_Gain2_Shift},
                    {HPF_Fs24000_Gain3_A1,             /* Gain setting 3 */
                     HPF_Fs24000_Gain3_A0,
                     -HPF_Fs24000_Gain3_B1,
                     HPF_Fs24000_Gain3_Shift},
                    {HPF_Fs24000_Gain4_A1,             /* Gain setting 4 */
                     HPF_Fs24000_Gain4_A0,
                     -HPF_Fs24000_Gain4_B1,
                     HPF_Fs24000_Gain4_Shift},
                    {HPF_Fs24000_Gain5_A1,             /* Gain setting 5 */
                     HPF_Fs24000_Gain5_A0,
                     -HPF_Fs24000_Gain5_B1,
                     HPF_Fs24000_Gain5_Shift},
                    {HPF_Fs24000_Gain6_A1,             /* Gain setting 6 */
                     HPF_Fs24000_Gain6_A0,
                     -HPF_Fs24000_Gain6_B1,
                     HPF_Fs24000_Gain6_Shift},
                    {HPF_Fs24000_Gain7_A1,             /* Gain setting 7 */
                     HPF_Fs24000_Gain7_A0,
                     -HPF_Fs24000_Gain7_B1,
                     HPF_Fs24000_Gain7_Shift},
                    {HPF_Fs24000_Gain8_A1,             /* Gain setting 8 */
                     HPF_Fs24000_Gain8_A0,
                     -HPF_Fs24000_Gain8_B1,
                     HPF_Fs24000_Gain8_Shift},
                    {HPF_Fs24000_Gain9_A1,             /* Gain setting 9 */
                     HPF_Fs24000_Gain9_A0,
                     -HPF_Fs24000_Gain9_B1,
                     HPF_Fs24000_Gain9_Shift},
                    {HPF_Fs24000_Gain10_A1,             /* Gain setting 10 */
                     HPF_Fs24000_Gain10_A0,
                     -HPF_Fs24000_Gain10_B1,
                     HPF_Fs24000_Gain10_Shift},
                    {HPF_Fs24000_Gain11_A1,             /* Gain setting 11 */
                     HPF_Fs24000_Gain11_A0,
                     -HPF_Fs24000_Gain11_B1,
                     HPF_Fs24000_Gain11_Shift},
                    {HPF_Fs24000_Gain12_A1,             /* Gain setting 12 */
                     HPF_Fs24000_Gain12_A0,
                     -HPF_Fs24000_Gain12_B1,
                     HPF_Fs24000_Gain12_Shift},
                    {HPF_Fs24000_Gain13_A1,             /* Gain setting 13 */
                     HPF_Fs24000_Gain13_A0,
                     -HPF_Fs24000_Gain13_B1,
                     HPF_Fs24000_Gain13_Shift},
                    {HPF_Fs24000_Gain14_A1,             /* Gain setting 14 */
                     HPF_Fs24000_Gain14_A0,
                     -HPF_Fs24000_Gain14_B1,
                     HPF_Fs24000_Gain14_Shift},
                    {HPF_Fs24000_Gain15_A1,             /* Gain setting 15 */
                     HPF_Fs24000_Gain15_A0,
                     -HPF_Fs24000_Gain15_B1,
                     HPF_Fs24000_Gain15_Shift},

                    /* 32kHz sampling rate */
                    {HPF_Fs32000_Gain1_A1,             /* Gain setting 1 */
                     HPF_Fs32000_Gain1_A0,
                     -HPF_Fs32000_Gain1_B1,
                    HPF_Fs32000_Gain1_Shift},
                    {HPF_Fs32000_Gain2_A1,             /* Gain setting 2 */
                     HPF_Fs32000_Gain2_A0,
                     -HPF_Fs32000_Gain2_B1,
                    HPF_Fs32000_Gain2_Shift},
                    {HPF_Fs32000_Gain3_A1,             /* Gain setting 3 */
                     HPF_Fs32000_Gain3_A0,
                     -HPF_Fs32000_Gain3_B1,
                    HPF_Fs32000_Gain3_Shift},
                    {HPF_Fs32000_Gain4_A1,             /* Gain setting 4 */
                     HPF_Fs32000_Gain4_A0,
                     -HPF_Fs32000_Gain4_B1,
                    HPF_Fs32000_Gain4_Shift},
                    {HPF_Fs32000_Gain5_A1,             /* Gain setting 5 */
                     HPF_Fs32000_Gain5_A0,
                     -HPF_Fs32000_Gain5_B1,
                    HPF_Fs32000_Gain5_Shift},
                    {HPF_Fs32000_Gain6_A1,             /* Gain setting 6 */
                     HPF_Fs32000_Gain6_A0,
                     -HPF_Fs32000_Gain6_B1,
                    HPF_Fs32000_Gain6_Shift},
                    {HPF_Fs32000_Gain7_A1,             /* Gain setting 7 */
                     HPF_Fs32000_Gain7_A0,
                     -HPF_Fs32000_Gain7_B1,
                    HPF_Fs32000_Gain7_Shift},
                    {HPF_Fs32000_Gain8_A1,             /* Gain setting 8 */
                     HPF_Fs32000_Gain8_A0,
                     -HPF_Fs32000_Gain8_B1,
                    HPF_Fs32000_Gain8_Shift},
                    {HPF_Fs32000_Gain9_A1,             /* Gain setting 9 */
                     HPF_Fs32000_Gain9_A0,
                     -HPF_Fs32000_Gain9_B1,
                    HPF_Fs32000_Gain9_Shift},
                    {HPF_Fs32000_Gain10_A1,             /* Gain setting 10 */
                     HPF_Fs32000_Gain10_A0,
                     -HPF_Fs32000_Gain10_B1,
                    HPF_Fs32000_Gain10_Shift},
                    {HPF_Fs32000_Gain11_A1,             /* Gain setting 11 */
                     HPF_Fs32000_Gain11_A0,
                     -HPF_Fs32000_Gain11_B1,
                    HPF_Fs32000_Gain11_Shift},
                    {HPF_Fs32000_Gain12_A1,             /* Gain setting 12 */
                     HPF_Fs32000_Gain12_A0,
                     -HPF_Fs32000_Gain12_B1,
                    HPF_Fs32000_Gain12_Shift},
                    {HPF_Fs32000_Gain13_A1,             /* Gain setting 13 */
                     HPF_Fs32000_Gain13_A0,
                     -HPF_Fs32000_Gain13_B1,
                    HPF_Fs32000_Gain13_Shift},
                    {HPF_Fs32000_Gain14_A1,             /* Gain setting 14 */
                     HPF_Fs32000_Gain14_A0,
                     -HPF_Fs32000_Gain14_B1,
                    HPF_Fs32000_Gain14_Shift},
                    {HPF_Fs32000_Gain15_A1,             /* Gain setting 15 */
                     HPF_Fs32000_Gain15_A0,
                     -HPF_Fs32000_Gain15_B1,
                    HPF_Fs32000_Gain15_Shift},

                    /* 44kHz sampling rate */
                    {HPF_Fs44100_Gain1_A1,             /* Gain setting 1 */
                     HPF_Fs44100_Gain1_A0,
                     -HPF_Fs44100_Gain1_B1,
                     HPF_Fs44100_Gain1_Shift},
                    {HPF_Fs44100_Gain2_A1,             /* Gain setting 2 */
                     HPF_Fs44100_Gain2_A0,
                     -HPF_Fs44100_Gain2_B1,
                     HPF_Fs44100_Gain2_Shift},
                    {HPF_Fs44100_Gain3_A1,             /* Gain setting 3 */
                     HPF_Fs44100_Gain3_A0,
                     -HPF_Fs44100_Gain3_B1,
                     HPF_Fs44100_Gain3_Shift},
                    {HPF_Fs44100_Gain4_A1,             /* Gain setting 4 */
                     HPF_Fs44100_Gain4_A0,
                     -HPF_Fs44100_Gain4_B1,
                     HPF_Fs44100_Gain4_Shift},
                    {HPF_Fs44100_Gain5_A1,             /* Gain setting 5 */
                     HPF_Fs44100_Gain5_A0,
                     -HPF_Fs44100_Gain5_B1,
                     HPF_Fs44100_Gain5_Shift},
                    {HPF_Fs44100_Gain6_A1,             /* Gain setting 6 */
                     HPF_Fs44100_Gain6_A0,
                     -HPF_Fs44100_Gain6_B1,
                     HPF_Fs44100_Gain6_Shift},
                    {HPF_Fs44100_Gain7_A1,             /* Gain setting 7 */
                     HPF_Fs44100_Gain7_A0,
                     -HPF_Fs44100_Gain7_B1,
                     HPF_Fs44100_Gain7_Shift},
                    {HPF_Fs44100_Gain8_A1,             /* Gain setting 8 */
                     HPF_Fs44100_Gain8_A0,
                     -HPF_Fs44100_Gain8_B1,
                     HPF_Fs44100_Gain8_Shift},
                    {HPF_Fs44100_Gain9_A1,             /* Gain setting 9 */
                     HPF_Fs44100_Gain9_A0,
                     -HPF_Fs44100_Gain9_B1,
                     HPF_Fs44100_Gain9_Shift},
                    {HPF_Fs44100_Gain10_A1,             /* Gain setting 10 */
                     HPF_Fs44100_Gain10_A0,
                     -HPF_Fs44100_Gain10_B1,
                     HPF_Fs44100_Gain10_Shift},
                    {HPF_Fs44100_Gain11_A1,             /* Gain setting 11 */
                     HPF_Fs44100_Gain11_A0,
                     -HPF_Fs44100_Gain11_B1,
                     HPF_Fs44100_Gain11_Shift},
                    {HPF_Fs44100_Gain12_A1,             /* Gain setting 12 */
                     HPF_Fs44100_Gain12_A0,
                     -HPF_Fs44100_Gain12_B1,
                     HPF_Fs44100_Gain12_Shift},
                    {HPF_Fs44100_Gain13_A1,             /* Gain setting 13 */
                     HPF_Fs44100_Gain13_A0,
                     -HPF_Fs44100_Gain13_B1,
                     HPF_Fs44100_Gain13_Shift},
                    {HPF_Fs44100_Gain14_A1,             /* Gain setting 14 */
                     HPF_Fs44100_Gain14_A0,
                     -HPF_Fs44100_Gain14_B1,
                     HPF_Fs44100_Gain14_Shift},
                    {HPF_Fs44100_Gain15_A1,             /* Gain setting 15 */
                     HPF_Fs44100_Gain15_A0,
                     -HPF_Fs44100_Gain15_B1,
                     HPF_Fs44100_Gain15_Shift},

                    /* 48kHz sampling rate */
                    {HPF_Fs48000_Gain1_A1,             /* Gain setting 1 */
                     HPF_Fs48000_Gain1_A0,
                     -HPF_Fs48000_Gain1_B1,
                     HPF_Fs48000_Gain1_Shift},
                    {HPF_Fs48000_Gain2_A1,             /* Gain setting 2 */
                     HPF_Fs48000_Gain2_A0,
                     -HPF_Fs48000_Gain2_B1,
                     HPF_Fs48000_Gain2_Shift},
                    {HPF_Fs48000_Gain3_A1,             /* Gain setting 3 */
                     HPF_Fs48000_Gain3_A0,
                     -HPF_Fs48000_Gain3_B1,
                     HPF_Fs48000_Gain3_Shift},
                    {HPF_Fs48000_Gain4_A1,             /* Gain setting 4 */
                     HPF_Fs48000_Gain4_A0,
                     -HPF_Fs48000_Gain4_B1,
                     HPF_Fs48000_Gain4_Shift},
                    {HPF_Fs48000_Gain5_A1,             /* Gain setting 5 */
                     HPF_Fs48000_Gain5_A0,
                     -HPF_Fs48000_Gain5_B1,
                     HPF_Fs48000_Gain5_Shift},
                    {HPF_Fs48000_Gain6_A1,             /* Gain setting 6 */
                     HPF_Fs48000_Gain6_A0,
                     -HPF_Fs48000_Gain6_B1,
                     HPF_Fs48000_Gain6_Shift},
                    {HPF_Fs48000_Gain7_A1,             /* Gain setting 7 */
                     HPF_Fs48000_Gain7_A0,
                     -HPF_Fs48000_Gain7_B1,
                     HPF_Fs48000_Gain7_Shift},
                    {HPF_Fs48000_Gain8_A1,             /* Gain setting 8 */
                     HPF_Fs48000_Gain8_A0,
                     -HPF_Fs48000_Gain8_B1,
                     HPF_Fs48000_Gain8_Shift},
                    {HPF_Fs48000_Gain9_A1,             /* Gain setting 9 */
                     HPF_Fs48000_Gain9_A0,
                     -HPF_Fs48000_Gain9_B1,
                     HPF_Fs48000_Gain9_Shift},
                    {HPF_Fs48000_Gain10_A1,             /* Gain setting 10 */
                     HPF_Fs48000_Gain10_A0,
                     -HPF_Fs48000_Gain10_B1,
                     HPF_Fs48000_Gain10_Shift},
                    {HPF_Fs48000_Gain11_A1,             /* Gain setting 11 */
                     HPF_Fs48000_Gain11_A0,
                     -HPF_Fs48000_Gain11_B1,
                     HPF_Fs48000_Gain11_Shift},
                    {HPF_Fs48000_Gain12_A1,             /* Gain setting 12 */
                     HPF_Fs48000_Gain12_A0,
                     -HPF_Fs48000_Gain12_B1,
                     HPF_Fs48000_Gain12_Shift},
                    {HPF_Fs48000_Gain13_A1,             /* Gain setting 13 */
                     HPF_Fs48000_Gain13_A0,
                     -HPF_Fs48000_Gain13_B1,
                     HPF_Fs48000_Gain13_Shift},
                    {HPF_Fs48000_Gain14_A1,             /* Gain setting 14 */
                     HPF_Fs48000_Gain14_A0,
                     -HPF_Fs48000_Gain14_B1,
                     HPF_Fs48000_Gain14_Shift},
                    {HPF_Fs48000_Gain15_A1,             /* Gain setting 15 */
                     HPF_Fs48000_Gain15_A0,
                     -HPF_Fs48000_Gain15_B1,
                     HPF_Fs48000_Gain15_Shift}};


/************************************************************************************/
/*                                                                                    */
/*    Volume control gain and time constant tables                                    */
/*                                                                                    */
/************************************************************************************/

/* dB to linear conversion table */
const LVM_INT16 LVM_VolumeTable[] = {
    0x7FFF,             /*  0dB */
    0x7215,             /* -1dB */
    0x65AD,             /* -2dB */
    0x5A9E,             /* -3dB */
    0x50C3,             /* -4dB */
    0x47FB,             /* -5dB */
    0x4000};            /* -6dB */

/************************************************************************************/
/*                                                                                  */
/*  Volume mixer time constants (100ms)                                             */
/*                                                                                  */
/************************************************************************************/

#define LVM_MIX_TC_Fs8000      32580         /* Floating point value 0.994262695 */
#define LVM_MIX_TC_Fs11025     32632         /* Floating point value 0.995849609 */
#define LVM_MIX_TC_Fs12000     32643         /* Floating point value 0.996185303 */
#define LVM_MIX_TC_Fs16000     32674         /* Floating point value 0.997131348 */
#define LVM_MIX_TC_Fs22050     32700         /* Floating point value 0.997924805 */
#define LVM_MIX_TC_Fs24000     32705         /* Floating point value 0.998077393 */
#define LVM_MIX_TC_Fs32000     32721         /* Floating point value 0.998565674 */
#define LVM_MIX_TC_Fs44100     32734         /* Floating point value 0.998962402 */
#define LVM_MIX_TC_Fs48000     32737         /* Floating point value 0.999053955 */


const LVM_INT16 LVM_MixerTCTable[] = {
    LVM_MIX_TC_Fs8000,
    LVM_MIX_TC_Fs11025,
    LVM_MIX_TC_Fs12000,
    LVM_MIX_TC_Fs16000,
    LVM_MIX_TC_Fs22050,
    LVM_MIX_TC_Fs24000,
    LVM_MIX_TC_Fs32000,
    LVM_MIX_TC_Fs44100,
    LVM_MIX_TC_Fs48000};

