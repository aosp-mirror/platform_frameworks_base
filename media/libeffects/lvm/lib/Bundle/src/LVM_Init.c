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

#include "LVM_Private.h"
#include "LVM_Tables.h"
#include "VectorArithmetic.h"
#include "InstAlloc.h"

/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVM_GetMemoryTable                                          */
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
/*  pCapabilities           Pointer to the default capabilities                         */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVM_SUCCESS             Succeeded                                                   */
/*  LVM_NULLADDRESS         When one of pMemoryTable or pInstParams is NULL             */
/*  LVM_OUTOFRANGE          When any of the Instance parameters are out of range        */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1.  This function may be interrupted by the LVM_Process function                    */
/*  2.  The scratch memory is the largest required by any of the sub-modules plus any   */
/*      additional scratch requirements of the bundle                                   */
/*                                                                                      */
/****************************************************************************************/

LVM_ReturnStatus_en LVM_GetMemoryTable(LVM_Handle_t         hInstance,
                                       LVM_MemTab_t         *pMemoryTable,
                                       LVM_InstParams_t     *pInstParams)
{

    LVM_Instance_t      *pInstance = (LVM_Instance_t *)hInstance;
    LVM_UINT32          AlgScratchSize;
    LVM_UINT32          BundleScratchSize;
    LVM_UINT16          InternalBlockSize;
    INST_ALLOC          AllocMem[LVM_NR_MEMORY_REGIONS];
    LVM_INT16           i;


    /*
     * Check parameters
     */
    if(pMemoryTable == LVM_NULL)
    {
        return LVM_NULLADDRESS;
    }


    /*
     * Return memory table if the instance has already been created
     */
    if (hInstance != LVM_NULL)
    {
       /* Read back memory allocation table */
        *pMemoryTable = pInstance->MemoryTable;
        return(LVM_SUCCESS);
    }

    if(pInstParams == LVM_NULL)
    {
        return LVM_NULLADDRESS;
    }

    /*
     *  Power Spectrum Analyser
     */
    if(pInstParams->PSA_Included > LVM_PSA_ON)
    {
        return (LVM_OUTOFRANGE);
    }

    /*
     * Check the instance parameters
     */
    if( (pInstParams->BufferMode != LVM_MANAGED_BUFFERS) && (pInstParams->BufferMode != LVM_UNMANAGED_BUFFERS) )
    {
        return (LVM_OUTOFRANGE);
    }

    /* N-Band Equalizer */
    if( pInstParams->EQNB_NumBands > 32 )
    {
        return (LVM_OUTOFRANGE);
    }

    if(pInstParams->BufferMode == LVM_MANAGED_BUFFERS)
    {
        if( (pInstParams->MaxBlockSize < LVM_MIN_MAXBLOCKSIZE ) || (pInstParams->MaxBlockSize > LVM_MANAGED_MAX_MAXBLOCKSIZE ) )
        {
            return (LVM_OUTOFRANGE);
        }
    }
    else
    {
        if( (pInstParams->MaxBlockSize < LVM_MIN_MAXBLOCKSIZE ) || (pInstParams->MaxBlockSize > LVM_UNMANAGED_MAX_MAXBLOCKSIZE) )
        {
            return (LVM_OUTOFRANGE);
        }
    }

    /*
    * Initialise the AllocMem structures
    */
    for (i=0; i<LVM_NR_MEMORY_REGIONS; i++)
    {
        InstAlloc_Init(&AllocMem[i], LVM_NULL);
    }
    InternalBlockSize = (LVM_UINT16)((pInstParams->MaxBlockSize) & MIN_INTERNAL_BLOCKMASK); /* Force to a multiple of MIN_INTERNAL_BLOCKSIZE */

    if (InternalBlockSize < MIN_INTERNAL_BLOCKSIZE)
    {
        InternalBlockSize = MIN_INTERNAL_BLOCKSIZE;
    }

    /* Maximum Internal Black Size should not be more than MAX_INTERNAL_BLOCKSIZE*/
    if(InternalBlockSize > MAX_INTERNAL_BLOCKSIZE)
    {
        InternalBlockSize = MAX_INTERNAL_BLOCKSIZE;
    }

    /*
    * Bundle requirements
    */
    InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_PERSISTENT_SLOW_DATA],
        sizeof(LVM_Instance_t));


    /*
     * Set the algorithm and bundle scratch requirements
     */
    AlgScratchSize    = 0;
    if (pInstParams->BufferMode == LVM_MANAGED_BUFFERS)
    {
        BundleScratchSize = 6 * (MIN_INTERNAL_BLOCKSIZE + InternalBlockSize) * sizeof(LVM_INT16);
        InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_TEMPORARY_FAST],        /* Scratch buffer */
                            BundleScratchSize);
        InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_PERSISTENT_SLOW_DATA],
                            sizeof(LVM_Buffer_t));
    }

    /*
     * Treble Enhancement requirements
     */
    InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_PERSISTENT_FAST_DATA],
                        sizeof(LVM_TE_Data_t));
    InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_PERSISTENT_FAST_COEF],
                        sizeof(LVM_TE_Coefs_t));

    /*
     * N-Band Equalizer requirements
     */
    InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_PERSISTENT_FAST_DATA],      /* Local storage */
                        (pInstParams->EQNB_NumBands * sizeof(LVM_EQNB_BandDef_t)));
    InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_PERSISTENT_FAST_DATA],      /* User storage */
                        (pInstParams->EQNB_NumBands * sizeof(LVM_EQNB_BandDef_t)));

    /*
     * Concert Sound requirements
     */
    {
        LVCS_MemTab_t           CS_MemTab;
        LVCS_Capabilities_t     CS_Capabilities;

        /*
         * Set the capabilities
         */
        CS_Capabilities.MaxBlockSize     = InternalBlockSize;

        /*
         * Get the memory requirements
         */
        LVCS_Memory(LVM_NULL,
                    &CS_MemTab,
                    &CS_Capabilities);

        /*
         * Update the memory allocation structures
         */
        InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_PERSISTENT_FAST_DATA],
                            CS_MemTab.Region[LVM_MEMREGION_PERSISTENT_FAST_DATA].Size);
        InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_PERSISTENT_FAST_COEF],
                            CS_MemTab.Region[LVM_MEMREGION_PERSISTENT_FAST_COEF].Size);
        if (CS_MemTab.Region[LVM_MEMREGION_TEMPORARY_FAST].Size > AlgScratchSize) AlgScratchSize = CS_MemTab.Region[LVM_MEMREGION_TEMPORARY_FAST].Size;

    }


    /*
     * Dynamic Bass Enhancement requirements
     */
    {
        LVDBE_MemTab_t          DBE_MemTab;
        LVDBE_Capabilities_t    DBE_Capabilities;

        /*
         * Set the capabilities
         */
        DBE_Capabilities.SampleRate      = LVDBE_CAP_FS_8000 | LVDBE_CAP_FS_11025 | LVDBE_CAP_FS_12000 | LVDBE_CAP_FS_16000 | LVDBE_CAP_FS_22050 | LVDBE_CAP_FS_24000 | LVDBE_CAP_FS_32000 | LVDBE_CAP_FS_44100 | LVDBE_CAP_FS_48000;
        DBE_Capabilities.CentreFrequency = LVDBE_CAP_CENTRE_55Hz | LVDBE_CAP_CENTRE_55Hz | LVDBE_CAP_CENTRE_66Hz | LVDBE_CAP_CENTRE_78Hz | LVDBE_CAP_CENTRE_90Hz;
        DBE_Capabilities.MaxBlockSize    = InternalBlockSize;

        /*
         * Get the memory requirements
         */
        LVDBE_Memory(LVM_NULL,
                    &DBE_MemTab,

                    &DBE_Capabilities);
        /*
         * Update the bundle table
         */
        InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_PERSISTENT_FAST_DATA],
                            DBE_MemTab.Region[LVM_MEMREGION_PERSISTENT_FAST_DATA].Size);
        InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_PERSISTENT_FAST_COEF],
                            DBE_MemTab.Region[LVM_MEMREGION_PERSISTENT_FAST_COEF].Size);
        if (DBE_MemTab.Region[LVM_MEMREGION_TEMPORARY_FAST].Size > AlgScratchSize) AlgScratchSize = DBE_MemTab.Region[LVM_MEMREGION_TEMPORARY_FAST].Size;

    }


    /*
     * N-Band equaliser requirements
     */
    {
        LVEQNB_MemTab_t         EQNB_MemTab;            /* For N-Band Equaliser */
        LVEQNB_Capabilities_t   EQNB_Capabilities;

        /*
         * Set the capabilities
         */
        EQNB_Capabilities.SampleRate   = LVEQNB_CAP_FS_8000 | LVEQNB_CAP_FS_11025 | LVEQNB_CAP_FS_12000 | LVEQNB_CAP_FS_16000 | LVEQNB_CAP_FS_22050 | LVEQNB_CAP_FS_24000 | LVEQNB_CAP_FS_32000 | LVEQNB_CAP_FS_44100 | LVEQNB_CAP_FS_48000;
        EQNB_Capabilities.SourceFormat = LVEQNB_CAP_STEREO | LVEQNB_CAP_MONOINSTEREO;
        EQNB_Capabilities.MaxBlockSize = InternalBlockSize;
        EQNB_Capabilities.MaxBands     = pInstParams->EQNB_NumBands;

        /*
         * Get the memory requirements
         */
        LVEQNB_Memory(LVM_NULL,
                      &EQNB_MemTab,
                      &EQNB_Capabilities);

        /*
         * Update the bundle table
         */
        InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_PERSISTENT_FAST_DATA],
                            EQNB_MemTab.Region[LVM_MEMREGION_PERSISTENT_FAST_DATA].Size);
        InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_PERSISTENT_FAST_COEF],
                            EQNB_MemTab.Region[LVM_MEMREGION_PERSISTENT_FAST_COEF].Size);
        if (EQNB_MemTab.Region[LVM_MEMREGION_TEMPORARY_FAST].Size > AlgScratchSize) AlgScratchSize = EQNB_MemTab.Region[LVM_MEMREGION_TEMPORARY_FAST].Size;

    }

    /*
     * Headroom management memory allocation
     */
    InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_PERSISTENT_FAST_DATA],
                       (LVM_HEADROOM_MAX_NBANDS * sizeof(LVM_HeadroomBandDef_t)));
    InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_PERSISTENT_FAST_DATA],
                       (LVM_HEADROOM_MAX_NBANDS * sizeof(LVM_HeadroomBandDef_t)));


    /*
     * Spectrum Analyzer memory requirements
     */
    {
        pLVPSA_Handle_t     hPSAInst = LVM_NULL;
        LVPSA_MemTab_t      PSA_MemTab;
        LVPSA_InitParams_t  PSA_InitParams;
        LVPSA_FilterParam_t FiltersParams[9];
        LVPSA_RETURN        PSA_Status;

        if(pInstParams->PSA_Included == LVM_PSA_ON)
        {
            PSA_InitParams.SpectralDataBufferDuration   = (LVM_UINT16) 500;
            PSA_InitParams.MaxInputBlockSize            = (LVM_UINT16) 1000;
            PSA_InitParams.nBands                       = (LVM_UINT16) 9;

            PSA_InitParams.pFiltersParams = &FiltersParams[0];
            for(i = 0; i < PSA_InitParams.nBands; i++)
            {
                FiltersParams[i].CenterFrequency    = (LVM_UINT16) 1000;
                FiltersParams[i].QFactor            = (LVM_UINT16) 25;
                FiltersParams[i].PostGain           = (LVM_INT16)  0;
            }

            /*
            * Get the memory requirements
            */
            PSA_Status = LVPSA_Memory (hPSAInst,
                                        &PSA_MemTab,
                                        &PSA_InitParams);

            if (PSA_Status != LVPSA_OK)
            {
                return((LVM_ReturnStatus_en) LVM_ALGORITHMPSA);
            }

            /*
            * Update the bundle table
            */
            /* Slow Data */
            InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_PERSISTENT_SLOW_DATA],
                PSA_MemTab.Region[LVM_PERSISTENT_SLOW_DATA].Size);

            /* Fast Data */
            InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_PERSISTENT_FAST_DATA],
                PSA_MemTab.Region[LVM_PERSISTENT_FAST_DATA].Size);

            /* Fast Coef */
            InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_PERSISTENT_FAST_COEF],
                PSA_MemTab.Region[LVM_PERSISTENT_FAST_COEF].Size);

            /* Fast Temporary */
            InstAlloc_AddMember(&AllocMem[LVM_TEMPORARY_FAST],
                                MAX_INTERNAL_BLOCKSIZE * sizeof(LVM_INT16));

            if (PSA_MemTab.Region[LVM_TEMPORARY_FAST].Size > AlgScratchSize)
            {
                AlgScratchSize = PSA_MemTab.Region[LVM_TEMPORARY_FAST].Size;
            }
        }
    }

    /*
     * Return the memory table
     */
    pMemoryTable->Region[LVM_MEMREGION_PERSISTENT_SLOW_DATA].Size         = InstAlloc_GetTotal(&AllocMem[LVM_MEMREGION_PERSISTENT_SLOW_DATA]);
    pMemoryTable->Region[LVM_MEMREGION_PERSISTENT_SLOW_DATA].Type         = LVM_PERSISTENT_SLOW_DATA;
    pMemoryTable->Region[LVM_MEMREGION_PERSISTENT_SLOW_DATA].pBaseAddress = LVM_NULL;

    pMemoryTable->Region[LVM_MEMREGION_PERSISTENT_FAST_DATA].Size         = InstAlloc_GetTotal(&AllocMem[LVM_MEMREGION_PERSISTENT_FAST_DATA]);
    pMemoryTable->Region[LVM_MEMREGION_PERSISTENT_FAST_DATA].Type         = LVM_PERSISTENT_FAST_DATA;
    pMemoryTable->Region[LVM_MEMREGION_PERSISTENT_FAST_DATA].pBaseAddress = LVM_NULL;
    if (pMemoryTable->Region[LVM_MEMREGION_PERSISTENT_FAST_DATA].Size < 4)
    {
        pMemoryTable->Region[LVM_MEMREGION_PERSISTENT_FAST_DATA].Size = 0;
    }

    pMemoryTable->Region[LVM_MEMREGION_PERSISTENT_FAST_COEF].Size         = InstAlloc_GetTotal(&AllocMem[LVM_MEMREGION_PERSISTENT_FAST_COEF]);
    pMemoryTable->Region[LVM_MEMREGION_PERSISTENT_FAST_COEF].Type         = LVM_PERSISTENT_FAST_COEF;
    pMemoryTable->Region[LVM_MEMREGION_PERSISTENT_FAST_COEF].pBaseAddress = LVM_NULL;
    if (pMemoryTable->Region[LVM_MEMREGION_PERSISTENT_FAST_COEF].Size < 4)
    {
        pMemoryTable->Region[LVM_MEMREGION_PERSISTENT_FAST_COEF].Size = 0;
    }

    InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_TEMPORARY_FAST],
                        AlgScratchSize);
    pMemoryTable->Region[LVM_MEMREGION_TEMPORARY_FAST].Size             = InstAlloc_GetTotal(&AllocMem[LVM_MEMREGION_TEMPORARY_FAST]);
    pMemoryTable->Region[LVM_MEMREGION_TEMPORARY_FAST].Type             = LVM_TEMPORARY_FAST;
    pMemoryTable->Region[LVM_MEMREGION_TEMPORARY_FAST].pBaseAddress     = LVM_NULL;
    if (pMemoryTable->Region[LVM_MEMREGION_TEMPORARY_FAST].Size < 4)
    {
        pMemoryTable->Region[LVM_MEMREGION_TEMPORARY_FAST].Size = 0;
    }

    return(LVM_SUCCESS);

}


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVM_GetInstanceHandle                                       */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  This function is used to create a bundle instance. It returns the created instance  */
/*  handle through phInstance. All parameters are set to their default, inactive state. */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  phInstance              pointer to the instance handle                              */
/*  pMemoryTable            Pointer to the memory definition table                      */
/*  pInstParams             Pointer to the initialisation capabilities                  */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVM_SUCCESS             Initialisation succeeded                                    */
/*  LVM_OUTOFRANGE          When any of the Instance parameters are out of range        */
/*  LVM_NULLADDRESS         When one of phInstance, pMemoryTable or pInstParams are NULL*/
/*                                                                                      */
/* NOTES:                                                                               */
/*  1. This function must not be interrupted by the LVM_Process function                */
/*                                                                                      */
/****************************************************************************************/

