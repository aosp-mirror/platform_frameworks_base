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

 Pathname: tns_inv_filter.c

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: Changes made per review comments.

 Description: As requested by JT, the q-format for the LPC coefficients is
 now passed via the parameter lpc_qformat.

 Description: For speed, the calculation of the shift amount was pulled
 outside of the loop.

 Description:
    Modified casting to ensure proper operations for different platforms

 Description:
    Simplified MAC operations for filter by eliminating extra variables

 Who:                       Date:
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:

    coef           = spectral input to be shaped by the filter.
                     Fixed point format
                     [Int32[], length = num_coef]

    num_coef       = length of spec array.
                     [const Int]

    direction      = direction for application of tns filter.
                     +1 applies forward filter
                     (first input to filter is coef[0])
                     -1 applies reversed filter
                     (first input to filter is coef[num_coef-1])
                     [const Int]

    lpc            = array of lpc coefficients.
                     Fixed point format Q-11
                     [const Int[], length = TNS_MAX_ORDER]

    lpc_qformat    = The q-format of the lpc coefficients.
                     [const Int]

    order          = order of the TNS filter (Range of 1 : TNS_MAX_ORDER)
                     [const Int]

    scratch_memory = scratch_memory needed for filter operation
                     [Int[], length = TNS_MAX_ORDER]

 Local Stores/Buffers/Pointers Needed:
    None

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    None

 Pointers and Buffers Modified:
    coef = contains spectral data after application of TNS filter
           q-format is not modified.
           Int32 array
           length = num_coef

 Local Stores Modified:
    None

 Global Stores Modified:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    A block of spectral data (Int32 coef[]) of length (const Int num_coef)
    is processed by a simple all-zero filter defined by
    LPC coefficients passed via (const Int lpc[])

    TNS filter equation
        y(n) =  x(n) + lpc(2)*x(n-1) + ... + lpc(order+1)*x(n-order)

    The filter calculation is performed in place, i.e. the output is passed
    back to the calling function via (Int32 coef[])

    In order to avoid overflow, the filter input (Int32 coef[]) must utilize
    only the lower 16-bits.  The upper 16-bits must be available.

    The filter's order is defined by the variable (const Int order)

    The direction of the filter's application is defined by
    (const Int direction)

------------------------------------------------------------------------------
 REQUIREMENTS

    [Int32 coef] must store no more than 16 bits of data.

    This is required to utilize methods that do not change the q-format of
    the input data [Int32 coef], and to make use of a fast
    16 x 16 bit multiply.

    This function should not be called for order <= 0.

    This function must not be called with lpc_qformat < 5
------------------------------------------------------------------------------
 REFERENCES

 (1) ISO/IEC 14496-3:1999(E)
     Part 3
        Subpart 4.6.6.4.1 (LTP with TNS)
        Subpart 4.6.8 (Temporal Noise Shaping)

 (2) MPEG-2 NBC Audio Decoder
   "This software module was originally developed by AT&T, Dolby
   Laboratories, Fraunhofer Gesellschaft IIS in the course of development
   of the MPEG-2 NBC/MPEG-4 Audio standard ISO/IEC 13818-7, 14496-1,2 and
   3. This software module is an implementation of a part of one or more
   MPEG-2 NBC/MPEG-4 Audio tools as specified by the MPEG-2 NBC/MPEG-4
   Audio standard. ISO/IEC  gives users of the MPEG-2 NBC/MPEG-4 Audio
   standards free license to this software module or modifications thereof
   for use in hardware or software products claiming conformance to the
   MPEG-2 NBC/MPEG-4 Audio  standards. Those intending to use this software
   module in hardware or software products are advised that this use may
   infringe existing patents. The original developer of this software
   module and his/her company, the subsequent editors and their companies,
   and ISO/IEC have no liability for use of this software module or
   modifications thereof in an implementation. Copyright is not released
   for non MPEG-2 NBC/MPEG-4 Audio conforming products.The original
   developer retains full right to use the code for his/her  own purpose,
   assign or donate the code to a third party and to inhibit third party
   from using the code for non MPEG-2 NBC/MPEG-4 Audio conforming products.
   This copyright notice must be included in all copies or derivative
   works."
   Copyright(c)1996.

