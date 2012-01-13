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
*       File: deemph.c                                                 *
*                                                                      *
*	   Description:filtering through 1/(1-mu z^ -1)                    *
*	               Deemph2 --> signal is divided by 2                  *
*				   Deemph_32 --> for 32 bits signal.                   *
*                                                                      *
************************************************************************/

#include "typedef.h"
#include "basic_op.h"
#include "math_op.h"

void Deemph(
		Word16 x[],                           /* (i/o)   : input signal overwritten by the output */
		Word16 mu,                            /* (i) Q15 : deemphasis factor                      */
		Word16 L,                             /* (i)     : vector size                            */
		Word16 * mem                          /* (i/o)   : memory (y[-1])                         */
	   )
{
	Word32 i;
	Word32 L_tmp;

	L_tmp = L_deposit_h(x[0]);
	L_tmp = L_mac(L_tmp, *mem, mu);
	x[0] = vo_round(L_tmp);

	for (i = 1; i < L; i++)
	{
		L_tmp = L_deposit_h(x[i]);
		L_tmp = L_mac(L_tmp, x[i - 1], mu);
		x[i] = voround(L_tmp);
	}

	*mem = x[L - 1];

	return;
}


void Deemph2(
		Word16 x[],                           /* (i/o)   : input signal overwritten by the output */
		Word16 mu,                            /* (i) Q15 : deemphasis factor                      */
		Word16 L,                             /* (i)     : vector size                            */
		Word16 * mem                          /* (i/o)   : memory (y[-1])                         */
	    )
{
	Word32 i;
	Word32 L_tmp;
	L_tmp = x[0] << 15;
	L_tmp += ((*mem) * mu)<<1;
	x[0] = (L_tmp + 0x8000)>>16;
	for (i = 1; i < L; i++)
	{
		L_tmp = x[i] << 15;
		L_tmp += (x[i - 1] * mu)<<1;
		x[i] = (L_tmp + 0x8000)>>16;
	}
	*mem = x[L - 1];
	return;
}


void Deemph_32(
		Word16 x_hi[],                        /* (i)     : input signal (bit31..16) */
		Word16 x_lo[],                        /* (i)     : input signal (bit15..4)  */
		Word16 y[],                           /* (o)     : output signal (x16)      */
		Word16 mu,                            /* (i) Q15 : deemphasis factor        */
		Word16 L,                             /* (i)     : vector size              */
		Word16 * mem                          /* (i/o)   : memory (y[-1])           */
	      )
{
	Word16 fac;
	Word32 i, L_tmp;

	fac = mu >> 1;                                /* Q15 --> Q14 */

	L_tmp = L_deposit_h(x_hi[0]);
	L_tmp += (x_lo[0] * 8)<<1;
	L_tmp = (L_tmp << 3);
	L_tmp += ((*mem) * fac)<<1;
	L_tmp = (L_tmp << 1);
	y[0] = (L_tmp + 0x8000)>>16;

	for (i = 1; i < L; i++)
	{
		L_tmp = L_deposit_h(x_hi[i]);
		L_tmp += (x_lo[i] * 8)<<1;
		L_tmp = (L_tmp << 3);
		L_tmp += (y[i - 1] * fac)<<1;
		L_tmp = (L_tmp << 1);
		y[i] = (L_tmp + 0x8000)>>16;
	}

	*mem = y[L - 1];

	return;
}



