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
	File:		psy_const.h

	Content:	Global psychoacoustic constants structures

*******************************************************************************/

#ifndef _PSYCONST_H
#define _PSYCONST_H

#include "config.h"

#define TRUE  1
#define FALSE 0

#define FRAME_LEN_LONG    AACENC_BLOCKSIZE
#define TRANS_FAC         8
#define FRAME_LEN_SHORT   (FRAME_LEN_LONG/TRANS_FAC)



/* Block types */
enum
{
  LONG_WINDOW = 0,
  START_WINDOW,
  SHORT_WINDOW,
  STOP_WINDOW
};

/* Window shapes */
enum
{
  SINE_WINDOW = 0,
  KBD_WINDOW  = 1
};

/*
  MS stuff
*/
enum
{
  SI_MS_MASK_NONE = 0,
  SI_MS_MASK_SOME = 1,
  SI_MS_MASK_ALL  = 2
};

#define MAX_NO_OF_GROUPS 4
#define MAX_SFB_SHORT   15  /* 15 for a memory optimized implementation, maybe 16 for convenient debugging */
#define MAX_SFB_LONG    51  /* 51 for a memory optimized implementation, maybe 64 for convenient debugging */
#define MAX_SFB         (MAX_SFB_SHORT > MAX_SFB_LONG ? MAX_SFB_SHORT : MAX_SFB_LONG)   /* = MAX_SFB_LONG */
#define MAX_GROUPED_SFB (MAX_NO_OF_GROUPS*MAX_SFB_SHORT > MAX_SFB_LONG ? \
                         MAX_NO_OF_GROUPS*MAX_SFB_SHORT : MAX_SFB_LONG)

#define BLOCK_SWITCHING_OFFSET		   (1*1024+3*128+64+128)
#define BLOCK_SWITCHING_DATA_SIZE          FRAME_LEN_LONG

#define TRANSFORM_OFFSET_LONG    0
#define TRANSFORM_OFFSET_SHORT   448

#define LOG_NORM_PCM          -15

#define NUM_SAMPLE_RATES	12

#endif /* _PSYCONST_H */
