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

#include "LVCS.h"
#include "LVCS_Private.h"
#include "LVCS_Tables.h"

/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVCS_Memory                                                 */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  This function is used for memory allocation and free. It can be called in           */
/*  two ways:                                                                           */
/*                                                                                      */
/*      hInstance = NULL                Returns the memory requirements                 */
/*      hInstance = Instance handle     Returns the memory requirements and             */
/*                                      allocated base addresses for the instance       */
/*                                                                                      */
/*  When this function is called for memory allocation (hInstance=NULL) it is           */
/*  passed the default capabilities.                                                    */
/*                                                                                      */
/*  When called for memory allocation the memory base address pointers are NULL on      */
/*  return.                                                                             */
/*                                                                                      */
/*  When the function is called for free (hInstance = Instance Handle) the              */
/*  capabilities are ignored and the memory table returns the allocated memory and      */
/*  base addresses used during initialisation.                                          */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance               Instance Handle                                             */
/*  pMemoryTable            Pointer to an empty memory definition table                 */
/*  pCapabilities           Pointer to the default capabilites                          */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVCS_Success            Succeeded                                                   */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1.  This function may be interrupted by the LVCS_Process function                   */
/*                                                                                      */
/****************************************************************************************/

LVCS_ReturnStatus_en LVCS_Memory(LVCS_Handle_t          hInstance,
                                 LVCS_MemTab_t          *pMemoryTable,
                                 LVCS_Capabilities_t    *pCapabilities)
{

    LVM_UINT32          ScratchSize;
    LVCS_Instance_t     *pInstance = (LVCS_Instance_t *)hInstance;


    /*
     * Fill in the memory table
     */
    if (hInstance == LVM_NULL)
    {
        /*
         * Instance memory
         */
        pMemoryTable->Region[LVCS_MEMREGION_PERSISTENT_SLOW_DATA].Size         = (LVM_UINT32)sizeof(LVCS_Instance_t);
        pMemoryTable->Region[LVCS_MEMREGION_PERSISTENT_SLOW_DATA].Type         = LVCS_PERSISTENT;
        pMemoryTable->Region[LVCS_MEMREGION_PERSISTENT_SLOW_DATA].pBaseAddress = LVM_NULL;

        /*
         * Data memory
         */
        pMemoryTable->Region[LVCS_MEMREGION_PERSISTENT_FAST_DATA].Size         = (LVM_UINT32)sizeof(LVCS_Data_t);
        pMemoryTable->Region[LVCS_MEMREGION_PERSISTENT_FAST_DATA].Type         = LVCS_DATA;
        pMemoryTable->Region[LVCS_MEMREGION_PERSISTENT_FAST_DATA].pBaseAddress = LVM_NULL;

        /*
         * Coefficient memory
         */
        pMemoryTable->Region[LVCS_MEMREGION_PERSISTENT_FAST_COEF].Size         = (LVM_UINT32)sizeof(LVCS_Coefficient_t);
        pMemoryTable->Region[LVCS_MEMREGION_PERSISTENT_FAST_COEF].Type         = LVCS_COEFFICIENT;
        pMemoryTable->Region[LVCS_MEMREGION_PERSISTENT_FAST_COEF].pBaseAddress = LVM_NULL;

        /*
         * Scratch memory
         */
        ScratchSize = (LVM_UINT32)(LVCS_SCRATCHBUFFERS*sizeof(LVM_INT16)*pCapabilities->MaxBlockSize);     /* Inplace processing */
        pMemoryTable->Region[LVCS_MEMREGION_TEMPORARY_FAST].Size         = ScratchSize;
        pMemoryTable->Region[LVCS_MEMREGION_TEMPORARY_FAST].Type         = LVCS_SCRATCH;
        pMemoryTable->Region[LVCS_MEMREGION_TEMPORARY_FAST].pBaseAddress = LVM_NULL;
    }
    else
    {
        /* Read back memory allocation table */
        *pMemoryTable = pInstance->MemoryTable;
    }

    return(LVCS_SUCCESS);
}


