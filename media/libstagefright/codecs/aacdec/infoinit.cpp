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

 Pathname: infoinit.c


------------------------------------------------------------------------------
 REVISION HISTORY

 Description:  Modified from original shareware code

 Description:  Modified to pass variables by reference to eliminate use
               of global variables.

 Description:  Pass eight_short_info and the array 'sfbwidth128'.
               Change function arguments' names for clarity

 Description:  move sfb definitions to "sfb.h", and "sfb.c", eliminated
               the function "huffbookinit.c"

 Description:  Remove initialization of the never used array,
               pFrameInfo->group_offs

 Description:
 (1) Changed "stdinc.h" to <stdlib.h> - this avoids linking in the math
 library and stdio.h.  (All for just defining the NULL pointer macro)

 (2) Updated copyright header.

 Description: Updated the SW template to include the full pathname to the
 source file and a slightly modified copyright header.

 Description: Addresses of constant vectors are now found by means of a
              switch statement, this solve linking problem when using the
              /ropi option (Read-only position independent) for some
              compilers

 Who:                               Date:
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    pSi              = pointer to sampling rate info
    ppWin_seq_info   = pointer array to window sequence Info struct
    pSfbwidth128     = pointer to sfb bandwidth array of short window

 Local Stores/Buffers/Pointers Needed:

 Global Stores/Buffers/Pointers Needed:

 Outputs:

 Pointers and Buffers Modified:

    ppWin_seq_info[ONLY_LONG_WINDOW]{all structure members} = setup values
    ppWin_seq_info[EIGHT_SHORT_WINDOW]{all structure members} = setup values

 Local Stores Modified:

 Global Stores Modified:


------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function sets the values of 'Info' structure for blocks containing long
 and short window sequences, the following structures are being set:

 win_seq_info[ONLY_LONG_WINDOW], win_seq_info[EIGHT_SHORT_WINDOW],
 only_long_info and eight_short_info

------------------------------------------------------------------------------
 REQUIREMENTS

------------------------------------------------------------------------------
 REFERENCES

 (1) MPEG-2 NBC Audio Decoder
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

 (2) ISO/IEC 14496-3: 1999(E)
    Subpart 4       p66     (sfb tables)
                    p111    (4.6.10)
                    p200    (Annex 4.B.5)
------------------------------------------------------------------------------
 PSEUDO-CODE

    pFrameInfo  =   pointer to only_long_info;
    win_seq_info[ONLY_LONG_WINDOW]  =   pFrameInfo;
    pFrameInfo{all structure members} = setup values;


    pFrameInfo  =   pointer to eight_short_info;
    win_seq_info[EIGHT_SHORT_WINDOW]  =   pFrameInfo;
    pFrameInfo{all structure.members} =   setup values;


    FOR (window_seq = 0; window_seq < NUM_WIN_SEQ; win_seq++)

        win_seq_info[window_seq].members = setup values;

    ENDFOR

------------------------------------------------------------------------------
 RESOURCES USED
   When the code is written for a specific target processor the
     the resources used should be documented below.

 STACK USAGE:

 DATA MEMORY USED: x words

 PROGRAM MEMORY USED: x words

 CLOCK CYCLES:

------------------------------------------------------------------------------
*/



/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include    "pv_audio_type_defs.h"
#include    "s_sr_info.h"
#include    "s_frameinfo.h"
#include    "e_blockswitching.h"
#include    "e_huffmanconst.h"
#include    "sfb.h"
#include    "huffman.h"

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

Int infoinit(
    const Int samp_rate_idx,
    FrameInfo   **ppWin_seq_info,
    Int    *pSfbwidth128)

