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

 Pathname: .inv_long_complex_rot.c
 Funtions:  inv_long_complex_rot

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:  Change the input argument, no shifts information from long fft_rx4
               , do not have to check for shifts.

 Date: 10/18/2002
 Description:
            (1) Change the input argument, only a single max is passed.
            (2) Eliminate search for max, a fixed shift has replaced the
                search for max with minimal loss of precision.
            (3) Eliminated unused variables

 Date: 10/28/2002
 Description:
            (1) Added comments per code review

 Description:

 ------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:

    Data_in   = Input vector (sized for long windows
                TWICE_INV_LONG_CX_ROT_LENGTH), with time domain samples
                type Int32 *

    Data_out  = Output vector with a post-rotation by exp(j(2pi/N)(k+1/8)),
                (sized for long windows TWICE_INV_LONG_CX_ROT_LENGTH)
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

    inv_long_complex_rot() performs the complex rotation for the inverse MDCT
    for the case of long windows. It also performs digit reverse ordering of
    the first and second halves of the input vector "Data_in", as well as
    reordering of the two half vectors (following radix-2 decomposition)
    Word normalization is also done to ensure 16 by 16 bit multiplications.

------------------------------------------------------------------------------
 REQUIREMENTS

    inv_long_complex_rot() should execute a post-rotation by
    exp(-j(2pi/N)(k+1/8)), digit reverse ordering and word normalization

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

#include "digit_reversal_tables.h"
#include "inv_long_complex_rot.h"
#include "imdct_fxp.h"
#include "inv_long_complex_rot.h"
#include "pv_normalize.h"

#include "fxp_mul32.h"
#include "aac_mem_funcs.h"

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



