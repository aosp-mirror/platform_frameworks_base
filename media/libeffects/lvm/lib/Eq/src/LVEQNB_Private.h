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

#ifndef __LVEQNB_PRIVATE_H__
#define __LVEQNB_PRIVATE_H__

#ifdef __cplusplus
extern "C" {
#endif /* __cplusplus */


/****************************************************************************************/
/*                                                                                      */
/*  Includes                                                                            */
/*                                                                                      */
/****************************************************************************************/

#include "LVEQNB.h"                                     /* Calling or Application layer definitions */
#include "BIQUAD.h"
#include "LVC_Mixer.h"

/****************************************************************************************/
/*                                                                                      */
/*  Defines                                                                             */
/*                                                                                      */
/****************************************************************************************/

/* General */
#define LVEQNB_INVALID              0xFFFF              /* Invalid init parameter */

/* Memory */
#define LVEQNB_INSTANCE_ALIGN       4                   /* 32-bit alignment for instance structures */
#define LVEQNB_DATA_ALIGN           4                   /* 32-bit alignment for structures */
#define LVEQNB_COEF_ALIGN           4                   /* 32-bit alignment for long words */
#define LVEQNB_SCRATCHBUFFERS       4                   /* Number of buffers required for inplace processing */
#define LVEQNB_SCRATCH_ALIGN        4                   /* 32-bit alignment for long data */

#define LVEQNB_BYPASS_MIXER_TC      100                 /* Bypass Mixer TC */

/****************************************************************************************/
/*                                                                                      */
/*  Types                                                                               */
/*                                                                                      */
/****************************************************************************************/

/* Filter biquad types */
typedef enum
{
    LVEQNB_SinglePrecision = 0,
    LVEQNB_DoublePrecision = 1,
    LVEQNB_OutOfRange      = 2,
    LVEQNB_BIQUADTYPE_MAX  = LVM_MAXINT_32
} LVEQNB_BiquadType_en;


/****************************************************************************************/
/*                                                                                      */
/*  Structures                                                                          */
/*                                                                                      */
/****************************************************************************************/



/* Instance structure */
typedef struct
{
    /* Public parameters */
    LVEQNB_MemTab_t                 MemoryTable;        /* Instance memory allocation table */
    LVEQNB_Params_t                 Params;             /* Instance parameters */
    LVEQNB_Capabilities_t           Capabilities;       /* Instance capabilities */

    /* Aligned memory pointers */
    LVM_INT16                      *pFastTemporary;        /* Fast temporary data base address */

    /* Process variables */
    Biquad_2I_Order2_Taps_t         *pEQNB_Taps;        /* Equaliser Taps */
    Biquad_Instance_t               *pEQNB_FilterState; /* State for each filter band */

    /* Filter definitions and call back */
    LVM_UINT16                      NBands;             /* Number of bands */
    LVEQNB_BandDef_t                *pBandDefinitions;  /* Filter band definitions */
    LVEQNB_BiquadType_en            *pBiquadType;       /* Filter biquad types */

    /* Bypass variable */
    LVMixer3_2St_st           BypassMixer;              /* Bypass mixer used in transitions */
    LVM_INT16               bInOperatingModeTransition; /* Operating mode transition flag */

} LVEQNB_Instance_t;


/****************************************************************************************/
/*                                                                                      */
/* Function prototypes                                                                  */
/*                                                                                      */
/****************************************************************************************/

void    LVEQNB_SetFilters(LVEQNB_Instance_t   *pInstance,
                          LVEQNB_Params_t     *pParams);

void    LVEQNB_SetCoefficients(LVEQNB_Instance_t    *pInstance);

void    LVEQNB_ClearFilterHistory(LVEQNB_Instance_t *pInstance);

LVEQNB_ReturnStatus_en LVEQNB_SinglePrecCoefs(LVM_UINT16        Fs,
                                              LVEQNB_BandDef_t  *pFilterDefinition,
                                              PK_C16_Coefs_t    *pCoefficients);

LVEQNB_ReturnStatus_en LVEQNB_DoublePrecCoefs(LVM_UINT16        Fs,
                                              LVEQNB_BandDef_t  *pFilterDefinition,
                                              PK_C32_Coefs_t    *pCoefficients);

LVM_INT32 LVEQNB_BypassMixerCallBack (void* hInstance, void *pGeneralPurpose, LVM_INT16 CallbackParam);

#ifdef __cplusplus
}
#endif /* __cplusplus */

#endif /* __LVEQNB_PRIVATE_H__ */

