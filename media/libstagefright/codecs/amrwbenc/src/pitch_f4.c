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
*      File: pitch_f4.c                                                *
*                                                                      *
*      Description: Find the closed loop pitch period with             *
*	            1/4 subsample resolution.                          *
*                                                                      *
************************************************************************/

#include "typedef.h"
#include "basic_op.h"
#include "math_op.h"
#include "acelp.h"
#include "cnst.h"

#define UP_SAMP      4
#define L_INTERPOL1  4

/* Local functions */

#ifdef ASM_OPT
void Norm_corr_asm(
		Word16 exc[],                         /* (i)     : excitation buffer                     */
		Word16 xn[],                          /* (i)     : target vector                         */
		Word16 h[],                           /* (i) Q15 : impulse response of synth/wgt filters */
		Word16 L_subfr,
		Word16 t_min,                         /* (i)     : minimum value of pitch lag.           */
		Word16 t_max,                         /* (i)     : maximum value of pitch lag.           */
		Word16 corr_norm[]                    /* (o) Q15 : normalized correlation                */
		);
#else
static void Norm_Corr(
		Word16 exc[],                         /* (i)     : excitation buffer                     */
		Word16 xn[],                          /* (i)     : target vector                         */
		Word16 h[],                           /* (i) Q15 : impulse response of synth/wgt filters */
		Word16 L_subfr,
		Word16 t_min,                         /* (i)     : minimum value of pitch lag.           */
		Word16 t_max,                         /* (i)     : maximum value of pitch lag.           */
		Word16 corr_norm[]                    /* (o) Q15 : normalized correlation                */
		);
#endif

static Word16 Interpol_4(                  /* (o)  : interpolated value  */
		Word16 * x,                           /* (i)  : input vector        */
		Word32 frac                           /* (i)  : fraction (-4..+3)   */
		);


Word16 Pitch_fr4(                          /* (o)     : pitch period.                         */
		Word16 exc[],                         /* (i)     : excitation buffer                     */
		Word16 xn[],                          /* (i)     : target vector                         */
		Word16 h[],                           /* (i) Q15 : impulse response of synth/wgt filters */
		Word16 t0_min,                        /* (i)     : minimum value in the searched range.  */
		Word16 t0_max,                        /* (i)     : maximum value in the searched range.  */
		Word16 * pit_frac,                    /* (o)     : chosen fraction (0, 1, 2 or 3).       */
		Word16 i_subfr,                       /* (i)     : indicator for first subframe.         */
		Word16 t0_fr2,                        /* (i)     : minimum value for resolution 1/2      */
		Word16 t0_fr1,                        /* (i)     : minimum value for resolution 1        */
		Word16 L_subfr                        /* (i)     : Length of subframe                    */
		)
{
	Word32 fraction, i;
	Word16 t_min, t_max;
	Word16 max, t0, step, temp;
	Word16 *corr;
	Word16 corr_v[40];                     /* Total length = t0_max-t0_min+1+2*L_inter */

	/* Find interval to compute normalized correlation */

	t_min = t0_min - L_INTERPOL1;
	t_max = t0_max + L_INTERPOL1;
	corr = &corr_v[-t_min];
	/* Compute normalized correlation between target and filtered excitation */
#ifdef ASM_OPT               /* asm optimization branch */
    Norm_corr_asm(exc, xn, h, L_subfr, t_min, t_max, corr);
#else
	Norm_Corr(exc, xn, h, L_subfr, t_min, t_max, corr);
#endif

	/* Find integer pitch */

	max = corr[t0_min];
	t0 = t0_min;
	for (i = t0_min + 1; i <= t0_max; i++)
	{
		if (corr[i] >= max)
		{
			max = corr[i];
			t0 = i;
		}
	}
	/* If first subframe and t0 >= t0_fr1, do not search fractionnal pitch */
	if ((i_subfr == 0) && (t0 >= t0_fr1))
	{
		*pit_frac = 0;
		return (t0);
	}
	/*------------------------------------------------------------------*
	 * Search fractionnal pitch with 1/4 subsample resolution.          *
	 * Test the fractions around t0 and choose the one which maximizes  *
	 * the interpolated normalized correlation.                         *
	 *------------------------------------------------------------------*/

	step = 1;               /* 1/4 subsample resolution */
	fraction = -3;
	if ((t0_fr2 == PIT_MIN)||((i_subfr == 0) && (t0 >= t0_fr2)))
	{
		step = 2;              /* 1/2 subsample resolution */
		fraction = -2;
	}
	if(t0 == t0_min)
	{
		fraction = 0;
	}
	max = Interpol_4(&corr[t0], fraction);

	for (i = fraction + step; i <= 3; i += step)
	{
		temp = Interpol_4(&corr[t0], i);
		if(temp > max)
		{
			max = temp;
			fraction = i;
		}
	}
	/* limit the fraction value in the interval [0,1,2,3] */
	if (fraction < 0)
	{
		fraction += UP_SAMP;
		t0 -= 1;
	}
	*pit_frac = fraction;
	return (t0);
}


