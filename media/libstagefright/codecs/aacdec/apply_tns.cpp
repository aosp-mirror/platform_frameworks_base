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

 Pathname: apply_tns.c

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    coef =       Array of input coefficients.
                 [Int32 *, length 1024]

    q_format   = Array of q-formats, one per scalefactor band, for the
                 entire frame.  In the case of tns_inv_filter, only the
                 first element is used, since the input to tns_inv_filter
                 is all of the same q-format.
                 [Int * const, length MAX_SFB]

    pFrameInfo = Pointer to structure that holds information about each group.
                 (long block flag, number of windows, scalefactor bands
                  per group, etc.)
                 [const FrameInfo * const]

    pTNS_frame_info = pointer to structure containing the details on each
                      TNS filter (order, filter coefficients,
                      coefficient res., etc.)
                      [TNS_frame_info * const]

    inverse_flag   = TRUE  if inverse filter is to be applied.
                     FALSE if forward filter is to be applied.
                     [Bool]

    scratch_Int_buffer = Pointer to scratch memory to store the
                           filter's state memory.  Used by both
                           tns_inv_filter.
                           [Int *, length TNS_MAX_ORDER]

 Local Stores/Buffers/Pointers Needed:
    None

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    None

 Pointers and Buffers Modified:
    coef[]   = TNS altered data.
    q_format = q-formats in TNS scalefactor bands may be modified.

 Local Stores Modified:
    None

 Global Stores Modified:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    This function applies either the TNS forward or TNS inverse filter, based
    on inverse_flag being FALSE or TRUE, respectively.

    For the TNS forward filter, the data fed into tns_ar_filter is normalized
    all to the same q-format.

------------------------------------------------------------------------------
 REQUIREMENTS

    The input, coef, should use all 32-bits, else the scaling by tns_ar_filter
    may eliminate the data.

------------------------------------------------------------------------------
 REFERENCES

 (1) ISO/IEC 14496-3:1999(E)
     Part 3
        Subpart 4.6.8 (Temporal Noise Shaping)

------------------------------------------------------------------------------
 PSEUDO-CODE

    NO PSEUDO-CODE

------------------------------------------------------------------------------
 RESOURCES USED
   When the code is written for a specific target processor
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
#include "s_tns_frame_info.h"
#include "s_tnsfilt.h"
#include "s_frameinfo.h"
#include "tns_inv_filter.h"
#include "tns_ar_filter.h"
#include "apply_tns.h"

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

