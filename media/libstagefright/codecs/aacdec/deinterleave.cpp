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

 Pathname: ./src/deinterleave.c

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:  Modified from original shareware code

 Description: (1) Modified with new template, rename variables
              (2) Removed for-loop to calculate win_inc, win_inc = SN2 (128)
              (3) Replaced for-loop with memcpy
              (4) Converted Int16 -> Int

 Description: Modified per review comments

 Who:                       Date:
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    interleaved   = input array that contains interleaved coefficients
                    Data Type Int

    deinterleaved = output array that will be updated with de-interleaved
                    coefficients of input array. Data Type Int

    pFrameInfo = pointer to structure that holds information of current
                 frame. Data Type FrameInfo

 Local Stores/Buffers/Pointers Needed:
    None

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    None

 Pointers and Buffers Modified:
    deinterleaved  contents updated with de-interleaved coefficients from
                   the input array: interleaved

 Local Stores Modified:
    None

 Global Stores Modified:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function performs the deinterleaving across all short windows in
 each group

------------------------------------------------------------------------------
 REQUIREMENTS

 This function should replace the contents of pDeinterleaved with the
 de-interleaved 1024 coefficients of one frame

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
    Subpart 4           p78     quant_to_spec

------------------------------------------------------------------------------
 PSEUDO-CODE

    pInterleaved   = interleaved;
    pDeinterleaved = deinterleaved;

    pSfbPerWin  = pFrameInfo->sfb_per_win;
    ngroups     = pFrameInfo->num_groups;
    pGroupLen   = pFrameInfo->group_len;

    pGroup = pDeinterleaved;

    FOR (group = ngroups; group > 0; group--)

        pSfbWidth   = pFrameInfo->sfb_width_128;
        sfb_inc = 0;
        pStart = pInterleaved;

        FOR (sfb = pSfbPerWin[ngroups-group]; sfb > 0; sfb--)

            pWin = pGroup;

            FOR (win = pGroupLen[ngroups-group]; win > 0; win--)

                pDeinterleaved = pWin + sfb_inc;

                pv_memcpy(
                     pDeinterleaved,
                     pInterleaved,
                    *pSfbWidth*sizeof(*pInterleaved));

                pInterleaved += *pSfbWidth;

                pWin += SN2;

            ENDFOR (win)

            sfb_inc += *pSfbWidth++;

        ENDFOR (sfb)

    pGroup += (pInterleaved - pStart);

    ENDFOR (group)

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
void deinterleave(
    Int16        interleaved[],
    Int16        deinterleaved[],
    FrameInfo   *pFrameInfo)
{

    Int      group;  /* group index */
    Int      sfb;    /* scalefactor band index */
    Int      win;    /* window index */
    Int16    *pGroup;
    Int16    *pWin;
    Int16    *pStart;
    Int16    *pInterleaved;
    Int16    *pDeinterleaved;
    Int      sfb_inc;

    Int      ngroups;
    Int     *pGroupLen;
    Int     *pSfbPerWin;
    Int     *pSfbWidth;

    pInterleaved   = interleaved;
    pDeinterleaved = deinterleaved;

    pSfbPerWin  = pFrameInfo->sfb_per_win;
    ngroups     = pFrameInfo->num_groups;
    pGroupLen   = pFrameInfo->group_len;

    pGroup = pDeinterleaved;

    for (group = ngroups; group > 0; group--)
    {
        pSfbWidth   = pFrameInfo->sfb_width_128;
        sfb_inc = 0;
        pStart = pInterleaved;

        /* Perform the deinterleaving across all windows in a group */

        for (sfb = pSfbPerWin[ngroups-group]; sfb > 0; sfb--)
        {
            pWin = pGroup;

            for (win = pGroupLen[ngroups-group]; win > 0; win--)
            {
                pDeinterleaved = pWin + sfb_inc;

                pv_memcpy(
                    pDeinterleaved,
                    pInterleaved,
                    *pSfbWidth*sizeof(*pInterleaved));

                pInterleaved += *pSfbWidth;

                pWin += SN2;

            } /* for (win) */

            sfb_inc += *pSfbWidth++;

        } /* for (sfb) */

        pGroup += (pInterleaved - pStart);

    } /* for (group) */

} /* deinterleave */
