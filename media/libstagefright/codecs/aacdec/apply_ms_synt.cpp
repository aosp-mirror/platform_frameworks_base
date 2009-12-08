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

 Pathname: ./src/apply_ms_synt.c


------------------------------------------------------------------------------
 REVISION HISTORY

 Description: Updated pseudocode to correct capitalized format for the IF
 FOR and WHILE statements.

 Description: Delete local variable start_indx, since it is never used.

 Who:                       Date:
 Description:
------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:

    pFrameInfo = Pointer to structure that holds information about each group.
                 (long block flag, number of windows, scalefactor bands
                  per group, etc.)
                 [const pFrameInfo * const]

    group      = Array that contains indexes of the
                 first window in the next group.
                 [const Int *, length 8]

    mask_map   = Array that denotes whether M/S stereo is turned on for
                 each grouped scalefactor band.
                 [const Int *, length MAX_SFB]

    codebook_map = Array that denotes which Huffman codebook was used for
                   the encoding of each grouped scalefactor band.
                   [const Int *, length MAX_SFB]

    coefLeft = Array containing the fixed-point spectral coefficients
                       for the left channel.
                       [Int32 *, length 1024]

    coefRight = Array containing the fixed-point spectral coefficients
                        for the right channel.
                        [Int32 *, length 1024]

    q_formatLeft = The Q-format for the left channel's fixed-point spectral
                   coefficients, on a per-scalefactor band, non-grouped basis.
                   [Int *, length MAX_SFB]

    q_formatRight = The Q-format for the right channel's fixed-point spectral
                    coefficients, on a per-scalefactor band, non-grouped basis.
                    [Int *, length MAX_SFB]

 Local Stores/Buffers/Pointers Needed:
    None

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    None

 Pointers and Buffers Modified:
    coefLeft  = Contains the new spectral information.

    coefRight = Contains the new spectral information.

    q_formatLeft      = Q-format may be updated with changed to fixed-point
                        data in coefLeft.

    q_formatRight     = Q-format may be updated with changed to fixed-point
                        data in coefRight.

 Local Stores Modified:
    None

 Global Stores Modified:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function steps through all of the tools that are applied on a
 scalefactor band basis.

 The use of M/S stereo is checked for.  For M/S decoding to take
 place, ms_mask_map must be TRUE for that particular SFB, AND the Huffman
 codebook used must be < NOISE_HCB.

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
   developer retains full right to use the code for his/her own purpose,
   assign or donate the code to a third party and to inhibit third party
   from using the code for non MPEG-2 NBC/MPEG-4 Audio conforming products.
   This copyright notice must be included in all copies or derivative
   works."
   Copyright(c)1996.

------------------------------------------------------------------------------
 PSEUDO-CODE

    pCoefRight = coefRight;
    pCoefLeft = coefLeft;

    window_start = 0;
    tot_sfb = 0;

    coef_per_win = pFrameInfo->coef_per_win[0];

    sfb_per_win = pFrameInfo->sfb_per_win[0];

    DO

        pBand     = pFrameInfo->win_sfb_top[window_start];

        partition = *(pGroup);

        pGroup = pGroup + 1;

        band_start = 0;

        wins_in_group = (partition - window_start);

        FOR (sfb = sfb_per_win; sfb > 0; sfb--)

            band_stop = *(pBand);

            pBand = pBand + 1;

            codebook = *(pCodebookMap);

            pCodebookMap = pCodebookMap + 1;

            mask_enabled = *(pMaskMap);

            pMaskMap = pMaskMap + 1;

            IF (codebook < NOISE_HCB)
            THEN
                IF (mask_enabled != FALSE)
                THEN
                    band_length = band_stop - band_start;

                    CALL
                        ms_synt(
                            wins_in_group,
                            coef_per_win,
                            sfb_per_win,
                            band_length,
                           &(pCoefLeft[band_start]),
                           &(pCoefRight[band_start]),
                           &(q_formatLeft[tot_sfb]),
                           &(q_formatRight[tot_sfb]) );

                    MODIFYING
                        &(pCoefLeft[band_start]),
                        &(pCoefRight[band_start]),
                        &(q_formatLeft[tot_sfb]),
                        &(q_formatRight[tot_sfb])

                    RETURNING
                        None
                ENDIF
            ENDIF
            band_start = band_stop;

            tot_sfb = tot_sfb + 1;

        ENDFOR

        pCoefRight = pCoefRight + coef_per_win * wins_in_group;
        pCoefLeft  = pCoefLeft  + coef_per_win * wins_in_group;

        wins_in_group = wins_in_group - 1;

        tot_sfb = tot_sfb + sfb_per_win * wins_in_group;

        window_start = partition;

    WHILE (partition < pFrameInfo->num_win);

    return;
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
#include "apply_ms_synt.h"
#include "e_huffmanconst.h"
#include "ms_synt.h"

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

