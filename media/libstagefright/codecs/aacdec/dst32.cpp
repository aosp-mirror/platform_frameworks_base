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

 Filename: dst32.c

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

    Int32 x             32-bit integer input length 32


------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    Implement discrete sine transform of lenght 32

------------------------------------------------------------------------------
 REQUIREMENTS


------------------------------------------------------------------------------
 REFERENCES

------------------------------------------------------------------------------
 PSEUDO-CODE

------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#ifdef AAC_PLUS


#include "dst32.h"
#include "dst16.h"

#include "fxp_mul32.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/


#define R_SHIFT1     29
#define Qfmt29(x)   (Int32)(x*((Int32)1<<R_SHIFT1) + (x>=0?0.5F:-0.5F))
#define Qfmt31(a)   (Int32)(a*0x7FFFFFFF + (a>=0?0.5F:-0.5F))

const Int32 CosTable_16[14] =
{
    Qfmt31(0.50060299823520F),   Qfmt31(0.50547095989754F),
    Qfmt31(0.51544730992262F),   Qfmt31(0.53104259108978F),
    Qfmt31(0.55310389603444F),   Qfmt31(0.58293496820613F),
    Qfmt31(0.62250412303566F),   Qfmt31(0.67480834145501F),
    Qfmt31(0.74453627100230F),   Qfmt31(0.83934964541553F),
    Qfmt29(0.97256823786196F),   Qfmt29(1.16943993343288F),
    Qfmt29(1.48416461631417F),   Qfmt29(2.05778100995341F)
};

/*----------------------------------------------------------------------------
; LOCAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL STORE/BUFFER/POINTER DEFINITIONS
; Variable declaration - defined here and used outside this module
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; EXTERNAL FUNCTION REFERENCES
; Declare functions defined elsewhere and referenced in this module
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; EXTERNAL GLOBAL STORE/BUFFER/POINTER REFERENCES
; Declare variables used in this module but defined elsewhere
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/


void dst_32(Int32 vec[], Int32 scratch_mem[])   /* scratch_mem size 32 */
{
    Int32 *temp_even = scratch_mem;

    Int32 i;
    const Int32 *pt_cos = &CosTable_16[13];
    Int32 tmp0 = vec[31] >> 1;
    Int32 tmp1, tmp2;
    Int32 *pt_even = temp_even;
    Int32 *pt_odd  = vec;
    Int32 *pt_vec  = vec;
    Int32 *pt_vecN_1  = vec;
    Int32 tmp3;


    tmp1 = 0;

    for (i = 5; i != 0; i--)
    {
        *(pt_even++) = *(pt_vec++);
        tmp2         = *(pt_vec++);
        *(pt_even++) = *(pt_vec++);
        tmp3         = *(pt_vec++);
        *(pt_even++) = *(pt_vec++);
        *(pt_odd++) = tmp2 + tmp1;
        *(pt_odd++) = tmp3 + tmp2;
        tmp1         = *(pt_vec++);
        *(pt_odd++) = tmp1 + tmp3;
    }

    *(pt_even) = *(pt_vec++);
    *(pt_odd)  = *(pt_vec) + tmp1;


    dst_16(temp_even, &scratch_mem[16]);
    dst_16(vec, &scratch_mem[24]);


    pt_vecN_1  = &vec[16];

    tmp1 = temp_even[15];

    tmp3  = fxp_mul32_Q31((vec[15] - tmp0) << 3, Qfmt31(0.63687550772175F)) << 2;
    tmp2  = temp_even[14];
    *(pt_vecN_1++)  = tmp3 - tmp1;
    vec[15]         = tmp3 + tmp1;
    tmp1  = temp_even[13];
    tmp3  = fxp_mul32_Q31((vec[14] + tmp0) << 3, Qfmt31(0.85190210461718F));
    *(pt_vecN_1++)  = tmp3 - tmp2;
    vec[14]         = tmp3 + tmp2;

    pt_even = &temp_even[12];
    pt_vec  = &vec[13];

    for (i = 2; i != 0; i--)
    {
        tmp3  = fxp_mul32_Q29((*(pt_vec) - tmp0), *(pt_cos--));
        tmp2 = *(pt_even--);
        *(pt_vec--)     = tmp3 + tmp1;
        *(pt_vecN_1++)  = tmp3 - tmp1;
        tmp3  = fxp_mul32_Q29((*(pt_vec) + tmp0), *(pt_cos--));
        tmp1 = *(pt_even--);
        *(pt_vec--)     = tmp3 + tmp2;
        *(pt_vecN_1++)  = tmp3 - tmp2;
    }

    for (i = 5; i != 0; i--)
    {
        tmp3  = fxp_mul32_Q31((*(pt_vec) - tmp0) << 1, *(pt_cos--));
        tmp2 = *(pt_even--);
        *(pt_vec--)     = tmp3 + tmp1;
        *(pt_vecN_1++)  = tmp3 - tmp1;
        tmp3  = fxp_mul32_Q31((*(pt_vec) + tmp0) << 1, *(pt_cos--));
        tmp1 = *(pt_even--);
        *(pt_vec--)     = tmp3 + tmp2;
        *(pt_vecN_1++)  = tmp3 - tmp2;
    }


}

#endif
