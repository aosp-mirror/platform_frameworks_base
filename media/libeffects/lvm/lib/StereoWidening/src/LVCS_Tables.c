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
/*  Includes                                                                        */
/*                                                                                  */
/************************************************************************************/

#include "LVCS_Private.h"
#include "Filters.h"                            /* Filter definitions */
#include "BIQUAD.h"                             /* Biquad definitions */
#include "LVCS_Headphone_Coeffs.h"              /* Headphone coefficients */


/************************************************************************************/
/*                                                                                  */
/*  Stereo Enhancer coefficient constant tables                                     */
/*                                                                                  */
/************************************************************************************/

/* Coefficient table for the middle filter */
const BiquadA01B1CoefsSP_t LVCS_SEMidCoefTable[] = {
    {CS_MIDDLE_8000_A0,         /* 8kS/s coefficients */
     CS_MIDDLE_8000_A1,
     CS_MIDDLE_8000_B1,
     (LVM_UINT16 )CS_MIDDLE_8000_SCALE},
    {CS_MIDDLE_11025_A0,        /* 11kS/s coefficients */
     CS_MIDDLE_11025_A1,
     CS_MIDDLE_11025_B1,
     (LVM_UINT16 )CS_MIDDLE_11025_SCALE},
    {CS_MIDDLE_12000_A0,        /* 12kS/s coefficients */
     CS_MIDDLE_12000_A1,
     CS_MIDDLE_12000_B1,
     (LVM_UINT16 )CS_MIDDLE_12000_SCALE},
    {CS_MIDDLE_16000_A0,        /* 16kS/s coefficients */
     CS_MIDDLE_16000_A1,
     CS_MIDDLE_16000_B1,
     (LVM_UINT16 )CS_MIDDLE_16000_SCALE},
    {CS_MIDDLE_22050_A0,        /* 22kS/s coefficients */
     CS_MIDDLE_22050_A1,
     CS_MIDDLE_22050_B1,
     (LVM_UINT16 )CS_MIDDLE_22050_SCALE},
    {CS_MIDDLE_24000_A0,        /* 24kS/s coefficients */
     CS_MIDDLE_24000_A1,
     CS_MIDDLE_24000_B1,
     (LVM_UINT16 )CS_MIDDLE_24000_SCALE},
    {CS_MIDDLE_32000_A0,        /* 32kS/s coefficients */
     CS_MIDDLE_32000_A1,
     CS_MIDDLE_32000_B1,
     (LVM_UINT16 )CS_MIDDLE_32000_SCALE},
    {CS_MIDDLE_44100_A0,        /* 44kS/s coefficients */
     CS_MIDDLE_44100_A1,
     CS_MIDDLE_44100_B1,
     (LVM_UINT16 )CS_MIDDLE_44100_SCALE},
    {CS_MIDDLE_48000_A0,        /* 48kS/s coefficients */
     CS_MIDDLE_48000_A1,
     CS_MIDDLE_48000_B1,
     (LVM_UINT16 )CS_MIDDLE_48000_SCALE}};

