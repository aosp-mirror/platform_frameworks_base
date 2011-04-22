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
*       File: autocorr.c                                               *
*                                                                      *
*       Description:Compute autocorrelations of signal with windowing  *
*                                                                      *
************************************************************************/

#include "typedef.h"
#include "basic_op.h"
#include "oper_32b.h"
#include "acelp.h"
#include "ham_wind.tab"

void Autocorr(
		Word16 x[],                           /* (i)    : Input signal                      */
		Word16 m,                             /* (i)    : LPC order                         */
		Word16 r_h[],                         /* (o) Q15: Autocorrelations  (msb)           */
		Word16 r_l[]                          /* (o)    : Autocorrelations  (lsb)           */
	     )
{
	Word32 i, norm, shift;
	Word16 y[L_WINDOW];
	Word32 L_sum, L_sum1, L_tmp, F_LEN;
	Word16 *p1,*p2,*p3;
	const Word16 *p4;
	/* Windowing of signal */
	p1 = x;
	p4 = vo_window;
	p3 = y;

	for (i = 0; i < L_WINDOW; i+=4)
	{
		*p3++ = vo_mult_r((*p1++), (*p4++));
		*p3++ = vo_mult_r((*p1++), (*p4++));
		*p3++ = vo_mult_r((*p1++), (*p4++));
		*p3++ = vo_mult_r((*p1++), (*p4++));
	}

	/* calculate energy of signal */
	L_sum = vo_L_deposit_h(16);               /* sqrt(256), avoid overflow after rounding */
	for (i = 0; i < L_WINDOW; i++)
	{
		L_tmp = vo_L_mult(y[i], y[i]);
		L_tmp = (L_tmp >> 8);
		L_sum += L_tmp;
	}

	/* scale signal to avoid overflow in autocorrelation */
	norm = norm_l(L_sum);
	shift = 4 - (norm >> 1);
	if(shift > 0)
	{
		p1 = y;
		for (i = 0; i < L_WINDOW; i+=4)
		{
			*p1 = vo_shr_r(*p1, shift); 
			p1++;
			*p1 = vo_shr_r(*p1, shift); 
			p1++;
			*p1 = vo_shr_r(*p1, shift);
			p1++;
			*p1 = vo_shr_r(*p1, shift); 
			p1++;
		}
	}

	/* Compute and normalize r[0] */
	L_sum = 1; 
	for (i = 0; i < L_WINDOW; i+=4)
	{
		L_sum += vo_L_mult(y[i], y[i]);
		L_sum += vo_L_mult(y[i+1], y[i+1]);
		L_sum += vo_L_mult(y[i+2], y[i+2]);
		L_sum += vo_L_mult(y[i+3], y[i+3]);
	}

	norm = norm_l(L_sum);
	L_sum = (L_sum << norm);

	r_h[0] = L_sum >> 16;
	r_l[0] = (L_sum & 0xffff)>>1;

	/* Compute r[1] to r[m] */
	for (i = 1; i <= 8; i++)
	{
		L_sum1 = 0;
		L_sum = 0;
		F_LEN = (Word32)(L_WINDOW - 2*i);
		p1 = y;
		p2 = y + (2*i)-1;
		do{
			L_sum1 += *p1 * *p2++;
			L_sum += *p1++ * *p2;
		}while(--F_LEN!=0);

		L_sum1 += *p1 * *p2++;

		L_sum1 = L_sum1<<norm;
		L_sum = L_sum<<norm;

		r_h[(2*i)-1] = L_sum1 >> 15;
		r_l[(2*i)-1] = L_sum1 & 0x00007fff;
		r_h[(2*i)] = L_sum >> 15;
		r_l[(2*i)] = L_sum & 0x00007fff;
	}
	return;
}



