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
*      File: decim54.c                                                 *
*                                                                      *
*	   Description:Decimation of 16kHz signal to 12.8kHz           *
*                                                                      *
************************************************************************/

#include "typedef.h"
#include "basic_op.h"
#include "acelp.h"
#include "cnst.h"

#define FAC5   5
#define DOWN_FAC  26215                    /* 4/5 in Q15 */

#define NB_COEF_DOWN  15

/* Local functions */
static void Down_samp(
		Word16 * sig,                         /* input:  signal to downsampling  */
		Word16 * sig_d,                       /* output: downsampled signal      */
		Word16 L_frame_d                      /* input:  length of output        */
		);

/* 1/5 resolution interpolation filter  (in Q14)  */
/* -1.5dB @ 6kHz, -6dB @ 6.4kHz, -10dB @ 6.6kHz, -20dB @ 6.9kHz, -25dB @ 7kHz, -55dB @ 8kHz */

static Word16 fir_down1[4][30] =
{
	{-5, 24, -50, 54, 0, -128, 294, -408, 344, 0, -647, 1505, -2379, 3034, 13107, 3034, -2379, 1505, -647, 0, 344, -408,
	294, -128, 0, 54, -50, 24, -5, 0},

	{-6, 19, -26, 0, 77, -188, 270, -233, 0, 434, -964, 1366, -1293, 0, 12254, 6575, -2746, 1030, 0, -507, 601, -441,
	198, 0, -95, 99, -58, 18, 0, -1},

	{-3, 9, 0, -41, 111, -170, 153, 0, -295, 649, -888, 770, 0, -1997, 9894, 9894, -1997, 0, 770, -888, 649, -295, 0,
	153, -170, 111, -41, 0, 9, -3},

	{-1, 0, 18, -58, 99, -95, 0, 198, -441, 601, -507, 0, 1030, -2746, 6575, 12254, 0, -1293, 1366, -964, 434, 0,
	-233, 270, -188, 77, 0, -26, 19, -6}
};

void Init_Decim_12k8(
		Word16 mem[]                          /* output: memory (2*NB_COEF_DOWN) set to zeros */
		)
{
	Set_zero(mem, 2 * NB_COEF_DOWN);
	return;
}

void Decim_12k8(
		Word16 sig16k[],                      /* input:  signal to downsampling  */
		Word16 lg,                            /* input:  length of input         */
		Word16 sig12k8[],                     /* output: decimated signal        */
		Word16 mem[]                          /* in/out: memory (2*NB_COEF_DOWN) */
	       )
{
	Word16 lg_down;
	Word16 signal[L_FRAME16k + (2 * NB_COEF_DOWN)];

	Copy(mem, signal, 2 * NB_COEF_DOWN);

	Copy(sig16k, signal + (2 * NB_COEF_DOWN), lg);

	lg_down = (lg * DOWN_FAC)>>15;

	Down_samp(signal + NB_COEF_DOWN, sig12k8, lg_down);

	Copy(signal + lg, mem, 2 * NB_COEF_DOWN);

	return;
}

static void Down_samp(
		Word16 * sig,                         /* input:  signal to downsampling  */
		Word16 * sig_d,                       /* output: downsampled signal      */
		Word16 L_frame_d                      /* input:  length of output        */
		)
{
	Word32 i, j, frac, pos;
	Word16 *x, *y;
	Word32 L_sum;

	pos = 0;                                 /* position is in Q2 -> 1/4 resolution  */
	for (j = 0; j < L_frame_d; j++)
	{
		i = (pos >> 2);                   /* integer part     */
		frac = pos & 3;                   /* fractional part */
		x = sig + i - NB_COEF_DOWN + 1;
		y = (Word16 *)(fir_down1 + frac);

		L_sum = vo_mult32((*x++),(*y++));
		L_sum += vo_mult32((*x++),(*y++));
		L_sum += vo_mult32((*x++),(*y++));
		L_sum += vo_mult32((*x++),(*y++));
		L_sum += vo_mult32((*x++),(*y++));
		L_sum += vo_mult32((*x++),(*y++));
		L_sum += vo_mult32((*x++),(*y++));
		L_sum += vo_mult32((*x++),(*y++));
		L_sum += vo_mult32((*x++),(*y++));
		L_sum += vo_mult32((*x++),(*y++));
		L_sum += vo_mult32((*x++),(*y++));
		L_sum += vo_mult32((*x++),(*y++));
		L_sum += vo_mult32((*x++),(*y++));
		L_sum += vo_mult32((*x++),(*y++));
		L_sum += vo_mult32((*x++),(*y++));
		L_sum += vo_mult32((*x++),(*y++));
		L_sum += vo_mult32((*x++),(*y++));
		L_sum += vo_mult32((*x++),(*y++));
		L_sum += vo_mult32((*x++),(*y++));
		L_sum += vo_mult32((*x++),(*y++));
		L_sum += vo_mult32((*x++),(*y++));
		L_sum += vo_mult32((*x++),(*y++));
		L_sum += vo_mult32((*x++),(*y++));
		L_sum += vo_mult32((*x++),(*y++));
		L_sum += vo_mult32((*x++),(*y++));
		L_sum += vo_mult32((*x++),(*y++));
		L_sum += vo_mult32((*x++),(*y++));
		L_sum += vo_mult32((*x++),(*y++));
		L_sum += vo_mult32((*x++),(*y++));
		L_sum += vo_mult32((*x),(*y));

		L_sum = L_shl2(L_sum, 2);              
		sig_d[j] = extract_h(L_add(L_sum, 0x8000)); 
		pos += FAC5;              /* pos + 5/4 */
	}
	return;
}