/* Coefficient table for the side filter */
const BiquadA012B12CoefsSP_t LVCS_SESideCoefTable[] = {
    /* Headphone Side coefficients */
    {CS_SIDE_8000_A0,           /* 8kS/s coefficients */
     CS_SIDE_8000_A1,
     CS_SIDE_8000_A2,
     CS_SIDE_8000_B1,
     CS_SIDE_8000_B2,
     (LVM_UINT16 )CS_SIDE_8000_SCALE},
    {CS_SIDE_11025_A0,          /* 11kS/s coefficients */
     CS_SIDE_11025_A1,
     CS_SIDE_11025_A2,
     CS_SIDE_11025_B1,
     CS_SIDE_11025_B2,
     (LVM_UINT16 )CS_SIDE_11025_SCALE},
    {CS_SIDE_12000_A0,          /* 12kS/s coefficients */
     CS_SIDE_12000_A1,
     CS_SIDE_12000_A2,
     CS_SIDE_12000_B1,
     CS_SIDE_12000_B2,
     (LVM_UINT16 )CS_SIDE_12000_SCALE},
    {CS_SIDE_16000_A0,          /* 16kS/s coefficients */
     CS_SIDE_16000_A1,
     CS_SIDE_16000_A2,
     CS_SIDE_16000_B1,
     CS_SIDE_16000_B2,
     (LVM_UINT16 )CS_SIDE_16000_SCALE},
    {CS_SIDE_22050_A0,          /* 22kS/s coefficients */
     CS_SIDE_22050_A1,
     CS_SIDE_22050_A2,
     CS_SIDE_22050_B1,
     CS_SIDE_22050_B2,
     (LVM_UINT16 )CS_SIDE_22050_SCALE},
    {CS_SIDE_24000_A0,          /* 24kS/s coefficients */
     CS_SIDE_24000_A1,
     CS_SIDE_24000_A2,
     CS_SIDE_24000_B1,
     CS_SIDE_24000_B2,
     (LVM_UINT16 )CS_SIDE_24000_SCALE},
    {CS_SIDE_32000_A0,          /* 32kS/s coefficients */
     CS_SIDE_32000_A1,
     CS_SIDE_32000_A2,
     CS_SIDE_32000_B1,
     CS_SIDE_32000_B2,
     (LVM_UINT16 )CS_SIDE_32000_SCALE},
    {CS_SIDE_44100_A0,          /* 44kS/s coefficients */
     CS_SIDE_44100_A1,
     CS_SIDE_44100_A2,
     CS_SIDE_44100_B1,
     CS_SIDE_44100_B2,
     (LVM_UINT16 )CS_SIDE_44100_SCALE},
    {CS_SIDE_48000_A0,          /* 48kS/s coefficients */
     CS_SIDE_48000_A1,
     CS_SIDE_48000_A2,
     CS_SIDE_48000_B1,
     CS_SIDE_48000_B2,
     (LVM_UINT16 )CS_SIDE_48000_SCALE}
};


/************************************************************************************/
/*                                                                                  */
/*  Equaliser coefficient constant tables                                           */
/*                                                                                  */
/************************************************************************************/

