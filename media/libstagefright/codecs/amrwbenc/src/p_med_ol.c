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
*      File: p_med_ol.c                                                *
*                                                                      *
*      Description: Compute the open loop pitch lag                    *
*	            output: open loop pitch lag                        *
************************************************************************/

#include "typedef.h"
#include "basic_op.h"
#include "acelp.h"
#include "oper_32b.h"
#include "math_op.h"
#include "p_med_ol.tab"

Word16 Pitch_med_ol(
		   Word16      wsp[],        /*   i: signal used to compute the open loop pitch*/
                                     /*      wsp[-pit_max] to wsp[-1] should be known */
		   Coder_State *st,          /* i/o: codec global structure */
		   Word16      L_frame       /*   i: length of frame to compute pitch */
		)
{
	Word16 Tm;
	Word16 hi, lo;
	Word16 *ww, *we, *hp_wsp;
	Word16 exp_R0, exp_R1, exp_R2;
	Word32 i, j, max, R0, R1, R2;
	Word16 *p1, *p2;
	Word16 L_min = 17;                   /* minimum pitch lag: PIT_MIN / OPL_DECIM */
	Word16 L_max = 115;                  /* maximum pitch lag: PIT_MAX / OPL_DECIM */
	Word16 L_0 = st->old_T0_med;         /* old open-loop pitch */
	Word16 *gain = &(st->ol_gain);       /* normalize correlation of hp_wsp for the lag */
	Word16 *hp_wsp_mem = st->hp_wsp_mem; /* memory of the hypass filter for hp_wsp[] (lg = 9)*/
	Word16 *old_hp_wsp = st->old_hp_wsp; /* hypass wsp[] */
	Word16 wght_flg = st->ol_wght_flg;   /* is weighting function used */

	ww = &corrweight[198];
	we = &corrweight[98 + L_max - L_0];

	max = MIN_32;
	Tm = 0;
	for (i = L_max; i > L_min; i--)
	{
		/* Compute the correlation */
		R0 = 0;
		p1 = wsp;
		p2 = &wsp[-i];
		for (j = 0; j < L_frame; j+=4)
		{
			R0 += vo_L_mult((*p1++), (*p2++));
			R0 += vo_L_mult((*p1++), (*p2++));
			R0 += vo_L_mult((*p1++), (*p2++));
			R0 += vo_L_mult((*p1++), (*p2++));
		}
		/* Weighting of the correlation function.   */
		hi = R0>>16;
		lo = (R0 & 0xffff)>>1;

		R0 = Mpy_32_16(hi, lo, *ww);
		ww--;

		if ((L_0 > 0) && (wght_flg > 0))
		{
			/* Weight the neighbourhood of the old lag. */
			hi = R0>>16;
			lo = (R0 & 0xffff)>>1;
			R0 = Mpy_32_16(hi, lo, *we);
			we--;
		}
		if(R0 >= max)
		{
			max = R0;
			Tm = i;
		}
	}

	/* Hypass the wsp[] vector */
	hp_wsp = old_hp_wsp + L_max;
	Hp_wsp(wsp, hp_wsp, L_frame, hp_wsp_mem);

	/* Compute normalize correlation at delay Tm */
	R0 = 0;
	R1 = 0;
	R2 = 0;
	p1 = hp_wsp;
	p2 = hp_wsp - Tm;
	for (j = 0; j < L_frame; j+=4)
	{
		R2 += vo_mult32(*p1, *p1);
		R1 += vo_mult32(*p2, *p2);
		R0 += vo_mult32(*p1++, *p2++);
		R2 += vo_mult32(*p1, *p1);
		R1 += vo_mult32(*p2, *p2);
		R0 += vo_mult32(*p1++, *p2++);
		R2 += vo_mult32(*p1, *p1);
		R1 += vo_mult32(*p2, *p2);
		R0 += vo_mult32(*p1++, *p2++);
		R2 += vo_mult32(*p1, *p1);
		R1 += vo_mult32(*p2, *p2);
		R0 += vo_mult32(*p1++, *p2++);
	}
	R0 = R0 <<1;
	R1 = (R1 <<1) + 1L;
	R2 = (R2 <<1) + 1L;
	/* gain = R0/ sqrt(R1*R2) */

	exp_R0 = norm_l(R0);
	R0 = (R0 << exp_R0);

	exp_R1 = norm_l(R1);
	R1 = (R1 << exp_R1);

	exp_R2 = norm_l(R2);
	R2 = (R2 << exp_R2);


	R1 = vo_L_mult(vo_round(R1), vo_round(R2));

	i = norm_l(R1);
	R1 = (R1 << i);

	exp_R1 += exp_R2;
	exp_R1 += i;
	exp_R1 = 62 - exp_R1;

	Isqrt_n(&R1, &exp_R1);

	R0 = vo_L_mult(voround(R0), voround(R1));
	exp_R0 = 31 - exp_R0;
	exp_R0 += exp_R1;

	*gain = vo_round(L_shl(R0, exp_R0));

	/* Shitf hp_wsp[] for next frame */

	for (i = 0; i < L_max; i++)
	{
		old_hp_wsp[i] = old_hp_wsp[i + L_frame];
	}

	return (Tm);
}

/************************************************************************
*  Function: median5                                                    *
*                                                                       *
*      Returns the median of the set {X[-2], X[-1],..., X[2]},          *
*      whose elements are 16-bit integers.                              *
*                                                                       *
*  Input:                                                               *
*      X[-2:2]   16-bit integers.                                       *
*                                                                       *
*  Return:                                                              *
*      The median of {X[-2], X[-1],..., X[2]}.                          *
************************************************************************/

Word16 median5(Word16 x[])
{
	Word16 x1, x2, x3, x4, x5;
	Word16 tmp;

	x1 = x[-2];
	x2 = x[-1];
	x3 = x[0];
	x4 = x[1];
	x5 = x[2];

	if (x2 < x1)
	{
		tmp = x1;
		x1 = x2;
		x2 = tmp;
	}
	if (x3 < x1)
	{
		tmp = x1;
		x1 = x3;
		x3 = tmp;
	}
	if (x4 < x1)
	{
		tmp = x1;
		x1 = x4;
		x4 = tmp;
	}
	if (x5 < x1)
	{
		x5 = x1;
	}
	if (x3 < x2)
	{
		tmp = x2;
		x2 = x3;
		x3 = tmp;
	}
	if (x4 < x2)
	{
		tmp = x2;
		x2 = x4;
		x4 = tmp;
	}
	if (x5 < x2)
	{
		x5 = x2;
	}
	if (x4 < x3)
	{
		x3 = x4;
	}
	if (x5 < x3)
	{
		x3 = x5;
	}
	return (x3);
}


Word16 Med_olag(                           /* output : median of  5 previous open-loop lags       */
		Word16 prev_ol_lag,                /* input  : previous open-loop lag                     */
		Word16 old_ol_lag[5]
	       )
{
	Word32 i;

	/* Use median of 5 previous open-loop lags as old lag */

	for (i = 4; i > 0; i--)
	{
		old_ol_lag[i] = old_ol_lag[i - 1];
	}

	old_ol_lag[0] = prev_ol_lag;

	i = median5(&old_ol_lag[2]);

	return i;

}



