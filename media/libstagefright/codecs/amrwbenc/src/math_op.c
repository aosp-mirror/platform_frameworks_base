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

/*___________________________________________________________________________
|                                                                           |
|  This file contains mathematic operations in fixed point.                 |
|                                                                           |
|  Isqrt()              : inverse square root (16 bits precision).          |
|  Pow2()               : 2^x  (16 bits precision).                         |
|  Log2()               : log2 (16 bits precision).                         |
|  Dot_product()        : scalar product of <x[],y[]>                       |
|                                                                           |
|  These operations are not standard double precision operations.           |
|  They are used where low complexity is important and the full 32 bits     |
|  precision is not necessary. For example, the function Div_32() has a     |
|  24 bits precision which is enough for our purposes.                      |
|                                                                           |
|  In this file, the values use theses representations:                     |
|                                                                           |
|  Word32 L_32     : standard signed 32 bits format                         |
|  Word16 hi, lo   : L_32 = hi<<16 + lo<<1  (DPF - Double Precision Format) |
|  Word32 frac, Word16 exp : L_32 = frac << exp-31  (normalised format)     |
|  Word16 int, frac        : L_32 = int.frac        (fractional format)     |
|___________________________________________________________________________|
*/
#include "typedef.h"
#include "basic_op.h"
#include "math_op.h"

/*___________________________________________________________________________
|                                                                           |
|   Function Name : Isqrt                                                   |
|                                                                           |
|       Compute 1/sqrt(L_x).                                                |
|       if L_x is negative or zero, result is 1 (7fffffff).                 |
|---------------------------------------------------------------------------|
|  Algorithm:                                                               |
|                                                                           |
|   1- Normalization of L_x.                                                |
|   2- call Isqrt_n(L_x, exponant)                                          |
|   3- L_y = L_x << exponant                                                |
|___________________________________________________________________________|
*/
Word32 Isqrt(                              /* (o) Q31 : output value (range: 0<=val<1)         */
		Word32 L_x                            /* (i) Q0  : input value  (range: 0<=val<=7fffffff) */
	    )
{
	Word16 exp;
	Word32 L_y;
	exp = norm_l(L_x);
	L_x = (L_x << exp);                 /* L_x is normalized */
	exp = (31 - exp);
	Isqrt_n(&L_x, &exp);
	L_y = (L_x << exp);                 /* denormalization   */
	return (L_y);
}

/*___________________________________________________________________________
|                                                                           |
|   Function Name : Isqrt_n                                                 |
|                                                                           |
|       Compute 1/sqrt(value).                                              |
|       if value is negative or zero, result is 1 (frac=7fffffff, exp=0).   |
|---------------------------------------------------------------------------|
|  Algorithm:                                                               |
|                                                                           |
|   The function 1/sqrt(value) is approximated by a table and linear        |
|   interpolation.                                                          |
|                                                                           |
|   1- If exponant is odd then shift fraction right once.                   |
|   2- exponant = -((exponant-1)>>1)                                        |
|   3- i = bit25-b30 of fraction, 16 <= i <= 63 ->because of normalization. |
|   4- a = bit10-b24                                                        |
|   5- i -=16                                                               |
|   6- fraction = table[i]<<16 - (table[i] - table[i+1]) * a * 2            |
|___________________________________________________________________________|
*/
static Word16 table_isqrt[49] =
{
	32767, 31790, 30894, 30070, 29309, 28602, 27945, 27330, 26755, 26214,
	25705, 25225, 24770, 24339, 23930, 23541, 23170, 22817, 22479, 22155,
	21845, 21548, 21263, 20988, 20724, 20470, 20225, 19988, 19760, 19539,
	19326, 19119, 18919, 18725, 18536, 18354, 18176, 18004, 17837, 17674,
	17515, 17361, 17211, 17064, 16921, 16782, 16646, 16514, 16384
};

