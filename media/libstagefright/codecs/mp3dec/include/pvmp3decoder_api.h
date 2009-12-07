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

   Filename: pvmp3decoder_api.h

   Date: 09/21/2007

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:

------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 This include file defines the structure tPVMP3DecoderExternal

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef PVMP3DECODER_API_H
#define PVMP3DECODER_API_H

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#include "pvmp3_audio_type_defs.h"
#include "pvmp3_dec_defs.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/
#ifdef __cplusplus
extern "C"
{
#endif

    /*----------------------------------------------------------------------------
    ; DEFINES
    ; Include all pre-processor statements here.
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; EXTERNAL VARIABLES REFERENCES
    ; Declare variables used in this module but defined elsewhere
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; SIMPLE TYPEDEF'S
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; ENUMERATED TYPEDEF'S
    ----------------------------------------------------------------------------*/
    typedef enum
    {
        flat       = 0,
        bass_boost = 1,
        rock       = 2,
        pop        = 3,
        jazz       = 4,
        classical  = 5,
        talk       = 6,
        flat_      = 7

    } e_equalization;



    typedef enum ERROR_CODE
    {
        NO_DECODING_ERROR         = 0,
        UNSUPPORTED_LAYER         = 1,
        UNSUPPORTED_FREE_BITRATE  = 2,
        FILE_OPEN_ERROR           = 3,          /* error opening file */
        CHANNEL_CONFIG_ERROR      = 4,     /* error in channel configuration */
        SYNTHESIS_WINDOW_ERROR    = 5,   /* error in synthesis window table */
        READ_FILE_ERROR           = 6,          /* error reading input file */
        SIDE_INFO_ERROR           = 7,          /* error in side info */
        HUFFMAN_TABLE_ERROR       = 8,      /* error in Huffman table */
        COMMAND_LINE_ERROR        = 9,       /* error in command line */
        MEMORY_ALLOCATION_ERROR   = 10,   /* error allocating memory */
        NO_ENOUGH_MAIN_DATA_ERROR = 11,
        SYNCH_LOST_ERROR          = 12,
        OUTPUT_BUFFER_TOO_SMALL   = 13     /* output buffer can't hold output */
    } ERROR_CODE;

    /*----------------------------------------------------------------------------
    ; STRUCTURES TYPEDEF'S
    ----------------------------------------------------------------------------*/

    typedef struct
#ifdef __cplusplus
                tPVMP3DecoderExternal
#endif
    {

        /*
         * INPUT:
         * Pointer to the input buffer that contains the encoded bistream data.
         * The data is filled in such that the first bit transmitted is
         * the most-significant bit (MSB) of the first array element.
         * The buffer is accessed in a linear fashion for speed, and the number of
         * bytes consumed varies frame to frame.
         * The calling environment can change what is pointed to between calls to
         * the decode function, library, as long as the inputBufferCurrentLength,
         * and inputBufferUsedLength are updated too. Also, any remaining bits in
         * the old buffer must be put at the beginning of the new buffer.
         */
        uint8      *pInputBuffer;

        /*
         * INPUT:
         * Number of valid bytes in the input buffer, set by the calling
         * function. After decoding the bitstream the library checks to
         * see if it when past this value; it would be to prohibitive to
         * check after every read operation. This value is not modified by
         * the MP3 library.
         */
        int32     inputBufferCurrentLength;

        /*
         * INPUT/OUTPUT:
         * Number of elements used by the library, initially set to zero by
         * the function pvmp3_resetDecoder(), and modified by each
         * call to pvmp3_framedecoder().
         */
        int32     inputBufferUsedLength;

        /*
         * OUTPUT:
         * holds the predicted frame size. It used on the test console, for parsing
         * purposes.
         */
        uint32     CurrentFrameLength;

        /*
         * INPUT:
         * This variable holds the type of equalization used
         *
         *
         */
        e_equalization     equalizerType;


        /*
         * INPUT:
         * The actual size of the buffer.
         * This variable is not used by the library, but is used by the
         * console test application. This parameter could be deleted
         * if this value was passed into these function.
         */
        int32     inputBufferMaxLength;

        /*
         * OUTPUT:
         * The number of channels decoded from the bitstream.
         */
        int16       num_channels;

        /*
         * OUTPUT:
         * The number of channels decoded from the bitstream.
         */
        int16       version;

        /*
         * OUTPUT:
         * The sampling rate decoded from the bitstream, in units of
         * samples/second.
         */
        int32       samplingRate;

        /*
         * OUTPUT:
         * This value is the bitrate in units of bits/second. IT
         * is calculated using the number of bits consumed for the current frame,
         * and then multiplying by the sampling_rate, divided by points in a frame.
         * This value can changes frame to frame.
         */
        int32       bitRate;

        /*
         * INPUT/OUTPUT:
         * In: Inform decoder how much more room is available in the output buffer in int16 samples
         * Out: Size of the output frame in 16-bit words, This value depends on the mp3 version
         */
        int32     outputFrameSize;

        /*
         * INPUT:
         * Flag to enable/disable crc error checking
         */
        int32     crcEnabled;

        /*
         * OUTPUT:
         * This value is used to accumulate bit processed and compute an estimate of the
         * bitrate. For debugging purposes only, as it will overflow for very long clips
         */
        uint32     totalNumberOfBitsUsed;


        /*
         * INPUT: (but what is pointed to is an output)
         * Pointer to the output buffer to hold the 16-bit PCM audio samples.
         * If the output is stereo, both left and right channels will be stored
         * in this one buffer.
         */

        int16       *pOutputBuffer;

    } tPVMP3DecoderExternal;

uint32 pvmp3_decoderMemRequirements(void);

void pvmp3_InitDecoder(tPVMP3DecoderExternal *pExt,
                       void  *pMem);

void pvmp3_resetDecoder(void  *pMem);

ERROR_CODE pvmp3_framedecoder(tPVMP3DecoderExternal *pExt,
                              void              *pMem);

#ifdef __cplusplus
}
#endif

/*----------------------------------------------------------------------------
; END
----------------------------------------------------------------------------*/

#endif


