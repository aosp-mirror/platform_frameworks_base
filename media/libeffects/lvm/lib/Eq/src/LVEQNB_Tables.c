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

#include "LVEQNB.h"
#include "LVEQNB_Coeffs.h"


/************************************************************************************/
/*                                                                                  */
/*    Sample rate table                                                             */
/*                                                                                  */
/************************************************************************************/

/*
 * Sample rate table for converting between the enumerated type and the actual
 * frequency
 */
const LVM_UINT16    LVEQNB_SampleRateTab[] = {8000,                    /* 8kS/s  */
                                              11025,
                                              12000,
                                              16000,
                                              22050,
                                              24000,
                                              32000,
                                              44100,
                                              48000};                  /* 48kS/s */


/************************************************************************************/
/*                                                                                  */
/*    Coefficient calculation tables                                                */
/*                                                                                  */
/************************************************************************************/

/*
 * Table for 2 * Pi / Fs
 */
const LVM_INT16     LVEQNB_TwoPiOnFsTable[] = {LVEQNB_2PiOn_8000,      /* 8kS/s */
                                               LVEQNB_2PiOn_11025,
                                               LVEQNB_2PiOn_12000,
                                               LVEQNB_2PiOn_16000,
                                               LVEQNB_2PiOn_22050,
                                               LVEQNB_2PiOn_24000,
                                               LVEQNB_2PiOn_32000,
                                               LVEQNB_2PiOn_44100,
                                               LVEQNB_2PiOn_48000};    /* 48kS/s */

/*
 * Gain table
 */
const LVM_INT16     LVEQNB_GainTable[] = {LVEQNB_Gain_Neg15_dB,        /* -15dB gain */
                                          LVEQNB_Gain_Neg14_dB,
                                          LVEQNB_Gain_Neg13_dB,
                                          LVEQNB_Gain_Neg12_dB,
                                          LVEQNB_Gain_Neg11_dB,
                                          LVEQNB_Gain_Neg10_dB,
                                          LVEQNB_Gain_Neg9_dB,
                                          LVEQNB_Gain_Neg8_dB,
                                          LVEQNB_Gain_Neg7_dB,
                                          LVEQNB_Gain_Neg6_dB,
                                          LVEQNB_Gain_Neg5_dB,
                                          LVEQNB_Gain_Neg4_dB,
                                          LVEQNB_Gain_Neg3_dB,
                                          LVEQNB_Gain_Neg2_dB,
                                          LVEQNB_Gain_Neg1_dB,
                                          LVEQNB_Gain_0_dB,            /* 0dB gain */
                                          LVEQNB_Gain_1_dB,
                                          LVEQNB_Gain_2_dB,
                                          LVEQNB_Gain_3_dB,
                                          LVEQNB_Gain_4_dB,
                                          LVEQNB_Gain_5_dB,
                                          LVEQNB_Gain_6_dB,
                                          LVEQNB_Gain_7_dB,
                                          LVEQNB_Gain_8_dB,
                                          LVEQNB_Gain_9_dB,
                                          LVEQNB_Gain_10_dB,
                                          LVEQNB_Gain_11_dB,
                                          LVEQNB_Gain_12_dB,
                                          LVEQNB_Gain_13_dB,
                                          LVEQNB_Gain_14_dB,
                                          LVEQNB_Gain_15_dB};          /* +15dB gain */


/*
 * D table for 100 / (Gain + 1)
 */
