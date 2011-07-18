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
*       File: lp_dec2.c                                                *
*                                                                      *
*	Description:Decimate a vector by 2 with 2nd order fir filter   *
*                                                                      *
************************************************************************/

#include "typedef.h"
#include "basic_op.h"
#include "cnst.h"

#define L_FIR  5
#define L_MEM  (L_FIR-2)

/* static float h_fir[L_FIR] = {0.13, 0.23, 0.28, 0.23, 0.13}; */
/* fixed-point: sum of coef = 32767 to avoid overflow on DC */
static Word16 h_fir[L_FIR] = {4260, 7536, 9175, 7536, 4260};

void LP_Decim2(
		Word16 x[],                           /* in/out: signal to process         */
		Word16 l,                             /* input : size of filtering         */
		Word16 mem[]                          /* in/out: memory (size=3)           */
	      )
{
	Word16 *p_x, x_buf[L_FRAME + L_MEM];
	Word32 i, j;
	Word32 L_tmp;
	/* copy initial filter states into buffer */
	p_x = x_buf;
	for (i = 0; i < L_MEM; i++)
	{
		*p_x++ = mem[i];
		mem[i] = x[l - L_MEM + i];
	}
	for (i = 0; i < l; i++)
	{
		*p_x++ = x[i];
	}
	for (i = 0, j = 0; i < l; i += 2, j++)
	{
		p_x = &x_buf[i];
		L_tmp  = ((*p_x++) * h_fir[0]);
		L_tmp += ((*p_x++) * h_fir[1]);
		L_tmp += ((*p_x++) * h_fir[2]);
		L_tmp += ((*p_x++) * h_fir[3]);
		L_tmp += ((*p_x++) * h_fir[4]);
		x[j] = (L_tmp + 0x4000)>>15;
	}
	return;
}




