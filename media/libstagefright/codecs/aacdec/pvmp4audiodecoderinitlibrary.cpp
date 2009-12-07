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

 Pathname: PVMP4AudioDecoderInitLibrary.c


------------------------------------------------------------------------------
 REVISION HISTORY

 Description:  Copied from aac_decode_frame

 Description:  Clean up.

 Description:  Update per review comments

 Description:  Add frame_length, fix mistake in pseudo-code.
               Change frame_length to frameLength, to matcht the API,
               look more professional, etc.

 Description:
 (1) Added #include of "e_ProgConfigConst.h"
     Previously, this function was relying on another include file
     to include "e_ProgConfigConst.h"

 (2) Updated the copyright header.

 Description:
 (1) Modified to initialize pointers for shared memory techniques.

 Description: Since memory will be allocated continuously, it is initialized
              in one spot

 Description: Added field aacPlusUpsamplingFactor (default == 1) to have a
              common interface for all AAC variations

 Description: Added PVMP4AudioDecoderDisableAacPlus to disable sbr decoding

 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    pExt = pointer to the external application-program interface (API)
           structure that a client program uses to communicate information
           with this library. Among the items in this structure is a pointer
           to the input and output buffers, data for handling the input buffer
           and output information. Look in PVMP4AudioDecoder_API.h for all the
           fields to this structure. Data type pointer to a
           tPVMP4AudioDecoderExternal structure.

   pMem =  pointer to allocated memory, of the size returned by the function
           PVMP4AudioDecoderGetMemRequirements. This is a void pointer for
           two reasons:
           1) So the external program does not need all of the header files
              for all of the fields in the structure tDec_Int_File
           2) To hide data and the implementation of the program. Even knowing
              how data is stored can help in reverse engineering software.

 Local Stores/Buffers/Pointers Needed: None

 Global Stores/Buffers/Pointers Needed: None

 Outputs:
    status = 0 (SUCCESS). Presently there is no error checking in this
    function.

 Pointers and Buffers Modified: None

 Local Stores Modified: None

 Global Stores Modified: None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 Initializes the internal memory for the MP4 Audio Decoder library.
 Also sets relevant values for the external interface structure, clears
 the bit rate, channel count, sampling rate, and number of used buffer
 elements.

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
   Audio standard. ISO/IEC gives users of the MPEG-2 NBC/MPEG-4 Audio
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

    pVars = pMem;

    CALL pv_memset(
           to = pVars,
           c  = 0,
           n  = sizeof(tDec_Int_File))
    MODIFYING(*pVars = 0)
    RETURNING(nothing)

    pVars->current_program = -1
    pVars->mc_info.sampling_rate_idx = Fs_44
    pVars->frameLength = LONG_WINDOW


    pVars->winmap[ONLY_LONG_SEQUENCE]   = &pVars->longFrameInfo;
    pVars->winmap[LONG_START_SEQUENCE]  = &pVars->longFrameInfo;
    pVars->winmap[EIGHT_SHORT_SEQUENCE] = &pVars->shortFrameInfo;
    pVars->winmap[LONG_STOP_SEQUENCE]   = &pVars->longFrameInfo;

    CALL infoinit(
        samp_rate_indx = pVars->mc_info.sampling_rate_idx,
        ppWin_seq_info = pVars->winmap,
        pSfbwidth128   = pVars->SFBWidth128)
    MODIFYING(ppWinSeq_info)
    MODIFYING(pSfbwidth128)
    RETURNING(nothing)

    pExt->bitRate = 0;
    pExt->encodedChannels = 0;
    pExt->samplingRate = 0;
    pExt->inputBufferUsedLength = 0;

    MODIFY(pExt)
    MODIFY(pMem)
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

#include "pv_audio_type_defs.h"
#include "s_tdec_int_file.h"
#include "e_progconfigconst.h"

