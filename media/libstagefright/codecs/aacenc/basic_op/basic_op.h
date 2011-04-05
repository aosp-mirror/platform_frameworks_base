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
	File:		basicop2.h

	Content:	Constants , Globals and Basic arithmetic operators.

*******************************************************************************/

#ifndef __BASIC_OP_H
#define __BASIC_OP_H

#include "typedef.h"

#define MAX_32 (Word32)0x7fffffffL
#define MIN_32 (Word32)0x80000000L

#define MAX_16 (Word16)0x7fff
#define MIN_16 (Word16)0x8000
#define ABS(a)	((a) >= 0) ? (a) : (-(a))

/* Short abs,           1   */
#define abs_s(x)       ((Word16)(((x) != MIN_16) ? (((x) >= 0) ? (x) : (-(x))) : MAX_16))

/* 16 bit var1 -> MSB,     2 */
#define L_deposit_h(x) (((Word32)(x)) << 16)


/* 16 bit var1 -> LSB,     2 */
#define L_deposit_l(x) ((Word32)(x))


/* Long abs,              3  */
#define L_abs(x) (((x) != MIN_32) ? (((x) >= 0) ? (x) : (-(x))) : MAX_32)


/* Short negate,        1   */
#define negate(var1) ((Word16)(((var1) == MIN_16) ? MAX_16 : (-(var1))))


/* Long negate,     2 */
#define L_negate(L_var1) (((L_var1) == (MIN_32)) ? (MAX_32) : (-(L_var1)))


#define MULHIGH(A,B) (int)(((Word64)(A)*(Word64)(B)) >> 32)
#define fixmul(a, b) (int)((((Word64)(a)*(Word64)(b)) >> 32) << 1)


#if  (SATRUATE_IS_INLINE)
__inline Word16 saturate(Word32 L_var1);
#else
Word16 saturate(Word32 L_var1);
#endif

/* Short shift left,    1   */
#if (SHL_IS_INLINE)
__inline Word16 shl (Word16 var1, Word16 var2);
#else
Word16 shl (Word16 var1, Word16 var2);
#endif

/* Short shift right,   1   */
#if (SHR_IS_INLINE)
__inline Word16 shr (Word16 var1, Word16 var2);
#else
Word16 shr (Word16 var1, Word16 var2);
#endif

#if (L_MULT_IS_INLINE)
__inline Word32 L_mult(Word16 var1, Word16 var2);
#else
Word32 L_mult(Word16 var1, Word16 var2);
#endif

/* Msu,  1  */
#if (L_MSU_IS_INLINE)
__inline Word32 L_msu (Word32 L_var3, Word16 var1, Word16 var2);
#else
Word32 L_msu (Word32 L_var3, Word16 var1, Word16 var2);
#endif

/* Long sub,        2 */
#if (L_SUB_IS_INLINE)
__inline Word32 L_sub(Word32 L_var1, Word32 L_var2);
#else
Word32 L_sub(Word32 L_var1, Word32 L_var2);
#endif

/* Long shift left, 2 */
#if (L_SHL_IS_INLINE)
__inline Word32 L_shl (Word32 L_var1, Word16 var2);
#else
Word32 L_shl (Word32 L_var1, Word16 var2);
#endif

/* Long shift right, 2*/
#if (L_SHR_IS_INLINE)
__inline Word32 L_shr (Word32 L_var1, Word16 var2);
#else
Word32 L_shr (Word32 L_var1, Word16 var2);
#endif

/* Short add,           1   */
#if (ADD_IS_INLINE)
__inline Word16 add (Word16 var1, Word16 var2);
#else
Word16 add (Word16 var1, Word16 var2);
#endif

/* Short sub,           1   */
#if (SUB_IS_INLINE)
__inline Word16 sub(Word16 var1, Word16 var2);
#else
Word16 sub(Word16 var1, Word16 var2);
#endif

/* Short division,       18  */
#if (DIV_S_IS_INLINE)
__inline Word16 div_s (Word16 var1, Word16 var2);
#else
Word16 div_s (Word16 var1, Word16 var2);
#endif

/* Short mult,          1   */
#if (MULT_IS_INLINE)
__inline Word16 mult (Word16 var1, Word16 var2);
#else
Word16 mult (Word16 var1, Word16 var2);
#endif

