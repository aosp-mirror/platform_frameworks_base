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

#include "InstAlloc.h"

/****************************************************************************************
 *  Name        : InstAlloc_Init()
 *  Input       : pms  - Pointer to the INST_ALLOC instance
                  StartAddr - Base address of the instance memory
 *  Returns     : Error code
 *  Description : Initializes the instance distribution and memory size calculation function
 *  Remarks     :
 ****************************************************************************************/

void    InstAlloc_Init( INST_ALLOC      *pms,
                        void            *StartAddr )
{
    pms->TotalSize = 3;
    pms->pNextMember = (LVM_UINT32)(((LVM_UINT32)StartAddr + 3) & 0xFFFFFFFC);/* This code will fail if the platform address space is more than 32-bits*/
}


/****************************************************************************************
 *  Name        : InstAlloc_AddMember()
 *  Input       : pms  - Pointer to the INST_ALLOC instance
                  Size - The size in bytes of the new added member
 *  Returns     : A pointer to the new added member
 *  Description : Allocates space for a new member in the instance memory and returns
                  a pointer to this new member.  The start address of all members will
                  be 32 bit alligned.
 *  Remarks     :
 ****************************************************************************************/

void*   InstAlloc_AddMember( INST_ALLOC         *pms,
                             LVM_UINT32           Size )
{
    void *NewMemberAddress; /* Variable to temporarily store the return value */
    NewMemberAddress = (void*)pms->pNextMember;

    Size = ((Size + 3) & 0xFFFFFFFC); /* Ceil the size to a multiple of four */

    pms->TotalSize += Size;
    pms->pNextMember += Size;

    return(NewMemberAddress);
}


/****************************************************************************************
 *  Name        : InstAlloc_GetTotal()
 *  Input       : pms  - Pointer to the INST_ALLOC instance
 *  Returns     : The instance memory size
 *  Description : This functions returns the calculated instance memory size
 *  Remarks     :
 ****************************************************************************************/

LVM_UINT32 InstAlloc_GetTotal( INST_ALLOC *pms)
{
    if (pms->TotalSize > 3)
    {
        return(pms->TotalSize);
    }
    else
    {
        return 0;           /* No memory added */
    }
}


void    InstAlloc_InitAll( INST_ALLOC                      *pms,
                           LVM_MemoryTable_st             *pMemoryTable)
{
    LVM_UINT32 StartAddr;

    StartAddr = (LVM_UINT32)pMemoryTable->Region[LVM_PERSISTENT_SLOW_DATA].pBaseAddress;

    pms[0].TotalSize = 3;
    pms[0].pNextMember = (LVM_UINT32)(((LVM_UINT32)StartAddr + 3) & 0xFFFFFFFC);


    StartAddr = (LVM_UINT32)pMemoryTable->Region[LVM_PERSISTENT_FAST_DATA].pBaseAddress;

    pms[1].TotalSize = 3;
    pms[1].pNextMember = (LVM_UINT32)(((LVM_UINT32)StartAddr + 3) & 0xFFFFFFFC);


    StartAddr = (LVM_UINT32)pMemoryTable->Region[LVM_PERSISTENT_FAST_COEF].pBaseAddress;

    pms[2].TotalSize = 3;
    pms[2].pNextMember = (LVM_UINT32)(((LVM_UINT32)StartAddr + 3) & 0xFFFFFFFC);


    StartAddr = (LVM_UINT32)pMemoryTable->Region[LVM_TEMPORARY_FAST].pBaseAddress;

    pms[3].TotalSize = 3;
    pms[3].pNextMember = (LVM_UINT32)(((LVM_UINT32)StartAddr + 3) & 0xFFFFFFFC);

}

/****************************************************************************************
 *  Name        : InstAlloc_InitAll_NULL()
 *  Input       : pms  - Pointer to array of four INST_ALLOC instances
 *  Returns     : Nothing
 *  Description : This function reserves Size of 3 bytes for all memory regions and
 *                intializes pNextMember for all regions to 0
 *  Remarks     :
 ****************************************************************************************/

