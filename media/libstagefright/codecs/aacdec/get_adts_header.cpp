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

 Pathname: get_adts_header.c

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: Remove default_config variable

 Description: change enter_mc_info to set_mc_info

 Description: (1) add error checking for channel_config > 2
              (2) eliminated call to check_mc_info
              (3) use (profile + 1) when calling set_mc_info
              (4) use winmap when calling set_mc_info

 Who:                                          Date:
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    pVars           =   Pointer to structure that holds file-scope variables.
                        [ tDec_Int_File * ]

    pSyncword       =   Pointer to variable that holds the 28-bit fixed
                        header upon the exit of this function. [ UInt32 * ]

    pInvoke         =   Pointer to variable that keeps track of how many
                        "short" (14 bit) headers have been successfully
                        parsed from the bitstream. [ Int * ]

 Local Stores/Buffers/Pointers Needed:
    None

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    Status = SUCCESS or ERROR CODE

 Pointers and Buffers Modified:
    pVars->prog_config   Updated with program information data as read from
                         the ADTS header.

    pSyncword            Value pointed to is updated with the contents of
                         the 28-bit fixed header.

    pInvoke              Value pointed to is updated to reflect the number
                         of successful "short" (14 bit) headers that have
                         been successfully parsed from the bitstream.

 Local Stores Modified:
    None

 Global Stores Modified:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 Acronym Definitions
 ADTS  Audio Data Transport Stream
 CRC   Cyclic Redundancy Code

 This function calls find_adts_syncword to find the next ADTS header.  Until
 three consistent headers have been read, the syncword used for detection
 consists of the 12-bit syncword and the 2-bit Layer.  After three consistent
 headers are read, the entire fixed header is used for a robust 28-bit
 syncword.

 Configuration information is then extracted from the bitstream.

 The bitstream information is packed as follows.
 Comments about the correct interpretation of these bits are contained within
 the code.

                                      CRC_absent    sampling_rate_idx
                                           \               / \
                                            \             /   \
                                             \  Profile  /     \  UNUSED
                                              \   / \   /       \   /
|00|01|02|03|04|05|06|07|08|09|10|11|12|13|14|15|16|17|18|19|20|21|22|23|24|25|
 \         _______________         / |   \  /                         \      /
  \-------|0xFFF syncword |-------/  |   Layer == '00' for AAC         \    /
           \-------------/           |                                  \  /
                                     |                                   \/
                                     ID == '1' for MPEG-2 AAC    channel_config
       copyright_id_bit                 == '0' for MPEG-4 AAC
          /
    home /
     /  /
|26|27|28|29|30|31|32|33|34|35|36|37|38|39|40|41|42|
  |        \  \          _____________           /
  |         \  \--------|frame length |---------/
  orig_copy  \           \-----------/
              \                                  ______________________________
        copyright_id_start                      | TOTAL HEADER LENGTH: 56 bits|
                                                |-----------------------------|
|43|44|45|46|47|48|49|50|51|52|53|54|55|        | FIXED    HEADER BITS 00-27  |
  \       _______________      /  |   |         | VARIABLE HEADER BITS 28-55  |
   \-----|buffer_fullness|----/    \ /          |_____________________________|
          \-------------/           |
                              headerless_frames

 In addition to the bits displayed above, if the value CRC_absent is '0' an
 additional 16 bits corresponding to a CRC word are read from the bitstream,
 following the header.
------------------------------------------------------------------------------
 REQUIREMENTS

 After the ADTS syncword is detected, this function shall parse the
 information residing behind the syncword in the bitstream.
