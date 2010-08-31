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
/*  Includes                                                                            */
/*                                                                                      */
/****************************************************************************************/

#include "LVEQNB.h"
#include "LVEQNB_Private.h"
#include "InstAlloc.h"

/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVEQNB_Memory                                               */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  This function is used for memory allocation and free. It can be called in           */
/*  two ways:                                                                           */
/*                                                                                      */
/*      hInstance = NULL                Returns the memory requirements                 */
/*      hInstance = Instance handle     Returns the memory requirements and             */
/*                                      allocated base addresses for the instance       */
/*                                                                                      */
/*  When this function is called for memory allocation (hInstance=NULL) the memory      */
/*  base address pointers are NULL on return.                                           */
/*                                                                                      */
/*  When the function is called for free (hInstance = Instance Handle) the memory       */
/*  table returns the allocated memory and base addresses used during initialisation.   */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance               Instance Handle                                             */
/*  pMemoryTable            Pointer to an empty memory definition table                 */
/*  pCapabilities           Pointer to the instance capabilities                        */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVEQNB_SUCCESS          Succeeded                                                   */
/*  LVEQNB_NULLADDRESS      When any of pMemoryTable and pCapabilities is NULL address  */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1.  This function may be interrupted by the LVEQNB_Process function                 */
/*                                                                                      */
/****************************************************************************************/

