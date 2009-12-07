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

 Pathname: intensity_right.c

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: Modified per review comments.

 Description: Noticed that the code could be more efficient by
 using some other method for storing the sign.  The code was changed to
 use a signed Int to store the table, and an adjustment of the q-format to
 reflect the difference between the data being shifted by 16 and the table
 being stored in q-15 format.

 Description: Updated pseudocode

 Description: When the multiplication of two 16-bits variables is stored in
              an 32-bits variable, the result should be typecasted explicitly
              to Int32 before it is stored.
              *(pCoefRight++) = (Int32) tempInt2 * multiplier;

 Description:
------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:

    scalefactor  =  Multiplier used to scale the data extracted from the left
                    channel for use on the right.
                    [const Int]

    coef_per_win =  Number of coefficients per window.
                    (128 for short, 1024 for long)
                    [const Int]

    sfb_per_win  =  Number of scalefactor bands per window.  This should be
                    a number divisible by four.
                    [const Int]

    wins_in_group = The number of windows in the group being decoded.
                    This number falls within the range 1-8.
                    [const Int]

    band_length  =  The length of the scalefactor band being decoded.
                    This value is divisible by 4.
                    [const Int]

    codebook     =  Value that denotes which Huffman codebook was used for
                    the encoding of this grouped scalefactor band.
                    [const Int]

    ms_used      =  Flag that denotes whether M/S is active for this band.
                    [const Bool]

    q_formatLeft = The Q-format for the left channel's fixed-point spectral
                   coefficients, on a per-scalefactor band, non-grouped basis.
                   [const Int *, length MAXBANDS]

    q_formatRight = The Q-format for the right channel's fixed-point spectral
                    coefficients, on a per-scalefactor band, non-grouped basis.
                    [Int *, length MAXBANDS]

    coefLeft =  Array containing the fixed-point spectral coefficients
                for the left channel.
                [const Int32 *, length 1024]

    coefRight = Array containing the fixed-point spectral coefficients
                for the right channel.
                [Int32 *, length 1024]

 Local Stores/Buffers/Pointers Needed:
    intensity_factor =  Table which stores the values of
                        0.5^(0), 0.5^(1/4), 0.5^(2/4) and 0.5^(3/4)
                        [UInt, length 4]

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    None

 Pointers and Buffers Modified:
    coefRight[]         Contains the new spectral information

    q_formatRight[]     Q-format may be updated with changed fixed-point
                        data in coefRight.

 Local Stores Modified:
    None

 Global Stores Modified:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function applies Intensity Stereo, generating data on the right channel
 that is derived from data on the Left.  A scalefactor is applied using the
 following formula...

 RightCh = LeftCh*0.5^(scalefactor/4)

 This function works for one scalefactor band, which may belong to a group.
 (i.e. the same scalefactor band repeated across multiple windows belonging
  to one group.)

------------------------------------------------------------------------------
 REQUIREMENTS

 codebook must be either INTENSITY_HCB or INTENSITY_HCB2 when this function
 is called.

 ms_used must be 1 when TRUE, 0 when FALSE.

 wins_in_group falls within the range [1-8]

------------------------------------------------------------------------------
 REFERENCES

 (1) ISO/IEC 14496-3:1999(E)
     Part 3
        Subpart 4.6.7.2.3 Decoding Process (Intensity Stereo)

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
    multiplier = codebook AND 0x1;

    multiplier = multiplier XOR ms_used;

    multiplier = multiplier << 1;

    multiplier = multiplier - 1;

    multiplier = multiplier * intensity_factor[scalefactor & 0x3];

    scf_div_4 = (scalefactor >> 2);

    nextWinPtrUpdate = (coef_per_win - band_length);

    FOR (win_indx = wins_in_group; win_indx > 0; win_indx--)

        *(pQformatRight) = scf_div_4 + *(pQformatLeft) - 1;

        FOR (tempInt = band_length; tempInt > 0; tempInt--)
           tempInt2 = (Int)(*(pCoefLeft) >> 16);

           *(pCoefRight) = tempInt2 * multiplier;

           pCoefRight = pCoefRight + 1;
           pCoefLeft  = pCoefLeft  + 1;

        ENDFOR

        pCoefRight = pCoefRight + nextWinPtrUpdate;
        pCoefLeft  = pCoefLeft + nextWinPtrUpdate;

        pQformatRight = pQformatRight + sfb_per_win;
        pQformatLeft  = pQformatLeft  + sfb_per_win;
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
#include "intensity_right.h"
#include "e_huffmanconst.h"

#include "fxp_mul32.h"
#include "aac_mem_funcs.h"


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
const Int16 intensity_factor[4] =
{
    32767,  /* (0.5^0.00)*2^15 - 1 (minus 1 for storage as type Int) */
    27554,  /* (0.5^0.25)*2^15 */
    23170,  /* (0.5^0.50)*2^15 */
    19484
}; /* (0.5^0.75)*2^15 */


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

