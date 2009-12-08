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

   Pathname: ./cpp/include/pv_mp3dec_fxd_op_msc_evc.h

     Date: 08/20/2007

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:
------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 This file select the associated fixed point functions with the OS/ARCH.


------------------------------------------------------------------------------
*/

#ifndef PV_MP3DEC_FXD_OP_MSC_EVC_H
#define PV_MP3DEC_FXD_OP_MSC_EVC_H


#ifdef __cplusplus
extern "C"
{
#endif

#include "pvmp3_audio_type_defs.h"


#if (defined(PV_ARM_MSC_EVC_V5)||defined(PV_ARM_MSC_EVC_V4))
#include "armintr.h"
#include "cmnintrin.h"


    __inline int32 fxp_mul32_Q30(const int32 a, const int32 b)
    {
        return (int32)(((int64)(a) * b) >> 30);
    }


    __inline int32 fxp_mac32_Q30(const int32 a, const int32 b, int32 L_add)
    {
        return (L_add + (int32)(((int64)(a) * b) >> 30));
    }


#define Qfmt_31(a)   (int32)(a*0x7FFFFFFF + (a>=0?0.5F:-0.5F))

#define Qfmt15(x)   (Int16)(x*((int32)1<<15) + (x>=0?0.5F:-0.5F))

#define fxp_mul32_Q32( a,  b)   _MulHigh( b, a)



    __inline int32 fxp_mul32_Q28(const int32 a, const int32 b)
    {
        return (int32)(((int64)(a) * b) >> 28);
    }


    __inline int32 fxp_mul32_Q27(const int32 a, const int32 b)
    {
        return (int32)(((int64)(a) * b) >> 27);
    }



    __inline int32 fxp_mul32_Q26(const int32 a, const int32 b)
    {
        return (int32)(((int64)(a) * b) >> 26);
    }


    __inline int32 fxp_mac32_Q32(int32 L_add, const int32 a, const int32 b)
    {
        return (L_add + _MulHigh(b, a));
    }


    __inline int32 fxp_msb32_Q32(int32 L_sub, const int32 a, const int32 b)
    {
        return (L_sub - _MulHigh(b, a));
    }



    __inline int32 fxp_mul32_Q29(const int32 a, const int32 b)
    {
        return (int32)(((int64)(a) * b) >> 29);
    }



    __inline int32 pv_abs(int32 a)
    {
        int32 b = (a < 0) ? -a : a;
        return b;
    }



#endif

#ifdef __cplusplus
}
#endif


#endif   /*  PV_MP3DEC_FXD_OP_MSC_EVC_H  */

