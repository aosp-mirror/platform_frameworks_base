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



 Pathname: ./audio/gsm-amr/c/src/dec_lag3.c
 Functions: Dec_lag3

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
    t0_min  -- Word16 -- minimum of search range
    t0_max  -- Word16 -- maximum of search range
    i_subfr -- Word16 -- subframe flag
    T0_prev -- Word16 -- integer pitch delay of last subframe
                         used in 2nd and 4th subframes
    flag4   -- Word16 -- flag for encoding with 4 bits

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


              )
------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 PURPOSE:  Decoding of fractional pitch lag with 1/3 resolution.
           Extract the integer and fraction parts of the pitch lag from
           the received adaptive codebook index.

  See "Enc_lag3.c" for more details about the encoding procedure.

  The fractional lag in 1st and 3rd subframes is encoded with 8 bits
  while that in 2nd and 4th subframes is relatively encoded with 4, 5
  and 6 bits depending on the mode.

------------------------------------------------------------------------------
 REQUIREMENTS



------------------------------------------------------------------------------
 REFERENCES

 dec_lag3.c, UMTS GSM AMR speech codec, R99 - Version 3.2.0, March 2, 2001

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
#include "dec_lag3.h"
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

void Dec_lag3(Word16 index,     /* i : received pitch index                 */
              Word16 t0_min,    /* i : minimum of search range              */
              Word16 t0_max,    /* i : maximum of search range              */
              Word16 i_subfr,   /* i : subframe flag                        */
              Word16 T0_prev,   /* i : integer pitch delay of last subframe
                                       used in 2nd and 4th subframes        */
              Word16 * T0,      /* o : integer part of pitch lag            */
              Word16 * T0_frac, /* o : fractional part of pitch lag         */
              Word16 flag4,     /* i : flag for encoding with 4 bits        */
              Flag  *pOverflow  /* o : Flag set when overflow occurs        */
             )
{
    Word16 i;
    Word16 tmp_lag;

    if (i_subfr == 0)    /* if 1st or 3rd subframe */
    {

        if (index < 197)
        {

            tmp_lag = index + 2;

            tmp_lag =
                mult(
                    tmp_lag,
                    10923,
                    pOverflow);

            i =
                add(
                    tmp_lag,
                    19,
                    pOverflow);

            *T0 = i;

            /* i = 3 * (*T0) */

            i = add(i, i, pOverflow);
            i = add(i, *T0, pOverflow);

            tmp_lag =
                sub(
                    index,
                    i,
                    pOverflow);

            *T0_frac =
                add(
                    tmp_lag,
                    58,
                    pOverflow);
        }
        else
        {
            *T0 = index - 112;

            *T0_frac = 0;
        }

    }
    else
    {  /* 2nd or 4th subframe */

        if (flag4 == 0)
        {

            /* 'normal' decoding: either with 5 or 6 bit resolution */

            i =
                add(
                    index,
                    2,
                    pOverflow);

            i =
                mult(
                    i,
                    10923,
                    pOverflow);

            i =
                sub(
                    i,
                    1,
                    pOverflow);

            *T0 =
                add(
                    i,
                    t0_min,
                    pOverflow);

            /* i = 3* (*T0) */
            i = add(add(i, i, pOverflow), i, pOverflow);

            tmp_lag =
                sub(
                    index,
                    2,
                    pOverflow);

            *T0_frac =
                sub(
                    tmp_lag,
                    i,
                    pOverflow);
        }
        else
        {

            /* decoding with 4 bit resolution */

            tmp_lag = T0_prev;

            i =
                sub(
                    tmp_lag,
                    t0_min,
                    pOverflow);

            if (i > 5)
            {
                tmp_lag =
                    add(
                        t0_min,
                        5,
                        pOverflow);
            }

            i =
                sub(
                    t0_max,
                    tmp_lag,
                    pOverflow);

            if (i > 4)
            {
                tmp_lag =
                    sub(
                        t0_max,
                        4,
                        pOverflow);
            }

            if (index < 4)
            {
                i =
                    sub(
                        tmp_lag,
                        5,
                        pOverflow);

                *T0 =
                    add(
                        i,
                        index,
                        pOverflow);

                *T0_frac = 0;
            }
            else
            {
                /* 4 >= index < 12 */
                if (index < 12)
                {
                    i = index - 5;

                    i = mult(
                            i,
                            10923,
                            pOverflow);

                    i--;

                    *T0 = add(
                              i,
                              tmp_lag,
                              pOverflow);

                    i = add(
                            add(
                                i,
                                i,
                                pOverflow),
                            i,
                            pOverflow);

                    tmp_lag = index - 9;

                    *T0_frac =
                        sub(
                            tmp_lag,
                            i,
                            pOverflow);
                }
                else
                {
                    i = index - 12;

                    i =
                        add(
                            i,
                            tmp_lag,
                            pOverflow);

                    *T0 =
                        add(
                            i,
                            1,
                            pOverflow);

                    *T0_frac = 0;
                }
            }

        } /* end if (decoding with 4 bit resolution) */
    }

    return;
}
