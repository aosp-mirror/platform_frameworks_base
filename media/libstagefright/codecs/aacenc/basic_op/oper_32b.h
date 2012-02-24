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
/*******************************************************************************
	File:		oper_32b.h

	Content:	Double precision operations

*******************************************************************************/

#ifndef __OPER_32b_H
#define __OPER_32b_H

#include "typedef.h"

#ifdef __cplusplus
extern "C" {
#endif

#define POW2_TABLE_BITS 8
#define POW2_TABLE_SIZE (1<<POW2_TABLE_BITS)

void L_Extract (Word32 L_32, Word16 *hi, Word16 *lo);
Word32 L_Comp (Word16 hi, Word16 lo);
Word32 Mpy_32 (Word16 hi1, Word16 lo1, Word16 hi2, Word16 lo2);
Word32 Mpy_32_16 (Word16 hi, Word16 lo, Word16 n);
Word32 Div_32 (Word32 L_num, Word32 denom);
Word16 iLog4(Word32 value);
Word32 rsqrt(Word32 value,  Word32 accuracy);
Word32 pow2_xy(Word32 x, Word32 y);

__inline Word32 L_mpy_ls(Word32 L_var2, Word16 var1)
{
    unsigned short swLow1;
    Word16 swHigh1;
    Word32 l_var_out;

    swLow1 = (unsigned short)(L_var2);
    swHigh1 = (Word16)(L_var2 >> 16);

    l_var_out = (long)swLow1 * (long)var1 >> 15;

    l_var_out += swHigh1 * var1 << 1;

    return(l_var_out);
}

__inline Word32 L_mpy_wx(Word32 L_var2, Word16 var1)
{
#if ARMV5TE_L_MPY_LS
	Word32 result;
	asm volatile(
		"SMULWB  %[result], %[L_var2], %[var1] \n"
		:[result]"=r"(result)
		:[L_var2]"r"(L_var2), [var1]"r"(var1)
		);
	return result;
#else
    unsigned short swLow1;
    Word16 swHigh1;
    Word32 l_var_out;

    swLow1 = (unsigned short)(L_var2);
    swHigh1 = (Word16)(L_var2 >> 16);

    l_var_out = (long)swLow1 * (long)var1 >> 16;
    l_var_out += swHigh1 * var1;

    return(l_var_out);
#endif
}

#ifdef __cplusplus
}
#endif

#endif