{
    /*----------------------------------------------------------------------------
    ; Define all local variables
    ----------------------------------------------------------------------------*/

    Int     i;
    Int     sfb_idx, sfb_sbk;
    Int     bins_sbk;
    Int     win_seq;
    Int     start_idx, end_idx;
    Int     nsfb_short;
    Int16   *sfbands;
    FrameInfo    *pFrameInfo;

    const SR_Info *pSi = &(samp_rate_info[samp_rate_idx]);

    const Int16 * pt_SFbands1024 = NULL;
    const Int16 * pt_SFbands128  = NULL;

    /*----------------------------------------------------------------------------
    ; Function body here
    ----------------------------------------------------------------------------*/

    switch (pSi->samp_rate)
    {
        case 96000:
        case 88200:
            pt_SFbands1024  = sfb_96_1024;
            pt_SFbands128   = sfb_64_128;  /* equal to table sfb_96_128, (eliminated) */
            break;
        case 64000:
            pt_SFbands1024  = sfb_64_1024;
            pt_SFbands128   = sfb_64_128;
            break;
        case 48000:
        case 44100:
            pt_SFbands1024  = sfb_48_1024;
            pt_SFbands128   = sfb_48_128;
            break;
        case 32000:
            pt_SFbands1024  = sfb_32_1024;
            pt_SFbands128   = sfb_48_128;
            break;
        case 24000:
        case 22050:
            pt_SFbands1024  = sfb_24_1024;
            pt_SFbands128   = sfb_24_128;
            break;
        case 16000:
        case 12000:
        case 11025:
            pt_SFbands1024  = sfb_16_1024;
            pt_SFbands128   = sfb_16_128;
            break;
        case 8000:
            pt_SFbands1024  = sfb_8_1024;
            pt_SFbands128   = sfb_8_128;
            break;
        default:
            // sampling rate not supported
            return -1;
    }

    /* long block info */

    pFrameInfo = ppWin_seq_info[ONLY_LONG_WINDOW];
    pFrameInfo->islong               = 1;
    pFrameInfo->num_win              = 1;
    pFrameInfo->coef_per_frame       = LN2; /* = 1024 */

    pFrameInfo->sfb_per_win[0]  = pSi->nsfb1024;
    pFrameInfo->sectbits[0]     = LONG_SECT_BITS;
    pFrameInfo->win_sfb_top[0]  = (Int16 *)pt_SFbands1024;

    pFrameInfo->sfb_width_128 = NULL; /* no short block sfb */
    pFrameInfo->num_groups    = 1; /* long block, one group */
    pFrameInfo->group_len[0]  = 1; /* only one window */

    /* short block info */
    pFrameInfo = ppWin_seq_info[EIGHT_SHORT_WINDOW];
    pFrameInfo->islong                  = 0;
    pFrameInfo->num_win                 = NSHORT;
    pFrameInfo->coef_per_frame          = LN2;

    for (i = 0; i < pFrameInfo->num_win; i++)
    {
        pFrameInfo->sfb_per_win[i] = pSi->nsfb128;
        pFrameInfo->sectbits[i]    = SHORT_SECT_BITS;
        pFrameInfo->win_sfb_top[i] = (Int16 *)pt_SFbands128;
    }

    /* construct sfb width table */
    pFrameInfo->sfb_width_128 = pSfbwidth128;
    for (i = 0, start_idx = 0, nsfb_short = pSi->nsfb128; i < nsfb_short; i++)
    {
        end_idx = pt_SFbands128[i];
        pSfbwidth128[i] = end_idx - start_idx;
        start_idx = end_idx;
    }


    /* common to long and short */
    for (win_seq = 0; win_seq < NUM_WIN_SEQ; win_seq++)
    {

        if (ppWin_seq_info[win_seq] != NULL)
        {
            pFrameInfo                 = ppWin_seq_info[win_seq];
            pFrameInfo->sfb_per_frame  = 0;
            sfb_sbk                    = 0;
            bins_sbk                   = 0;

            for (i = 0; i < pFrameInfo->num_win; i++)
            {

                /* compute coef_per_win */
                pFrameInfo->coef_per_win[i] =
                    pFrameInfo->coef_per_frame / pFrameInfo->num_win;

                /* compute sfb_per_frame */
                pFrameInfo->sfb_per_frame += pFrameInfo->sfb_per_win[i];

                /* construct default (non-interleaved) bk_sfb_top[] */
                sfbands = pFrameInfo->win_sfb_top[i];
                for (sfb_idx = 0; sfb_idx < pFrameInfo->sfb_per_win[i];
                        sfb_idx++)
                {
                    pFrameInfo->frame_sfb_top[sfb_idx+sfb_sbk] =
                        sfbands[sfb_idx] + bins_sbk;
                }

                bins_sbk += pFrameInfo->coef_per_win[i];
                sfb_sbk  += pFrameInfo->sfb_per_win[i];
            } /* for i = sbk ends */
        }

    } /* for win_seq ends */

    return SUCCESS;

} /* infoinit */
