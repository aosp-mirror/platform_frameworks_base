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

 Filename: dst16.c

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

    Int32 x             32-bit integer input length 16


------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    Implement discrete sine transform of lenght 16

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

#include "dst16.h"
#include "dst8.h"
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


#define R_SHIFT     28
#define Qfmt(x)   (Int32)(x*((Int32)1<<R_SHIFT) + (x>=0?0.5F:-0.5F))

const Int32 CosTable_8[8] =
{
    Qfmt(0.50241928618816F),   Qfmt(0.52249861493969F),
    Qfmt(0.56694403481636F),   Qfmt(0.64682178335999F),
    Qfmt(0.78815462345125F),   Qfmt(1.06067768599035F),
    Qfmt(1.72244709823833F),   Qfmt(5.10114861868916F)
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


void dst_16(Int32 vec[], Int32 scratch_mem[])     /* scratch_mem size 8 */
{
    Int32 *temp_even = scratch_mem;

    Int i;
    const Int32 *pt_cos = &CosTable_8[7];
    Int32 tmp0 = vec[15] >> 1;
    Int32 tmp1, tmp2;
    Int32 *pt_even = temp_even;
    Int32 *pt_odd  = vec;
    Int32 *pt_vec  = vec;
    Int32 *pt_vecN_1;
    Int32 tmp3;


    *(pt_even++) = *(pt_vec++);
    tmp1         = *(pt_vec++);
    *(pt_odd++) = tmp1;

    for (i = 3; i != 0; i--)
    {
        *(pt_even++) = *(pt_vec++);
        tmp2         = *(pt_vec++);
        *(pt_even++) = *(pt_vec++);
        tmp3         = *(pt_vec++);
        *(pt_odd++) = tmp2 + tmp1;
        *(pt_odd++) = tmp3 + tmp2;
        tmp1         = tmp3;

    }

    *(pt_even)   = *(pt_vec++);
    *(pt_odd++) = *(pt_vec) + tmp1;


    dst_8(temp_even);
    dst_8(vec);

    pt_vec  = &vec[7];

    pt_even = &temp_even[7];
    pt_vecN_1  = &vec[8];

    tmp1 = *(pt_even--);

    for (i = 4; i != 0; i--)
    {
        tmp3  = fxp_mul32_Q28((*(pt_vec) - tmp0), *(pt_cos--));
        tmp2 = *(pt_even--);
        *(pt_vec--)     = tmp3 + tmp1;
        *(pt_vecN_1++)  = tmp3 - tmp1;
        tmp3  = fxp_mul32_Q28((*(pt_vec) + tmp0), *(pt_cos--));
        tmp1 = *(pt_even--);
        *(pt_vecN_1++)  = tmp3 - tmp2;
        *(pt_vec--)     = tmp3 + tmp2;
    }

}

#endif