/* Short norm,           15  */
#if (NORM_S_IS_INLINE)
__inline Word16 norm_s (Word16 var1);
#else
Word16 norm_s (Word16 var1);
#endif

/* Long norm,            30  */
#if (NORM_L_IS_INLINE)
__inline Word16 norm_l (Word32 L_var1);
#else
Word16 norm_l (Word32 L_var1);
#endif

/* Round,               1   */
#if (ROUND_IS_INLINE)
__inline Word16 round16(Word32 L_var1);
#else
Word16 round16(Word32 L_var1);
#endif

/* Mac,  1  */
#if (L_MAC_IS_INLINE)
__inline Word32 L_mac (Word32 L_var3, Word16 var1, Word16 var2);
#else
Word32 L_mac (Word32 L_var3, Word16 var1, Word16 var2);
#endif

#if (L_ADD_IS_INLINE)
__inline Word32 L_add (Word32 L_var1, Word32 L_var2);
#else
Word32 L_add (Word32 L_var1, Word32 L_var2);
#endif

/* Extract high,        1   */
#if (EXTRACT_H_IS_INLINE)
__inline Word16 extract_h (Word32 L_var1);
#else
Word16 extract_h (Word32 L_var1);
#endif

/* Extract low,         1   */
#if (EXTRACT_L_IS_INLINE)
__inline Word16 extract_l(Word32 L_var1);
#else
Word16 extract_l(Word32 L_var1);
#endif

/* Mult with round, 2 */
#if (MULT_R_IS_INLINE)
__inline Word16 mult_r(Word16 var1, Word16 var2);
#else
Word16 mult_r(Word16 var1, Word16 var2);
#endif

/* Shift right with round, 2           */
#if (SHR_R_IS_INLINE)
__inline Word16 shr_r (Word16 var1, Word16 var2);
#else
Word16 shr_r (Word16 var1, Word16 var2);
#endif

/* Mac with rounding,2 */
#if (MAC_R_IS_INLINE)
__inline Word16 mac_r (Word32 L_var3, Word16 var1, Word16 var2);
#else
Word16 mac_r (Word32 L_var3, Word16 var1, Word16 var2);
#endif

/* Msu with rounding,2 */
#if (MSU_R_IS_INLINE)
__inline Word16 msu_r (Word32 L_var3, Word16 var1, Word16 var2);
#else
Word16 msu_r (Word32 L_var3, Word16 var1, Word16 var2);
#endif

/* Long shift right with round,  3             */
#if (L_SHR_R_IS_INLINE)
__inline Word32 L_shr_r (Word32 L_var1, Word16 var2);
#else
Word32 L_shr_r (Word32 L_var1, Word16 var2);
#endif

#if ARMV4_INASM
__inline Word32 ASM_L_shr(Word32 L_var1, Word16 var2)
{
	Word32 result;
	asm volatile(
		"MOV %[result], %[L_var1], ASR %[var2] \n"
		:[result]"=r"(result)
		:[L_var1]"r"(L_var1), [var2]"r"(var2)
		);
	return result;
}

__inline Word32 ASM_L_shl(Word32 L_var1, Word16 var2)
{
	Word32 result;
	asm volatile(
		"MOV	r2, %[L_var1] \n"
		"MOV	r3, #0x7fffffff\n"
		"MOV	%[result], %[L_var1], ASL %[var2] \n"
		"TEQ	r2, %[result], ASR %[var2]\n"
		"EORNE  %[result],r3,r2,ASR#31\n"
		:[result]"+r"(result)
		:[L_var1]"r"(L_var1), [var2]"r"(var2)
		:"r2", "r3"
		);
	return result;
}

__inline Word32 ASM_shr(Word32 L_var1, Word16 var2)
{
	Word32 result;
	asm volatile(
		"CMP	%[var2], #15\n"
		"MOVGE  %[var2], #15\n"
		"MOV	%[result], %[L_var1], ASR %[var2]\n"
		:[result]"=r"(result)
		:[L_var1]"r"(L_var1), [var2]"r"(var2)
		);
	return result;
}

