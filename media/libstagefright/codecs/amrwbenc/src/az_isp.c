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
*  File: az_isp.c
*
*  Description:
*-----------------------------------------------------------------------*
* Compute the ISPs from  the LPC coefficients  (order=M)                *
*-----------------------------------------------------------------------*
*                                                                       *
* The ISPs are the roots of the two polynomials F1(z) and F2(z)         *
* defined as                                                            *
*               F1(z) = A(z) + z^-m A(z^-1)                             *
*  and          F2(z) = A(z) - z^-m A(z^-1)                             *
*                                                                       *
* For a even order m=2n, F1(z) has M/2 conjugate roots on the unit      *
* circle and F2(z) has M/2-1 conjugate roots on the unit circle in      *
* addition to two roots at 0 and pi.                                    *
*                                                                       *
* For a 16th order LP analysis, F1(z) and F2(z) can be written as       *
*                                                                       *
*   F1(z) = (1 + a[M])   PRODUCT  (1 - 2 cos(w_i) z^-1 + z^-2 )         *
*                        i=0,2,4,6,8,10,12,14                           *
*                                                                       *
*   F2(z) = (1 - a[M]) (1 - z^-2) PRODUCT (1 - 2 cos(w_i) z^-1 + z^-2 ) *
*                                 i=1,3,5,7,9,11,13                     *
*                                                                       *
* The ISPs are the M-1 frequencies w_i, i=0...M-2 plus the last         *
* predictor coefficient a[M].                                           *
*-----------------------------------------------------------------------*

************************************************************************/

#include "typedef.h"
#include "basic_op.h"
#include "oper_32b.h"
#include "stdio.h"
#include "grid100.tab"

#define M   16
#define NC  (M/2)

/* local function */
static __inline Word16 Chebps2(Word16 x, Word16 f[], Word32 n);

void Az_isp(
		Word16 a[],                           /* (i) Q12 : predictor coefficients                 */
		Word16 isp[],                         /* (o) Q15 : Immittance spectral pairs              */
		Word16 old_isp[]                      /* (i)     : old isp[] (in case not found M roots)  */
	   )
{
	Word32 i, j, nf, ip, order;
	Word16 xlow, ylow, xhigh, yhigh, xmid, ymid, xint;
	Word16 x, y, sign, exp;
	Word16 *coef;
	Word16 f1[NC + 1], f2[NC];
	Word32 t0;
	/*-------------------------------------------------------------*
	 * find the sum and diff polynomials F1(z) and F2(z)           *
	 *      F1(z) = [A(z) + z^M A(z^-1)]                           *
	 *      F2(z) = [A(z) - z^M A(z^-1)]/(1-z^-2)                  *
	 *                                                             *
	 * for (i=0; i<NC; i++)                                        *
	 * {                                                           *
	 *   f1[i] = a[i] + a[M-i];                                    *
	 *   f2[i] = a[i] - a[M-i];                                    *
	 * }                                                           *
	 * f1[NC] = 2.0*a[NC];                                         *
	 *                                                             *
	 * for (i=2; i<NC; i++)            Divide by (1-z^-2)          *
	 *   f2[i] += f2[i-2];                                         *
	 *-------------------------------------------------------------*/
	for (i = 0; i < NC; i++)
	{
		t0 = a[i] << 15;
		f1[i] = vo_round(t0 + (a[M - i] << 15));        /* =(a[i]+a[M-i])/2 */
		f2[i] = vo_round(t0 - (a[M - i] << 15));        /* =(a[i]-a[M-i])/2 */
	}
	f1[NC] = a[NC];
	for (i = 2; i < NC; i++)               /* Divide by (1-z^-2) */
		f2[i] = add1(f2[i], f2[i - 2]);

	/*---------------------------------------------------------------------*
	 * Find the ISPs (roots of F1(z) and F2(z) ) using the                 *
	 * Chebyshev polynomial evaluation.                                    *
	 * The roots of F1(z) and F2(z) are alternatively searched.            *
	 * We start by finding the first root of F1(z) then we switch          *
	 * to F2(z) then back to F1(z) and so on until all roots are found.    *
	 *                                                                     *
	 *  - Evaluate Chebyshev pol. at grid points and check for sign change.*
	 *  - If sign change track the root by subdividing the interval        *
	 *    2 times and ckecking sign change.                                *
	 *---------------------------------------------------------------------*/
	nf = 0;                                  /* number of found frequencies */
	ip = 0;                                  /* indicator for f1 or f2      */
	coef = f1;
	order = NC;
	xlow = vogrid[0];
	ylow = Chebps2(xlow, coef, order);
	j = 0;
	while ((nf < M - 1) && (j < GRID_POINTS))
	{
		j ++;
		xhigh = xlow;
		yhigh = ylow;
		xlow = vogrid[j];
		ylow = Chebps2(xlow, coef, order);
		if ((ylow * yhigh) <= (Word32) 0)
		{
			/* divide 2 times the interval */
			for (i = 0; i < 2; i++)
			{
				xmid = (xlow >> 1) + (xhigh >> 1);        /* xmid = (xlow + xhigh)/2 */
				ymid = Chebps2(xmid, coef, order);
				if ((ylow * ymid) <= (Word32) 0)
				{
					yhigh = ymid;
					xhigh = xmid;
				} else
				{
					ylow = ymid;
					xlow = xmid;
				}
			}
			/*-------------------------------------------------------------*
			 * Linear interpolation                                        *
			 *    xint = xlow - ylow*(xhigh-xlow)/(yhigh-ylow);            *
			 *-------------------------------------------------------------*/
			x = xhigh - xlow;
			y = yhigh - ylow;
			if (y == 0)
			{
				xint = xlow;
			} else
			{
				sign = y;
				y = abs_s(y);
				exp = norm_s(y);
				y = y << exp;
				y = div_s((Word16) 16383, y);
				t0 = x * y;
				t0 = (t0 >> (19 - exp));
				y = vo_extract_l(t0);         /* y= (xhigh-xlow)/(yhigh-ylow) in Q11 */
				if (sign < 0)
					y = -y;
				t0 = ylow * y;      /* result in Q26 */
				t0 = (t0 >> 10);        /* result in Q15 */
				xint = vo_sub(xlow, vo_extract_l(t0));        /* xint = xlow - ylow*y */
			}
			isp[nf] = xint;
			xlow = xint;
			nf++;
			if (ip == 0)
			{
				ip = 1;
				coef = f2;
				order = NC - 1;
			} else
			{
				ip = 0;
				coef = f1;
				order = NC;
			}
			ylow = Chebps2(xlow, coef, order);
		}
	}
	/* Check if M-1 roots found */
	if(nf < M - 1)
	{
		for (i = 0; i < M; i++)
		{
			isp[i] = old_isp[i];
		}
	} else
	{
		isp[M - 1] = a[M] << 3;                      /* From Q12 to Q15 with saturation */
	}
	return;
}