void    InstAlloc_InitAll_NULL( INST_ALLOC  *pms)
{
    pms[0].TotalSize = 3;
    pms[0].pNextMember = 0;


    pms[1].TotalSize = 3;
    pms[1].pNextMember = 0;

    pms[2].TotalSize = 3;
    pms[2].pNextMember = 0;

    pms[3].TotalSize = 3;
    pms[3].pNextMember = 0;

}


void*   InstAlloc_AddMemberAll( INST_ALLOC                     *pms,
                                 LVM_UINT32                   Size[],
                                 LVM_MemoryTable_st           *pMemoryTable)
{
    void *NewMemberAddress; /* Variable to temporarily store the return value */

    /* coverity[returned_pointer] Ignore coverity warning that ptr is not used */
    NewMemberAddress = InstAlloc_AddMember(&pms[LVM_PERSISTENT_SLOW_DATA], Size[LVM_PERSISTENT_SLOW_DATA]);

    pMemoryTable->Region[LVM_PERSISTENT_SLOW_DATA].Size         = InstAlloc_GetTotal(&pms[LVM_PERSISTENT_SLOW_DATA]);
    pMemoryTable->Region[LVM_PERSISTENT_SLOW_DATA].Type         = LVM_PERSISTENT_SLOW_DATA;
    pMemoryTable->Region[LVM_PERSISTENT_SLOW_DATA].pBaseAddress = LVM_NULL;

    NewMemberAddress = InstAlloc_AddMember(&pms[LVM_PERSISTENT_FAST_DATA], Size[LVM_PERSISTENT_FAST_DATA]);

    pMemoryTable->Region[LVM_PERSISTENT_FAST_DATA].Size         = InstAlloc_GetTotal(&pms[LVM_PERSISTENT_FAST_DATA]);
    pMemoryTable->Region[LVM_PERSISTENT_FAST_DATA].Type         = LVM_PERSISTENT_FAST_DATA;
    pMemoryTable->Region[LVM_PERSISTENT_FAST_DATA].pBaseAddress = LVM_NULL;

    NewMemberAddress = InstAlloc_AddMember(&pms[LVM_PERSISTENT_FAST_COEF], Size[LVM_PERSISTENT_FAST_COEF]);

    pMemoryTable->Region[LVM_PERSISTENT_FAST_COEF].Size         = InstAlloc_GetTotal(&pms[LVM_PERSISTENT_FAST_COEF]);
    pMemoryTable->Region[LVM_PERSISTENT_FAST_COEF].Type         = LVM_PERSISTENT_FAST_COEF;
    pMemoryTable->Region[LVM_PERSISTENT_FAST_COEF].pBaseAddress = LVM_NULL;

    NewMemberAddress = InstAlloc_AddMember(&pms[LVM_TEMPORARY_FAST], Size[LVM_TEMPORARY_FAST]);

    pMemoryTable->Region[LVM_TEMPORARY_FAST].Size                 = InstAlloc_GetTotal(&pms[LVM_TEMPORARY_FAST]);
    pMemoryTable->Region[LVM_TEMPORARY_FAST].Type                 = LVM_TEMPORARY_FAST;
    pMemoryTable->Region[LVM_TEMPORARY_FAST].pBaseAddress         = LVM_NULL;

    return(NewMemberAddress);
}


void*   InstAlloc_AddMemberAllRet(     INST_ALLOC                 *pms,
                                     LVM_UINT32               Size[],
                                     void                    **ptr)
{
    ptr[0] = InstAlloc_AddMember(&pms[LVM_PERSISTENT_SLOW_DATA], Size[LVM_PERSISTENT_SLOW_DATA]);
    ptr[1] = InstAlloc_AddMember(&pms[LVM_PERSISTENT_FAST_DATA], Size[LVM_PERSISTENT_FAST_DATA]);
    ptr[2] = InstAlloc_AddMember(&pms[LVM_PERSISTENT_FAST_COEF], Size[LVM_PERSISTENT_FAST_COEF]);
    ptr[3] = InstAlloc_AddMember(&pms[LVM_TEMPORARY_FAST], Size[LVM_TEMPORARY_FAST]);

    return (ptr[0]);
}