__inline Word32 ASM_shl(Word32 L_var1, Word16 var2)
{
	Word32 result;
	asm volatile(
		"CMP	%[var2], #16\n"
		"MOVGE  %[var2], #16\n"
		"MOV    %[result], %[L_var1], ASL %[var2]\n"
		"MOV    r3, #1\n"
        "MOV    r2, %[result], ASR #15\n"
        "RSB    r3,r3,r3,LSL #15 \n"
        "TEQ    r2, %[result], ASR #31 \n"
        "EORNE  %[result], r3, %[result],ASR #31"
		:[result]"+r"(result)
		:[L_var1]"r"(L_var1), [var2]"r"(var2)
		:"r2", "r3"
		);
	return result;
}
#endif

/*___________________________________________________________________________
 |                                                                           |
 |   definitions for inline basic arithmetic operators                       |
 |___________________________________________________________________________|
*/
#if (SATRUATE_IS_INLINE)
__inline Word16 saturate(Word32 L_var1)
{
#if ARMV5TE_SAT
	Word16 result;
	asm volatile (
		"MOV	%[result], %[L_var1]\n"
		"MOV	r3, #1\n"
		"MOV	r2,%[L_var1],ASR#15\n"
		"RSB	r3, r3, r3, LSL #15\n"
		"TEQ	r2,%[L_var1],ASR#31\n"
		"EORNE	%[result],r3,%[L_var1],ASR#31\n"
		:[result]"+r"(result)
		:[L_var1]"r"(L_var1)
		:"r2", "r3"
	);

	return result;
#else
    Word16 var_out;

    //var_out = (L_var1 > (Word32)0X00007fffL) ? (MAX_16) : ((L_var1 < (Word32)0xffff8000L) ? (MIN_16) : ((Word16)L_var1));

    if (L_var1 > 0X00007fffL)
    {
        var_out = MAX_16;
    }
    else if (L_var1 < (Word32) 0xffff8000L)
    {
        var_out = MIN_16;
    }
    else
    {
        var_out = extract_l(L_var1);
    }

    return (var_out);
#endif
}
#endif

/* Short shift left,    1   */
#if (SHL_IS_INLINE)
__inline Word16 shl (Word16 var1, Word16 var2)
{
#if ARMV5TE_SHL
	if(var2>=0)
	{
		return ASM_shl( var1, var2);
	}
	else
	{
		return ASM_shr( var1, -var2);
	}
#else
    Word16 var_out;
    Word32 result;

    if (var2 < 0)
    {
        var_out = shr (var1, (Word16)-var2);
    }
    else
    {
        result = (Word32) var1 *((Word32) 1 << var2);

        if ((var2 > 15 && var1 != 0) || (result != (Word32) ((Word16) result)))
        {
            var_out = (Word16)((var1 > 0) ? MAX_16 : MIN_16);
        }
        else
        {
            var_out = extract_l(result);
        }
    }
    return (var_out);
#endif
}
#endif

/* Short shift right,   1   */
#if (SHR_IS_INLINE)
__inline Word16 shr (Word16 var1, Word16 var2)
{
#if ARMV5TE_SHR
	if(var2>=0)
	{
		return  ASM_shr( var1, var2);
	}
	else
	{
		return  ASM_shl( var1, -var2);
	}
#else
    Word16 var_out;

    if (var2 < 0)
    {
        var_out = shl (var1, (Word16)-var2);
    }
    else
    {
        if (var2 >= 15)
        {
            var_out = (Word16)((var1 < 0) ? -1 : 0);
        }
        else
        {
            if (var1 < 0)
            {
                var_out = (Word16)(~((~var1) >> var2));
            }
            else
            {
                var_out = (Word16)(var1 >> var2);
            }
        }
    }

    return (var_out);
#endif
}
#endif


#if (L_MULT_IS_INLINE)
__inline Word32 L_mult(Word16 var1, Word16 var2)
{
#if ARMV5TE_L_MULT
	Word32 result;
	asm volatile(
		"SMULBB %[result], %[var1], %[var2] \n"
		"QADD %[result], %[result], %[result] \n"
		:[result]"+r"(result)
		:[var1]"r"(var1), [var2]"r"(var2)
		);
	return result;
#else
    Word32 L_var_out;

    L_var_out = (Word32) var1 *(Word32) var2;

    if (L_var_out != (Word32) 0x40000000L)
    {
        L_var_out <<= 1;
    }
    else
    {
        L_var_out = MAX_32;
    }
    return (L_var_out);
#endif
}
#endif