LVM_ReturnStatus_en LVM_GetInstanceHandle(LVM_Handle_t           *phInstance,
                                          LVM_MemTab_t           *pMemoryTable,
                                          LVM_InstParams_t       *pInstParams)
{

    LVM_ReturnStatus_en     Status = LVM_SUCCESS;
    LVM_Instance_t          *pInstance;
    INST_ALLOC              AllocMem[LVM_NR_MEMORY_REGIONS];
    LVM_INT16               i;
    LVM_UINT16              InternalBlockSize;
    LVM_INT32               BundleScratchSize;


    /*
     * Check valid points have been given
     */
    if ((phInstance == LVM_NULL) || (pMemoryTable == LVM_NULL) || (pInstParams == LVM_NULL))
    {
        return (LVM_NULLADDRESS);
    }

    /*
     * Check the memory table for NULL pointers
     */
    for (i=0; i<LVM_NR_MEMORY_REGIONS; i++)
    {
        if ((pMemoryTable->Region[i].Size != 0) &&
            (pMemoryTable->Region[i].pBaseAddress==LVM_NULL))
        {
            return(LVM_NULLADDRESS);
        }
    }

    /*
     * Check the instance parameters
     */
    if( (pInstParams->BufferMode != LVM_MANAGED_BUFFERS) && (pInstParams->BufferMode != LVM_UNMANAGED_BUFFERS) )
    {
        return (LVM_OUTOFRANGE);
    }

    if( pInstParams->EQNB_NumBands > 32 )
    {
        return (LVM_OUTOFRANGE);
    }

    if(pInstParams->BufferMode == LVM_MANAGED_BUFFERS)
    {
        if( (pInstParams->MaxBlockSize < LVM_MIN_MAXBLOCKSIZE ) || (pInstParams->MaxBlockSize > LVM_MANAGED_MAX_MAXBLOCKSIZE ) )
        {
            return (LVM_OUTOFRANGE);
        }
    }
    else
    {
        if( (pInstParams->MaxBlockSize < LVM_MIN_MAXBLOCKSIZE ) || (pInstParams->MaxBlockSize > LVM_UNMANAGED_MAX_MAXBLOCKSIZE) )
        {
            return (LVM_OUTOFRANGE);
        }
    }

    if(pInstParams->PSA_Included > LVM_PSA_ON)
    {
        return (LVM_OUTOFRANGE);
    }

    /*
     * Initialise the AllocMem structures
     */
    for (i=0; i<LVM_NR_MEMORY_REGIONS; i++)
    {
        InstAlloc_Init(&AllocMem[i],
                       pMemoryTable->Region[i].pBaseAddress);
    }


    /*
     * Set the instance handle
     */
    *phInstance  = (LVM_Handle_t)InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_PERSISTENT_SLOW_DATA],
                                                     sizeof(LVM_Instance_t));
    pInstance =(LVM_Instance_t  *)*phInstance;


    /*
     * Save the memory table, parameters and capabilities
     */
    pInstance->MemoryTable    = *pMemoryTable;
    pInstance->InstParams     = *pInstParams;


    /*
     * Set the bundle scratch memory and initialse the buffer management
     */
    InternalBlockSize = (LVM_UINT16)((pInstParams->MaxBlockSize) & MIN_INTERNAL_BLOCKMASK); /* Force to a multiple of MIN_INTERNAL_BLOCKSIZE */
    if (InternalBlockSize < MIN_INTERNAL_BLOCKSIZE)
    {
        InternalBlockSize = MIN_INTERNAL_BLOCKSIZE;
    }

    /* Maximum Internal Black Size should not be more than MAX_INTERNAL_BLOCKSIZE*/
    if(InternalBlockSize > MAX_INTERNAL_BLOCKSIZE)
    {
        InternalBlockSize = MAX_INTERNAL_BLOCKSIZE;
    }
    pInstance->InternalBlockSize = (LVM_INT16)InternalBlockSize;


    /*
     * Common settings for managed and unmanaged buffers
     */
    pInstance->SamplesToProcess = 0;                /* No samples left to process */
    if (pInstParams->BufferMode == LVM_MANAGED_BUFFERS)
    {
        /*
         * Managed buffers required
         */
        pInstance->pBufferManagement = InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_PERSISTENT_SLOW_DATA],
                                                           sizeof(LVM_Buffer_t));
        BundleScratchSize = (LVM_INT32)(6 * (MIN_INTERNAL_BLOCKSIZE + InternalBlockSize) * sizeof(LVM_INT16));
        pInstance->pBufferManagement->pScratch = InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_TEMPORARY_FAST],   /* Scratch 1 buffer */
                                                                     (LVM_UINT32)BundleScratchSize);

        LoadConst_16(0,                                                        /* Clear the input delay buffer */
                     (LVM_INT16 *)&pInstance->pBufferManagement->InDelayBuffer,
                     (LVM_INT16)(2 * MIN_INTERNAL_BLOCKSIZE));
        pInstance->pBufferManagement->InDelaySamples = MIN_INTERNAL_BLOCKSIZE; /* Set the number of delay samples */
        pInstance->pBufferManagement->OutDelaySamples = 0;                     /* No samples in the output buffer */
        pInstance->pBufferManagement->BufferState = LVM_FIRSTCALL;             /* Set the state ready for the first call */
    }


    /*
     * Set default parameters
     */
    pInstance->Params.OperatingMode    = LVM_MODE_OFF;
    pInstance->Params.SampleRate       = LVM_FS_8000;
    pInstance->Params.SourceFormat     = LVM_MONO;
    pInstance->Params.SpeakerType      = LVM_HEADPHONES;
    pInstance->Params.VC_EffectLevel   = 0;
    pInstance->Params.VC_Balance       = 0;

    /*
     * Set callback
     */
    pInstance->CallBack = LVM_AlgoCallBack;


    /*
     * DC removal filter
     */
    DC_2I_D16_TRC_WRA_01_Init(&pInstance->DC_RemovalInstance);


    /*
     * Treble Enhancement
     */
    pInstance->pTE_Taps  = (LVM_TE_Data_t *)InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_PERSISTENT_FAST_DATA],
                                                                sizeof(LVM_TE_Data_t));

    pInstance->pTE_State = (LVM_TE_Coefs_t *)InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_PERSISTENT_FAST_COEF],
                                                                 sizeof(LVM_TE_Coefs_t));
    pInstance->Params.TE_OperatingMode = LVM_TE_OFF;
    pInstance->Params.TE_EffectLevel   = 0;
    pInstance->TE_Active               = LVM_FALSE;


    /*
     * Set the volume control and initialise Current to Target
     */
    pInstance->VC_Volume.MixerStream[0].CallbackParam      = 0;
    pInstance->VC_Volume.MixerStream[0].CallbackSet        = 0;
    pInstance->VC_Volume.MixerStream[0].pCallbackHandle    = pInstance;
    pInstance->VC_Volume.MixerStream[0].pCallBack          = LVM_VCCallBack;

    /* In managed buffering, start with low signal level as delay in buffer management causes a click*/
    if (pInstParams->BufferMode == LVM_MANAGED_BUFFERS)
    {
        LVC_Mixer_Init(&pInstance->VC_Volume.MixerStream[0],0,0);
    }
    else
    {
        LVC_Mixer_Init(&pInstance->VC_Volume.MixerStream[0],LVM_MAXINT_16,LVM_MAXINT_16);
    }

    LVC_Mixer_SetTimeConstant(&pInstance->VC_Volume.MixerStream[0],0,LVM_FS_8000,2);

    pInstance->VC_VolumedB                  = 0;
    pInstance->VC_AVLFixedVolume            = 0;
    pInstance->VC_Active                    = LVM_FALSE;

    pInstance->VC_BalanceMix.MixerStream[0].CallbackParam      = 0;
    pInstance->VC_BalanceMix.MixerStream[0].CallbackSet        = 0;
    pInstance->VC_BalanceMix.MixerStream[0].pCallbackHandle    = pInstance;
    pInstance->VC_BalanceMix.MixerStream[0].pCallBack          = LVM_VCCallBack;
    LVC_Mixer_Init(&pInstance->VC_BalanceMix.MixerStream[0],LVM_MAXINT_16,LVM_MAXINT_16);
    LVC_Mixer_VarSlope_SetTimeConstant(&pInstance->VC_BalanceMix.MixerStream[0],LVM_VC_MIXER_TIME,LVM_FS_8000,2);

    pInstance->VC_BalanceMix.MixerStream[1].CallbackParam      = 0;
    pInstance->VC_BalanceMix.MixerStream[1].CallbackSet        = 0;
    pInstance->VC_BalanceMix.MixerStream[1].pCallbackHandle    = pInstance;
    pInstance->VC_BalanceMix.MixerStream[1].pCallBack          = LVM_VCCallBack;
    LVC_Mixer_Init(&pInstance->VC_BalanceMix.MixerStream[1],LVM_MAXINT_16,LVM_MAXINT_16);
    LVC_Mixer_VarSlope_SetTimeConstant(&pInstance->VC_BalanceMix.MixerStream[1],LVM_VC_MIXER_TIME,LVM_FS_8000,2);
    /*
     * Set the default EQNB pre-gain and pointer to the band definitions
     */
    pInstance->pEQNB_BandDefs = InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_PERSISTENT_FAST_DATA],
                                                    (pInstParams->EQNB_NumBands * sizeof(LVM_EQNB_BandDef_t)));
    pInstance->pEQNB_UserDefs = InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_PERSISTENT_FAST_DATA],
                                                   (pInstParams->EQNB_NumBands * sizeof(LVM_EQNB_BandDef_t)));


    /*
     * Initialise the Concert Sound module
     */
    {
        LVCS_Handle_t           hCSInstance;                /* Instance handle */
        LVCS_MemTab_t           CS_MemTab;                  /* Memory table */
        LVCS_Capabilities_t     CS_Capabilities;            /* Initial capabilities */
        LVCS_ReturnStatus_en    LVCS_Status;                /* Function call status */

        /*
         * Set default parameters
         */
        pInstance->Params.VirtualizerReverbLevel    = 100;
        pInstance->Params.VirtualizerType           = LVM_CONCERTSOUND;
        pInstance->Params.VirtualizerOperatingMode  = LVM_MODE_OFF;
        pInstance->CS_Active                        = LVM_FALSE;

        /*
         * Set the initialisation capabilities
         */
        CS_Capabilities.MaxBlockSize    = (LVM_UINT16)InternalBlockSize;
        CS_Capabilities.CallBack = pInstance->CallBack;
        CS_Capabilities.pBundleInstance = (void*)pInstance;


        /*
         * Get the memory requirements and then set the address pointers, forcing alignment
         */
        LVCS_Status = LVCS_Memory(LVM_NULL,                /* Get the memory requirements */
                                  &CS_MemTab,
                                  &CS_Capabilities);
        CS_MemTab.Region[LVCS_MEMREGION_PERSISTENT_SLOW_DATA].pBaseAddress = &pInstance->CS_Instance;
        CS_MemTab.Region[LVCS_MEMREGION_PERSISTENT_FAST_DATA].pBaseAddress = (void *)InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_PERSISTENT_FAST_DATA],
                                                                                                         CS_MemTab.Region[LVCS_MEMREGION_PERSISTENT_FAST_DATA].Size);
        CS_MemTab.Region[LVCS_MEMREGION_PERSISTENT_FAST_COEF].pBaseAddress = (void *)InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_PERSISTENT_FAST_COEF],
                                                                                                         CS_MemTab.Region[LVCS_MEMREGION_PERSISTENT_FAST_COEF].Size);
        CS_MemTab.Region[LVCS_MEMREGION_TEMPORARY_FAST].pBaseAddress       = (void *)InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_TEMPORARY_FAST],
                                                                                                         0);

        /*
         * Initialise the Concert Sound instance and save the instance handle
         */
        hCSInstance = LVM_NULL;                            /* Set to NULL to return handle */
        LVCS_Status = LVCS_Init(&hCSInstance,              /* Initiailse */
                                &CS_MemTab,
                                &CS_Capabilities);
        if (LVCS_Status != LVCS_SUCCESS) return((LVM_ReturnStatus_en)LVCS_Status);
        pInstance->hCSInstance = hCSInstance;              /* Save the instance handle */

    }

    /*
     * Initialise the Bass Enhancement module
     */
    {
        LVDBE_Handle_t          hDBEInstance;               /* Instance handle */
        LVDBE_MemTab_t          DBE_MemTab;                 /* Memory table */
        LVDBE_Capabilities_t    DBE_Capabilities;           /* Initial capabilities */
        LVDBE_ReturnStatus_en   LVDBE_Status;               /* Function call status */


        /*
         * Set the initialisation parameters
         */
        pInstance->Params.BE_OperatingMode = LVM_BE_OFF;
        pInstance->Params.BE_CentreFreq    = LVM_BE_CENTRE_55Hz;
        pInstance->Params.BE_EffectLevel   = 0;
        pInstance->Params.BE_HPF           = LVM_BE_HPF_OFF;

        pInstance->DBE_Active              = LVM_FALSE;



        /*
         * Set the initialisation capabilities
         */
        DBE_Capabilities.SampleRate      = LVDBE_CAP_FS_8000 | LVDBE_CAP_FS_11025 | LVDBE_CAP_FS_12000 | LVDBE_CAP_FS_16000 | LVDBE_CAP_FS_22050 | LVDBE_CAP_FS_24000 | LVDBE_CAP_FS_32000 | LVDBE_CAP_FS_44100 | LVDBE_CAP_FS_48000;
        DBE_Capabilities.CentreFrequency = LVDBE_CAP_CENTRE_55Hz | LVDBE_CAP_CENTRE_55Hz | LVDBE_CAP_CENTRE_66Hz | LVDBE_CAP_CENTRE_78Hz | LVDBE_CAP_CENTRE_90Hz;
        DBE_Capabilities.MaxBlockSize    = (LVM_UINT16)InternalBlockSize;


        /*
         * Get the memory requirements and then set the address pointers
         */
        LVDBE_Status = LVDBE_Memory(LVM_NULL,               /* Get the memory requirements */
                                    &DBE_MemTab,
                                    &DBE_Capabilities);
        DBE_MemTab.Region[LVDBE_MEMREGION_INSTANCE].pBaseAddress        = &pInstance->DBE_Instance;
        DBE_MemTab.Region[LVDBE_MEMREGION_PERSISTENT_DATA].pBaseAddress = (void *)InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_PERSISTENT_FAST_DATA],
                                                                                                      DBE_MemTab.Region[LVDBE_MEMREGION_PERSISTENT_DATA].Size);
        DBE_MemTab.Region[LVDBE_MEMREGION_PERSISTENT_COEF].pBaseAddress = (void *)InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_PERSISTENT_FAST_COEF],
                                                                                                      DBE_MemTab.Region[LVDBE_MEMREGION_PERSISTENT_COEF].Size);
        DBE_MemTab.Region[LVDBE_MEMREGION_SCRATCH].pBaseAddress         = (void *)InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_TEMPORARY_FAST],
                                                                                                      0);


        /*
         * Initialise the Dynamic Bass Enhancement instance and save the instance handle
         */
        hDBEInstance = LVM_NULL;                            /* Set to NULL to return handle */
        LVDBE_Status = LVDBE_Init(&hDBEInstance,            /* Initiailse */
                                  &DBE_MemTab,
                                  &DBE_Capabilities);
        if (LVDBE_Status != LVDBE_SUCCESS) return((LVM_ReturnStatus_en)LVDBE_Status);
        pInstance->hDBEInstance = hDBEInstance;             /* Save the instance handle */
    }


    /*
     * Initialise the N-Band Equaliser module
     */
    {
        LVEQNB_Handle_t          hEQNBInstance;             /* Instance handle */
        LVEQNB_MemTab_t          EQNB_MemTab;               /* Memory table */
        LVEQNB_Capabilities_t    EQNB_Capabilities;         /* Initial capabilities */
        LVEQNB_ReturnStatus_en   LVEQNB_Status;             /* Function call status */


        /*
         * Set the initialisation parameters
         */
        pInstance->Params.EQNB_OperatingMode   = LVM_EQNB_OFF;
        pInstance->Params.EQNB_NBands          = 0;
        pInstance->Params.pEQNB_BandDefinition = LVM_NULL;
        pInstance->EQNB_Active                 = LVM_FALSE;


        /*
         * Set the initialisation capabilities
         */
        EQNB_Capabilities.SampleRate      = LVEQNB_CAP_FS_8000 | LVEQNB_CAP_FS_11025 | LVEQNB_CAP_FS_12000 | LVEQNB_CAP_FS_16000 | LVEQNB_CAP_FS_22050 | LVEQNB_CAP_FS_24000 | LVEQNB_CAP_FS_32000 | LVEQNB_CAP_FS_44100 | LVEQNB_CAP_FS_48000;
        EQNB_Capabilities.MaxBlockSize    = (LVM_UINT16)InternalBlockSize;
        EQNB_Capabilities.MaxBands        = pInstParams->EQNB_NumBands;
        EQNB_Capabilities.SourceFormat    = LVEQNB_CAP_STEREO | LVEQNB_CAP_MONOINSTEREO;
        EQNB_Capabilities.CallBack        = pInstance->CallBack;
        EQNB_Capabilities.pBundleInstance  = (void*)pInstance;


        /*
         * Get the memory requirements and then set the address pointers, forcing alignment
         */
        LVEQNB_Status = LVEQNB_Memory(LVM_NULL,             /* Get the memory requirements */
                                      &EQNB_MemTab,
                                      &EQNB_Capabilities);
        EQNB_MemTab.Region[LVEQNB_MEMREGION_INSTANCE].pBaseAddress        = &pInstance->EQNB_Instance;
        EQNB_MemTab.Region[LVEQNB_MEMREGION_PERSISTENT_DATA].pBaseAddress = (void *)InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_PERSISTENT_FAST_DATA],
                                                                                                        EQNB_MemTab.Region[LVEQNB_MEMREGION_PERSISTENT_DATA].Size);
        EQNB_MemTab.Region[LVEQNB_MEMREGION_PERSISTENT_COEF].pBaseAddress = (void *)InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_PERSISTENT_FAST_COEF],
                                                                                                        EQNB_MemTab.Region[LVEQNB_MEMREGION_PERSISTENT_COEF].Size);
        EQNB_MemTab.Region[LVEQNB_MEMREGION_SCRATCH].pBaseAddress         = (void *)InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_TEMPORARY_FAST],
                                                                                                        0);


        /*
         * Initialise the Dynamic Bass Enhancement instance and save the instance handle
         */
        hEQNBInstance = LVM_NULL;                           /* Set to NULL to return handle */
        LVEQNB_Status = LVEQNB_Init(&hEQNBInstance,         /* Initiailse */
                                    &EQNB_MemTab,
                                    &EQNB_Capabilities);
        if (LVEQNB_Status != LVEQNB_SUCCESS) return((LVM_ReturnStatus_en)LVEQNB_Status);
        pInstance->hEQNBInstance = hEQNBInstance;           /* Save the instance handle */
    }

    /*
     * Headroom management memory allocation
     */
    {
        pInstance->pHeadroom_BandDefs = InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_PERSISTENT_FAST_DATA],
                                                        (LVM_HEADROOM_MAX_NBANDS * sizeof(LVM_HeadroomBandDef_t)));
        pInstance->pHeadroom_UserDefs = InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_PERSISTENT_FAST_DATA],
                                                       (LVM_HEADROOM_MAX_NBANDS * sizeof(LVM_HeadroomBandDef_t)));

        /* Headroom management parameters initialisation */
        pInstance->NewHeadroomParams.NHeadroomBands = 2;
        pInstance->NewHeadroomParams.pHeadroomDefinition = pInstance->pHeadroom_BandDefs;
        pInstance->NewHeadroomParams.pHeadroomDefinition[0].Limit_Low          = 20;
        pInstance->NewHeadroomParams.pHeadroomDefinition[0].Limit_High         = 4999;
        pInstance->NewHeadroomParams.pHeadroomDefinition[0].Headroom_Offset    = 3;
        pInstance->NewHeadroomParams.pHeadroomDefinition[1].Limit_Low          = 5000;
        pInstance->NewHeadroomParams.pHeadroomDefinition[1].Limit_High         = 24000;
        pInstance->NewHeadroomParams.pHeadroomDefinition[1].Headroom_Offset    = 4;
        pInstance->NewHeadroomParams.Headroom_OperatingMode = LVM_HEADROOM_ON;

        pInstance->Headroom =0;
    }


    /*
     * Initialise the PSA module
     */
    {
        pLVPSA_Handle_t     hPSAInstance = LVM_NULL;   /* Instance handle */
        LVPSA_MemTab_t      PSA_MemTab;
        LVPSA_RETURN        PSA_Status;                 /* Function call status */
        LVPSA_FilterParam_t FiltersParams[9];

        if(pInstParams->PSA_Included==LVM_PSA_ON)
        {
            pInstance->PSA_InitParams.SpectralDataBufferDuration   = (LVM_UINT16) 500;
            pInstance->PSA_InitParams.MaxInputBlockSize            = (LVM_UINT16) 2048;
            pInstance->PSA_InitParams.nBands                       = (LVM_UINT16) 9;
            pInstance->PSA_InitParams.pFiltersParams               = &FiltersParams[0];
            for(i = 0; i < pInstance->PSA_InitParams.nBands; i++)
            {
                FiltersParams[i].CenterFrequency    = (LVM_UINT16) 1000;
                FiltersParams[i].QFactor            = (LVM_UINT16) 100;
                FiltersParams[i].PostGain           = (LVM_INT16)  0;
            }

            /*Get the memory requirements and then set the address pointers*/
            PSA_Status = LVPSA_Memory (hPSAInstance,
                                          &PSA_MemTab,
                                          &pInstance->PSA_InitParams);

            if (PSA_Status != LVPSA_OK)
            {
                return((LVM_ReturnStatus_en) LVM_ALGORITHMPSA);
            }

            /* Slow Data */
            PSA_MemTab.Region[LVM_PERSISTENT_SLOW_DATA].pBaseAddress = (void *)InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_PERSISTENT_SLOW_DATA],
                PSA_MemTab.Region[LVM_PERSISTENT_SLOW_DATA].Size);


            /* Fast Data */
            PSA_MemTab.Region[LVM_PERSISTENT_FAST_DATA].pBaseAddress = (void *)InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_PERSISTENT_FAST_DATA],
                PSA_MemTab.Region[LVM_PERSISTENT_FAST_DATA].Size);


            /* Fast Coef */
            PSA_MemTab.Region[LVM_PERSISTENT_FAST_COEF].pBaseAddress = (void *)InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_PERSISTENT_FAST_COEF],
                PSA_MemTab.Region[LVM_PERSISTENT_FAST_COEF].Size);

            /* Fast Temporary */
            pInstance->pPSAInput = InstAlloc_AddMember(&AllocMem[LVM_TEMPORARY_FAST],
                                                                     (LVM_UINT32) MAX_INTERNAL_BLOCKSIZE * sizeof(LVM_INT16));

            PSA_MemTab.Region[LVM_TEMPORARY_FAST].pBaseAddress       = (void *)InstAlloc_AddMember(&AllocMem[LVM_MEMREGION_TEMPORARY_FAST],0);


            /*Initialise PSA instance and save the instance handle*/
            pInstance->PSA_ControlParams.Fs = LVM_FS_48000;
            pInstance->PSA_ControlParams.LevelDetectionSpeed  = LVPSA_SPEED_MEDIUM;
            PSA_Status = LVPSA_Init (&hPSAInstance,
                                    &pInstance->PSA_InitParams,
                                    &pInstance->PSA_ControlParams,
                                    &PSA_MemTab);

            if (PSA_Status != LVPSA_OK)
            {
                return((LVM_ReturnStatus_en) LVM_ALGORITHMPSA);
            }

            pInstance->hPSAInstance = hPSAInstance;       /* Save the instance handle */
            pInstance->PSA_GainOffset = 0;
        }
        else
        {
            pInstance->hPSAInstance = LVM_NULL;
        }

        /*
         * Set the initialisation parameters.
         */
        pInstance->Params.PSA_PeakDecayRate   = LVM_PSA_SPEED_MEDIUM;
        pInstance->Params.PSA_Enable          = LVM_PSA_OFF;
    }

    /*
     * Copy the initial parameters to the new parameters for correct readback of
     * the settings.
     */
    pInstance->NewParams = pInstance->Params;


    /*
     * Create configuration number
     */
    pInstance->ConfigurationNumber = 0x00000000;
    pInstance->ConfigurationNumber += LVM_CS_MASK;
    pInstance->ConfigurationNumber += LVM_EQNB_MASK;
    pInstance->ConfigurationNumber += LVM_DBE_MASK;
    pInstance->ConfigurationNumber += LVM_VC_MASK;
    pInstance->ConfigurationNumber += LVM_PSA_MASK;

    if(((pInstance->ConfigurationNumber  & LVM_CS_MASK)!=0)  ||
        ((pInstance->ConfigurationNumber & LVM_DBE_MASK)!=0) ||
        ((pInstance->ConfigurationNumber & LVM_EQNB_MASK)!=0)||
        ((pInstance->ConfigurationNumber & LVM_TE_MASK)!=0)  ||
        ((pInstance->ConfigurationNumber & LVM_VC_MASK)!=0))
    {
        pInstance->BlickSizeMultiple    = 4;
    }
    else
    {
        pInstance->BlickSizeMultiple    = 1;
    }

    return(Status);
}


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVM_ClearAudioBuffers                                       */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  This function is used to clear the internal audio buffers of the bundle.            */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance               Instance handle                                             */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVM_SUCCESS             Initialisation succeeded                                    */
/*  LVM_NULLADDRESS         Instance or scratch memory has a NULL pointer               */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1. This function must not be interrupted by the LVM_Process function                */
/*                                                                                      */
/****************************************************************************************/

