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
       File: convolve.c

	   Description:Perform the convolution between two vectors x[] and h[]
	               and write the result in the vector y[]

************************************************************************/

#include "typedef.h"
#include "basic_op.h"

void Convolve (
		Word16 x[],        /* (i)     : input vector                           */
		Word16 h[],        /* (i)     : impulse response                       */
		Word16 y[],        /* (o)     : output vector                          */
		Word16 L           /* (i)     : vector size                            */
	      )
{
	Word32  i, n;
	Word16 *tmpH,*tmpX;
	Word32 s;
	for (n = 0; n < 64;)
	{
		tmpH = h+n;
		tmpX = x;
		i=n+1;
		s = vo_mult32((*tmpX++), (*tmpH--));i--;
		while(i>0)
		{
			s += vo_mult32((*tmpX++), (*tmpH--));
			s += vo_mult32((*tmpX++), (*tmpH--));
			s += vo_mult32((*tmpX++), (*tmpH--));
			s += vo_mult32((*tmpX++), (*tmpH--));
			i -= 4;
		}
		y[n] = ((s<<1) + 0x8000)>>16;
		n++;

		tmpH = h+n;
		tmpX = x;
		i=n+1;
		s =  vo_mult32((*tmpX++), (*tmpH--));i--;
		s += vo_mult32((*tmpX++), (*tmpH--));i--;

		while(i>0)
		{
			s += vo_mult32((*tmpX++), (*tmpH--));
			s += vo_mult32((*tmpX++), (*tmpH--));
			s += vo_mult32((*tmpX++), (*tmpH--));
			s += vo_mult32((*tmpX++), (*tmpH--));
			i -= 4;
		}
		y[n] = ((s<<1) + 0x8000)>>16;
		n++;

		tmpH = h+n;
		tmpX = x;
		i=n+1;
		s =  vo_mult32((*tmpX++), (*tmpH--));i--;
		s += vo_mult32((*tmpX++), (*tmpH--));i--;
		s += vo_mult32((*tmpX++), (*tmpH--));i--;

		while(i>0)
		{
			s += vo_mult32((*tmpX++), (*tmpH--));
			s += vo_mult32((*tmpX++), (*tmpH--));
			s += vo_mult32((*tmpX++), (*tmpH--));
			s += vo_mult32((*tmpX++), (*tmpH--));
			i -= 4;
		}
		y[n] = ((s<<1) + 0x8000)>>16;
		n++;

		s = 0;
		tmpH = h+n;
		tmpX = x;
		i=n+1;
		while(i>0)
		{
			s += vo_mult32((*tmpX++), (*tmpH--));
			s += vo_mult32((*tmpX++), (*tmpH--));
			s += vo_mult32((*tmpX++), (*tmpH--));
			s += vo_mult32((*tmpX++), (*tmpH--));
			i -= 4;
		}
		y[n] = ((s<<1) + 0x8000)>>16;
		n++;
	}
	return;
}