/*--------------------------------------------------------------*
* function  Chebps2:                                           *
*           ~~~~~~~                                            *
*    Evaluates the Chebishev polynomial series                 *
*--------------------------------------------------------------*
*                                                              *
*  The polynomial order is                                     *
*     n = M/2   (M is the prediction order)                    *
*  The polynomial is given by                                  *
*    C(x) = f(0)T_n(x) + f(1)T_n-1(x) + ... +f(n-1)T_1(x) + f(n)/2 *
* Arguments:                                                   *
*  x:     input value of evaluation; x = cos(frequency) in Q15 *
*  f[]:   coefficients of the pol.                      in Q11 *
*  n:     order of the pol.                                    *
*                                                              *
* The value of C(x) is returned. (Satured to +-1.99 in Q14)    *
*                                                              *
*--------------------------------------------------------------*/

static __inline Word16 Chebps2(Word16 x, Word16 f[], Word32 n)
{
	Word32 i, cheb;
	Word16 b0_h, b0_l, b1_h, b1_l, b2_h, b2_l;
	Word32 t0;

	/* Note: All computation are done in Q24. */

	t0 = f[0] << 13;
	b2_h = t0 >> 16;
	b2_l = (t0 & 0xffff)>>1;

	t0 = ((b2_h * x)<<1) + (((b2_l * x)>>15)<<1);
	t0 <<= 1;
	t0 += (f[1] << 13);						/* + f[1] in Q24        */

	b1_h = t0 >> 16;
	b1_l = (t0 & 0xffff) >> 1;

	for (i = 2; i < n; i++)
	{
		t0 = ((b1_h * x)<<1) + (((b1_l * x)>>15)<<1);

		t0 += (b2_h * (-16384))<<1;
		t0 += (f[i] << 12);
		t0 <<= 1;
		t0 -= (b2_l << 1);					/* t0 = 2.0*x*b1 - b2 + f[i]; */

		b0_h = t0 >> 16;
		b0_l = (t0 & 0xffff) >> 1;

		b2_l = b1_l;                         /* b2 = b1; */
		b2_h = b1_h;
		b1_l = b0_l;                         /* b1 = b0; */
		b1_h = b0_h;
	}

	t0 = ((b1_h * x)<<1) + (((b1_l * x)>>15)<<1);
	t0 += (b2_h * (-32768))<<1;				/* t0 = x*b1 - b2          */
	t0 -= (b2_l << 1);
	t0 += (f[n] << 12);						/* t0 = x*b1 - b2 + f[i]/2 */

	t0 = L_shl2(t0, 6);                     /* Q24 to Q30 with saturation */

	cheb = extract_h(t0);                  /* Result in Q14              */

	if (cheb == -32768)
	{
		cheb = -32767;                     /* to avoid saturation in Az_isp */
	}
	return (cheb);
}



