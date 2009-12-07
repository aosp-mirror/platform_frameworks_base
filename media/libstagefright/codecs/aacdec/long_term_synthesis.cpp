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

 Pathname: long_term_synthesis.c


------------------------------------------------------------------------------
 REVISION HISTORY

 Description: Made the following changes based on the review comments.
              1. Separated "shift_factor>=0" on line 395 to "shift_factor>0"
                 and "shift_factor=0" two cases.
              2. Added comments on line 393 to explain why factor 2 is being
                 used to calculate shift_factor.
              3. Added comments for short window implementation.
              4. Changed "*(pPredicted_spectral++) = *pPredicted_spectral>>2"
                 to "*(pPredicted++)>>=2" although they are the same.
              5. Changed pseudo code "X+=Y" to "X=X+Y".
              6. Fixed ending comment of "for" loop.
              7. Passed in the size of the array and deleted some of the
                 include files.

 Description: Unroll the loops.

 Description: Changed index "wnd" in previous line 584 with "wnd_offset"
              and made other correspondent changes to the code.


 Description: Based on Ken's suggestion, modified the function with the
              passing-in Q format as scalefactor band basis in order to
              simplify TNS block functions.

 Description: Optimization.

 Description: Made changes based on review comments.
              1. Changed misspellings.
              2. Changed win_sfb_top[] from two dimensional array to one
              dimensional array and correspondently changed the code.
              3. Changed function prototype to remove some redundant
              informations.
              4. Fixed the adjusting Q format part code.
              5. Fixed lines 825, 826 with correct updating pointers.

 Description: Due to TNS and LTP Q format issue, added code to adjust
              predicted_spectral() to maximum resolution before perform
              long term synthesis.

 Description: Modified based on review comments.

 Description: Changed "max" data type from UInt to UInt32.

 Description: Changed so that nothing is done for the case of "all zero"
 data coming from the output of Trans4m_time_2_freq. Also, included more
 efficient calculation of the abs(x).  And, I updated the pseudocode.

 Description: Use an auxiliary variable temp, to avoid using the same
    pointer and a post-increment pointer in the same line. This may not
    work with all compilers.

 Who:                                   Date:
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    win_seq = type of window sequence (WINDOW_SEQUENCE).
    sfb_per_win = number of scalefactor bands for each window, 1024 for
                  long window, 128 for short window, type of Int.
    win_sfb_top = buffer (Int16) containing the top coefficient per
                  scalefactor band for each window.
    win_prediction_used = buffer (Int) containing the prediction flag
                          information for short windows. Each item in the
                          buffer toggles prediction on(1)/off(0) for each
                          window separately.
    sfb_prediction_used = buffer (Int) containing the prediction flag
                          information for scalefactor band(sfb). Each item
                          toggle prediction on(1)/off(0) on each scalefactor
                          band of every window.
    current_frame = channel buffer (Int32) containing the dequantized
                    spectral coefficients or errors of current frame.
    q_format = buffer (Int) containing Q format for each scalefactor band of
               input current_frame.
    predicted_spectral = buffer (Int32) containing predicted spectral
                         components of current frame.
    pred_q_format = Q format (Int) for predicted spectral components of
                    current frame.
    coef_per_win = number of coefficients per window for short windows.
                   type of Int.
    short_window_num = number of short windows, type of Int.
    reconstruct_sfb_num = number of scalefactor bands used for reconstruction
                          for short windows, type of Int.

 Local Stores/Buffers/Pointers Needed:
    None

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    None

 Pointers and Buffers Modified:
    current_frame contents are the dequantized spectrum with a prediction
    vector added when prediction is turned on.

    q_format contents are updated with the new Q format (Int) for each
    scalefactor band of output current_frame buffer.

 Local Stores Modified:
    None

 Global Stores Modified:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function performs long term synthesis using transmitted spectral
 coeffients or errors and predicted spectral components.

 Long term synthesis is part of long term prediction (LTP) which is used to
 reduce the redundancy of a signal between successive coding frames. The
 functionality of long term synthesis is to reconstruct the frequency domain
 spectral by adding the predicted spectral components and the transmitted
 spectral error when prediction is turned on.

------------------------------------------------------------------------------
 REQUIREMENTS

 None

