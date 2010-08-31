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
/*  Header file for the private layer interface of concert sound.                   */
/*                                                                                  */
/*  This files includes all definitions, types, structures and function             */
/*  prototypes required by the execution layer.                                     */
/*                                                                                  */
/************************************************************************************/

#ifndef __LVCS_PRIVATE_H__
#define __LVCS_PRIVATE_H__

#ifdef __cplusplus
extern "C" {
#endif /* __cplusplus */


/************************************************************************************/
/*                                                                                  */
/*  Includes                                                                        */
/*                                                                                  */
/************************************************************************************/

#include "LVCS.h"                               /* Calling or Application layer definitions */
#include "LVCS_StereoEnhancer.h"                /* Stereo enhancer module definitions */
#include "LVCS_ReverbGenerator.h"               /* Reverberation module definitions */
#include "LVCS_Equaliser.h"                     /* Equaliser module definitions */
#include "LVCS_BypassMix.h"                     /* Bypass Mixer module definitions */
#include "LVM_Timer.h"


/************************************************************************************/
/*                                                                                  */
/*  Defines                                                                         */
/*                                                                                  */
/************************************************************************************/

/* Configuration switch controls */
#define LVCS_STEREOENHANCESWITCH    0x0001      /* Stereo enhancement enable control */
#define LVCS_REVERBSWITCH           0x0002      /* Reverberation enable control */
#define LVCS_EQUALISERSWITCH        0x0004      /* Equaliser enable control */
#define LVCS_BYPASSMIXSWITCH        0x0008      /* Bypass mixer enable control */
#define LVCS_COMPGAINFRAME          64          /* Compressor gain update interval */

/* Memory */
#define LVCS_SCRATCHBUFFERS              6      /* Number of buffers required for inplace processing */

/* General */
#define LVCS_INVALID                0xFFFF      /* Invalid init parameter */
#define LVCS_BYPASS_MIXER_TC        100         /* Bypass mixer time */

/* Access to external coefficients table */
#define LVCS_NR_OF_FS                    9
#define LVCS_NR_OF_CHAN_CFG              2


/************************************************************************************/
/*                                                                                  */
/*  Types                                                                           */
/*                                                                                  */
/************************************************************************************/

typedef LVM_UINT16  LVCS_Configuration_t;       /* Internal algorithm configuration */

typedef enum
{
    LVCS_HEADPHONE  = 0,
    LVCS_DEVICE_MAX = LVM_MAXENUM
} LVCS_OutputDevice_en;


/************************************************************************************/
/*                                                                                  */
/*  Structures                                                                      */
/*                                                                                  */
/************************************************************************************/

/* Volume correction structure */
typedef struct
{
    LVM_INT16   CompFull;                       /* Post CS compression 100% effect */
    LVM_INT16   CompMin;                        /* Post CS compression 0% effect */
    LVM_INT16   GainFull;                       /* CS gain correct 100% effect */
    LVM_INT16   GainMin;                        /* CS gain correct 0% effect */
} LVCS_VolCorrect_t;

/* Instance structure */
typedef struct
{
    /* Public parameters */
    LVCS_MemTab_t           MemoryTable;        /* Instance memory allocation table */
    LVCS_Params_t           Params;             /* Instance parameters */
    LVCS_Capabilities_t     Capabilities;       /* Initialisation capabilities */

    /* Private parameters */
    LVCS_OutputDevice_en    OutputDevice;       /* Selected output device type */
    LVCS_VolCorrect_t       VolCorrect;         /* Volume correction settings */
    LVM_INT16               TransitionGain;     /* Transition gain */
    LVM_INT16               CompressGain;       /* Last used compressor gain*/

    /* Sub-block configurations */
    LVCS_StereoEnhancer_t   StereoEnhancer;     /* Stereo enhancer configuration */
    LVCS_ReverbGenerator_t  Reverberation;      /* Reverberation configuration */
    LVCS_Equaliser_t        Equaliser;          /* Equaliser configuration */
    LVCS_BypassMix_t        BypassMix;          /* Bypass mixer configuration */

    /* Bypass variable */
    LVM_INT16               MSTarget0;                          /* Mixer state control variable for smooth transtion */
    LVM_INT16               MSTarget1;                          /* Mixer state control variable for smooth transtion */
    LVM_INT16               bInOperatingModeTransition;         /* Operating mode transition flag */
    LVM_INT16               bTimerDone;                         /* Timer completion flag */
    LVM_Timer_Params_t      TimerParams;                        /* Timer parameters */
    LVM_Timer_Instance_t    TimerInstance;                      /* Timer instance */

} LVCS_Instance_t;

/* Coefficient Structure */
typedef struct
{
    Biquad_Instance_t       EqualiserBiquadInstance;
    Biquad_Instance_t       ReverbBiquadInstance;
    Biquad_Instance_t       SEBiquadInstanceMid;
    Biquad_Instance_t       SEBiquadInstanceSide;

} LVCS_Coefficient_t;

/* Data Structure */
typedef struct
{
    Biquad_2I_Order2_Taps_t EqualiserBiquadTaps;
    Biquad_2I_Order2_Taps_t ReverbBiquadTaps;
    Biquad_1I_Order1_Taps_t SEBiquadTapsMid;
    Biquad_1I_Order2_Taps_t SEBiquadTapsSide;

} LVCS_Data_t;


void LVCS_TimerCallBack (   void* hInstance,
                            void* pCallBackParams,
                            LVM_INT32 CallbackParam);


#ifdef __cplusplus
}
#endif /* __cplusplus */

#endif      /* PRIVATE_H */


