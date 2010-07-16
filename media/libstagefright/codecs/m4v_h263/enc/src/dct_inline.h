/* ------------------------------------------------------------------
 * Copyright (C) 1998-2009 PacketVideo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 * -------------------------------------------------------------------
 */
/*  Filename: dct_inline.h                                                      */
/*  Description: Implementation for in-line functions used in dct.cpp           */
/*  Modified:                                                                   */
/*********************************************************************************/
#ifndef _DCT_INLINE_H_
#define _DCT_INLINE_H_

#if !defined(PV_ARM_GCC_V5) && !defined(PV_ARM_GCC_V4)

__inline int32 mla724(int32 op1, int32 op2, int32 op3)
{
    int32 out;

    OSCL_UNUSED_ARG(op1);

    out = op2 * 724 + op3; /* op1 is not used here */

    return out;
}

__inline int32 mla392(int32 k0, int32 k14, int32 round)
{
    int32 k1;

    OSCL_UNUSED_ARG(k14);

    k1 = k0 * 392 + round;

    return k1;
}

__inline int32 mla554(int32 k4, int32 k12, int32 k1)
{
    int32 k0;

    OSCL_UNUSED_ARG(k12);

    k0 = k4 * 554 + k1;

    return k0;
}

__inline int32 mla1338(int32 k6, int32 k14, int32 k1)
{
    int32 out;

    OSCL_UNUSED_ARG(k14);

    out = k6 * 1338 + k1;

    return out;
}

__inline int32 mla946(int32 k6, int32 k14, int32 k1)
{
    int32 out;

    OSCL_UNUSED_ARG(k14);

    out = k6 * 946 + k1;

    return out;
}

__inline int32 sum_abs(int32 k0, int32 k1, int32 k2, int32 k3,
                       int32 k4, int32 k5, int32 k6, int32 k7)
{
    int32 carry, abs_sum;

    carry = k0 >> 31;
    abs_sum = (k0 ^ carry);
    carry = k1 >> 31;
    abs_sum += (k1 ^ carry) - carry;
    carry = k2 >> 31;
    abs_sum += (k2 ^ carry) - carry;
    carry = k3 >> 31;
    abs_sum += (k3 ^ carry) - carry;
    carry = k4 >> 31;
    abs_sum += (k4 ^ carry) - carry;
    carry = k5 >> 31;
    abs_sum += (k5 ^ carry) - carry;
    carry = k6 >> 31;
    abs_sum += (k6 ^ carry) - carry;
    carry = k7 >> 31;
    abs_sum += (k7 ^ carry) - carry;

    return abs_sum;
}

#elif defined(__CC_ARM)  /* only work with arm v5 */

#if defined(__TARGET_ARCH_5TE)

__inline int32 mla724(int32 op1, int32 op2, int32 op3)
{
    int32 out;

    __asm
    {
        smlabb out, op1, op2, op3
    }

    return out;
}

__inline int32 mla392(int32 k0, int32 k14, int32 round)
{
    int32 k1;

    __asm
    {
        smlabt k1, k0, k14, round
    }

    return k1;
}

__inline int32 mla554(int32 k4, int32 k12, int32 k1)
{
    int32 k0;

    __asm
    {
        smlabt k0, k4, k12, k1
    }

    return k0;
}

__inline int32 mla1338(int32 k6, int32 k14, int32 k1)
{
    int32 out;

    __asm
    {
        smlabb out, k6, k14, k1
    }

    return out;
}

__inline int32 mla946(int32 k6, int32 k14, int32 k1)
{
    int32 out;

    __asm
    {
        smlabb out, k6, k14, k1
    }

    return out;
}

#else // not ARM5TE


__inline int32 mla724(int32 op1, int32 op2, int32 op3)
{
    int32 out;

    __asm
    {
        and out, op2, #0xFFFF
        mla out, op1, out, op3
    }

    return out;
}

__inline int32 mla392(int32 k0, int32 k14, int32 round)
{
    int32 k1;

    __asm
    {
        mov k1, k14, asr #16
        mla k1, k0, k1, round
    }

    return k1;
}

__inline int32 mla554(int32 k4, int32 k12, int32 k1)
{
    int32 k0;

    __asm
    {
        mov  k0, k12, asr #16
        mla k0, k4, k0, k1
    }

    return k0;
}

__inline int32 mla1338(int32 k6, int32 k14, int32 k1)
{
    int32 out;

    __asm
    {
        and out, k14, 0xFFFF
        mla out, k6, out, k1
    }

    return out;
}

__inline int32 mla946(int32 k6, int32 k14, int32 k1)
{
    int32 out;

    __asm
    {
        and out, k14, 0xFFFF
        mla out, k6, out, k1
    }

    return out;
}

#endif