------------------------------------------------------------------------------
 REFERENCES

 (1) ISO/IEC 14496-3:1999(E)
     Part 3: Audio
        Subpart 4.6.6   Long Term Prediction (LTP)

 (2) MPEG-2 NBC Audio Decoder
     "This software module was originally developed by Nokia in the course
     of development of the MPEG-2 AAC/MPEG-4 Audio standard ISO/IEC13818-7,
     14496-1, 2 and 3. This software module is an implementation of a part
     of one or more MPEG-2 AAC/MPEG-4 Audio tools as specified by the MPEG-2
     aac/MPEG-4 Audio standard. ISO/IEC  gives users of the MPEG-2aac/MPEG-4
     Audio standards free license to this software module or modifications
     thereof for use in hardware or software products claiming conformance
     to the MPEG-2 aac/MPEG-4 Audio  standards. Those intending to use this
     software module in hardware or software products are advised that this
     use may infringe existing patents. The original developer of this
     software module, the subsequent editors and their companies, and ISO/IEC
     have no liability for use of this software module or modifications
     thereof in an implementation. Copyright is not released for non MPEG-2
     aac/MPEG-4 Audio conforming products. The original developer retains
     full right to use the code for the developer's own purpose, assign or
     donate the code to a third party and to inhibit third party from using
     the code for non MPEG-2 aac/MPEG-4 Audio conforming products. This
     copyright notice must be included in all copies or derivative works.
     Copyright (c)1997.