const BiquadA012B12CoefsSP_t LVCS_EqualiserCoefTable[] = {
    /* Headphone coefficients */
    {CS_EQUALISER_8000_A0,      /* 8kS/s coefficients */
     CS_EQUALISER_8000_A1,
     CS_EQUALISER_8000_A2,
     CS_EQUALISER_8000_B1,
     CS_EQUALISER_8000_B2,
     (LVM_UINT16 )CS_EQUALISER_8000_SCALE},
    {CS_EQUALISER_11025_A0,     /* 11kS/s coefficients */
     CS_EQUALISER_11025_A1,
     CS_EQUALISER_11025_A2,
     CS_EQUALISER_11025_B1,
     CS_EQUALISER_11025_B2,
     (LVM_UINT16 )CS_EQUALISER_11025_SCALE},
    {CS_EQUALISER_12000_A0,     /* 12kS/s coefficients */
     CS_EQUALISER_12000_A1,
     CS_EQUALISER_12000_A2,
     CS_EQUALISER_12000_B1,
     CS_EQUALISER_12000_B2,
     (LVM_UINT16 )CS_EQUALISER_12000_SCALE},
    {CS_EQUALISER_16000_A0,     /* 16kS/s coefficients */
     CS_EQUALISER_16000_A1,
     CS_EQUALISER_16000_A2,
     CS_EQUALISER_16000_B1,
     CS_EQUALISER_16000_B2,
     (LVM_UINT16 )CS_EQUALISER_16000_SCALE},
    {CS_EQUALISER_22050_A0,     /* 22kS/s coefficients */
     CS_EQUALISER_22050_A1,
     CS_EQUALISER_22050_A2,
     CS_EQUALISER_22050_B1,
     CS_EQUALISER_22050_B2,
     (LVM_UINT16 )CS_EQUALISER_22050_SCALE},
    {CS_EQUALISER_24000_A0,     /* 24kS/s coefficients */
     CS_EQUALISER_24000_A1,
     CS_EQUALISER_24000_A2,
     CS_EQUALISER_24000_B1,
     CS_EQUALISER_24000_B2,
     (LVM_UINT16 )CS_EQUALISER_24000_SCALE},
    {CS_EQUALISER_32000_A0,     /* 32kS/s coefficients */
     CS_EQUALISER_32000_A1,
     CS_EQUALISER_32000_A2,
     CS_EQUALISER_32000_B1,
     CS_EQUALISER_32000_B2,
     (LVM_UINT16 )CS_EQUALISER_32000_SCALE},
    {CS_EQUALISER_44100_A0,     /* 44kS/s coefficients */
     CS_EQUALISER_44100_A1,
     CS_EQUALISER_44100_A2,
     CS_EQUALISER_44100_B1,
     CS_EQUALISER_44100_B2,
     (LVM_UINT16 )CS_EQUALISER_44100_SCALE},
    {CS_EQUALISER_48000_A0,     /* 48kS/s coefficients */
     CS_EQUALISER_48000_A1,
     CS_EQUALISER_48000_A2,
     CS_EQUALISER_48000_B1,
     CS_EQUALISER_48000_B2,
     (LVM_UINT16 )CS_EQUALISER_48000_SCALE},

    /* Concert Sound EX Headphone coefficients */
    {CSEX_EQUALISER_8000_A0,    /* 8kS/s coefficients */
     CSEX_EQUALISER_8000_A1,
     CSEX_EQUALISER_8000_A2,
     CSEX_EQUALISER_8000_B1,
     CSEX_EQUALISER_8000_B2,
     (LVM_UINT16 )CSEX_EQUALISER_8000_SCALE},
    {CSEX_EQUALISER_11025_A0,   /* 11kS/s coefficients */
     CSEX_EQUALISER_11025_A1,
     CSEX_EQUALISER_11025_A2,
     CSEX_EQUALISER_11025_B1,
     CSEX_EQUALISER_11025_B2,
     (LVM_UINT16 )CSEX_EQUALISER_11025_SCALE},
    {CSEX_EQUALISER_12000_A0,   /* 12kS/s coefficients */
     CSEX_EQUALISER_12000_A1,
     CSEX_EQUALISER_12000_A2,
     CSEX_EQUALISER_12000_B1,
     CSEX_EQUALISER_12000_B2,
     (LVM_UINT16 )CSEX_EQUALISER_12000_SCALE},
    {CSEX_EQUALISER_16000_A0,   /* 16kS/s coefficients */
     CSEX_EQUALISER_16000_A1,
     CSEX_EQUALISER_16000_A2,
     CSEX_EQUALISER_16000_B1,
     CSEX_EQUALISER_16000_B2,
     (LVM_UINT16 )CSEX_EQUALISER_16000_SCALE},
    {CSEX_EQUALISER_22050_A0,   /* 22kS/s coefficients */
     CSEX_EQUALISER_22050_A1,
     CSEX_EQUALISER_22050_A2,
     CSEX_EQUALISER_22050_B1,
     CSEX_EQUALISER_22050_B2,
     (LVM_UINT16 )CSEX_EQUALISER_22050_SCALE},
    {CSEX_EQUALISER_24000_A0,   /* 24kS/s coefficients */
     CSEX_EQUALISER_24000_A1,
     CSEX_EQUALISER_24000_A2,
     CSEX_EQUALISER_24000_B1,
     CSEX_EQUALISER_24000_B2,
     (LVM_UINT16 )CSEX_EQUALISER_24000_SCALE},
    {CSEX_EQUALISER_32000_A0,   /* 32kS/s coefficients */
     CSEX_EQUALISER_32000_A1,
     CSEX_EQUALISER_32000_A2,
     CSEX_EQUALISER_32000_B1,
     CSEX_EQUALISER_32000_B2,
     (LVM_UINT16 )CSEX_EQUALISER_32000_SCALE},
    {CSEX_EQUALISER_44100_A0,   /* 44kS/s coefficients */
     CSEX_EQUALISER_44100_A1,
     CSEX_EQUALISER_44100_A2,
     CSEX_EQUALISER_44100_B1,
     CSEX_EQUALISER_44100_B2,
     (LVM_UINT16 )CSEX_EQUALISER_44100_SCALE},
    {CSEX_EQUALISER_48000_A0,   /* 48kS/s coefficients */
     CSEX_EQUALISER_48000_A1,
     CSEX_EQUALISER_48000_A2,
     CSEX_EQUALISER_48000_B1,
     CSEX_EQUALISER_48000_B2,
     (LVM_UINT16 )CSEX_EQUALISER_48000_SCALE}
};


