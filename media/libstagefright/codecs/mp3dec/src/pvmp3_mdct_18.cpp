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

   Filename: mdct_18.cpp

     Date: 09/21/2007

------------------------------------------------------------------------------
 REVISION HISTORY


 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

Input
    int32 vec[],        input vector of length 18
    int32 *history      input for overlap and add, vector updated with
                        next overlap and add values
    const int32 *window sine window used in the mdct, three types are allowed
                        noraml, start and stop
Returns
    none                mdct computation in-place


------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    Returns the mdct of length 18 of the input vector, as well as the overlap
    vector for next iteration ( on history[])

------------------------------------------------------------------------------
 REQUIREMENTS


------------------------------------------------------------------------------
 REFERENCES

------------------------------------------------------------------------------
 PSEUDO-CODE

------------------------------------------------------------------------------
*/

#if ( !defined(PV_ARM_GCC_V5) && !defined(PV_ARM_GCC_V4) && !defined(PV_ARM_V5) && !defined(PV_ARM_V4) )
/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#include "pv_mp3dec_fxd_op.h"
#include "pvmp3_mdct_18.h"


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
const int32 cosTerms_dct18[9] =
{
    Qfmt(0.50190991877167f),   Qfmt(0.51763809020504f),   Qfmt(0.55168895948125f),
    Qfmt(0.61038729438073f),   Qfmt(0.70710678118655f),   Qfmt(0.87172339781055f),
    Qfmt(1.18310079157625f),   Qfmt(1.93185165257814f),   Qfmt(5.73685662283493f)
};


const int32 cosTerms_1_ov_cos_phi[18] =
{

    Qfmt1(0.50047634258166f),  Qfmt1(0.50431448029008f),  Qfmt1(0.51213975715725f),
    Qfmt1(0.52426456257041f),  Qfmt1(0.54119610014620f),  Qfmt1(0.56369097343317f),
    Qfmt1(0.59284452371708f),  Qfmt1(0.63023620700513f),  Qfmt1(0.67817085245463f),

    Qfmt2(0.74009361646113f),  Qfmt2(0.82133981585229f),  Qfmt2(0.93057949835179f),
    Qfmt2(1.08284028510010f),  Qfmt2(1.30656296487638f),  Qfmt2(1.66275476171152f),
    Qfmt2(2.31011315767265f),  Qfmt2(3.83064878777019f),  Qfmt2(11.46279281302667f)
};

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



