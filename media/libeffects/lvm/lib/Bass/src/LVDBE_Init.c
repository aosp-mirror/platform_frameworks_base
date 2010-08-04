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

/****************************************************************************************

     $Author: beq06068 $
     $Revision: 1399 $
     $Date: 2010-08-03 08:16:00 +0200 (Tue, 03 Aug 2010) $

*****************************************************************************************/

/****************************************************************************************/
/*                                                                                      */
/*    Includes                                                                          */
/*                                                                                      */
/****************************************************************************************/

#include "LVDBE.h"
#include "LVDBE_Private.h"

/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                 LVDBE_Memory                                               */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*    This function is used for memory allocation and free. It can be called in         */
/*    two ways:                                                                         */
/*                                                                                      */
/*        hInstance = NULL                Returns the memory requirements               */
/*        hInstance = Instance handle        Returns the memory requirements and        */
/*                                        allocated base addresses for the instance     */
/*                                                                                      */
/*    When this function is called for memory allocation (hInstance=NULL) the memory    */
/*  base address pointers are NULL on return.                                           */
/*                                                                                      */
/*    When the function is called for free (hInstance = Instance Handle) the memory     */
/*  table returns the allocated memory and base addresses used during initialisation.   */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance                Instance Handle                                            */
/*  pMemoryTable             Pointer to an empty memory definition table                */
/*    pCapabilities           Pointer to the instance capabilities                      */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVDBE_SUCCESS            Succeeded                                                  */
/*                                                                                      */
/* NOTES:                                                                               */
/*    1.    This function may be interrupted by the LVDBE_Process function              */
/*                                                                                      */
/****************************************************************************************/

LVDBE_ReturnStatus_en LVDBE_Memory(LVDBE_Handle_t            hInstance,
                                   LVDBE_MemTab_t            *pMemoryTable,
                                   LVDBE_Capabilities_t      *pCapabilities)
{

    LVM_UINT32          ScratchSize;
    LVDBE_Instance_t    *pInstance = (LVDBE_Instance_t *)hInstance;


    /*
     * Fill in the memory table
     */
    if (hInstance == LVM_NULL)
    {
        /*
         * Instance memory
         */
        pMemoryTable->Region[LVDBE_MEMREGION_INSTANCE].Size         = sizeof(LVDBE_Instance_t);
        pMemoryTable->Region[LVDBE_MEMREGION_INSTANCE].Alignment    = LVDBE_INSTANCE_ALIGN;
        pMemoryTable->Region[LVDBE_MEMREGION_INSTANCE].Type         = LVDBE_PERSISTENT;
        pMemoryTable->Region[LVDBE_MEMREGION_INSTANCE].pBaseAddress = LVM_NULL;

        /*
         * Data memory
         */
        pMemoryTable->Region[LVDBE_MEMREGION_PERSISTENT_DATA].Size         = sizeof(LVDBE_Data_t);
        pMemoryTable->Region[LVDBE_MEMREGION_PERSISTENT_DATA].Alignment    = LVDBE_PERSISTENT_DATA_ALIGN;
        pMemoryTable->Region[LVDBE_MEMREGION_PERSISTENT_DATA].Type         = LVDBE_PERSISTENT_DATA;
        pMemoryTable->Region[LVDBE_MEMREGION_PERSISTENT_DATA].pBaseAddress = LVM_NULL;

        /*
         * Coef memory
         */
        pMemoryTable->Region[LVDBE_MEMREGION_PERSISTENT_COEF].Size         = sizeof(LVDBE_Coef_t);
        pMemoryTable->Region[LVDBE_MEMREGION_PERSISTENT_COEF].Alignment    = LVDBE_PERSISTENT_COEF_ALIGN;
        pMemoryTable->Region[LVDBE_MEMREGION_PERSISTENT_COEF].Type         = LVDBE_PERSISTENT_COEF;
        pMemoryTable->Region[LVDBE_MEMREGION_PERSISTENT_COEF].pBaseAddress = LVM_NULL;

        /*
         * Scratch memory
         */
        ScratchSize = (LVM_UINT32)(LVDBE_SCRATCHBUFFERS_INPLACE*sizeof(LVM_INT16)*pCapabilities->MaxBlockSize);
        pMemoryTable->Region[LVDBE_MEMREGION_SCRATCH].Size         = ScratchSize;
        pMemoryTable->Region[LVDBE_MEMREGION_SCRATCH].Alignment    = LVDBE_SCRATCH_ALIGN;
        pMemoryTable->Region[LVDBE_MEMREGION_SCRATCH].Type         = LVDBE_SCRATCH;
        pMemoryTable->Region[LVDBE_MEMREGION_SCRATCH].pBaseAddress = LVM_NULL;
    }
    else
    {
        /* Read back memory allocation table */
        *pMemoryTable = pInstance->MemoryTable;
    }

    return(LVDBE_SUCCESS);
}


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                 LVDBE_Init                                                 */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*    Create and initialisation function for the Dynamic Bass Enhancement module        */
/*                                                                                      */
/*    This function can be used to create an algorithm instance by calling with         */
/*    hInstance set to NULL. In this case the algorithm returns the new instance        */
/*    handle.                                                                           */
/*                                                                                      */
/*    This function can be used to force a full re-initialisation of the algorithm      */
/*    by calling with hInstance = Instance Handle. In this case the memory table        */
/*    should be correct for the instance, this can be ensured by calling the function   */
/*    DBE_Memory before calling this function.                                          */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance                  Instance handle                                          */
/*  pMemoryTable             Pointer to the memory definition table                     */
/*  pCapabilities              Pointer to the instance capabilities                     */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVDBE_SUCCESS            Initialisation succeeded                                   */
/*  LVDBE_ALIGNMENTERROR    Instance or scratch memory on incorrect alignment           */
/*    LVDBE_NULLADDRESS        Instance or scratch memory has a NULL pointer            */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1.     The instance handle is the pointer to the base address of the first memory   */
/*        region.                                                                       */
/*    2.    This function must not be interrupted by the LVDBE_Process function         */
/*                                                                                      */
/****************************************************************************************/

