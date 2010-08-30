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

#ifndef _LVPSA_H_
#define _LVPSA_H_


#include "LVM_Types.h"


#ifdef __cplusplus
extern "C" {
#endif /* __cplusplus */

/****************************************************************************************/
/*                                                                                      */
/*  CONSTANTS DEFINITIONS                                                               */
/*                                                                                      */
/****************************************************************************************/

/* Memory table*/
#define     LVPSA_NR_MEMORY_REGIONS                  4      /* Number of memory regions                                          */

/****************************************************************************************/
/*                                                                                      */
/*  TYPES DEFINITIONS                                                                   */
/*                                                                                      */
/****************************************************************************************/
/* Memory Types */
typedef enum
{
    LVPSA_PERSISTENT      = LVM_PERSISTENT,
    LVPSA_PERSISTENT_DATA = LVM_PERSISTENT_DATA,
    LVPSA_PERSISTENT_COEF = LVM_PERSISTENT_COEF,
    LVPSA_SCRATCH         = LVM_SCRATCH,
    LVPSA_MEMORY_DUMMY = LVM_MAXINT_32                      /* Force 32 bits enum, don't use it!                                 */
} LVPSA_MemoryTypes_en;

/* Level detection speed control parameters */
typedef enum
{
    LVPSA_SPEED_LOW,                                        /* Low speed level   detection                                       */
    LVPSA_SPEED_MEDIUM,                                     /* Medium speed level   detection                                    */
    LVPSA_SPEED_HIGH,                                       /* High speed level   detection                                      */
    LVPSA_SPEED_DUMMY = LVM_MAXINT_32                       /* Force 32 bits enum, don't use it!                                 */
} LVPSA_LevelDetectSpeed_en;

/* Filter control parameters */
typedef struct
{
    LVM_UINT16                 CenterFrequency;             /* Center frequency of the band-pass filter (in Hz)                  */
    LVM_UINT16                 QFactor;                     /* Quality factor of the filter             (in 1/100)               */
    LVM_INT16                  PostGain;                    /* Postgain to apply after the filtering    (in dB Q16.0)            */

} LVPSA_FilterParam_t;

/* LVPSA initialization parameters */
typedef struct
{
    LVM_UINT16                 SpectralDataBufferDuration;  /* Spectral data buffer duration in time (ms in Q16.0)               */
    LVM_UINT16                 MaxInputBlockSize;           /* Maximum expected input block size (in samples)                    */
    LVM_UINT16                 nBands;                      /* Number of bands of the SA                                         */
    LVPSA_FilterParam_t       *pFiltersParams;              /* Points to nBands filter param structures for filters settings     */

} LVPSA_InitParams_t, *pLVPSA_InitParams_t;

/* LVPSA control parameters */
typedef struct
{
    LVM_Fs_en                  Fs;                          /* Input sampling rate                                               */
    LVPSA_LevelDetectSpeed_en  LevelDetectionSpeed;         /* Level detection speed                                             */

} LVPSA_ControlParams_t, *pLVPSA_ControlParams_t;

/* Memory region definition */
typedef struct
{
    LVM_UINT32                 Size;                        /* Region size in bytes                                              */
    LVPSA_MemoryTypes_en       Type;                        /* Region type                                                       */
    void                       *pBaseAddress;               /* Pointer to the region base address                                */
} LVPSA_MemoryRegion_t;

/* Memory table containing the region definitions */
typedef struct
{
    LVPSA_MemoryRegion_t       Region[LVPSA_NR_MEMORY_REGIONS];/* One definition for each region                                 */
} LVPSA_MemTab_t;

/* Audio time type */
typedef LVM_INT32 LVPSA_Time;

/* Module instance Handle */
typedef void *pLVPSA_Handle_t;

/* LVPSA return codes */
typedef enum
{
    LVPSA_OK,                                               /* The function ran without any problem                              */
    LVPSA_ERROR_INVALIDPARAM,                               /* A parameter is incorrect                                          */
    LVPSA_ERROR_WRONGTIME,                                  /* An incorrect AudioTime is used                                    */
    LVPSA_ERROR_NULLADDRESS,                                /* A pointer has a NULL value                                        */
    LVPSA_RETURN_DUMMY = LVM_MAXINT_32                      /* Force 32 bits enum, don't use it!                                 */
} LVPSA_RETURN;



/*********************************************************************************************************************************
   FUNCTIONS PROTOTYPE
**********************************************************************************************************************************/
/*********************************************************************************************************************************/
/*                                                                                                                               */
/* FUNCTION:            LVPSA_Memory                                                                                         */
/*                                                                                                                               */
/* DESCRIPTION:                                                                                                                  */
/*  This function is used for memory allocation and free. It can be called in                                                    */
/*  two ways:                                                                                                                    */
/*                                                                                                                               */
/*      hInstance = NULL                Returns the memory requirements                                                          */
/*      hInstance = Instance handle     Returns the memory requirements and                                                      */
/*                                      allocated base addresses for the instance                                                */
/*                                                                                                                               */
/*  When this function is called for memory allocation (hInstance=NULL) the memory                                               */
/*  base address pointers are NULL on return.                                                                                    */
/*                                                                                                                               */
/*  When the function is called for free (hInstance = Instance Handle) the memory                                                */
/*  table returns the allocated memory and base addresses used during initialisation.                                            */
/*                                                                                                                               */
/* PARAMETERS:                                                                                                                   */
/*  hInstance           Instance Handle                                                                                          */
/*  pMemoryTable        Pointer to an empty memory definition table                                                              */
/*  pInitParams         Pointer to the instance init parameters                                                                  */
/*                                                                                                                               */
/* RETURNS:                                                                                                                      */
/*  LVPSA_OK            Succeeds                                                                                                 */
/*  otherwise           Error due to bad parameters                                                                              */
/*                                                                                                                               */
/*********************************************************************************************************************************/
LVPSA_RETURN LVPSA_Memory            ( pLVPSA_Handle_t             hInstance,
                                       LVPSA_MemTab_t             *pMemoryTable,
                                       LVPSA_InitParams_t         *pInitParams    );

/*********************************************************************************************************************************/
/*                                                                                                                               */
/* FUNCTION:            LVPSA_Init                                                                                               */
/*                                                                                                                               */
/* DESCRIPTION:                                                                                                                  */
/*  Initializes the LVPSA module.                                                                                                */
/*                                                                                                                               */
/*                                                                                                                               */
/* PARAMETERS:                                                                                                                   */
/*  phInstance          Pointer to the instance Handle                                                                           */
/*  pInitParams         Pointer to the instance init parameters                                                                  */
/*  pControlParams      Pointer to the instance control parameters                                                               */
/*  pMemoryTable        Pointer to the memory definition table                                                                   */
/*                                                                                                                               */
/*                                                                                                                               */
/* RETURNS:                                                                                                                      */
/*  LVPSA_OK            Succeeds                                                                                                 */
/*  otherwise           Error due to bad parameters                                                                              */
/*                                                                                                                               */
/*********************************************************************************************************************************/
LVPSA_RETURN LVPSA_Init              ( pLVPSA_Handle_t             *phInstance,
                                       LVPSA_InitParams_t          *pInitParams,
                                       LVPSA_ControlParams_t       *pControlParams,
                                       LVPSA_MemTab_t              *pMemoryTable  );

/*********************************************************************************************************************************/
/*                                                                                                                               */
/* FUNCTION:            LVPSA_Control                                                                                            */
/*                                                                                                                               */
/* DESCRIPTION:                                                                                                                  */
/*  Controls the LVPSA module.                                                                                                   */
/*                                                                                                                               */
/* PARAMETERS:                                                                                                                   */
/*  hInstance           Instance Handle                                                                                          */
/*  pNewParams          Pointer to the instance new control parameters                                                           */
/*                                                                                                                               */
/* RETURNS:                                                                                                                      */
/*  LVPSA_OK            Succeeds                                                                                                 */
/*  otherwise           Error due to bad parameters                                                                              */
/*                                                                                                                               */
/*********************************************************************************************************************************/
LVPSA_RETURN LVPSA_Control           ( pLVPSA_Handle_t             hInstance,
                                       LVPSA_ControlParams_t      *pNewParams     );

/*********************************************************************************************************************************/
/*                                                                                                                               */
/* FUNCTION:            LVPSA_Process                                                                                            */
/*                                                                                                                               */
/* DESCRIPTION:                                                                                                                  */
/*  The process calculates the levels of the frequency bands.                                                                    */
/*                                                                                                                               */
/* PARAMETERS:                                                                                                                   */
/*  hInstance           Instance Handle                                                                                          */
/*  pLVPSA_InputSamples Pointer to the input samples buffer                                                                      */
/*  InputBlockSize      Number of mono samples to process                                                                        */
/*  AudioTime           Playback time of the first input sample                                                                  */
/*                                                                                                                               */
/*                                                                                                                               */
/* RETURNS:                                                                                                                      */
/*  LVPSA_OK            Succeeds                                                                                                 */
/*  otherwise           Error due to bad parameters                                                                              */
/*                                                                                                                               */
/*********************************************************************************************************************************/
LVPSA_RETURN LVPSA_Process           ( pLVPSA_Handle_t      hInstance,
                                       LVM_INT16           *pLVPSA_InputSamples,
                                       LVM_UINT16           InputBlockSize,
                                       LVPSA_Time           AudioTime             );

/*********************************************************************************************************************************/
/*                                                                                                                               */
/* FUNCTION:            LVPSA_GetSpectrum                                                                                        */
/*                                                                                                                               */
/* DESCRIPTION:                                                                                                                  */
/*  This function is used for memory allocation and free.                                                                        */
/*                                                                                                                               */
/*                                                                                                                               */
/* PARAMETERS:                                                                                                                   */
/*  hInstance            Instance Handle                                                                                         */
/*  GetSpectrumAudioTime Time to retrieve the values at                                                                          */
/*  pCurrentValues       Pointer to an empty buffer : Current level values output                                                */
/*  pPeakValues          Pointer to an empty buffer : Peak level values output                                                   */
/*                                                                                                                               */
/*                                                                                                                               */
/* RETURNS:                                                                                                                      */
/*  LVPSA_OK            Succeeds                                                                                                 */
/*  otherwise           Error due to bad parameters                                                                              */
/*                                                                                                                               */
/*********************************************************************************************************************************/
LVPSA_RETURN LVPSA_GetSpectrum       ( pLVPSA_Handle_t      hInstance,
                                       LVPSA_Time           GetSpectrumAudioTime,
                                       LVM_UINT8           *pCurrentValues,
                                       LVM_UINT8           *pPeakValues           );

/*********************************************************************************************************************************/
/*                                                                                                                               */
/* FUNCTION:            LVPSA_GetControlParams                                                                                   */
/*                                                                                                                               */
/* DESCRIPTION:                                                                                                                  */
/*  Get the current control parameters of the LVPSA module.                                                                      */
/*                                                                                                                               */
/* PARAMETERS:                                                                                                                   */
/*  hInstance           Instance Handle                                                                                          */
/*  pParams             Pointer to an empty control parameters structure                                                         */
/* RETURNS:                                                                                                                      */
/*  LVPSA_OK            Succeeds                                                                                                 */
/*  otherwise           Error due to bad parameters                                                                              */
/*                                                                                                                               */
/*********************************************************************************************************************************/
LVPSA_RETURN LVPSA_GetControlParams  (    pLVPSA_Handle_t            hInstance,
                                          LVPSA_ControlParams_t     *pParams      );

/*********************************************************************************************************************************/
/*                                                                                                                               */
/* FUNCTION:            LVPSA_GetInitParams                                                                                      */
/*                                                                                                                               */
/* DESCRIPTION:                                                                                                                  */
/*  Get the initialization parameters of the LVPSA module.                                                                       */
/*                                                                                                                               */
/* PARAMETERS:                                                                                                                   */
/*  hInstance           Instance Handle                                                                                          */
/*  pParams             Pointer to an empty init parameters structure                                                            */
/* RETURNS:                                                                                                                      */
/*  LVPSA_OK            Succeeds                                                                                                 */
/*  otherwise           Error due to bad parameters                                                                              */
/*                                                                                                                               */
/*********************************************************************************************************************************/
LVPSA_RETURN LVPSA_GetInitParams     (    pLVPSA_Handle_t            hInstance,
                                          LVPSA_InitParams_t        *pParams      );


#ifdef __cplusplus
}
#endif /* __cplusplus */

#endif /* _LVPSA_H */
