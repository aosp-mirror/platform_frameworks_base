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

  Name: PVMP4AudioDecoder_API.h

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: Change buffer type to UChar

 Description: Update comments

 Description: Updated a comment that MT did not get around to
 before the end of his contract.

 Description: add a new API to decode audioSpecificConfig separately, the same
              change has been made on 32-bits version (element \main\2)

 Description: add a new API to reset history buffer, the same change has been
              made on a 32-bits version(element \nd.e0352.wjin\1)

 Who:                                       Date:
 Description:

------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 Main header file for the Packet Video MP4/AAC audio decoder library. The
 constants, structures, and functions defined within this file, along with
 a basic data types header file, is all that is needed to use and communicate
 with the library. The internal data structures within the library are
 purposely hidden.

 ---* Need description of the input buffering. *-------

 ---* Need an example of calling the library here *----

------------------------------------------------------------------------------
 REFERENCES

  (Normally header files do not have a reference section)

  ISO/EIC 14496-3:(1999) Document titled
------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef PVMP4AUDIODECODER_API_H
#define PVMP4AUDIODECODER_API_H

#include "pv_audio_type_defs.h"  /* Basic data types used within the lib */

#include "e_tmp4audioobjecttype.h"

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#ifdef __cplusplus
extern "C"
{
#endif

    /*----------------------------------------------------------------------------
    ; MACROS
    ; Define module specific macros here
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; DEFINES
    ; Include all pre-processor statements here.
    ----------------------------------------------------------------------------*/

    /*
     * This constant is the guaranteed-to-work buffer size, specified in bytes,
     * for the input buffer for 2 audio channels to decode one frame of data,
     * as specified by the MPEG-2 or MPEG-4 standard.
     * The standard, and this constant, do not take into account that lower
     * bitrates will use less data per frame. Note that the number of bits
     * used per frame is variable, and only that the average value will be the
     * bit rate specified during encoding. The standard does not specify
     * over how many frames the average must be maintained.
     *
     * The constant value is 6144 * 2 channels / 8 bits per byte
     */


#define PVMP4AUDIODECODER_INBUFSIZE  1536

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
    /*
     * This enumeration is used for the structure element outputFormat. It
     * specifies how the output data is to be formatted. Presently only 16-bit
     * PCM data is supported, and this enum informs how the single output
     * buffer should be for two-channel stereo data.
     * Grouped format stores all the left channel values, then right:
     * "LLLL...LLRRRR...RR"
     * Interleave format store left, then right audio samples:
     * "LRLRLRLR...."
     */
    typedef enum ePVMP4AudioDecoderOutputFormat
    {
        OUTPUTFORMAT_16PCM_GROUPED = 0,
        OUTPUTFORMAT_16PCM_INTERLEAVED = 1

    } tPVMP4AudioDecoderOutputFormat;

    /*
     * This enumeration holds the possible return values for the main decoder
     * function, PVMP4AudioDecodeFrame. The plan was to easily distinguish
     * whether an error was recoverable (streaming mode) or not. Presently no
     * errors are recoverable, which is a result of not supporting ADTS in
     * this release.
     */
    typedef enum ePVMP4AudioDecoderErrorCode
    {
        MP4AUDEC_SUCCESS           =  0,
        MP4AUDEC_INVALID_FRAME     = 10,
        MP4AUDEC_INCOMPLETE_FRAME  = 20,
        MP4AUDEC_LOST_FRAME_SYNC   = 30     /* Cannot happen since no ADTS */
    } tPVMP4AudioDecoderErrorCode;


    /*
     * This enumeration holds the possible return values for stream type
     * being decoded
     */
    typedef enum
    {
        AAC = 0,
        AACPLUS,
        ENH_AACPLUS
    } STREAMTYPE;

    /*----------------------------------------------------------------------------
    ; STRUCTURES TYPEDEF'S
    ----------------------------------------------------------------------------*/
    /*
     * This structure is used to communicate information in to and out of the
     * AAC decoder.
     */

    typedef struct
#ifdef __cplusplus
                tPVMP4AudioDecoderExternal  // To allow forward declaration of this struct in C++
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
        UChar  *pInputBuffer;

        /*
         * INPUT:
         * Number of valid bytes in the input buffer, set by the calling
         * function. After decoding the bitstream the library checks to
         * see if it when past this value; it would be to prohibitive to
         * check after every read operation. This value is not modified by
         * the AAC library.
         */
        Int     inputBufferCurrentLength;

        /*
         * INPUT:
         * The actual size of the buffer.
         * This variable is not used by the library, but is used by the
         * console test application. This parameter could be deleted
         * if this value was passed into these function. The helper functions are
         * not part of the library and are not used by the Common Audio Decoder
         * Interface.
         */
        Int     inputBufferMaxLength;

        /*
         * INPUT:
         * Enumerated value the output is to be interleaved left-right-left-right.
         * For further information look at the comments for the enumeration.
         */
        tPVMP4AudioDecoderOutputFormat  outputFormat;

        /*
         * INPUT: (but what is pointed to is an output)
         * Pointer to the output buffer to hold the 16-bit PCM audio samples.
         * If the output is stereo, both left and right channels will be stored
         * in this one buffer. Presently it must be of length of 2048 points.
         * The format of the buffer is set by the parameter outputFormat.
         */
        Int16  *pOutputBuffer;

        /*
         * INPUT: (but what is pointed to is an output)
         * Pointer to the output buffer to hold the 16-bit PCM AAC-plus audio samples.
         * If the output is stereo, both left and right channels will be stored
         * in this one buffer. Presently it must be of length of 2048 points.
         * The format of the buffer is set by the parameter outputFormat.
         */
        Int16  *pOutputBuffer_plus;     /* Used in AAC+ and enhanced AAC+  */

        /*
         * INPUT:
         * AAC Plus Upsampling Factor. Normally set to 2 when Spectrum Band
         * Replication (SBR) is used
         */
        Int32  aacPlusUpsamplingFactor; /* Used in AAC+ and enhanced AAC+  */

        /*
         * INPUT:
         * AAC Plus enabler. Deafaults to be ON, unless run time conditions
         * require the SBR and PS tools disabled
         */
        bool    aacPlusEnabled;
        /*
         * INPUT:
         * (Currently not being used inside the AAC library.)
         * This flag is set to TRUE when the playback position has been changed,
         * for example, rewind or fast forward. This informs the AAC library to
         * take an appropriate action, which has yet to be determined.
         */
        Bool    repositionFlag;

        /*
         * INPUT:
         * Number of requested output audio channels. This relieves the calling
         * environment from having to perform stereo-to-mono or mono-to-stereo
         * conversions.
         */
        Int     desiredChannels;

        /*
         * INPUT/OUTPUT:
         * Number of elements used by the library, initially set to zero by
         * the function PVMP4AudioDecoderInitLibrary, and modified by each
         * call to PVMP4AudioDecodeFrame.
         */
        Int     inputBufferUsedLength;

        /*
         * INPUT/OUTPUT:
         * Number of bits left over in the next buffer element,
         * This value will always be zero, unless support for ADTS is added.
         */
        Int32    remainderBits;

        /*
         * OUTPUT:
         * The sampling rate decoded from the bitstream, in units of
         * samples/second. For this release of the library this value does
         * not change from frame to frame, but future versions will.
         */
        Int32   samplingRate;

        /*
         * OUTPUT:
         * This value is the bitrate in units of bits/second. IT
         * is calculated using the number of bits consumed for the current frame,
         * and then multiplying by the sampling_rate, divided by points in a frame.
         * This value can changes frame to frame.
         */
        Int32   bitRate;

        /*
         * OUTPUT:
         * The number of channels decoded from the bitstream. The output data
         * will have be the amount specified in the variable desiredChannels,
         * this output is informative only, and can be ignored.
         */
        Int     encodedChannels;

        /*
         * OUTPUT:
         * This value is the number of output PCM samples per channel.
         * It is presently hard-coded to 1024, but may change in the future.
         * It will not change frame to frame, and would take on
         * one of these four values: 1024, 960, 512, or 480. If an error occurs
         * do not rely on this value.
         */
        Int     frameLength;

        /*
        * This value is audio object type as defined in struct tMP4AudioObjectType
        * in file e_tMP4AudioObjectType.h
        */
        Int     audioObjectType;

        /*
        * This value is extended audio object type as defined in struct tMP4AudioObjectType
        * in file e_tMP4AudioObjectType.h. It carries the output Audio Object Type
        */
        Int     extendedAudioObjectType;


    } tPVMP4AudioDecoderExternal;

    /*----------------------------------------------------------------------------
    ; GLOBAL FUNCTION DEFINITIONS
    ; Function Prototype declaration
    ----------------------------------------------------------------------------*/

    OSCL_IMPORT_REF UInt32 PVMP4AudioDecoderGetMemRequirements(void);

    OSCL_IMPORT_REF Int PVMP4AudioDecoderInitLibrary(
        tPVMP4AudioDecoderExternal  *pExt,
        void                        *pMem);

    OSCL_IMPORT_REF Int PVMP4AudioDecodeFrame(
        tPVMP4AudioDecoderExternal  *pExt,
        void                        *pMem);

    OSCL_IMPORT_REF Int PVMP4AudioDecoderConfig(
        tPVMP4AudioDecoderExternal  *pExt,
        void                        *pMem);

    OSCL_IMPORT_REF void PVMP4AudioDecoderResetBuffer(
        void                        *pMem);

    OSCL_IMPORT_REF void PVMP4AudioDecoderDisableAacPlus(
        tPVMP4AudioDecoderExternal  *pExt,
        void                        *pMem);

    Int PVMP4SetAudioConfig(
        tPVMP4AudioDecoderExternal  *pExt,
        void                        *pMem,
        Int                         upsamplingFactor,
        Int                         samp_rate,
        int                         num_ch,
        tMP4AudioObjectType         audioObjectType);

    /*----------------------------------------------------------------------------
    ; END
    ----------------------------------------------------------------------------*/

#ifdef __cplusplus
}
#endif


#endif  /* PVMP4AUDIODECODER_API_H */


