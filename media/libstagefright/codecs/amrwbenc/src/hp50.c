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
*      File: hp50.c                                                     *
*                                                                       *
*	   Description:                                                 *
* 2nd order high pass filter with cut off frequency at 31 Hz.           *
* Designed with cheby2 function in MATLAB.                              *
* Optimized for fixed-point to get the following frequency response:    *
*                                                                       *
*  frequency:     0Hz    14Hz  24Hz   31Hz   37Hz   41Hz   47Hz         *
*  dB loss:     -infdB  -15dB  -6dB   -3dB  -1.5dB  -1dB  -0.5dB        *
*                                                                       *
* Algorithm:                                                            *
*                                                                       *
*  y[i] = b[0]*x[i] + b[1]*x[i-1] + b[2]*x[i-2]                         *
*                   + a[1]*y[i-1] + a[2]*y[i-2];                        *
*                                                                       *
*  Word16 b[3] = {4053, -8106, 4053};       in Q12                      *
*  Word16 a[3] = {8192, 16211, -8021};       in Q12                     *
*                                                                       *
*  float -->   b[3] = {0.989501953, -1.979003906,  0.989501953};        *
*              a[3] = {1.000000000,  1.978881836, -0.979125977};        *
************************************************************************/

#include "typedef.h"
#include "basic_op.h"
#include "oper_32b.h"
#include "cnst.h"
#include "acelp.h"

/* filter coefficients  */
static Word16 b[3] = {4053, -8106, 4053};  /* Q12 */
static Word16 a[3] = {8192, 16211, -8021}; /* Q12 (x2) */

/* Initialization of static values */

void Init_HP50_12k8(Word16 mem[])
{
	Set_zero(mem, 6);
}


void HP50_12k8(
		Word16 signal[],                      /* input/output signal */
		Word16 lg,                            /* lenght of signal    */
		Word16 mem[]                          /* filter memory [6]   */
	      )
{
	Word16 x2;
	Word16 y2_hi, y2_lo, y1_hi, y1_lo, x0, x1;
	Word32 L_tmp;
	Word32 num;

	y2_hi = *mem++;
	y2_lo = *mem++;
	y1_hi = *mem++;
	y1_lo = *mem++;
	x0 = *mem++;   
	x1 = *mem;
	num = (Word32)lg;
	do
	{
		x2 = x1;
		x1 = x0;
		x0 = *signal;
		/* y[i] = b[0]*x[i] + b[1]*x[i-1] + b140[2]*x[i-2]  */
		/* + a[1]*y[i-1] + a[2] * y[i-2];  */
		L_tmp = 8192 ;                    /* rounding to maximise precision */
		L_tmp += y1_lo * a[1];
		L_tmp += y2_lo * a[2];
		L_tmp = L_tmp >> 14;
		L_tmp += (y1_hi * a[1] + y2_hi * a[2] + (x0 + x2) * b[0] + x1 * b[1]) << 1;
		L_tmp <<= 2;           /* coeff Q12 --> Q13 */
		y2_hi = y1_hi;
		y2_lo = y1_lo;
		y1_hi = (Word16)(L_tmp>>16);
		y1_lo = (Word16)((L_tmp & 0xffff)>>1);
		*signal++ = extract_h((L_add((L_tmp<<1), 0x8000)));
	}while(--num !=0);

	*mem-- = x1;
	*mem-- = x0;
	*mem-- = y1_lo;
	*mem-- = y1_hi;
	*mem-- = y2_lo;
	*mem-- = y2_hi;  

	return;
}