void intensity_right(
    const Int   scalefactor,
    const Int   coef_per_win,
    const Int   sfb_per_win,
    const Int   wins_in_group,
    const Int   band_length,
    const Int   codebook,
    const Bool  ms_used,
    const Int   q_formatLeft[],
    Int   q_formatRight[],
    const Int32 coefLeft[],
    Int32 coefRight[])

{
    const Int32 *pCoefLeft  = coefLeft;
    Int32 *pCoefRight = coefRight;

    const Int *pQformatLeft  = q_formatLeft;
    Int *pQformatRight = q_formatRight;

    Int   multiplier;
    Int   scf_div_4;
    Int   nextWinPtrUpdate;

    /*
     * The sign of the intensity multiplier obeys the following table...
     *
     * codebook       | ms_used | multiplier
     * --------------------------------------
     * INTENSITY_HCB  | TRUE    |    -1
     * INTENSITY_HCB  | FALSE   |    +1
     * INTENSITY_HCB2 | TRUE    |    +1
     * INTENSITY_HCB2 | FALSE   |    -1
     *
     * In binary, the above table is represented as...
     *
     * codebook       | ms_used | multiplier
     * --------------------------------------
     * 1111b          | 1       |    -1
     * 1111b          | 0       |    +1
     * 1110b          | 1       |    +1
     * 1110b          | 0       |    -1
     *
     */

    /*
     * Deriving the correct value for "multiplier" is illustrated
     * below for all 4 possible combinations of codebook and ms_used
     */

    /*
     * 1111b AND 0x1 = 1b
     * 1111b AND 0x1 = 1b
     * 1110b AND 0x1 = 0b
     * 1110b AND 0x1 = 0b
     */
    multiplier  = (codebook & 0x1);

    /*
     * 1b XOR 1 = 0b
     * 1b XOR 0 = 1b
     * 0b XOR 1 = 1b
     * 0b XOR 0 = 0b
     */
    multiplier ^= ms_used;

    /*
     * 0b << 1 = 0
     * 1b << 1 = 2
     * 1b << 1 = 2
     * 0b << 1 = 0
     */
    multiplier <<= 1;

    /*
     * 0 - 1 = -1
     * 2 - 1 = +1
     * 2 - 1 = +1
     * 0 - 1 = -1
     */
    multiplier--;

    multiplier *= intensity_factor[scalefactor & 0x3];

    scf_div_4 = (scalefactor >> 2);

    /*
     * Step through all the windows in this group, replacing this
     * band in each window's spectrum with
     * left-channel correlated data
     */

    nextWinPtrUpdate = (coef_per_win - band_length);

    for (Int win_indx = wins_in_group; win_indx > 0; win_indx--)
    {

        /*
         * Calculate q_formatRight
         *
         * q_formatLeft must be included, since the values
         * on the right-channel are derived from the values
         * on the left-channel.
         *
         * scalefactor/4 is included, since the intensity
         * formula is RightCh = LeftCh*0.5^(scalefactor/4)
         *
         * powers of 0.5 increase the q_format by 1.
         * (Another way to multiply something by 0.5^(x)
         *  is to increase its q-format by x.)
         *
         * Finally the q-format must be decreased by 1.
         * The reason for this is because the table is stored
         * in q-15 format, but we are shifting by 16 to do
         * a 16 x 16 multiply.
         */

        *(pQformatRight) = scf_div_4 + *(pQformatLeft);

        /*
         * reconstruct right intensity values
         *
         * to make things faster, this for loop
         * can be partially unrolled, since band_length is a multiple
         * of four.
         */


        if (multiplier == 32767)
        {
            Int32 tempInt2 = *(pCoefLeft++);
            Int32 tempInt22 = *(pCoefLeft++);

            for (Int tempInt = band_length >> 1; tempInt > 0; tempInt--)
            {
                *(pCoefRight++) = tempInt2;
                *(pCoefRight++) = tempInt22;
                tempInt2 = *(pCoefLeft++);
                tempInt22 = *(pCoefLeft++);
            }

        }
        else
        {

            Int32 tempInt2 = *(pCoefLeft++);
            Int32 tempInt22 = *(pCoefLeft++);
            for (Int tempInt = band_length >> 1; tempInt > 0; tempInt--)
            {
                *(pCoefRight++) = fxp_mul32_by_16(tempInt2, multiplier) << 1;
                *(pCoefRight++) = fxp_mul32_by_16(tempInt22, multiplier) << 1;
                tempInt2 = *(pCoefLeft++);
                tempInt22 = *(pCoefLeft++);
            }
        }

        /*
         * Set pCoefRight and pCoefLeft to the beginning of
         * this sfb in the next window in the group.
         */

        pCoefRight += nextWinPtrUpdate;
        pCoefLeft  += (nextWinPtrUpdate - 2);

        /*
         * Update pQformatRight and pQformatLeft to this sfb in
         * in the next window in the group.
         */

        pQformatRight += sfb_per_win;
        pQformatLeft  += sfb_per_win;

    } /* for (win_indx) */


} /* void intensity_right */
