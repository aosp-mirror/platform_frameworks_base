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
*      File: lag_wind.c                                                *
*                                                                      *
*	   Description: Lag_windows on autocorrelations                *
*	                r[i] *= lag_wind[i]                            *
*                                                                      *
************************************************************************/

#include "typedef.h"
#include "basic_op.h"
#include "oper_32b.h"
#include "lag_wind.tab"


void Lag_window(
		Word16 r_h[],                         /* (i/o)   : Autocorrelations  (msb)          */
		Word16 r_l[]                          /* (i/o)   : Autocorrelations  (lsb)          */
	       )
{
	Word32 i;
	Word32 x;

	for (i = 1; i <= M; i++)
	{
		x = Mpy_32(r_h[i], r_l[i], volag_h[i - 1], volag_l[i - 1]);
		r_h[i] = x >> 16;
		r_l[i] = (x & 0xffff)>>1;
	}
	return;
}



