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

   Filename: pvmp3_decode_header.cpp

     Date: 09/21/2007

------------------------------------------------------------------------------
 REVISION HISTORY


 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

Input
    tbits  *inputStream,        bit stream
    mp3Header  *info,
    uint32 *crc
 Returns

    mp3Header  *info,           structure holding the parsed mp3 header info
    uint32 *crc                 initialized crc computation


------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    gets mp3 header information

------------------------------------------------------------------------------
 REQUIREMENTS


------------------------------------------------------------------------------
 REFERENCES

 [1] ISO MPEG Audio Subgroup Software Simulation Group (1996)
     ISO 13818-3 MPEG-2 Audio Decoder - Lower Sampling Frequency Extension

------------------------------------------------------------------------------
 PSEUDO-CODE

------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#include "pvmp3_decode_header.h"
#include "pvmp3_crc.h"
#include "pvmp3_getbits.h"
#include "pvmp3_seek_synch.h"


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

ERROR_CODE pvmp3_decode_header(tmp3Bits  *inputStream,
                               mp3Header  *info,
                               uint32 *crc)
{

    ERROR_CODE err = NO_DECODING_ERROR;
    uint32  temp;

    /*
     * Verify that at least the header is complete
     * Note that SYNC_WORD_LNGTH is in unit of bits, but inputBufferCurrentLength
     * is in unit of bytes.
     */
    if (inputStream->inputBufferCurrentLength < ((SYNC_WORD_LNGTH + 21) >> 3))
    {
        return NO_ENOUGH_MAIN_DATA_ERROR;
    }

    /*
     *  MPEG Audio Version ID
     */
    temp = getUpTo17bits(inputStream, SYNC_WORD_LNGTH);
    if ((temp & SYNC_WORD) != SYNC_WORD)
    {
        err = pvmp3_header_sync(inputStream);

        if (err != NO_DECODING_ERROR)
        {
            return err;
        }
    }

    temp = getNbits(inputStream, 21);   // to avoid multiple bitstream accesses


    switch (temp >> 19)  /* 2 */
    {
        case 0:
            info->version_x = MPEG_2_5;
            break;
        case 2:
            info->version_x = MPEG_2;
            break;
        case 3:
            info->version_x = MPEG_1;
            break;
        default:
            info->version_x = INVALID_VERSION;
            err = UNSUPPORTED_LAYER;
            break;
    }

    info->layer_description  = 4 - ((temp << 13) >> 30);  /* 2 */
    info->error_protection   =  !((temp << 15) >> 31);  /* 1 */

    if (info->error_protection)
    {
        *crc = 0xffff;           /* CRC start value */
        calculate_crc((temp << 16) >> 16, 16, crc);
    }

    info->bitrate_index      = (temp << 16) >> 28;  /* 4 */
    info->sampling_frequency = (temp << 20) >> 30;  /* 2 */
    info->padding            = (temp << 22) >> 31;  /* 1 */
    info->extension          = (temp << 23) >> 31;  /* 1 */
    info->mode               = (temp << 24) >> 30;  /* 2 */
    info->mode_ext           = (temp << 26) >> 30;  /* 2 */
    info->copyright          = (temp << 27) >> 31;  /* 1 */
    info->original           = (temp << 28) >> 31;  /* 1 */
    info->emphasis           = (temp << 30) >> 30;  /* 2 */


    if (!info->bitrate_index || info->sampling_frequency == 3)
    {
        err = UNSUPPORTED_FREE_BITRATE;
    }

    return(err);
}

