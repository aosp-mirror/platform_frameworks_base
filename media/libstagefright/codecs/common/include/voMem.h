/*
 ** Copyright 2003-2010, VisualOn, Inc.
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */
/*******************************************************************************
	File:		voMem.h

	Content:	memory functions & data structures

*******************************************************************************/

#ifndef __voMem_H__
#define __voMem_H__

#ifdef __cplusplus
extern "C" {
#endif /* __cplusplus */

#include "voIndex.h"

typedef struct
{
	VO_S32				Size;				/*!< Buffer stride */
	VO_S32				Flag;
	VO_PTR				VBuffer;			/*!< user data pointer */
	VO_PTR				PBuffer;			/*!< user data pointer */
}
VO_MEM_INFO;

typedef struct VO_MEM_OPERATOR
{
	VO_U32 (VO_API * Alloc) (VO_S32 uID, VO_MEM_INFO * pMemInfo);
	VO_U32 (VO_API * Free) (VO_S32 uID, VO_PTR pBuff);
	VO_U32 (VO_API * Set) (VO_S32 uID, VO_PTR pBuff, VO_U8 uValue, VO_U32 uSize);
	VO_U32 (VO_API * Copy) (VO_S32 uID, VO_PTR pDest, VO_PTR pSource, VO_U32 uSize);
	VO_U32 (VO_API * Check) (VO_S32 uID, VO_PTR pBuffer, VO_U32 uSize);
	VO_S32 (VO_API * Compare) (VO_S32 uID, VO_PTR pBuffer1, VO_PTR pBuffer2, VO_U32 uSize);
	VO_U32 (VO_API * Move) (VO_S32 uID, VO_PTR pDest, VO_PTR pSource, VO_U32 uSize);
} VO_MEM_OPERATOR;

#define voMemAlloc(pBuff, pMemOP, ID, nSize) \
{ \
	VO_MEM_INFO voMemInfo; \
	voMemInfo.Size=nSize; \
	pMemOP->Alloc(ID, &voMemInfo); \
	pBuff=(VO_PBYTE)voMemInfo.VBuffer; \
}


#ifdef __cplusplus
}
#endif /* __cplusplus */

#endif // __voMem_H__
