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
*      File: isp_az.c                                                  *
*                                                                      *
*      Description:Compute the LPC coefficients from isp (order=M)     *
*                                                                      *
************************************************************************/

#include "typedef.h"
#include "basic_op.h"
#include "oper_32b.h"
#include "cnst.h"

#define NC (M/2)
#define NC16k (M16k/2)

/* local function */

static void Get_isp_pol(Word16 * isp, Word32 * f, Word16 n);
static void Get_isp_pol_16kHz(Word16 * isp, Word32 * f, Word16 n);

void Isp_Az(
		Word16 isp[],                         /* (i) Q15 : Immittance spectral pairs            */
		Word16 a[],                           /* (o) Q12 : predictor coefficients (order = M)   */
		Word16 m,
		Word16 adaptive_scaling               /* (i) 0   : adaptive scaling disabled */
		                                      /*     1   : adaptive scaling enabled  */
	   )
{
	Word32 i, j; 
	Word16 hi, lo;
	Word32 f1[NC16k + 1], f2[NC16k];
	Word16 nc;
	Word32 t0;
	Word16 q, q_sug;
	Word32 tmax;

	nc = (m >> 1);
	if(nc > 8)
	{
		Get_isp_pol_16kHz(&isp[0], f1, nc);
		for (i = 0; i <= nc; i++)
		{
			f1[i] = f1[i] << 2;
		}
	} else
		Get_isp_pol(&isp[0], f1, nc);

	if (nc > 8)
	{
		Get_isp_pol_16kHz(&isp[1], f2, (nc - 1));
		for (i = 0; i <= nc - 1; i++)
		{
			f2[i] = f2[i] << 2;
		}
	} else
		Get_isp_pol(&isp[1], f2, (nc - 1));

	/*-----------------------------------------------------*
	 *  Multiply F2(z) by (1 - z^-2)                       *
	 *-----------------------------------------------------*/

	for (i = (nc - 1); i > 1; i--)
	{
		f2[i] = vo_L_sub(f2[i], f2[i - 2]);          /* f2[i] -= f2[i-2]; */
	}

	/*----------------------------------------------------------*
	 *  Scale F1(z) by (1+isp[m-1])  and  F2(z) by (1-isp[m-1]) *
	 *----------------------------------------------------------*/

	for (i = 0; i < nc; i++)
	{
		/* f1[i] *= (1.0 + isp[M-1]); */

		hi = f1[i] >> 16;
		lo = (f1[i] & 0xffff)>>1;

		t0 = Mpy_32_16(hi, lo, isp[m - 1]);
		f1[i] = vo_L_add(f1[i], t0); 

		/* f2[i] *= (1.0 - isp[M-1]); */

		hi = f2[i] >> 16;
		lo = (f2[i] & 0xffff)>>1;
		t0 = Mpy_32_16(hi, lo, isp[m - 1]);
		f2[i] = vo_L_sub(f2[i], t0); 
	}

	/*-----------------------------------------------------*
	 *  A(z) = (F1(z)+F2(z))/2                             *
	 *  F1(z) is symmetric and F2(z) is antisymmetric      *
	 *-----------------------------------------------------*/

	/* a[0] = 1.0; */
	a[0] = 4096;  
	tmax = 1;                            
	for (i = 1, j = m - 1; i < nc; i++, j--)
	{
		/* a[i] = 0.5*(f1[i] + f2[i]); */

		t0 = vo_L_add(f1[i], f2[i]);          /* f1[i] + f2[i]             */
		tmax |= L_abs(t0);                 
		a[i] = (Word16)(vo_L_shr_r(t0, 12)); /* from Q23 to Q12 and * 0.5 */

		/* a[j] = 0.5*(f1[i] - f2[i]); */

		t0 = vo_L_sub(f1[i], f2[i]);          /* f1[i] - f2[i]             */
		tmax |= L_abs(t0);                
		a[j] = (Word16)(vo_L_shr_r(t0, 12)); /* from Q23 to Q12 and * 0.5 */
	}

	/* rescale data if overflow has occured and reprocess the loop */
	if(adaptive_scaling == 1)
		q = 4 - norm_l(tmax);        /* adaptive scaling enabled */
	else
		q = 0;                           /* adaptive scaling disabled */

	if (q > 0)
	{
		q_sug = (12 + q);
		for (i = 1, j = m - 1; i < nc; i++, j--)
		{
			/* a[i] = 0.5*(f1[i] + f2[i]); */
			t0 = vo_L_add(f1[i], f2[i]);          /* f1[i] + f2[i]             */
			a[i] = (Word16)(vo_L_shr_r(t0, q_sug)); /* from Q23 to Q12 and * 0.5 */

			/* a[j] = 0.5*(f1[i] - f2[i]); */
			t0 = vo_L_sub(f1[i], f2[i]);          /* f1[i] - f2[i]             */
			a[j] = (Word16)(vo_L_shr_r(t0, q_sug)); /* from Q23 to Q12 and * 0.5 */
		}
		a[0] = shr(a[0], q); 
	}
	else
	{
		q_sug = 12; 
		q     = 0; 
	}
	/* a[NC] = 0.5*f1[NC]*(1.0 + isp[M-1]); */
	hi = f1[nc] >> 16;
	lo = (f1[nc] & 0xffff)>>1;
	t0 = Mpy_32_16(hi, lo, isp[m - 1]);
	t0 = vo_L_add(f1[nc], t0);
	a[nc] = (Word16)(L_shr_r(t0, q_sug));    /* from Q23 to Q12 and * 0.5 */
	/* a[m] = isp[m-1]; */

	a[m] = vo_shr_r(isp[m - 1], (3 + q));           /* from Q15 to Q12          */
	return;
}