LVEQNB_ReturnStatus_en LVEQNB_Memory(LVEQNB_Handle_t            hInstance,
                                     LVEQNB_MemTab_t            *pMemoryTable,
                                     LVEQNB_Capabilities_t      *pCapabilities)
{

    INST_ALLOC          AllocMem;
    LVEQNB_Instance_t   *pInstance = (LVEQNB_Instance_t *)hInstance;


    if((pMemoryTable == LVM_NULL)|| (pCapabilities == LVM_NULL))
    {
        return LVEQNB_NULLADDRESS;
    }


    /*
     * Fill in the memory table
     */
    if (hInstance == LVM_NULL)
    {
        /*
         * Instance memory
         */
        InstAlloc_Init(&AllocMem,
                       LVM_NULL);
        InstAlloc_AddMember(&AllocMem,                              /* Low pass filter */
                            sizeof(LVEQNB_Instance_t));
        pMemoryTable->Region[LVEQNB_MEMREGION_INSTANCE].Size         = InstAlloc_GetTotal(&AllocMem);
        pMemoryTable->Region[LVEQNB_MEMREGION_INSTANCE].Alignment    = LVEQNB_INSTANCE_ALIGN;
        pMemoryTable->Region[LVEQNB_MEMREGION_INSTANCE].Type         = LVEQNB_PERSISTENT;
        pMemoryTable->Region[LVEQNB_MEMREGION_INSTANCE].pBaseAddress = LVM_NULL;


        /*
         * Persistant data memory
         */
        InstAlloc_Init(&AllocMem,
                       LVM_NULL);
        InstAlloc_AddMember(&AllocMem,                              /* Low pass filter */
                            sizeof(Biquad_2I_Order2_Taps_t));
        InstAlloc_AddMember(&AllocMem,                              /* High pass filter */
                            sizeof(Biquad_2I_Order2_Taps_t));
        InstAlloc_AddMember(&AllocMem,
                            (pCapabilities->MaxBands * sizeof(Biquad_2I_Order2_Taps_t))); /* Equaliser Biquad Taps */
        InstAlloc_AddMember(&AllocMem,
                            (pCapabilities->MaxBands * sizeof(LVEQNB_BandDef_t)));        /* Filter definitions */
        InstAlloc_AddMember(&AllocMem,
                            (pCapabilities->MaxBands * sizeof(LVEQNB_BiquadType_en)));    /* Biquad types */
        pMemoryTable->Region[LVEQNB_MEMREGION_PERSISTENT_DATA].Size         = InstAlloc_GetTotal(&AllocMem);
        pMemoryTable->Region[LVEQNB_MEMREGION_PERSISTENT_DATA].Alignment    = LVEQNB_DATA_ALIGN;
        pMemoryTable->Region[LVEQNB_MEMREGION_PERSISTENT_DATA].Type         = LVEQNB_PERSISTENT_DATA;
        pMemoryTable->Region[LVEQNB_MEMREGION_PERSISTENT_DATA].pBaseAddress = LVM_NULL;

        /*
         * Persistant coefficient memory
         */
        InstAlloc_Init(&AllocMem,
                       LVM_NULL);
        InstAlloc_AddMember(&AllocMem,                              /* Low pass filter */
                            sizeof(Biquad_Instance_t));
        InstAlloc_AddMember(&AllocMem,                              /* High pass filter */
                            sizeof(Biquad_Instance_t));
        InstAlloc_AddMember(&AllocMem,
                            pCapabilities->MaxBands * sizeof(Biquad_Instance_t)); /* Equaliser Biquad Instance */
        pMemoryTable->Region[LVEQNB_MEMREGION_PERSISTENT_COEF].Size         = InstAlloc_GetTotal(&AllocMem);
        pMemoryTable->Region[LVEQNB_MEMREGION_PERSISTENT_COEF].Alignment    = LVEQNB_COEF_ALIGN;
        pMemoryTable->Region[LVEQNB_MEMREGION_PERSISTENT_COEF].Type         = LVEQNB_PERSISTENT_COEF;
        pMemoryTable->Region[LVEQNB_MEMREGION_PERSISTENT_COEF].pBaseAddress = LVM_NULL;

        /*
         * Scratch memory
         */
        InstAlloc_Init(&AllocMem,
                       LVM_NULL);
        InstAlloc_AddMember(&AllocMem,                              /* Low pass filter */
                            LVEQNB_SCRATCHBUFFERS*sizeof(LVM_INT16)*pCapabilities->MaxBlockSize);
        pMemoryTable->Region[LVEQNB_MEMREGION_SCRATCH].Size              = InstAlloc_GetTotal(&AllocMem);
        pMemoryTable->Region[LVEQNB_MEMREGION_SCRATCH].Alignment         = LVEQNB_SCRATCH_ALIGN;
        pMemoryTable->Region[LVEQNB_MEMREGION_SCRATCH].Type              = LVEQNB_SCRATCH;
        pMemoryTable->Region[LVEQNB_MEMREGION_SCRATCH].pBaseAddress      = LVM_NULL;
    }
    else
    {
        /* Read back memory allocation table */
        *pMemoryTable = pInstance->MemoryTable;
    }

    return(LVEQNB_SUCCESS);
}


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVEQNB_Init                                                 */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  Create and initialisation function for the N-Band equaliser module                  */
/*                                                                                      */
/*  This function can be used to create an algorithm instance by calling with           */
/*  hInstance set to NULL. In this case the algorithm returns the new instance          */
/*  handle.                                                                             */
/*                                                                                      */
/*  This function can be used to force a full re-initialisation of the algorithm        */
/*  by calling with hInstance = Instance Handle. In this case the memory table          */
/*  should be correct for the instance, this can be ensured by calling the function     */
/*  DBE_Memory before calling this function.                                            */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance               Instance handle                                             */
/*  pMemoryTable            Pointer to the memory definition table                      */
/*  pCapabilities           Pointer to the instance capabilities                        */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVEQNB_SUCCESS          Initialisation succeeded                                    */
/*  LVEQNB_NULLADDRESS        When pCapabilities or pMemoryTableis or phInstance are NULL */
/*  LVEQNB_NULLADDRESS        One or more of the memory regions has a NULL base address   */
/*                          pointer for a memory region with a non-zero size.           */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1.  The instance handle is the pointer to the base address of the first memory      */
/*      region.                                                                         */
/*  2.  This function must not be interrupted by the LVEQNB_Process function            */
/*                                                                                      */
/****************************************************************************************/

