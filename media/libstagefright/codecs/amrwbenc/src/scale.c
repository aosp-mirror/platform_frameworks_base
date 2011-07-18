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
*       File: scale.c                                                  *
*                                                                      *
*       Description: Scale signal to get maximum of dynamic            *
*                                                                      *
************************************************************************/

#include "typedef.h"
#include "basic_op.h"

void Scale_sig(
		Word16 x[],                           /* (i/o) : signal to scale               */
		Word16 lg,                            /* (i)   : size of x[]                   */
		Word16 exp                            /* (i)   : exponent: x = round(x << exp) */
	      )
{
	Word32 i;
	Word32 L_tmp;
	if(exp > 0)
	{
		for (i = lg - 1 ; i >= 0; i--)
		{
			L_tmp = L_shl2(x[i], 16 + exp);
			x[i] = extract_h(L_add(L_tmp, 0x8000));
		}
	}
	else
	{
		exp = -exp;
		for (i = lg - 1; i >= 0; i--)
		{
			L_tmp = x[i] << 16;
			L_tmp >>= exp;
			x[i] = (L_tmp + 0x8000)>>16;
		}
	}
	return;
}