#if (L_MSU_IS_INLINE)
__inline Word32 L_msu (Word32 L_var3, Word16 var1, Word16 var2)
{
#if ARMV5TE_L_MSU
	Word32 result;
	asm volatile(
		"SMULBB %[result], %[var1], %[var2] \n"
		"QADD %[result], %[result], %[result] \n"
		"QSUB %[result], %[L_var3], %[result]\n"
		:[result]"+r"(result)
		:[L_var3]"r"(L_var3), [var1]"r"(var1), [var2]"r"(var2)
		);
	return result;
#else
    Word32 L_var_out;
    Word32 L_product;

    L_product = L_mult(var1, var2);
    L_var_out = L_sub(L_var3, L_product);
    return (L_var_out);
#endif
}
#endif

#if (L_SUB_IS_INLINE)
__inline Word32 L_sub(Word32 L_var1, Word32 L_var2)
{
#if ARMV5TE_L_SUB
	Word32 result;
	asm volatile(
		"QSUB %[result], %[L_var1], %[L_var2]\n"
		:[result]"+r"(result)
		:[L_var1]"r"(L_var1), [L_var2]"r"(L_var2)
		);
	return result;
#else
    Word32 L_var_out;

    L_var_out = L_var1 - L_var2;

    if (((L_var1 ^ L_var2) & MIN_32) != 0)
    {
        if ((L_var_out ^ L_var1) & MIN_32)
        {
            L_var_out = (L_var1 < 0L) ? MIN_32 : MAX_32;
        }
    }

    return (L_var_out);
#endif
}
#endif

#if (L_SHL_IS_INLINE)
__inline Word32 L_shl(Word32 L_var1, Word16 var2)
{
#if ARMV5TE_L_SHL
    if(var2>=0)
    {
        return  ASM_L_shl( L_var1, var2);
    }
    else
    {
        return  ASM_L_shr( L_var1, -var2);
    }
#else
    Word32 L_var_out = 0L;

    if (var2 <= 0)
    {
        L_var1 = L_shr(L_var1, (Word16)-var2);
    }
    else
    {
        for (; var2 > 0; var2--)
        {
            if (L_var1 > (Word32) 0X3fffffffL)
            {
                return MAX_32;
            }
            else
            {
                if (L_var1 < (Word32) 0xc0000000L)
                {
                    return MIN_32;
                }
            }
            L_var1 <<= 1;
            L_var_out = L_var1;
        }
    }
    return (L_var1);
#endif
}
#endif

#if (L_SHR_IS_INLINE)
__inline Word32 L_shr (Word32 L_var1, Word16 var2)
{
#if ARMV5TE_L_SHR
	if(var2>=0)
	{
		return ASM_L_shr( L_var1, var2);
	}
	else
	{
		return ASM_L_shl( L_var1, -var2);
	}
#else
    Word32 L_var_out;

    if (var2 < 0)
    {
        L_var_out = L_shl (L_var1, (Word16)-var2);
    }
    else
    {
        if (var2 >= 31)
        {
            L_var_out = (L_var1 < 0L) ? -1 : 0;
        }
        else
        {
            if (L_var1 < 0)
            {
                L_var_out = ~((~L_var1) >> var2);
            }
            else
            {
                L_var_out = L_var1 >> var2;
            }
        }
    }
    return (L_var_out);
#endif
}
#endif

/* Short add,           1   */
#if (ADD_IS_INLINE)
__inline Word16 add (Word16 var1, Word16 var2)
{
#if ARMV5TE_ADD
	Word32 result;
	asm volatile(
		"ADD  %[result], %[var1], %[var2] \n"
		"MOV  r3, #0x1\n"
		"MOV  r2, %[result], ASR #15\n"
		"RSB  r3, r3, r3, LSL, #15\n"
		"TEQ  r2, %[result], ASR #31\n"
		"EORNE %[result], r3, %[result], ASR #31"
		:[result]"+r"(result)
		:[var1]"r"(var1), [var2]"r"(var2)
		:"r2", "r3"
		);
	return result;
#else
    Word16 var_out;
    Word32 L_sum;

    L_sum = (Word32) var1 + var2;
    var_out = saturate(L_sum);

    return (var_out);
#endif
}
#endif

