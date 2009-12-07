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

 Pathname: .fxp_mul32_msc_evc_armv5.h

------------------------------------------------------------------------------
 REVISION HISTORY

 Who:                                       Date:
 Description:
------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

------------------------------------------------------------------------------
*/

#ifndef FXP_MUL32_MSC_EVC_ARMV5
#define FXP_MUL32_MSC_EVC_ARMV5


#ifdef __cplusplus
extern "C"
{
#endif


#include "pv_audio_type_defs.h"

#if defined(PV_ARM_MSC_EVC_V5)

#include "armintr.h"
#include "cmnintrin.h"

#define preload_cache( a)

#define shft_lft_1( L_var1)  _AddSatInt( L_var1, L_var1)

#define fxp_mul_16_by_16bb( L_var1, L_var2)  _SmulLo_SW_SL( L_var1, L_var2)

#define fxp_mul_16_by_16(a, b)  fxp_mul_16_by_16bb(  a, b)

#define fxp_mul_16_by_16tb( L_var1, L_var2)  _SmulHiLo_SW_SL( L_var1, L_var2)

#define fxp_mul_16_by_16bt( L_var1, L_var2)  _SmulLoHi_SW_SL( L_var1, L_var2)

#define fxp_mul_16_by_16tt( L_var1, L_var2)  _SmulHi_SW_SL( L_var1, L_var2)

#define fxp_mac_16_by_16( L_var1, L_var2, L_add)  _SmulAddLo_SW_SL( L_add, L_var1, L_var2)

#define fxp_mac_16_by_16_bb(a, b, c)  fxp_mac_16_by_16(  a, b, c)

#define fxp_mac_16_by_16_bt( L_var1, L_var2, L_add)  _SmulAddLoHi_SW_SL( L_add, L_var1, L_var2)


    __inline  Int32 cmplx_mul32_by_16(Int32 L_var1, const Int32 L_var2, const Int32 cmplx)
    {
        Int32 result64_hi;

        result64_hi = _SmulWHi_SW_SL(L_var1, cmplx);
        result64_hi = _SmulAddWLo_SW_SL(result64_hi, L_var2, cmplx);

        return (result64_hi);
    }

#define fxp_mul32_by_16( L_var1, L_var2)  _SmulWLo_SW_SL( L_var1, L_var2)

#define fxp_mul32_by_16b( a, b)   fxp_mul32_by_16( a, b)

#define fxp_mul32_by_16t( L_var1, L_var2)  _SmulWHi_SW_SL( L_var1, L_var2)

#define fxp_mac32_by_16( L_var1, L_var2, L_add)  _SmulAddWLo_SW_SL( L_add, L_var1, L_var2)


    __inline  int64 fxp_mac64_Q31(int64 sum, const Int32 L_var1, const Int32 L_var2)
    {
        sum += (int64)L_var1 * L_var2;
        return (sum);
    }

#define fxp_mul32_Q31( a,  b)   _MulHigh( b, a)


    __inline Int32 fxp_mac32_Q31(Int32 L_add, const Int32 a, const Int32 b)
    {
        return (L_add + _MulHigh(b, a));
    }


    __inline Int32 fxp_msu32_Q31(Int32 L_sub, const Int32 a, const Int32 b)
    {
        return (L_sub - _MulHigh(b, a));
    }


    __inline Int32 fxp_mul32_Q30(const Int32 a, const Int32 b)
    {
        return (Int32)(((int64)(a) * b) >> 30);
    }

    __inline Int32 fxp_mac32_Q30(const Int32 a, const Int32 b, Int32 L_add)
    {
        return (L_add + (Int32)(((int64)(a) * b) >> 30));
    }


    __inline Int32 fxp_mul32_Q29(const Int32 a, const Int32 b)
    {
        return (Int32)(((int64)(a) * b) >> 29);
    }

    __inline Int32 fxp_mac32_Q29(const Int32 a, const Int32 b, Int32 L_add)
    {
        return (L_add + (Int32)(((int64)(a) * b) >> 29));
    }

    __inline Int32 fxp_msu32_Q29(const Int32 a, const Int32 b, Int32 L_sub)
    {
        return (L_sub - (Int32)(((int64)(a) * b) >> 29));
    }


    __inline Int32 fxp_mul32_Q28(const Int32 a, const Int32 b)
    {
        return (Int32)(((int64)(a) * b) >> 28);
    }

    __inline Int32 fxp_mul32_Q27(const Int32 a, const Int32 b)
    {
        return (Int32)(((int64)(a) * b) >> 27);
    }

    __inline Int32 fxp_mul32_Q26(const Int32 a, const Int32 b)
    {
        return (Int32)(((int64)(a) * b) >> 26);
    }

    __inline Int32 fxp_mul32_Q20(const Int32 a, const Int32 b)
    {
        return (Int32)(((int64)(a) * b) >> 20);
    }

    __inline Int32 fxp_mul32_Q15(const Int32 a, const Int32 b)
    {
        return (Int32)(((int64)(a) * b) >> 15);
    }

    __inline Int32 fxp_mul32_Q14(const Int32 a, const Int32 b)
    {
        return (Int32)(((int64)(a) * b) >> 14);
    }


#endif


#ifdef __cplusplus
}
#endif


#endif   /*  FXP_MUL32  */

