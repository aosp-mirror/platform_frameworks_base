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

 Pathname: fxp_mul32_c_equivalent.h


------------------------------------------------------------------------------
 REVISION HISTORY

 Who:                                       Date:
 Description:
------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

------------------------------------------------------------------------------
*/

#ifndef FXP_MUL32_ARM_V4
#define FXP_MUL32_ARM_V4


#ifdef __cplusplus
extern "C"
{
#endif


#include "pv_audio_type_defs.h"


#if defined(PV_ARM_V4)

#define preload_cache( a)


    __inline  Int32 shft_lft_1(Int32 L_var1)
    {
        Int32 x;
        Int32 z = 1; /* rvct compiler problem */
        __asm
        {
            mov x, L_var1, asl 1
            teq L_var1, x, asr z
            eorne  x, INT32_MAX, L_var1, asr #31
        }

        return(x);
    }


    __inline  Int32 fxp_mul_16_by_16bb(Int32 L_var1,  Int32 L_var2)
    {
        __asm
        {

            mov L_var2, L_var2, asl #16
            mov L_var2, L_var2, asr #16
            mov L_var1, L_var1, asl #16
            mov L_var1, L_var1, asr #16


            mul L_var1, L_var2, L_var1
        }

        return L_var1;

    }


#define fxp_mul_16_by_16(a, b)  fxp_mul_16_by_16bb(  a, b)


    __inline  Int32 fxp_mul_16_by_16tb(Int32 L_var1,  Int32 L_var2)
    {
        __asm
        {
            mov L_var2, L_var2, asl #16
            mov L_var2, L_var2, asr #16
            mov L_var1, L_var1, asr #16

            mul L_var1, L_var2, L_var1
        }
        return L_var1;
    }

    __inline  Int32 fxp_mul_16_by_16bt(Int32 L_var1,  Int32 L_var2)
    {
        __asm
        {
            mov L_var2, L_var2, asr #16
            mov L_var1, L_var1, asl #16
            mov L_var1, L_var1, asr #16

            mul L_var1, L_var2, L_var1
        }

        return L_var1;

    }


    __inline  Int32 fxp_mul_16_by_16tt(Int32 L_var1,  Int32 L_var2)
    {
        __asm
        {
            mov L_var2, L_var2, asr #16
            mov L_var1, L_var1, asr #16

            mul L_var1, L_var2, L_var1
        }

        return L_var1;

    }

    __inline  Int32 fxp_mac_16_by_16(const Int32 L_var1, const Int32 L_var2, Int32 L_add)
    {
        __asm
        {
            mla L_add, L_var1, L_var2, L_add
        }
        return (L_add);
    }


    __inline  Int32 fxp_mac_16_by_16_bb(const Int32 L_var1,  Int32 L_var2, Int32 L_add)
    {
        __asm
        {
            mov L_var2, L_var2, asl #16
            mov L_var2, L_var2, asr #16
            mla L_add, L_var1, L_var2, L_add
        }
        return L_add;
    }

    __inline  Int32 fxp_mac_16_by_16_bt(Int16 L_var1,  Int32 L_var2, Int32 L_add)
    {
        __asm
        {
            mov L_var2, L_var2, asr #16
            mla L_add, L_var1, L_var2, L_add
        }
        return L_add;
    }


    __inline  Int32 cmplx_mul32_by_16(Int32 x, const Int32 y, Int32 exp_jw)
    {

        Int32 result64_hi;
        Int32 rTmp0;
        Int32 iTmp0;
        __asm
        {
            mov rTmp0, exp_jw, asr #16
            mov rTmp0, rTmp0, asl #16
            mov iTmp0, exp_jw, asl #16
            smull rTmp0, result64_hi, x, rTmp0
            smlal iTmp0, result64_hi, y, iTmp0
        }

        return (result64_hi);
    }


    __inline  Int32 fxp_mul32_by_16(Int32 L_var1, Int32 L_var2)
    {
        Int32 result64_hi;
        __asm
        {
            mov L_var2, L_var2, asl #16
            smull L_var1, result64_hi, L_var2, L_var1
        }
        return (result64_hi);
    }



#define fxp_mul32_by_16b( a, b)   fxp_mul32_by_16( a, b)



    __inline  Int32 fxp_mul32_by_16t(Int32 L_var1, Int32 L_var2)
    {

        Int32 result64_hi;
        __asm
        {
            mov L_var2, L_var2, asr #16
            mov L_var2, L_var2, asl #16
            smull L_var1, result64_hi, L_var2, L_var1
        }
        return (result64_hi);

    }

    __inline  Int32 fxp_mac32_by_16(Int32 L_var1, Int32 L_var2, Int32 L_add)
    {

        __asm
        {
            mov L_var2, L_var2, asl #16
            smlal L_var1, L_add, L_var2, L_var1
        }

        return (L_add);
    }


    __inline  int64 fxp_mac64_Q31(int64 sum, const Int32 L_var1, const Int32 L_var2)
    {
        uint32 b = (UInt32)(sum);
        int32 c = Int32(sum >> 32);
        __asm
        {
            smlal b, c, L_var1, L_var2
        }
        return (((int64(c)) << 32) | b);
    }


    __inline  Int32 fxp_mul32_Q31(Int32 L_var1, const Int32 L_var2)
    {
        Int32 result64_hi;
        __asm
        {
            smull L_var1, result64_hi, L_var2, L_var1
        }
        return (result64_hi);
    }


    __inline  Int32 fxp_mac32_Q31(Int32 L_add,  Int32 L_var1, const Int32 L_var2)
    {
        __asm
        {
            smlal L_var1, L_add, L_var2, L_var1
        }
        return L_add;
    }

    __inline  Int32 fxp_msu32_Q31(Int32 L_sub,  Int32 L_var1, const Int32 L_var2)
    {
        __asm
        {
            rsb   L_var1, L_var1, #0
            smlal L_var1, L_sub, L_var2, L_var1
        }
        return L_sub;
    }


    __inline  Int32 fxp_mul32_Q30(const Int32 L_var1, const Int32 L_var2)
    {
        Int32 result64_hi;
        Int32 result64_lo;
        __asm
        {
            smull result64_lo, result64_hi, L_var2, L_var1
            mov result64_hi, result64_hi, asl  #2
            orr  result64_hi, result64_hi, result64_lo, lsr #30
        }
        return (result64_hi);
    }


    __inline  Int32 fxp_mac32_Q30(const Int32 L_var1, const Int32 L_var2, Int32 L_add)
    {
        Int32 result64_hi;
        Int32 result64_lo;
        __asm
        {
            smull result64_lo, result64_hi, L_var2, L_var1
            add L_add, L_add, result64_hi, asl  #2
            add L_add, L_add, result64_lo, lsr  #30
        }
        return (L_add);
    }


    __inline  Int32 fxp_mul32_Q29(const Int32 L_var1, const Int32 L_var2)
    {
        Int32 result64_hi;
        Int32 result64_lo;
        __asm
        {
            smull result64_lo, result64_hi, L_var2, L_var1
            mov result64_hi, result64_hi, asl  #3
            orr  result64_hi, result64_hi, result64_lo, lsr #29
        }
        return (result64_hi);
    }

    __inline  Int32 fxp_mac32_Q29(const Int32 L_var1, const Int32 L_var2, Int32 L_add)
    {
        Int32 result64_hi;
        Int32 result64_lo;
        __asm
        {
            smull result64_lo, result64_hi, L_var2, L_var1
            add L_add, L_add, result64_hi, asl  #3
            add L_add, L_add, result64_lo, lsr  #29
        }
        return (L_add);
    }

    __inline  Int32 fxp_msu32_Q29(const Int32 L_var1, const Int32 L_var2, Int32 L_sub)
    {
        Int32 result64_hi;
        Int32 result64_lo;
        __asm
        {
            smull result64_lo, result64_hi, L_var2, L_var1
            sub L_sub, L_sub, result64_hi, asl  #3
            sub L_sub, L_sub, result64_lo, lsr  #29
        }
        return (L_sub);
    }

    __inline  Int32 fxp_mul32_Q28(const Int32 L_var1, const Int32 L_var2)
    {
        Int32 result64_hi;
        Int32 result64_lo;
        __asm
        {
            smull result64_lo, result64_hi, L_var2, L_var1
            mov result64_hi, result64_hi, asl  #4
            orr  result64_hi, result64_hi, result64_lo, lsr #28
        }
        return (result64_hi);
    }

    __inline  Int32 fxp_mul32_Q27(const Int32 L_var1, const Int32 L_var2)
    {
        Int32 result64_hi;
        Int32 result64_lo;
        __asm
        {
            smull result64_lo, result64_hi, L_var2, L_var1
            mov result64_hi, result64_hi, asl  #5
            orr  result64_hi, result64_hi, result64_lo, lsr #27
        }
        return (result64_hi);
    }

    __inline  Int32 fxp_mul32_Q26(const Int32 L_var1, const Int32 L_var2)
    {
        Int32 result64_hi;
        Int32 result64_lo;
        __asm
        {
            smull result64_lo, result64_hi, L_var2, L_var1
            mov result64_hi, result64_hi, asl  #6
            orr  result64_hi, result64_hi, result64_lo, lsr #26
        }
        return (result64_hi);
    }

    __inline  Int32 fxp_mul32_Q20(const Int32 L_var1, const Int32 L_var2)
    {
        Int32 result64_hi;
        Int32 result64_lo;
        __asm
        {
            smull result64_lo, result64_hi, L_var2, L_var1
            mov result64_hi, result64_hi, asl  #12
            orr  result64_hi, result64_hi, result64_lo, lsr #20
        }
        return (result64_hi);
    }

    __inline  Int32 fxp_mul32_Q15(const Int32 L_var1, const Int32 L_var2)
    {
        Int32 result64_hi;
        Int32 result64_lo;
        __asm
        {
            smull result64_lo, result64_hi, L_var2, L_var1
            mov result64_hi, result64_hi, asl  #17
            orr  result64_hi, result64_hi, result64_lo, lsr #15
        }
        return (result64_hi);
    }




    __inline  Int32 fxp_mul32_Q14(const Int32 L_var1, const Int32 L_var2)
    {
        Int32 result64_hi;
        Int32 result64_lo;
        __asm
        {
            smull result64_lo, result64_hi, L_var2, L_var1
            mov result64_hi, result64_hi, asl  #18
            orr  result64_hi, result64_hi, result64_lo, lsr #14
        }
        return (result64_hi);
    }



#endif


#ifdef __cplusplus
}
#endif


#endif   /*  FXP_MUL32  */

