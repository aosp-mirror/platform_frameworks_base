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

 Filename: analysis_sub_band.c

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

    Int32 vec[],            Input vector, 32-bit
    const Int32 *cosTerms,  Cosine Terms
    Int   maxbands          number of bands used
    Int32 *scratch_mem      Scratch memory


------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    Implement root squared of a number

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


#include "analysis_sub_band.h"
#include "dst32.h"
#include "idct32.h"
#include "mdst.h"

#include "aac_mem_funcs.h"
#include "pv_audio_type_defs.h"
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

/*----------------------------------------------------------------------------
; LOCAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL STORE/BUFFER/POINTER DEFINITIONS
; Variable declaration - defined here and used outside this module
----------------------------------------------------------------------------*/


#ifdef HQ_SBR


const Int32 exp_1_5_phi[32] =
{

    0x7FEA04B6,  0x7F380E1C, 0x7DD6176E, 0x7BC6209F,
    0x790A29A4,  0x75A6326E, 0x719E3AF3, 0x6CF94326,
    0x67BD4AFB,  0x61F15269, 0x5B9D5964, 0x54CA5FE4,
    0x4D8165DE,  0x45CD6B4B, 0x3DB87023, 0x354E7460,
    0x2C9977FB,  0x23A77AEF, 0x1A837D3A, 0x113A7ED6,
    0x07D97FC2,  0xFE6E7FFE, 0xF5057F87, 0xEBAB7E60,
    0xE26D7C89,  0xD9587A06, 0xD07976D9, 0xC7DB7308,
    0xBF8C6E97,  0xB796698C, 0xB00563EF, 0xA8E25DC8,

};

#endif


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


void analysis_sub_band_LC(Int32 vec[64],
                          Int32 cosine_total[],
                          Int32 maxBand,
                          Int32 scratch_mem[][64])
{
    Int32 i;
    Int32 *cosine_term = &scratch_mem[0][0];
    Int32 *sine_term   = &scratch_mem[0][32];

    Int32 *pt_cos_t;


    Int32 *pt_vec    =  &vec[0];
    Int32 *pt_vec_32 =  &vec[32];

    Int32 *pt_cos = cosine_term;
    Int32 *pt_sin = sine_term;

    for (i = 8; i != 0; i--)
    {
        Int32 tmp1 = *(pt_vec_32++);
        Int32 tmp3 = *(pt_vec_32++);
        Int32 tmp2 = *(pt_vec++);
        Int32 tmp4 = *(pt_vec++);
        *(pt_cos++) = (tmp1 - tmp2) >> 1;
        *(pt_cos++) = (tmp3 - tmp4) >> 1;
        *(pt_sin++) = (tmp1 + tmp2);
        *(pt_sin++) = (tmp3 + tmp4);
        tmp1 = *(pt_vec_32++);
        tmp3 = *(pt_vec_32++);
        tmp2 = *(pt_vec++);
        tmp4 = *(pt_vec++);
        *(pt_cos++) = (tmp1 - tmp2) >> 1;
        *(pt_cos++) = (tmp3 - tmp4) >> 1;
        *(pt_sin++) = (tmp1 + tmp2);
        *(pt_sin++) = (tmp3 + tmp4);
    }


    idct_32(cosine_term, scratch_mem[1]);

    dst_32(sine_term, scratch_mem[1]);

    pt_cos  = cosine_term;
    pt_sin  = sine_term;

    pt_cos_t  = cosine_total;

    for (i = 0; i < maxBand; i += 4)
    {
        *(pt_cos_t++) = (*(pt_cos++) + *(pt_sin++));
        *(pt_cos_t++) = (-*(pt_cos++) + *(pt_sin++));
        *(pt_cos_t++) = (-*(pt_cos++) - *(pt_sin++));
        *(pt_cos_t++) = (*(pt_cos++) - *(pt_sin++));
    }

    pt_cos_t  = &cosine_total[maxBand];

    for (i = (32 - maxBand); i != 0; i--)
    {
        *(pt_cos_t++) =   0;
    }
}


#ifdef HQ_SBR


void analysis_sub_band(Int32 vec[64],
                       Int32 cosine_total[],
                       Int32 sine_total[],
                       Int32 maxBand,
                       Int32 scratch_mem[][64])
{
    Int32 i;
    Int32 *sine_term1   = &scratch_mem[0][0];
    Int32 *sine_term2   = &scratch_mem[0][32];

    Int32 temp1;
    Int32 temp2;
    Int32 temp3;
    Int32 temp4;

    const Int32 *pt_exp;
    Int32 exp_1_5;

    Int32 *pt_vec    =  &vec[0];
    Int32 *pt_vec_32 =  &vec[32];

    Int32 *pt_cos1 = pt_vec;
    Int32 *pt_sin1 = sine_term1;
    Int32 *pt_cos2 = pt_vec_32;
    Int32 *pt_sin2 = sine_term2;


    pv_memcpy(sine_term1, vec, 64*sizeof(*vec));

    mdst_32(sine_term1, scratch_mem[1]);
    mdst_32(sine_term2, scratch_mem[1]);

    mdct_32(&vec[ 0]);
    mdct_32(&vec[32]);

    pt_cos1 = &vec[ 0];
    pt_cos2 = &vec[32];


    pt_sin1 = sine_term1;
    pt_sin2 = sine_term2;

    pt_vec     = cosine_total;
    pt_vec_32  =   sine_total;
    pt_exp  = exp_1_5_phi;

    temp3 = (*(pt_cos1++) - *(pt_sin2++));
    temp4 = (*(pt_sin1++) + *(pt_cos2++));

    for (i = 0; i < maxBand; i += 2)
    {

        exp_1_5 = *(pt_exp++);
        temp1    =  cmplx_mul32_by_16(temp3,  temp4, exp_1_5);
        temp2    =  cmplx_mul32_by_16(temp4, -temp3, exp_1_5);

        *(pt_vec++)    =  shft_lft_1(temp1);
        *(pt_vec_32++) =  shft_lft_1(temp2);

        temp3 = (*(pt_cos1++) + *(pt_sin2++));
        temp4 = (*(pt_sin1++) - *(pt_cos2++));

        exp_1_5 = *(pt_exp++);
        temp1    =  cmplx_mul32_by_16(temp3,  temp4, exp_1_5);
        temp2    =  cmplx_mul32_by_16(temp4, -temp3, exp_1_5);

        *(pt_vec++)    =  shft_lft_1(temp1);
        *(pt_vec_32++) =  shft_lft_1(temp2);

        temp3 = (*(pt_cos1++) - *(pt_sin2++));
        temp4 = (*(pt_sin1++) + *(pt_cos2++));
    }


    pt_cos1  = &cosine_total[maxBand];  /* in the chance that maxband is not even */
    pt_sin1  = &sine_total[maxBand];

    for (i = (32 - maxBand); i != 0; i--)
    {
        *(pt_cos1++) =  0;
        *(pt_sin1++) =  0;
    }

}


#endif

#endif

