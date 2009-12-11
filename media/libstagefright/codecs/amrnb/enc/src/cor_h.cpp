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



 Pathname: ./audio/gsm-amr/c/src/cor_h.c

     Date: 06/12/2000

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: Updated template used to PV coding template. First attempt at
          optimizing C code.

 Description: Used MAX_16 and MIN_16 when checking the result of Inv_sqrt.
          Synced up to the new template.

 Description: Added setting of Overflow flag in inlined code.

 Description: Took out cor_h_x function and put it in its own file. Sync'ed
          up with the single_func_template.c template. Delete version
          ID variable.

 Description: Synchronized file with UTMS version 3.2.0. Updated coding
              template. Removed unnecessary include files.

 Description: Fixed portion of the code that builds the rr[] matrix. There
              was an error in the original inlining of code that caused
              the code to be not bit-exact with UMTS version 3.2.0.

 Description: Added calls to L_add() and mult() in the code to handle overflow
              scenario. Moved cor_h.h after cnst.h in the Include section.
              Doing this allows the unit test to build using the cnst.h in the
              /test/include directory. Fixed initialization of the accumulator
              in the first calculation of the sum of squares.

 Description: Made the following changes per comments from Phase 2/3 review:
              1. Used #define value instead of hard-coded numbers in the code.
              2. Fixed typecasting issue with TI C compiler.
              3. Removed typecasting of 0x00008000L in the call to L_add.

 Description: Changed pOverflow from a global variable into a function
 parameter.

 Description:
            1. Added pointer to avoid adding offsets in every pass
            2. Eliminate variables defined as registers
            3. Removed extra check for overflow by doing scaling right
               after overflow is detected.
            4. Eliminated calls to basic operations (like extract) not
               needed because of the nature of the number (all bounded)
            5. Eliminated duplicate loop accessing same data
            6. Simplified matrix addressing by use of pointers

 Description:
              1. Eliminated unused include files.
              2. Access twice the number of points when delaing with matrices
                 and in the process only 3 pointers (instead of 4) are needed
              3. Replaced array addressing (array sign[]) by pointers

 Description: Changed round function name to pv_round to avoid conflict with
              round function in C standard library.

 Description: Using inlines from fxp_arithmetic.h .

 Description: Replacing fxp_arithmetic.h with basic_op.h.

 Description:

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include "cnst.h"
#include "cor_h.h"
#include "basicop_malloc.h"
#include "inv_sqrt.h"
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

