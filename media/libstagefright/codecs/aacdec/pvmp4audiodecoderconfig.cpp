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

 Pathname: PVMP4AudioDecoderConfig

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: (1) Modified to decode AudioSpecificConfig for any frame number
                  pVars->bno
              (2) Update the input and output descriptions

 Description: Eliminated search for ADIF header

 Description: Added support for AAC+

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

 (1) ISO/IEC 14496-3: 1999(E)
      subclause 1.6

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

OSCL_EXPORT_REF Int PVMP4AudioDecoderConfig(
    tPVMP4AudioDecoderExternal  *pExt,
    void                        *pMem)
{

    UInt           initialUsedBits;  /* Unsigned for C55x */
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

    pVars->inputStream.inputBufferCurrentLength =
        (UInt)pExt->inputBufferCurrentLength;

    pVars->inputStream.availableBits =
        (UInt)(pExt->inputBufferCurrentLength << INBUF_ARRAY_INDEX_SHIFT);

    initialUsedBits =
        (UInt)((pExt->inputBufferUsedLength << INBUF_ARRAY_INDEX_SHIFT) +
               pExt->remainderBits);

    pVars->inputStream.usedBits = initialUsedBits;

    if (initialUsedBits <= pVars->inputStream.availableBits)
    {

        /*
         * Buffer is not overrun, then
         * decode the AudioSpecificConfig() structure
         */

        pVars->aacConfigUtilityEnabled = false;  /* set aac dec mode */

        status = get_audio_specific_config(pVars);

    }

    byte_align(&pVars->inputStream);


    if (status == SUCCESS)
    {

        pVars->bno++;

        /*
         * A possible improvement would be to set these values only
         * when they change.
         */
        pExt->samplingRate =
            samp_rate_info[pVars->prog_config.sampling_rate_idx].samp_rate;

        /*
         *  we default to 2 channel, even for mono files, (where channels have same content)
         *  this is done to ensure support for enhanced aac+ with implicit signalling
         */
        pExt->aacPlusEnabled = pVars->aacPlusEnabled;

//        pExt->encodedChannels = pVars->mc_info.nch;

        pExt->encodedChannels = 2;

        pExt->frameLength = pVars->frameLength;
#ifdef AAC_PLUS
        pExt->aacPlusUpsamplingFactor = pVars->mc_info.upsamplingFactor;
#endif

    }
    else
    {
        /*
         *  Default to nonrecoverable error status unless there is a Buffer overrun
         */
        status = MP4AUDEC_INVALID_FRAME;

        if (pVars->inputStream.usedBits > pVars->inputStream.availableBits)
        {
            /* all bits were used but were not enough to complete parsing */
            pVars->inputStream.usedBits = pVars->inputStream.availableBits;

            status = MP4AUDEC_INCOMPLETE_FRAME; /* audio config too small */
        }

    }

    /*
     * Translate from units of bits back into units of words.
     */

    pExt->inputBufferUsedLength =
        pVars->inputStream.usedBits >> INBUF_ARRAY_INDEX_SHIFT;

    pExt->remainderBits = pVars->inputStream.usedBits & INBUF_BIT_MODULO_MASK;

    pVars->status = status;

    return (status);

} /* PVMP4AudioDecoderDecodeFrame */

