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
*      File: levinson.c                                                *
*                                                                      *
*      Description:LEVINSON-DURBIN algorithm in double precision       *
*                                                                      *
************************************************************************/
/*---------------------------------------------------------------------------*
 *                         LEVINSON.C					     *
 *---------------------------------------------------------------------------*
 *                                                                           *
 *      LEVINSON-DURBIN algorithm in double precision                        *
 *                                                                           *
 *                                                                           *
 * Algorithm                                                                 *
 *                                                                           *
 *       R[i]    autocorrelations.                                           *
 *       A[i]    filter coefficients.                                        *
 *       K       reflection coefficients.                                    *
 *       Alpha   prediction gain.                                            *
 *                                                                           *
 *       Initialization:                                                     *
 *               A[0] = 1                                                    *
 *               K    = -R[1]/R[0]                                           *
 *               A[1] = K                                                    *
 *               Alpha = R[0] * (1-K**2]                                     *
 *                                                                           *
 *       Do for  i = 2 to M                                                  *
 *                                                                           *
 *            S =  SUM ( R[j]*A[i-j] ,j=1,i-1 ) +  R[i]                      *
 *                                                                           *
 *            K = -S / Alpha                                                 *
 *                                                                           *
 *            An[j] = A[j] + K*A[i-j]   for j=1 to i-1                       *
 *                                      where   An[i] = new A[i]             *
 *            An[i]=K                                                        *
 *                                                                           *
 *            Alpha=Alpha * (1-K**2)                                         *
 *                                                                           *
 *       END                                                                 *
 *                                                                           *
 * Remarks on the dynamics of the calculations.                              *
 *                                                                           *
 *       The numbers used are in double precision in the following format :  *
 *       A = AH <<16 + AL<<1.  AH and AL are 16 bit signed integers.         *
 *       Since the LSB's also contain a sign bit, this format does not       *
 *       correspond to standard 32 bit integers.  We use this format since   *
 *       it allows fast execution of multiplications and divisions.          *
 *                                                                           *
 *       "DPF" will refer to this special format in the following text.      *
 *       See oper_32b.c                                                      *
 *                                                                           *
 *       The R[i] were normalized in routine AUTO (hence, R[i] < 1.0).       *
 *       The K[i] and Alpha are theoretically < 1.0.                         *
 *       The A[i], for a sampling frequency of 8 kHz, are in practice        *
 *       always inferior to 16.0.                                            *
 *                                                                           *
 *       These characteristics allow straigthforward fixed-point             *
 *       implementation.  We choose to represent the parameters as           *
 *       follows :                                                           *
 *                                                                           *
 *               R[i]    Q31   +- .99..                                      *
 *               K[i]    Q31   +- .99..                                      *
 *               Alpha   Normalized -> mantissa in Q31 plus exponent         *
 *               A[i]    Q27   +- 15.999..                                   *
 *                                                                           *
 *       The additions are performed in 32 bit.  For the summation used      *
 *       to calculate the K[i], we multiply numbers in Q31 by numbers        *
 *       in Q27, with the result of the multiplications in Q27,              *
 *       resulting in a dynamic of +- 16.  This is sufficient to avoid       *
 *       overflow, since the final result of the summation is                *
 *       necessarily < 1.0 as both the K[i] and Alpha are                    *
 *       theoretically < 1.0.                                                *
 *___________________________________________________________________________*/
#include "typedef.h"
#include "basic_op.h"
#include "oper_32b.h"
#include "acelp.h"

#define M   16
#define NC  (M/2)

void Init_Levinson(
		Word16 * mem                          /* output  :static memory (18 words) */
		)
{
	Set_zero(mem, 18);                     /* old_A[0..M-1] = 0, old_rc[0..1] = 0 */
	return;
}


