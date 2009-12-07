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

 Pathname: pvmp4setaudioconfigg

------------------------------------------------------------------------------
 REVISION HISTORY

 Who:                                         Date:
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    pExt = pointer to the external interface structure. See the file
           PVMP4AudioDecoder_API.h for a description of each field.
           Data type of pointer to a tPVMP4AudioDecoderExternal
           structure.

           pExt->pInputBuffer: pointer to input buffer containing input
                               bitstream

           pExt->inputBufferCurrentLength: number of bytes in the input buffer

           pExt->inputBufferUsedLength: number of bytes already consumed in
                                        input buffer

           pExt->remainderBits: number of bits consumed in addition to
                                pExt->inputBufferUsedLength

    pMem = void pointer to hide the internal implementation of the library
           It is cast back to a tDec_Int_File structure. This structure
           contains information that needs to persist between calls to
           this function, or is too big to be placed on the stack, even
           though the data is only needed during execution of this function
           Data type void pointer, internally pointer to a tDec_Int_File
           structure.

 Local Stores/Buffers/Pointers Needed: None
           (The memory set aside in pMem performs this task)

 Global Stores/Buffers/Pointers Needed: None

 Outputs:
     status = 0                       if no error occurred
              MP4AUDEC_NONRECOVERABLE if a non-recoverable error occurred
              MP4AUDEC_RECOVERABLE    if a recoverable error occurred.
              Presently a recoverable error does not exist, but this
              was a requirement.


 Pointers and Buffers Modified:
    pMem contents are modified.
    pExt: (more detail in the file PVMP4AudioDecoder_API.h)
    inputBufferUsedLength - number of array elements used up by the stream.
    remainderBits - remaining bits in the next UInt32 buffer
    samplingRate - sampling rate in samples per sec
    encodedChannels - channels found on the file (informative)
    frameLength - length of the frame

 Local Stores Modified: None.

 Global Stores Modified: None.

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION


------------------------------------------------------------------------------
 REQUIREMENTS

 PacketVideo Document # CCC-AUD-AAC-ERS-0003

------------------------------------------------------------------------------
 REFERENCES


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
#include "ibstream.h"           /* where #define INBUF_ARRAY_INDEX_SHIFT */
#include "sfb.h"                   /* Where samp_rate_info[] is declared */

#include "get_audio_specific_config.h"
#include "pvmp4audiodecoder_api.h"   /* Where this function is declared */
#include "set_mc_info.h"

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

Int PVMP4SetAudioConfig(
    tPVMP4AudioDecoderExternal  *pExt,
    void                        *pMem,
    Int                         upsamplingFactor,
    Int                         samp_rate,
    Int                         num_ch,
    tMP4AudioObjectType         audioObjectType)

