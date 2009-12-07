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
/*

 Pathname: ./c/include/fxp_mul32_arm_gcc.h

------------------------------------------------------------------------------
 REVISION HISTORY

 Who:                                       Date:
 Description:
------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

------------------------------------------------------------------------------
*/

#ifndef FXP_MUL32_ARM_GCC
#define FXP_MUL32_ARM_GCC


#ifdef __cplusplus
extern "C"
{
#endif


#include "pv_audio_type_defs.h"


#if (defined (PV_ARM_GCC_V4) || defined(PV_ARM_GCC_V5)) /* ARM GNU COMPILER  */



#define preload_cache( a)


    static inline Int32 shft_lft_1(Int32 y)
    {
        register Int32 x;
        register Int32 ra = y;


        asm volatile(
            "qadd %0, %1, %1\n\t"
    : "=&r*i"(x)
                    : "r"(ra));

        return (x);
    }

    static inline Int32 fxp_mul_16_by_16bb(Int32 L_var1, const Int32 L_var2)
    {

        register Int32 tmp;
        register Int32 ra = (Int32)L_var1;
        register Int32 rb = (Int32)L_var2;

        asm volatile(
            "smulbb %0, %1, %2"
    : "=&r*i"(tmp)
                    : "r"(ra),
                    "r"(rb));

        return (tmp);
    }


#define fxp_mul_16_by_16(a, b)  fxp_mul_16_by_16bb(  a, b)


    static inline Int32 fxp_mul_16_by_16tb(Int32 L_var1, const Int32 L_var2)
{

        register Int32 tmp;
        register Int32 ra = (Int32)L_var1;
        register Int32 rb = (Int32)L_var2;

        asm volatile(
            "smultb %0, %1, %2"
    : "=&r*i"(tmp)
                    : "r"(ra),
                    "r"(rb));

        return (tmp);
    }

    static inline Int32 fxp_mul_16_by_16bt(Int32 L_var1, const Int32 L_var2)
{

        register Int32 tmp;
        register Int32 ra = (Int32)L_var1;
        register Int32 rb = (Int32)L_var2;

        asm volatile(
            "smulbt %0, %1, %2"
    : "=&r*i"(tmp)
                    : "r"(ra),
                    "r"(rb));

        return (tmp);
    }

    static inline Int32 fxp_mul_16_by_16tt(Int32 L_var1, const Int32 L_var2)
{

        register Int32 tmp;
        register Int32 ra = (Int32)L_var1;
        register Int32 rb = (Int32)L_var2;

        asm volatile(
            "smultt %0, %1, %2"
    : "=&r*i"(tmp)
                    : "r"(ra),
                    "r"(rb));

        return (tmp);
    }

    static inline Int32 fxp_mac_16_by_16(const Int32 L_var1, const Int32 L_var2, Int32 L_add)
{
        register Int32 tmp;
        register Int32 ra = (Int32)L_var1;
        register Int32 rb = (Int32)L_var2;
        register Int32 rc = (Int32)L_add;

        asm volatile(
            "smlabb %0, %1, %2, %3"
    : "=&r*i"(tmp)
                    : "r"(ra),
                    "r"(rb),
                    "r"(rc));

        return (tmp);
    }



    static inline Int32 fxp_mac_16_by_16_bb(const Int32 L_var1, const Int32 L_var2, Int32 L_add)
{
        register Int32 tmp;
        register Int32 ra = (Int32)L_var1;
        register Int32 rb = (Int32)L_var2;
        register Int32 rc = (Int32)L_add;

        asm volatile(
            "smlabb %0, %1, %2, %3"
    : "=&r*i"(tmp)
                    : "r"(ra),
                    "r"(rb),
                    "r"(rc));

        return (tmp);
    }


    static inline Int32 fxp_mac_16_by_16_bt(const Int32 L_var1, const Int32 L_var2, Int32 L_add)
{
        register Int32 tmp;
        register Int32 ra = (Int32)L_var1;
        register Int32 rb = (Int32)L_var2;
        register Int32 rc = (Int32)L_add;

        asm volatile(
            "smlabt %0, %1, %2, %3"
    : "=&r*i"(tmp)
                    : "r"(ra),
                    "r"(rb),
                    "r"(rc));

        return (tmp);
    }



    static inline Int32 cmplx_mul32_by_16(Int32 x, const Int32 y, Int32 exp_jw)
{
        register Int32 cx_sum;
        register Int32 rx = (Int32)x;
        register Int32 ry = (Int32)y;
        register Int32 rexp = (Int32)exp_jw;
        asm volatile(
            "smulwt %0, %1, %3\n\t"
            "smlawb %0, %2, %3, %0"
    : "=&r*i"(cx_sum)
                    : "r"(rx),
                    "r"(ry),
                    "r"(rexp));

        return (cx_sum);
    }


    static inline Int32 fxp_mul32_by_16(Int32 L_var1, const Int32 L_var2)
{

        register Int32 tmp;
        register Int32 ra = (Int32)L_var1;
        register Int32 rb = (Int32)L_var2;

        asm volatile(
            "smulwb %0, %1, %2"
    : "=&r*i"(tmp)
                    : "r"(ra),
                    "r"(rb));

        return (tmp);
    }

#define fxp_mul32_by_16b( a, b)   fxp_mul32_by_16( a, b)


    static inline Int32 fxp_mul32_by_16t(Int32 L_var1, const Int32 L_var2)
{

        register Int32 tmp;
        register Int32 ra = (Int32)L_var1;
        register Int32 rb = (Int32)L_var2;

        asm volatile(
            "smulwt %0, %1, %2"
    : "=&r*i"(tmp)
                    : "r"(ra),
                    "r"(rb));

        return (tmp);
    }



    static inline Int32 fxp_mac32_by_16(const Int32 L_var1, const Int32 L_var2, Int32 L_add)
{

        register Int32 tmp;
        register Int32 ra = (Int32)L_var1;
        register Int32 rb = (Int32)L_var2;
        register Int32 rc = (Int32)L_add;

        asm volatile(
            "smlawb %0, %1, %2, %3"
    : "=&r*i"(tmp)
                    : "r"(ra),
                    "r"(rb),
                    "r"(rc));

        return (tmp);
    }


    __inline  int64 fxp_mac64_Q31(int64 sum, const Int32 L_var1, const Int32 L_var2)
{
        sum += (int64)L_var1 * L_var2;
        return (sum);
    }




    static inline Int32 fxp_mac32_Q30(const Int32 a, const Int32 b, Int32 L_add)
    {
        Int32 result64_hi;
        Int32 result64_lo;
        register Int32 ra = (Int32)a;
        register Int32 rb = (Int32)b;
        register Int32 rc = (Int32)L_add;

        asm volatile("smull %1, %0, %2, %3\n\t"
                     "add %4, %4, %0, asl #2\n\t"
                     "add %0, %4, %1, lsr #30"
             : "=&r*i"(result64_hi),
                     "=&r*i"(result64_lo)
                             : "r"(ra),
                             "r"(rb),
                             "r"(rc));

        return (result64_hi);
    }


    static inline Int32 fxp_mac32_Q31(Int32 L_add, const Int32 a, const Int32 b)
{

        Int32 result64_hi;
        Int32 result64_lo;
        register Int32 ra = (Int32)a;
        register Int32 rb = (Int32)b;
        register Int32 rc = (Int32)L_add;

        asm volatile("smull %1, %0, %2, %3\n\t"
                     "add %0, %0, %4"
             : "=&r*i"(result64_hi),
                     "=&r*i"(result64_lo)
                             : "r"(ra),
                             "r"(rb),
                             "r"(rc));

        return (result64_hi);
    }



    static inline Int32 fxp_msu32_Q31(Int32 L_sub, const Int32 a, const Int32 b)
{
        Int32 result64_hi;
        Int32 result64_lo;
        register Int32 ra = (Int32)a;
        register Int32 rb = (Int32)b;
        register Int32 rc = (Int32)L_sub;

        asm volatile("smull %1, %0, %2, %3\n\t"
                     "sub %0, %4, %0"
             : "=&r*i"(result64_hi),
                     "=&r*i"(result64_lo)
                             : "r"(ra),
                             "r"(rb),
                             "r"(rc));


        return (result64_hi);
    }


    static inline Int32 fxp_mul32_Q31(const Int32 a, const Int32 b)
{
        Int32 result64_hi;
        Int32 result64_lo;
        register Int32 ra = (Int32)a;
        register Int32 rb = (Int32)b;
        asm volatile(
            "smull %1, %0, %2, %3"
    : "=&r*i"(result64_hi),
            "=&r*i"(result64_lo)
                    : "r"(ra),
                    "r"(rb));

        return (result64_hi);
    }


    static inline Int32 fxp_mul32_Q30(const Int32 a, const Int32 b)
{
        Int32 result64_hi;
        Int32 result64_lo;
        register Int32 ra = (Int32)a;
        register Int32 rb = (Int32)b;
        asm volatile("smull %1, %0, %2, %3\n\t"
                     "mov %0, %0, lsl #2\n\t"
                     "orr   %0, %0, %1, lsr #30"
             : "=&r*i"(result64_hi),
                     "=&r*i"(result64_lo)
                             : "r"(ra),
                             "r"(rb));
        return (result64_hi);
    }



    static inline Int32 fxp_mac32_Q29(const Int32 a, const Int32 b, Int32 L_add)
{
        Int32 result64_hi;
        Int32 result64_lo;
        register Int32 ra = (Int32)a;
        register Int32 rb = (Int32)b;
        register Int32 rc = (Int32)L_add;

        asm volatile("smull %1, %0, %2, %3\n\t"
                     "add   %4, %4, %0, lsl #3\n\t"
                     "add   %0, %4, %1, lsr #29"
             : "=&r*i"(result64_hi),
                     "=&r*i"(result64_lo)
                             : "r"(ra),
                             "r"(rb),
                             "r"(rc));

        return (result64_hi);
    }


    static inline Int32 fxp_msu32_Q29(const Int32 a, const Int32 b, Int32 L_sub)
{
        Int32 result64_hi;
        Int32 result64_lo;
        register Int32 ra = (Int32)a;
        register Int32 rb = (Int32)b;
        register Int32 rc = (Int32)L_sub;

        asm volatile("smull %1, %0, %2, %3\n\t"
                     "sub   %4, %4, %0, lsl #3\n\t"
                     "sub   %0, %4, %1, lsr #29"
             : "=&r*i"(result64_hi),
                     "=&r*i"(result64_lo)
                             : "r"(ra),
                             "r"(rb),
                             "r"(rc));

        return (result64_hi);
    }

    static inline Int32 fxp_mul32_Q29(const Int32 a, const Int32 b)
{
        Int32 result64_hi;
        Int32 result64_lo;
        register Int32 ra = (Int32)a;
        register Int32 rb = (Int32)b;
        asm volatile("smull %1, %0, %2, %3\n\t"
                     "mov %0, %0, lsl #3\n\t"
                     "orr   %0, %0, %1, lsr #29"
             : "=&r*i"(result64_hi),
                     "=&r*i"(result64_lo)
                             : "r"(ra),
                             "r"(rb));
        return (result64_hi);
    }



    static inline Int32 fxp_mul32_Q28(const Int32 a, const Int32 b)
{
        Int32 result64_hi;
        Int32 result64_lo;
        register Int32 ra = (Int32)a;
        register Int32 rb = (Int32)b;
        asm volatile("smull %1, %0, %2, %3\n\t"
                     "mov %0, %0, lsl #4\n\t"
                     "orr   %0, %0, %1, lsr #28"
             : "=&r*i"(result64_hi),
                     "=&r*i"(result64_lo)
                             : "r"(ra),
                             "r"(rb));
        return (result64_hi);
    }

    static inline Int32 fxp_mul32_Q27(const Int32 a, const Int32 b)
{
        Int32 result64_hi;
        Int32 result64_lo;
        register Int32 ra = (Int32)a;
        register Int32 rb = (Int32)b;
        asm volatile("smull %1, %0, %2, %3\n\t"
                     "mov %0, %0, lsl #5\n\t"
                     "orr   %0, %0, %1, lsr #27"
             : "=&r*i"(result64_hi),
                     "=&r*i"(result64_lo)
                             : "r"(ra),
                             "r"(rb));

        return (result64_hi);
    }


    static inline Int32 fxp_mul32_Q26(const Int32 a, const Int32 b)
{
        Int32 result64_hi;
        Int32 result64_lo;
        register Int32 ra = (Int32)a;
        register Int32 rb = (Int32)b;
        asm volatile("smull %1, %0, %2, %3\n\t"
                     "mov %0, %0, lsl #6\n\t"
                     "orr   %0, %0, %1, lsr #26"
             : "=&r*i"(result64_hi),
                     "=&r*i"(result64_lo)
                             : "r"(ra),
                             "r"(rb));

        return (result64_hi);
    }


    static inline Int32 fxp_mul32_Q20(const Int32 a, const Int32 b)
{
        Int32 result64_hi;
        Int32 result64_lo;
        register Int32 ra = (Int32)a;
        register Int32 rb = (Int32)b;
        asm volatile("smull %1, %0, %2, %3\n\t"
                     "mov %0, %0, lsl #12\n\t"
                     "orr   %0, %0, %1, lsr #20"
             : "=&r*i"(result64_hi),
                     "=&r*i"(result64_lo)
                             : "r"(ra),
                             "r"(rb));

        return (result64_hi);
    }


    static inline Int32 fxp_mul32_Q15(const Int32 a, const Int32 b)
{
        Int32 result64_hi;
        Int32 result64_lo;
        register Int32 ra = (Int32)a;
        register Int32 rb = (Int32)b;
        asm volatile("smull %1, %0, %2, %3\n\t"
                     "mov %0, %0, lsl #17\n\t"
                     "orr   %0, %0, %1, lsr #15"
             : "=&r*i"(result64_hi),
                     "=&r*i"(result64_lo)
                             : "r"(ra),
                             "r"(rb));

        return (result64_hi);
    }



    static inline Int32 fxp_mul32_Q14(const Int32 a, const Int32 b)
{
        Int32 result64_hi;
        Int32 result64_lo;
        register Int32 ra = (Int32)a;
        register Int32 rb = (Int32)b;
        asm volatile("smull %1, %0, %2,  %3\n\t"
                     "mov   %0, %0, lsl #18\n\t"
                     "orr   %0, %0, %1, lsr #14"
             : "=&r*i"(result64_hi),
                     "=&r*i"(result64_lo)
                             : "r"(ra),
                             "r"(rb));

        return (result64_hi);
    }

#endif


#ifdef __cplusplus
}
#endif


#endif   /*  FXP_MUL32  */