#include "huffman.h"               /* For the definition of infoinit        */
#include "aac_mem_funcs.h"         /* For pv_memset                         */
#include "pvmp4audiodecoder_api.h" /* Where this function is declared       */
#include "s_tdec_int_chan.h"
#include "sfb.h"                   /* samp_rate_info[] is declared here     */

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
; LOCAL VARIABLE DEFINITIONS
; Variable declaration - defined here and used outside this module
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; EXTERNAL FUNCTION REFERENCES
; Declare functions defined elsewhere and referenced in this module
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; EXTERNAL VARIABLES REFERENCES
; Declare variables used in this module but defined elsewhere
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/
OSCL_EXPORT_REF Int PVMP4AudioDecoderInitLibrary(
    tPVMP4AudioDecoderExternal  *pExt,
    void                        *pMem)
{
    tDec_Int_File *pVars;

    pVars = (tDec_Int_File *)pMem;

    /*
     * Initialize all memory. The pointers to channel memory will be
     * set to zero also.
     */
    pv_memset(
        pVars,
        0,
        sizeof(tDec_Int_File));

    /*
     * Pick default values for the library.
     */
    pVars->perChan[0].fxpCoef = pVars->fxpCoef[0];
    pVars->perChan[1].fxpCoef = pVars->fxpCoef[1];

    /* Here, the "shared memory" pointer is set to point
     * at the 1024th element of fxpCoef, because those spaces
     * in memory are not used until the filterbank is called.
     *
     * Therefore, any variables that are only used before
     * the filterbank can occupy this same space in memory.
     */

    pVars->perChan[0].pShareWfxpCoef = (per_chan_share_w_fxpCoef *)
                                       & (pVars->perChan[0].fxpCoef[1024]);

    pVars->perChan[1].pShareWfxpCoef = (per_chan_share_w_fxpCoef *)
                                       & (pVars->perChan[1].fxpCoef[1024]);

    /*
     * This next line informs the function get_prog_config that no
     * configuration has been found thus far, so it is a default
     * configuration.
     */

    pVars->current_program = -1;
    pVars->mc_info.sampling_rate_idx = Fs_44; /* Fs_44 = 4, 44.1kHz */

    /*
     * In the future, the frame length will change with MP4 file format.
     * Presently this variable is used to simply the unit test for
     * the function PVMP4AudioDecodeFrame() .. otherwise the test would
     * have to pass around 1024 length arrays.
     */
    pVars->frameLength = LONG_WINDOW; /* 1024*/

    /*
     * The window types ONLY_LONG_SEQUENCE, LONG_START_SEQUENCE, and
     * LONG_STOP_SEQUENCE share the same information. The only difference
     * between the windows is accounted for in the "filterbank", in
     * the function trans4m_freq_2_time_fxp()
     */

    pVars->winmap[ONLY_LONG_SEQUENCE]   /* 0 */ = &pVars->longFrameInfo;
    pVars->winmap[LONG_START_SEQUENCE]  /* 1 */ = &pVars->longFrameInfo;
    pVars->winmap[EIGHT_SHORT_SEQUENCE] /* 2 */ = &pVars->shortFrameInfo;
    pVars->winmap[LONG_STOP_SEQUENCE]   /* 3 */ = &pVars->longFrameInfo;

    infoinit(
        pVars->mc_info.sampling_rate_idx,
        (FrameInfo   **)pVars->winmap,
        pVars->SFBWidth128);


    /*
     * Clear out external output values. These values are set later at the end
     * of PVMP4AudioDecodeFrames()
     */
    pExt->bitRate = 0;
    pExt->encodedChannels = 0;
    pExt->samplingRate = 0;
    pExt->aacPlusUpsamplingFactor = 1;  /*  Default for regular AAC */
    pVars->aacPlusEnabled = pExt->aacPlusEnabled;


#if defined(AAC_PLUS)
    pVars->sbrDecoderData.setStreamType = 1;        /* Enable Lock for AAC stream type setting  */
#endif

    /*
     * Initialize input buffer variable.
     */

    pExt->inputBufferUsedLength = 0;

    return (MP4AUDEC_SUCCESS);

}  /* PVMP4AudioDecoderInitLibrary */


/*
------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    pExt = pointer to the external application-program interface (API)
           structure that a client program uses to communicate information
           with this library. Among the items in this structure is a pointer
           to the input and output buffers, data for handling the input buffer
           and output information. Look in PVMP4AudioDecoder_API.h for all the
           fields to this structure. Data type pointer to a
           tPVMP4AudioDecoderExternal structure.

   pMem =  pointer to allocated memory, of the size returned by the function
           PVMP4AudioDecoderGetMemRequirements. This is a void pointer for
           two reasons:
           1) So the external program does not need all of the header files
              for all of the fields in the structure tDec_Int_File
           2) To hide data and the implementation of the program. Even knowing
              how data is stored can help in reverse engineering software.

 Local Stores/Buffers/Pointers Needed: None

 Global Stores/Buffers/Pointers Needed: None

 Outputs:
    status = 0 (SUCCESS). Presently there is no error checking in this
    function.

 Pointers and Buffers Modified: None

 Local Stores Modified: None

 Global Stores Modified: None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 Disable SBR decoding functionality and set parameters accordingly

------------------------------------------------------------------------------
 REQUIREMENTS


----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/


OSCL_EXPORT_REF void PVMP4AudioDecoderDisableAacPlus(
    tPVMP4AudioDecoderExternal  *pExt,
    void                        *pMem)
{
    tDec_Int_File *pVars;

    pVars = (tDec_Int_File *)pMem;

    if ((pVars->aacPlusEnabled == true) && (pExt->aacPlusEnabled == true))
    {
        // disable only when makes sense
        pVars->aacPlusEnabled = false;
        pExt->aacPlusEnabled = false;

#if defined(AAC_PLUS)
        pVars->mc_info.upsamplingFactor = 1;
        pVars->mc_info.psPresentFlag  = 0;
        pVars->mc_info.sbrPresentFlag = 0;
        pVars->prog_config.sampling_rate_idx += 3;
        pVars->sbrDecoderData.SbrChannel[0].syncState = SBR_NOT_INITIALIZED;
        pVars->sbrDecoderData.SbrChannel[1].syncState = SBR_NOT_INITIALIZED;


        pExt->samplingRate = samp_rate_info[pVars->prog_config.sampling_rate_idx].samp_rate;
        pExt->aacPlusUpsamplingFactor = 1;
#endif
    }
}  /* PVMP4AudioDecoderDisableAacPlus */