/***********************************************************************************
* Function:  Norm_Corr()                                                            *
*                                                                                   *
* Description: Find the normalized correlation between the target vector and the    *
* filtered past excitation.                                                         *
* (correlation between target and filtered excitation divided by the                *
*  square root of energy of target and filtered excitation).                        *
************************************************************************************/
#ifndef ASM_OPT
static void Norm_Corr(
		Word16 exc[],                         /* (i)     : excitation buffer                     */
		Word16 xn[],                          /* (i)     : target vector                         */
		Word16 h[],                           /* (i) Q15 : impulse response of synth/wgt filters */
		Word16 L_subfr,
		Word16 t_min,                         /* (i)     : minimum value of pitch lag.           */
		Word16 t_max,                         /* (i)     : maximum value of pitch lag.           */
		Word16 corr_norm[])                   /* (o) Q15 : normalized correlation                */
{
	Word32 i, k, t;
	Word32 corr, exp_corr, norm, exp, scale;
	Word16 exp_norm, excf[L_SUBFR], tmp;
	Word32 L_tmp, L_tmp1, L_tmp2;

	/* compute the filtered excitation for the first delay t_min */
	k = -t_min;

#ifdef ASM_OPT              /* asm optimization branch */
	Convolve_asm(&exc[k], h, excf, 64);
#else
	Convolve(&exc[k], h, excf, 64);
#endif

	/* Compute rounded down 1/sqrt(energy of xn[]) */
	L_tmp = 0;
	for (i = 0; i < 64; i+=4)
	{
		L_tmp += (xn[i] * xn[i]);
		L_tmp += (xn[i+1] * xn[i+1]);
		L_tmp += (xn[i+2] * xn[i+2]);
		L_tmp += (xn[i+3] * xn[i+3]);
	}

	L_tmp = (L_tmp << 1) + 1;
	exp = norm_l(L_tmp);
	exp = (32 - exp);
	//exp = exp + 2;                     /* energy of xn[] x 2 + rounded up     */
	scale = -(exp >> 1);           /* (1<<scale) < 1/sqrt(energy rounded) */

	/* loop for every possible period */

	for (t = t_min; t <= t_max; t++)
	{
		/* Compute correlation between xn[] and excf[] */
		L_tmp  = 0;
		L_tmp1 = 0;
		for (i = 0; i < 64; i+=4)
		{
			L_tmp  += (xn[i] * excf[i]);
			L_tmp1 += (excf[i] * excf[i]);
			L_tmp  += (xn[i+1] * excf[i+1]);
			L_tmp1 += (excf[i+1] * excf[i+1]);
			L_tmp  += (xn[i+2] * excf[i+2]);
			L_tmp1 += (excf[i+2] * excf[i+2]);
			L_tmp  += (xn[i+3] * excf[i+3]);
			L_tmp1 += (excf[i+3] * excf[i+3]);
		}

		L_tmp = (L_tmp << 1) + 1;
		L_tmp1 = (L_tmp1 << 1) + 1;

		exp = norm_l(L_tmp);
		L_tmp = (L_tmp << exp);
		exp_corr = (30 - exp);
		corr = extract_h(L_tmp);

		exp = norm_l(L_tmp1);
		L_tmp = (L_tmp1 << exp);
		exp_norm = (30 - exp);

		Isqrt_n(&L_tmp, &exp_norm);
		norm = extract_h(L_tmp);

		/* Normalize correlation = correlation * (1/sqrt(energy)) */

		L_tmp = vo_L_mult(corr, norm);

		L_tmp2 = exp_corr + exp_norm + scale;
		if(L_tmp2 < 0)
		{
			L_tmp2 = -L_tmp2;
			L_tmp = L_tmp >> L_tmp2;
		}
		else
		{
			L_tmp = L_tmp << L_tmp2;
		}

		corr_norm[t] = vo_round(L_tmp);
		/* modify the filtered excitation excf[] for the next iteration */

		if(t != t_max)
		{
			k = -(t + 1);
			tmp = exc[k];
			for (i = 63; i > 0; i--)
			{
				excf[i] = add1(vo_mult(tmp, h[i]), excf[i - 1]);
			}
			excf[0] = vo_mult(tmp, h[0]);
		}
	}
	return;
}

#endif
/************************************************************************************
* Function: Interpol_4()                                                             *
*                                                                                    *
* Description: For interpolating the normalized correlation with 1/4 resolution.     *
**************************************************************************************/

/* 1/4 resolution interpolation filter (-3 dB at 0.791*fs/2) in Q14 */
static Word16 inter4_1[4][8] =
{
	{-12, 420, -1732, 5429, 13418, -1242, 73, 32},
	{-26, 455, -2142, 9910, 9910,  -2142, 455, -26},
	{32,  73, -1242, 13418, 5429, -1732, 420, -12},
	{206, -766, 1376, 14746, 1376, -766, 206, 0}
};

/*** Coefficients in floating point
static float inter4_1[UP_SAMP*L_INTERPOL1+1] = {
0.900000,
0.818959,  0.604850,  0.331379,  0.083958,
-0.075795, -0.130717, -0.105685, -0.046774,
0.004467,  0.027789,  0.025642,  0.012571,
0.001927, -0.001571, -0.000753,  0.000000};
***/

static Word16 Interpol_4(                  /* (o)  : interpolated value  */
		Word16 * x,                           /* (i)  : input vector        */
		Word32 frac                           /* (i)  : fraction (-4..+3)   */
		)
{
	Word16 sum;
	Word32  k, L_sum;
	Word16 *ptr;

	if (frac < 0)
	{
		frac += UP_SAMP;
		x--;
	}
	x = x - L_INTERPOL1 + 1;
	k = UP_SAMP - 1 - frac;
	ptr = &(inter4_1[k][0]);

	L_sum  = vo_mult32(x[0], (*ptr++));
	L_sum += vo_mult32(x[1], (*ptr++));
	L_sum += vo_mult32(x[2], (*ptr++));
	L_sum += vo_mult32(x[3], (*ptr++));
	L_sum += vo_mult32(x[4], (*ptr++));
	L_sum += vo_mult32(x[5], (*ptr++));
	L_sum += vo_mult32(x[6], (*ptr++));
	L_sum += vo_mult32(x[7], (*ptr++));

	sum = extract_h(L_add(L_shl2(L_sum, 2), 0x8000));
	return (sum);
}




