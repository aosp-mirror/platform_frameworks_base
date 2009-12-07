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

 Pathname: get_tns.c

     Date: 10/25/2000

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:  Modified from original shareware code

 Description:  Modified to pass variables by reference to eliminate use
               of global variables.

 Description:  Brought code in-line with PV standards.  Some minor
               optimizations (count-down for loops, etc.) were made.

 Description:  Made cosmetic changes as suggested during review.  Also,
 changed calculation of s_mask and n_mask from table-based to being
 calculated based on res_index.  Also, the flag coef_res was changed
 from having a range of [3,4] to having a range of [0,1], which corresponds
 exactly with the true value that is passed via the bitstream.

 Description:  Modified to use more efficient TNS memory structure.

 Description: Updated to reflect more efficient usage of memory by the TNS
 filters.

 Description: Updated the SW template to include the full pathname to the
 source file and a slightly modified copyright header.

 Description: Moved pInputStream to be the 2nd parameter, for a slight
 optimization on some platforms.

 Description: Moved pSfbTop outside of the loops, since its value does
 not change.

 Description: Replace some instances of getbits to get1bits
              when only 1 bit is read.

 Who:                       Date:
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    FrameInfo *pFrameInfo
        Pointer to structure that holds information about each block.
        (long block flag,
         number of subblocks,
         scalefactor bands per subblock, etc.)

    BITS *pInputStream
        Pointer to a BITS structure that is
        passed on to function getbits to pull information from the bitstream.

    TNS_Frame_info *pTnsFrameInfo
        Pointer to filter data structure - to be populated by this function.

 Local Stores/Buffers/Pointers Needed:
    None

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    None

 Pointers and Buffers Modified:
    TNS_frame_info *pTnsFrameInfo

    pTnsFrameInfo->n_filt = Number of tns filters to be applied to the data.

    pTnsFrameInfo->filt[]->order = The order of each individual TNS filter.

    pTnsFrameInfo->filt[]->coef_res = The resolution of the filter coefficients

    pTnsFrameInfo->filt[]->start_band = start of spectral band

    pTnsFrameInfo->filt[]->stop_band = end of spectral band

    pTnsFrameInfo->filt[]->coef[] = Each filter's coefficients are filled with
    data read from the input bitstream.

    pTnsFrameInfo->filt[]->direction = A flag is set for each TNS filter.

    If the direction flag (on the bitstream) = 0, then the filter
    is applied to the block of spectral data in normal (upward) fashion.

    If the direction flag (on the bitstream) = 1, then the filter
    is applied in a reverse (downward) fashion.
    (Starting with the last element in the block of data.)

    The value stored in filt[]->direction maps the values [0,1] to [1,-1] for
    a more intuitive storage of this flag's meaning.

 Local Stores Modified:

 Global Stores Modified:


------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function reads the TNS filter information from the bitstream, and stores
 the filter order, LPC coefficients, and the number of TNS filters to
 be applied in the structure TNS_frame_info.

------------------------------------------------------------------------------
 REQUIREMENTS

 This code should match the ISO code in functionality, with the exception
 that coef_res has range of [0,1] (PV code) instead of [3,4] (ISO code)

 coef_res is only used by tns_decode_coef.

------------------------------------------------------------------------------
 REFERENCES
 (1) ISO/IEC 14496-3:1999(E)
     Part 3
        Subpart 4.6.8 (Temporal Noise Shaping)

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

----------------------------------------------------------------------------*/
/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include "pv_audio_type_defs.h"
#include "get_tns.h"
#include "s_mc_info.h"
#include "s_frameinfo.h"
#include "s_tnsfilt.h"
#include "s_tns_frame_info.h"
#include "s_bits.h"
#include "ibstream.h"
#include "e_window_sequence.h"
#include "e_progconfigconst.h"

#include "tns_decode_coef.h"


/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/
#define SCALE_FACTOR_BAND_OFFSET(x) ( ((x) > 0) ? pSFB_top[(x)-1] : 0 )
#define MINIMUM(x,y) ( ((x) < (y)) ? (x) : (y) )

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
 * The entries in the ensuing tables provide the maximum permissable
 * number of scalefactor bands for each TNS filter.  This value is effected
 * by the sampling rate, and window type.
 */

