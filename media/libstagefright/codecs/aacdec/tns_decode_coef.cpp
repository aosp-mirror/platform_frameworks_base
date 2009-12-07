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

 Pathname: tns_decode_coef.c

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:  Modified from original shareware code

 Description:  Implemented in 16-bit Fixed Point

 Description:  Implemented in 24-bit Fixed Point

 Description:  Modified to return the calculated LPC coefficients "in place"
 This saves memory, cycles, etc. because it saves a large temporary
 array being declared on the stack in another function (tns_setup_filter)

 Description:  Modified to return the q-format of the lpc coefficients.

 Description:  Modified for more reliable overflow protection.  tns_decode_coef
 no longer relies on "reasonable" outputs.  This code should handle all
 possible inputs.

 Description:  Modified per review comments.

 Description:  Added check condition to avoid numbers with a Q bigger than
        15 from being passed, otherwise in a 16-bit number the sign is lost.

 Description:  Modified to utilize scratch memory techniques, thereby
 eliminating two arrays of size TNS_MAX_ORDER, which were previously declared
 on the stack.

 Description: Updated the SW template to include the full pathname to the
 source file and a slightly modified copyright header.

 Description:
 (1) Changed the order of the unsigned * signed multiply so the
     casting to Int32 is performed on the unsigned operand.

 (2) Removed some unnecessary casting.
 (3) Fixed a problem where a 16-bit value was casted to 32-bits AFTER
     a shift.  It should have been cast to 32-bits BEFORE the shifting.


 Description:  modified precision of coefficients

 Who:                                   Date:
 Description:
------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 The inputs and their range are defined in ISO/IEC 14496-3:1999(E)
                                            Part 3 MPEG-4 Audio
                                            Subpart 4

 Inputs:       order    = RANGE = 1-20
               const Int

               coef_res = RANGE = 0-1
               const Int

               lpc_coef = RANGE = -8 to 7 if coef_res = 1   compression OFF
                                  -4 to 3 if coef_res = 1   compression ON
                                  -4 to 3 if coef_res = 0   compression OFF
                                  -2 to 1 if coef_res = 0   compression ON

               [Int *, length TNS_MAX_ORDER]

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    q_lpc     = q_format for the calculated LPC coefs.
    Int

 Pointers and Buffers Modified:
    lpc_coef  = used to return the calculated LPC coefs in-place.
    Int *

 Global Stores Modified:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

This function calculates the LPC coefs from the encoded coefs...

------------------------------------------------------------------------------
 REQUIREMENTS

This function should match the functionality of the ISO source code within
a reasonable tolerance for fixed point errors.

------------------------------------------------------------------------------
 REFERENCES

 (1) ISO/IEC 14496-3:1999(E)
     Part 3
        Subpart 4.6.8 (Temporal Noise Shaping)
 (2) Markel & Gray Page 95
     As referenced in the ISO source code

 (3) MPEG-2 NBC Audio Decoder
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
*/

/*----------------------------------------------------------------------------
 PSEUDOCODE:  (ISO Reference Code)

    int i, m;
    Real iqfac, iqfac_m;
    Real lpc_fp[TNS_MAX_ORDER+1];
    Real sin_result_fp[TNS_MAX_ORDER+1], b[TNS_MAX_ORDER+1];

    Inverse quantization
    iqfac   = (Real)(((1 << (coef_res-1)) - 0.5) / (PI/2.0));
    iqfac_m = (Real)(((1 << (coef_res-1)) + 0.5) / (PI/2.0));

    for (i=0; i<order; i++)
    {
        sin_result[i+1] =
        (Real)sin( coef[i] / ((coef[i] >= 0) ? iqfac : iqfac_m) );
    }

    lpc[0] = 1;
    for (m=1; m<=order; m++)
    {

        b[0] = lpc[0];
        for (i=1; i<m; i++)
        {
            b[i] = sin_result[m] * lpc[m-i];
            b[i] += lpc[i];
        }

        b[m] = sin_result[m];


        for (i=0; i<=m; i++)
        {
            lpc[i] = b[i];
        }

    }

    return;

}
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include "pv_audio_type_defs.h"
#include "e_tns_const.h"
#include "tns_decode_coef.h"
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
#define MASK_LOW16  0xffff
#define UPPER16     16

/*----------------------------------------------------------------------------
; LOCAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL VARIABLE DEFINITIONS
; Variable declaration - defined here and used outside this module
----------------------------------------------------------------------------*/
/*
 * Derivation of tns_tables and q_tns_tables
 *
 * As defined in the ISO source code
 * (with the modification that our coef_res has a range[0,1]
 * The ISO code has a range of [3,4])
 *
 *               pi / 2                              pi / 2
 * iqfac =  --------------------      iqfac_m =  --------------------
 *          (coef_res + 2) - 1/                  (coef_res + 2) + 1/
 *         2                 /2                 2                 /2
 *
 *
 * ... Move 1/2 into denominator
 *
 *              pi                                   pi
 * iqfac =  --------------------      iqfac_m =  --------------------
 *          (coef_res + 3)                        (coef_res + 3)
 *         2               - 1                   2              + 1
 *
 *
 * if a coef is negative, it is multiplied by iqfac_m
 *           if positive, "   "     "         iqfac
 *
 * The range of coefs is limited to  -4:3 if coef_res = 0
 *                                   -8:7 if coef_res = 1
 *
 *
 *
 */


