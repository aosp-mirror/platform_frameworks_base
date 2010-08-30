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
/* FUNCTION:                LVREV_GetMemoryTable                                        */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  This function is used for memory allocation and free. It can be called in           */
/*  two ways:                                                                           */
/*                                                                                      */
/*  hInstance = NULL                Returns the memory requirements                     */
/*  hInstance = Instance handle     Returns the memory requirements and allocated       */
/*                                  base addresses.                                     */
/*                                                                                      */
/*  When this function is called for memory allocation (hInstance=NULL) the memory      */
/*  base address pointers are NULL on return.                                           */
/*                                                                                      */
/*  When the function is called for free (hInstance = Instance Handle) the memory       */
/*  table returns the allocated memory and base addresses used during initialisation.   */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance               Instance Handle                                             */
/*  pMemoryTable            Pointer to an empty memory table                            */
/*  pInstanceParams         Pointer to the instance parameters                          */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVREV_Success           Succeeded                                                   */
/*  LVREV_NULLADDRESS       When pMemoryTable is NULL                                   */
/*  LVREV_NULLADDRESS       When requesting memory requirements and pInstanceParams     */
/*                          is NULL                                                     */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1.  This function may be interrupted by the LVREV_Process function                  */
/*                                                                                      */
/****************************************************************************************/
LVREV_ReturnStatus_en LVREV_GetMemoryTable(LVREV_Handle_t           hInstance,
                                           LVREV_MemoryTable_st     *pMemoryTable,
                                           LVREV_InstanceParams_st  *pInstanceParams)
{

    INST_ALLOC              SlowData;
    INST_ALLOC              FastData;
    INST_ALLOC              FastCoef;
    INST_ALLOC              Temporary;
    LVM_INT16               i;
    LVM_UINT16              MaxBlockSize;


    /*
     * Check for error conditions
     */
    /* Check for NULL pointer */
    if (pMemoryTable == LVM_NULL)
    {
        return(LVREV_NULLADDRESS);
    }

    /*
     * Check all instance parameters are in range
     */
    if (pInstanceParams != LVM_NULL)
    {
        /*
         * Call for memory allocation, so check the parameters
         */
        /* Check for a non-zero block size */
        if (pInstanceParams->MaxBlockSize == 0)
        {
            return LVREV_OUTOFRANGE;
        }

        /* Check for a valid number of delay lines */
        if ((pInstanceParams->NumDelays != LVREV_DELAYLINES_1) &&
            (pInstanceParams->NumDelays != LVREV_DELAYLINES_2) &&
            (pInstanceParams->NumDelays != LVREV_DELAYLINES_4))
        {
            return LVREV_OUTOFRANGE;
        }
    }

    /*
     * Initialise the InstAlloc instances
     */
    InstAlloc_Init(&SlowData,  (void *)LVM_NULL);
    InstAlloc_Init(&FastData,  (void *)LVM_NULL);
    InstAlloc_Init(&FastCoef,  (void *)LVM_NULL);
    InstAlloc_Init(&Temporary, (void *)LVM_NULL);


    /*
     * Fill in the memory table
     */
    if (hInstance == LVM_NULL)
    {
        /*
         * Check for null pointers
         */
        if (pInstanceParams == LVM_NULL)
        {
            return(LVREV_NULLADDRESS);
        }


        /*
         * Select the maximum internal block size
         */
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
         * Slow data memory
         */
        InstAlloc_AddMember(&SlowData, sizeof(LVREV_Instance_st));
        pMemoryTable->Region[LVM_PERSISTENT_SLOW_DATA].Size         = InstAlloc_GetTotal(&SlowData);
        pMemoryTable->Region[LVM_PERSISTENT_SLOW_DATA].Type         = LVM_PERSISTENT_SLOW_DATA;
        pMemoryTable->Region[LVM_PERSISTENT_SLOW_DATA].pBaseAddress = LVM_NULL;


        /*
         * Persistent fast data memory
         */
        InstAlloc_AddMember(&FastData, sizeof(LVREV_FastData_st));
        if(pInstanceParams->NumDelays == LVREV_DELAYLINES_4)
        {
            InstAlloc_AddMember(&FastData, LVREV_MAX_T3_DELAY  * sizeof(LVM_INT32));
            InstAlloc_AddMember(&FastData, LVREV_MAX_T2_DELAY  * sizeof(LVM_INT32));
            InstAlloc_AddMember(&FastData, LVREV_MAX_T1_DELAY * sizeof(LVM_INT32));
            InstAlloc_AddMember(&FastData, LVREV_MAX_T0_DELAY * sizeof(LVM_INT32));
        }

        if(pInstanceParams->NumDelays == LVREV_DELAYLINES_2)
        {
            InstAlloc_AddMember(&FastData, LVREV_MAX_T1_DELAY * sizeof(LVM_INT32));
            InstAlloc_AddMember(&FastData, LVREV_MAX_T0_DELAY * sizeof(LVM_INT32));
        }

        if(pInstanceParams->NumDelays == LVREV_DELAYLINES_1)
        {
            InstAlloc_AddMember(&FastData, LVREV_MAX_T0_DELAY * sizeof(LVM_INT32));
        }

        pMemoryTable->Region[LVM_PERSISTENT_FAST_DATA].Size         = InstAlloc_GetTotal(&FastData);
        pMemoryTable->Region[LVM_PERSISTENT_FAST_DATA].Type         = LVM_PERSISTENT_FAST_DATA;
        pMemoryTable->Region[LVM_PERSISTENT_FAST_DATA].pBaseAddress = LVM_NULL;


        /*
         * Persistent fast coefficient memory
         */
        InstAlloc_AddMember(&FastCoef, sizeof(LVREV_FastCoef_st));
        pMemoryTable->Region[LVM_PERSISTENT_FAST_COEF].Size         = InstAlloc_GetTotal(&FastCoef);
        pMemoryTable->Region[LVM_PERSISTENT_FAST_COEF].Type         = LVM_PERSISTENT_FAST_COEF;
        pMemoryTable->Region[LVM_PERSISTENT_FAST_COEF].pBaseAddress = LVM_NULL;


        /*
         * Temporary fast memory
         */
        InstAlloc_AddMember(&Temporary, sizeof(LVM_INT32) * MaxBlockSize);          /* General purpose scratch memory */
        InstAlloc_AddMember(&Temporary, 2*sizeof(LVM_INT32) * MaxBlockSize);        /* Mono->stereo input saved for end mix */

        if(pInstanceParams->NumDelays == LVREV_DELAYLINES_4)
        {
            for(i=0; i<4; i++)
            {
                InstAlloc_AddMember(&Temporary, sizeof(LVM_INT32) * MaxBlockSize);      /* A Scratch buffer for each delay line */
            }
        }

        if(pInstanceParams->NumDelays == LVREV_DELAYLINES_2)
        {
            for(i=0; i<2; i++)
            {
                InstAlloc_AddMember(&Temporary, sizeof(LVM_INT32) * MaxBlockSize);      /* A Scratch buffer for each delay line */
            }
        }

        if(pInstanceParams->NumDelays == LVREV_DELAYLINES_1)
        {
            for(i=0; i<1; i++)
            {
                InstAlloc_AddMember(&Temporary, sizeof(LVM_INT32) * MaxBlockSize);      /* A Scratch buffer for each delay line */
            }
        }

        pMemoryTable->Region[LVM_TEMPORARY_FAST].Size         = InstAlloc_GetTotal(&Temporary);
        pMemoryTable->Region[LVM_TEMPORARY_FAST].Type         = LVM_TEMPORARY_FAST;
        pMemoryTable->Region[LVM_TEMPORARY_FAST].pBaseAddress = LVM_NULL;

    }
    else
    {
        LVREV_Instance_st   *pLVREV_Private = (LVREV_Instance_st *)hInstance;


        /*
         * Read back memory allocation table
         */
        *pMemoryTable = pLVREV_Private->MemoryTable;
    }


    return(LVREV_SUCCESS);
}

/* End of file */
