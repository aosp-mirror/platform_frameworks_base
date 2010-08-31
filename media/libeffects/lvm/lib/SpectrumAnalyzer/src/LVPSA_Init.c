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

#include    "LVPSA.h"
#include    "LVPSA_Private.h"
#include    "InstAlloc.h"

/************************************************************************************/
/*                                                                                  */
/* FUNCTION:            LVPSA_Init                                                  */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  Initialize the LVPSA module                                                     */
/*                                                                                  */
/*                                                                                  */
/* PARAMETERS:                                                                      */
/*  phInstance          Pointer to pointer to the instance                          */
/*  InitParams          Init parameters structure                                   */
/*  ControlParams       Control parameters structure                                */
/*  pMemoryTable        Memory table that contains memory areas definition          */
/*                                                                                  */
/*                                                                                  */
/* RETURNS:                                                                         */
/*  LVPSA_OK            Succeeds                                                    */
/*  otherwise           Error due to bad parameters                                 */
/*                                                                                  */
/************************************************************************************/
LVPSA_RETURN LVPSA_Init              ( pLVPSA_Handle_t             *phInstance,
                                       LVPSA_InitParams_t          *pInitParams,
                                       LVPSA_ControlParams_t       *pControlParams,
                                       LVPSA_MemTab_t              *pMemoryTable )
{
    LVPSA_InstancePr_t          *pLVPSA_Inst;
    LVPSA_RETURN                errorCode       = LVPSA_OK;
    LVM_UINT32                  ii;
    extern LVM_INT16            LVPSA_GainTable[];
    LVM_UINT32                  BufferLength = 0;

    /* Ints_Alloc instances, needed for memory alignment management */
    INST_ALLOC          Instance;
    INST_ALLOC          Scratch;
    INST_ALLOC          Data;
    INST_ALLOC          Coef;

    /* Check parameters */
    if((phInstance == LVM_NULL) || (pInitParams == LVM_NULL) || (pControlParams == LVM_NULL) || (pMemoryTable == LVM_NULL))
    {
        return(LVPSA_ERROR_NULLADDRESS);
    }
    if( (pInitParams->SpectralDataBufferDuration > LVPSA_MAXBUFFERDURATION)   ||
        (pInitParams->SpectralDataBufferDuration == 0)                        ||
        (pInitParams->MaxInputBlockSize > LVPSA_MAXINPUTBLOCKSIZE)      ||
        (pInitParams->MaxInputBlockSize == 0)                           ||
        (pInitParams->nBands < LVPSA_NBANDSMIN)                         ||
        (pInitParams->nBands > LVPSA_NBANDSMAX)                         ||
        (pInitParams->pFiltersParams == 0))
    {
        return(LVPSA_ERROR_INVALIDPARAM);
    }
    for(ii = 0; ii < pInitParams->nBands; ii++)
    {
        if((pInitParams->pFiltersParams[ii].CenterFrequency > LVPSA_MAXCENTERFREQ) ||
           (pInitParams->pFiltersParams[ii].PostGain        > LVPSA_MAXPOSTGAIN)   ||
           (pInitParams->pFiltersParams[ii].PostGain        < LVPSA_MINPOSTGAIN)   ||
           (pInitParams->pFiltersParams[ii].QFactor < LVPSA_MINQFACTOR)            ||
           (pInitParams->pFiltersParams[ii].QFactor > LVPSA_MAXQFACTOR))
           {
                return(LVPSA_ERROR_INVALIDPARAM);
           }
    }


    /*Inst_Alloc instances initialization */
    InstAlloc_Init( &Instance   , pMemoryTable->Region[LVPSA_MEMREGION_INSTANCE].pBaseAddress);
    InstAlloc_Init( &Scratch    , pMemoryTable->Region[LVPSA_MEMREGION_SCRATCH].pBaseAddress);
    InstAlloc_Init( &Data       , pMemoryTable->Region[LVPSA_MEMREGION_PERSISTENT_DATA].pBaseAddress);
    InstAlloc_Init( &Coef       , pMemoryTable->Region[LVPSA_MEMREGION_PERSISTENT_COEF].pBaseAddress);


    /* Set the instance handle if not already initialised */
    if (*phInstance == LVM_NULL)
    {
        *phInstance = InstAlloc_AddMember( &Instance, sizeof(LVPSA_InstancePr_t) );
    }
    pLVPSA_Inst =(LVPSA_InstancePr_t*)*phInstance;


    /* Check the memory table for NULL pointers */
    for (ii = 0; ii < LVPSA_NR_MEMORY_REGIONS; ii++)
    {
        if (pMemoryTable->Region[ii].Size!=0)
        {
            if (pMemoryTable->Region[ii].pBaseAddress==LVM_NULL)
            {
                return(LVPSA_ERROR_NULLADDRESS);
            }
            pLVPSA_Inst->MemoryTable.Region[ii] = pMemoryTable->Region[ii];
        }
    }

    /* Initialize module's internal parameters */
    pLVPSA_Inst->bControlPending = LVM_FALSE;
    pLVPSA_Inst->nBands = pInitParams->nBands;
    pLVPSA_Inst->MaxInputBlockSize = pInitParams->MaxInputBlockSize;
    pLVPSA_Inst->SpectralDataBufferDuration = pInitParams->SpectralDataBufferDuration;
    pLVPSA_Inst->CurrentParams.Fs = LVM_FS_DUMMY;
    pLVPSA_Inst->CurrentParams.LevelDetectionSpeed = LVPSA_SPEED_DUMMY;

    {   /* for avoiding QAC warnings */
        LVM_INT32 SDBD=(LVM_INT32)pLVPSA_Inst->SpectralDataBufferDuration;
        LVM_INT32 IRTI=(LVM_INT32)LVPSA_InternalRefreshTimeInv;
        LVM_INT32 BL;

        MUL32x32INTO32(SDBD,IRTI,BL,LVPSA_InternalRefreshTimeShift)

        BufferLength=(LVM_UINT32)BL;
    }

    if((BufferLength * LVPSA_InternalRefreshTime) != pLVPSA_Inst->SpectralDataBufferDuration)
    {
        pLVPSA_Inst->SpectralDataBufferLength = BufferLength + 1;
    }
    else
    {
        pLVPSA_Inst->SpectralDataBufferLength = BufferLength;
    }


    /* Assign the pointers */

    pLVPSA_Inst->pPostGains                 = InstAlloc_AddMember( &Instance, pInitParams->nBands * sizeof(LVM_UINT16) );
    pLVPSA_Inst->pFiltersParams             = InstAlloc_AddMember( &Instance, pInitParams->nBands * sizeof(LVPSA_FilterParam_t) );
    pLVPSA_Inst->pSpectralDataBufferStart   = InstAlloc_AddMember( &Instance, pInitParams->nBands * pLVPSA_Inst->SpectralDataBufferLength * sizeof(LVM_UINT8) );
    pLVPSA_Inst->pPreviousPeaks             = InstAlloc_AddMember( &Instance, pInitParams->nBands * sizeof(LVM_UINT8) );
    pLVPSA_Inst->pBPFiltersPrecision        = InstAlloc_AddMember( &Instance, pInitParams->nBands * sizeof(LVPSA_BPFilterPrecision_en) );

    pLVPSA_Inst->pBP_Instances          = InstAlloc_AddMember( &Coef, pInitParams->nBands * sizeof(Biquad_Instance_t) );
    pLVPSA_Inst->pQPD_States            = InstAlloc_AddMember( &Coef, pInitParams->nBands * sizeof(QPD_State_t) );

    pLVPSA_Inst->pBP_Taps               = InstAlloc_AddMember( &Data, pInitParams->nBands * sizeof(Biquad_1I_Order2_Taps_t) );
    pLVPSA_Inst->pQPD_Taps              = InstAlloc_AddMember( &Data, pInitParams->nBands * sizeof(QPD_Taps_t) );


    /* Copy filters parameters in the private instance */
    for(ii = 0; ii < pLVPSA_Inst->nBands; ii++)
    {
        pLVPSA_Inst->pFiltersParams[ii] = pInitParams->pFiltersParams[ii];
    }

    /* Set Post filters gains*/
    for(ii = 0; ii < pLVPSA_Inst->nBands; ii++)
    {
        pLVPSA_Inst->pPostGains[ii] =(LVM_UINT16) LVPSA_GainTable[pInitParams->pFiltersParams[ii].PostGain + 15];
    }
    pLVPSA_Inst->pSpectralDataBufferWritePointer = pLVPSA_Inst->pSpectralDataBufferStart;


    /* Initialize control dependant internal parameters */
    errorCode = LVPSA_Control (*phInstance, pControlParams);

    if(errorCode!=0)
    {
        return errorCode;
    }

    errorCode = LVPSA_ApplyNewSettings (pLVPSA_Inst);

    if(errorCode!=0)
    {
        return errorCode;
    }

    return(errorCode);
}

