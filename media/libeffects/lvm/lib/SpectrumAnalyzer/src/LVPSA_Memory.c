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

/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVEQNB_Memory                                               */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  This function is used for memory allocation and free. It can be called in           */
/*  two ways:                                                                           */
/*                                                                                      */
/*      hInstance = NULL         Returns the memory requirements                        */
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
/*  InitParams              Pointer to the instance init parameters                     */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVPSA_OK            Succeeds                                                        */
/*  otherwise           Error due to bad parameters                                     */
/*                                                                                      */
/****************************************************************************************/
LVPSA_RETURN LVPSA_Memory            ( pLVPSA_Handle_t             hInstance,
                                       LVPSA_MemTab_t             *pMemoryTable,
                                       LVPSA_InitParams_t         *pInitParams    )
{
    LVM_UINT32          ii;
    LVM_UINT32          BufferLength;
    INST_ALLOC          Instance;
    INST_ALLOC          Scratch;
    INST_ALLOC          Data;
    INST_ALLOC          Coef;
    LVPSA_InstancePr_t *pLVPSA_Inst = (LVPSA_InstancePr_t*)hInstance;


    InstAlloc_Init( &Instance   , LVM_NULL);
    InstAlloc_Init( &Scratch    , LVM_NULL);
    InstAlloc_Init( &Data       , LVM_NULL);
    InstAlloc_Init( &Coef       , LVM_NULL);


    if((pMemoryTable == LVM_NULL) || (pInitParams == LVM_NULL))
    {
        return(LVPSA_ERROR_NULLADDRESS);
    }


    /*
     * Fill in the memory table
     */
    if (hInstance == LVM_NULL)
    {

        /* Check init parameter */
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

        /*
         * Instance memory
         */

        InstAlloc_AddMember( &Instance, sizeof(LVPSA_InstancePr_t) );
        InstAlloc_AddMember( &Instance, pInitParams->nBands * sizeof(LVM_UINT16) );
        InstAlloc_AddMember( &Instance, pInitParams->nBands * sizeof(LVPSA_FilterParam_t) );

        {
            /* for avoiding QAC warnings as MUL32x32INTO32 works on LVM_INT32 only*/
            LVM_INT32 SDBD=(LVM_INT32)pInitParams->SpectralDataBufferDuration;
            LVM_INT32 IRTI=(LVM_INT32)LVPSA_InternalRefreshTimeInv;
            LVM_INT32 BL;

            MUL32x32INTO32(SDBD,IRTI,BL,LVPSA_InternalRefreshTimeShift)
            BufferLength=(LVM_UINT32)BL;
        }


        if((BufferLength * LVPSA_InternalRefreshTime) != pInitParams->SpectralDataBufferDuration)
        {
            BufferLength++;
        }
        InstAlloc_AddMember( &Instance, pInitParams->nBands * BufferLength * sizeof(LVM_UINT8) );
        InstAlloc_AddMember( &Instance, pInitParams->nBands * sizeof(LVM_UINT8) );
        InstAlloc_AddMember( &Instance, pInitParams->nBands * sizeof(LVPSA_BPFilterPrecision_en) );
        pMemoryTable->Region[LVPSA_MEMREGION_INSTANCE].Size         = InstAlloc_GetTotal(&Instance);
        pMemoryTable->Region[LVPSA_MEMREGION_INSTANCE].Type         = LVPSA_PERSISTENT;
        pMemoryTable->Region[LVPSA_MEMREGION_INSTANCE].pBaseAddress = LVM_NULL;

        /*
         * Scratch memory
         */
        InstAlloc_AddMember( &Scratch, 2 * pInitParams->MaxInputBlockSize * sizeof(LVM_INT16) );
        pMemoryTable->Region[LVPSA_MEMREGION_SCRATCH].Size         = InstAlloc_GetTotal(&Scratch);
        pMemoryTable->Region[LVPSA_MEMREGION_SCRATCH].Type         = LVPSA_SCRATCH;
        pMemoryTable->Region[LVPSA_MEMREGION_SCRATCH].pBaseAddress = LVM_NULL;

        /*
         * Persistent coefficients memory
         */
        InstAlloc_AddMember( &Coef, pInitParams->nBands * sizeof(Biquad_Instance_t) );
        InstAlloc_AddMember( &Coef, pInitParams->nBands * sizeof(QPD_State_t) );
        pMemoryTable->Region[LVPSA_MEMREGION_PERSISTENT_COEF].Size         = InstAlloc_GetTotal(&Coef);
        pMemoryTable->Region[LVPSA_MEMREGION_PERSISTENT_COEF].Type         = LVPSA_PERSISTENT_COEF;
        pMemoryTable->Region[LVPSA_MEMREGION_PERSISTENT_COEF].pBaseAddress = LVM_NULL;

        /*
         * Persistent data memory
         */
        InstAlloc_AddMember( &Data, pInitParams->nBands * sizeof(Biquad_1I_Order2_Taps_t) );
        InstAlloc_AddMember( &Data, pInitParams->nBands * sizeof(QPD_Taps_t) );
        pMemoryTable->Region[LVPSA_MEMREGION_PERSISTENT_DATA].Size         = InstAlloc_GetTotal(&Data);
        pMemoryTable->Region[LVPSA_MEMREGION_PERSISTENT_DATA].Type         = LVPSA_PERSISTENT_DATA;
        pMemoryTable->Region[LVPSA_MEMREGION_PERSISTENT_DATA].pBaseAddress = LVM_NULL;

    }
    else
    {
        /* Read back memory allocation table */
        *pMemoryTable = pLVPSA_Inst->MemoryTable;
    }

    return(LVPSA_OK);
}