LVM_ReturnStatus_en LVM_ClearAudioBuffers(LVM_Handle_t  hInstance)
{
    LVM_MemTab_t            MemTab;                                     /* Memory table */
    LVM_InstParams_t        InstParams;                                 /* Instance parameters */
    LVM_ControlParams_t     Params;                                     /* Control Parameters */
    LVM_Instance_t          *pInstance  = (LVM_Instance_t  *)hInstance; /* Pointer to Instance */


    if(hInstance == LVM_NULL){
        return LVM_NULLADDRESS;
    }

    /* Save the control parameters */ /* coverity[unchecked_value] */ /* Do not check return value internal function calls */
    LVM_GetControlParameters(hInstance, &Params);

    /*  Retrieve allocated buffers in memtab */
    LVM_GetMemoryTable(hInstance, &MemTab,  LVM_NULL);

    /*  Save the instance parameters */
    InstParams = pInstance->InstParams;

    /*  Call  LVM_GetInstanceHandle to re-initialise the bundle */
    LVM_GetInstanceHandle( &hInstance,
                           &MemTab,
                           &InstParams);

    /* Restore control parameters */ /* coverity[unchecked_value] */ /* Do not check return value internal function calls */
    LVM_SetControlParameters(hInstance, &Params);

    /* DC removal filter */
    DC_2I_D16_TRC_WRA_01_Init(&pInstance->DC_RemovalInstance);


    return LVM_SUCCESS;
}