LVEQNB_ReturnStatus_en LVEQNB_Init(LVEQNB_Handle_t          *phInstance,
                                   LVEQNB_MemTab_t          *pMemoryTable,
                                   LVEQNB_Capabilities_t    *pCapabilities)
{

    LVEQNB_Instance_t   *pInstance;
    LVM_UINT32          MemSize;
    INST_ALLOC          AllocMem;
    LVM_INT32           i;

    /*
     * Check for NULL pointers
     */
    if((phInstance == LVM_NULL) || (pMemoryTable == LVM_NULL) || (pCapabilities == LVM_NULL))
    {
        return LVEQNB_NULLADDRESS;
    }

    /*
     * Check the memory table for NULL pointers
     */
    for (i = 0; i < LVEQNB_NR_MEMORY_REGIONS; i++)
    {
        if (pMemoryTable->Region[i].Size!=0)
        {
            if (pMemoryTable->Region[i].pBaseAddress==LVM_NULL)
            {
                return(LVEQNB_NULLADDRESS);
            }
        }
    }

    /*
     * Set the instance handle if not already initialised
     */

    InstAlloc_Init(&AllocMem,  pMemoryTable->Region[LVEQNB_MEMREGION_INSTANCE].pBaseAddress);

    if (*phInstance == LVM_NULL)
    {
        *phInstance = InstAlloc_AddMember(&AllocMem, sizeof(LVEQNB_Instance_t));
    }
    pInstance =(LVEQNB_Instance_t  *)*phInstance;



    /*
     * Save the memory table in the instance structure
     */
    pInstance->Capabilities = *pCapabilities;


    /*
     * Save the memory table in the instance structure and
     * set the structure pointers
     */
    pInstance->MemoryTable       = *pMemoryTable;

    /*
     * Allocate coefficient memory
     */
    InstAlloc_Init(&AllocMem,
                   pMemoryTable->Region[LVEQNB_MEMREGION_PERSISTENT_COEF].pBaseAddress);

    pInstance->pEQNB_FilterState = InstAlloc_AddMember(&AllocMem,
                                                       pCapabilities->MaxBands * sizeof(Biquad_Instance_t)); /* Equaliser Biquad Instance */



    /*
     * Allocate data memory
     */
    InstAlloc_Init(&AllocMem,
                   pMemoryTable->Region[LVEQNB_MEMREGION_PERSISTENT_DATA].pBaseAddress);

    MemSize = (pCapabilities->MaxBands * sizeof(Biquad_2I_Order2_Taps_t));
    pInstance->pEQNB_Taps = (Biquad_2I_Order2_Taps_t *)InstAlloc_AddMember(&AllocMem,
                                                                           MemSize);
    MemSize = (pCapabilities->MaxBands * sizeof(LVEQNB_BandDef_t));
    pInstance->pBandDefinitions  = (LVEQNB_BandDef_t *)InstAlloc_AddMember(&AllocMem,
                                                                           MemSize);
    MemSize = (pCapabilities->MaxBands * sizeof(LVEQNB_BiquadType_en));
    pInstance->pBiquadType = (LVEQNB_BiquadType_en *)InstAlloc_AddMember(&AllocMem,
                                                                         MemSize);


    /*
     * Internally map, structure and allign scratch memory
     */
    InstAlloc_Init(&AllocMem,
                   pMemoryTable->Region[LVEQNB_MEMREGION_SCRATCH].pBaseAddress);

    pInstance->pFastTemporary = (LVM_INT16 *)InstAlloc_AddMember(&AllocMem,
                                                                 sizeof(LVM_INT16));

    /*
     * Update the instance parameters
     */
    pInstance->Params.NBands          = 0;
    pInstance->Params.OperatingMode   = LVEQNB_BYPASS;
    pInstance->Params.pBandDefinition = LVM_NULL;
    pInstance->Params.SampleRate      = LVEQNB_FS_8000;
    pInstance->Params.SourceFormat    = LVEQNB_STEREO;

    /*
     * Initialise the filters
     */
    LVEQNB_SetFilters(pInstance,                        /* Set the filter types */
                      &pInstance->Params);

    LVEQNB_SetCoefficients(pInstance);                  /* Set the filter coefficients */

    LVEQNB_ClearFilterHistory(pInstance);               /* Clear the filter history */

    /*
     * Initialise the bypass variables
     */
    pInstance->BypassMixer.MixerStream[0].CallbackSet        = 0;
    pInstance->BypassMixer.MixerStream[0].CallbackParam      = 0;
    pInstance->BypassMixer.MixerStream[0].pCallbackHandle    = (void*)pInstance;
    pInstance->BypassMixer.MixerStream[0].pCallBack          = LVEQNB_BypassMixerCallBack;
    LVC_Mixer_Init(&pInstance->BypassMixer.MixerStream[0],0,0);
    LVC_Mixer_SetTimeConstant(&pInstance->BypassMixer.MixerStream[0],0,LVM_FS_8000,2);

    pInstance->BypassMixer.MixerStream[1].CallbackSet        = 1;
    pInstance->BypassMixer.MixerStream[1].CallbackParam      = 0;
    pInstance->BypassMixer.MixerStream[1].pCallbackHandle    = LVM_NULL;
    pInstance->BypassMixer.MixerStream[1].pCallBack          = LVM_NULL;
    LVC_Mixer_Init(&pInstance->BypassMixer.MixerStream[1],0,LVM_MAXINT_16);
    LVC_Mixer_SetTimeConstant(&pInstance->BypassMixer.MixerStream[1],0,LVM_FS_8000,2);

    pInstance->bInOperatingModeTransition      = LVM_FALSE;

    return(LVEQNB_SUCCESS);
}