/************************************************************************************/
/*                                                                                  */
/*  Reverb delay constant tables                                                    */
/*                                                                                  */
/************************************************************************************/

/* Stereo delay table for Concert Sound */
const LVM_UINT16    LVCS_StereoDelayCS[] = {
    LVCS_STEREODELAY_CS_8KHZ,
    LVCS_STEREODELAY_CS_11KHZ,
    LVCS_STEREODELAY_CS_12KHZ,
    LVCS_STEREODELAY_CS_16KHZ,
    LVCS_STEREODELAY_CS_22KHZ,
    LVCS_STEREODELAY_CS_24KHZ,
    LVCS_STEREODELAY_CS_32KHZ,
    LVCS_STEREODELAY_CS_44KHZ,
    LVCS_STEREODELAY_CS_48KHZ};

/************************************************************************************/
/*                                                                                  */
/*  Reverb coefficients constant table                                              */
/*                                                                                  */
/************************************************************************************/

const BiquadA012B12CoefsSP_t LVCS_ReverbCoefTable[] = {
    /* Headphone coefficients */
    {CS_REVERB_8000_A0,             /* 8kS/s coefficients */
     CS_REVERB_8000_A1,
     CS_REVERB_8000_A2,
     CS_REVERB_8000_B1,
     CS_REVERB_8000_B2,
     (LVM_UINT16 )CS_REVERB_8000_SCALE},
    {CS_REVERB_11025_A0,            /* 11kS/s coefficients */
     CS_REVERB_11025_A1,
     CS_REVERB_11025_A2,
     CS_REVERB_11025_B1,
     CS_REVERB_11025_B2,
     (LVM_UINT16 )CS_REVERB_11025_SCALE},
    {CS_REVERB_12000_A0,            /* 12kS/s coefficients */
     CS_REVERB_12000_A1,
     CS_REVERB_12000_A2,
     CS_REVERB_12000_B1,
     CS_REVERB_12000_B2,
     (LVM_UINT16 )CS_REVERB_12000_SCALE},
    {CS_REVERB_16000_A0,            /* 16kS/s coefficients */
     CS_REVERB_16000_A1,
     CS_REVERB_16000_A2,
     CS_REVERB_16000_B1,
     CS_REVERB_16000_B2,
     (LVM_UINT16 )CS_REVERB_16000_SCALE},
    {CS_REVERB_22050_A0,            /* 22kS/s coefficients */
     CS_REVERB_22050_A1,
     CS_REVERB_22050_A2,
     CS_REVERB_22050_B1,
     CS_REVERB_22050_B2,
     (LVM_UINT16 )CS_REVERB_22050_SCALE},
    {CS_REVERB_24000_A0,            /* 24kS/s coefficients */
     CS_REVERB_24000_A1,
     CS_REVERB_24000_A2,
     CS_REVERB_24000_B1,
     CS_REVERB_24000_B2,
     (LVM_UINT16 )CS_REVERB_24000_SCALE},
    {CS_REVERB_32000_A0,            /* 32kS/s coefficients */
     CS_REVERB_32000_A1,
     CS_REVERB_32000_A2,
     CS_REVERB_32000_B1,
     CS_REVERB_32000_B2,
     (LVM_UINT16 )CS_REVERB_32000_SCALE},
    {CS_REVERB_44100_A0,            /* 44kS/s coefficients */
     CS_REVERB_44100_A1,
     CS_REVERB_44100_A2,
     CS_REVERB_44100_B1,
     CS_REVERB_44100_B2,
     (LVM_UINT16 )CS_REVERB_44100_SCALE},
    {CS_REVERB_48000_A0,            /* 48kS/s coefficients */
     CS_REVERB_48000_A1,
     CS_REVERB_48000_A2,
     CS_REVERB_48000_B1,
     CS_REVERB_48000_B2,
     (LVM_UINT16 )CS_REVERB_48000_SCALE}
};


/************************************************************************************/
/*                                                                                  */
/*  Bypass mixer constant tables                                                    */
/*                                                                                  */
/************************************************************************************/