------------------------------------------------------------------------------
 REFERENCES
 (1) ISO/IEC 13818-7:1997(E)
     Part 7
        Subpart 6.2 (Audio_Data_Transport_Stream frame, ADTS)

 (2) ISO/IEC 11172-3:1993(E)
     Part 3
        Subpart 2.4.3 The audio decoding process

 (3) MPEG-2 NBC Audio Decoder
   "This software module was originally developed by AT&T, Dolby
   Laboratories, Fraunhofer Gesellschaft IIS in the course of development
   of the MPEG-2 NBC/MPEG-4 Audio standard ISO/IEC 13818-7, 14496-1,2 and
   3. This software module is an implementation of a part of one or more
   MPEG-2 NBC/MPEG-4 Audio tools as specified by the MPEG-2 NBC/MPEG-4
   Audio standard. ISO/IEC  gives users of the MPEG-2 NBC/MPEG-4 Audio
   standards free license to this software module or modifications thereof
   for use in hardware or software products claiming conformance to the
   MPEG-2 NBC/MPEG-4 Audio  standards. Those UIntending to use this software
   module in hardware or software products are advised that this use may
   infringe existing patents. The original developer of this software
   module and his/her company, the subsequent editors and their companies,
   and ISO/IEC have no liability for use of this software module or
   modifications thereof in an implementation. Copyright is not released
   for non MPEG-2 NBC/MPEG-4 Audio conforming products.The original
   developer retains full right to use the code for his/her  own purpose,
   assign or donate the code to a third party and to inhibit third party
   from using the code for non MPEG-2 NBC/MPEG-4 Audio conforming products.
   This copyright notice must be included in all copies or derivative
   works."
   Copyright(c)1996.

------------------------------------------------------------------------------
 PSEUDO-CODE

    IF (*(pInvoke) > 3)

         CALL find_adts_syncword(
                    pSyncword,
                   &(pVars->inputStream),
                    LENGTH_FIXED_HEADER,
                    MASK_28BITS);
           RETURNING  status
    ELSE

        *(pSyncword) = SYNCWORD_15BITS;

        CALL find_adts_syncword(
                   pSyncword,
                  &(pVars->inputStream),
                   LENGTH_SYNCWORD,
                   ID_BIT_FILTER);

          MODIFYING  *(pSyncword) = 28-bit fixed header (long syncword)
          RETURNING  status

        CALL getbits(
                (LENGTH_FIXED_HEADER - LENGTH_SYNCWORD),
               &(pVars->inputStream));

          MODIFYING pVars->inputStream
          RETURNING adts_header = remaining bits in the fixed header

        *(pSyncword) <<= 13;
        *(pSyncword) = *(pSyncword) OR adts_header;

        pVars->prog_config.CRC_absent  = ((UInt)(adts_header >> 12)) AND 0x0001;

        lower_16 = (UInt)adts_header;

        pVars->prog_config.profile = (lower_16 >> 10) AND 0x3;

        pVars->prog_config.sampling_rate_idx = (lower_16 >> 6) AND 0xF;

        channel_configuration = (lower_16 >> 2) AND 0x7;

        channel_configuration = channel_configuration - 1;
        pVars->prog_config.front.ele_is_cpe[0] = channel_configuration;

        pVars->prog_config.front.num_ele    = 1;

        pVars->prog_config.front.ele_tag[0] = 0;

        pVars->prog_config.mono_mix.present = 0;
        pVars->prog_config.stereo_mix.present = 0;
        pVars->prog_config.matrix_mix.present = 0;

        CALL set_mc_info(
                &(pVars->mc_info),
                &(pVars->savedMCInfo),
                &(pVars->prog_config),
                  pVars->pWinSeqInfo,
                  pVars->SFBWidth128);
          MODIFYING pVars->mc_info = multi-channel configuration information
          RETURNING status         = SUCCESS/FAILURE

        IF ( (*pInvoke) != 0)
            CALL check_mc_info(
                    &(pVars->mc_info),
                    &(pVars->savedMCInfo),
                     FALSE);
              RETURNING status = SUCCESS/FAILURE
        ELSE
            CALL check_mc_info(
                    &(pVars->mc_info),
                    &(pVars->savedMCInfo),
                     TRUE);
              MODIFYING pVars->savedMCInfo = pVars->mc_info
              RETURNING status = SUCCESS/FAILURE
        ENDIF

        IF (status == SUCCESS)
            (*pInvoke) = (*pInvoke) + 1;
        ELSE
            (*pInvoke) = 0;
        ENDIF

    ENDIF

    CALL getbits(
            LENGTH_VARIABLE_HEADER,
           &(pVars->inputStream));
      RETURNING adts_header = 28-bits (the contents of the variable header.)

    pVars->prog_config.frame_length  = ((UInt)(adts_header >> 13)) AND 0x1FFF;

    lower_16 = (UInt)adts_header;

    pVars->prog_config.buffer_fullness = (lower_16 >> 2) AND 0x7FF;

    pVars->prog_config.headerless_frames = (lower_16 AND 0x0003);

    IF (pVars->prog_config.CRC_absent == 0)

        CALL getbits(
                LENGTH_CRC,
               &(pVars->inputStream) );
          RETURNING pVars->prog_config.CRC_check = 16-bit CRC

    ENDIF

    pVars->default_config = 0;

    IF (byte_align_offset > 7)
        status = 1;
    ENDIF

    return (status);

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
#include "s_bits.h"
#include "s_tdec_int_file.h"
#include "ibstream.h"
#include "set_mc_info.h"
#include "find_adts_syncword.h"
#include "get_adts_header.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/
#define LENGTH_VARIABLE_HEADER  28
#define LENGTH_FIXED_HEADER     28
#define LENGTH_SYNCWORD         15
#define LENGTH_CRC              16