/* Short sub,           1   */
#if (SUB_IS_INLINE)
__inline Word16 sub(Word16 var1, Word16 var2)
{
#if ARMV5TE_SUB
	Word32 result;
	asm volatile(
		"MOV   r3, #1\n"
		"SUB   %[result], %[var1], %[var2] \n"
		"RSB   r3,r3,r3,LSL#15\n"
		"MOV   r2, %[var1], ASR #15 \n"
		"TEQ   r2, %[var1], ASR #31 \n"
		"EORNE %[result], r3, %[result], ASR #31 \n"
		:[result]"+r"(result)
		:[var1]"r"(var1), [var2]"r"(var2)
		:"r2", "r3"
		);
	return result;
#else
    Word16 var_out;
    Word32 L_diff;

    L_diff = (Word32) var1 - var2;
    var_out = saturate(L_diff);

    return (var_out);
#endif
}
#endif

/* Short division,       18  */
#if (DIV_S_IS_INLINE)
__inline Word16 div_s (Word16 var1, Word16 var2)
{
    Word16 var_out = 0;
    Word16 iteration;
    Word32 L_num;
    Word32 L_denom;

    var_out = MAX_16;
    if (var1!= var2)//var1!= var2
    {
    	var_out = 0;
    	L_num = (Word32) var1;

    	L_denom = (Word32) var2;

		//return (L_num<<15)/var2;

    	for (iteration = 0; iteration < 15; iteration++)
    	{
    		var_out <<= 1;
    		L_num <<= 1;

    		if (L_num >= L_denom)
    		{
    			L_num -= L_denom;
    			var_out++;
    		}
    	}
    }
    return (var_out);
}
#endif

/* Short mult,          1   */
#if (MULT_IS_INLINE)
__inline Word16 mult (Word16 var1, Word16 var2)
{
#if ARMV5TE_MULT
	Word32 result;
	asm volatile(
		"SMULBB r2, %[var1], %[var2] \n"
		"MOV	r3, #1\n"
		"MOV	%[result], r2, ASR #15\n"
		"RSB	r3, r3, r3, LSL #15\n"
		"MOV	r2, %[result], ASR #15\n"
		"TEQ	r2, %[result], ASR #31\n"
		"EORNE  %[result], r3, %[result], ASR #31 \n"
		:[result]"+r"(result)
		:[var1]"r"(var1), [var2]"r"(var2)
		:"r2", "r3"
		);
	return result;
#else
    Word16 var_out;
    Word32 L_product;

    L_product = (Word32) var1 *(Word32) var2;
    L_product = (L_product & (Word32) 0xffff8000L) >> 15;
    if (L_product & (Word32) 0x00010000L)
        L_product = L_product | (Word32) 0xffff0000L;
    var_out = saturate(L_product);

    return (var_out);
#endif
}
#endif


/* Short norm,           15  */
#if (NORM_S_IS_INLINE)
__inline Word16 norm_s (Word16 var1)
{
#if ARMV5TE_NORM_S
	Word16 result;
	asm volatile(
		"MOV   r2,%[var1] \n"
		"CMP   r2, #0\n"
		"RSBLT %[var1], %[var1], #0 \n"
		"CLZNE %[result], %[var1]\n"
		"SUBNE %[result], %[result], #17\n"
		"MOVEQ %[result], #0\n"
		"CMP   r2, #-1\n"
		"MOVEQ %[result], #15\n"
		:[result]"+r"(result)
		:[var1]"r"(var1)
		:"r2"
		);
	return result;
#else
    Word16 var_out;

    if (var1 == 0)
    {
        var_out = 0;
    }
    else
    {
        if (var1 == -1)
        {
            var_out = 15;
        }
        else
        {
            if (var1 < 0)
            {
                var1 = (Word16)~var1;
            }
            for (var_out = 0; var1 < 0x4000; var_out++)
            {
                var1 <<= 1;
            }
        }
    }
    return (var_out);
#endif
}
#endif