void Levinson(
		Word16 Rh[],                          /* (i)     : Rh[M+1] Vector of autocorrelations (msb) */
		Word16 Rl[],                          /* (i)     : Rl[M+1] Vector of autocorrelations (lsb) */
		Word16 A[],                           /* (o) Q12 : A[M]    LPC coefficients  (m = 16)       */
		Word16 rc[],                          /* (o) Q15 : rc[M]   Reflection coefficients.         */
		Word16 * mem                          /* (i/o)   :static memory (18 words)                  */
	     )
{
	Word32 i, j;
	Word16 hi, lo;
	Word16 Kh, Kl;                         /* reflection coefficient; hi and lo           */
	Word16 alp_h, alp_l, alp_exp;          /* Prediction gain; hi lo and exponent         */
	Word16 Ah[M + 1], Al[M + 1];           /* LPC coef. in double prec.                   */
	Word16 Anh[M + 1], Anl[M + 1];         /* LPC coef.for next iteration in double prec. */
	Word32 t0, t1, t2;                     /* temporary variable                          */
	Word16 *old_A, *old_rc;

	/* Last A(z) for case of unstable filter */
	old_A = mem;
	old_rc = mem + M;

	/* K = A[1] = -R[1] / R[0] */

	t1 = ((Rh[1] << 16) + (Rl[1] << 1));   /* R[1] in Q31 */
	t2 = L_abs(t1);                        /* abs R[1]         */
	t0 = Div_32(t2, Rh[0], Rl[0]);         /* R[1]/R[0] in Q31 */
	if (t1 > 0)
		t0 = -t0;                          /* -R[1]/R[0]       */

	Kh = t0 >> 16;
	Kl = (t0 & 0xffff)>>1;
	rc[0] = Kh;
	t0 = (t0 >> 4);                        /* A[1] in Q27      */

	Ah[1] = t0 >> 16;
	Al[1] = (t0 & 0xffff)>>1;

	/* Alpha = R[0] * (1-K**2) */
	t0 = Mpy_32(Kh, Kl, Kh, Kl);           /* K*K      in Q31 */
	t0 = L_abs(t0);                        /* Some case <0 !! */
	t0 = vo_L_sub((Word32) 0x7fffffffL, t0);  /* 1 - K*K  in Q31 */

	hi = t0 >> 16;
	lo = (t0 & 0xffff)>>1;

	t0 = Mpy_32(Rh[0], Rl[0], hi, lo);     /* Alpha in Q31    */

	/* Normalize Alpha */
	alp_exp = norm_l(t0);
	t0 = (t0 << alp_exp);

	alp_h = t0 >> 16;
	alp_l = (t0 & 0xffff)>>1;
	/*--------------------------------------*
	 * ITERATIONS  I=2 to M                 *
	 *--------------------------------------*/
	for (i = 2; i <= M; i++)
	{
		/* t0 = SUM ( R[j]*A[i-j] ,j=1,i-1 ) +  R[i] */
		t0 = 0;
		for (j = 1; j < i; j++)
			t0 = vo_L_add(t0, Mpy_32(Rh[j], Rl[j], Ah[i - j], Al[i - j]));

		t0 = t0 << 4;                 /* result in Q27 -> convert to Q31 */
		/* No overflow possible            */
		t1 = ((Rh[i] << 16) + (Rl[i] << 1));
		t0 = vo_L_add(t0, t1);                /* add R[i] in Q31                 */

		/* K = -t0 / Alpha */
		t1 = L_abs(t0);
		t2 = Div_32(t1, alp_h, alp_l);     /* abs(t0)/Alpha                   */
		if (t0 > 0)
			t2 = -t2;                   /* K =-t0/Alpha                    */
		t2 = (t2 << alp_exp);           /* denormalize; compare to Alpha   */

		Kh = t2 >> 16;
		Kl = (t2 & 0xffff)>>1;

		rc[i - 1] = Kh;
		/* Test for unstable filter. If unstable keep old A(z) */
		if (abs_s(Kh) > 32750)
		{
			A[0] = 4096;                    /* Ai[0] not stored (always 1.0) */
			for (j = 0; j < M; j++)
			{
				A[j + 1] = old_A[j];
			}
			rc[0] = old_rc[0];             /* only two rc coefficients are needed */
			rc[1] = old_rc[1];
			return;
		}
		/*------------------------------------------*
		 *  Compute new LPC coeff. -> An[i]         *
		 *  An[j]= A[j] + K*A[i-j]     , j=1 to i-1 *
		 *  An[i]= K                                *
		 *------------------------------------------*/
		for (j = 1; j < i; j++)
		{
			t0 = Mpy_32(Kh, Kl, Ah[i - j], Al[i - j]);
			t0 = vo_L_add(t0, ((Ah[j] << 16) + (Al[j] << 1)));
			Anh[j] = t0 >> 16;
			Anl[j] = (t0 & 0xffff)>>1;
		}
		t2 = (t2 >> 4);                 /* t2 = K in Q31 ->convert to Q27  */

		VO_L_Extract(t2, &Anh[i], &Anl[i]);   /* An[i] in Q27                    */

		/* Alpha = Alpha * (1-K**2) */
		t0 = Mpy_32(Kh, Kl, Kh, Kl);               /* K*K      in Q31 */
		t0 = L_abs(t0);                            /* Some case <0 !! */
		t0 = vo_L_sub((Word32) 0x7fffffffL, t0);   /* 1 - K*K  in Q31 */
		hi = t0 >> 16;
		lo = (t0 & 0xffff)>>1;
		t0 = Mpy_32(alp_h, alp_l, hi, lo); /* Alpha in Q31    */

		/* Normalize Alpha */
		j = norm_l(t0);
		t0 = (t0 << j);
		alp_h = t0 >> 16;
		alp_l = (t0 & 0xffff)>>1;
		alp_exp += j;         /* Add normalization to alp_exp */

		/* A[j] = An[j] */
		for (j = 1; j <= i; j++)
		{
			Ah[j] = Anh[j];
			Al[j] = Anl[j];
		}
	}
	/* Truncate A[i] in Q27 to Q12 with rounding */
	A[0] = 4096;
	for (i = 1; i <= M; i++)
	{
		t0 = (Ah[i] << 16) + (Al[i] << 1);
		old_A[i - 1] = A[i] = vo_round((t0 << 1));
	}
	old_rc[0] = rc[0];
	old_rc[1] = rc[1];

	return;
}



