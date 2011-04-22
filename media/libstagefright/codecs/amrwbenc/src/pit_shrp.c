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
*      File: pit_shrp.c                                                *
*                                                                      *
*      Description: Performs Pitch sharpening routine                  *
*                                                                      *
************************************************************************/

#include "typedef.h"
#include "basic_op.h"

void Pit_shrp(
		Word16 * x,                           /* in/out: impulse response (or algebraic code) */
		Word16 pit_lag,                       /* input : pitch lag                            */
		Word16 sharp,                         /* input : pitch sharpening factor (Q15)        */
		Word16 L_subfr                        /* input : subframe size                        */
	     )
{
	Word32 i;
	Word32 L_tmp;
	Word16 *x_ptr = x + pit_lag;

	for (i = pit_lag; i < L_subfr; i++)
	{
		L_tmp = (*x_ptr << 15);
		L_tmp += *x++ * sharp;
		*x_ptr++ = ((L_tmp + 0x4000)>>15);
	}

	return;
}