void Isqrt_n(
		Word32 * frac,                        /* (i/o) Q31: normalized value (1.0 < frac <= 0.5) */
		Word16 * exp                          /* (i/o)    : exponent (value = frac x 2^exponent) */
	    )
{
	Word16 i, a, tmp;

	if (*frac <= (Word32) 0)
	{
		*exp = 0;
		*frac = 0x7fffffffL;
		return;
	}

	if((*exp & 1) == 1)                       /*If exponant odd -> shift right */
		*frac = (*frac) >> 1;

	*exp = negate((*exp - 1) >> 1);

	*frac = (*frac >> 9);
	i = extract_h(*frac);                  /* Extract b25-b31 */
	*frac = (*frac >> 1);
	a = (Word16)(*frac);                  /* Extract b10-b24 */
	a = (Word16) (a & (Word16) 0x7fff);
	i -= 16;
	*frac = L_deposit_h(table_isqrt[i]);   /* table[i] << 16         */
	tmp = vo_sub(table_isqrt[i], table_isqrt[i + 1]);      /* table[i] - table[i+1]) */
	*frac = vo_L_msu(*frac, tmp, a);          /* frac -=  tmp*a*2       */

	return;
}

/*___________________________________________________________________________
|                                                                           |
|   Function Name : Pow2()                                                  |
|                                                                           |
|     L_x = pow(2.0, exponant.fraction)         (exponant = interger part)  |
|         = pow(2.0, 0.fraction) << exponant                                |
|---------------------------------------------------------------------------|
|  Algorithm:                                                               |
|                                                                           |
|   The function Pow2(L_x) is approximated by a table and linear            |
|   interpolation.                                                          |
|                                                                           |
|   1- i = bit10-b15 of fraction,   0 <= i <= 31                            |
|   2- a = bit0-b9   of fraction                                            |
|   3- L_x = table[i]<<16 - (table[i] - table[i+1]) * a * 2                 |
|   4- L_x = L_x >> (30-exponant)     (with rounding)                       |
|___________________________________________________________________________|
*/
static Word16 table_pow2[33] =
{
	16384, 16743, 17109, 17484, 17867, 18258, 18658, 19066, 19484, 19911,
	20347, 20792, 21247, 21713, 22188, 22674, 23170, 23678, 24196, 24726,
	25268, 25821, 26386, 26964, 27554, 28158, 28774, 29405, 30048, 30706,
	31379, 32066, 32767
};

Word32 Pow2(                               /* (o) Q0  : result       (range: 0<=val<=0x7fffffff) */
		Word16 exponant,                      /* (i) Q0  : Integer part.      (range: 0<=val<=30)   */
		Word16 fraction                       /* (i) Q15 : Fractionnal part.  (range: 0.0<=val<1.0) */
	   )
{
	Word16 exp, i, a, tmp;
	Word32 L_x;

	L_x = vo_L_mult(fraction, 32);            /* L_x = fraction<<6           */
	i = extract_h(L_x);                    /* Extract b10-b16 of fraction */
	L_x =L_x >> 1;
	a = (Word16)(L_x);                    /* Extract b0-b9   of fraction */
	a = (Word16) (a & (Word16) 0x7fff);

	L_x = L_deposit_h(table_pow2[i]);      /* table[i] << 16        */
	tmp = vo_sub(table_pow2[i], table_pow2[i + 1]);        /* table[i] - table[i+1] */
	L_x -= (tmp * a)<<1;              /* L_x -= tmp*a*2        */

	exp = vo_sub(30, exponant);
	L_x = vo_L_shr_r(L_x, exp);

	return (L_x);
}

/*___________________________________________________________________________
|                                                                           |
|   Function Name : Dot_product12()                                         |
|                                                                           |
|       Compute scalar product of <x[],y[]> using accumulator.              |
|                                                                           |
|       The result is normalized (in Q31) with exponent (0..30).            |
|---------------------------------------------------------------------------|
|  Algorithm:                                                               |
|                                                                           |
|       dot_product = sum(x[i]*y[i])     i=0..N-1                           |
|___________________________________________________________________________|
*/

Word32 Dot_product12(                      /* (o) Q31: normalized result (1 < val <= -1) */
		Word16 x[],                           /* (i) 12bits: x vector                       */
		Word16 y[],                           /* (i) 12bits: y vector                       */
		Word16 lg,                            /* (i)    : vector length                     */
		Word16 * exp                          /* (o)    : exponent of result (0..+30)       */
		)
{
	Word16 sft;
	Word32 i, L_sum;
	L_sum = 0;
	for (i = 0; i < lg; i++)
	{
		L_sum += x[i] * y[i];
	}
	L_sum = (L_sum << 1) + 1;
	/* Normalize acc in Q31 */
	sft = norm_l(L_sum);
	L_sum = L_sum << sft;
	*exp = 30 - sft;            /* exponent = 0..30 */
	return (L_sum);

}


