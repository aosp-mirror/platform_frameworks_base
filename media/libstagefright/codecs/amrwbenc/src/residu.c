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
*  File: residu.c                                                      *
*                                                                      *
*  Description: Compute the LPC residual by filtering                  *
*             the input speech through A(z)                            *
*                                                                      *
************************************************************************/

#include "typedef.h"
#include "basic_op.h"

void Residu(
		Word16 a[],                           /* (i) Q12 : prediction coefficients                     */
		Word16 x[],                           /* (i)     : speech (values x[-m..-1] are needed         */
		Word16 y[],                           /* (o) x2  : residual signal                             */
		Word16 lg                             /* (i)     : size of filtering                           */
		)
{
	Word16 i,*p1, *p2;
	Word32 s;
	for (i = 0; i < lg; i++)
	{
		p1 = a;
		p2 = &x[i];
		s  = vo_mult32((*p1++), (*p2--));
		s += vo_mult32((*p1++), (*p2--));
		s += vo_mult32((*p1++), (*p2--));
		s += vo_mult32((*p1++), (*p2--));
		s += vo_mult32((*p1++), (*p2--));
		s += vo_mult32((*p1++), (*p2--));
		s += vo_mult32((*p1++), (*p2--));
		s += vo_mult32((*p1++), (*p2--));
		s += vo_mult32((*p1++), (*p2--));
		s += vo_mult32((*p1++), (*p2--));
		s += vo_mult32((*p1++), (*p2--));
		s += vo_mult32((*p1++), (*p2--));
		s += vo_mult32((*p1++), (*p2--));
		s += vo_mult32((*p1++), (*p2--));
		s += vo_mult32((*p1++), (*p2--));
		s += vo_mult32((*p1++), (*p2--));
		s += vo_mult32((*p1), (*p2));

		s = L_shl2(s, 5); 
		y[i] = extract_h(L_add(s, 0x8000));
	}

	return;
}