Int inv_long_complex_rot(
    Int32 *Data,
    Int32  max)
{
    Int     i;
    Int16     I;
    const   Int32 *p_rotate;
    Int32   temp_re;
    Int32   temp_im;

    Int32    exp_jw;
    Int32   *pData_in_1;
    Int32   *pData_in_2;
    Int     exp;
    Int32   *pData_in_ref1;
    Int32   *pData_in_ref2;


    Int16   temp_re_0;
    Int16   temp_im_0;
    Int16   temp_re_1;
    Int16   temp_im_1;
    Int16   *p_Data_Int_precision;
    Int     n     = 2048;
    Int     n_2   = n >> 1;
    Int     n_4   = n >> 2;
    Int     n_3_4 = n_2 + n_4;

    Int16   *px_1;
    Int16   *px_2;
    Int16   *px_3;
    Int16   *px_4;

    Int16     J;
    const   Int32 *p_rotate2;




    p_rotate    =  &exp_rotation_N_2048[255];
    p_rotate2   =  &exp_rotation_N_2048[256];

    pData_in_ref1  =  Data;
    pData_in_ref2  = &Data[TWICE_INV_LONG_CX_ROT_LENGTH];


    /*
     *  Apply  A/2^(diff) + B
     */

    p_Data_Int_precision = (Int16 *)Data;

    exp = 16 - pv_normalize(max);


    /*
     *        px2-->               <--px1 px4-->               <--px3
     *
     *                     |                           |             |
     *       |+++++++++++++|+++++++++++++|+++++++++++++|+++++++++++++|
     *                     |             |             |             |
     *                    n/4           n/2          3n/4
     */

    I = 255;
    J = 256;

    pData_in_1 = pData_in_ref2 + I;

    px_1 = (Int16 *)pData_in_1;
    px_1++;

    pData_in_2 = pData_in_ref2 + J;

    px_4 = (Int16 *)pData_in_2;



    exp -= 1;


    for (i = INV_LONG_CX_ROT_LENGTH >> 1; i != 0; i--)
    {

        pData_in_2 = pData_in_ref1 + J;

        temp_im =  *(pData_in_2++);
        temp_re =  *(pData_in_2);


        /*
         * cos_n + j*sin_n == exp(j(2pi/N)(k+1/8))
         */
        exp_jw = *p_rotate2++;

        /*
         *   Post-rotation
         */



        temp_re_0  = (Int16)(cmplx_mul32_by_16(temp_re,  -temp_im,  exp_jw) >> exp);
        temp_im_0  = (Int16)(cmplx_mul32_by_16(temp_im,   temp_re,  exp_jw) >> exp);


        pData_in_1 = pData_in_ref2 + I;

        /*
         *  Use auxiliary variables to avoid double accesses to memory.
         *  Data in is scaled to use only lower 16 bits.
         */

        temp_re =  *(pData_in_1--);
        temp_im =  *(pData_in_1);

        /*
         * cos_n + j*sin_n == exp(j(2pi/N)(k+1/8))
         */
        exp_jw = *p_rotate--;


        /*
         *   Post-rotation
         */

        temp_re_1  = (Int16)(cmplx_mul32_by_16(temp_re,  -temp_im,  exp_jw) >> exp);
        temp_im_1  = (Int16)(cmplx_mul32_by_16(temp_im,   temp_re,  exp_jw) >> exp);


        /*
         *   Repeat procedure for odd index at the output
         */

        pData_in_2 = pData_in_ref2 + J;
        J += 2;

        temp_im =  *(pData_in_2++);
        temp_re =  *(pData_in_2);


        *(px_1--) =  temp_re_0;
        *(px_1--) =  temp_im_1;
        *(px_4++) =  temp_im_0;
        *(px_4++) =  temp_re_1;


        exp_jw = *p_rotate2++;


        *(px_1--)  = (Int16)(cmplx_mul32_by_16(temp_re,  -temp_im,  exp_jw) >> exp);
        *(px_4++)  = (Int16)(cmplx_mul32_by_16(temp_im,   temp_re,  exp_jw) >> exp);



        /*
         *   Repeat procedure for odd index at the output
         */

        pData_in_1 = pData_in_ref1 + I;
        I -= 2;

        temp_re =  *(pData_in_1--);
        temp_im =  *(pData_in_1);


        exp_jw = *p_rotate--;


        *(px_4++)  = (Int16)(cmplx_mul32_by_16(temp_re,  -temp_im,  exp_jw) >> exp);
        *(px_1--)  = (Int16)(cmplx_mul32_by_16(temp_im,   temp_re,  exp_jw) >> exp);

    }

    /*
     *                                           <--px1 px4-->
     *
     *                     |                           |             |
     *       |-------------|-------------|/////////////|\\\\\\\\\\\\\|
     *                     |             |             |             |
     *                    n/4           n/2          3n/4
     */


    px_1 = p_Data_Int_precision + n_2 - 1;
    px_2 = p_Data_Int_precision;

    px_4 = p_Data_Int_precision + n_3_4 - 1;

    for (i = 0; i<INV_LONG_CX_ROT_LENGTH >> 1; i++)
    {

        Int16 temp_re_0 = *(px_4--);
        Int16 temp_im_1 = *(px_4--);
        Int16 temp_re_2 = *(px_4--);
        Int16 temp_im_3 = *(px_4--);
        *(px_1--) = temp_re_0;
        *(px_1--) = temp_im_1;
        *(px_1--) = temp_re_2;
        *(px_1--) = temp_im_3;

        *(px_2++) = (-temp_re_0);
        *(px_2++) = (-temp_im_1);
        *(px_2++) = (-temp_re_2);
        *(px_2++) = (-temp_im_3);

    }


    px_4 = p_Data_Int_precision + n_2;


    pv_memcpy(px_4, pData_in_ref2 + 256, TWICE_INV_LONG_CX_ROT_LENGTH*sizeof(*px_4));



    /*
     *        px2-->               <--px1 px4-->               <--px3
     *
     *                     |                           |             |
     *       |+++++++++++++|+++++++++++++|+++++++++++++|+++++++++++++|
     *                     |             |             |             |
     *                    n/4           n/2          3n/4
     */
    px_3 = p_Data_Int_precision + n - 1;


    for (i = 0; i<INV_LONG_CX_ROT_LENGTH >> 1; i++)
    {

        Int16 temp_im_0 = *(px_4++);
        Int16 temp_re_1 = *(px_4++);
        Int16 temp_im_2 = *(px_4++);
        Int16 temp_re_3 = *(px_4++);
        *(px_3--) =  temp_im_0;
        *(px_3--) =  temp_re_1;
        *(px_3--) =  temp_im_2;
        *(px_3--) =  temp_re_3;

    }


    return (exp + 1);
}

