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
	File:		cmnMemory.c

	Content:	sample code for memory operator implementation

*******************************************************************************/
#include "cmnMemory.h"

#include <stdlib.h>
#include <string.h>

//VO_MEM_OPERATOR		g_memOP;

VO_U32 cmnMemAlloc (VO_S32 uID,  VO_MEM_INFO * pMemInfo)
{
	if (!pMemInfo)
		return VO_ERR_INVALID_ARG;

	pMemInfo->VBuffer = malloc (pMemInfo->Size);
	return 0;
}

VO_U32 cmnMemFree (VO_S32 uID, VO_PTR pMem)
{
	free (pMem);
	return 0;
}

VO_U32	cmnMemSet (VO_S32 uID, VO_PTR pBuff, VO_U8 uValue, VO_U32 uSize)
{
	memset (pBuff, uValue, uSize);
	return 0;
}

VO_U32	cmnMemCopy (VO_S32 uID, VO_PTR pDest, VO_PTR pSource, VO_U32 uSize)
{
	memcpy (pDest, pSource, uSize);
	return 0;
}

VO_U32	cmnMemCheck (VO_S32 uID, VO_PTR pBuffer, VO_U32 uSize)
{
	return 0;
}

VO_S32 cmnMemCompare (VO_S32 uID, VO_PTR pBuffer1, VO_PTR pBuffer2, VO_U32 uSize)
{
	return memcmp(pBuffer1, pBuffer2, uSize);
}

VO_U32	cmnMemMove (VO_S32 uID, VO_PTR pDest, VO_PTR pSource, VO_U32 uSize)
{
	memmove (pDest, pSource, uSize);
	return 0;
}

