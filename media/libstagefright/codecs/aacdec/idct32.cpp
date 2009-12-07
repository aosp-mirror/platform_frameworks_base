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

  Filename: idct32.c

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

    Int32 x             32-bit integer input length 32


------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    Implement inverse discrete cosine transform of lenght 32

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

#include "idct32.h"
#include "dst32.h"
#include "idct16.h"

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
#define Qfmt1(x)   (Int32)(x*((Int32)1<<R_SHIFT1) + (x>=0?0.5F:-0.5F))

#define Qfmt3(a)   (Int32)(a*0x7FFFFFFF + (a>=0?0.5F:-0.5F))


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

void idct_32(Int32 vec[], Int32 scratch_mem[])   /* scratch_mem size 32 */
{
    Int32 *temp_even = scratch_mem;

    Int32 i;
    const Int32 *pt_cos = CosTable_16;
    Int32 tmp1, tmp2;
    Int32 *pt_even = temp_even;
    Int32 *pt_odd  = vec;
    Int32 *pt_vec  = vec;
    Int32 *pt_vecN_1;
    Int32 tmp3;


    *(pt_even++) = *(pt_vec++);
    tmp1         = *(pt_vec++);
    tmp2 = 0;

    for (i = 7; i != 0; i--)
    {
        *(pt_odd++) = tmp2 + tmp1;
        *(pt_even++) = *(pt_vec++);
        tmp2         = *(pt_vec++);
        *(pt_even++) = *(pt_vec++);
        *(pt_odd++) = tmp2 + tmp1;
        tmp1         = *(pt_vec++);
    }

    *(pt_odd++) = tmp2 + tmp1;
    *(pt_even++) = *(pt_vec++);
    tmp2         = *(pt_vec++);
    *(pt_odd++) = tmp2 + tmp1;


    idct_16(temp_even, &scratch_mem[16]);
    idct_16(vec, &scratch_mem[24]);


    pt_cos = &CosTable_16[13];

    pt_vec  = &vec[15];

    pt_even = &temp_even[15];
    pt_vecN_1  = &vec[16];

    tmp1 = *(pt_even--);


    tmp3  = fxp_mul32_Q31(*(pt_vec) << 3, Qfmt3(0.63687550772175F)) << 2;
    tmp2 = *(pt_even--);
    *(pt_vecN_1++)  = tmp1 - tmp3;
    *(pt_vec--)     = tmp1 + tmp3;
    tmp3  = fxp_mul32_Q31(*(pt_vec) << 3, Qfmt3(0.85190210461718F));

    tmp1 = *(pt_even--);
    *(pt_vecN_1++)  = tmp2 - tmp3;
    *(pt_vec--)     = tmp2 + tmp3;

    for (i = 2; i != 0; i--)
    {
        tmp3  = fxp_mul32_Q29(*(pt_vec), *(pt_cos--));
        tmp2 = *(pt_even--);
        *(pt_vecN_1++)  = tmp1 - tmp3;
        *(pt_vec--)     = tmp1 + tmp3;
        tmp3  = fxp_mul32_Q29(*(pt_vec), *(pt_cos--));
        tmp1 = *(pt_even--);
        *(pt_vecN_1++)  = tmp2 - tmp3;
        *(pt_vec--)     = tmp2 + tmp3;
    }

    for (i = 5; i != 0; i--)
    {
        tmp3  = fxp_mul32_Q31(*(pt_vec) << 1, *(pt_cos--));
        tmp2 = *(pt_even--);
        *(pt_vecN_1++)  = tmp1 - tmp3;
        *(pt_vec--)     = tmp1 + tmp3;
        tmp3  = fxp_mul32_Q31(*(pt_vec) << 1, *(pt_cos--));
        tmp1 = *(pt_even--);
        *(pt_vecN_1++)  = tmp2 - tmp3;
        *(pt_vec--)     = tmp2 + tmp3;
    }
}



#endif