const Gain_t LVCS_OutputGainTable[] = {
    {LVCS_HEADPHONE_SHIFT,         /* Headphone, stereo mode */
     LVCS_HEADPHONE_SHIFTLOSS,
     LVCS_HEADPHONE_GAIN},
    {LVCS_EX_HEADPHONE_SHIFT,      /* EX Headphone, stereo mode */
     LVCS_EX_HEADPHONE_SHIFTLOSS,
     LVCS_EX_HEADPHONE_GAIN},
    {LVCS_HEADPHONE_SHIFT,         /* Headphone, mono mode */
     LVCS_HEADPHONE_SHIFTLOSS,
     LVCS_HEADPHONE_GAIN},
    {LVCS_EX_HEADPHONE_SHIFT,      /* EX Headphone, mono mode */
     LVCS_EX_HEADPHONE_SHIFTLOSS,
     LVCS_EX_HEADPHONE_GAIN}
};


/************************************************************************************/
/*                                                                                  */
/*  Volume correction table                                                         */
/*                                                                                  */
/*  Coefficient order:                                                              */
/*      Compression 100% effect                                                     */
/*      Compression 0% effect                                                       */
/*      Gain 100% effect                                                            */
/*      Gain 0% effect                                                              */
/*                                                                                  */
/*  The Compression gain is represented by a Q1.15 number to give a range of 0dB    */
/*  to +6dB, E.g.:                                                                  */
/*          0       is 0dB compression (no effect)                                  */
/*          5461    is 1dB compression gain                                         */
/*          10923   is 2dB compression gain                                         */
/*          32767   is 6dB compression gain                                         */
/*                                                                                  */
/*  The Gain is represented as a Q3.13 number to give a range of +8 to -infinity    */
/*  E.g.:                                                                           */
/*          0       is -infinity                                                    */
/*          32767   is +18dB (x8) gain                                              */
/*          4096    is 0dB gain                                                     */
/*          1024    is -12dB gain                                                   */
/*                                                                                  */
/************************************************************************************/
const LVCS_VolCorrect_t LVCS_VolCorrectTable[] = {
    {14200,          /* Headphone, stereo mode */
     0,
     4096,
     5786},
    {14200,          /* EX Headphone, stereo mode */
     0,
     4096,
     5786},
    {32767,         /* Headphone, mono mode */
     0,
     4096,
     5786},
    {32767,         /* EX Headphone, mono mode */
     0,
     4096,
     5786}
};

/************************************************************************************/
/*                                                                                  */
/*  Mixer time constants, 100ms                                                     */
/*                                                                                  */
/************************************************************************************/

#define LVCS_VOL_TC_Fs8000      32580       /* Floating point value 0.994262695 */
#define LVCS_VOL_TC_Fs11025     32632       /* Floating point value 0.995849609 */
#define LVCS_VOL_TC_Fs12000     32643       /* Floating point value 0.996185303 */
#define LVCS_VOL_TC_Fs16000     32674       /* Floating point value 0.997131348 */
#define LVCS_VOL_TC_Fs22050     32700       /* Floating point value 0.997924805 */
#define LVCS_VOL_TC_Fs24000     32705       /* Floating point value 0.998077393 */
#define LVCS_VOL_TC_Fs32000     32721       /* Floating point value 0.998565674 */
#define LVCS_VOL_TC_Fs44100     32734       /* Floating point value 0.998962402 */
#define LVCS_VOL_TC_Fs48000     32737       /* Floating point value 0.999053955 */


const LVM_INT16 LVCS_VolumeTCTable[9] = {LVCS_VOL_TC_Fs8000,
                                        LVCS_VOL_TC_Fs11025,
                                        LVCS_VOL_TC_Fs12000,
                                        LVCS_VOL_TC_Fs16000,
                                        LVCS_VOL_TC_Fs22050,
                                        LVCS_VOL_TC_Fs24000,
                                        LVCS_VOL_TC_Fs32000,
                                        LVCS_VOL_TC_Fs44100,
                                        LVCS_VOL_TC_Fs48000};

/************************************************************************************/
/*                                                                                  */
/*  Sample rate table                                                               */
/*                                                                                  */
/************************************************************************************/

const LVM_INT32   LVCS_SampleRateTable[9] = {8000,
                                            11025,
                                            12000,
                                            16000,
                                            22050,
                                            24000,
                                            32000,
                                            44100,
                                            48000};

