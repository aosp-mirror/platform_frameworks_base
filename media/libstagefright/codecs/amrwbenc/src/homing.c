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
*       File: homing.c                                                 *
*                                                                      *
*       Description:Performs the homing routines                       *
*                                                                      *
************************************************************************/

#include "typedef.h"
#include "cnst.h"
#include "basic_op.h"
#include "bits.h"
#include "homing.tab"

Word16 encoder_homing_frame_test(Word16 input_frame[])
{
	Word32 i;
	Word16 j = 0;

	/* check 320 input samples for matching EHF_MASK: defined in e_homing.h */
	for (i = 0; i < L_FRAME16k; i++)
	{
		j = (Word16) (input_frame[i] ^ EHF_MASK);

		if (j)
			break;
	}

	return (Word16) (!j);
}

