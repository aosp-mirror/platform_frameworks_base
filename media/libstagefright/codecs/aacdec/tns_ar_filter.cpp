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

 Pathname: tns_ar_filter.c

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:  Modified from original shareware code

 Description:  Implemented 24-bit fixed point version
               Optimized C code

 Description:
            - Added OVERFLOW_SHIFT_DOWN to avoid overflow.
            - Increased precision by using the Q format of the LPC coefficient.
            - Modified interface to add LPC Q format and scratch memory
              for the state variables.
            - Added pv_memset to clear state filter
            - Updated format for comments (to PV standard)
            - Updated copyright notice

 Description:
            - Changed multiplication scheme to increase precision. This
              works better than older version.

 Description:
            - Include log2(order) as a scaling down parameter.

 Description:
            Modified to reflect code review comments
                - misspelled words, extra comments and explicit requirements

 Description:
            deleted comment about fix Q format (L 107)

 Description:  Implemented a more efficient version, which eliminated the use
 of "scratch memory" via introducing a pointer that references the actual
 output.

 Description: Removed the parameter "scratch_Int32_buffer" as this space
 in memory is no longer needed by this function.

 Description: Removed references to "scratch_Int32_buffer" in the Inputs
 section.

 Description:
    Modified casting to ensure proper operations for different platforms

 Description:
    Per code review comment:
    Eliminated casting to UInt and Int in b_low and b_high, they are
    redundant and may add unncessary extra cycles in some platforms

 Description: Updated the SW template to include the full pathname to the
 source file and a slightly modified copyright header.

 Description: Changed the order of the unsigned * signed multiply so the
 casting to Int32 is performed on the unsigned operand.

 Description:
    Modified 32 by 16 bit multiplications to avoid unnecessary moves to
    registers. Also split the code (based on flag direction) to simplify
    pointer's updates

 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    spec  = spectral input to be shaped by the filter.
            Fixed point format
            Int32[]
            length = spec_length

    spec_length  = length of spec array.
            const Int

    direction = direction for application of tns filter.
                +1  filters spectrum from low to high frequencies
                    (first input to filter is spec[0])
                -1  filters spectrum from high to low frequencies
                    (first input to filter is spec[spec_length-1])
                const Int

    lpc   = array of lpc coefficients, minus lpc[0] which is assumed to be "1"
            Fixed point format
            const Int[]
            length = TNS_MAX_ORDER

    Q_lpc = Q format for the lpc coeffcients (for max. precision, it assumes
            that all 16 bits are used)
            const Int

    order = order of the TNS filter (Range of 1 - TNS_MAX_ORDER)
            Int

 Local Stores/Buffers/Pointers Needed:
    None

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    None

 Pointers and Buffers Modified:
    spec = contains spectral data after application of TNS filter
           Int32 array
           length = spec_length


 Local Stores Modified:

 Global Stores Modified:


------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    A block of spectral data (Int32 spec[]) of length (const Int spec_length)
    is processed by a simple all-pole filter defined by
    LPC coefficients passed via (const Int lpc[])

    TNS filter equation
        y(n) =  x(n) - lpc(2)*y(n-1) - ... - lpc(order+1)*y(n-order)

    The filter calculation is performed in place, i.e. the output is passed
    back to the calling function via (Int32 spec[])

    The filter's order is defined by the variable (const Int order)
    The direction of the filter's application is defined by (const Int inc)

------------------------------------------------------------------------------
 REQUIREMENTS

    This function should match the functionality of the ISO code.
    The implementation does support filter orders bigger or equal to 1.
    The size of the spectral coeffcients has to be bigger or equal than 1.

------------------------------------------------------------------------------
 REFERENCES

 (1) ISO/IEC 14496-3:1999(E)
     Part 3
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


    FOR (i=0; i<order; i++)
        state[i] = 0;
    ENDFOR

    IF (inc == -1)
    THEN
        spec = spec + spec_length - 1;
    ENDIF

    FOR (i=0; i<spec_length; i++)

        y = *spec;

        FOR (j=0; j<order; j++)

            y -= lpc[j] * state[j];

        ENDFOR

        FOR (j=order-1; j>0; j--)

            state[j] = state[j-1];

        ENDFOR

        state[0] = y;

        *spec = y;

        spec = spec + inc;

    ENDFOR


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
#include "e_tns_const.h"
#include "tns_ar_filter.h"
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
#define MASK_LOW16               0xFFFF
#define UPPER16                      16

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

