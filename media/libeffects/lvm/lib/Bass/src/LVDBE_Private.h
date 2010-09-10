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

/****************************************************************************************/
/*                                                                                      */
/*    Header file for the private layer interface of Dynamic Bass Enhancement module    */
/*                                                                                      */
/*  This files includes all definitions, types, structures and function                 */
/*  prototypes required by the execution layer.                                         */
/*                                                                                      */
/****************************************************************************************/

#ifndef __LVDBE_PRIVATE_H__
#define __LVDBE_PRIVATE_H__

#ifdef __cplusplus
extern "C" {
#endif /* __cplusplus */


/****************************************************************************************/
/*                                                                                      */
/*    Includes                                                                          */
/*                                                                                      */
/****************************************************************************************/

#include "LVDBE.h"                                /* Calling or Application layer definitions */
#include "BIQUAD.h"
#include "LVC_Mixer.h"
#include "AGC.h"


/****************************************************************************************/
/*                                                                                      */
/*    Defines                                                                           */
/*                                                                                      */
/****************************************************************************************/

/* General */
#define    LVDBE_INVALID            0xFFFF        /* Invalid init parameter */

/* Memory */
#define LVDBE_MEMREGION_INSTANCE         0       /* Offset to the instance memory region */
#define LVDBE_MEMREGION_PERSISTENT_DATA  1       /* Offset to persistent data memory region */
#define LVDBE_MEMREGION_PERSISTENT_COEF  2       /* Offset to persistent coefficient region */
#define LVDBE_MEMREGION_SCRATCH          3       /* Offset to data scratch memory region */

#define LVDBE_INSTANCE_ALIGN             4       /* 32-bit alignment for structures */
#define LVDBE_PERSISTENT_DATA_ALIGN      4       /* 32-bit alignment for data */
#define LVDBE_PERSISTENT_COEF_ALIGN      4       /* 32-bit alignment for coef */
#define LVDBE_SCRATCH_ALIGN              4       /* 32-bit alignment for long data */

#define LVDBE_SCRATCHBUFFERS_INPLACE     6       /* Number of buffers required for inplace processing */

#define LVDBE_MIXER_TC                   5       /* Mixer time  */
#define LVDBE_BYPASS_MIXER_TC            100     /* Bypass mixer time */


/****************************************************************************************/
/*                                                                                      */
/*    Structures                                                                        */
/*                                                                                      */
/****************************************************************************************/

/* Data structure */
typedef struct
{
    /* AGC parameters */
    AGC_MIX_VOL_2St1Mon_D32_t   AGCInstance;        /* AGC instance parameters */

    /* Process variables */
    Biquad_2I_Order2_Taps_t     HPFTaps;            /* High pass filter taps */
    Biquad_1I_Order2_Taps_t     BPFTaps;            /* Band pass filter taps */
    LVMixer3_1St_st             BypassVolume;       /* Bypass volume scaler */
    LVMixer3_2St_st             BypassMixer;        /* Bypass Mixer for Click Removal */

} LVDBE_Data_t;

/* Coefs structure */
typedef struct
{
    /* Process variables */
    Biquad_Instance_t           HPFInstance;        /* High pass filter instance */
    Biquad_Instance_t           BPFInstance;        /* Band pass filter instance */

} LVDBE_Coef_t;

/* Instance structure */
typedef struct
{
    /* Public parameters */
    LVDBE_MemTab_t                MemoryTable;        /* Instance memory allocation table */
    LVDBE_Params_t                Params;             /* Instance parameters */
    LVDBE_Capabilities_t        Capabilities;         /* Instance capabilities */

    /* Data and coefficient pointers */
    LVDBE_Data_t                *pData;                /* Instance data */
    LVDBE_Coef_t                *pCoef;                /* Instance coefficients */
} LVDBE_Instance_t;


/****************************************************************************************/
/*                                                                                      */
/* Function prototypes                                                                  */
/*                                                                                      */
/****************************************************************************************/

void    LVDBE_SetAGC(LVDBE_Instance_t       *pInstance,
                     LVDBE_Params_t         *pParams);


void    LVDBE_SetVolume(LVDBE_Instance_t    *pInstance,
                        LVDBE_Params_t      *pParams);


void    LVDBE_SetFilters(LVDBE_Instance_t   *pInstance,
                         LVDBE_Params_t     *pParams);


#ifdef __cplusplus
}
#endif /* __cplusplus */

#endif      /* __LVDBE_PRIVATE_H__ */


