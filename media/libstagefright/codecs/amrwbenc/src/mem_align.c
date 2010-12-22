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
	File:		mem_align.c

	Content:	Memory alloc alignments functions

*******************************************************************************/


#include	"mem_align.h"
#ifdef _MSC_VER
#include	<stddef.h>
#else
#include	<stdint.h>
#endif

/*****************************************************************************
*
* function name: mem_malloc
* description:  malloc the alignments memory
* returns:      the point of the memory
*
**********************************************************************************/
void *
mem_malloc(VO_MEM_OPERATOR *pMemop, unsigned int size, unsigned char alignment, unsigned int CodecID)
{
	int ret;
	unsigned char *mem_ptr;
	VO_MEM_INFO MemInfo;

	if (!alignment) {

		MemInfo.Flag = 0;
		MemInfo.Size = size + 1;
		ret = pMemop->Alloc(CodecID, &MemInfo);
		if(ret != 0)
			return 0;
		mem_ptr = (unsigned char *)MemInfo.VBuffer;

		pMemop->Set(CodecID, mem_ptr, 0, size + 1);

		*mem_ptr = (unsigned char)1;

		return ((void *)(mem_ptr+1));
	} else {
		unsigned char *tmp;

		MemInfo.Flag = 0;
		MemInfo.Size = size + alignment;
		ret = pMemop->Alloc(CodecID, &MemInfo);
		if(ret != 0)
			return 0;

		tmp = (unsigned char *)MemInfo.VBuffer;

		pMemop->Set(CodecID, tmp, 0, size + alignment);

		mem_ptr =
			(unsigned char *) ((intptr_t) (tmp + alignment - 1) &
					(~((intptr_t) (alignment - 1))));

		if (mem_ptr == tmp)
			mem_ptr += alignment;

		*(mem_ptr - 1) = (unsigned char) (mem_ptr - tmp);

		return ((void *)mem_ptr);
	}

	return(0);
}


/*****************************************************************************
*
* function name: mem_free
* description:  free the memory
*
*******************************************************************************/
void
mem_free(VO_MEM_OPERATOR *pMemop, void *mem_ptr, unsigned int CodecID)
{

	unsigned char *ptr;

	if (mem_ptr == 0)
		return;

	ptr = mem_ptr;

	ptr -= *(ptr - 1);

	pMemop->Free(CodecID, ptr);
}



