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
/*  Header file for the private layer interface of concert sound bundle             */
/*                                                                                  */
/*  This files includes all definitions, types, structures and function             */
/*  prototypes required by the execution layer.                                     */
/*                                                                                  */
/************************************************************************************/

#ifndef __LVM_PRIVATE_H__
#define __LVM_PRIVATE_H__

#ifdef __cplusplus
extern "C" {
#endif /* __cplusplus */


/************************************************************************************/
/*                                                                                  */
/*  Includes                                                                        */
/*                                                                                  */
/************************************************************************************/

#include "LVM.h"                                /* LifeVibes */
#include "LVM_Common.h"                         /* LifeVibes common */
#include "BIQUAD.h"                             /* Biquad library */
#include "LVC_Mixer.h"                          /* Mixer library */
#include "LVCS_Private.h"                       /* Concert Sound */
#include "LVDBE_Private.h"                      /* Dynamic Bass Enhancement */
#include "LVEQNB_Private.h"                     /* N-Band equaliser */
#include "LVPSA_Private.h"                      /* Parametric Spectrum Analyzer */


/************************************************************************************/
/*                                                                                  */
/*  Defines                                                                         */
/*                                                                                  */
/************************************************************************************/

/* General */
#define LVM_INVALID                     0xFFFF    /* Invalid init parameter */

/* Memory */
#define LVM_INSTANCE_ALIGN              4         /* 32-bit for structures */
#define LVM_FIRSTCALL                   0         /* First call to the buffer */
#define LVM_MAXBLOCKCALL                1         /* Maximum block size calls to the buffer */
#define LVM_LASTCALL                    2         /* Last call to the buffer */
#define LVM_FIRSTLASTCALL               3         /* Single call for small number of samples */

/* Block Size */
#define LVM_MIN_MAXBLOCKSIZE            16        /* Minimum MaxBlockSize Limit*/
#define LVM_MANAGED_MAX_MAXBLOCKSIZE    8191      /* Maximum MaxBlockSzie Limit for Managed Buffer Mode*/
#define LVM_UNMANAGED_MAX_MAXBLOCKSIZE  4096      /* Maximum MaxBlockSzie Limit for Unmanaged Buffer Mode */

#define MAX_INTERNAL_BLOCKSIZE          8128      /* Maximum multiple of 64  below 8191*/

#define MIN_INTERNAL_BLOCKSIZE          16        /* Minimum internal block size */
#define MIN_INTERNAL_BLOCKSHIFT         4         /* Minimum internal block size as a power of 2 */
#define MIN_INTERNAL_BLOCKMASK          0xFFF0    /* Minimum internal block size mask */

#define LVM_PSA_DYNAMICRANGE            60        /* Spectral Dynamic range: used for offseting output*/
#define LVM_PSA_BARHEIGHT               127       /* Spectral Bar Height*/

#define LVM_TE_MIN_EFFECTLEVEL          0         /*TE Minimum EffectLevel*/
#define LVM_TE_MAX_EFFECTLEVEL          15        /*TE Maximum Effect level*/

#define LVM_VC_MIN_EFFECTLEVEL          -96       /*VC Minimum EffectLevel*/
#define LVM_VC_MAX_EFFECTLEVEL          0         /*VC Maximum Effect level*/

#define LVM_BE_MIN_EFFECTLEVEL          0         /*BE Minimum EffectLevel*/
#define LVM_BE_MAX_EFFECTLEVEL          15        /*BE Maximum Effect level*/

#define LVM_EQNB_MIN_BAND_FREQ          20        /*EQNB Minimum Band Frequency*/
#define LVM_EQNB_MAX_BAND_FREQ          24000     /*EQNB Maximum Band Frequency*/
#define LVM_EQNB_MIN_BAND_GAIN          -15       /*EQNB Minimum Band Frequency*/
#define LVM_EQNB_MAX_BAND_GAIN          15        /*EQNB Maximum Band Frequency*/
#define LVM_EQNB_MIN_QFACTOR            25        /*EQNB Minimum Q Factor*/
#define LVM_EQNB_MAX_QFACTOR            1200      /*EQNB Maximum Q Factor*/
#define LVM_EQNB_MIN_LPF_FREQ           1000      /*EQNB Minimum Low Pass Corner frequency*/
#define LVM_EQNB_MIN_HPF_FREQ           20        /*EQNB Minimum High Pass Corner frequency*/
#define LVM_EQNB_MAX_HPF_FREQ           1000      /*EQNB Maximum High Pass Corner frequency*/

#define LVM_CS_MIN_EFFECT_LEVEL         0         /*CS Minimum Effect Level*/
#define LVM_CS_MAX_REVERB_LEVEL         100       /*CS Maximum Reverb Level*/
#define LVM_VIRTUALIZER_MAX_REVERB_LEVEL 100      /*Vitrualizer Maximum Reverb Level*/

#define LVM_VC_MIXER_TIME              100       /*VC mixer time*/
#define LVM_VC_BALANCE_MAX             96        /*VC balance max value*/
#define LVM_VC_BALANCE_MIN             -96       /*VC balance min value*/

/* Algorithm masks */
#define LVM_CS_MASK                     1
#define LVM_EQNB_MASK                   2
#define LVM_DBE_MASK                    4
#define LVM_VC_MASK                     16
#define LVM_TE_MASK                     32
#define LVM_PSA_MASK                    2048


/************************************************************************************/
/*                                                                                  */
/*  Structures                                                                      */
/*                                                                                  */
/************************************************************************************/

/* Memory region definition */
typedef struct
{
    LVM_UINT32              Size;               /* Region size in bytes */
    LVM_UINT16              Alignment;          /* Byte alignment */
    LVM_MemoryTypes_en      Type;               /* Region type */
    void                    *pBaseAddress;      /* Pointer to the region base address */
} LVM_IntMemoryRegion_t;


/* Memory table containing the region definitions */
typedef struct
{
    LVM_IntMemoryRegion_t   Region[LVM_NR_MEMORY_REGIONS];  /* One definition for each region */
} LVM_IntMemTab_t;


/* Buffer Management */
typedef struct
{
    LVM_INT16               *pScratch;          /* Bundle scratch buffer */

    LVM_INT16               BufferState;        /* Buffer status */
    LVM_INT16               InDelayBuffer[6*MIN_INTERNAL_BLOCKSIZE]; /* Input buffer delay line, left and right */
    LVM_INT16               InDelaySamples;     /* Number of samples in the input delay buffer */

    LVM_INT16               OutDelayBuffer[2*MIN_INTERNAL_BLOCKSIZE]; /* Output buffer delay line */
    LVM_INT16               OutDelaySamples;    /* Number of samples in the output delay buffer, left and right */
    LVM_INT16               SamplesToOutput;    /* Samples to write to the output */
} LVM_Buffer_t;


/* Filter taps */
typedef struct
{
    Biquad_2I_Order1_Taps_t TrebleBoost_Taps;   /* Treble boost Taps */
} LVM_TE_Data_t;


/* Coefficients */
typedef struct
{
    Biquad_Instance_t       TrebleBoost_State;  /* State for the treble boost filter */
} LVM_TE_Coefs_t;


typedef struct
{
    /* Public parameters */
    LVM_MemTab_t            MemoryTable;        /* Instance memory allocation table */
    LVM_ControlParams_t     Params;             /* Control parameters */
    LVM_InstParams_t        InstParams;         /* Instance parameters */

    /* Private parameters */
    LVM_UINT16              ControlPending;     /* Control flag to indicate update pending */
    LVM_ControlParams_t     NewParams;          /* New control parameters pending update */

    /* Buffer control */
    LVM_INT16               InternalBlockSize;  /* Maximum internal block size */
    LVM_Buffer_t            *pBufferManagement; /* Buffer management variables */
    LVM_INT16               SamplesToProcess;   /* Input samples left to process */
    LVM_INT16               *pInputSamples;     /* External input sample pointer */
    LVM_INT16               *pOutputSamples;    /* External output sample pointer */

    /* Configuration number */
    LVM_INT32               ConfigurationNumber;
    LVM_INT32               BlickSizeMultiple;

    /* DC removal */
    Biquad_Instance_t       DC_RemovalInstance; /* DC removal filter instance */

    /* Concert Sound */
    LVCS_Handle_t           hCSInstance;        /* Concert Sound instance handle */
    LVCS_Instance_t         CS_Instance;        /* Concert Sound instance */
    LVM_INT16               CS_Active;          /* Control flag */

    /* Equalizer */
    LVEQNB_Handle_t         hEQNBInstance;      /* N-Band Equaliser instance handle */
    LVEQNB_Instance_t       EQNB_Instance;      /* N-Band Equaliser instance */
    LVM_EQNB_BandDef_t      *pEQNB_BandDefs;    /* Local storage for new definitions */
    LVM_EQNB_BandDef_t      *pEQNB_UserDefs;    /* Local storage for the user's definitions */
    LVM_INT16               EQNB_Active;        /* Control flag */

    /* Dynamic Bass Enhancement */
    LVDBE_Handle_t          hDBEInstance;       /* Dynamic Bass Enhancement instance handle */
    LVDBE_Instance_t        DBE_Instance;       /* Dynamic Bass Enhancement instance */
    LVM_INT16               DBE_Active;         /* Control flag */

    /* Volume Control */
    LVMixer3_1St_st         VC_Volume;          /* Volume scaler */
    LVMixer3_2St_st         VC_BalanceMix;      /* VC balance mixer */
    LVM_INT16               VC_VolumedB;        /* Gain in dB */
    LVM_INT16               VC_Active;          /* Control flag */
    LVM_INT16               VC_AVLFixedVolume;  /* AVL fixed volume */

    /* Treble Enhancement */
    LVM_TE_Data_t           *pTE_Taps;          /* Treble boost Taps */
    LVM_TE_Coefs_t          *pTE_State;         /* State for the treble boost filter */
    LVM_INT16               TE_Active;          /* Control flag */

    /* Headroom */
    LVM_HeadroomParams_t    NewHeadroomParams;   /* New headroom parameters pending update */
    LVM_HeadroomParams_t    HeadroomParams;      /* Headroom parameters */
    LVM_HeadroomBandDef_t   *pHeadroom_BandDefs; /* Local storage for new definitions */
    LVM_HeadroomBandDef_t   *pHeadroom_UserDefs; /* Local storage for the user's definitions */
    LVM_UINT16              Headroom;            /* Value of the current headroom */

    /* Spectrum Analyzer */
    pLVPSA_Handle_t         hPSAInstance;       /* Spectrum Analyzer instance handle */
    LVPSA_InstancePr_t      PSA_Instance;       /* Spectrum Analyzer instance */
    LVPSA_InitParams_t      PSA_InitParams;     /* Spectrum Analyzer initialization parameters */
    LVPSA_ControlParams_t   PSA_ControlParams;  /* Spectrum Analyzer control parameters */
    LVM_INT16               PSA_GainOffset;     /* Tone control flag */
    LVM_Callback            CallBack;
    LVM_INT16               *pPSAInput;         /* PSA input pointer */

    LVM_INT16              NoSmoothVolume;      /* Enable or disable smooth volume changes*/

} LVM_Instance_t;


/************************************************************************************/
/*                                                                                  */
/*  Function Prototypes                                                             */
/*                                                                                  */
/************************************************************************************/

LVM_ReturnStatus_en LVM_ApplyNewSettings(LVM_Handle_t       hInstance);

void    LVM_SetTrebleBoost( LVM_Instance_t         *pInstance,
                            LVM_ControlParams_t    *pParams);

void    LVM_SetVolume(  LVM_Instance_t         *pInstance,
                        LVM_ControlParams_t    *pParams);

LVM_INT32    LVM_VCCallBack(void*   pBundleHandle,
                            void*   pGeneralPurpose,
                            short   CallBackParam);

void    LVM_SetHeadroom(    LVM_Instance_t         *pInstance,
                            LVM_ControlParams_t    *pParams);

void    LVM_BufferIn(   LVM_Handle_t      hInstance,
                        const LVM_INT16   *pInData,
                        LVM_INT16         **pToProcess,
                        LVM_INT16         **pProcessed,
                        LVM_UINT16        *pNumSamples);

void    LVM_BufferOut(  LVM_Handle_t     hInstance,
                        LVM_INT16        *pOutData,
                        LVM_UINT16       *pNumSamples);

LVM_INT32 LVM_AlgoCallBack(     void          *pBundleHandle,
                                void          *pData,
                                LVM_INT16     callbackId);

#ifdef __cplusplus
}
#endif /* __cplusplus */

#endif      /* __LVM_PRIVATE_H__ */