/*
------------------------------------------------------------------------------
 FUNCTION NAME: cor_h
------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    h = vector containing the impulse response of the weighted synthesis
        filter; vector contents are of type Word16; vector length is
        2 * L_SUBFR
    sign = vector containing the sign information for the correlation
           values; vector contents are of type Word16; vector length is
           L_CODE
    rr = autocorrelation matrix; matrix contents are of type Word16;
         matrix dimension is L_CODE by L_CODE

 Outputs:
    rr contents are the newly calculated autocorrelation values

 Returns:
    None

 Global Variables Used:
    None

 Local Variables Needed:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function computes correlations of the impulse response (h) needed for
 the codebook search, and includes the sign information into the correlations.

 The correlations are given by:
    rr[i][j] = sum_{n=i}^{L-1} h[n-i] h[n-j];   i>=j; i,j=0,...,L-1

 The sign information is included by:
    rr[i][j] = rr[i][j]*sign[i]*sign[j]

------------------------------------------------------------------------------
 REQUIREMENTS

 None

------------------------------------------------------------------------------
 REFERENCES

 cor_h.c, UMTS GSM AMR speech codec, R99 - Version 3.2.0, March 2, 2001

------------------------------------------------------------------------------
 PSEUDO-CODE

void cor_h (
    Word16 h[],         // (i) : impulse response of weighted synthesis
                                 filter
    Word16 sign[],      // (i) : sign of d[n]
    Word16 rr[][L_CODE] // (o) : matrix of autocorrelation
)
{
    Word16 i, j, k, dec, h2[L_CODE];
    Word32 s;

    // Scaling for maximum precision

    s = 2;
    for (i = 0; i < L_CODE; i++)
        s = L_mac (s, h[i], h[i]);

    j = sub (extract_h (s), 32767);
    if (j == 0)
    {
        for (i = 0; i < L_CODE; i++)
        {
            h2[i] = shr (h[i], 1);
        }
    }
    else
    {
        s = L_shr (s, 1);
        k = extract_h (L_shl (Inv_sqrt (s), 7));
        k = mult (k, 32440);                     // k = 0.99*k

        for (i = 0; i < L_CODE; i++)
        {
            h2[i] = pv_round (L_shl (L_mult (h[i], k), 9));
        }
    }

    // build matrix rr[]
    s = 0;
    i = L_CODE - 1;
    for (k = 0; k < L_CODE; k++, i--)
    {
        s = L_mac (s, h2[k], h2[k]);
        rr[i][i] = pv_round (s);
    }

    for (dec = 1; dec < L_CODE; dec++)
    {
        s = 0;
        j = L_CODE - 1;
        i = sub (j, dec);
        for (k = 0; k < (L_CODE - dec); k++, i--, j--)
        {
            s = L_mac (s, h2[k], h2[k + dec]);
            rr[j][i] = mult (pv_round (s), mult (sign[i], sign[j]));
            rr[i][j] = rr[j][i];
        }
    }
}

---------------------------------------------------------------------------
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

void cor_h(
    Word16 h[],          /* (i) : impulse response of weighted synthesis
                                  filter                                  */
    Word16 sign[],       /* (i) : sign of d[n]                            */
    Word16 rr[][L_CODE], /* (o) : matrix of autocorrelation               */
    Flag  *pOverflow
)
{
    register Word16 i;
    register Word16 dec;

    Word16 h2[L_CODE];
    Word32 s;
    Word32 s2;
    Word16 tmp1;
    Word16 tmp2;
    Word16 tmp11;
    Word16 tmp22;

    Word16 *p_h;
    Word16 *p_h2;
    Word16 *rr1;
    Word16 *rr2;
    Word16 *rr3;
    Word16 *p_rr_ref1;
    Word16 *p_sign1;
    Word16 *p_sign2;

    /* Scaling for maximum precision */

    /* Initialize accumulator to 1 since left shift happens    */
    /* after the accumulation of the sum of squares (original  */
    /* code initialized s to 2)                                */
    s = 1;
    p_h = h;

    for (i = (L_CODE >> 1); i != 0 ; i--)
    {
        tmp1 = *(p_h++);
        s = amrnb_fxp_mac_16_by_16bb((Word32) tmp1, (Word32) tmp1, s);
        tmp1 = *(p_h++);
        s = amrnb_fxp_mac_16_by_16bb((Word32) tmp1, (Word32) tmp1, s);

    }

    s <<= 1;

    if (s & MIN_32)
    {
        p_h2 = h2;
        p_h  = h;

        for (i = (L_CODE >> 1); i != 0; i--)
        {
            *(p_h2++) =  *(p_h++)  >> 1;
            *(p_h2++) =  *(p_h++)  >> 1;
        }
    }
    else
    {

        s >>= 1;

        s = Inv_sqrt(s, pOverflow);

        if (s < (Word32) 0x00ffffffL)
        {
            /* k = 0.99*k */
            dec = (Word16)(((s >> 9) * 32440) >> 15);
        }
        else
        {
            dec = 32440;  /* 0.99 */
        }

        p_h  = h;
        p_h2 = h2;

        for (i = (L_CODE >> 1); i != 0; i--)
        {
            *(p_h2++) = (Word16)((amrnb_fxp_mac_16_by_16bb((Word32) * (p_h++), (Word32) dec, 0x020L)) >> 6);
            *(p_h2++) = (Word16)((amrnb_fxp_mac_16_by_16bb((Word32) * (p_h++), (Word32) dec, 0x020L)) >> 6);
        }
    }
    /* build matrix rr[] */

    s = 0;

    p_h2 = h2;

    rr1 = &rr[L_CODE-1][L_CODE-1];

    for (i = L_CODE >> 1; i != 0 ; i--)
    {
        tmp1   = *(p_h2++);
        s = amrnb_fxp_mac_16_by_16bb((Word32) tmp1, (Word32) tmp1, s);
        *rr1 = (Word16)((s + 0x00004000L) >> 15);
        rr1 -= (L_CODE + 1);
        tmp1   = *(p_h2++);
        s = amrnb_fxp_mac_16_by_16bb((Word32) tmp1, (Word32) tmp1, s);
        *rr1 = (Word16)((s + 0x00004000L) >> 15);
        rr1 -= (L_CODE + 1);
    }


    p_rr_ref1 = rr[L_CODE-1];

    for (dec = 1; dec < L_CODE; dec += 2)
    {
        rr1 = &p_rr_ref1[L_CODE-1-dec];

        rr2 = &rr[L_CODE-1-dec][L_CODE-1];
        rr3 = &rr[L_CODE-1-(dec+1)][L_CODE-1];

        s  = 0;
        s2 = 0;

        p_sign1 = &sign[L_CODE - 1];
        p_sign2 = &sign[L_CODE - 1 - dec];

        p_h2 = h2;
        p_h  = &h2[dec];

        for (i = (L_CODE - dec - 1); i != 0 ; i--)
        {
            s = amrnb_fxp_mac_16_by_16bb((Word32) * (p_h2), (Word32) * (p_h++), s);
            s2 = amrnb_fxp_mac_16_by_16bb((Word32) * (p_h2++), (Word32) * (p_h), s2);

            tmp1  = (Word16)((s + 0x00004000L) >> 15);
            tmp11 = (Word16)((s2 + 0x00004000L) >> 15);

            tmp2  = ((Word32) * (p_sign1) * *(p_sign2--)) >> 15;
            tmp22 = ((Word32) * (p_sign1--) * *(p_sign2)) >> 15;

            *rr2 = ((Word32) tmp1 * tmp2) >> 15;
            *(rr1--) = *rr2;
            *rr1 = ((Word32) tmp11 * tmp22) >> 15;
            *rr3 = *rr1;

            rr1 -= (L_CODE);
            rr2 -= (L_CODE + 1);
            rr3 -= (L_CODE + 1);

        }

        s = amrnb_fxp_mac_16_by_16bb((Word32) * (p_h2), (Word32) * (p_h), s);

        tmp1 = (Word16)((s + 0x00004000L) >> 15);

        tmp2 = ((Word32) * (p_sign1) * *(p_sign2)) >> 15;
        *rr1 = ((Word32) tmp1 * tmp2) >> 15;

        *rr2 = *rr1;

        rr1 -= (L_CODE + 1);
        rr2 -= (L_CODE + 1);

    }

    return;

}

