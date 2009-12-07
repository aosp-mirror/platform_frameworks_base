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
------------------------------------------------------------------------------

   PacketVideo Corp.
   MP3 Decoder Library

   Filename: pvmp3_seek_synch.cpp

   Functions:
        pvmp3_seek_synch
        pvmp3_header_sync


     Date: 9/21/2007

------------------------------------------------------------------------------
 REVISION HISTORY


 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

pvmp3_frame_synch

Input
    pExt = pointer to the external interface structure. See the file
           pvmp3decoder_api.h for a description of each field.
           Data type of pointer to a tPVMP3DecoderExternal
           structure.

    pMem = void pointer to hide the internal implementation of the library
           It is cast back to a tmp3dec_file structure. This structure
           contains information that needs to persist between calls to
           this function, or is too big to be placed on the stack, even
           though the data is only needed during execution of this function
           Data type void pointer, internally pointer to a tmp3dec_file
           structure.


------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    search mp3 sync word, when found, it verifies, based on header parameters,
    the locations of the very next sync word,
    - if fails, then indicates a false sync,
    - otherwise, it confirm synchronization of at least 2 consecutives frames

------------------------------------------------------------------------------
 REQUIREMENTS


------------------------------------------------------------------------------
 REFERENCES

------------------------------------------------------------------------------
 PSEUDO-CODE

------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#include "pvmp3_seek_synch.h"
#include "pvmp3_getbits.h"
#include "s_tmp3dec_file.h"
#include "pv_mp3dec_fxd_op.h"
#include "pvmp3_tables.h"


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



ERROR_CODE pvmp3_frame_synch(tPVMP3DecoderExternal *pExt,
                             void                  *pMem) /* bit stream structure */
{
    uint16 val;
    ERROR_CODE err;

    tmp3dec_file      *pVars;

    pVars = (tmp3dec_file *)pMem;

    pVars->inputStream.pBuffer = pExt->pInputBuffer;
    pVars->inputStream.usedBits = (pExt->inputBufferUsedLength << 3); // in bits


    pVars->inputStream.inputBufferCurrentLength = (pExt->inputBufferCurrentLength); // in bits

    err = pvmp3_header_sync(&pVars->inputStream);

    if (err == NO_DECODING_ERROR)
    {
        /* validate synchronization by checking two consecutive sync words */

        // to avoid multiple bitstream accesses
        uint32 temp = getNbits(&pVars->inputStream, 21);
        // put back whole header
        pVars->inputStream.usedBits -= 21 + SYNC_WORD_LNGTH;

        int32  version;

        switch (temp >> 19)  /* 2 */
        {
            case 0:
                version = MPEG_2_5;
                break;
            case 2:
                version = MPEG_2;
                break;
            case 3:
                version = MPEG_1;
                break;
            default:
                version = INVALID_VERSION;
                break;
        }

        int32 freq_index = (temp << 20) >> 30;

        if (version != INVALID_VERSION && (freq_index != 3))
        {
            int32 numBytes = fxp_mul32_Q28(mp3_bitrate[version][(temp<<16)>>28] << 20,
                                           inv_sfreq[freq_index]);

            numBytes >>= (20 - version);

            if (version != MPEG_1)
            {
                numBytes >>= 1;
            }
            if ((temp << 22) >> 31)
            {
                numBytes++;
            }

            if (numBytes > (int32)pVars->inputStream.inputBufferCurrentLength)
            {
                /* frame should account for padding and 2 bytes to check sync */
                pExt->CurrentFrameLength = numBytes + 3;
                return (SYNCH_LOST_ERROR);
            }
            else if (numBytes == (int32)pVars->inputStream.inputBufferCurrentLength)
            {
                /* No enough data to validate, but current frame appears to be correct ( EOF case) */
                pExt->inputBufferUsedLength = pVars->inputStream.usedBits >> 3;
                return (NO_DECODING_ERROR);
            }
            else
            {

                int32 offset = pVars->inputStream.usedBits + ((numBytes) << 3);

                offset >>= INBUF_ARRAY_INDEX_SHIFT;
                uint8    *pElem  = pVars->inputStream.pBuffer + offset;
                uint16 tmp1 = *(pElem++);
                uint16 tmp2 = *(pElem);

                val = (tmp1 << 3);
                val |= (tmp2 >> 5);
            }
        }
        else
        {
            val = 0; // force mismatch
        }

        if (val == SYNC_WORD)
        {
            pExt->inputBufferUsedLength = pVars->inputStream.usedBits >> 3; ///  !!!!!
            err = NO_DECODING_ERROR;
        }
        else
        {
            pExt->inputBufferCurrentLength = 0;
            err = SYNCH_LOST_ERROR;
        }
    }
    else
    {
        pExt->inputBufferCurrentLength = 0;
    }

    return(err);

}

/*
------------------------------------------------------------------------------
 REVISION HISTORY


 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

pvmp3_header_sync

Input
    tmp3Bits *inputStream,     structure holding the input stream parameters

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    search mp3 sync word

------------------------------------------------------------------------------
 REQUIREMENTS


------------------------------------------------------------------------------
 REFERENCES

------------------------------------------------------------------------------
 PSEUDO-CODE

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/


ERROR_CODE pvmp3_header_sync(tmp3Bits  *inputStream)
{
    uint16 val;
    uint32 availableBits = (inputStream->inputBufferCurrentLength << 3); // in bits

    // byte aligment
    inputStream->usedBits = (inputStream->usedBits + 7) & 8;

    val = (uint16)getUpTo17bits(inputStream, SYNC_WORD_LNGTH);

    while (((val&SYNC_WORD) != SYNC_WORD) && (inputStream->usedBits < availableBits))
    {
        val <<= 8;
        val |= getUpTo9bits(inputStream, 8);
    }

    if ((val&SYNC_WORD) == SYNC_WORD && (inputStream->usedBits < availableBits))
    {
        return(NO_DECODING_ERROR);
    }
    else
    {
        return(SYNCH_LOST_ERROR);
    }

}

