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
*                                                                      *
*      File             : log2.c                                       *
*      Purpose          : Computes log2(L_x)                           *
*                                                                      *
************************************************************************/

#include "log2.h"
/********************************************************************************
*                         INCLUDE FILES
*********************************************************************************/
#include "typedef.h"
#include "basic_op.h"

/*********************************************************************************
*                         LOCAL VARIABLES AND TABLES
**********************************************************************************/
#include "log2_tab.h"     /* Table for Log2() */

/*************************************************************************
*
*   FUNCTION:   Log2_norm()
*
*   PURPOSE:   Computes log2(L_x, exp),  where   L_x is positive and
*              normalized, and exp is the normalisation exponent
*              If L_x is negative or zero, the result is 0.
*
*   DESCRIPTION:
*        The function Log2(L_x) is approximated by a table and linear
*        interpolation. The following steps are used to compute Log2(L_x)
*
*           1- exponent = 30-norm_exponent
*           2- i = bit25-b31 of L_x;  32<=i<=63  (because of normalization).
*           3- a = bit10-b24
*           4- i -=32
*           5- fraction = table[i]<<16 - (table[i] - table[i+1]) * a * 2
*
*************************************************************************/

void Log2_norm (
		Word32 L_x,         /* (i) : input value (normalized)                    */
		Word16 exp,         /* (i) : norm_l (L_x)                                */
		Word16 *exponent,   /* (o) : Integer part of Log2.   (range: 0<=val<=30) */
		Word16 *fraction    /* (o) : Fractional part of Log2. (range: 0<=val<1)  */
	       )
{
	Word16 i, a, tmp;
	Word32 L_y;
	if (L_x <= (Word32) 0)
	{
		*exponent = 0;
		*fraction = 0;
		return;
	}
	*exponent = (30 - exp);
	L_x = (L_x >> 9);
	i = extract_h (L_x);                /* Extract b25-b31 */
	L_x = (L_x >> 1);
	a = (Word16)(L_x);                /* Extract b10-b24 of fraction */
	a = (Word16)(a & (Word16)0x7fff);
	i -= 32;
	L_y = L_deposit_h (table[i]);       /* table[i] << 16        */
	tmp = vo_sub(table[i], table[i + 1]); /* table[i] - table[i+1] */
	L_y = vo_L_msu (L_y, tmp, a);          /* L_y -= tmp*a*2        */
	*fraction = extract_h (L_y);

	return;
}

/*************************************************************************
*
*   FUNCTION:   Log2()
*
*   PURPOSE:   Computes log2(L_x),  where   L_x is positive.
*              If L_x is negative or zero, the result is 0.
*
*   DESCRIPTION:
*        normalizes L_x and then calls Log2_norm().
*
*************************************************************************/

void Log2 (
		Word32 L_x,         /* (i) : input value                                 */
		Word16 *exponent,   /* (o) : Integer part of Log2.   (range: 0<=val<=30) */
		Word16 *fraction    /* (o) : Fractional part of Log2. (range: 0<=val<1) */
	  )
{
	Word16 exp;

	exp = norm_l(L_x);
	Log2_norm ((L_x << exp), exp, exponent, fraction);
}



