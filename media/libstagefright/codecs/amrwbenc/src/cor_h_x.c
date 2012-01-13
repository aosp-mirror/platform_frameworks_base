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
*       File: cor_h_x.c                                                *
*                                                                      *
*	   Description:Compute correlation between target "x[]" and "h[]"  *
*	               Designed for codebook search (24 pulses, 4 tracks,  *
*				   4 pulses per track, 16 positions in each track) to  *
*				   avoid saturation.                                   *
*                                                                      *
************************************************************************/

#include "typedef.h"
#include "basic_op.h"
#include "math_op.h"

#define L_SUBFR   64
#define NB_TRACK  4
#define STEP      4

void cor_h_x(
		Word16 h[],                           /* (i) Q12 : impulse response of weighted synthesis filter */
		Word16 x[],                           /* (i) Q0  : target vector                                 */
		Word16 dn[]                           /* (o) <12bit : correlation between target and h[]         */
	    )
{
	Word32 i, j;
	Word32 L_tmp, y32[L_SUBFR], L_tot;
	Word16 *p1, *p2;
	Word32 *p3;
	Word32 L_max, L_max1, L_max2, L_max3;
	/* first keep the result on 32 bits and find absolute maximum */
	L_tot  = 1;
	L_max  = 0;
	L_max1 = 0;
	L_max2 = 0;
	L_max3 = 0;
	for (i = 0; i < L_SUBFR; i += STEP)
	{
		L_tmp = 1;                                    /* 1 -> to avoid null dn[] */
		p1 = &x[i];
		p2 = &h[0];
		for (j = i; j < L_SUBFR; j++)
			L_tmp += vo_L_mult(*p1++, *p2++);

		y32[i] = L_tmp;
		L_tmp = (L_tmp > 0)? L_tmp:-L_tmp;
		if(L_tmp > L_max)
		{
			L_max = L_tmp;
		}

		L_tmp = 1L;
		p1 = &x[i+1];
		p2 = &h[0];
		for (j = i+1; j < L_SUBFR; j++)
			L_tmp += vo_L_mult(*p1++, *p2++);

		y32[i+1] = L_tmp;
		L_tmp = (L_tmp > 0)? L_tmp:-L_tmp;
		if(L_tmp > L_max1)
		{
			L_max1 = L_tmp;
		}

		L_tmp = 1;
		p1 = &x[i+2];
		p2 = &h[0];
		for (j = i+2; j < L_SUBFR; j++)
			L_tmp += vo_L_mult(*p1++, *p2++);

		y32[i+2] = L_tmp;
		L_tmp = (L_tmp > 0)? L_tmp:-L_tmp;
		if(L_tmp > L_max2)
		{
			L_max2 = L_tmp;
		}

		L_tmp = 1;
		p1 = &x[i+3];
		p2 = &h[0];
		for (j = i+3; j < L_SUBFR; j++)
			L_tmp += vo_L_mult(*p1++, *p2++);

		y32[i+3] = L_tmp;
		L_tmp = (L_tmp > 0)? L_tmp:-L_tmp;
		if(L_tmp > L_max3)
		{
			L_max3 = L_tmp;
		}
	}
	/* tot += 3*max / 8 */
	L_max = ((L_max + L_max1 + L_max2 + L_max3) >> 2);
	L_tot = vo_L_add(L_tot, L_max);       /* +max/4 */
	L_tot = vo_L_add(L_tot, (L_max >> 1));  /* +max/8 */

	/* Find the number of right shifts to do on y32[] so that    */
	/* 6.0 x sumation of max of dn[] in each track not saturate. */
	j = norm_l(L_tot) - 4;             /* 4 -> 16 x tot */
	p1 = dn;
	p3 = y32;
	for (i = 0; i < L_SUBFR; i+=4)
	{
		*p1++ = vo_round(L_shl(*p3++, j));
		*p1++ = vo_round(L_shl(*p3++, j));
		*p1++ = vo_round(L_shl(*p3++, j));
		*p1++ = vo_round(L_shl(*p3++, j));
	}
	return;
}



