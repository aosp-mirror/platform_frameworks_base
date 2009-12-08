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

 Pathname: ./src/fwd_long_complex_rot.c
 Funtions: fwd_long_complex_rot

------------------------------------------------------------------------------
 REVISION HISTORY

 Date: 10/18/2002
 Description:
            (1) Change the input arguments, no shifts information from
                long_fft_rx4 is passed, only a single max is passed.
            (2) Eliminate search for max, a fixed shift has replaced the
                search for max with minimal loss of precision.
            (3) Eliminated unused variables

 Date: 10/28/2002
 Description:
            (1) Added comments per code review
            (2) Eliminated hardly used condition on if-else (exp==0)

 Description:

 ------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:

    Data_in   = Input vector (sized for long windows
                TWICE_FWD_LONG_CX_ROT_LENGTH), with time domain samples
                type Int32 *

    Data_out  = Output vector with a post-rotation by exp(-j(2pi/N)(k+1/8)),
                (sized for long windows TWICE_FWD_LONG_CX_ROT_LENGTH)
                type Int32 *

    max       = Input, carries the maximum value of the input vector
                "Data_in"
                type Int32


 Local Stores/Buffers/Pointers Needed:
    None

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    exp = shift factor to reflect signal scaling

 Pointers and Buffers Modified:
    Results are return in "Data_out"

 Local Stores Modified:
    None

 Global Stores Modified:
    None
------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    fwd_long_complex_rot() performs the pre complex rotation for the MDCT
    for the case of long windows. It also performs digit reverse ordering of
    the first and second halves of the input vector "Data_in", as well as
    reordering of the two half vectors (following radix-2 decomposition)
    Word normalization is also done to ensure 16 by 16 bit multiplications.

------------------------------------------------------------------------------
 REQUIREMENTS

    fwd_long_complex_rot() should execute a pre-rotation by
    exp(-j(2pi/N)(k+1/8)), digit reverse ordering and normalization
------------------------------------------------------------------------------
 REFERENCES

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

#include "fwd_long_complex_rot.h"
#include "digit_reversal_tables.h"
#include "imdct_fxp.h"
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

/*----------------------------------------------------------------------------
; EXTERNAL VARIABLES REFERENCES
; Declare variables used in this module but defined elsewhere
----------------------------------------------------------------------------*/

Int fwd_long_complex_rot(
    Int32 *Data_in,
    Int32 *Data_out,
    Int32  max)
{
    Int     i;
    const   Int32 *p_rotate;
    Int32   temp_re;
    Int32   temp_im;
    Int32   *pData_in_ref1;
    Int32   *pData_in_ref2;
    Int32   exp_jw;
    Int32   temp_re_32;
    Int32   temp_im_32;

    Int32   *pData_out_1;
    Int32   *pData_out_2;
    Int32   *pData_out_3;
    Int32   *pData_out_4;

    Int32 *pData_in_1;
    Int32 *pData_in_2;

    Int     exp;

    p_rotate       =  exp_rotation_N_2048;

    pData_in_ref1  =  Data_in;
    pData_in_ref2  = &Data_in[TWICE_FWD_LONG_CX_ROT_LENGTH];

    pData_out_1 = Data_out;
    pData_out_2 = &Data_out[LONG_WINDOW_LENGTH_m_1];
    pData_out_3 = &Data_out[LONG_WINDOW_LENGTH];
    pData_out_4 = &Data_out[TWICE_LONG_WINDOW_LENGTH_m_1];

    /*
     *  Data_out
     *                                   >>>>                   <<<<
     *                                pData_out_3             pData_out_4
     *      |             |             |             |             |
     * pData_out_1               pData_out_2
     *      >>>>                     <<<<
     */


    exp = 16 - pv_normalize(max);

    if (exp < 0)
    {
        exp = 0;
    }

    /*
     *  Apply  A/2^(diff) + B
     */


    pData_in_1 = pData_in_ref1;
    pData_in_2 = pData_in_ref2;

    for (i = FWD_LONG_CX_ROT_LENGTH; i != 0; i--)
    {

        /*
         * cos_n + j*sin_n == exp(j(2pi/N)(k+1/8))
         */

        exp_jw = *p_rotate++;

        /*
         *  Use auxiliary variables to avoid double accesses to memory.
         *  Data in is scaled to use only lower 16 bits.
         */

        temp_re =  *(pData_in_1++) >> exp;
        temp_im =  *(pData_in_1++) >> exp;

        /*
         *   Pre-rotation
         */

        temp_re_32  = (cmplx_mul32_by_16(temp_re,   temp_im,  exp_jw));
        temp_im_32  = (cmplx_mul32_by_16(temp_im,  -temp_re,  exp_jw));

        *(pData_out_1++) = - temp_re_32;
        *(pData_out_2--) =   temp_im_32;
        *(pData_out_3++) = - temp_im_32;
        *(pData_out_4--) =   temp_re_32;

        /*
         *   Pointer increment to jump over imag (1 & 4) or real parts
         *   (2 & 3)
         */
        pData_out_1++;
        pData_out_2--;
        pData_out_3++;
        pData_out_4--;

        /*
         *   Repeat procedure for odd index at the output
         */

        exp_jw = *p_rotate++;

        temp_re =  *(pData_in_2++) >> exp;
        temp_im =  *(pData_in_2++) >> exp;

        temp_re_32  = (cmplx_mul32_by_16(temp_re,   temp_im,  exp_jw));
        temp_im_32  = (cmplx_mul32_by_16(temp_im,  -temp_re,  exp_jw));

        *(pData_out_1++) = - temp_re_32;
        *(pData_out_2--) =   temp_im_32;
        *(pData_out_3++) = - temp_im_32;
        *(pData_out_4--) =   temp_re_32;

        pData_out_1++;
        pData_out_2--;
        pData_out_3++;
        pData_out_4--;

    }

    return (exp + 1);
}