------------------------------------------------------------------------------
 PSEUDO-CODE

    pPredicted_spectral = &predicted_spectral[0];
    pPredicted_spectral_start = pPredicted_spectral;
    pSfb_prediction_used = &sfb_prediction_used[0];

    IF (win_seq != EIGHT_SHORT_SEQUENCE)
    THEN

        sfb_offset = 0;

        pWinSfbTop = &pWin_sfb_top[0];

        pQ_format = &q_format[0];

        FOR (i = sfb_per_frame; i>0; i--)

            IF (*(pSfb_prediction_used++) != FALSE)
            THEN

                pPredicted_offset = pPredicted_spectral_start +
                                                            sfb_offset;
                pCurrent_frame = &current_frame[sfb_offset];

                quarter_sfb_width = (*pWinSfbTop - sfb_offset) >> 2;

                max = 0;

                pPredicted_spectral = pPredicted_offset;

                FOR (j = (*pWinSfbTop - sfb_offset); j>0 ; j--)

                    tmpInt32 = *(pPredicted_spectral++);

                    IF (tmpInt32 < 0)
                    THEN

                        tmpInt32 = -tmpInt32;

                    ENDIF

                    max |= tmpInt32;

                ENDFOR

                tmpInt = 0;

                IF (max != 0)
                THEN

                    WHILE (max < 0x40000000L)

                        max <<= 1;
                        tmpInt++;

                    ENDWHILE

                    pPredicted_spectral = pPredicted_offset;

                    FOR (j = quarter_sfb_width; j>0 ; j--)

                        *(pPredicted_spectral++) <<= tmpInt;
                        *(pPredicted_spectral++) <<= tmpInt;
                        *(pPredicted_spectral++) <<= tmpInt;
                        *(pPredicted_spectral++) <<= tmpInt;

                    ENDFOR

                    adjusted_pred_q = pred_q_format + tmpInt;

                    pPredicted_spectral = pPredicted_offset;

                    shift_factor = *(pQ_format) - adjusted_pred_q;

                    IF ((shift_factor >= 0) && (shift_factor < 31))
                    THEN

                        shift_factor = shift_factor + 1;

                        FOR (j = quarter_sfb_width; j>0 ; j--)

                            *(pCurrent_frame++) =
                                (*pCurrent_frame>>shift_factor)
                              + (*(pPredicted_spectral++)>>1);
                            *(pCurrent_frame++) =
                                (*pCurrent_frame>>shift_factor)
                              + (*(pPredicted_spectral++)>>1);
                            *(pCurrent_frame++) =
                                (*pCurrent_frame>>shift_factor)
                              + (*(pPredicted_spectral++)>>1);
                            *(pCurrent_frame++) =
                                (*pCurrent_frame>>shift_factor)
                              + (*(pPredicted_spectral++)>>1);

                        ENDFOR

                        *(pQ_format) = adjusted_pred_q - 1;

                    ELSEIF (shift_factor >= 31)
                    THEN

                        FOR (j = quarter_sfb_width; j>0 ; j--)

                            *(pCurrent_frame++) = *(pPredicted_spectral++);
                            *(pCurrent_frame++) = *(pPredicted_spectral++);
                            *(pCurrent_frame++) = *(pPredicted_spectral++);
                            *(pCurrent_frame++) = *(pPredicted_spectral++);

                        ENDFOR

                        *(pQ_format) = adjusted_pred_q;

                    ELSEIF ((shift_factor < 0) && (shift_factor > -31))
                    THEN

                        shift_factor = 1 - shift_factor;

                        FOR (j = quarter_sfb_width; j>0 ; j--)

                            *(pCurrent_frame++) = (*pCurrent_frame>>1) +
                                (*(pPredicted_spectral++)>>shift_factor);
                            *(pCurrent_frame++) = (*pCurrent_frame>>1) +
                                (*(pPredicted_spectral++)>>shift_factor);
                            *(pCurrent_frame++) = (*pCurrent_frame>>1) +
                                (*(pPredicted_spectral++)>>shift_factor);
                            *(pCurrent_frame++) = (*pCurrent_frame>>1) +
                                (*(pPredicted_spectral++)>>shift_factor);

                        ENDFOR

                        *(pQ_format) = *(pQ_format) - 1;

                    ENDIF

                ENDIF

            ENDIF [ IF (*(pSfb_prediction_used++) != FALSE) ]

            sfb_offset = *pWinSfbTop;
            pWinSfbTop = pWinSfbTop + 1;
            pQ_format = pQ_format + 1;

        ENDFOR [ FOR (i = sfb_per_frame; i>0; i--) ]

    ELSE

        pCurrent_frame_start = &current_frame[0];

        pQ_format_start = &q_format[0];

        num_sfb = sfb_per_win[0];

        FOR (wnd=0; wnd<short_window_num; wnd++)

            pWinSfbTop = &pWin_sfb_top[0];

            pQ_format = pQ_format_start;

            IF (win_prediction_used[wnd] != FALSE)
            THEN

                sfb_offset = 0;

                FOR (i = reconstruct_sfb_num; i > 0; i--)

                    pPredicted_offset = pPredicted_spectral_start +
                                                                sfb_offset;
                    pCurrent_frame = pCurrent_frame_start + sfb_offset;

                    quarter_sfb_width = (*pWinSfbTop - sfb_offset) >> 2;

                    max = 0;

                    pPredicted_spectral = pPredicted_offset;

                    FOR (j = (*pWinSfbTop - sfb_offset); j>0 ; j--)

                        tmpInt32 = *(pPredicted_spectral++);

                        IF (tmpInt32 < 0)
                        THEN

                            tmpInt32 = -tmpInt32;

                        ENDIF

                        max |= tmpInt32;

                    ENDFOR

                    tmpInt = 0;

                    IF (max != 0)
                    THEN

                        WHILE (max < 0x40000000L)

                            max <<= 1;
                            tmpInt++;

                        ENDWHILE


                        pPredicted_spectral = pPredicted_offset;

                        FOR (j = quarter_sfb_width; j>0 ; j--)

                            *(pPredicted_spectral++) <<= tmpInt;
                            *(pPredicted_spectral++) <<= tmpInt;
                            *(pPredicted_spectral++) <<= tmpInt;
                            *(pPredicted_spectral++) <<= tmpInt;

                        ENDFOR

                        adjusted_pred_q = pred_q_format + tmpInt;

                        pPredicted_spectral = pPredicted_offset;

                        shift_factor = *(pQ_format) - adjusted_pred_q;

                        IF ((shift_factor >= 0) && (shift_factor < 31))
                        THEN

                            shift_factor = shift_factor + 1;

                            FOR (j = quarter_sfb_width; j>0 ; j--)

                                *(pCurrent_frame++) =
                                    (*pCurrent_frame>>shift_factor) +
                                                (*(pPredicted_spectral++)>>1);
                                *(pCurrent_frame++) =
                                    (*pCurrent_frame>>shift_factor) +
                                                (*(pPredicted_spectral++)>>1);
                                *(pCurrent_frame++) =
                                    (*pCurrent_frame>>shift_factor) +
                                                (*(pPredicted_spectral++)>>1);
                                *(pCurrent_frame++) =
                                    (*pCurrent_frame>>shift_factor) +
                                                (*(pPredicted_spectral++)>>1);

                            ENDFOR

                            *(pQ_format) = adjusted_pred_q - 1;

                        ELSEIF (shift_factor >= 31)
                        THEN

                            FOR (j = quarter_sfb_width; j>0 ; j--)

                                *(pCurrent_frame++) = *(pPredicted_spectral++);
                                *(pCurrent_frame++) = *(pPredicted_spectral++);
                                *(pCurrent_frame++) = *(pPredicted_spectral++);
                                *(pCurrent_frame++) = *(pPredicted_spectral++);

                            ENDFOR

                            *(pQ_format) = adjusted_pred_q;

                        ELSEIF ((shift_factor < 0) && (shift_factor > -31))
                        THEN

                            shift_factor = 1 - shift_factor;

                            FOR (j = quarter_sfb_width; j>0 ; j--)

                                *(pCurrent_frame++) = (*pCurrent_frame>>1) +
                                    (*(pPredicted_spectral++)>>shift_factor);
                                *(pCurrent_frame++) = (*pCurrent_frame>>1) +
                                    (*(pPredicted_spectral++)>>shift_factor);
                                *(pCurrent_frame++) = (*pCurrent_frame>>1) +
                                    (*(pPredicted_spectral++)>>shift_factor);
                                *(pCurrent_frame++) = (*pCurrent_frame>>1) +
                                    (*(pPredicted_spectral++)>>shift_factor);

                            ENDFOR

                            *(pQ_format) = *(pQ_format) - 1;

                        ENDIF

                    ENDIF

                    sfb_offset = *pWinSfbTop;
                    pWinSfbTop = pWinSfbTop + 1;
                    pQ_format = pQ_format + 1;

                ENDFOR [ FOR (i = reconstruct_sfb_num; i > 0; i--) ]

            ENDIF [ IF (win_prediction_used[wnd] != FALSE) ]

            pPredicted_spectral_start = pPredicted_spectral_start + num_sfb;
            pCurrent_frame_start = pCurrent_frame_start + num_sfb;
            wnd_offset = wnd_offset + num_sfb;
            pQ_format_start = pQ_format_start + num_sfb;

        ENDFOR [ FOR (wnd=0; wnd<short_window_num; wnd++) ]

    ENDIF [ IF (win_seq != EIGHT_SHORT_SEQUENCE) ]

    RETURN

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
#include "pv_audio_type_defs.h"
#include "e_window_sequence.h"
#include "long_term_synthesis.h"

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
void long_term_synthesis(
    WINDOW_SEQUENCE     win_seq,
    Int                 sfb_per_win,
    Int16               win_sfb_top[],
    Int                 win_prediction_used[],
    Int                 sfb_prediction_used[],
    Int32               current_frame[],
    Int                 q_format[],         /* for each sfb */
    Int32               predicted_spectral[],
    Int                 pred_q_format,      /* for predicted_spectral[] */
    Int                 coef_per_win,
    Int                 short_window_num,
    Int                 reconstruct_sfb_num)
{
    /*----------------------------------------------------------------------------
    ; Define all local variables
    ----------------------------------------------------------------------------*/
    /* Scalefactor band offset */
    Int sfb_offset;

    /* Window index */
    Int wnd;

    /* Pointer to array containing predicted samples */
    Int32 *pPredicted_spectral;

    /* Pointer to the beginning of array containing predicted samples */
    Int32 *pPredicted_spectral_start;

    Int32 *pPredicted_offset;

    /* Pointer to array containing current spectral components for a channel*/
    Int32 *pCurrent_frame;

    /* Another pointer to array containing current spectral components */
    Int32 *pCurrent_frame_start;

    /* Pointer to prediction flag for each scalefactor band */
    Int *pSfb_prediction_used;

    /* Pointer to top coef per scalefactor band */
    Int16 *pWinSfbTop;

    /* Pointer to q_format array */
    Int *pQ_format;
    Int *pQ_format_start;
    Int32   temp;

    Int i;
    Int j;

    Int quarter_sfb_width;
    Int num_sfb;
    Int shift_factor;

    UInt32  max;
    Int32   tmpInt32;

    Int tmpInt;
    Int adjusted_pred_q;
    Int pred_shift;

    /*----------------------------------------------------------------------------
    ; Function body here
    ----------------------------------------------------------------------------*/
    /* Initialize pointers */
    pPredicted_spectral = &predicted_spectral[0];
    pPredicted_spectral_start = pPredicted_spectral;

    /*
     * NOTE:
     * sfb_prediction_used[] start from 0 or 1 depending on nok_lt_decode.c;
     * currently we agree to make it start from 0;
     */
    pSfb_prediction_used = &sfb_prediction_used[0];

    /*********************************/
    /* LTP synthesis for long window */
    /*********************************/
    if (win_seq != EIGHT_SHORT_SEQUENCE)
    {

        /*******************************************************/
        /* Reconstruction of current frequency domain spectrum */
        /*******************************************************/

        /* Initialize scalefactor band offset */
        sfb_offset = 0;

        /*
         * Reconstruction is processed on scalefactor band basis.
         * 1. When prediction is turned on, all the predicted spectral
         * components will be used for reconstruction.
         * 2. When prediction is turned off, reconstruction is not
         * needed. Spectral components of current frame will directly
         * come from the transmitted data.
         */
        pWinSfbTop = &win_sfb_top[0];

        pQ_format = &q_format[0];

        for (i = sfb_per_win; i > 0; i--)
        {
            /* Check prediction flag for each scalefactor band. */
            if (*(pSfb_prediction_used++) != FALSE)
            {
                /*
                 * Prediction is on. Do reconstruction routine.
                 * Reconstruct spectral component of current
                 * frame by adding the predicted spectral
                 * components and the quantized prediction
                 * errors that reconstructed from transmitted
                 * data when prediction is turned on.
                 */

                /* Set pointers to the offset of scalefactor bands */
                pPredicted_offset = pPredicted_spectral_start +
                                    sfb_offset;
                pCurrent_frame = &current_frame[sfb_offset];

                /*
                 * (*pWinSfbTop - sfb_offset) is number of coefficients
                 * of the scalefactor band.
                 * ">>2" is used to set up for later unrolling the loop.
                 */
                quarter_sfb_width = (*pWinSfbTop - sfb_offset) >> 2;

                /*
                 * Adjust pred_q_format and predicted_spectral() to
                 * maximum resolution.
                 */
                max = 0;

                pPredicted_spectral = pPredicted_offset;

                /* Find the maximum absolute value */
                for (j = (*pWinSfbTop - sfb_offset); j > 0 ; j--)
                {
                    tmpInt32 = *(pPredicted_spectral++);

                    /*
                     * Note: overflow is protected here even though
                     * tmpInt32 = 0x80000000 is very rare case.
                     *
                     *  if (tmpInt32 == LONG_MIN)
                     *  {
                     *      tmpInt32 = LONG_MAX;
                     *  }
                     *  if (tmpInt32 < 0)
                     *  {
                     *      tmpInt32 = -tmpInt32;
                     *  }
                     */

                    max |= tmpInt32 ^(tmpInt32 >> 31);
                }

                /*
                 * IF the LTP data is all zeros
                 * (max == 0) - do nothing for this sfb.
                 */

                if (max != 0)
                {
                    /* Find the number of bits to reach the max resolution */
                    tmpInt = 0;

                    while (max < 0x40000000L)
                    {
                        max <<= 1;
                        tmpInt++;
                    }

                    /*
                     * The following codes are combinded into shift factor
                     * adjusting and reconstruction section.
                     *
                     * pPredicted_spectral = pPredicted_offset;
                     * for(j = quarter_sfb_width; j>0 ; j--)
                     * {
                     *      *(pPredicted_spectral++) <<= tmpInt;
                     *      *(pPredicted_spectral++) <<= tmpInt;
                     *      *(pPredicted_spectral++) <<= tmpInt;
                     *      *(pPredicted_spectral++) <<= tmpInt;
                     * }
                     *
                     */

                    /* Adjust Q format for predicted_spectral() */
                    adjusted_pred_q = pred_q_format + tmpInt;

                    /*
                     * Adjust Q format to prevent overflow that may occur during
                     * frequency domain reconstruction.
                     *
                     */
                    pPredicted_spectral = pPredicted_offset;

                    shift_factor = *(pQ_format) - adjusted_pred_q;

                    if ((shift_factor >= 0) && (shift_factor < 31))
                    {
                        shift_factor = shift_factor + 1;
                        pred_shift = tmpInt - 1;

                        if (pred_shift >= 0)
                        {
                            for (j = quarter_sfb_width; j > 0 ; j--)
                            {
                                temp = *pCurrent_frame >> shift_factor;
                                *(pCurrent_frame++) = temp
                                                      + (*(pPredicted_spectral++) << pred_shift);
                                temp = *pCurrent_frame >> shift_factor;
                                *(pCurrent_frame++) = temp
                                                      + (*(pPredicted_spectral++) << pred_shift);
                                temp = *pCurrent_frame >> shift_factor;
                                *(pCurrent_frame++) = temp
                                                      + (*(pPredicted_spectral++) << pred_shift);
                                temp = *pCurrent_frame >> shift_factor;
                                *(pCurrent_frame++) = temp
                                                      + (*(pPredicted_spectral++) << pred_shift);
                            }
                        }
                        else
                        {
                            for (j = quarter_sfb_width; j > 0 ; j--)
                            {
                                temp = *pCurrent_frame >> shift_factor;
                                *(pCurrent_frame++) = temp
                                                      + (*(pPredicted_spectral++) >> 1);
                                temp = *pCurrent_frame >> shift_factor;
                                *(pCurrent_frame++) = temp
                                                      + (*(pPredicted_spectral++) >> 1);
                                temp = *pCurrent_frame >> shift_factor;
                                *(pCurrent_frame++) = temp
                                                      + (*(pPredicted_spectral++) >> 1);
                                temp = *pCurrent_frame >> shift_factor;
                                *(pCurrent_frame++) = temp
                                                      + (*(pPredicted_spectral++) >> 1);
                            }
                        }

                        /* Updated new Q format for current scalefactor band */
                        *(pQ_format) = adjusted_pred_q  - 1;
                    }
                    else if (shift_factor >= 31)
                    {
                        for (j = quarter_sfb_width; j > 0 ; j--)
                        {
                            *(pCurrent_frame++) =
                                *(pPredicted_spectral++) << tmpInt;
                            *(pCurrent_frame++) =
                                *(pPredicted_spectral++) << tmpInt;
                            *(pCurrent_frame++) =
                                *(pPredicted_spectral++) << tmpInt;
                            *(pCurrent_frame++) =
                                *(pPredicted_spectral++) << tmpInt;
                        }

                        /* Updated new Q format for current scalefactor band */
                        *(pQ_format) = adjusted_pred_q ;
                    }
                    else if ((shift_factor < 0) && (shift_factor > -31))
                    {
                        shift_factor = 1 - shift_factor;
                        pred_shift = tmpInt - shift_factor;

                        if (pred_shift >= 0)
                        {
                            for (j = quarter_sfb_width; j > 0 ; j--)
                            {
                                temp = *pCurrent_frame >> 1;
                                *(pCurrent_frame++) =  temp +
                                                       (*(pPredicted_spectral++) << pred_shift);
                                temp = *pCurrent_frame >> 1;
                                *(pCurrent_frame++) =  temp +
                                                       (*(pPredicted_spectral++) << pred_shift);
                                temp = *pCurrent_frame >> 1;
                                *(pCurrent_frame++) =  temp +
                                                       (*(pPredicted_spectral++) << pred_shift);
                                temp = *pCurrent_frame >> 1;
                                *(pCurrent_frame++) =  temp +
                                                       (*(pPredicted_spectral++) << pred_shift);
                            }
                        }
                        else
                        {
                            pred_shift = -pred_shift;

                            for (j = quarter_sfb_width; j > 0 ; j--)
                            {
                                temp = *pCurrent_frame >> 1;
                                *(pCurrent_frame++) =  temp +
                                                       (*(pPredicted_spectral++) >> pred_shift);
                                temp = *pCurrent_frame >> 1;
                                *(pCurrent_frame++) =  temp +
                                                       (*(pPredicted_spectral++) >> pred_shift);
                                temp = *pCurrent_frame >> 1;
                                *(pCurrent_frame++) =  temp +
                                                       (*(pPredicted_spectral++) >> pred_shift);
                                temp = *pCurrent_frame >> 1;
                                *(pCurrent_frame++) =  temp +
                                                       (*(pPredicted_spectral++) >> pred_shift);
                            }
                        }

                        /*
                         * Updated new Q format for current scalefactor band
                         *
                         * This is NOT a pointer decrement
                         */
                        (*pQ_format)--;
                    }

                } /* if (max != 0) */

                /*
                 * For case (shift_factor <= -31), *pCurrent_frame and
                 * *pQ_format do not need to be updated.
                 */

            } /* if (*(pSfb_prediction_used++) != FALSE) */

            /* Updated to next scalefactor band. */
            sfb_offset = *(pWinSfbTop++);

            /* Updated pointer to next scalefactor band's Q-format */
            pQ_format++;

        } /* for (i = sfb_per_frame; i>0; i--) */

    } /* if (win_seq!=EIGHT_SHORT_SEQUENCE) */

    /**********************************/
    /* LTP synthesis for short window */
    /**********************************/
    else
    {
        /******************************************************/
        /*Reconstruction of current frequency domain spectrum */
        /******************************************************/
        pCurrent_frame_start = &current_frame[0];

        pQ_format_start = &q_format[0];

        num_sfb = sfb_per_win;

        /* Reconstruction is processed on window basis */
        for (wnd = 0; wnd < short_window_num; wnd++)
        {
            pWinSfbTop = &win_sfb_top[0];

            pQ_format = pQ_format_start;

            /* Check if prediction flag is on for each window */
            if (win_prediction_used[wnd] != FALSE)
            {
                /* Initialize scalefactor band offset */
                sfb_offset = 0;

                /*
                 * Reconstruction is processed on scalefactor band basis.
                 * 1. When prediction is turned on, all the predicted
                 * spectral components will be used for reconstruction.
                 * 2. When prediction is turned off, reconstruction is
                 * not needed. Spectral components of current frame
                 * will directly come from the transmitted data.
                 */

                /*
                 * According to ISO/IEC 14496-3 pg.91
                 * Only the spectral components in first eight scalefactor
                 * bands are added to the quantized prediction error.
                 */
                for (i = reconstruct_sfb_num; i > 0; i--)
                {
                    /* Set pointer to the offset of scalefactor bands */
                    pPredicted_offset = pPredicted_spectral_start +
                                        sfb_offset;
                    pCurrent_frame = pCurrent_frame_start + sfb_offset;

                    /*
                     * Prediction is on. Do reconstruction routine.
                     * Reconstruct spectral component of
                     * current frame by adding the predicted
                     * spectral components and the quantized
                     * prediction errors that reconstructed
                     * from transmitted data when prediction
                     * is turned on.
                     */

                    /*
                     * (*pWinSfbTop - sfb_offset) is number of coefficients
                     * of the scalefactor band.
                     * ">>2" is used to set up for later unrolling the loop.
                     */
                    quarter_sfb_width = (*pWinSfbTop - sfb_offset) >> 2;

                    /*
                     * Adjust pred_q_format and predicted_spectral() to
                     * maximum resolution.
                     */
                    max = 0;
                    pPredicted_spectral = pPredicted_offset;

                    /* Find the maximum absolute value */
                    for (j = (*pWinSfbTop - sfb_offset); j > 0 ; j--)
                    {
                        tmpInt32 = *(pPredicted_spectral++);


                        /*
                         * Note: overflow is protected here even though
                         * tmpInt32 = 0x80000000 is very rare case.
                         *
                         *  if (tmpInt32 == LONG_MIN)
                         *  {
                         *      tmpInt32 = LONG_MAX;
                         *  }
                         *  if (tmpInt32 < 0)
                         *  {
                         *      tmpInt32 = -tmpInt32;
                         *  }
                         */

                        max |= tmpInt32 ^(tmpInt32 >> 31);
                    }

                    if (max != 0)
                    {
                        /* Find the number of bits to reach
                         * the max resolution
                         */
                        tmpInt = 0;

                        while (max < 0x40000000L)
                        {
                            max <<= 1;
                            tmpInt++;
                        }
                        /*
                         * The following codes are combined into shift factor
                         * adjusting and reconstruction section.
                         *
                         * pPredicted_spectral = pPredicted_offset;
                         * for(j = quarter_sfb_width; j>0 ; j--)
                         * {
                         *      *(pPredicted_spectral++) <<= tmpInt;
                         *      *(pPredicted_spectral++) <<= tmpInt;
                         *      *(pPredicted_spectral++) <<= tmpInt;
                         *      *(pPredicted_spectral++) <<= tmpInt;
                         * }
                         *
                         */

                        /* Adjust Q format for predicted_spectral() */
                        adjusted_pred_q = pred_q_format + tmpInt;

                        /*
                         * Adjust Q format to prevent overflow that may occur
                         * during frequency domain reconstruction.
                         */
                        pPredicted_spectral = pPredicted_offset;

                        shift_factor = *(pQ_format) - adjusted_pred_q;

                        if ((shift_factor >= 0) && (shift_factor < 31))
                        {
                            shift_factor = shift_factor + 1;

                            pred_shift = tmpInt - 1;

                            if (pred_shift >= 0)
                            {
                                for (j = quarter_sfb_width; j > 0 ; j--)
                                {
                                    temp = *pCurrent_frame >> shift_factor;
                                    *(pCurrent_frame++) = temp
                                                          + (*(pPredicted_spectral++) << pred_shift);
                                    temp = *pCurrent_frame >> shift_factor;
                                    *(pCurrent_frame++) = temp
                                                          + (*(pPredicted_spectral++) << pred_shift);
                                    temp = *pCurrent_frame >> shift_factor;
                                    *(pCurrent_frame++) = temp
                                                          + (*(pPredicted_spectral++) << pred_shift);
                                    temp = *pCurrent_frame >> shift_factor;
                                    *(pCurrent_frame++) = temp
                                                          + (*(pPredicted_spectral++) << pred_shift);

                                }
                            }
                            else
                            {
                                for (j = quarter_sfb_width; j > 0 ; j--)
                                {
                                    temp = *pCurrent_frame >> shift_factor;
                                    *(pCurrent_frame++) = temp
                                                          + (*(pPredicted_spectral++) >> 1);
                                    temp = *pCurrent_frame >> shift_factor;
                                    *(pCurrent_frame++) = temp
                                                          + (*(pPredicted_spectral++) >> 1);
                                    temp = *pCurrent_frame >> shift_factor;
                                    *(pCurrent_frame++) = temp
                                                          + (*(pPredicted_spectral++) >> 1);
                                    temp = *pCurrent_frame >> shift_factor;
                                    *(pCurrent_frame++) = temp
                                                          + (*(pPredicted_spectral++) >> 1);
                                }
                            }

                            /* Updated new Q format for current scalefactor band*/
                            *(pQ_format) = adjusted_pred_q - 1;
                        }
                        else if (shift_factor >= 31)
                        {
                            for (j = quarter_sfb_width; j > 0 ; j--)
                            {
                                *(pCurrent_frame++) =
                                    *(pPredicted_spectral++) << tmpInt;
                                *(pCurrent_frame++) =
                                    *(pPredicted_spectral++) << tmpInt;
                                *(pCurrent_frame++) =
                                    *(pPredicted_spectral++) << tmpInt;
                                *(pCurrent_frame++) =
                                    *(pPredicted_spectral++) << tmpInt;
                            }

                            /* Updated new Q format for current scalefactor band*/
                            *(pQ_format) = adjusted_pred_q;
                        }
                        else if ((shift_factor < 0) && (shift_factor > -31))
                        {
                            shift_factor = 1 - shift_factor;

                            pred_shift = tmpInt - shift_factor;

                            if (pred_shift >= 0)
                            {
                                for (j = quarter_sfb_width; j > 0 ; j--)
                                {
                                    temp = *pCurrent_frame >> 1;
                                    *(pCurrent_frame++) =  temp +
                                                           (*(pPredicted_spectral++) << pred_shift);
                                    temp = *pCurrent_frame >> 1;
                                    *(pCurrent_frame++) =  temp +
                                                           (*(pPredicted_spectral++) << pred_shift);
                                    temp = *pCurrent_frame >> 1;
                                    *(pCurrent_frame++) =  temp +
                                                           (*(pPredicted_spectral++) << pred_shift);
                                    temp = *pCurrent_frame >> 1;
                                    *(pCurrent_frame++) =  temp +
                                                           (*(pPredicted_spectral++) << pred_shift);

                                }
                            }
                            else
                            {
                                pred_shift = -pred_shift;

                                for (j = quarter_sfb_width; j > 0 ; j--)
                                {
                                    temp = *pCurrent_frame >> 1;
                                    *(pCurrent_frame++) =  temp +
                                                           (*(pPredicted_spectral++) >> pred_shift);
                                    temp = *pCurrent_frame >> 1;
                                    *(pCurrent_frame++) =  temp +
                                                           (*(pPredicted_spectral++) >> pred_shift);
                                    temp = *pCurrent_frame >> 1;
                                    *(pCurrent_frame++) =  temp +
                                                           (*(pPredicted_spectral++) >> pred_shift);
                                    temp = *pCurrent_frame >> 1;
                                    *(pCurrent_frame++) =  temp +
                                                           (*(pPredicted_spectral++) >> pred_shift);
                                }
                            }

                            /* Updated new Q format for current scalefactor band*/
                            *(pQ_format) = *(pQ_format) - 1;
                        }

                        /*
                         * For case (shift_factor <= -31), *pCurrent_frame and
                         * *pQ_format do not need to be updated.
                         */

                    } /* if (max != 0) */

                    /* Updated to next scalefactor band. */
                    sfb_offset = *(pWinSfbTop++);

                    /* Updated pointer to next scalefactor band's Q-format */
                    pQ_format++;

                } /* for (i = reconstruct_sfb_num; i > 0; i--) */

            } /* if (win_prediction_used[wnd] != FALSE) */

            /* Updated to next window */
            pPredicted_spectral_start += coef_per_win;
            pCurrent_frame_start += coef_per_win;
            pQ_format_start += num_sfb;

        } /* for (wnd=0; wnd<short_window_num; wnd++) */

    } /* else */

    /*----------------------------------------------------------------------------
    ; Return nothing or data or data pointer
    ----------------------------------------------------------------------------*/
    return;
} /* long_term_synthesis */


