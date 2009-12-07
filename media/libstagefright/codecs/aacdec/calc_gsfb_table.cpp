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

 Pathname: ./src/calc_gsfb_table.c

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:  Modified from original shareware code

 Description: (1) Modified to bring in-line with PV standards
              (2) Removed if(pFrameInfo->islong), only short windows will
                  call this routine from getics.c

 Description: Modified per review comments

 Who:                       Date:
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    pFrameInfo  = pointer to structure that holds information for current
                  frame. Data type FrameInfo

    group[]     = array that contains the grouping information of short
                  windows (stop index of windows in each group).
                  Data type Int

 Local Stores/Buffers/Pointers Needed:
    None

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    None

 Pointers and Buffers Modified:
    pFrameInfo -> frame_sfb_top   contains the cumulative bandwidth of
                                    scalefactor bands in each group

 Local Stores Modified:
    None

 Global Stores Modified:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function is only invoked when short windows are present. It calculates
 the number of groups in one frame, and the scalefactor bandwidth of each
 scalefactor band in each group.
 All windows within one group share the same scalefactors and are interleaved
 on a scalefactor band basis. Within each group, the actual length of one
 scalefactor band equals to the number of windows times the number of
 coefficients in a regular scalefactor band.

------------------------------------------------------------------------------
 REQUIREMENTS

 This function shall replace the contents of pFrameInfo->frame_sfb_top
 with the cumulative bandwidth of each scalefactor band in each group

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
    Subpart 4       p54.    4.5.2.3.2   decoding process

------------------------------------------------------------------------------
 PSEUDO-CODE

    offset      = 0;
    group_idx   = 0;

    DO
        pFrameInfo->group_len[group_idx] = group[group_idx] - offset;
        offset = group[group_idx];
        group_idx++;

    WHILE (offset < NUM_SHORT_WINDOWS);


    pFrameInfo->num_groups = group_idx;

    pFrameSfbTop = pFrameInfo->frame_sfb_top;
    offset = 0;

    FOR (group_idx = 0; group_idx < pFrameInfo->num_groups; group_idx++)

        len = pFrameInfo->group_len[group_idx];

        FOR (sfb = 0; sfb < pFrameInfo->sfb_per_win[group_idx]; sfb++)

            offset += pFrameInfo->sfb_width_128[sfb] * len;
            *pFrameSfbTop++ = offset;

        ENDFOR

    ENDFOR

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
#include    "pv_audio_type_defs.h"
#include    "huffman.h"
#include    "aac_mem_funcs.h"

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
void  calc_gsfb_table(
    FrameInfo   *pFrameInfo,
    Int         group[])
{

    Int      group_idx;
    Int      offset;
    Int     *pFrameSfbTop;
    Int     *pSfbWidth128;
    Int      sfb;
    Int      nsfb;
    Int      len;
    Int      ngroups;

    /* clear out the default values set by infoinit */
    /* */
    pv_memset(pFrameInfo->frame_sfb_top,
              0,
              MAXBANDS*sizeof(pFrameInfo->frame_sfb_top[0]));
    /* */
    /* first calculate the group length*/
    offset      = 0;
    ngroups     = 0;
    do
    {
        pFrameInfo->group_len[ngroups] = group[ngroups] - offset;
        offset = group[ngroups];
        ngroups++;

    }
    while (offset < NUM_SHORT_WINDOWS);


    /* calculate the cumulative scalefactor bandwidth for one frame */
    pFrameInfo->num_groups = ngroups;

    pFrameSfbTop = pFrameInfo->frame_sfb_top;
    offset = 0;


    for (group_idx = 0; group_idx < ngroups; group_idx++)
    {
        len  = pFrameInfo->group_len[  group_idx];
        nsfb = pFrameInfo->sfb_per_win[group_idx];

        pSfbWidth128 = pFrameInfo->sfb_width_128;

        for (sfb = nsfb; sfb > 0; sfb--)
        {
            offset += *pSfbWidth128++ * len;
            *pFrameSfbTop++ = offset;
        }
    }


} /* calc_gsfb_table */