/*-----------------------------------------------------------*
* procedure Get_isp_pol:                                    *
*           ~~~~~~~~~~~                                     *
*   Find the polynomial F1(z) or F2(z) from the ISPs.       *
* This is performed by expanding the product polynomials:   *
*                                                           *
* F1(z) =   product   ( 1 - 2 isp_i z^-1 + z^-2 )           *
*         i=0,2,4,6,8                                       *
* F2(z) =   product   ( 1 - 2 isp_i z^-1 + z^-2 )           *
*         i=1,3,5,7                                         *
*                                                           *
* where isp_i are the ISPs in the cosine domain.            *
*-----------------------------------------------------------*
*                                                           *
* Parameters:                                               *
*  isp[]   : isp vector (cosine domaine)         in Q15     *
*  f[]     : the coefficients of F1 or F2        in Q23     *
*  n       : == NC for F1(z); == NC-1 for F2(z)             *
*-----------------------------------------------------------*/

static void Get_isp_pol(Word16 * isp, Word32 * f, Word16 n)
{
	Word16 hi, lo;
	Word32 i, j, t0;
	/* All computation in Q23 */

	f[0] = vo_L_mult(4096, 1024);               /* f[0] = 1.0;        in Q23  */
	f[1] = vo_L_mult(isp[0], -256);             /* f[1] = -2.0*isp[0] in Q23  */

	f += 2;                                  /* Advance f pointer          */
	isp += 2;                                /* Advance isp pointer        */
	for (i = 2; i <= n; i++)
	{
		*f = f[-2];                        
		for (j = 1; j < i; j++, f--)
		{
			hi = f[-1]>>16;
			lo = (f[-1] & 0xffff)>>1;

			t0 = Mpy_32_16(hi, lo, *isp);  /* t0 = f[-1] * isp    */
			t0 = t0 << 1;
			*f = vo_L_sub(*f, t0);              /* *f -= t0            */
			*f = vo_L_add(*f, f[-2]);           /* *f += f[-2]         */
		}
		*f -= (*isp << 9);           /* *f -= isp<<8        */
		f += i;                            /* Advance f pointer   */
		isp += 2;                          /* Advance isp pointer */
	}
	return;
}

static void Get_isp_pol_16kHz(Word16 * isp, Word32 * f, Word16 n)
{
	Word16 hi, lo;
	Word32 i, j, t0;

	/* All computation in Q23 */
	f[0] = L_mult(4096, 256);                /* f[0] = 1.0;        in Q23  */
	f[1] = L_mult(isp[0], -64);              /* f[1] = -2.0*isp[0] in Q23  */

	f += 2;                                  /* Advance f pointer          */
	isp += 2;                                /* Advance isp pointer        */

	for (i = 2; i <= n; i++)
	{
		*f = f[-2];                        
		for (j = 1; j < i; j++, f--)
		{
			VO_L_Extract(f[-1], &hi, &lo);
			t0 = Mpy_32_16(hi, lo, *isp);  /* t0 = f[-1] * isp    */
			t0 = L_shl2(t0, 1);
			*f = L_sub(*f, t0);              /* *f -= t0            */
			*f = L_add(*f, f[-2]);           /* *f += f[-2]         */
		}
		*f = L_msu(*f, *isp, 64);            /* *f -= isp<<8        */
		f += i;                            /* Advance f pointer   */
		isp += 2;                          /* Advance isp pointer */
	}
	return;
}


