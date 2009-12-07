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

 Pathname: ms_synt.c

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:  Modified from original shareware code

 Description:  Cleaned up a bit, not finished.

 Description:
 Copied in code from pns_intensity_right.c, which has the same structure as
 this file.  Also, merged in code from ms_map_mask.c

 Description:
 (1) Various optimizations (eliminated extra variables by making use of a
 single temporary register throughout the code, etc.)
 (2) Wrote pseudocode, pasted in correct function template, etc.

 Description:  Unrolled loops to get speed up code

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    wins_in_group        = Number of windows in the current group.
                           [const Int]

    coef_per_win         = Number of coefficients per window.
                           [const Int]

    num_bands            = Number of scalefactor bands.
                           [const Int]

    band_length          = Number of coefficients per scalefactor band.
                           [const Int]

    pFirst_Window_CoefsL = Array containing the spectral coefficients for
                           the left channel.
                           [Int32 *, length LN]
    pFirst_Window_CoefsR = Array containing the spectral coefficients for
                           the right channel.
                           [Int32 *, length LN]
    q_formatLeft         = Array containing the q-format used to encode each
                           scalefactor band's data on the left channel.
                           [Int *, length MAXBANDS]
    q_formatRight        = Array containing the q-format used to encode each
                           scalefactor band's data on the right channel.
                           [Int *, length MAXBANDS]

 Local Stores/Buffers/Pointers Needed:
    None

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    None

 Pointers and Buffers Modified:
   pFirst_Window_CoefsL  The coefficients in the group will be modified per the
                         formula for M/S stereo on each scalefactor band where
                         M/S stereo is active.

   pFirst_Window_CoefsR  The coefficients in the group will be modified per the
                         formula for M/S stereo on each scalefactor band where
                         M/S stereo is active.

   q_formatLeft          The q_format may be modified on scalefactor bands
                         where M/S stereo is active.

   q_formatRight         The q_format may be modified on scalefactor bands
                         where M/S stereo is active.

 Local Stores Modified:

 Global Stores Modified:

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This module applies the formula for M/S coding to one grouped scalefactor
 band.  The ISO code has a similar function which applies M/S coding to an
 entire frame.

 It is the calling function's responsibility to check the map_mask array, which
 is filled by the function getmask.  If a scalefactor band is identified as
 using M/S stereo, the coefficients in that array are calculated using
 the following formula...

 TempLeft = LeftCoefficient;

 LeftCoefficient  = LeftCoefficient  + RightCoefficient;
 RightCoefficient = TempLeft         - RightCoefficient;

 This function should be inlined if the compiler supports C99 inlining,
 as this short function is only called by sfb_tools_ms().
 Therefore, inlining will not increase the code size.

------------------------------------------------------------------------------
 REQUIREMENTS


------------------------------------------------------------------------------
 REFERENCES

 (1) ISO/IEC 14496-3:1999(E)
     Part 3
        Subpart 4.6.7.1   M/S stereo
        Subpart 4.6.2     ScaleFactors

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

    start_indx = 0;

    pCoefR = coefLeft;
    pCoefL = coefRight;

    FOR (win_indx = wins_in_group; win_indx > 0; win_indx--)


        tempInt = q_formatLeft[start_indx] - q_formatRight[start_indx];

        IF (tempInt > 0)
        THEN

            shift_left_chan  = 1 + tempInt;
            shift_right_chan = 1;

            q_formatLeft[start_indx]  = (q_formatRight[start_indx] - 1);
            q_formatRight[start_indx] = (q_formatRight[start_indx] - 1);

        ELSE
            shift_left_chan  = 1;
            shift_right_chan = 1 - tempInt;

            q_formatRight[start_indx] = (q_formatLeft[start_indx] - 1);
            q_formatLeft[start_indx]  = (q_formatLeft[start_indx] - 1);

        ENDIF

        FOR (tempInt = band_length; tempInt > 0; tempInt--)

            temp_left  = *(pCoefL) >> shift_left_chan;
            temp_right = *(pCoefR) >> shift_right_chan;

            *(pCoefL++) = temp_left + temp_right;
            *(pCoefR++) = temp_left - temp_right;

        ENDFOR

        tempInt = (coef_per_win - band_length);

        pCoefR = pCoefR + tempInt;
        pCoefL = pCoefL + tempInt;

        start_indx = start_indx + num_bands;

    ENDFOR

