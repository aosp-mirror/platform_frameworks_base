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
*      File: hp400.c                                                    *
*                                                                       *
*      Description:                                                     *
* 2nd order high pass filter with cut off frequency at 400 Hz.          *
* Designed with cheby2 function in MATLAB.                              *
* Optimized for fixed-point to get the following frequency response:    *
*                                                                       *
*  frequency:     0Hz   100Hz  200Hz  300Hz  400Hz  630Hz  1.5kHz  3kHz *
*  dB loss:     -infdB  -30dB  -20dB  -10dB  -3dB   +6dB    +1dB    0dB *
*                                                                       *
* Algorithm:                                                            *
*                                                                       *
*  y[i] = b[0]*x[i] + b[1]*x[i-1] + b[2]*x[i-2]                         *
*                   + a[1]*y[i-1] + a[2]*y[i-2];                        *
*                                                                       *
*  Word16 b[3] = {3660, -7320,  3660};       in Q12                     *
*  Word16 a[3] = {4096,  7320, -3540};       in Q12                     *
*                                                                       *
*  float -->   b[3] = {0.893554687, -1.787109375,  0.893554687};        *
*              a[3] = {1.000000000,  1.787109375, -0.864257812};        *
*                                                                       *
************************************************************************/

#include "typedef.h"
#include "basic_op.h"
#include "oper_32b.h"
#include "acelp.h"

/* filter coefficients  */
static Word16 b[3] = {915, -1830, 915};         /* Q12 (/4) */
static Word16 a[3] = {16384, 29280, -14160};    /* Q12 (x4) */
/* Initialization of static values */

void Init_HP400_12k8(Word16 mem[])
{
	Set_zero(mem, 6);
}


void HP400_12k8(
		Word16 signal[],                      /* input signal / output is divided by 16 */
		Word16 lg,                            /* lenght of signal    */
		Word16 mem[]                          /* filter memory [6]   */
	       )
{
	Word16  x2;
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
		L_tmp = 8192L;                    /* rounding to maximise precision */
		L_tmp += y1_lo * a[1];
		L_tmp += y2_lo * a[2];
		L_tmp = L_tmp >> 14;
		L_tmp += (y1_hi * a[1] + y2_hi * a[2] + (x0 + x2)* b[0] + x1 * b[1]) << 1;
		L_tmp <<= 1;           /* coeff Q12 --> Q13 */
		y2_hi = y1_hi;
		y2_lo = y1_lo;
		y1_hi = (Word16)(L_tmp>>16);
		y1_lo = (Word16)((L_tmp & 0xffff)>>1);

		/* signal is divided by 16 to avoid overflow in energy computation */
		*signal++ = (L_tmp + 0x8000) >> 16;
	}while(--num !=0);

	*mem-- = x1;
	*mem-- = x0;
	*mem-- = y1_lo;
	*mem-- = y1_hi;
	*mem-- = y2_lo;
	*mem   = y2_hi;  
	return;
}



