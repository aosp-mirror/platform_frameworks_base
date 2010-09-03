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
#include "LVREV_Private.h"
#include "InstAlloc.h"


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVREV_GetInstanceHandle                                     */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  This function is used to create a LVREV module instance. It returns the created     */
/*  instance handle through phInstance. All parameters are set to their default,        */
/*  inactive state.                                                                     */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  phInstance              pointer to the instance handle                              */
/*  pMemoryTable            Pointer to the memory definition table                      */
/*  pInstanceParams         Pointer to the instance parameters                          */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVREV_SUCCESS           Succeeded                                                   */
/*  LVREV_NULLADDRESS       When phInstance or pMemoryTable or pInstanceParams is NULL  */
/*  LVREV_NULLADDRESS       When one of the memory regions has a NULL pointer           */
/*                                                                                      */
/* NOTES:                                                                               */
/*                                                                                      */
/****************************************************************************************/
LVREV_ReturnStatus_en LVREV_GetInstanceHandle(LVREV_Handle_t            *phInstance,
                                              LVREV_MemoryTable_st      *pMemoryTable,
                                              LVREV_InstanceParams_st   *pInstanceParams)
{

    INST_ALLOC              SlowData;
    INST_ALLOC              FastData;
    INST_ALLOC              FastCoef;
    INST_ALLOC              Temporary;
    LVREV_Instance_st       *pLVREV_Private;
    LVM_INT16               i;
    LVM_UINT16              MaxBlockSize;


    /*
     * Check for error conditions
     */
    /* Check for NULL pointers */
    if((phInstance == LVM_NULL) || (pMemoryTable == LVM_NULL) || (pInstanceParams == LVM_NULL))
    {
        return LVREV_NULLADDRESS;
    }
    /* Check the memory table for NULL pointers */
    for (i = 0; i < LVREV_NR_MEMORY_REGIONS; i++)
    {
        if (pMemoryTable->Region[i].Size!=0)
        {
            if (pMemoryTable->Region[i].pBaseAddress==LVM_NULL)
            {
                return(LVREV_NULLADDRESS);
            }
        }
    }

    /*
     * Check all instance parameters are in range
     */
    /* Check for a non-zero block size */
    if (pInstanceParams->MaxBlockSize == 0)
    {
        return LVREV_OUTOFRANGE;
    }

    /* Check for a valid number of delay lines */
    if ((pInstanceParams->NumDelays != LVREV_DELAYLINES_1)&&
        (pInstanceParams->NumDelays != LVREV_DELAYLINES_2)&&
        (pInstanceParams->NumDelays != LVREV_DELAYLINES_4))
    {
        return LVREV_OUTOFRANGE;
    }

    /*
     * Initialise the InstAlloc instances
     */
    InstAlloc_Init(&SlowData,  pMemoryTable->Region[LVM_PERSISTENT_SLOW_DATA].pBaseAddress);
    InstAlloc_Init(&FastData,  pMemoryTable->Region[LVM_PERSISTENT_FAST_DATA].pBaseAddress);
    InstAlloc_Init(&FastCoef,  pMemoryTable->Region[LVM_PERSISTENT_FAST_COEF].pBaseAddress);
    InstAlloc_Init(&Temporary, pMemoryTable->Region[LVM_TEMPORARY_FAST].pBaseAddress);

    /*
     * Zero all memory regions
     */
     LoadConst_16(0, (LVM_INT16 *)pMemoryTable->Region[LVM_PERSISTENT_SLOW_DATA].pBaseAddress, (LVM_INT16)((pMemoryTable->Region[LVM_PERSISTENT_SLOW_DATA].Size)/sizeof(LVM_INT16)));
     LoadConst_16(0, (LVM_INT16 *)pMemoryTable->Region[LVM_PERSISTENT_FAST_DATA].pBaseAddress, (LVM_INT16)((pMemoryTable->Region[LVM_PERSISTENT_FAST_DATA].Size)/sizeof(LVM_INT16)));
     LoadConst_16(0, (LVM_INT16 *)pMemoryTable->Region[LVM_PERSISTENT_FAST_COEF].pBaseAddress, (LVM_INT16)((pMemoryTable->Region[LVM_PERSISTENT_FAST_COEF].Size)/sizeof(LVM_INT16)));
     LoadConst_16(0, (LVM_INT16 *)pMemoryTable->Region[LVM_TEMPORARY_FAST].pBaseAddress, (LVM_INT16)((pMemoryTable->Region[LVM_TEMPORARY_FAST].Size)/sizeof(LVM_INT16)));

    /*
     * Set the instance handle if not already initialised
     */
    if (*phInstance == LVM_NULL)
    {
        *phInstance = InstAlloc_AddMember(&SlowData, sizeof(LVREV_Instance_st));
    }
    pLVREV_Private              =(LVREV_Instance_st *)*phInstance;
    pLVREV_Private->MemoryTable = *pMemoryTable;

    if(pInstanceParams->NumDelays ==LVREV_DELAYLINES_4)
    {
        MaxBlockSize = LVREV_MAX_AP3_DELAY;
    }
    else if(pInstanceParams->NumDelays ==LVREV_DELAYLINES_2)
    {
        MaxBlockSize = LVREV_MAX_AP1_DELAY;
    }
    else
    {
        MaxBlockSize = LVREV_MAX_AP0_DELAY;
    }

    if(MaxBlockSize>pInstanceParams->MaxBlockSize)
    {
        MaxBlockSize=pInstanceParams->MaxBlockSize;
    }


    /*
     * Set the data, coefficient and temporary memory pointers
     */
    pLVREV_Private->pFastData = InstAlloc_AddMember(&FastData, sizeof(LVREV_FastData_st));                              /* Fast data memory base address */

    if(pInstanceParams->NumDelays == LVREV_DELAYLINES_4)
    {
        pLVREV_Private->pDelay_T[3]     = InstAlloc_AddMember(&FastData, LVREV_MAX_T3_DELAY  * sizeof(LVM_INT32));
        pLVREV_Private->pDelay_T[2]     = InstAlloc_AddMember(&FastData, LVREV_MAX_T2_DELAY  * sizeof(LVM_INT32));
        pLVREV_Private->pDelay_T[1]     = InstAlloc_AddMember(&FastData, LVREV_MAX_T1_DELAY * sizeof(LVM_INT32));
        pLVREV_Private->pDelay_T[0]     = InstAlloc_AddMember(&FastData, LVREV_MAX_T0_DELAY * sizeof(LVM_INT32));

        for( i = 0; i < 4; i++)
        {
            pLVREV_Private->pScratchDelayLine[i] = InstAlloc_AddMember(&Temporary, sizeof(LVM_INT32) * MaxBlockSize);       /* Scratch for each delay line output */
        }

        LoadConst_32(0,pLVREV_Private->pDelay_T[3]  ,(LVM_INT16)LVREV_MAX_T3_DELAY);
        LoadConst_32(0,pLVREV_Private->pDelay_T[2]  ,(LVM_INT16)LVREV_MAX_T2_DELAY);
        LoadConst_32(0,pLVREV_Private->pDelay_T[1]  ,(LVM_INT16)LVREV_MAX_T1_DELAY);
        LoadConst_32(0,pLVREV_Private->pDelay_T[0]  ,(LVM_INT16)LVREV_MAX_T0_DELAY);
    }

    if(pInstanceParams->NumDelays == LVREV_DELAYLINES_2)
    {
        pLVREV_Private->pDelay_T[1]  = InstAlloc_AddMember(&FastData, LVREV_MAX_T1_DELAY * sizeof(LVM_INT32));
        pLVREV_Private->pDelay_T[0]  = InstAlloc_AddMember(&FastData, LVREV_MAX_T0_DELAY * sizeof(LVM_INT32));

        for( i = 0; i < 2; i++)
        {
            pLVREV_Private->pScratchDelayLine[i] = InstAlloc_AddMember(&Temporary, sizeof(LVM_INT32) * MaxBlockSize);       /* Scratch for each delay line output */
        }

        LoadConst_32(0,pLVREV_Private->pDelay_T[1] , (LVM_INT16)LVREV_MAX_T1_DELAY);
        LoadConst_32(0,pLVREV_Private->pDelay_T[0] , (LVM_INT16)LVREV_MAX_T0_DELAY);
    }

    if(pInstanceParams->NumDelays == LVREV_DELAYLINES_1)
    {
        pLVREV_Private->pDelay_T[0]  = InstAlloc_AddMember(&FastData, LVREV_MAX_T0_DELAY * sizeof(LVM_INT32));

        for( i = 0; i < 1; i++)
        {
            pLVREV_Private->pScratchDelayLine[i] = InstAlloc_AddMember(&Temporary, sizeof(LVM_INT32) * MaxBlockSize);       /* Scratch for each delay line output */
        }

        LoadConst_32(0,pLVREV_Private->pDelay_T[0]  , (LVM_INT16)LVREV_MAX_T0_DELAY);
    }

    /* All-pass delay buffer addresses and sizes */
    pLVREV_Private->T[0]         = LVREV_MAX_T0_DELAY;
    pLVREV_Private->T[1]         = LVREV_MAX_T1_DELAY;
    pLVREV_Private->T[2]         = LVREV_MAX_T2_DELAY;
    pLVREV_Private->T[3]         = LVREV_MAX_T3_DELAY;
    pLVREV_Private->AB_Selection = 1;       /* Select smoothing A to B */


    pLVREV_Private->pFastCoef       = InstAlloc_AddMember(&FastCoef, sizeof(LVREV_FastCoef_st));                        /* Fast coefficient memory base address */
    pLVREV_Private->pScratch        = InstAlloc_AddMember(&Temporary, sizeof(LVM_INT32) * MaxBlockSize);                /* General purpose scratch */
    pLVREV_Private->pInputSave      = InstAlloc_AddMember(&Temporary, 2 * sizeof(LVM_INT32) * MaxBlockSize);            /* Mono->stereo input save for end mix */
    LoadConst_32(0, pLVREV_Private->pInputSave, (LVM_INT16)(MaxBlockSize*2));


    /*
     * Save the instance parameters in the instance structure
     */
    pLVREV_Private->InstanceParams = *pInstanceParams;


    /*
     * Set the parameters to invalid
     */
    pLVREV_Private->CurrentParams.SampleRate    = LVM_FS_INVALID;
    pLVREV_Private->CurrentParams.OperatingMode = LVM_MODE_DUMMY;
    pLVREV_Private->CurrentParams.SourceFormat  = LVM_SOURCE_DUMMY;

    pLVREV_Private->bControlPending             = LVM_FALSE;
    pLVREV_Private->bFirstControl               = LVM_TRUE;
    pLVREV_Private->bDisableReverb              = LVM_FALSE;


    /*
     * Set mixer parameters
     */
    pLVREV_Private->BypassMixer.CallbackParam2      = 0;
    pLVREV_Private->BypassMixer.pCallbackHandle2    = pLVREV_Private;
    pLVREV_Private->BypassMixer.pGeneralPurpose2    = LVM_NULL;
    pLVREV_Private->BypassMixer.pCallBack2          = BypassMixer_Callback;
    pLVREV_Private->BypassMixer.CallbackSet2        = LVM_FALSE;
    pLVREV_Private->BypassMixer.Current2            = 0;
    pLVREV_Private->BypassMixer.Target2             = 0;
    pLVREV_Private->BypassMixer.CallbackParam1      = 0;
    pLVREV_Private->BypassMixer.pCallbackHandle1    = LVM_NULL;
    pLVREV_Private->BypassMixer.pGeneralPurpose1    = LVM_NULL;
    pLVREV_Private->BypassMixer.pCallBack1          = LVM_NULL;
    pLVREV_Private->BypassMixer.CallbackSet1        = LVM_FALSE;
    pLVREV_Private->BypassMixer.Current1            = 0x00000000;
    pLVREV_Private->BypassMixer.Target1             = 0x00000000;

    pLVREV_Private->RoomSizeInms                    = 100;  // 100 msec


    /*
     *  Set the output gain mixer parameters
     */
    pLVREV_Private->GainMixer.CallbackParam      = 0;
    pLVREV_Private->GainMixer.pCallbackHandle    = LVM_NULL;
    pLVREV_Private->GainMixer.pGeneralPurpose    = LVM_NULL;
    pLVREV_Private->GainMixer.pCallBack          = LVM_NULL;
    pLVREV_Private->GainMixer.CallbackSet        = LVM_FALSE;
    pLVREV_Private->GainMixer.Current            = 0x03ffffff;
    pLVREV_Private->GainMixer.Target             = 0x03ffffff;


    /*
     * Set the All-Pass Filter mixers
     */
    for (i=0; i<4; i++)
    {
        pLVREV_Private->pOffsetA[i] = pLVREV_Private->pDelay_T[i];
        pLVREV_Private->pOffsetB[i] = pLVREV_Private->pDelay_T[i];
        /* Delay tap selection mixer */
        pLVREV_Private->Mixer_APTaps[i].CallbackParam2   = 0;
        pLVREV_Private->Mixer_APTaps[i].pCallbackHandle2 = LVM_NULL;
        pLVREV_Private->Mixer_APTaps[i].pGeneralPurpose2 = LVM_NULL;
        pLVREV_Private->Mixer_APTaps[i].pCallBack2       = LVM_NULL;
        pLVREV_Private->Mixer_APTaps[i].CallbackSet2     = LVM_FALSE;
        pLVREV_Private->Mixer_APTaps[i].Current2         = 0;
        pLVREV_Private->Mixer_APTaps[i].Target2          = 0;
        pLVREV_Private->Mixer_APTaps[i].CallbackParam1   = 0;
        pLVREV_Private->Mixer_APTaps[i].pCallbackHandle1 = LVM_NULL;
        pLVREV_Private->Mixer_APTaps[i].pGeneralPurpose1 = LVM_NULL;
        pLVREV_Private->Mixer_APTaps[i].pCallBack1       = LVM_NULL;
        pLVREV_Private->Mixer_APTaps[i].CallbackSet1     = LVM_FALSE;
        pLVREV_Private->Mixer_APTaps[i].Current1         = 0;
        pLVREV_Private->Mixer_APTaps[i].Target1          = 0x7fffffff;
        /* Feedforward mixer */
        pLVREV_Private->Mixer_SGFeedforward[i].CallbackParam   = 0;
        pLVREV_Private->Mixer_SGFeedforward[i].pCallbackHandle = LVM_NULL;
        pLVREV_Private->Mixer_SGFeedforward[i].pGeneralPurpose = LVM_NULL;
        pLVREV_Private->Mixer_SGFeedforward[i].pCallBack       = LVM_NULL;
        pLVREV_Private->Mixer_SGFeedforward[i].CallbackSet     = LVM_FALSE;
        pLVREV_Private->Mixer_SGFeedforward[i].Current         = 0;
        pLVREV_Private->Mixer_SGFeedforward[i].Target          = 0;
        /* Feedback mixer */
        pLVREV_Private->Mixer_SGFeedback[i].CallbackParam   = 0;
        pLVREV_Private->Mixer_SGFeedback[i].pCallbackHandle = LVM_NULL;
        pLVREV_Private->Mixer_SGFeedback[i].pGeneralPurpose = LVM_NULL;
        pLVREV_Private->Mixer_SGFeedback[i].pCallBack       = LVM_NULL;
        pLVREV_Private->Mixer_SGFeedback[i].CallbackSet     = LVM_FALSE;
        pLVREV_Private->Mixer_SGFeedback[i].Current         = 0;
        pLVREV_Private->Mixer_SGFeedback[i].Target          = 0;
        /* Feedback gain mixer */
        pLVREV_Private->FeedbackMixer[i].CallbackParam    = 0;
        pLVREV_Private->FeedbackMixer[i].pCallbackHandle  = LVM_NULL;
        pLVREV_Private->FeedbackMixer[i].pGeneralPurpose  = LVM_NULL;
        pLVREV_Private->FeedbackMixer[i].pCallBack        = LVM_NULL;
        pLVREV_Private->FeedbackMixer[i].CallbackSet      = LVM_FALSE;
        pLVREV_Private->FeedbackMixer[i].Current          = 0;
        pLVREV_Private->FeedbackMixer[i].Target           = 0;
    }
    /* Delay tap index */
    pLVREV_Private->A_DelaySize[0] = LVREV_MAX_AP0_DELAY;
    pLVREV_Private->B_DelaySize[0] = LVREV_MAX_AP0_DELAY;
    pLVREV_Private->A_DelaySize[1] = LVREV_MAX_AP1_DELAY;
    pLVREV_Private->B_DelaySize[1] = LVREV_MAX_AP1_DELAY;
    pLVREV_Private->A_DelaySize[2] = LVREV_MAX_AP2_DELAY;
    pLVREV_Private->B_DelaySize[2] = LVREV_MAX_AP2_DELAY;
    pLVREV_Private->A_DelaySize[3] = LVREV_MAX_AP3_DELAY;
    pLVREV_Private->B_DelaySize[3] = LVREV_MAX_AP3_DELAY;


    LVREV_ClearAudioBuffers(*phInstance);

    return LVREV_SUCCESS;
}

/* End of file */