#define ID_BIT_FILTER           0x7FFB
#define SYNCWORD_15BITS         0x7FF8
#define MASK_28BITS             0x0FFFFFFFL

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
Int get_adts_header(
    tDec_Int_File *pVars,
    UInt32        *pSyncword,
    Int           *pInvoke,
    Int            CorrectlyReadFramesCount)
{
    UInt32 adts_header;
    UInt   lower_16;
    Int    status = SUCCESS;
    UInt   channel_configuration;

    /*
     * Search for the LONG ADTS syncword (comprised of the entire fixed header)
     * if the number of CorrectlyReadFrames is > CorrectlyReadFramesCount
     *
     * Otherwise, search for just the short syncword.
     */
    if (*(pInvoke) > CorrectlyReadFramesCount)
    {
        /*
         * Find the long ADTS syncword
         * (comprised of the entire ADTS fixed header)
         */

        status = find_adts_syncword(pSyncword,
                                    &(pVars->inputStream),
                                    LENGTH_FIXED_HEADER,
                                    MASK_28BITS);
    }
    else
    {

        *(pSyncword) = SYNCWORD_15BITS;

        status = find_adts_syncword(pSyncword,
                                    &(pVars->inputStream),
                                    LENGTH_SYNCWORD,
                                    ID_BIT_FILTER);

        /*
         *  Extract the data from the header following the syncword
         */
        adts_header = getbits((LENGTH_FIXED_HEADER - LENGTH_SYNCWORD),
                              &(pVars->inputStream));

        *(pSyncword) <<= (LENGTH_FIXED_HEADER - LENGTH_SYNCWORD);
        *(pSyncword)  |= adts_header;

        /* Denotes whether a CRC check should be performed */
        pVars->prog_config.CRC_absent  = ((UInt)(adts_header >> 12)) & 0x0001;

        /*
         * All the unread bits in adts_header reside in the lower
         * 16-bits at this point.  Perform a typecast for faster
         * execution on 16-bit processors.
         */
        lower_16 = (UInt)adts_header;

        /*
         * Profile consists of 2 bits, which indicate
         * the profile used.
         *
         * '00' AAC_MAIN profile
         * '01' AAC_LC (Low Complexity) profile
         * '10' AAC_SSR (Scaleable Sampling Rate) profile
         * '11' AAC_LTP (Long Term Prediction) profile
         */
        pVars->prog_config.profile = (lower_16 >> 10) & 0x3;

        if (pVars->prog_config.profile == MP4AUDIO_AAC_SSR)
        {
            status = 1;     /* Not supported */
        }

        /*
         * Sampling_rate_idx consists of 4 bits
         * see Ref #1 for their interpretation.
         */
        pVars->prog_config.sampling_rate_idx = (lower_16 >> 6) & 0xF;

        /*
         * private_bit is a bit for private use.  ISO/IEC will not make
         * use of this bit in the future.
         *
         * We currently make no use of it, but parsing the information
         * from the bitstream could be easily implemented with the
         * following instruction...
         *
         * private_bit = (lower_16 & 0x0400) >> 10;
         */

        /*
         * These 3 bits indicate the channel configuration used.
         *
         * If '0' then the channel configuration is unspecified here,
         * and must be given by a program configuration element in
         * the raw data block.
         *
         * If '1' then the channel configuration is MONO.
         * If '2' then the channel configuration is STEREO
         *
         * 3-7 represent channel configurations which this library
         * will not support in the forseeable future.
         */
        channel_configuration = (lower_16 >> 2) & 0x7;
        /* do not support more than 2 channels */
        if (channel_configuration > 2)
        {
            status = 1;
        }

        /*
         * The following 2 bits encode copyright information.
         * original_copy is '0' if there is no copyright in the bitstream.
         *                  '1' if the bitstream is copyright protected.
         *
         * home is '0' for a copy, '1' for an original.
         *
         * PacketVideo currently does nothing with this information,
         * however, parsing the data from the bitstream could be easily
         * implemented with the following instructions...
         *
         * original_copy = (lower_16 >> 1) & 0x1;
         *
         * home = (lower_16 & 0x1);
         *
         */

        /* Set up based on information extracted from the ADTS FIXED header */

        /* This equals 1 for STEREO, 0 for MONO */
        if (channel_configuration)
        {
            channel_configuration--;
        }
        pVars->prog_config.front.ele_is_cpe[0] = channel_configuration;

        /* This value is constant for both MONO and STEREO */
        pVars->prog_config.front.num_ele    = 1;

        /* ADTS does not specify this tag value - do we even use it? */
        pVars->prog_config.front.ele_tag[0] = 0;

        /* Disable all mix related variables */
        pVars->prog_config.mono_mix.present = 0;
        pVars->prog_config.stereo_mix.present = 0;
        pVars->prog_config.matrix_mix.present = 0;

        /* enter configuration into MC_Info structure */
        if (status == SUCCESS)
        {
            /* profile + 1 == audioObjectType */
            status =
                set_mc_info(
                    &(pVars->mc_info),
                    (tMP4AudioObjectType)(pVars->prog_config.profile + 1),
                    pVars->prog_config.sampling_rate_idx,
                    pVars->prog_config.front.ele_tag[0],
                    pVars->prog_config.front.ele_is_cpe[0],
                    pVars->winmap, /* changed from pVars->pWinSeqInfo, */
                    pVars->SFBWidth128);

        } /* if (status == SUCCESS) */


#ifdef AAC_PLUS

        /*
         *  For implicit signalling, no hint that sbr or ps is used, so we need to
         *  check the sampling frequency of the aac content, if lesser or equal to
         *  24 KHz, by defualt upsample, otherwise, do nothing
         */
        if ((pVars->prog_config.sampling_rate_idx >= 6) && (pVars->aacPlusEnabled == TRUE))
        {
            pVars->mc_info.upsamplingFactor = 2;
            pVars->prog_config.sampling_rate_idx -= 3;
            pVars->mc_info.sbrPresentFlag = 1;
            pVars->sbrDecoderData.SbrChannel[0].syncState = SBR_ACTIVE;
            pVars->sbrDecoderData.SbrChannel[1].syncState = SBR_ACTIVE;
        }
#endif


        /*
         * The tag and is_cpe will be checked in huffdecode,
         * remove this check routine.
         */
        /*if (status == SUCCESS)
         *{
         *   if ( (*pInvoke) != 0)
         *   {
         *       status =
         *           check_mc_info(
         *               &(pVars->mc_info),
         *               &(pVars->savedMCInfo),
         *               FALSE);
         *   }
         *   else
         *   {
         *       status =
         *           check_mc_info(
         *               &(pVars->mc_info),
         *               &(pVars->savedMCInfo),
         *               TRUE);
         *   }
         *
         *}*/ /* if (status == SUCCESS) */

        /*
         * This keeps track of how many headers have been read in the file.
         * After the three successful headers with the same configuration
         * are read in, the entire ADTS fixed header is used as the syncword
         * for a more robust 28-bit long syncword
         */

        if (status == SUCCESS)
        {
            (*pInvoke)++;
        }
        else
        {
            (*pInvoke) = 0;
        }

    } /* END if (*(pInvoke) > 3) */

    /* Grab the bits in the ADTS variable header */
    adts_header = getbits(
                      LENGTH_VARIABLE_HEADER,
                      &(pVars->inputStream));
    /*
     * copyright_identification bit is a single bit of the 72-bit
     * copyright_id field.  This consists of a 8-bit copyright identifier
     * and a 64-bit copyright_number.  72 headers must be decoded
     * to reconstruct the entire copyright_id field.
     *
     * copyright_identification_start is a single bit flagging
     * the beginning bit of the copyright_id field.  '1' for start of
     * copyright_id, '0' otherwise.
     *
     *
     * PacketVideo currently does nothing with this information,
     * however, parsing the data from the bitstream could be easily
     * implemented with the following instructions...
     *
     * copyright_id_bit = ((UInt)(adts_header >> 27)) & 0x1;
     *
     * copyright_id_start = ((UInt)(adts_header >> 26)) & 0x1;
     */

    /*
     * frame_length is a 13-bit field which indicates the length,
     * in bytes, of the frame including error_check and headers.
     * This information can theoretically be used to help verify syncwords.
     */
    pVars->prog_config.frame_length  = ((UInt)(adts_header >> 13)) & 0x1FFF;

    /*
     * All the unread bits in adts_header reside in the lower
     * 16-bits at this point.  Perform a typecast for faster
     * execution on 16-bit processors.
     */
    lower_16 = (UInt)adts_header;

    /*
     * Indicates the number of 32-bit words remaining in the
     * encoder buffer after the encoding of the first raw
     * data block.  This value is 0x7ff for variable bit
     * rate encoders, since buffer fullness does not apply
     * to Variable Bit Rate (VBR) encoders.
     */
    pVars->prog_config.buffer_fullness = (lower_16 >> 2) & 0x7FF;

    /*
     * headerless_frames indicates the number of
     * frames with no headers to be processed before the reading
     * in of the next header.
     *
     * In ADTS, up to 4 "no header frames" can exist between
     * syncwords.
     *
     * EXAMPLES:
     *
     * Legend: (Sync words denoted by X, frames
     * deonted by FRAME_#)
     *
     * Example(1): The ADTS sequence below packs 5
     * frames per header.
     * Here, headerless_frames would always be read in as "4"
     *
     * |X||FRAME_0||FRAME_1||FRAME_2||FRAME_3||FRAME_4||X||FRAME_0|
     *
     * Example(2): The ADTS sequence below packs 1 frame per header.
     * Here, headerless_frames would always be read in as "0"
     *
     * |X||FRAME_0||X||FRAME_1||X||FRAME_2|
     *
     */
    pVars->prog_config.headerless_frames = (lower_16 & 0x0003);

    if (pVars->prog_config.CRC_absent == 0)
    {
        pVars->prog_config.CRC_check = (UInt)getbits(
                                           LENGTH_CRC,
                                           &(pVars->inputStream));
    }

    /* pVars->current_program = 0; */ /* shall be set after PCE is read */

    return (status);

} /* END get_adts_header */
