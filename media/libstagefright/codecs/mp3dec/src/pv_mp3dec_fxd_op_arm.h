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

   Pathname: ./cpp/include/pv_mp3dec_fxd_op_arm.h

     Date: 08/20/2007

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:
------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 This file select the associated fixed point functions with the OS/ARCH.


------------------------------------------------------------------------------
*/

#ifndef PV_MP3DEC_FXD_OP_ARM
#define PV_MP3DEC_FXD_OP_ARM


#ifdef __cplusplus
extern "C"
{
#endif

#include "pvmp3_audio_type_defs.h"


#if (defined(PV_ARM_V5)||defined(PV_ARM_V4))


    __inline  Int32 fxp_mul32_Q30(const Int32 L_var1, const Int32 L_var2)
    {

        Int32 result64_hi;
        Int32 result64_lo;
        __asm
        {
            smull result64_lo, result64_hi, L_var2, L_var1
            mov   result64_lo, result64_lo, lsr #30
            add   result64_hi, result64_lo, result64_hi, asl  #2
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



#define Qfmt_31(a)   (Int32)(a*0x7FFFFFFF + (a>=0?0.5F:-0.5F))



    __inline  Int32 fxp_mul32_Q32(Int32 L_var1, const Int32 L_var2)
    {
        Int32 result64_hi;
        __asm
        {
            smull L_var1, result64_hi, L_var2, L_var1
        }
        return (result64_hi);
    }

    __inline  Int32 fxp_mul32_Q28(const Int32 L_var1, const Int32 L_var2)
    {

        Int32 result64_hi;
        Int32 result64_lo;
        __asm
        {
            smull result64_lo, result64_hi, L_var2, L_var1
            mov   result64_lo, result64_lo, lsr #28
            add   result64_hi, result64_lo, result64_hi, asl  #4
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
            mov   result64_lo, result64_lo, lsr #27
            add   result64_hi, result64_lo, result64_hi, asl  #5
        }
        return (result64_hi);
    }


    __inline  Int32 fxp_mul32_Q26(Int32 L_var1,  Int32 L_var2)
    {

        Int32 result64_hi;
        Int32 result64_lo;
        __asm
        {
            smull result64_lo, result64_hi, L_var2, L_var1
            mov   result64_lo, result64_lo, lsr #26
            add   result64_hi, result64_lo, result64_hi, asl  #6
        }
        return (result64_hi);
    }



    __inline  Int32 fxp_mac32_Q32(Int32 L_add,  Int32 L_var1, const Int32 L_var2)
    {
        __asm
        {
            smlal L_var1, L_add, L_var2, L_var1
        }
        return L_add;
    }


    __inline  Int32 fxp_msb32_Q32(Int32 L_sub,  Int32 L_var1, Int32 L_var2)
    {

        __asm
        {
            smull  L_var2, L_var1, L_var2, L_var1
            sub  L_sub, L_sub, L_var1
        }
        return L_sub;
    }


    __inline  Int32 fxp_mul32_Q29(const Int32 L_var1,  const Int32 L_var2)
    {
        Int32 result64_hi;
        Int32 result64_lo;
        __asm
        {
            smull result64_lo, result64_hi, L_var2, L_var1
            mov   result64_lo, result64_lo, lsr #29
            add   result64_hi, result64_lo, result64_hi, asl  #3
        }
        return (result64_hi);
    }


    __inline int32 pv_abs(int32 a)
    {
        Int32 b;
        /*
            b = a - (a<0);
            a = b ^ sign(b)
         */
        __asm
        {
            sub  b, a, a, lsr #31
            eor  a, b, b, asr #31
        }
        return (a);
    }

#endif

#ifdef __cplusplus
}
#endif


#endif   /*  PV_MP3DEC_FXD_OP_ARM  */