------------------------------------------------------------------------------
 RESOURCES USED
   When the code is written for a specific target processor the
   resources used should be documented below.

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
#include "ms_synt.h"
#include "fxp_mul32.h"
#include "aac_mem_funcs.h"
#include "window_block_fxp.h"

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

void ms_synt(
    const Int   wins_in_group,
    const Int   coef_per_win,
    const Int   num_bands,
    const Int   band_length,
    Int32 coefLeft[],
    Int32 coefRight[],
    Int q_formatLeft[],
    Int q_formatRight[])
{

    Int32 *pCoefL = coefLeft;
    Int32 *pCoefR = coefRight;
    Int start_indx = 0;


    if (band_length < 0 || band_length > LONG_WINDOW)
    {
        return;     /*  avoid any processing on error condition */
    }


    Int nextWinPtrUpdate = (coef_per_win - band_length);

    for (Int win_indx = wins_in_group; win_indx > 0; win_indx--)
    {

        if (q_formatRight[start_indx] < 31)
        {
            Int tempInt = q_formatLeft[start_indx] - q_formatRight[start_indx];

            /* Normalize the left and right channel to the same q-format */
            if (tempInt > 0)
            {
                /*
                 * shift_left_chan and shift_right_chan each have an offset
                 * of 1.  Even if the left and right channel share the same
                 * q-format, we must shift each by 1 to guard against
                 * overflow.
                 */
                Int shift_left_chan  = 1 + tempInt;

                /*
                 * Following code line is equivalent to...
                 * q_formatLeft  = q_formatRight - 1;
                 * q_formatRight = q_formatRight - 1;
                 */
                q_formatLeft[start_indx] = --(q_formatRight[start_indx]);


                /*
                 *  band_length is always an even number (check tables in pg.66 IS0 14496-3)
                 */

                Int32 temp_left  = *(pCoefL) >> shift_left_chan;
                Int32 temp_right = *(pCoefR) >> 1;



                for (Int i = band_length; i != 0; i--)
                {
                    *(pCoefL++) = temp_left + temp_right;
                    *(pCoefR++) = temp_left - temp_right;
                    temp_left  = *(pCoefL) >> shift_left_chan;
                    temp_right = *(pCoefR) >> 1;

                }

            }
            else
            {
                /*
                 * shift_left_chan and shift_right_chan each have an offset
                 * of 1.  Even if the left and right channel share the same
                 * q-format, we must shift each by 1 to guard against
                 * overflow.
                 */
                Int shift_right_chan = 1 - tempInt;

                /*
                 * Following code line is equivalent to...
                 * q_formatRight = q_formatLeft - 1;
                 * q_formatLeft  = q_formatLeft - 1;
                 */
                q_formatRight[start_indx] = --(q_formatLeft[start_indx]);

                /*
                 *  band_length is always an even number (check tables in pg.66 IS0 14496-3)
                 */

                Int32 temp_left  = *(pCoefL) >> 1;
                Int32 temp_right = *(pCoefR) >> shift_right_chan;

                for (Int i = band_length; i != 0; i--)
                {
                    *(pCoefL++) = temp_left + temp_right;
                    *(pCoefR++) = temp_left - temp_right;

                    temp_left  = *(pCoefL) >> 1;
                    temp_right = *(pCoefR) >> shift_right_chan;

                }
            }

        }
        else
        {
            /*
             *  Nothing on right channel, just copy left into right
             */
            q_formatRight[start_indx] = (q_formatLeft[start_indx]);

            pv_memcpy(pCoefR, pCoefL, band_length*sizeof(*pCoefR));
            pCoefR += band_length;
            pCoefL += band_length;
        }

        /*
         * Increment the window pointers so they point
         * to the next window in the group
         */
        pCoefL += nextWinPtrUpdate;
        pCoefR += nextWinPtrUpdate;

        start_indx += num_bands;

    } /* for (win_indx) */

    return;

} /* MS_synt */