------------------------------------------------------------------------------
 PSEUDO-CODE

    IF (direction == -1)
    THEN
        pCoef = pCoef + (num_coef - 1);
    END IF

    FOR (i = order; i > 0; i--)

        *(pFilterInput) = 0;
        pFilterInput = pFilterInput + 1;

    END FOR

    wrap_point = 0;

    shift_amt  = (lpc_qformat - 5);

    FOR (i = num_coef; i > 0; i--)

        pLPC = lpc;

        mult = 0;

        FOR (j = wrap_point; j>0; j--)

           tempInt32 = (Int32)(*(pLPC) * *(pFilterInput));
           tempInt32 = tempInt32 >> 5;

           mult = mult + tempInt32;

           pFilterInput = pFilterInput + 1;
           pLPC = pLPC + 1;

        ENDFOR

        pFilterInput = scratch_memory;

        FOR (j = (order - wrap_point); j>0; j--)

           tempInt32 = (Int32)(*(pLPC) * *(pFilterInput));
           tempInt32 = tempInt32 >> 5;

           mult = mult + tempInt32;

           pFilterInput = pFilterInput + 1;
           pLPC = pLPC + 1;

        ENDFOR

        pFilterInput = pFilterInput - 1;
        *(pFilterInput) = (Int)(*pCoef);

        mult = mult >> shift_amt;

        *(pCoef) = *(pCoef) + mult;

        pCoef = pCoef + direction;

        wrap_point = wrap_point + 1;

        IF (wrap_point == order)
        THEN
            wrap_point = 0;
        END IF

    END FOR

------------------------------------------------------------------------------
 RESOURCES USED

   When the code is written for a specific target processor
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
#include "pv_audio_type_defs.h"
#include "tns_inv_filter.h"
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

void tns_inv_filter(
    Int32 coef[],
    const Int num_coef,
    const Int direction,
    const Int32 lpc[],
    const Int lpc_qformat,
    const Int order,
    Int32 scratch_memory[])
{

    Int i;
    Int j;
    Int shift_amt;
    Int wrap_point;

    Int32 mult;

    /*
     * Circular buffer to hold the filter's input
     *
     * (x[n-1],x[n-2],x[n-3],etc.)
     *
     * This scratch space is necessary, because
     * the filter's output is returned in-place.
     *
     * pFilterInput and pLPC should take advantage
     * of any special circular buffer instructions
     * if this code is hand-optimized in assembly.
     *
     */
    Int32 *pFilterInput = scratch_memory;

    const Int32 *pLPC;

    /*
     * Pointer to the I/O memory space
     */
    Int32 *pCoef = coef;

    if (direction == -1)
    {
        pCoef += (num_coef - 1);
    }

    /* Make sure the scratch memory is "clean" */
    for (i = order; i != 0; i--)
    {
        *(pFilterInput++) = 0;
    }

    wrap_point = 0;

    shift_amt  = (lpc_qformat - 5);

    for (i = num_coef; i > 0; i--)
    {
        /*
         * Copy spectral input into special
         * filter input buffer.
         */
        pLPC = lpc;

        mult = 0;

        /*
         * wrap_point = 0 when this code is
         * entered for the first iteration of
         * for(i=num_coef; i>0; i--)
         *
         * So, this first for-loop will be
         * skipped when i == num_coef.
         */

        for (j = wrap_point; j > 0; j--)
        {
            mult += fxp_mul32_Q31(*(pLPC++), *(pFilterInput++)) >> 5;

        } /* for (j = wrap_point; j>0; j--) */

        /*
         * pFilterInput has reached &scratch_memory[order-1]
         * Reset pointer to beginning of filter's state memory
         */
        pFilterInput = scratch_memory;

        for (j = (order - wrap_point); j > 0; j--)
        {
            mult += fxp_mul32_Q31(*(pLPC++), *(pFilterInput++)) >> 5;

        } /* for (j = wrap_point; j>0; j--) */


        /*
         * Fill the filter's state buffer
         * avoid obvious casting
         */
        *(--pFilterInput) = (*pCoef);


        /* Scale the data down so the output q-format is not adjusted.
         *
         * Here is an equation, which shows how the spectral coefficients
         * and lpc coefficients are multiplied and the spectral
         * coefficient's q-format does not change.
         *
         * Q-(coef) * Q-(lpc_qformat) >> 5 = Q-(coef + lpc_q_format - 5)
         *
         * Q-(coef + lpc_q_format - 5) >> (lpc_qformat - 5) = Q-(coef)
         */

        /* Store output in place */
        *(pCoef) += (mult >> shift_amt);

        /* Adjust pointers and placeholders */
        pCoef += direction;

        wrap_point++;

        if (wrap_point == order)
        {
            wrap_point = 0;
        }

    } /* for (i = num_coef; i > 0; i--) */

} /* tns_inv_filter */