const Int tns_max_bands_tbl_long_wndw[(1<<LEN_SAMP_IDX)] =
    {31,       /* 96000 Hz */
     31,       /* 88200 Hz */
     34,       /* 64000 Hz */
     40,       /* 48000 Hz */
     42,       /* 44100 Hz */
     51,       /* 32000 Hz */
     46,       /* 24000 Hz */
     46,       /* 22050 Hz */
     42,       /* 16000 Hz */
     42,       /* 12000 Hz */
     42,       /* 11025 Hz */
     39,       /* 8000  Hz */
     0,
     0,
     0,
     0
    };

const Int tns_max_bands_tbl_short_wndw[(1<<LEN_SAMP_IDX)] =
    {9,       /* 96000 Hz */
     9,       /* 88200 Hz */
     10,       /* 64000 Hz */
     14,       /* 48000 Hz */
     14,       /* 44100 Hz */
     14,       /* 32000 Hz */
     14,       /* 24000 Hz */
     14,       /* 22050 Hz */
     14,       /* 16000 Hz */
     14,       /* 12000 Hz */
     14,       /* 11025 Hz */
     14,       /* 8000  Hz */
     0,
     0,
     0,
     0
    };

/*
 * For completeness, here are the table entries for object types that make
 * use of PQF filter bank.  We do not currently support this; these are
 * given here only to ease future implementation.
 *
 *  const Int tns_max_bands_tbl_long_wndw_PQF[(1<<LEN_SAMP_IDX)] =
 *         {28,       ; 96000
 *          28,       ; 88200
 *          27,       ; 64000
 *          26,       ; 48000
 *          26,       ; 44100
 *          26,       ; 32000
 *          29,       ; 24000
 *          29,       ; 22050
 *          23,       ; 16000
 *          23,       ; 12000
 *          23,       ; 11025
 *          19,       ; 8000
 *           0,
 *           0,
 *           0,
 *           0};
 *
 *  const Int tns_max_bands_tbl_short_wndw_PQF[(1<<LEN_SAMP_IDX)] =
 *         {7,       ; 96000
 *          7,       ; 88200
 *          7,       ; 64000
 *          6,       ; 48000
 *          6,       ; 44100
 *          6,       ; 32000
 *          7,       ; 24000
 *          7,       ; 22050
 *          8,       ; 16000
 *          8,       ; 12000
 *          8,       ; 11025
 *          7,       ; 8000
 *          0,
 *          0,
 *          0,
 *          0};
 */

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