void apply_tns(
    Int32                  coef[],
    Int                    q_format[],
    const FrameInfo      * const pFrameInfo,
    TNS_frame_info * const pTNS_frame_info,
    const Bool                   inverse_flag,
    Int32                  scratch_Int_buffer[])
{
    Int num_tns_bands;
    Int num_TNS_coef;

    Int f;

    Int tempInt;
    Int tempInt2;

    Int sfb_per_win;
    Int sfbWidth;

    Int coef_per_win;
    Int min_q;
    Int win;

    Int32 *pCoef = coef;
    Int32 *pTempCoef;

    Int   *pStartQformat = q_format;

    Int   *pQformat;
    Int32 *pLpcCoef;

    Int sfb_offset;

    const Int16 *pWinSfbTop;

    TNSfilt *pFilt;

    coef_per_win = pFrameInfo->coef_per_win[0];
    sfb_per_win  = pFrameInfo->sfb_per_win[0];

    win = 0;

    pLpcCoef = pTNS_frame_info->lpc_coef;

    pFilt = pTNS_frame_info->filt;

    do
    {
        for (f = pTNS_frame_info->n_filt[win]; f > 0; f--)
        {
            /* Skip to the next filter if the order is 0 */
            tempInt = pFilt->order;

            if (tempInt > 0)
            {
                /*
                 * Do not call tns_ar_filter or tns_inv_filter
                 * if the difference
                 * between start_coef and stop_stop is <= 0.
                 *
                 */
                num_TNS_coef = (pFilt->stop_coef - pFilt->start_coef);

                if (num_TNS_coef > 0)
                {
                    if (inverse_flag != FALSE)
                    {
                        tns_inv_filter(
                            &(pCoef[pFilt->start_coef]),
                            num_TNS_coef,
                            pFilt->direction,
                            pLpcCoef,
                            pFilt->q_lpc,
                            pFilt->order,
                            scratch_Int_buffer);
                    }
                    else
                    {
                        num_tns_bands = (pFilt->stop_band - pFilt->start_band);

                        /*
                         * pQformat is initialized only once.
                         *
                         * Here is how TNS is applied on scalefactor bands
                         *
                         * [0][1][2][3][4][5][6][7][8]
                         *  |                        \
                         * start_band               stop_band
                         *
                         * In this example, TNS would be applied to 8
                         * scalefactor bands, 0-7.
                         *
                         * pQformat is initially set to &(pStartQformat[8])
                         *
                         * 1st LOOP
                         *      Entry: pQformat = &(pStartQformat[8])
                         *
                         *      pQformat is pre-decremented 8 times in the
                         *      search for min_q
                         *
                         *      Exit:  pQformat = &(pStartQformat[0])
                         *
                         * 2nd LOOP
                         *      Entry: pQformat = &(pStartQformat[0])
                         *
                         *      pQformat is post-incremented 8 times in the
                         *      normalization of the data loop.
                         *
                         *      Exit:  pQformat = &(pStartQformat[8]
                         *
                         *
                         * shift_amt = tns_ar_filter(...)
                         *
                         * 3rd LOOP
                         *      Entry: pQformat = &(pStartQformat[8])
                         *
                         *      pQformat is pre-decremented 8 times in the
                         *      adjustment of the q-format to min_q - shift_amt
                         *
                         *      Exit:  pQformat = &(pStartQformat[0])
                         *
                         */

                        pQformat =
                            &(pStartQformat[pFilt->stop_band]);

                        /*
                         * Scan the array of q-formats and find the minimum over
                         * the range where the filter is to be applied.
                         *
                         * At the end of this scan,
                         * pQformat = &(q-format[pFilt->start_band]);
                         *
                         */

                        min_q = INT16_MAX;

                        for (tempInt = num_tns_bands; tempInt > 0; tempInt--)
                        {
                            tempInt2 = *(--pQformat);

                            if (tempInt2 < min_q)
                            {
                                min_q = tempInt2;
                            }
                        } /* for(tempInt = num_bands; tempInt > 0; tempInt--)*/

                        /*
                         * Set up the pointers so we can index into coef[]
                         * on a scalefactor band basis.
                         */
                        tempInt = pFilt->start_band;

                        tempInt--;

                        /* Initialize sfb_offset and pWinSfbTop */
                        if (tempInt >= 0)
                        {
                            pWinSfbTop =
                                &(pFrameInfo->win_sfb_top[win][tempInt]);

                            sfb_offset = *(pWinSfbTop++);
                        }
                        else
                        {
                            pWinSfbTop = pFrameInfo->win_sfb_top[win];
                            sfb_offset = 0;
                        }

                        pTempCoef = pCoef + pFilt->start_coef;

                        /* Scale the data in the TNS bands to min_q q-format */
                        for (tempInt = num_tns_bands; tempInt > 0; tempInt--)
                        {
                            sfbWidth  = *(pWinSfbTop++) - sfb_offset;

                            sfb_offset += sfbWidth;

                            tempInt2 = *(pQformat++) - min_q;

                            /*
                             * This should zero out the data in one scalefactor
                             * band if it is so much less than the neighboring
                             * scalefactor bands.
                             *
                             * The only way this "should" happen is if one
                             * scalefactor band contains zero data.
                             *
                             * Zero data can be of any q-format, but we always
                             * set it very high to avoid the zero-data band being
                             * picked as the one to normalize to in the scan for
                             * min_q.
                             *
                             */
                            if (tempInt2 > 31)
                            {
                                tempInt2 = 31;
                            }

                            for (sfbWidth >>= 2; sfbWidth > 0; sfbWidth--)
                            {
                                *(pTempCoef++) >>= tempInt2;
                                *(pTempCoef++) >>= tempInt2;
                                *(pTempCoef++) >>= tempInt2;
                                *(pTempCoef++) >>= tempInt2;
                            }

                        } /* for(tempInt = num_bands; tempInt > 0; tempInt--)*/

                        tempInt2 =
                            tns_ar_filter(
                                &(pCoef[pFilt->start_coef]),
                                num_TNS_coef,
                                pFilt->direction,
                                pLpcCoef,
                                pFilt->q_lpc,
                                pFilt->order);

                        /*
                         * Update the q-format for all the scalefactor bands
                         * taking into account the adjustment caused by
                         * tns_ar_filter
                         */

                        min_q -= tempInt2;

                        for (tempInt = num_tns_bands; tempInt > 0; tempInt--)
                        {
                            *(--pQformat) = min_q;
                        }

                    } /* if (inverse_flag != FALSE) */

                } /* if (num_TNS_coef > 0) */

                pLpcCoef += pFilt->order;

            } /* if (tempInt > 0) */

            pFilt++;

        } /* for (f = pTNSinfo->n_filt; f > 0; f--) */

        pCoef += coef_per_win;
        pStartQformat += sfb_per_win;

        win++;

    }
    while (win < pFrameInfo->num_win);

    return;

} /* apply_tns() */