const LVM_INT16    LVEQNB_DTable[] = {LVEQNB_100D_Neg15_dB,            /* -15dB gain */
                                      LVEQNB_100D_Neg14_dB,
                                      LVEQNB_100D_Neg13_dB,
                                      LVEQNB_100D_Neg12_dB,
                                      LVEQNB_100D_Neg11_dB,
                                      LVEQNB_100D_Neg10_dB,
                                      LVEQNB_100D_Neg9_dB,
                                      LVEQNB_100D_Neg8_dB,
                                      LVEQNB_100D_Neg7_dB,
                                      LVEQNB_100D_Neg6_dB,
                                      LVEQNB_100D_Neg5_dB,
                                      LVEQNB_100D_Neg4_dB,
                                      LVEQNB_100D_Neg3_dB,
                                      LVEQNB_100D_Neg2_dB,
                                      LVEQNB_100D_Neg1_dB,
                                      LVEQNB_100D_0_dB};               /* 0dB gain */


/************************************************************************************/
/*                                                                                  */
/*    Filter polynomial coefficients                                                */
/*                                                                                  */
/************************************************************************************/

/*
 * Coefficients for calculating the cosine with the equation:
 *
 *  Cos(x) = (2^Shifts)*(a0 + a1*x + a2*x^2 + a3*x^3 + a4*x^4 + a5*x^5)
 *
 * These coefficients expect the input, x, to be in the range 0 to 32768 respresenting
 * a range of 0 to Pi. The output is in the range 32767 to -32768 representing the range
 * +1.0 to -1.0
 */
const LVM_INT16     LVEQNB_CosCoef[] = {3,                             /* Shifts */
                                        4096,                          /* a0 */
                                        -36,                           /* a1 */
                                        -19725,                        /* a2 */
                                        -2671,                         /* a3 */
                                        23730,                         /* a4 */
                                        -9490};                        /* a5 */

/*
 * Coefficients for calculating the cosine error with the equation:
 *
 *  CosErr(x) = (2^Shifts)*(a0 + a1*x + a2*x^2 + a3*x^3)
 *
 * These coefficients expect the input, x, to be in the range 0 to 32768 respresenting
 * a range of 0 to Pi/25. The output is in the range 0 to 32767 representing the range
 * 0.0 to 0.0078852986
 *
 * This is used to give a double precision cosine over the range 0 to Pi/25 using the
 * the equation:
 *
 * Cos(x) = 1.0 - CosErr(x)
 */
const LVM_INT16     LVEQNB_DPCosCoef[] = {1,                           /* Shifts */
                                          0,                           /* a0 */
                                          -6,                          /* a1 */
                                          16586,                       /* a2 */
                                          -44};                        /* a3 */

/************************************************************************************/
/*                                                                                  */
/*  Bypass mixer time constants (100ms)                                             */
/*                                                                                  */
/************************************************************************************/

#define LVEQNB_MIX_TC_Fs8000    32580         /* Floating point value 0.994262695 */
#define LVEQNB_MIX_TC_Fs11025   32632         /* Floating point value 0.995849609 */
#define LVEQNB_MIX_TC_Fs12000   32643         /* Floating point value 0.996185303 */
#define LVEQNB_MIX_TC_Fs16000   32674         /* Floating point value 0.997131348 */
#define LVEQNB_MIX_TC_Fs22050   32700         /* Floating point value 0.997924805 */
#define LVEQNB_MIX_TC_Fs24000   32705         /* Floating point value 0.998077393 */
#define LVEQNB_MIX_TC_Fs32000   32721         /* Floating point value 0.998565674 */
#define LVEQNB_MIX_TC_Fs44100   32734         /* Floating point value 0.998962402 */
#define LVEQNB_MIX_TC_Fs48000   32737         /* Floating point value 0.999053955 */


const LVM_INT16 LVEQNB_MixerTCTable[] = {
    LVEQNB_MIX_TC_Fs8000,
    LVEQNB_MIX_TC_Fs11025,
    LVEQNB_MIX_TC_Fs12000,
    LVEQNB_MIX_TC_Fs16000,
    LVEQNB_MIX_TC_Fs22050,
    LVEQNB_MIX_TC_Fs24000,
    LVEQNB_MIX_TC_Fs32000,
    LVEQNB_MIX_TC_Fs44100,
    LVEQNB_MIX_TC_Fs48000};