const Int32 tns_table[2][16] =
{
    {
        -2114858546,  -1859775393,  -1380375881,  -734482665,
        0,    931758235,   1678970324,  2093641749
    },
    {
        -2138322861,  -2065504841,  -1922348530,  -1713728946,
        -1446750378,  -1130504462,   -775760571,   -394599085,
        0,    446486956,    873460290,   1262259218,
        1595891361,   1859775393,   2042378317,   2135719508
    }
};


const Int neg_offset[2] = {4, 8};
/*----------------------------------------------------------------------------
; EXTERNAL FUNCTION REFERENCES
; Declare functions defined elsewhere and referenced in this module
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; EXTERNAL VARIABLES REFERENCES
; Declare variables used in this module but defined elsewhere
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
 FUNCTION NAME: tns_decode_coef
    Decoder transmitted coefficients for one TNS filter
----------------------------------------------------------------------------*/

Int tns_decode_coef(
    const Int   order,
    const Int   coef_res,
    Int32 lpc_coef[TNS_MAX_ORDER],
    Int32 scratchTnsDecCoefMem[2*TNS_MAX_ORDER])
{

    /* Simple loop counters */
    Int i;
    Int m;

    /* Arrays for calculation of the LPC */
    Int32 *pB = &(scratchTnsDecCoefMem[TNS_MAX_ORDER]);

    Int32 *pA = scratchTnsDecCoefMem;

    Int32 *temp_ptr = NULL;

    /* Pointer for reading/storing the lpc_coef in place */
    Int32 *pLPC;
    Int q_lpc = Q_LPC;

    /* TNS table related variables */
    const Int32 *pTnsTable;
    Int coef_offset;
    Int32 table_index;
    Int shift_amount;
    Int32 sin_result;

    Int32 tempInt32;

    Int32 max;
    Int32 mask;

    Int32 mult_high;

    /* Conversion to LPC coefficients Ref. (2) */
    coef_offset = neg_offset[coef_res];
    pTnsTable   = tns_table[coef_res];

    m = 0;
    pLPC = lpc_coef;


    /*
     *  Conversion to LPC coefficients
     */

    do
    {
        table_index = coef_offset + *(pLPC++);

        /* Equiv. to sin_result  = tns_table[coef_res][table_index]; */
        sin_result = *(pTnsTable + table_index);

        /* sin_result has a range of -0.999 to +0.999 in Q-31 */

        /*
         * It is important that this for loop is not entered on the first
         * iteration of the do-while( m < order ) loop.
         */
        for (i = m; i > 0; i--)
        {

            /*
             * temp_ptr used to optimize index into pA
             * mult = (Int32)( pA[m-i] * sin_result);
             */

            mult_high = fxp_mul32_Q31(*(temp_ptr--), sin_result);

            /*
             *  pB[i] = pA[i] + sin_result * pA[m-i]
             *
             *  (mult_high <<1)  eliminates extra sign bit
             */

            *(pB++) =  *(pA++) + (mult_high << 1);

        } /* END for (i=m; i > 0; i--) */


        /* Shift to place pB[m] in q_lpc format */

        *pB =  sin_result >> 12;

        /*
         * Swapping the pointers here has the same effect
         * as specifically copying the data from b to a
         */

        temp_ptr = pA;
        pA       = pB;
        pB       = temp_ptr;

        /*
         *  At this point, pA = pA[m]
         *             and pB = pB[m]
         */
        temp_ptr = pA;

        tempInt32 = *(pA);

        mask = tempInt32 >> 31;
        tempInt32 ^= mask;

        max = tempInt32;

        /*
         * It is important that this for loop is not entered on the first
         * iteration of the do-while( m < order ) loop.
         */
        for (i = m; i > 0; i--)
        {
            tempInt32 = *(--pA);

            mask = tempInt32 >> 31;
            tempInt32 ^= mask;

            max |= tempInt32;
        }

        pB -= m;

        /*
         * Here, pA = &(pA[0])
         * and   pB = &(pB[0])
         */

        if (max >= 0x40000000L)
        {
            max >>= 1;

            for (i = m; i > 0; i--)
            {
                *(pA++) >>= 1;
                *(pB++) >>= 1;
            }

            /* Shift the most recent entry down also */
            *(pA) >>= 1;

            q_lpc--;

            pA -= m;
            pB -= m;
        }

        m++;

    }
    while (m < order);


    /*
     * The following code compacts
     * 32-bit LPC coefficients into 16-bit numbers,
     * shifting by the minimum amount necessary.
     */

    shift_amount = 0;

    while (max > 32767)
    {
        max >>= 1;
        shift_amount++;
    }

    /*
     * This while loop is for protective purposes only.
     * I have not found data that causes it to be entered.
     *
     */
    if (max != 0)
    {
        while (max < 16384)
        {
            max <<= 1;
            shift_amount--;
        }
    }


    pLPC = lpc_coef;

    if (shift_amount >= 0)
    {

        for (m = order; m > 0; m--)
        {
            *(pLPC++) = *(pA++) << (16 - shift_amount);
        }
    }


    q_lpc -= shift_amount;

    /*
     *  make sure that the numbers have some meaning, q_lpc can not be
     *  bigger than 15 (15 bits + sign)
     */

    if (q_lpc > 15)
    {
        shift_amount = q_lpc - 15;
        pLPC = lpc_coef;

        for (m = order; m > 0; m--)
        {
            *(pLPC++) >>= shift_amount;
        }

        q_lpc -= shift_amount;
    }

    return (q_lpc);

} /* tns_decode_coef */
