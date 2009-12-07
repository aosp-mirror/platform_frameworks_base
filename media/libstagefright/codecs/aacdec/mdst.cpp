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

 Filename: mdst.c

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

    Int32 x             32-bit integer input length 64


------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    mdst

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

#include "pv_audio_type_defs.h"
#include "synthesis_sub_band.h"
#include "dct16.h"
#include "dct64.h"
#include "mdst.h"

#ifdef HQ_SBR


/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/

#include "fxp_mul32.h"
#include "dst32.h"


#define Qfmt1(a)   (Int32)(a*0x7FFFFFFF + (a>=0?0.5F:-0.5F))
#define Qfmt(a)   (Int32)(a*((Int32)1<<27) + (a>=0?0.5F:-0.5F))

const Int32 CosTable_32[32] =
{
    Qfmt1(0.50015063602065F),  Qfmt1(0.50135845244641F),
    Qfmt1(0.50378872568104F),  Qfmt1(0.50747117207256F),
    Qfmt1(0.51245147940822F),  Qfmt1(0.51879271310533F),
    Qfmt1(0.52657731515427F),  Qfmt1(0.53590981690799F),
    Qfmt1(0.54692043798551F),  Qfmt1(0.55976981294708F),
    Qfmt1(0.57465518403266F),  Qfmt1(0.59181853585742F),
    Qfmt1(0.61155734788251F),  Qfmt1(0.63423893668840F),
    Qfmt1(0.66031980781371F),  Qfmt1(0.69037212820021F),
    Qfmt1(0.72512052237720F),  Qfmt1(0.76549416497309F),
    Qfmt1(0.81270209081449F),  Qfmt1(0.86834471522335F),
    Qfmt(0.93458359703641F),  Qfmt(1.01440826499705F),
    Qfmt(1.11207162057972F),  Qfmt(1.23383273797657F),
    Qfmt(1.38929395863283F),  Qfmt(1.59397228338563F),
    Qfmt(1.87467598000841F),  Qfmt(2.28205006800516F),
    Qfmt(2.92462842815822F),  Qfmt(4.08461107812925F),
    Qfmt(6.79675071167363F),  Qfmt(10.18693908361573F)
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

/*----------------------------------------------------------------------------
; mdst_32
----------------------------------------------------------------------------*/

void mdst_32(Int32 vec[], Int32 scratch_mem[])
{

    Int i;
    const Int32 *pt_cos = CosTable_32;
    Int32 *pt_vec = vec;
    Int32 tmp1;
    Int32 tmp2;



    Int32 tmp3;

    tmp3 = *(pt_vec++);
    tmp2 = *(pt_vec);

    for (i = 5; i != 0; i--)
    {
        *(pt_vec++)  += tmp3;
        tmp1 = *(pt_vec);
        *(pt_vec++)  += tmp2;
        tmp3 = *(pt_vec);
        *(pt_vec++)  += tmp1;
        tmp2 = *(pt_vec);
        *(pt_vec++)  += tmp3;
        tmp1 = *(pt_vec);
        *(pt_vec++)  += tmp2;
        tmp3 = *(pt_vec);
        *(pt_vec++)  += tmp1;
        tmp2 = *(pt_vec);
    }

    *(pt_vec)  += tmp3;

    dst_32(vec, scratch_mem);

    pt_vec = vec;

    for (i = 5; i != 0; i--)
    {
        *(pt_vec)   = fxp_mul32_Q31((*(pt_vec) << 1) + tmp2, *(pt_cos++));
        pt_vec++;
        *(pt_vec)   = fxp_mul32_Q31((*(pt_vec) << 1) - tmp2, *(pt_cos++));
        pt_vec++;
        *(pt_vec)   = fxp_mul32_Q31((*(pt_vec) << 1) + tmp2, *(pt_cos++));
        pt_vec++;
        *(pt_vec)   = fxp_mul32_Q31((*(pt_vec) << 1) - tmp2, *(pt_cos++));
        pt_vec++;
    }

    tmp2 >>= 1;
    for (i = 3; i != 0; i--)
    {
        *(pt_vec)   = fxp_mul32_Q27((*(pt_vec) + tmp2), *(pt_cos++));
        pt_vec++;
        *(pt_vec)   = fxp_mul32_Q27((*(pt_vec) - tmp2), *(pt_cos++));
        pt_vec++;
        *(pt_vec)   = fxp_mul32_Q27((*(pt_vec) + tmp2), *(pt_cos++));
        pt_vec++;
        *(pt_vec)   = fxp_mul32_Q27((*(pt_vec) - tmp2), *(pt_cos++));
        pt_vec++;
    }

    *(pt_vec - 1)   <<= 1;

}



/*----------------------------------------------------------------------------
; mdct_32
----------------------------------------------------------------------------*/

void mdct_32(Int32 vec[])
{
    Int i;
    Int32 *pt_vec  = vec;
    Int32 tmp1, tmp2;


    const Int32 *pt_CosTable = CosTable_32;


    for (i = 5; i != 0; i--)
    {
        *(pt_vec) = fxp_mul32_Q31(*(pt_vec) << 1, *(pt_CosTable++));
        pt_vec++;
        *(pt_vec) = fxp_mul32_Q31(*(pt_vec) << 1, *(pt_CosTable++));
        pt_vec++;
        *(pt_vec) = fxp_mul32_Q31(*(pt_vec) << 1, *(pt_CosTable++));
        pt_vec++;
        *(pt_vec) = fxp_mul32_Q31(*(pt_vec) << 1, *(pt_CosTable++));
        pt_vec++;
    }
    for (i = 3; i != 0; i--)
    {
        *(pt_vec) = fxp_mul32_Q27(*(pt_vec), *(pt_CosTable++));
        pt_vec++;
        *(pt_vec) = fxp_mul32_Q27(*(pt_vec), *(pt_CosTable++));
        pt_vec++;
        *(pt_vec) = fxp_mul32_Q27(*(pt_vec), *(pt_CosTable++));
        pt_vec++;
        *(pt_vec) = fxp_mul32_Q27(*(pt_vec), *(pt_CosTable++));
        pt_vec++;
    }
    *(pt_vec - 1)   <<= 1;


    dct_32(vec);


    pt_vec  = &vec[31];

    tmp1 = *(pt_vec--);

    for (i = 5; i != 0; i--)
    {
        tmp2 = *(pt_vec);
        *(pt_vec--)  += tmp1;
        tmp1 = *(pt_vec);
        *(pt_vec--)  += tmp2;
        tmp2 = *(pt_vec);
        *(pt_vec--)  += tmp1;
        tmp1 = *(pt_vec);
        *(pt_vec--)  += tmp2;
        tmp2 = *(pt_vec);
        *(pt_vec--)  += tmp1;
        tmp1 = *(pt_vec);
        *(pt_vec--)  += tmp2;
    }

    *(pt_vec)  += tmp1;

}

#endif /*  HQ_SBR  */


/*----------------------------------------------------------------------------
; dct_32
----------------------------------------------------------------------------*/


void dct_32(Int32 vec[])
{

    pv_split(&vec[16]);

    dct_16(&vec[16], 0);
    dct_16(vec, 1);     // Even terms

    pv_merge_in_place_N32(vec);
}

#endif  /* AAC_PLUS */


