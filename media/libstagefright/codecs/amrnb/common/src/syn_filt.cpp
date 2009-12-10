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
 Pathname: ./audio/gsm-amr/c/src/syn_filt.c

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: Making changes based on comments from the review meeting.

 Description: Added typedef to Input/Output Definition section.

 Description: Synchronized file with UMTS version 3.2.0. Updated coding
              template.

 Description: Fixed typecasting issue with the TI C compiler.

 Description: Modified FOR loops to count down.

 Description: Modified FOR loop to count up again so that the correct values
              are stored in the tmp buffer. Updated copyright year.

 Description:
        - Modified for loop and introduced pointers to avoid adding
          offsets
        - Eliminated check for saturation given that the max values of input
          data and coefficients will not saturate the multiply and
          accumulation
        - eliminated memcpy to update history buffer in every pass. This is
          done now just updating the pointers.

 Description:
              1. Eliminated unused include files.
              2. Unrolled loops to process twice as many samples as before,
                 this saves on memory accesses to the vector coeff. a[] and
                 elements in the history buffer of this recursive filter

 Description:
              1. Added overflow check inside both loops. (this is needed just
                 to satisfy bit exactness on the decoder, a faster
                 implementation will add an extra shift, do the same,
                 but will not be bit exact, and it may have better audio
                 quality because will avoid clipping)
              2. Added include file for constant definitions

 Description:  Replaced OSCL mem type functions and eliminated include
               files that now are chosen by OSCL definitions

 Description:  Replaced "int" and/or "char" with OSCL defined types.

 Description: Changed round function name to pv_round to avoid conflict with
              round function in C standard library.

 Description: Using fxp_arithmetic.h that includes inline assembly functions
              for ARM and linux-arm.

 Description: Replacing fxp_arithmetic.h with basic_op.h.

 Who:                           Date:
 Description:

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#include <string.h>

#include    "syn_filt.h"
#include    "cnst.h"
#include    "basic_op.h"

#include    "basic_op.h"

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
 FUNCTION NAME: Syn_filt
------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    a = buffer containing the prediction coefficients (Word16)  max 2^12
    x = input signal buffer (Word16)                            max 2^15
    y = output signal buffer (Word16)
    lg = size of filtering (Word16)
    mem = memory buffer associated with this filtering (Word16)
    update = flag to indicate memory update; 0=no update, 1=update memory
             (Word16)

 Outputs:
    mem buffer is changed to be the last M data points of the output signal
      if update was set to 1
    y buffer contains the newly calculated filter output

 Returns:
    None

 Global Variables Used:
    None

 Local Variables Needed:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 Perform synthesis filtering through 1/A(z)

------------------------------------------------------------------------------
 REQUIREMENTS

 None

------------------------------------------------------------------------------
 REFERENCES

 syn_filt.c, UMTS GSM AMR speech codec, R99 - Version 3.2.0, March 2, 2001

------------------------------------------------------------------------------
 PSEUDO-CODE

