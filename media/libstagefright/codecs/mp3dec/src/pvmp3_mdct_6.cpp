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
    int32 vec[],        input vector of length 6
    int32 *history      input for overlap and add, vector updated with
                        next overlap and add values
Returns
    none                mdct computation in-place


------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    Returns the mdct of length 6 of the input vector, as well as the overlap
    vector for next iteration ( on history[])

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

#include "pv_mp3dec_fxd_op.h"
#include "pvmp3_mdct_6.h"


/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/
#define QFORMAT    29
#define Qfmt29(a)   (int32)(a*((int32)1<<QFORMAT) + (a>=0?0.5F:-0.5F))

/*----------------------------------------------------------------------------
; LOCAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL STORE/BUFFER/POINTER DEFINITIONS
; Variable declaration - defined here and used outside this module
----------------------------------------------------------------------------*/
/*
 *  (1./(2*cos((pi/(2*N))*(2*i+1)))),  N = 12, i = [0:N/2-1]
 */

const int32 cosTerms_1_ov_cos_phi_N6[6] =
{

    Qfmt29(0.50431448029008f),   Qfmt29(0.54119610014620f),
    Qfmt29(0.63023620700513f),   Qfmt29(0.82133981585229f),
    Qfmt29(1.30656296487638f),   Qfmt29(3.83064878777019f)
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


void pvmp3_mdct_6(int32 vec[], int32 *history)
{
    int32 i;
    int32 tmp;
    int32 tmp1;
    int32 tmp2;

    int32 *pt_vec   = vec;
    int32 *pt_vec_o = vec;
    const int32 *pt_cos = cosTerms_1_ov_cos_phi_N6;

    for (i = 2; i != 0; i--)
    {
        tmp  = *(pt_vec++);
        tmp1 = *(pt_vec++);
        tmp2 = *(pt_vec++);
        *(pt_vec_o++)   = fxp_mul32_Q29(tmp, *(pt_cos++));
        *(pt_vec_o++)   = fxp_mul32_Q29(tmp1, *(pt_cos++));
        *(pt_vec_o++)   = fxp_mul32_Q29(tmp2, *(pt_cos++));
    }


    pvmp3_dct_6(vec);    // Even terms


    tmp = -(vec[0] + vec[1]);
    history[3] = tmp;
    history[2] = tmp;
    tmp = -(vec[1] + vec[2]);
    vec[0] =  vec[3] + vec[4];
    vec[1] =  vec[4] + vec[5];
    history[4] = tmp;
    history[1] = tmp;
    tmp = -(vec[2] + vec[3]);
    vec[4] = -vec[1];
    history[5] = tmp;
    history[0] = tmp;

    vec[2] =  vec[5];
    vec[3] = -vec[5];
    vec[5] = -vec[0];

}

