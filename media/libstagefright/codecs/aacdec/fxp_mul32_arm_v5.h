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

 Pathname: ./c/include/fxp_mul32_arm_v5.h

------------------------------------------------------------------------------
 REVISION HISTORY

 Who:                                       Date:
 Description:
------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

------------------------------------------------------------------------------
*/

#ifndef FXP_MUL32_ARM_V5
#define FXP_MUL32_ARM_V5


#ifdef __cplusplus
extern "C"
{
#endif

#include "pv_audio_type_defs.h"


#if defined(PV_ARM_V5)

//#undef EXTENDED_ASM
#define EXTENDED_ASM
#define _ARM_V5_


    __inline  Int32 shft_lft_1(Int32 L_var1)
    {
        __asm
        {
            qadd L_var1, L_var1, L_var1
        }

        return L_var1;
    }


    __inline  Int32 fxp_mul_16_by_16(Int32 L_var1,  Int32 L_var2)
    {
        __asm
        {
            smulbb L_var1, L_var1, L_var2
        }
        return L_var1;
    }


    __inline  Int32 fxp_mul_16_by_16bb(Int32 L_var1,  Int32 L_var2)
    {
        __asm
        {
            smulbb L_var1, L_var1, L_var2
        }
        return L_var1;
    }


    __inline  Int32 fxp_mul_16_by_16tb(Int32 L_var1,  Int32 L_var2)
    {
        __asm
        {
            smultb L_var1, L_var1, L_var2
        }
        return L_var1;
    }

    __inline  Int32 fxp_mul_16_by_16tt(Int32 L_var1,  Int32 L_var2)
    {
        __asm
        {
            smultt L_var1, L_var1, L_var2
        }
        return L_var1;
    }

    __inline  Int32 fxp_mul_16_by_16bt(Int32 L_var1,  Int32 L_var2)
    {
        __asm
        {
            smulbt L_var1, L_var1, L_var2
        }
        return L_var1;
    }



    __inline  Int32 fxp_mac_16_by_16(const Int32 L_var1, const Int32 L_var2, Int32 L_add)
    {
        __asm
        {
            smlabb L_add, L_var1, L_var2, L_add
        }
        return (L_add);
    }

    __inline  Int32 fxp_mac_16_by_16_bb(const Int32 L_var1,  Int32 L_var2, Int32 L_add)
    {
        __asm
        {
            smlabb L_add, L_var1, L_var2, L_add
        }
        return L_add;
    }

    __inline  Int32 fxp_mac_16_by_16_bt(const Int32 L_var1,  Int32 L_var2, Int32 L_add)
    {
        __asm
        {
            smlabt L_add, L_var1, L_var2, L_add
        }
        return L_add;
    }


    __inline  Int32 fxp_mac_16_by_16_tb(const Int32 L_var1,  Int32 L_var2, Int32 L_add)
    {
        __asm
        {
            smlatb L_add, L_var1, L_var2, L_add
        }
        return L_add;
    }

    __inline  Int32 fxp_mac_16_by_16_tt(const Int32 L_var1,  Int32 L_var2, Int32 L_add)
    {
        __asm
        {
            smlatt L_add, L_var1, L_var2, L_add
        }
        return L_add;
    }

    __inline  Int32 fxp_mac32_by_16(Int32 L_var1, const Int32 L_var2, Int32 L_add)
    {
        __asm
        {
            smlawb L_add, L_var1, L_var2, L_add
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

    __inline  Int32 fxp_mul32_Q31(Int32 L_var1, const Int32 L_var2)
    {
        Int32 result64_hi;
        __asm
        {
            smull L_var1, result64_hi, L_var2, L_var1
        }
        return (result64_hi);
    }

    __inline  Int32 fxp_mul32_Q30(const Int32 L_var1, const Int32 L_var2)
    {
        Int32 result64_hi;
        Int32 result64_lo;
        __asm
        {
            smull result64_lo, result64_hi, L_var2, L_var1
            mov result64_hi, result64_hi, asl  #2
#ifdef EXTENDED_ASM
            mov result64_lo, result64_lo, lsr  #30
            orr  result64_hi, result64_lo, result64_hi
#else
            orr  result64_hi, result64_hi, result64_lo, lsr #30
#endif
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
#ifdef EXTENDED_ASM
            mov result64_lo, result64_lo, lsr  #29
            orr  result64_hi, result64_lo, result64_hi
#else
            orr  result64_hi, result64_hi, result64_lo, lsr #29
#endif
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
#ifdef EXTENDED_ASM
            mov result64_lo, result64_lo, lsr  #28
            orr  result64_hi, result64_lo, result64_hi
#else
            orr  result64_hi, result64_hi, result64_lo, lsr #28
#endif

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
#ifdef EXTENDED_ASM
            mov result64_lo, result64_lo, lsr  #27
            orr  result64_hi, result64_lo, result64_hi
#else
            orr  result64_hi, result64_hi, result64_lo, lsr #27
#endif
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
#ifdef EXTENDED_ASM
            mov result64_lo, result64_lo, lsr  #26
            orr  result64_hi, result64_lo, result64_hi
#else
            orr  result64_hi, result64_hi, result64_lo, lsr #26
#endif

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
#ifdef EXTENDED_ASM
            mov result64_lo, result64_lo, lsr  #20
            orr  result64_hi, result64_lo, result64_hi
#else
            orr  result64_hi, result64_hi, result64_lo, lsr #20
#endif
        }
        return (result64_hi);
    }

    __inline  Int32 fxp_mul32_by_16(Int32 L_var1, const Int32 L_var2)
    {
        Int32 result64_hi;
        __asm
        {
            smulwb result64_hi, L_var1, L_var2
        }
        return (result64_hi);
    }

#define fxp_mul32_by_16b( a, b)         fxp_mul32_by_16(a, b)

    __inline  Int32 fxp_mul32_by_16t(Int32 L_var1, const Int32 L_var2)
    {
        Int32 result64_hi;
        __asm
        {
            smulwt result64_hi, L_var1, L_var2
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
#ifdef EXTENDED_ASM
            mov result64_lo, result64_lo, lsr  #15
            orr  result64_hi, result64_lo, result64_hi
#else
            orr  result64_hi, result64_hi, result64_lo, lsr #15
#endif
        }
        return (result64_hi);
    }


    __inline  Int32 cmplx_mul32_by_16(Int32 L_var1, const Int32 L_var2, const Int32 cmplx)
    {
        Int32 result64_hi;

        __asm
        {
            smulwt result64_hi, L_var1, cmplx
            smlawb result64_hi, L_var2, cmplx, result64_hi
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
#ifdef EXTENDED_ASM
            mov result64_lo, result64_lo, lsr  #14
            orr  result64_hi, result64_lo, result64_hi
#else
            orr  result64_hi, result64_hi, result64_lo, lsr #14
#endif
        }
        return (result64_hi);
    }


#define preload_cache( a)




#endif

#ifdef __cplusplus
}
#endif


#endif   /*  FXP_MUL32  */