void pvmp3_mdct_18(int32 vec[], int32 *history, const int32 *window)
{
    int32 i;
    int32 tmp;
    int32 tmp1;
    int32 tmp2;
    int32 tmp3;
    int32 tmp4;



    const int32 *pt_cos_split = cosTerms_dct18;
    const int32 *pt_cos       = cosTerms_1_ov_cos_phi;
    const int32 *pt_cos_x     = &cosTerms_1_ov_cos_phi[17];
    int32 *pt_vec   =  vec;
    int32 *pt_vec_o = &vec[17];


    for (i = 9; i != 0; i--)
    {
        tmp  = *(pt_vec);
        tmp1 = *(pt_vec_o);
        tmp  = fxp_mul32_Q32(tmp << 1,  *(pt_cos++));
        tmp1 = fxp_mul32_Q27(tmp1, *(pt_cos_x--));
        *(pt_vec++)   =   tmp + tmp1 ;
        *(pt_vec_o--) = fxp_mul32_Q28((tmp - tmp1), *(pt_cos_split++));
    }


    pvmp3_dct_9(vec);         // Even terms
    pvmp3_dct_9(&vec[9]);     // Odd  terms


    tmp3     = vec[16];  //
    vec[16]  = vec[ 8];
    tmp4     = vec[14];  //
    vec[14]  = vec[ 7];
    tmp      = vec[12];
    vec[12]  = vec[ 6];
    tmp2     = vec[10];  // vec[10]
    vec[10]  = vec[ 5];
    vec[ 8]  = vec[ 4];
    vec[ 6]  = vec[ 3];
    vec[ 4]  = vec[ 2];
    vec[ 2]  = vec[ 1];
    vec[ 1]  = vec[ 9] - tmp2; //  vec[9] +  vec[10]
    vec[ 3]  = vec[11] - tmp2;
    vec[ 5]  = vec[11] - tmp;
    vec[ 7]  = vec[13] - tmp;
    vec[ 9]  = vec[13] - tmp4;
    vec[11]  = vec[15] - tmp4;
    vec[13]  = vec[15] - tmp3;
    vec[15]  = vec[17] - tmp3;


    /* overlap and add */

    tmp2 = vec[0];
    tmp3 = vec[9];

    for (i = 0; i < 6; i++)
    {
        tmp  = history[ i];
        tmp4 = vec[i+10];
        vec[i+10] = tmp3 + tmp4;
        tmp1 = vec[i+1];
        vec[ i] =  fxp_mac32_Q32(tmp, (vec[i+10]), window[ i]);
        tmp3 = tmp4;
        history[i  ] = -(tmp2 + tmp1);
        tmp2 = tmp1;
    }

    tmp  = history[ 6];
    tmp4 = vec[16];
    vec[16] = tmp3 + tmp4;
    tmp1 = vec[7];
    vec[ 6] =  fxp_mac32_Q32(tmp, vec[16] << 1, window[ i]);
    tmp  = history[ 7];
    history[6] = -(tmp2 + tmp1);
    history[7] = -(tmp1 + vec[8]);

    tmp1  = history[ 8];
    tmp4    = vec[17] + tmp4;
    vec[ 7] =  fxp_mac32_Q32(tmp, tmp4 << 1, window[ 7]);
    history[8] = -(vec[8] + vec[9]);
    vec[ 8] =  fxp_mac32_Q32(tmp1, vec[17] << 1, window[ 8]);

    tmp  = history[9];
    tmp1 = history[17];
    tmp2 = history[16];
    vec[ 9] =  fxp_mac32_Q32(tmp,  vec[17] << 1, window[ 9]);

    vec[17] =  fxp_mac32_Q32(tmp1, vec[10] << 1, window[17]);
    vec[10] = -vec[ 16];
    vec[16] =  fxp_mac32_Q32(tmp2, vec[11] << 1, window[16]);
    tmp1 = history[15];
    tmp2 = history[14];
    vec[11] = -vec[ 15];
    vec[15] =  fxp_mac32_Q32(tmp1, vec[12] << 1, window[15]);
    vec[12] = -vec[ 14];
    vec[14] =  fxp_mac32_Q32(tmp2, vec[13] << 1, window[14]);

    tmp  = history[13];
    tmp1 = history[12];
    tmp2 = history[11];
    tmp3 = history[10];
    vec[13] =  fxp_mac32_Q32(tmp,  vec[12] << 1, window[13]);
    vec[12] =  fxp_mac32_Q32(tmp1, vec[11] << 1, window[12]);
    vec[11] =  fxp_mac32_Q32(tmp2, vec[10] << 1, window[11]);
    vec[10] =  fxp_mac32_Q32(tmp3,    tmp4 << 1, window[10]);


    /* next iteration overlap */

    tmp1 = history[ 8];
    tmp3 = history[ 7];
    tmp2 = history[ 1];
    tmp  = history[ 0];
    tmp1 <<= 1;
    tmp3 <<= 1;

    history[ 0] = fxp_mul32_Q32(tmp1, window[18]);
    history[17] = fxp_mul32_Q32(tmp1, window[35]);
    history[ 1] = fxp_mul32_Q32(tmp3, window[19]);
    history[16] = fxp_mul32_Q32(tmp3, window[34]);

    tmp2 <<= 1;
    tmp  <<= 1;
    history[ 7] = fxp_mul32_Q32(tmp2, window[25]);
    history[10] = fxp_mul32_Q32(tmp2, window[28]);
    history[ 8] = fxp_mul32_Q32(tmp,  window[26]);
    history[ 9] = fxp_mul32_Q32(tmp,  window[27]);

    tmp1 = history[ 6];
    tmp3 = history[ 5];
    tmp4 = history[ 4];
    tmp2 = history[ 3];
    tmp  = history[ 2];

    tmp1 <<= 1;
    tmp3 <<= 1;
    tmp4 <<= 1;

    history[ 2] = fxp_mul32_Q32(tmp1, window[20]);
    history[15] = fxp_mul32_Q32(tmp1, window[33]);
    history[ 3] = fxp_mul32_Q32(tmp3, window[21]);
    history[14] = fxp_mul32_Q32(tmp3, window[32]);
    history[ 4] = fxp_mul32_Q32(tmp4, window[22]);
    history[13] = fxp_mul32_Q32(tmp4, window[31]);
    tmp2 <<= 1;
    tmp  <<= 1;
    history[ 5] = fxp_mul32_Q32(tmp2, window[23]);
    history[12] = fxp_mul32_Q32(tmp2, window[30]);
    history[ 6] = fxp_mul32_Q32(tmp,  window[24]);
    history[11] = fxp_mul32_Q32(tmp,  window[29]);
}

#endif // If not assembly
