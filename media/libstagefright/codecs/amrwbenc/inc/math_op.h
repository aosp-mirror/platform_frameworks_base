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


/*--------------------------------------------------------------------------*
 *                         MATH_OP.H	                                    *
 *--------------------------------------------------------------------------*
 *       Mathematical operations					    *
 *--------------------------------------------------------------------------*/

#ifndef __MATH_OP_H__
#define __MATH_OP_H__

Word32 Isqrt(                              /* (o) Q31 : output value (range: 0<=val<1)         */
		Word32 L_x                            /* (i) Q0  : input value  (range: 0<=val<=7fffffff) */
	    );

void Isqrt_n(
		Word32 * frac,                        /* (i/o) Q31: normalized value (1.0 < frac <= 0.5) */
		Word16 * exp                          /* (i/o)    : exponent (value = frac x 2^exponent) */
	    );

Word32 Pow2(                               /* (o) Q0  : result       (range: 0<=val<=0x7fffffff) */
		Word16 exponant,                      /* (i) Q0  : Integer part.      (range: 0<=val<=30)   */
		Word16 fraction                       /* (i) Q15 : Fractionnal part.  (range: 0.0<=val<1.0) */
	   );

Word32 Dot_product12(                      /* (o) Q31: normalized result (1 < val <= -1) */
		Word16 x[],                           /* (i) 12bits: x vector                       */
		Word16 y[],                           /* (i) 12bits: y vector                       */
		Word16 lg,                            /* (i)    : vector length                     */
		Word16 * exp                          /* (o)    : exponent of result (0..+30)       */
		);

Word32 Dot_product12_asm(                      /* (o) Q31: normalized result (1 < val <= -1) */
		Word16 x[],                           /* (i) 12bits: x vector                       */
		Word16 y[],                           /* (i) 12bits: y vector                       */
		Word16 lg,                            /* (i)    : vector length                     */
		Word16 * exp                          /* (o)    : exponent of result (0..+30)       */
		);
#endif //__MATH_OP_H__

