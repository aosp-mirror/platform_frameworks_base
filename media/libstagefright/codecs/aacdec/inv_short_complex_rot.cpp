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

 Pathname: inv_short_complex_rot.c
 Funtions:  inv_short_complex_rot

------------------------------------------------------------------------------
 REVISION HISTORY

 Date: 10/18/2002
 Description:
            (1) Change the input argument, only a single max is passed.
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

    Data_in   = Input vector (sized for short windows
                2*INV_SHORT_CX_ROT_LENGTH elements), with time domain samples
                type Int32 *

    Data_out  = Output vector with a post-rotation by exp(j(2pi/N)(k+1/8)),
                (sized for short windows 2*INV_SHORT_CX_ROT_LENGTH)
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

    inv_short_complex_rot() performs the complex rotation for the inverse MDCT
    for the case of short windows. It performs digit reverse ordering as well
    word normalization to ensure 16 by 16 bit multiplications.

------------------------------------------------------------------------------
 REQUIREMENTS

    inv_short_complex_rot() should execute a post-rotation by
    exp( j(2pi/N)(k+1/8)), digit reverse ordering and word normalization

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
#include "imdct_fxp.h"
#include "inv_short_complex_rot.h"
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


Int inv_short_complex_rot(
    Int32 *Data_in,
    Int32 *Data_out,
    Int32  max)

{
    Int     i;
    Int16     I;
    const   Int16 *pTable;
    const   Int32 *p_rotate;

    Int32   *pData_in_1;
    Int     exp;
    Int32   temp_re;
    Int32   temp_im;

    Int32   exp_jw;
    Int16   *pData_re;
    Int16   *pData_im;
    Int32   *pData_in_ref;

    Int16   temp_re_0;
    Int16   temp_im_0;
    Int16   temp_re_1;
    Int16   temp_im_1;
    Int16   *p_data_1;
    Int16   *p_data_2;
    Int16   *p_Data_Int_precision;
    Int16   *p_Data_Int_precision_1;
    Int16   *p_Data_Int_precision_2;

    Int     n     = 256;
    Int     n_2   = n >> 1;
    Int     n_4   = n >> 2;
    Int     n_8   = n >> 3;
    Int     n_3_4 = n_2 + n_4;


    p_data_1 = (Int16 *)Data_out;
    p_data_1 += n;
    pData_re  = p_data_1;
    pData_im  = p_data_1 + n_4;


    p_rotate  =  exp_rotation_N_256;
    pTable    =  digit_reverse_64;

    pData_in_ref  =  Data_in;

    exp = 16 - pv_normalize(max);


    if (exp < 0)
    {
        exp = 0;
    }

    exp -= 1;

    for (i = INV_SHORT_CX_ROT_LENGTH; i != 0; i--)
    {

        /*
         * cos_n + j*sin_n == exp(j(2pi/N)(k+1/8))
         */

        /*
         *   Perform digit reversal by accessing index I from table
         */

        I = *pTable++;
        pData_in_1 = pData_in_ref + I;
        /*
         *  Use auxiliary variables to avoid double accesses to memory.
         *  Data in is scaled to use only lower 16 bits.
         */

        temp_im =  *(pData_in_1++);
        temp_re =  *(pData_in_1);

        exp_jw = *p_rotate++;

        /*
         *   Post-rotation
         */

        *(pData_re++)  = (Int16)(cmplx_mul32_by_16(temp_re, -temp_im, exp_jw) >> exp);
        *(pData_im++)  = (Int16)(cmplx_mul32_by_16(temp_im,  temp_re, exp_jw) >> exp);
    }


    p_data_2 = pData_im -  1;


    p_Data_Int_precision = (Int16 *)Data_out;
    p_Data_Int_precision_1 = p_Data_Int_precision + n_3_4 - 1;
    p_Data_Int_precision_2 = p_Data_Int_precision + n_3_4;

    for (i = n_8 >> 1; i != 0; i--)
    {
        temp_re_0 = (*(p_data_1++));
        temp_re_1 = (*(p_data_1++));
        temp_im_0 = (*(p_data_2--));
        temp_im_1 = (*(p_data_2--));

        *(p_Data_Int_precision_1--) =  temp_re_0;
        *(p_Data_Int_precision_1--) =  temp_im_0;
        *(p_Data_Int_precision_1--) =  temp_re_1;
        *(p_Data_Int_precision_1--) =  temp_im_1;

        *(p_Data_Int_precision_2++) =  temp_re_0;
        *(p_Data_Int_precision_2++) =  temp_im_0;
        *(p_Data_Int_precision_2++) =  temp_re_1;
        *(p_Data_Int_precision_2++) =  temp_im_1;

    }


    /*
     *  loop is split to avoid conditional testing inside loop
     */

    p_Data_Int_precision_2 = p_Data_Int_precision;

    for (i = n_8 >> 1; i != 0; i--)
    {

        temp_re_0 = (*(p_data_1++));
        temp_re_1 = (*(p_data_1++));
        temp_im_0 = (*(p_data_2--));
        temp_im_1 = (*(p_data_2--));

        *(p_Data_Int_precision_1--) =   temp_re_0;
        *(p_Data_Int_precision_1--) =   temp_im_0;
        *(p_Data_Int_precision_1--) =   temp_re_1;
        *(p_Data_Int_precision_1--) =   temp_im_1;

        *(p_Data_Int_precision_2++) = (Int16)(-temp_re_0);
        *(p_Data_Int_precision_2++) = (Int16)(-temp_im_0);
        *(p_Data_Int_precision_2++) = (Int16)(-temp_re_1);
        *(p_Data_Int_precision_2++) = (Int16)(-temp_im_1);

    }

    return (exp + 1);
}
