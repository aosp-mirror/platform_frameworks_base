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
*       File: weight_a.c                                               *
*                                                                      *
*       Description:Weighting of LPC coefficients                      *
*	               ap[i] = a[i] * (gamma ** i)                     *
*                                                                      * 
************************************************************************/

#include "typedef.h"
#include "basic_op.h"

void Weight_a(
		Word16 a[],                           /* (i) Q12 : a[m+1]  LPC coefficients             */
		Word16 ap[],                          /* (o) Q12 : Spectral expanded LPC coefficients   */
		Word16 gamma,                         /* (i) Q15 : Spectral expansion factor.           */
		Word16 m                              /* (i)     : LPC order.                           */
	     )
{
	Word32 num = m - 1, fac;
	*ap++ = *a++;
	fac = gamma;
	do{
		*ap++ =(Word16)(((vo_L_mult((*a++), fac)) + 0x8000) >> 16);
		fac = (vo_L_mult(fac, gamma) + 0x8000) >> 16;
	}while(--num != 0);

	*ap++ = (Word16)(((vo_L_mult((*a++), fac)) + 0x8000) >> 16);
	return;
}



