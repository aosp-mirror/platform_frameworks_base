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
/****************************************************************************************
Portions of this file are derived from the following 3GPP standard:

    3GPP TS 26.073
    ANSI-C code for the Adaptive Multi-Rate (AMR) speech codec
    Available from http://www.3gpp.org

(C) 2004, 3GPP Organizational Partners (ARIB, ATIS, CCSA, ETSI, TTA, TTC)
Permission to distribute, modify and use this file under the standard license
terms listed above has been obtained from the copyright holder.
****************************************************************************************/
/*
------------------------------------------------------------------------------



 Pathname: ./audio/gsm-amr/c/src/dec_lag6.c
 Functions: Dec_lag6

     Date: 01/31/2002

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:
 (1) Updated to accept new parameter, Flag *pOverflow.
 (2) Placed file in the proper PV Software template.

 Description:
 (1) Removed "count.h" and "basic_op.h" and replaced with individual include
     files (add.h, sub.h, etc.)

 Description:
 (1) Removed optimization -- mult(i, 3, pOverflow) is NOT the same as adding
     i to itself 3 times.  The reason is because the mult function does a
     right shift by 15, which will obliterate smaller numbers.

 Description:  Replaced "int" and/or "char" with OSCL defined types.

 Description:

 ------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    index   -- Word16 -- received pitch index
    pit_min  -- Word16 -- minimum pitch lag
    pit_max  -- Word16 -- maximum pitch lag
    i_subfr -- Word16 -- subframe flag
    T0 -- Pointer to type Word16 -- integer part of pitch lag

 Outputs:

    T0 -- Pointer to type Word16 -- integer part of pitch lag
    T0_frac -- Pointer to type Word16 -- fractional part of pitch lag
    pOverflow -- Pointer to type Flag -- Flag set when overflow occurs

 Returns:
    None.

 Global Variables Used:
    None

 Local Variables Needed:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 PURPOSE:  Decoding of fractional pitch lag with 1/6 resolution.
           Extract the integer and fraction parts of the pitch lag from
           the received adaptive codebook index.

  See "Enc_lag6.c" for more details about the encoding procedure.

  The fractional lag in 1st and 3rd subframes is encoded with 9 bits
  while that in 2nd and 4th subframes is relatively encoded with 6 bits.
  Note that in relative encoding only 61 values are used. If the
  decoder receives 61, 62, or 63 as the relative pitch index, it means
  that a transmission error occurred. In this case, the pitch lag from
  previous subframe (actually from previous frame) is used.

------------------------------------------------------------------------------
 REQUIREMENTS



------------------------------------------------------------------------------
 REFERENCES

 dec_lag6.c, UMTS GSM AMR speech codec, R99 - Version 3.2.0, March 2, 2001

------------------------------------------------------------------------------
 PSEUDO-CODE



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
#include "dec_lag6.h"
#include "typedef.h"
#include "basic_op.h"

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

void Dec_lag6(
    Word16 index,      /* input : received pitch index           */
    Word16 pit_min,    /* input : minimum pitch lag              */
    Word16 pit_max,    /* input : maximum pitch lag              */
    Word16 i_subfr,    /* input : subframe flag                  */
    Word16 *T0,        /* in/out: integer part of pitch lag      */
    Word16 *T0_frac,   /* output: fractional part of pitch lag   */
    Flag   *pOverflow  /* o : Flag set when overflow occurs      */
)
{
    Word16 i;
    Word16 T0_min;
    Word16 T0_max;
    Word16 k;
    Word16 w;

    if (i_subfr == 0)          /* if 1st or 3rd subframe */
    {
        if (index < 463)
        {
            /* T0 = (index+5)/6 + 17 */
            i = index + 5;
            i =
                mult(
                    i,
                    5462,
                    pOverflow);

            i =
                add(
                    i,
                    17,
                    pOverflow);

            *T0 = i;

            /* i = 3* (*T0) */

            i = add(i, i, pOverflow);
            i = add(i, *T0, pOverflow);

            /* *T0_frac = index - T0*6 + 105 */

            i =
                add(
                    i,
                    i,
                    pOverflow);

            i =
                sub(
                    index,
                    i,
                    pOverflow);

            *T0_frac =
                add(
                    i,
                    105,
                    pOverflow);
        }
        else
        {
            *T0 =
                sub(
                    index,
                    368,
                    pOverflow);

            *T0_frac = 0;
        }
    }
    else       /* second or fourth subframe */
    {
        /* find T0_min and T0_max for 2nd (or 4th) subframe */

        T0_min =
            sub(
                *T0,
                5,
                pOverflow);

        if (T0_min < pit_min)
        {
            T0_min = pit_min;
        }

        T0_max =
            add(
                T0_min,
                9,
                pOverflow);

        if (T0_max > pit_max)
        {
            T0_max = pit_max;

            T0_min =
                sub(
                    T0_max,
                    9,
                    pOverflow);
        }

        /* i = (index+5)/6 - 1 */
        i =
            add(
                index,
                5,
                pOverflow);

        i =
            mult(
                i,
                5462,
                pOverflow);

        i =
            sub(
                i,
                1,
                pOverflow);

        *T0 =
            add(
                i,
                T0_min,
                pOverflow);

        /* i = 3* (*T0) */

        w = add(i, i, pOverflow);
        i = add(i, w, pOverflow);

        i =
            add(
                i,
                i,
                pOverflow);

        k =
            sub(
                index,
                3,
                pOverflow);

        *T0_frac =
            sub(
                k,
                i,
                pOverflow);
    }
}
