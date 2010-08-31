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

#ifndef __LVREV_PRIVATE_H__
#define __LVREV_PRIVATE_H__

#ifdef __cplusplus
extern "C" {
#endif


/****************************************************************************************/
/*                                                                                      */
/*  Includes                                                                            */
/*                                                                                      */
/****************************************************************************************/
#include "LVREV.h"
#include "LVREV_Tables.h"
#include "BIQUAD.h"
#include "Filter.h"
#include "VectorArithmetic.h"
#include "Mixer.h"
#include "LVM_Macros.h"


/****************************************************************************************/
/*                                                                                      */
/*  Defines                                                                             */
/*                                                                                      */
/****************************************************************************************/
/* General */
#define ONE_OVER_SQRT_TWO               23170           /* 1/sqrt(2) * 2^15 */
#define LVREV_B_8_on_1000            17179869           /* 0.8 * 2^31 */
#define LVREV_HEADROOM                   8192           /* -12dB * 2^15 */
#define LVREV_2_9_INQ29           1583769190L           /* 2.9 in Q29 format */
#define LVREV_MIN3DB                   0x5A82           /* -3dB in Q15 format */

/* Intenal constants */
#define LVREV_LP_Poly_Order                 4
#define LVREV_LP_Poly_Shift                 5
#define LVREV_T_3_Power_0_on_4          32768
#define LVREV_T_3_Power_1_on_4          43125
#define LVREV_T_3_Power_2_on_4          56755
#define LVREV_T_3_Power_3_on_4          74694
#define LVREV_T60_SCALE                306774           /*(32767/7000)<<16 */
#define LVREV_T_3_Power_minus0_on_4     32767           /* 3^(-0/4) * 2^15 */
#define LVREV_T_3_Power_minus1_on_4     24898           /* 3^(-1/4) * 2^15 */
#define LVREV_T_3_Power_minus2_on_4     18919           /* 3^(-2/4) * 2^15 */
#define LVREV_T_3_Power_minus3_on_4     14375           /* 3^(-3/4) * 2^15 */
#define LVREV_MAX_T3_DELAY               2527           /* ((48000 * 120 * LVREV_T_3_Power_minus3_on_4) >> 15) / 1000 */
#define LVREV_MAX_T2_DELAY               3326           /* ((48000 * 120 * LVREV_T_3_Power_minus2_on_4) >> 15) / 1000 */
#define LVREV_MAX_T1_DELAY               4377           /* ((48000 * 120 * LVREV_T_3_Power_minus1_on_4) >> 15) / 1000 */
#define LVREV_MAX_T0_DELAY               5760           /* ((48000 * 120 * LVREV_T_3_Power_minus0_on_4) >> 15) / 1000 */
#define LVREV_MAX_AP3_DELAY              1685           /* ((48000 * 120 * LVREV_T_3_Power_minus3_on_4) >> 15) / 1500 */
#define LVREV_MAX_AP2_DELAY              2218           /* ((48000 * 120 * LVREV_T_3_Power_minus2_on_4) >> 15) / 1500 */
#define LVREV_MAX_AP1_DELAY              2918           /* ((48000 * 120 * LVREV_T_3_Power_minus1_on_4) >> 15) / 1500 */
#define LVREV_MAX_AP0_DELAY              3840           /* ((48000 * 120 * LVREV_T_3_Power_minus0_on_4) >> 15) / 1500 */
#define LVREV_BYPASSMIXER_TC             1000           /* Bypass mixer time constant*/
#define LVREV_ALLPASS_TC                 1000           /* All-pass filter time constant */
#define LVREV_ALLPASS_TAP_TC             10000           /* All-pass filter dely tap change */
#define LVREV_FEEDBACKMIXER_TC            100           /* Feedback mixer time constant*/
#define LVREV_OUTPUTGAIN_SHIFT              5           /* Bits shift for output gain correction */

/* Parameter limits */
#define LVREV_NUM_FS                        9           /* Number of supported sample rates */
#define LVREV_MAXBLKSIZE_LIMIT             64           /* Maximum block size low limit */
#define LVREV_MAX_LEVEL                   100           /* Maximum level, 100% */
#define LVREV_MIN_LPF_CORNER               50           /* Low pass filter limits */
#define LVREV_MAX_LPF_CORNER            23999
#define LVREV_MIN_HPF_CORNER               20           /* High pass filrer limits */
#define LVREV_MAX_HPF_CORNER             1000
#define LVREV_MAX_T60                    7000           /* Maximum T60 time in ms */
#define LVREV_MAX_DENSITY                 100           /* Maximum density, 100% */
#define LVREV_MAX_DAMPING                 100           /* Maximum damping, 100% */
#define LVREV_MAX_ROOMSIZE                100           /* Maximum room size, 100% */



/****************************************************************************************/
/*                                                                                      */
/*  Structures                                                                          */
/*                                                                                      */
/****************************************************************************************/
/* Fast data structure */
typedef struct
{

    Biquad_1I_Order1_Taps_t HPTaps;                     /* High pass filter taps */
    Biquad_1I_Order1_Taps_t LPTaps;                     /* Low pass filter taps */
    Biquad_1I_Order1_Taps_t RevLPTaps[4];               /* Reverb low pass filters taps */

} LVREV_FastData_st;


/* Fast coefficient structure */
typedef struct
{

    Biquad_Instance_t       HPCoefs;                    /* High pass filter coefficients */
    Biquad_Instance_t       LPCoefs;                    /* Low pass filter coefficients */
    Biquad_Instance_t       RevLPCoefs[4];              /* Reverb low pass filters coefficients */

} LVREV_FastCoef_st;


/* Instance parameter structure */
typedef struct
{
    /* General */
    LVREV_InstanceParams_st InstanceParams;             /* Initialisation time instance parameters */
    LVREV_MemoryTable_st    MemoryTable;                /* Memory table */
    LVREV_ControlParams_st  CurrentParams;              /* Parameters being used */
    LVREV_ControlParams_st  NewParams;                  /* New parameters from the calling application */
    LVM_CHAR                bControlPending;            /* Flag to indicate new parameters are available */
    LVM_CHAR                bFirstControl;              /* Flag to indicate that the control function is called for the first time */
    LVM_CHAR                bDisableReverb;             /* Flag to indicate that the mix level is 0% and the reverb can be disabled */
    LVM_INT32               RoomSizeInms;               /* Room size in msec */
    LVM_INT32               MaxBlkLen;                  /* Maximum block size for internal processing */

    /* Aligned memory pointers */
    LVREV_FastData_st       *pFastData;                 /* Fast data memory base address */
    LVREV_FastCoef_st       *pFastCoef;                 /* Fast coefficient memory base address */
    LVM_INT32               *pScratchDelayLine[4];      /* Delay line scratch memory */
    LVM_INT32               *pScratch;                  /* Multi ussge scratch */
    LVM_INT32               *pInputSave;                /* Reverb block input save for dry/wet mixing*/

    /* Feedback matrix */
    Mix_1St_Cll_t           FeedbackMixer[4];           /* Mixer for Pop and Click Supression caused by feedback Gain */

    /* All-Pass Filter */
    LVM_INT32               T[4];                       /* Maximum delay size of buffer */
    LVM_INT32               *pDelay_T[4];               /* Pointer to delay buffers */
    LVM_INT32               Delay_AP[4];                /* Offset to AP delay buffer start */
    LVM_INT16               AB_Selection;               /* Smooth from tap A to B when 1 otherwise B to A */
    LVM_INT32               A_DelaySize[4];             /* A delay length in samples */
    LVM_INT32               B_DelaySize[4];             /* B delay length in samples */
    LVM_INT32               *pOffsetA[4];               /* Offset for the A delay tap */
    LVM_INT32               *pOffsetB[4];               /* Offset for the B delay tap */
    Mix_2St_Cll_t           Mixer_APTaps[4];            /* Smoothed AP delay mixer */
    Mix_1St_Cll_t           Mixer_SGFeedback[4];        /* Smoothed SAfeedback gain */
    Mix_1St_Cll_t           Mixer_SGFeedforward[4];     /* Smoothed AP feedforward gain */

    /* Output gain */
    Mix_2St_Cll_t           BypassMixer;                /* Dry/wet mixer */
    LVM_INT16               Gain;                       /* Gain applied to output to maintain average signal power */
    Mix_1St_Cll_t           GainMixer;                  /* Gain smoothing */

} LVREV_Instance_st;


/****************************************************************************************/
/*                                                                                      */
/*  Function prototypes                                                                 */
/*                                                                                      */
/****************************************************************************************/

LVREV_ReturnStatus_en   LVREV_ApplyNewSettings(LVREV_Instance_st     *pPrivate);

void                    ReverbBlock(LVM_INT32           *pInput,
                                    LVM_INT32           *pOutput,
                                    LVREV_Instance_st   *pPrivate,
                                    LVM_UINT16          NumSamples);

LVM_INT32               BypassMixer_Callback(void       *pCallbackData,
                                             void       *pGeneralPurpose,
                                             LVM_INT16  GeneralPurpose );


#ifdef __cplusplus
}
#endif

#endif  /** __LVREV_PRIVATE_H__ **/

/* End of file */
