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

   Filename: pvmp3_dct6.cpp

     Date: 09/21/2007

------------------------------------------------------------------------------
 REVISION HISTORY


 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

Input
    Int32  vec[]             vector of 6  32-bit integers
Returns
    Int32  vec[]             dct computation in-place


------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    Returns the dct of length 6 of the input vector

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

#include "pvmp3_audio_type_defs.h"
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
#define Qfmt30(a)   (Int32)(a*((Int32)1<<30) + (a>=0?0.5F:-0.5F))

#define cos_pi_6     Qfmt30(  0.86602540378444f)
#define cos_2_pi_6   Qfmt30(  0.5f)
#define cos_7_pi_12  Qfmt30( -0.25881904510252f)
#define cos_3_pi_12  Qfmt30(  0.70710678118655f)
#define cos_11_pi_12 Qfmt30( -0.96592582628907f)

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

void pvmp3_dct_6(int32 vec[])
{

    Int32 tmp0;
    Int32 tmp1;
    Int32 tmp2;
    Int32 tmp3;
    Int32 tmp4;
    Int32 tmp5;


    /*  split input vector */

    tmp0 =  vec[5] + vec[0];
    tmp5 =  vec[5] - vec[0];
    tmp1 =  vec[4] + vec[1];
    tmp4 =  vec[4] - vec[1];
    tmp2 =  vec[3] + vec[2];
    tmp3 =  vec[3] - vec[2];

    vec[0]  = tmp0 + tmp2 ;
    vec[2]  = fxp_mul32_Q30(tmp0 - tmp2,   cos_pi_6);
    vec[4]  = (vec[0] >> 1) - tmp1;
    vec[0] += tmp1;

    tmp0    =  fxp_mul32_Q30(tmp3,  cos_7_pi_12);
    tmp0    =  fxp_mac32_Q30(tmp4,  -cos_3_pi_12, tmp0);
    vec[1]  =  fxp_mac32_Q30(tmp5,  cos_11_pi_12, tmp0);

    vec[3]  =  fxp_mul32_Q30((tmp3 + tmp4  - tmp5), cos_3_pi_12);
    tmp0    =  fxp_mul32_Q30(tmp3, cos_11_pi_12);
    tmp0    =  fxp_mac32_Q30(tmp4,  cos_3_pi_12, tmp0);
    vec[5]  =  fxp_mac32_Q30(tmp5,  cos_7_pi_12, tmp0);

}




