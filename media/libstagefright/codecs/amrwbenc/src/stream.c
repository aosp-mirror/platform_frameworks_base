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

/***********************************************************************
*       File: stream.c                                                 *
*                                                                      *
*       Description: VOME API Buffer Operator Implement Code           *
*                                                                      *
************************************************************************/

#include "stream.h"

void voAWB_InitFrameBuffer(FrameStream *stream)
{
	stream->set_ptr = NULL;
	stream->frame_ptr_bk = stream->frame_ptr;
	stream->set_len = 0;
	stream->framebuffer_len = 0;
	stream->frame_storelen = 0;	
}

void voAWB_UpdateFrameBuffer(
		FrameStream *stream, 
		VO_MEM_OPERATOR *pMemOP
		)
{
	int  len;
	len  = MIN(Frame_Maxsize - stream->frame_storelen, stream->set_len);
	pMemOP->Copy(VO_INDEX_ENC_AMRWB, stream->frame_ptr_bk + stream->frame_storelen , stream->set_ptr, len);
	stream->set_len -= len;
	stream->set_ptr += len;
	stream->framebuffer_len = stream->frame_storelen + len;
	stream->frame_ptr = stream->frame_ptr_bk;
	stream->used_len += len;
}

void voAWB_FlushFrameBuffer(FrameStream *stream)
{
	stream->set_ptr = NULL;
	stream->frame_ptr_bk = stream->frame_ptr;
	stream->set_len = 0;
	stream->framebuffer_len = 0;
	stream->frame_storelen = 0;	
}

