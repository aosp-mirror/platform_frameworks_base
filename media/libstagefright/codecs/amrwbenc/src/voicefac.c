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
*   File: voicefac.c                                                   *
*                                                                      *
*   Description: Find the voicing factors (1 = voice to -1 = unvoiced) *
*                                                                      *
************************************************************************/

#include "typedef.h"
#include "basic_op.h"
#include "math_op.h"

Word16 voice_factor(                                  /* (o) Q15   : factor (-1=unvoiced to 1=voiced) */
		Word16 exc[],                         /* (i) Q_exc : pitch excitation                 */
		Word16 Q_exc,                         /* (i)       : exc format                       */
		Word16 gain_pit,                      /* (i) Q14   : gain of pitch                    */
		Word16 code[],                        /* (i) Q9    : Fixed codebook excitation        */
		Word16 gain_code,                     /* (i) Q0    : gain of code                     */
		Word16 L_subfr                        /* (i)       : subframe length                  */
		)
{
	Word16 tmp, exp, ener1, exp1, ener2, exp2;
	Word32 i, L_tmp;

#ifdef ASM_OPT               /* asm optimization branch */
	ener1 = extract_h(Dot_product12_asm(exc, exc, L_subfr, &exp1));
#else
	ener1 = extract_h(Dot_product12(exc, exc, L_subfr, &exp1));
#endif
	exp1 = exp1 - (Q_exc + Q_exc);
	L_tmp = vo_L_mult(gain_pit, gain_pit);
	exp = norm_l(L_tmp);
	tmp = extract_h(L_tmp << exp);
	ener1 = vo_mult(ener1, tmp);
	exp1 = exp1 - exp - 10;        /* 10 -> gain_pit Q14 to Q9 */

#ifdef ASM_OPT                /* asm optimization branch */
	ener2 = extract_h(Dot_product12_asm(code, code, L_subfr, &exp2));
#else
	ener2 = extract_h(Dot_product12(code, code, L_subfr, &exp2));
#endif

	exp = norm_s(gain_code);
	tmp = gain_code << exp;
	tmp = vo_mult(tmp, tmp);
	ener2 = vo_mult(ener2, tmp);
	exp2 = exp2 - (exp + exp);

	i = exp1 - exp2;

	if (i >= 0)
	{
		ener1 = ener1 >> 1;
		ener2 = ener2 >> (i + 1);
	} else
	{
		ener1 = ener1 >> (1 - i);
		ener2 = ener2 >> 1;
	}

	tmp = vo_sub(ener1, ener2);
	ener1 = add1(add1(ener1, ener2), 1);

	if (tmp >= 0)
	{
		tmp = div_s(tmp, ener1);
	} else
	{
		tmp = vo_negate(div_s(vo_negate(tmp), ener1));
	}

	return (tmp);
}