Int tns_ar_filter(
    Int32 spec[],
    const Int spec_length,
    const Int direction,
    const Int32 lpc[],
    const Int Q_lpc,
    const Int order)
{

    Int i;
    Int j;

    /*
     * Multiplication related variables
     */

    Int32 temp;

    /*
     *  Filter related variables
     */
    Int32 y0;

    /*
     *  Circular buffer to hold the filter's state
     *  (y[n-1],y[n-2],y[n-3],etc.)
     *
     *  p_state and p_lpc should take advantage
     *  of any special circular buffer instructions
     *  if this code is hand-optimized in assembly.
     */

    Int32 *p_state = NULL;

    const Int32 *p_lpc;


    Int shift_up;
    Int shift_down_amount;

    /*
     *  Pointer to the I/O memory space
     */
    Int32 *p_spec = spec;


    i = 0;
    j = order;

    /*
     *  get the power of 2 that is bigger than the order
     *  i is the bit counter and j is modified until exceed
     *  the power of 2 corresponding to TNS_MAX_ORDER
     */

    while (j < 0x010)
    {
        j <<= 1;
        i++;
    }

    /*
     *  5 is the number of bits needed to represent 0x010
     *  TNS_MAX_ORDER = 20, power of 2 that include 20 is 5
     */
    shift_down_amount = 4 - i;

    shift_up = UPPER16 - Q_lpc;

    /*
     *  shift_down_amount == power of 2 that is bigger than the order - 1
     */

    shift_down_amount += shift_up;

    if (direction == -1)
    {
        p_spec += spec_length - 1;

        for (i = order; i != 0; i--)
        {

            y0 = *p_spec >> shift_down_amount;

            p_lpc = lpc;

            /* 32 by 32 bit multiplication */
            for (j = order; j > i; j--)
            {
                temp = *p_state++;
                y0 -= fxp_mul32_Q31(temp, *(p_lpc++)) << shift_up;
            }

            /*
            * Record the output in-place
            */
            p_state     = p_spec;
            *(p_spec--) = y0;

        }

        if (spec_length > order)
        {
            for (i = (spec_length - order); i != 0; i--)
            {
                y0 = *p_spec >> shift_down_amount;

                p_lpc = &(lpc[0]);

                /* 32 by 32 bit multiplication */
                for (j = order; j != 0; j--)
                {
                    temp = *p_state++;
                    y0 -= fxp_mul32_Q31(temp, *(p_lpc++)) << shift_up;
                }

                /*
                 * Record the output in-place
                 */
                p_state     = p_spec;
                *(p_spec--) = y0;

            } /* END for (i = (spec_length - order); i>0; i--) */
        }

    }
    else
    {
        for (i = order; i != 0; i--)
        {

            p_lpc =  lpc;

            y0 = 0;

            /* 32 by 32 bit multiplication */
            for (j = order; j > i; j--)
            {
                y0 -= fxp_mul32_Q31(*p_state--, *(p_lpc++));
            }

            p_state     = p_spec;
            /*
            * Record the output in-place
            */
            *(p_spec) = (*p_spec >> shift_down_amount) + (y0 << shift_up);
            p_spec++;
        }

        if (spec_length > order)
        {
            for (i = (spec_length - order); i != 0; i--)
            {
                p_lpc =  lpc;

                y0 = 0;

                /* 32 by 32 bit multiplication */
                for (j = order; j != 0; j--)
                {
                    y0 -= fxp_mul32_Q31(*p_state--, *(p_lpc++));
                }

                p_state     = p_spec;
                /*
                 * Record the output in-place
                 */
                *(p_spec) = (*p_spec >> shift_down_amount) + (y0 << shift_up);
                p_spec++;

            } /* END for (i = (spec_length - order); i>0; i--) */
        }
    }

    return(shift_down_amount);


} /* tns_ar_filter */
