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
*       File: isp_isf.c                                                *
*                                                                      *
*       Description:                                                   *
*	Isp_isf   Transformation isp to isf                            *
*	Isf_isp   Transformation isf to isp                            *
*                                                                      *
*	The transformation from isp[i] to isf[i] and isf[i] to isp[i]  *
*	are approximated by a look-up table and interpolation          *
*                                                                      *
************************************************************************/

#include "typedef.h"
#include "basic_op.h"
#include "isp_isf.tab"                     /* Look-up table for transformations */

void Isp_isf(
		Word16 isp[],                         /* (i) Q15 : isp[m] (range: -1<=val<1)                */
		Word16 isf[],                         /* (o) Q15 : isf[m] normalized (range: 0.0<=val<=0.5) */
		Word16 m                              /* (i)     : LPC order                                */
	    )
{
	Word32 i, ind;
	Word32 L_tmp;
	ind = 127;                               /* beging at end of table -1 */
	for (i = (m - 1); i >= 0; i--)
	{
		if (i >= (m - 2))
		{                                  /* m-2 is a constant */
			ind = 127;                       /* beging at end of table -1 */
		}
		/* find value in table that is just greater than isp[i] */
		while (table[ind] < isp[i])
			ind--;
		/* acos(isp[i])= ind*128 + ( ( isp[i]-table[ind] ) * slope[ind] )/2048 */
		L_tmp = vo_L_mult(vo_sub(isp[i], table[ind]), slope[ind]);
		isf[i] = vo_round((L_tmp << 4));   /* (isp[i]-table[ind])*slope[ind])>>11 */
		isf[i] = add1(isf[i], (ind << 7)); 
	}
	isf[m - 1] = (isf[m - 1] >> 1);      
	return;
}


void Isf_isp(
		Word16 isf[],                         /* (i) Q15 : isf[m] normalized (range: 0.0<=val<=0.5) */
		Word16 isp[],                         /* (o) Q15 : isp[m] (range: -1<=val<1)                */
		Word16 m                              /* (i)     : LPC order                                */
	    )
{
	Word16 offset;
	Word32 i, ind, L_tmp;

	for (i = 0; i < m - 1; i++)
	{
		isp[i] = isf[i];                  
	}
	isp[m - 1] = (isf[m - 1] << 1);

	for (i = 0; i < m; i++)
	{
		ind = (isp[i] >> 7);                      /* ind    = b7-b15 of isf[i] */
		offset = (Word16) (isp[i] & 0x007f);      /* offset = b0-b6  of isf[i] */

		/* isp[i] = table[ind]+ ((table[ind+1]-table[ind])*offset) / 128 */
		L_tmp = vo_L_mult(vo_sub(table[ind + 1], table[ind]), offset);
		isp[i] = add1(table[ind], (Word16)((L_tmp >> 8)));   
	}

	return;
}




