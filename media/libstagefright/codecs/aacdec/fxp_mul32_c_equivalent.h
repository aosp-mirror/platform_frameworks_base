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

 Pathname: ./c/include/fxp_mul32_c_equivalent.h

------------------------------------------------------------------------------
 REVISION HISTORY

 Who:                                       Date:
 Description:
------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

------------------------------------------------------------------------------
*/

#ifndef FXP_MUL32_C_EQUIVALENT
#define FXP_MUL32_C_EQUIVALENT


#ifdef __cplusplus
extern "C"
{
#endif


#include "pv_audio_type_defs.h"


#if defined(C_EQUIVALENT)

#define preload_cache( a)

    __inline  Int32 shft_lft_1(Int32 L_var1)
    {
        if (((L_var1 << 1) >> 1) == L_var1)
            L_var1 <<= 1;
        else
            L_var1 = ((L_var1 >> 31) ^ INT32_MAX);

        return (L_var1);

    }


    __inline  Int32 fxp_mul_16_by_16bb(Int32 L_var1,  Int32 L_var2)
    {
        L_var2 = (L_var2 << 16) >> 16;
        L_var1 = (L_var1 << 16) >> 16;

        L_var1 *= L_var2;

        return L_var1;

    }


#define fxp_mul_16_by_16(a, b)  fxp_mul_16_by_16bb(  a, b)


    __inline  Int32 fxp_mul_16_by_16tb(Int32 L_var1,  Int32 L_var2)
    {
        L_var2 = (L_var2 << 16) >> 16;
        L_var1 =  L_var1 >> 16;

        L_var1 *= L_var2;

        return L_var1;

    }


    __inline  Int32 fxp_mul_16_by_16bt(Int32 L_var1,  Int32 L_var2)
    {
        L_var2 = L_var2 >> 16;
        L_var1 = (L_var1 << 16) >> 16;

        L_var1 *= L_var2;

        return L_var1;

    }


    __inline  Int32 fxp_mul_16_by_16tt(Int32 L_var1,  Int32 L_var2)
    {
        L_var2 = L_var2 >> 16;
        L_var1 = L_var1 >> 16;

        L_var1 *= L_var2;

        return L_var1;

    }

    __inline  Int32 fxp_mac_16_by_16(Int16 L_var1,  Int16 L_var2, Int32 L_add)
    {

        L_add += L_var1 * L_var2;

        return L_add;

    }





    __inline  Int32 fxp_mac_16_by_16_bb(Int16 L_var1,  Int32 L_var2, Int32 L_add)
    {
        L_var2 = (L_var2 << 16) >> 16;

        L_add += L_var1 * L_var2;

        return L_add;

    }


    __inline  Int32 fxp_mac_16_by_16_bt(Int16 L_var1,  Int32 L_var2, Int32 L_add)
    {
        L_var2 = L_var2 >> 16;

        L_add += L_var1 * L_var2;

        return L_add;

    }





    __inline  Int32 cmplx_mul32_by_16(Int32 x, const Int32 y, Int32 exp_jw)
    {
        Int32  rTmp0 = (Int16)(exp_jw >> 16);
        Int32  iTmp0 = exp_jw;
        Int32  z;

        z  = (Int32)(((int64_t)x * (rTmp0 << 16)) >> 32);
        z += (Int32)(((int64_t)y * (iTmp0 << 16)) >> 32);

        return (z);
    }


    __inline  Int32 fxp_mul32_by_16(Int32 L_var1, const Int32 L_var2)
    {
        Int32  z;

        z = (Int32)(((int64_t) L_var1 * (L_var2 << 16)) >> 32);
        return(z);
    }


#define fxp_mul32_by_16b( a, b)   fxp_mul32_by_16( a, b)


    __inline  Int32 fxp_mul32_by_16t(Int32 L_var1, const Int32 L_var2)
    {
        Int32  rTmp0 = (Int16)(L_var2 >> 16);
        Int32  z;

        z = (Int32)(((int64_t) L_var1 * (rTmp0 << 16)) >> 32);

        return(z);
    }


    __inline  Int32 fxp_mac32_by_16(const Int32 L_var1, const Int32 L_var2, Int32 L_add)
    {
        Int32  rTmp0 = L_var2 << 16;

        L_add += (Int32)(((int64_t) L_var1 * rTmp0) >> 32);

        return(L_add);
    }

    __inline  int64_t fxp_mac64_Q31(int64_t sum, const Int32 L_var1, const Int32 L_var2)
    {
        sum += (int64_t)L_var1 * L_var2;
        return (sum);
    }

    __inline Int32 fxp_mul32_Q31(const Int32 a, const Int32 b)
    {
        return (Int32)(((int64_t)(a) * b) >> 32);
    }

    __inline Int32 fxp_mac32_Q31(Int32 L_add, const Int32 a, const Int32 b)
    {
        return (L_add + (Int32)(((int64_t)(a) * b) >> 32));
    }

    __inline Int32 fxp_msu32_Q31(Int32 L_sub, const Int32 a, const Int32 b)
    {
        return (L_sub - (Int32)(((int64_t)(a) * b) >> 32));
    }


    __inline Int32 fxp_mul32_Q30(const Int32 a, const Int32 b)
    {
        return (Int32)(((int64_t)(a) * b) >> 30);
    }

    __inline Int32 fxp_mac32_Q30(const Int32 a, const Int32 b, Int32 L_add)
    {
        return (L_add + (Int32)(((int64_t)(a) * b) >> 30));
    }


    __inline Int32 fxp_mul32_Q29(const Int32 a, const Int32 b)
    {
        return (Int32)(((int64_t)(a) * b) >> 29);
    }

    __inline Int32 fxp_mac32_Q29(const Int32 a, const Int32 b, Int32 L_add)
    {
        return (L_add + (Int32)(((int64_t)(a) * b) >> 29));
    }

    __inline Int32 fxp_msu32_Q29(const Int32 a, const Int32 b, Int32 L_sub)
    {
        return (L_sub - (Int32)(((int64_t)(a) * b) >> 29));
    }


    __inline Int32 fxp_mul32_Q28(const Int32 a, const Int32 b)
    {
        return (Int32)(((int64_t)(a) * b) >> 28);
    }

    __inline Int32 fxp_mul32_Q27(const Int32 a, const Int32 b)
    {
        return (Int32)(((int64_t)(a) * b) >> 27);
    }

    __inline Int32 fxp_mul32_Q26(const Int32 a, const Int32 b)
    {
        return (Int32)(((int64_t)(a) * b) >> 26);
    }

    __inline Int32 fxp_mul32_Q20(const Int32 a, const Int32 b)
    {
        return (Int32)(((int64_t)(a) * b) >> 20);
    }

    __inline Int32 fxp_mul32_Q15(const Int32 a, const Int32 b)
    {
        return (Int32)(((int64_t)(a) * b) >> 15);
    }

    __inline Int32 fxp_mul32_Q14(const Int32 a, const Int32 b)
    {
        return (Int32)(((int64_t)(a) * b) >> 14);
    }



#endif


#ifdef __cplusplus
}
#endif


#endif   /*  FXP_MUL32  */