{

    tDec_Int_File *pVars;           /* Helper pointer */

    Int            status = MP4AUDEC_INCOMPLETE_FRAME;

    /*
     * Initialize "helper" pointers to existing memory.
     */
    pVars = (tDec_Int_File *)pMem;
    /*
     * Translate input buffer variables.
     */
    pVars->inputStream.pBuffer = pExt->pInputBuffer;

    pVars->inputStream.availableBits = 0;

    pVars->inputStream.usedBits = 0;



    /*
     *  get sampling rate index
     */

    switch (samp_rate)
    {
        case 96000:
            pVars->prog_config.sampling_rate_idx = 0;
            break;
        case 88200:
            pVars->prog_config.sampling_rate_idx = 1;
            break;
        case 64000:
            pVars->prog_config.sampling_rate_idx = 2;
            break;
        case 48000:
            pVars->prog_config.sampling_rate_idx = 3;
            break;
        case 44100:
            pVars->prog_config.sampling_rate_idx = 4;
            break;
        case 32000:
            pVars->prog_config.sampling_rate_idx = 5;
            break;
        case 24000:
            pVars->prog_config.sampling_rate_idx = 6;
            break;
        case 22050:
            pVars->prog_config.sampling_rate_idx = 7;
            break;
        case 16000:
            pVars->prog_config.sampling_rate_idx = 8;
            break;
        case 12000:
            pVars->prog_config.sampling_rate_idx = 9;
            break;
        case 11025:
            pVars->prog_config.sampling_rate_idx = 10;
            break;
        case 8000:
            pVars->prog_config.sampling_rate_idx = 11;
            break;
        case 7350:
            pVars->prog_config.sampling_rate_idx = 12;
            break;
        default:
            status = -1;

            break;
    }

    pVars->mc_info.sbrPresentFlag = 0;
    pVars->mc_info.psPresentFlag = 0;
#ifdef AAC_PLUS
    pVars->mc_info.bDownSampledSbr = 0;
#endif
    pVars->mc_info.implicit_channeling = 0;
    pVars->mc_info.nch = num_ch;
    pVars->mc_info.upsamplingFactor = upsamplingFactor;


    /*
     *  Set number of channels
     */

    if (num_ch == 2)
    {
        pVars->prog_config.front.ele_is_cpe[0] = 1;
    }
    else if (num_ch == 1)
    {
        pVars->prog_config.front.ele_is_cpe[0] = 0;
    }
    else
    {
        status = -1; /* do not support more than two channels */
        pVars->status = status;
        return (status);
    }


    /*
     *  Set AAC bitstream
     */

    if ((audioObjectType == MP4AUDIO_AAC_LC)        ||
            (audioObjectType == MP4AUDIO_LTP))
    {
        pVars->aacPlusEnabled = false;

        status = set_mc_info(&(pVars->mc_info),
                             audioObjectType, /* previously profile */
                             pVars->prog_config.sampling_rate_idx,
                             pVars->prog_config.front.ele_tag[0],
                             pVars->prog_config.front.ele_is_cpe[0],
                             pVars->winmap, /*pVars->pWinSeqInfo,*/
                             pVars->SFBWidth128);
    }
    else if ((audioObjectType == MP4AUDIO_SBR)        ||
             (audioObjectType == MP4AUDIO_PS))
    {
        pVars->aacPlusEnabled = true;


        status = set_mc_info(&(pVars->mc_info),
                             MP4AUDIO_AAC_LC,
                             pVars->prog_config.sampling_rate_idx,
                             pVars->prog_config.front.ele_tag[0],
                             pVars->prog_config.front.ele_is_cpe[0],
                             pVars->winmap, /*pVars->pWinSeqInfo,*/
                             pVars->SFBWidth128);

        pVars->mc_info.sbrPresentFlag = 1;
        if (audioObjectType == MP4AUDIO_PS)
        {
            pVars->mc_info.psPresentFlag = 1;
        }

        if (upsamplingFactor == 1)
        {
#ifdef AAC_PLUS
            pVars->mc_info.bDownSampledSbr = 1;
#endif

            /*
             *  Disable SBR decoding for any sbr-downsampled file whose SF is >= 24 KHz
             */
            if (pVars->prog_config.sampling_rate_idx < 6)
            {
                pVars->aacPlusEnabled = false;
            }
        }

    }
    else
    {
        status = -1;
    }


    /*
     * Translate from units of bits back into units of words.
     */
    pExt->inputBufferUsedLength = 0;

    pExt->remainderBits = 0;

    pVars->bno++;

    pExt->samplingRate = samp_rate * upsamplingFactor;

    pExt->aacPlusEnabled = pVars->aacPlusEnabled;

    /*
     *  we default to 2 channel, even for mono files, (where channels have same content)
     *  this is done to ensure support for enhanced aac+ with implicit signalling
     */

    pExt->encodedChannels = 2;

    pExt->frameLength = 1024;
#ifdef AAC_PLUS
    pExt->aacPlusUpsamplingFactor = upsamplingFactor;
#endif

    pVars->status = status;

    return (status);

} /* PVMP4AudioDecoderDecodeFrame */