void get_tns(
    const Int               max_bands,
    BITS            * const pInputStream,
    const WINDOW_SEQUENCE   wnd_seq,
    const FrameInfo * const pFrameInfo,
    const MC_Info   * const pMC_Info,
    TNS_frame_info  * const pTnsFrameInfo,
    Int32                   scratchTnsDecCoefMem[])
{

    const Int16 * const pSFB_top = pFrameInfo->win_sfb_top[0];

    Int f;
    Int t;
    Int win;
    UInt tempInt;

    Int num_filt_bits;
    Int num_order_bits;
    Int num_start_band_bits;

    Int top;
    Int res;
    Int res_index;
    Int compress;

    Int sfb_per_win;

    Int32 *pLpcCoef;
    Int32 *pStartLpcCoef;
    Int s_mask;
    Int n_mask;

    Int tns_bands;
    UInt max_order;
    Int coef_res;


    TNSfilt *pFilt;

    if (wnd_seq != EIGHT_SHORT_SEQUENCE)
    {
        num_filt_bits  = 2;
        num_order_bits = 5;
        num_start_band_bits = 6;

        tns_bands = tns_max_bands_tbl_long_wndw[pMC_Info->sampling_rate_idx];

        /*
         *  Definition from 14496-3:1999 doc. Our first encoder follows this rule,
         *  later encoders don't
         */

        if (pMC_Info->sampling_rate_idx > 4)  /* if (sampling_rate <= 32000 */
        {
            max_order = 20;
        }
        else
        {
            max_order = 12;
        }
    }
    else
    {
        num_filt_bits  = 1;
        num_order_bits = 3;
        num_start_band_bits = 4;

        tns_bands = tns_max_bands_tbl_short_wndw[pMC_Info->sampling_rate_idx];

        max_order = 7;
    }

    /*
     * After this branch, tns_bands will be equal to the minimum of
     * the passed in variable, nbands, and the result from the
     * tns_max_bands_tbl
     */

    if (max_bands < tns_bands)
    {
        tns_bands = max_bands;
    }

    sfb_per_win = pFrameInfo->sfb_per_win[0];

    win = 0;

    pLpcCoef = pTnsFrameInfo->lpc_coef;

    pFilt = pTnsFrameInfo->filt;

    do
    {
        tempInt = get9_n_lessbits(num_filt_bits,
                                  pInputStream);

        pTnsFrameInfo->n_filt[win] = tempInt;

        if (tempInt != 0)
        {
            /*
             * coef_res = [0, 1]
             * Switch between a resolution of 3 and 4 bits respectively
             *
             * if coef_res = 0, the coefficients have a range of
             *
             *                 -4  -3  -2  -1  0   1   2   3
             *
             * if coef_res = 1, the coefficients have a range of
             *
             * -8  -7  -6  -5  -4  -3  -2  -1  0   1   2   3   4   5   6   7
             *
             * The arrays in ./src/tns_tab.c are completely based on
             * the value of coef_res.
             */
            res = get1bits(
                      pInputStream);

            /* res is post-incremented for correct calculation of res_index */
            coef_res = res++;

            top = sfb_per_win;

            for (f = pTnsFrameInfo->n_filt[win]; f > 0; f--)
            {
                tempInt = MINIMUM(top, tns_bands);

                pFilt->stop_coef = SCALE_FACTOR_BAND_OFFSET(tempInt);

                pFilt->stop_band = tempInt;

                top -= get9_n_lessbits(num_start_band_bits,
                                       pInputStream);

                tempInt = MINIMUM(top, tns_bands);

                pFilt->start_coef = SCALE_FACTOR_BAND_OFFSET(tempInt);

                pFilt->start_band = tempInt;

                tempInt = get9_n_lessbits(num_order_bits,
                                          pInputStream);

                pFilt->order = tempInt;

                if (tempInt != 0)
                {
                    if (tempInt > max_order)
                    {
                        pFilt->order = max_order;
                    }

                    /*
                     * This maps the bitstream's [0,1] to
                     * pFilt->direction = [1,-1]
                     */

                    tempInt = get1bits(pInputStream);

                    pFilt->direction = (-(Int)tempInt) | 0x1;

                    /*
                     * compress = [0,1]
                     * If compress is true, the MSB has
                     * been omitted from transmission (Ref. 1)
                     *
                     * For coef_res = 0, this limits the range of
                     * transmitted coefficients to...
                     *
                     *         -2  -1  0   1
                     *
                     * For coef_res = 1, the coefficients have
                     * a range of...
                     *
                     * -4  -3  -2  -1  0   1   2   3
                     */
                    compress = get1bits(pInputStream);

                    /*
                     * res has a range of [1,2]
                     * compress has a range of [0,1]
                     * So (res - compress) has range [0,2];
                     */
                    res_index = res - compress;

                    s_mask =  2 << res_index;

                    /*
                     * If res_index = 0, grab 2 bits of data
                     * If res_index = 1, grab 3 bits of data
                     * If res_index = 2, grab 4 bits of data
                     */
                    res_index += 2;

                    pStartLpcCoef = pLpcCoef;

                    for (t = pFilt->order; t > 0; t--)
                    {
                        /*
                         * These are the encoded coefficients, which will
                         * later be decoded into LPC coefficients by
                         * the function tns_decode_coef()
                         */
                        tempInt = get9_n_lessbits(res_index,
                                                  pInputStream);

                        n_mask  = -((Int)tempInt & s_mask);

                        /*
                         * n_mask is used to sign_extend the
                         * value, if it is negative.
                         *
                         */
                        *(pLpcCoef++) = tempInt | n_mask;
                    }

                    /* Decode the TNS coefficients */

                    tempInt = pFilt->stop_coef - pFilt->start_coef;

                    if (tempInt > 0)
                    {
                        pFilt->q_lpc =
                            tns_decode_coef(
                                pFilt->order,
                                coef_res,
                                pStartLpcCoef,
                                scratchTnsDecCoefMem);
                    }

                } /* if (pTnsFilt->order != 0) */

                pFilt++;

            } /* END for (f=pTnsInfo->n_filt; f>0; f--, pTnsFilt++) */

        } /* if (pTnsInfo->n_filt != 0) */

        win++;

    }
    while (win < pFrameInfo->num_win);

    return;

} /* get_tns */
