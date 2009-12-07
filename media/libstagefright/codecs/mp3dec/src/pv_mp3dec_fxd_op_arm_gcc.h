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
------------------------------------------------------------------------------
   PacketVideo Corp.
   MP3 Decoder Library

   Pathname: ./cpp/include/pv_mp3dec_fxd_op_arm_gcc.h

     Date: 08/20/2007

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:
------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 This file select the associated fixed point functions with the OS/ARCH.


------------------------------------------------------------------------------
*/

#ifndef PV_MP3DEC_FXD_OP_ARM_GCC_H
#define PV_MP3DEC_FXD_OP_ARM_GCC_H


#ifdef __cplusplus
extern "C"
{
#endif

#include "pvmp3_audio_type_defs.h"


#if (defined(PV_ARM_GCC_V5)||defined(PV_ARM_GCC_V4))

#define Qfmt_31(a)   (int32)(a*0x7FFFFFFF + (a>=0?0.5F:-0.5F))

#define Qfmt15(x)   (Int16)(x*((int32)1<<15) + (x>=0?0.5F:-0.5F))

    static inline int32 fxp_mul32_Q30(const int32 a, const int32 b)
    {
        int32 result64_hi;
        int32 result64_lo;
        register int32 ra = (int32)a;
        register int32 rb = (int32)b;
        asm volatile("smull %1, %0, %2, %3\n\t"
                     "mov   %1, %1, lsr #30\n\t"
                     "add   %0, %1, %0, asl #2"
             : "=&r*i"(result64_hi),
                     "=&r*i"(result64_lo)
                             : "r"(ra),
                             "r"(rb));
        return (result64_hi);
    }


    static inline int32 fxp_mac32_Q30(const int32 a, const int32 b, int32 L_add)
{
        int32 result64_hi;
        int32 result64_lo;
        register int32 ra = (int32)a;
        register int32 rb = (int32)b;
        register int32 rc = (int32)L_add;

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



    static inline int32 fxp_mul32_Q32(const int32 a, const int32 b)
{
        int32 result64_hi;
        int32 result64_lo;
        register int32 ra = (int32)a;
        register int32 rb = (int32)b;
        asm volatile(
            "smull %1, %0, %2, %3"
    : "=&r*i"(result64_hi),
            "=&r*i"(result64_lo)
                    : "r"(ra),
                    "r"(rb));

        return (result64_hi);
    }


    static inline int32 fxp_mul32_Q29(const int32 a, const int32 b)
{
        int32 result64_hi;
        int32 result64_lo;
        register int32 ra = (int32)a;
        register int32 rb = (int32)b;
        asm volatile("smull %1, %0, %2, %3\n\t"
                     "mov   %1, %1, lsr #29\n\t"
                     "add   %0, %1, %0, asl #3"
             : "=&r*i"(result64_hi),
                     "=&r*i"(result64_lo)
                             : "r"(ra),
                             "r"(rb));
        return (result64_hi);

    }

    static inline int32 fxp_mul32_Q28(const int32 a, const int32 b)
{

        int32 result64_hi;
        int32 result64_lo;
        register int32 ra = (int32)a;
        register int32 rb = (int32)b;
        asm volatile("smull %1, %0, %2, %3\n\t"
                     "mov   %1, %1, lsr #28\n\t"
                     "add   %0, %1, %0, asl #4"
             : "=&r*i"(result64_hi),
                     "=&r*i"(result64_lo)
                             : "r"(ra),
                             "r"(rb));
        return (result64_hi);

    }


    static inline int32 fxp_mul32_Q27(const int32 a, const int32 b)
{
        int32 result64_hi;
        int32 result64_lo;
        register int32 ra = (int32)a;
        register int32 rb = (int32)b;
        asm volatile("smull %1, %0, %2, %3\n\t"
                     "mov   %1, %1, lsr #27\n\t"
                     "add   %0, %1, %0, asl #5"
             : "=&r*i"(result64_hi),
                     "=&r*i"(result64_lo)
                             : "r"(ra),
                             "r"(rb));
        return (result64_hi);

    }


    static inline int32 fxp_mul32_Q26(const int32 a, const int32 b)
{
        int32 result64_hi;
        int32 result64_lo;
        register int32 ra = (int32)a;
        register int32 rb = (int32)b;
        asm volatile("smull %1, %0, %2, %3\n\t"
                     "mov   %1, %1, lsr #26\n\t"
                     "add   %0, %1, %0, asl #6"
             : "=&r*i"(result64_hi),
                     "=&r*i"(result64_lo)
                             : "r"(ra),
                             "r"(rb));
        return (result64_hi);

    }



    static inline int32 fxp_mac32_Q32(int32 L_add, const int32 a, const int32 b)
{

        int32 result64_hi;
        int32 result64_lo;
        register int32 ra = (int32)a;
        register int32 rb = (int32)b;
        register int32 rc = (int32)L_add;

        asm volatile("smull %1, %0, %2, %3\n\t"
                     "add %0, %0, %4"
             : "=&r*i"(result64_hi),
                     "=&r*i"(result64_lo)
                             : "r"(ra),
                             "r"(rb),
                             "r"(rc));

        return (result64_hi);
    }

    static inline int32 fxp_msb32_Q32(int32 L_sub, const int32 a, const int32 b)
{
        int32 result64_hi;
        int32 result64_lo;
        register int32 ra = (int32)a;
        register int32 rb = (int32)b;
        register int32 rc = (int32)L_sub;

        asm volatile("smull %1, %0, %2, %3\n\t"
                     "sub %0, %4, %0"
             : "=&r*i"(result64_hi),
                     "=&r*i"(result64_lo)
                             : "r"(ra),
                             "r"(rb),
                             "r"(rc));


        return (result64_hi);
    }


    __inline int32 pv_abs(int32 x)
{
        register int32 z;
        register int32 y;
        register int32 ra = x;
        asm volatile(
            "sub  %0, %2, %2, lsr #31\n\t"
            "eor  %1, %0, %0, asr #31"
    : "=&r*i"(z),
            "=&r*i"(y)
                    : "r"(ra));

        return (y);
    }


#endif

#ifdef __cplusplus
}
#endif


#endif   /*  PV_MP3DEC_FXD_OP_ARM_GCC_H  */

