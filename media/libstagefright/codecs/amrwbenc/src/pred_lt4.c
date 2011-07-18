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
*      File: pred_lt4.c                                                *
*                                                                      *
*      Description: Compute the result of long term prediction with    *
*      fractional interpolation of resolution 1/4                      *
*      on return exc[0..L_subr-1] contains the interpolated signal     *
*      (adaptive codebook excitation)                                  *
*                                                                      *
************************************************************************/

#include "typedef.h"
#include "basic_op.h"

#define UP_SAMP      4
#define L_INTERPOL2  16

/* 1/4 resolution interpolation filter (-3 dB at 0.856*fs/2) in Q14 */

Word16 inter4_2[4][32] =
{
	{0,-2,4,-2,-10,38,-88,165,-275,424,-619,871,-1207,1699,-2598,5531,14031,-2147,780,-249,
	-16,153,-213,226,-209,175,-133,91,-55,28,-10,2},

	{1,-7,19,-33,47,-52,43,-9,-60,175,-355,626,-1044,1749,-3267,10359,10359,-3267,1749,-1044,
	626,-355,175,-60,-9,43,-52,47,-33,19, -7, 1},

	{2,-10,28,-55,91,-133,175,-209,226,-213,153,-16,-249,780,-2147,14031,5531,-2598,1699,-1207,
	871,-619,424,-275,165,-88,38,-10,-2,4,-2,0},

	{1,-7,22,-49,92,-153,231,-325,431,-544,656,-762,853,-923,968,15401,968,-923,853,-762,
	656,-544,431,-325,231,-153,92,-49,22,-7, 1, 0}

};

void Pred_lt4(
		Word16 exc[],                         /* in/out: excitation buffer */
		Word16 T0,                            /* input : integer pitch lag */
		Word16 frac,                          /* input : fraction of lag   */
		Word16 L_subfr                        /* input : subframe size     */
	     )
{
	Word16 j, k, *x;
	Word32 L_sum;
	Word16 *ptr, *ptr1;
	Word16 *ptr2;

	x = exc - T0;
	frac = -frac;
	if (frac < 0)
	{
		frac += UP_SAMP;
		x--;
	}
	x -= 15;                                     /* x = L_INTERPOL2 - 1 */
	k = 3 - frac;                                /* k = UP_SAMP - 1 - frac */

	ptr2 = &(inter4_2[k][0]);
	for (j = 0; j < L_subfr; j++)
	{
		ptr = ptr2;
		ptr1 = x;
		L_sum  = vo_mult32((*ptr1++), (*ptr++));
		L_sum += vo_mult32((*ptr1++), (*ptr++));
		L_sum += vo_mult32((*ptr1++), (*ptr++));
		L_sum += vo_mult32((*ptr1++), (*ptr++));
		L_sum += vo_mult32((*ptr1++), (*ptr++));
		L_sum += vo_mult32((*ptr1++), (*ptr++));
		L_sum += vo_mult32((*ptr1++), (*ptr++));
		L_sum += vo_mult32((*ptr1++), (*ptr++));
		L_sum += vo_mult32((*ptr1++), (*ptr++));
		L_sum += vo_mult32((*ptr1++), (*ptr++));
		L_sum += vo_mult32((*ptr1++), (*ptr++));
		L_sum += vo_mult32((*ptr1++), (*ptr++));
		L_sum += vo_mult32((*ptr1++), (*ptr++));
		L_sum += vo_mult32((*ptr1++), (*ptr++));
		L_sum += vo_mult32((*ptr1++), (*ptr++));
		L_sum += vo_mult32((*ptr1++), (*ptr++));
		L_sum += vo_mult32((*ptr1++), (*ptr++));
		L_sum += vo_mult32((*ptr1++), (*ptr++));
		L_sum += vo_mult32((*ptr1++), (*ptr++));
		L_sum += vo_mult32((*ptr1++), (*ptr++));
		L_sum += vo_mult32((*ptr1++), (*ptr++));
		L_sum += vo_mult32((*ptr1++), (*ptr++));
		L_sum += vo_mult32((*ptr1++), (*ptr++));
		L_sum += vo_mult32((*ptr1++), (*ptr++));
		L_sum += vo_mult32((*ptr1++), (*ptr++));
		L_sum += vo_mult32((*ptr1++), (*ptr++));
		L_sum += vo_mult32((*ptr1++), (*ptr++));
		L_sum += vo_mult32((*ptr1++), (*ptr++));
		L_sum += vo_mult32((*ptr1++), (*ptr++));
		L_sum += vo_mult32((*ptr1++), (*ptr++));
		L_sum += vo_mult32((*ptr1++), (*ptr++));
		L_sum += vo_mult32((*ptr1++), (*ptr++));

		L_sum = L_shl2(L_sum, 2);
		exc[j] = extract_h(L_add(L_sum, 0x8000));
		x++;
	}

	return;
}



