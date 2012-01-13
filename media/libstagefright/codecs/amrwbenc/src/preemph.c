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
*      File: preemph.c                                                *
*                                                                     *
*      Description: Preemphasis: filtering through 1 - g z^-1         *
*	           Preemph2 --> signal is multiplied by 2             *
*                                                                     *
************************************************************************/

#include "typedef.h"
#include "basic_op.h"

void Preemph(
		Word16 x[],                           /* (i/o)   : input signal overwritten by the output */
		Word16 mu,                            /* (i) Q15 : preemphasis coefficient                */
		Word16 lg,                            /* (i)     : lenght of filtering                    */
		Word16 * mem                          /* (i/o)   : memory (x[-1])                         */
	    )
{
	Word16 temp;
	Word32 i, L_tmp;

	temp = x[lg - 1];

	for (i = lg - 1; i > 0; i--)
	{
		L_tmp = L_deposit_h(x[i]);
		L_tmp -= (x[i - 1] * mu)<<1;
		x[i] = (L_tmp + 0x8000)>>16;
	}

	L_tmp = L_deposit_h(x[0]);
	L_tmp -= ((*mem) * mu)<<1;
	x[0] = (L_tmp + 0x8000)>>16;

	*mem = temp;

	return;
}


void Preemph2(
		Word16 x[],                           /* (i/o)   : input signal overwritten by the output */
		Word16 mu,                            /* (i) Q15 : preemphasis coefficient                */
		Word16 lg,                            /* (i)     : lenght of filtering                    */
		Word16 * mem                          /* (i/o)   : memory (x[-1])                         */
	     )
{
	Word16 temp;
	Word32 i, L_tmp;

	temp = x[lg - 1];

	for (i = (Word16) (lg - 1); i > 0; i--)
	{
		L_tmp = L_deposit_h(x[i]);
		L_tmp -= (x[i - 1] * mu)<<1;
		L_tmp = (L_tmp << 1);
		x[i] = (L_tmp + 0x8000)>>16;
	}

	L_tmp = L_deposit_h(x[0]);
	L_tmp -= ((*mem) * mu)<<1;
	L_tmp = (L_tmp << 1);
	x[0] = (L_tmp + 0x8000)>>16;

	*mem = temp;

	return;
}



