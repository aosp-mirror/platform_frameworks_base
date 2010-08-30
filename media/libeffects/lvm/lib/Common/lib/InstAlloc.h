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

#ifndef __INSTALLOC_H__
#define __INSTALLOC_H__

#ifdef __cplusplus
extern "C" {
#endif /* __cplusplus */

#include "LVM_Types.h"
/*######################################################################################*/
/*  Type declarations                                                                   */
/*######################################################################################*/
typedef struct
{
    LVM_UINT32              TotalSize;      /*  Accumulative total memory size                      */
    LVM_UINT32              pNextMember;    /*  Pointer to the next instance member to be allocated */
}   INST_ALLOC;


/*######################################################################################*/
/*  Function prototypes                                                          */
/*######################################################################################*/

/****************************************************************************************
 *  Name        : InstAlloc_Init()
 *  Input       : pms  - Pointer to the INST_ALLOC instance
                  StartAddr - Base address of the instance memory
 *  Returns     : Error code
 *  Description : Initializes the instance distribution and memory size calculation function
 *  Remarks     :
 ****************************************************************************************/

void   InstAlloc_Init( INST_ALLOC *pms, void *StartAddr );


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

void* InstAlloc_AddMember( INST_ALLOC *pms, LVM_UINT32 Size );

/****************************************************************************************
 *  Name        : InstAlloc_GetTotal()
 *  Input       : pms  - Pointer to the INST_ALLOC instance
 *  Returns     : The instance memory size
 *  Description : This functions returns the calculated instance memory size
 *  Remarks     :
 ****************************************************************************************/

LVM_UINT32 InstAlloc_GetTotal( INST_ALLOC *pms);

void*   InstAlloc_AddMemberAllRet(     INST_ALLOC                 *pms,
                                     LVM_UINT32               Size[],
                                     void                    **ptr);

void*   InstAlloc_AddMemberAll( INST_ALLOC                     *pms,
                                 LVM_UINT32                   Size[],
                                 LVM_MemoryTable_st           *pMemoryTable);

void    InstAlloc_InitAll( INST_ALLOC                      *pms,
                           LVM_MemoryTable_st             *pMemoryTable);

void    InstAlloc_InitAll_NULL( INST_ALLOC              *pms);

#ifdef __cplusplus
}
#endif /* __cplusplus */

#endif /* __JBS_INSTALLOC_H__ */
