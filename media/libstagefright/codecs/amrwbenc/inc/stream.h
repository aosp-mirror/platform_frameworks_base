
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
File:		stream.h

Contains:       VOME API Buffer Operator Implement Header

************************************************************************/
#ifndef __STREAM_H__
#define __STREAM_H__

#include "voMem.h"
#define Frame_Maxsize  1024 * 2  //Work Buffer 10K
#define Frame_MaxByte  640        //AMR_WB Encoder one frame 320 samples = 640 Bytes
#define MIN(a,b)	 ((a) < (b)? (a) : (b))

typedef struct{
	unsigned char *set_ptr;
	unsigned char *frame_ptr;
	unsigned char *frame_ptr_bk;
	int  set_len;
	int  framebuffer_len;
	int  frame_storelen;
	int  used_len;
}FrameStream;

void voAWB_UpdateFrameBuffer(FrameStream *stream, VO_MEM_OPERATOR *pMemOP);
void voAWB_InitFrameBuffer(FrameStream *stream);
void voAWB_FlushFrameBuffer(FrameStream *stream);
#endif //__STREAM_H__