__inline int32 sum_abs(int32 k0, int32 k1, int32 k2, int32 k3,
                       int32 k4, int32 k5, int32 k6, int32 k7)
{
    int32 carry, abs_sum;
    __asm
    {
        eor     carry, k0, k0, asr #31 ;
        eors    abs_sum, k1, k1, asr #31 ;
        adc     abs_sum, abs_sum, carry ;
        eors    carry,  k2, k2, asr #31 ;
        adc     abs_sum, abs_sum, carry ;
        eors    carry,  k3, k3, asr #31 ;
        adc     abs_sum, abs_sum, carry ;
        eors    carry,  k4, k4, asr #31 ;
        adc     abs_sum, abs_sum, carry ;
        eors    carry,  k5, k5, asr #31 ;
        adc     abs_sum, abs_sum, carry ;
        eors    carry,  k6, k6, asr #31 ;
        adc     abs_sum, abs_sum, carry ;
        eors    carry,  k7, k7, asr #31 ;
        adc     abs_sum, abs_sum, carry ;
    }

    return abs_sum;
}

#elif ( defined(PV_ARM_GCC_V5) || defined(PV_ARM_GCC_V4) )  /* ARM GNU COMPILER  */

__inline int32 mla724(int32 op1, int32 op2, int32 op3)
{
    register int32 out;
    register int32 aa = (int32)op1;
    register int32 bb = (int32)op2;
    register int32 cc = (int32)op3;

    asm volatile("smlabb %0, %1, %2, %3"
             : "=&r"(out)
                         : "r"(aa),
                         "r"(bb),
                         "r"(cc));
    return out;
}


__inline int32 mla392(int32 k0, int32 k14, int32 round)
{
    register int32 out;
    register int32 aa = (int32)k0;
    register int32 bb = (int32)k14;
    register int32 cc = (int32)round;

    asm volatile("smlabt %0, %1, %2, %3"
             : "=&r"(out)
                         : "r"(aa),
                         "r"(bb),
                         "r"(cc));

    return out;
}

__inline int32 mla554(int32 k4, int32 k12, int32 k1)
{
    register int32 out;
    register int32 aa = (int32)k4;
    register int32 bb = (int32)k12;
    register int32 cc = (int32)k1;

    asm volatile("smlabt %0, %1, %2, %3"
             : "=&r"(out)
                         : "r"(aa),
                         "r"(bb),
                         "r"(cc));

    return out;
}

__inline int32 mla1338(int32 k6, int32 k14, int32 k1)
{
    register int32 out;
    register int32 aa = (int32)k6;
    register int32 bb = (int32)k14;
    register int32 cc = (int32)k1;

    asm volatile("smlabb %0, %1, %2, %3"
             : "=&r"(out)
                         : "r"(aa),
                         "r"(bb),
                         "r"(cc));
    return out;
}

__inline int32 mla946(int32 k6, int32 k14, int32 k1)
{
    register int32 out;
    register int32 aa = (int32)k6;
    register int32 bb = (int32)k14;
    register int32 cc = (int32)k1;

    asm volatile("smlabb %0, %1, %2, %3"
             : "=&r"(out)
                         : "r"(aa),
                         "r"(bb),
                         "r"(cc));
    return out;
}

__inline int32 sum_abs(int32 k0, int32 k1, int32 k2, int32 k3,
                       int32 k4, int32 k5, int32 k6, int32 k7)
{
    register int32 carry;
    register int32 abs_sum;
    register int32 aa = (int32)k0;
    register int32 bb = (int32)k1;
    register int32 cc = (int32)k2;
    register int32 dd = (int32)k3;
    register int32 ee = (int32)k4;
    register int32 ff = (int32)k5;
    register int32 gg = (int32)k6;
    register int32 hh = (int32)k7;

    asm volatile("eor  %0, %2, %2, asr #31\n\t"
                 "eors %1, %3, %3, asr #31\n\t"
                 "adc  %1, %1, %0\n\t"
                 "eors %0, %4, %4, asr #31\n\t"
                 "adc  %1, %1, %0\n\t"
                 "eors %0, %5, %5, asr #31\n\t"
                 "adc  %1, %1, %0\n\t"
                 "eors %0, %6, %6, asr #31\n\t"
                 "adc  %1, %1, %0\n\t"
                 "eors %0, %7, %7, asr #31\n\t"
                 "adc  %1, %1, %0\n\t"
                 "eors %0, %8, %8, asr #31\n\t"
                 "adc  %1, %1, %0\n\t"
                 "eors %0, %9, %9, asr #31\n\t"
                 "adc  %1, %1, %0\n\t"

             : "=&r"(carry),
                 "=&r"(abs_sum):
                         "r"(aa),
                         "r"(bb),
                         "r"(cc),
                         "r"(dd),
                         "r"(ee),
                         "r"(ff),
                         "r"(gg),
                         "r"(hh));

    return abs_sum;
}

#endif // Diff. OS

#endif //_DCT_INLINE_H_


