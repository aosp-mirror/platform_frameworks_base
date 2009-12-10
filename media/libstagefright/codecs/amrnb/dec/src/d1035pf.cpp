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



 Pathname: ./audio/gsm-amr/c/src/d1035pf.c

     Date: 04/14/2000

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: Updated template used to PV coding template. First attempt at
          optimizing C code.

 Description: Updated file per comments gathered from Phase 2/3 review.

 Description: Synchronized file with UTMS version 3.2.0. Updated coding
              template. Removed unnecessary include files.

 Description: Removed inclusion of "gray.tab".

 Description:

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include "d1035pf.h"
#include "typedef.h"
#include "basic_op.h"
#include "cnst.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/
#define NB_PULSE  10            /* number of pulses  */

/*----------------------------------------------------------------------------
; LOCAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL STORE/BUFFER/POINTER DEFINITIONS
; Variable declaration - defined here and used outside this module
----------------------------------------------------------------------------*/

/*
------------------------------------------------------------------------------
 FUNCTION NAME: dec_10i40_35bits
------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    index = buffer containing index of 10 pulses; each element is
        represented by sign+position
    cod = buffer of algebraic (fixed) codebook excitation

 Outputs:
    cod buffer contains the new algebraic codebook excitation

 Returns:
    None

 Global Variables Used:
    dgray = gray decoding table

 Local Variables Needed:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function builds the innovative codevector from the received index of
 algebraic codebook. See c1035pf.c for more details about the algebraic
 codebook structure.

------------------------------------------------------------------------------
 REQUIREMENTS

 None

------------------------------------------------------------------------------
 REFERENCES

 d1035pf.c, UMTS GSM AMR speech codec, R99 - Version 3.2.0, March 2, 2001

------------------------------------------------------------------------------
 PSEUDO-CODE

void dec_10i40_35bits (
    Word16 index[],    // (i)     : index of 10 pulses (sign+position)
    Word16 cod[]       // (o)     : algebraic (fixed) codebook excitation
)
{
    Word16 i, j, pos1, pos2, sign, tmp;

    for (i = 0; i < L_CODE; i++)
    {
        cod[i] = 0;
    }

    // decode the positions and signs of pulses and build the codeword

    for (j = 0; j < NB_TRACK; j++)
    {
        // compute index i

        tmp = index[j];
        i = tmp & 7;
        i = dgray[i];

        i = extract_l (L_shr (L_mult (i, 5), 1));
        pos1 = add (i, j); // position of pulse "j"

        i = shr (tmp, 3) & 1;
        if (i == 0)
        {
            sign = 4096; // +1.0
        }
        else
        {
            sign = -4096; // -1.0
        }

        cod[pos1] = sign;

        // compute index i

        i = index[add (j, 5)] & 7;
        i = dgray[i];
        i = extract_l (L_shr (L_mult (i, 5), 1));

        pos2 = add (i, j);      // position of pulse "j+5"

        if (sub (pos2, pos1) < 0)
        {
            sign = negate (sign);
        }
        cod[pos2] = add (cod[pos2], sign);
    }

    return;
}

------------------------------------------------------------------------------
 RESOURCES USED [optional]

 When the code is written for a specific target processor the
 the resources used should be documented below.

 HEAP MEMORY USED: x bytes

 STACK MEMORY USED: x bytes

 CLOCK CYCLES: (cycle count equation for this function) + (variable
                used to represent cycle count for each subroutine
                called)
     where: (cycle count variable) = cycle count for [subroutine
                                     name]

------------------------------------------------------------------------------
 CAUTION [optional]
 [State any special notes, constraints or cautions for users of this function]

------------------------------------------------------------------------------
*/

void dec_10i40_35bits(
    Word16 index[],    /* (i)     : index of 10 pulses (sign+position)       */
    Word16 cod[]       /* (o)     : algebraic (fixed) codebook excitation    */
)
{
    register Word16 i, j, pos1, pos2;
    Word16 sign, tmp;

    for (i = 0; i < L_CODE; i++)
    {
        *(cod + i) = 0;
    }

    /* decode the positions and signs of pulses and build the codeword */

    for (j = 0; j < NB_TRACK; j++)
    {
        /* compute index i */

        tmp = *(index + j);
        i = tmp & 7;
        i = *(dgray + i);

        i = (Word16)(i * 5);
        pos1 = i + j; /* position of pulse "j" */

        i = (tmp >> 3) & 1;

        if (i == 0)
        {
            sign = 4096;                                 /* +1.0 */
        }
        else
        {
            sign = -4096;                                /* -1.0 */
        }

        *(cod + pos1) = sign;

        /* compute index i */

        i = *(index + j + 5) & 7;
        i = *(dgray + i);
        i = (Word16)(i * 5);

        pos2 = i + j;      /* position of pulse "j+5" */


        if (pos2 < pos1)
        {
            sign = negate(sign);
        }
        *(cod + pos2) += sign;
    }

    return;
}