LVDBE_ReturnStatus_en LVDBE_Init(LVDBE_Handle_t         *phInstance,
                                   LVDBE_MemTab_t       *pMemoryTable,
                                   LVDBE_Capabilities_t *pCapabilities)
{

    LVDBE_Instance_t      *pInstance;
    LVMixer3_1St_st       *pMixer_Instance;
    LVMixer3_2St_st       *pBypassMixer_Instance;
    LVM_INT16             i;
    LVM_INT32             MixGain;


    /*
     * Set the instance handle if not already initialised
     */
    if (*phInstance == LVM_NULL)
    {
        *phInstance = (LVDBE_Handle_t)pMemoryTable->Region[LVDBE_MEMREGION_INSTANCE].pBaseAddress;
    }
    pInstance =(LVDBE_Instance_t  *)*phInstance;


    /*
     * Check the memory table for NULL pointers and incorrectly aligned data
     */
    for (i=0; i<LVDBE_NR_MEMORY_REGIONS; i++)
    {
        if (pMemoryTable->Region[i].Size!=0)
        {
            if (pMemoryTable->Region[i].pBaseAddress==LVM_NULL)
            {
                return(LVDBE_NULLADDRESS);
            }
            if (((LVM_UINT32)pMemoryTable->Region[i].pBaseAddress % pMemoryTable->Region[i].Alignment)!=0){
                return(LVDBE_ALIGNMENTERROR);
            }
        }
    }


    /*
     * Save the memory table in the instance structure
     */
    pInstance->Capabilities = *pCapabilities;


    /*
     * Save the memory table in the instance structure
     */
    pInstance->MemoryTable = *pMemoryTable;


    /*
     * Set the default instance parameters
     */
    pInstance->Params.CentreFrequency   =    LVDBE_CENTRE_55HZ;
    pInstance->Params.EffectLevel       =    0;
    pInstance->Params.HeadroomdB        =    0;
    pInstance->Params.HPFSelect         =    LVDBE_HPF_OFF;
    pInstance->Params.OperatingMode     =    LVDBE_OFF;
    pInstance->Params.SampleRate        =    LVDBE_FS_8000;
    pInstance->Params.VolumeControl     =    LVDBE_VOLUME_OFF;
    pInstance->Params.VolumedB          =    0;


    /*
     * Set pointer to data and coef memory
     */
    pInstance->pData = pMemoryTable->Region[LVDBE_MEMREGION_PERSISTENT_DATA].pBaseAddress;
    pInstance->pCoef = pMemoryTable->Region[LVDBE_MEMREGION_PERSISTENT_COEF].pBaseAddress;


    /*
     * Initialise the filters
     */
    LVDBE_SetFilters(pInstance,                 /* Set the filter taps and coefficients */
                     &pInstance->Params);


    /*
     * Initialise the AGC
     */
    LVDBE_SetAGC(pInstance,                                     /* Set the AGC gain */
                 &pInstance->Params);
    pInstance->pData->AGCInstance.AGC_Gain = pInstance->pData->AGCInstance.AGC_MaxGain;
                                                /* Default to the bass boost setting */


    /*
     * Initialise the volume
     */
    LVDBE_SetVolume(pInstance,                                         /* Set the Volume */
                    &pInstance->Params);

    pInstance->pData->AGCInstance.Volume = pInstance->pData->AGCInstance.Target;
                                                /* Initialise as the target */

    pMixer_Instance = &pInstance->pData->BypassVolume;
    MixGain = LVC_Mixer_GetTarget(&pMixer_Instance->MixerStream[0]);
    LVC_Mixer_Init(&pMixer_Instance->MixerStream[0],MixGain,MixGain);

    /* Configure the mixer process path */
    pMixer_Instance->MixerStream[0].CallbackParam = 0;
    pMixer_Instance->MixerStream[0].pCallbackHandle = LVM_NULL;
    pMixer_Instance->MixerStream[0].pCallBack = LVM_NULL;
    pMixer_Instance->MixerStream[0].CallbackSet = 0;

    /*
     * Initialise the clicks minimisation BypassMixer
     */

    pBypassMixer_Instance = &pInstance->pData->BypassMixer;

    /*
     * Setup the mixer gain for the processed path
     */
    pBypassMixer_Instance->MixerStream[0].CallbackParam = 0;
    pBypassMixer_Instance->MixerStream[0].pCallbackHandle = LVM_NULL;
    pBypassMixer_Instance->MixerStream[0].pCallBack = LVM_NULL;
    pBypassMixer_Instance->MixerStream[0].CallbackSet=0;
    LVC_Mixer_Init(&pBypassMixer_Instance->MixerStream[0],0,0);
    LVC_Mixer_SetTimeConstant(&pBypassMixer_Instance->MixerStream[0],
        LVDBE_BYPASS_MIXER_TC,pInstance->Params.SampleRate,2);
    /*
     * Setup the mixer gain for the unprocessed path
     */
    pBypassMixer_Instance->MixerStream[1].CallbackParam = 0;
    pBypassMixer_Instance->MixerStream[1].pCallbackHandle = LVM_NULL;
    pBypassMixer_Instance->MixerStream[1].pCallBack = LVM_NULL;
    pBypassMixer_Instance->MixerStream[1].CallbackSet=0;
    LVC_Mixer_Init(&pBypassMixer_Instance->MixerStream[1],0x00007FFF,0x00007FFF);
    LVC_Mixer_SetTimeConstant(&pBypassMixer_Instance->MixerStream[1],
        LVDBE_BYPASS_MIXER_TC,pInstance->Params.SampleRate,2);

    return(LVDBE_SUCCESS);
}

