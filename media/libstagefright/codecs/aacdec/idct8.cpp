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

 Filename: idct8.c

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

    Int32 x             32-bit integer input length 8


------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    Implement inverse discrete cosine transform of lenght 8

------------------------------------------------------------------------------
 REQUIREMENTS


------------------------------------------------------------------------------
 REFERENCES

------------------------------------------------------------------------------
 PSEUDO-CODE

------------------------------------------------------------------------------
*/

#ifdef AAC_PLUS

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include "idct8.h"

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


#define R_SHIFT     29
#define Qfmt(x)   (Int32)(x*((Int32)1<<R_SHIFT) + (x>=0?0.5F:-0.5F))

#define Qfmt15(x)   (Int16)(x*((Int32)1<<15) + (x>=0?0.5F:-0.5F))


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

void idct_8(Int32 vec[])
{

    Int32 tmp0;
    Int32 tmp1;
    Int32 tmp2;
    Int32 tmp3;
    Int32 tmp4;
    Int32 tmp5;
    Int32 tmp6;
    Int32 tmp7;
    Int32 tmp8;


    tmp5 = fxp_mul32_by_16(vec[4] << 1, Qfmt15(0.70710678118655F));

    tmp1 =  vec[0] + tmp5;
    tmp5 =  vec[0] - tmp5;

    tmp3 = fxp_mul32_by_16(vec[2] << 1, Qfmt15(0.54119610014620F));     /* (1/(2*cos(2*phi)));*/
    tmp7 = fxp_mul32_Q29(vec[6], Qfmt(1.30656296487638F));      /* (1/(2*cos(6*phi)));*/

    tmp0  = fxp_mul32_by_16((tmp3 - tmp7) << 1, Qfmt15(0.70710678118655F)); /* (1/(2*cos(2*phi)));  */
    tmp7 = (tmp3 + tmp7) + tmp0;

    vec[0] = tmp1 + tmp7;
    tmp2 = fxp_mul32_by_16(vec[1] << 1, Qfmt15(0.50979557910416F));     /* (1/(2*cos(  phi)));*/
    vec[1] = tmp5 + tmp0;
    vec[2] = tmp5 - tmp0;
    tmp4 = fxp_mul32_by_16(vec[3] << 1, Qfmt15(0.60134488693505F));     /* (1/(2*cos(3*phi)));*/
    vec[3] = tmp1 - tmp7;

    tmp6 = fxp_mul32_by_16(vec[5] << 1, Qfmt15(0.89997622313642F));     /* (1/(2*cos(5*phi)));*/
    tmp8 = fxp_mul32_Q29(vec[7], Qfmt(2.56291544774151F));      /* (1/(2*cos(7*phi)));*/

    tmp7  =  tmp2 + tmp8;
    tmp5  = fxp_mul32_by_16((tmp2 - tmp8) << 1, Qfmt15(0.54119610014620F));
    tmp8  =  tmp4 + tmp6;
    tmp6  = fxp_mul32_Q29((tmp4 - tmp6), Qfmt(1.30656296487638F));

    tmp0 =  tmp7 + tmp8;
    tmp2 = fxp_mul32_by_16((tmp7 - tmp8) << 1, Qfmt15(0.70710678118655F));

    tmp3 = fxp_mul32_by_16((tmp5 - tmp6) << 1, Qfmt15(0.70710678118655F));
    tmp1 = (tmp5 + tmp6) + tmp3;

    tmp5 = tmp0 + tmp1;
    tmp6 = tmp1 + tmp2;
    tmp7 = tmp2 + tmp3;

    vec[7]  = vec[0] - tmp5;
    vec[0] +=          tmp5;
    vec[6]  = vec[1] - tmp6;
    vec[1] +=          tmp6;
    vec[5]  = vec[2] - tmp7;
    vec[2] +=          tmp7;
    vec[4]  = vec[3] - tmp3;
    vec[3] +=          tmp3;

}


#endif