/* Long norm,            30  */
#if (NORM_L_IS_INLINE)
__inline Word16 norm_l (Word32 L_var1)
{
#if ARMV5TE_NORM_L
	Word16 result;
	asm volatile(
		"CMP    %[L_var1], #0\n"
		"CLZNE  %[result], %[L_var1]\n"
		"SUBNE  %[result], %[result], #1\n"
		"MOVEQ  %[result], #0\n"
		:[result]"+r"(result)
		:[L_var1]"r"(L_var1)
		);
	return result;
#else
    //Word16 var_out;

    //if (L_var1 == 0)
    //{
    //    var_out = 0;
    //}
    //else
    //{
    //    if (L_var1 == (Word32) 0xffffffffL)
    //    {
    //        var_out = 31;
    //    }
    //    else
    //    {
    //        if (L_var1 < 0)
    //        {
    //            L_var1 = ~L_var1;
    //        }
    //        for (var_out = 0; L_var1 < (Word32) 0x40000000L; var_out++)
    //        {
    //            L_var1 <<= 1;
    //        }
    //    }
    //}
    //return (var_out);
  Word16 a16;
  Word16 r = 0 ;


  if ( L_var1 < 0 ) {
    L_var1 = ~L_var1;
  }

  if (0 == (L_var1 & 0x7fff8000)) {
    a16 = extract_l(L_var1);
    r += 16;

    if (0 == (a16 & 0x7f80)) {
      r += 8;

      if (0 == (a16 & 0x0078)) {
        r += 4;

        if (0 == (a16 & 0x0006)) {
          r += 2;

          if (0 == (a16 & 0x0001)) {
            r += 1;
          }
        }
        else {

          if (0 == (a16 & 0x0004)) {
            r += 1;
          }
        }
      }
      else {

        if (0 == (a16 & 0x0060)) {
          r += 2;

          if (0 == (a16 & 0x0010)) {
            r += 1;
          }
        }
        else {

          if (0 == (a16 & 0x0040)) {
            r += 1;
          }
        }
      }
    }
    else {

      if (0 == (a16 & 0x7800)) {
        r += 4;

        if (0 == (a16 & 0x0600)) {
          r += 2;

          if (0 == (a16 & 0x0100)) {
            r += 1;
          }
        }
        else {

          if (0 == (a16 & 0x0400)) {
            r += 1;
          }
        }
      }
      else {

        if (0 == (a16 & 0x6000)) {
          r += 2;

          if (0 == (a16 & 0x1000)) {
            r += 1;
          }
        }
        else {

          if (0 == (a16 & 0x4000)) {
            r += 1;
          }
        }
      }
    }
  }
  else {
    a16 = extract_h(L_var1);

    if (0 == (a16 & 0x7f80)) {
      r += 8;

      if (0 == (a16 & 0x0078)) {
        r += 4 ;

        if (0 == (a16 & 0x0006)) {
          r += 2;

          if (0 == (a16 & 0x0001)) {
            r += 1;
          }
        }
        else {

          if (0 == (a16 & 0x0004)) {
            r += 1;
          }
        }
      }
      else {

        if (0 == (a16 & 0x0060)) {
          r += 2;

          if (0 == (a16 & 0x0010)) {
            r += 1;
          }
        }
        else {

          if (0 == (a16 & 0x0040)) {
            r += 1;
          }
        }
      }
    }
    else {

      if (0 == (a16 & 0x7800)) {
        r += 4;

        if (0 == (a16 & 0x0600)) {
          r += 2;

          if (0 == (a16 & 0x0100)) {
            r += 1;
          }
        }
        else {

          if (0 == (a16 & 0x0400)) {
            r += 1;
          }
        }
      }
      else {

        if (0 == (a16 & 0x6000)) {
          r += 2;

          if (0 == (a16 & 0x1000)) {
            r += 1;
          }
        }
        else {

          if (0 == (a16 & 0x4000)) {
            return 1;
          }
        }
      }
    }
  }

  return r ;
#endif
}
#endif

/* Round,               1   */
#if (ROUND_IS_INLINE)
__inline Word16 round16(Word32 L_var1)
{
#if ARMV5TE_ROUND
	Word16 result;
	asm volatile(
		"MOV   r1,#0x00008000\n"
		"QADD  %[result], %[L_var1], r1\n"
		"MOV   %[result], %[result], ASR #16 \n"
		:[result]"+r"(result)
		:[L_var1]"r"(L_var1)
		:"r1"
		);
	return result;
#else
    Word16 var_out;
    Word32 L_rounded;

    L_rounded = L_add (L_var1, (Word32) 0x00008000L);
    var_out = extract_h (L_rounded);
    return (var_out);
#endif
}
#endif

