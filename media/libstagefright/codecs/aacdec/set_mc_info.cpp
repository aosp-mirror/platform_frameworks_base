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

 Pathname: ./src/set_mc_info.c

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: Modified per review comments

 Description: Change audioObjectType from Int to enum types

 Who:                               Date:
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    pMC_Info    = pointer to structure MC_Info that holds information of
                  multiple channels' configurations
                  Data type pointer to MC_Info

    objectType  = variable that holds the Audio Object Type of current
                  file/bitstream.
                  Data type Int

    sampling_rate_idx = variable that indicates the sampling rate of the
                        source file being encoded
                        Data Type Int

    tag         = variable that stores the element instance tag of the
                  first (front) channel element.
                  Data type Int

    is_cpe      = variable that indicates if a Channel Pair Element (CPE)
                  or a Single Channel Element (SCE) is used.
                  Data type Int (maybe Boolean)

    pWinSeqInfo = array of pointers that points to structures holding
                  frame information of long and short window sequences.
                  Data type FrameInfo

    pSfbwidth128 = array that will store the scalefactor bandwidth of
                   short window sequence frame.
                   Data type Int array

 Local Stores/Buffers/Pointers Needed:
    None

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    return SUCCESS

 Pointers and Buffers Modified:
    pMC_Info->nch           contains the number of channels depending
                            upon if CPE or SCE is used
    pMC_Info->objectType    contents updated with the decoded Audio
                            Object Type

    pMC_Info->ch_info.tag   contents updated with the value of decoded
                            channel element tag

    PMC_Info->ch_info.cpe   contents updated depending upon if CPE or
                            SCE is used

    pWinSeqInfo             contents updated by calling infoinit if
                            sampling_rate_idx is different from
                            previous value

    pSfbWidth128            contents updated by calling infoinit if
                            sampling_rate_idx is different from
                            previous value

 Local Stores Modified:
    None

 Global Stores Modified:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function initializes the channel configuration information. The
 structure MC_Info stores the number of channels, channel element tag.
 If sampling rate index is different from the previous value,
 The frame information will be updated by calling infoinit.c

------------------------------------------------------------------------------
 REQUIREMENTS

 This function shall update the relevant information on channel configs

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
    Subpart 1   p20 Table 1.6.3
    Subpart 4   p30 5.1.2.1
    Subpart 4   p31 4.5.2.1.1

------------------------------------------------------------------------------
 PSEUDO-CODE

    pMC_Info->nch   = 0;

    pMC_Info->profile = objectType;

    IF (pMC_Info->sampling_rate_idx != sampling_rate_idx)
    THEN
        pMC_Info->sampling_rate_idx = sampling_rate_idx;

        CALL infoinit(
                samp_rate_idx = sampling_rate_idx
                ppWin_seq_info= pWinSeqInfo
                pSfbwidth128  = pSfbwidth128)
        MODIFYING(pWinSeqInfo, pSfbwidth128)
        RETURNING(None)
    ENDIF

    pCh_Info = &pMC_Info->ch_info[0];
    pCh_Info->tag = tag;

    IF (is_cpe == FALSE)
    THEN
        pCh_Info->cpe = FALSE;

        pMC_Info->nch = 1;

    ELSE
        pCh_Info->cpe = TRUE;
        pCh_Info = &pMC_Info->ch_info[1];
        pCh_Info->tag = tag;
        pCh_Info->cpe = TRUE;

        pMC_Info->nch = 2;

    ENDIF

    RETURN(SUCCESS)

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
#include    "set_mc_info.h"
#include    "huffman.h"
#include    "s_ch_info.h"

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
Int set_mc_info(
    MC_Info     *pMC_Info,
    const tMP4AudioObjectType audioObjectType, /* used to be profile */
    const Int    sampling_rate_idx,
    const Int    tag,   /* always pass-in last element's value */
    const Int    is_cpe,
    FrameInfo   *pWinSeqInfo[],
    Int          sfbwidth128[]
)
{
    Ch_Info *pCh_Info; /*optional task: eliminate this structure */

    /*
     *   audioObjectType and sampling rate
     *   re-configure if new sampling rate
     *
     */
    pMC_Info->audioObjectType = audioObjectType;

    if (pMC_Info->sampling_rate_idx != sampling_rate_idx)
    {
        pMC_Info->sampling_rate_idx = sampling_rate_idx;

        Int status;
        status = infoinit(sampling_rate_idx,
                          pWinSeqInfo,
                          sfbwidth128);
        if (SUCCESS != status)
        {
            return 1;
        }
    }

    /*
     * first setup values for mono config, Single Channel Element (SCE)
     * then if stereo, go inside if(is_cpe != FALSE) branch to setup
     * values for stereo.
     * set the channel counts
     * save tag for left channel
     */
    pMC_Info->nch   = 1 + is_cpe;

    pCh_Info = &pMC_Info->ch_info[0];
    pCh_Info->tag = tag;
    pCh_Info->cpe = is_cpe;

    /* This if branch maybe deleted in the future */
    if (is_cpe != FALSE)
    {
        /* Channel Pair Element (CPE) */
        /* right channel*/
        pCh_Info = &pMC_Info->ch_info[1];
        pCh_Info->cpe = TRUE;

    }

    return(SUCCESS); /* possible future error checkings */
}
