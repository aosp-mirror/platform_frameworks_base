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

 Pathname: pns_corr.c

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: Made changes per review comments, the most major of which
 being the change of the scaling into a 16 x 16 multiply.

 Description: When the multiplication of two 16-bits variables is stored in
              an 32-bits variable, the result should be typecasted explicitly
              to Int32 before it is stored.
              *(pCoefRight++) = (Int32) tempInt2 * multiplier;

 Who:                       Date:
 Description:
------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:

    scale        =  Multiplier used to scale the noise extracted from the left
                    channel for use on the right.
                    [const Int]

    coef_per_win =  Number of coefficients per window.
                    (128 for short, 1024 for long)
                    [const Int]

    sfb_per_win  =  Number of scalefactors per window.
                    [const Int]

    wins_in_group = The number of windows in the group being decoded.
                    [const Int]

    band_length  =  The length of the scalefactor band being decoded.
                    [const Int]

    sfb_prediction_used =  Flag that denotes the activation of long term
                           prediction on a per-scalefactor band,
                           non-grouped basis.
                           [const Int *, length MAX_SFB]

    q_formatLeft = The Q-format for the left channel's fixed-point spectral
                   coefficients, on a per-scalefactor band, non-grouped basis.
                   [const Int]

    q_formatRight = The Q-format for the right channel's fixed-point spectral
                    coefficients, on a per-scalefactor band, non-grouped basis.
                    [Int *, length MAX_SFB]

    coefLeft = Array containing the fixed-point spectral coefficients
               for the left channel.
               [const Int32 *, length 1024]

    coefRight = Array containing the fixed-point spectral coefficients
                for the right channel.
                [Int32 *, length 1024]

 Local Stores/Buffers/Pointers Needed:

 Global Stores/Buffers/Pointers Needed:

 Outputs:

 Pointers and Buffers Modified:
    pcoefRight  Contains the new spectral information

    q_formatRight       Q-format may be updated with changed to fixed-point
                        data in coefRight.

    sfb_prediction_used              LTP may be disabled by presence of PNS tool on the
                        same scalefactor band.

 Local Stores Modified:

 Global Stores Modified:

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function derives noise from the left channel.  The PNS tool is assumed
 to have been used on the same scalefactor band on the left channel.  The
 noise on the left/right channels are not necessarily of the same amplitude,
 and therefore have separate scalefactors.  The noise is thus scaled by the
 difference between the two transmitted scalefactors.  This scaling is done
 in fixed-point using a constant 4-element table.

------------------------------------------------------------------------------
 REQUIREMENTS


------------------------------------------------------------------------------
 REFERENCES

 (1) ISO/IEC 14496-3:1999(E)
     Part 3
        Subpart 4.6.7.1   M/S stereo
        Subpart 4.6.12.3  Decoding Process (PNS)
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
   developer retains full right to use the code for his/her own purpose,
   assign or donate the code to a third party and to inhibit third party
   from using the code for non MPEG-2 NBC/MPEG-4 Audio conforming products.
   This copyright notice must be included in all copies or derivative
   works."
   Copyright(c)1996.

------------------------------------------------------------------------------
 PSEUDO-CODE

    q_format = q_formatLeft - (scale >> 2);
    q_format = q_format - 1;
    q_formatRight = q_format;

    multiplier = hcb2_scale_mod_4[scale & 0x3];

    pCoefLeft = coefLeft;
    pCoefRight = coefRight;

    start_indx = 0;

    FOR (win_indx = wins_in_group; win_indx > 0; win_indx--)

        q_formatRight[start_indx] = q_format;

        sfb_prediction_used[start_indx] = FALSE;

        start_indx = start_indx + sfb_per_win;

        FOR (tempInt = band_length; tempInt > 0; tempInt--)

            *(pCoefRight) = (*(pCoefLeft) >> 9) * multiplier;
            pCoefRight = pCoefRight + 1;
            pCoefLeft = pCoefLeft + 1;

        ENDFOR

        tempInt = (coef_per_win - band_length);
        pCoefRight = pCoefRight + tempInt;
        pCoefLeft = pCoefLeft + tempInt;

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
#include "pns_corr.h"

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

const UInt hcb2_scale_mod_4[4] =
{
    32768,  /* (2.0^0.00)*2^15 */
    38968,  /* (2.0^0.25)*2^15 */
    46341,  /* (2.0^0.50)*2^15 */
    55109
}; /* (2.0^0.75)*2^15 */

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

void pns_corr(
    const Int scale,
    const Int coef_per_win,
    const Int sfb_per_win,
    const Int wins_in_group,
    const Int band_length,
    const Int q_formatLeft,
    Int q_formatRight[],
    const Int32 coefLeft[],
    Int32 coefRight[])
{
    Int tempInt;
    Int nextWinPtrUpdate;

    Int q_format;

    Int start_indx;
    Int win_indx;

    const Int32   *pCoefLeft;
    Int32   *pCoefRight;

    UInt multiplier;

    /*
     * Generate noise correlated with the noise on the left channel
     *
     */

    /*
     * scale is interpreted as 2^(scale/4)
     * Therefore, we can adjust the q-format by floor(scale/4)
     * and save some complexity in the multiplier.
     */
    q_format = q_formatLeft - (scale >> 2);

    /*
     * Reduce the q-format by 1 to guard against overflow.
     * This must be done because the hcb2_scale_mod_4 table
     * must be stored in a common q-format, and we must shift
     * by 16 to get *pCoefLeft into a 16-bit value, but we
     * cannot store 2^0*2^16 and 2^0.75*2^16 in a table.
     */
    q_format--;

    multiplier = hcb2_scale_mod_4[scale & 0x3];

    pCoefLeft  = coefLeft;
    pCoefRight = coefRight;

    nextWinPtrUpdate = (coef_per_win - band_length);

    /*
     * Step through all the windows in this group, replacing this
     * band in each window's spectrum with correlated random noise
     */

    start_indx = 0;

    for (win_indx = wins_in_group; win_indx > 0; win_indx--)
    {
        /*
         * Set the q-format for all scalefactor bands in the group.
         * Normally, we could not assume that grouped scalefactors
         * share the same q-format.
         * However, here we can make this assumption.  The reason
         * being -- if this function is called, it is assumed
         * PNS was used on the left channel.  When PNS is used,
         * all scalefactors in a group share the same q-format.
         *
         */
        q_formatRight[start_indx] = q_format;

        start_indx += sfb_per_win;

        /* reconstruct right noise values */
        for (tempInt = band_length; tempInt > 0; tempInt--)
        {
            *(pCoefRight++) = (Int32)(*(pCoefLeft++) >> 16) * multiplier;
        }

        pCoefRight += nextWinPtrUpdate;
        pCoefLeft  += nextWinPtrUpdate;

    } /* for (win_indx) */

    return;

} /* void pns_corr */