void Syn_filt (
    Word16 a[],     // (i)     : a[M+1] prediction coefficients   (M=10)
    Word16 x[],     // (i)     : input signal
    Word16 y[],     // (o)     : output signal
    Word16 lg,      // (i)     : size of filtering
    Word16 mem[],   // (i/o)   : memory associated with this filtering.
    Word16 update   // (i)     : 0=no update, 1=update of memory.
)
{
    Word16 i, j;
    Word32 s;
    Word16 tmp[80];   // This is usually done by memory allocation (lg+M)
    Word16 *yy;

    // Copy mem[] to yy[]

    yy = tmp;

    for (i = 0; i < M; i++)
    {
        *yy++ = mem[i];
    }

    // Do the filtering.

    for (i = 0; i < lg; i++)
    {
        s = L_mult (x[i], a[0]);
        for (j = 1; j <= M; j++)
        {
            s = L_msu (s, a[j], yy[-j]);
        }
        s = L_shl (s, 3);
        *yy++ = pv_round (s);
    }

    for (i = 0; i < lg; i++)
    {
        y[i] = tmp[i + M];
    }

    // Update of memory if update==1

    if (update != 0)
    {
        for (i = 0; i < M; i++)
        {
            mem[i] = y[lg - M + i];
        }
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

void Syn_filt(
    Word16 a[],     /* (i)   : a[M+1] prediction coefficients   (M=10)  */
    Word16 x[],     /* (i)   : input signal                             */
    Word16 y[],     /* (o)   : output signal                            */
    Word16 lg,      /* (i)   : size of filtering   (40)                 */
    Word16 mem[],   /* (i/o) : memory associated with this filtering.   */
    Word16 update   /* (i)   : 0=no update, 1=update of memory.         */
)
{
    Word16 i, j;
    Word32 s1;
    Word32 s2;
    Word16 tmp[2*M]; /* This is usually done by memory allocation (lg+M) */
    Word16 *yy;

    Word16 *p_a;
    Word16 *p_yy1;
    Word16 *p_y;
    Word16 *p_x;
    Word16 temp;
    /* Copy mem[] to yy[] */

    yy = tmp;

    memcpy(yy, mem, M*sizeof(Word16));

    yy = yy + M;

    /* Do the filtering. */

    p_y  = y;
    p_x  = x;
    p_yy1 = &yy[-1];

    for (i = M >> 1; i != 0; i--)
    {
        p_a  = a;

        s1 = amrnb_fxp_mac_16_by_16bb((Word32) * (p_x++), (Word32) * (p_a), 0x00000800L);
        s2 = amrnb_fxp_mac_16_by_16bb((Word32) * (p_x++), (Word32) * (p_a++), 0x00000800L);
        s1 = amrnb_fxp_msu_16_by_16bb((Word32) * (p_a++), (Word32) * (p_yy1), s1);

        for (j = (M >> 1) - 2; j != 0; j--)
        {
            s2 = amrnb_fxp_msu_16_by_16bb((Word32) * (p_a), (Word32) * (p_yy1--), s2);
            s1 = amrnb_fxp_msu_16_by_16bb((Word32) * (p_a++), (Word32) * (p_yy1), s1);
            s2 = amrnb_fxp_msu_16_by_16bb((Word32) * (p_a), (Word32) * (p_yy1--), s2);
            s1 = amrnb_fxp_msu_16_by_16bb((Word32) * (p_a++), (Word32) * (p_yy1), s1);
            s2 = amrnb_fxp_msu_16_by_16bb((Word32) * (p_a), (Word32) * (p_yy1--), s2);
            s1 = amrnb_fxp_msu_16_by_16bb((Word32) * (p_a++), (Word32) * (p_yy1), s1);
        }

        /* check for overflow on s1 */
        if ((UWord32)(s1 - 0xf8000000L) < 0x0fffffffL)
        {
            temp = (Word16)(s1 >> 12);
        }
        else if (s1 > 0x07ffffffL)
        {
            temp = MAX_16;
        }
        else
        {
            temp = MIN_16;
        }

        s2 = amrnb_fxp_msu_16_by_16bb((Word32)a[1], (Word32)temp, s2);

        *(yy++)  = temp;
        *(p_y++) = temp;

        p_yy1 = yy;

        /* check for overflow on s2 */
        if ((UWord32)(s2 - 0xf8000000L) < 0x0fffffffL)
        {
            temp = (Word16)(s2 >> 12);
        }
        else if (s2 > 0x07ffffffL)
        {
            temp = MAX_16;
        }
        else
        {
            temp = MIN_16;
        }

        *(yy++)  = temp;
        *(p_y++) = temp;
    }

    p_yy1 = &y[M-1];

    for (i = (lg - M) >> 1; i != 0; i--)
    {
        p_a  = a;

        s1 = amrnb_fxp_mac_16_by_16bb((Word32) * (p_x++), (Word32) * (p_a), 0x00000800L);
        s2 = amrnb_fxp_mac_16_by_16bb((Word32) * (p_x++), (Word32) * (p_a++), 0x00000800L);
        s1 = amrnb_fxp_msu_16_by_16bb((Word32) * (p_a++), (Word32) * (p_yy1), s1);

        for (j = (M >> 1) - 2; j != 0; j--)
        {
            s2 = amrnb_fxp_msu_16_by_16bb((Word32) * (p_a), (Word32) * (p_yy1--), s2);
            s1 = amrnb_fxp_msu_16_by_16bb((Word32) * (p_a++), (Word32) * (p_yy1), s1);
            s2 = amrnb_fxp_msu_16_by_16bb((Word32) * (p_a), (Word32) * (p_yy1--), s2);
            s1 = amrnb_fxp_msu_16_by_16bb((Word32) * (p_a++), (Word32) * (p_yy1), s1);
            s2 = amrnb_fxp_msu_16_by_16bb((Word32) * (p_a), (Word32) * (p_yy1--), s2);
            s1 = amrnb_fxp_msu_16_by_16bb((Word32) * (p_a++), (Word32) * (p_yy1), s1);
        }

        if ((UWord32)(s1 - 0xf8000000L) < 0x0fffffffL)
        {
            temp = (Word16)(s1 >> 12);
        }
        else if (s1 > 0x07ffffffL)
        {
            temp = MAX_16;
        }
        else
        {
            temp = MIN_16;
        }

        s2 = amrnb_fxp_msu_16_by_16bb((Word32)a[1], (Word32)temp, s2);

        *(p_y++) = temp;
        p_yy1 = p_y;

        if ((UWord32)(s2 - 0xf8000000L) < 0x0fffffffL)
        {
            *(p_y++) = (Word16)(s2 >> 12);
        }
        else if (s2 > 0x07ffffffL)
        {
            *(p_y++) = MAX_16;
        }
        else
        {
            *(p_y++) = MIN_16;
        }
    }

    /* Update of memory if update==1 */
    if (update != 0)
    {
        memcpy(mem, &y[lg-M], M*sizeof(Word16));
    }

    return;
}
