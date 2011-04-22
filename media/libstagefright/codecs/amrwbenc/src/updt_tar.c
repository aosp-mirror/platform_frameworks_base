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
*       File: updt_tar.c                                               *
*                                                                      *
*       Description: Update the target vector for codebook search      *
*                                                                      *
************************************************************************/

#include "typedef.h"
#include "basic_op.h"

void Updt_tar(
		Word16 * x,                           /* (i) Q0  : old target (for pitch search)     */
		Word16 * x2,                          /* (o) Q0  : new target (for codebook search)  */
		Word16 * y,                           /* (i) Q0  : filtered adaptive codebook vector */
		Word16 gain,                          /* (i) Q14 : adaptive codebook gain            */
		Word16 L                              /* (i)     : subframe size                     */
	     )
{
	Word32 i;
	Word32 L_tmp;

	for (i = 0; i < L; i++)
	{
		L_tmp = x[i] << 15;
		L_tmp -= (y[i] * gain)<<1;
		x2[i] = extract_h(L_shl2(L_tmp, 1)); 
	}

	return;
}