/* Mac,  1  */
#if (L_MAC_IS_INLINE)
__inline Word32 L_mac (Word32 L_var3, Word16 var1, Word16 var2)
{
#if ARMV5TE_L_MAC
	Word32 result;
	asm volatile(
		"SMULBB %[result], %[var1], %[var2]\n"
		"QADD	%[result], %[result], %[result]\n"
		"QADD   %[result], %[result], %[L_var3]\n"
		:[result]"+r"(result)
		: [L_var3]"r"(L_var3), [var1]"r"(var1), [var2]"r"(var2)
		);
	return result;
#else
    Word32 L_var_out;
    Word32 L_product;

    L_product = L_mult(var1, var2);
    L_var_out = L_add (L_var3, L_product);
    return (L_var_out);
#endif
}
#endif

#if (L_ADD_IS_INLINE)
__inline Word32 L_add (Word32 L_var1, Word32 L_var2)
{
#if ARMV5TE_L_ADD
	Word32 result;
	asm volatile(
		"QADD %[result], %[L_var1], %[L_var2]\n"
		:[result]"+r"(result)
		:[L_var1]"r"(L_var1), [L_var2]"r"(L_var2)
		);
	return result;
#else
    Word32 L_var_out;

    L_var_out = L_var1 + L_var2;
    if (((L_var1 ^ L_var2) & MIN_32) == 0)
    {
        if ((L_var_out ^ L_var1) & MIN_32)
        {
            L_var_out = (L_var1 < 0) ? MIN_32 : MAX_32;
        }
    }
    return (L_var_out);
#endif
}
#endif



#if (MULT_R_IS_INLINE)
__inline Word16 mult_r (Word16 var1, Word16 var2)
{
    Word16 var_out;
    Word32 L_product_arr;

    L_product_arr = (Word32)var1 *(Word32)var2;       /* product */
    L_product_arr += (Word32)0x00004000L;      /* round */
    L_product_arr >>= 15;       /* shift */

    var_out = saturate(L_product_arr);

    return (var_out);
}
#endif

#if (SHR_R_IS_INLINE)
__inline Word16 shr_r (Word16 var1, Word16 var2)
{
    Word16 var_out;

    if (var2 > 15)
    {
        var_out = 0;
    }
    else
    {
        var_out = shr(var1, var2);

        if (var2 > 0)
        {
            if ((var1 & ((Word16) 1 << (var2 - 1))) != 0)
            {
                var_out++;
            }
        }
    }

    return (var_out);
}
#endif

#if (MAC_R_IS_INLINE)
__inline Word16 mac_r (Word32 L_var3, Word16 var1, Word16 var2)
{
    Word16 var_out;

    L_var3 = L_mac (L_var3, var1, var2);
    var_out = (Word16)((L_var3 + 0x8000L) >> 16);

    return (var_out);
}
#endif

#if (MSU_R_IS_INLINE)
__inline Word16 msu_r (Word32 L_var3, Word16 var1, Word16 var2)
{
    Word16 var_out;

    L_var3 = L_msu (L_var3, var1, var2);
    var_out = (Word16)((L_var3 + 0x8000L) >> 16);

    return (var_out);
}
#endif

#if (L_SHR_R_IS_INLINE)
__inline Word32 L_shr_r (Word32 L_var1, Word16 var2)
{
    Word32 L_var_out;

    if (var2 > 31)
    {
        L_var_out = 0;
    }
    else
    {
        L_var_out = L_shr(L_var1, var2);

        if (var2 > 0)
        {
            if ((L_var1 & ((Word32) 1 << (var2 - 1))) != 0)
            {
                L_var_out++;
            }
        }
    }

    return (L_var_out);
}
#endif

#if (EXTRACT_H_IS_INLINE)
__inline Word16 extract_h (Word32 L_var1)
{
    Word16 var_out;

    var_out = (Word16) (L_var1 >> 16);

    return (var_out);
}
#endif

#if (EXTRACT_L_IS_INLINE)
__inline Word16 extract_l(Word32 L_var1)
{
	return (Word16) L_var1;
}
#endif

#endif