/************************************************************************************/
/*                                                                                  */
/* FUNCTION:                LVCS_Init                                               */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  Create and initialisation function for the Concert Sound module                 */
/*                                                                                  */
/*  This function can be used to create an algorithm instance by calling with       */
/*  hInstance set to LVM_NULL. In this case the algorithm returns the new instance  */
/*  handle.                                                                         */
/*                                                                                  */
/*  This function can be used to force a full re-initialisation of the algorithm    */
/*  by calling with hInstance = Instance Handle. In this case the memory table      */
/*  should be correct for the instance, this can be ensured by calling the function */
/*  LVCS_Memory before calling this function.                                       */
/*                                                                                  */
/* PARAMETERS:                                                                      */
/*  hInstance               Instance handle                                         */
/*  pMemoryTable            Pointer to the memory definition table                  */
/*  pCapabilities           Pointer to the capabilities structure                   */
/*                                                                                  */
/* RETURNS:                                                                         */
/*  LVCS_Success            Initialisation succeeded                                */
/*                                                                                  */
/* NOTES:                                                                           */
/*  1.  The instance handle is the pointer to the base address of the first memory  */
/*      region.                                                                     */
/*  2.  This function must not be interrupted by the LVCS_Process function          */
/*  3.  This function must be called with the same capabilities as used for the     */
/*      call to the memory function                                                 */
/*                                                                                  */
/************************************************************************************/

LVCS_ReturnStatus_en LVCS_Init(LVCS_Handle_t         *phInstance,
                               LVCS_MemTab_t         *pMemoryTable,
                               LVCS_Capabilities_t   *pCapabilities)
{

    LVCS_Instance_t                 *pInstance;
    LVCS_VolCorrect_t               *pLVCS_VolCorrectTable;


    /*
     * Set the instance handle if not already initialised
     */
    if (*phInstance == LVM_NULL)
    {
        *phInstance = (LVCS_Handle_t)pMemoryTable->Region[LVCS_MEMREGION_PERSISTENT_SLOW_DATA].pBaseAddress;
    }
    pInstance =(LVCS_Instance_t  *)*phInstance;


    /*
     * Save the capabilities in the instance structure
     */
    pInstance->Capabilities = *pCapabilities;

    /*
     * Save the memory table in the instance structure
     */
    pInstance->MemoryTable = *pMemoryTable;


    /*
     * Set all initial parameters to invalid to force a full initialisation
     */
    pInstance->Params.OperatingMode  = LVCS_OFF;
    pInstance->Params.SpeakerType    = LVCS_SPEAKERTYPE_MAX;
    pInstance->OutputDevice          = LVCS_HEADPHONE;
    pInstance->Params.SourceFormat   = LVCS_SOURCEMAX;
    pInstance->Params.CompressorMode = LVM_MODE_OFF;
    pInstance->Params.SampleRate     = LVM_FS_INVALID;
    pInstance->Params.EffectLevel    = 0;
    pInstance->Params.ReverbLevel    = (LVM_UINT16)0x8000;
    pLVCS_VolCorrectTable            = (LVCS_VolCorrect_t*)&LVCS_VolCorrectTable[0];
    pInstance->VolCorrect            = pLVCS_VolCorrectTable[0];
    pInstance->TransitionGain        = 0;
    /* These current and target values are intialized again in LVCS_Control.c */
    LVC_Mixer_Init(&pInstance->BypassMix.Mixer_Instance.MixerStream[0],0,0);
    /* These current and target values are intialized again in LVCS_Control.c */
    LVC_Mixer_Init(&pInstance->BypassMix.Mixer_Instance.MixerStream[1],0,0);

    /*
     * Initialise the bypass variables
     */
    pInstance->MSTarget0=0;
    pInstance->MSTarget1=0;
    pInstance->bInOperatingModeTransition          = LVM_FALSE;
    pInstance->bTimerDone                        = LVM_FALSE;
    pInstance->TimerParams.CallBackParam         = 0;
    pInstance->TimerParams.pCallBack             = LVCS_TimerCallBack;
    pInstance->TimerParams.pCallbackInstance     = pInstance;
    pInstance->TimerParams.pCallBackParams       = LVM_NULL;

    return(LVCS_SUCCESS);
}

