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

 Pathname: mix_radix_fft.c
 Funtions: mix_radix_fft

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:  Eliminated pointer dependency ( pData_1) on Buffer address.
               Modified for-loop to countdown loops.

 Description:  No shift information going in/out from fft_rx4_long.

 Description:
            (1) Increased precision on the radix 2 fft coeff. (from Q10 to Q12)
            (2) Increased precision on the input (from 0.5 to 1.0).
            (3) Eliminated hardly used condition (exp = 0).
            (4) Change interface to fft_rx4_long, so now the same function is
                used for forward and inverse calculations.

 Description:  per code review comments, eliminated unnecessary headers

 Who:                       Date:
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    Data         = Input vector, with quantized spectral with a pre-rotation
                   by exp(j(2pi/N)(k+1/8))
                   type Int32 *

    peak_value   = Input, carries the maximum value in input vector "Data"
                   Output, maximum value computed in the first FFT, used
                   to set precision on next stages
                   type Int32 *


 Local Stores/Buffers/Pointers Needed:
    None

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    exponent = shift factor to reflect signal scaling

 Pointers and Buffers Modified:
    Results are return in "Data"

 Local Stores Modified:
    None

 Global Stores Modified:
    None
------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    mix_radix_fft() mixes radix-2 and radix-4 FFT. This is needed to be able
    to use power of 4 length when the input length sequence is a power of 2.
------------------------------------------------------------------------------
 REQUIREMENTS

    mix_radix_fft() should support only the FFT for the long window case of
    the inverse modified cosine transform (IMDCT)
------------------------------------------------------------------------------
 REFERENCES

  ------------------------------------------------------------------------------
 PSEUDO-CODE


   MODIFY( x[] )
   RETURN( exponent )

------------------------------------------------------------------------------
 RESOURCES USED
   When the code is written for a specific target processor the
     the resources used should be documented below.

 STACK USAGE: [stack count for this module] + [variable to represent
          stack usage for each subroutine called]

     where: [stack usage variable] = stack usage for [subroutine
         name] (see [filename].ext)

 DATA MEMORY USED: x words

 PROGRAM MEMORY USED: x words

 CLOCK CYCLES: [cycle count equation for this module] + [variable
           used to represent cycle count for each subroutine
           called]

     where: [cycle count variable] = cycle count for [subroutine
        name] (see [filename].ext)

------------------------------------------------------------------------------
*/
/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#include "fft_rx4.h"
#include "mix_radix_fft.h"
#include "pv_normalize.h"

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
; LOCAL VARIABLE DEFINITIONS
; Variable declaration - defined here and used outside this module
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; EXTERNAL FUNCTION REFERENCES
; Declare functions defined elsewhere and referenced in this module
----------------------------------------------------------------------------*/

#ifdef __cplusplus
extern "C"
{
#endif

    void digit_reversal_swapping(Int32 *y, Int32 *x);

#ifdef __cplusplus
}
#endif

/*----------------------------------------------------------------------------
; EXTERNAL VARIABLES REFERENCES
; Declare variables used in this module but defined elsewhere
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; EXTERNAL GLOBAL STORE/BUFFER/POINTER REFERENCES
; Declare variables used in this module but defined elsewhere
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/

Int mix_radix_fft(
    Int32   *Data,
    Int32   *peak_value
)

{

    const Int32     *p_w;
    Int32   *pData_1;
    Int32   *pData_2;

    Int32   *pData_3;
    Int32   *pData_4;

    Int32   exp_jw;
    Int32   max1;
    Int32   max2;
    Int32   temp1;
    Int32   temp2;
    Int32   temp3;
    Int32   temp4;
    Int32   diff1;
    Int32   diff2;
    Int     i;
    Int   exp;

    max1 = *peak_value;
    p_w  = w_512rx2;

    pData_1 = Data;
    pData_3 = Data + HALF_FFT_RX4_LENGTH_FOR_LONG;


    /*
     * normalization to 0.9999 (0x7FFF) guarantees proper operation
     */

    exp = 8 - pv_normalize(max1);   /* use 24 bits for mix radix fft */

    if (exp < 4)
    {
        exp = 4;
    }


    temp1      = (*pData_3);
    pData_4    = pData_3 + FFT_RX4_LENGTH_FOR_LONG;
    temp2      = (*pData_4++);



    diff1      = (temp1  - temp2) >> exp;
    *pData_3++ = (temp1  + temp2) >> exp;

    temp3      = (*pData_3);
    temp4      = (*pData_4);

    *pData_4-- = -diff1;
    *pData_3++ = (temp3  + temp4) >> exp;
    *pData_4   = (temp3  - temp4) >> exp;

    temp1      = (*pData_1);
    pData_2    = pData_1 + FFT_RX4_LENGTH_FOR_LONG;
    temp2      = (*pData_2++);
    temp4      = (*pData_2);

    *pData_1++ = (temp1  + temp2) >> exp;

    temp3      = (*pData_1);
    diff1      = (temp1  - temp2) >> exp ;

    *pData_1++ = (temp3  + temp4) >> exp;
    *pData_2-- = (temp3  - temp4) >> exp;
    *pData_2   =  diff1;

    temp1      = (*pData_3);
    pData_4    = pData_3 + FFT_RX4_LENGTH_FOR_LONG;
    temp2      = (*pData_4++);


    for (i = ONE_FOURTH_FFT_RX4_LENGTH_FOR_LONG - 1; i != 0; i--)
    {
        /*
         * radix 2 Butterfly
         */

        diff1      = (temp1  - temp2) >> (exp - 4);
        *pData_3++ = (temp1  + temp2) >> exp;

        temp3      = (*pData_3);
        temp4      = (*pData_4);

        exp_jw     = *p_w++;


        diff2      = (temp3  - temp4) >> (exp - 4);
        *pData_3++ = (temp3  + temp4) >> exp;

        *pData_4-- = -cmplx_mul32_by_16(diff1,  diff2, exp_jw) >> 3;
        *pData_4   =  cmplx_mul32_by_16(diff2, -diff1, exp_jw) >> 3;


        temp1      = (*pData_1);
        pData_2    = pData_1 + FFT_RX4_LENGTH_FOR_LONG;
        temp2      = (*pData_2++);
        temp4      = (*pData_2);

        *pData_1++ = (temp1  + temp2) >> exp;

        temp3      = (*pData_1);
        diff1      = (temp1  - temp2) >> (exp - 4);

        diff2      = (temp3  - temp4) >> (exp - 4);
        *pData_1++ = (temp3  + temp4) >> exp;

        *pData_2-- =  cmplx_mul32_by_16(diff2, -diff1, exp_jw) >> 3;
        *pData_2   =  cmplx_mul32_by_16(diff1,  diff2, exp_jw) >> 3;

        temp1      = (*pData_3);
        pData_4    = pData_3 + FFT_RX4_LENGTH_FOR_LONG;
        temp2      = (*pData_4++);

    }/* for i  */


    fft_rx4_long(
        Data,
        &max1);


    fft_rx4_long(
        &Data[FFT_RX4_LENGTH_FOR_LONG],
        &max2);

    digit_reversal_swapping(Data, &Data[FFT_RX4_LENGTH_FOR_LONG]);

    *peak_value = max1 | max2;

    return(exp);
}

