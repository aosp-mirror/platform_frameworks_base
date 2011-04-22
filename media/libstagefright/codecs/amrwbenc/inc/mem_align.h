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
	File:		mem_align.h

	Content:	Memory alloc alignments functions

*******************************************************************************/

#ifndef __VO_MEM_ALIGN_H__
#define __VO_MEM_ALIGN_H__

#include "voMem.h"
#include "typedef.h"

extern void *mem_malloc(VO_MEM_OPERATOR *pMemop, unsigned int size, unsigned char alignment, unsigned int CodecID);
extern void mem_free(VO_MEM_OPERATOR *pMemop, void *mem_ptr, unsigned int CodecID);

#endif	/* __VO_MEM_ALIGN_H__ */



