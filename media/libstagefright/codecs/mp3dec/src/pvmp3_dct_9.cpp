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

   Filename: pvmp3_dct_9.cpp

     Date: 09/21/2007

------------------------------------------------------------------------------
 REVISION HISTORY


 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

Input
    int32  vec[]             vector of 9  32-bit integers
Returns
    int32  vec[]             dct computation in-place


------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    Returns the dct of length 9 of the input vector

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
#include "pvmp3_audio_type_defs.h"
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
#define Qfmt31(a)   (int32)(a*(0x7FFFFFFF))

#define cos_pi_9    Qfmt31( 0.93969262078591f)
#define cos_2pi_9   Qfmt31( 0.76604444311898f)
#define cos_4pi_9   Qfmt31( 0.17364817766693f)
#define cos_5pi_9   Qfmt31(-0.17364817766693f)
#define cos_7pi_9   Qfmt31(-0.76604444311898f)
#define cos_8pi_9   Qfmt31(-0.93969262078591f)
#define cos_pi_6    Qfmt31( 0.86602540378444f)
#define cos_5pi_6   Qfmt31(-0.86602540378444f)
#define cos_5pi_18  Qfmt31( 0.64278760968654f)
#define cos_7pi_18  Qfmt31( 0.34202014332567f)
#define cos_11pi_18 Qfmt31(-0.34202014332567f)
#define cos_13pi_18 Qfmt31(-0.64278760968654f)
#define cos_17pi_18 Qfmt31(-0.98480775301221f)

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

void pvmp3_dct_9(int32 vec[])
{

    /*  split input vector */

    int32 tmp0 =  vec[8] + vec[0];
    int32 tmp8 =  vec[8] - vec[0];
    int32 tmp1 =  vec[7] + vec[1];
    int32 tmp7 =  vec[7] - vec[1];
    int32 tmp2 =  vec[6] + vec[2];
    int32 tmp6 =  vec[6] - vec[2];
    int32 tmp3 =  vec[5] + vec[3];
    int32 tmp5 =  vec[5] - vec[3];

    vec[0]  = (tmp0 + tmp2 + tmp3)     + (tmp1 + vec[4]);
    vec[6]  = ((tmp0 + tmp2 + tmp3) >> 1) - (tmp1 + vec[4]);
    vec[2]  = (tmp1 >> 1) - vec[4];
    vec[4]  =  -vec[2];
    vec[8]  =  -vec[2];
    vec[4]  = fxp_mac32_Q32(vec[4], tmp0 << 1, cos_2pi_9);
    vec[8]  = fxp_mac32_Q32(vec[8], tmp0 << 1, cos_4pi_9);
    vec[2]  = fxp_mac32_Q32(vec[2], tmp0 << 1, cos_pi_9);
    vec[2]  = fxp_mac32_Q32(vec[2], tmp2 << 1, cos_5pi_9);
    vec[4]  = fxp_mac32_Q32(vec[4], tmp2 << 1, cos_8pi_9);
    vec[8]  = fxp_mac32_Q32(vec[8], tmp2 << 1, cos_2pi_9);
    vec[8]  = fxp_mac32_Q32(vec[8], tmp3 << 1, cos_8pi_9);
    vec[4]  = fxp_mac32_Q32(vec[4], tmp3 << 1, cos_4pi_9);
    vec[2]  = fxp_mac32_Q32(vec[2], tmp3 << 1, cos_7pi_9);

    vec[1]  = fxp_mul32_Q32(tmp5 << 1, cos_11pi_18);
    vec[1]  = fxp_mac32_Q32(vec[1], tmp6 << 1, cos_13pi_18);
    vec[1]  = fxp_mac32_Q32(vec[1], tmp7 << 1,   cos_5pi_6);
    vec[1]  = fxp_mac32_Q32(vec[1], tmp8 << 1, cos_17pi_18);
    vec[3]  = fxp_mul32_Q32((tmp5 + tmp6  - tmp8) << 1, cos_pi_6);
    vec[5]  = fxp_mul32_Q32(tmp5 << 1, cos_17pi_18);
    vec[5]  = fxp_mac32_Q32(vec[5], tmp6 << 1,  cos_7pi_18);
    vec[5]  = fxp_mac32_Q32(vec[5], tmp7 << 1,    cos_pi_6);
    vec[5]  = fxp_mac32_Q32(vec[5], tmp8 << 1, cos_13pi_18);
    vec[7]  = fxp_mul32_Q32(tmp5 << 1, cos_5pi_18);
    vec[7]  = fxp_mac32_Q32(vec[7], tmp6 << 1, cos_17pi_18);
    vec[7]  = fxp_mac32_Q32(vec[7], tmp7 << 1,    cos_pi_6);
    vec[7]  = fxp_mac32_Q32(vec[7], tmp8 << 1, cos_11pi_18);

}



#endif // If not assembly