void apply_ms_synt(
    const FrameInfo * const pFrameInfo,
    const Int        group[],
    const Bool       mask_map[],
    const Int        codebook_map[],
    Int32      coefLeft[],
    Int32      coefRight[],
    Int        q_formatLeft[MAXBANDS],
    Int        q_formatRight[MAXBANDS])

{

    Int32   *pCoefRight;

    Int32   *pCoefLeft;

    Int     tot_sfb;
    Int     sfb;

    Int     band_length;
    Int     band_start;
    Int     band_stop;
    Int     coef_per_win;

    Int     codebook;
    Int     partition;
    Int     window_start;

    Int     sfb_per_win;
    Int     wins_in_group;

    const Int16 *pBand;
    const Int   *pCodebookMap  = codebook_map;
    const Int   *pGroup        = group;
    const Bool  *pMaskMap      = mask_map;

    Bool mask_enabled;

    pCoefRight = coefRight;
    pCoefLeft = coefLeft;

    window_start = 0;
    tot_sfb = 0;

    /*
     * Each window in the frame should have the same number of coef's,
     * so coef_per_win is constant in all the loops
     */
    coef_per_win = pFrameInfo->coef_per_win[0];

    /*
     * Because the number of scalefactor bands per window should be
     * constant for each frame, sfb_per_win can be determined outside
     * of the loop.
     *
     * For 44.1 kHz sampling rate   sfb_per_win = 14 for short windows
     *                              sfb_per_win = 49 for long  windows
     */

    sfb_per_win = pFrameInfo->sfb_per_win[0];

    do
    {
        pBand     = pFrameInfo->win_sfb_top[window_start];

        /*
         * Partition is equal to the first window in the next group
         *
         * { Group 0    }{      Group 1      }{    Group 2 }{Group 3}
         * [win 0][win 1][win 2][win 3][win 4][win 5][win 6][win 7]
         *
         * pGroup[0] = 2
         * pGroup[1] = 5
         * pGroup[2] = 7
         * pGroup[3] = 8
         */

        partition = *(pGroup++);

        band_start = 0;

        wins_in_group = (partition - window_start);

        for (sfb = sfb_per_win; sfb > 0; sfb--)
        {
            /* band is offset table, band_stop is last coef in band */
            band_stop = *(pBand++);

            codebook = *(pCodebookMap++);

            mask_enabled = *(pMaskMap++);

            /*
             * When a codebook < NOISE_HCB is found, apply M/S to that
             * scalefactorband.
             *
             * Example...  sfb[3] == NOISE_HCB
             *
             * [ Group 1                                      ]
             * [win 0                 ][win 1                 ]
             * [0][1][2][X][4][5][6][7][0][1][2][X][4][5][6][7]
             *
             * The for(sfb) steps through the sfb's 0-7 in win 0.
             *
             * Finding sfb[3]'s codebook == NOISE_HCB, the code
             * steps through all the windows in the group (they share
             * the same scalefactors) and replaces that sfb with noise.
             */

            if (codebook < NOISE_HCB)
            {
                if (mask_enabled != FALSE)
                {
                    band_length = band_stop - band_start;

                    ms_synt(
                        wins_in_group,
                        coef_per_win,
                        sfb_per_win,
                        band_length,
                        &(pCoefLeft[band_start]),
                        &(pCoefRight[band_start]),
                        &(q_formatLeft[tot_sfb]),
                        &(q_formatRight[tot_sfb]));
                }
            }
            band_start = band_stop;

            tot_sfb++;

        } /* for (sfb) */

        /*
         * Increment pCoefRight and pCoefLeft by
         * coef_per_win * the number of windows
         */

        pCoefRight += coef_per_win * wins_in_group;
        pCoefLeft  += coef_per_win * wins_in_group--;

        /*
         * Increase tot_sfb by sfb_per_win times the number of windows minus 1.
         * The minus 1 comes from the fact that tot_sfb is already pointing
         * to the first sfb in the 2nd window of the group.
         */
        tot_sfb += sfb_per_win * wins_in_group;

        window_start = partition;

    }
    while (partition < pFrameInfo->num_win);

    /* pFrameInfo->num_win = 1 for long windows, 8 for short_windows */

    return;

} /* apply_ms_synt() */


