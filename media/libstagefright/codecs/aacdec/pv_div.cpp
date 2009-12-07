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

 Filename: pv_div.c

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

    Int32 x             32-bit integer numerator
    Int32 y             32-bit integer denominator
    Quotient *result    structure that hold result and shift factor


------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    Implement division of two Int32 numbers, provides back quotient and a
    shift factor
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
#include "fxp_mul32.h"
#include "pv_div.h"
#include "pv_normalize.h"

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


void pv_div(Int32 x, Int32 y, Quotient *result)
{
    /*----------------------------------------------------------------------------
    ; Define all local variables
    ----------------------------------------------------------------------------*/
    Int32 quotient;
    Int32 i;
    Int32 j;
    Int32 y_ov_y_hi;
    Int32 flag = 0;     /* carries negative sign, if any  */


    result->shift_factor = 0;   /* default  */

    if (y == 0)
    {
        x = 0;   /* this will return 0 for any div/0 */
    }
    /*
     *  make sure x and y are both positive
     */

    if (y < 0)
    {
        y = -y;
        flag ^= 1;
    }


    if (x < 0)
    {
        x = -x;
        flag ^= 1;
    }

    if (x != 0)
    {
        /*----------------------------------------------------------------------------
        ; Scale the input to get maximum precision for x
        ----------------------------------------------------------------------------*/

        i = pv_normalize(x);

        x <<= i;


        /*----------------------------------------------------------------------------
        ; Scale the input to get maximum precision for y
        ----------------------------------------------------------------------------*/

        j = pv_normalize(y);

        y <<= j;

        result->shift_factor = i - j;

        /*----------------------------------------------------------------------------
        ; Function body here
        ----------------------------------------------------------------------------*/
        /*---------------------------------------------------------------
         ; take the inverse of the 16 MSB of y
         ---------------------------------------------------------------*/

        quotient = (0x40000000 / (y >> 15));

        y_ov_y_hi = fxp_mul32_Q15(y, quotient);            /*  y*(1/y_hi)     */

        y_ov_y_hi = 0x7FFFFFFF - y_ov_y_hi;                 /*  2 - y*(1/y_hi) */
        y_ov_y_hi = fxp_mul32_Q14(quotient,  y_ov_y_hi);
        i  = fxp_mul32_Q31(y_ov_y_hi,  x) << 1;

        result->quotient = flag ? -i : i;
    }
    else
    {
        result->quotient = 0;
    }



}

#endif


